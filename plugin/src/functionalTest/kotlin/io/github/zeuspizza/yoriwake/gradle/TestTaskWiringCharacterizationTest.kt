package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Pins what the plugin does to a wired `Test` task, observed from outside, so moving or splitting
 * the wiring code cannot silently reorder it or change what reaches the test JVM.
 *
 * The plugin's actions are anonymous lambdas with no stable name, so each is identified by what it
 * observably does: the system properties and JVM arguments it changes, the files it creates or
 * deletes, and the `[yoriwake]` lines it logs. `doFirst` prepends, so the order is itself behaviour.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestTaskWiringCharacterizationTest {

    private lateinit var dir: File

    private lateinit var captureActions: String
    private lateinit var selectActions: String
    private lateinit var captureJvm: String
    private lateinit var selectJvm: String
    private lateinit var selectChangeSet: String

    // Wraps every action of `test` just before execution. Everything lives inside the action body
    // so the configuration cache serializes files and strings, never the script object.
    private val probe = """

        gradle.taskGraph.whenReady {
            val test = tasks.named<Test>("test").get()
            val root = layout.projectDirectory.asFile
            val label = providers.gradleProperty("probe.label").get()
            val out = layout.buildDirectory.file("probe/actions-" + label + ".txt").get().asFile
            test.actions = test.actions.mapIndexed { index, action ->
                val name = (action as? org.gradle.api.Describable)?.displayName ?: action.javaClass.simpleName
                Action<Task> {
                    val t = this as Test
                    val snapshot = { ->
                        val dirs = listOf(File(root, "build"), File(root, ".gradle"))
                        dirs.flatMap { d -> d.walk().filter { it.isFile }.toList() }
                            .map { it.relativeTo(root).invariantSeparatorsPath }
                            .filter { p ->
                                !p.startsWith("build/probe/") && !p.startsWith("build/tmp/") &&
                                    (!p.startsWith(".gradle/") || p.startsWith(".gradle/yoriwake"))
                            }
                            .toSet()
                    }
                    val propsBefore = t.systemProperties.mapValues { it.value?.toString() }
                    val argsBefore = t.jvmArgs.orEmpty().toList()
                    val filesBefore = snapshot()
                    val logged = mutableListOf<String>()
                    val listener = StandardOutputListener { logged += it.toString() }
                    t.logging.addStandardOutputListener(listener)
                    try {
                        action.execute(t)
                    } finally {
                        t.logging.removeStandardOutputListener(listener)
                    }
                    val lines = mutableListOf<String>()
                    if (index == 0) {
                        lines += "configured"
                        propsBefore.filterKeys { it.startsWith("yoriwake") }.toSortedMap()
                            .forEach { (k, v) -> lines += "  prop " + k + "=" + v }
                        lines += "  jvmArgs " + argsBefore
                    }
                    lines += "#" + index + " " + name
                    // The test execution itself is only located, not described: its outputs name
                    // workers and timings that vary between runs.
                    if (name.contains("doFirst") || name.contains("doLast")) {
                        val propsAfter = t.systemProperties.mapValues { it.value?.toString() }
                        (propsBefore.keys + propsAfter.keys).sorted().forEach { k ->
                            if (propsBefore[k] != propsAfter[k]) lines += "  prop " + k + "=" + propsAfter[k]
                        }
                        val argsAfter = t.jvmArgs.orEmpty().toList()
                        if (argsAfter != argsBefore) lines += "  jvmArgs " + argsAfter
                        val filesAfter = snapshot()
                        (filesAfter - filesBefore).sorted().forEach { lines += "  +file " + it }
                        (filesBefore - filesAfter).sorted().forEach { lines += "  -file " + it }
                        logged.joinToString("").lines().filter { it.contains("[yoriwake]") }
                            .forEach { lines += "  log " + it.trim() }
                    }
                    out.parentFile.mkdirs()
                    out.appendText(lines.joinToString("\n") + "\n")
                }
            }
        }
    """.trimIndent()

    private val build = """
        plugins {
            java
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        dependencies {
            testImplementation(platform("org.junit:junit-bom:5.11.4"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }
        tasks.test { useJUnitPlatform() }
    """.trimIndent()

    // Reports, from inside the forked test JVM, exactly what it was given: the agent reads nothing
    // else. Runs in both builds because the select build's change reaches Alpha.
    private val alphaTest = """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.io.File;
        import java.lang.management.ManagementFactory;
        import java.nio.file.Files;
        import java.util.TreeMap;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AlphaTest {
            @Test void passes() throws Exception {
                assertEquals(2, new Alpha().twice(1));
                StringBuilder out = new StringBuilder();
                new TreeMap<>(System.getProperties()).forEach((k, v) -> {
                    if (k.toString().startsWith("yoriwake")) out.append("prop ").append(k).append('=').append(v).append('\n');
                });
                for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                    if (a.contains("javaagent") || a.contains("yoriwake")) out.append("arg ").append(a).append('\n');
                }
                for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
                    if (new File(e).getName().contains("yoriwake")) out.append("classpath ").append(e).append('\n');
                }
                File target = new File("build/probe/jvm.txt");
                target.getParentFile().mkdirs();
                Files.write(target.toPath(), out.toString().getBytes("UTF-8"));
            }
        }
    """.trimIndent()

    private fun write(path: String, content: String) {
        File(dir, path).parentFile.mkdirs()
        File(dir, path).writeText(content)
    }

    private fun git(vararg args: String) {
        val exit = ProcessBuilder("git", "-c", "user.email=t@example.com", "-c", "user.name=t", *args)
            .directory(dir).redirectErrorStream(true).start().waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed")
    }

    private fun run(label: String, vararg args: String) {
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withGradleVersion(FunctionalGradle.version)
            .withTestKitDir(FunctionalGradle.testKitDir())
            .withArguments(
                "test", *args, "-Pprobe.label=$label",
                "--configuration-cache", "--stacktrace", "--no-watch-fs",
            )
            .forwardOutput()
            .build()
    }

    /** Paths and the Gradle distribution's locations differ per machine; the names do not. */
    private fun normalise(text: String): String {
        var result = text
        listOf(dir.canonicalPath, dir.absolutePath).forEach { result = result.replace(it, "<project>") }
        result = result.replace(File.separatorChar, '/')
        // Any other absolute path is outside the project: keep only its file name.
        result = result.replace(Regex("""(?<![<>\w])/[^\s,=:;\]]*/([^/\s,=:;\]]+)"""), "<path>/$1")
        // Commit ids, process ids, identity hashes and worker numbers differ per run; a TestKit
        // daemon numbers its workers across every build it has run.
        result = result.replace(Regex("[0-9a-f]{40}"), "<sha>")
        result = result.replace(Regex("""worker-\d+"""), "worker-<n>")
        result = result.replace(Regex("zip_[0-9a-f]+"), "zip_<hash>")
        result = result.replace(Regex("""\.\d+-\d+\.part"""), ".<pid>-<n>.part")
        return result.replace(Regex("""@\d+:\d+"""), "@<id>")
    }

    @BeforeAll
    fun runCaptureThenSelect(@TempDir tempDir: File) {
        dir = tempDir
        write("settings.gradle.kts", """rootProject.name = "sample"""")
        write("build.gradle.kts", build + "\n" + probe)
        write("src/main/java/dev/sample/Alpha.java",
            "package dev.sample;\npublic class Alpha { public int twice(int n) { return n * 2; } }")
        write("src/main/java/dev/sample/Beta.java",
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return n * 3; } }")
        write("src/test/java/dev/sample/AlphaTest.java", alphaTest)
        write("src/test/java/dev/sample/BetaTest.java", """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest {
                @Test void passes() { assertEquals(3, new Beta().thrice(1)); }
            }
        """.trimIndent())
        write(".gitignore", "build/\n.gradle/\n")
        git("init", "-b", "main")
        git("commit", "--allow-empty", "-m", "base")
        git("add", ".")
        git("commit", "-m", "sample")

        run("capture")
        captureActions = normalise(File(dir, "build/probe/actions-capture.txt").readText())
        captureJvm = normalise(File(dir, "build/probe/jvm.txt").readText())
        File(dir, "build/probe/jvm.txt").delete()

        write("src/main/java/dev/sample/Alpha.java",
            "package dev.sample;\npublic class Alpha { public int twice(int n) { return n + n; } }")
        run("select", "-Pyoriwake.select")
        selectActions = normalise(File(dir, "build/probe/actions-select.txt").readText())
        selectJvm = normalise(File(dir, "build/probe/jvm.txt").readText())
        selectChangeSet = java.util.Properties().apply {
            File(dir, ".gradle/yoriwake/test-7f007aca/change-set").reader().use { load(it) }
        }.entries.map { (k, v) -> "$k=$v" }.sorted().joinToString("\n")
    }

    @Test
    fun `a capture build runs the plugin's test-task actions in this order`() {
        val expected = """
            configured
              prop yoriwake.internal.capture.outputDir=<project>/.gradle/yoriwake/test-7f007aca/raw
              prop yoriwake.map.dir=<project>/.gradle/yoriwake/test-7f007aca
              jvmArgs []
            #0 Execute doFirst {} action
              log [yoriwake] :test map=<project>/.gradle/yoriwake/test-7f007aca scope=derived(dev.sample.*) includes=[dev.sample.*] nolocation=true exclloaders=[sun.reflect.DelegatingClassLoader, jdk.internal.reflect.DelegatingClassLoader] agent=true forks=1 unfiltered=yes (task includes=[] excludes=[])
            #1 Execute doFirst {} action
              +file .gradle/yoriwake/test-7f007aca/task-facts
            #2 Execute doFirst {} action
            #3 Execute doFirst {} action
              jvmArgs [-javaagent:<project>/.gradle/yoriwake-agent/yoriwake-agent.jar]
            #4 Execute doFirst {} action
            #5 Execute doFirst {} action
              +file .gradle/yoriwake/test-7f007aca/ran.marker
            #6 Execute doFirst {} action
              +file .gradle/yoriwake/test-7f007aca/capture-head.pending
            #7 Execute doFirst {} action
              +file .gradle/yoriwake-agent/yoriwake-agent.jar
            #8 Execute doFirst {} action
            #9 Execute executeTests
        """.trimIndent()
        assertEquals(expected, captureActions.trimEnd())
    }

    @Test
    fun `a select build runs the plugin's test-task actions in this order`() {
        val expected = """
            configured
              prop yoriwake.change.file=<project>/.gradle/yoriwake/test-7f007aca/change-set
              prop yoriwake.internal.capture.outputDir=<project>/.gradle/yoriwake/test-7f007aca/raw
              prop yoriwake.map.dir=<project>/.gradle/yoriwake/test-7f007aca
              prop yoriwake.select=true
              prop yoriwake.select.classGranularity=false
              jvmArgs []
            #0 Execute doFirst {} action
              log [yoriwake] :test map=<project>/.gradle/yoriwake/test-7f007aca scope=derived(dev.sample.*) includes=[dev.sample.*] nolocation=true exclloaders=[sun.reflect.DelegatingClassLoader, jdk.internal.reflect.DelegatingClassLoader] agent=true forks=1 unfiltered=yes (task includes=[] excludes=[])
            #1 Execute doFirst {} action
            #2 Execute doFirst {} action
              prop yoriwake.change.accountedFor=false
              prop yoriwake.internal.capture.outputDir=
              +file .gradle/yoriwake/test-7f007aca/change-set
              log [yoriwake] :test: compared 4 compiled classes against 4 the map recorded: 0 changed with no source change behind them
              log [yoriwake] :test selecting against <sha> (merge base with main): 1 changed classes, 0 paths coverage cannot see
              log [yoriwake] :test: narrowing, so nothing is instrumented and the map is left alone. A partial run cannot produce a map worth keeping.
            #3 Execute doFirst {} action
            #4 Execute doFirst {} action
            #5 Execute doFirst {} action
              jvmArgs [-javaagent:<project>/.gradle/yoriwake-agent/yoriwake-agent.jar]
            #6 Execute doFirst {} action
            #7 Execute doFirst {} action
              +file .gradle/yoriwake/test-7f007aca/ran.marker
              -file .gradle/yoriwake/test-7f007aca/decisions.tsv
              -file .gradle/yoriwake/test-7f007aca/decisions.tsv.<pid>-<n>.part
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/000001.exec
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/000002.exec
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/000003.exec
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/000004.exec
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/000005.exec
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/000006.exec
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/index.tsv
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/overhead.txt
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/plan-complete
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/raw-schema-version
              -file .gradle/yoriwake/test-7f007aca/raw/worker-<n>/touches.tsv
            #8 Execute doFirst {} action
            #9 Execute doFirst {} action
            #10 Execute doFirst {} action
              -file build/jacoco/test.exec
            #11 Execute executeTests
        """.trimIndent()
        assertEquals(expected, selectActions.trimEnd())
    }

    @Test
    fun `a capture build hands the test JVM exactly these properties and agents`() {
        val expected = """
            prop yoriwake.internal.capture.outputDir=<project>/.gradle/yoriwake/test-7f007aca/raw
            prop yoriwake.internal.capture.owner=io.github.zeuspizza.yoriwake.agent.engines.PlatformEvents@<id>
            prop yoriwake.map.dir=<project>/.gradle/yoriwake/test-7f007aca
            arg -Dyoriwake.internal.capture.outputDir=<project>/.gradle/yoriwake/test-7f007aca/raw
            arg -Dyoriwake.map.dir=<project>/.gradle/yoriwake/test-7f007aca
            arg -javaagent:<project>/.gradle/yoriwake-agent/yoriwake-agent.jar
            arg -javaagent:<project>/build/tmp/.cache/expanded/zip_<hash>/jacocoagent.jar=destfile=build/jacoco/test.exec,append=true,includes=dev.sample.*,exclclassloader=sun.reflect.DelegatingClassLoader:jdk.internal.reflect.DelegatingClassLoader,inclnolocationclasses=true,dumponexit=true,output=file,jmx=false
            classpath <project>/.gradle/yoriwake-agent/yoriwake-agent.jar
        """.trimIndent()
        assertEquals(expected, captureJvm.trimEnd())
    }

    @Test
    fun `a select build hands the test JVM exactly these properties and agents`() {
        val expected = """
            prop yoriwake.change.accountedFor=false
            prop yoriwake.change.file=<project>/.gradle/yoriwake/test-7f007aca/change-set
            prop yoriwake.internal.capture.outputDir=
            prop yoriwake.map.dir=<project>/.gradle/yoriwake/test-7f007aca
            prop yoriwake.select=true
            prop yoriwake.select.classGranularity=false
            arg -Dyoriwake.change.accountedFor=false
            arg -Dyoriwake.change.file=<project>/.gradle/yoriwake/test-7f007aca/change-set
            arg -Dyoriwake.internal.capture.outputDir
            arg -Dyoriwake.map.dir=<project>/.gradle/yoriwake/test-7f007aca
            arg -Dyoriwake.select=true
            arg -Dyoriwake.select.classGranularity=false
            arg -javaagent:<project>/.gradle/yoriwake-agent/yoriwake-agent.jar
            classpath <project>/.gradle/yoriwake-agent/yoriwake-agent.jar
        """.trimIndent()
        assertEquals(expected, selectJvm.trimEnd())
        // The list-valued change set, which reaches the JVM through the file it names.
        assertEquals(
            """
            yoriwake.change.absenceProvable=dev.sample.Alpha
            yoriwake.change.bytes=
            yoriwake.change.classes=dev.sample.Alpha
            yoriwake.change.exemptTestClasses=
            yoriwake.change.ownTestClasses=
            yoriwake.change.unmappable=
            yoriwake.change.unreadable=
            """.trimIndent(),
            selectChangeSet,
        )
    }
}
