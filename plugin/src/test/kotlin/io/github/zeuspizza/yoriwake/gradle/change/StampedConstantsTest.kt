package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.bytecode.DigestScan
import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StampedConstantsTest {

    /** Compiles [sources] (`name -> Java source`, default package) into [into], replacing what it held. */
    private fun javac(into: File, vararg sources: Pair<String, String>) {
        val compiler = requireNotNull(javax.tools.ToolProvider.getSystemJavaCompiler()) { "this test needs a JDK" }
        val src = java.nio.file.Files.createTempDirectory("yoriwake-stamped").toFile()
        try {
            val files = sources.map { (name, text) -> File(src, "$name.java").apply { writeText(text) }.path }
            into.deleteRecursively()
            into.mkdirs()
            check(compiler.run(null, null, java.io.ByteArrayOutputStream(), "-d", into.path, *files.toTypedArray()) == 0)
        } finally {
            src.deleteRecursively()
        }
    }

    private fun buildConfig(time: Long, name: String = "a", code: Int = 7) = "BuildConfig" to """
        public final class BuildConfig {
            public static final long BUILD_TIME = ${100_000 + time}L;
            public static final String NAME = "$name";
            public static final int CODE = $code;
        }
    """.trimIndent()

    private fun app(literal: String = "x") = "App" to """
        public class App {
            public long time() { return BuildConfig.BUILD_TIME; }
            public String label() { return "$literal"; }
        }
    """.trimIndent()

    private val coded = "Coded" to "public class Coded { public int code() { return BuildConfig.CODE; } }"

    /** What a capture of [classes] records: class digests, constant fields, masked digests. */
    private class Captured(val digests: Map<String, String>, val fields: Map<String, String>, val masked: Map<String, String>)

    private fun artifacts(root: File, classes: File) = TaskArtifacts(listOf(classes), listOf(File(root, "build").absolutePath))

    private fun capture(root: File, classes: File): Captured {
        val artifacts = artifacts(root, classes)
        val fields = assertNotNull(artifacts.constantFields())
        val mask = Recordability.ConstantMask(fields)
        return Captured(
            assertIs<DigestScan.Found>(artifacts.classDigests()).digests,
            fields.flatMap { (owner, declared) -> declared.map { "$owner#${it.name}:${it.descriptor}" to it.valueDigest } }.toMap(),
            assertIs<DigestScan.Found>(artifacts.classDigests { Recordability.maskedDigest(it, mask) }).digests,
        )
    }

    private fun added(then: Captured, root: File, classes: File): Set<String> {
        val now = assertIs<DigestScan.Found>(artifacts(root, classes).classDigests()).digests
        return (now.keys + then.digests.keys).filterTo(sortedSetOf()) { now[it] != then.digests[it] }
    }

    private fun apply(
        then: Captured,
        root: File,
        classes: File,
        declared: List<String>,
        changedPrefixes: List<String> = emptyList(),
        tracked: (String, String) -> Boolean = { _, _ -> true },
        recordedFields: Map<String, String>? = then.fields,
        recordedMasked: Map<String, String>? = then.masked,
    ) = StampedConstants.apply(
        StampedConstants.Rule(recordedFields, recordedMasked, declared, tracked),
        added(then, root, classes), changedPrefixes, artifacts(root, classes),
    )

    @Test
    fun `a masked digest leaves out constant values and their inlined copies, and nothing else`(@TempDir root: File) {
        val classes = File(root, "build/classes")
        fun digests(vararg sources: Pair<String, String>): Map<String, String?> {
            javac(classes, *sources)
            val fields = assertNotNull(artifacts(root, classes).constantFields())
            val mask = Recordability.ConstantMask(fields)
            return classes.listFiles()!!.associate { it.nameWithoutExtension to Recordability.maskedDigest(it.readBytes(), mask).value }
        }
        val first = digests(buildConfig(1), app(), coded)

        val stamped = digests(buildConfig(2, name = "b"), app(), coded)
        assertEquals(first["BuildConfig"], stamped["BuildConfig"])
        assertEquals(first["App"], stamped["App"])
        // An `int` the compiler pushes as a literal is the holder's only.
        val recoded = digests(buildConfig(1, code = 8), app(), coded)
        assertEquals(first["BuildConfig"], recoded["BuildConfig"])
        assertNotEquals(first["Coded"], recoded["Coded"])
        // Another literal of the consumer still counts.
        assertNotEquals(first["App"], digests(buildConfig(2), app("y"), coded)["App"])
        // A literal that equals a constant's value is masked only while it does.
        val equal = digests(buildConfig(1, name = "x"), app("x"), coded)
        val unequal = digests(buildConfig(1, name = "y"), app("x"), coded)
        assertNotEquals(equal["App"], unequal["App"])
        // A value two constants share names neither.
        val two = Recordability.ConstantMask(
            mapOf("A" to listOf(Recordability.ConstantField("X", "J", 100_001L)), "B" to listOf(Recordability.ConstantField("Y", "J", 100_001L))),
        )
        assertNull(two.fieldOf(100_001L))
    }

    @Test
    fun `a stamped constant is named, and a class whose only change is a declared value is left out`(@TempDir root: File) {
        val classes = File(root, "build/classes")
        javac(classes, buildConfig(1), app(), coded)
        val then = capture(root, classes)
        javac(classes, buildConfig(2), app(), coded)

        val named = assertNotNull(apply(then, root, classes, declared = emptyList()))
        assertEquals(mapOf("BuildConfig" to listOf("BUILD_TIME")), named.moved)
        assertEquals(1, named.consumers)
        assertEquals(setOf("App", "BuildConfig"), named.added)
        assertContains(StampedConstants.lines(named) { null }.single(), "BuildConfig BUILD_TIME; 1 other changed class(es)")

        val left = assertNotNull(apply(then, root, classes, declared = listOf("BuildConfig#BUILD_TIME", "BuildConfig#MISSING")))
        assertEquals(setOf("App", "BuildConfig"), left.masked)
        assertEquals(emptySet(), left.added)
        assertEquals(listOf("BuildConfig#MISSING"), left.invalid)
        val lines = StampedConstants.lines(left) { 3 }
        assertTrue(lines.any { "stampedConstants left out 2 changed class(es)" in it && "the 3 test(s)" in it }, lines.toString())
        assertTrue(lines.any { "BuildConfig#MISSING" in it }, lines.toString())
    }

    @Test
    fun `nothing is left out when an undeclared constant moved too, or a class cannot qualify`(@TempDir root: File) {
        val classes = File(root, "build/classes")
        javac(classes, buildConfig(1), app(), coded)
        val then = capture(root, classes)
        val declared = listOf("BuildConfig#BUILD_TIME")

        javac(classes, buildConfig(2, name = "b"), app(), coded)
        val undeclared = assertNotNull(apply(then, root, classes, declared))
        assertEquals(emptySet(), undeclared.masked)
        assertContains(assertNotNull(undeclared.unmasked), "BuildConfig#NAME")

        javac(classes, buildConfig(2), app(), coded)
        // Its source changed: its own coverage selects it, never the mask.
        assertEquals(setOf("BuildConfig"), assertNotNull(apply(then, root, classes, declared, changedPrefixes = listOf("App"))).masked)
        // Generated, it may hold a literal that moved for another reason; the holder still qualifies.
        assertEquals(setOf("BuildConfig"), assertNotNull(apply(then, root, classes, declared, tracked = { _, _ -> false })).masked)
        // A map captured without the declaration, or before constant fields were recorded, masks nothing.
        assertEquals(emptySet(), assertNotNull(apply(then, root, classes, declared, recordedMasked = null)).masked)
        val older = assertNotNull(apply(then, root, classes, declared, recordedFields = null))
        assertEquals(emptySet(), older.masked)
        assertEquals(mapOf("BuildConfig" to emptyList<String>()), older.moved)
        // A field missing from the map's table moved.
        val missing = then.fields.filterKeys { !it.startsWith("BuildConfig#NAME") }
        assertEquals(emptySet(), assertNotNull(apply(then, root, classes, declared, recordedFields = missing)).masked)
    }

    @Test
    fun `a constant elsewhere that copies a declared one must be declared too`(@TempDir root: File) {
        val classes = File(root, "build/classes")
        val copy = "Version" to "public class Version { public static final long BUILT = BuildConfig.BUILD_TIME; }"
        javac(classes, buildConfig(1), app(), copy)
        val then = capture(root, classes)
        javac(classes, buildConfig(2), app(), copy)

        val found = assertNotNull(apply(then, root, classes, listOf("BuildConfig#BUILD_TIME")))
        assertEquals(emptySet(), found.masked)
        assertContains(assertNotNull(found.unmasked), "Version#BUILT")
        // Declared too, both holders are left out; their shared value names neither field, so its copy in App stays.
        assertEquals(
            setOf("BuildConfig", "Version"),
            assertNotNull(apply(then, root, classes, listOf("BuildConfig#BUILD_TIME", "Version#BUILT"))).masked,
        )
    }

    @Test
    fun `nothing moved, nothing to say`(@TempDir root: File) {
        val classes = File(root, "build/classes")
        javac(classes, buildConfig(1), app())
        val then = capture(root, classes)
        javac(classes, buildConfig(1), app("y"))
        assertNull(apply(then, root, classes, listOf("BuildConfig#BUILD_TIME"), changedPrefixes = listOf("App")))
    }

    @Test
    fun `a class is tracked only when its source file lies in a source directory outside every build directory`(@TempDir root: File) {
        File(root, "app/src/main/java/dev/App.java").apply { parentFile.mkdirs(); writeText("") }
        File(root, "app/build/generated/stamp/dev/Stamp.java").apply { parentFile.mkdirs(); writeText("") }
        val facts = object : io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView {
            override val rootDir = root
            override val moduleDirs = mapOf("app" to ":app")
            override val buildDirs = mapOf(":app" to File(root, "app/build").absolutePath)
            override val dependencyHomes = emptyMap<String, File>()
            override val ownPath = ":app"
            override fun classpathFiles() = emptySet<File>()
            override fun testOutputFiles() = emptySet<File>()
            override fun compileTimeFiles() = emptySet<File>()
            override fun declaredModules(): Set<String>? = null
            override fun jacocoVersion(): String? = null
            override fun jacocoSetting() = ""
            override fun testJvm(): Int? = null
            override fun agentCeiling(): String? = null
        }
        val tracked = trackedSource(root, listOf("app/src/main/java", "app/build/generated/stamp"), facts)

        assertTrue(tracked("dev.App", "App.java"))
        assertFalse(tracked("dev.Stamp", "Stamp.java"))
        assertFalse(tracked("dev.Other", "Other.java"))
    }
}
