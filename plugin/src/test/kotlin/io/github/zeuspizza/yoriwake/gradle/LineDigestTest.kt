package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Attribute
import org.objectweb.asm.ByteVector
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.io.File
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The line digest: equal across an edit that moves only lines, different across any edit a test
 * could observe without reading a line number.
 */
class LineDigestTest {

    @TempDir
    lateinit var dir: File

    /** `dev.sample.Calc` compiled by javac from [source], with [options]. */
    private fun javac(source: String, vararg options: String): ByteArray {
        val out = File(dir, "out-${source.hashCode()}-${options.joinToString("")}").apply { mkdirs() }
        val file = File(dir, "src-${source.hashCode()}/dev/sample/Calc.java").apply {
            parentFile.mkdirs()
            writeText(source)
        }
        val exit = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, *options, "--release", "11", "-d", out.path, file.path)
        assertEquals(0, exit, "javac failed on\n$source")
        return File(out, "dev/sample/Calc.class").readBytes()
    }

    private fun line(bytes: ByteArray) = Recordability.lineDigest(bytes).let { assertNotNull(it.value, it.reason) }

    private fun same(before: String, after: String, vararg options: String) {
        val a = javac(before, *options)
        val b = javac(after, *options)
        assertFalse(a.contentEquals(b), "the edit did not change the class file, so it proves nothing")
        assertEquals(line(a), line(b))
    }

    private fun differs(before: String, after: String, vararg options: String) =
        assertNotEquals(line(javac(before, *options)), line(javac(after, *options)))

    private val calc = """
        package dev.sample;
        public class Calc {
            public int add(int left, int right) {
                int sum = left + right;
                return sum;
            }
            public String name() { return "calc"; }
        }
    """.trimIndent()

    @Test
    fun `a comment above a method leaves the line digest as it was`() {
        same(calc, calc.replace("    public int add", "    // Adds.\n    public int add"))
        same(calc, calc.replace("    public int add", "    // Adds.\n    public int add"), "-g")
    }

    @Test
    fun `blank lines leave it as it was`() {
        same(calc, calc.replace("public class Calc {", "public class Calc {\n\n\n"), "-g")
    }

    @Test
    fun `a statement split over two lines leaves it as it was`() {
        same(calc, calc.replace("int sum = left + right;", "int sum = left\n            + right;"), "-g")
    }

    @Test
    fun `two statements joined on one line leave it as it was`() {
        val before = javac(calc, "-g")
        val after = javac(calc.replace("int sum = left + right;\n        return sum;", "int sum = left + right; return sum;"), "-g")
        assertFalse(before.contentEquals(after))
        assertEquals(line(before), line(after))
    }

    @Test
    fun `a renamed local or parameter changes it`() {
        // Without -parameters, the local variable table is where a framework reads parameter names.
        differs(calc, calc.replace("int sum", "int total").replace("return sum", "return total"), "-g")
        differs(calc, calc.replace("int left", "int a").replace("left +", "a +"), "-g")
    }

    @Test
    fun `an edit to behaviour changes it`() {
        differs(calc, calc.replace("left + right", "left - right"))
        differs(calc, calc.replace("\"calc\"", "\"calculator\""))
        differs(calc, calc.replace("public String name()", "@Deprecated public String name()"))
        val lambda = calc.replace(
            "public String name() { return \"calc\"; }",
            "public java.util.function.IntUnaryOperator twice() { return n -> n * 2; }",
        )
        differs(lambda, lambda.replace("n * 2", "n + n"))
        val guarded = calc.replace(
            "public String name() { return \"calc\"; }",
            "public int parse(String s) { int n = 0; try { n = Integer.parseInt(s); } catch (NumberFormatException e) { n = -1; } return n + 1; }",
        )
        differs(guarded, guarded.replace("try { n = Integer.parseInt(s); }", "n = 1; try { n = Integer.parseInt(s); }"))
    }

    private fun klass(body: ClassWriter.() -> Unit): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/sample/StepsKt\$twoSteps\$1", null, "java/lang/Object", null)
        writer.body()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun debugMetadata(lines: IntArray, nextLines: IntArray, method: String = "twoSteps") = klass {
        visitAnnotation("Lkotlin/coroutines/jvm/internal/DebugMetadata;", true).apply {
            visit("f", "Steps.kt")
            visit("l", lines)
            visit("nl", nextLines)
            visit("m", method)
            visit("c", "dev.sample.StepsKt")
            visitEnd()
        }
    }

    @Test
    fun `a coroutine's debug metadata is line information only in its line elements`() {
        assertEquals(line(debugMetadata(intArrayOf(5, 6), intArrayOf(6, 7))), line(debugMetadata(intArrayOf(9, 10), intArrayOf(10, 11))))
        assertNotEquals(line(debugMetadata(intArrayOf(5), intArrayOf(6))), line(debugMetadata(intArrayOf(5), intArrayOf(6), method = "threeSteps")))
        // The class digest still sees it, which is what forces today.
        assertNotEquals(
            Recordability.classDigest(debugMetadata(intArrayOf(5), intArrayOf(6))).value,
            Recordability.classDigest(debugMetadata(intArrayOf(9), intArrayOf(10))).value,
        )
    }

    private fun inliner(smap: String) = klass {
        visitSource("Caller.kt", smap)
        visitAnnotation("Lkotlin/jvm/internal/SourceDebugExtension;", false).apply {
            visitArray("value").apply { visit(null, smap); visitEnd() }
            visitEnd()
        }
    }

    @Test
    fun `an inliner's SMAP and its annotation copy are left out`() {
        val before = inliner("SMAP\nCaller.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Inlined.kt\n*L\n1#1,3:1\n*E\n")
        val after = inliner("SMAP\nCaller.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Inlined.kt\n*L\n2#1,3:1\n*E\n")
        assertEquals(line(before), line(after))
        assertNotEquals(Recordability.classDigest(before).value, Recordability.classDigest(after).value)
    }

    /** A method that pushes [constant], as a compiler plugin that bakes in a line number does. */
    private fun lineConstant(constant: Int) = klass {
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "position", "()I", null, null).apply {
            visitCode()
            val start = Label()
            visitLabel(start)
            visitLineNumber(constant, start)
            visitLdcInsn(constant)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    @Test
    fun `a line number baked in as a constant changes it`() {
        assertNotEquals(line(lineConstant(12)), line(lineConstant(13)))
    }

    @Test
    fun `a line number alone does not`() {
        val at = { line: Int ->
            klass {
                visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "nothing", "()V", null, null).apply {
                    visitCode()
                    val start = Label()
                    visitLabel(start)
                    visitLineNumber(line, start)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
        }
        assertEquals(line(at(3)), line(at(30)))
    }

    private class Opaque(private val content: ByteArray) : Attribute("CharacterRangeTable") {
        override fun write(classWriter: ClassWriter?, code: ByteArray?, codeLength: Int, maxStack: Int, maxLocals: Int): ByteVector =
            ByteVector().putByteArray(content, 0, content.size)
    }

    @Test
    fun `a class carrying an attribute ASM does not know is refused`() {
        val digest = Recordability.lineDigest(klass { visitAttribute(Opaque(byteArrayOf(1, 2))) })
        assertNull(digest.value)
        assertTrue(digest.reason!!.contains("CharacterRangeTable"), digest.reason)
        // The class digest keeps reading it by name, as before.
        assertNotNull(Recordability.classDigest(klass { visitAttribute(Opaque(byteArrayOf(1, 2))) }).value)
    }

    @Test
    fun `bytes that are no class are refused`() {
        assertNull(Recordability.lineDigest(byteArrayOf(1, 2, 3)).value)
    }
}
