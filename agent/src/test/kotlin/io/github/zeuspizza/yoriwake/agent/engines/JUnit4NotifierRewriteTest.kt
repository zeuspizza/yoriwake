package io.github.zeuspizza.yoriwake.agent.engines

import org.junit.jupiter.api.Test
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.CheckClassAdapter
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.instrument.ClassFileTransformer
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rewrite of JUnit 4's RunNotifier, which runs inside every plain JUnit 4 test JVM.
 *
 * Whatever the injected call does -- fail to link because the notifier's loader cannot see this
 * agent, or throw -- the notification must go on exactly as it would without us.
 */
class JUnit4NotifierRewriteTest {

    private val notifierName = "org/junit/runner/notification/RunNotifier"
    private val eventsName = "io.github.zeuspizza.yoriwake.agent.engines.JUnit4Events"

    private fun bytesOf(type: Class<*>): ByteArray =
        type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.readBytes()

    /** Through the installed transformer itself, which rewrites whatever it is told is the notifier. */
    private fun rewrite(bytes: ByteArray): ByteArray {
        val transformer = Class.forName("io.github.zeuspizza.yoriwake.agent.engines.JUnit4Hook\$Transformer")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance() as ClassFileTransformer
        val rewritten = transformer.transform(null, notifierName, null, null, bytes)
        assertNotNull(rewritten, "the notifier could not be rewritten")
        return rewritten
    }

    /**
     * JUnit 4 alone, with nothing of this agent visible, as under an isolating runner -- or, for a
     * stub notifier, the test's own loader. The rewritten notifier is defined here, beside the rest
     * of its package, and optionally a stand-in for the event recorder.
     */
    private fun loaderFor(
        rewritten: ByteArray,
        events: ByteArray? = null,
        notifier: String = RunNotifier::class.java.name,
        parent: ClassLoader? = null,
    ) =
        object : URLClassLoader(
            if (parent == null) arrayOf(RunNotifier::class.java.protectionDomain.codeSource.location) else arrayOf(),
            parent ?: getPlatformClassLoader(),
        ) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name) ?: when {
                    name == notifier -> defineClass(name, rewritten, 0, rewritten.size)
                    name == eventsName && events != null -> defineClass(name, events, 0, events.size)
                    else -> super.loadClass(name, resolve)
                }
            }
        }

    /** Fires every hooked notification on a notifier from [loader]; the count JUnit's own listener saw. */
    private fun fireAll(loader: ClassLoader): Int {
        val notifier = loader.loadClass(RunNotifier::class.java.name).getDeclaredConstructor().newInstance()
        val description = loader.loadClass(Description::class.java.name)
            .getMethod("createTestDescription", String::class.java, String::class.java, java.io.Serializable::class.java)
            .invoke(null, "Sample", "test", "Sample.test")
        val failure = loader.loadClass(Failure::class.java.name)
            .getConstructor(description.javaClass, Throwable::class.java)
            .newInstance(description, AssertionError("expected"))
        val result = loader.loadClass(Result::class.java.name).getDeclaredConstructor().newInstance()
        val listener = result.javaClass.getMethod("createListener").invoke(result)
        val type = notifier.javaClass
        type.getMethod("addListener", listener.javaClass.superclass).invoke(notifier, listener)
        type.getMethod("fireTestStarted", description.javaClass).invoke(notifier, description)
        type.getMethod("fireTestFailure", failure.javaClass).invoke(notifier, failure)
        type.getMethod("fireTestFinished", description.javaClass).invoke(notifier, description)
        return result.javaClass.getMethod("getRunCount").invoke(result) as Int
    }

    /** An event recorder whose every entry point throws, as a broken one would. */
    private fun throwingEvents(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, eventsName.replace('.', '/'), null, "java/lang/Object", null)
        for (hook in listOf("started", "finished", "failed")) {
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, hook, "(Ljava/lang/Object;)V", null, null)
            method.visitCode()
            method.visitTypeInsn(Opcodes.NEW, "java/lang/Error")
            method.visitInsn(Opcodes.DUP)
            method.visitLdcInsn("recorder broke in $hook")
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Error", "<init>", "(Ljava/lang/String;)V", false)
            method.visitInsn(Opcodes.ATHROW)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun verificationErrors(rewritten: ByteArray): String {
        val out = StringWriter()
        CheckClassAdapter.verify(ClassReader(rewritten), javaClass.classLoader, false, PrintWriter(out))
        return out.toString()
    }

    @Test
    fun `a notifier whose loader cannot see the agent still notifies`() {
        val loader = loaderFor(rewrite(bytesOf(RunNotifier::class.java)))
        assertEquals(1, fireAll(loader))
    }

    @Test
    fun `a throwing event recorder does not reach the notifier's caller`() {
        val loader = loaderFor(rewrite(bytesOf(RunNotifier::class.java)), throwingEvents())
        assertEquals(1, fireAll(loader))
    }

    /** The hook each of the real notifier's methods calls after the rewrite, by method name. */
    private fun hooksCalled(rewritten: ByteArray): Map<String, List<String>> {
        val calls = mutableMapOf<String, MutableList<String>>()
        ClassReader(rewritten).accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
            ) = object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(opcode: Int, owner: String, hook: String, desc: String, itf: Boolean) {
                    if (owner == eventsName.replace('.', '/')) calls.getOrPut(name) { mutableListOf() } += hook
                }
            }
        }, 0)
        return calls
    }

    @Test
    fun `an assumption failure is recorded as a failure`() {
        // JUnit 4 names it fireTestAssumptionFailed; a test skipped by an assumption must not reach the
        // map as a pass, or it can be deselected on a machine where the assumption holds.
        val hooks = hooksCalled(rewrite(bytesOf(RunNotifier::class.java)))
        assertEquals(listOf("failed"), hooks["fireTestAssumptionFailed"], "hooks: $hooks")
    }

    @Test
    fun `the rewritten notifier passes the verifier`() {
        val errors = verificationErrors(rewrite(bytesOf(RunNotifier::class.java)))
        assertTrue(errors.isEmpty(), errors)
    }

    @Test
    fun `a hooked method with no operand stack of its own passes the verifier`() {
        val errors = verificationErrors(rewrite(bytesOf(BareNotifier::class.java)))
        assertTrue(errors.isEmpty(), errors)
    }

    @Test
    fun `a hooked method whose first instruction is a branch target still runs`() {
        // The throwing recorder keeps this test out of the real one's static state.
        val loader = loaderFor(
            rewrite(bytesOf(BareNotifier::class.java)), throwingEvents(), BareNotifier::class.java.name, javaClass.classLoader,
        )
        val type = loader.loadClass(BareNotifier::class.java.name)
        val notifier = type.getDeclaredConstructor().newInstance()
        val failure = Failure(Description.createTestDescription("Sample", "test"), AssertionError("expected"))
        type.getMethod("fireTestStarted", Description::class.java).invoke(notifier, failure.description)
        type.getMethod("fireTestFailure", Failure::class.java).invoke(notifier, failure)
        assertEquals(3, type.getField("loops").getInt(notifier))
    }
}
