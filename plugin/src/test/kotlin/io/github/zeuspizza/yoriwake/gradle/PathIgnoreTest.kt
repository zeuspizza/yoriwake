package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.PathIgnore
import io.github.zeuspizza.yoriwake.gradle.change.PathIgnore.Protection
import org.gradle.api.InvalidUserDataException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathIgnoreTest {

    private val files = listOf(
        "README.md", "CHANGELOG.md", "notes.txt",
        "docs/guide.md", "docs/api/index.md", "docs/api/deep/x.txt", "docs/a.b",
        "core/README.md", "core/docs/guide.md",
        "a/b", "a/x/b", "a/x/y/b", "a/bb",
        "x1.md", "x22.md",
        "with space/file one.md",
    )

    private val patterns = listOf(
        "docs/**", "**/*.md", "*.md", "docs/", "a/**/b", "x?.md", "docs/*", "docs/*/*.md",
        "**", "docs/**/*.txt", "**/docs/**", "core/*", "with space/*",
    )

    @Test
    fun `each pattern matches what Gradle's include matches`(@TempDir dir: File) {
        files.forEach { File(dir, it).also { file -> file.parentFile.mkdirs() }.writeText("x") }
        val project = ProjectBuilder.builder().withProjectDir(File(dir, "project").also { it.mkdirs() }).build()
        val all = files.toSet()
        patterns.forEach { pattern ->
            val gradle = project.fileTree(dir).matching { it.include(pattern) }.files
                .map { it.relativeTo(dir).invariantSeparatorsPath }.filter { it in all }.toSet()
            val ours = files.filter { PathIgnore.matches(pattern, it) }.toSet()
            assertEquals(gradle, ours, "pattern $pattern")
        }
    }

    @Test
    fun `a star stays within one directory`() {
        assertTrue(PathIgnore.matches("docs/*", "docs/guide.md"))
        assertFalse(PathIgnore.matches("docs/*", "docs/api/index.md"))
        assertFalse(PathIgnore.matches("*.md", "core/README.md"))
    }

    @Test
    fun `a leading double star matches no directory as well as several`() {
        assertTrue(PathIgnore.matches("**/*.md", "README.md"))
        assertTrue(PathIgnore.matches("**/*.md", "core/README.md"))
        assertTrue(PathIgnore.matches("**/*.md", "a/b/c/d.md"))
    }

    @Test
    fun `matching is case-exact`() {
        assertFalse(PathIgnore.matches("docs/**", "Docs/guide.md"))
        assertFalse(PathIgnore.matches("*.md", "README.MD"))
    }

    @Test
    fun `paths with spaces and non-ASCII names match as written`() {
        assertTrue(PathIgnore.matches("with space/*", "with space/file one.md"))
        assertTrue(PathIgnore.matches("docs/ü*", "docs/übersicht.md"))
        assertTrue(PathIgnore.matches("docs/??.md", "docs/日本.md"))
    }

    @Test
    fun `entries written for another dialect fail the build naming pathIgnore and the entry`() {
        listOf("!docs/**", "/docs/**", "docs\\x", "../x", "docs/./x", "docs/{a,b}", "docs/[ab]", "", " ", "C:/docs",
            "docs/a**", "docs\tx").forEach { entry ->
            val failure = assertThrows<InvalidUserDataException>("entry '$entry'") {
                PathIgnore.validated(listOf("docs/**", entry), ":app")
            }
            assertContains(failure.message!!, "pathIgnore")
            assertContains(failure.message!!, "'$entry'")
            assertContains(failure.message!!, ":app")
        }
    }

    @Test
    fun `entries in the grammar pass validation`() {
        val entries = listOf("docs/**", "**/*.md", "*.md", "docs/", "a/**/b", "x?.md", "**", "with space/*")
        assertEquals(entries, PathIgnore.validated(entries, ":"))
    }

    private fun declaration(
        root: File,
        vararg entries: String,
        sourceDirs: List<String> = emptyList(),
        buildLogicDirs: List<String> = emptyList(),
        outsideRoot: Set<String> = emptySet(),
    ) = PathIgnore.Declaration(entries.toList(), root, sourceDirs, buildLogicDirs, outsideRoot)

    private val namesNothing: (Collection<String>) -> Set<String> = { emptySet() }

    @Test
    fun `a matched path leaves the change set with the entry that matched it`(@TempDir root: File) {
        val applied = PathIgnore.apply(
            listOf("docs/guide.md", "core/src/main/java/a/Calc.java"), declaration(root, "docs/**"), namesNothing,
        )
        assertEquals(listOf("core/src/main/java/a/Calc.java"), applied.kept)
        assertEquals(listOf(PathIgnore.Ignored("docs/guide.md", "docs/**")), applied.ignored)
        assertTrue(applied.notIgnored.isEmpty())
    }

    @Test
    fun `no declaration leaves every path in place`(@TempDir root: File) {
        val paths = listOf("docs/guide.md", "README.md")
        assertEquals(paths, PathIgnore.apply(paths, null, namesNothing).kept)
        assertEquals(paths, PathIgnore.apply(paths, declaration(root), namesNothing).kept)
    }

    @Test
    fun `a file under any src directory is kept as source-set`(@TempDir root: File) {
        val applied = PathIgnore.apply(
            listOf("core/src/test/resources/expected.md", "app/src/test/resources/fixture.md", "docs/src/docs/x.md"),
            declaration(root, "**/*.md"), namesNothing,
        )
        assertTrue(applied.ignored.isEmpty())
        assertEquals(setOf(Protection.SOURCE_SET), applied.notIgnored.map { it.protection }.toSet())
        assertEquals(3, applied.kept.size)
    }

    @Test
    fun `a file under a source directory Gradle reports is kept as source-set`(@TempDir root: File) {
        val applied = PathIgnore.apply(
            listOf("lib/fixtures/data.md"), declaration(root, "**", sourceDirs = listOf("lib/fixtures")), namesNothing,
        )
        assertEquals(listOf("lib/fixtures/data.md"), applied.kept)
        assertEquals(Protection.SOURCE_SET, applied.notIgnored.single().protection)
    }

    @Test
    fun `build files and build logic are kept as build-file`(@TempDir root: File) {
        File(root, "tools/settings.gradle.kts").also { it.parentFile.mkdirs() }.writeText("")
        val paths = listOf(
            "build.gradle.kts", "settings.gradle", "gradle.properties", "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties", "gradlew", "buildSrc/README.md", "conventions.gradle",
            "logic/README.md", "tools/README.md", "libs.versions.toml",
        )
        val applied = PathIgnore.apply(paths, declaration(root, "**", buildLogicDirs = listOf("logic")), namesNothing)
        assertTrue(applied.ignored.isEmpty(), "${applied.ignored}")
        assertEquals(setOf(Protection.BUILD_FILE), applied.notIgnored.map { it.protection }.toSet())
    }

    @Test
    fun `a path outside the Gradle root is kept as outside-root`(@TempDir root: File) {
        val applied = PathIgnore.apply(
            listOf("docs/x.md"), declaration(root, "docs/**", outsideRoot = setOf("docs/x.md")), namesNothing,
        )
        assertEquals(listOf("docs/x.md"), applied.kept)
        assertEquals(Protection.OUTSIDE_ROOT, applied.notIgnored.single().protection)
    }

    @Test
    fun `a path a compiled class names is kept as named-in-classes`(@TempDir root: File) {
        val applied = PathIgnore.apply(
            listOf("README.md", "docs/guide.md"), declaration(root, "**/*.md"), { setOf("README.md") },
        )
        assertEquals(listOf("README.md"), applied.kept)
        assertEquals(PathIgnore.Kept("README.md", "**/*.md", Protection.NAMED_IN_CLASSES), applied.notIgnored.single())
    }

    @Test
    fun `classes that cannot be searched keep every matched path`(@TempDir root: File) {
        val failing: (Collection<String>) -> Set<String>? = { error("no classes") }
        assertEquals(listOf("README.md"), PathIgnore.apply(listOf("README.md"), declaration(root, "*.md"), failing).kept)
        assertEquals(listOf("README.md"), PathIgnore.apply(listOf("README.md"), declaration(root, "*.md")) { null }.kept)
    }

    @Test
    fun `a double star alone leaves out only unprotected paths`(@TempDir root: File) {
        val paths = listOf("src/main/java/a/A.java", "src/main/resources/app.yml", "build.gradle.kts", "README.md")
        val applied = PathIgnore.apply(paths, declaration(root, "**"), namesNothing)
        assertEquals(listOf("README.md"), applied.ignored.map { it.path })
        assertEquals(paths - "README.md", applied.kept)
    }

    @Test
    fun `an encoded item keeps commas and percent signs apart from the list`() {
        assertEquals("a%2Cb%25c.md\tdocs/**", PathIgnore.encode(PathIgnore.Ignored("a,b%c.md", "docs/**")))
        assertNull(PathIgnore.problem("docs/**"))
    }
}
