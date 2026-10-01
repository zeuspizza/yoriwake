package io.github.zeuspizza.yoriwake.agent.contract

import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps docs/contract.md true: every [AgentContract] constant is a row there with the code's
 * value, and every row names a constant that exists.
 */
class AgentContractTest {

    private val doc = File("../docs/contract.md").let { if (it.isFile) it else File("docs/contract.md") }

    /** `| `CONSTANT` | `value` | ...` rows, keyed by constant. */
    private fun documented(): Map<String, List<String>> {
        assertTrue(doc.isFile, "contract not found at ${doc.absolutePath}")
        val row = Regex("""^\|\s*`([A-Z][A-Z0-9_]*)`\s*\|\s*`([^`]*)`\s*\|""")
        return doc.readLines()
            .mapNotNull { row.find(it) }
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
    }

    private fun declared(): Map<String, String> =
        AgentContract::class.java.declaredFields
            .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.get(null).toString() }

    @Test
    fun `every constant is documented once, with its value`() {
        val documented = documented()
        declared().forEach { (name, value) ->
            val rows = documented[name].orEmpty()
            assertEquals(1, rows.size, "$name must be exactly one row of ${doc.name}, found ${rows.size}")
            assertEquals(value, rows.single(), "${doc.name} documents $name with the wrong value")
        }
    }

    @Test
    fun `every documented constant exists`() {
        val unknown = documented().keys - declared().keys
        assertTrue(unknown.isEmpty(), "${doc.name} documents constants AgentContract does not have: $unknown")
    }

    @Test
    fun `the map and the raw records are stamped under different names`() {
        // One name for two formats would let a reader take either stamp for the other.
        assertTrue(AgentContract.MAP_SCHEMA_VERSION_FILE != AgentContract.RAW_SCHEMA_VERSION_FILE)
    }
}
