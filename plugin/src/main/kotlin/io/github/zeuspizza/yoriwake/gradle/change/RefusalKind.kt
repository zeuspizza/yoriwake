package io.github.zeuspizza.yoriwake.gradle.change

/**
 * Why the daemon refused to let a run narrow, as one of a closed set: the token the test JVM gets
 * in `yoriwake.refused.kind`, `decisions.tsv` records as `refusal-kind` and `explain.json` reports
 * as `refusalKind`. Each value has a different remedy, so a reader can tell whether recapturing
 * would help.
 */
internal enum class RefusalKind(val token: String) {
    /** The map's capture stamp cannot be related to the base, so its age is unknown. */
    STAMP_UNRELATABLE("stamp-unrelatable"),

    /** A map exists and carries no usable capture stamp, so its age is unknown. */
    STAMP_ABSENT("stamp-absent"),

    /** A map exists without a readable working-tree snapshot. See [WorkingTree]. */
    SNAPSHOT_ABSENT("snapshot-absent"),

    /** git could not report a change set at all -- distinct from reporting an empty one. */
    NO_CHANGE_SET("no-change-set"),

    /** A refusal that named no kind. Recorded rather than dropped, so the gate still sees it. */
    UNNAMED("unnamed"),

    /** In-JVM parallelism: the one refusal that also suppresses capture. */
    IN_JVM_PARALLELISM("in-jvm-parallelism"),

    /** The map holds no digest for a changed constant holder. A capture fixes this. */
    CONSTANT_UNRECORDED("constant-unrecorded"),

    /** A constant's value differs from the captured one. Recapturing will not change that. */
    CONSTANT_CHANGED("constant-changed"),

    /** The map holds no annotation digest for a changed class that has annotations. A capture fixes this. */
    ANNOTATIONS_UNRECORDED("annotations-unrecorded"),

    /** A changed class's annotations differ from the captured ones. Recapturing will not change that. */
    ANNOTATIONS_CHANGED("annotations-changed"),

    /**
     * The map holds no digest of the compiled classes, so bytes that changed with no source
     * change cannot be ruled out. A capture fixes this.
     */
    BYTES_UNRECORDED("bytes-unrecorded"),

    /**
     * The map holds no digests of the build-produced files on this task's classpath, or they could
     * not be taken now. A capture fixes the first.
     */
    CLASSPATH_FILES_UNRECORDED("classpath-files-unrecorded"),

    /**
     * A build-produced file on this task's classpath differs from the captured one, with no tracked
     * change behind it. Recapturing will not change that.
     */
    CLASSPATH_FILES_CHANGED("classpath-files-changed"),

    /** The inline scan or the bytes walk ran out of budget, or could not read something it walked. */
    SCAN_EXHAUSTED("scan-exhausted"),

    /** The scan threw. A defect, unlike [SCAN_EXHAUSTED], which is a size problem. */
    SCAN_THREW("scan-threw"),

    /** The scan declined to start: there was nothing to walk, or no artifacts to walk it with. */
    SCAN_REFUSED("scan-refused"),

    /** The walk finished and no SMAP exists anywhere, with Kotlin in the output. */
    SMAP_ABSENT("smap-absent"),

    /** The change set could not be handed to the test JVM through its file. */
    CHANGE_SET_UNREADABLE(io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGE_SET_UNREADABLE_KIND),

    /**
     * The working tree changed between configuration and the task's execution, or could not be
     * listed again then: the change set no longer describes the tree the tests see.
     */
    CHANGE_SET_STALE("change-set-stale"),

    /**
     * A run given a trusted-map list found the map not named there: no trusted run vouched for it.
     * The next recording on the default branch, listed by the caller, fixes this.
     */
    MAP_UNVERIFIED("map-unverified"),

    /**
     * The trusted-map list names the map with another digest: its content is not what a trusted
     * run recorded. The next recording on the default branch replaces it.
     */
    MAP_UNTRUSTED("map-untrusted"),

    /**
     * Develocity Test Distribution runs this task's tests, possibly on other machines: yoriwake
     * declines, so it neither selects nor records. Turning it off for the task lifts this.
     */
    DEVELOCITY_TEST_DISTRIBUTION("develocity-test-distribution"),

    /** Develocity Predictive Test Selection chooses this task's tests: yoriwake declines as above. */
    DEVELOCITY_TEST_SELECTION("develocity-test-selection"),

    /** The task carries a Develocity test extension whose switches could not be read: declines. */
    DEVELOCITY_UNDETERMINED("develocity-undetermined"),
    ;

    companion object {
        /** The kind a token names, or null for one this build does not know. */
        fun fromToken(token: String): RefusalKind? = entries.firstOrNull { it.token == token }
    }
}
