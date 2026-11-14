package io.github.zeuspizza.yoriwake.maven

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressionsTest {

    @Test
    fun `false keeps the user properties from the fork from Surefire 3_4_0 on only`() {
        for (version in listOf("2.22.2", "3.3.1")) assertTrue(promotesUserProperties("false", version), version)
        for (version in listOf("3.4.0", "3.5.2", "3.10.0", "4.0.0-M4")) assertFalse(promotesUserProperties("false", version), version)
    }

    @Test
    fun `false is read in any case and with blanks around it`() {
        for (value in listOf("FALSE", "False", " false ")) assertFalse(promotesUserProperties(value, "3.5.2"), "'$value'")
    }

    @Test
    fun `any other value promotes, as no value does`() {
        for (value in listOf("true", null, "", " ", "no")) assertTrue(promotesUserProperties(value, "3.5.2"), "'$value'")
    }

    @Test
    fun `a version that cannot be read promotes`() {
        for (version in listOf(null, "", "unknown")) assertTrue(promotesUserProperties("false", version), "'$version'")
    }

    @Test
    fun `an expression nothing defines resolves to nothing, which promotes`() {
        val resolved = resolveExpressions("\${missing}") { null }

        assertNull(resolved)
        assertTrue(promotesUserProperties(resolved, "3.5.2"))
    }

    @Test
    fun `both expression forms resolve, and the text around them is kept`() {
        val known = mapOf("p" to "false", "name" to "lib")

        assertEquals("false", resolveExpressions("\${p}", known::get))
        assertEquals("false", resolveExpressions("@{p}", known::get))
        assertEquals("-Dmodule=lib -Xmx1g", resolveExpressions("-Dmodule=\${name} @{missing}-Xmx1g", known::get))
        assertNull(resolveExpressions(null, known::get))
        assertFalse(promotesUserProperties(resolveExpressions("\${p}", known::get), "3.5.2"))
    }
}
