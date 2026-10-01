package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.DERIVE_SCOPE_COUNTER
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.SCOPE_KEY
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.SOURCE_FILES_OPENED_COUNTER
import io.github.zeuspizza.yoriwake.gradle.bytecode.InstrumentationScope
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import io.github.zeuspizza.yoriwake.gradle.facts.projectFacts
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

/**
 * Scopes a `Test` task's JaCoCo to the build's own packages and makes Robolectric's location-less
 * classes recordable, without taking the host's own scoping away.
 */
internal object JacocoScoping {

    /**
     * Every package declared anywhere in the build: the filter on the loaded-class union at decode,
     * which the host's JaCoCo includes cannot override.
     */
    internal fun allProjectPackages(project: Project): List<String> =
        deriveScope(project, BuildMemo.of(project)).orEmpty()
            .map { it.removeSuffix("*").removeSuffix(".") }
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()

    /** The packages this build's sources declare, derived once per build and keyed by root. */
    internal fun deriveScope(project: Project, buildMemo: BuildMemo?): List<String>? {
        // Resolved before the memo below, never inside it: both are `computeIfAbsent` on the same
        // map, and a reentrant mapping function throws `Recursive update`.
        val sourceDirs = projectFacts(project, buildMemo).sourceDirs
        val derive = {
            InstrumentationScope.toIncludePatterns(
                InstrumentationScope.derive(sourceDirs) { opened ->
                    buildMemo?.count(SOURCE_FILES_OPENED_COUNTER, opened.toLong())
                }
            )
        }
        val compute = { buildMemo?.time(DERIVE_SCOPE_COUNTER, derive) ?: derive() }
        // Not `?: compute()`: the derivation may legitimately answer null, and an elvis would
        // re-walk the source tree per `Test` task in that case.
        return if (buildMemo == null) compute() else buildMemo.value(SCOPE_KEY + project.rootDir.path, compute)
    }

    /**
     * Scopes JaCoCo to the build's own packages, without taking the host's own scoping away.
     * Each outcome is distinct so the diagnostic never reports success for nothing attempted.
     */
    internal fun applyJacocoIncludes(project: Project, test: Test, patterns: List<String>?): ScopeOutcome {
        if (!project.plugins.hasPlugin("jacoco")) {
            return ScopeOutcome.NotApplied(
                "the jacoco plugin is not applied, so no coverage is recorded at all",
                ScopeOutcome.NotApplied.Kind.NO_JACOCO_PLUGIN,
            )
        }
        val jacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
            ?: return ScopeOutcome.NotApplied(
                "${test.path} has no jacoco extension",
                ScopeOutcome.NotApplied.Kind.NO_JACOCO_EXTENSION,
            )

        // A disabled extension adds no JaCoCo agent, so it is asked here.
        if (!jacoco.isEnabled) {
            return ScopeOutcome.NotApplied(
                "${test.path} has a jacoco extension and it is DISABLED, so no coverage agent " +
                    "reaches the test JVM and nothing can be recorded",
                ScopeOutcome.NotApplied.Kind.JACOCO_DISABLED,
            )
        }

        // Before either early return below, or builds with their own includes keep empty
        // Robolectric records and force forever.
        applyNoLocationInstrumentation(test, jacoco)

        val existing: List<String>? = jacoco.includes

        // The host scoped its own coverage; overwriting it would change what their tooling
        // measures. Adopt it: it states what this build considers its own code.
        if (!existing.isNullOrEmpty()) {
            return ScopeOutcome.AdoptedFromHost(existing)
        }
        if (patterns == null) {
            return ScopeOutcome.NotApplied(
                "no packages were found in this build's sources, so no scope could be derived",
                ScopeOutcome.NotApplied.Kind.NO_PACKAGES_DERIVED,
            )
        }

        runCatching { jacoco.includes = patterns }.onFailure {
            return ScopeOutcome.NotApplied(
                "setting jacoco includes failed: ${it.message}",
                ScopeOutcome.NotApplied.Kind.INCLUDES_REJECTED,
            )
        }

        return ScopeOutcome.Applied(patterns)
    }

    /**
     * Instruments classes with no code-source location, which Robolectric's sandbox classes are.
     * Set unconditionally: `isIncludeNoLocationClasses()` cannot tell a host's `false` from the
     * default. The host's `jacocoTestReport` then includes generated and proxy classes.
     */
    internal fun applyIncludeNoLocationClasses(test: Test, jacoco: JacocoTaskExtension) {
        runCatching { jacoco.isIncludeNoLocationClasses = true }.onFailure {
            // Not fatal; non-Robolectric builds are unaffected, but the symptom points at nothing.
            test.logger.warn(
                "[yoriwake] ${test.path}: could not set jacoco includeNoLocationClasses " +
                    "(${it.message}). Coverage is still scoped, but classes with no code-source " +
                    "location -- everything Robolectric defines inside its sandbox -- will not be " +
                    "instrumented, so those tests stay unattributable and run on every build."
            )
        }
    }

    /**
     * Instruments classes with no location, but only if the JDK's generated ones can be kept out:
     * without that exclusion a deserializing suite produces no test results at all. The fallback,
     * a full Robolectric run every time, is costly but correct.
     */
    internal fun applyNoLocationInstrumentation(test: Test, jacoco: JacocoTaskExtension) {
        if (excludeReflectionAccessorClassLoaders(test, jacoco)) {
            applyIncludeNoLocationClasses(test, jacoco)
        }
    }

    /**
     * The loaders the JDK defines its runtime-generated reflection accessors in. Both spellings,
     * because JaCoCo's default `exclclassloader` names only the pre-JDK 9 one.
     */
    private val REFLECTION_ACCESSOR_CLASSLOADERS = listOf(
        "sun.reflect.DelegatingClassLoader",
        "jdk.internal.reflect.DelegatingClassLoader",
    )

    /**
     * Keeps `includeNoLocationClasses` away from the JDK's generated serialization accessors,
     * defined in a location-less `DelegatingClassLoader` that cannot see JaCoCo. Instrumenting
     * them breaks every deserialization, Gradle's worker startup included. Scoped by loader, not
     * package, since a scope of `*` covers `jdk.internal.*`. The host's own entries are kept.
     */
    internal fun excludeReflectionAccessorClassLoaders(test: Test, jacoco: JacocoTaskExtension): Boolean =
        runCatching {
            val existing: List<String>? = jacoco.excludeClassLoaders
            jacoco.excludeClassLoaders = (existing.orEmpty() + REFLECTION_ACCESSOR_CLASSLOADERS).distinct()
        }.onFailure {
            // Not fatal: without the containment the caller does not set the setting either, so
            // the build keeps full runs rather than producing nothing.
            test.logger.warn(
                "[yoriwake] ${test.path}: could not exclude the JDK's reflection classloaders " +
                    "(${it.message}), so includeNoLocationClasses was left alone as well. " +
                    "Coverage is still scoped and captured, but classes with no code-source " +
                    "location -- everything Robolectric defines inside its sandbox -- stay " +
                    "uninstrumented, so those tests are unattributable and run on every build."
            )
        }.isSuccess
}

/** What happened when the plugin tried to scope coverage, in a form the diagnostic can report. */
internal sealed class ScopeOutcome(val detail: String) {
    class Applied(val patterns: List<String>) : ScopeOutcome("derived ${patterns.joinToString()}") {
        override fun toString() = "derived(${patterns.joinToString()})"
    }

    class AdoptedFromHost(val patterns: List<String>) :
        ScopeOutcome("adopted the build's own includes ${patterns.joinToString()}") {
        override fun toString() = "host(${patterns.joinToString()})"
    }

    /**
     * Nothing was scoped, so nothing is captured or selected for this task. [kind] is the token;
     * [detail] is the sentence.
     */
    class NotApplied(detail: String, val kind: Kind) : ScopeOutcome(detail) {
        override fun toString() = "NOT APPLIED: $detail"

        /** Why the plugin declined, as one of a closed set. */
        enum class Kind(val token: String) {
            /** The build applies no jacoco plugin, so no coverage is recorded at all. */
            NO_JACOCO_PLUGIN("no-jacoco-plugin"),

            /** The task has no jacoco extension to scope. */
            NO_JACOCO_EXTENSION("no-jacoco-extension"),

            /** No packages derive from this build's sources: Groovy, Scala, generated-only. */
            NO_PACKAGES_DERIVED("no-packages-derived"),

            /** Setting the includes threw. The build is left exactly as it was. */
            INCLUDES_REJECTED("includes-rejected"),

            /** The build applies no plugin this tool knows how to attach to. */
            NO_HOST_PLUGIN("no-host-plugin"),

            /** Isolated Projects is enabled, which forbids every cross-project read this needs. */
            ISOLATED_PROJECTS("isolated-projects"),

            /** The running Gradle is older than the oldest this plugin supports. */
            GRADLE_TOO_OLD("gradle-too-old"),

            /**
             * The task's jacoco extension exists and is switched off, which otherwise looks like a
             * working setup at configuration time.
             */
            JACOCO_DISABLED("jacoco-disabled"),

            /** A Kotlin Multiplatform module that cannot be scoped. */
            KOTLIN_MULTIPLATFORM("kotlin-multiplatform"),
        }
    }
}
