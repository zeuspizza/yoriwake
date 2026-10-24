package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A native load is judged where the JDK has opened a library: its resolution method calls the
 * holder before each return, and the holder reports only a library, never a miss.
 */
class ReadHooksResolutionTest {

    private val holderName = ReadHooks.HOLDER

    @Test
    fun `the holder reports a library the JDK opened, and nothing for a miss`() {
        val bytes = ReadHooks::class.java.getDeclaredMethod("holderBytes").apply { isAccessible = true }
            .invoke(null) as ByteArray
        // Same length, so the class file stays valid: a test cannot define a class into java.lang.
        val renamed = String(bytes, Charsets.ISO_8859_1).replace(holderName, "zzzz/lang/YoriwakeReadSink")
            .toByteArray(Charsets.ISO_8859_1)
        val holder = object : ClassLoader(javaClass.classLoader) {
            val defined: Class<*> = defineClass("zzzz.lang.YoriwakeReadSink", renamed, 0, renamed.size)
        }.defined
        val reported = mutableListOf<List<Any?>>()
        holder.getField("RESOLVED").set(null, Consumer<Any> { reported.add((it as Array<*>).toList()) })
        val loaded = holder.getMethod(ReadHooks.LOADED, Any::class.java, Any::class.java, Any::class.java)

        loaded.invoke(null, null, String::class.java, "/lib/absent.so")
        loaded.invoke(null, false, String::class.java, "/lib/failed.so")
        loaded.invoke(null, true, String::class.java, "/lib/a.so")
        loaded.invoke(null, Any(), String::class.java, "/lib/b.so")

        assertEquals(listOf(listOf<Any?>(String::class.java, "/lib/a.so"), listOf<Any?>(String::class.java, "/lib/b.so")), reported)
    }

    @Test
    fun `this JDK's resolution method calls the holder before every return`() {
        val hook = ReadHooks.RESOLUTIONS.single { runCatching { Class.forName(it[0].replace('/', '.')) }.isSuccess }
        val type = Class.forName("io.github.zeuspizza.yoriwake.agent.capture.ReadHooks\$Injector")
        val injector = type.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            .newInstance(listOf(arrayOf(hook[0], hook[1], hook[2], hook[3], "RESOLVED"))) as ClassFileTransformer
        val bytes = ClassLoader.getSystemResourceAsStream("${hook[0]}.class")!!.use { it.readBytes() }

        val rewritten = injector.transform(null, hook[0], Class.forName(hook[0].replace('/', '.')),
            null as ProtectionDomain?, bytes)!!

        var returns = 0
        var calls = 0
        ClassReader(rewritten).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                exceptions: Array<String>?): MethodVisitor? {
                if (name != hook[1] || descriptor != hook[2]) return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitInsn(opcode: Int) {
                        if (opcode == Opcodes.IRETURN || opcode == Opcodes.ARETURN) returns++
                    }

                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (owner == holderName && name == ReadHooks.LOADED) calls++
                    }
                }
            }
        }, 0)
        assertTrue(returns > 0, "no return found in ${hook[0]}.${hook[1]}")
        assertEquals(returns, calls)
        val found = type.getDeclaredField("found").apply { isAccessible = true }.get(injector) as Set<*>
        assertTrue(hook[0] + '.' + hook[1] + hook[2] in found)
    }
}
