package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Paths
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the read hooks' sink keeps: first touches of class and source files, nothing else. */
class TouchRecorderTest {

    private fun drained(recorder: TouchRecorder) = recorder.drain().map { it[0] to it[1] }

    @Test
    fun `a class or source file read is kept once, and any other file is not`() {
        val recorder = TouchRecorder()
        recorder.read(File("build/classes/java/main/com/acme/Codec.class"))
        recorder.read(Paths.get("src/main/kotlin/com/acme/Utils.kt"))
        recorder.read(File("build/classes/java/main/com/acme/Codec.class"))
        recorder.read(File("src/test/resources/registry.yaml"))

        assertEquals(
            listOf(
                AgentContract.TOUCH_READ to "build/classes/java/main/com/acme/Codec.class",
                AgentContract.TOUCH_READ to "src/main/kotlin/com/acme/Utils.kt",
            ),
            drained(recorder),
        )
        assertEquals(emptyList(), drained(recorder), "a drain empties")
    }

    @Test
    fun `a jar entry read is kept by its entry name`(@TempDir dir: File) {
        val jar = File(dir, "lib.jar")
        ZipOutputStream(jar.outputStream()).use { it.putNextEntry(ZipEntry("com/acme/A.class")); it.closeEntry() }
        val recorder = TouchRecorder()
        ZipFile(jar).use { zip -> recorder.read(arrayOf<Any>(zip, zip.getEntry("com/acme/A.class"))) }

        assertEquals(listOf(AgentContract.TOUCH_READ to "com/acme/A.class"), drained(recorder))
    }

    @Test
    fun `a jar opened by something other than the JDK's zip code counts as every class in it`(@TempDir dir: File) {
        val recorder = TouchRecorder()
        recorder.read(File(dir, "scanned.jar"))

        assertEquals(listOf(AgentContract.TOUCH_JAR to File(dir, "scanned.jar").absolutePath), drained(recorder))
    }

    @Test
    fun `a recorder the agent never installed says it observed nothing`() {
        assertNotNull(TouchRecorder().incomplete())
    }

    @Test
    fun `a class definition is a first touch, and a redefinition of a loaded class is not`() {
        val recorder = TouchRecorder()
        val observer = TouchRecorder.LoadObserver(recorder)
        val loader = javaClass.classLoader
        observer.transform(loader, "com/acme/Generated\$\$Proxy", null, null, ByteArray(0))
        observer.transform(loader, "java/lang/String", String::class.java, null, ByteArray(0))

        assertEquals(listOf(AgentContract.TOUCH_LOADED to "com.acme.Generated\$\$Proxy"), drained(recorder))
    }

    @Test
    fun `a lookup is kept by name, and again after the plan starts even if discovery made it`() {
        val recorder = TouchRecorder()
        recorder.lookup("com.acme.ATest")
        recorder.lookup("com.acme.ATest")
        recorder.planStarted()
        recorder.lookup("com.acme.ATest")

        assertEquals(
            listOf(AgentContract.TOUCH_LOOKUP to "com.acme.ATest", AgentContract.TOUCH_LOOKUP to "com.acme.ATest"),
            drained(recorder),
        )
    }

    @Test
    fun `a call that can reach any class, such as the whole loaded set, counts as a lookup of every class`() {
        val recorder = TouchRecorder()
        recorder.lookup(Any())

        assertEquals(listOf(AgentContract.TOUCH_LOOKUP to AgentContract.FIRST_TOUCH_ANY), drained(recorder))
    }

    /** A class of that name, defined by a loader of its own, standing in for a library's loader. */
    private fun classNamed(name: String): Class<*> {
        val bytes = classBytes(name.replace('.', '/'))
        return object : ClassLoader(javaClass.classLoader) {
            val defined: Class<*> = defineClass(name, bytes, 0, bytes.size)
        }.defined
    }

    private val any = AgentContract.TOUCH_LOOKUP to AgentContract.FIRST_TOUCH_ANY

    @Test
    fun `a native library that is not on the reviewed list touches every class from its load`() {
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(TouchRecorderTest::class.java, "/opt/acme/lib/libacme.so"))
        recorder.library(arrayOf<Any>(TouchRecorderTest::class.java, "codec"))

        val why = "a native library that is not on the reviewed list was loaded: "
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to why + "libacme.so",
                AgentContract.TOUCH_ALL to why + System.mapLibraryName("codec")),
            drained(recorder),
        )
    }

    @Test
    fun `a native library the JDK loads for itself is not recorded`() {
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(String::class.java, "/jdk/lib/libnio.so"))
        recorder.library(arrayOf<Any>(Class.forName("java.sql.Driver"), "net"))

        assertEquals(emptyList(), drained(recorder))
    }

    @Test
    fun `a native library a class on the boot class path loads touches every class`() {
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(standin.boot.BootCaller::class.java, "codec"))

        assertEquals(
            listOf(AgentContract.TOUCH_ALL to
                "a native library that is not on the reviewed list was loaded: " + System.mapLibraryName("codec")),
            drained(recorder),
        )
    }

    @Test
    fun `a native library a JDK module on the platform loader loads is not recorded`() {
        // jdk.security.auth's class that loads libjaas, which the platform loader defines.
        val jaas = Class.forName(
            if (System.getProperty("os.name").startsWith("Windows")) "com.sun.security.auth.module.NTSystem"
            else "com.sun.security.auth.module.UnixSystem"
        )
        assertSame(ClassLoader.getPlatformClassLoader(), jaas.classLoader)
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(jaas, "jaas"))

        assertEquals(emptyList(), drained(recorder))
    }

    @Test
    fun `a reviewed library loaded by its own class counts as a lookup of every class, extracted name and all`() {
        val recorder = TouchRecorder()
        val snappy = classNamed("org.xerial.snappy.SnappyLoader")
        recorder.library(arrayOf<Any>(snappy,
            "/tmp/snappy-1.1.10.8-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsnappyjava.dylib"))
        recorder.library(arrayOf<Any>(classNamed("net.jpountz.util.Native"), "lz4-java"))
        // zstd-jni loads from an anonymous class nested in its loader.
        recorder.library(arrayOf<Any>(classNamed("com.github.luben.zstd.util.Native\$2"),
            "/tmp/libzstd-jni-1.5.7-201234567890.so"))

        assertEquals(listOf(any), drained(recorder))
    }

    @Test
    fun `a reviewed library's file loaded by any other class touches every class`() {
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(TouchRecorderTest::class.java,
            "/tmp/snappy-1.1.10.8-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsnappyjava.so"))

        assertEquals(AgentContract.TOUCH_ALL, drained(recorder).single().first)
    }

    @Test
    fun `JNA's dispatch library touches every class, whatever loads it`() {
        for (file in listOf("/tmp/jna123456.tmp", "/usr/lib/jni/libjnidispatch.so")) {
            val recorder = TouchRecorder()
            recorder.library(arrayOf<Any>(classNamed("com.sun.jna.Native"), file))

            assertEquals(AgentContract.TOUCH_ALL, drained(recorder).single().first, file)
        }
    }

    @Test
    fun `every reviewed entry matches the names its loader gives the file, and nothing else`() {
        val reviewed = mapOf(
            "io.netty.util.internal.NativeLibraryUtil" to listOf(
                "libnetty_transport_native_epoll_x86_64.so", "libnetty_transport_native_epoll_aarch_64123456789.so",
                "libnetty_transport_native_kqueue_aarch_648123456.dylib", "libnetty_transport_native_kqueue_x86_64.jnilib",
                "libnetty_transport_native_io_uring_x86_64998877.so",
            ),
            "org.xerial.snappy.SnappyLoader" to listOf(
                "snappy-1.1.10.8-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsnappyjava.so", "libsnappyjava.jnilib",
            ),
            "com.github.luben.zstd.util.Native\$1" to listOf("libzstd-jni-1.5.7-20.so"),
            "net.jpountz.util.Native" to listOf("liblz4-java-1727000000000.so", "liblz4-java.dylib"),
            "org.conscrypt.NativeLibraryUtil" to listOf(
                "libconscrypt_openjdk_jni-linux-x86_6417590000000000000.so", "libconscrypt_openjdk_jni.dylib",
            ),
            "org.rocksdb.NativeLibraryLoader" to listOf(
                "librocksdbjni-linux64.so", "librocksdbjni-osx-arm64.jnilib", "librocksdbjni8765432.so",
            ),
        )
        for ((loader, files) in reviewed) {
            for (file in files) {
                assertTrue(ReviewedLibraries.reviewed(loader, file), "$loader $file")
                assertFalse(ReviewedLibraries.reviewed("com.acme.Loader", file), file)
            }
        }
        for (file in listOf(
            // The in-tree io_uring transport lets any caller submit an open; not reviewed.
            "libnetty_transport_native_io_uring42_x86_64.so", "libnetty_transport_native_io_uring42123.so",
            // netty-tcnative's BoringSSL and OpenSSL builds share this name; not reviewed.
            "libnetty_tcnative_osx_aarch_64.jnilib", "libacme.so", "libshaded_netty_transport_native_epoll_x86_64.so",
        )) {
            assertFalse(ReviewedLibraries.reviewed("io.netty.util.internal.NativeLibraryUtil", file), file)
        }
        // A system library of that bare name need not be the build that was reviewed.
        assertFalse(ReviewedLibraries.reviewed("org.conscrypt.NativeLibraryUtil", "libconscrypt.so"))
        assertFalse(ReviewedLibraries.reviewed("org.sqlite.SQLiteJDBCLoader",
            "sqlite-3.53.4.0-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsqlitejdbc.so"))
    }

    /** The bytes of an empty class named [internalName]. */
    private fun classBytes(internalName: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(org.objectweb.asm.Opcodes.V11, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null,
            "java/lang/Object", null)
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a class another loader defines is a named touch, and one defined with no name is named by its bytes`() {
        val recorder = TouchRecorder()
        val observer = TouchRecorder.LoadObserver(recorder)
        val own = object : ClassLoader(javaClass.classLoader) {}
        recorder.planStarted()
        observer.transform(own, "com/acme/ATest", null, null, ByteArray(0))
        observer.transform(own, null, null, null, classBytes("com/acme/BTest"))
        observer.transform(ClassLoader.getSystemClassLoader(), null, null, null, classBytes("com/acme/C"))

        assertEquals(
            listOf(
                AgentContract.TOUCH_DEFINED to "com.acme.ATest",
                AgentContract.TOUCH_DEFINED to "com.acme.BTest",
                AgentContract.TOUCH_LOADED to "com.acme.C",
            ),
            drained(recorder),
        )
    }

    @Test
    fun `a class defined from bytes no name can be read from touches every class`() {
        val recorder = TouchRecorder()
        TouchRecorder.LoadObserver(recorder).transform(javaClass.classLoader, null, null, null, byteArrayOf(1, 2, 3))

        assertEquals(AgentContract.TOUCH_ALL, drained(recorder).single().first)
    }

    @Test
    fun `a hidden class defined from outside the JDK is kept by the name in its bytes, and the JDK's own are not`() {
        val recorder = TouchRecorder()
        // Called through the JDK's own stream code, which then stands where the JDK's hooked
        // method would, with more JDK code as its caller.
        java.util.stream.Stream.of<Any>(classBytes("com/acme/Generated")).forEach(recorder::defined)
        assertEquals(emptyList(), drained(recorder), "a JDK caller defined it")

        recorder.defined(classBytes("com/acme/ATest"))
        recorder.defined(byteArrayOf(1, 2, 3))

        assertEquals(
            listOf(AgentContract.TOUCH_DEFINED to "com.acme.ATest", AgentContract.TOUCH_ALL to
                "a class was defined from bytes whose name could not be read"),
            drained(recorder),
        )
    }

    @Test
    fun `a child process touches every class, once`() {
        val recorder = TouchRecorder()
        recorder.childProcess(null)
        recorder.childProcess(null)

        assertEquals(listOf(AgentContract.TOUCH_ALL to "a child process was started"), drained(recorder))
    }

    @Test
    fun `native code reached through the foreign-function API from outside the JDK touches every class`() {
        val recorder = TouchRecorder()
        java.util.stream.Stream.of<Any?>(null).forEach(recorder::nativeCode)
        assertEquals(emptyList(), drained(recorder), "a JDK caller linked it")

        recorder.nativeCode(null)

        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(recorder),
        )
    }

    @Test
    fun `a hooked method called through reflection or a method handle is judged by who called it`() {
        val native = TouchRecorder()
        val call = standin.Hooked::class.java.getMethod("call", Runnable::class.java)
        call.invoke(null, Runnable { native.nativeCode(null) })
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(native),
            "Method.invoke is not the caller",
        )

        val handled = TouchRecorder()
        java.lang.invoke.MethodHandles.lookup().unreflect(call)
            .invokeWithArguments(Runnable { handled.nativeCode(null) })
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(handled),
            "MethodHandle.invokeWithArguments is not the caller",
        )

        val referenced = TouchRecorder()
        standin.Hooked.referencedThroughTheJdk { referenced.nativeCode(null) }
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(referenced),
            "a method reference handed to JDK code belongs to the class that wrote it",
        )

        for ((route, through) in listOf<Pair<String, (Runnable) -> Unit>>(
            "invokeExact" to standin.Hooked.Caller::invokeExact,
            "a varargs handle" to standin.Hooked.Caller::varargs,
            "an interface proxy" to standin.Hooked.Caller::throughAnInterfaceProxy,
        )) {
            val recorder = TouchRecorder()
            through(Runnable { recorder.nativeCode(null) })
            assertEquals(
                listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
                drained(recorder),
                "$route is not the caller",
            )
        }

        val defined = TouchRecorder()
        call.invoke(null, Runnable { defined.defined(classBytes("com/acme/Generated")) })
        assertEquals(listOf(AgentContract.TOUCH_DEFINED to "com.acme.Generated"), drained(defined))
    }

    @Test
    fun `a hooked method reached through an interface proxy that JDK code calls is judged as the project's`() {
        val native = TouchRecorder()
        standin.Hooked.Caller.throughAnInterfaceProxyTheJdkCalls { native.nativeCode(null) }
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(native),
            "JDK code that calls a proxy did not choose what the proxy calls",
        )

        val defined = TouchRecorder()
        standin.Hooked.Caller.throughAnInterfaceProxyTheJdkCalls { defined.defined(classBytes("com/acme/Generated")) }
        assertEquals(listOf(AgentContract.TOUCH_DEFINED to "com.acme.Generated"), drained(defined))

        // Reflection frames with no proxy among them still leave the JDK code beyond them the caller.
        val constructed = TouchRecorder()
        var ran = false
        standin.Hooked.Caller.constructedByTheJdk {
            constructed.nativeCode(null)
            constructed.defined(classBytes("com/acme/Generated"))
            ran = true
        }
        assertTrue(ran, "the service loader constructed the provider")
        assertEquals(emptyList(), drained(constructed), "the JDK's service loader constructed it")
    }

    @Test
    fun `native code and hidden classes a class on the boot class path reaches are recorded`() {
        val native = TouchRecorder()
        standin.boot.BootCaller.call(Consumer { native.nativeCode(null) }, "value")
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(native),
        )

        val defined = TouchRecorder()
        standin.boot.BootCaller.call(Consumer { defined.defined(it) }, classBytes("com/acme/Generated"))
        assertEquals(listOf(AgentContract.TOUCH_DEFINED to "com.acme.Generated"), drained(defined))
    }

    @Test
    fun `a class that only carries a proxy's name is judged as the caller`() {
        // JDK code calls the spoof, so passing the spoof over would judge that JDK code instead.
        val recorder = TouchRecorder()
        java.util.stream.Stream.of<Any>("value").forEach(com.sun.proxy.`Spoof$1`(Consumer { recorder.nativeCode(null) }))

        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(recorder),
        )
    }
}
