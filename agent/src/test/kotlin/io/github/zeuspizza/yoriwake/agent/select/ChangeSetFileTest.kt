package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.StringWriter
import java.util.Properties
import java.util.function.UnaryOperator
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The change set handed over in a file rather than on the test JVM's command line. */
class ChangeSetFileTest {

    private fun system(vararg properties: Pair<String, String>): UnaryOperator<String?> {
        val values = mapOf(*properties)
        return UnaryOperator { values[it] }
    }

    private fun written(file: File, vararg properties: Pair<String, String>, end: Boolean = true): File {
        val text = StringWriter().also { out ->
            Properties().apply { properties.forEach { (k, v) -> setProperty(k, v) } }.store(out, null)
        }.toString()
        file.writeText(text + if (end) AgentContract.CHANGE_SET_END + "\n" else "")
        return file
    }

    @Test
    fun `the file answers for the change set, and the properties for everything else`(@TempDir dir: File) {
        val file = written(
            File(dir, "change-set"),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha,dev.Beta",
            AgentContract.UNMAPPABLE_PATHS_PROPERTY to "a b/c=d:e\\f.txt",
        )
        val inputs = FilterRule.resolveInputs(system(
            AgentContract.CHANGE_SET_FILE_PROPERTY to file.path,
            AgentContract.SELECT_PROPERTY to "true",
            // Set by hand beside the file: the file alone answers.
            AgentContract.CHANGED_BYTES_PROPERTY to "dev.Stale",
        ))

        assertEquals("dev.Alpha,dev.Beta", inputs.apply(AgentContract.CHANGED_CLASSES_PROPERTY))
        assertEquals("a b/c=d:e\\f.txt", inputs.apply(AgentContract.UNMAPPABLE_PATHS_PROPERTY))
        assertNull(inputs.apply(AgentContract.CHANGED_BYTES_PROPERTY))
        assertEquals("true", inputs.apply(AgentContract.SELECT_PROPERTY))
        assertNull(FilterRule.refusalFrom(inputs))
    }

    @Test
    fun `a change-set file that is missing or cut short refuses, so everything runs`(@TempDir dir: File) {
        // Read as an empty change set, either would narrow with nothing known to have changed.
        val cut = written(File(dir, "cut"), AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha", end = false)
        for (path in listOf(File(dir, "missing").path, cut.path)) {
            val inputs = FilterRule.resolveInputs(system(
                AgentContract.CHANGE_SET_FILE_PROPERTY to path,
                AgentContract.SELECT_PROPERTY to "true",
            ))

            assertContains(FilterRule.refusalFrom(inputs).orEmpty(), path)
            assertEquals(AgentContract.CHANGE_SET_UNREADABLE_KIND, inputs.apply(AgentContract.REFUSED_KIND_PROPERTY))
            assertNull(inputs.apply(AgentContract.CHANGED_CLASSES_PROPERTY))
            assertEquals(Verdict.DAEMON_REFUSED, FilterRule.verdictFor(inputs, null, "[engine:junit-jupiter]", true))
        }
    }

    @Test
    fun `a refusal the daemon made keeps its own reason beside an unreadable file`(@TempDir dir: File) {
        val inputs = FilterRule.resolveInputs(system(
            AgentContract.CHANGE_SET_FILE_PROPERTY to File(dir, "missing").path,
            AgentContract.REFUSED_PROPERTY to "a constant changed",
            AgentContract.REFUSED_KIND_PROPERTY to "constant-changed",
        ))

        assertEquals("a constant changed", FilterRule.refusalFrom(inputs))
        assertEquals("constant-changed", inputs.apply(AgentContract.REFUSED_KIND_PROPERTY))
    }
}
