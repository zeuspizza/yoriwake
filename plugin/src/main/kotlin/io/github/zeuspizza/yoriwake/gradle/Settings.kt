package io.github.zeuspizza.yoriwake.gradle

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project

/**
 * The plugin's `-P` flags, and the only place they are read.
 *
 * A boolean flag that is absent takes its default; empty (a bare `-Pflag`) or `true` turns it on,
 * `false` turns it off, and anything else fails the build naming the flag -- a mistyped value that
 * read as either answer would change what runs without saying so.
 *
 * `yoriwake.internal.*` flags drive diagnostics and experiments and are unsupported.
 *
 * @param lookup a Gradle property's raw value, or null when it is not set.
 */
internal class Settings(lookup: (String) -> String?) {

    val select: Boolean = parseFlag(SELECT, lookup(SELECT))
    val base: String? = lookup(BASE)
    val disabled: Boolean = parseFlag(DISABLED, lookup(DISABLED))
    val isolatedCapture: Boolean = parseFlag(ISOLATED_CAPTURE, lookup(ISOLATED_CAPTURE))
    val alwaysRun: List<String> =
        lookup(ALWAYS_RUN)?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    val measureToll: Boolean = parseFlag(MEASURE_TOLL, lookup(MEASURE_TOLL))
    val instrumentedSeconds: String? = lookup(INSTRUMENTED_SECONDS)
    val uninstrumentedSeconds: String? = lookup(UNINSTRUMENTED_SECONDS)

    val loaded: Boolean = parseFlag(LOADED, lookup(LOADED))
    val classSelection: Boolean = parseFlag(CLASS_SELECTION, lookup(CLASS_SELECTION))
    val classGranularity: Boolean = parseFlag(CLASS_GRANULARITY, lookup(CLASS_GRANULARITY))
    val counters: String? = lookup(COUNTERS)
    /** Raw, because "present but unparseable" and "never supplied" get different answers. */
    val forcedShare: String? = lookup(FORCED_SHARE)

    companion object {
        const val SELECT = "yoriwake.select"
        const val BASE = "yoriwake.base"
        const val DISABLED = "yoriwake.disabled"
        const val ISOLATED_CAPTURE = "yoriwake.isolatedCapture"
        const val ALWAYS_RUN = "yoriwake.alwaysRun"
        const val MEASURE_TOLL = "yoriwake.audit.measureToll"
        const val INSTRUMENTED_SECONDS = "yoriwake.audit.instrumentedSeconds"
        const val UNINSTRUMENTED_SECONDS = "yoriwake.audit.uninstrumentedSeconds"

        const val LOADED = "yoriwake.internal.loaded"
        const val CLASS_SELECTION = "yoriwake.internal.classSelection"
        const val CLASS_GRANULARITY = "yoriwake.internal.select.classGranularity"
        const val COUNTERS = "yoriwake.internal.counters"
        const val FORCED_SHARE = "yoriwake.internal.payback.forcedShare"

        /** Via `providers.gradleProperty`, which is allowed under Isolated Projects. */
        fun of(project: Project): Settings =
            Settings { project.providers.gradleProperty(it).orNull }

        fun parseFlag(name: String, value: String?): Boolean = when {
            value == null -> false
            value.isEmpty() || value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> throw InvalidUserDataException(
                "[yoriwake] -P$name=$value is not a boolean. Pass -P$name, -P$name=true or " +
                    "-P$name=false."
            )
        }
    }
}
