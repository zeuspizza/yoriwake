package io.github.zeuspizza.yoriwake.gradle

import java.io.File

/**
 * The Gradle version this functional-test task drives its builds with, and the TestKit home that
 * version owns. The build runs the whole suite once per supported version, as separate tasks.
 */
object FunctionalGradle {

    val version: String = requireNotNull(System.getProperty("yoriwake.functional.gradleVersion")) {
        "the build did not say which Gradle version to test against; run this through Gradle"
    }

    /**
     * One TestKit home per version, under this task's own temporary directory. Shared, an idle
     * daemon of the other version holds the journal cache lock, and a reusing build must get it
     * released to load its entry: under load that handoff once timed out after 60s and failed the
     * reuse guard.
     *
     * Every runner takes its home from here, so this is also where the daemons learn whether to
     * report coverage.
     */
    fun testKitDir(gradleVersion: String = version): File = File(
        System.getProperty("org.gradle.internal.worker.tmpdir") ?: System.getProperty("java.io.tmpdir"),
        ".gradle-test-kit-$gradleVersion",
    ).also(FunctionalCoverage::configure)

    /**
     * The Gradle home for Tooling API clients, beside [testKitDir] and never the same: a Tooling API
     * daemon stopping in a TestKit home is the one event a Kotlin DSL compile failure in that home
     * was seen to follow, so the two kinds of daemon do not share one. Configured like it, so its
     * daemons report coverage too.
     */
    fun toolingApiDir(gradleVersion: String = version): File = File(
        System.getProperty("org.gradle.internal.worker.tmpdir") ?: System.getProperty("java.io.tmpdir"),
        ".gradle-tooling-api-$gradleVersion",
    ).also(FunctionalCoverage::configure)
}
