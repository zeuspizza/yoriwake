package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A JDK class newer than the agent's ASM reads: the read hook is not injected, and nothing else
 * changes, so the hooks report themselves incomplete rather than failing the test JVM.
 */
class ReadHooksCeilingTest {

    private val owner = "java/io/FileInputStream"

    private fun jdkClass(major: Int): ByteArray {
        val bytes = ClassLoader.getSystemResourceAsStream("$owner.class")!!.use { it.readBytes() }
        bytes[6] = (major ushr 8).toByte()
        bytes[7] = major.toByte()
        return bytes
    }

    /** The hooks' transformer with one read hook, and the set of hooks it found. */
    private fun injector(): Pair<ClassFileTransformer, Set<*>> {
        val type = Class.forName("io.github.zeuspizza.yoriwake.agent.capture.ReadHooks\$Injector")
        val constructor = type.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
        val hook = arrayOf(owner, "<init>", "(Ljava/io/File;)V", "1", "SINK")
        val injector = constructor.newInstance(listOf(hook)) as ClassFileTransformer
        val found = type.getDeclaredField("found").apply { isAccessible = true }.get(injector) as Set<*>
        return injector to found
    }

    private fun ClassFileTransformer.retransform(bytes: ByteArray): ByteArray? =
        transform(null, owner, java.io.FileInputStream::class.java, null as ProtectionDomain?, bytes)

    @Test
    fun `a JDK class at a version the agent reads gets its hook`() {
        val (injector, found) = injector()

        assertNotNull(injector.retransform(jdkClass(61)))
        assertEquals(setOf("$owner.<init>(Ljava/io/File;)V"), found)
    }

    @Test
    fun `a JDK class above the agent's ceiling is left alone and its hook is missing`() {
        val (injector, found) = injector()

        assertNull(injector.retransform(jdkClass(72)))
        assertEquals(emptySet<Any>(), found)
    }

    @Test
    fun `a class defined from bytes above the agent's ceiling has no name the agent can read`() {
        assertEquals("java/io/FileInputStream", TouchRecorder.nameIn(jdkClass(61)))
        assertNull(TouchRecorder.nameIn(jdkClass(72)))
    }
}
