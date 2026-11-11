package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.select.Strictness
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
 * @param commandLine a `-P` property's raw value as this invocation's command line passed it, or
 *   null; a flag only the command line may set is refused from anywhere else.
 */
internal class Settings(lookup: (String) -> String?, commandLine: (String) -> String?) {

    /** Every flag as [lookup] answers it, the command line included. */
    constructor(lookup: (String) -> String?) : this(lookup, lookup)

    val select: Boolean = parseFlag(SELECT, lookup(SELECT))
    /** Runs every test and records what a selecting run would have left out. */
    val observe: Boolean = parseFlag(OBSERVE, lookup(OBSERVE))
    /**
     * Runs every test but those a selecting run's record lists as ran: null when off, empty for the
     * record in this build's own map directories, else a directory holding a saved copy of a build's
     * `.gradle/yoriwake`. Bare or `true` is the build's own, `false` is off.
     */
    val complement: String? = lookup(COMPLEMENT)?.let {
        when {
            it.isEmpty() || it.equals("true", ignoreCase = true) -> ""
            it.equals("false", ignoreCase = true) -> null
            else -> it
        }
    }
    /** Asks a selecting run to run every test instead, as a recording run. */
    val fullRun: Boolean = parseFlag(FULL_RUN, lookup(FULL_RUN))
    val base: String? = lookup(BASE)
    private val modeValue: String? = lookup(MODE)
    private val modePassed: String? = commandLine(MODE)

    /**
     * `safe` (the default) or `optimistic`, which drops the shared-JVM windows the change opens. Read
     * only from the command line: set in a file a release gate also reads, it would loosen that run.
     * Parsed when asked, so a disabled build never fails over it.
     */
    val mode: Strictness get() = parseMode(modeValue, modePassed)
    val disabled: Boolean = parseFlag(DISABLED, lookup(DISABLED))
    val isolatedCapture: Boolean = parseFlag(ISOLATED_CAPTURE, lookup(ISOLATED_CAPTURE))
    /** Null when not passed, so the build's `recordSequentially` answers. */
    val recordSequentially: Boolean? = lookup(RECORD_SEQUENTIALLY)?.let { parseFlag(RECORD_SEQUENTIALLY, it) }
    /** A file of trusted map digests; while set, a map narrows only when it names the map's. */
    val trustedMaps: String? = lookup(TRUSTED_MAPS)
    val alwaysRun: List<String> =
        lookup(ALWAYS_RUN)?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    val measureToll: Boolean = parseFlag(MEASURE_TOLL, lookup(MEASURE_TOLL))
    val instrumentedSeconds: String? = lookup(INSTRUMENTED_SECONDS)
    val uninstrumentedSeconds: String? = lookup(UNINSTRUMENTED_SECONDS)

    val loaded: Boolean = parseFlag(LOADED, lookup(LOADED))
    val classGranularity: Boolean = parseFlag(CLASS_GRANULARITY, lookup(CLASS_GRANULARITY))
    /** Null when not passed, so the build's `narrowPlainRunners` answers. */
    val narrowPlainRunners: Boolean? = lookup(NARROW_PLAIN_RUNNERS)?.let { parseFlag(NARROW_PLAIN_RUNNERS, it) }
    /** Null when not passed, so the build's `narrowDependencies` answers. */
    val narrowDependencies: Boolean? = lookup(NARROW_DEPENDENCIES)?.let { parseFlag(NARROW_DEPENDENCIES, it) }
    /** Null when not passed, so the build's `narrowLineOnlyChanges` answers. */
    val narrowLineOnlyChanges: Boolean? =
        lookup(NARROW_LINE_ONLY_CHANGES)?.let { parseFlag(NARROW_LINE_ONLY_CHANGES, it) }
    val counters: String? = lookup(COUNTERS)
    /** Raw, because "present but unparseable" and "never supplied" get different answers. */
    val forcedShare: String? = lookup(FORCED_SHARE)

    companion object {
        const val SELECT = SettingNames.SELECT
        const val OBSERVE = SettingNames.OBSERVE
        const val COMPLEMENT = SettingNames.COMPLEMENT
        const val FULL_RUN = SettingNames.FULL_RUN
        const val BASE = SettingNames.BASE
        const val MODE = SettingNames.MODE
        const val DISABLED = "yoriwake.disabled"
        const val ISOLATED_CAPTURE = SettingNames.ISOLATED_CAPTURE
        const val RECORD_SEQUENTIALLY = SettingNames.RECORD_SEQUENTIALLY
        const val ALWAYS_RUN = "yoriwake.alwaysRun"
        const val NARROW_PLAIN_RUNNERS = "yoriwake.narrowPlainRunners"
        const val NARROW_DEPENDENCIES = SettingNames.NARROW_DEPENDENCIES
        const val NARROW_LINE_ONLY_CHANGES = SettingNames.NARROW_LINE_ONLY_CHANGES
        const val TRUSTED_MAPS = SettingNames.TRUSTED_MAPS
        const val MEASURE_TOLL = SettingNames.MEASURE_TOLL
        const val INSTRUMENTED_SECONDS = SettingNames.INSTRUMENTED_SECONDS
        const val UNINSTRUMENTED_SECONDS = SettingNames.UNINSTRUMENTED_SECONDS

        const val LOADED = "yoriwake.internal.loaded"
        const val CLASS_GRANULARITY = "yoriwake.internal.select.classGranularity"
        const val COUNTERS = "yoriwake.internal.counters"
        const val FORCED_SHARE = "yoriwake.internal.payback.forcedShare"

        /**
         * Via `providers.gradleProperty`, which is allowed under Isolated Projects and keys the
         * configuration cache; the start parameter says only whether the command line passed it.
         */
        fun of(project: Project): Settings {
            val passed = project.gradle.startParameter.projectProperties
            // Read through providers too, so the configuration cache keys on them: the start parameter
            // is no input, and a cached entry made with the flag on the command line would otherwise
            // serve a run that sets the same value only in the environment or a daemon property.
            project.providers.environmentVariable("ORG_GRADLE_PROJECT_$MODE").orNull
            project.providers.systemProperty("org.gradle.project.$MODE").orNull
            return Settings({ project.providers.gradleProperty(it).orNull }) { passed[it] }
        }

        /**
         * [value] as `-P$MODE` asks: absent is safe; `safe` or `optimistic` in any letter case.
         * Anything else, or a value [passed] does not hold because a file or the environment set it,
         * fails the build naming the flag.
         */
        fun parseMode(value: String?, passed: String?): Strictness {
            if (value == null) return Strictness.SAFE
            if (passed != value) {
                throw InvalidUserDataException(
                    "[yoriwake] $MODE=$value is set outside the command line (a gradle.properties file, " +
                        "ORG_GRADLE_PROJECT_$MODE or -Dorg.gradle.project.$MODE). Optimistic mode runs fewer tests than " +
                        "the safe rule, so only the invocation that accepts that may ask for it: pass " +
                        "-P$MODE=optimistic on the command line, and remove it from there."
                )
            }
            return when {
                value.equals(Strictness.SAFE.token(), ignoreCase = true) -> Strictness.SAFE
                value.equals(Strictness.OPTIMISTIC.token(), ignoreCase = true) -> Strictness.OPTIMISTIC
                else -> throw InvalidUserDataException(
                    "[yoriwake] -P$MODE=$value is not a mode. Pass -P$MODE=safe or -P$MODE=optimistic."
                )
            }
        }

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
