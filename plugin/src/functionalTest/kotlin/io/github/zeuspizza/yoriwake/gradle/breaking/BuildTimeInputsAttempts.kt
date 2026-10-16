package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on what the build itself decides before any test runs: the test filter a build script
 * sets, the Develocity features and in-JVM parallelism that make yoriwake decline, a task another
 * plugin reconfigures late, the wiring a build script can overwrite, and files the build reads.
 */
class BuildTimeInputsAttempts : DeclinedAttempt() {

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

    /** The usual target: fails once [breakAlpha] changes `twice`. */
    private val alphaTest = source(
        "src/test/java/dev/sample/A2AlphaTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A2AlphaTest { @Test void doubles() { assertEquals(6, new Alpha().twice(3)); } }
        """,
    )

    /** Executes Alpha and survives [breakAlpha], so Alpha stays in the map whatever drops out of it. */
    private val alphaNameTest = source(
        "src/test/java/dev/sample/A3AlphaNameTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A3AlphaNameTest { @Test void named() { assertEquals("alpha", new Alpha().name()); } }
        """,
    )

    private val beta = source(
        "src/main/java/dev/sample/Beta.java",
        "package dev.sample;\npublic class Beta { public int thrice(int n) { return n * 3; } }",
    )

    private val betaTest = source(
        "src/test/java/dev/sample/A5BetaTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A5BetaTest { @Test void triples() { assertEquals(3, new Beta().thrice(1)); } }
        """,
    )

    private fun breakAlpha(dir: File) {
        val file = File(dir, alpha.first)
        file.writeText(file.readText().replace("n * 2", "n * 2 + 1"))
    }

    private fun withTask(body: String) = minimalBuild.replace("tasks.test { useJUnitPlatform() }", body.trimIndent())

    /** Leaves A2AlphaTest out when the run is given `-Pquick`, as the build script's own filter. */
    private val quickScriptFilter = withTask(
        """
        tasks.test {
            useJUnitPlatform()
            if (project.hasProperty("quick")) filter { excludeTestsMatching("*A2AlphaTest") }
        }
        """,
    )

    // Track: code reading. Boundary: a capture under a build-script filter dates the map, and
    // CoverageDecoder.decode keeps no record the dating run did not re-observe. The test the filter
    // left out was edited to reach Gamma after its record was taken, so a record kept past the
    // dating capture would describe code the map's commit no longer holds, and Gamma's change would
    // skip it. Dropped, it is not in the map and runs.
    @Test
    fun `a test a build-script filter left out after it began reaching a new class runs as not in the map`(
        @TempDir dir: File,
    ) {
        val gamma = source(
            "src/main/java/dev/sample/Gamma.java",
            "package dev.sample;\npublic class Gamma { public int add(int a, int b) { return a + b; } }",
        )
        val gammaTest = source(
            "src/test/java/dev/sample/A4GammaTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A4GammaTest { @Test void adds() { assertEquals(5, new Gamma().add(2, 3)); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("not-in-map"),
            listOf(other, first, alpha, alphaTest, alphaNameTest, gamma, gammaTest, classOrderByName),
            buildScript = quickScriptFilter,
        ) { dir ->
            File(dir, alphaTest.first).writeText(
                alphaTest.second.replace("new Alpha().twice(3)", "new Gamma().add(new Alpha().twice(1), 4)"),
            )
            commit(dir, "A2AlphaTest reaches Gamma")
            runner(dir, "test", "-Pquick").build()
            val file = File(dir, gamma.first)
            file.writeText(file.readText().replace("a + b", "a + b + 1"))
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: BI2.
    @Test
    fun `a test a build-script filter left out of a dating capture runs once the filter is off`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("not-in-map"),
            listOf(other, first, alpha, alphaTest, alphaNameTest, classOrderByName),
            buildScript = quickScriptFilter,
        ) { dir ->
            runner(dir, "test", "-Pquick").build()
            breakAlpha(dir)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: BI3.
    @Test
    fun `a pattern an init script adds once the graph is ready leaves the map's records as they were`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("reaches-change"),
            listOf(other, first, alpha, alphaTest, beta, betaTest, classOrderByName),
        ) { dir ->
            File(dir, beta.first).writeText(beta.second.replace("n * 3", "n + n + n"))
            commit(dir, "a change A2AlphaTest does not reach")
            val launcher = File(dir, "build/launcher.init.gradle").apply {
                writeText(
                    "gradle.taskGraph.whenReady { graph -> graph.allTasks.findAll { it instanceof Test }" +
                        ".each { it.filter.includeTest('dev.sample.A5BetaTest', null) } }\n",
                )
            }
            runner(dir, "test", "--init-script", launcher.absolutePath).build()
            breakAlpha(dir)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: BI4.
    @Test
    fun `a test a tag filter left out of a dating capture runs once the filter is off`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("not-in-map"),
            listOf(
                other, first, alpha,
                alphaTest.first to alphaTest.second
                    .replace("import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Tag;\nimport org.junit.jupiter.api.Test;")
                    .replace("class A2AlphaTest", "@Tag(\"slow\") class A2AlphaTest"),
                alphaNameTest, classOrderByName,
            ),
            buildScript = withTask(
                "tasks.test { useJUnitPlatform { if (project.hasProperty(\"quick\")) excludeTags(\"slow\") } }",
            ),
        ) { dir ->
            runner(dir, "test", "-Pquick").build()
            breakAlpha(dir)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: BI10.
    @Test
    fun `a test a sharding plugin left out of a dating capture runs once the shard is off`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("not-in-map"),
            listOf(other, first, alpha, alphaTest, alphaNameTest, classOrderByName),
            buildScript = minimalBuild + "\n" + """
                class Shards : Plugin<Project> {
                    override fun apply(project: Project) {
                        val shard = project.findProperty("shard")
                        project.tasks.withType<Test>().configureEach {
                            if (shard == "b") filter { excludeTestsMatching("*A2AlphaTest") }
                        }
                    }
                }
                apply<Shards>()
            """.trimIndent(),
        ) { dir ->
            runner(dir, "test", "-Pshard=b").build()
            breakAlpha(dir)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    /** A `develocity` task extension shaped like the real plugin's: a feature per getter, each with `getEnabled()`. */
    private fun standIn(td: Boolean, pts: Boolean) = withTask(
        """
        class StandInFeature(val enabled: Property<Boolean>)
        class StandInDevelocity(val testDistribution: StandInFeature, val predictiveTestSelection: StandInFeature)
        tasks.test {
            useJUnitPlatform()
            val develocity = StandInDevelocity(
                StandInFeature(objects.property(Boolean::class.java).value($td)),
                StandInFeature(objects.property(Boolean::class.java).value($pts)),
            )
            extensions.add("develocity", develocity)
        }
        """,
    )

    private val declineSources = listOf(other, first, alpha, alphaTest, alphaNameTest, classOrderByName)

    // Track: fixtures. Row: BI5.
    @Test
    fun `a task with Test Distribution enabled runs every test after a change`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("develocity-test-distribution"), declineSources,
            buildScript = standIn(td = true, pts = false),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: BI6.
    @Test
    fun `a task with Develocity test selection enabled runs every test after a change`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("develocity-test-selection"), declineSources,
            buildScript = standIn(td = false, pts = true),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: BI7.
    @Test
    fun `a task whose Develocity extension has no feature getters runs every test after a change`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("develocity-undetermined"), declineSources,
            buildScript = withTask(
                """
                class Broken
                tasks.test { useJUnitPlatform(); extensions.add("develocity", Broken()) }
                """,
            ),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: DevelocityDetection reads the modern `develocity` extension and
    // also each feature's own legacy extension (`distribution`), which the Gradle Enterprise plugin
    // registers. A build with only the legacy shape switched on must decline as surely.
    @Test
    fun `Test Distribution switched on only through its legacy extension runs every test`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("develocity-test-distribution"), declineSources,
            buildScript = withTask(
                """
                class Distribution(val enabled: Property<Boolean>)
                tasks.test {
                    useJUnitPlatform()
                    extensions.add("distribution", Distribution(objects.property(Boolean::class.java).value(true)))
                }
                """,
            ),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: Predictive Test Selection is on when `getEnabled()` is false but
    // its optional `getEnabledFromSystemProperty()` twin, which `-Dpts.enabled=true` sets, is true. A
    // read of `getEnabled()` alone would take it as off.
    @Test
    fun `Develocity test selection switched on only through its system-property twin runs every test`(
        @TempDir dir: File,
    ) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("develocity-test-selection"), declineSources,
            buildScript = withTask(
                """
                class Feature(val enabled: Property<Boolean>, val enabledFromSystemProperty: Boolean)
                class StandInDevelocity(val testDistribution: Feature, val predictiveTestSelection: Feature)
                tasks.test {
                    useJUnitPlatform()
                    extensions.add(
                        "develocity",
                        StandInDevelocity(
                            Feature(objects.property(Boolean::class.java).value(false), false),
                            Feature(objects.property(Boolean::class.java).value(false), true),
                        ),
                    )
                }
                """,
            ),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: readFlag accepts a Boolean or a provider of one and nothing
    // else. A switch holding the text "true" is neither on nor off to it; a read through toString()
    // would answer one or the other. It must decline as undetermined.
    @Test
    fun `a Develocity switch holding text rather than a boolean runs every test as undetermined`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("develocity-undetermined"), declineSources,
            buildScript = withTask(
                """
                class TextFeature(val enabled: Property<String>)
                class Feature(val enabled: Property<Boolean>)
                class StandInDevelocity(val testDistribution: TextFeature, val predictiveTestSelection: Feature)
                tasks.test {
                    useJUnitPlatform()
                    extensions.add(
                        "develocity",
                        StandInDevelocity(
                            TextFeature(objects.property(String::class.java).value("true")),
                            Feature(objects.property(Boolean::class.java).value(false)),
                        ),
                    )
                }
                """,
            ),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    private val parallelProperties =
        "junit.jupiter.execution.parallel.enabled=true\njunit.jupiter.execution.parallel.mode.default=concurrent\n"

    // Track: fixtures. Row: BI8.
    @Test
    fun `a suite in Jupiter's parallel mode runs every test after a change`(@TempDir dir: File) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("in-jvm-parallelism"),
            listOf(other, first, alpha, alphaTest, alphaNameTest, "src/test/resources/junit-platform.properties" to parallelProperties),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: ParallelismDetector reads junit-platform.properties from each
    // directory on the test runtime classpath and from each jar. A build that ships it inside a jar
    // of its own, as multi-module builds share it, must be found there.
    @Test
    fun `Jupiter's parallel mode switched on from a jar on the test runtime classpath runs every test`(
        @TempDir dir: File,
    ) {
        val result = declinedAttempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("in-jvm-parallelism"),
            listOf(other, first, alpha, alphaTest, alphaNameTest, "conf/junit-platform.properties" to parallelProperties),
            buildScript = minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                """
                val confJar by tasks.registering(Jar::class) {
                    archiveFileName.set("conf.jar")
                    destinationDirectory.set(layout.buildDirectory.dir("conf"))
                    from("conf")
                }
                dependencies { testRuntimeOnly(files(confJar)) }
                tasks.test { useJUnitPlatform() }
                """.trimIndent(),
            ),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the check that the map directory was not overwritten runs in
    // one of yoriwake's doFirst actions, and Gradle runs those before the build script's own. A
    // build script that points the test JVM at another map directory in its doFirst is past the
    // check; the agent then finds no usable map there and must run everything.
    @Test
    fun `a map directory a build script's doFirst overwrites runs every test as an unusable map`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("map-unusable"),
            listOf(other, first, alpha, alphaTest, alphaNameTest, classOrderByName),
            buildScript = withTask(
                """
                tasks.test {
                    useJUnitPlatform()
                    val moved = layout.projectDirectory.dir(".gradle/yoriwake/moved").asFile
                    doFirst {
                        moved.deleteRecursively()
                        moved.mkdirs()
                        (this as Test).systemProperty("yoriwake.map.dir", moved.absolutePath)
                    }
                }
                """,
            ),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: BI9.
    @Test
    fun `a host scope set in afterEvaluate selects the test that reaches a change`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("reaches-change"),
            listOf(other, first, alpha, alphaTest, classOrderByName),
            buildScript = minimalBuild + "\n" + """
                afterEvaluate {
                    tasks.test { extensions.getByType<JacocoTaskExtension>().includes = listOf("dev.sample.*") }
                }
            """.trimIndent(),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the scope a record was captured under is read back off the task
    // when it runs (DecodeTask's effectiveScope), not taken from what JacocoScoping applied. A
    // projectsEvaluated hook the build script registers runs after yoriwake's own and narrows the
    // includes to one of the two packages yoriwake derived; the test that reaches a change inside
    // what is left must still be selected by its record.
    @Test
    fun `a host scope narrowed after yoriwake configured the task still selects the test that reaches a change`(
        @TempDir dir: File,
    ) {
        val beta = source(
            "src/main/java/dev/extra/Beta.java",
            "package dev.extra;\npublic class Beta { public int three() { return 3; } }",
        )
        val betaTest = source(
            "src/test/java/dev/sample/A3BetaTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A3BetaTest { @Test void three() { assertEquals(3, new dev.extra.Beta().three()); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Row("reaches-change"),
            listOf(other, first, alpha, beta, alphaTest, betaTest, classOrderByName),
            buildScript = minimalBuild + "\n" + """
                gradle.projectsEvaluated {
                    tasks.test { extensions.getByType<JacocoTaskExtension>().includes = listOf("dev.sample.*") }
                }
            """.trimIndent(),
        ) { breakAlpha(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a changed file that no compiled class or build script names
    // forces the run, since a test or the build can read a file without naming it. A build script
    // that finds its input by extension names neither the file nor its directory, yet hands it to
    // the tests by path, so the test JVM's arguments stay the same while what the test reads changes.
    @Test
    fun `a file the build script finds by its extension and hands to the tests forces the run`(@TempDir dir: File) {
        val factorTest = source(
            "src/test/java/dev/sample/A2FactorTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2FactorTest {
                @Test void factor() throws Exception {
                    int factor = Integer.parseInt(Files.readString(Paths.get(System.getProperty("factor.file"))).trim());
                    assertEquals(factor, new Alpha().twice(1));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2FactorTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, alpha, factorTest, alphaNameTest, "level.knob" to "2\n", classOrderByName),
            buildScript = withTask(
                """
                val knob = rootDir.listFiles { f -> f.name.endsWith(".knob") }!!.single()
                tasks.test { useJUnitPlatform(); systemProperty("factor.file", knob.absolutePath) }
                """,
            ),
        ) { dir -> File(dir, "level.knob").writeText("3\n") }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }
}
