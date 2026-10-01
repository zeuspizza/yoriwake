package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Changed files nothing reads, like a CI workflow, need not force a full run. A filename allowlist
// would be unsafe, because tests do read repository files.
class UnreadablePathsTest {

    private val named = emptySet<String>()

    @Test
    fun `a workflow file nothing names is not read by anything`() {
        val paths = listOf(".github/workflows/ci.yml", ".github/dependabot.yml")

        assertEquals(paths.toSet(), ClasspathScope.unreadable(paths, named))
    }

    @Test
    fun `a path some compiled class names keeps forcing`() {
        // A test that reads the README has "README.md" in its bytecode and depends on the file.
        val paths = listOf("README.md", ".github/workflows/ci.yml")

        val unreadable = ClasspathScope.unreadable(paths, setOf("README.md"))

        assertEquals(setOf(".github/workflows/ci.yml"), unreadable)
    }

    @Test
    fun `build scripts and settings keep forcing whether or not anything names them`() {
        // They change what compiles, which no amount of "nothing reads this at runtime" affects.
        val paths = listOf(
            "build.gradle.kts",
            "webserver/build.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            "gradle/libs.versions.toml",
            "gradlew",
        )

        assertTrue(ClasspathScope.unreadable(paths, named).isEmpty())
    }

    @Test
    fun `a class that names only the directory protects everything under it`(@TempDir dir: java.io.File) {
        // A path assembled at runtime (`Path.of("docs", name + ".md")`) never appears whole, so the
        // scan matches ancestors too. It searches raw bytes, so a fake class file is realistic.
        val classes = java.io.File(dir, "pkg").apply { mkdirs() }
        java.io.File(classes, "Reader.class").writeBytes("....docs....".toByteArray())
        val artifacts = TaskArtifacts(listOf(dir), emptyList())

        val named = artifacts.pathsNamedInClasses(listOf("docs/api.md", "other/thing.md"))

        assertEquals(setOf("docs/api.md"), named)
        assertEquals(setOf("other/thing.md"), ClasspathScope.unreadable(
            listOf("docs/api.md", "other/thing.md"), named,
        ))
    }

    @Test
    fun `a full path in bytecode is found as readily as a directory`(@TempDir dir: java.io.File) {
        val classes = java.io.File(dir, "pkg").apply { mkdirs() }
        java.io.File(classes, "Reader.class").writeBytes("x.github/workflows/ci.ymlx".toByteArray())
        val artifacts = TaskArtifacts(listOf(dir), emptyList())

        assertEquals(
            setOf(".github/workflows/ci.yml"),
            artifacts.pathsNamedInClasses(listOf(".github/workflows/ci.yml")),
        )
    }

    @Test
    fun `a source file keeps forcing, because coverage owns that question`() {
        val paths = listOf("core/src/main/resources/icons/thing.svg", "src/test/resources/flow.yaml")

        assertTrue(ClasspathScope.unreadable(paths, named).isEmpty())
    }
}
