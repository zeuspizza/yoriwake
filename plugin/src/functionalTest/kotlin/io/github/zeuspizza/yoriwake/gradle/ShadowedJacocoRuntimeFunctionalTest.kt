package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * A JaCoCo runtime of another version on the test classpath, as Android's `testCoverageEnabled`
 * adds, comes before the `-javaagent`'s jar: the capture still records through the agent that
 * started, and a selecting run narrows on the map.
 */
class ShadowedJacocoRuntimeFunctionalTest : FunctionalTestSupport() {

    private val shadowed = minimalBuild + System.lineSeparator() + """
        jacoco { toolVersion = "0.8.14" }
        dependencies { testRuntimeOnly("org.jacoco:org.jacoco.agent:0.8.13:runtime") }
    """.trimIndent()

    @Test
    fun `a capture behind another JaCoCo runtime records a map and a selecting run narrows on it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to shadowed, oneClass, oneTest, secondClass, secondTest)
        committed(dir)

        val captured = runner(dir, "test").build().output

        assertFalse("recorded no coverage" in captured, captured)
        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            "package dev.sample;\npublic class Alpha { public int twice(int n) { return n + n; } }"
        )

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(output, ":test: narrowing")
        assertContains(output, "reaches-change=1")
    }
}
