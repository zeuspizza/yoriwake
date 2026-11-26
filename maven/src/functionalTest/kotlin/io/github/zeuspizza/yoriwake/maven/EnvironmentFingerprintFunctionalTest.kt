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
 * differs: the test JVM's arguments, the user properties Surefire hands it, Maven's own environment
 * and the variables the POM lists, and the JDK the map was recorded on.
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
     * The demo, `lib`'s argLine passing [argLine] beside JaCoCo's agent and its Surefire configured
     * with [surefire], the yoriwake plugin with [yoriwake], [setup] run on it, committed and captured
     * with [arguments] and Maven's process [environment]; then a Greeter edit committed.
     */
    private fun captured(
        dir: File,
        vararg arguments: String,
        environment: Map<String, String> = emptyMap(),
        argLine: String = "-Dfeature=${'$'}{feature}",
        surefire: String = "",
        yoriwake: String = "",
        setup: (File) -> Unit = {},
    ): File =
        FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
            File(project, "lib/pom.xml").edit {
                it.replace(
                    "<artifactId>lib</artifactId>",
                    "<artifactId>lib</artifactId><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>" +
                        "<configuration><argLine>@{argLine} $argLine</argLine>$surefire</configuration>" +
                        "</plugin></plugins></build>",
                )
            }
            configureYoriwake(project, yoriwake)
            setup(project)
            File(project, ".gitignore").writeText("target/\n")
            git(project, "init", "-q")
            git(project, "add", ".")
            git(project, "commit", "-q", "-m", "demo")
            FunctionalMaven.run(maven, project, "verify", "-Dfeature=on", *arguments, environment = environment).succeeded()
            editGreeter(project)
        }

    /** The root POM's yoriwake plugin, configured with [configuration]. */
    private fun configureYoriwake(project: File, configuration: String) {
        if (configuration.isEmpty()) return
        File(project, "pom.xml").edit {
            it.replace("<extensions>true</extensions>", "<extensions>true</extensions><configuration>$configuration</configuration>")
        }
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
        var replaced = ""
        rewriteBlock(map) { components ->
            replaced = components.single { it.first == "jdk.version" }.second
            components.map { if (it.first == "jdk.version") it.first to version else it }
        }
        return replaced
    }

    /**
     * Rewrites the map's one fingerprint block through [transform], as a capture that recorded the
     * result would have written it, under the id the block then hashes to.
     */
    private fun rewriteBlock(map: File, transform: (List<Pair<String, String>>) -> List<Pair<String, String>>) {
        val fingerprints = File(map, "environment-fingerprints.tsv")
        val rows = fingerprints.readLines().filter(String::isNotBlank).map { Tsv.split(it).toList() }
        val old = rows.map { it[0] }.distinct().single()
        val block = transform(rows.map { it[1] to it[2] })
        val id = MessageDigest.getInstance("SHA-256")
            .digest(block.joinToString("") { Tsv.join(it.first, it.second) + "\n" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        fingerprints.writeText(block.joinToString("") { Tsv.join(id, it.first, it.second) + "\n" })
        File(map, "environment.tsv").apply { writeText(readText().replace(old, id)) }
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
        // A literal in the argLine, so no user property carries it: only the argLine path can see it change.
        val project = captured(dir, argLine = "-Dmode=fast")
        File(project, "lib/pom.xml").edit { it.replace("-Dmode=fast", "-Dmode=slow") }
        git(project, "commit", "-q", "-am", "another mode")

        val output = selecting(project)

        assertRanEverything(project, output)
        val line = refusalLine(output)
        assertContains(line, "sys:mode")
        assertFalse(Regex("""\b(fast|slow)\b""").containsMatchIn(line), line)
    }

    @Test
    fun `another value in Surefire's systemProperties runs every test`(@TempDir dir: File) {
        val project = captured(dir, argLine = "", surefire = "<systemProperties><mode>fast</mode></systemProperties>")
        File(project, "lib/pom.xml").edit { it.replace("<mode>fast</mode>", "<mode>slow</mode>") }
        git(project, "commit", "-q", "-am", "another mode")

        val output = selecting(project)

        assertRanEverything(project, output)
        val line = refusalLine(output)
        assertContains(line, "sys:mode")
        assertFalse(Regex("""\b(fast|slow)\b""").containsMatchIn(line), line)
    }

    @Test
    fun `another value in Surefire's systemProperties written as name and value runs every test`(@TempDir dir: File) {
        val project = captured(
            dir, argLine = "",
            surefire = "<systemProperties><property><name>mode</name><value>fast</value></property></systemProperties>",
        )
        File(project, "lib/pom.xml").edit { it.replace("<value>fast</value>", "<value>slow</value>") }
        git(project, "commit", "-q", "-am", "another mode")

        val output = selecting(project)

        assertRanEverything(project, output)
        assertContains(refusalLine(output), "sys:mode")
    }

    /**
     * `lib`'s Surefire naming, by a path relative to the module, a `local.properties` beside the
     * checkout that holds [content] at the capture, or is a directory when null. Outside the
     * checkout, so no change to the tree says it changed: only the file's own properties can.
     */
    private fun capturedWithFile(dir: File, content: String?, yoriwake: String = ""): File =
        captured(dir, argLine = "", surefire = SYSTEM_PROPERTIES_FILE, yoriwake = yoriwake) {
            val file = File(dir, "local.properties")
            if (content == null) file.mkdirs() else file.writeText(content)
        }

    @Test
    fun `another value in the systemPropertiesFile runs every test`(@TempDir dir: File) {
        val project = capturedWithFile(dir, "mode=fast\n")
        File(dir, "local.properties").writeText("mode=slow\n")

        val output = selecting(project)

        assertRanEverything(project, output)
        val line = refusalLine(output)
        assertContains(line, "sys:mode")
        assertFalse(Regex("""\b(fast|slow)\b""").containsMatchIn(line), line)
        val stored = File(libMap(project), "environment-fingerprints.tsv").readText()
        assertFalse(Regex("""\t(fast|slow)\n""").containsMatchIn(stored), stored)
    }

    @Test
    fun `an ignored property in the systemPropertiesFile narrows`(@TempDir dir: File) {
        val project = capturedWithFile(dir, "mode=fast\n", yoriwake = "<systemPropertyIgnore><property>mode</property></systemPropertyIgnore>")
        File(dir, "local.properties").writeText("mode=slow\n")

        val output = selecting(project)

        assertNarrowed(output)
        assertEquals("mode", notes(project)["fingerprint-ignored"], output)
    }

    @Test
    fun `a systemPropertiesFile that cannot be read runs every test`(@TempDir dir: File) {
        val project = capturedWithFile(dir, null)

        val output = selecting(project)

        assertRanEverything(project, output)
        assertContains(refusalLine(output), "sys:@systemPropertiesFile")
    }

    @Test
    fun `an absent systemPropertiesFile narrows`(@TempDir dir: File) {
        val project = captured(dir, argLine = "", surefire = SYSTEM_PROPERTIES_FILE)

        assertNarrowed(selecting(project))
    }

    @Test
    fun `the same systemPropertiesFile narrows as before`(@TempDir dir: File) {
        val project = capturedWithFile(dir, "mode=fast\n")

        assertNarrowed(selecting(project))
    }

    /**
     * The demo captured with the invocation, not the POM, naming a `local.properties` beside the
     * checkout that holds [content]; the argument that names it, for the selecting run.
     */
    private fun capturedWithFileNamedOnTheCommandLine(dir: File, content: String): Pair<File, String> {
        val file = File(dir, "local.properties").apply { writeText(content) }
        val named = "-Dsurefire.systemPropertiesFile=${file.absolutePath}"
        return captured(dir, named, argLine = "") to named
    }

    @Test
    fun `another value in a systemPropertiesFile named on the command line runs every test`(@TempDir dir: File) {
        val (project, named) = capturedWithFileNamedOnTheCommandLine(dir, "mode=fast\n")
        File(dir, "local.properties").writeText("mode=slow\n")

        val output = selecting(project, named)

        assertRanEverything(project, output)
        assertContains(refusalLine(output), "sys:mode")
    }

    @Test
    fun `the same systemPropertiesFile named on the command line narrows as before`(@TempDir dir: File) {
        val (project, named) = capturedWithFileNamedOnTheCommandLine(dir, "mode=fast\n")

        assertNarrowed(selecting(project, named))
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

    @Test
    fun `another value of a user property runs every test`(@TempDir dir: File) {
        // Nothing in the POM passes it: Surefire hands the fork every user property of the invocation.
        val project = captured(dir, argLine = "")

        val output = selecting(project, feature = "off")

        assertRanEverything(project, output)
        val line = refusalLine(output)
        assertContains(line, "sys:feature")
        assertContains(line, "systemPropertyIgnore leaves out a property that differs between builds or checkouts")
        assertFalse(Regex("""\b(on|off)\b""").containsMatchIn(line), line)
        val stored = File(libMap(project), "environment-fingerprints.tsv").readText()
        assertFalse(Regex("""\t(on|off)\n""").containsMatchIn(stored), stored)
    }

    @Test
    fun `two user properties that differ are both named with the setting that leaves them out`(@TempDir dir: File) {
        val project = captured(dir, "-Dother=a", argLine = "")

        val output = selecting(project, "-Dother=b", feature = "off")

        assertRanEverything(project, output)
        assertContains(
            refusalLine(output),
            "sys:feature has another value than when the map was recorded (1 other system property differs too: sys:other; systemPropertyIgnore",
        )
    }

    @Test
    fun `the same user properties narrow as before`(@TempDir dir: File) {
        val project = captured(dir, argLine = "")

        assertNarrowed(selecting(project))
    }

    @Test
    fun `a user property left out by systemPropertyIgnore narrows`(@TempDir dir: File) {
        val project = captured(
            dir, "-Dbuild.number=1", argLine = "",
            yoriwake = "<systemPropertyIgnore><property>build.*</property></systemPropertyIgnore>",
        )

        val output = selecting(project, "-Dbuild.number=2")

        assertNarrowed(output)
        assertEquals("build.number", notes(project)["fingerprint-ignored"], output)
    }

    @Test
    fun `an ignored property in the argLine narrows`(@TempDir dir: File) {
        val project = captured(
            dir, "-Dbuild.number=1", argLine = "-Dbuild.number=${'$'}{build.number}",
            yoriwake = "<systemPropertyIgnore><property>build.*</property></systemPropertyIgnore>",
        )

        val output = selecting(project, "-Dbuild.number=2")

        assertNarrowed(output)
        assertEquals("build.number", notes(project)["fingerprint-ignored"], output)
    }

    @Test
    fun `a listed variable with another value runs every test`(@TempDir dir: File) {
        val project = captured(
            dir, environment = mapOf("DB_MODE" to "memory"),
            yoriwake = "<environmentInclude><variable>DB_MODE</variable></environmentInclude>",
        )

        val output = selecting(project, environment = mapOf("DB_MODE" to "postgres"))

        assertRanEverything(project, output)
        val line = refusalLine(output)
        assertContains(line, "env:DB_MODE")
        assertFalse("memory" in line || "postgres" in line, line)
    }

    @Test
    fun `a listed variable set through environmentVariables is compared`(@TempDir dir: File) {
        val project = captured(
            dir, surefire = "<environmentVariables><DB_MODE>memory</DB_MODE></environmentVariables>",
            yoriwake = "<environmentInclude><variable>DB_*</variable></environmentInclude>",
        )
        File(project, "lib/pom.xml").edit { it.replace("<DB_MODE>memory</DB_MODE>", "<DB_MODE>postgres</DB_MODE>") }
        git(project, "commit", "-q", "-am", "another database")

        val output = selecting(project)

        assertRanEverything(project, output)
        assertContains(refusalLine(output), "env:DB_MODE")
    }

    @Test
    fun `a map recorded before user properties were compared runs every test once`(@TempDir dir: File) {
        val project = captured(dir)
        rewriteBlock(libMap(project)) { components -> components.filterNot { it.first == "maven.user-properties" } }

        val output = selecting(project)

        assertRanEverything(project, output)
        assertContains(refusalLine(output), "maven.user-properties")
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello,\" + \" \"", "\"Hello\" + \", \"") }
        git(project, "commit", "-q", "-am", "another edit")
        assertNarrowed(selecting(project))
    }

    @Test
    fun `a module whose parent is not the root compares a variable the root lists`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        // lib's own parent is outside the reactor, so it inherits no yoriwake plugin entry.
        File(project, "lib/pom.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>dev.demo</groupId>
              <artifactId>lib</artifactId>
              <version>1.0</version>
              <properties>
                <maven.compiler.release>17</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.11.4</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                  <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                  <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
                  <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                  <plugin>
                    <groupId>org.jacoco</groupId>
                    <artifactId>jacoco-maven-plugin</artifactId>
                    <version>0.8.12</version>
                    <executions><execution><id>prepare-agent</id><goals><goal>prepare-agent</goal></goals></execution></executions>
                  </plugin>
                </plugins>
              </build>
            </project>
            """.trimIndent()
        )
        configureYoriwake(project, "<environmentInclude><variable>DB_MODE</variable></environmentInclude>")
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
        FunctionalMaven.run(maven, project, "verify", "-Dfeature=on", environment = mapOf("DB_MODE" to "memory")).succeeded()
        editGreeter(project)

        // The whole reactor: with `-pl lib` alone, no module of the build declares the extension.
        val output = FunctionalMaven.run(
            maven, project, "verify", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", "-Dfeature=on",
            environment = mapOf("DB_MODE" to "postgres"),
        ).succeeded().output

        assertRanEverything(project, output)
        assertContains(refusalLine(output.substringAfter("[yoriwake] lib surefire:test").substringBefore("[yoriwake] app surefire:test")), "env:DB_MODE")
    }

    private fun explaining(
        project: File,
        vararg arguments: String,
        feature: String,
        environment: Map<String, String> = emptyMap(),
    ): String = FunctionalMaven.run(
        maven, project, "test-compile", "yoriwake:explain", "-pl", "lib", "-Dyoriwake.base=HEAD~1", "-Dfeature=$feature", *arguments,
        environment = environment,
    ).succeeded().output

    @Test
    fun `explain over a map recorded under another user property says the run would run everything`(@TempDir dir: File) {
        val project = captured(dir)

        val explained = explaining(project, feature = "off")

        val line = explained.lines().single { "[yoriwake] lib surefire:test (default-test) would run everything" in it }
        assertContains(line, "differs from the one the map was recorded in")
        assertContains(line, "sys:feature")
        assertContains(File(libMap(project), "explain.json").readText(), "environment-changed")
        // As the selecting run it explains decides.
        assertRanEverything(project, selecting(project, feature = "off"))
    }

    @Test
    fun `explain under the test JVM the map was recorded with narrows, as the selecting run does`(@TempDir dir: File) {
        // Named on the command line only, where the POM's configuration says nothing of it.
        val bin = File(System.getProperty("java.home"), "bin")
        val jvm = "-Djvm=${(File(bin, "java.exe").takeIf(File::isFile) ?: File(bin, "java")).path}"
        val project = captured(dir, jvm)

        val explained = explaining(project, jvm, feature = "on")

        assertContains(explained, "[yoriwake] lib surefire:test (default-test) would run 1 of 2 known tests")
        assertNarrowed(selecting(project, jvm))
    }

    @Test
    fun `explain under the recorded environment narrows and names a variable a test read that nobody compares`(@TempDir dir: File) {
        val project = captured(dir, environment = mapOf("DB_MODE" to "a")) {
            // Named to run before GreeterTest, so only GreeterTest reaches the change.
            File(it, "lib/src/test/java/dev/demo/lib/EnvModeTest.java").writeText(
                """
                package dev.demo.lib;

                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import org.junit.jupiter.api.Test;

                class EnvModeTest {
                    @Test
                    void readsItsMode() {
                        assertNotNull(System.getenv("DB_MODE"));
                    }
                }
                """.trimIndent() + "\n",
            )
        }

        val explained = explaining(project, feature = "on", environment = mapOf("DB_MODE" to "b"))

        assertContains(explained, "[yoriwake] lib surefire:test (default-test) would run 1 of 3 known tests")
        val line = explained.lines().single { "read by the tests and not compared" in it }
        assertContains(line, "[yoriwake] lib surefire:test (default-test): ")
        assertContains(line, "DB_MODE")
    }

    @Test
    fun `an entry of only a star fails the build before any test runs`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        configureYoriwake(project, "<systemPropertyIgnore><property>*</property></systemPropertyIgnore>")

        val result = FunctionalMaven.run(maven, project, "verify")

        assertFalse(result.exitCode == 0, result.output)
        assertContains(result.output, "demo-maven: systemPropertyIgnore holds '*', which matches every name")
        assertFalse("Tests run:" in result.output, result.output)
    }

    @Test
    fun `an empty entry fails the build, naming the list`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        configureYoriwake(project, "<environmentInclude><variable> </variable></environmentInclude>")

        val result = FunctionalMaven.run(maven, project, "verify")

        assertFalse(result.exitCode == 0, result.output)
        assertContains(result.output, "demo-maven: environmentInclude holds '', which names nothing")
    }

    private companion object {
        const val SYSTEM_PROPERTIES_FILE = "<systemPropertiesFile>../../local.properties</systemPropertiesFile>"
    }
}
