package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.security.CodeSource
import java.security.ProtectionDomain
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which library module jars a JVM reached and which absent names it looked up in them: every jar
 * entry lookup reports what it found, and a class lookup some jar answered is no miss.
 */
class JarTouchTest {

    private fun drained(recorder: TouchRecorder) = recorder.drain().map { it[0] to it[1] }

    private fun jar(dir: File, name: String, vararg entries: String): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        return file
    }

    /** A recorder told of [modules], with what telling it recorded drained. */
    private fun recorder(vararg modules: File): TouchRecorder {
        val listing = File.createTempFile("modules", ".tsv").apply { deleteOnExit() }
        listing.writeText(modules.joinToString("") { "${it.absolutePath}\tdev.lib:${it.nameWithoutExtension}\n" })
        return TouchRecorder().also { recorder ->
            recorder.recordJars(TouchRecorder.modulesOf(listing.absolutePath)!!)
            assertEquals(listOf(AgentContract.TOUCH_JARS_RECORDED to ""), drained(recorder))
        }
    }

    /** What a class-path search for [name] does: each jar in order until one holds it. */
    private fun search(recorder: TouchRecorder, name: String, vararg jars: File) {
        for (jar in jars) {
            ZipFile(jar).use { zip ->
                val found = zip.getEntry(name)
                recorder.entry(arrayOf<Any?>(zip, name, found))
                if (found != null) return
            }
        }
    }

    @Test
    fun `a class found in the third of three jars reaches the third, and its misses in the others do not stand`(@TempDir dir: File) {
        val a = jar(dir, "a.jar", "a/A.class")
        val b = jar(dir, "b.jar", "b/B.class")
        val c = jar(dir, "c.jar", "lib/math/Add.class")
        val recorder = recorder(a, b, c)

        search(recorder, "lib/math/Add.class", a, b, c)

        assertEquals(listOf(AgentContract.TOUCH_JAR_REACHED to c.absolutePath), drained(recorder))
    }

    @Test
    fun `a class no jar holds is a miss in its package, once`(@TempDir dir: File) {
        val a = jar(dir, "a.jar", "a/A.class")
        val b = jar(dir, "b.jar", "lib/math/Add.class")
        val recorder = recorder(a, b)

        search(recorder, "lib/math/Fast.class", a, b)
        search(recorder, "lib/math/Faster.class", a, b)

        assertEquals(listOf(AgentContract.TOUCH_MISSED to "lib/math/"), drained(recorder))
        search(recorder, "lib/math/Fastest.class", a, b)
        assertTrue(drained(recorder).isEmpty(), "a directory with a standing miss is recorded once")
    }

    @Test
    fun `a probe of an absent class after a class of its package loaded from a later jar still stands`(@TempDir dir: File) {
        val a = jar(dir, "a.jar", "a/A.class")
        val b = jar(dir, "b.jar", "b/B.class")
        val c = jar(dir, "c.jar", "lib/math/Add.class")
        val recorder = recorder(a, b, c)

        search(recorder, "lib/math/Add.class", a, b, c)
        drained(recorder)
        search(recorder, "lib/math/Fast.class", a, b, c)

        assertEquals(listOf(AgentContract.TOUCH_MISSED to "lib/math/"), drained(recorder))
    }

    @Test
    fun `a resource two of three jars lack stands as a miss though the third holds it`(@TempDir dir: File) {
        val a = jar(dir, "a.jar", "a/A.class")
        val b = jar(dir, "b.jar", "b/B.class")
        val c = jar(dir, "c.jar", "META-INF/services/dev.Spi")
        val recorder = recorder(a, b, c)

        // ServiceLoader asks every jar.
        for (jar in listOf(a, b, c)) {
            ZipFile(jar).use { zip -> recorder.entry(arrayOf<Any?>(zip, "META-INF/services/dev.Spi", zip.getEntry("META-INF/services/dev.Spi"))) }
        }

        assertEquals(
            setOf(AgentContract.TOUCH_JAR_REACHED to c.absolutePath, AgentContract.TOUCH_MISSED to "META-INF/services/"),
            drained(recorder).toSet(),
        )
    }

    @Test
    fun `a jar no module is, such as a project's own, is neither reached nor missed in`(@TempDir dir: File) {
        val module = jar(dir, "lib.jar", "lib/L.class")
        val own = jar(dir, "own.jar", "own/O.class")
        val recorder = recorder(module)

        search(recorder, "own/O.class", own)
        search(recorder, "own/Absent.class", own)

        assertTrue(drained(recorder).isEmpty())
    }

    @Test
    fun `a class defined from a module jar reaches it, and answers a miss of its name`(@TempDir dir: File) {
        val a = jar(dir, "a.jar", "a/A.class")
        val c = jar(dir, "c.jar", "lib/math/Add.class")
        val recorder = recorder(a, c)
        ZipFile(a).use { zip -> recorder.entry(arrayOf<Any?>(zip, "lib/math/Add.class", null)) }

        val domain = ProtectionDomain(CodeSource(c.toURI().toURL(), null as Array<java.security.cert.Certificate>?), null)
        TouchRecorder.LoadObserver(recorder).transform(javaClass.classLoader, "lib/math/Add", null, domain, ByteArray(0))

        assertEquals(
            listOf(AgentContract.TOUCH_LOADED to "lib.math.Add", AgentContract.TOUCH_JAR_REACHED to c.absolutePath),
            drained(recorder),
        )
    }

    @Test
    fun `an entry read from a module jar reaches it`(@TempDir dir: File) {
        val c = jar(dir, "c.jar", "data.txt")
        val recorder = recorder(c)

        ZipFile(c).use { zip -> recorder.read(arrayOf<Any?>(zip, zip.getEntry("data.txt"))) }

        assertEquals(listOf(AgentContract.TOUCH_JAR_REACHED to c.absolutePath), drained(recorder))
    }

    @Test
    fun `a manifest a test reads from a module jar reaches it, as the class loader opening the jar does not`(@TempDir dir: File) {
        val c = jar(dir, "c.jar", "META-INF/MANIFEST.MF")
        val recorder = recorder(c)

        ZipFile(c).use { zip -> recorder.entry(arrayOf<Any?>(zip, "META-INF/MANIFEST.MF", zip.getEntry("META-INF/MANIFEST.MF"))) }

        assertEquals(listOf(AgentContract.TOUCH_JAR_REACHED to c.absolutePath), drained(recorder))
    }

    @Test
    fun `a recorder told of no modules records no jar`(@TempDir dir: File) {
        val c = jar(dir, "c.jar", "lib/L.class")
        val recorder = TouchRecorder()

        ZipFile(c).use { zip -> recorder.entry(arrayOf<Any?>(zip, "lib/Absent.class", null)) }

        assertTrue(drained(recorder).isEmpty())
    }

    @Test
    fun `the holder reports each entry lookup with what it found`() {
        val bytes = ReadHooks::class.java.getDeclaredMethod("holderBytes").apply { isAccessible = true }
            .invoke(null) as ByteArray
        // Same length, so the class file stays valid: a test cannot define a class into java.lang.
        val renamed = String(bytes, Charsets.ISO_8859_1).replace(ReadHooks.HOLDER, "zzzz/lang/YoriwakeReadSink")
            .toByteArray(Charsets.ISO_8859_1)
        val holder = object : ClassLoader(javaClass.classLoader) {
            val defined: Class<*> = defineClass("zzzz.lang.YoriwakeReadSink", renamed, 0, renamed.size)
        }.defined
        val entry = holder.getMethod(ReadHooks.ENTRY, Any::class.java, Any::class.java, Any::class.java)
        entry.invoke(null, "found", "zip", "a/A.class")
        val reported = mutableListOf<List<Any?>>()
        holder.getField("ENTRY").set(null, Consumer<Any> { reported.add((it as Array<*>).toList()) })

        entry.invoke(null, null, "zip", "a/Absent.class")
        entry.invoke(null, "found", "zip", "a/A.class")

        assertEquals(listOf(listOf<Any?>("zip", "a/Absent.class", null), listOf<Any?>("zip", "a/A.class", "found")), reported)
    }

    @Test
    fun `this JDK's ZipFile getEntry calls the holder before every return`() {
        val hooks = ReadHooks.ENTRIES.filter { hook ->
            runCatching { ZipFile::class.java.getDeclaredMethod(hook[1], *parameters(hook[2])) }.isSuccess
        }
        assertTrue(hooks.isNotEmpty())
        val type = Class.forName("io.github.zeuspizza.yoriwake.agent.capture.ReadHooks\$Injector")
        val injector = type.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            .newInstance(hooks.map { arrayOf(it[0], it[1], it[2], it[3], "ENTRY") }) as ClassFileTransformer
        val bytes = ClassLoader.getSystemResourceAsStream("java/util/zip/ZipFile.class")!!.use { it.readBytes() }

        val rewritten = injector.transform(null, "java/util/zip/ZipFile", ZipFile::class.java, null as ProtectionDomain?, bytes)!!

        for (hook in hooks) {
            var returns = 0
            var calls = 0
            ClassReader(rewritten).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                    exceptions: Array<String>?): MethodVisitor? {
                    if (name != hook[1] || descriptor != hook[2]) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitInsn(opcode: Int) {
                            if (opcode == Opcodes.ARETURN) returns++
                        }

                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            if (owner == ReadHooks.HOLDER && name == ReadHooks.ENTRY) calls++
                        }
                    }
                }
            }, 0)
            assertTrue(returns > 0)
            assertEquals(returns, calls, "${hook[1]}${hook[2]}")
        }
    }

    private fun parameters(descriptor: String): Array<Class<*>> =
        org.objectweb.asm.Type.getArgumentTypes(descriptor).map { Class.forName(it.className) }.toTypedArray()
}
