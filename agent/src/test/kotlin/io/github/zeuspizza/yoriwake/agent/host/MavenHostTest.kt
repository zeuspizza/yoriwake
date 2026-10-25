package io.github.zeuspizza.yoriwake.agent.host

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MavenHostTest {

    private fun runFile(dir: File, text: String) = File(dir, AgentContract.RUN_FILE).apply { writeText(text) }

    @Test
    fun `the run file answers, never a system property of the same name`(@TempDir dir: File) {
        val host = MavenHost(runFile(dir, "${AgentContract.SELECT_PROPERTY}=false\n${AgentContract.RUN_FILE_END}\n"))
        System.setProperty(AgentContract.SELECT_PROPERTY, "true")
        try {
            assertEquals("false", host.property(AgentContract.SELECT_PROPERTY))
            assertNull(host.property(AgentContract.OBSERVE_PROPERTY))
        } finally {
            System.clearProperty(AgentContract.SELECT_PROPERTY)
        }
    }

    @Test
    fun `a run file cut short refuses the run and names only the map directory`(@TempDir dir: File) {
        val host = MavenHost(runFile(dir, "${AgentContract.SELECT_PROPERTY}=true\n${AgentContract.RECORDS_DIR_PROPERTY}=/r\n"))

        assertContains(host.property(AgentContract.REFUSED_PROPERTY)!!, "could not be read whole")
        assertEquals(AgentContract.CHANGE_SET_UNREADABLE_KIND, host.property(AgentContract.REFUSED_KIND_PROPERTY))
        assertEquals(dir.path, host.property(AgentContract.MAP_DIR_PROPERTY))
        assertNull(host.property(AgentContract.SELECT_PROPERTY))
        assertNull(host.property(AgentContract.RECORDS_DIR_PROPERTY), "a refused run must not capture")
    }

    @Test
    fun `a run file that does not exist refuses the same way`(@TempDir dir: File) {
        val host = MavenHost(File(dir, AgentContract.RUN_FILE))

        assertEquals(AgentContract.CHANGE_SET_UNREADABLE_KIND, host.property(AgentContract.REFUSED_KIND_PROPERTY))
        assertNull(host.property(AgentContract.RECORDS_DIR_PROPERTY))
    }

    @Test
    fun `Maven is chosen only by name, and its worker id is distinct per JVM, not per fork number`(@TempDir dir: File) {
        assertSame(GradleHost.INSTANCE, HostChoice.of(null, null))
        assertSame(GradleHost.INSTANCE, HostChoice.of("gradle", runFile(dir, "").path))
        val maven = HostChoice.of(AgentContract.HOST_MAVEN, runFile(dir, "").path)
        assertTrue(maven is MavenHost)
        assertTrue(maven.workerId().startsWith("${ProcessHandle.current().pid()}-"), maven.workerId())
        assertEquals(false, maven.runFinished())
    }

    @Test
    fun `no contract property is read outside the host`() {
        val allowed = setOf("\"java.specification.version\"", "\"os.name\"", "property", "DRY_RUN_PROPERTY")
        val read = Regex("""(?:System\.getProperty|Boolean\.getBoolean)\(\s*([^,)]+)""")
        val offenders = File("src/main/java").walk()
            .filter { it.isFile && it.extension == "java" && "/host/" !in it.invariantSeparatorsPath }
            .flatMap { file -> read.findAll(file.readText()).map { it.groupValues[1].trim() to file.name } }
            .filter { (argument, _) -> argument !in allowed }
            .toList()
        assertEquals(emptyList(), offenders)
    }
}
