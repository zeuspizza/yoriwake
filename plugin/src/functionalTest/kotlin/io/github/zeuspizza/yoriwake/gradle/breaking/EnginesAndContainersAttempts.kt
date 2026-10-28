package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.gradle.FunctionalGradle
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on the engines and containers a test runs under: the Platform Suite engine and
 * Kotest on the Platform, Jupiter's own lifecycle around a test, and parallel execution. Each
 * engine names its tests in its own id shape and reports its own events, and selection must
 * decide each test by the id capture recorded for it.
 */
class EnginesAndContainersAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        """
        package dev.sample;
        public class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }
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

    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    /**
     * Codec's source copied to a file under the build directory that is no source file: a test
     * reading it depends on Codec while neither loading it nor reading a file that dates it.
     */
    private val copyCodec = """
        val copyCodec = tasks.register<Copy>("copyCodec") {
            from("src/main/java/dev/sample/Codec.java")
            rename { "codec.txt" }
            into(layout.buildDirectory.dir("copies"))
        }
    """.trimIndent()

    private val readsCopy = """
        dependsOn(copyCodec)
        systemProperty("codec.copy", layout.buildDirectory.file("copies/codec.txt").get().asFile.absolutePath)
    """.trimIndent()

    /** A Jupiter test that fails once Codec's source no longer reverses plainly. */
    private fun copyReader(name: String, annotations: String = "", body: String = "") = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        $annotations
        class $name {
            @Test void readsTheCopy() throws Exception {
                $body
                assertTrue(Files.readString(Paths.get(System.getProperty("codec.copy"))).contains("reverse().toString()"));
            }
        }
        """,
    )

    private val suiteBuild = """
        plugins {
            java
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        dependencies {
            testImplementation(platform("org.junit:junit-bom:5.11.4"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            testImplementation("org.junit.platform:junit-platform-suite")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }
    """.trimIndent()

    // Track: code reading. Boundary: the filter judges every descriptor the launcher discovered, and
    // capture keys each record by the id the listener reports. Under the Platform Suite engine both
    // are the suite's: the test's class and method sit beneath [suite:...]/[engine:junit-jupiter],
    // and only an exact match of that id against the map lets selection find the record.
    @Test
    fun `a test the suite engine runs is selected by the record capture keyed under the suite`(@TempDir dir: File) {
        val suite = source(
            "src/test/java/dev/sample/AllSuite.java",
            """
            package dev.sample;
            import org.junit.platform.suite.api.SelectClasses;
            import org.junit.platform.suite.api.Suite;
            @Suite
            @SelectClasses({A0UnrelatedTest.class, A1SuiteTargetTest.class, A9CodecTest.class})
            public class AllSuite {}
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A1SuiteTargetTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1SuiteTargetTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1SuiteTargetTest", Aim.Row("reaches-change"),
            listOf(codec, other, first, target, codecTest, suite, classOrderByName),
            buildScript = suiteBuild + "\ntasks.test { useJUnitPlatform { includeEngines(\"junit-platform-suite\") } }\n",
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a test's window opens when the listener hears it start, but
    // Jupiter builds the test instance before that, while preparing the method. What a field
    // initialiser runs lands in the window between tests, which belongs to the classes on either
    // side of it, so only the class-scoped record carries the test's use of Codec.
    @Test
    fun `a test that uses the change only in a field initialiser is held by its class's setup`(@TempDir dir: File) {
        val target = source(
            "src/test/java/dev/sample/A1FieldTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1FieldTest {
                private final String encoded = new Codec().encode("abc");
                @Test void reverses() { assertEquals("cba", encoded); }
            }
            """,
        )
        val build = minimalBuild.replace("tasks.test { useJUnitPlatform() }", "tasks.test { useJUnitPlatform(); forkEvery = 1 }")
        val result = attempt(
            dir, "dev.sample.A1FieldTest", Aim.Row("class-setup"),
            listOf(codec, other, first, target, codecTest, classOrderByName),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a test is known to the map only by its exact id, and Jupiter
    // names an inherited test by the class that runs it. Renaming a test in an abstract base class
    // gives the unchanged subclass a test the map has never seen. (TestNG on the Platform cannot
    // stand here: its engine instantiates test classes during discovery, so any edit to the base
    // class touches startup coverage and forces the run first.)
    @Test
    fun `a test renamed in its abstract base class is held as a test the map never saw`(@TempDir dir: File) {
        val base = source(
            "src/test/java/dev/sample/ReversingBase.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            abstract class ReversingBase {
                @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); }
            }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A1InheritingTest.java",
            "package dev.sample;\nclass A1InheritingTest extends ReversingBase {}",
        )
        val result = attempt(
            dir, "dev.sample.A1InheritingTest", Aim.Row("not-in-map"),
            listOf(codec, other, first, base, target),
            buildScript = minimalBuild.replace("tasks.test { useJUnitPlatform() }", "tasks.test { useJUnitPlatform(); forkEvery = 1 }"),
        ) {
            val file = File(it, base.first)
            file.writeText(
                file.readText().replace(
                    "void reverses() { assertEquals(\"cba\", new Codec().encode(\"abc\")); }",
                    "void reversesBack() { assertEquals(\"abc!\", new Codec().encode(\"cba\")); }",
                ),
            )
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: an engine that runs nothing once one of its tests is left out is
    // never asked to leave one out, and which engine runs a test is read from the engine segment of
    // its id. Kotest yields each spec whole at discovery, with no class or method segment, so the
    // spec itself is the row, judged before the map is consulted at all.
    @Test
    fun `a Kotest spec the change cannot reach is held because its engine runs everything`(@TempDir dir: File) {
        val build = """
            plugins {
                kotlin("jvm") version "2.1.20"
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
                testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            kotlin { jvmToolchain(21) }
        """.trimIndent() + "\n" + copyCodec + "\n" + """
            tasks.test {
                useJUnitPlatform()
                forkEvery = 1
                systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
                systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
            """.trimIndent() + "\n" + readsCopy + "\n}\n"
        // A Kotlin build in which no class calls an inline function carries no SMAP, and selection
        // then refuses; that refusal is not what this attempt is about.
        val same = source("src/main/kotlin/dev/sample/Same.kt", "package dev.sample\n\ninline fun same(n: Int) = n\n")
        val twice = source("src/main/kotlin/dev/sample/Twice.kt", "package dev.sample\n\nclass Twice { fun of(n: Int) = same(n) * 2 }\n")
        val spec = source(
            "src/test/kotlin/dev/sample/A1KotestSpec.kt",
            """
            package dev.sample

            import io.kotest.core.spec.style.StringSpec
            import io.kotest.matchers.string.shouldContain
            import java.io.File

            class A1KotestSpec : StringSpec({
                "reads the copy" { File(System.getProperty("codec.copy")).readText() shouldContain "reverse().toString()" }
            })
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1KotestSpec", Aim.Row("engine-runs-everything"),
            listOf(codec, other, same, twice, first, spec, codecTest),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: in-JVM parallelism is refused where the build configures it
    // (a system property, a JVM argument, junit-platform.properties, TestNG's options). A suite's
    // own configuration parameter is none of these, so capture runs while tests overlap; then only
    // the session's check that a starting test is inside every open one stands between two blended
    // records and a map that believes them.
    @Test
    fun `a test a suite runs in parallel is held as not known to pass`(@TempDir dir: File) {
        val suite = source(
            "src/test/java/dev/sample/ParallelSuite.java",
            """
            package dev.sample;
            import org.junit.platform.suite.api.ConfigurationParameter;
            import org.junit.platform.suite.api.SelectClasses;
            import org.junit.platform.suite.api.Suite;
            @Suite
            @SelectClasses({A1OverlapTargetTest.class, A2PartnerTest.class, A9CodecTest.class})
            @ConfigurationParameter(key = "junit.jupiter.execution.parallel.enabled", value = "true")
            @ConfigurationParameter(key = "junit.jupiter.execution.parallel.mode.classes.default", value = "concurrent")
            public class ParallelSuite {}
            """,
        )
        // The unrelated test runs in a suite of its own, in a JVM of its own.
        val firstSuite = source(
            "src/test/java/dev/sample/FirstSuite.java",
            """
            package dev.sample;
            import org.junit.platform.suite.api.SelectClasses;
            import org.junit.platform.suite.api.Suite;
            @Suite
            @SelectClasses(A0UnrelatedTest.class)
            public class FirstSuite {}
            """,
        )
        val rendezvous = source(
            "src/test/java/dev/sample/Rendezvous.java",
            """
            package dev.sample;
            import java.util.concurrent.CyclicBarrier;
            import java.util.concurrent.TimeUnit;
            final class Rendezvous {
                static final CyclicBarrier BOTH = new CyclicBarrier(2);
                static void meet() throws Exception { BOTH.await(60, TimeUnit.SECONDS); }
            }
            """,
        )
        val partner = source(
            "src/test/java/dev/sample/A2PartnerTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            class A2PartnerTest { @Test void meets() throws Exception { Rendezvous.meet(); } }
            """,
        )
        val target = copyReader("A1OverlapTargetTest", body = "Rendezvous.meet();")
        val build = suiteBuild + "\n" + copyCodec + "\n" + """
            tasks.test {
                useJUnitPlatform { includeEngines("junit-platform-suite") }
                forkEvery = 1
            """.trimIndent() + "\n" + readsCopy + "\n}\n"
        val result = attempt(
            dir, "dev.sample.A1OverlapTargetTest", Aim.Row("not-known-to-pass"),
            listOf(codec, other, first, rendezvous, partner, target, codecTest, suite, firstSuite),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the filter judges a test by its own record and a container by
    // the records beneath it. A test factory is a container at discovery with nothing beneath it;
    // its dynamic tests, and the containers they sit in, exist only once it runs, so their records
    // are the only ones that reach the change and none of them is discovered.
    @Test
    fun `a dynamic test nested in a dynamic container is selected through its factory`(@TempDir dir: File) {
        val target = source(
            "src/test/java/dev/sample/A1FactoryTest.java",
            """
            package dev.sample;
            import java.util.stream.Stream;
            import org.junit.jupiter.api.DynamicNode;
            import org.junit.jupiter.api.TestFactory;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
            import static org.junit.jupiter.api.DynamicTest.dynamicTest;
            class A1FactoryTest {
                @TestFactory Stream<DynamicNode> codecs() {
                    return Stream.of(dynamicContainer("codec", Stream.of(
                        dynamicTest("reverses", () -> assertEquals("cba", new Codec().encode("abc"))))));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1FactoryTest", Aim.Row("reaches-change"),
            listOf(codec, other, first, target, codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: Spock runs setupSpec before any feature starts, so no feature's
    // own record holds what it runs. A feature that only reads a @Shared field setupSpec filled
    // depends on the change through it. In a JVM of its own, the spec's setup is the JVM's startup.
    @Test
    fun `a Spock feature that reads what setupSpec computed in its own JVM runs every test as startup coverage`(@TempDir dir: File) {
        val build = """
            plugins {
                groovy
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.spockframework:spock-core:2.3-groovy-4.0")
                testImplementation("org.apache.groovy:groovy:4.0.22")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            // Groovy 4.0.22 cannot read Java 25 class files.
            java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
            tasks.test { useJUnitPlatform(); forkEvery = 1 }
        """.trimIndent()
        fun spec(name: String, body: String) = source(
            "src/test/groovy/dev/sample/$name.groovy",
            """
            package dev.sample
            import spock.lang.Shared
            import spock.lang.Specification
            class $name extends Specification {
            $body
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1SetupSpec", Aim.Forced("startup-coverage"),
            listOf(
                codec, other,
                spec("A0UnrelatedSpec", "def \"runs first\"() { expect: new Other().one() == 1 }"),
                spec(
                    "A1SetupSpec",
                    """
                    @Shared String encoded
                    def setupSpec() { encoded = new Codec().encode("abc") }
                    def "reverses"() { expect: encoded == "cba" }
                    """.trimIndent(),
                ),
                spec("A9CodecSpec", "def \"reverses\"() { expect: new Codec().encode(\"abc\") == \"cba\" }"),
            ),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: engineRunsEverything finds Kotest by its engine segment at any
    // depth of the id. Run through the Platform Suite engine, a spec's id opens with the suite's
    // engine and holds Kotest's only further in: a spec the change cannot reach must still be kept.
    @Test
    fun `a Kotest spec a suite runs is held because its engine runs everything`(@TempDir dir: File) {
        val build = """
            plugins {
                kotlin("jvm") version "2.1.20"
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testImplementation("org.junit.platform:junit-platform-suite")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            kotlin { jvmToolchain(21) }
        """.trimIndent() + "\n" + copyCodec + "\n" + """
            tasks.test {
                useJUnitPlatform { includeEngines("junit-platform-suite") }
                forkEvery = 1
                systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
                systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
            """.trimIndent() + "\n" + readsCopy + "\n}\n"
        val same = source("src/main/kotlin/dev/sample/Same.kt", "package dev.sample\n\ninline fun same(n: Int) = n\n")
        val twice = source("src/main/kotlin/dev/sample/Twice.kt", "package dev.sample\n\nclass Twice { fun of(n: Int) = same(n) * 2 }\n")
        val spec = source(
            "src/test/kotlin/dev/sample/A1KotestSpec.kt",
            """
            package dev.sample

            import io.kotest.core.spec.style.StringSpec
            import io.kotest.matchers.string.shouldContain
            import java.io.File

            class A1KotestSpec : StringSpec({
                "reads the copy" { File(System.getProperty("codec.copy")).readText() shouldContain "reverse().toString()" }
            })
            """,
        )
        fun suite(name: String, engine: String, selected: String) = source(
            "src/test/kotlin/dev/sample/$name.kt",
            """
            package dev.sample
            import org.junit.platform.suite.api.IncludeEngines
            import org.junit.platform.suite.api.SelectClasses
            import org.junit.platform.suite.api.Suite
            @Suite
            @IncludeEngines("$engine")
            @SelectClasses($selected::class)
            class $name
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1KotestSpec", Aim.Row("engine-runs-everything"),
            listOf(
                codec, other, same, twice, first, spec, codecTest,
                suite("FirstSuite", "junit-jupiter", "A0UnrelatedTest"),
                suite("KotestSuite", "kotest", "A1KotestSpec"),
                suite("CodecSuite", "junit-jupiter", "A9CodecTest"),
            ),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the Platform filter includes a container that has children and
    // judges each child on its own record; a leaf container is judged through the records beneath
    // it. A JUnit 4 runner on the vintage engine is a container whose children are the tests its
    // description lists at discovery, but a runner may report a test at run time its description
    // never listed (vintage registers it as dynamic). Only that test's record reaches the change.
    @Test
    fun `a test a vintage runner reports only at run time is selected through its runner`(@TempDir dir: File) {
        // Gradle 9 files the listed test under the runner's class beside the one it reports at run
        // time, and a target's result must hold one test case.
        assumeTrue(FunctionalGradle.version == "8.14", "runs in the 8.14 suite only")
        val runner = source(
            "src/test/java/dev/sample/GrowingRunner.java",
            """
            package dev.sample;
            import org.junit.runner.Description;
            import org.junit.runner.Runner;
            import org.junit.runner.manipulation.Filter;
            import org.junit.runner.manipulation.Filterable;
            import org.junit.runner.notification.Failure;
            import org.junit.runner.notification.RunNotifier;
            public class GrowingRunner extends Runner implements Filterable {
                private final Class<?> type;
                private final Description placeholder = Description.createTestDescription(Other.class, "placeholder");
                private boolean placeholderKept = true;
                public GrowingRunner(Class<?> type) { this.type = type; }
                @Override public Description getDescription() {
                    Description suite = Description.createSuiteDescription(type);
                    if (placeholderKept) suite.addChild(placeholder);
                    return suite;
                }
                @Override public void filter(Filter filter) { placeholderKept = filter.shouldRun(placeholder); }
                @Override public void run(RunNotifier notifier) {
                    if (placeholderKept) {
                        notifier.fireTestStarted(placeholder);
                        new Other().one();
                        notifier.fireTestFinished(placeholder);
                    }
                    Description grown = Description.createTestDescription(type, "reverses");
                    notifier.fireTestStarted(grown);
                    try {
                        if (!"cba".equals(new Codec().encode("abc"))) throw new AssertionError("not reversed");
                    } catch (Throwable failure) {
                        notifier.fireTestFailure(new Failure(grown, failure));
                    }
                    notifier.fireTestFinished(grown);
                }
            }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A1GrowingTest.java",
            "package dev.sample;\n@org.junit.runner.RunWith(GrowingRunner.class)\npublic class A1GrowingTest {}",
        )
        val build = minimalBuild.replace(
            "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")",
            "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n" +
                "testImplementation(\"junit:junit:4.13.2\")\ntestRuntimeOnly(\"org.junit.vintage:junit-vintage-engine\")",
        ).replace("tasks.test { useJUnitPlatform() }", "tasks.test { useJUnitPlatform(); forkEvery = 1 }")
        val result = attempt(
            dir, "dev.sample.A1GrowingTest", Aim.Row("CLASS_RUNS_WHOLE"),
            listOf(codec, other, first, runner, target, codecTest),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
