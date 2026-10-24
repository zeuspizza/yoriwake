package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader
import java.util.function.Consumer
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Netty's in-tree io_uring transport: its only submission-queue writer checks each operation as it
 * is entered, and any operation outside the ones Netty itself submits is reported first.
 */
class IoUringGuardTest {

    private val holder = GuardSinkStandIn::class.java.name.replace('.', '/')
    private val check = GuardHooks.wrapFor("io/netty/channel/uring/SubmissionQueue")!!
    private val allowed = check[4].split(",").map(String::toInt)

    @Test
    fun `an operation Netty submits goes on unreported, any other is reported before the body runs`() {
        val calls = mutableListOf<List<Any?>>()
        GuardSinkStandIn.GUARD = Consumer { calls.add((it as Array<*>).toList()) }
        try {
            val queue = defined(GuardHooks.wrapped(submissionQueue(), check, holder))
            val instance = queue.getConstructor().newInstance()
            val enqueue = queue.getDeclaredMethod("enqueueSqe", *parameters).apply { isAccessible = true }
            fun enqueue(opcode: Int) = enqueue.invoke(instance, opcode.toByte(), 0.toByte(), 0.toShort(), 0, 0L, 0L,
                0, 0, 0L, 0.toShort(), 0.toShort(), 0, 0L)

            for (opcode in allowed) {
                assertEquals(42L, enqueue(opcode), "opcode $opcode")
            }
            assertEquals(emptyList(), calls)
            // Netty's open, the same through openat2, a rename, a symlink, a link, and one no kernel has yet.
            for (opcode in listOf(18, 28, 35, 38, 39, 200)) {
                assertEquals(42L, enqueue(opcode), "opcode $opcode")
            }
            assertEquals(listOf(18, 28, 35, 38, 39, 200).map { listOf<Any?>(check[3], true, it) }, calls)
        } finally {
            GuardSinkStandIn.GUARD = null
        }
    }

    @Test
    fun `no operation that takes a path or names a file is allowed`() {
        // OPENAT, STATX, OPENAT2, RENAMEAT, UNLINKAT, MKDIRAT, SYMLINKAT, LINKAT, the four xattr calls.
        for (opcode in listOf(18, 21, 28, 35, 36, 37, 38, 39, 41, 42, 43, 44)) {
            assertFalse(opcode in allowed, "opcode $opcode")
        }
        assertEquals(19, allowed.size)
    }

    @Test
    fun `a submission queue whose class file has the writer is armed before it is defined`(@TempDir dir: File) {
        assertNull(GuardHooks(null).unarmed(standIn(dir, submissionQueue()).caller, check))
        assertContains(
            GuardHooks(null).unarmed(standIn(File(dir, "other"), submissionQueue(withWriter = false)).caller, check)!!,
            "io.netty.channel.uring.SubmissionQueue declares no method enqueueSqe(BBSIJJIIJSSIJ)J",
        )
    }

    @Test
    fun `a submission queue defined without the writer counts as a guard lost`(@TempDir dir: File) {
        val lost = mutableListOf<Any>()
        val wrapper = GuardHooks(null).Wrapper(holder, Consumer { lost.add(it) })

        assertNull(wrapper.transform(standIn(dir, submissionQueue()), "io/netty/channel/uring/SubmissionQueue",
            null, null, submissionQueue(withWriter = false)))
        assertContains(lost.single() as String, "io.netty.channel.uring.SubmissionQueue was defined without its guard")
    }

    private val parameters = arrayOf(
        Byte::class.javaPrimitiveType, Byte::class.javaPrimitiveType, Short::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        Short::class.javaPrimitiveType, Short::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
    )

    /** A loader over [dir] holding [queue], as Netty's classes jar does, and its loading class. */
    private class StandIn(dir: File) : URLClassLoader(arrayOf(dir.toURI().toURL()), IoUringGuardTest::class.java.classLoader) {
        val caller: Class<*> by lazy {
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "io/netty/util/internal/NativeLibraryUtil", null,
                "java/lang/Object", null)
            writer.visitEnd()
            val bytes = writer.toByteArray()
            defineClass("io.netty.util.internal.NativeLibraryUtil", bytes, 0, bytes.size)
        }
    }

    private fun standIn(dir: File, queue: ByteArray): StandIn {
        File(dir, "io/netty/channel/uring/SubmissionQueue.class").apply { parentFile.mkdirs() }.writeBytes(queue)
        return StandIn(dir)
    }

    private fun defined(bytes: ByteArray): Class<*> = object : ClassLoader(javaClass.classLoader) {
        val defined: Class<*> = defineClass("io.netty.channel.uring.SubmissionQueue", bytes, 0, bytes.size)
    }.defined

    /**
     * Netty's SubmissionQueue as far as the guard needs it: `long enqueueSqe(byte opcode, ...)`
     * returning 42, with a branch on the opcode so its body carries a stack map frame of its own.
     */
    private fun submissionQueue(withWriter: Boolean = true): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "io/netty/channel/uring/SubmissionQueue",
            null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        if (withWriter) {
            writer.visitMethod(0, "enqueueSqe", "(BBSIJJIIJSSIJ)J", null, null).apply {
                visitCode()
                val start = Label()
                visitLabel(start)
                visitVarInsn(Opcodes.ILOAD, 2)
                val other = Label()
                visitJumpInsn(Opcodes.IFNE, other)
                visitLdcInsn(42L)
                visitInsn(Opcodes.LRETURN)
                visitLabel(other)
                visitInsn(Opcodes.LCONST_0)
                visitInsn(Opcodes.LRETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
