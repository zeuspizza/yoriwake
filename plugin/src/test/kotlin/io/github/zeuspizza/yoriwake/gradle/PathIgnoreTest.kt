package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.select.FilterRule
import io.github.zeuspizza.yoriwake.gradle.change.PathIgnore
import io.github.zeuspizza.yoriwake.gradle.change.PathIgnore.Protection
import io.github.zeuspizza.yoriwake.gradle.change.validated
import io.github.zeuspizza.yoriwake.gradle.report.PathIgnoreReport
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.api.InvalidUserDataException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

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
    fun `a change-set item escapes a comma and a percent, and the test JVM reads back the path`() {
        // The escaped forms the agent's tests feed its matcher, so the two sides cannot drift.
        val escaped = mapOf(
            "a,b" to "a%2Cb", "100%" to "100%25", "a%2Cb" to "a%252Cb", "%25" to "%2525",
            "docker/users,v2.json" to "docker/users%2Cv2.json", "docker/100%.json" to "docker/100%25.json",
        )
        escaped.forEach { (path, item) ->
            assertEquals(item, PathIgnore.escape(path), path)
            assertEquals(path, FilterRule.unescape(item), item)
        }
    }

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
    fun `on Maven the POMs, the wrapper, mvn configuration and another build are kept as build-file`(@TempDir root: File) {
        File(root, "tools/standalone/pom.xml").also { it.parentFile.mkdirs() }.writeText("")
        File(root, "lib/pom.xml").also { it.parentFile.mkdirs() }.writeText("")
        val maven = PathIgnore.Declaration(listOf("**"), root, emptyList(), emptyList(), emptySet(), maven = true, moduleDirs = listOf("", "lib"))
        val kept = listOf("pom.xml", "lib/pom.xml", ".mvn/maven.config", ".mvn/extensions.xml", "mvnw", "mvnw.cmd", "lib/pom.yml", "tools/standalone/notes.md")

        val applied = PathIgnore.apply(kept + "lib/README.md", maven, namesNothing)

        assertEquals(kept.toSet(), applied.notIgnored.filter { it.protection == Protection.BUILD_FILE }.map { it.path }.toSet())
        assertEquals(listOf("lib/README.md"), applied.ignored.map { it.path })
    }

    @Test
    fun `under a Gradle declaration a pom is no build file`(@TempDir root: File) {
        val applied = PathIgnore.apply(listOf("pom.xml"), declaration(root, "**/*.xml"), namesNothing)
        assertEquals(listOf("pom.xml"), applied.ignored.map { it.path })
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
    fun `a path git printed in quotes is kept as git-quoted`(@TempDir root: File) {
        val quoted = "\"docs/\\303\\274bersicht.md\""
        val applied = PathIgnore.apply(listOf(quoted, "docs/a.md"), declaration(root, "**"), namesNothing)
        assertEquals(listOf(quoted), applied.kept)
        assertEquals(PathIgnore.Kept(quoted, "**", Protection.GIT_QUOTED), applied.notIgnored.single())
    }

    @Test
    fun `a map that records no reads is said to give no read evidence`(@TempDir root: File) {
        val applied = PathIgnore.apply(listOf("docs/a.md"), declaration(root, "docs/**"), namesNothing)
        val lines = PathIgnoreReport.lines(applied, File(root, "no-map"))
        assertTrue(lines.any { "nothing can show that the paths pathIgnore left out are unread" in it }, "$lines")
        assertEquals(PathIgnore.Evidence.NO_READ_EVIDENCE, PathIgnoreReport.evidence(applied, File(root, "no-map"))["docs/a.md"])
    }

    private fun withDefaults(root: File, vararg entries: String, sourceDirs: List<String> = emptyList()) =
        PathIgnore.Declaration(entries.toList(), root, sourceDirs, emptyList(), emptySet(), PathIgnore.DEFAULTS)

    private val noInputs: () -> List<String> = { emptyList() }

    private val everyJvmRecorded: () -> Boolean = { true }

    @Test
    fun `a build's own entry wins over a default that matches the same path`(@TempDir root: File) {
        val applied = PathIgnore.apply(listOf("docs/guide.md", "README.md"), withDefaults(root, "docs/**"), namesNothing)
        assertEquals(
            listOf(PathIgnore.Ignored("README.md", "**/README*", default = true), PathIgnore.Ignored("docs/guide.md", "docs/**")),
            applied.ignored,
        )
    }

    @Test
    fun `the docs default is the top-level directory only`(@TempDir root: File) {
        val applied = PathIgnore.apply(listOf("docs/a/b.txt", "core/docs/a.txt"), withDefaults(root), namesNothing)
        assertEquals(listOf(PathIgnore.Ignored("docs/a/b.txt", "docs/", default = true)), applied.ignored)
        assertEquals(listOf("core/docs/a.txt"), applied.kept)
    }

    @Test
    fun `a readme under src is kept from a default as source-set`(@TempDir root: File) {
        val applied = PathIgnore.apply(listOf("src/test/resources/README.txt"), withDefaults(root), namesNothing)
        assertEquals(
            PathIgnore.Kept("src/test/resources/README.txt", "**/README*", Protection.SOURCE_SET, default = true),
            applied.notIgnored.single(),
        )
    }

    @Test
    fun `a default-matched path the map cannot show unread stays, where a build's own entry leaves it out`(
        @TempDir root: File,
    ) {
        val noMap = File(root, "no-map")
        val byDefault = PathIgnore.gated(
            PathIgnore.apply(listOf("docs/x.md"), withDefaults(root), namesNothing),
            { PathIgnore.recordsEveryJvm(noMap) }, noInputs,
        )
        assertEquals(listOf("docs/x.md"), byDefault.kept)
        assertEquals(PathIgnore.Kept("docs/x.md", "docs/", Protection.NO_READ_EVIDENCE, default = true), byDefault.notIgnored.single())

        val declared = PathIgnore.gated(
            PathIgnore.apply(listOf("docs/x.md"), withDefaults(root, "docs/**"), namesNothing),
            { PathIgnore.recordsEveryJvm(noMap) }, noInputs,
        )
        assertEquals(emptyList(), declared.kept)
        assertEquals(listOf(PathIgnore.Ignored("docs/x.md", "docs/**")), declared.ignored)
    }

    @Test
    fun `a default-matched path stays left out when the map recorded every test JVM's reads`(@TempDir root: File) {
        val applied = PathIgnore.gated(PathIgnore.apply(listOf("docs/x.md"), withDefaults(root), namesNothing), everyJvmRecorded, noInputs)
        assertEquals(listOf(PathIgnore.Ignored("docs/x.md", "docs/", default = true)), applied.ignored)
    }

    @Test
    fun `a default-matched path stays when some test JVM's reads went unrecorded, whatever another JVM read`(@TempDir root: File) {
        // A read in a recorded JVM opens no window in an unrecorded one, so it cannot leave a default standing.
        val applied = PathIgnore.gated(PathIgnore.apply(listOf("docs/x.md"), withDefaults(root), namesNothing), { false }, noInputs)
        assertEquals(emptyList(), applied.ignored)
        assertEquals(PathIgnore.Kept("docs/x.md", "docs/", Protection.NO_READ_EVIDENCE, default = true), applied.notIgnored.single())
    }

    @Test
    fun `a test task input of its own under the root keeps every default-matched path`(@TempDir root: File) {
        val applied = PathIgnore.gated(
            PathIgnore.apply(listOf("docs/x.md", "README.md", "notes/a.txt"), withDefaults(root, "notes/**"), namesNothing),
            everyJvmRecorded, { listOf("docs/x.md") },
        )
        assertEquals(listOf(PathIgnore.Ignored("notes/a.txt", "notes/**")), applied.ignored)
        assertEquals(setOf(Protection.TASK_INPUT), applied.notIgnored.map { it.protection }.toSet())
        assertEquals(listOf("README.md", "docs/x.md"), applied.notIgnored.map { it.path })
    }

    @Test
    fun `no default left a path out, so neither the map nor the task's inputs are asked`(@TempDir root: File) {
        val applied = PathIgnore.apply(listOf("notes/a.txt"), withDefaults(root, "notes/**"), namesNothing)
        assertTrue(applied === PathIgnore.gated(applied, { error("map asked") }, { error("inputs asked") }))
    }

    @Test
    fun `a task input is any file under the root outside the classpath and the test classes`(@TempDir root: File) {
        val classes = File(root, "build/classes/java/test")
        val extra = io.github.zeuspizza.yoriwake.gradle.wiring.extraTaskInputs(
            root,
            setOf(File(classes, "dev/ATest.class"), File(root, "libs/a.jar"), File(root, "docs/x.md"), File("/elsewhere/agent.jar")),
            setOf(classes, File(root, "libs/a.jar")),
        )
        assertEquals(listOf("docs/x.md"), extra)
    }

    @Test
    fun `a build whose changed paths match no entry or default builds no declaration`(@TempDir root: File) {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val declare = { paths: List<String>, defaults: Boolean ->
            io.github.zeuspizza.yoriwake.gradle.wiring.pathIgnoreDeclaration(project, emptyList(), defaults, emptyList(), listOf("HEAD"), paths, null)
        }
        assertNull(declare(listOf("src/main/java/A.java"), true))
        assertNull(declare(listOf("docs/x.md"), false))
        assertEquals(PathIgnore.DEFAULTS, declare(listOf("docs/x.md"), true)!!.defaults)
    }

    @Test
    fun `a default entry is written with its prefix in the change set`() {
        assertEquals("docs/x.md\tdefault:docs/", PathIgnore.encode(PathIgnore.Ignored("docs/x.md", "docs/", default = true)))
    }

    @Test
    fun `an encoded item keeps commas and percent signs apart from the list`() {
        assertEquals("a%2Cb%25c.md\tdocs/**", PathIgnore.encode(PathIgnore.Ignored("a,b%c.md", "docs/**")))
        assertNull(PathIgnore.problem("docs/**"))
    }
}
