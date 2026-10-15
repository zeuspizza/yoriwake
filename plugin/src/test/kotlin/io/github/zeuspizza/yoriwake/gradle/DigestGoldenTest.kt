package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals

/**
 * Class digests pinned, so an ASM upgrade that changes what a class digests to fails here: every
 * map recorded before it would then read every class as changed, and its release notes must say so.
 */
class DigestGoldenTest {

    private fun klass(name: String, body: ClassWriter.() -> Unit): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.body()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private val constant = klass("dev/sample/Limits") {
        visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "MAX", "I", null, 42).visitEnd()
    }

    private val method = klass("dev/sample/Calc") {
        val add = visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "add", "(II)I", null, null)
        add.visitCode()
        add.visitVarInsn(Opcodes.ILOAD, 0)
        add.visitVarInsn(Opcodes.ILOAD, 1)
        add.visitInsn(Opcodes.IADD)
        add.visitInsn(Opcodes.IRETURN)
        add.visitMaxs(0, 0)
        add.visitEnd()
    }

    private val annotated = klass("dev/sample/Tagged") {
        visitAnnotation("Ldev/sample/Marker;", true).apply { visit("value", "x") }.visitEnd()
        visitSource("Tagged.java", null)
    }

    @Test
    fun `class digests are unchanged by the ASM in use`() {
        assertEquals(
            listOf(
                "23ca26bef2cde651895eaf26ca7972dfaf7586c3680cde4b0132decc1af4f800",
                "5fce5e14cab2d137f64242176bfbd57d046612a9da4f56dee9ba04abfe9c4fe6",
                "0efbe42f83482478ca60e81d8e182c0711c131d0d3862a6b705cbde78f5c6d24",
            ),
            listOf(constant, method, annotated).map { Recordability.classDigest(it).value },
        )
    }
}
