package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on complement runs. A complement run leaves out the tests a selecting run at the same
 * commit shows it ran, only when the selecting run's record carries the same stamp as the
 * complement: commit, clean tree, task, build, classpath, configuration and test JVM. A test the
 * record lists ran on that code, that way; anything that differs between the two runs and is in
 * no stamp would let a test be left out on the strength of a result from other code.
 */
class ComplementRunsAttempts : BreakAttempt() {

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

    /** A target that reaches Codec, so a selecting run after [rewriteCodec] runs it, and reads [resource]. */
    private fun flavorTarget(resource: String) = source(
        "src/test/java/dev/sample/A9TargetTest.java",
        """
        package dev.sample;
        import java.util.Properties;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9TargetTest {
            @Test void flavorA() throws Exception {
                assertEquals("cba", new Codec().encode("abc"));
                Properties properties = new Properties();
                properties.load(getClass().getClassLoader().getResourceAsStream("$resource"));
                assertEquals("a", properties.getProperty("flavor"));
            }
        }
        """,
    )

    /** Codec rewritten to behave as before: a committed change the selecting run selects its tests on. */
    private fun rewriteCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("new StringBuilder(s).reverse().toString()", "new StringBuilder().append(s).reverse().toString()"))
    }

    /** A task writing `flavor.properties` from `-Pflavor` (default `a`) into [into]. */
    private fun flavorTask(name: String, into: String) = """
        val $name = tasks.register("$name") {
            val flavor = providers.gradleProperty("flavor").getOrElse("a")
            val out = layout.buildDirectory.dir("$into")
            inputs.property("flavor", flavor)
            outputs.dir(out)
            doLast { out.get().file("flavor.properties").asFile.writeText("flavor=${'$'}flavor\n") }
        }
    """.trimIndent()

    // Track: code reading. Boundary: the classpath digest reads each build-produced jar under the
    // root entry by entry, so a jar the build rebuilds compares by what it holds. Here the jar holds
    // a file generated from a property the complement run passes differently.
    @Test
    fun `a jar the build packs from a property differently is a record mismatch`(@TempDir dir: File) {
        val build = minimalBuild + "\n" + flavorTask("flavorFile", "generated/flavor") + """

            val flavorJar = tasks.register<Jar>("flavorJar") {
                archiveFileName.set("flavor.jar")
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
                from(flavorFile)
            }
            dependencies { testRuntimeOnly(files(flavorJar)) }
        """.trimIndent()
        val result = complementAttempt(
            dir, "dev.sample.A9TargetTest", Aim.Forced(AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND),
            listOf(codec, other, first, flavorTarget("flavor.properties"), classOrderByName), build,
            narrow = ::rewriteCodec, selecting = listOf("-Pflavor=a"), complementing = listOf("-Pflavor=b"),
        )

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: CR3, as 0.2 changed it: a build-produced directory on the classpath
    // compares by the content of its files, so a file generated from a property differs.
    @Test
    fun `a resource directory the build generates from a property differently is a record mismatch`(@TempDir dir: File) {
        val build = minimalBuild + "\n" + flavorTask("flavorFile", "generated/flavor") +
            "\ndependencies { testRuntimeOnly(files(flavorFile)) }\n"
        val result = complementAttempt(
            dir, "dev.sample.A9TargetTest", Aim.Forced(AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND),
            listOf(codec, other, first, flavorTarget("flavor.properties"), classOrderByName), build,
            narrow = ::rewriteCodec, selecting = listOf("-Pflavor=a"), complementing = listOf("-Pflavor=b"),
        )

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a selecting run leaves a record only when the tree is clean
    // where it ends as well as where it started. A test of the selecting run, and not of the
    // capture, writes a file into the working tree, so the run starts clean and ends not clean.
    @Test
    fun `a selecting run whose test writes into the working tree leaves no record`(@TempDir dir: File) {
        val writer = source(
            "src/test/java/dev/sample/A8WriterTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A8WriterTest {
                @Test void writesReport() throws Exception {
                    String encoded = new Codec().encode("abc");
                    boolean reports;
                    try { Codec.class.getMethod("reports"); reports = true; } catch (NoSuchMethodException absent) { reports = false; }
                    if (reports) Files.writeString(Paths.get("report.txt"), encoded);
                    assertEquals("cba", encoded);
                }
            }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A9TargetTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A9TargetTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
            """,
        )
        val result = complementAttempt(
            dir, "dev.sample.A9TargetTest", Aim.Forced("complement-no-record"),
            listOf(codec, other, first, writer, target, classOrderByName), minimalBuild,
            narrow = { dir ->
                val file = File(dir, codec.first)
                file.writeText(file.readText().replace("public String encode", "public static boolean reports() { return true; }\n    public String encode"))
            },
            between = { dir ->
                val file = File(dir, codec.first)
                file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
            },
        )

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the classpath digest reads the entries the task's classpath
    // lists. A jar's manifest can put further files on the test JVM's class path, which the JVM's
    // own loader follows: here the listed jar is a manifest only, and the file it names is
    // generated from a property the complement run passes differently.
    @Test
    fun `a jar a classpath jar's manifest names, packed from a property differently, is a record mismatch`(@TempDir dir: File) {
        val build = minimalBuild + "\n" + flavorTask("flavorFile", "generated/flavor") + """

            val flavorJar = tasks.register<Jar>("flavorJar") {
                archiveFileName.set("flavor.jar")
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
                from(flavorFile)
            }
            val pathJar = tasks.register<Jar>("pathJar") {
                dependsOn(flavorJar)
                archiveFileName.set("path.jar")
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
                manifest { attributes("Class-Path" to "flavor.jar") }
            }
            dependencies { testRuntimeOnly(files(pathJar)) }
        """.trimIndent()
        val result = complementAttempt(
            dir, "dev.sample.A9TargetTest", Aim.Forced(AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND),
            listOf(codec, other, first, flavorTarget("flavor.properties"), classOrderByName), build,
            narrow = ::rewriteCodec, selecting = listOf("-Pflavor=a"), complementing = listOf("-Pflavor=b"),
        )

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the stamp's configuration (system properties, JVM arguments,
    // filters) is read in the plugin's own doFirst, which runs before every doFirst the build adds;
    // only the classpath is looked at again after them. Here the build's own doFirst sets a system
    // property from a project property the complement run passes differently.
    @Test
    fun `a system property the test task's doFirst sets from a property differently is a record mismatch`(@TempDir dir: File) {
        val build = minimalBuild + """

            tasks.test {
                val flavor = providers.gradleProperty("flavor").getOrElse("a")
                doFirst { (this as Test).systemProperty("flavor", flavor) }
            }
        """.trimIndent()
        val target = source(
            "src/test/java/dev/sample/A9TargetTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A9TargetTest {
                @Test void flavorA() {
                    assertEquals("cba", new Codec().encode("abc"));
                    assertEquals("a", System.getProperty("flavor"));
                }
            }
            """,
        )
        val result = complementAttempt(
            dir, "dev.sample.A9TargetTest", Aim.Forced(AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND),
            listOf(codec, other, first, target, classOrderByName), build,
            narrow = ::rewriteCodec, selecting = listOf("-Pflavor=a"), complementing = listOf("-Pflavor=b"),
        )

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a selecting run with fail-fast leaves no record, since Gradle
    // drops the results of tests that finish after the first failure; the plugin reads `failFast` in
    // its own doFirst, before every doFirst the build adds. Here the build's own doFirst turns
    // fail-fast on, so the run that drops a failing target's result may still list it as ran.
    @Test
    fun `a selecting run whose test task's doFirst turns on fail-fast leaves no record`(@TempDir dir: File) {
        // Only on the selecting run, as a CI job that wants the first failure fast would ask.
        val build = minimalBuild + """

            tasks.test {
                val failFastAsked = providers.gradleProperty("failFirst").isPresent
                doFirst { (this as Test).failFast = failFastAsked }
            }
        """.trimIndent()
        fun failing(name: String) = source(
            "src/test/java/dev/sample/$name.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class $name { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
            """,
        )
        val target = "dev.sample.A9TargetTest"
        build(dir, "build.gradle.kts" to build, codec, other, first, failing("A5FailsFirstTest"), failing("A9TargetTest"), classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        val captured = ranTests(dir)
        check(passedIn(dir, target) == true) { "$target did not pass when the map was recorded; ran $captured" }

        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
        commit(dir, "break codec")
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-PfailFirst").run()
        val selected = ranTests(dir)
        check("dev.sample.A0UnrelatedTest" !in selected) { "the selecting run did not narrow; it ran $selected" }

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.complement").run()
        val complemented = ranTests(dir)
        val targetResult = passedIn(dir, target)
        val notes = notesOf(dir)

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "--rerun", "-Pyoriwake.disabled").run()
        val failsUnderFullRun = passedIn(dir, target) == false

        val result = classify(
            Observed(
                targetFailsUnderFullRun = failsUnderFullRun,
                targetRan = targetResult != null,
                targetFailed = targetResult == false,
                leftOut = captured - complemented,
                forcedKinds = forcedOf(notes),
                targetRows = emptyList(),
            ),
            Aim.Forced("complement-no-record"),
        )
        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, Result(result.outcome, "${result.why}; the selecting run reported $selected"))
    }

    private fun forcedOf(notes: List<Map<String, String>>) = notes.flatMap { note ->
        listOfNotNull(note[AgentContract.REFUSAL_KIND_NOTE], note[AgentContract.FULL_RUN_KIND_NOTE]) +
            note[AgentContract.FORCING_KINDS_NOTE].orEmpty().split(',')
    }.filter { it.isNotBlank() && it != AgentContract.RULE_NONE }.distinct()

    /**
     * As [attempt], for a complement run: records the map, commits [narrow] and runs a selecting
     * build with [selecting], which must narrow and run [target]; applies [between], then runs a
     * complement build with [complementing] and a build with yoriwake disabled and [complementing],
     * and classifies what the complement build did. Left out is what the recording ran and the
     * complement did not.
     */
    private fun complementAttempt(
        dir: File,
        target: String,
        aim: Aim,
        sources: List<Pair<String, String>>,
        buildScript: String,
        narrow: (File) -> Unit,
        selecting: List<String> = emptyList(),
        between: (File) -> Unit = {},
        complementing: List<String> = emptyList(),
    ): Result {
        build(dir, "build.gradle.kts" to buildScript, *sources.toTypedArray())
        committed(dir)
        runner(dir, "test", *selecting.toTypedArray()).build()
        val captured = ranTests(dir)
        check(passedIn(dir, target) == true) { "$target did not pass when the map was recorded; ran $captured" }

        narrow(dir)
        commit(dir, "narrow")
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *selecting.toTypedArray()).run()
        val selected = ranTests(dir)
        check(passedIn(dir, target) == true) { "$target did not run and pass in the selecting run; it ran $selected" }
        check(selected != captured) { "the selecting run did not narrow; it ran $selected" }

        between(dir)
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.complement", *complementing.toTypedArray()).run()
        val complemented = ranTests(dir)
        val targetResult = passedIn(dir, target)
        val notes = notesOf(dir)

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "--rerun", "-Pyoriwake.disabled", *complementing.toTypedArray()).run()
        val failsUnderFullRun = passedIn(dir, target) == false

        val forced = notes.flatMap { note ->
            listOfNotNull(note[AgentContract.REFUSAL_KIND_NOTE], note[AgentContract.FULL_RUN_KIND_NOTE]) +
                note[AgentContract.FORCING_KINDS_NOTE].orEmpty().split(',')
        }.filter { it.isNotBlank() && it != AgentContract.RULE_NONE }.distinct()
        return classify(
            Observed(
                targetFailsUnderFullRun = failsUnderFullRun,
                targetRan = targetResult != null,
                targetFailed = targetResult == false,
                leftOut = captured - complemented,
                forcedKinds = forced,
                targetRows = emptyList(),
            ),
            aim,
        )
    }

    /** Whether [testClass] passed (true), failed (false), or did not run (null), from its result file. */
    private fun passedIn(dir: File, testClass: String): Boolean? {
        val xml = File(dir, "build/test-results/test/TEST-$testClass.xml")
        if (!xml.isFile) return null
        val suite = Regex("""<testsuite [^>]*>""").find(xml.readText())?.value ?: return null
        fun count(attribute: String) = Regex("""$attribute="(\d+)"""").find(suite)?.groupValues?.get(1)?.toInt() ?: 0
        if (count("tests") == count("skipped")) return null
        return count("failures") + count("errors") == 0
    }

    /** The notes of every decision record part the last run's test JVMs wrote, or of its decisions file. */
    private fun notesOf(dir: File): List<Map<String, String>> {
        fun isPart(file: File) =
            file.name.startsWith("${AgentContract.DECISIONS_FILE}.") && file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)
        val mapDir = File(dir, ".gradle/yoriwake").listFiles().orEmpty().filter(File::isDirectory)
            .singleOrNull { d -> d.listFiles().orEmpty().any { it.name == AgentContract.DECISIONS_FILE || isPart(it) } }
            ?: return emptyList()
        val files = mapDir.listFiles().orEmpty().filter(::isPart).sortedBy(File::getName)
            .ifEmpty { listOf(File(mapDir, AgentContract.DECISIONS_FILE)).filter(File::isFile) }
        return files.map { file ->
            file.readLines().filter { it.startsWith(AgentContract.NOTE_PREFIX) }.associate { line ->
                val (key, value) = (line.removePrefix(AgentContract.NOTE_PREFIX) + "\t").split("\t", limit = 3)
                key to value
            }
        }
    }
}
