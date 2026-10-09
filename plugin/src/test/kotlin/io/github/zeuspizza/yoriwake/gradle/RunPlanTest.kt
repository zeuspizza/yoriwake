package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CheckedOut
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CommitScan
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import org.gradle.api.InvalidUserDataException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `-Pyoriwake_observe makes an observing run, which neither selects nor records`() {
        val plan = resolve(settings(Settings.OBSERVE))

        assertEquals(RunPlan.Kind.OBSERVE, plan.kind)
        assertEquals(RunPlan.Kind.OBSERVE, plan.asked)
        assertFalse(plan.selecting)
        assertTrue(plan.observing)
        assertTrue(plan.widening != null)
    }

    @Test
    fun `-Pyoriwake_observe=false is a recording run`() {
        val plan = RunPlan.resolve(
            Settings { if (it == Settings.OBSERVE) "false" else null }, { error("asked for the base") }, { error("scanned") },
        )

        assertEquals(RunPlan.Kind.RECORD, plan.kind)
    }

    @Test
    fun `a decline leaves an observing run observing, and names why`() {
        val plan = resolve(settings(Settings.OBSERVE, Settings.FULL_RUN), scan = marked)

        assertEquals(RunPlan.Kind.OBSERVE, plan.kind)
        assertEquals(listOf(RefusalKind.FULL_RUN_REQUESTED, RefusalKind.FULL_RUN_COMMIT), plan.declines.map { it.kind })
    }

    @Test
    fun `observing and selecting together fail, naming both flags`() {
        val failure = assertThrows<InvalidUserDataException> {
            RunPlan.resolve(settings(Settings.SELECT, Settings.OBSERVE), { error("asked for the base") }, { error("scanned") })
        }

        assertTrue(Settings.SELECT in failure.message.orEmpty(), failure.message)
        assertTrue(Settings.OBSERVE in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `nothing but the run plan reads the selection and observation flags`() {
        val readers = File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" && it.name != "RunPlan.kt" && it.name != "Settings.kt" }
            .filter { Regex("""\bsettings\.(select|observe)\b""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()

        assertEquals(emptyList(), readers)
    }

    private fun onBranch(
        head: CheckedOut,
        vararg listed: String,
        flags: Array<String> = arrayOf(Settings.SELECT),
        scan: CommitScan = clean,
    ) = RunPlan.resolve(settings(*flags), { known }, { scan }, listed.toList(), { head })

    @Test
    fun `on a listed branch a selecting run declines, naming the branch, and records`() {
        val plan = onBranch(CheckedOut.Branch("main"), "main")

        assertEquals(RunPlan.Kind.RECORD, plan.kind)
        val decline = plan.declines.single()
        assertEquals(RefusalKind.FULL_RUN_BRANCH, decline.kind)
        assertTrue(decline.kind.requested)
        assertTrue("main" in decline.reason, decline.reason)
    }

    @Test
    fun `on a branch not listed the run selects and says what it detected`() {
        val plan = onBranch(CheckedOut.Branch("feature/x"), "main")

        assertEquals(RunPlan.Kind.SELECT, plan.kind)
        assertTrue(plan.notes.single().contains("on branch feature/x"), plan.notes.toString())
        assertEquals("on branch feature/x", plan.branch)
    }

    @Test
    fun `a branch name matches whole, with a star matching any characters`() {
        assertEquals(emptyList(), onBranch(CheckedOut.Branch("main.old"), "main").declines)
        assertEquals(emptyList(), onBranch(CheckedOut.Branch("old-main"), "main").declines)
        assertEquals(
            listOf(RefusalKind.FULL_RUN_BRANCH),
            onBranch(CheckedOut.Branch("release/1.2"), "release/*").declines.map { it.kind },
        )
    }

    @Test
    fun `a detached HEAD a listed branch contains declines, naming the ref`() {
        val head = CheckedOut.Detached(listOf(ChangeDetection.BranchRef("origin/main", "main")))
        val plan = onBranch(head, "main")

        assertEquals(RefusalKind.FULL_RUN_BRANCH, plan.declines.single().kind)
        assertTrue("origin/main" in plan.declines.single().reason, plan.declines.single().reason)
    }

    @Test
    fun `a detached HEAD no listed branch contains selects and says so`() {
        val head = CheckedOut.Detached(listOf(ChangeDetection.BranchRef("origin/feature", "feature")))
        val plan = onBranch(head, "main")

        assertEquals(RunPlan.Kind.SELECT, plan.kind)
        assertTrue(plan.notes.single().contains("detached; no listed branch contains HEAD"), plan.notes.toString())
    }

    @Test
    fun `a branch git cannot read declines as undetermined, which is forced, naming git`() {
        val plan = onBranch(CheckedOut.Failed("git rev-parse --symbolic-full-name HEAD could not answer"), "main")

        assertEquals(RunPlan.Kind.RECORD, plan.kind)
        val decline = plan.declines.single()
        assertEquals(RefusalKind.DECLINE_UNDETERMINED, decline.kind)
        assertFalse(decline.kind.requested)
        assertTrue("git" in decline.reason, decline.reason)
    }

    @Test
    fun `with no listed branch the branch is never asked for`() {
        var asked = 0
        val plan = RunPlan.resolve(settings(Settings.SELECT), { known }, { clean }, emptyList(), {
            asked++
            CheckedOut.Branch("main")
        })

        assertEquals(0, asked)
        assertEquals(RunPlan.Kind.SELECT, plan.kind)
        assertEquals(emptyList(), plan.notes)
    }

    @Test
    fun `a recording run never asks for the branch`() {
        val plan = RunPlan.resolve(settings(), { error("asked for the base") }, { error("scanned") }, listOf("main"), {
            error("asked for the branch")
        })

        assertEquals(emptyList(), plan.declines)
    }

    @Test
    fun `the flag, then the branch, then a marked commit, each listed in that order`() {
        val plan = onBranch(
            CheckedOut.Branch("main"), "main",
            flags = arrayOf(Settings.SELECT, Settings.FULL_RUN), scan = marked,
        )

        assertEquals(
            listOf(RefusalKind.FULL_RUN_REQUESTED, RefusalKind.FULL_RUN_BRANCH, RefusalKind.FULL_RUN_COMMIT),
            plan.declines.map { it.kind },
        )
    }

    @Test
    fun `an empty entry, or a star alone, fails naming the property`() {
        listOf("  ", "*", "**").forEach { entry ->
            val refused = assertThrows<InvalidUserDataException> { RunPlan.fullRunBranches(listOf("main", entry), ":") }
            assertTrue("fullRunBranches" in refused.message.orEmpty(), refused.message)
        }
        assertEquals(listOf("main", "release/*"), RunPlan.fullRunBranches(listOf(" main ", "release/*"), ":"))
    }
}
