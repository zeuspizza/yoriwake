package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CommitScan
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunPlanTest {

    private val known = RunPlan.Widening(MapAge.Known(ChangeDetection.Base("abc1234", "test"), "abc1234"), null)
    private val unknown = RunPlan.Widening(MapAge.Unknown(RefusalKind.STAMP_ABSENT, "no stamp"), null)
    private val marked = CommitScan.Read(ChangeDetection.Commit("d".repeat(40), "risky"), emptyList())
    private val clean = CommitScan.Read(null, emptyList())

    private fun settings(vararg flags: String) = Settings { name -> if (name in flags) "" else null }

    private fun resolve(settings: Settings, widening: RunPlan.Widening = known, scan: CommitScan = clean) =
        RunPlan.resolve(settings, { widening }, { scan })

    @Test
    fun `a run records unless selection is asked for`() {
        assertEquals(RunPlan.Kind.RECORD, resolve(Settings { null }).kind)
        assertEquals(RunPlan.Kind.RECORD, resolve(Settings { if (it == Settings.SELECT) "false" else null }).kind)
    }

    @Test
    fun `-Pyoriwake_select makes a selecting run`() {
        val plan = resolve(settings(Settings.SELECT))

        assertEquals(RunPlan.Kind.SELECT, plan.kind)
        assertTrue(plan.selecting)
    }

    @Test
    fun `a recording run asks for neither the base nor the commit messages`() {
        val plan = RunPlan.resolve(settings(Settings.FULL_RUN), { error("asked for the base") }, { error("scanned") })

        assertEquals(RunPlan.Kind.RECORD, plan.kind)
        assertEquals(emptyList(), plan.declines)
    }

    @Test
    fun `a full run asked for, then a marked commit, each turn a selecting run into a recording one`() {
        val plan = resolve(settings(Settings.SELECT, Settings.FULL_RUN), scan = marked)

        assertEquals(RunPlan.Kind.RECORD, plan.kind)
        assertEquals(RunPlan.Kind.SELECT, plan.asked)
        assertEquals(listOf(RefusalKind.FULL_RUN_REQUESTED, RefusalKind.FULL_RUN_COMMIT), plan.declines.map { it.kind })
        assertTrue(plan.declines.all { it.kind.requested })
    }

    @Test
    fun `a marked commit names its sha and subject`() {
        val decline = resolve(settings(Settings.SELECT), scan = marked).declines.single()

        assertEquals(RefusalKind.FULL_RUN_COMMIT, decline.kind)
        assertTrue("d".repeat(12) in decline.reason && "risky" in decline.reason, decline.reason)
    }

    @Test
    fun `messages git cannot list decline as undetermined, which is forced`() {
        val plan = resolve(settings(Settings.SELECT), scan = CommitScan.Failed("git log x..HEAD could not answer"))

        assertEquals(RunPlan.Kind.RECORD, plan.kind)
        assertEquals(RefusalKind.DECLINE_UNDETERMINED, plan.declines.single().kind)
        assertFalse(plan.declines.single().kind.requested)
    }

    @Test
    fun `a map of unknown age reads no message, and the flag still declines`() {
        val plan = RunPlan.resolve(settings(Settings.SELECT, Settings.FULL_RUN), { unknown }, { error("scanned") })

        assertEquals(listOf(RefusalKind.FULL_RUN_REQUESTED), plan.declines.map { it.kind })
    }

    @Test
    fun `with no map yet the run records anyway, so no message is read`() {
        val noMap = RunPlan.Widening(MapAge.Known(ChangeDetection.Base("HEAD", "test")), null)
        val plan = RunPlan.resolve(settings(Settings.SELECT), { noMap }, { error("scanned") })

        assertEquals(emptyList(), plan.declines)
    }

    @Test
    fun `a commit that looks like a request and is not one is a note, not a decline`() {
        val hinted = CommitScan.Read(null, listOf(ChangeDetection.Commit("e".repeat(40), "tweak")))
        val plan = resolve(settings(Settings.SELECT), scan = hinted)

        assertEquals(RunPlan.Kind.SELECT, plan.kind)
        assertTrue(plan.notes.single().contains("tweak"), plan.notes.toString())
    }

    @Test
    fun `nothing but the run plan reads the selection flag`() {
        val readers = File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" && it.name != "RunPlan.kt" && it.name != "Settings.kt" }
            .filter { Regex("""\bsettings\.select\b""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()

        assertEquals(emptyList(), readers)
    }
}
