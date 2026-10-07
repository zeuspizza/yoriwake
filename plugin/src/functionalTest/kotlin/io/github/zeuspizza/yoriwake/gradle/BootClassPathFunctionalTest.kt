package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A project's own class can be put on the bootstrap loader's path from a test task's JVM
 * arguments, from a Java agent's manifest, or by an agent at run time. It is still the project's
 * code: what it does is recorded as an application class's would be, and a change to it is a
 * change to the project. A class patched into a JDK module can reach that module's internals
 * unseen, so such a test JVM counts every class as touched.
 */
class BootClassPathFunctionalTest : FunctionalTestSupport() {

    /** The `boot` source set's jar is appended to the test JVM's boot class path, and nowhere else. */
    private val bootBuild = """
        plugins {
            java
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        val boot = sourceSets.create("boot")
        val bootJar = tasks.register<Jar>("bootJar") {
            archiveBaseName.set("boot")
            from(boot.output)
        }
        dependencies {
            testCompileOnly(boot.output)
            testImplementation(platform("org.junit:junit-bom:5.11.4"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }
        val bootPath = bootJar.flatMap { it.archiveFile }.get().asFile.absolutePath
        tasks.test {
            useJUnitPlatform()
            dependsOn(bootJar)
            inputs.files(bootJar)
            jvmArgs("-Xbootclasspath/a:" + bootPath)
            systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\${'$'}ClassName")
        }
    """.trimIndent()

    private val bootArgument = """jvmArgs("-Xbootclasspath/a:" + bootPath)"""

    /**
     * [bootBuild] with the `boot` jar reaching the bootstrap loader through a one-class Java agent
     * instead of the boot class path argument: its manifest gets the attributes [manifest] adds,
     * and `-javaagent` the options [agentOptions] adds.
     */
    private fun agentBuild(manifest: String, agentOptions: String) =
        bootBuild.replace(
            "val bootPath =",
            """
            val bootAgent = sourceSets.create("bootAgent")
            val bootAgentJar = tasks.register<Jar>("bootAgentJar") {
                archiveBaseName.set("boot-agent")
                from(bootAgent.output)
                manifest.attributes(mapOf("Premain-Class" to "com.acme.bootagent.Premain"$manifest))
            }
            val agentPath = bootAgentJar.flatMap { it.archiveFile }.get().asFile.absolutePath
            val bootPath =
            """.trimIndent(),
        ).replace(
            bootArgument,
            """
            dependsOn(bootAgentJar)
            inputs.files(bootAgentJar)
            jvmArgs("-javaagent:" + agentPath$agentOptions)
            """.trimIndent(),
        )

    private fun premain(body: String) = "src/bootAgent/java/com/acme/bootagent/Premain.java" to """
        package com.acme.bootagent;
        public final class Premain {
            public static void premain(String options, java.lang.instrument.Instrumentation instrumentation)
                    throws Exception {
                $body
            }
        }
    """.trimIndent()

    private val patchBuild = bootBuild.replace(
        "val bootPath =",
        """
        tasks.named<JavaCompile>("compileBootJava") {
            options.compilerArgs.addAll(listOf("--patch-module", "java.base=" + file("src/boot/java")))
        }
        val bootClasses = boot.output.classesDirs.singleFile.absolutePath
        tasks.named<JavaCompile>("compileTestJava") {
            options.compilerArgs.addAll(listOf("--patch-module", "java.base=" + bootClasses))
        }
        val bootPath =
        """.trimIndent(),
    ).replace(
        bootArgument,
        """
        dependsOn(boot.output)
        jvmArgs("--patch-module", "java.base=" + bootClasses)
        """.trimIndent(),
    )

    private fun main(name: String, body: String) =
        "src/main/java/dev/sample/$name.java" to "package dev.sample;\n$body"

    private fun test(name: String, body: String) =
        "src/test/java/dev/sample/$name.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class $name {
                $body
            }
        """.trimIndent()

    private fun names(vararg simple: String) = simple.map { "dev.sample.$it" }.toSet()

    /**
     * A class on the boot class path that asks for a native library file that does not exist: the
     * load is seen as it starts, so the fixture needs no compiler, and the failed load is caught.
     * The first test checks the class really came from the bootstrap loader.
     */
    private val codec = "src/boot/java/com/acme/nativecodec/Codec.java" to """
        package com.acme.nativecodec;
        public final class Codec {
            public static boolean load(java.io.File file) {
                try { System.load(file.getAbsolutePath()); return true; }
                catch (UnsatisfiedLinkError absent) { return false; }
            }
            public static String name() { return "codec"; }
        }
    """.trimIndent()

    /** [codec] as a new class of `java.util`, patched into `java.base` when compiled and tested. */
    private val patchedCodec = "src/boot/java/java/util/AcmeCodec.java" to codec.second
        .replace("package com.acme.nativecodec;", "package java.util;")
        .replace("class Codec", "class AcmeCodec")

    /**
     * The project captured with [codecSource] on the bootstrap loader, Tool beside it, then
     * committed. The tests call it by [codecName].
     */
    private fun captured(
        dir: File,
        buildScript: String = bootBuild,
        vararg extra: Pair<String, String>,
        codecSource: Pair<String, String> = codec,
        codecName: String = "com.acme.nativecodec.Codec",
    ) {
        build(
            dir,
            "build.gradle.kts" to buildScript,
            codecSource,
            *extra,
            main("Other", "public class Other { public int one() { return 1; } }"),
            main("Tool", "public class Tool { public static String name() { return \"tool\"; } }"),
            test(
                "P0UnrelatedTest",
                """@Test void runsFirst() {
                    assertEquals(1, new Other().one());
                    assertNull($codecName.class.getClassLoader());
                }""",
            ),
            test(
                "P1NativeTest",
                """@Test void loads() {
                    assertFalse($codecName.load(
                        new java.io.File(System.getProperty("java.io.tmpdir"), "libacmecodec.so")));
                }""",
            ),
            test("P2ToolTest", "@Test void runs() { assertTrue(Tool.name().startsWith(\"tool\")); }"),
            test("P3LaterTest", "@Test void later() { assertTrue($codecName.name().startsWith(\"codec\")); }"),
        )
        committed(dir)
        runner(dir, "test").build()
        assertEquals(4, ranTests(dir).size, "the capture ran ${ranTests(dir)}")
    }

    private fun selectAfterEditing(dir: File, edited: String, from: String, to: String): String {
        val file = File(dir, edited)
        val before = file.readText()
        file.writeText(before.replace(from, to))
        check(file.readText() != before) { "the edit changed nothing in $edited" }
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
    }

    /** After an edit to Tool, the native test and every test after it run; the one before does not. */
    private fun assertLaterTestsSelected(dir: File) {
        val output = selectAfterEditing(dir, "src/main/java/dev/sample/Tool.java", "\"tool\"", "\"tool!\"")

        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(names("P1NativeTest", "P2ToolTest", "P3LaterTest"), ranTests(dir), output)
        val firstTouches = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
            .resolve("first-touch.tsv").readLines()
        assertTrue(firstTouches.any { it.endsWith("\t*") }, "first touches: $firstTouches")
    }

    @Test
    fun `a native library loaded by a class on the boot class path selects every later test in its JVM`(
        @TempDir dir: File,
    ) {
        captured(dir)

        assertLaterTestsSelected(dir)
    }

    @Test
    fun `a native library loaded by a class in a Boot-Class-Path agent jar selects every later test in its JVM`(
        @TempDir dir: File,
    ) {
        // The agent jar and the boot jar are built into the same directory.
        captured(dir, agentBuild(manifest = ", \"Boot-Class-Path\" to \"boot.jar\"", agentOptions = ""), premain(""))

        assertLaterTestsSelected(dir)
    }

    @Test
    fun `a native library loaded by a class an agent appends to the bootstrap search selects every later test in its JVM`(
        @TempDir dir: File,
    ) {
        captured(
            dir,
            agentBuild(manifest = "", agentOptions = " + \"=\" + bootPath"),
            premain("instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(options));"),
        )

        assertLaterTestsSelected(dir)
    }

    @Test
    fun `a test JVM that patches the JDK's base module runs every test after a change`(@TempDir dir: File) {
        captured(dir, patchBuild, codecSource = patchedCodec, codecName = "java.util.AcmeCodec")
        val touches = File(dir, ".gradle/yoriwake").walkTopDown()
            .filter { it.name == AgentContract.TOUCHES_FILE }.flatMap { it.readLines() }.toList()
        assertTrue(
            touches.any {
                it.contains("\t${AgentContract.TOUCH_ALL}\tan image module is patched: --patch-module") &&
                    it.contains("java.base=")
            },
            "touches: $touches",
        )

        val output = selectAfterEditing(dir, "src/main/java/dev/sample/Tool.java", "\"tool\"", "\"tool!\"")

        assertEquals(names("P0UnrelatedTest", "P1NativeTest", "P2ToolTest", "P3LaterTest"), ranTests(dir), output)
    }

    @Test
    fun `a change to a class on the boot class path runs every test`(@TempDir dir: File) {
        captured(dir)

        val output = selectAfterEditing(dir, codec.first, "\"codec\"", "\"codec!\"")

        assertEquals("no-coverage-for-changed", decisionNotes(dir)["full-run-kind"], output)
        assertEquals(names("P0UnrelatedTest", "P1NativeTest", "P2ToolTest", "P3LaterTest"), ranTests(dir), output)
    }
}
