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

    // Track: code reading. Boundary: applyJacocoIncludes adopts a host's own jacoco includes as the
    // scope rather than deriving one. A host that scopes coverage to one package of the build's
    // sources leaves a class its tests reach out of every record: its change must not read as a
    // change no test reaches.
    @Test
    fun `a host whose own jacoco includes leave out a package the tests reach runs every test`(@TempDir dir: File) {
        val util = source(
            "src/main/java/dev/sample/util/Alpha.java",
            """
            package dev.sample.util;
            public class Alpha {
                public int twice(int n) { return n * 2; }
                public String name() { return "alpha"; }
            }
            """,
        )
        fun usesUtil(source: Pair<String, String>) =
            source.first to source.second.replace("package dev.sample;", "package dev.sample;\nimport dev.sample.util.Alpha;")
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("no-coverage-for-changed"),
            listOf(other, first, util, usesUtil(alphaTest), usesUtil(alphaNameTest), classOrderByName),
            buildScript = minimalBuild + "\n" + """
                tasks.test { extensions.getByType<JacocoTaskExtension>().includes = listOf("dev.sample.Other*") }
            """.trimIndent(),
        ) { breakAlpha(it, util.first) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    /**
     * A single-project multiplatform build with one JVM target, [jvmTarget], and [extra] in its
     * `kotlin` block. Its test task writes its results where [attempt] reads them, and `test` runs
     * it, so the attempt runs as on a plain JVM module.
     */
    private fun multiplatformBuild(jvmTarget: String, extra: String = "") = """
        plugins {
            kotlin("multiplatform") version "2.1.20"
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        kotlin {
            $jvmTarget { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
            sourceSets {
                commonTest.dependencies { implementation(kotlin("test")) }
                $extra
            }
        }
        val targetTest = tasks.withType<Test>().single()
        targetTest.reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/test"))
        tasks.register("test") { dependsOn(targetTest) }
    """.trimIndent()

    private fun kotlinTest(path: String, name: String, assertion: String) = source(
        "$path/dev/sample/$name.kt",
        """
        package dev.sample
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class $name { @Test fun passes() { $assertion } }
        """,
    )

    private val kotlinAlpha = """
        class Alpha {
            fun twice(n: Int) = n * 2
            fun name() = "alpha"
        }
    """.trimIndent()

    // Track: code reading. Boundary: multiplatformSourceDirs reads the module's own sources by
    // layout, every `src/<set>Main/kotlin` and `java`, never through the Kotlin plugin's model. A
    // JVM source set given a directory of its own outside that layout attaches all the same, and its
    // classes are in no package of the scope: their change must force, not narrow past their tests.
    @Test
    fun `a multiplatform JVM source set with a directory outside the layout runs every test when its class changes`(
        @TempDir dir: File,
    ) {
        val extraDir = "gen/src/extra/kotlin"
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("no-coverage-for-changed"),
            listOf(
                source("src/commonMain/kotlin/dev/sample/Other.kt", "package dev.sample\nclass Other { fun one() = 1 }"),
                source("src/jvmMain/kotlin/dev/sample/Platform.kt", "package dev.sample\nfun platform() = \"jvm\""),
                source("$extraDir/dev/extra/Alpha.kt", "package dev.extra\n$kotlinAlpha"),
                kotlinTest("src/jvmTest/kotlin", "A0UnrelatedTest", "assertEquals(1, Other().one())"),
                kotlinTest("src/jvmTest/kotlin", "A2AlphaTest", "assertEquals(6, dev.extra.Alpha().twice(3))"),
                kotlinTest("src/jvmTest/kotlin", "A3PlatformTest", "assertEquals(\"jvm\", platform())"),
                "src/jvmTest/resources/junit-platform.properties" to classOrderByName.second,
            ),
            buildScript = multiplatformBuild("jvm()", "jvmMain { kotlin.srcDir(\"$extraDir\") }"),
        ) { breakAlpha(it, "$extraDir/dev/extra/Alpha.kt") }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: hasJvmTarget takes a `src/jvmMain` or `src/jvmTest` directory
    // as the JVM target. A JVM target given its own name beside a leftover `src/jvmTest` directory
    // passes that check, so the module is attached rather than declined: its own sources must still
    // be in the scope, and its tests selected by what they reach. An inline call gives the inline
    // scan the SMAP it needs to narrow.
    @Test
    fun `a multiplatform JVM target with its own name beside a leftover jvmTest directory selects the test that reaches the change`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("reaches-change"),
            listOf(
                source(
                    "src/desktopMain/kotlin/dev/sample/Other.kt",
                    "package dev.sample\nclass Other { fun one() = listOf(1).map { it }.single() }",
                ),
                source("src/desktopMain/kotlin/dev/sample/Alpha.kt", "package dev.sample\n$kotlinAlpha"),
                source("src/jvmTest/README.txt", "Tests moved to desktopTest."),
                kotlinTest("src/desktopTest/kotlin", "A0UnrelatedTest", "assertEquals(1, Other().one())"),
                kotlinTest("src/desktopTest/kotlin", "A2AlphaTest", "assertEquals(6, Alpha().twice(3))"),
                "src/desktopTest/resources/junit-platform.properties" to classOrderByName.second,
            ),
            buildScript = multiplatformBuild("jvm(\"desktop\")"),
        ) { breakAlpha(it, "src/desktopMain/kotlin/dev/sample/Alpha.kt") }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }
}
