package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import org.gradle.api.provider.Provider

/** A Develocity feature that decides where a task's tests run, or which of them run. */
internal enum class DevelocityFeature(
    val kind: RefusalKind,
    /** Its getter on the `develocity` task extension (Develocity 3.17+). */
    val modern: String,
    /** Its own task extension on the Gradle Enterprise plugin, which 3.17-3.19 also register. */
    val legacy: String,
    val label: String,
    /** What turns it off for the task, as a build script would write it. */
    val switchOff: String,
) {
    TEST_DISTRIBUTION(
        RefusalKind.DEVELOCITY_TEST_DISTRIBUTION, "getTestDistribution", "distribution",
        "Test Distribution", "develocity { testDistribution { enabled = false } }",
    ),
    TEST_SELECTION(
        RefusalKind.DEVELOCITY_TEST_SELECTION, "getPredictiveTestSelection", "predictiveSelection",
        "Predictive Test Selection",
        "develocity { predictiveTestSelection { enabled = false } }, and no -Dpts.enabled=true",
    ),
}

/**
 * Whether Develocity runs or chooses a test task's tests, read off the task's own extensions by
 * method name: the plugin is closed source, its classes are generated, and nothing in this build
 * may depend on it. A shape that is there but cannot be read counts as on: yoriwake then declines,
 * which costs narrowing, never a test.
 */
internal object DevelocityDetection {

    sealed interface Detected {
        /** The refusal this detection makes, as its kind and reason; null when nothing is on. */
        val refusal: Pair<RefusalKind, String>?

        fun encode(): String = refusal?.let { (kind, reason) -> "${kind.token}\t$reason" }.orEmpty()
    }

    object Off : Detected {
        override val refusal: Pair<RefusalKind, String>? get() = null
    }

    data class On(val features: Set<DevelocityFeature>) : Detected {
        override val refusal: Pair<RefusalKind, String>
            get() {
                val first = DevelocityFeature.entries.first { it in features }
                val named = features.sortedBy { it.ordinal }.joinToString(" and ") { "Develocity ${it.label}" }
                val off = features.sortedBy { it.ordinal }.joinToString("; ") { it.switchOff }
                return first.kind to "$named is enabled on this task, so yoriwake declines: it neither " +
                    "selects nor records here (turn it off for this task to use yoriwake: $off)"
            }
    }

    data class Undetermined(val feature: DevelocityFeature, val cause: String) : Detected {
        override val refusal: Pair<RefusalKind, String>
            get() = RefusalKind.DEVELOCITY_UNDETERMINED to "this task carries a Develocity extension " +
                "whose ${feature.label} switch could not be read ($cause), so yoriwake declines: it " +
                "neither selects nor records here"
    }

    /** [extension] looks a task extension up by name, as `test.extensions.findByName` does. */
    fun detect(extension: (String) -> Any?): Detected {
        val on = mutableSetOf<DevelocityFeature>()
        for (feature in DevelocityFeature.entries) {
            val shapes = listOfNotNull(
                extension("develocity")?.let { develocity ->
                    runCatching { call(develocity, feature.modern) }
                        .getOrElse { return Undetermined(feature, describe(it)) }
                },
                extension(feature.legacy),
            )
            for (shape in shapes) {
                val enabled = runCatching {
                    // -Dpts.enabled=true reaches only this twin, where the runtime class has it.
                    readFlag(shape, "getEnabled", required = true) == true ||
                        readFlag(shape, "getEnabledFromSystemProperty", required = false) == true
                }.getOrElse { return Undetermined(feature, describe(it)) }
                if (enabled) on += feature
            }
        }
        return if (on.isEmpty()) Off else On(on)
    }

    /** What [Detected.encode] wrote, as its refusal; null for nothing on or text it did not write. */
    fun decode(text: String?): Pair<RefusalKind, String>? {
        val (token, reason) = text?.split('\t', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return RefusalKind.fromToken(token)?.let { it to reason }
    }

    private fun call(target: Any, name: String): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: error("${target.javaClass.name} has no $name()")
        return try {
            method.invoke(target)
        } catch (thrown: java.lang.reflect.InvocationTargetException) {
            throw thrown.targetException
        }
    }

    /** The boolean a getter holds, through a provider or not; null when unset or, if optional, absent. */
    private fun readFlag(target: Any, name: String, required: Boolean): Boolean? {
        if (!required && target.javaClass.methods.none { it.name == name && it.parameterCount == 0 }) {
            return null
        }
        return when (val value = call(target, name)) {
            null -> null
            is Provider<*> -> when (val held = value.orNull) {
                null -> null
                is Boolean -> held
                else -> error("$name() holds a ${held.javaClass.name}, not a boolean")
            }
            is Boolean -> value
            else -> error("$name() returns a ${value.javaClass.name}, not a boolean")
        }
    }

    private fun describe(thrown: Throwable) = thrown.message ?: thrown.javaClass.name
}
