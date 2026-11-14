package io.github.zeuspizza.yoriwake.gradle

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The Gradle version this functional-test task drives its builds with, and the TestKit home that
 * version owns in this test JVM. The build runs the whole suite once per supported version, as
 * separate tasks, each in one test JVM or several.
 */
object FunctionalGradle {

    val version: String = requireNotNull(System.getProperty("yoriwake.functional.gradleVersion")) {
        "the build did not say which Gradle version to test against; run this through Gradle"
    }

    /**
     * Whether [version] is [major].[minor] or later, a pre-release counting as its release, so a
     * gate on what a Gradle added stays open on every later major.
     */
    fun atLeast(major: Int, minor: Int): Boolean {
        val (actualMajor, actualMinor) = version.split('.', '-').take(2).map { it.toIntOrNull() ?: 0 }
        return actualMajor > major || actualMajor == major && actualMinor >= minor
    }

    /** This task's own temporary directory, the same for every test JVM the task forks. */
    private val workDir = File(
        System.getProperty("org.gradle.internal.worker.tmpdir") ?: System.getProperty("java.io.tmpdir"),
    )

    private val claimed: TestKitSlot by lazy { TestKitSlot.claim(workDir) }

    /**
     * What sets this test JVM's homes apart from those of the other test JVMs its task runs beside
     * it. Nothing for the first, so a task that runs one test JVM keeps the homes it always had.
     */
    private val slot: String get() = claimed.number.let { if (it == 1) "" else "-slot$it" }

    /**
     * Gradle's number for this test JVM, which no other test JVM of the build has: what its coverage
     * and diagnostics are filed under. [slot] would not do for them, because a test JVM that
     * finishes early frees its number for another of the same task.
     */
    val testJvm: String get() = System.getProperty("org.gradle.test.worker") ?: "1"

    /**
     * One TestKit home per version and test JVM, under this task's own temporary directory. Shared
     * between versions, an idle daemon of the other version holds the journal cache lock, and a
     * reusing build must get it released to load its entry: under load that handoff once timed out
     * after 60s and failed the reuse guard. Shared between test JVMs, each would also overwrite the
     * coverage port the other's daemons report to.
     *
     * Every runner takes its home from here, so this is also where the daemons learn whether to
     * report coverage.
     */
    fun testKitDir(gradleVersion: String = version): File =
        File(workDir, ".gradle-test-kit-$gradleVersion$slot").also(FunctionalCoverage::configure)

    /**
     * The Gradle home for Tooling API clients, beside [testKitDir] and never the same: a Tooling API
     * daemon stopping in a TestKit home is the one event a Kotlin DSL compile failure in that home
     * was seen to follow, so the two kinds of daemon do not share one. Configured like it, so its
     * daemons report coverage too.
     */
    fun toolingApiDir(gradleVersion: String = version): File =
        File(workDir, ".gradle-tooling-api-$gradleVersion$slot").also(FunctionalCoverage::configure)
}

/**
 * A number one test JVM holds for as long as it runs: the lowest whose lock file in [claim]'s
 * directory no live JVM has locked. The numbers stay small and the same from run to run, so the
 * homes named after them keep what they downloaded; a JVM that dies frees its number with its lock.
 */
internal class TestKitSlot private constructor(val number: Int, private val file: File, private val channel: FileChannel) {

    /** Frees the number for the next claim. */
    fun release() {
        synchronized(held) {
            if (!channel.isOpen) return
            channel.close()
            held -= file
        }
    }

    companion object {
        // Closing any channel on a file drops this JVM's lock on it on POSIX, so a number this JVM
        // holds is never probed again.
        private val held = mutableSetOf<File>()

        fun claim(directory: File): TestKitSlot = synchronized(held) {
            directory.mkdirs()
            generateSequence(1) { it + 1 }.firstNotNullOf { number ->
                val file = File(directory, ".gradle-test-kit-slot-$number.lock").absoluteFile
                if (file in held) return@firstNotNullOf null
                val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                if (channel.tryLock() == null) {
                    channel.close()
                    null
                } else {
                    held += file
                    TestKitSlot(number, file, channel)
                }
            }
        }
    }
}
