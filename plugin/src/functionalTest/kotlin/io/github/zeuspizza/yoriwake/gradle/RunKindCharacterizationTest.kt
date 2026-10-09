package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Pins what a recording, a selecting, an observing and a complement run hand the test JVM, and what they capture, as seen just
 * before the tests start: the selection and refusal properties, whether records are written,
 * `forkEvery`, whether JaCoCo is on, and whether the run is marked as one that executes everything.
 * Every site that reads whether a run selects is behind one of these observations.
 */
class RunKindCharacterizationTest : FunctionalTestSupport() {

    // Inserted just before the tests run, after every action of the plugin's.
    private val probe = """

        gradle.taskGraph.whenReady {
            val test = tasks.named<Test>("test").get()
            val root = layout.projectDirectory.asFile
            val out = layout.buildDirectory.file("probe/run-kind.txt").get().asFile
            val probe = Action<Task> {
                val t = this as Test
                val props = t.systemProperties.mapValues { it.value?.toString() }
                val map = File(root, ".gradle/yoriwake").listFiles()?.singleOrNull { it.isDirectory }
                out.parentFile.mkdirs()
                out.writeText(
                    "select=" + props["yoriwake.select"] +
                        " refused=" + props["yoriwake.refused.kind"] +
                        " records=" + (if (props["yoriwake.internal.capture.outputDir"].isNullOrEmpty()) "off" else "on") +
                        " forkEvery=" + t.forkEvery +
                        " jacoco=" + t.allJvmArgs.any { it.contains("jacocoagent") } +
                        " fullRunMarker=" + (map != null && File(map, "full-run.marker").isFile)
                )
            }
            val actions = test.actions.toMutableList()
            val tests = actions.indexOfFirst { (it as? org.gradle.api.Describable)?.displayName?.contains("executeTests") == true }
            actions.add(tests, probe)
            test.actions = actions
        }
    """.trimIndent()

    private val build = minimalBuild + probe

    @Test
    fun `runs without a map`(@TempDir tmp: File) {
        val observed = COMBINATIONS.associateWith { flags ->
            val dir = committedSample(File(tmp, "run" + COMBINATIONS.indexOf(flags)))
            observe(dir, *flags.toTypedArray())
        }

        assertEquals(
            mapOf(
                emptyList<String>() to "select=null refused=null records=on forkEvery=0 jacoco=true fullRunMarker=false",
                listOf(ISOLATED) to "select=null refused=null records=on forkEvery=1 jacoco=true fullRunMarker=false",
                listOf(SELECT) to "select=false refused=bytes-unrecorded records=on forkEvery=0 jacoco=true fullRunMarker=true",
                listOf(SELECT, ISOLATED) to "select=false refused=bytes-unrecorded records=on forkEvery=0 jacoco=true fullRunMarker=true",
                listOf(OBSERVE) to "select=false refused=bytes-unrecorded records=on forkEvery=0 jacoco=true fullRunMarker=true",
                listOf(OBSERVE, ISOLATED) to "select=false refused=bytes-unrecorded records=on forkEvery=1 jacoco=true fullRunMarker=true",
                listOf(COMPLEMENT) to "select=false refused=complement-no-record records=on forkEvery=0 jacoco=true fullRunMarker=false",
            ),
            observed,
        )
    }

    @Test
    fun `runs over a map, with a change`(@TempDir tmp: File) {
        val observed = COMBINATIONS.associateWith { flags ->
            val dir = capturedSample(File(tmp, "run" + COMBINATIONS.indexOf(flags)))
            changeBeta(dir)
            observe(dir, *flags.toTypedArray())
        }

        assertEquals(
            mapOf(
                emptyList<String>() to "select=null refused=null records=on forkEvery=0 jacoco=true fullRunMarker=false",
                listOf(ISOLATED) to "select=null refused=null records=on forkEvery=1 jacoco=true fullRunMarker=false",
                listOf(SELECT) to "select=true refused=null records=off forkEvery=0 jacoco=false fullRunMarker=false",
                listOf(SELECT, ISOLATED) to "select=true refused=null records=off forkEvery=0 jacoco=false fullRunMarker=false",
                listOf(OBSERVE) to "select=true refused=null records=on forkEvery=0 jacoco=true fullRunMarker=true",
                listOf(OBSERVE, ISOLATED) to "select=true refused=null records=on forkEvery=1 jacoco=true fullRunMarker=true",
                listOf(COMPLEMENT) to "select=false refused=complement-no-record records=on forkEvery=0 jacoco=true fullRunMarker=false",
            ),
            observed,
        )
    }

    @Test
    fun `a complement run whose selection record matches`(@TempDir dir: File) {
        capturedSample(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        observe(dir, SELECT)

        assertEquals(
            "select=null refused=null records=off forkEvery=0 jacoco=false fullRunMarker=false",
            observe(dir, COMPLEMENT),
        )
    }

    @Test
    fun `a selecting run over a map with no capture stamp`(@TempDir dir: File) {
        capturedSample(dir)
        File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).delete()

        assertEquals(
            "select=false refused=stamp-absent records=on forkEvery=0 jacoco=true fullRunMarker=true",
            observe(dir, SELECT),
        )
    }

    @Test
    fun `a selecting run over a map whose stamp git cannot relate`(@TempDir dir: File) {
        capturedSample(dir)
        File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).writeText("f".repeat(40))

        assertEquals(
            "select=false refused=stamp-unrelatable records=on forkEvery=0 jacoco=true fullRunMarker=true",
            observe(dir, SELECT),
        )
    }

    @Test
    fun `a selecting run over a map recorded in isolation`(@TempDir dir: File) {
        capturedSample(dir, ISOLATED)
        File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).writeText("f".repeat(40))

        assertEquals(
            "select=false refused=stamp-unrelatable records=off forkEvery=0 jacoco=false fullRunMarker=false",
            observe(dir, SELECT),
        )
    }

    @Test
    fun `a selecting run whose git diff fails`(@TempDir dir: File) {
        capturedSample(dir)
        changeBeta(dir)

        assertEquals(
            "select=false refused=no-change-set records=on forkEvery=0 jacoco=true fullRunMarker=true",
            observe(dir, SELECT, environment = mapOf("PATH" to failingDiff(dir) + File.pathSeparator + System.getenv("PATH"))),
        )
    }

    private fun committedSample(dir: File): File {
        dir.mkdirs()
        build(dir, "build.gradle.kts" to build, oneClass, oneTest, secondClass, secondTest)
        committed(dir)
        return dir
    }

    private fun capturedSample(dir: File, vararg flags: String): File {
        committedSample(dir)
        runner(dir, "test", *flags).build()
        return dir
    }

    private fun observe(dir: File, vararg flags: String, environment: Map<String, String>? = null): String {
        val runner = runner(dir, "test", "-Pyoriwake.base=HEAD", *flags)
        (environment?.let { runner.withEnvironment(it) } ?: runner).build()
        return File(dir, "build/probe/run-kind.txt").readText()
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    /** A `git` first on PATH that fails `git diff` and runs every other command as git would. */
    private fun failingDiff(dir: File): String {
        val bin = File(dir.parentFile, "${dir.name}-bin").apply { mkdirs() }
        File(bin, "git").apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = diff ]; then exit 128; fi\nexec ${realGit()} \"\$@\"\n")
            setExecutable(true)
        }
        return bin.absolutePath
    }

    private fun realGit(): String =
        System.getenv("PATH").split(File.pathSeparator).map { File(it, "git") }.first { it.canExecute() }.absolutePath

    private companion object {
        const val SELECT = "-Pyoriwake.select"
        const val ISOLATED = "-Pyoriwake.isolatedCapture"
        const val OBSERVE = "-Pyoriwake.observe"
        const val COMPLEMENT = "-Pyoriwake.complement"
        val COMBINATIONS = listOf(
            emptyList(), listOf(ISOLATED), listOf(SELECT), listOf(SELECT, ISOLATED), listOf(OBSERVE), listOf(OBSERVE, ISOLATED),
            listOf(COMPLEMENT),
        )
    }
}
