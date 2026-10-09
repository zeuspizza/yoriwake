package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunPlanTest {

    @Test
    fun `a run records unless selection is asked for`() {
        assertEquals(RunPlan.Kind.RECORD, RunPlan.resolve(Settings { null }).kind)
        assertEquals(RunPlan.Kind.RECORD, RunPlan.resolve(Settings { if (it == Settings.SELECT) "false" else null }).kind)
    }

    @Test
    fun `-Pyoriwake_select makes a selecting run`() {
        val plan = RunPlan.resolve(Settings { if (it == Settings.SELECT) "" else null })

        assertEquals(RunPlan.Kind.SELECT, plan.kind)
        assertTrue(plan.selecting)
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
