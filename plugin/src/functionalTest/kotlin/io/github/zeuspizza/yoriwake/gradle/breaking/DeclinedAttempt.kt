package io.github.zeuspizza.yoriwake.gradle.breaking

import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * Attempts whose verdict is a decline: yoriwake leaves the task alone, so no agent writes a decision
 * record and [attempt] would see nothing forced. The decline is read where the daemon states it
 * instead, in the build's output and `audit.json`, and the attempt is proved and classified exactly
 * as [attempt] does: the change must fail the target when every test runs, and a run that left no
 * test out holds only on the decline it aimed at.
 */
abstract class DeclinedAttempt : BreakAttempt() {

    /**
     * What a selecting run prints, or its audit reports, when each decline holds. Most declines
     * print their token; those that do not print the sentence that names them.
     */
    private val evidence = mapOf(
        "develocity-test-distribution" to "develocity-test-distribution",
        "develocity-test-selection" to "develocity-test-selection",
        "develocity-undetermined" to "develocity-undetermined",
        "in-jvm-parallelism" to "in-jvm-parallelism",
        "gradle-too-old" to "gradle-too-old",
        "isolated-projects" to "isolated-projects",
        "no-host-plugin" to "no-host-plugin",
        "kotlin-multiplatform" to "Kotlin Multiplatform module with no JVM target",
        "jacoco-disabled" to "jacoco extension and it is DISABLED",
        "no-packages-derived" to "no packages were found in this build's sources",
        "no-jacoco-plugin" to "the jacoco plugin is not applied",
        "no-jacoco-extension" to "has no jacoco extension",
        "includes-rejected" to "setting jacoco includes failed",
    )

    /**
     * As [attempt], for [task] (its results under `build/test-results/<task>`), with every build
     * run through [gradle], and the decline read from the selecting run's output and, when the
     * task has one, its audit.
     */
    protected fun declinedAttempt(
        dir: File,
        target: String,
        aim: Aim.Forced,
        sources: List<Pair<String, String>>,
        buildScript: String = minimalBuild,
        task: String = "test",
        gradle: (Array<out String>) -> GradleRunner = { args -> runner(dir, *args) },
        change: (File) -> Unit,
    ): Result {
        build(dir, "build.gradle.kts" to buildScript, *sources.toTypedArray())
        committed(dir)
        gradle(arrayOf(task)).build()
        val captured = ran(dir, task)
        check(passed(dir, task, target) == true) { "$target did not pass when the map was recorded; ran $captured" }
        val targetCases = Regex("<testcase ").findAll(File(dir, "build/test-results/$task/TEST-$target.xml").readText())
            .count()
        check(targetCases == 1) { "$target ran $targetCases test cases; a target holds one test method" }

        File(dir, "build/test-results").deleteRecursively()
        change(dir)
        check(gitStatus(dir).isNotBlank()) { "the change left the working tree as it was" }

        val output = gradle(arrayOf(task, "-Pyoriwake.select", "-Pyoriwake.base=HEAD")).run().output
        val selected = ran(dir, task)
        val targetResult = passed(dir, task, target)
        val audit = gradle(arrayOf("yoriwakeAudit${task.replaceFirstChar(Char::uppercase)}")).run().output +
            File(dir, ".gradle/yoriwake").walkTopDown().filter { it.name == "audit.json" }.joinToString("") { it.readText() }

        File(dir, "build/test-results").deleteRecursively()
        gradle(arrayOf(task, "--rerun", "-Pyoriwake.disabled")).run()
        val failsUnderFullRun = passed(dir, task, target) == false

        val text = output + audit
        return classify(
            Observed(
                targetFailsUnderFullRun = failsUnderFullRun,
                targetRan = targetResult != null,
                targetFailed = targetResult == false,
                leftOut = captured - selected,
                forcedKinds = evidence.filterValues { it in text }.keys.toList(),
                targetRows = emptyList(),
            ),
            aim,
        )
    }

    private fun ran(dir: File, task: String): Set<String> =
        File(dir, "build/test-results/$task").listFiles { f: File -> f.name.endsWith(".xml") }.orEmpty()
            .map { it.name.removePrefix("TEST-").removeSuffix(".xml") }.toSet()

    private fun passed(dir: File, task: String, testClass: String): Boolean? {
        val xml = File(dir, "build/test-results/$task/TEST-$testClass.xml")
        if (!xml.isFile) return null
        val suite = Regex("""<testsuite [^>]*>""").find(xml.readText())?.value ?: return null
        fun count(attribute: String) = Regex("""$attribute="(\d+)"""").find(suite)?.groupValues?.get(1)?.toInt() ?: 0
        if (count("tests") == count("skipped")) return null
        return count("failures") + count("errors") == 0
    }

    private fun gitStatus(dir: File): String =
        ProcessBuilder("git", "status", "--porcelain").directory(dir).redirectErrorStream(true).start()
            .let { process -> process.inputStream.bufferedReader().readText().also { process.waitFor() } }
}
