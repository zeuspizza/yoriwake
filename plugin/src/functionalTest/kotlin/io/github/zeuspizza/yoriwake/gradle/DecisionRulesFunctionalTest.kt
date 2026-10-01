package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/** Every rule behind each decision, as a real test JVM records it beside the rows it applied. */
class DecisionRulesFunctionalTest : FunctionalTestSupport() {

    /**
     * `test class -> rule line value` for each test method. A refused run also records the engine
     * and class containers, which carry no method.
     */
    private fun ruleLines(dir: File): Map<String, String> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        return File(mapDir, AgentContract.DECISIONS_FILE).readLines()
            .filter { it.startsWith(AgentContract.RULES_LINE_PREFIX) && it.contains("[method:") }
            .associate { line ->
                val (id, rules) = line.removePrefix(AgentContract.RULES_LINE_PREFIX).split("\t")
                Regex("""\[class:([^\]]+)]""").find(id)!!.groupValues[1] to rules
            }
    }

    private fun reasons(dir: File) = decisionReasons(dir).filterKeys { it.contains("[method:") }.mapKeys { (id, _) ->
        Regex("""\[class:([^\]]+)]""").find(id)!!.groupValues[1]
    }

    @Test
    fun `a test two rules select records both, and the row keeps the one that decided it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        // BetaTest runs last and is where its JVM first loads Beta, so it both reaches the change
        // and ran inside the window the change opened. AlphaTest ran before either.
        changeBeta(dir)

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        assertEquals(
            mapOf("dev.sample.AlphaTest" to "SKIPPED", "dev.sample.BetaTest" to "REACHES_CHANGE"),
            reasons(dir),
        )
        assertEquals(
            mapOf(
                "dev.sample.AlphaTest" to AgentContract.RULE_NONE,
                "dev.sample.BetaTest" to "reaches-change,shares-jvm-changed-class",
            ),
            ruleLines(dir),
        )
        assertEquals(AgentContract.RULES_COMPLETE, decisionNotes(dir)[AgentContract.RULES_NOTE])
        assertEquals(AgentContract.RULE_NONE, decisionNotes(dir)[AgentContract.FORCING_KINDS_NOTE])
    }

    @Test
    fun `a refused run records its refusal, and beneath it what coverage alone would have run`(
        @TempDir dir: File,
    ) {
        // BetaTest baked the constant into its own bytecode, so no coverage edge reaches it. The
        // refusal runs it; beneath the refusal, coverage alone would not have, and only the window
        // AlphaTest opened by loading Limits first would.
        val limits = "src/main/java/dev/sample/Limits.java" to """
            package dev.sample;
            public class Limits {
                public static final int MAX = 10;
                public int max() { return MAX; }
            }
        """.trimIndent()
        val readsAtRuntime = "src/test/java/dev/sample/AlphaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AlphaTest {
                @Test void passes() { assertEquals(10, new Limits().max()); }
            }
        """.trimIndent()
        val bakedIn = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest {
                @Test void passes() { assertEquals(10, Limits.MAX); }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to minimalBuild, limits, readsAtRuntime, bakedIn, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/java/dev/sample/Limits.java").writeText(
            limits.second.replace("MAX = 10", "MAX = 11"),
        )

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").buildAndFail()

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        val notes = decisionNotes(dir)
        assertEquals("constant-changed", notes[AgentContract.REFUSAL_KIND_NOTE])
        assertEquals(AgentContract.RULES_FROM_REFUSED_INPUTS, notes[AgentContract.RULES_NOTE])
        assertEquals("daemon-refused", notes[AgentContract.FORCING_KINDS_NOTE])
        assertEquals(setOf("DAEMON_REFUSED"), reasons(dir).values.toSet())
        assertEquals(
            mapOf(
                "dev.sample.AlphaTest" to "reaches-change,shares-jvm-changed-class",
                "dev.sample.BetaTest" to "shares-jvm-changed-class",
            ),
            ruleLines(dir),
        )
    }

    @Test
    fun `a refusal that came before any change set records that nothing beneath it is known`(
        @TempDir dir: File,
    ) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        committed(dir)
        runner(dir, "test").build()
        // A stamp git cannot relate to the base refuses before any change set is derived.
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        File(mapDir, io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder.CAPTURE_COMMIT_FILE)
            .writeText("f".repeat(40))

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()

        val notes = decisionNotes(dir)
        assertEquals("stamp-unrelatable", notes[AgentContract.REFUSAL_KIND_NOTE])
        assertEquals(AgentContract.RULES_NO_CHANGE_SET, notes[AgentContract.RULES_NOTE])
        assertEquals(emptyMap(), ruleLines(dir))
    }
}
