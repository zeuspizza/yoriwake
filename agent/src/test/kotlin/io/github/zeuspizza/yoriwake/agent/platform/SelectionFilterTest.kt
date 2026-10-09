package io.github.zeuspizza.yoriwake.agent.platform

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.FilterRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import java.io.File
import java.util.Optional
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The filter that turns a decision into tests that do or do not run. It fails invisibly by
 * excluding a container (removing tests never decided about) or deselecting during a capture-only
 * run (freezing the map).
 */
class SelectionFilterTest {

    private val properties = listOf(
        AgentContract.MAP_DIR_PROPERTY,
        AgentContract.CHANGED_CLASSES_PROPERTY,
        AgentContract.UNMAPPABLE_PATHS_PROPERTY,
        AgentContract.SELECT_PROPERTY,
        AgentContract.CLASS_GRANULARITY_PROPERTY,
        AgentContract.OBSERVE_PROPERTY,
    )

    @AfterEach
    fun clearProperties() = properties.forEach(System::clearProperty)

    private class Descriptor(id: String, private val test: Boolean) :
        AbstractTestDescriptor(UniqueId.parse(id), id) {
        override fun getType() =
            if (test) TestDescriptor.Type.TEST else TestDescriptor.Type.CONTAINER
        override fun getSource(): Optional<TestSource> = Optional.empty()
    }

    private fun writeMap(dir: File, vararg lines: String): File {
        dir.mkdirs()
        File(dir, "coverage.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
        io.github.zeuspizza.yoriwake.agent.select.writeSoloOrder(dir)
        File(dir, "schema-version").writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        return dir
    }

    private fun record(cls: String, covered: String, outcome: String = "SUCCESSFUL") =
        "$outcome\t1000000\t$covered\t[engine:junit-jupiter]/[class:$cls]/[method:t()]"

    private fun testId(cls: String) = "[engine:junit-jupiter]/[class:$cls]/[method:t()]"

    /** A record for a NAMED method, so a sibling can be genuinely SKIPPED and not NOT_IN_MAP. */
    private fun methodRecord(cls: String, method: String, covered: String) =
        "SUCCESSFUL\t1000000\t$covered\t[engine:junit-jupiter]/[class:$cls]/[method:$method]"

    private fun selectingAgainst(dir: File, changed: String) {
        System.setProperty(AgentContract.MAP_DIR_PROPERTY, dir.absolutePath)
        System.setProperty(AgentContract.CHANGED_CLASSES_PROPERTY, changed)
        System.setProperty(AgentContract.SELECT_PROPERTY, "true")
    }

    @Test
    fun `a parameterised test whose iterations reach the change is included`(@TempDir dir: File) {
        // A Spock data-driven feature reports itself as a TEST while containing iterations, and its
        // own record holds only what the container touched; the iterations' coverage must count.
        // A leaf, because at discovery time the iterations do not exist yet.
        val feature = "[engine:spock]/[spec:com.acme.HandlerTest]/[feature:\$spock_feature_0_0]"
        writeMap(
            dir,
            "SUCCESSFUL\t1000000\torg.junit.platform.engine.TestDescriptor\t$feature",
            "SUCCESSFUL\t1000000\tcom.acme.Handler\t$feature/[iteration:0]",
            "SUCCESSFUL\t1000000\tcom.acme.Handler\t$feature/[iteration:1]",
        )
        selectingAgainst(dir, "com.acme.Handler")

        val result = SelectionFilter(false).apply(Descriptor(feature, test = true))

        assertTrue(
            result.included(),
            "its iterations record the changed class, so the feature must run",
        )
    }

    @Test
    fun `a parameterised test whose iterations reach nothing is still excluded`(@TempDir dir: File) {
        // Same shape with nothing under it touching the change: inclusion is not blanket.
        val feature = "[engine:spock]/[spec:com.acme.OtherTest]/[feature:\$spock_feature_0_0]"
        writeMap(
            dir,
            "SUCCESSFUL\t1000000\torg.junit.platform.engine.TestDescriptor\t$feature",
            "SUCCESSFUL\t1000000\tcom.acme.Other\t$feature/[iteration:0]",
            record("Alpha", "com.acme.Handler"),
        )
        selectingAgainst(dir, "com.acme.Handler")

        assertFalse(SelectionFilter(false).apply(Descriptor(feature, test = true)).included())
    }

    @Test
    fun `without the select flag every test is included`(@TempDir dir: File) {
        // A capture-only run must not deselect: the map it builds would otherwise only ever
        // describe the tests the previous map already knew about.
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        System.setProperty(AgentContract.MAP_DIR_PROPERTY, dir.absolutePath)
        System.setProperty(AgentContract.CHANGED_CLASSES_PROPERTY, "com.acme.A")

        val result = SelectionFilter(false).apply(Descriptor(testId("Beta"), test = true))

        assertTrue(result.included())
    }

    @Test
    fun `a filter created while a test plan executes serves a nested launcher and includes everything`(
        @TempDir dir: File,
    ) {
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")
        val events = io.github.zeuspizza.yoriwake.agent.engines.PlatformEvents()

        events.testPlanExecutionStarted(null)
        val nested = try {
            SelectionFilter()
        } finally {
            events.testPlanExecutionFinished(null)
        }

        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Beta"), test = true)).excluded())
        assertTrue(nested.apply(Descriptor(testId("Beta"), test = true)).included(), "a nested launcher's test was deselected")
    }

    @Test
    fun `an observing run includes a test the change cannot reach`(@TempDir dir: File) {
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")
        System.setProperty(AgentContract.OBSERVE_PROPERTY, "true")

        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Beta"), test = true)).included())
        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Alpha"), test = true)).included())
    }

    @Test
    fun `a test the change cannot reach is excluded`(@TempDir dir: File) {
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")

        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Beta"), test = true)).excluded())
    }

    @Test
    fun `the recorded inputs alone reproduce the filter's own verdicts`(@TempDir dir: File) {
        // The decision rebuilt from only the properties in INPUTS must give every test the filter's
        // verdict; a property read but not recorded would make offline reproduction drift.
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")
        val recorded = FilterRule.INPUTS.associateWith { System.getProperty(it) ?: "" }
        val replayed = FilterRule.decisionFrom({ recorded[it] ?: "" }, dir)

        for ((cls, expected) in listOf("Alpha" to "included", "Beta" to "excluded", "Gamma" to "included")) {
            val filter = SelectionFilter(false).apply(Descriptor(testId(cls), test = true))
            val verdict = FilterRule.verdictFor({ recorded[it] ?: "" }, replayed, testId(cls), true)
            kotlin.test.assertEquals(expected, verdict.inclusionToken(), "$cls replayed as $verdict")
            kotlin.test.assertEquals(filter.included(), verdict.included(), "$cls disagrees with the filter")
        }
    }

    @Test
    fun `a Kotest spec the change cannot reach is still included`(@TempDir dir: File) {
        // Kotest runs its own spec list, and runs nothing at all once one spec is excluded.
        val alpha = "[engine:kotest]/[spec:com.acme.AlphaSpec]"
        val beta = "[engine:kotest]/[spec:com.acme.BetaSpec]"
        writeMap(
            dir,
            "SUCCESSFUL\t1000000\tcom.acme.A\t$alpha/[test:t]",
            "SUCCESSFUL\t1000000\tcom.acme.B\t$beta/[test:t]",
            record("Alpha", "com.acme.A"),
        )
        selectingAgainst(dir, "com.acme.B")
        val decision = FilterRule.decisionFrom(System::getProperty, dir)

        assertTrue(SelectionFilter(false).apply(Descriptor(alpha, test = false)).included())
        kotlin.test.assertEquals(
            io.github.zeuspizza.yoriwake.agent.select.Verdict.ENGINE_RUNS_EVERYTHING,
            FilterRule.verdictFor(System::getProperty, decision, alpha, false),
        )
        kotlin.test.assertEquals(
            io.github.zeuspizza.yoriwake.agent.select.Verdict.SKIPPED,
            FilterRule.verdictFor(System::getProperty, decision, testId("Alpha"), true),
            "only the engines that need it are exempt from exclusion",
        )
    }

    @Test
    fun `a test the change reaches is included`(@TempDir dir: File) {
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")

        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Alpha"), test = true)).included())
    }

    @Test
    fun `class granularity includes a skipped test whose sibling is selected`(@TempDir dir: File) {
        // Order-dependent tests usually share a class and its static state, so selection rounds up
        // to the class. `other()` is in the map so it is genuinely SKIPPED, not NOT_IN_MAP.
        writeMap(dir, record("Alpha", "com.acme.A"),
                 methodRecord("Alpha", "other()", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")
        System.setProperty(AgentContract.CLASS_GRANULARITY_PROPERTY, "true")

        val cls = Descriptor("[engine:junit-jupiter]/[class:Alpha]", test = false)
        val reaching = Descriptor(testId("Alpha"), test = true)
        val skipped = Descriptor("[engine:junit-jupiter]/[class:Alpha]/[method:other()]", test = true)
        cls.addChild(reaching)
        cls.addChild(skipped)

        assertTrue(SelectionFilter(false).apply(skipped).included())
    }

    @Test
    fun `without the flag the same test is still excluded`(@TempDir dir: File) {
        // Proves the flag, not something else, included it above.
        writeMap(dir, record("Alpha", "com.acme.A"),
                 methodRecord("Alpha", "other()", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")

        val cls = Descriptor("[engine:junit-jupiter]/[class:Alpha]", test = false)
        val reaching = Descriptor(testId("Alpha"), test = true)
        val skipped = Descriptor("[engine:junit-jupiter]/[class:Alpha]/[method:other()]", test = true)
        cls.addChild(reaching)
        cls.addChild(skipped)

        assertTrue(SelectionFilter(false).apply(skipped).excluded())
    }

    @Test
    fun `class granularity does not include a class nothing selects`(@TempDir dir: File) {
        // It rounds a selection up; it does not turn selection off.
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")
        System.setProperty(AgentContract.CLASS_GRANULARITY_PROPERTY, "true")

        val cls = Descriptor("[engine:junit-jupiter]/[class:Beta]", test = false)
        val skipped = Descriptor(testId("Beta"), test = true)
        cls.addChild(skipped)

        assertTrue(SelectionFilter(false).apply(skipped).excluded())
    }

    @Test
    fun `rounding is to the outermost class, so a nested sibling is included`(@TempDir dir: File) {
        // Rounding to the immediate parent would leave the enclosing class's other tests skipped,
        // though they share its static state.
        writeMap(dir, record("Outer", "com.acme.A"),
                 "SUCCESSFUL\t1000000\tcom.acme.B\t" +
                     "[engine:junit-jupiter]/[class:Outer]/[nested-class:Inner]/[method:t()]")
        selectingAgainst(dir, "com.acme.A")
        System.setProperty(AgentContract.CLASS_GRANULARITY_PROPERTY, "true")

        val outer = Descriptor("[engine:junit-jupiter]/[class:Outer]", test = false)
        val reaching = Descriptor(testId("Outer"), test = true)
        val nested = Descriptor(
            "[engine:junit-jupiter]/[class:Outer]/[nested-class:Inner]", test = false)
        val skipped = Descriptor(
            "[engine:junit-jupiter]/[class:Outer]/[nested-class:Inner]/[method:t()]", test = true)
        outer.addChild(reaching)
        outer.addChild(nested)
        nested.addChild(skipped)

        assertTrue(SelectionFilter(false).apply(skipped).included())
    }

    @Test
    fun `a container with children is included even when none of them would be`(@TempDir dir: File) {
        // Excluding a container removes its children wholesale, without the selector ever having
        // decided about them individually.
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")

        val container = Descriptor("[engine:junit-jupiter]/[class:Beta]", test = false)
        container.addChild(Descriptor(testId("Beta"), test = true))

        assertTrue(SelectionFilter(false).apply(container).included())
    }

    @Test
    fun `a leaf container the change cannot reach is excluded`(@TempDir dir: File) {
        // A parameterised test's invocations do not exist until execution, so nothing downstream
        // judges them; waving every leaf container through would defeat selection.
        val template = "[engine:junit-jupiter]/[class:Beta]/[test-template:t()]"
        writeMap(
            dir,
            record("Alpha", "com.acme.A"),
            "SUCCESSFUL	1000000	com.acme.B	$template/[test-template-invocation:#1]",
        )
        selectingAgainst(dir, "com.acme.A")

        assertTrue(SelectionFilter(false).apply(Descriptor(template, test = false)).excluded())
    }

    @Test
    fun `a leaf container whose invocations reach the change is included`(@TempDir dir: File) {
        val template = "[engine:junit-jupiter]/[class:Alpha]/[test-template:t()]"
        writeMap(
            dir,
            "SUCCESSFUL	1000000	com.acme.A	$template/[test-template-invocation:#1]",
            record("Beta", "com.acme.B"),
        )
        selectingAgainst(dir, "com.acme.A")

        assertTrue(SelectionFilter(false).apply(Descriptor(template, test = false)).included())
    }

    @Test
    fun `a leaf container the map has never seen is included`(@TempDir dir: File) {
        // A newly added parameterised test: no record exists, so no change can intersect it.
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")

        val template = "[engine:junit-jupiter]/[class:Brand]/[test-template:t()]"

        assertTrue(SelectionFilter(false).apply(Descriptor(template, test = false)).included())
    }

    @Test
    fun `a missing map includes everything and says why`(@TempDir dir: File) {
        selectingAgainst(File(dir, "absent"), "com.acme.A")

        val result = SelectionFilter(false).apply(Descriptor(testId("Beta"), test = true))

        assertTrue(result.included())
        assertTrue(result.getReason().orElse("").contains("no map"))
    }

    @Test
    fun `an unmappable path includes everything`(@TempDir dir: File) {
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")
        System.setProperty(AgentContract.UNMAPPABLE_PATHS_PROPERTY, "build.gradle.kts")

        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Beta"), test = true)).included())
    }

    @Test
    fun `a newly added test absent from the map is included`(@TempDir dir: File) {
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")

        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Brand"), test = true)).included())
    }

    @Test
    fun `a failing test is included though the change does not reach it`(@TempDir dir: File) {
        writeMap(
            dir,
            record("Alpha", "com.acme.A"),
            record("Beta", "com.acme.B", outcome = "FAILED"),
        )
        selectingAgainst(dir, "com.acme.A")

        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Beta"), test = true)).included())
    }

    @Test
    fun `an empty change set runs everything`(@TempDir dir: File) {
        // Empty cannot be told apart from "we never got a change set", and the default base makes a
        // committed tree look exactly like this.
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        System.setProperty(AgentContract.MAP_DIR_PROPERTY, dir.absolutePath)
        System.setProperty(AgentContract.SELECT_PROPERTY, "true")

        assertTrue(SelectionFilter(false).apply(Descriptor(testId("Beta"), test = true)).included())
    }

    @Test
    fun `a broken selection includes the test rather than failing the build`(@TempDir dir: File) {
        // A filter that throws aborts discovery for the whole host build. A bug here must cost
        // selectivity, never availability.
        System.setProperty(AgentContract.MAP_DIR_PROPERTY, dir.absolutePath)
        System.setProperty(AgentContract.SELECT_PROPERTY, "true")

        val exploding = object : TestDescriptor by Descriptor(testId("Beta"), test = true) {
            override fun getUniqueId(): UniqueId = throw IllegalStateException("boom")
        }

        val result = SelectionFilter(false).apply(exploding)

        assertTrue(result.included())
    }

    @Test
    fun `blank entries in the change property are ignored rather than matched`(@TempDir dir: File) {
        // A trailing comma from a joined empty list must not become a prefix that matches nothing
        // and forces a full run for a reason nobody can read.
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A, ,")

        val filter = SelectionFilter(false)

        assertTrue(filter.apply(Descriptor(testId("Alpha"), test = true)).included())
        assertTrue(filter.apply(Descriptor(testId("Beta"), test = true)).excluded())
    }

    @Test
    fun `the decision survives a map that disappears mid-run`(@TempDir dir: File) {
        // Discovery calls the filter once per descriptor; recomputing per call would put the whole
        // map on the hot path and make the answer depend on when the file was read.
        writeMap(dir, record("Alpha", "com.acme.A"), record("Beta", "com.acme.B"))
        selectingAgainst(dir, "com.acme.A")
        val filter = SelectionFilter(false)

        assertTrue(filter.apply(Descriptor(testId("Alpha"), test = true)).included())
        File(dir, "coverage.tsv").delete()

        assertTrue(filter.apply(Descriptor(testId("Beta"), test = true)).excluded())
    }
}
