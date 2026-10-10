package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `-Pyoriwake.complement`: the tests a selecting run of the same commit left out, and only those. A
 * test is left out only when the selecting run's record shows it ran to an outcome under the same
 * commit, tree, task, build, classpath, configuration and test JVM; anything else runs.
 */
class ComplementFunctionalTest : FunctionalTestSupport() {

    private val early = listOf("Charlie", "Delta", "Echo", "Foxtrot").map { "dev.sample.${it}Test" }.toSet()
    private val late = setOf("dev.sample.XrayTest", "dev.sample.ZuluTest")
    private val everyTest = early + late

    @Test
    fun `a selecting run and its complement at one commit run every test exactly once`(@TempDir dir: File) {
        selected(dir)
        val selected = ranTests(dir)
        val before = mapFiles(dir)

        val result = complement(dir)

        assertEquals(late, selected, result.output)
        assertEquals(early, ranTests(dir), result.output)
        assertEquals(everyTest, selected + ranTests(dir))
        assertContains(result.output, "2 tests left out as already run, 4 run")
        assertEquals(setOf("ALREADY_RAN"), reasonsOf(dir, late), result.output)
        assertEquals(AgentContract.RUN_COMPLEMENTED, decisionNotes(dir)[AgentContract.OUTCOME_NOTE], result.output)
        assertFalse("recorded no coverage" in result.output, result.output)
        assertEquals(before, mapFiles(dir), "the complement run changed the map or the selection record")
    }

    @Test
    fun `a fail-fast selecting run leaves no record, so a test whose result it dropped runs in the complement`(@TempDir dir: File) {
        captured(dir)
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            "package dev.sample;\npublic class Alpha { public int twice(int n) { return n * 3; } }\n"
        )
        commit(dir, "break alpha")
        // After XrayTest fails the test JVM may run ZuluTest too, and Gradle may drop its result.
        val selecting = runner(dir, "test", SELECT, BASE, "--fail-fast").buildAndFail()
        assertTrue("dev.sample.XrayTest" in ranTests(dir), selecting.output)
        assertEquals(null, selectionRecord(dir), "a fail-fast run left a record")

        val output = runner(dir, "test", COMPLEMENT).buildAndFail().output

        assertEquals(everyTest, ranTests(dir), output)
        assertEquals("complement-no-record", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a selecting run that runs everything removes an earlier run's record`(@TempDir dir: File) {
        selected(dir)

        runner(dir, "test", SELECT, BASE, "-Pyoriwake.fullRun").build()
        assertEquals(null, selectionRecord(dir), "a run that ran everything left the earlier record")
        val output = complement(dir).output

        assertRecordedInFull(dir, output, "complement-no-record")
    }

    @Test
    fun `a commit since the selecting run runs every test and records, naming the commit`(@TempDir dir: File) {
        selected(dir)
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "--allow-empty", "-m", "later")

        val output = complement(dir).output

        assertRecordedInFull(dir, output, AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND)
        assertContains(output, "the commit")
    }

    @Test
    fun `no selecting run runs every test and records, as having no record`(@TempDir dir: File) {
        captured(dir)
        changeAlpha(dir)
        commit(dir, "change alpha")

        val output = complement(dir).output

        assertRecordedInFull(dir, output, "complement-no-record")
    }

    @Test
    fun `an untracked file since the selecting run runs every test, the tree no longer clean`(@TempDir dir: File) {
        selected(dir)
        File(dir, "notes.txt").writeText("written between the two runs\n")

        val output = complement(dir).output

        assertRecordedInFull(dir, output, AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND)
        assertContains(output, "working tree")
    }

    @Test
    fun `a test JVM that died in the selecting run leaves its tests to the complement`(@TempDir dir: File) {
        captured(dir, forkEvery = true)
        changeAlpha(dir)
        commit(dir, "change alpha")
        // ZuluTest halts its JVM while this file exists; build/ is ignored, so the tree stays clean.
        File(dir, "build/halt.flag").apply { parentFile.mkdirs() }.writeText("halt\n")
        runner(dir, "test", SELECT, BASE).buildAndFail()
        File(dir, "build/halt.flag").delete()
        assertEquals(setOf("XrayTest"), selectionRecord(dir)?.let(::ranIn), "the record of the surviving JVM")

        val output = complement(dir).output

        assertEquals(early + "dev.sample.ZuluTest", ranTests(dir), output)
    }

    @Test
    fun `a test an assumption aborted in the selecting run runs in the complement`(@TempDir dir: File) {
        captured(dir)
        changeAlpha(dir)
        commit(dir, "change alpha")
        // ZuluTest's assumption fails while this file exists, unless ZULU_RUNS is set; build/ is ignored.
        File(dir, "build/zulu-assumes").writeText("abort\n")
        runner(dir, "test", SELECT, BASE).build()
        assertEquals(setOf("XrayTest"), selectionRecord(dir)?.let(::ranIn))

        val output = runner(dir, "test", COMPLEMENT).withEnvironment(System.getenv() + ("ZULU_RUNS" to "yes")).build().output

        assertEquals(early + "dev.sample.ZuluTest", ranTests(dir), output)
        assertFalse(
            "<skipped" in File(dir, "build/test-results/test/TEST-dev.sample.ZuluTest.xml").readText(),
            "ZuluTest was aborted again rather than run",
        )
    }

    @Test
    fun `a parameterised test the selecting run finished runs again in the complement`(@TempDir dir: File) {
        captured(dir, parameterised = true)
        changeAlpha(dir)
        commit(dir, "change alpha")
        runner(dir, "test", SELECT, BASE).build()
        assertTrue("dev.sample.YankeeTest" in ranTests(dir))

        val output = complement(dir).output

        assertTrue("dev.sample.YankeeTest" in ranTests(dir), output)
        assertEquals(
            "NOT_ALREADY_RAN",
            decisionReasons(dir).entries.single { "YankeeTest" in it.key }.value,
            output,
        )
    }

    @Test
    fun `a saved copy of the map directory is read by task`(@TempDir dir: File) {
        selected(dir)
        val saved = File(dir.parentFile, "${dir.name}-saved").apply { deleteRecursively() }
        File(dir, ".gradle/yoriwake").copyRecursively(saved)
        File(mapDir(dir), AgentContract.SELECTION_FILE).delete()

        val output = runner(dir, "test", "-Pyoriwake.complement=${saved.absolutePath}").build().output

        assertEquals(early, ranTests(dir), output)
    }

    @Test
    fun `with a trusted-map list, this build's own record runs every test and only a saved copy is read`(@TempDir dir: File) {
        selected(dir)
        val saved = File(dir.parentFile, "${dir.name}-saved").apply { deleteRecursively() }
        File(dir, ".gradle/yoriwake").copyRecursively(saved)
        // Outside the checkout, so the tree stays clean; an empty list is valid.
        val list = File(dir.parentFile, "${dir.name}-trusted.tsv").apply { writeText("") }
        val trusted = "-Pyoriwake.trustedMaps=${list.absolutePath}"

        val own = runner(dir, "test", COMPLEMENT, trusted).build().output

        assertRecordedInFull(dir, own, "complement-record-mismatch")
        assertContains(own, "is not one the trusted-map list vouches for")

        val copied = runner(dir, "test", "-Pyoriwake.complement=${saved.absolutePath}", trusted).build().output

        assertEquals(early, ranTests(dir), copied)
    }

    @Test
    fun `a saved directory holding no record for this task runs every test`(@TempDir dir: File) {
        selected(dir)
        val saved = File(dir.parentFile, "${dir.name}-empty").apply { deleteRecursively(); mkdirs() }

        val output = runner(dir, "test", "-Pyoriwake.complement=${saved.absolutePath}").build().output

        assertRecordedInFull(dir, output, "complement-no-record")
    }

    @Test
    fun `a record another build of the same repository wrote runs every test, naming the build`(@TempDir root: File) {
        // The same sample twice: the repository's top level and a separate build beneath it.
        val other = File(root, "other")
        writeSample(root)
        writeSample(other)
        committed(root)
        runner(other, "test").build()
        changeAlpha(root)
        changeAlpha(other)
        commit(root, "change alpha")
        runner(other, "test", SELECT, BASE).build()
        assertTrue(File(mapDir(other), AgentContract.SELECTION_FILE).isFile, "the other build left no record")

        val output = runner(root, "test", "-Pyoriwake.complement=${File(other, ".gradle/yoriwake").absolutePath}").build().output

        assertEquals(AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND, decisionNotes(root)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertContains(output, "the build")
        assertEquals(everyTest, ranTests(root), output)
    }

    @Test
    fun `a classpath resolved differently runs every test, naming the classpath`(@TempDir dir: File) {
        captured(dir, extraBuild = "\ndependencies { testRuntimeOnly(\"org.slf4j:slf4j-api:\" + providers.gradleProperty(\"slf4j\").getOrElse(\"2.0.13\")) }\n")
        changeAlpha(dir)
        commit(dir, "change alpha")
        runner(dir, "test", SELECT, BASE).build()

        val output = runner(dir, "test", COMPLEMENT, "-Pslf4j=2.0.12").build().output

        assertRecordedInFull(dir, output, AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND)
        assertContains(output, "classpath")
    }

    @Test
    fun `a file the build generates from a property differently runs every test, naming the classpath`(@TempDir dir: File) {
        captured(
            dir,
            extraBuild = "\nval buildInfo = tasks.register(\"buildInfo\") {\n" +
                "    val flavor = providers.gradleProperty(\"flavor\").getOrElse(\"a\")\n" +
                "    val out = layout.buildDirectory.dir(\"generated/res\")\n" +
                "    inputs.property(\"flavor\", flavor)\n    outputs.dir(out)\n" +
                "    doLast { out.get().file(\"build-info.properties\").asFile.writeText(\"flavor=\$flavor\\n\") }\n}\n" +
                "dependencies { testRuntimeOnly(files(buildInfo)) }\n",
        )
        changeAlpha(dir)
        commit(dir, "change alpha")
        runner(dir, "test", SELECT, BASE, "-Pflavor=a").build()
        assertEquals(setOf("XrayTest", "ZuluTest"), selectionRecord(dir)?.let(::ranIn), "the selecting run left no record")

        val output = runner(dir, "test", COMPLEMENT, "-Pflavor=b").build().output

        assertEquals("flavor=b\n", File(dir, "build/generated/res/build-info.properties").readText())
        assertRecordedInFull(dir, output, AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND)
        assertContains(output, "classpath")
    }

    @Test
    fun `a task configured differently runs every test, naming its configuration`(@TempDir dir: File) {
        captured(dir, extraBuild = "\ntasks.test { systemProperty(\"sample.mode\", providers.gradleProperty(\"mode\").getOrElse(\"a\")) }\n")
        changeAlpha(dir)
        commit(dir, "change alpha")
        runner(dir, "test", SELECT, BASE).build()

        val output = runner(dir, "test", COMPLEMENT, "-Pmode=b").build().output

        assertRecordedInFull(dir, output, AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND)
        assertContains(output, "configuration")
    }

    @Test
    fun `a JVM argument an argument provider sets differently runs every test, naming the configuration`(@TempDir dir: File) {
        captured(
            dir,
            extraBuild = "\ntasks.test {\n    val mode = providers.gradleProperty(\"mode\").getOrElse(\"a\")\n" +
                "    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf(\"-Dsample.mode=\$mode\") })\n}\n",
        )
        changeAlpha(dir)
        commit(dir, "change alpha")
        runner(dir, "test", SELECT, BASE).build()

        val output = runner(dir, "test", COMPLEMENT, "-Pmode=b").build().output

        assertRecordedInFull(dir, output, AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND)
        assertContains(output, "configuration")
    }

    @Test
    fun `a record from another Java runtime runs every test uncaptured, noting the mismatch`(@TempDir dir: File) {
        selected(dir)
        val record = File(mapDir(dir), AgentContract.SELECTION_FILE)
        record.writeText(
            record.readLines().joinToString("\n", postfix = "\n") {
                if (it.startsWith("#!${AgentContract.JVM_NOTE_PREFIX}java.vendor\t")) "#!${AgentContract.JVM_NOTE_PREFIX}java.vendor\tAnother Vendor" else it
            }
        )
        val before = mapFiles(dir)

        val output = complement(dir).output

        assertEquals(everyTest, ranTests(dir), output)
        val notes = decisionNotes(dir)
        assertEquals(AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND, notes[AgentContract.REFUSAL_KIND_NOTE], output)
        assertContains(notes[AgentContract.FULL_RUN_REASON_NOTE].orEmpty(), "java.vendor")
        assertEquals(before, mapFiles(dir), "an uncaptured run changed the map")
    }

    @Test
    fun `tests named on a complement run all run, declined as tests-named`(@TempDir dir: File) {
        selected(dir)
        val before = mapFiles(dir)

        val output = runner(dir, "test", COMPLEMENT, "--tests", "dev.sample.XrayTest").build().output

        assertEquals(setOf("dev.sample.XrayTest"), ranTests(dir), output)
        assertEquals("tests-named", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertFalse("recorded no coverage" in output, output)
        assertEquals(before, mapFiles(dir), "a run of named tests changed the map")
    }

    @Test
    fun `a complement run whose record was replaced since the last one is not up to date`(@TempDir dir: File) {
        selected(dir)
        val first = File(mapDir(dir), AgentContract.SELECTION_FILE).readText()
        runner(dir, "test", SELECT, BASE, "-Pyoriwake.alwaysRun=dev.sample.CharlieTest").build()
        assertEquals(late + "dev.sample.CharlieTest", ranTests(dir))
        val second = File(mapDir(dir), AgentContract.SELECTION_FILE).readText()
        // A saved record at a fixed path, as a later selecting run hands over a new one: between the
        // two complement runs only the record changes, not the commit, the classpath or the options.
        val saved = File(dir.parentFile, "${dir.name}-saved").apply { deleteRecursively() }
        File(dir, ".gradle/yoriwake").copyRecursively(saved)
        val savedRecord = File(saved, "${mapDir(dir).name}/${AgentContract.SELECTION_FILE}")
        savedRecord.writeText(first)
        runner(dir, "test", "-Pyoriwake.complement=${saved.absolutePath}").build()
        assertEquals(early, ranTests(dir))
        savedRecord.writeText(second)

        val result = runner(dir, "test", "-Pyoriwake.complement=${saved.absolutePath}").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome, result.output)
        assertEquals(early - "dev.sample.CharlieTest", ranTests(dir), result.output)
    }

    @Test
    fun `complementing beside selecting fails, naming both`(@TempDir dir: File) {
        captured(dir)

        val output = runner(dir, "test", COMPLEMENT, SELECT).buildAndFail().output

        assertContains(output, "-Pyoriwake.select")
        assertContains(output, "-Pyoriwake.complement")
    }

    @Test
    fun `every Kotest spec runs in the complement, though the selecting run ran it`(@TempDir dir: File) {
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "engine"""")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.1.20"
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            kotlin { jvmToolchain(21) }
            tasks.test {
                useJUnitPlatform()
                systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
                systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
            }
            """.trimIndent()
        )
        File(dir, "src/main/kotlin/dev/engine/Same.kt").apply { parentFile.mkdirs() }
            .writeText("package dev.engine\n\ninline fun same(n: Int) = n\n")
        for (name in listOf("Alpha", "Beta")) {
            File(dir, "src/main/kotlin/dev/engine/$name.kt")
                .writeText("package dev.engine\n\nclass $name {\n    fun twice(n: Int) = same(n * 2)\n}\n")
            File(dir, "src/test/kotlin/dev/engine/${name}Spec.kt").apply { parentFile.mkdirs() }.writeText(
                "package dev.engine\n\nimport io.kotest.core.spec.style.StringSpec\nimport io.kotest.matchers.shouldBe\n\n" +
                    "class ${name}Spec : StringSpec({\n    \"doubles\" { $name().twice(1) shouldBe 2 }\n})\n"
            )
        }
        File(dir, ".gitignore").writeText("build/\n.gradle/\n.kotlin/\n")
        git(dir, "init")
        commit(dir, "base")
        runner(dir, "test").build()
        File(dir, "src/main/kotlin/dev/engine/Beta.kt")
            .writeText("package dev.engine\n\nclass Beta {\n    fun twice(n: Int) = same(n + n)\n}\n")
        commit(dir, "change beta")
        runner(dir, "test", SELECT, BASE).build()
        val specs = setOf("dev.engine.AlphaSpec", "dev.engine.BetaSpec")
        assertEquals(specs, ranTests(dir))
        assertTrue(selectionRecord(dir) != null, "the selecting run left no record")

        val output = complement(dir).output

        assertEquals(specs, ranTests(dir), output)
        assertEquals(setOf("ENGINE_RUNS_EVERYTHING"), decisionReasons(dir).values.toSet(), output)
    }

    /** Checks a complement run that could not use its record ran and recorded as a run without the flag. */
    private fun assertRecordedInFull(dir: File, output: String, refusal: String) {
        assertEquals(everyTest, ranTests(dir), output)
        assertEquals(refusal, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertEquals(head(dir), stamp(dir), "the run did not record the map\n$output")
    }

    private fun writeSample(dir: File, forkEvery: Boolean = false, parameterised: Boolean = false, extraBuild: String = "") {
        dir.mkdirs()
        val beta = "@Test void passes() { assertEquals(3, new Beta().thrice(1)); }"
        val alpha = "@Test void passes() { assertEquals(2, new Alpha().twice(1)); }"
        val imports = "package dev.sample;\nimport org.junit.jupiter.api.*;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n"
        val tests = early.map { it.removePrefix("dev.sample.") }.map { name ->
            "src/test/java/dev/sample/$name.java" to "${imports}class $name { $beta }\n"
        } + listOf(
            "src/test/java/dev/sample/XrayTest.java" to "${imports}class XrayTest { $alpha }\n",
            "src/test/java/dev/sample/ZuluTest.java" to "${imports}class ZuluTest {\n" +
                "  @Test void passes() throws Exception {\n" +
                "    if (new java.io.File(\"build/halt.flag\").exists()) Runtime.getRuntime().halt(1);\n" +
                "    Assumptions.assumeTrue(System.getenv(\"ZULU_RUNS\") != null || !new java.io.File(\"build/zulu-assumes\").exists());\n" +
                "    assertEquals(2, new Alpha().twice(1));\n  }\n}\n",
        ) + if (parameterised) listOf(
            "src/test/java/dev/sample/YankeeTest.java" to "${imports}import org.junit.jupiter.params.ParameterizedTest;\n" +
                "import org.junit.jupiter.params.provider.ValueSource;\n" +
                "class YankeeTest { @ParameterizedTest @ValueSource(ints = {1, 2}) void each(int n) { assertEquals(2 * n, new Alpha().twice(n)); } }\n",
        ) else emptyList()
        build(
            dir,
            "build.gradle.kts" to minimalBuild + (if (forkEvery) "\ntasks.test { forkEvery = 1 }\n" else "") + extraBuild,
            oneClass, secondClass, classOrderByName, *tests.toTypedArray(),
        )
    }

    /** A committed sample whose map was recorded at HEAD. */
    private fun captured(dir: File, forkEvery: Boolean = false, parameterised: Boolean = false, extraBuild: String = "") {
        writeSample(dir, forkEvery, parameterised, extraBuild)
        committed(dir)
        runner(dir, "test").build()
    }

    /** [captured], then a committed change to Alpha and a selecting run that ran XrayTest and ZuluTest. */
    private fun selected(dir: File) {
        captured(dir)
        changeAlpha(dir)
        commit(dir, "change alpha")
        runner(dir, "test", SELECT, BASE).build()
        assertEquals(setOf("XrayTest", "ZuluTest"), selectionRecord(dir)?.let(::ranIn), "the selecting run left no record")
    }

    private fun changeAlpha(dir: File) {
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            "package dev.sample;\npublic class Alpha { public int twice(int n) { return n + n; } }\n"
        )
    }

    private fun complement(dir: File): BuildResult = runner(dir, "test", COMPLEMENT).build()

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun selectionRecord(dir: File): String? =
        File(mapDir(dir), AgentContract.SELECTION_FILE).takeIf(File::isFile)?.readText()

    /** The simple names of the test classes a selection record lists. */
    private fun ranIn(record: String): Set<String> = record.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.substringBefore('\t').substringAfter("[class:dev.sample.").substringBefore(']') }
        .toSet()

    private fun reasonsOf(dir: File, classes: Set<String>) =
        decisionReasons(dir).filterKeys { id -> classes.any { "[class:$it]" in id } }.values.toSet()

    private fun stamp(dir: File) = File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).readText().trim()

    private fun head(dir: File): String =
        ProcessBuilder("git", "rev-parse", "HEAD").directory(dir).start().inputStream.bufferedReader().readText().trim()

    /** The map files a run reads to select, and the selection record, as bytes. */
    private fun mapFiles(dir: File): Map<String, String> = mapDir(dir).listFiles().orEmpty()
        .filter { it.isFile && it.name in COMPARED }
        .associate { it.name to it.readText() }
        .also { assertNotEquals(emptyMap(), it) }

    private companion object {
        const val SELECT = "-Pyoriwake.select"
        const val COMPLEMENT = "-Pyoriwake.complement"
        const val BASE = "-Pyoriwake.base=HEAD"
        val COMPARED = setOf(
            AgentContract.COVERAGE_FILE, AgentContract.POSITIONS_FILE, AgentContract.FIRST_TOUCH_FILE,
            AgentContract.MAP_SCHEMA_VERSION_FILE, AgentContract.MAP_DIGEST_FILE, CoverageDecoder.CAPTURE_COMMIT_FILE,
            AgentContract.SELECTION_FILE,
        )
    }
}
