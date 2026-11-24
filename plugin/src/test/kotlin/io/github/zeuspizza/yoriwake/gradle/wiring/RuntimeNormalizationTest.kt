package io.github.zeuspizza.yoriwake.gradle.wiring

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeNormalizationTest {

    @Test
    fun `a build's runtime classpath normalization is read without fixing it, and matched as Gradle matches it`() {
        val project = ProjectBuilder.builder().build()
        assertEquals(RuntimeNormalization.Companion.Read.None, RuntimeNormalization.read(project))
        project.normalization.runtimeClasspath.apply {
            ignore("org/gradle/build-receipt.properties")
            ignore("generated/**")
            metaInf { it.ignoreAttribute("Bnd-LastModified") }
        }

        val read = assertIs<RuntimeNormalization.Companion.Read.Declared>(RuntimeNormalization.read(project)).normalization
        // Reading it left it open: Gradle refuses a declaration after the normalization is fixed.
        project.normalization.runtimeClasspath.ignore("later.properties")

        assertEquals(listOf("generated/**", "org/gradle/build-receipt.properties"), read.resources)
        assertEquals(listOf("bnd-lastmodified"), read.attributes)
        val filters = assertNotNull(read.filters())
        assertTrue(filters.ignoresResource("org/gradle/build-receipt.properties"))
        assertTrue(filters.ignoresResource("generated/a/b.txt"))
        assertFalse(filters.ignoresResource("org/gradle/other.properties"))
        assertFalse(filters.ignoresResource("deeper/org/gradle/build-receipt.properties"))
        assertTrue(filters.ignoresAttribute("Bnd-LastModified"))
        assertTrue(filters.ignoresAttribute("bnd-lastmodified"))
        assertFalse(filters.ignoresAttribute("Built-By"))
    }
}
