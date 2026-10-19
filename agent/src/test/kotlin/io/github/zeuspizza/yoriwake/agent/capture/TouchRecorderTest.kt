package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Paths
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the read hooks' sink keeps: first touches of class and source files, nothing else. */
class TouchRecorderTest {

    private fun drained(recorder: TouchRecorder) = recorder.drain().map { it[0] to it[1] }

    @Test
    fun `a class or source file read is kept once, and any other file is not`() {
        val recorder = TouchRecorder()
        recorder.read(File("build/classes/java/main/com/acme/Codec.class"))
        recorder.read(Paths.get("src/main/kotlin/com/acme/Utils.kt"))
        recorder.read(File("build/classes/java/main/com/acme/Codec.class"))
        recorder.read(File("src/test/resources/registry.yaml"))

        assertEquals(
            listOf(
                AgentContract.TOUCH_READ to File("build/classes/java/main/com/acme/Codec.class").path,
                AgentContract.TOUCH_READ to Paths.get("src/main/kotlin/com/acme/Utils.kt").toString(),
            ),
            drained(recorder),
        )
        assertEquals(emptyList(), drained(recorder), "a drain empties")
    }

    @Test
    fun `a file read beneath the root is kept once by its absolute path, and one elsewhere is not`(
        @TempDir root: File,
        @TempDir elsewhere: File,
    ) {
        val stamp = File(root, "build/resources/test/info.properties").apply { parentFile.mkdirs(); writeText("a") }
        val outside = File(elsewhere, "scratch.txt").apply { writeText("b") }
        val recorder = TouchRecorder(root.path)
        recorder.read(stamp)
        recorder.read(Paths.get(stamp.path))
        recorder.read(outside)
        recorder.read(File(root, "build/classes/java/main/com/acme/Codec.class"))

        assertEquals(
            listOf(
                AgentContract.TOUCH_RESOURCE to stamp.absolutePath,
                AgentContract.TOUCH_READ to File(root, "build/classes/java/main/com/acme/Codec.class").path,
            ),
            drained(recorder),
        )
    }

    @Test
    fun `a file read through a link to the root is kept`(@TempDir dir: File) {
        val root = File(dir, "root").apply { mkdirs() }
        val link = java.nio.file.Files.createSymbolicLink(File(dir, "link").toPath(), root.toPath()).toFile()
        File(root, "info.properties").writeText("a")
        val recorder = TouchRecorder(root.path)
        recorder.read(File(link, "info.properties"))

        assertEquals(listOf(AgentContract.TOUCH_RESOURCE to File(link, "info.properties").absolutePath), drained(recorder))
    }

    /** Whether [dir]'s file system finds a name in another letter case, as macOS and Windows do. */
    private fun caseInsensitive(dir: File): Boolean {
        val probe = File(dir, "case-probe").apply { writeText("") }
        return File(dir, "CASE-PROBE").exists().also { probe.delete() }
    }

    @Test
    fun `a file read in another letter case below the root is kept under its case on disk too`(@TempDir root: File) {
        assumeTrue(caseInsensitive(root), "the file system tells letter cases apart")
        val file = File(root, "app/info.properties").apply { parentFile.mkdirs(); writeText("a") }
        val recorder = TouchRecorder(root.path)
        recorder.read(File(root, "APP/info.properties"))

        assertEquals(
            listOf(
                AgentContract.TOUCH_RESOURCE to File(root, "APP/info.properties").absolutePath,
                AgentContract.TOUCH_RESOURCE to file.toPath().toRealPath().toString(),
            ),
            drained(recorder),
        )
    }

    @Test
    fun `a file read through a link inside the root is kept under the file it reached, after the link is gone`(
        @TempDir root: File,
    ) {
        val file = File(root, "app/info.properties").apply { parentFile.mkdirs(); writeText("a") }
        val link = java.nio.file.Files.createSymbolicLink(File(root, "alias").toPath(), File(root, "app").toPath())
        val recorder = TouchRecorder(root.path)
        recorder.read(File(link.toFile(), "info.properties"))
        java.nio.file.Files.delete(link)

        assertEquals(
            listOf(
                AgentContract.TOUCH_RESOURCE to File(link.toFile(), "info.properties").absolutePath,
                AgentContract.TOUCH_RESOURCE to file.toPath().toRealPath().toString(),
            ),
            drained(recorder),
        )
    }

    @Test
    fun `a jar entry read through another letter case of the jar's path is kept under its case on disk too`(
        @TempDir root: File,
    ) {
        assumeTrue(caseInsensitive(root), "the file system tells letter cases apart")
        val jar = File(root, "lib.jar")
        ZipOutputStream(jar.outputStream()).use { it.putNextEntry(ZipEntry("x.properties")); it.closeEntry() }
        val recorder = TouchRecorder(root.path)
        ZipFile(File(root, "LIB.jar")).use { zip -> recorder.read(arrayOf<Any>(zip, zip.getEntry("x.properties"))) }

        assertEquals(
            listOf(
                AgentContract.TOUCH_RESOURCE to "${File(root, "LIB.jar").absolutePath}!/x.properties",
                AgentContract.TOUCH_RESOURCE to "${jar.toPath().toRealPath()}!/x.properties",
            ),
            drained(recorder),
        )
    }

    /** A same-file check that takes [alias] for the root, standing in for a bind mount or a drive alias. */
    private fun aliasOf(root: File, alias: File) = TouchRecorder.SameFile { a, b ->
        (a == alias.toPath().toRealPath() && b == java.nio.file.Paths.get(root.absolutePath + File.separator)) ||
            java.nio.file.Files.isSameFile(a, b)
    }

    @Test
    fun `a file read through another name for the root is kept beneath the root`(
        @TempDir root: File,
        @TempDir mount: File,
    ) {
        File(mount, "build/x.properties").apply { parentFile.mkdirs(); writeText("a") }
        val recorder = TouchRecorder(root.path, aliasOf(root, mount))
        recorder.read(File(mount, "build/x.properties"))

        assertEquals(
            listOf(AgentContract.TOUCH_RESOURCE to File(root, "build/x.properties").absolutePath),
            drained(recorder),
        )
    }

    @Test
    fun `a file read through a link outside the root into another name for the root is kept beneath the root`(
        @TempDir root: File,
        @TempDir mount: File,
        @TempDir elsewhere: File,
    ) {
        val file = File(mount, "build/x.properties").apply { parentFile.mkdirs(); writeText("a") }
        val link = java.nio.file.Files.createSymbolicLink(File(elsewhere, "x.properties").toPath(), file.toPath())
        val recorder = TouchRecorder(root.path, aliasOf(root, mount))
        recorder.read(link.toFile())

        assertEquals(
            listOf(AgentContract.TOUCH_RESOURCE to File(root, "build/x.properties").absolutePath),
            drained(recorder),
        )
    }

    @Test
    fun `a link inside the root pointed elsewhere between two reads is followed both times`(@TempDir root: File) {
        File(root, "a/info.properties").apply { parentFile.mkdirs(); writeText("a") }
        File(root, "b/info.properties").apply { parentFile.mkdirs(); writeText("b") }
        val link = File(root, "alias").toPath()
        val recorder = TouchRecorder(root.path)
        java.nio.file.Files.createSymbolicLink(link, File(root, "a").toPath())
        recorder.read(File(link.toFile(), "info.properties"))
        java.nio.file.Files.delete(link)
        java.nio.file.Files.createSymbolicLink(link, File(root, "b").toPath())
        recorder.read(File(link.toFile(), "info.properties"))

        assertTrue(File(root, "b/info.properties").toPath().toRealPath().toString() in drained(recorder).map { it.second })
    }

    @Test
    fun `a jar entry read through another name for the root is kept beneath the root`(
        @TempDir root: File,
        @TempDir mount: File,
    ) {
        val jar = File(mount, "lib.jar")
        ZipOutputStream(jar.outputStream()).use { it.putNextEntry(ZipEntry("x.properties")); it.closeEntry() }
        val recorder = TouchRecorder(root.path, aliasOf(root, mount))
        ZipFile(jar).use { zip -> recorder.read(arrayOf<Any>(zip, zip.getEntry("x.properties"))) }

        assertEquals(
            listOf(AgentContract.TOUCH_RESOURCE to "${File(root, "lib.jar").absolutePath}!/x.properties"),
            drained(recorder),
        )
    }

    @Test
    fun `a read whose place cannot be told is kept as given, which counts as a read of every file`(
        @TempDir root: File,
        @TempDir elsewhere: File,
    ) {
        val file = File(elsewhere, "x.properties").apply { writeText("a") }
        val recorder = TouchRecorder(root.path) { _, _ -> throw java.io.IOException("cannot tell") }
        recorder.read(file)

        assertEquals(listOf(AgentContract.TOUCH_RESOURCE to file.absolutePath), drained(recorder))
    }

    @Test
    fun `a read of a file that is not there, outside the root, is not kept`(@TempDir root: File, @TempDir elsewhere: File) {
        val recorder = TouchRecorder(root.path) { _, _ -> throw java.io.IOException("cannot tell") }
        recorder.read(File(elsewhere, "missing.properties"))

        assertEquals(emptyList(), drained(recorder))
    }

    private val windows = System.getProperty("os.name").startsWith("Windows")

    @Test
    fun `a file read through a short name below the root is kept under its long name too`(@TempDir root: File) {
        assumeTrue(windows, "short names are Windows'")
        val file = File(root, "longdirectoryname/info.properties").apply { parentFile.mkdirs(); writeText("a") }
        val short = ProcessBuilder("cmd", "/c", "for %I in (\"${file.parentFile.absolutePath}\") do @echo %~sI")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        assumeTrue(short.isNotEmpty() && !short.endsWith("longdirectoryname", ignoreCase = true),
            "this volume keeps no short names: $short")
        val recorder = TouchRecorder(root.path)
        recorder.read(File(short, "info.properties"))

        assertTrue(file.toPath().toRealPath().toString() in drained(recorder).map { it.second })
    }

    @Test
    fun `a file read through a drive letter given to a directory above the root is kept`(@TempDir dir: File) {
        assumeTrue(windows, "drive letters are Windows'")
        val root = File(dir, "root").apply { mkdirs() }
        File(root, "info.properties").writeText("a")
        val letter = ('P'..'Z').firstOrNull { !File("$it:\\").exists() }
        assumeTrue(letter != null, "no free drive letter")
        assertEquals(0, ProcessBuilder("subst", "$letter:", dir.absolutePath).start().waitFor())
        try {
            val recorder = TouchRecorder(root.path)
            recorder.read(File("$letter:\\root\\info.properties"))

            // Kept beneath the root, or as given when the drive resolves to it: never widened.
            val given = File("$letter:\\root\\info.properties").absolutePath
            assertTrue(drained(recorder).map { it.second } in setOf(listOf(given), listOf(File(root, "info.properties").absolutePath)))
        } finally {
            ProcessBuilder("subst", "$letter:", "/d").start().waitFor()
        }
    }

    @Test
    fun `a jar entry read beneath the root is kept by the jar's path and the entry's name`(@TempDir root: File) {
        val jar = File(root, "lib/build/libs/lib.jar").apply { parentFile.mkdirs() }
        ZipOutputStream(jar.outputStream()).use {
            it.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); it.closeEntry()
            it.putNextEntry(ZipEntry("build-info.properties")); it.closeEntry()
        }
        val recorder = TouchRecorder(root.path)
        ZipFile(jar).use { zip ->
            recorder.read(arrayOf<Any>(zip, zip.getEntry("build-info.properties")))
            // Read by a test, not by the JDK's class loader.
            recorder.read(arrayOf<Any>(zip, zip.getEntry("META-INF/MANIFEST.MF")))
        }

        assertEquals(
            listOf(
                AgentContract.TOUCH_RESOURCE to "${jar.absolutePath}!/build-info.properties",
                AgentContract.TOUCH_RESOURCE to "${jar.absolutePath}!/META-INF/MANIFEST.MF",
            ),
            drained(recorder),
        )
    }

    @Test
    fun `a jar read whole beneath the root is a jar touch and a read of the jar`(@TempDir root: File) {
        val jar = File(root, "lib.jar")
        ZipOutputStream(jar.outputStream()).use { it.putNextEntry(ZipEntry("x.properties")); it.closeEntry() }
        val recorder = TouchRecorder(root.path)
        recorder.read(jar)

        assertEquals(
            listOf(AgentContract.TOUCH_JAR to jar.absolutePath, AgentContract.TOUCH_RESOURCE to jar.absolutePath),
            drained(recorder),
        )
    }

    @Test
    fun `a manifest read from project code is not the class loader's`() {
        assertFalse(TouchRecorder.loaderReadingManifest())
    }

    @Test
    fun `without a root no file but a class or source file is kept`(@TempDir root: File) {
        val recorder = TouchRecorder()
        recorder.read(File(root, "info.properties").apply { writeText("a") })

        assertEquals(emptyList(), drained(recorder))
    }

    @Test
    fun `a jar entry read is kept by its entry name`(@TempDir dir: File) {
        val jar = File(dir, "lib.jar")
        ZipOutputStream(jar.outputStream()).use { it.putNextEntry(ZipEntry("com/acme/A.class")); it.closeEntry() }
        val recorder = TouchRecorder()
        ZipFile(jar).use { zip -> recorder.read(arrayOf<Any>(zip, zip.getEntry("com/acme/A.class"))) }

        assertEquals(listOf(AgentContract.TOUCH_READ to "com/acme/A.class"), drained(recorder))
    }

    @Test
    fun `a class file read by project code running inside a class definition is a read`(@TempDir dir: File) {
        File(dir, "com/acme/Other.class").apply { parentFile.mkdirs() }.writeBytes(classBytes("com/acme/Other"))
        val recorder = TouchRecorder()
        var ran = false
        standin.PermissionsReader(dir) {
            recorder.read(File("build/classes/java/main/com/acme/Codec.class"))
            ran = true
        }.use { it.loadClass("com.acme.Other") }

        assertTrue(ran, "the loader's getPermissions ran while it defined the class")
        assertEquals(listOf(AgentContract.TOUCH_READ to File("build/classes/java/main/com/acme/Codec.class").path), drained(recorder))
    }

    /**
     * A loader that reads the class file it is defining from its `getPermissions`. Declared in the
     * agent's own package, so its frame is passed over with the agent's and only the JDK's frames
     * stand between the read and the definition, as when the JDK reads a class file to define it.
     */
    private class JdkOnlyReader(private val dir: File, private val recorder: TouchRecorder) :
        java.net.URLClassLoader(arrayOf(dir.toURI().toURL()), null) {
        var ran = false

        override fun getPermissions(codesource: java.security.CodeSource?): java.security.PermissionCollection {
            recorder.read(File(dir, "com/acme/Other.class"))
            ran = true
            return super.getPermissions(codesource)
        }
    }

    @Test
    fun `a class file read with only the JDK's frames between it and the definition is not a read`(@TempDir dir: File) {
        File(dir, "com/acme/Other.class").apply { parentFile.mkdirs() }.writeBytes(classBytes("com/acme/Other"))
        val recorder = TouchRecorder()
        val loader = JdkOnlyReader(dir, recorder)
        loader.use { it.loadClass("com.acme.Other") }

        assertTrue(loader.ran, "the loader's getPermissions ran while it defined the class")
        assertEquals(emptyList(), drained(recorder))
    }

    @Test
    fun `a jar opened by something other than the JDK's zip code counts as every class in it`(@TempDir dir: File) {
        val recorder = TouchRecorder()
        recorder.read(File(dir, "scanned.jar"))

        assertEquals(listOf(AgentContract.TOUCH_JAR to File(dir, "scanned.jar").absolutePath), drained(recorder))
    }

    private fun zipOf(file: File, vararg entries: Pair<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return file
    }

    @Test
    fun `an archive of any name, or none, opened by something other than the JDK's zip code counts by its content`(
        @TempDir dir: File,
    ) {
        val recorder = TouchRecorder()
        val archives = listOf("app.war", "app.ear", "lib.aar", "classes.zip", "bundle")
            .map { zipOf(File(dir, it), "com/acme/A.class" to ByteArray(1)) }
        // A launch script ahead of the archive, as a fully executable jar carries.
        val scripted = File(dir, "app.run").apply {
            writeBytes("#!/bin/sh\nexit 0\n".toByteArray() + zipOf(File(dir, "plain"), "com/acme/B.class" to ByteArray(1)).readBytes())
        }
        val text = File(dir, "notes.bin").apply { writeText("PK is not enough on its own, a zip needs its signature") }
        // A zip of data names no class, so a temporary one gone before the records are read cannot
        // count as every class.
        val sheet = zipOf(File(dir, "report.xlsx"), "xl/workbook.xml" to "<workbook/>".toByteArray())
        val nested = zipOf(File(dir, "dist.tar"), "lib/inner.jar" to zipOf(File(dir, "inner"), "a.txt" to ByteArray(1)).readBytes())
        (archives + scripted + text + sheet).forEach(recorder::read)
        recorder.read(nested)

        assertEquals(
            (archives + scripted + nested).map { AgentContract.TOUCH_JAR to it.absolutePath },
            drained(recorder),
        )
    }

    @Test
    fun `an archive entry that is an archive itself counts as every class in the outer archive`(@TempDir dir: File) {
        val inner = zipOf(File(dir, "inner"), "com/acme/A.class" to ByteArray(1)).readBytes()
        val outer = zipOf(File(dir, "outer.war"), "WEB-INF/lib/inner.jar" to inner, "index.html" to "<p>".toByteArray())
        val recorder = TouchRecorder()
        ZipFile(outer).use { zip ->
            recorder.read(arrayOf<Any>(zip, zip.getEntry("index.html")))
            recorder.read(arrayOf<Any>(zip, zip.getEntry("WEB-INF/lib/inner.jar")))
        }

        assertEquals(listOf(AgentContract.TOUCH_JAR to outer.absolutePath), drained(recorder))
    }

    @Test
    fun `a recorder the agent never installed says it observed nothing`() {
        assertNotNull(TouchRecorder().incomplete())
    }

    @Test
    fun `a recorder the agent never installed reports no environment reads, and a read touches no class`() {
        val recorder = TouchRecorder()
        recorder.environmentRead("DB_MODE")
        recorder.environmentRead("DB_MODE")
        recorder.environmentRead(null)
        recorder.environmentRead(true)

        // Not installed, so its reads were not all observed: none is reported, never a partial list.
        assertEquals(null, recorder.environmentReads())
        assertTrue(drained(recorder).isEmpty(), "a read of the environment is no touch of a class")
    }

    @Test
    fun `a class definition is a first touch, and a redefinition of a loaded class is not`() {
        val recorder = TouchRecorder()
        val observer = TouchRecorder.LoadObserver(recorder)
        val loader = javaClass.classLoader
        observer.transform(loader, "com/acme/Generated\$\$Proxy", null, null, ByteArray(0))
        observer.transform(loader, "java/lang/String", String::class.java, null, ByteArray(0))

        assertEquals(listOf(AgentContract.TOUCH_LOADED to "com.acme.Generated\$\$Proxy"), drained(recorder))
    }

    @Test
    fun `a lookup is kept by name, and again after the plan starts even if discovery made it`() {
        val recorder = TouchRecorder()
        recorder.lookup("com.acme.ATest")
        recorder.lookup("com.acme.ATest")
        recorder.planStarted()
        recorder.lookup("com.acme.ATest")

        assertEquals(
            listOf(AgentContract.TOUCH_LOOKUP to "com.acme.ATest", AgentContract.TOUCH_LOOKUP to "com.acme.ATest"),
            drained(recorder),
        )
    }

    @Test
    fun `a call that can reach any class, such as the whole loaded set, counts as a lookup of every class`() {
        val recorder = TouchRecorder()
        recorder.lookup(Any())

        assertEquals(listOf(AgentContract.TOUCH_LOOKUP to AgentContract.FIRST_TOUCH_ANY), drained(recorder))
    }

    /** A class of that name, defined by a loader of its own, standing in for a library's loader. */
    private fun classNamed(name: String): Class<*> {
        val bytes = classBytes(name.replace('.', '/'))
        return object : ClassLoader(javaClass.classLoader) {
            val defined: Class<*> = defineClass(name, bytes, 0, bytes.size)
        }.defined
    }

    private val any = AgentContract.TOUCH_LOOKUP to AgentContract.FIRST_TOUCH_ANY

    @Test
    fun `a native library that is not on the reviewed list touches every class from its load`() {
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(TouchRecorderTest::class.java, "/opt/acme/lib/libacme.so"))
        recorder.library(arrayOf<Any>(TouchRecorderTest::class.java, "codec"))

        val why = "a native library that is not on the reviewed list was loaded: "
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to why + "libacme.so",
                AgentContract.TOUCH_ALL to why + System.mapLibraryName("codec")),
            drained(recorder),
        )
    }

    @Test
    fun `a library loaded by path is named by its file, with either of Windows' separators`() {
        // Windows' System.load takes '/' as readily as '\', so '/' may not be read as a bare name.
        val recorder = TouchRecorder()
        val back = "C:\\acme\\lib\\back.dll"
        recorder.library(arrayOf<Any>(TouchRecorderTest::class.java, "C:/acme/lib/forward.dll"))
        recorder.library(arrayOf<Any>(TouchRecorderTest::class.java, back))

        val why = "a native library that is not on the reviewed list was loaded: "
        // Off Windows a backslash is part of a name, so the whole name is the library's.
        val backFile = if (File.separatorChar == '\\') "back.dll" else System.mapLibraryName(back)
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to why + "forward.dll", AgentContract.TOUCH_ALL to why + backFile),
            drained(recorder),
        )
    }

    @Test
    fun `a backslash off Windows cannot make an unreviewed file read as a reviewed one`() {
        // On Linux and macOS this loads a file named `x\libzstd-...so`, which nobody reviewed;
        // cutting at the backslash would match the reviewed zstd-jni name and narrow.
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(classNamed("com.github.luben.zstd.util.Native"),
            "/tmp/x\\libzstd-jni-1.5.7-201234567890.so"))

        // On Windows the backslash is a separator and the file really is zstd-jni's.
        val expected = if (File.separatorChar == '\\') AgentContract.TOUCH_LOOKUP else AgentContract.TOUCH_ALL
        assertEquals(expected, drained(recorder).single().first)
    }

    @Test
    fun `a native library the JDK loads for itself is not recorded`() {
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(String::class.java, "/jdk/lib/libnio.so"))
        recorder.library(arrayOf<Any>(Class.forName("java.sql.Driver"), "net"))

        assertEquals(emptyList(), drained(recorder))
    }

    @Test
    fun `a native library a class on the boot class path loads touches every class`() {
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(standin.boot.BootCaller::class.java, "codec"))

        assertEquals(
            listOf(AgentContract.TOUCH_ALL to
                "a native library that is not on the reviewed list was loaded: " + System.mapLibraryName("codec")),
            drained(recorder),
        )
    }

    @Test
    fun `a native library a JDK module on the platform loader loads is not recorded`() {
        // jdk.security.auth's class that loads libjaas, which the platform loader defines.
        val jaas = Class.forName(
            if (System.getProperty("os.name").startsWith("Windows")) "com.sun.security.auth.module.NTSystem"
            else "com.sun.security.auth.module.UnixSystem"
        )
        assertSame(ClassLoader.getPlatformClassLoader(), jaas.classLoader)
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(jaas, "jaas"))

        assertEquals(emptyList(), drained(recorder))
    }

    @Test
    fun `a reviewed library loaded by its own class counts as a lookup of every class, extracted name and all`() {
        val recorder = TouchRecorder()
        val snappy = classNamed("org.xerial.snappy.SnappyLoader")
        recorder.library(arrayOf<Any>(snappy,
            "/tmp/snappy-1.1.10.8-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsnappyjava.dylib"))
        // zstd-jni loads from an anonymous class nested in its loader.
        recorder.library(arrayOf<Any>(classNamed("com.github.luben.zstd.util.Native\$2"),
            "/tmp/libzstd-jni-1.5.7-201234567890.so"))

        assertEquals(listOf(any), drained(recorder))
    }

    @Test
    fun `a reviewed library loaded by name is reviewed only under the names its review covered`() {
        // loadLibrary's name becomes the platform's file name. The review covered the Linux and
        // macOS builds, so on Windows the same call names a .dll nobody reviewed, and runs wide.
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(classNamed("net.jpountz.util.Native"), "lz4-java"))

        // The Windows name is checked on every platform, not only where it arises.
        kotlin.test.assertFalse(ReviewedLibraries.reviewed("net.jpountz.util.Native", "lz4-java.dll"))
        val file = System.mapLibraryName("lz4-java")
        val expected = if (file.endsWith(".dll")) {
            listOf(AgentContract.TOUCH_ALL to "a native library that is not on the reviewed list was loaded: $file")
        } else {
            listOf(any)
        }
        assertEquals(expected, drained(recorder))
    }

    @Test
    fun `a reviewed library's file loaded by any other class touches every class`() {
        val recorder = TouchRecorder()
        recorder.library(arrayOf<Any>(TouchRecorderTest::class.java,
            "/tmp/snappy-1.1.10.8-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsnappyjava.so"))

        assertEquals(AgentContract.TOUCH_ALL, drained(recorder).single().first)
    }

    @Test
    fun `JNA's dispatch library touches every class, whatever loads it`() {
        for (file in listOf("/tmp/jna123456.tmp", "/usr/lib/jni/libjnidispatch.so")) {
            val recorder = TouchRecorder()
            recorder.library(arrayOf<Any>(classNamed("com.sun.jna.Native"), file))

            assertEquals(AgentContract.TOUCH_ALL, drained(recorder).single().first, file)
        }
    }

    @Test
    fun `every reviewed entry matches the names its loader gives the file, and nothing else`() {
        val reviewed = mapOf(
            "io.netty.util.internal.NativeLibraryUtil" to listOf(
                "libnetty_transport_native_epoll_x86_64.so", "libnetty_transport_native_epoll_aarch_64123456789.so",
                "libnetty_transport_native_kqueue_aarch_648123456.dylib", "libnetty_transport_native_kqueue_x86_64.jnilib",
                "libnetty_transport_native_io_uring_x86_64998877.so",
            ),
            "org.xerial.snappy.SnappyLoader" to listOf(
                "snappy-1.1.10.8-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsnappyjava.so", "libsnappyjava.jnilib",
            ),
            "com.github.luben.zstd.util.Native\$1" to listOf("libzstd-jni-1.5.7-20.so"),
            "net.jpountz.util.Native" to listOf("liblz4-java-1727000000000.so", "liblz4-java.dylib"),
            "org.conscrypt.NativeLibraryUtil" to listOf(
                "libconscrypt_openjdk_jni-linux-x86_6417590000000000000.so", "libconscrypt_openjdk_jni.dylib",
            ),
            "org.rocksdb.NativeLibraryLoader" to listOf(
                "librocksdbjni-linux64.so", "librocksdbjni-osx-arm64.jnilib", "librocksdbjni8765432.so",
            ),
        )
        for ((loader, files) in reviewed) {
            for (file in files) {
                assertTrue(ReviewedLibraries.reviewed(loader, file), "$loader $file")
                assertFalse(ReviewedLibraries.reviewed("com.acme.Loader", file), file)
            }
        }
        for (file in listOf(
            // The in-tree io_uring transport lets any caller submit an open; not reviewed.
            "libnetty_transport_native_io_uring42_x86_64.so", "libnetty_transport_native_io_uring42123.so",
            // netty-tcnative's BoringSSL and OpenSSL builds share this name; not reviewed.
            "libnetty_tcnative_osx_aarch_64.jnilib", "libacme.so", "libshaded_netty_transport_native_epoll_x86_64.so",
        )) {
            assertFalse(ReviewedLibraries.reviewed("io.netty.util.internal.NativeLibraryUtil", file), file)
        }
        // A system library of that bare name need not be the build that was reviewed.
        assertFalse(ReviewedLibraries.reviewed("org.conscrypt.NativeLibraryUtil", "libconscrypt.so"))
        assertFalse(ReviewedLibraries.reviewed("org.sqlite.SQLiteJDBCLoader",
            "sqlite-3.53.4.0-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsqlitejdbc.so"))
    }

    /** The bytes of an empty class named [internalName]. */
    private fun classBytes(internalName: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(org.objectweb.asm.Opcodes.V11, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null,
            "java/lang/Object", null)
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a class another loader defines is a named touch, and one defined with no name is named by its bytes`() {
        val recorder = TouchRecorder()
        val observer = TouchRecorder.LoadObserver(recorder)
        val own = object : ClassLoader(javaClass.classLoader) {}
        recorder.planStarted()
        observer.transform(own, "com/acme/ATest", null, null, ByteArray(0))
        observer.transform(own, null, null, null, classBytes("com/acme/BTest"))
        observer.transform(ClassLoader.getSystemClassLoader(), null, null, null, classBytes("com/acme/C"))

        assertEquals(
            listOf(
                AgentContract.TOUCH_DEFINED to "com.acme.ATest",
                AgentContract.TOUCH_DEFINED to "com.acme.BTest",
                AgentContract.TOUCH_LOADED to "com.acme.C",
            ),
            drained(recorder),
        )
    }

    @Test
    fun `a class defined from bytes no name can be read from touches every class`() {
        val recorder = TouchRecorder()
        TouchRecorder.LoadObserver(recorder).transform(javaClass.classLoader, null, null, null, byteArrayOf(1, 2, 3))

        assertEquals(AgentContract.TOUCH_ALL, drained(recorder).single().first)
    }

    @Test
    fun `a hidden class defined from outside the JDK is kept by the name in its bytes, and the JDK's own are not`() {
        val recorder = TouchRecorder()
        // Called through the JDK's own stream code, which then stands where the JDK's hooked
        // method would, with more JDK code as its caller.
        java.util.stream.Stream.of<Any>(classBytes("com/acme/Generated")).forEach(recorder::defined)
        assertEquals(emptyList(), drained(recorder), "a JDK caller defined it")

        recorder.defined(classBytes("com/acme/ATest"))
        recorder.defined(byteArrayOf(1, 2, 3))

        assertEquals(
            listOf(AgentContract.TOUCH_DEFINED to "com.acme.ATest", AgentContract.TOUCH_ALL to
                "a class was defined from bytes whose name could not be read"),
            drained(recorder),
        )
    }

    @Test
    fun `a child process touches every class, once`() {
        val recorder = TouchRecorder()
        recorder.childProcess(null)
        recorder.childProcess(null)

        assertEquals(listOf(AgentContract.TOUCH_ALL to "a child process was started"), drained(recorder))
    }

    @Test
    fun `native code reached through the foreign-function API from outside the JDK touches every class`() {
        val recorder = TouchRecorder()
        java.util.stream.Stream.of<Any?>(null).forEach(recorder::nativeCode)
        assertEquals(emptyList(), drained(recorder), "a JDK caller linked it")

        recorder.nativeCode(null)

        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(recorder),
        )
    }

    @Test
    fun `a hooked method called through reflection or a method handle is judged by who called it`() {
        val native = TouchRecorder()
        val call = standin.Hooked::class.java.getMethod("call", Runnable::class.java)
        call.invoke(null, Runnable { native.nativeCode(null) })
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(native),
            "Method.invoke is not the caller",
        )

        val handled = TouchRecorder()
        java.lang.invoke.MethodHandles.lookup().unreflect(call)
            .invokeWithArguments(Runnable { handled.nativeCode(null) })
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(handled),
            "MethodHandle.invokeWithArguments is not the caller",
        )

        val referenced = TouchRecorder()
        standin.Hooked.referencedThroughTheJdk { referenced.nativeCode(null) }
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(referenced),
            "a method reference handed to JDK code belongs to the class that wrote it",
        )

        for ((route, through) in listOf<Pair<String, (Runnable) -> Unit>>(
            "invokeExact" to standin.Hooked.Caller::invokeExact,
            "a varargs handle" to standin.Hooked.Caller::varargs,
            "an interface proxy" to standin.Hooked.Caller::throughAnInterfaceProxy,
        )) {
            val recorder = TouchRecorder()
            through(Runnable { recorder.nativeCode(null) })
            assertEquals(
                listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
                drained(recorder),
                "$route is not the caller",
            )
        }

        val defined = TouchRecorder()
        call.invoke(null, Runnable { defined.defined(classBytes("com/acme/Generated")) })
        assertEquals(listOf(AgentContract.TOUCH_DEFINED to "com.acme.Generated"), drained(defined))
    }

    @Test
    fun `a hooked method reached through an interface proxy that JDK code calls is judged as the project's`() {
        val native = TouchRecorder()
        standin.Hooked.Caller.throughAnInterfaceProxyTheJdkCalls { native.nativeCode(null) }
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(native),
            "JDK code that calls a proxy did not choose what the proxy calls",
        )

        val defined = TouchRecorder()
        standin.Hooked.Caller.throughAnInterfaceProxyTheJdkCalls { defined.defined(classBytes("com/acme/Generated")) }
        assertEquals(listOf(AgentContract.TOUCH_DEFINED to "com.acme.Generated"), drained(defined))

        // Reflection frames with no proxy among them still leave the JDK code beyond them the caller.
        val constructed = TouchRecorder()
        var ran = false
        standin.Hooked.Caller.constructedByTheJdk {
            constructed.nativeCode(null)
            constructed.defined(classBytes("com/acme/Generated"))
            ran = true
        }
        assertTrue(ran, "the service loader constructed the provider")
        assertEquals(emptyList(), drained(constructed), "the JDK's service loader constructed it")
    }

    @Test
    fun `native code and hidden classes a class on the boot class path reaches are recorded`() {
        val native = TouchRecorder()
        standin.boot.BootCaller.call(Consumer { native.nativeCode(null) }, "value")
        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(native),
        )

        val defined = TouchRecorder()
        standin.boot.BootCaller.call(Consumer { defined.defined(it) }, classBytes("com/acme/Generated"))
        assertEquals(listOf(AgentContract.TOUCH_DEFINED to "com.acme.Generated"), drained(defined))
    }

    @Test
    fun `a class that only carries a proxy's name is judged as the caller`() {
        // JDK code calls the spoof, so passing the spoof over would judge that JDK code instead.
        val recorder = TouchRecorder()
        java.util.stream.Stream.of<Any>("value").forEach(com.sun.proxy.`Spoof$1`(Consumer { recorder.nativeCode(null) }))

        assertEquals(
            listOf(AgentContract.TOUCH_ALL to "native code was reached through the foreign-function API"),
            drained(recorder),
        )
    }
}
