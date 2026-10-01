package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A test that runs a nested JUnit launcher, in a class loader it built from the test classpath as
 * spring-core-test's `@CompileWithForkedClassLoader` does, or in its own. The forked loader defines
 * the agent's classes a second time. Only the attached copy, serving the outer launcher, may
 * select, capture or write records: a nested launcher runs every test it discovers.
 */
class NestedLoaderFunctionalTest : FunctionalTestSupport() {

    private val nestedBuild = minimalBuild.replace(
        """testRuntimeOnly("org.junit.platform:junit-platform-launcher")""",
        """testImplementation("org.junit.platform:junit-platform-launcher")""",
    )

    /** Defines every class itself from the bytes the test classpath holds, like Spring's loader. */
    private val forkedLoader = "src/test/java/dev/sample/ForkedLoader.java" to """
        package dev.sample;
        import java.io.IOException;
        import java.io.InputStream;
        import java.net.URL;
        import java.util.Enumeration;
        final class ForkedLoader extends ClassLoader {
            private final ClassLoader source;
            private final boolean sharesJUnit;
            ForkedLoader(ClassLoader source, boolean sharesJUnit) {
                super(ClassLoader.getPlatformClassLoader());
                this.source = source;
                this.sharesJUnit = sharesJUnit;
            }
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (sharesJUnit && (name.startsWith("org.junit.") || name.startsWith("org.opentest4j.")
                        || name.startsWith("org.apiguardian."))) {
                    return Class.forName(name, false, source);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) {
                        try {
                            found = getParent().loadClass(name);
                        } catch (ClassNotFoundException notPlatform) {
                            found = findClass(name);
                        }
                    }
                    return found;
                }
            }
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                try (InputStream in = source.getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) {
                        throw new ClassNotFoundException(name);
                    }
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            @Override
            protected URL findResource(String name) {
                return source.getResource(name);
            }
            @Override
            protected Enumeration<URL> findResources(String name) throws IOException {
                return source.getResources(name);
            }
        }
    """.trimIndent()

    /**
     * Runs a test class through a nested launcher in a fresh [forkedLoader]: the launcher forked
     * with it, or shared with the test JVM as Spring's extension shares it.
     */
    private val nestedRun = "src/test/java/dev/sample/NestedRun.java" to """
        package dev.sample;
        import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
        import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
        import org.junit.platform.launcher.core.LauncherFactory;
        import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
        import org.junit.platform.launcher.listeners.TestExecutionSummary;
        public final class NestedRun {
            public static long[] run(String testClass) {
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                LauncherFactory.create().execute(
                    LauncherDiscoveryRequestBuilder.request().selectors(selectClass(testClass)).build(),
                    listener);
                TestExecutionSummary summary = listener.getSummary();
                return new long[] {summary.getTestsFoundCount(), summary.getTestsSucceededCount()};
            }
            static long[] forked(boolean sharesJUnit, String testClass) throws Exception {
                ClassLoader loader = new ForkedLoader(NestedRun.class.getClassLoader(), sharesJUnit);
                Thread thread = Thread.currentThread();
                ClassLoader previous = thread.getContextClassLoader();
                thread.setContextClassLoader(loader);
                try {
                    if (sharesJUnit) {
                        return run(testClass);
                    }
                    return (long[]) Class.forName(NestedRun.class.getName(), true, loader)
                        .getMethod("run", String.class).invoke(null, testClass);
                } finally {
                    thread.setContextClassLoader(previous);
                }
            }
        }
    """.trimIndent()

    /** Reaches Beta, and runs AlphaTest nested, writing what each nested launcher found and passed. */
    private val nestingTest = "src/test/java/dev/sample/NestingTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.Paths;
        class NestingTest {
            @Test void forkedLauncher() throws Exception {
                assertEquals(3, new Beta().thrice(1));
                record("forked-launcher", NestedRun.forked(false, "dev.sample.AlphaTest"));
            }
            @Test void sharedLauncher() throws Exception {
                assertEquals(3, new Beta().thrice(1));
                record("shared-launcher", NestedRun.forked(true, "dev.sample.AlphaTest"));
            }
            @Test void sameLoaderLauncher() throws Exception {
                assertEquals(3, new Beta().thrice(1));
                record("same-loader-launcher", NestedRun.run("dev.sample.AlphaTest"));
            }
            private static void record(String name, long[] counts) throws Exception {
                Path out = Paths.get("build", "nested", name + ".txt");
                Files.createDirectories(out.getParent());
                Files.write(out, (counts[0] + " " + counts[1]).getBytes());
            }
        }
    """.trimIndent()

    private val outerTests = setOf(
        "dev.sample.AlphaTest.passes",
        "dev.sample.BetaTest.passes",
        "dev.sample.NestingTest.forkedLauncher",
        "dev.sample.NestingTest.sharedLauncher",
        "dev.sample.NestingTest.sameLoaderLauncher",
    )

    /** Captured, then Beta changed: the outer run selects BetaTest and NestingTest, not AlphaTest. */
    private fun capturedWithBetaChanged(dir: File): String {
        build(
            dir, "build.gradle.kts" to nestedBuild, oneClass, oneTest, secondClass, secondTest,
            forkedLoader, nestedRun, nestingTest, classOrderByName,
        )
        committed(dir)
        val capture = runner(dir, "test").build().output
        File(dir, "build/test-results").deleteRecursively()
        File(dir, "build/nested").deleteRecursively()
        changeBeta(dir)
        return capture
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    /** `dev.sample.Class.method` for each test row of a decision record. */
    private fun decidedTests(record: File): Set<String> = record.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("[method:") }
        .map { line -> testName(line.substringBefore('\t')) }
        .toSet()

    private fun testName(id: String): String {
        val type = Regex("""\[class:([^\]]+)]""").find(id)!!.groupValues[1]
        val method = Regex("""\[method:([^\](]+)""").find(id)!!.groupValues[1]
        return "$type.$method"
    }

    @Test
    fun `a nested launcher runs every test it discovers, in this class loader or another`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()

        assertEquals(setOf("dev.sample.BetaTest", "dev.sample.NestingTest"), ranTests(dir))
        // AlphaTest was deselected at the outer level; inside the nested launchers it must still
        // run, since what a nested launcher runs is part of the outer test that is running.
        for (shape in listOf("forked-launcher", "shared-launcher", "same-loader-launcher")) {
            assertEquals("1 1", File(dir, "build/nested/$shape.txt").readText(), "the $shape nested run")
        }
    }

    @Test
    fun `the decision record is the outer launcher's alone, whole and single-writer`(@TempDir dir: File) {
        val capture = capturedWithBetaChanged(dir)

        val select = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        for (output in listOf(capture, select)) {
            assertFalse(output.contains("could not write"), "a decision write was lost:\n$output")
            assertFalse(output.contains("NoSuchFileException"), "a decision write collided:\n$output")
        }
        val parts = mapDir(dir).listFiles { f: File ->
            f.name.startsWith(AgentContract.DECISIONS_FILE + ".") &&
                f.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)
        }!!.toList()
        assertEquals(1, parts.size, "one writer, one part: ${parts.map(File::getName)}")
        assertEquals(outerTests, decidedTests(parts.single()))
        assertEquals(outerTests, decidedTests(File(mapDir(dir), AgentContract.DECISIONS_FILE)))
        assertEquals("1 of 1", decisionNotes(dir)[AgentContract.WRITER_NOTE])
        assertEquals(AgentContract.RUN_NARROWED, decisionNotes(dir)[AgentContract.OUTCOME_NOTE])
    }

    @Test
    fun `capture records each outer test once, and the nested runs' coverage on the test that ran them`(
        @TempDir dir: File,
    ) {
        capturedWithBetaChanged(dir)

        val records = File(mapDir(dir), "coverage.tsv").readLines()
            .filter { it.contains("[method:") && !it.contains("[yoriwake:") }
        val tests = records.map { line -> testName(line.split('\t').first { it.contains("[method:") }) }
        assertEquals(outerTests, tests.toSet())
        assertEquals(outerTests.size, tests.size, "a test was recorded twice: $tests")
        for (nesting in records.filter { it.contains("NestingTest") }) {
            val classes = nesting.split('\t')[2].split(',')
            assertTrue("dev.sample.Alpha" in classes, "the nested run's coverage was lost: $nesting")
        }
    }
}
