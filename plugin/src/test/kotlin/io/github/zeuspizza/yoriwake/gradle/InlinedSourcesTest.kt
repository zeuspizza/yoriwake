package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.InlinedSources
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Real compiled bytecode where the question is what kotlinc emits; hand-written SMAPs for shapes
// a compiler will not produce on demand.
class InlinedSourcesTest {

    private fun bytesOf(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")) {
            "no class file for $name"
        }.use { it.readBytes() }

    @Test
    fun `a class that inlines another names it, which is the edge coverage cannot see`() {
        val inlined = InlinedSources.of(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"))

        // Without this edge a change to KotlinShapes.kt selects nothing, since coverage names nobody.
        assertTrue(
            inlined.any { it.endsWith("KotlinShapesKt") },
            "expected the inlined source to be named, got $inlined",
        )
    }

    @Test
    fun `a class file from a newer JDK is read, not reported as an SMAP nobody could parse`() {
        // Class-file major 70 (Java 26) must be readable by the bundled ASM. The version is stamped
        // onto a class this project compiles, rather than checking in a binary.
        val bytes = bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing").copyOf()
        bytes[6] = 0
        bytes[7] = 70

        val scan = InlinedSources.scan(bytes)

        assertFalse(
            scan.unknown,
            "a class file this ASM supports was not understood: " + InlinedSources.diagnose(bytes),
        )
    }

    @Test
    fun `bytes that cannot be read at all say so, and say nothing about an SMAP`() {
        // An unreadable class must be diagnosed as such, not blamed on the `*F` section.
        val diagnosis = InlinedSources.diagnose(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0, 0))

        assertFalse(
            diagnosis.contains("*F"),
            "unreadable bytes were blamed on the SMAP file section: $diagnosis",
        )
    }

    @Test
    fun `a class that inlines nothing names nothing`() {
        assertEquals(emptySet(), InlinedSources.of(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing")))
    }

    @Test
    fun `the class's own source is never reported as inlined into itself`() {
        val inlined = InlinedSources.of(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"))

        assertFalse(
            inlined.any { it.endsWith("InlinesSomethingElse") },
            "file 1 is the class's own source and must be dropped, got $inlined",
        )
    }

    @Test
    fun `a real SMAP is parsed to the inlined class, and only that`() {
        // Captured verbatim from demo-kotlin's InlinedTest.class.
        val smap = listOf(
            "SMAP", "InlinedTest.kt", "Kotlin", "*S Kotlin", "*F",
            "+ 1 InlinedTest.kt", "dev/demokt/InlinedTest",
            "+ 2 Inlined.kt", "dev/demokt/InlinedKt",
            "*L", "1#1,29:1", "18#2:30", "18#2:31", "*E",
        ).joinToString("\n")

        assertEquals(setOf("dev.demokt.InlinedKt"), InlinedSources.parse(smap))
    }

    @Test
    fun `input that is not an SMAP names nothing rather than guessing`() {
        assertEquals(emptySet(), InlinedSources.parse(null))
        assertEquals(emptySet(), InlinedSources.parse(""))
        assertEquals(emptySet(), InlinedSources.parse("not an smap at all"))
        // Truncated after the header: the file table never arrives.
        assertEquals(emptySet(), InlinedSources.parse("SMAP\nA.kt\nKotlin\n*S Kotlin\n"))
    }

    @Test
    fun `a file table with no path lines still names the files it can`() {
        val smap = listOf(
            "SMAP", "A.kt", "Kotlin", "*S Kotlin", "*F",
            "1 A.kt",
            "2 B.kt",
            "*L", "*E",
        ).joinToString("\n")

        assertEquals(setOf("B"), InlinedSources.parse(smap))
    }

    @Test
    fun `absence of an SMAP is a property of the whole output, not of one class`() {
        // A class that inlines nothing and a build compiled with -Xno-source-debug-extension look
        // alike one class at a time; TaskArtifacts.SmapEvidence decides over the whole output.
        assertTrue(
            InlinedSources.scan(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse")).carriesSmap,
            "the fixture inlines a function, so its class must carry an SMAP",
        )
        assertFalse(
            InlinedSources.scan(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing")).carriesSmap,
            "a class that inlines nothing must not be read as evidence the signal exists",
        )
    }

    @Test
    fun `the id decides which file is the class's own, not the order it was written in`() {
        // JSR-045 does not promise file 1 comes first; dropping by position would lose a real
        // inlined source, and a shorter set means fewer tests run.
        val smap = listOf(
            "SMAP", "InlinedTest.kt", "Kotlin", "*S Kotlin", "*F",
            "+ 2 Inlined.kt", "dev/demokt/InlinedKt",
            "+ 1 InlinedTest.kt", "dev/demokt/InlinedTest",
            "*L", "*E",
        ).joinToString("\n")

        assertEquals(setOf("dev.demokt.InlinedKt"), InlinedSources.parse(smap))
    }

    @Test
    fun `a truncated file table is unknown rather than a shorter set`() {
        // No `*L` or `*E`: what was read is only a prefix, and a missing consumer goes unselected.
        val truncated = listOf(
            "SMAP", "A.kt", "Kotlin", "*S Kotlin", "*F",
            "+ 1 A.kt", "dev/demo/A",
            "+ 2 B.kt",
        ).joinToString("\n")

        assertTrue(InlinedSources.parseFiles(truncated).unknown, "a truncated *F section is not a fact")
        assertFalse(
            InlinedSources.parseFiles(
                listOf("SMAP", "A.kt", "Kotlin", "*S Kotlin", "*F", "+ 1 A.kt", "dev/demo/A", "*E")
                    .joinToString("\n")
            ).unknown,
            "a table that closed properly was read in full",
        )
    }

    @Test
    fun `an entry whose id is not a number is unknown`() {
        val smap = listOf(
            "SMAP", "A.kt", "Kotlin", "*S Kotlin", "*F",
            "+ 1 A.kt", "dev/demo/A",
            "+ x B.kt", "dev/demo/B",
            "*E",
        ).joinToString("\n")

        assertTrue(InlinedSources.parseFiles(smap).unknown)
    }

    @Test
    fun `bytecode that cannot be read is unknown, never an empty answer`() {
        assertTrue(InlinedSources.scan(byteArrayOf(1, 2, 3)).unknown)
    }

    @Test
    fun `a class with no SMAP is not unknown, and says whether it is Kotlin`() {
        val scan = InlinedSources.scan(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing"))

        assertFalse(scan.unknown, "no SMAP is a fact about the class, not a failure to read it")
        assertFalse(scan.carriesSmap)
        assertTrue(scan.kotlin, "the whole-output question only applies to Kotlin output")
    }
}
