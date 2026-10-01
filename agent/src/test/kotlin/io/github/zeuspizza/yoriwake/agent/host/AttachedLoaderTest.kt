package io.github.zeuspizza.yoriwake.agent.host

import io.github.zeuspizza.yoriwake.agent.capture.CaptureClaim
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.engines.PlatformEvents
import io.github.zeuspizza.yoriwake.agent.platform.DecisionRecord
import io.github.zeuspizza.yoriwake.agent.platform.SelectionFilter
import io.github.zeuspizza.yoriwake.agent.select.writeSoloOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.launcher.PostDiscoveryFilter
import org.junit.platform.launcher.TestExecutionListener
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Only the copy of the agent the JVM attached acts. A second copy, defined by a loader built from
 * the test classpath and discovered by a nested launcher, must not select, capture or write.
 */
class AttachedLoaderTest {

    /** Defines every class itself from the test classpath's bytes, sharing JUnit and Kotlin, as Spring's forked loader does. */
    private class CopyingLoader(private val source: ClassLoader = AttachedLoaderTest::class.java.classLoader) :
        ClassLoader(getPlatformClassLoader()) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (SHARED.any(name::startsWith)) {
                return Class.forName(name, false, source)
            }
            synchronized(getClassLoadingLock(name)) {
                return findLoadedClass(name)
                    ?: try {
                        parent.loadClass(name)
                    } catch (notPlatform: ClassNotFoundException) {
                        findClass(name)
                    }
            }
        }

        override fun findClass(name: String): Class<*> {
            val bytes = source.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }
                ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun findResource(name: String) = source.getResource(name)

        override fun findResources(name: String) = source.getResources(name)

        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)

        companion object {
            val SHARED = listOf("org.junit.", "org.opentest4j.", "org.apiguardian.", "kotlin.")
        }
    }

    private fun copyOf(type: Class<*>): Class<*> = Class.forName(type.name, true, CopyingLoader())

    private val properties = listOf(
        AgentContract.MAP_DIR_PROPERTY,
        AgentContract.CHANGED_CLASSES_PROPERTY,
        AgentContract.SELECT_PROPERTY,
        AgentContract.RECORDS_DIR_PROPERTY,
        PROBE_FILE_PROPERTY,
    )

    @AfterEach
    fun clearProperties() = properties.forEach(System::clearProperty)

    @Test
    fun `a class the system loader resolves is the attached copy`() {
        assertTrue(AttachedLoader.attached(SelectionFilter::class.java))
    }

    @Test
    fun `a copy another loader defined is not the attached copy, asked from either copy`() {
        val loader = CopyingLoader()
        val copy = Class.forName(SelectionFilter::class.java.name, false, loader)
        val copiedCheck = Class.forName(AttachedLoader::class.java.name, true, loader)
            .getMethod("attached", Class::class.java)

        assertFalse(copy === SelectionFilter::class.java, "the loader under test did not copy the class")
        assertFalse(AttachedLoader.attached(copy))
        assertEquals(false, copiedCheck.invoke(null, copy))
        assertEquals(true, copiedCheck.invoke(null, SelectionFilter::class.java))
    }

    @Test
    fun `a class a child loader only delegates for stays the attached copy`() {
        val delegating = URLClassLoader(arrayOf(), AttachedLoaderTest::class.java.classLoader)
        assertTrue(AttachedLoader.attached(Class.forName(SelectionFilter::class.java.name, false, delegating)))
    }

    @Test
    fun `a class the system loader cannot resolve is not the attached copy`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "io/github/zeuspizza/yoriwake/agent/host/Unlisted", null,
            "java/lang/Object", null)
        writer.visitEnd()
        val unlisted = CopyingLoader().define("io.github.zeuspizza.yoriwake.agent.host.Unlisted", writer.toByteArray())

        assertFalse(AttachedLoader.attached(unlisted))
    }

    private class Descriptor(id: String) : AbstractTestDescriptor(UniqueId.parse(id), id) {
        override fun getType() = TestDescriptor.Type.TEST
        override fun getSource(): Optional<TestSource> = Optional.empty()
    }

    @Test
    fun `a copy of the filter includes a test the attached filter deselects`(@TempDir dir: File) {
        val test = "[engine:junit-jupiter]/[class:com.acme.OtherTest]/[method:t()]"
        File(dir, AgentContract.COVERAGE_FILE).writeText(
            "SUCCESSFUL\t1000000\tcom.acme.Changed\t[engine:junit-jupiter]/[class:com.acme.ChangedTest]/[method:t()]\n" +
                "SUCCESSFUL\t1000000\tcom.acme.Other\t$test\n",
        )
        writeSoloOrder(dir)
        File(dir, "schema-version").writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        System.setProperty(AgentContract.MAP_DIR_PROPERTY, dir.absolutePath)
        System.setProperty(AgentContract.CHANGED_CLASSES_PROPERTY, "com.acme.Changed")
        System.setProperty(AgentContract.SELECT_PROPERTY, "true")
        val copy = copyOf(SelectionFilter::class.java).getDeclaredConstructor().newInstance() as PostDiscoveryFilter

        val attached = SelectionFilter::class.java.getDeclaredConstructor(Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(false)
        assertTrue(attached.apply(Descriptor(test)).excluded(), "the map should deselect this test")
        assertTrue(copy.apply(Descriptor(test)).included(), "a copy deselected a nested launcher's test")
    }

    @Test
    fun `a copy of the decision record writes nothing`(@TempDir dir: File) {
        fun written(type: Class<*>, into: File): List<String> {
            val record = type.getDeclaredConstructor().newInstance()
            type.getDeclaredMethod("writeTo", File::class.java).apply { isAccessible = true }.invoke(record, into)
            return into.list().orEmpty().sorted()
        }

        assertTrue(written(DecisionRecord::class.java, File(dir, "attached")).isNotEmpty(), "the attached copy wrote nothing")
        assertEquals(emptyList(), written(copyOf(DecisionRecord::class.java), File(dir, "copy")))
    }

    @Test
    fun `a copy never takes the capture claim, even when nothing holds it`() {
        val loader = CopyingLoader()
        val take = Class.forName(CaptureClaim::class.java.name, true, loader).getMethod("take", Class::class.java)
        val events = Class.forName(PlatformEvents::class.java.name, false, loader)

        assertNull(System.getProperty(CaptureClaim.PROPERTY), "a claim is held before the test")
        assertNull(take.invoke(null, events))
        assertNull(System.getProperty(CaptureClaim.PROPERTY))
        assertNull(CaptureClaim.take(events), "the attached claim was given to a copy's capture")
        val attached = assertNotNull(CaptureClaim.take(PlatformEvents::class.java))
        attached.release()
    }

    @Test
    fun `a copy of the Platform listener does not probe`(@TempDir dir: File) {
        fun probed(type: Class<*>, file: File): Boolean {
            System.setProperty(PROBE_FILE_PROPERTY, file.absolutePath)
            (type.getDeclaredConstructor().newInstance() as TestExecutionListener).testPlanExecutionStarted(null)
            return file.exists()
        }

        assertTrue(probed(PlatformEvents::class.java, File(dir, "attached.txt")), "the attached copy did not probe")
        assertFalse(probed(copyOf(PlatformEvents::class.java), File(dir, "copy.txt")))
    }

    private companion object {
        /** ProbeReporter.ToFile's target, which a Platform listener with no records directory writes to. */
        const val PROBE_FILE_PROPERTY = "yoriwake.internal.capture.probeFile"
    }
}
