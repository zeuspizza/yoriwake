package io.github.zeuspizza.yoriwake.gradle.wiring

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

            /** The running Maven is older than the oldest this plugin supports, or not a release. */
            MAVEN_VERSION("maven-version"),

            /** The Surefire or Failsafe plugin is older than the oldest this plugin supports. */
            SUREFIRE_VERSION("surefire-version"),

            /** `forkCount=0`: the tests run inside Maven's own JVM, which no agent can join. */
            SUREFIRE_IN_PROCESS("surefire-in-process"),

            /**
             * The Apache Maven build cache is on: a hit restores the execution without starting it,
             * so nothing could capture or select it.
             */
            MAVEN_BUILD_CACHE("maven-build-cache"),

            /** Develocity distributes the execution's tests to other machines, where no agent of ours runs. */
            DEVELOCITY_TEST_DISTRIBUTION("develocity-test-distribution"),

            /** Develocity's Predictive Test Selection chooses the execution's tests itself. */
            DEVELOCITY_TEST_SELECTION("develocity-test-selection"),

            /** Develocity is loaded and whether it acts on the execution could not be read. */
            DEVELOCITY_UNDETERMINED("develocity-undetermined"),

            /** A Surefire provider other than the JUnit Platform's, where no filter can select. */
            SUREFIRE_LEGACY_PROVIDER("surefire-legacy-provider"),
        }
    }
}
