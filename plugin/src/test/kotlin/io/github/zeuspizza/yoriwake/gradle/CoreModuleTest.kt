package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared core names no Gradle type, so a front end for another build tool can use it. Its own
 * build has no Gradle API on the classpath; this also catches a name in a comment or a string,
 * which would read as a promise the core does not keep.
 */
class CoreModuleTest {

    @Test
    fun `no core source names Gradle`() {
        val sources = File("../core/src/main/kotlin")
        assertTrue(sources.isDirectory, "run from the plugin project: ${sources.absolutePath}")
        val naming = sources.walk().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().let { "org.gradle." in it || "org/gradle/" in it } }
            .map { it.relativeTo(sources).invariantSeparatorsPath }.toList()
        assertEquals(emptyList(), naming)
    }
}
