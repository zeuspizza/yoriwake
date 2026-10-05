package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every rule behind each test, as the decision record states it: each rule on its own, beneath a
 * full run too, and never a change to what runs.
 */
class RulesTest {

    private fun id(name: String) = "[engine:junit-jupiter]/[class:$name]/[method:t()]"

    /** A map of `testId -> covered classes` in one JVM `j`, each test at the sequence it is listed. */
    private fun map(
        dir: File,
        tests: List<Pair<String, String>>,
        firstTouches: List<String> = emptyList(),
        outcome: (String) -> String = { AgentContract.OUTCOME_SUCCESSFUL },
        unpositioned: Set<String> = emptySet(),
    ): File {
        dir.mkdirs()
        File(dir, AgentContract.COVERAGE_FILE).writeText(
            tests.joinToString("") { (test, classes) -> "${outcome(test)}\t1000\t$classes\t$test\n" },
        )
        File(dir, AgentContract.POSITIONS_FILE).writeText(
            tests.mapIndexedNotNull { i, (test, _) -> if (test in unpositioned) null else "j\t${i + 1}\t$test\n" }
                .joinToString(""),
        )
        File(dir, AgentContract.FIRST_TOUCH_FILE).writeText(firstTouches.joinToString("") { "$it\n" })
        File(dir, AgentContract.NAMED_TOUCH_FILE).writeText("")
        File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        return dir
    }

    private fun decide(dir: File, vararg properties: Pair<String, String>): Selector.Decision {
        val values = mapOf(AgentContract.SELECT_PROPERTY to "true") + properties
        return FilterRule.decisionFrom({ values[it] }, dir)
    }

    private fun Selector.Decision.tokens(id: String, verdict: Verdict = Verdict.REACHES_CHANGE) =
        assertNotNull(rules(), "the test JVM's decision carries its rules").tokensFor(id, verdict)

    @Test
    fun `a test two rules select records both, whichever one decided it`(@TempDir dir: File) {
        // Alpha is first touched by AlphaTest's own record, so AlphaTest both reaches the change and
        // ran inside the window the change opened; BetaTest only shares the JVM.
        val decision = decide(
            map(
                dir,
                listOf(id("AlphaTest") to "dev.Alpha", id("BetaTest") to "dev.Beta", id("GammaTest") to "dev.Gamma"),
                firstTouches = listOf("j\t1\tdev.Alpha"),
            ),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha",
        )

        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(id("AlphaTest")))
        assertEquals("reaches-change,shares-jvm-changed-class", decision.tokens(id("AlphaTest")))
        assertEquals("shares-jvm-changed-class", decision.tokens(id("BetaTest")))
        assertEquals(AgentContract.RULES_COMPLETE, decision.rules()!!.basis())
        assertEquals(AgentContract.RULE_NONE, decision.rules()!!.forcingKinds())
    }

    @Test
    fun `a window is named by what opened it`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                listOf(
                    id("EarlyTest") to "dev.Other",
                    id("BytesTest") to "dev.Woven",
                    id("LateTest") to "dev.Other",
                    id("NowhereTest") to "dev.Other",
                ),
                // `*` from record 3 on: a child process, say. dev.Woven's bytes from record 2.
                firstTouches = listOf("j\t2\tdev.Woven", "j\t3\t*"),
                unpositioned = setOf(id("NowhereTest")),
            ),
            AgentContract.CHANGED_CLASSES_PROPERTY to "",
            AgentContract.CHANGED_BYTES_PROPERTY to "dev.Woven",
            AgentContract.ACCOUNTED_PROPERTY to "true",
        )

        assertFalse(decision.isFullRun())

        assertEquals(AgentContract.RULE_NONE, decision.tokens(id("EarlyTest"), Verdict.SKIPPED))
        assertEquals("changed-bytes,shares-jvm-changed-bytes", decision.tokens(id("BytesTest")))
        assertEquals("shares-jvm-changed-bytes,shares-jvm-unobserved", decision.tokens(id("LateTest")))
        assertEquals("shares-jvm-unpositioned", decision.tokens(id("NowhereTest")))
    }

    @Test
    fun `setup coverage, an unknown outcome, a test the map never saw and a pin are each a rule`(
        @TempDir dir: File,
    ) {
        val decision = decide(
            map(
                dir,
                listOf(
                    "[yoriwake:class][engine:junit-jupiter]/[class:SetupTest]" to "dev.Alpha",
                    id("SetupTest") to "dev.Other",
                    id("FlakyTest") to "dev.Other",
                ),
                outcome = { if (it.contains("Flaky")) "FAILED" else if (it.startsWith("[yoriwake")) "NONE" else "SUCCESSFUL" },
            ),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha",
        )

        assertEquals("class-setup", decision.tokens(id("SetupTest")))
        assertEquals("not-known-to-pass", decision.tokens(id("FlakyTest")))
        assertEquals("not-in-map", decision.tokens(id("NewTest"), Verdict.NOT_IN_MAP))
        assertEquals("not-known-to-pass,always-run", decision.tokens(id("FlakyTest"), Verdict.ALWAYS_RUN))
    }

    @Test
    fun `a test rounded up to its class records the rounding`(@TempDir dir: File) {
        val decision = decide(
            map(dir, listOf(id("AlphaTest") to "dev.Other")),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Other",
        )

        assertEquals("reaches-change,class-granularity", decision.tokens(id("AlphaTest"), Verdict.CLASS_GRANULARITY))
    }

    @Test
    fun `a Kotest spec records that its engine runs it whatever changed`(@TempDir dir: File) {
        val spec = "[engine:kotest]/[spec:dev.AlphaSpec]"
        val decision = decide(
            map(dir, listOf("$spec/[test:t]" to "dev.Alpha", id("BetaTest") to "dev.Beta")),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Beta",
        )

        assertEquals("engine-runs-everything", decision.tokens(spec, Verdict.ENGINE_RUNS_EVERYTHING))
        assertEquals("reaches-change", decision.tokens(id("BetaTest")))
    }

    @Test
    fun `each full-run kind is named when its condition holds`(@TempDir dir: File) {
        fun kinds(sub: String, vararg properties: Pair<String, String>, startup: Boolean = false): String {
            val tests = listOf(id("AlphaTest") to "dev.Alpha") +
                if (startup) listOf("[yoriwake:unattributed]" to "dev.Alpha") else emptyList()
            return decide(map(File(dir, sub), tests), *properties).rules()!!.forcingKinds()
        }

        assertEquals("empty-change-set", kinds("empty", AgentContract.CHANGED_CLASSES_PROPERTY to ""))
        assertEquals(
            "startup-coverage",
            kinds("startup", AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha", startup = true),
        )
        assertEquals(
            "no-coverage-for-changed-bytes",
            kinds(
                "bytes", AgentContract.CHANGED_CLASSES_PROPERTY to "",
                AgentContract.ACCOUNTED_PROPERTY to "true", AgentContract.CHANGED_BYTES_PROPERTY to "dev.Unrecorded",
            ),
        )
    }

    @Test
    fun `a template carries the rules of the invocations beneath it`(@TempDir dir: File) {
        val template = "[engine:junit-jupiter]/[class:ParamTest]/[test-template:t(int)]"
        val decision = decide(
            map(dir, listOf("$template/[test-template-invocation:#1]" to "dev.Alpha")),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha",
        )

        assertEquals("reaches-change", decision.tokens(template))
    }

    @Test
    fun `a refused run records the refusal and the selection coverage alone makes beneath it`(
        @TempDir dir: File,
    ) {
        val decision = decide(
            map(dir, listOf(id("AlphaTest") to "dev.Alpha", id("BetaTest") to "dev.Beta")),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha",
            AgentContract.REFUSED_PROPERTY to "a compile-time constant changed",
            AgentContract.REFUSED_KIND_PROPERTY to "constant-changed",
        )

        // What runs is the refusal's answer.
        assertTrue(decision.isFullRun())
        assertEquals(Selector.Decision.FullRunKind.DAEMON_REFUSED, decision.fullRunKind())
        val rules = decision.rules()!!
        assertEquals(AgentContract.RULES_FROM_REFUSED_INPUTS, rules.basis())
        assertEquals("daemon-refused", rules.forcingKinds())
        assertEquals("reaches-change", rules.tokensFor(id("AlphaTest"), Verdict.DAEMON_REFUSED))
        assertEquals(AgentContract.RULE_NONE, rules.tokensFor(id("BetaTest"), Verdict.DAEMON_REFUSED))
    }

    @Test
    fun `a refusal that came before any change set says the selection beneath it is unknown`(
        @TempDir dir: File,
    ) {
        // No changed-classes property at all: the daemon refused before git answered.
        val decision = decide(
            map(dir, listOf(id("AlphaTest") to "dev.Alpha")),
            AgentContract.REFUSED_PROPERTY to "the map's stamp cannot be related to the base",
        )

        val rules = decision.rules()!!
        assertEquals(AgentContract.RULES_NO_CHANGE_SET, rules.basis())
        assertFalse(rules.computed())
        assertEquals("daemon-refused", rules.forcingKinds())
    }

    @Test
    fun `a full run names every kind that held, and the coverage beneath it`(@TempDir dir: File) {
        val decision = decide(
            map(dir, listOf(id("AlphaTest") to "dev.Alpha", id("BetaTest") to "dev.Beta")),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha,dev.Unrecorded",
            AgentContract.UNMAPPABLE_PATHS_PROPERTY to "build.gradle.kts",
        )

        assertEquals(Selector.Decision.FullRunKind.UNMAPPABLE_PATHS, decision.fullRunKind())
        val rules = decision.rules()!!
        assertEquals("unmappable-paths,no-coverage-for-changed", rules.forcingKinds())
        assertEquals(AgentContract.RULES_COMPLETE, rules.basis())
        assertEquals("reaches-change", rules.tokensFor(id("AlphaTest"), Verdict.FULL_RUN))
        assertEquals(AgentContract.RULE_NONE, rules.tokensFor(id("BetaTest"), Verdict.FULL_RUN))
    }

    @Test
    fun `no map, no rules`(@TempDir dir: File) {
        val decision = decide(File(dir, "absent"), AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha")

        assertEquals(AgentContract.RULES_NO_MAP, decision.rules()!!.basis())
        assertEquals("map-unusable", decision.rules()!!.forcingKinds())
    }

    @Test
    fun `a narrowed run's rules say exactly which tests it runs`(@TempDir dir: File) {
        // The rules are computed apart from the decision, so they must agree with it on every test:
        // a rule that names a skipped test, or none for a run one, has drifted from the selector.
        val tests = listOf(
            id("SkippedTest") to "dev.Other", id("ATest") to "dev.Other", id("BTest") to "dev.Alpha", id("CTest") to "dev.Other",
            id("DTest") to "dev.Woven", id("ETest") to "dev.Other", id("FTest") to "dev.Other",
            "[yoriwake:class][engine:junit-jupiter]/[class:ATest]" to "dev.Alpha",
        )
        val decision = decide(
            map(
                dir, tests,
                firstTouches = listOf("j\t5\tdev.Woven", "j\t7\t*"),
                outcome = { if (it.contains("CTest")) "UNKNOWN" else if (it.startsWith("[yoriwake")) "NONE" else "SUCCESSFUL" },
                unpositioned = setOf(id("ETest")),
            ),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.Alpha",
            AgentContract.CHANGED_BYTES_PROPERTY to "dev.Woven",
        )

        assertFalse(decision.isFullRun())
        assertFalse(decision.includes(id("SkippedTest")))
        for ((test, _) in tests.filter { !it.first.startsWith("[yoriwake") }) {
            val tokens = decision.tokens(test)
            assertEquals(decision.includes(test), tokens != AgentContract.RULE_NONE, "$test: $tokens")
        }
    }

    @Test
    fun `the daemon's own decide leaves the rules to the test JVM`(@TempDir dir: File) {
        val decision = Selector.decide(
            MapReader.read(map(dir, listOf(id("AlphaTest") to "dev.Alpha"))),
            ChangeSet.of(listOf("dev.Alpha"), emptyList()),
            emptyList(),
        )

        assertNull(decision.rules(), "a daemon-side decide pays for nothing it does not write")
    }

    @Test
    fun `an edited test class names own-class-changed on its tests`(@TempDir dir: File) {
        fun editedTestRules(name: String, covered: String) = decide(
            map(File(dir, name), listOf(id("dev.EditedTest") to covered, id("dev.OtherTest") to "dev.Other")),
            AgentContract.CHANGED_CLASSES_PROPERTY to "dev.EditedTest",
            AgentContract.OWN_TEST_CLASSES_PROPERTY to "dev.EditedTest",
            AgentContract.EXEMPT_TEST_CLASSES_PROPERTY to "dev.EditedTest",
        )

        val instrumented = editedTestRules("instrumented", "dev.Calc,dev.EditedTest")
        val uninstrumented = editedTestRules("uninstrumented", "dev.Calc")

        assertEquals("reaches-change,own-class-changed", instrumented.tokens(id("dev.EditedTest")))
        assertEquals("own-class-changed", uninstrumented.tokens(id("dev.EditedTest")))
        assertEquals(AgentContract.RULE_NONE, uninstrumented.tokens(id("dev.OtherTest"), Verdict.SKIPPED))
    }
}
