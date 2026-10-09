package io.github.zeuspizza.yoriwake.gradle

/**
 * What kind of run one test task makes: the single answer every wiring site reads, so no two of
 * them can disagree about whether a run selects.
 */
internal class RunPlan(val kind: Kind) {

    enum class Kind {
        /** Runs every test, captures and dates the map. The default. */
        RECORD,

        /** Runs what selection keeps; captures only when it falls back to running everything. */
        SELECT,
    }

    val selecting: Boolean get() = kind == Kind.SELECT

    companion object {
        fun resolve(settings: Settings): RunPlan =
            RunPlan(if (settings.select) Kind.SELECT else Kind.RECORD)
    }
}
