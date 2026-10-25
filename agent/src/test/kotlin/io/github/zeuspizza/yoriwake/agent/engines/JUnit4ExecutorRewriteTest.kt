package io.github.zeuspizza.yoriwake.agent.engines

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rewrite of Gradle's JUnit 4 executor, which asks the agent whether to leave a class out.
 *
 * Its bytecode lands in Gradle's own worker, so a class the verifier rejects fails the host's build
 * outright. Each case loads the rewritten class and runs it, which is what verifies it; with no
 * judgement active the agent leaves nothing out.
 */
class JUnit4ExecutorRewriteTest {

    private fun rewritten(type: Class<*>, method: String, descriptor: String): ByteArray? {
        val bytes = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.readBytes()
        return JUnit4Hook.rewriteExecutor(bytes, method, descriptor)
    }

    private fun load(type: Class<*>, bytes: ByteArray): Any {
        val loader = object : ClassLoader(type.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == type.name) defineClass(name, bytes, 0, bytes.size) else super.loadClass(name, resolve)
        }
        return loader.loadClass(type.name).getDeclaredConstructor().newInstance()
    }

    @Test
    fun `Gradle 8's executor is rewritten to ask, verifies, and still runs the class`() {
        val bytes = assertNotNull(rewritten(ExecutorStandIns.Gradle8::class.java, "runTestClass", "(Ljava/lang/String;)V"))
        assertContains(String(bytes, Charsets.ISO_8859_1), "io.github.zeuspizza.yoriwake.agent.engines.JUnit4Selection")

        val executor = load(ExecutorStandIns.Gradle8::class.java, bytes)
        executor.javaClass.getMethod("execute", String::class.java).invoke(executor, TwoTests::class.java.name)

        @Suppress("UNCHECKED_CAST")
        val ran = executor.javaClass.getField("ran").get(executor) as List<String>
        assertTrue(TwoTests::class.java.name in ran)
    }

    @Test
    fun `Gradle 9's executor is rewritten to ask, verifies, and still builds the request`() {
        val bytes = assertNotNull(rewritten(ExecutorStandIns.Gradle9::class.java, "buildFilteredRequest",
            "(Ljava/lang/Class;)Lorg/junit/runner/Request;"))

        val executor = load(ExecutorStandIns.Gradle9::class.java, bytes)
        assertNotNull(executor.javaClass.getMethod("request", Class::class.java).invoke(executor, TwoTests::class.java))
    }

    @Test
    fun `an executor without the shape is left as it was`() {
        assertNull(rewritten(ExecutorStandIns.Unknown::class.java, "runTestClass", "(Ljava/lang/String;)V"))
    }
}
