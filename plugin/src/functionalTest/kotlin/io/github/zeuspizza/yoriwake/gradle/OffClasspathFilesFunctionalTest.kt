package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A sibling module's file that reaches the tested project's tests although the sibling is not on
 * its classpath: handed to the test JVM by path, built by a task the test task depends on,
 * configuring the tested project, or named by a script the tested project reads. Each keeps its
 * edits, and the change in each breaks the test that reads it, so a skip is a passing build and a
 * run is a failing one.
 */
class OffClasspathFilesFunctionalTest : FunctionalTestSupport() {

    /**
     * The root project, with [rootScript], beside a sibling `beta` and [files]; committed and
     * captured. [edit] then changes the working tree, and the selecting run that follows must fail.
     */
    private fun selectingFails(
        dir: File,
        rootScript: String,
        vararg files: Pair<String, String>,
        edit: (File) -> Unit,
    ): String {
        build(
            dir,
            "settings.gradle.kts" to "rootProject.name = \"sample\"\ninclude(\"beta\")\n",
            "build.gradle.kts" to rootScript,
            "beta/build.gradle.kts" to "plugins { java }\n",
            oneClass, oneTest, *files,
        )
        committed(dir)
        runner(dir, "test").build()
        edit(dir)
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").buildAndFail().output
    }

    /** [minimalBuild] with [extra] inside the test task's block. */
    private fun rootBuild(extra: String = "") =
        minimalBuild.replace("tasks.test { useJUnitPlatform() }", "tasks.test {\n    useJUnitPlatform()\n    $extra\n}")

    /** A test reading the first file under a directory it walks whose name ends in `.fixture`, as `3`. */
    private fun walkingTest(root: String) = "src/test/java/dev/sample/FixtureTest.java" to """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.Paths;
        import java.util.stream.Stream;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class FixtureTest {
            @Test void maxIsThree() throws Exception {
                try (Stream<Path> files = Files.walk(Paths.get($root))) {
                    Path found = files.filter(p -> p.toString().endsWith(".fixture")).findFirst().get();
                    assertEquals("3", Files.readString(found).trim());
                }
            }
        }
    """.trimIndent()

    // Under no resource directory, so no build step copies it and a walk finds this one alone.
    private val maxFixture = "beta/src/fixtures/limits/max.fixture" to "3"

    private fun changeMax(dir: File) = File(dir, maxFixture.first).writeText("4")

    private fun assertForcedAndFailed(output: String, dir: File, failed: String) {
        assertContains(output, "$failed FAILED")
        assertEquals("unmappable-paths", decisionNotes(dir)["full-run-kind"], output)
    }

    /**
     * The sibling's script hands the test its directory in an environment variable, and the test
     * walks it: no class names the file, and the tested project's scripts name no sibling.
     */
    @Test
    fun `a sibling's resource a test walks to from the directory its environment hands it forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir, rootBuild(),
            "beta/build.gradle.kts" to "plugins { java }\n" +
                "rootProject.tasks.withType<Test>().configureEach { environment(\"FIXTURE_HOME\", projectDir.absolutePath) }\n",
            maxFixture, walkingTest("System.getenv(\"FIXTURE_HOME\")"),
        ) { changeMax(it) }

        assertForcedAndFailed(output, dir, "FixtureTest > maxIsThree()")
    }

    @Test
    fun `a sibling's resource a test walks to from the directory a system property hands it forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir, rootBuild(),
            "beta/build.gradle.kts" to "plugins { java }\n" +
                "rootProject.tasks.withType<Test>().configureEach { systemProperty(\"fixtures\", file(\"src/fixtures\").absolutePath) }\n",
            maxFixture, walkingTest("System.getProperty(\"fixtures\")"),
        ) { changeMax(it) }

        assertForcedAndFailed(output, dir, "FixtureTest > maxIsThree()")
    }

    /** The test walks the whole checkout, which alone is the documented limit; the declared input is not. */
    @Test
    fun `a sibling's resource under a directory the test task declares as an input forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir, rootBuild(),
            "beta/build.gradle.kts" to "plugins { java }\n" +
                "rootProject.tasks.withType<Test>().configureEach { inputs.dir(file(\"src/fixtures\")) }\n",
            maxFixture, walkingTest("\"\""),
        ) { changeMax(it) }

        assertForcedAndFailed(output, dir, "FixtureTest > maxIsThree()")
    }

    /**
     * The sibling's jar is copied below the root's build directory, outside the sibling's own, by a
     * task the test task depends on; the test reads the one text entry it holds, by no name of it.
     */
    @Test
    fun `a sibling's resource in a jar a task the test depends on copies outside the sibling forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir, rootBuild(),
            "beta/build.gradle.kts" to """
                plugins { java }
                val fixtureJar = tasks.register<Copy>("fixtureJar") {
                    from(tasks.jar)
                    into(rootProject.layout.buildDirectory.dir("fixtures"))
                    rename { "beta.jar" }
                }
                rootProject.tasks.withType<Test>().configureEach {
                    dependsOn(fixtureJar)
                    systemProperty("fixture.jar", rootProject.layout.buildDirectory.file("fixtures/beta.jar").get().asFile.absolutePath)
                }
            """.trimIndent(),
            "beta/src/main/resources/value.txt" to "v1",
            "src/test/java/dev/sample/JarTest.java" to """
                package dev.sample;
                import java.util.jar.JarEntry;
                import java.util.jar.JarFile;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class JarTest {
                    @Test void firstVersion() throws Exception {
                        try (JarFile jar = new JarFile(System.getProperty("fixture.jar"))) {
                            JarEntry entry = jar.stream().filter(e -> e.getName().endsWith(".txt")).findFirst().get();
                            assertEquals("v1", new String(jar.getInputStream(entry).readAllBytes()).trim());
                        }
                    }
                }
            """.trimIndent(),
        ) { File(it, "beta/src/main/resources/value.txt").writeText("v2") }

        assertForcedAndFailed(output, dir, "JarTest > firstVersion()")
    }

    /**
     * A task of another module, gamma, copies a file of beta's `src` into gamma's build directory;
     * the test task depends on it and hands the test that directory. No script of the tested project
     * names beta, and no path the test is handed lies in it.
     */
    @Test
    fun `a sibling's file a task the test depends on copies elsewhere forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            rootBuild(
                "dependsOn(\":gamma:shared\")\n" +
                    "    systemProperty(\"shared\", file(\"gamma/build/shared\").absolutePath)",
            ),
            "settings.gradle.kts" to "rootProject.name = \"sample\"\ninclude(\"beta\", \"gamma\")\n",
            "gamma/build.gradle.kts" to "tasks.register<Copy>(\"shared\") {\n" +
                "    from(rootDir.resolve(\"beta/src/shared\"))\n    into(layout.buildDirectory.dir(\"shared\"))\n}\n",
            "beta/src/shared/limits/max.fixture" to "3",
            walkingTest("System.getProperty(\"shared\")"),
        ) { File(it, "beta/src/shared/limits/max.fixture").writeText("4") }

        assertForcedAndFailed(output, dir, "FixtureTest > maxIsThree()")
    }

    /**
     * A resource of the tested project lists the sibling's file, and the test reads the file from
     * that list: no class holds the path, the test is handed no path into the sibling, and no task it
     * depends on reads the file.
     */
    @Test
    fun `a sibling's file an own resource lists forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir, rootBuild(),
            maxFixture,
            "src/test/resources/fixtures.list" to "beta/src/fixtures/limits/max.fixture\n",
            "src/test/java/dev/sample/ListedTest.java" to """
                package dev.sample;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.nio.file.Paths;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ListedTest {
                    @Test void maxIsThree() throws Exception {
                        try (InputStream list = getClass().getResourceAsStream("/fixtures.list")) {
                            String path = new String(list.readAllBytes(), StandardCharsets.UTF_8).trim();
                            assertEquals("3", Files.readString(Paths.get(path)).trim());
                        }
                    }
                }
            """.trimIndent(),
        ) { changeMax(it) }

        assertForcedAndFailed(output, dir, "ListedTest > maxIsThree()")
    }

    /**
     * A source set of the tested project the tests never build compiles the sibling's sources, so the
     * sibling counts as on the classpath though no task the test depends on reads them and no path
     * into it reaches the test JVM. The test walks the checkout to the source, which alone is the
     * documented limit.
     */
    @Test
    fun `a sibling's sources a source set the tests do not build compiles in keep their edits`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            rootBuild() + "\nsourceSets.create(\"docs\") { java.srcDir(\"beta/src/main/java\") }\n",
            betaThing,
            "src/test/java/dev/sample/SourceTest.java" to """
                package dev.sample;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.Paths;
                import java.util.stream.Stream;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class SourceTest {
                    @Test void firstVersion() throws Exception {
                        try (Stream<Path> files = Files.walk(Paths.get(""))) {
                            Path found = files.filter(p -> p.endsWith("Thing.java")).findFirst().get();
                            assertTrue(Files.readString(found).contains("v1"));
                        }
                    }
                }
            """.trimIndent(),
        ) { changeThing(it) }

        assertContains(output, "SourceTest > firstVersion() FAILED")
    }

    /** A test asserting that its JVM's `FROM_BETA` variable is [expected], or unset for null. */
    private fun environmentTest(expected: String?) = "src/test/java/dev/sample/EnvironmentTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class EnvironmentTest {
            @Test void variable() { assertEquals(${if (expected == null) "null" else "\"$expected\""}, System.getenv("FROM_BETA")); }
        }
    """.trimIndent()

    private fun appendToBeta(dir: File, text: String) = File(dir, "beta/build.gradle.kts").appendText("$text\n")

    /** A `project(":")` block in the sibling's script sets a variable on the tested project's test task. */
    @Test
    fun `a sibling's build script that starts configuring the tested project forces the run`(@TempDir dir: File) {
        val output = selectingFails(dir, rootBuild(), environmentTest(null)) {
            appendToBeta(it, "project(\":\") { tasks.withType<Test>().configureEach { environment(\"FROM_BETA\", \"2\") } }")
        }

        assertForcedAndFailed(output, dir, "EnvironmentTest > variable()")
    }

    /** The base revision reaches across, the working tree no longer does. */
    @Test
    fun `a sibling's build script that stops configuring the tested project forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir, rootBuild(),
            "beta/build.gradle.kts" to "plugins { java }\n" +
                "project(\":\") { tasks.withType<Test>().configureEach { environment(\"FROM_BETA\", \"1\") } }\n",
            environmentTest("1"),
        ) { File(it, "beta/build.gradle.kts").writeText("plugins { java }\n") }

        assertForcedAndFailed(output, dir, "EnvironmentTest > variable()")
    }

    @Test
    fun `a sibling's build script that configures the root project's test task forces the run`(@TempDir dir: File) {
        val output = selectingFails(dir, rootBuild(), environmentTest(null)) {
            appendToBeta(it, "rootProject.tasks.withType<Test>().configureEach { environment(\"FROM_BETA\", \"2\") }")
        }

        assertForcedAndFailed(output, dir, "EnvironmentTest > variable()")
    }

    /** The sibling newly applies a convention plugin of the build's own, whose code configures every test task. */
    @Test
    fun `a sibling's build script that newly applies a plugin which configures every test task forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir, rootBuild(),
            "buildSrc/build.gradle" to "plugins { id 'groovy-gradle-plugin' }\n",
            "buildSrc/src/main/groovy/local.convention.gradle" to
                "rootProject.tasks.withType(Test).configureEach { environment 'FROM_BETA', '2' }\n",
            environmentTest(null),
        ) { File(it, "beta/build.gradle.kts").writeText("plugins { java; id(\"local.convention\") }\n") }

        assertForcedAndFailed(output, dir, "EnvironmentTest > variable()")
    }

    /**
     * The tested project's script hands the test the sibling's version in a variable no
     * fingerprint lists, so only its naming the sibling keeps the bump forcing.
     */
    @Test
    fun `a sibling's version the tested project's script hands its test forces the run when it changes`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            // Evaluated first, so the version is set by the time the test task is configured.
            rootBuild("environment(\"FROM_BETA\", project(\":beta\").version.toString())") + "\nevaluationDependsOn(\":beta\")\n",
            "beta/build.gradle.kts" to "plugins { java }\nversion = \"1\"\n",
            environmentTest("1"),
        ) { File(it, "beta/build.gradle.kts").writeText("plugins { java }\nversion = \"2\"\n") }

        assertForcedAndFailed(output, dir, "EnvironmentTest > variable()")
    }

    /** A test reading the first `.sql` file under a directory its class loader serves, as `v1`. */
    private val schemaTest = "src/test/java/dev/sample/SchemaTest.java" to """
        package dev.sample;
        import java.net.URL;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.Paths;
        import java.util.Collections;
        import java.util.stream.Stream;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class SchemaTest {
            @Test void firstVersion() throws Exception {
                for (URL root : Collections.list(getClass().getClassLoader().getResources(""))) {
                    if (!"file".equals(root.getProtocol())) continue;
                    try (Stream<Path> files = Files.walk(Paths.get(root.toURI()))) {
                        Path found = files.filter(p -> p.toString().endsWith(".sql")).findFirst().orElse(null);
                        if (found != null) { assertEquals("v1", Files.readString(found).trim()); return; }
                    }
                }
                throw new AssertionError("no schema");
            }
        }
    """.trimIndent()

    private val schema = "beta/src/main/resources/db/schema.sql" to "v1"

    private fun changeSchema(dir: File) = File(dir, schema.first).writeText("v2")

    /** The tested project's script copies the sibling's file into its test resources; the test finds it by extension. */
    @Test
    fun `a sibling's file the tested project's test resources copy in forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            rootBuild() + "\ntasks.processTestResources { from(project(\":beta\").file(\"src/main/resources/db\")) }\n",
            schema, schemaTest,
        ) { changeSchema(it) }

        assertForcedAndFailed(output, dir, "SchemaTest > firstVersion()")
    }

    /** A module holding a resource directory of the tested project is on its classpath; none of its files drops. */
    @Test
    fun `a sibling's resource directory the tested project adds to its own forces the run`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            rootBuild() + "\nsourceSets.test { resources.srcDir(\"beta/src/main/resources\") }\n",
            schema, schemaTest,
        ) { changeSchema(it) }

        assertForcedAndFailed(output, dir, "SchemaTest > firstVersion()")
    }
    private val betaThing = "beta/src/main/java/dev/beta/Thing.java" to
        "package dev.beta;\npublic class Thing { public String v() { return \"v1\"; } }\n"

    private fun changeThing(dir: File) =
        File(dir, betaThing.first).writeText(betaThing.second.replace("v1", "v2"))

    /** A test loading the sibling's class from the jar at [jar], a Java expression, and asserting its first version. */
    private fun jarLoadingTest(jar: String) = "src/test/java/dev/sample/BetaJarTest.java" to """
        package dev.sample;
        import java.net.URL;
        import java.net.URLClassLoader;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class BetaJarTest {
            @Test void firstVersion() throws Exception {
                URL url = Paths.get($jar).toUri().toURL();
                try (URLClassLoader loader = new URLClassLoader(new URL[] { url }, null)) {
                    Object thing = loader.loadClass("dev.beta.Thing").getDeclaredConstructor().newInstance();
                    assertEquals("v1", thing.getClass().getMethod("v").invoke(thing));
                }
            }
        }
    """.trimIndent()

    /**
     * The test task builds the sibling's jar and hands its path in a system property; the test
     * loads the sibling's class from it. Neither its classpath nor any class of it names the sibling.
     */
    @Test
    fun `a sibling's jar the test task builds and hands as a system property keeps its source edits`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            rootBuild("dependsOn(\":beta:jar\")\n    systemProperty(\"beta.jar\", file(\"beta/build/libs/beta.jar\").absolutePath)"),
            betaThing, jarLoadingTest("System.getProperty(\"beta.jar\")"),
        ) { changeThing(it) }

        assertContains(output, "BetaJarTest > firstVersion() FAILED")
    }

    @Test
    fun `a sibling's jar the test task builds and hands as an environment value keeps its source edits`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            rootBuild("dependsOn(\":beta:jar\")\n    environment(\"BETA_JAR\", file(\"beta/build/libs/beta.jar\").absolutePath)"),
            betaThing, jarLoadingTest("System.getenv(\"BETA_JAR\")"),
        ) { changeThing(it) }

        assertContains(output, "BetaJarTest > firstVersion() FAILED")
    }

    /** The sibling's jar is an agent the test JVM starts with; its `premain` sets what the test asserts. */
    @Test
    fun `a sibling's agent jar the test task hands with -javaagent keeps its source edits`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            rootBuild("dependsOn(\":beta:jar\")\n    jvmArgs(\"-javaagent:\" + file(\"beta/build/libs/beta.jar\").absolutePath)"),
            "beta/build.gradle.kts" to "plugins { java }\ntasks.jar { manifest { attributes(\"Premain-Class\" to \"dev.beta.Agent\") } }\n",
            "beta/src/main/java/dev/beta/Agent.java" to
                "package dev.beta;\npublic class Agent { public static void premain(String args) { System.setProperty(\"agent.value\", \"v1\"); } }\n",
            "src/test/java/dev/sample/AgentTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class AgentTest { @Test void premainRan() { assertEquals("v1", System.getProperty("agent.value")); } }
            """.trimIndent(),
        ) {
            val agent = File(it, "beta/src/main/java/dev/beta/Agent.java")
            agent.writeText(agent.readText().replace("\"v1\"", "\"v2\""))
        }

        assertContains(output, "AgentTest > premainRan() FAILED")
    }

    /**
     * A task of the sibling, which the test task depends on, copies its jar below the root's build
     * directory, outside the sibling's own; the path the test is handed holds no part of the sibling's.
     */
    @Test
    fun `a sibling's jar a task the test depends on copies outside the sibling keeps its source edits`(@TempDir dir: File) {
        val output = selectingFails(
            dir,
            rootBuild(
                "dependsOn(\":beta:fixtureJar\")\n" +
                    "    systemProperty(\"beta.jar\", layout.buildDirectory.file(\"fixtures/beta.jar\").get().asFile.absolutePath)",
            ),
            "beta/build.gradle.kts" to """
                plugins { java }
                tasks.register<Copy>("fixtureJar") {
                    from(tasks.jar)
                    into(rootProject.layout.buildDirectory.dir("fixtures"))
                }
            """.trimIndent(),
            betaThing, jarLoadingTest("System.getProperty(\"beta.jar\")"),
        ) { changeThing(it) }

        assertContains(output, "BetaJarTest > firstVersion() FAILED")
    }
}
