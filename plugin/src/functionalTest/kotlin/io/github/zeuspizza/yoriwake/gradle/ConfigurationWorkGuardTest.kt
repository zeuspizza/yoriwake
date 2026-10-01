package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.MapLocation
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards what applying the plugin costs before it runs a single test.
 *
 * It counts work instead of timing it: a count is exact and means the same on every machine. A
 * `PATH` shim counts git subprocesses (including the ones Gradle runs to validate a cached entry),
 * and counters inside the plugin see the work that spawns no subprocess.
 *
 * The shim is safe here because these builds store a configuration-cache entry, and a first
 * invocation stores whatever `PATH` held.
 */
class ConfigurationWorkGuardTest {

    @Test
    fun `the git command set does not grow with the number of projects`(@TempDir tmp: File) {
        GRADLE_VERSIONS.forEach { version ->
            // Only a selecting build asks git at configuration time, so only there is memoising per
            // build rather than per `Test` task observable.
            val small = observe(
                fixture(File(tmp, "small-$version"), subprojects = 3), version, SELECTING,
            )
            val large = observe(
                fixture(File(tmp, "large-$version"), subprojects = 6), version, SELECTING,
            )

            // Pinned, not only compared: a command added to both fixtures would still agree.
            assertEquals(
                EXPECTED_COMMANDS, small.gitCommands,
                "Gradle $version: the set of git commands a build runs changed",
            )
            assertEquals(
                small.gitCommands, large.gitCommands,
                "Gradle $version: doubling the projects changed which git commands the build runs",
            )
            // The set alone misses the same questions asked once per `Test` task instead of once.
            assertEquals(
                small.gitCalls, large.gitCalls,
                "Gradle $version: git subprocesses grew with the project count",
            )
            // Classpath facts are built per task, so they double with the tasks but not beyond.
            assertEquals(
                small.count(YoriwakePlugin.CLASSPATH_FACTS_COUNTER) * 2,
                large.count(YoriwakePlugin.CLASSPATH_FACTS_COUNTER),
                "Gradle $version: classpathFacts per Test task grew with the project count",
            )

            // Build-wide derivations: exactly once per build.
            listOf(YoriwakePlugin.DERIVE_SCOPE_COUNTER, YoriwakePlugin.WALK_COUNTER).forEach { counter ->
                assertEquals(1L, small.count(counter), "Gradle $version: $counter, 3 subprojects")
                assertEquals(1L, large.count(counter), "Gradle $version: $counter, 6 subprojects")
            }

            // Each source file opened once; the larger fixture has twice the files, so opens double.
            assertEquals(
                small.count(YoriwakePlugin.SOURCE_FILES_OPENED_COUNTER) * 2,
                large.count(YoriwakePlugin.SOURCE_FILES_OPENED_COUNTER),
                "Gradle $version: source files were opened more than once each",
            )
            assertEquals(
                sourceFileCount(File(tmp, "large-$version")),
                large.count(YoriwakePlugin.SOURCE_FILES_OPENED_COUNTER),
                "Gradle $version: opens do not equal the build's own main source file count",
            )
        }
    }

    /** Every file the scope derivation is entitled to open, counted from the fixture itself. */
    private fun sourceFileCount(dir: File): Long =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "java" && it.path.contains("/src/main/") }
            .count().toLong()

    @Test
    fun `a map that has been captured asks one extra question per distinct stamp`(@TempDir tmp: File) {
        // The one configuration-time git call that is per map: `merge-base` relates each capture
        // stamp to the base. Maps captured at the same commit must share one call.
        val small = fixture(File(tmp, "stamped-small"), subprojects = 3)
        val large = fixture(File(tmp, "stamped-large"), subprojects = 6)
        val stamp = "0".repeat(40)
        listOf(small to 3, large to 6).forEach { (dir, subprojects) ->
            (1..subprojects).forEach { index ->
                val mapDir = MapLocation.forTask(File(dir, ".gradle"), ":mod$index:test")
                mapDir.mkdirs()
                File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE).writeText(stamp)
            }
        }

        val observedSmall = observe(small, GRADLE_VERSIONS.first(), SELECTING)
        val observedLarge = observe(large, GRADLE_VERSIONS.first(), SELECTING)

        assertTrue(
            "merge-base --end-of-options" in observedLarge.gitCommands,
            "no map stamp was read, so this proves nothing about the question that reads them",
        )
        assertEquals(
            observedSmall.gitCalls, observedLarge.gitCalls,
            "one stamp shared by twice the tasks cost twice the git calls",
        )
    }

    @Test
    fun `a build that never selects asks git nothing at configuration time`(@TempDir tmp: File) {
        val dir = fixture(File(tmp, "never-selects"), subprojects = 3)
        // A selecting build first: its non-zero count proves the shim is counting, so the zero
        // below means the plugin asked nothing.
        val selecting = observe(dir, GRADLE_VERSIONS.first(), SELECTING)
        assertTrue(selecting.gitCalls > 0, "the selecting build asked git nothing; the shim is not counting")

        val ordinary = observe(dir, GRADLE_VERSIONS.first(), CONFIGURE_ONLY, expectGit = false)

        assertEquals(
            emptySet(), ordinary.gitCommands,
            "a build with no -Pyoriwake.select and no yoriwakeExplain still ran git at configuration time",
        )
        assertTrue(
            ordinary.count(YoriwakePlugin.CLASSPATH_FACTS_COUNTER) > 0,
            "the build did not configure at all, so its zero says nothing about git",
        )
    }

    @Test
    fun `a reusing build re-asks git and runs no configuration phase at all`(@TempDir tmp: File) {
        val dir = fixture(File(tmp, "reuse"), subprojects = 3)
        // The second build must reuse. Unpacking the agent at configuration time would invalidate
        // the stored entry ("the file system entry has been created") and fail this.
        val stored = observe(dir, GRADLE_VERSIONS.first(), SELECTING)
        assertTrue(stored.output.contains("Configuration cache entry stored"), stored.output)
        // A missing counters file below means no configuration phase ran only if the storing
        // build did write one.
        assertTrue(
            stored.countersWritten,
            "the storing build wrote no counters, so the reusing build's missing file proves nothing",
        )

        val reused = observe(dir, GRADLE_VERSIONS.first(), SELECTING)

        assertTrue(
            reused.output.contains("Configuration cache entry reused"),
            "the second invocation did not reuse the entry, so it proves nothing about that path: " +
                reused.output.lines().firstOrNull { it.contains("cannot be reused") },
        )
        // Gradle re-runs git to validate a cache hit, so this has something to compare.
        assertEquals(stored.gitCommands, reused.gitCommands, "a reusing build asked git something else")
        // Counters would be zero here regardless; the file's absence is what shows no configuration.
        assertFalse(
            reused.countersWritten,
            "a reusing build wrote configuration counters, so a configuration phase did run",
        )
    }

    @Test
    fun `a disabled build does no configuration work at all`(@TempDir tmp: File) {
        // The escape hatch has to be free, not merely inert: no counters file at all.
        val dir = fixture(File(tmp, "disabled"), subprojects = 3)
        // The "disabled" log line prints in `doFirst`, which a dry run never reaches, so compare
        // against an enabled build one flag apart instead.
        val enabled = observe(dir, GRADLE_VERSIONS.first(), CONFIGURE_ONLY, expectGit = false)
        assertTrue(enabled.countersWritten, "the enabled build configured nothing to compare against")

        val disabled = observe(
            dir, GRADLE_VERSIONS.first(), CONFIGURE_ONLY + "-Pyoriwake.disabled", expectGit = false,
        )

        assertFalse(disabled.countersWritten, "a disabled build still did configuration work")
        // Only a selecting build asks git, so only there can this assertion fail.
        val selectingEnabled = observe(dir, GRADLE_VERSIONS.first(), SELECTING)
        assertTrue(selectingEnabled.gitCalls > 0, "the enabled selecting build asked git nothing")
        val selectingDisabled = observe(
            dir, GRADLE_VERSIONS.first(), SELECTING + "-Pyoriwake.disabled", expectGit = false,
        )
        assertEquals(
            emptySet(), selectingDisabled.gitCommands,
            "a disabled build still ran git on the path that otherwise would",
        )
    }

    @Test
    fun `a run whose shim was not on PATH fails rather than reporting zero`(@TempDir tmp: File) {
        // A daemon keeps the environment it started with, so a missing shim reads as zero calls.
        val dir = fixture(File(tmp, "no-shim"), subprojects = 3)
        val failure = kotlin
            .runCatching { observe(dir, GRADLE_VERSIONS.first(), SELECTING, withShim = false) }
            .exceptionOrNull()

        assertTrue(
            failure?.message?.contains("shim") == true,
            "a run with no shim on PATH was accepted; its zero would have read as a pass",
        )
    }

    private data class Observed(
        val gitCalls: Int,
        val gitCommands: Set<String>,
        val counts: Map<String, Long>,
        val output: String,
        val countersWritten: Boolean,
    ) {
        fun count(key: String): Long = counts[key] ?: 0
    }

    private fun observe(
        dir: File,
        gradleVersion: String,
        arguments: List<String> = CONFIGURE_ONLY,
        expectGit: Boolean = true,
        withShim: Boolean = true,
    ): Observed {
        val gitLog = File(dir, "git-calls.log").also { it.delete() }
        val counters = File(dir, "counters.tsv").also { it.delete() }
        val environment = System.getenv().toMutableMap()
        if (withShim) {
            environment["PATH"] = "${shimDir().absolutePath}${File.pathSeparator}${System.getenv("PATH")}"
            environment["YORIWAKE_GIT_LOG"] = gitLog.absolutePath
            environment["YORIWAKE_REAL_GIT"] = realGit()
        }

        val output = GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withTestKitDir(FunctionalGradle.testKitDir(gradleVersion))
            // The property is part of the cache key, so every build passes it.
            .withArguments(
                arguments + listOf(
                    "--configuration-cache", "--stacktrace",
                    "-P${Settings.COUNTERS}=${counters.absolutePath}",
                )
            )
            .withEnvironment(environment)
            .forwardOutput()
            .build()
            .output

        val calls = if (gitLog.isFile) gitLog.readLines().filter(String::isNotBlank) else emptyList()
        // No git call at all means the shim is not counting, unless the caller expects none.
        if (expectGit && calls.isEmpty()) {
            error(
                "no git call reached the shim, so its count is not evidence. The shim must be " +
                    "discoverable as `${shimName()}` on PATH and the daemon must have been started " +
                    "with that PATH -- a daemon already running keeps the environment it started with."
            )
        }
        return Observed(
            gitCalls = calls.size,
            // Two words tell subcommands apart; the third is often a commit that changes per run.
            gitCommands = calls.map { it.split(" ").take(2).joinToString(" ") }.toSet(),
            counts = if (counters.isFile) {
                counters.readLines().filter(String::isNotBlank).associate { line ->
                    val (key, value) = line.split("\t")
                    key to value.toLong()
                }
            } else {
                emptyMap()
            },
            output = output,
            countersWritten = counters.isFile,
        )
    }

    /** A root and [subprojects] subprojects, each with its own `Test` task, in a real git repo. */
    private fun fixture(dir: File, subprojects: Int): File {
        dir.mkdirs()
        val names = (1..subprojects).map { "mod$it" }
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "guard"
            ${names.joinToString("\n") { "include(\"$it\")" }}
            """.trimIndent()
        )
        // The root has no Test task, as in most multi-project builds.
        File(dir, "build.gradle.kts").writeText("")
        names.forEach { name ->
            File(dir, "$name/src/main/java/dev/guard/$name").mkdirs()
            File(dir, "$name/src/test/java/dev/guard/$name").mkdirs()
            File(dir, "$name/build.gradle.kts").writeText(
                """
                plugins {
                    java
                    jacoco
                    id("io.github.zeuspizza.yoriwake")
                }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation(platform("org.junit:junit-bom:5.11.4"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }
                tasks.test { useJUnitPlatform() }
                """.trimIndent()
            )
            File(dir, "$name/src/main/java/dev/guard/$name/Thing.java").writeText(
                "package dev.guard.$name;\npublic class Thing { public int twice(int n) { return n * 2; } }\n"
            )
            File(dir, "$name/src/test/java/dev/guard/$name/ThingTest.java").writeText(
                """
                package dev.guard.$name;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ThingTest {
                    @Test void twice() { assertEquals(2, new Thing().twice(1)); }
                }
                """.trimIndent()
            )
        }
        File(dir, ".gitignore").writeText("build/\n.gradle/\n*.log\ncounters.tsv\n")
        git(dir, "init")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "fixture")
        return dir
    }

    private fun git(dir: File, vararg args: String) {
        val exit = ProcessBuilder(listOf("git") + args)
            .directory(dir).redirectErrorStream(true).start().waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed in the fixture")
    }

    private fun shimName(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "git.cmd" else "git"

    private fun shimDir(): File {
        // The working directory is the plugin module or the repository root depending on the runner.
        var here: File? = File(".").canonicalFile
        while (here != null) {
            val candidate = File(here, "scripts/gitcount")
            if (File(candidate, shimName()).isFile) return candidate
            here = here.parentFile
        }
        error("scripts/gitcount/${shimName()} was not found; the guard cannot count git without it")
    }

    private fun realGit(): String =
        listOf("/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git")
            .firstOrNull { File(it).canExecute() }
            ?: error("no real git found for the shim to delegate to")

    private companion object {
        /** Configures everything and executes nothing, so every git call counted is configuration's. */
        val CONFIGURE_ONLY = listOf("test", "--dry-run")

        /** The same, on the path that asks git at configuration time. */
        val SELECTING = CONFIGURE_ONLY + "-Pyoriwake.select"

        /** This task's version; the build brackets the supported range with one task per version. */
        val GRADLE_VERSIONS = listOf(FunctionalGradle.version)

        /**
         * Every question the plugin asks git at configuration time, by its first two words.
         *
         * Named rather than counted because commands differ in cost by an order of magnitude.
         * `merge-base HEAD` may run up to three times while looking for a base. `merge-base
         * --end-of-options` only appears once a map is captured, and `rev-parse HEAD` only at
         * execution time.
         */
        val EXPECTED_COMMANDS = setOf(
            "merge-base HEAD",           // where this branch left the one it will merge into
            "symbolic-ref --short",      // which branch that is
            "diff --name-only",          // what changed since
            "ls-files --others",         // and what git has never seen, which `diff` cannot report
            "ls-files -z",               // and every untracked file, ignored ones too, for the snapshot
            "rev-parse --show-toplevel", // where the repository root is, to rebase those paths
        )
    }
}
