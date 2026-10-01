package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.wiring.AgentJar
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The plugin as a stranger gets it: resolved by id and version from the repository this build
// publishes to. `withPluginClasspath()` proves nothing about the published artifact, marker or POM.
class PublishedPluginTest {

    private val repository = File(
        requireNotNull(System.getProperty("yoriwake.localRepository")) {
            "the build did not say where it published the plugin; run this through Gradle"
        },
    )

    private val artifact = File(repository, "io/github/zeuspizza/yoriwake-gradle-plugin/0.1.0-SNAPSHOT")

    /** The one timestamped file of a kind; the build empties the repository before publishing. */
    private fun published(dir: File, kind: String, accept: (String) -> Boolean): File {
        val matches = dir.listFiles().orEmpty().filter { accept(it.name) }
        assertEquals(1, matches.size, "expected one $kind in $dir, found ${matches.map(File::getName)}")
        return matches.single()
    }

    private fun pom(dir: File) = published(dir, "POM") { it.endsWith(".pom") }.readText()

    @Test
    fun `the POM names the artifact, the licence and the source, and no person`() {
        val pom = pom(artifact)

        assertContains(pom, "<artifactId>yoriwake-gradle-plugin</artifactId>")
        assertContains(pom, "<name>The Apache License, Version 2.0</name>")
        assertContains(pom, "<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>")
        assertContains(pom, "<url>https://github.com/zeuspizza/yoriwake</url>")
        // A public artifact cannot be unpublished, so an address in it is there for good.
        assertFalse("<email>" in pom, "the POM carries an email address")
        assertFalse(
            Regex("@[A-Za-z0-9-]+\\.[A-Za-z]").containsMatchIn(pom),
            "the POM carries something shaped like an address",
        )
        // The agent is bundled, never depended on: a dependency here makes every host resolve it.
        assertFalse("<artifactId>agent</artifactId>" in pom, "the agent leaked into the POM as a dependency")
    }

    @Test
    fun `neither the POM nor the Gradle module metadata declares any dependency`() {
        // Every dependency is either relocated into the jar or supplied by Gradle, kotlin-stdlib
        // included; one declared here lands unshaded on every host's buildscript classpath. Gradle
        // reads the .module file before the POM, so both are checked.
        val pom = pom(artifact)
        assertFalse("<dependency>" in pom, "the POM declares a dependency:\n$pom")
        val module = published(artifact, "module metadata") { it.endsWith(".module") }.readText()
        assertFalse("\"dependencies\"" in module, "the module metadata declares a dependency:\n$module")
    }

    @Test
    fun `the plugin marker points at the renamed artifact`() {
        val marker = File(
            repository,
            "io/github/zeuspizza/yoriwake/io.github.zeuspizza.yoriwake.gradle.plugin/0.1.0-SNAPSHOT",
        )
        val pom = pom(marker)

        assertContains(pom, "<artifactId>yoriwake-gradle-plugin</artifactId>")
    }

    private fun pluginJar() = published(artifact, "plugin jar") {
        it.endsWith(".jar") && !it.endsWith("-sources.jar") && !it.endsWith("-javadoc.jar")
    }

    @Test
    fun `the plugin jar carries no class outside its own package`() {
        // Whatever the jar carries lands on the host's buildscript classpath, where an unrelocated
        // JaCoCo or ASM would meet the host's own copy. The Kotlin stdlib is Gradle's to supply.
        val entries = ZipFile(pluginJar()).use { zip -> zip.entries().toList().map { it.name } }
        val classes = entries.filter { it.endsWith(".class") }

        val foreign = classes.filterNot { it.startsWith("io/github/zeuspizza/yoriwake/") }
        assertTrue(foreign.isEmpty(), "the plugin jar carries unrelocated classes, e.g. ${foreign.take(5)}")
        assertTrue(
            classes.any { it.startsWith("io/github/zeuspizza/yoriwake/shaded/plugin/jacoco/core/data/") },
            "JaCoCo's decoder is not relocated into the plugin jar",
        )
        assertTrue(
            classes.any { it.startsWith("io/github/zeuspizza/yoriwake/shaded/plugin/asm/") },
            "the plugin's ASM is not relocated into the plugin jar",
        )
    }

    @Test
    fun `the plugin jar carries JaCoCo's EPL-2 licence and where its source is`() {
        ZipFile(pluginJar()).use { zip ->
            fun text(name: String) = zip.getEntry(name)?.let { zip.getInputStream(it).reader().readText() }
                ?: error("the plugin jar is missing $name")

            assertContains(text("META-INF/licenses/JACOCO-LICENSE.txt"), "Eclipse Public License - v 2.0")
            val source = text("META-INF/licenses/JACOCO-SOURCE.txt")
            assertContains(source, "https://github.com/jacoco/jacoco/tree/v0.")
            assertFalse("\${" in source, "the JaCoCo version was never filled in:\n$source")
            assertContains(text("META-INF/NOTICE"), "JaCoCo")
        }
    }

    @Test
    fun `both jars carry the licences, and the agent embeds no JaCoCo`() {
        // The host supplies JaCoCo to the agent through -javaagent; a copy in the agent jar would
        // sit on the test JVM's classpath next to it.
        val plugin = pluginJar()
        val pluginEntries = ZipFile(plugin).use { zip -> zip.entries().toList().map { it.name } }
        val agentEntries = ZipFile(plugin).use { zip ->
            val nested = zip.getEntry(AgentJar.RESOURCE) ?: error("the plugin jar does not bundle the agent")
            ZipInputStream(zip.getInputStream(nested)).use { stream ->
                generateSequence { stream.nextEntry }.map { it.name }.toList()
            }
        }

        for ((jar, entries) in listOf("plugin" to pluginEntries, "agent" to agentEntries)) {
            val licences = listOf("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/licenses/ASM-LICENSE.txt")
            for (licence in licences) {
                assertTrue(licence in entries, "the $jar jar is missing $licence")
            }
            assertTrue(entries.none { it.startsWith("org/jacoco/") }, "the $jar jar embeds unrelocated JaCoCo")
        }
    }

    @Test
    fun `a build that has never seen this source tree resolves the plugin by id, captures and selects`(
        @TempDir dir: File,
    ) {
        // No mavenLocal, no init script, no plugin classpath injection. The published repository is
        // the ONLY source of anything under io.github.zeuspizza; Maven Central is here for the
        // plugin's own third-party dependencies, as the Portal would serve them.
        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    exclusiveContent {
                        forRepository { maven { url = uri("${repository.toURI()}") } }
                        filter {
                            includeGroup("io.github.zeuspizza")
                            includeGroup("io.github.zeuspizza.yoriwake")
                        }
                    }
                    mavenCentral()
                }
            }
            rootProject.name = "stranger"
            """.trimIndent(),
        )
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                java
                jacoco
                id("io.github.zeuspizza.yoriwake") version "0.1.0-SNAPSHOT"
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
            """.trimIndent(),
        )
        for (name in listOf("Alpha", "Beta")) {
            File(dir, "src/main/java/dev/sample/$name.java").apply { parentFile.mkdirs() }.writeText(
                "package dev.sample; public class $name { public int twice(int n) { return n * 2; } }",
            )
            File(dir, "src/test/java/dev/sample/${name}Test.java").apply { parentFile.mkdirs() }.writeText(
                """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ${name}Test { @Test void passes() { assertEquals(2, new $name().twice(1)); } }
                """.trimIndent(),
            )
        }
        // Class order by name, so BetaTest runs last; see the selecting run below.
        File(dir, "src/test/resources/junit-platform.properties").apply { parentFile.mkdirs() }.writeText(
            "junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer\$ClassName\n",
        )
        File(dir, ".gitignore").writeText("build/" + System.lineSeparator() + ".gradle/")
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        // On every supported Gradle; the oldest matters most, because its embedded Kotlin is the
        // stdlib the plugin compiles against and then runs on, since it ships none of its own.
        fun run(vararg args: String) = GradleRunner.create()
            .withProjectDir(dir)
            .withGradleVersion(FunctionalGradle.version)
            .withTestKitDir(FunctionalGradle.testKitDir())
            .withArguments(*args, "--configuration-cache", "--stacktrace", "--no-watch-fs")
            .forwardOutput()
            .build()

        val capture = run("test")
        assertEquals(TaskOutcome.SUCCESS, capture.task(":test")?.outcome)
        // Proves the published plugin applied, not merely resolved.
        assertContains(capture.output, "[yoriwake] :test")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))

        // Selecting reads back the map the relocated JaCoCo decoded, so a relocation that broke
        // decoding shows here as a full run rather than as a missing class.
        File(dir, "build/test-results").deleteRecursively()
        // Beta, whose test runs last: a change to Alpha would also select BetaTest, which ran after
        // Alpha was first loaded, and a run that selects everything proves no narrowing.
        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample; public class Beta { public int twice(int n) { return n + n; } }",
        )
        run("test", "-Pyoriwake.select")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
    }

    private fun git(dir: File, vararg args: String) {
        val exit = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true).start().waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed")
    }

    private fun ranTests(dir: File): Set<String> =
        File(dir, "build/test-results/test")
            .listFiles { f: File -> f.name.endsWith(".xml") }
            .orEmpty()
            .map { it.name.removePrefix("TEST-").removeSuffix(".xml") }
            .toSet()
}
