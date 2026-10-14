package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Which Gradle and which daemon JDK this suite's builds really ran on.
 *
 * A supported-matrix cell is labelled with both, and a green cell must not be a run of the build's
 * defaults under another label: a toolchain that was not found, or a run line that dropped the
 * cell's properties, would otherwise pass the whole suite and put a row in the table nothing ran.
 * The observed pair is printed for the matrix summary, which compares it with the cell's label.
 */
class MatrixCellIdentityTest : FunctionalTestSupport() {

    @Test
    fun `the builds run on the Gradle and daemon JDK this cell asks for`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to """
                println("observed gradle=" + gradle.gradleVersion + " jdk=" + System.getProperty("java.specification.version"))
            """.trimIndent(),
        )

        val observed = runner(dir, "help").build().output.lines().single { it.startsWith("observed ") }
        println("yoriwake-cell ${observed.removePrefix("observed ")}")

        val expectedJdk = requireNotNull(System.getProperty("yoriwake.functional.expectedJdk")) {
            "the build did not say which daemon JDK to expect; run this through Gradle"
        }
        assertEquals("observed gradle=${FunctionalGradle.version} jdk=$expectedJdk", observed)
    }
}
