package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.function.Function
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which classes count as the JDK's own code, and when a JVM's image is not the JDK's as shipped. */
class JdkCodeTest {

    @Test
    fun `the JDK's own classes are the JDK's`() {
        val jaas = Class.forName(
            if (System.getProperty("os.name").startsWith("Windows")) "com.sun.security.auth.module.NTSystem"
            else "com.sun.security.auth.module.UnixSystem"
        )
        for (type in listOf(String::class.java, Class.forName("java.sql.Driver"), jaas)) {
            assertTrue(JdkCode.isJdk(type), type.name)
        }
    }

    @Test
    fun `a class on the boot class path is not the JDK's own`() {
        assertNull(standin.boot.BootCaller::class.java.classLoader)
        assertFalse(JdkCode.isJdk(standin.boot.BootCaller::class.java))
    }

    @Test
    fun `an image module the application loader defines is the project's`() {
        val javac = Class.forName("com.sun.tools.javac.Main")
        assertSame(ClassLoader.getSystemClassLoader(), javac.classLoader)
        assertEquals("jdk.compiler", javac.module.name)
        assertFalse(JdkCode.isJdk(javac))
    }

    @Test
    fun `only java and jdk modules are the JDK's`() {
        assertTrue(JdkCode.imageModule("java.base"))
        assertTrue(JdkCode.imageModule("jdk.security.auth"))
        assertFalse(JdkCode.imageModule("com.acme"))
        assertFalse(JdkCode.imageModule("org.graalvm.word"))
    }

    @Test
    fun `the JDK's own hidden classes are the JDK's`() {
        val identity = Function.identity<Any>().javaClass
        assertTrue(identity.isHidden)
        assertSame(Function::class.java, identity.nestHost)
        assertTrue(JdkCode.isJdk(identity))
    }

    @Test
    fun `the JDK's own lambda forms are the JDK's`() {
        // Each is its own nest host, so no nest host can speak for it.
        val form = MethodHandles.lookup()
            .findVirtual(JdkCodeTest::class.java, "lambdaFormAbove", MethodType.methodType(Class::class.java))
            .bindTo(this).invokeWithArguments() as Class<*>
        assertSame(form, form.nestHost)
        assertTrue(JdkCode.isJdk(form))
    }

    /** The first lambda form on the stack: the JDK spins one for each shape it has no class for. */
    fun lambdaFormAbove(): Class<*> = StackWalker.getInstance(
        setOf(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES)
    ).walk { frames ->
        frames.map { it.declaringClass }
            .filter { it.isHidden && it.name.startsWith("java.lang.invoke.LambdaForm$") }
            .findFirst().get()
    }

    @Test
    fun `the project's own hidden and defined classes are the project's`() {
        val lambda = Function<Any, Any> { it }.javaClass
        assertTrue(lambda.isHidden)
        assertFalse(JdkCode.isJdk(lambda))

        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(org.objectweb.asm.Opcodes.V11, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            "io/github/zeuspizza/yoriwake/agent/capture/JdkCodeTestDefined", null, "java/lang/Object", null)
        writer.visitEnd()
        assertFalse(JdkCode.isJdk(MethodHandles.lookup().defineClass(writer.toByteArray())))
    }

    @Test
    fun `a class file that cannot be found or looked up is the project's`() {
        assertTrue(JdkCode.judge(String::class.java) { it.getResource("String.class") })
        assertFalse(JdkCode.judge(String::class.java) { null })
        assertFalse(JdkCode.judge(String::class.java) { throw IllegalStateException("no resource") })
    }

    private fun altered(vararg arguments: String) = JdkCode.imageAltered { arguments.toList() }

    @Test
    fun `a patch of the base module marks the capture incomplete`() {
        assertEquals("an image module is patched: --patch-module=java.base=/x",
            altered("-Xmx1g", "--patch-module=java.base=/x"))
        assertEquals("an image module is patched: --patch-module=java.base=/x",
            altered("--patch-module", "java.base=/x"))
        assertEquals("an image module is patched: --patch-module=java.base",
            altered("--patch-module=java.base"))
    }

    @Test
    fun `a patch of a module outside the image leaves the capture as it was`() {
        assertNull(altered("--patch-module=com.acme.app=/x", "-Xmx1g"))
        assertNull(altered())
    }

    @Test
    fun `an upgrade module path marks the capture incomplete`() {
        assertEquals("an image module is upgraded: --upgrade-module-path=/u", altered("--upgrade-module-path=/u"))
        assertEquals("an image module is upgraded: --upgrade-module-path=/u", altered("--upgrade-module-path", "/u"))
    }

    @Test
    fun `arguments that cannot be read mark the capture incomplete`() {
        assertEquals(
            "the JVM's own arguments could not be read: java.lang.SecurityException: denied",
            JdkCode.imageAltered { throw SecurityException("denied") },
        )
    }
}
