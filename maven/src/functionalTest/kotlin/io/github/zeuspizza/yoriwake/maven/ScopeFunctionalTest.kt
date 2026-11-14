package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The host's JaCoCo agent, scoped to the reactor's packages or to the host's own includes. */
class ScopeFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    /** Every package root the demo's modules declare, each kept apart as on Gradle. */
    private val REACTOR = listOf("dev.demo.app.*", "dev.demo.kt.*", "dev.demo.lib.*", "dev.demo.modp.*")

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    /** The `prepare-agent` configuration of [module] as Maven built it, after yoriwake read the reactor. */
    private fun agentConfiguration(project: File, module: String, vararg arguments: String, version: String = maven): Element {
        val out = File(project, "effective.xml")
        FunctionalMaven.run(version, project, *arguments, "help:effective-pom", "-Doutput=${out.path}").succeeded()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(out)
        val projects = document.getElementsByTagName("project").let { list -> (0 until list.length).map { list.item(it) as Element } }
        val target = projects.single { child(it, "artifactId")?.textContent == module }
        val jacoco = elements(target, "plugin").single { child(it, "artifactId")?.textContent == "jacoco-maven-plugin" }
        val execution = elements(jacoco, "execution").single { e -> elements(e, "goal").any { it.textContent == "prepare-agent" } }
        return assertNotNull(child(execution, "configuration"), "prepare-agent has no configuration")
    }

    private fun elements(parent: Element, tag: String): List<Element> =
        parent.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun child(parent: Element, tag: String): Element? =
        (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == tag }

    private fun includes(configuration: Element) =
        child(configuration, "includes")?.let { elements(it, "include").map(Element::getTextContent) }

    @ParameterizedTest(name = "Maven {0}")
    @ValueSource(strings = ["3.9.16", "4.0.0-rc-7"])
    fun `with no includes of its own, the agent is scoped to every package the reactor declares`(version: String, @TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)

        val configuration = agentConfiguration(project, "lib", version = version)

        assertEquals(REACTOR, includes(configuration))
        assertEquals("true", child(configuration, "inclNoLocationClasses")?.textContent)
        assertEquals(
            "sun.reflect.DelegatingClassLoader:jdk.internal.reflect.DelegatingClassLoader",
            child(configuration, "exclClassLoaders")?.textContent,
        )
    }

    @Test
    fun `the host's own includes are adopted, not replaced`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, "pom.xml").edit {
            it.replace("<id>prepare-agent</id>", "<id>prepare-agent</id>\n            <configuration><includes><include>dev.demo.lib.*</include></includes></configuration>")
        }

        assertEquals(listOf("dev.demo.lib.*"), includes(agentConfiguration(project, "lib")))
    }

    @Test
    fun `an agent under its own property name is scoped too`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, "pom.xml").edit {
            it.replace("<id>prepare-agent</id>", "<id>prepare-agent</id>\n            <configuration><propertyName>coverageAgent</propertyName></configuration>")
                .replace(
                    "<artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>",
                    "<artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version><configuration><argLine>@{coverageAgent}</argLine></configuration>",
                )
        }

        val configuration = agentConfiguration(project, "lib")

        assertEquals(REACTOR, includes(configuration))
        assertEquals("coverageAgent", child(configuration, "propertyName")?.textContent)
    }

    @Test
    fun `a source file that cannot be read widens the scope to everything`(@TempDir dir: File) {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "file permissions do not deny a read there")
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        val unreadable = File(project, "lib/src/main/java/dev/demo/lib/Counter.java")
        Files.setPosixFilePermissions(unreadable.toPath(), PosixFilePermissions.fromString("---------"))
        try {
            assumeFalse(unreadable.canRead(), "this user reads any file")

            assertEquals(listOf("*"), includes(agentConfiguration(project, "lib", "-pl", "lib", "-Dmaven.main.skip")))
        } finally {
            Files.setPosixFilePermissions(unreadable.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun `a build of one module derives the scope of the whole reactor`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)

        assertEquals(REACTOR, includes(agentConfiguration(project, "app", "-pl", "app")))
    }

    private fun git(project: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
    }

    /** The demo, [edit] applied and committed. */
    private fun committed(dir: File, edit: (File) -> Unit = {}): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        edit(project)
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
    }

    /** The demo, [edit] applied, committed and captured. */
    private fun captured(dir: File, edit: (File) -> Unit = {}): File =
        committed(dir, edit).also { FunctionalMaven.run(maven, it, "verify").succeeded() }

    /** The map of `lib`'s Surefire `default-test` execution. */
    private fun libMap(project: File): File? = File(project, ".yoriwake/yoriwake").listFiles().orEmpty()
        .singleOrNull { it.name.startsWith("dev.demo-lib-maven-surefire-plugin-default-test-") }

    /** The scope `lib`'s Surefire map records, one line per pattern. */
    private fun libScope(project: File): List<String> = File(assertNotNull(libMap(project)), "effective-scope").readLines()

    /** JaCoCo's agent jar of [version], in the local repository every build shares. */
    private fun agentJar(version: String): File =
        File(FunctionalMaven.localRepo, "org/jacoco/org.jacoco.agent/$version/org.jacoco.agent-$version-runtime.jar")

    /** `lib`'s POM, its Surefire configured with [surefire] and [dependencies] declared. */
    private fun configureLib(project: File, surefire: String, dependencies: String = "") = File(project, "lib/pom.xml").edit {
        it.replace(
            "<artifactId>lib</artifactId>",
            "<artifactId>lib</artifactId>$dependencies<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>" +
                "<configuration>$surefire</configuration></plugin></plugins></build>",
        )
    }

    /** [agent] as the `java` launcher of the fork reads it, from the execution's environment. */
    private fun launcherOptions(agent: String) = "<environmentVariables><JDK_JAVA_OPTIONS>$agent</JDK_JAVA_OPTIONS></environmentVariables>"

    @Test
    fun `a capture records the scope its JaCoCo agent was given`(@TempDir dir: File) {
        val project = captured(dir)

        assertEquals(listOf("yoriwake-effective-scope 1") + REACTOR.map { "include\t$it" }, libScope(project))

        // A class the map has never seen: the absence rule now has a scope to ask.
        File(project, "lib/src/main/java/dev/demo/lib/Extra.java").writeText("package dev.demo.lib;\npublic class Extra {}\n")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "a new class")
        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output
        assertFalse("the map does not record the scope" in output, output)
    }

    @Test
    fun `the host's excludes are in the recorded scope`(@TempDir dir: File) {
        val project = captured(dir) {
            File(it, "pom.xml").edit { pom ->
                pom.replace("<id>prepare-agent</id>", "<id>prepare-agent</id>\n            <configuration><excludes><exclude>dev.demo.lib.Counter</exclude></excludes></configuration>")
            }
        }

        assertTrue("exclude\tdev.demo.lib.Counter" in libScope(project), libScope(project).toString())
    }

    @Test
    fun `a JaCoCo agent handed over only in JDK_JAVA_OPTIONS captures and records its scope`(@TempDir dir: File) {
        // The argLine drops the agent prepare-agent built, so the launcher's variable is the only way in.
        val agent = "-javaagent:${agentJar("0.8.12").invariantSeparatorsPath}=destfile=target/launcher.exec,includes=dev.demo.*"
        val project = committed(dir) { configureLib(it, "<argLine>-Xmx256m</argLine>" + launcherOptions(agent)) }

        val captured = FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded().output

        assertFalse("lib surefire:test (default-test) declined" in captured, captured)
        assertContains(captured, "[yoriwake] lib surefire:test (default-test) map updated: ")
        assertEquals(listOf("yoriwake-effective-scope 1", "include\tdev.demo.*"), libScope(project))

        // A class the map has never seen is judged against the scope that agent was given.
        File(project, "lib/src/main/java/dev/demo/lib/Extra.java").writeText("package dev.demo.lib;\npublic class Extra {}\n")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "a new class")
        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output
        assertFalse("the map does not record the scope" in output, output)
    }

    @Test
    fun `a JaCoCo agent JDK_JAVA_OPTIONS names by a property captures and records its scope`(@TempDir dir: File) {
        // prepare-agent's property, read by the launcher's variable instead of the argLine.
        val project = committed(dir) { configureLib(it, "<argLine>-Xmx256m</argLine>" + launcherOptions("\${argLine}")) }

        val captured = FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded().output

        assertFalse("lib surefire:test (default-test) declined" in captured, captured)
        assertContains(captured, "[yoriwake] lib surefire:test (default-test) map updated: ")
        assertEquals(listOf("yoriwake-effective-scope 1") + REACTOR.map { "include\t$it" }, libScope(project))
    }

    @Test
    fun `a second JaCoCo agent of another version in JDK_JAVA_OPTIONS fails the test JVM and dates no map`(@TempDir dir: File) {
        val second = agentJar("0.8.13")
        val project = committed(dir) {
            configureLib(
                it,
                launcherOptions("-javaagent:${second.invariantSeparatorsPath}=destfile=target/second.exec,includes=dev.demo.app.*"),
                // A dependency of the module, so that this build brings the jar into the local repository.
                "<dependencies><dependency><groupId>org.jacoco</groupId><artifactId>org.jacoco.agent</artifactId>" +
                    "<version>0.8.13</version><classifier>runtime</classifier><scope>test</scope></dependency></dependencies>",
            )
        }

        val result = FunctionalMaven.run(maven, project, "verify", "-pl", "lib")

        assertNotEquals(0, result.exitCode, result.output)
        assertTrue(second.isFile, "the second agent's jar is not in the local repository")
        assertFalse("map updated" in result.output, result.output)
        assertFalse(libMap(project)?.let { File(it, "capture-commit").exists() } == true, "the map was dated")
    }
}
