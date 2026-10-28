package io.github.zeuspizza.yoriwake.gradle.change

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClasspathFilesTest {

    private fun jar(target: File, time: Long, vararg entries: Pair<String, String>): File {
        target.parentFile.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { this.time = time })
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }

    private fun file(root: File, path: String, content: String) =
        File(root, path).apply { parentFile.mkdirs(); writeText(content) }

    private fun found(walk: ClasspathFiles.Walk) = assertIs<ClasspathFiles.Walk.Found>(walk).digests

    /** [found] without each jar manifest's loaded-attribute row, for tests about which files are walked. */
    private fun files(walk: ClasspathFiles.Walk) = found(walk).filterKeys { !it.endsWith(ClasspathFiles.EXPOSED_SUFFIX) }

    @Test
    fun `a resource directory and an own jar give a row per non-class file, the jar per entry`(@TempDir root: File) {
        val resources = File(root, "app/build/resources/test")
        file(resources, "info.properties", "info=a\n")
        file(resources, "nested/data.json", "{}")
        file(resources, "dev/sample/Ignored.class", "bytes")
        val lib = jar(
            File(root, "lib/build/libs/lib.jar"), 0L,
            "META-INF/MANIFEST.MF" to "Build-Revision: r1\n",
            "dev/lib/Lib.class" to "bytes",
        )

        val digests = found(ClasspathFiles.walk(listOf(resources, lib), listOf(), root))

        assertEquals(
            setOf(
                "app/build/resources/test/info.properties",
                "app/build/resources/test/nested/data.json",
                "lib/build/libs/lib.jar!/META-INF/MANIFEST.MF",
                "lib/build/libs/lib.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}",
            ),
            digests.keys,
        )
    }

    private fun manifestRows(root: File, manifest: String): Map<String, String> {
        val lib = jar(File(root, "lib.jar"), 0L, "META-INF/MANIFEST.MF" to manifest)
        return found(ClasspathFiles.walk(listOf(lib), listOf(), root))
    }

    @Test
    fun `a stamp the class loader does not take moves the manifest and not its loaded attributes`(@TempDir root: File) {
        val before = manifestRows(root, "Manifest-Version: 1.0\nImplementation-Version: 1\nBuild-Time: t1\n")
        val after = manifestRows(root, "Manifest-Version: 1.0\nImplementation-Version: 1\nBuild-Time: t2\n")

        assertEquals(mapOf("lib.jar!/META-INF/MANIFEST.MF" to "changed"), ClasspathFiles.compare(before, after).files)
    }

    @Test
    fun `an attribute the class loader takes moves the manifest's loaded attributes too`(@TempDir root: File) {
        for (name in listOf("Class-Path", "Multi-Release", "Automatic-Module-Name", "Implementation-Version", "Sealed")) {
            val before = manifestRows(root, "Manifest-Version: 1.0\n$name: a\n")
            val after = manifestRows(root, "Manifest-Version: 1.0\n$name: b\n")

            assertEquals(
                setOf("lib.jar!/META-INF/MANIFEST.MF", "lib.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}"),
                ClasspathFiles.compare(before, after).files.keys,
                name,
            )
        }
    }

    @Test
    fun `a package's section counts as the main section does`(@TempDir root: File) {
        val before = manifestRows(root, "Manifest-Version: 1.0\n\nName: dev/lib/\nSpecification-Version: 1\n")
        val after = manifestRows(root, "Manifest-Version: 1.0\n\nName: dev/lib/\nSpecification-Version: 2\n")

        assertTrue(
            "lib.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}" in ClasspathFiles.compare(before, after).files,
        )
    }

    @Test
    fun `a manifest that does not parse cannot vouch for its loaded attributes`(@TempDir root: File) {
        val rows = manifestRows(root, "not a manifest line without a colon\n")

        assertEquals(ClasspathFiles.UNREADABLE, rows["lib.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}"])
    }

    @Test
    fun `a directory's manifest is a plain file`(@TempDir root: File) {
        val resources = File(root, "res")
        file(resources, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 1\n")

        assertEquals(setOf("res/META-INF/MANIFEST.MF"), found(ClasspathFiles.walk(listOf(resources), listOf(), root)).keys)
    }

    @Test
    fun `a dependency jar from outside the root gives no rows`(@TempDir dir: File) {
        val root = File(dir, "project").apply { mkdirs() }
        val dependency = jar(File(dir, "cache/dep.jar"), 0L, "META-INF/MANIFEST.MF" to "x\n")

        assertEquals(emptyMap(), found(ClasspathFiles.walk(listOf(dependency), listOf(), root)))
    }

    @Test
    fun `a module whose build directory lies outside the root still gives rows`(@TempDir dir: File) {
        val root = File(dir, "project").apply { mkdirs() }
        val buildDir = File(dir, "out/app")
        val resources = File(buildDir, "resources/test")
        file(resources, "info.properties", "info=a\n")

        val digests = found(ClasspathFiles.walk(listOf(resources), listOf(buildDir.path), root))

        assertEquals(setOf("../out/app/resources/test/info.properties"), digests.keys)
    }

    @Test
    fun `Gradle's state under the root is not build output`(@TempDir root: File) {
        val agent = jar(File(root, ".gradle/yoriwake-agent/agent.jar"), 0L, "META-INF/MANIFEST.MF" to "x\n")

        assertEquals(emptyMap(), found(ClasspathFiles.walk(listOf(agent), listOf(), root)))
    }

    @Test
    fun `a jar rebuilt with the same entries and new timestamps gives the same rows`(@TempDir root: File) {
        val lib = File(root, "lib/build/libs/lib.jar")
        jar(lib, 0L, "META-INF/MANIFEST.MF" to "Build-Revision: r1\n", "a.txt" to "a")
        val before = found(ClasspathFiles.walk(listOf(lib), listOf(), root))

        jar(lib, 1_700_000_000_000L, "META-INF/MANIFEST.MF" to "Build-Revision: r1\n", "a.txt" to "a")

        assertEquals(before, found(ClasspathFiles.walk(listOf(lib), listOf(), root)))
    }

    @Test
    fun `a walk over its file budget refuses rather than recording part of the files`(@TempDir root: File) {
        val resources = File(root, "build/resources/test")
        repeat(3) { file(resources, "r$it.txt", "$it") }

        assertIs<ClasspathFiles.Walk.Refused>(ClasspathFiles.walk(listOf(resources), listOf(), root, maxFiles = 2))
    }

    @Test
    fun `a jar that cannot be opened moves as a whole`(@TempDir root: File) {
        val broken = file(root, "lib/build/libs/lib.jar", "not a zip")

        assertEquals(
            mapOf("lib/build/libs/lib.jar" to ClasspathFiles.UNREADABLE),
            found(ClasspathFiles.walk(listOf(broken), listOf(), root)),
        )
    }

    @Test
    fun `what a walked jar's manifest Class-Path names gives rows too, class files included`(@TempDir root: File) {
        val extra = File(root, "app/build/extra")
        val path = jar(File(extra, "path.jar"), 0L, "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nClass-Path: flavor.jar lib/\n")
        jar(File(extra, "flavor.jar"), 0L, "flavor.properties" to "flavor=a\n", "dev/F.class" to "bytes")
        file(extra, "lib/x.txt", "x")
        file(extra, "lib/dev/Y.class", "bytes")

        val digests = files(ClasspathFiles.walk(listOf(path), listOf(), root))

        assertEquals(
            setOf(
                "app/build/extra/path.jar!/META-INF/MANIFEST.MF",
                "app/build/extra/flavor.jar!/flavor.properties",
                "app/build/extra/flavor.jar!/dev/F.class",
                "app/build/extra/lib/x.txt",
                "app/build/extra/lib/dev/Y.class",
            ),
            digests.keys,
        )
    }

    @Test
    fun `jars whose manifests name each other are walked once each`(@TempDir root: File) {
        val libs = File(root, "build/libs")
        val a = jar(File(libs, "a.jar"), 0L, "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nClass-Path: b.jar\n")
        jar(File(libs, "b.jar"), 0L, "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nClass-Path: a.jar\n")

        assertEquals(
            setOf("build/libs/a.jar!/META-INF/MANIFEST.MF", "build/libs/b.jar!/META-INF/MANIFEST.MF"),
            files(ClasspathFiles.walk(listOf(a), listOf(), root)).keys,
        )
    }

    @Test
    fun `a Class-Path entry the walk cannot resolve moves`(@TempDir root: File) {
        val path = jar(File(root, "build/libs/path.jar"), 0L, "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nClass-Path: %zz\n")

        assertTrue(ClasspathFiles.UNREADABLE in found(ClasspathFiles.walk(listOf(path), listOf(), root)).values)
    }

    @Test
    fun `a Class-Path entry that is not a file URL, or names nothing, gives no rows`(@TempDir root: File) {
        val path = jar(
            File(root, "build/libs/path.jar"), 0L,
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nClass-Path: https://example.com/x.jar missing.jar\n",
        )

        val digests = files(ClasspathFiles.walk(listOf(path), listOf(), root))

        assertEquals(setOf("build/libs/path.jar!/META-INF/MANIFEST.MF"), digests.keys)
        assertTrue(ClasspathFiles.UNREADABLE !in digests.values)
    }

    @Test
    fun `a jar in Gradle's module cache a manifest names gives no rows`(@TempDir dir: File) {
        val root = File(dir, "project").apply { mkdirs() }
        val cached = jar(File(dir, "home/caches/modules-2/files-2.1/g/a/1/0a/a.jar"), 0L, "a.txt" to "a")
        val path = jar(
            File(root, "build/libs/path.jar"), 0L,
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nClass-Path: ${cached.toURI()}\n",
        )

        assertEquals(
            setOf("build/libs/path.jar!/META-INF/MANIFEST.MF"),
            files(ClasspathFiles.walk(listOf(path), listOf(), root)).keys,
        )
    }

    @Test
    fun `a changed, an appeared and a gone file all count as moved`() {
        val moved = ClasspathFiles.compare(
            recorded = mapOf("a" to "1", "b" to "2", "c" to "3"),
            current = mapOf("a" to "1", "b" to "changed", "d" to "4"),
        )

        assertEquals(mapOf("b" to "changed", "c" to "gone", "d" to "appeared"), moved.files)
    }

    @Test
    fun `a file unreadable at either end counts as moved, even unreadable at both`() {
        val unreadable = ClasspathFiles.UNREADABLE

        val moved = ClasspathFiles.compare(
            recorded = mapOf("now" to "1", "then" to unreadable, "both" to unreadable),
            current = mapOf("now" to unreadable, "then" to "1", "both" to unreadable),
        )

        assertEquals(setOf("now", "then", "both"), moved.files.keys)
    }

    @Test
    fun `nothing moved when every digest matches`() {
        assertTrue(ClasspathFiles.compare(mapOf("a" to "1"), mapOf("a" to "1")).isEmpty)
    }

    @Test
    fun `changed non-class files beneath the root are handed to the selector`(@TempDir root: File) {
        file(root, "app/info.properties", "info=b\n")
        val moved = ClasspathFiles.compare(
            recorded = mapOf("app/info.properties" to "1", "lib.jar!/META-INF/MANIFEST.MF" to "1", "lib.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}" to "e"),
            current = mapOf("app/info.properties" to "2", "lib.jar!/META-INF/MANIFEST.MF" to "2", "lib.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}" to "e"),
        )

        assertEquals(listOf("app/info.properties", "lib.jar!/META-INF/MANIFEST.MF"), moved.traceable(root))
    }

    @Test
    fun `any move whose readers are not recorded is not handed over`(@TempDir dir: File) {
        val root = File(dir, "project").apply { mkdirs() }
        // Each file exists, so a case is stopped by its own rule: a file that cannot be read counts
        // as an archive and would be stopped by that rule instead.
        file(dir, "elsewhere/a.properties", "a")
        val cases = mapOf(
            "appeared" to (emptyMap<String, String>() to mapOf("a.properties" to "1")),
            "gone" to (mapOf("a.properties" to "1") to emptyMap()),
            "unreadable" to (mapOf("a.properties" to "1") to mapOf("a.properties" to ClasspathFiles.UNREADABLE)),
            "a class file" to (mapOf("x.jar!/A.class" to "1") to mapOf("x.jar!/A.class" to "2")),
            "outside the root" to (mapOf("../elsewhere/a.properties" to "1") to mapOf("../elsewhere/a.properties" to "2")),
            "loaded attributes" to (
                mapOf("x.jar!/META-INF/MANIFEST.MF" to "1", "x.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}" to "e") to
                    mapOf("x.jar!/META-INF/MANIFEST.MF" to "2", "x.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}" to "f")
                ),
        )
        for ((case, tables) in cases) {
            assertEquals(null, ClasspathFiles.compare(tables.first, tables.second).traceable(root), case)
        }
    }

    @Test
    fun `a changed file with a comma in its name is handed over`(@TempDir root: File) {
        // The list reaches the selector escaped, so the comma no longer splits it.
        assertEquals(listOf("a,b.properties"), changed(root, "a,b.properties", "a"))
    }

    /** One file of [path] that changed, written beneath [root] with [content]. */
    private fun changed(root: File, path: String, content: String): List<String>? {
        file(root, path, content)
        return ClasspathFiles.compare(mapOf(path to "1"), mapOf(path to "2")).traceable(root)
    }

    @Test
    fun `a moved source file is not handed over, its reads are recorded as a class's`(@TempDir root: File) {
        for (path in listOf("res/config.groovy", "res/A.java", "res/a.kt", "res/a.scala")) {
            assertEquals(null, changed(root, path, "plain text"), path)
        }
        assertEquals(listOf("res/config.properties"), changed(root, "res/config.properties", "plain text"))
    }

    @Test
    fun `an archive inside a directory is not handed over, by its name or by its content`(@TempDir root: File) {
        assertEquals(null, changed(root, "res/fixture.jar", "plain text"), "an archive by its name")
        assertEquals(null, changed(root, "res/fixture.bin", "PK\u0003\u0004 an archive by its content"),
            "an archive by its content")
        assertEquals(listOf("res/fixture.txt"), changed(root, "res/fixture.txt", "plain text"))
    }

    @Test
    fun `a zip behind a launch script is not handed over, a plain text file is`(@TempDir root: File) {
        File(root, "res/launcher").apply { parentFile.mkdirs() }.outputStream().use { out ->
            out.write("#!/bin/sh\n# runs the archive appended to this script\n".toByteArray())
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("greeting.txt"))
                zip.write("clean\n".toByteArray())
                zip.closeEntry()
            }
        }
        file(root, "res/notes.txt", "a plain text file, longer than a zip's end record")

        val launcher = ClasspathFiles.compare(mapOf("res/launcher" to "1"), mapOf("res/launcher" to "2"))
        val notes = ClasspathFiles.compare(mapOf("res/notes.txt" to "1"), mapOf("res/notes.txt" to "2"))

        assertEquals(null, launcher.traceable(root))
        assertEquals(listOf("res/notes.txt"), notes.traceable(root))
    }

    @Test
    fun `a manifest's loaded attributes are named on the manifest`() {
        val moved = ClasspathFiles.compare(
            recorded = mapOf("x.jar!/META-INF/MANIFEST.MF" to "1"),
            current = mapOf("x.jar!/META-INF/MANIFEST.MF" to "1", "x.jar!/META-INF/MANIFEST.MF${ClasspathFiles.EXPOSED_SUFFIX}" to "e"),
        )

        assertEquals("x.jar!/META-INF/MANIFEST.MF (its loaded attributes appeared)", moved.named())
    }

    @Test
    fun `a read is keyed as the walk keys the file it read`(@TempDir dir: File) {
        val root = File(dir, "root").apply { mkdirs() }
        val link = java.nio.file.Files.createSymbolicLink(File(dir, "link").toPath(), root.toPath()).toFile()
        File(root, "app/info.properties").apply { parentFile.mkdirs(); writeText("a") }

        assertEquals("app/info.properties", ClasspathFiles.keyOf(root, File(root, "app/info.properties").absolutePath))
        assertEquals("app/info.properties", ClasspathFiles.keyOf(root, File(link, "app/info.properties").absolutePath))
        assertEquals("lib.jar!/x.properties", ClasspathFiles.keyOf(root, File(root, "lib.jar").absolutePath + "!/x.properties"))
        assertEquals(null, ClasspathFiles.keyOf(root, File(dir, "elsewhere.txt").absolutePath))
    }

    @Test
    fun `the resolved name a read is also recorded under keys as the walk keys the file`(@TempDir root: File) {
        val file = File(root, "app/info.properties").apply { parentFile.mkdirs(); writeText("a") }
        val link = java.nio.file.Files.createSymbolicLink(File(root, "alias").toPath(), File(root, "app").toPath())
        val resolved = file.toPath().toRealPath().toString()
        java.nio.file.Files.delete(link)

        assertEquals("app/info.properties", ClasspathFiles.keyOf(root, resolved))
        // The path as given no longer leads to the file once the link is gone.
        assertNotEquals("app/info.properties", ClasspathFiles.keyOf(root, File(link.toFile(), "info.properties").absolutePath))
    }

    @Test
    fun `seven moved files name five and count the rest`() {
        val moved = ClasspathFiles.compare(emptyMap(), (1..7).associate { "f$it" to "$it" })

        assertEquals(
            "f1 (appeared), f2 (appeared), f3 (appeared), f4 (appeared), f5 (appeared) and 2 more",
            moved.named(),
        )
    }

    private fun git(dir: File, vararg args: String) {
        val exit = ProcessBuilder(listOf("git", "-c", "user.email=t@example.com", "-c", "user.name=t") + args)
            .directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")}")
    }

    /**
     * A repository at [root] with `src/test/resources/a.txt` committed with `\n` and [attributes] as
     * `.gitattributes` when given, then checked out again under `core.autocrlf` as [autocrlf].
     */
    private fun repository(root: File, autocrlf: String = "true", attributes: String? = null): File {
        git(root, "init", "-q")
        git(root, "config", "core.autocrlf", "false")
        file(root, "src/test/resources/a.txt", "one\ntwo\n")
        attributes?.let { file(root, ".gitattributes", it) }
        git(root, "add", ".")
        git(root, "commit", "-q", "-m", "base")
        git(root, "config", "core.autocrlf", autocrlf)
        File(root, "src/test/resources/a.txt").delete()
        git(root, "checkout", "--", "src/test/resources/a.txt")
        return root
    }

    /** The digest the walk records for a copy of a.txt under build/resources/test with [content]. */
    private fun copyDigest(root: File, content: String, name: String = "a.txt"): String? {
        val resources = File(root, "build/resources/test")
        resources.deleteRecursively()
        file(resources, name, content)
        return found(ClasspathFiles.walk(listOf(resources), listOf(), root))["build/resources/test/$name"]
    }

    @Test
    fun `a copy of a tracked text file digests the same whatever its line endings`(@TempDir root: File) {
        repository(root)

        assertEquals(copyDigest(root, "one\ntwo\n"), copyDigest(root, "one\r\ntwo\r\n"))
    }

    @Test
    fun `the same holds for the copy inside an own jar`(@TempDir root: File) {
        repository(root)
        fun jarDigest(content: String) = found(
            ClasspathFiles.walk(listOf(jar(File(root, "build/libs/x.jar"), 0L, "a.txt" to content)), listOf(), root)
        )["build/libs/x.jar!/a.txt"]

        assertEquals(jarDigest("one\ntwo\n"), jarDigest("one\r\ntwo\r\n"))
    }

    @Test
    fun `a file without a carriage return digests by its bytes, as before`(@TempDir root: File) {
        repository(root)
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest("one\ntwo\n".toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertEquals(bytes, copyDigest(root, "one\ntwo\n"))
    }

    @Test
    fun `a pinned, unset or binary path keeps its bytes digest`(@TempDir dir: File) {
        for (attributes in listOf("*.txt eol=lf\n", "*.txt -text\n", "*.txt binary\n")) {
            val root = File(dir, attributes.filter(Char::isLetter)).apply { mkdirs() }
            repository(root, attributes = attributes)

            assertNotEquals(copyDigest(root, "one\ntwo\n"), copyDigest(root, "one\r\ntwo\r\n"), attributes)
        }
    }

    @Test
    fun `a file no tracked blob matches, or matched at another path, keeps its bytes digest`(@TempDir root: File) {
        repository(root)

        // Generated: its content is no tracked file's.
        assertNotEquals(copyDigest(root, "gen\n"), copyDigest(root, "gen\r\n"))
        // a.txt's content under another name is not a copy of a.txt.
        assertNotEquals(copyDigest(root, "one\ntwo\n", "b.txt"), copyDigest(root, "one\r\ntwo\r\n", "b.txt"))
    }

    @Test
    fun `a file committed with carriage returns keeps its bytes digest`(@TempDir root: File) {
        git(root, "init", "-q")
        git(root, "config", "core.autocrlf", "false")
        file(root, "src/test/resources/a.txt", "one\r\ntwo\r\n")
        git(root, "add", ".")
        git(root, "commit", "-q", "-m", "base")
        git(root, "config", "core.autocrlf", "true")

        assertNotEquals(copyDigest(root, "one\ntwo\n"), copyDigest(root, "one\r\ntwo\r\n"))
    }

    @Test
    fun `a copy with carriage returns git did not write keeps its bytes digest`(@TempDir root: File) {
        // Checked out with '\n': the '\r\n' in the copy came from the build, not from git.
        repository(root, autocrlf = "false")

        assertNotEquals(copyDigest(root, "one\ntwo\n"), copyDigest(root, "one\r\ntwo\r\n"))
    }

    @Test
    fun `the working tree's file decides, whatever setting put its carriage returns there`(@TempDir root: File) {
        // `* text eol=crlf`, with core.autocrlf off: git still writes '\r\n'.
        repository(root, autocrlf = "false", attributes = "*.txt text eol=crlf\n")

        assertEquals(copyDigest(root, "one\ntwo\n"), copyDigest(root, "one\r\ntwo\r\n"))
    }

    @Test
    fun `outside a git work tree, or when git fails, every file keeps its bytes digest`(@TempDir root: File) {
        assertNotEquals(copyDigest(root, "one\ntwo\n"), copyDigest(root, "one\r\ntwo\r\n"))

        repository(root)
        val resources = File(root, "build/resources/test").apply { deleteRecursively() }
        file(resources, "a.txt", "one\r\ntwo\r\n")
        val failing = found(ClasspathFiles.walk(listOf(resources), listOf(), root, text = TrackedText(root) { null }))
        assertNotEquals(copyDigest(root, "one\ntwo\n"), failing["build/resources/test/a.txt"])
    }
}
