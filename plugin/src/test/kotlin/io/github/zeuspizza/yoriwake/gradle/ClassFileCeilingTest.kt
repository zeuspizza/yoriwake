package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.DigestRule
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.widenForInlining
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A selecting run over a class newer than a reader of it can take runs every test, and says why. */
class ClassFileCeilingTest {

    private fun classAt(name: String, major: Int): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
        writer.visitEnd()
        return writer.toByteArray().also {
            it[6] = (major ushr 8).toByte()
            it[7] = major.toByte()
        }
    }

    /** One module whose output directory holds [classes] and whose build directory holds [jars]. */
    private fun facts(
        dir: File,
        jacoco: String? = "0.8.15",
        testJvm: Int? = 21,
        classes: Map<String, ByteArray> = emptyMap(),
        jars: Map<String, Map<String, ByteArray>> = emptyMap(),
    ): ClasspathFacts {
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val output = File(dir, "core/build/classes").also { it.mkdirs() }
        classes.forEach { (name, bytes) ->
            File(output, name.replace('.', '/') + ".class").apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        val jarFiles = jars.map { (jarName, entries) ->
            File(dir, "core/build/libs/$jarName").apply {
                parentFile.mkdirs()
                ZipOutputStream(outputStream()).use { zip ->
                    entries.forEach { (entry, bytes) ->
                        zip.putNextEntry(ZipEntry(entry))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
        }
        return ClasspathFacts(
            rootDir = dir,
            moduleDirs = mapOf("core" to ":core"),
            buildDirs = mapOf(":core" to File(dir, "core/build").absolutePath),
            declared = project.provider { setOf(":core") },
            compileTime = project.files(),
            ownPath = ":core",
            testOutputs = project.files(),
            classpath = project.files(output, *jarFiles.toTypedArray()),
            jacocoVersion = project.provider { jacoco },
            testJvm = project.provider { testJvm },
        )
    }

    private fun widen(facts: ClasspathFacts, prefixes: List<String> = listOf("com.acme.Changed")) =
        widenForInlining(
            prefixes, facts,
            digestRule = DigestRule(recorded = null, bytesAreFresh = true),
            recordedAnnotations = emptyMap(),
        )

    @Test
    fun `a class newer than the plugin's ASM reads refuses by name`(@TempDir dir: File) {
        val widening = widen(facts(dir, classes = mapOf("com.acme.Late" to classAt("com.acme.Late", 72))))

        assertTrue(widening.forces)
        assertEquals(RefusalKind.CLASS_FILE_UNSUPPORTED, widening.refusalKind)
        val reason = widening.refusal!!
        assertContains(reason, "com.acme.Late is compiled for Java 28")
        assertContains(reason, "the plugin's ASM reads up to Java 27")
    }

    @Test
    fun `a class newer than the host's JaCoCo officially instruments refuses by name`(@TempDir dir: File) {
        val widening = widen(
            facts(dir, jacoco = "0.8.12", classes = mapOf("com.acme.Modern" to classAt("com.acme.Modern", 69)))
        )

        assertEquals(RefusalKind.CLASS_FILE_UNSUPPORTED, widening.refusalKind)
        assertContains(widening.refusal!!, "JaCoCo 0.8.12")
        assertContains(widening.refusal!!, "jacoco.toolVersion")
    }

    @Test
    fun `a JaCoCo newer than the table is held to the newest row`(@TempDir dir: File) {
        val within = widen(facts(dir, jacoco = "0.9.0", classes = mapOf("com.acme.A" to classAt("com.acme.A", 70))))

        assertFalse(within.refusalKind == RefusalKind.CLASS_FILE_UNSUPPORTED, within.refusal)
    }

    @Test
    fun `classes within every ceiling refuse nothing new`(@TempDir dir: File) {
        val widening = widen(facts(dir, classes = mapOf("com.acme.A" to classAt("com.acme.A", 65))))

        assertFalse(widening.refusalKind == RefusalKind.CLASS_FILE_UNSUPPORTED, widening.refusal)
    }

    @Test
    fun `the refusal holds on an empty change set`(@TempDir dir: File) {
        val widening = widen(
            facts(dir, classes = mapOf("com.acme.Late" to classAt("com.acme.Late", 72))),
            prefixes = emptyList(),
        )

        assertEquals(RefusalKind.CLASS_FILE_UNSUPPORTED, widening.refusalKind)
    }

    @Test
    fun `a class in an own jar counts`(@TempDir dir: File) {
        val widening = widen(
            facts(dir, jars = mapOf("core.jar" to mapOf("com/acme/Late.class" to classAt("com.acme.Late", 72))))
        )

        assertEquals(RefusalKind.CLASS_FILE_UNSUPPORTED, widening.refusalKind)
        assertContains(widening.refusal!!, "com.acme.Late")
    }

    @Test
    fun `a multi-release entry counts only on a test JVM that loads it`(@TempDir dir: File) {
        val jar = mapOf(
            "core.jar" to mapOf(
                "com/acme/A.class" to classAt("com.acme.A", 61),
                "META-INF/versions/25/com/acme/A.class" to classAt("com.acme.A", 69),
            )
        )

        val onJdk21 = widen(facts(File(dir, "21"), jacoco = "0.8.12", testJvm = 21, jars = jar))
        val onJdk25 = widen(facts(File(dir, "25"), jacoco = "0.8.12", testJvm = 25, jars = jar))
        val unknown = widen(facts(File(dir, "unknown"), jacoco = "0.8.12", testJvm = null, jars = jar))

        assertFalse(onJdk21.refusalKind == RefusalKind.CLASS_FILE_UNSUPPORTED, onJdk21.refusal)
        assertEquals(RefusalKind.CLASS_FILE_UNSUPPORTED, onJdk25.refusalKind)
        assertEquals(RefusalKind.CLASS_FILE_UNSUPPORTED, unknown.refusalKind)
    }
}
