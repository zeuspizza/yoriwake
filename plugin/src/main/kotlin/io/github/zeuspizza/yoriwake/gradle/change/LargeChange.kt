package io.github.zeuspizza.yoriwake.gradle.change

/**
 * A change too large to be worth selecting on: above the build's `largeChangeThreshold` of changed
 * paths, a selecting run runs everything without analysing the change, which would end in a full
 * run anyway and only cost time. Counted over the change the run was asked about (against the
 * requested base, untracked files and the working tree's drift), not the one widened to the map's
 * age, so an aging map cannot turn a small change into a full run on every build.
 */
internal object LargeChange {

    /** Datadog's published bound: commits touching more files than this never skipped a test. */
    const val DEFAULT_THRESHOLD = 5_000

    /** Why [count] changed paths against [base] are not selected on, or null at or below [threshold]. */
    fun refusal(count: Int, threshold: Int, base: String): String? =
        if (count > threshold) {
            "$count paths changed against $base, above the large-change threshold of $threshold"
        } else {
            null
        }
}
