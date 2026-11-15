package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Attempts on a changed class that selects nothing because no test executed it, loaded it or names
 * it. A whole capture lists the classes its test JVMs loaded, and the rule narrows only on a class
 * absent from that list. Each attempt here makes a test depend on a class in a way the list could
 * miss: from a child JVM, through a loader gone before the JVM ends, by a name in a resource, on a
 * path only another environment takes, from a test the capture did not run, in a JVM that died.
 */
class AbsenceAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    /** The class nothing executes. `main` is for a child JVM; no test JVM calls it. */
    private val orphan = source(
        "src/main/java/dev/sample/Orphan.java",
        """
        package dev.sample;
        public class Orphan {
            public int once() { return 1; }
            public static void main(String[] args) { System.out.print(new Orphan().once()); }
        }
        """,
    )

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    /** [minimalBuild] handing the test JVM the main classes' directory, with [extra] inside the task's block. */
    private fun buildWith(extra: String = "") = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        tasks.test {
            useJUnitPlatform()
            systemProperty("main.classes", sourceSets.main.get().java.destinationDirectory.get().asFile.absolutePath)
        """.trimIndent() + "\n" + extra.trimIndent().prependIndent("    ") + "\n}",
    )

    private fun changeOrphan(dir: File) {
        val file = File(dir, orphan.first)
        file.writeText(file.readText().replace("return 1;", "return 2;"))
    }

    /** Gives Orphan a method, which a test that only inspects the class can see. */
    private fun addStrict(dir: File) {
        val file = File(dir, orphan.first)
        file.writeText(file.readText().replace("public int once()", "public boolean strict() { return true; } public int once()"))
    }

    // Track: code reading. Boundary: the list holds what the test JVM loaded, and a child JVM is
    // another process. The child's start makes the test JVM unobserved from that point, which
    // counts as a touch of every class, so the changed class is not one nothing touched.
    @Test
    fun `a class only a child JVM of a test loads forces a full run`(@TempDir dir: File) {
        val child = source(
            "src/test/java/dev/sample/A1ChildTest.java",
            """
            package dev.sample;
            import java.nio.charset.StandardCharsets;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1ChildTest {
                @Test void printsOne() throws Exception {
                    Process process = new ProcessBuilder(
                        System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("main.classes"), "dev.sample.Orphan")
                        .redirectErrorStream(true).start();
                    String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(0, process.waitFor());
                    assertEquals("1", printed.trim());
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ChildTest", Aim.Forced("no-coverage-for-changed"),
            listOf(orphan, other, first, child, classOrderByName),
            buildScript = buildWith(),
        ) { changeOrphan(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the list is taken from the classes the JVM still holds when
    // it is asked, and a class whose loader was closed and collected is no longer among them. A
    // class a loader of the test's own defines is recorded as touched when it is defined, so the
    // changed class is not one nothing touched, whatever the list holds.
    @Test
    fun `a class loaded through a loader closed and collected before the JVM ends forces a full run`(@TempDir dir: File) {
        val collected = source(
            "src/test/java/dev/sample/A1CollectedLoaderTest.java",
            """
            package dev.sample;
            import java.lang.ref.WeakReference;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.file.Paths;
            import java.util.Arrays;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1CollectedLoaderTest {
                @Test void noStrictMode() throws Exception {
                    URL classes = Paths.get(System.getProperty("main.classes")).toUri().toURL();
                    URLClassLoader loader = new URLClassLoader(new URL[] { classes }, null);
                    Class<?> type = loader.loadClass("dev.sample.Orphan");
                    boolean strict = Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals("strict"));
                    WeakReference<Class<?>> held = new WeakReference<>(type);
                    type = null;
                    loader.close();
                    loader = null;
                    for (int i = 0; i < 20 && held.get() != null; i++) { System.gc(); Thread.sleep(50); }
                    assertFalse(strict);
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1CollectedLoaderTest", Aim.Forced("no-coverage-for-changed"),
            listOf(orphan, other, first, collected, classOrderByName),
            buildScript = buildWith(),
        ) { addStrict(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a class a resource names can be loaded with no bytecode edge
    // to it. A provider-configuration file names this one, and the test that lists the providers'
    // types loads it without running it, so it is in the list as well; either keeps the change
    // from selecting nothing.
    @Test
    fun `a class a provider-configuration file names forces a full run`(@TempDir dir: File) {
        val spi = source("src/main/java/dev/sample/Spi.java", "package dev.sample;\npublic interface Spi { int once(); }")
        val provider = source(
            orphan.first,
            "package dev.sample;\npublic class Orphan implements Spi { public int once() { return 1; } }",
        )
        val registration = source("src/main/resources/META-INF/services/dev.sample.Spi", "dev.sample.Orphan\n")
        val types = source(
            "src/test/java/dev/sample/A1ProviderTypesTest.java",
            """
            package dev.sample;
            import java.util.Arrays;
            import java.util.ServiceLoader;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1ProviderTypesTest {
                @Test void noStrictMode() {
                    assertFalse(ServiceLoader.load(Spi.class).stream().map(ServiceLoader.Provider::type)
                            .anyMatch(type -> Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals("strict"))));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ProviderTypesTest", Aim.Forced("no-coverage-for-changed"),
            listOf(spi, provider, registration, other, first, types, classOrderByName),
        ) { addStrict(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the list says what the capture's JVMs loaded in the capture's
    // environment. A test that loads the class only under a variable the capture did not have is
    // absent from it. The variable is one the build lists, so the run with it set is in another
    // environment and runs every test. A variable nobody listed is not compared, here as anywhere.
    @Test
    fun `a class a test loads only under a listed variable the capture lacked runs everything`(@TempDir dir: File) {
        val gated = source(
            "src/test/java/dev/sample/A1GatedTest.java",
            """
            package dev.sample;
            import java.util.Arrays;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1GatedTest {
                @Test void noStrictMode() throws Exception {
                    if (!"dirty".equals(System.getenv("TREE_STATE"))) return;
                    assertFalse(Arrays.stream(Class.forName("dev.sample.Orphan").getMethods())
                            .anyMatch(m -> m.getName().equals("strict")));
                }
            }
            """,
        )
        val treeState = """
            val treeState = providers.exec { commandLine("git", "status", "--porcelain") }
                .standardOutput.asText.map { if (it.isBlank()) "clean" else "dirty" }
        """.trimIndent()
        val result = attempt(
            dir, "dev.sample.A1GatedTest", Aim.Forced("environment-changed"),
            listOf(orphan, other, first, gated, classOrderByName),
            buildScript = treeState + "\n" + buildWith("environment(\"TREE_STATE\", treeState.get())") +
                "\nyoriwake { environmentInclude.add(\"TREE_STATE\") }",
        ) { addStrict(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a test the capture did not run loaded nothing, so a class
    // only it loads is absent from the list. The map holds no passing record of that test either,
    // and a test the map does not know to pass runs whatever changed.
    @Test
    fun `a test a condition disabled at the capture runs when the class only it loads changes`(@TempDir dir: File) {
        val conditional = source(
            "src/test/java/dev/sample/A1ConditionalTest.java",
            """
            package dev.sample;
            import java.util.Arrays;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.condition.EnabledIf;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1ConditionalTest {
                static boolean enabled() { return java.nio.file.Files.exists(java.nio.file.Paths.get(System.getProperty("gate"))); }
                @Test @EnabledIf("enabled") void noStrictMode() throws Exception {
                    assertFalse(Arrays.stream(Class.forName("dev.sample.Orphan").getMethods())
                            .anyMatch(m -> m.getName().equals("strict")));
                }
            }
            """,
        )
        build(
            dir,
            "build.gradle.kts" to buildWith(
                "systemProperty(\"gate\", layout.buildDirectory.file(\"gate\").get().asFile.absolutePath)",
            ),
            orphan, other, first, conditional, classOrderByName,
        )
        ignoreBuildOutputs(dir)
        git(dir, "init")
        commit(dir, "base")
        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertFalse("dev.sample.Orphan" in File(mapDir, "loaded.txt").readLines(), "the capture loaded Orphan")

        // The gate is a file under the build directory: no change to the tree or the environment.
        File(dir, "build/gate").writeText("open")
        addStrict(dir)
        File(dir, "build/test-results").deleteRecursively()
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").run().output

        assertEquals(false, passedIn(File(dir, "build/test-results/test/TEST-dev.sample.A1ConditionalTest.xml")), output)
    }

    // Track: code reading. Boundary: a test JVM that dies wrote no list of what it loaded. A run
    // with such a JVM does not date the map and takes no list from the JVMs that did finish, so a
    // class the dead one loaded is not missing from a list that claims to be whole.
    @Test
    fun `a capture one of whose test JVMs died writes no list, and the class it loaded runs its test`(@TempDir dir: File) {
        val dying = source(
            "src/test/java/dev/sample/A1DyingTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.util.Arrays;
            import org.junit.jupiter.api.AfterAll;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1DyingTest {
                @Test void noStrictMode() throws Exception {
                    assertFalse(Arrays.stream(Class.forName("dev.sample.Orphan").getMethods())
                            .anyMatch(m -> m.getName().equals("strict")));
                }
                @AfterAll static void die() throws Exception {
                    Path marker = Paths.get(System.getProperty("die"));
                    if (Files.deleteIfExists(marker)) Runtime.getRuntime().halt(1);
                }
            }
            """,
        )
        build(
            dir,
            "build.gradle.kts" to buildWith(
                """
                setForkEvery(1)
                systemProperty("die", layout.buildDirectory.file("die").get().asFile.absolutePath)
                """,
            ),
            orphan, other, first, dying, classOrderByName,
        )
        ignoreBuildOutputs(dir)
        git(dir, "init")
        commit(dir, "base")
        File(dir, "build").mkdirs()
        File(dir, "build/die").writeText("once")

        val crashed = runner(dir, "test").buildAndFail().output

        assertFalse(File(dir, "build/die").exists(), "the test JVM did not reach the point where it dies; output: $crashed")
        val mapDirs = File(dir, ".gradle/yoriwake").listFiles().orEmpty().filter(File::isDirectory)
        assertFalse(mapDirs.any { File(it, "loaded.txt").exists() }, "a run with a dead test JVM wrote a list; output: $crashed")

        addStrict(dir)
        File(dir, "build/test-results").deleteRecursively()
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").run().output

        assertEquals(false, passedIn(File(dir, "build/test-results/test/TEST-dev.sample.A1DyingTest.xml")), output)
        assertContains(ranTests(dir), "dev.sample.A0UnrelatedTest", "with no list, the change did not run every test")
    }
}
