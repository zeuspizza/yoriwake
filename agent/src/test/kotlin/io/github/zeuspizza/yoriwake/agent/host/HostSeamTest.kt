package io.github.zeuspizza.yoriwake.agent.host

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gradle's internal names change between releases without notice, so the agent names them in one
 * place: whatever else supports another host must not have to find them first.
 */
class HostSeamTest {

    @Test
    fun `Gradle-internal names appear only in the host package`() {
        val sources = File("src/main/java/io/github/zeuspizza/yoriwake/agent")
        assertTrue(sources.isDirectory, "run from the agent project: ${sources.absolutePath}")
        val naming = sources.walk().filter { it.isFile && it.name.endsWith(".java") }.filter { file ->
            val code = file.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\n]*"), "")
            "org.gradle." in code || "org/gradle/" in code
        }.map { it.relativeTo(sources).path }.toList()
        assertEquals(listOf("host/GradleHost.java"), naming)
    }
}
