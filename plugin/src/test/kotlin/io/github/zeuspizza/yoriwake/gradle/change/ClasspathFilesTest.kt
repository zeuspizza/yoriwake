package io.github.zeuspizza.yoriwake.gradle.change

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
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
    fun `any move whose readers are not recorded is not handed over`(@TempDir root: File) {
        file(root, "res/fixture.bin", "PK\u0003\u0004 an archive by its content")
        val cases = mapOf(
            "appeared" to (emptyMap<String, String>() to mapOf("a.properties" to "1")),
            "gone" to (mapOf("a.properties" to "1") to emptyMap()),
            "unreadable" to (mapOf("a.properties" to "1") to mapOf("a.properties" to ClasspathFiles.UNREADABLE)),
            "a class file" to (mapOf("x.jar!/A.class" to "1") to mapOf("x.jar!/A.class" to "2")),
            "outside the root" to (mapOf("../elsewhere/a.properties" to "1") to mapOf("../elsewhere/a.properties" to "2")),
            "a comma" to (mapOf("a,b.properties" to "1") to mapOf("a,b.properties" to "2")),
            "a source file" to (mapOf("res/config.groovy" to "1") to mapOf("res/config.groovy" to "2")),
            "an archive by its name" to (mapOf("res/fixture.jar" to "1") to mapOf("res/fixture.jar" to "2")),
            "an archive by its content" to (mapOf("res/fixture.bin" to "1") to mapOf("res/fixture.bin" to "2")),
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
    fun `seven moved files name five and count the rest`() {
        val moved = ClasspathFiles.compare(emptyMap(), (1..7).associate { "f$it" to "$it" })

        assertEquals(
            "f1 (appeared), f2 (appeared), f3 (appeared), f4 (appeared), f5 (appeared) and 2 more",
            moved.named(),
        )
    }
}
