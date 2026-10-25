package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a class a framework can only run or skip whole may be left out. Leaving one out wrongly
 * hides a failure, so every answer but "all its tests skipped" runs the class.
 */
class ClassVerdictsTest {

    @TempDir
    lateinit var dir: File

    private fun id(cls: String, method: String) = "[engine:junit4]/[class:$cls]/[method:$method]"

    private val alphaA = id("AlphaTest", "a")
    private val alphaB = id("AlphaTest", "b")
    private val betaA = id("BetaTest", "a")

    /** Each AlphaTest test covers Alpha; BetaTest's covers Beta. */
    private val records = listOf(
        "SUCCESSFUL\t1000000\tcom.acme.Alpha\t$alphaA",
        "SUCCESSFUL\t1000000\tcom.acme.Alpha\t$alphaB",
        "SUCCESSFUL\t1000000\tcom.acme.Beta\t$betaA",
    )

    private fun inputs(vararg values: Pair<String, String>): (String) -> String? {
        val map = mapOf(AgentContract.SELECT_PROPERTY to "true", *values)
        return { map[it] }
    }

    private fun judge(
        ids: List<String>,
        changed: String = "com.acme.Beta",
        lines: List<String> = records,
        inputs: (String) -> String? = inputs(AgentContract.CHANGED_CLASSES_PROPERTY to changed),
        pinned: (String) -> Boolean = { false },
        keepReason: String? = null,
    ): ClassVerdicts {
        dir.mkdirs()
        File(dir, "coverage.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
        writeSoloOrder(dir)
        File(dir, "schema-version").writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        // An unset property reads as null, as System::getProperty gives it.
        @Suppress("UNCHECKED_CAST")
        val operator = java.util.function.UnaryOperator<String?> { inputs(it!!) } as java.util.function.UnaryOperator<String>
        return ClassVerdicts.of(operator, FilterRule.decisionFrom(operator, dir), pinned, ids, keepReason)
    }

    @Test
    fun `a class whose every test is skipped is left out`() {
        val judged = judge(listOf(alphaA, alphaB))

        assertTrue(judged.leaveOut())
        assertEquals(mapOf(alphaA to Verdict.SKIPPED, alphaB to Verdict.SKIPPED), judged.verdicts())
    }

    @Test
    fun `one test reaching the change keeps the class, and the rest are rounded up to it`() {
        val judged = judge(
            listOf(alphaA, alphaB),
            lines = records.map { if (it.endsWith(alphaB)) it.replace("com.acme.Alpha", "com.acme.Beta") else it },
        )

        assertFalse(judged.leaveOut())
        assertEquals(mapOf(alphaA to Verdict.CLASS_GRANULARITY, alphaB to Verdict.REACHES_CHANGE), judged.verdicts())
    }

    @Test
    fun `a test the map has never seen keeps its class`() {
        val unseen = id("AlphaTest", "c")
        val judged = judge(listOf(alphaA, unseen))

        assertFalse(judged.leaveOut())
        assertEquals(Verdict.NOT_IN_MAP, judged.verdicts()[unseen])
        assertEquals(Verdict.CLASS_GRANULARITY, judged.verdicts()[alphaA])
    }

    @Test
    fun `a class with no test runs`() {
        val judged = judge(emptyList())

        assertFalse(judged.leaveOut())
        assertTrue(judged.verdicts().isEmpty())
    }

    @Test
    fun `a refused run keeps every class, each test recorded as refused`() {
        val judged = judge(
            listOf(alphaA),
            inputs = inputs(
                AgentContract.CHANGED_CLASSES_PROPERTY to "com.acme.Beta",
                AgentContract.REFUSED_PROPERTY to "the daemon refused",
            ),
            pinned = { true },
        )

        assertFalse(judged.leaveOut())
        assertEquals(mapOf(alphaA to Verdict.DAEMON_REFUSED), judged.verdicts())
    }

    @Test
    fun `a run that did not ask to select keeps every class`() {
        val judged = judge(listOf(alphaA), inputs = { name ->
            if (name == AgentContract.CHANGED_CLASSES_PROPERTY) "com.acme.Beta" else null
        })

        assertFalse(judged.leaveOut())
        assertEquals(mapOf(alphaA to Verdict.SELECTION_NOT_REQUESTED), judged.verdicts())
    }

    @Test
    fun `a full run keeps every class`() {
        val judged = judge(listOf(alphaA), changed = "com.acme.Gamma")

        assertFalse(judged.leaveOut())
        assertEquals(mapOf(alphaA to Verdict.FULL_RUN), judged.verdicts())
    }

    @Test
    fun `a pinned test keeps its class`() {
        val judged = judge(listOf(alphaA, alphaB), pinned = { it == alphaB })

        assertFalse(judged.leaveOut())
        assertEquals(mapOf(alphaA to Verdict.CLASS_GRANULARITY, alphaB to Verdict.ALWAYS_RUN), judged.verdicts())
    }

    @Test
    fun `a class whose setup reached the change keeps the class`() {
        val setup = "SUCCESSFUL\t1000000\tcom.acme.Beta\t${AgentContract.CLASS_SCOPED_RECORD_PREFIX}" +
            "[engine:junit4]/[class:AlphaTest]"
        val judged = judge(listOf(alphaA, alphaB), lines = records + setup)

        assertFalse(judged.leaveOut())
        assertEquals(setOf(Verdict.REACHES_CHANGE), judged.verdicts().values.toSet())
    }

    @Test
    fun `a keep reason keeps the class, each test recorded as running whole, pins still named`() {
        val judged = judge(listOf(alphaA, alphaB), pinned = { it == alphaB }, keepReason = "junit4-runner")

        assertFalse(judged.leaveOut())
        assertEquals(mapOf(alphaA to Verdict.CLASS_RUNS_WHOLE, alphaB to Verdict.ALWAYS_RUN), judged.verdicts())
    }
}
