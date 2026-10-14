package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that what a `Test` task says about the test JVM before it starts is what the test JVM then
 * sees: the JDK its launcher names, the system properties of its `-D` arguments and the variables
 * of its `environment`. The environment fingerprint is computed from the task's side, on the
 * recording and the selecting run alike, so it stands for the tests only while the two agree.
 *
 * The task's view is read by a `doFirst` added once the task graph is ready, so it runs before the
 * plugin's own actions and sees what they see. A property the build sets in its own `doFirst` runs
 * after them, so it is outside that view; the last scenario records that limit.
 */
class EnvironmentCharacterizationTest {

    enum class Launcher { DEFAULT, DEFAULT_WITHOUT_CONFIGURATION_CACHE, TOOLCHAIN, EXECUTABLE }

    private val javaHome = File(System.getProperty("java.home"))

    private val feature = Runtime.version().feature()

    private fun buildScript(launcher: Launcher) = """
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
        ${if (launcher == Launcher.TOOLCHAIN) "java { toolchain { languageVersion.set(JavaLanguageVersion.of($feature)) } }" else ""}
        tasks.test {
            useJUnitPlatform()
            ${if (launcher == Launcher.EXECUTABLE) "executable = \"${File(javaHome, "bin/java").invariantSeparatorsPath}\"" else ""}
            systemProperty("probe.declared", "declared")
            systemProperty("probe.path", layout.projectDirectory.asFile.absolutePath)
            jvmArgs("-Dprobe.argument=argument", "-Dprobe.bare")
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dprobe.provided=provided") })
            environment("PROBE_TASK", "task")
            doFirst { (this as Test).systemProperty("probe.late", "late") }
        }

        gradle.taskGraph.whenReady {
            val out = layout.buildDirectory.file("probe/task.txt").get().asFile
            tasks.named<Test>("test").get().doFirst {
                val t = this as Test
                val escape = { s: Any? -> s.toString().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") }
                val lines = mutableListOf<String>()
                runCatching { t.javaLauncher.get().metadata }
                    .onSuccess {
                        lines += "jdk.version=" + escape(it.javaRuntimeVersion)
                        lines += "jdk.vendor=" + escape(it.vendor)
                    }
                    .onFailure { lines += "jdk.unavailable=" + escape(it) }
                lines += "jdk.executable=" + escape(t.executable)
                lines += "os.name=" + escape(System.getProperty("os.name"))
                lines += "os.arch=" + escape(System.getProperty("os.arch"))
                t.allJvmArgs.filter { it.startsWith("-D") }.map { it.removePrefix("-D") }.forEach { body ->
                    lines += "sys:" + body.substringBefore('=') + "=" + escape(if ('=' in body) body.substringAfter('=') else "")
                }
                t.systemProperties.forEach { (k, v) -> lines += "prop:" + k + "=" + escape(v) }
                listOf("TZ", "LANG", "LC_ALL", "PROBE_TASK", "PROBE_DAEMON", "PROBE_UNSET").forEach { n ->
                    lines += "env:" + n + "=" + escape(t.environment[n])
                }
                out.parentFile.mkdirs()
                out.writeText(lines.joinToString("\n") + "\n")
            }
        }
    """.trimIndent()

    private val probeTest = """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.io.File;
        import java.nio.file.Files;
        import java.util.TreeMap;
        class ProbeTest {
            private static String escape(Object s) {
                return String.valueOf(s).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
            }
            @Test void reports() throws Exception {
                StringBuilder out = new StringBuilder();
                out.append("jdk.version=").append(escape(System.getProperty("java.runtime.version"))).append('\n');
                out.append("jdk.vendor=").append(escape(System.getProperty("java.vendor"))).append('\n');
                out.append("jdk.home=").append(escape(System.getProperty("java.home"))).append('\n');
                out.append("os.name=").append(escape(System.getProperty("os.name"))).append('\n');
                out.append("os.arch=").append(escape(System.getProperty("os.arch"))).append('\n');
                new TreeMap<>(System.getProperties()).forEach((k, v) ->
                    out.append("sys:").append(k).append('=').append(escape(v)).append('\n'));
                for (String n : new String[] {"TZ", "LANG", "LC_ALL", "PROBE_TASK", "PROBE_DAEMON", "PROBE_UNSET"}) {
                    out.append("env:").append(n).append('=').append(escape(System.getenv(n))).append('\n');
                }
                File target = new File("build/probe/jvm.txt");
                target.getParentFile().mkdirs();
                Files.write(target.toPath(), out.toString().getBytes("UTF-8"));
            }
        }
    """.trimIndent()

    private fun read(file: File): List<Pair<String, String>> =
        file.readLines().filter(String::isNotEmpty).map { it.substringBefore('=') to it.substringAfter('=') }

    @ParameterizedTest
    @EnumSource(Launcher::class)
    fun `the task's view of the test JVM is what the test JVM sees`(launcher: Launcher, @TempDir dir: File) {
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "sample"""")
        File(dir, "build.gradle.kts").writeText(buildScript(launcher))
        File(dir, "src/test/java/dev/sample/ProbeTest.java").apply { parentFile.mkdirs() }.writeText(probeTest)
        val cache = if (launcher == Launcher.DEFAULT_WITHOUT_CONFIGURATION_CACHE) "--no-configuration-cache" else "--configuration-cache"
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withGradleVersion(FunctionalGradle.version)
            .withTestKitDir(FunctionalGradle.testKitDir())
            .withEnvironment(System.getenv() + ("PROBE_DAEMON" to "daemon"))
            .withArguments("test", cache, "--stacktrace", "--no-watch-fs")
            .forwardOutput()
            .build()

        val task = read(File(dir, "build/probe/task.txt"))
        val jvm = read(File(dir, "build/probe/jvm.txt")).toMap()
        val taskSays = task.toMap()
        println("[characterization] $launcher task: $task")
        println("[characterization] $launcher jvm: jdk=${jvm["jdk.version"]} (${jvm["jdk.vendor"]}) at ${jvm["jdk.home"]}")

        // The launcher's metadata names the JDK the tests run on, whichever way it was chosen.
        assertEquals(jvm["jdk.version"], taskSays["jdk.version"], "the launcher's runtime version")
        // Gradle names some vendors its own way; the map records Gradle's name on both sides, so
        // the view only has to name the JVM's vendor.
        val gradleVendor = mapOf("Eclipse Adoptium" to "Eclipse Temurin")
        assertEquals(jvm["jdk.vendor"]?.let { gradleVendor[it] ?: it }, taskSays["jdk.vendor"], "the launcher's vendor")
        assertEquals(jvm["os.name"], taskSays["os.name"])
        assertEquals(jvm["os.arch"], taskSays["os.arch"])

        // Every -D argument the task passes, those of argument providers included, is a property
        // the JVM starts with, with that value.
        val properties = task.filter { it.first.startsWith("sys:") }
        assertTrue(properties.map { it.first }.containsAll(
            listOf("sys:probe.declared", "sys:probe.path", "sys:probe.argument", "sys:probe.bare", "sys:probe.provided"),
        ), "-D arguments the task reports: $properties")
        properties.forEach { (name, value) -> assertEquals(value, jvm[name], name) }
        // The system properties the task holds are among its -D arguments.
        task.filter { it.first.startsWith("prop:") }.forEach { (name, value) ->
            assertEquals(value, taskSays["sys:" + name.removePrefix("prop:")], name)
        }

        // A variable set on the task, one inherited from the daemon, one unset, and the locale and
        // time zone, as the JVM reads them by name.
        listOf("TZ", "LANG", "LC_ALL", "PROBE_TASK", "PROBE_DAEMON", "PROBE_UNSET").forEach { name ->
            assertEquals(jvm["env:$name"], taskSays["env:$name"], name)
        }
        assertEquals("task", jvm["env:PROBE_TASK"])
        assertEquals("daemon", jvm["env:PROBE_DAEMON"])

        // The build's own doFirst runs after the view was read: the JVM sees what the view does not.
        assertEquals("late", jvm["sys:probe.late"])
        assertEquals(null, taskSays["sys:probe.late"])
    }
}
