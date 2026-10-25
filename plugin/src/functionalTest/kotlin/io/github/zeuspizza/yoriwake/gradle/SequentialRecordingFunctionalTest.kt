package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A test JVM records only once it has confirmed its tests run one at a time: parallel execution
 * the plugin could not see from the build script still leaves the map as it was.
 */
class SequentialRecordingFunctionalTest : FunctionalTestSupport() {

    private val switch = "junit.jupiter.execution.parallel.enabled"

    /** Each map file by its path to a digest of its bytes, leaving out what every run writes afresh. */
    private fun mapDigest(dir: File): Map<String, String> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val perRun = Regex("""raw/.*|decisions\.tsv.*|task-facts|change-set|classpath-files\.compared|.*\.(pending|part|marker)""")
        return mapDir.walk().filter(File::isFile).filterNot { perRun.matches(it.relativeTo(mapDir).invariantSeparatorsPath) }.associate { file ->
            file.relativeTo(mapDir).invariantSeparatorsPath to
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }.toSortedMap()
    }

    @Test
    fun `parallel execution a host's doFirst turns on records nothing`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild + "\nif (providers.gradleProperty(\"late\").isPresent) {\n" +
                "tasks.test { doFirst { systemProperty(\"$switch\", \"true\") } }\n}\n",
            oneClass, oneTest, secondClass, secondTest,
        )
        committed(dir)
        runner(dir, "test").build()
        val before = mapDigest(dir)
        File(dir, "src/main/java/dev/sample/Alpha.java").appendText("\n")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Plate").build().output

        assertContains(output, "JUnit Jupiter's parallel execution is on in the test JVM")
        assertContains(output, "the map is left exactly as it was")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        val after = mapDigest(dir)
        assertEquals(emptyList(), (before.keys + after.keys).filter { before[it] != after[it] }, output)
    }
}
