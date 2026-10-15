package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `a JVM is judged against the agent's ceiling, which an override can only lower`() {
        assertEquals(null, ClassFileSupport.testJvmUnsupported("27", null))
        val above = ClassFileSupport.testJvmUnsupported("28", null)!!
        assertTrue(above.startsWith(AgentContract.TEST_JVM_UNSUPPORTED_KIND))
        assertTrue("reads up to Java 27" in above, above)
        assertTrue("reads up to Java 16" in ClassFileSupport.testJvmUnsupported("21", "60")!!)
        assertEquals(null, ClassFileSupport.testJvmUnsupported("21", "1000"))
        assertEquals(null, ClassFileSupport.testJvmUnsupported(null, "60"))
    }
}
