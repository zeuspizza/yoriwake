package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A selecting run on Maven (`-Dyoriwake.select`) runs what a change reaches, and refuses where Gradle refuses. */
class SelectionFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo, committed as a Maven project is, with its build output ignored. */
    private fun committed(dir: File): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
    }

    /** The demo, committed and captured, so a selecting run has a dated map to select from. */
    private fun captured(dir: File): File = committed(dir).also { project ->
        FunctionalMaven.run(maven, project, "verify").succeeded()
    }

    private fun editCounter(project: File) {
        File(project, "lib/src/main/java/dev/demo/lib/Counter.java").edit { it.replace("n * 2", "n + n") }
        git(project, "commit", "-q", "-am", "an edit")
    }

    @Test
    fun `a change to one class runs the tests that reach it`(@TempDir dir: File) {
        val project = captured(dir)
        // Greeter, which only the second test class loads: a class loaded earlier in the shared test
        // JVM would keep every test that ran after it.
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selecting, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selecting, output)
    }

    @Test
    fun `an observing run runs every test and reports what selection would have done`(@TempDir dir: File) {
        val project = captured(dir)
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.observe", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) observed: selection would have narrowed; no test failed; 0 would-be misses; 1 tests would have been skipped")
        val map = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-") }
        assertTrue(File(map, "observation.json").isFile, "no observation.json in $map")
    }

    /** The demo with commons-lang3 on `lib`'s test classpath at `${lang3.version}`, 3.17.0 unless set; captured. */
    private fun capturedWithLang3(dir: File): File = committed(dir).also { project ->
        File(project, "lib/pom.xml").edit {
            it.replace(
                "<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId>\n  <properties><lang3.version>3.17.0</lang3.version></properties>\n" +
                    "  <dependencies><dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId>" +
                    "<version>${'$'}{lang3.version}</version><scope>test</scope></dependency></dependencies>",
            )
        }
        git(project, "commit", "-q", "-am", "commons-lang3")
        FunctionalMaven.run(maven, project, "verify").succeeded()
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")
    }

    @Test
    fun `a dependency a version property resolves to another version runs every test`(@TempDir dir: File) {
        val project = capturedWithLang3(dir)

        val output = FunctionalMaven.run(
            maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", "-Dlang3.version=3.16.0",
        ).succeeded().output

        assertContains(output, "dependencies on this task's classpath changed since the map was captured")
        assertContains(output, "commons-lang3")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `an unchanged classpath selects as before`(@TempDir dir: File) {
        val project = capturedWithLang3(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selecting, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selecting, output)
        assertFalse("dependencies on this task's classpath changed" in output, output)
    }

    /** A one-class library at [version], [value] its answer, installed into the shared local repository. */
    private fun installLibrary(dir: File, groupId: String, version: String, value: Int) {
        val library = File(dir, "library").apply { deleteRecursively() }
        File(library, "src/main/java/dev/library/Answer.java").apply { parentFile.mkdirs() }
            .writeText("package dev.library;\npublic class Answer { public static int value() { return $value; } }\n")
        File(library, "pom.xml").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$groupId</groupId>
              <artifactId>answer</artifactId>
              <version>$version</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
              <build><pluginManagement><plugins>
                <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
                <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                <plugin><artifactId>maven-install-plugin</artifactId><version>3.1.3</version></plugin>
              </plugins></pluginManagement></build>
            </project>
            """.trimIndent(),
        )
        FunctionalMaven.run(maven, library, "install").succeeded()
    }

    @Test
    fun `a SNAPSHOT dependency replaced in the repository runs every test`(@TempDir dir: File) {
        // Coordinates of this test's own, so no other test resolves the library it replaces.
        val groupId = "dev.replaced${System.nanoTime()}"
        try {
            installLibrary(dir, groupId, "1.0-SNAPSHOT", 1)
            val project = committed(File(dir, "project"))
            File(project, "lib/pom.xml").edit {
                it.replace(
                    "<artifactId>lib</artifactId>",
                    "<artifactId>lib</artifactId>\n  <dependencies><dependency><groupId>$groupId</groupId><artifactId>answer</artifactId>" +
                        "<version>1.0-SNAPSHOT</version><scope>test</scope></dependency></dependencies>",
                )
            }
            git(project, "commit", "-q", "-am", "a SNAPSHOT dependency")
            FunctionalMaven.run(maven, project, "verify").succeeded()
            editGreeter(project)
            installLibrary(dir, groupId, "1.0-SNAPSHOT", 2)

            val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
                .succeeded().output

            assertContains(output, "dependencies on this task's classpath changed since the map was captured")
            assertContains(output, "answer-1.0-SNAPSHOT.jar")
            assertContains(output, "Tests run: 2, Failures: 0")
        } finally {
            File(FunctionalMaven.localRepo, groupId.replace('.', '/')).deleteRecursively()
        }
    }

    @Test
    fun `a change committed after the capture runs its tests though the base is newer`(@TempDir dir: File) {
        val project = captured(dir)
        editGreeter(project)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")
            .succeeded().output

        val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selecting, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selecting, output)
    }

    @Test
    fun `a selecting run that stops after a failure leaves no record, so a complement runs every test`(@TempDir dir: File) {
        val project = captured(dir)
        editGreeter(project)
        val stopAfter = "-Dsurefire.skipAfterFailureCount=1"
        assertContains(
            FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", stopAfter)
                .succeeded().output,
            "in dev.demo.lib.GreeterTest",
        )

        // The same property, so the stamps agree and only the missing record can refuse.
        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.complement", stopAfter).succeeded().output

        assertContains(output, "no selection.tsv for lib surefire:test (default-test) in this build's map directory")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `a module whose every test is left out passes under failIfNoTests`(@TempDir dir: File) {
        val project = captured(dir)
        editGreeter(project)
        // The same properties on both runs, so the complement's stamp matches the selecting run's.
        val arguments = arrayOf("verify", "-pl", "lib,app", "-DfailIfNoTests=true")
        assertContains(
            FunctionalMaven.run(maven, project, *arguments, "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1").succeeded().output,
            "in dev.demo.app.AppTest",
        )

        // app's one test ran in the selecting run, so the complement leaves out every test app has.
        val output = FunctionalMaven.run(maven, project, *arguments, "-Dyoriwake.complement").succeeded().output

        assertContains(output, "in dev.demo.lib.CounterTest")
        assertFalse("in dev.demo.app.AppTest" in output, output)
    }

    /** The plugin's `<configuration>` in the root POM, given [xml]; committed. */
    private fun configured(project: File, xml: String) {
        File(project, "pom.xml").edit {
            it.replace("<extensions>true</extensions>\n      </plugin>", "<extensions>true</extensions>\n        <configuration>$xml</configuration>\n      </plugin>")
        }
        git(project, "commit", "-q", "-am", "configure yoriwake")
    }

    @Test
    fun `on a branch fullRunBranches lists a selecting run runs everything and records the map`(@TempDir dir: File) {
        val project = captured(dir)
        git(project, "branch", "-M", "main")
        configured(project, "<fullRunBranches><branch>main</branch></fullRunBranches>")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        assertContains(output, "on branch main, and fullRunBranches lists `main`, so selection is declined")
        // Maven's flag, as the run was asked with: `-P` selects a Maven profile.
        assertContains(output, "every test runs as on a run without -Dyoriwake.select.")
        assertFalse("-Pyoriwake" in output, output)
        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }

    @Test
    fun `more changed paths than largeChangeThreshold run everything`(@TempDir dir: File) {
        val project = captured(dir)
        configured(project, "<largeChangeThreshold>1</largeChangeThreshold>")
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        File(project, "lib/src/main/java/dev/demo/lib/Counter.java").edit { it.replace("n * 2", "n + n") }
        git(project, "commit", "-q", "-am", "two edits")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        assertContains(output, "Analysing a change this size costs more than it saves")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `a largeChangeThreshold below one fails the build`(@TempDir dir: File) {
        val project = committed(dir)
        configured(project, "<largeChangeThreshold>0</largeChangeThreshold>")

        val result = FunctionalMaven.run(maven, project, "verify", "-pl", "lib")

        assertContains(result.output, "largeChangeThreshold is 0; it counts changed paths, so it must be 1 or more")
        assertTrue(result.exitCode != 0, result.output)
    }

    @Test
    fun `a selecting run against a reactor module from a repository runs every test`(@TempDir dir: File) {
        val project = captured(dir)
        FunctionalMaven.run(maven, project, "install", "-pl", ":demo-maven,:lib", "-DskipTests").succeeded()

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "app", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "[yoriwake] app surefire:test (default-test) declined, reactor-module-from-repository: lib")
        assertFalse("app surefire:test (default-test) selecting against" in output, output)
    }

    @Test
    fun `a scheduled full run falls due after fullRunEveryBuilds builds`(@TempDir dir: File) {
        val project = committed(dir)
        configured(project, "<fullRunEveryBuilds>2</fullRunEveryBuilds>")
        FunctionalMaven.run(maven, project, "verify").succeeded()
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")
        val select = arrayOf("verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")

        val first = FunctionalMaven.run(maven, project, *select).succeeded().output
        val second = FunctionalMaven.run(maven, project, *select).succeeded().output

        assertContains(first, "[yoriwake] lib surefire:test (default-test) selecting against ")
        assertContains(second, "2 builds since the last full run")
        assertContains(second, "Tests run: 2, Failures: 0")
    }

    /** A capture, a Greeter edit committed, and a selecting run that narrows and leaves its record. */
    private fun selectedOnce(dir: File): File = captured(dir).also { project ->
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")
        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output
        assertContains(output, "in dev.demo.lib.GreeterTest")
    }

    @Test
    fun `a complement run runs what the selecting run left out`(@TempDir dir: File) {
        val project = selectedOnce(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.complement").succeeded().output

        assertContains(output, "leaving out the 1 tests the selecting run at ")
        assertContains(output, "in dev.demo.lib.CounterTest")
        assertFalse("in dev.demo.lib.GreeterTest" in output.substringAfter("leaving out the "), output)
    }

    @Test
    fun `a complement run with another user property runs everything and names the configuration`(@TempDir dir: File) {
        val project = selectedOnce(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.complement", "-Ddemo.flag=on")
            .succeeded().output

        assertContains(output, "differs from this run in")
        assertContains(output, "configuration")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `with a trusted-map list a complement reading this build's own record runs everything`(@TempDir dir: File) {
        val project = selectedOnce(dir)
        File(project, "trusted.txt").writeText("")

        val output = FunctionalMaven.run(
            maven, project, "verify", "-pl", "lib", "-Dyoriwake.complement", "-Dyoriwake.trustedMaps=trusted.txt",
        ).succeeded().output

        assertContains(output, "is not one the trusted-map list vouches for")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `tests named with -Dtest all run in a selecting run`(@TempDir dir: File) {
        val project = captured(dir)
        editGreeter(project)

        val output = FunctionalMaven.run(
            maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1",
            "-Dtest=CounterTest,GreeterTest",
        ).succeeded().output

        assertContains(output, "tests were named with -Dtest=CounterTest,GreeterTest")
        assertContains(output, "in dev.demo.lib.CounterTest")
        assertContains(output, "in dev.demo.lib.GreeterTest")
    }

    @Test
    fun `two run kinds asked together fail the build before any test runs`(@TempDir dir: File) {
        val project = captured(dir)

        val result = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.observe")

        assertContains(result.output, "-Dyoriwake.select and -Dyoriwake.observe were set together")
        assertFalse("Tests run:" in result.output, result.output)
        assertTrue(result.exitCode != 0, result.output)
    }

    @Test
    fun `a complement run with no selecting run before it runs everything`(@TempDir dir: File) {
        val project = captured(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.complement").succeeded().output

        assertContains(output, "no selection.tsv for lib surefire:test (default-test) in this build's map directory")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `an empty change set runs every test`(@TempDir dir: File) {
        val project = captured(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "[yoriwake] running everything: the change set is empty")
        assertContains(output, "pass -Dyoriwake.base=<ref> to select against a specific base")
        assertFalse("-Pyoriwake" in output, output)
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `with no map a selecting run runs everything and records it`(@TempDir dir: File) {
        val project = committed(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }

    @Test
    fun `a full run asked for on the command line runs everything and records the map`(@TempDir dir: File) {
        val project = captured(dir)
        editCounter(project)

        val output = FunctionalMaven.run(
            maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.fullRun", "-Dyoriwake.base=HEAD~1",
        ).succeeded().output

        assertContains(output, "a full run was requested with -Dyoriwake.fullRun, so selection is declined")
        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }

    @Test
    fun `a map captured at a commit git cannot relate refuses and captures`(@TempDir dir: File) {
        val project = captured(dir)
        File(project, ".yoriwake/yoriwake").listFiles().orEmpty()
            .filter { it.name.startsWith("dev.demo-lib-") }
            .forEach { File(it, "capture-commit").writeText("0123456789abcdef0123456789abcdef01234567\n") }

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "captured at a commit git cannot relate to")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    /** `lib`'s decision record, each test's verdict and reason by the row's test id; observing rows unprefixed. */
    private fun libDecisions(project: File): Map<String, List<String>> {
        val map = File(project, ".yoriwake/yoriwake").listFiles().orEmpty()
            .single { it.name.startsWith("dev.demo-lib-maven-surefire-plugin-default-test-") }
        return File(map, "decisions.tsv").readLines()
            .map { it.removePrefix("#?") }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
            .associate { it[0] to listOf(it[1], it[2]) }
    }

    private fun counterDecision(project: File): List<String>? =
        libDecisions(project).entries.singleOrNull { "CounterTest" in it.key }?.value

    private fun editGreeter(project: File) {
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")
    }

    private fun writeWorkspaceId(project: File, id: String = "a-workspace-id") =
        File(project, ".mvn/.develocity/develocity-workspace-id").also { it.parentFile.mkdirs() }.writeText(id)

    private fun selecting(project: File): String =
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

    @Test
    fun `Develocity's untracked workspace id does not force a selecting run`(@TempDir dir: File) {
        val project = captured(dir)
        writeWorkspaceId(project)
        editGreeter(project)

        val output = selecting(project)

        val selectingLine = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
            .substringBefore("\n")
        assertContains(selectingLine, "0 paths coverage cannot see")
        assertContains(output, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in output.substringAfter("selecting against "), output)
    }

    @Test
    fun `a tracked workspace id that changed still forces`(@TempDir dir: File) {
        val project = committed(dir)
        writeWorkspaceId(project)
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "a tracked workspace id")
        FunctionalMaven.run(maven, project, "verify").succeeded()
        writeWorkspaceId(project, "another-workspace-id")
        git(project, "commit", "-q", "-am", "a new workspace id")

        val output = selecting(project)

        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `a forced run names gitignore and not pathIgnore on Maven`(@TempDir dir: File) {
        val project = captured(dir)
        File(project, "notes.txt").writeText("notes")
        editGreeter(project)

        val output = selecting(project)

        assertContains(output, "notes.txt is untracked or ignored")
        assertContains(output, "ignore it in git (.gitignore)")
        assertFalse(output.lines().any { "[yoriwake]" in it && "pathIgnore" in it }, output)
    }

    @Test
    fun `a file a test writes during the capture names neither pathIgnore nor gitignore`(@TempDir dir: File) {
        val project = committed(dir)
        File(project, "lib/src/test/java/dev/demo/lib/GreeterTest.java").edit {
            it.replace("void greets() {", "void greets() throws Exception {").replace(
                "new Greeter().greet(\"Ada\"));",
                "new Greeter().greet(\"Ada\"));\n        java.nio.file.Files.writeString(java.nio.file.Path.of(\"written.txt\"), String.valueOf(System.nanoTime()));",
            )
        }
        git(project, "commit", "-q", "-am", "a test that writes a file")
        FunctionalMaven.run(maven, project, "verify").succeeded()
        editCounter(project)

        val output = selecting(project)

        val forced = output.lines().filter { "[yoriwake]" in it && "written.txt" in it }
        assertTrue(forced.any { "changed during" in it && "build directory" in it }, output)
        assertFalse(forced.any { "pathIgnore" in it || ".gitignore" in it }, output)
    }

    @Test
    fun `with an untracked workspace id a complement run runs what the selecting run left out`(@TempDir dir: File) {
        val project = captured(dir)
        writeWorkspaceId(project)
        editGreeter(project)
        assertContains(selecting(project), "in dev.demo.lib.GreeterTest")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.complement").succeeded().output

        assertContains(output, "leaving out the 1 tests the selecting run at ")
        assertContains(output, "in dev.demo.lib.CounterTest")
    }

    @Test
    fun `a test alwaysRun names runs though the change cannot reach it`(@TempDir dir: File) {
        val project = captured(dir)
        editGreeter(project)

        val output = FunctionalMaven.run(
            maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1",
            "-Dyoriwake.alwaysRun=dev.demo.lib.CounterTest",
        ).succeeded().output

        val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selecting, "in dev.demo.lib.CounterTest")
        assertEquals(listOf("included", "ALWAYS_RUN"), counterDecision(project), output)
    }

    @Test
    fun `a module's own alwaysRun pins its test in a full reactor run`(@TempDir dir: File) {
        val project = committed(dir)
        File(project, "lib/pom.xml").edit {
            it.replace(
                "<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId><build><plugins><plugin><groupId>io.github.zeuspizza</groupId>" +
                    "<artifactId>yoriwake-maven-plugin</artifactId><configuration><alwaysRun>" +
                    "<pattern>dev.demo.lib.CounterTest</pattern></alwaysRun></configuration></plugin></plugins></build>",
            )
        }
        git(project, "commit", "-q", "-am", "pin a test in lib")
        FunctionalMaven.run(maven, project, "verify").succeeded()
        editGreeter(project)

        val output = FunctionalMaven.run(maven, project, "verify", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1").succeeded().output

        assertEquals(listOf("included", "ALWAYS_RUN"), counterDecision(project), output)
    }

    @Test
    fun `an observing run records a pinned test as always-run`(@TempDir dir: File) {
        val project = captured(dir)
        editGreeter(project)

        val output = FunctionalMaven.run(
            maven, project, "verify", "-pl", "lib", "-Dyoriwake.observe", "-Dyoriwake.base=HEAD~1",
            "-Dyoriwake.alwaysRun=dev.demo.lib.CounterTest",
        ).succeeded().output

        assertEquals(listOf("included", "ALWAYS_RUN"), counterDecision(project), output)
    }

    @Test
    fun `a pattern alwaysRun cannot read fails the build naming it and where it came from`(@TempDir dir: File) {
        val project = committed(dir)

        val result = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.alwaysRun=**")

        assertTrue(result.exitCode != 0, result.output)
        assertContains(result.output, "-Dyoriwake.alwaysRun")
        assertContains(result.output, "'**' as an always-run pattern")
        assertContains(result.output, "-Dyoriwake.enabled=false")
        assertFalse("Tests run:" in result.output, result.output)
    }
}
