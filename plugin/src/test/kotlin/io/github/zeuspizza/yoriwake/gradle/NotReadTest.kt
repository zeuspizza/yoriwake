package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.NotRead
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/** The changed paths that are no change: an IDE's state, and what `notReadByTests` declares. */
class NotReadTest {

    @Test
    fun `an IDE's state is no change at any depth, and nothing else is`() {
        val ide = listOf(
            ".idea/workspace.xml", "app/.idea/misc.xml", ".vscode/settings.json",
            "web/.fleet/settings.json", "app.iml", "app/app.main.iml",
        )
        val kept = listOf(
            "idea/a.txt", ".idea", ".ideas/x", "src/main/resources/x.iml.txt", "README.md",
            "src/main/java/A.java",
        )

        assertEquals(ide, (ide + kept).filter(NotRead::isIdeState))
    }

    @Test
    fun `the listing leaves an IDE's state out, as it leaves Gradle's`(@TempDir root: File) {
        val untracked = listOf(".idea/workspace.xml", "app/.vscode/x.json", "app/app.iml", "kept.txt")
            .joinToString("\u0000", postfix = "\u0000")
        val listed = WorkingTree.listing(root, emptyList()) { arguments ->
            if (arguments.first() == "diff") "" else untracked
        }

        assertEquals(listOf("kept.txt"), listed)
    }

    @Test
    fun `a declared glob matches a root-relative path whole, its star crossing directories`() {
        val declared = NotRead.declared(listOf("docs/*", "*.md", " .github/* ", ""))

        listOf("docs/a.txt", "docs/deep/b.png", "README.md", "app/notes.md", ".github/workflows/ci.yml")
            .forEach { assertEquals(true, declared(it), it) }
        listOf("app/docs/a.txt", "README.md.bak", "src/main/java/A.java", "docs")
            .forEach { assertEquals(false, declared(it), it) }
    }

    @Test
    fun `nothing is declared by default`() {
        assertEquals(false, NotRead.declared(emptyList())("README.md"))
    }
}
