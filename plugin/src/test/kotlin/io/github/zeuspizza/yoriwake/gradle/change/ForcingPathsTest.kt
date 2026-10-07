package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.change.ForcingPaths.Origin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForcingPathsTest {

    private val sourceDirs = listOf("app/src/main/resources", "app/src/test/resources")

    private fun classify(
        forcing: Collection<String>,
        tracked: Set<String> = emptySet(),
        captured: Map<String, WorkingTree.Move>? = emptyMap(),
    ) = ForcingPaths.classify(forcing, sourceDirs, { tracked }, { captured })

    @Test
    fun `a path in a source directory is named as there even when the capture moved it`() {
        val classified = classify(
            listOf("app/src/main/resources/info.properties"),
            captured = mapOf("app/src/main/resources/info.properties" to WorkingTree.Move.CHANGED),
        )

        assertEquals(mapOf("app/src/main/resources/info.properties" to Origin.GENERATED_IN_SOURCES), classified.origins)
    }

    @Test
    fun `a path in a test source directory is named as in the sources`() {
        assertEquals(
            mapOf("app/src/test/resources/fixture.txt" to Origin.GENERATED_IN_SOURCES),
            classify(listOf("app/src/test/resources/fixture.txt")).origins,
        )
    }

    @Test
    fun `a path the capture moved is written during it, and any other untracked path is untracked`() {
        val classified = classify(
            listOf("app/run.log", "app/data.json"),
            captured = mapOf("app/run.log" to WorkingTree.Move.CREATED),
        )

        assertEquals(mapOf("app/data.json" to Origin.UNTRACKED, "app/run.log" to Origin.WRITTEN_DURING_CAPTURE), classified.origins)
        assertEquals(mapOf("app/run.log" to WorkingTree.Move.CREATED), classified.moves)
    }

    @Test
    fun `a path the capture deleted and that is absent now is written during it`() {
        assertEquals(
            mapOf("app/old.log" to Origin.WRITTEN_DURING_CAPTURE),
            classify(listOf("app/old.log"), captured = mapOf("app/old.log" to WorkingTree.Move.DELETED)).origins,
        )
    }

    @Test
    fun `a path HEAD tracks gets no origin, even one the capture moved`() {
        val classified = classify(
            listOf("app/golden.txt", "app/run.log"),
            tracked = setOf("app/golden.txt"),
            captured = mapOf("app/golden.txt" to WorkingTree.Move.CHANGED, "app/run.log" to WorkingTree.Move.CHANGED),
        )

        assertEquals(mapOf("app/run.log" to Origin.WRITTEN_DURING_CAPTURE), classified.origins)
        assertEquals(1, classified.unnamed)
    }

    @Test
    fun `without a record of the capture a moved path is untracked`() {
        assertEquals(mapOf("app/run.log" to Origin.UNTRACKED), classify(listOf("app/run.log"), captured = null).origins)
    }

    @Test
    fun `when git cannot say what is tracked no path gets an origin`() {
        val classified = ForcingPaths.classify(listOf("app/run.log"), sourceDirs, { null }, { emptyMap() })

        assertEquals(emptyMap(), classified.origins)
        assertEquals(1, classified.unnamed)
    }

    @Test
    fun `no forcing path asks neither git nor the record`() {
        var asked = false

        val classified = ForcingPaths.classify(
            emptyList(), sourceDirs,
            { asked = true; emptySet() },
            { asked = true; emptyMap() },
        )

        assertFalse(asked)
        assertEquals(emptyList(), ForcingPaths.lines(":app:test", classified))
    }

    @Test
    fun `only tracked forcing paths leave the record unread and print nothing`() {
        var read = false

        val classified = ForcingPaths.classify(
            listOf("build.gradle.kts"), sourceDirs, { setOf("build.gradle.kts") }, { read = true; emptyMap() },
        )

        assertFalse(read)
        assertEquals(emptyList(), ForcingPaths.lines(":app:test", classified))
    }

    @Test
    fun `one line per origin, five paths each, then the count of the rest`() {
        val written = (1..7).map { "app/f$it.log" }
        val classified = classify(
            written + listOf("app/src/main/resources/x.txt", "app/data.json", "build.gradle.kts"),
            tracked = setOf("build.gradle.kts"),
            captured = written.associateWith { WorkingTree.Move.CHANGED },
        )

        val lines = ForcingPaths.lines(":app:test", classified)

        assertEquals(4, lines.size, lines.joinToString("\n"))
        (1..5).forEach { assertContains(lines[0], "app/f$it.log") }
        assertContains(lines[0], "and 2 more")
        assertFalse("app/f6.log" in lines[0])
        assertContains(lines[0], "derby.stream.error.file")
        assertContains(lines[1], "app/src/main/resources/x.txt")
        assertContains(lines[2], "app/data.json")
        assertTrue(lines.drop(1).none { "derby.stream.error.file" in it })
        assertContains(lines[3], "1 other")
    }

    @Test
    fun `a build under a subdirectory of the repo names no tracked path, inside it or outside`(@TempDir repo: File) {
        fun git(vararg args: String) {
            val code = ProcessBuilder("git", *args).directory(repo).redirectErrorStream(true).start().waitFor()
            check(code == 0) { "git ${args.joinToString(" ")} failed with $code" }
        }
        git("init")
        val root = File(repo, "backend")
        File(root, "src/main/resources").mkdirs()
        File(root, "src/main/resources/a.txt").writeText("a")
        File(repo, "gradle").mkdirs()
        File(repo, "gradle/libs.versions.toml").writeText("[versions]\n")
        git("add", ".")
        git("-c", "user.email=t@e.com", "-c", "user.name=t", "-c", "commit.gpgsign=false", "commit", "-m", "base")
        File(repo, "gradle/libs.versions.toml").appendText("# touched\n")
        File(root, "src/main/resources/a.txt").writeText("b")
        File(root, "notes.txt").writeText("n")

        // As the change set has them: rebased onto the build, and repo-relative outside it.
        val classified = ForcingPaths.classify(
            root, File(repo, "map"),
            listOf("gradle/libs.versions.toml", "src/main/resources/a.txt", "notes.txt"),
            listOf("src/main/resources"),
            base = "HEAD",
        )

        assertEquals(mapOf("notes.txt" to Origin.UNTRACKED), classified.origins)
        assertEquals(2, classified.unnamed)
    }
}
