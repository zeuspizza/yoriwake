package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.function.Consumer
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the wrappers built in these tests report, standing in for the read hooks' holder in java.lang. */
object GuardSinkStandIn {
    @JvmField
    @Volatile
    var GUARD: Consumer<Any>? = null
}

/** Conditional reviewed libraries: the wrap of a guarded native, and when a guard counts as armed. */
class GuardHooksTest {

    private val holder = GuardSinkStandIn::class.java.name.replace('.', '/')
    private val sqlite = GuardHooks.wrapFor("org/sqlite/core/NativeDB")!!

    @Test
    fun `a wrapped native reports its argument before the native runs`() {
        val calls = mutableListOf<List<Any?>>()
        GuardSinkStandIn.GUARD = Consumer { calls.add((it as Array<*>).toList()) }
        try {
            val nativeDb = defined(GuardHooks.wrapped(nativeDb(), sqlite, holder))
            val instance = nativeDb.getConstructor().newInstance()
            val method = nativeDb.getMethod("enable_load_extension", Boolean::class.javaPrimitiveType)

            for (on in listOf(true, false)) {
                // The stand-in's native is linked to nothing, so the call fails once it reaches it.
                val failure = assertFailsWith<InvocationTargetException> { method.invoke(instance, on) }
                assertTrue(failure.targetException is UnsatisfiedLinkError, failure.targetException.toString())
            }
            assertEquals(listOf(listOf<Any?>(sqlite[3], true), listOf<Any?>(sqlite[3], false)), calls)
        } finally {
            GuardSinkStandIn.GUARD = null
        }
    }

    @Test
    fun `a wrapped native without a boolean first argument reports every call and passes each argument on`() {
        val listed = GuardHooks.wrapFor("net/rubygrapefruit/platform/internal/jni/PosixFileFunctions")!!
        // The library's result type stands as Object, so no library class is needed to call it.
        val symlink = arrayOf(listed[0], listed[1], listed[2].replace("Lnet/rubygrapefruit/platform/internal/FunctionResult;",
            "Ljava/lang/Object;"), listed[3])
        val calls = mutableListOf<List<Any?>>()
        GuardSinkStandIn.GUARD = Consumer { calls.add((it as Array<*>).toList()) }
        try {
            val functions = defined(GuardHooks.wrapped(posixFileFunctions(), symlink, holder), symlink[0])
            val method = functions.getMethod("symlink", String::class.java, String::class.java, Any::class.java)

            // Linked to nothing, so it fails once it reaches the native; the verifier accepted the wrapper.
            val failure = assertFailsWith<InvocationTargetException> { method.invoke(null, "link", "Target.class", null) }
            assertTrue(failure.targetException is UnsatisfiedLinkError, failure.targetException.toString())
            assertEquals(listOf(listOf<Any?>(symlink[3], true)), calls)
        } finally {
            GuardSinkStandIn.GUARD = null
        }
    }

    @Test
    fun `a wrapped instance native with wide arguments passes each one on in its own slots`() {
        val wide = arrayOf("org/sqlite/core/NativeDB", "widen", "(JLjava/lang/String;D)J", "a wide call")
        val calls = mutableListOf<List<Any?>>()
        GuardSinkStandIn.GUARD = Consumer { calls.add((it as Array<*>).toList()) }
        try {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, wide[0], null, "java/lang/Object", null)
            writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, wide[1], wide[2], null, null).visitEnd()
            writer.visitEnd()
            val cls = defined(GuardHooks.wrapped(writer.toByteArray(), wide, holder))
            val method = cls.getMethod(wide[1], Long::class.javaPrimitiveType, String::class.java,
                Double::class.javaPrimitiveType)

            // A wrong slot or stack size fails verification; a right one reaches the unlinked native.
            val failure = assertFailsWith<InvocationTargetException> {
                method.invoke(cls.getConstructor().newInstance(), 1L, "x", 2.0)
            }
            assertTrue(failure.targetException is UnsatisfiedLinkError, failure.targetException.toString())
            assertEquals(listOf(listOf<Any?>(wide[3], true)), calls)
        } finally {
            GuardSinkStandIn.GUARD = null
        }
    }

    @Test
    fun `the renamed native is private and synthetic, and only the wrapper has the original name`() {
        val nativeDb = defined(GuardHooks.wrapped(nativeDb(), sqlite, holder))

        assertFalse(nativeDb.methods.any { it.name.startsWith(GuardHooks.PREFIX) }, "getMethods() lists the native")
        val renamed = nativeDb.getDeclaredMethod(GuardHooks.PREFIX + "enable_load_extension",
            Boolean::class.javaPrimitiveType)
        assertTrue(Modifier.isPrivate(renamed.modifiers) && renamed.isSynthetic && Modifier.isNative(renamed.modifiers))
        val wrapper = nativeDb.getMethod("enable_load_extension", Boolean::class.javaPrimitiveType)
        assertFalse(Modifier.isNative(wrapper.modifiers))
        assertTrue(Modifier.isSynchronized(wrapper.modifiers) && Modifier.isPublic(wrapper.modifiers))
    }

    @Test
    fun `a guard class defined without its native counts as a guard lost, and is left as it is`(@TempDir dir: File) {
        val lost = mutableListOf<Any>()
        val hooks = GuardHooks(null)
        val wrapper = hooks.Wrapper(holder, Consumer { lost.add(it) })
        val loader = StandInLoader(dir)

        assertNull(wrapper.transform(loader, "org/sqlite/core/NativeDB", null, null, nativeDb(withNative = false)))
        assertContains(lost.single() as String, "org.sqlite.core.NativeDB was defined without its guard")
    }

    @Test
    fun `a guard is armed in a loader that defined its class wrapped, and in no other`(@TempDir dir: File) {
        val hooks = GuardHooks(null)
        val wrapper = hooks.Wrapper(holder, Consumer { })
        val wrapped = StandInLoader(File(dir, "wrapped"))
        assertNotNull(wrapper.transform(wrapped, "org/sqlite/core/NativeDB", null, null, nativeDb()))

        assertNull(hooks.unarmed(wrapped.caller, sqlite))
        // A loader that has not defined it yet, whose class file would not take the wrap.
        val unwrappable = jarDir(File(dir, "other"), nativeDb(withNative = false))
        assertContains(hooks.unarmed(unwrappable.caller, sqlite)!!, "declares no native enable_load_extension(Z)I")
    }

    @Test
    fun `a guard class not yet defined is armed when its class file declares the native`(@TempDir dir: File) {
        assertNull(GuardHooks(null).unarmed(jarDir(dir, nativeDb()).caller, sqlite))
    }

    @Test
    fun `a guard class not found beside the loading class leaves the guard unarmed`(@TempDir dir: File) {
        assertContains(GuardHooks(null).unarmed(StandInLoader(dir).caller, sqlite)!!,
            "org.sqlite.core.NativeDB is not found beside")
    }

    @Test
    fun `no guard is armed where native methods cannot be wrapped`(@TempDir dir: File) {
        val hooks = GuardHooks.none("this JVM cannot wrap a native method")
        assertEquals("this JVM cannot wrap a native method", hooks.unarmed(jarDir(dir, nativeDb()).caller, sqlite))
    }

    @Test
    fun `only a version reviewed, read beside the guard class, is listed`(@TempDir dir: File) {
        val pom = "META-INF/maven/org.xerial/sqlite-jdbc/pom.properties"
        fun unlisted(version: String?): String? {
            val root = File(dir, version ?: "none")
            val loader = jarDir(root, nativeDb())
            version?.let { File(root, pom).apply { parentFile.mkdirs() }.writeText("version=$it\n") }
            return GuardHooks.unlisted(loader.caller, sqlite[0], pom,
                arrayOf("3.53.4.0"))
        }

        assertNull(unlisted("3.53.4.0"))
        assertEquals("its version 3.53.5.0 is not one reviewed", unlisted("3.53.5.0"))
        assertContains(unlisted(null)!!, "its version could not be read")
    }

    @Test
    fun `a version read from a class's string constant is listed only when it is one reviewed`(@TempDir dir: File) {
        fun unlisted(version: String?): String? {
            val root = File(dir, version ?: "none")
            val loader = jarDir(root, nativeDb())
            if (version != null) {
                val writer = ClassWriter(0)
                writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                    "org/sqlite/core/NativeVersion", null, "java/lang/Object", null)
                writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "VERSION",
                    "Ljava/lang/String;", null, version).visitEnd()
                writer.visitEnd()
                File(root, "org/sqlite/core/NativeVersion.class").writeBytes(writer.toByteArray())
            }
            return GuardHooks.unlisted(loader.caller, sqlite[0], "org/sqlite/core/NativeVersion.class#VERSION",
                arrayOf("abc", "def"))
        }

        assertNull(unlisted("def"))
        assertEquals("its version 123 is not one reviewed", unlisted("123"))
        assertContains(unlisted(null)!!, "its version could not be read")
    }

    /** A loader over [dir], as a library's jar, that defines a stand-in for the library's own loading class. */
    private class StandInLoader(dir: File) : URLClassLoader(arrayOf(dir.toURI().toURL()), GuardHooksTest::class.java.classLoader) {
        val caller: Class<*> by lazy {
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "org/sqlite/SQLiteJDBCLoader", null, "java/lang/Object", null)
            writer.visitEnd()
            val bytes = writer.toByteArray()
            defineClass("org.sqlite.SQLiteJDBCLoader", bytes, 0, bytes.size)
        }
    }

    /** A loader over [dir] holding [nativeDb], as a library's jar does. */
    private fun jarDir(dir: File, nativeDb: ByteArray): StandInLoader {
        File(dir, "org/sqlite/core/NativeDB.class").apply { parentFile.mkdirs() }.writeBytes(nativeDb)
        return StandInLoader(dir)
    }

    private fun defined(bytes: ByteArray, name: String = "org/sqlite/core/NativeDB"): Class<*> =
        object : ClassLoader(javaClass.classLoader) {
            val defined: Class<*> = defineClass(name.replace('/', '.'), bytes, 0, bytes.size)
        }.defined

    /** native-platform's PosixFileFunctions as far as the guard needs it: its static native symlink. */
    private fun posixFileFunctions(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "net/rubygrapefruit/platform/internal/jni/PosixFileFunctions", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "symlink",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** sqlite-jdbc's NativeDB as far as the guard needs it: a public synchronized native (Z)I. */
    private fun nativeDb(withNative: Boolean = true): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "org/sqlite/core/NativeDB", null,
            "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        if (withNative) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNCHRONIZED or Opcodes.ACC_NATIVE,
                "enable_load_extension", "(Z)I", null, null).visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
