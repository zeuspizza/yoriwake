package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on classes whose compiled bytes change with no source change behind them: a weaver, a
 * generator or a post-processor. Only the digest of the compiled classes can see such a change, so
 * each attempt stands where that digest, or the map's record of it, is thinnest.
 */
class GeneratedBytecodeAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private fun mainClass(name: String, body: String) =
        source("src/main/java/dev/sample/$name.java", "package dev.sample;\npublic class $name { $body }")

    private fun testClass(name: String, body: String) = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.*;
        class $name { $body }
        """,
    )

    private val other = mainClass("Other", "public int two() { return 2; }")
    private val otherTest = testClass("A2OtherTest", "@Test void two() { assertEquals(2, new Other().two()); }")
    private val third = mainClass("Third", "public int three() { return 3; }")
    private val thirdTest = testClass("A3ThirdTest", "@Test void three() { assertEquals(3, new Third().three()); }")

    /** The source edit that dirties the tree and, in the weaving builds, switches the weaver on. */
    private fun changeOther(dir: File) {
        val file = File(dir, other.first)
        file.writeText(file.readText().replace("return 2;", "return 1 + 1;"))
    }

    /**
     * Stands in for a post-compile weaver: once Other's source holds `1 + 1`, [task] rewrites the
     * string "hello" in [classFile] to "HELLO" after compiling, leaving every source as it was.
     */
    private fun weaving(task: String, classFile: String) = """
        tasks.$task {
            val out = destinationDirectory
            val trigger = layout.projectDirectory.file("src/main/java/dev/sample/Other.java")
            inputs.file(trigger)
            doLast {
                if (!trigger.asFile.readText().contains("1 + 1")) return@doLast
                val file = out.get().file("$classFile").asFile
                val bytes = file.readBytes()
                val from = "hello".toByteArray()
                val to = "HELLO".toByteArray()
                var i = 0
                while (i <= bytes.size - from.size) {
                    if (from.indices.all { bytes[i + it] == from[it] }) to.copyInto(bytes, i)
                    i++
                }
                file.writeBytes(bytes)
            }
        }
    """.trimIndent()

    /** Each test class in its own JVM, so a window one opens reaches no other. */
    private fun forkingBuild(vararg extra: String) = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); forkEvery = 1 }",
    ) + "\n" + extra.joinToString("\n")

    // Track: code reading. Boundary: the capture writes the class-digest table only when a walk
    // that reads every method body finishes, while the annotation and constant tables read with
    // SKIP_CODE. A class whose code ASM rejects drops the one table and keeps the others, and the
    // selecting run consults the missing table before it walks anything.
    @Test
    fun `a class whose code the digest cannot read leaves the map without bytes and forces`(@TempDir dir: File) {
        val broken = mainClass("Broken", "public int seven() { return 7; }")
        // `bipush 7; ireturn` becomes `bipush 7; 0xff`, an opcode ASM refuses. Nothing loads Broken.
        val breakingBuild = minimalBuild + "\n" + """
            tasks.compileJava {
                val out = destinationDirectory
                doLast {
                    val file = out.get().file("dev/sample/Broken.class").asFile
                    val bytes = file.readBytes()
                    for (i in 0 until bytes.size - 2) {
                        if (bytes[i] == 0x10.toByte() && bytes[i + 1] == 0x07.toByte() && bytes[i + 2] == 0xAC.toByte()) {
                            bytes[i + 2] = 0xFF.toByte()
                        }
                    }
                    file.writeBytes(bytes)
                }
            }
        """.trimIndent()
        val result = attempt(
            dir, "dev.sample.AlphaTest", Aim.Forced("bytes-unrecorded"),
            listOf(oneClass, oneTest, secondClass, secondTest, broken, classOrderByName),
            buildScript = breakingBuild,
        ) { File(it, oneClass.first).writeText(oneClass.second.replace("n * 2", "n * 2 + 1")) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a test recorded changed bytes only if it executed that very
    // class, by exact name. The weaver here rewrites a class of the test output, the target's own,
    // so the rule holds only if a test's record names the test class it ran from.
    @Test
    fun `a test class rewritten after compilation selects the test that ran from it`(@TempDir dir: File) {
        val greeter = mainClass("Greeter", """public String greet() { return "hello"; }""")
        val target = testClass("A1GreetTest", """@Test void greets() { assertEquals("hello", new Greeter().greet()); }""")
        val result = attempt(
            dir, "dev.sample.A1GreetTest", Aim.Row("changed-bytes"),
            listOf(greeter, target, other, otherTest, third, thirdTest),
            buildScript = forkingBuild(weaving("compileTestJava", "dev/sample/A1GreetTest.class")),
        ) { changeOther(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: "recorded" means executed. A class a test only loads and reads
    // a constant field of by reflection fires no probe, so no record names it, and its rewritten
    // bytes must force rather than read as bytes nobody depends on.
    @Test
    fun `a rewritten class a test only reads by reflection forces`(@TempDir dir: File) {
        val holder = mainClass("Holder", """public static final String WORD = "hello";""")
        // By reflection: a direct reference would copy the constant into the test at compile time.
        val target = testClass(
            "A1WordTest",
            """@Test void word() throws Exception { assertEquals("hello", Holder.class.getField("WORD").get(null)); }""",
        )
        val result = attempt(
            dir, "dev.sample.A1WordTest", Aim.Forced("no-coverage-for-changed-bytes"),
            listOf(holder, target, other, otherTest, third, thirdTest),
            buildScript = forkingBuild(weaving("compileJava", "dev/sample/Holder.class")),
        ) { changeOther(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a window for changed bytes opens on a first touch of that exact
    // class. The target never executes the rewritten class; it reads the class file through the
    // class loader as a resource, which must count as a touch of the class it holds.
    @Test
    fun `a test that reads a rewritten class file as a resource is held by the window it opens`(@TempDir dir: File) {
        val greeter = mainClass("Greeter", """public String greet() { return "hello"; }""")
        val greeterTest = testClass("A2GreeterTest", """@Test void greets() { assertEquals(5, new Greeter().greet().length()); }""")
        val target = testClass(
            "A1ReaderTest",
            """
            @Test void readsTheClassFile() throws Exception {
                try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("dev/sample/Greeter.class")) {
                    assertTrue(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("hello"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ReaderTest", Aim.Row("shares-jvm-changed-bytes"),
            listOf(greeter, greeterTest, target, other, otherTest, third, thirdTest),
            buildScript = forkingBuild(weaving("compileJava", "dev/sample/Greeter.class")),
        ) { changeOther(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
