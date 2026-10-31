package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A selecting run on Maven in another environment than the capture's runs every test and says what
 * differs: the test JVM's arguments, Maven's own environment, and the JDK the map was recorded on.
 */
class EnvironmentFingerprintFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /**
     * The demo, `lib`'s argLine passing `-Dfeature=${feature}` beside JaCoCo's agent, committed and
     * captured with [arguments] and Maven's process [environment]; then a Greeter edit committed.
     */
    private fun captured(dir: File, vararg arguments: String, environment: Map<String, String> = emptyMap()): File =
        FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
            File(project, "lib/pom.xml").edit {
                it.replace(
                    "<artifactId>lib</artifactId>",
                    "<artifactId>lib</artifactId><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>" +
                        "<configuration><argLine>@{argLine} -Dfeature=${'$'}{feature}</argLine></configuration>" +
                        "</plugin></plugins></build>",
                )
            }
            File(project, ".gitignore").writeText("target/\n")
            git(project, "init", "-q")
            git(project, "add", ".")
            git(project, "commit", "-q", "-m", "demo")
            FunctionalMaven.run(maven, project, "verify", "-Dfeature=on", *arguments, environment = environment).succeeded()
            editGreeter(project)
        }

    private fun editGreeter(project: File) {
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")
    }

    private fun selecting(
        project: File,
        vararg arguments: String,
        feature: String = "on",
        environment: Map<String, String> = emptyMap(),
    ): String = FunctionalMaven.run(
        maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", "-Dfeature=$feature", *arguments,
        environment = environment,
    ).succeeded().output

    private fun libMap(project: File): File = File(project, ".yoriwake/yoriwake").listFiles().orEmpty()
        .single { it.name.startsWith("dev.demo-lib-maven-surefire-plugin-default-test-") }

    /** The decision record's notes, as `#!key<TAB>value` lines. */
    private fun notes(project: File): Map<String, String> = File(libMap(project), "decisions.tsv").readLines()
        .filter { it.startsWith("#!") }
        .associate { line -> line.removePrefix("#!").split("\t", limit = 2).let { it[0] to it.getOrElse(1) { "" } } }

    /**
     * Rewrites the map's recorded `jdk.version` to [version] as a capture on that JDK would have
     * written it, under the id its block then hashes to; returns the version it replaced.
     */
    private fun recordJdk(map: File, version: String): String {
        val fingerprints = File(map, "environment-fingerprints.tsv")
        val rows = fingerprints.readLines().filter(String::isNotBlank).map { Tsv.split(it).toList() }
        val jdk = rows.single { it[1] == "jdk.version" }
        val block = rows.filter { it[0] == jdk[0] }.map { if (it === jdk) listOf(it[1], version) else listOf(it[1], it[2]) }
        val id = MessageDigest.getInstance("SHA-256")
            .digest(block.joinToString("") { Tsv.join(it[0], it[1]) + "\n" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        fingerprints.writeText(
            rows.joinToString("") { row ->
                when {
                    row === jdk -> Tsv.join(id, row[1], version)
                    row[0] == jdk[0] -> Tsv.join(id, row[1], row[2])
                    else -> Tsv.join(*row.toTypedArray())
                } + "\n"
            },
        )
        File(map, "environment.tsv").apply { writeText(readText().replace(jdk[0], id)) }
        return jdk[2]
    }

    private fun refusalLine(output: String) = output.lines().single { "differs from the one the map was recorded in" in it }

    private fun assertRanEverything(project: File, output: String) {
        assertContains(output, "Tests run: 2, Failures: 0")
        assertEquals("environment-changed", notes(project)["refusal-kind"], output)
    }

    private fun assertNarrowed(output: String) {
        val selected = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selected, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selected, output)
    }

    @Test
    fun `the same environment narrows as before`(@TempDir dir: File) {
        val project = captured(dir)

        val output = selecting(project)

        assertNarrowed(output)
        assertFalse(output.lines().any { "[yoriwake]" in it && "environment" in it }, output)
    }

    @Test
    fun `another value of a property the argLine passes runs every test`(@TempDir dir: File) {
        val project = captured(dir)

        val output = selecting(project, feature = "off")

        assertRanEverything(project, output)
        val line = refusalLine(output)
        assertContains(line, "sys:feature")
        assertFalse(Regex("""\b(on|off)\b""").containsMatchIn(line), line)
    }

    @Test
    fun `another time zone in Maven's environment runs every test`(@TempDir dir: File) {
        val project = captured(dir, environment = mapOf("TZ" to "UTC"))

        val output = selecting(project, environment = mapOf("TZ" to "Asia/Tokyo"))

        assertRanEverything(project, output)
        assertContains(refusalLine(output), "TZ")
    }

    @Test
    fun `a map recorded on another JDK runs every test, names both versions, and the next run narrows`(@TempDir dir: File) {
        val project = captured(dir)
        val real = recordJdk(libMap(project), "17.0.9+9")

        val output = selecting(project)

        assertRanEverything(project, output)
        val line = refusalLine(output)
        assertContains(line, "jdk.version was 17.0.9+9")
        assertContains(line, "is $real now")
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello,\" + \" \"", "\"Hello\" + \", \"") }
        git(project, "commit", "-q", "-am", "another edit")
        assertNarrowed(selecting(project))
    }
}
