package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filter that decides which untracked and ignored paths are build state, held against the one
 * yoriwake 0.1.0 shipped. What enters a change set must not move when only the filter's cost does:
 * a path the new filter drops and the old one kept is a change selection no longer sees.
 */
class WorkingTreeFilterTest {

    // ---- yoriwake 0.1.0's filter, copied verbatim from WorkingTree.kt at release 0.1.0. Never
    // edit it: it is the definition the shipping filter is checked against.

    private fun isExcluded(
        path: String,
        excluded: Collection<String>,
        isGradleBuild: (String) -> Boolean,
    ): Boolean {
        val directories = path.split('/').dropLast(1)
        // `.kotlin` is the Kotlin Gradle plugin's state beside `.gradle`: a marker per live
        // compiler session, so every run that starts a new daemon would otherwise force.
        return ".gradle" in directories || ".kotlin" in directories || ".git" in directories ||
            excluded.any { path == it || path.startsWith("$it/") } ||
            directories.indices.any { i ->
                directories[i] == "build" && isGradleBuild(directories.subList(0, i).joinToString("/"))
            }
    }

    private val BUILD_SCRIPTS =
        listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

    private fun gradleBuildAt(rootDir: File): (String) -> Boolean {
        val seen = HashMap<String, Boolean>()
        return { dir ->
            seen.getOrPut(dir) {
                val at = if (dir.isEmpty()) rootDir else File(rootDir, dir)
                BUILD_SCRIPTS.any { File(at, it).isFile }
            }
        }
    }

    // ---- end of the 0.1.0 copy.

    private fun oracle(rootDir: File, excluded: Collection<String>): (String) -> Boolean {
        val isGradleBuild = gradleBuildAt(rootDir)
        return { path -> isExcluded(path, excluded, isGradleBuild) }
    }

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder("git", *args).directory(dir).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), "git ${args.joinToString(" ")} failed")
        return output
    }

    private fun file(root: File, path: String, text: String = "x") =
        File(root, path).also { it.parentFile.mkdirs() }.writeText(text)

    /**
     * A tree with every shape the filter distinguishes: nested builds with and without a build
     * script beside their `build/`, TestKit projects inside another build's output, Gradle and
     * Kotlin state at several depths, `node_modules`, names that only start like an excluded
     * directory, and symlinks git lists as one path.
     */
    private fun shapes(root: File): List<String> {
        git(root, "init", "--initial-branch=main")
        file(root, "settings.gradle.kts", "")
        file(root, "build.gradle.kts", "")
        file(root, "src/main/java/A.java", "class A {}")
        git(root, "add", ".")
        git(root, "-c", "user.email=t@e.com", "-c", "user.name=t", "-c", "commit.gpgsign=false",
            "commit", "-m", "base")

        file(root, "buildSrc/build.gradle.kts", "")
        file(root, "buildSrc/build/classes/B.class")
        listOf("p1", "p2").forEach { p ->
            file(root, "buildSrc/build/tmp/test/work/$p/settings.gradle", "")
            file(root, "buildSrc/build/tmp/test/work/$p/src/main/java/G.java")
            file(root, "buildSrc/build/tmp/test/work/$p/build/classes/G.class")
            file(root, "buildSrc/build/tmp/test/work/$p/.gradle/8.14/lock")
        }
        file(root, "buildSrc/src/main/kotlin/Conv.kt")
        file(root, "inc/settings.gradle", "")
        file(root, "inc/build/libs/inc.jar")
        file(root, "inc/src/main/java/I.java")
        file(root, "node_modules/left-pad/index.js")
        file(root, "docs-client/node_modules/react/index.js")
        file(root, ".gradle/8.14/fileHashes/lock")
        file(root, "app/.gradle/config.properties")
        file(root, "app/sub/.kotlin/sessions/s.salive")
        file(root, ".kotlin/errors/e.log")
        file(root, "app/build/classes/A.class")
        file(root, "app/build.gradle.kts", "")
        file(root, "app2/build/classes/A.class")
        file(root, "app2/data.txt")
        file(root, "apple")
        file(root, "lonely/build/out.txt")
        file(root, "out/production/A.class")
        file(root, "build/reports/r.html")
        file(root, "data/fixture.local")
        val linked = File(root, "linked").toPath()
        Files.createSymbolicLink(linked, File(root, "app2").toPath())
        Files.createSymbolicLink(File(root, "excludedLink").toPath(), File(root, "data").toPath())
        Files.createSymbolicLink(File(root, "app/build/libs").toPath().also {
            it.parent.toFile().mkdirs()
        }, File(root, "data").toPath())

        return git(root, "ls-files", "-z", "--others").split('\u0000').filter(String::isNotEmpty)
    }

    private val shapesExcluded = listOf("app/build", "app2/build", "build", "excludedLink", "out")

    @Test
    fun `the constant-depth filter keeps exactly the paths 0_1_0's keeps`(@TempDir root: File) {
        val listed = shapes(root)
        val old = oracle(root, shapesExcluded)
        // A fixture the filter cannot fail on proves nothing: both outcomes must be present.
        assertTrue(listed.any(old) && listed.any { !old(it) }, "the fixture does not exercise the filter")

        val new = WorkingTree.buildState(root, shapesExcluded)
        listed.forEach { path -> assertEquals(old(path), new(path), path) }

        // And through the listing, which is what a change set is built from.
        assertEquals(
            listed.filterNot(old).sorted(),
            assertNotNull(WorkingTree.listing(root, shapesExcluded)).sorted(),
        )
    }

    @Test
    fun `seeded random paths decide alike`(@TempDir root: File) {
        val seed = 149L
        val random = Random(seed)
        val components = listOf("build", "buildSrc", ".gradle", ".kotlin", "app", "app2", "node_modules", "src")
        fun randomPath(depth: Int) = (1..depth).joinToString("/") { components.random(random) }

        repeat(20) { round ->
            val dir = File(root, "round$round").also { it.mkdirs() }
            // Build scripts in random directories, so `build` beside one is output and beside none is not.
            repeat(15) {
                val at = randomPath(random.nextInt(0, 4))
                val script = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                    .random(random)
                file(dir, if (at.isEmpty()) script else "$at/$script", "")
            }
            val excluded = (1..random.nextInt(0, 6)).map { randomPath(random.nextInt(1, 4)) }.distinct()
            val paths = (1..300).map { randomPath(random.nextInt(1, 8)) } + excluded

            val old = oracle(dir, excluded)
            val new = WorkingTree.buildState(dir, excluded)
            paths.forEach { path ->
                assertEquals(
                    old(path), new(path),
                    "seed $seed, round $round: '$path' against excluded $excluded",
                )
            }
        }
    }

    @Test
    fun `a path equal to an excluded directory is left out`(@TempDir root: File) {
        // git lists an untracked nested repository as `nested/`, and a symlink as the link's own path.
        val untracked = listOf("nested/", "out", "kept.txt").joinToString("\u0000", postfix = "\u0000")
        val listed = WorkingTree.listing(root, listOf("nested", "out")) { arguments ->
            if (arguments.first() == "diff") "" else untracked
        }

        assertEquals(listOf("kept.txt"), listed)
    }

    @Test
    fun `a name that only starts like an excluded directory is kept`(@TempDir root: File) {
        file(root, "build.gradle.kts", "")
        val untracked = listOf("app/x", "app2/x", "apple", "build/x", "buildSrc/x", "buildSrc.txt")
            .joinToString("\u0000", postfix = "\u0000")
        val listed = WorkingTree.listing(root, listOf("app")) { arguments ->
            if (arguments.first() == "diff") "" else untracked
        }

        assertEquals(listOf("app2/x", "apple", "buildSrc/x", "buildSrc.txt"), listed)
    }

    @Test
    fun `buildState and listing decide alike`(@TempDir root: File) {
        // The recheck at execution filters with one and compares against the other; a path they
        // decided differently would read as the tree changing, and refuse.
        val listed = shapes(root)
        val kept = assertNotNull(WorkingTree.listing(root, shapesExcluded)).toSet()
        val isBuildState = WorkingTree.buildState(root, shapesExcluded)

        listed.forEach { path -> assertEquals(path !in kept, isBuildState(path), path) }
    }

    @Test
    fun `a non-UTF-8 name still makes the tree unlisted`(@TempDir root: File) {
        // A name that does not decode reads as a file that is always absent, so it is no answer.
        fun listing(vararg untracked: String) = WorkingTree.listing(root, listOf("build")) { arguments ->
            if (arguments.first() == "diff") "" else untracked.joinToString("\u0000", postfix = "\u0000")
        }

        assertNull(listing("kept.txt", "caf�.txt"))
        // Filtered first: an undecodable name inside build output is output, not a gap in the listing.
        assertFalse(listing("kept.txt", "build/caf�.txt") == null)
    }
}
