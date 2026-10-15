package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The highest class-file version the agent's ASM reads, proven against ASM's own check. */
class ClassFileSupportTest {

    private fun classAt(major: Int): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "dev/sample/Probe", null, "java/lang/Object", null)
        writer.visitEnd()
        return writer.toByteArray().also {
            it[6] = (major ushr 8).toByte()
            it[7] = major.toByte()
        }
    }

    @Test
    fun `the ceiling is the highest version the agent's ASM reads`() {
        val ceiling = ClassFileSupport.asmCeiling()

        assertEquals(71, ceiling, "ASM 9.10.1 reads up to Java 27")
        ClassReader(classAt(ceiling))
        assertFailsWith<IllegalArgumentException> { ClassReader(classAt(ceiling + 1)) }
    }

    @Test
    fun `a feature version is read from either form of the specification version`() {
        assertEquals(8, ClassFileSupport.featureOf("1.8"))
        assertEquals(21, ClassFileSupport.featureOf("21"))
        assertEquals(-1, ClassFileSupport.featureOf("garbage"))
    }
}
