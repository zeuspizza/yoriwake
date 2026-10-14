package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A map recorded in another environment, or with none recorded, does not select: the run runs every
 * test, says what differs, and records the map again, so the next run selects.
 */
class EnvironmentFingerprintFunctionalTest : FunctionalTestSupport() {

    private val both = setOf("dev.sample.AModeTest", "dev.sample.AlphaTest", "dev.sample.BetaTest")

    // The feature flag reaches the test JVM as a system property; the fixture path holds the
    // checkout's own directory.
    private val buildScript = minimalBuild + """

        tasks.test {
            systemProperty("feature.x", providers.gradleProperty("feature").getOrElse("on"))
            systemProperty("fixtures", layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
            providers.gradleProperty("jvm").orNull?.let { executable = it }
            providers.gradleProperty("build").orNull?.let { systemProperty("build.number", it) }
        }
        yoriwake {
            providers.gradleProperty("include").orNull?.let { environmentInclude.add(it) }
            providers.gradleProperty("ignore").orNull?.let { systemPropertyIgnore.add(it) }
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
    """.trimIndent()

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun fingerprints(dir: File) = File(mapDir(dir), AgentContract.ENVIRONMENT_FINGERPRINTS_FILE)

    /** The map's one recorded fingerprint, with [component] set to [value], written as a capture would. */
    private fun rewriteRecorded(dir: File, component: String, value: String) {
        val mapDir = mapDir(dir)
        val rows = File(mapDir, AgentContract.ENVIRONMENT_FILE).readLines().filter(String::isNotBlank).map(Tsv::split)
        val block = fingerprints(dir).readLines().filter(String::isNotBlank).map(Tsv::split)
            .associate { it[1] to it[2] }
        assertTrue(component in block, "no $component recorded: $block")
        EnvironmentFingerprint.write(mapDir, emptyList(), rows.map { it[0] }, block + (component to value))
    }

    /**
     * A committed two-class sample whose map was recorded under [arguments], then a change to Beta:
     * BetaTest runs last by name, so a run that narrows runs it alone.
     */
    private fun recorded(dir: File, vararg arguments: String, environment: Map<String, String> = System.getenv()) {
        dir.mkdirs()
        build(dir, "build.gradle.kts" to buildScript, oneClass, secondClass, oneTest, secondTest, classOrderByName, modeTest)
        committed(dir)
        runner(dir, "test", *arguments).withEnvironment(environment).build()
        changeBeta(dir)
    }

    /** Another change to Beta, so the next run executes rather than finding the task up to date. */
    private fun changeBetaAgain(dir: File) {
        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return 3 * n; } }\n"
        )
    }

    private val narrowed = setOf("dev.sample.BetaTest")

    // Runs first by name, so a change to Beta never selects it; reads a variable by name.
    private val modeTest = "src/test/java/dev/sample/AModeTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        class AModeTest {
            @Test void readsTheMode() { System.getenv("DB_MODE"); new Alpha().twice(1); }
        }
    """.trimIndent()

    private fun refusalLine(output: String) = output.lines().single { "differs from the one the map was recorded in" in it }

    @Test
    fun `the same environment narrows as before`(@TempDir dir: File) {
        recorded(dir)
        assertTrue(fingerprints(dir).isFile, "the capture recorded no fingerprint")

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(narrowed, ranTests(dir), output)
        assertFalse("environment" in output, output)
    }

    @Test
    fun `a map recorded on another JDK runs everything, names both, and records this one`(@TempDir dir: File) {
        recorded(dir)
        val real = fingerprints(dir).readLines().map(Tsv::split).single { it[1] == "jdk.version" }[2]
        rewriteRecorded(dir, "jdk.version", "17.0.9+9")

        val explained = runner(dir, "yoriwakeExplainTest").build().output
        assertContains(explained, "would run everything: the test JVM's environment differs")
        assertContains(File(mapDir(dir), YoriwakePlugin.EXPLANATION_FILE).readText(), "\"environment-changed\"")

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(both, ranTests(dir), output)
        val notes = decisionNotes(dir)
        assertEquals("environment-changed", notes["refusal-kind"], output)
        val reason = assertNotNull(notes["full-run-reason"])
        assertContains(reason, "jdk.version was 17.0.9+9")
        assertContains(reason, "is $real now")
        assertContains(refusalLine(output), "jdk.version")
        assertTrue(fingerprints(dir).readLines().any { it.endsWith("jdk.version\t$real") }, "the map was not recorded again")

        changeBetaAgain(dir)
        val next = runner(dir, "test", "-Pyoriwake.select").build().output
        assertEquals(null, decisionNotes(dir)["refusal-kind"], next)
        assertEquals(narrowed, ranTests(dir), next)
    }

    @Test
    fun `a selecting run on another installed JDK runs everything`(@TempDir dir: File) {
        val other = anotherJdk()
        assumeTrue(other != null, "no second JDK of another version is installed")
        recorded(dir, "-Pjvm=${File(System.getProperty("java.home"), "bin/java").absolutePath}")

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pjvm=${File(other, "bin/java").absolutePath}").build().output

        assertEquals(both, ranTests(dir), output)
        assertEquals("environment-changed", decisionNotes(dir)["refusal-kind"], output)
        assertContains(refusalLine(output), "jdk.version")
    }

    @Test
    fun `another value of a system property runs everything, and no value is printed or stored`(@TempDir dir: File) {
        recorded(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pfeature=off").build().output

        assertEquals(both, ranTests(dir), output)
        assertEquals("environment-changed", decisionNotes(dir)["refusal-kind"], output)
        val line = refusalLine(output)
        assertContains(line, "sys:feature.x")
        assertFalse(Regex("""\b(on|off)\b""").containsMatchIn(line), line)
        // Recorded again with the new value, and neither value is a field of any map file.
        val fields = mapDir(dir).walk().filter(File::isFile)
            .flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()).flatMap { it.split('\t') } }
            .toSet()
        assertFalse("on" in fields || "off" in fields, "a value was written to the map")

        changeBetaAgain(dir)
        val again = runner(dir, "test", "-Pyoriwake.select", "-Pfeature=off").build().output
        assertEquals(null, decisionNotes(dir)["refusal-kind"], again)
        assertEquals(narrowed, ranTests(dir), again)
    }

    @Test
    fun `a map recorded with no fingerprint runs everything once`(@TempDir dir: File) {
        recorded(dir)
        File(mapDir(dir), AgentContract.ENVIRONMENT_FILE).delete()
        fingerprints(dir).delete()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(both, ranTests(dir), output)
        assertEquals("environment-unrecorded", decisionNotes(dir)["refusal-kind"], output)
        changeBetaAgain(dir)
        val next = runner(dir, "test", "-Pyoriwake.select").build().output
        assertEquals(null, decisionNotes(dir)["refusal-kind"], next)
        assertEquals(narrowed, ranTests(dir), next)
    }

    @Test
    fun `a map restored into another checkout, with other CI run variables, still selects`(@TempDir root: File) {
        val dir = File(root, "recorded")
        val ci = mapOf(
            "GITHUB_RUN_ID" to "1", "GITHUB_SHA" to "a".repeat(40), "GITHUB_REF" to "refs/heads/main",
            "GITHUB_EVENT_NAME" to "push", "GITHUB_HEAD_REF" to "", "BUILD_NUMBER" to "41", "CI_JOB_ID" to "7",
        )
        recorded(dir, environment = System.getenv() + ci)
        val other = File(root, "pull-request")
        git(root, "clone", "-q", dir.absolutePath, other.absolutePath)
        File(dir, ".gradle/yoriwake").copyRecursively(File(other, ".gradle/yoriwake"))
        changeBeta(other)

        val output = runner(other, "test", "-Pyoriwake.select").withEnvironment(
            System.getenv() + mapOf(
                "GITHUB_RUN_ID" to "2", "GITHUB_SHA" to "b".repeat(40), "GITHUB_REF" to "refs/pull/3/merge",
                "GITHUB_EVENT_NAME" to "pull_request", "GITHUB_HEAD_REF" to "feature", "BUILD_NUMBER" to "42",
                "CI_JOB_ID" to "8",
            ),
        ).build().output

        assertFalse("environment" in output, output)
        assertEquals(narrowed, ranTests(other), output)
    }

    @Test
    fun `a map recorded in isolation is left alone, and the console says how to record it`(@TempDir dir: File) {
        recorded(dir, "-Pyoriwake.isolatedCapture")
        rewriteRecorded(dir, "jdk.version", "17.0.9+9")
        val before = fingerprints(dir).readText()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(both, ranTests(dir), output)
        assertEquals("environment-changed", decisionNotes(dir)["refusal-kind"], output)
        assertContains(output, "-Pyoriwake.isolatedCapture")
        assertEquals(before, fingerprints(dir).readText(), "the selecting run recorded over an isolated map")
    }

    @Test
    fun `a listed variable with another value runs everything`(@TempDir dir: File) {
        recorded(dir, "-Pinclude=DB_MODE", environment = System.getenv() + ("DB_MODE" to "a"))

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pinclude=DB_MODE")
            .withEnvironment(System.getenv() + ("DB_MODE" to "b")).build().output

        assertEquals(both, ranTests(dir), output)
        assertEquals("environment-changed", decisionNotes(dir)["refusal-kind"], output)
        assertContains(refusalLine(output), "env:DB_MODE")
    }

    @Test
    fun `a variable a test reads and nobody listed is not compared, and explain names it`(@TempDir dir: File) {
        recorded(dir, environment = System.getenv() + ("DB_MODE" to "a"))
        println("[env-reads] " + File(mapDir(dir), AgentContract.ENV_READS_FILE).takeIf(File::isFile)?.readText())

        val explained = runner(dir, "yoriwakeExplainTest").withEnvironment(System.getenv() + ("DB_MODE" to "b")).build().output
        val line = explained.lines().single { "read by the tests and not compared" in it }
        assertContains(line, "DB_MODE")

        val output = runner(dir, "test", "-Pyoriwake.select")
            .withEnvironment(System.getenv() + ("DB_MODE" to "b")).build().output
        assertEquals(narrowed, ranTests(dir), output)
    }

    @Test
    fun `an ignored property that changes on every build is no change, and the record says it was left out`(@TempDir dir: File) {
        recorded(dir, "-Pbuild=41", "-Pignore=build.*")

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pbuild=42", "-Pignore=build.*").build().output

        assertEquals(narrowed, ranTests(dir), output)
        assertEquals("build.number", decisionNotes(dir)["fingerprint-ignored"], output)
    }

    @Test
    fun `a list entry that matches every name fails the build, naming the property`(@TempDir dir: File) {
        recorded(dir)

        val output = runner(dir, "test", "-Pinclude=*").buildAndFail().output

        assertContains(output, "environmentInclude holds '*'")
    }

    /** An installed JDK of another major version that can run Java 11 classes, or null. */
    private fun anotherJdk(): File? {
        val current = File(System.getProperty("java.home")).canonicalFile
        val feature = { home: File ->
            runCatching {
                File(home, "release").readLines().first { it.startsWith("JAVA_VERSION=") }
                    .substringAfter('"').substringBefore('"').removePrefix("1.").substringBefore('.').toInt()
            }.getOrNull()
        }
        val fromCi = System.getenv().filterKeys { Regex("JAVA_HOME_\\d+_[A-Z0-9]+").matches(it) }.values.map(::File)
        val fromMac = runCatching {
            ProcessBuilder("/usr/libexec/java_home", "-V").redirectErrorStream(true).start()
                .inputStream.bufferedReader().readLines()
        }.getOrDefault(emptyList()).mapNotNull { line -> line.trim().substringAfterLast(' ').takeIf { it.startsWith("/") }?.let(::File) }
        val own = feature(current)
        return (fromCi + fromMac).map { it.canonicalFile }.distinct()
            .filter { File(it, "bin/java").isFile && it != current }
            .firstOrNull { home -> feature(home)?.let { it >= 11 && it != own } == true }
            .also { found -> found?.let { assertNotEquals(current, it) } }
    }
}
