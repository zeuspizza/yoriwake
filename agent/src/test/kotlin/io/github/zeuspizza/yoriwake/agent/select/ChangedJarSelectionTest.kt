package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A library module a dependency bump moved runs the tests from their JVM's first touch of its jar
 * on, and a directory whose entry names it changed runs them from their JVM's first lookup of an
 * absent name in it on.
 */
class ChangedJarSelectionTest {

    private fun id(name: String) = "[engine:junit-jupiter]/[class:com.acme.$name]/[method:t()]"

    private val tests = listOf("EarlyTest" to 2, "JsonTest" to 4, "LateTest" to 6)

    /** Three tests in JVM `j` at sequences 2, 4 and 6, and one in JVM `k`. */
    private fun map(dir: File, jarTouches: List<String>?, missTouches: List<String>? = listOf(recorded("j"), recorded("k"))): MapReader.Result {
        val all = tests + ("OtherJvmTest" to 2)
        File(dir, "coverage.tsv").writeText(all.joinToString("") { (name, _) -> "SUCCESSFUL\t1000000\tcom.acme.Code\t${id(name)}\n" })
        File(dir, AgentContract.POSITIONS_FILE).writeText(
            tests.joinToString("") { (name, at) -> "j\t$at\t${id(name)}\n" } + "k\t2\t${id("OtherJvmTest")}\n"
        )
        File(dir, AgentContract.FIRST_TOUCH_FILE).writeText("")
        File(dir, AgentContract.NAMED_TOUCH_FILE).writeText("")
        jarTouches?.let { File(dir, AgentContract.JAR_TOUCH_FILE).writeText(it.joinToString("") { row -> "$row\n" }) }
        missTouches?.let { File(dir, AgentContract.MISS_TOUCH_FILE).writeText(it.joinToString("") { row -> "$row\n" }) }
        File(dir, "schema-version").writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        return MapReader.read(dir)
    }

    private fun recorded(jvm: String) = "$jvm\t0\t${AgentContract.JARS_RECORDED}"

    private fun decide(map: MapReader.Result, jars: List<String>, dirs: List<String> = emptyList()) = Selector.decide(
        map, ChangeSet.of(emptyList(), emptyList()).withAccountedFor(true).withChangedJars(jars, dirs), emptyList(),
    )

    @Test
    fun `a moved module runs the tests from its JVM's first touch of its jar on, and no other JVM's`(@TempDir dir: File) {
        val decision = decide(map(dir, listOf(recorded("j"), recorded("k"), "j\t4\tdev.lib:lib-json", "k\t2\tdev.lib:lib-math")), listOf("dev.lib:lib-json"))

        assertFalse(decision.isFullRun)
        assertFalse(decision.includes(id("EarlyTest")))
        assertTrue(decision.includes(id("JsonTest")))
        assertTrue(decision.includes(id("LateTest")))
        assertFalse(decision.includes(id("OtherJvmTest")))
        assertEquals(Selector.Decision.Reason.SHARES_JVM_WITH_CHANGE, decision.reasonFor(id("JsonTest")))
    }

    @Test
    fun `a JVM whose touches were not recorded runs whole`(@TempDir dir: File) {
        val decision = decide(map(dir, listOf(recorded("j"), "j\t4\tdev.lib:lib-json")), listOf("dev.lib:lib-json"))

        assertTrue(decision.includes(id("OtherJvmTest")))
        assertFalse(decision.includes(id("EarlyTest")))
    }

    @Test
    fun `a map without the jar table runs every test`(@TempDir dir: File) {
        val decision = decide(map(dir, null), listOf("dev.lib:lib-json"))

        assertTrue(listOf("EarlyTest", "JsonTest", "LateTest", "OtherJvmTest").all { decision.includes(id(it)) })
    }

    @Test
    fun `every jar touched from a sequence on runs the tests from there`(@TempDir dir: File) {
        val decision = decide(map(dir, listOf(recorded("j"), recorded("k"), "j\t4\t*")), listOf("dev.lib:lib-json"))

        assertFalse(decision.includes(id("EarlyTest")))
        assertTrue(decision.includes(id("JsonTest")))
        assertFalse(decision.includes(id("OtherJvmTest")))
    }

    @Test
    fun `a changed directory runs the tests from their JVM's first absent lookup in it on`(@TempDir dir: File) {
        val decision = decide(
            map(dir, listOf(recorded("j"), recorded("k")), listOf(recorded("j"), recorded("k"), "j\t6\tlib/math/")),
            listOf("dev.lib:lib-math"), listOf("lib/math/"),
        )

        assertFalse(decision.includes(id("JsonTest")))
        assertTrue(decision.includes(id("LateTest")))
    }

    @Test
    fun `a module no test reached selects nothing`(@TempDir dir: File) {
        val decision = decide(map(dir, listOf(recorded("j"), recorded("k"), "j\t4\tdev.lib:lib-json")), listOf("dev.lib:unused"))

        assertFalse(decision.isFullRun)
        assertFalse(listOf("EarlyTest", "JsonTest", "LateTest", "OtherJvmTest").any { decision.includes(id(it)) })
    }

    @Test
    fun `the moved jar's window is named as its own rule`(@TempDir dir: File) {
        val rules = Selector.rules(
            map(dir, listOf(recorded("j"), recorded("k"), "j\t4\tdev.lib:lib-json")),
            ChangeSet.of(emptyList(), emptyList()).withAccountedFor(true).withChangedJars(listOf("dev.lib:lib-json"), emptyList()),
            true,
        )

        assertTrue(Rule.SHARES_JVM_CHANGED_JAR in rules.rulesFor(id("LateTest"), Verdict.SHARES_JVM_WITH_CHANGE))
        assertFalse(Rule.SHARES_JVM_CHANGED_JAR in rules.rulesFor(id("EarlyTest"), Verdict.SKIPPED))
    }
}
