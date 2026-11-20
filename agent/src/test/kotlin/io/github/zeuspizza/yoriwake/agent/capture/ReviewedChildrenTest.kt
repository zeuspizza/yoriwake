package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader
import java.util.function.Consumer
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewedChildrenTest {

    private val java = System.getProperty("java.home") + File.separatorChar + "bin" + File.separatorChar +
        (if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java")
    private val pid = ProcessHandle.current().pid().toString()
    private val temp = System.getProperty("java.io.tmpdir")

    /**
     * A directory standing in for Byte Buddy's agent jar at [version] (no version file when null):
     * `ByteBuddyAgent`, whose `run` hands an object to a consumer, so the consumer's call comes from
     * it, and a class of another name with the same method.
     */
    private class ByteBuddyJar(val dir: File, version: String?) {
        val loader: URLClassLoader

        init {
            write("net/bytebuddy/agent/ByteBuddyAgent")
            write("net/bytebuddy/agent/ByteBuddyAgent\$Installer")
            write("com/acme/Starter")
            version?.let {
                File(dir, "META-INF/maven/net.bytebuddy/byte-buddy-agent/pom.properties")
                    .apply { parentFile.mkdirs() }.writeText("version=$it\n")
            }
            loader = URLClassLoader(arrayOf(dir.toURI().toURL()), ReviewedChildrenTest::class.java.classLoader)
        }

        val agent: Class<*> get() = loader.loadClass("net.bytebuddy.agent.ByteBuddyAgent")
        val other: Class<*> get() = loader.loadClass("com.acme.Starter")
        val nested: Class<*> get() = loader.loadClass("net.bytebuddy.agent.ByteBuddyAgent\$Installer")

        /** Calls [sink] with [command] from [from]'s frame. */
        fun startFrom(from: Class<*>, sink: Consumer<Any?>, command: Any?) {
            from.getMethod("run", Consumer::class.java, Any::class.java).invoke(null, sink, command)
        }

        private fun write(name: String) {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run",
                "(Ljava/util/function/Consumer;Ljava/lang/Object;)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept",
                    "(Ljava/lang/Object;)V", true)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            writer.visitEnd()
            File(dir, "$name.class").apply { parentFile.mkdirs() }.writeBytes(writer.toByteArray())
        }
    }

    /** The command `ByteBuddyAgent.install` has the child run, from [jar]. */
    private fun attacher(jar: ByteBuddyJar) = arrayOf(
        java, "-Dnet.bytebuddy.agent.attacher.dump=", "-cp", jar.dir.path, "net.bytebuddy.agent.Attacher",
        "com.sun.tools.attach.VirtualMachine", pid, jar.dir.path, "false", "",
    )

    private fun drained(recorder: TouchRecorder) = recorder.drain().map { it[0] to it[1] }

    @Test
    fun `Byte Buddy's attacher started by ByteBuddyAgent at a reviewed version touches nothing`(@TempDir dir: File) {
        val jar = ByteBuddyJar(dir, "1.15.4")
        val recorder = TouchRecorder()

        jar.startFrom(jar.agent, recorder::childProcess, attacher(jar))

        assertEquals(emptyList(), drained(recorder))
        assertTrue(ReviewedChildren.reviewed(jar.agent, attacher(jar), false))
        assertTrue(ReviewedChildren.reviewed(jar.nested, attacher(jar), false), "a class nested in it")
    }

    @Test
    fun `the attacher started by another class touches every class`(@TempDir dir: File) {
        val jar = ByteBuddyJar(dir, "1.15.4")
        val recorder = TouchRecorder()

        jar.startFrom(jar.other, recorder::childProcess, attacher(jar))

        assertEquals(listOf(AgentContract.TOUCH_ALL to "a child process was started"), drained(recorder))
        assertFalse(ReviewedChildren.reviewed(jar.other, attacher(jar), false))
        assertFalse(ReviewedChildren.reviewed(null, attacher(jar), false))
    }

    @Test
    fun `a child started while the agent is already recording touches every class without a review`(
        @TempDir dir: File,
    ) {
        val jar = ByteBuddyJar(dir, "1.15.4")
        val recorder = TouchRecorder()
        recorder.inside.set(true)
        try {
            jar.startFrom(jar.agent, recorder::childProcess, attacher(jar))
        } finally {
            recorder.inside.remove()
        }

        assertEquals(listOf(AgentContract.TOUCH_ALL to "a child process was started"), drained(recorder))
    }

    @Test
    fun `the attacher of a Byte Buddy version not reviewed, or of none read, is not reviewed`(@TempDir dir: File) {
        for (version in listOf("1.13.0", "1.18.18", null)) {
            val jar = ByteBuddyJar(File(dir, version ?: "none"), version)
            assertFalse(ReviewedChildren.reviewed(jar.agent, attacher(jar), false), "$version")
        }
    }

    @Test
    fun `the version of a multi-release jar is read from its root, not beside the versioned class`(
        @TempDir dir: File,
    ) {
        val classes = ByteBuddyJar(File(dir, "classes"), "1.17.7")
        val jar = File(dir, "byte-buddy-agent-1.17.7.jar")
        JarOutputStream(jar.outputStream(), Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }).use { out ->
            val agent = File(classes.dir, "net/bytebuddy/agent/ByteBuddyAgent.class").readBytes()
            for ((name, bytes) in listOf(
                "net/bytebuddy/agent/ByteBuddyAgent.class" to agent,
                "META-INF/versions/9/net/bytebuddy/agent/ByteBuddyAgent.class" to agent,
                "META-INF/maven/net.bytebuddy/byte-buddy-agent/pom.properties" to "version=1.17.7\n".toByteArray(),
            )) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
            val agent = loader.loadClass("net.bytebuddy.agent.ByteBuddyAgent")
            val command = attacher(classes)
            command[3] = jar.path
            command[7] = jar.path
            assertTrue(loader.getResource("net/bytebuddy/agent/ByteBuddyAgent.class").toString()
                .contains("META-INF/versions/9/"), "the JDK reads the versioned copy")
            assertTrue(ReviewedChildren.reviewed(agent, command, false))
        }
    }

    @Test
    fun `the attacher is not reviewed while the environment sets launcher options`(@TempDir dir: File) {
        val jar = ByteBuddyJar(dir, "1.15.4")
        assertFalse(ReviewedChildren.reviewed(jar.agent, attacher(jar), true))
    }

    @Test
    fun `Byte Buddy's temporary copies of the attacher and the agent are reviewed`(@TempDir dir: File) {
        val jar = ByteBuddyJar(dir, "1.15.4")
        val command = attacher(jar)
        command[3] = File(temp, "byteBuddyAttacher123.jar").path
        command[7] = File(temp, "byteBuddyAgent456.jar").path
        command[9] = "=an argument"
        assertTrue(ReviewedChildren.reviewed(jar.agent, command, false))
    }

    @Test
    fun `any other command from ByteBuddyAgent is not reviewed`(@TempDir dir: File) {
        val jar = ByteBuddyJar(dir, "1.15.4")
        val elsewhere = File(dir, "elsewhere.jar").path
        val changes = mapOf<String, (Array<String>) -> Unit>(
            "a native agent" to { it[8] = "true" },
            "another JVM" to { it[6] = "1" },
            "another launcher" to { it[0] = "/usr/bin/java" },
            "a second class path entry" to { it[3] = it[3] + File.pathSeparator + elsewhere },
            "another class path entry" to { it[3] = elsewhere },
            "another agent jar" to { it[7] = elsewhere },
            "a temporary jar of another name" to { it[3] = File(temp, "acme.jar").path },
            "another main class" to { it[4] = "com.acme.Main" },
            "another attach type" to { it[5] = "com.acme.VirtualMachine" },
            "another option" to { it[1] = "-javaagent:" + elsewhere },
        )
        for ((change, apply) in changes) {
            assertFalse(ReviewedChildren.reviewed(jar.agent, attacher(jar).also(apply), false), change)
        }
        assertFalse(ReviewedChildren.reviewed(jar.agent, attacher(jar).copyOf(9), false), "a shorter command")
        assertFalse(ReviewedChildren.reviewed(jar.agent, arrayOf(*attacher(jar), "more"), false), "a longer one")
        assertFalse(ReviewedChildren.reviewed(jar.agent, null, false), "no command")
        assertFalse(ReviewedChildren.reviewed(jar.agent, "a string", false), "a command of another type")
    }
}
