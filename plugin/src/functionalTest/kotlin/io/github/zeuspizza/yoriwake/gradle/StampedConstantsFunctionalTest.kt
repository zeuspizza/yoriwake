package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A build that stamps a compile-time constant with the time on every build (Android's
 * `BuildConfig`) changes every class the compiler copied it into: named on every run, and no change
 * once `stampedConstants` declares that no test depends on it.
 */
class StampedConstantsFunctionalTest : FunctionalTestSupport() {

    /** Generates `Stamp.TIME` from the `time` property, as Android generates `BuildConfig`. */
    private val stampBuild = """
        val generateStamp by tasks.registering {
            val time = providers.gradleProperty("time").orElse("1")
            val out = layout.buildDirectory.dir("generated/stamp")
            inputs.property("time", time)
            outputs.dir(out)
            doLast {
                val file = out.get().file("dev/sample/Stamp.java").asFile
                file.parentFile.mkdirs()
                file.writeText("package dev.sample;\npublic final class Stamp { public static final String TIME = \"time-" + time.get() + "\"; }\n")
            }
        }
        sourceSets.main { java.srcDir(generateStamp) }
    """.trimIndent()

    private val app = "src/main/java/dev/sample/App.java" to """
        package dev.sample;
        public class App { public String built() { return Stamp.TIME; } }
    """.trimIndent()

    private val appTest = "src/test/java/dev/sample/AaAppTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        class AaAppTest {
            @Test void built() { assertTrue(new App().built().startsWith("time-")); }
        }
    """.trimIndent()

    private fun fixture(dir: File, extra: String) {
        build(dir, "build.gradle.kts" to minimalBuild + "\n" + stampBuild + "\n" + extra, app, appTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test", "-Ptime=1").build()
        changeBeta(dir)
    }

    private fun select(dir: File): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Ptime=2").build().output
    }

    @Test
    fun `a constant stamped on every build is named, and the classes that copied it run their tests`(@TempDir dir: File) {
        fixture(dir, "")

        val output = select(dir)

        assertTrue("dev.sample.AaAppTest" in ranTests(dir), output)
        assertContains(output, "compile-time constants changed with no source change behind them: dev.sample.Stamp TIME")
    }

    @Test
    fun `a constant stampedConstants declares runs no test that executed its copies, and the run names it`(@TempDir dir: File) {
        fixture(dir, "yoriwake { stampedConstants.add(\"dev.sample.Stamp#TIME\") }")

        val output = select(dir)

        assertFalse("dev.sample.AaAppTest" in ranTests(dir), output)
        assertTrue("dev.sample.BetaTest" in ranTests(dir), output)
        assertEquals(null, decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertContains(output, "stampedConstants left out 2 changed class(es)")
        assertContains(output, "dev.sample.Stamp#TIME")
    }

    /** As [stampBuild], with an undeclared `Stamp.MAX` from the `max` property beside `TIME`. */
    private val stampAndMaxBuild = """
        val generateStamp by tasks.registering {
            val time = providers.gradleProperty("time").orElse("1")
            val max = providers.gradleProperty("max").orElse("1")
            val out = layout.buildDirectory.dir("generated/stamp")
            inputs.property("time", time)
            inputs.property("max", max)
            outputs.dir(out)
            doLast {
                val file = out.get().file("dev/sample/Stamp.java").asFile
                file.parentFile.mkdirs()
                file.writeText("package dev.sample;\npublic final class Stamp { public static final String TIME = \"time-" + time.get() +
                    "\"; public static final int MAX = " + max.get() + "; }\n")
            }
        }
        sourceSets.main { java.srcDir(generateStamp) }
    """.trimIndent()

    private val gate = "src/main/java/dev/sample/Gate.java" to """
        package dev.sample;
        public class Gate { public boolean admits(int n) { return n <= Stamp.MAX; } }
    """.trimIndent()

    // Ordered before BetaTest, so sharing the changed class's JVM does not run it.
    private val gateTest = "src/test/java/dev/sample/AbGateTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        class AbGateTest {
            @Test void admits() { assertTrue(new Gate().admits(1)); }
        }
    """.trimIndent()

    @Test
    fun `tables an older yoriwake left behind do not mask a class whose undeclared constant moved`(@TempDir dir: File) {
        val declared = "yoriwake { stampedConstants.add(\"dev.sample.Stamp#TIME\") }"
        build(dir, "build.gradle.kts" to minimalBuild + "\n" + stampAndMaxBuild + "\n" + declared, app, appTest, gate, gateTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test", "-Ptime=1").build()
        val mapDir = File(dir, ".gradle/yoriwake").walkTopDown().single { it.name == CoverageDecoder.CAPTURE_COMMIT_FILE }.parentFile
        val left = listOf(CoverageDecoder.CONSTANT_FIELDS_FILE, CoverageDecoder.MASKED_DIGESTS_FILE)
            .associateWith { File(mapDir, it).readText() }

        // A recording by a yoriwake that writes neither table: it dates the map at the next commit
        // with MAX = 2, and leaves the first recording's tables, which name MAX = 1.
        File(dir, "notes.txt").writeText("next\n")
        commit(dir, "next")
        runner(dir, "test", "-Ptime=1", "-Pmax=2").build()
        left.forEach { (name, text) -> File(mapDir, name).writeText(text) }

        // MAX back to 1 with no source change: Gate's bytes differ from the map's, and only TIME is declared.
        changeBeta(dir)
        val output = select(dir)

        assertTrue("dev.sample.AbGateTest" in ranTests(dir), output)
        assertFalse("stampedConstants left out" in output, output)
    }
}
