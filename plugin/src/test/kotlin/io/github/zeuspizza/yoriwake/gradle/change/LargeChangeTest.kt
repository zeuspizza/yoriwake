package io.github.zeuspizza.yoriwake.gradle.change

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LargeChangeTest {

    @Test
    fun `only a count above the threshold refuses, naming both`() {
        assertNull(LargeChange.refusal(5_000, 5_000, "main"))
        assertEquals(
            "5001 paths changed against main, above the large-change threshold of 5000",
            LargeChange.refusal(5_001, 5_000, "main"),
        )
    }

    @Test
    fun `paths pathIgnore takes out are not counted, protected ones are`() {
        val changed = listOf("docs/a.md", "docs/b.md", "docs/src/c.md", "src/main/java/A.java", "docs/a.md")
        val declaration = PathIgnore.Declaration(listOf("docs/**"), File("."), emptyList(), emptyList(), emptySet())

        assertEquals(4, LargeChange.counted(changed, null))
        assertEquals(2, LargeChange.counted(changed, declaration))
    }
}
