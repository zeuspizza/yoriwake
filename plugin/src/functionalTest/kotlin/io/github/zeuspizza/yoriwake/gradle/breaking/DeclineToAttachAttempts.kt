package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.gradle.FunctionalGradle
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on where yoriwake declines to attach: a Gradle too old, Isolated Projects, a project
 * with no host plugin, a task whose JaCoCo is off, a multiplatform module it cannot scope, sources
 * it derives no packages from. A declined task must run every test; one it attaches to must have a
 * scope that covers what its tests reach.
 */
class DeclineToAttachAttempts : DeclinedAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

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

    private val alpha = source(
        "src/main/java/dev/sample/Alpha.java",
        """
        package dev.sample;
        public class Alpha {
            public int twice(int n) { return n * 2; }
            public String name() { return "alpha"; }
        }
        """,
    )

    private val alphaTest = source(
        "src/test/java/dev/sample/A2AlphaTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A2AlphaTest { @Test void doubles() { assertEquals(6, new Alpha().twice(3)); } }
        """,
    )

    private val alphaNameTest = source(
        "src/test/java/dev/sample/A3AlphaNameTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A3AlphaNameTest { @Test void named() { assertEquals("alpha", new Alpha().name()); } }
        """,
    )

    private val sources = listOf(other, first, alpha, alphaTest, alphaNameTest, classOrderByName)

    private fun breakAlpha(dir: File, path: String = alpha.first) {
        val file = File(dir, path)
        file.writeText(file.readText().replace("n * 2", "n * 2 + 1"))
    }

    // Track: code reading. Boundary: declineOldGradle compares the running Gradle's base version
    // with 8.14 before any host-plugin hook, so a release just below it declines whole rather than
    // reading around missing API.
    @Test
    fun `a build on the Gradle release before 8_14 runs every test`(@TempDir dir: File) {
        assumeTrue(FunctionalGradle.version == "8.14", "runs in the 8.14 suite only")
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("gradle-too-old"), sources,
            gradle = { args -> runner(dir, *args).withGradleVersion("8.13").withTestKitDir(FunctionalGradle.testKitDir("8.13")) },
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: declineIsolatedProjects asks Gradle's own build features, not a
    // property name, and declines before any cross-project read. A single-project build switching
    // the feature on in its gradle.properties, where no cross-project read would even fail, must
    // decline all the same.
    @Test
    fun `a single-project build with Isolated Projects on runs every test`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("isolated-projects"),
            sources + ("gradle.properties" to "org.gradle.unsafe.isolated-projects=true\norg.gradle.isolated-projects=true\n"),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: yoriwake attaches through the `java` plugin or an Android one.
    // `java-base` gives a build source sets and compilation without `java`; a test task wired by hand
    // there is one yoriwake never configures, so it must be declined by name and run in full.
    @Test
    fun `a test task a java-base build wires by hand runs every test`(@TempDir dir: File) {
        val javaBase = """
            plugins {
                `java-base`
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            val main = sourceSets.create("main")
            val tests = sourceSets.create("test") {
                compileClasspath += main.output
                runtimeClasspath += main.output
            }
            dependencies {
                "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
                "testImplementation"("org.junit.jupiter:junit-jupiter")
                "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
            }
            tasks.register<Test>("test") {
                useJUnitPlatform()
                testClassesDirs = tests.output.classesDirs
                classpath = tests.runtimeClasspath
            }
        """.trimIndent()
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("no-host-plugin"), sources, buildScript = javaBase,
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: whether the task's JaCoCo is on is read when yoriwake
    // configures the task, once every project is evaluated. A build script that switches it off in
    // its own afterEvaluate, the latest hook that runs before that, must be declined.
    @Test
    fun `a task whose jacoco a build script switches off in afterEvaluate runs every test`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("jacoco-disabled"), sources,
            buildScript = minimalBuild + "\n" + """
                afterEvaluate { tasks.test { extensions.getByType<JacocoTaskExtension>().isEnabled = false } }
            """.trimIndent(),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the same read, past it: a projectsEvaluated hook the build
    // script registers runs after yoriwake's own, so the switch is still on when yoriwake looks and
    // off when the tests run. No coverage agent reaches the test JVM, so the capture writes no map
    // and nothing declines: the selecting run must find the map unusable and run every test.
    @Test
    fun `a task whose jacoco a build script switches off after yoriwake configured it runs every test as an unusable map`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("map-unusable"), sources,
            buildScript = minimalBuild + "\n" + """
                gradle.projectsEvaluated { tasks.test { extensions.getByType<JacocoTaskExtension>().isEnabled = false } }
            """.trimIndent(),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: canAttachMultiplatform looks for `src/jvmMain` or `src/jvmTest`.
    // A JVM target given its own name (`jvm("desktop")`) lays its sources out as `src/desktopMain`,
    // so the module has a JVM target yoriwake cannot see, and must be declined.
    @Test
    fun `a multiplatform module whose JVM target has its own name runs every test`(@TempDir dir: File) {
        val kmp = """
            plugins {
                kotlin("multiplatform") version "2.1.20"
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            kotlin {
                jvm("desktop") { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
                sourceSets {
                    val desktopTest by getting { dependencies { implementation(kotlin("test-junit5")) } }
                }
            }
        """.trimIndent()
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("kotlin-multiplatform"),
            listOf(
                source(
                    "src/desktopMain/kotlin/dev/sample/Alpha.kt",
                    """
                    package dev.sample
                    class Alpha {
                        fun twice(n: Int) = n * 2
                        fun name() = "alpha"
                    }
                    """,
                ),
                source(
                    "src/desktopTest/kotlin/dev/sample/A2AlphaTest.kt",
                    """
                    package dev.sample
                    import kotlin.test.Test
                    import kotlin.test.assertEquals
                    class A2AlphaTest { @Test fun doubles() { assertEquals(6, Alpha().twice(3)) } }
                    """,
                ),
                source(
                    "src/desktopTest/kotlin/dev/sample/A3AlphaNameTest.kt",
                    """
                    package dev.sample
                    import kotlin.test.Test
                    import kotlin.test.assertEquals
                    class A3AlphaNameTest { @Test fun named() { assertEquals("alpha", Alpha().name()) } }
                    """,
                ),
            ),
            buildScript = kmp,
            task = "desktopTest",
        ) { breakAlpha(it, "src/desktopMain/kotlin/dev/sample/Alpha.kt") }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the scope comes from the main source sets alone. A module
    // whose code under test lives beside its tests, in the test source set, derives no package, and
    // must be declined rather than captured with a scope that covers none of it.
    @Test
    fun `a module whose code under test lives in its test sources runs every test`(@TempDir dir: File) {
        val helper = source(
            "src/test/java/dev/sample/Helper.java",
            """
            package dev.sample;
            public class Helper {
                public int twice(int n) { return n * 2; }
                public int one() { return 1; }
            }
            """,
        )
        val result = declinedAttempt(
            dir, "dev.sample.A2HelperTest", Aim.Forced("no-packages-derived"),
            listOf(
                helper,
                source(
                    "src/test/java/dev/sample/A0UnrelatedTest.java",
                    """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Helper().one()); } }
                    """,
                ),
                source(
                    "src/test/java/dev/sample/A2HelperTest.java",
                    """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class A2HelperTest { @Test void doubles() { assertEquals(6, new Helper().twice(3)); } }
                    """,
                ),
                classOrderByName,
            ),
        ) { breakAlpha(it, helper.first) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the package scan strips comments but not string literals. A
    // package annotation whose string holds a comment opener hides the package line behind it, so
    // the scan sees code with no package line and must widen the scope to everything rather than
    // leave the package's classes out of it.
    @Test
    fun `a package annotation whose string opens a comment keeps the package in scope and selects the test that reaches it`(
        @TempDir dir: File,
    ) {
        val packageInfo = source(
            "src/main/java/dev/sample/package-info.java",
            """
            @Deprecated(since = "/*")
            package dev.sample;
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("reaches-change"),
            listOf(other, first, alpha, packageInfo, alphaTest, classOrderByName),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: DA8.
    @Test
    fun `a build with no jacoco plugin runs every test`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("no-jacoco-plugin"), sources,
            buildScript = minimalBuild.replace("    jacoco\n", ""),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }
}
