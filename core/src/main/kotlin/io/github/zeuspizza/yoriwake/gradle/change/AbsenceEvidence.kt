package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.bytecode.EffectiveScope
import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import org.jacoco.core.runtime.WildcardMatcher

/**
 * Decides, per changed class, whether its absence from the coverage map is evidence of anything.
 *
 * Every condition must be established; anything that cannot be established forces a run.
 *
 * These are the conditions that need compiled classes and the resolved classpath. The test JVM adds
 * one more: the class must also be absent from a trustworthy loaded-class union, because a test can
 * load a class and assert on its fields or annotations without executing a line of it.
 *
 * A provable verdict means only: had a test executed this class, a probe would have fired, and
 * nothing else could have depended on it invisibly.
 */
internal object AbsenceEvidence {

    /** What was established for one changed class prefix. */
    data class Verdict(val provable: Boolean, val reason: String?)

    /** Whether every class a prefix compiles to belongs to this task's own test output. */
    fun interface TestOutput {
        fun containsAllOf(prefix: String): Boolean
    }

    /**
     * The compiled classes a changed source file produced.
     *
     * Null (could not be enumerated) and an empty list are both refusals.
     */
    fun interface CompiledClasses {
        fun of(prefix: String): List<Pair<String, ByteArray>>?
    }

    /**
     * Which of these names appear as text in a resource the task can read. Asked once for all
     * names, because each call walks the classpath.
     */
    fun interface NamedInResources {
        fun matching(classNames: Collection<String>): Set<String>
    }

    /**
     * Assesses every changed prefix.
     *
     * @param scopes every scope the map's records were captured under. Empty makes every verdict a
     *   refusal.
     */
    fun assess(
        prefixes: Collection<String>,
        scopes: List<EffectiveScope>,
        compiled: CompiledClasses,
        namedInResources: NamedInResources,
    ): Map<String, Verdict> {
        if (scopes.isEmpty()) {
            return prefixes.associateWith {
                Verdict(false, "the scope JaCoCo was instrumenting under could not be established")
            }
        }
        // JaCoCo's own defaults: everything is included and nothing is excluded unless said.
        val matchers = scopes.map { scope ->
            matcher(scope.includes, default = "*")!! to matcher(scope.excludes, default = null)
        }
        val classesByPrefix = prefixes.associateWith { compiled.of(it) }
        val everyClass = classesByPrefix.values.filterNotNull().flatten().map { it.first }
        val named = namedInResources.matching(everyClass)
        return prefixes.associateWith { prefix ->
            verdictFor(prefix, matchers, classesByPrefix[prefix], named)
        }
    }

    private fun verdictFor(
        prefix: String,
        matchers: List<Pair<WildcardMatcher, WildcardMatcher?>>,
        classes: List<Pair<String, ByteArray>>?,
        namedInResources: Set<String>,
    ): Verdict {
        if (classes.isNullOrEmpty()) {
            return Verdict(false, "no compiled class could be found for $prefix")
        }
        classes.forEach { (name, bytes) ->
            // Every class the file declares (Kotlin puts top-level code in `FooKt`, constants in
            // `Foo$Companion`), against every scope the records were captured under.
            if (matchers.any { (includes, _) -> !includes.matches(name) }) {
                return Verdict(false, "$name was outside the instrumentation scope, so no probe existed")
            }
            if (matchers.any { (_, excludes) -> excludes != null && excludes.matches(name) }) {
                return Verdict(false, "$name was excluded from instrumentation, so no probe existed")
            }
            val recordable = Recordability.of(bytes)
            if (!recordable.recordable) {
                return Verdict(false, "$name cannot be recorded: ${recordable.reason}")
            }
            if (name in namedInResources) {
                // A class named as a string in a resource (e.g. a plugin registry in YAML) can be
                // loaded with no bytecode edge and no coverage record.
                return Verdict(false, "$name is named as a string in a resource this task reads")
            }
        }
        return Verdict(true, null)
    }

    /**
     * [verdicts] with every provable prefix refused that compiles to a class carrying a
     * class-level annotation. A framework can act on the annotation alone, a Dagger module whose
     * `@Binds` are abstract or a bean a scan registers, so no test need load the class for one to
     * depend on it.
     *
     * A class in this task's test output that declares a test method is exempt: discovery runs it,
     * so it never narrows on absence. A helper beside it, or nested in it, is not.
     */
    fun unannotated(
        verdicts: Map<String, Verdict>,
        compiled: CompiledClasses,
        testOutput: TestOutput,
    ): Map<String, Verdict> = verdicts.mapValues { (prefix, verdict) ->
        if (!verdict.provable) return@mapValues verdict
        val classes = compiled.of(prefix)
        if (classes.isNullOrEmpty()) {
            return@mapValues Verdict(false, "no compiled class could be found for $prefix")
        }
        val discovered = testOutput.containsAllOf(prefix)
        classes.forEach { (name, bytes) ->
            val annotations = Recordability.classAnnotations(bytes)
                ?: return@mapValues Verdict(false, "the annotations of $name could not be read")
            if (annotations.isEmpty() || (discovered && Recordability.declaresTestMethod(bytes))) {
                return@forEach
            }
            val first = annotations.first().removePrefix("L").removeSuffix(";").replace('/', '.')
            return@mapValues Verdict(
                false,
                "$name carries the class-level annotation @$first, which a framework can act on " +
                    "without any test loading the class",
            )
        }
        verdict
    }

    /**
     * JaCoCo's own pattern matcher, so "was this instrumented" is answered exactly as JaCoCo does.
     */
    private fun matcher(patterns: List<String>, default: String?): WildcardMatcher? = when {
        patterns.isNotEmpty() -> WildcardMatcher(patterns.joinToString(":"))
        default != null -> WildcardMatcher(default)
        else -> null
    }

}
