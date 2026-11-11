package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.DigestRule
import io.github.zeuspizza.yoriwake.gradle.change.InlineWidening
import io.github.zeuspizza.yoriwake.gradle.change.LineOnlyKept
import io.github.zeuspizza.yoriwake.gradle.change.LineOnlyRule
import io.github.zeuspizza.yoriwake.gradle.change.ScopedChange
import io.github.zeuspizza.yoriwake.gradle.change.accountedFor
import io.github.zeuspizza.yoriwake.gradle.change.widenForInlining
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which changed paths `narrowLineOnlyChanges` takes out of the change set, and why each other one
 * stays: through the helper every selecting call site shares.
 */
class LineOnlyClassifyTest {

    @TempDir
    lateinit var dir: File

    private val betaPath = "core/src/main/java/com/acme/Beta.java"

    /** A class whose one method sits at [line], with a local named [local] and, when given, an SMAP. */
    private fun klass(name: String, line: Int = 3, local: String = "n", smap: String? = null, operand: Int = 1): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
        writer.visitSource(name.substringAfterLast('.') + ".java", smap)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f", "(I)I", null, null).apply {
            visitCode()
            val start = Label()
            val end = Label()
            visitLabel(start)
            visitLineNumber(line, start)
            visitVarInsn(Opcodes.ILOAD, 0)
            visitInsn(Opcodes.ICONST_0 + operand)
            visitInsn(Opcodes.IADD)
            visitInsn(Opcodes.IRETURN)
            visitLabel(end)
            visitLocalVariable(local, "I", null, start, end, 0)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** The module's output, written now, and its facts. */
    private fun module(vararg classes: Pair<String, ByteArray>): ClasspathFacts {
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val output = File(dir, "core/build/classes").also { it.deleteRecursively(); it.mkdirs() }
        classes.forEach { (name, bytes) ->
            File(output, name.replace('.', '/') + ".class").apply { parentFile.mkdirs(); writeBytes(bytes) }
        }
        File(dir, betaPath).apply { parentFile.mkdirs(); if (!exists()) writeText("class Beta {}") }
        return ClasspathFacts(
            rootDir = dir,
            moduleDirs = mapOf("core" to ":core"),
            buildDirs = mapOf(":core" to File(dir, "core/build").absolutePath),
            declared = project.provider { setOf(":core") },
            compileTime = project.files(),
            ownPath = ":core",
            testOutputs = project.files(),
            classpath = project.files(output),
        )
    }

    private fun lines(classes: Map<String, ByteArray>) = classes.mapValues { Recordability.lineDigest(it.value).value!! }
    private fun bytes(classes: Map<String, ByteArray>) = classes.mapValues { Recordability.classDigest(it.value).value!! }

    /** Captured with [captured], widened over [now] for [byPath]. */
    private fun widen(
        captured: Map<String, ByteArray>,
        now: Map<String, ByteArray>,
        byPath: Map<String, Set<String>> = mapOf(betaPath to setOf("com.acme.Beta")),
        debugTouches: Boolean = true,
        movedOutput: String? = null,
        recordedBytes: Map<String, String>? = bytes(captured),
        recordedLines: Map<String, String>? = lines(captured),
    ): InlineWidening {
        val facts = module(*now.toList().toTypedArray())
        return widenForInlining(
            byPath.values.flatten().distinct(), facts, inlinableSourceChanged = false,
            digestRule = DigestRule(recordedBytes, bytesAreFresh = true),
            recordedAnnotations = captured.mapValues { Recordability.annotationDigest(it.value).value!! },
            lineOnly = LineOnlyRule(dir, recordedLines, debugTouches, byPath, movedOutput),
        )
    }

    private fun InlineWidening.kept(path: String = betaPath) = lines!!.kept[path]?.kind

    @Test
    fun `a class that moved only its lines leaves the change set`() {
        val widening = widen(mapOf("com.acme.Beta" to klass("com.acme.Beta")), mapOf("com.acme.Beta" to klass("com.acme.Beta", line = 30)))

        assertEquals(setOf("com.acme.Beta"), widening.lines!!.classes)
        assertEquals(emptyList(), widening.prefixes)
        assertEquals(emptySet(), widening.changedBytes)
        assertEquals(listOf(betaPath), widening.lines!!.paths)
    }

    @Test
    fun `a changed operator stays changed`() {
        val widening = widen(mapOf("com.acme.Beta" to klass("com.acme.Beta")), mapOf("com.acme.Beta" to klass("com.acme.Beta", operand = 2)))

        assertEquals(LineOnlyKept.CLASS_UNMATCHED, widening.kept())
        assertEquals(listOf("com.acme.Beta"), widening.prefixes)
    }

    @Test
    fun `a renamed local stays changed`() {
        val widening = widen(mapOf("com.acme.Beta" to klass("com.acme.Beta")), mapOf("com.acme.Beta" to klass("com.acme.Beta", local = "m")))

        assertEquals(LineOnlyKept.CLASS_UNMATCHED, widening.kept())
    }

    @Test
    fun `a deleted file stays changed though its class lingers`() {
        val beta = mapOf("com.acme.Beta" to klass("com.acme.Beta"))
        val facts = module(*beta.toList().toTypedArray())
        File(dir, betaPath).delete()
        val widening = widenForInlining(
            listOf("com.acme.Beta"), facts, inlinableSourceChanged = false,
            digestRule = DigestRule(bytes(beta), bytesAreFresh = true),
            recordedAnnotations = beta.mapValues { Recordability.annotationDigest(it.value).value!! },
            lineOnly = LineOnlyRule(dir, lines(beta), true, mapOf(betaPath to setOf("com.acme.Beta")), null),
        )

        assertEquals(LineOnlyKept.PATH_DELETED, widening.kept())
    }

    @Test
    fun `a class the map recorded for the file that is no longer compiled keeps it changed`() {
        val captured = mapOf("com.acme.Beta" to klass("com.acme.Beta"), "com.acme.Beta\$Gone" to klass("com.acme.Beta\$Gone"))
        val widening = widen(captured, mapOf("com.acme.Beta" to klass("com.acme.Beta", line = 9)))

        assertEquals(LineOnlyKept.CLASS_VANISHED, widening.kept())
    }

    @Test
    fun `a prefix another changed path keeps stays changed`() {
        val other = "core/src/main/java/com/acme/BetaParts.java"
        val widening = widen(
            mapOf("com.acme.Beta" to klass("com.acme.Beta")), mapOf("com.acme.Beta" to klass("com.acme.Beta", line = 9)),
            byPath = mapOf(betaPath to setOf("com.acme.Beta"), other to setOf("com.acme.Beta")),
        )

        assertEquals(LineOnlyKept.PATH_DELETED, widening.kept(other))
        assertEquals(listOf("com.acme.Beta"), widening.prefixes)
        assertEquals(emptySet(), widening.lines!!.prefixes)
    }

    @Test
    fun `a class whose line digest moved where nothing stays changed keeps every path`() {
        // A generated class, no changed file's by name, whose local variables alone moved.
        val captured = mapOf("com.acme.Beta" to klass("com.acme.Beta"), "com.acme.Beta_Factory" to klass("com.acme.Beta_Factory"))
        val widening = widen(
            captured,
            mapOf("com.acme.Beta" to klass("com.acme.Beta", line = 9), "com.acme.Beta_Factory" to klass("com.acme.Beta_Factory", local = "x")),
        )

        assertEquals(LineOnlyKept.CLASS_UNMATCHED, widening.kept())
        assertEquals("com.acme.Beta_Factory", widening.lines!!.kept.getValue(betaPath).detail)
    }

    @Test
    fun `a class whose bytes moved in line information only is windowed, not a changed byte`() {
        val captured = mapOf("com.acme.Caller" to klass("com.acme.Caller", smap = "SMAP\n1"))
        val widening = widen(captured, mapOf("com.acme.Caller" to klass("com.acme.Caller", smap = "SMAP\n2")), byPath = emptyMap())

        assertEquals(setOf("com.acme.Caller"), widening.lines!!.bytes)
        assertEquals(emptySet(), widening.changedBytes)
    }

    private val capturedBeta = mapOf("com.acme.Beta" to klass("com.acme.Beta"))
    private val movedBeta = mapOf("com.acme.Beta" to klass("com.acme.Beta", line = 9))

    @Test
    fun `a map without line digests keeps the file changed`() {
        assertEquals(LineOnlyKept.LINE_DIGESTS_ABSENT, widen(capturedBeta, movedBeta, recordedLines = null).kept())
    }

    @Test
    fun `a map without debug touches keeps the file changed`() {
        assertEquals(LineOnlyKept.DEBUG_TOUCH_ABSENT, widen(capturedBeta, movedBeta, debugTouches = false).kept())
    }

    @Test
    fun `a walk that did not finish keeps every path`() {
        assertEquals(LineOnlyKept.WALK_INCOMPLETE, widen(capturedBeta, movedBeta, recordedBytes = null).kept())
    }

    @Test
    fun `a build output file that moved keeps every path`() {
        assertEquals(
            LineOnlyKept.OUTPUT_FILE_CHANGED,
            widen(capturedBeta, movedBeta, movedOutput = "core/build/resources/main/doc.json").kept(),
        )
    }

    @Test
    fun `a change set the rule emptied is accounted for`() {
        val change = ChangeDetection.Change(setOf("com.acme.Beta"), emptyList())
        assertTrue(accountedFor(listOf(betaPath), ScopedChange(change, 0), setOf("com.acme.Beta")))
    }
}
