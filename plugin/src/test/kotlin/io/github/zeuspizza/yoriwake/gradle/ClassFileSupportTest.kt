package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileSupport
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The highest class-file version each reader takes. JaCoCo's versions and the Java version each
 * officially supports were read from its change log on 2026-10-15; Gradle 8.14 defaults to JaCoCo
 * 0.8.13 and Gradle 9.8 to 0.8.15.
 */
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
    fun `the ceiling is the highest version the bundled ASM reads`() {
        val ceiling = ClassFileSupport.asmCeiling()

        assertEquals(71, ceiling, "ASM 9.10.1 reads up to Java 27")
        ClassReader(classAt(ceiling))
        assertFailsWith<IllegalArgumentException> { ClassReader(classAt(ceiling + 1)) }
    }

    @Test
    fun `a listed JaCoCo version answers its official ceiling`() {
        assertEquals(ClassFileSupport.JacocoCeiling(25, null), ClassFileSupport.jacocoCeiling("0.8.14"))
        assertEquals(ClassFileSupport.JacocoCeiling(26, null), ClassFileSupport.jacocoCeiling("0.8.15"))
        assertEquals(ClassFileSupport.JacocoCeiling(22, null), ClassFileSupport.jacocoCeiling("0.8.12"))
        assertNull(ClassFileSupport.jacocoCeiling("0.8.13").note)
    }

    @Test
    fun `a JaCoCo version newer than the table takes the newest row and says so`() {
        val ceiling = ClassFileSupport.jacocoCeiling("0.9.0")

        assertEquals(26, ceiling.java)
        assertNotNull(ceiling.note)
        assertEquals(26, ClassFileSupport.jacocoCeiling("0.8.16-SNAPSHOT").java)
    }

    @Test
    fun `an unlisted older JaCoCo version takes the next lower row`() {
        assertEquals(ClassFileSupport.JacocoCeiling(20, null), ClassFileSupport.jacocoCeiling("0.8.10"))
    }

    @Test
    fun `an unreadable JaCoCo version takes the lowest row`() {
        val ceiling = ClassFileSupport.jacocoCeiling("garbage")

        assertEquals(9, ceiling.java)
        assertNotNull(ceiling.note)
        assertEquals(9, ClassFileSupport.jacocoCeiling(null).java)
    }

    @Test
    fun `the major version is read from the header alone`() {
        assertEquals(72, ClassFileSupport.majorOf(classAt(72)))
        assertEquals(-1, ClassFileSupport.majorOf(ByteArray(7)))
        assertEquals(27, ClassFileSupport.javaOf(71))
    }
}
