package io.github.zeuspizza.yoriwake.gradle

import org.gradle.api.InvalidUserDataException
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    private fun settings(vararg flags: Pair<String, String>) = Settings(mapOf(*flags)::get)

    @Test
    fun `an absent flag is its default`() {
        val settings = settings()
        assertFalse(settings.select)
        assertFalse(settings.disabled)
        assertFalse(settings.loaded)
        assertNull(settings.base)
        assertEquals(emptyList(), settings.alwaysRun)
    }

    @Test
    fun `a bare flag is on, because Gradle reads -Pflag as the empty string`() {
        assertTrue(settings(Settings.SELECT to "").select)
        assertTrue(settings(Settings.DISABLED to "").disabled)
    }

    @Test
    fun `true and false are read in any case`() {
        assertTrue(settings(Settings.SELECT to "true").select)
        assertTrue(settings(Settings.SELECT to "TRUE").select)
        assertFalse(settings(Settings.SELECT to "false").select)
        assertFalse(settings(Settings.DISABLED to "False").disabled)
    }

    @Test
    fun `any other value fails the build and names the flag`() {
        val failure = assertFailsWith<InvalidUserDataException> {
            settings(Settings.SELECT to "yes")
        }
        assertContains(failure.message.orEmpty(), "yoriwake.select")
        assertContains(failure.message.orEmpty(), "yes")
    }

    @Test
    fun `fullRun is a switch, and a value that is not a boolean names it`() {
        assertFalse(settings().fullRun)
        assertTrue(settings(Settings.FULL_RUN to "").fullRun)
        val failure = assertFailsWith<InvalidUserDataException> { settings(Settings.FULL_RUN to "yes") }
        assertContains(failure.message.orEmpty(), "yoriwake.fullRun=yes")
    }

    @Test
    fun `observe is a switch, and a value that is not a boolean names it`() {
        assertFalse(settings().observe)
        assertTrue(settings(Settings.OBSERVE to "").observe)
        assertFalse(settings(Settings.OBSERVE to "false").observe)
        val failure = assertFailsWith<InvalidUserDataException> { settings(Settings.OBSERVE to "maybe") }
        assertContains(failure.message.orEmpty(), "yoriwake.observe=maybe")
    }

    @Test
    fun `complement is off unless set, bare or true for this build's record, any other value a directory`() {
        assertEquals(null, settings().complement)
        assertEquals("", settings(Settings.COMPLEMENT to "").complement)
        assertEquals("", settings(Settings.COMPLEMENT to "true").complement)
        assertEquals(null, settings(Settings.COMPLEMENT to "false").complement)
        assertEquals("ci/yoriwake", settings(Settings.COMPLEMENT to "ci/yoriwake").complement)
    }

    @Test
    fun `isolatedCapture is a switch like the others`() {
        assertFalse(settings().isolatedCapture)
        assertTrue(settings(Settings.ISOLATED_CAPTURE to "").isolatedCapture)
        assertFalse(settings(Settings.ISOLATED_CAPTURE to "false").isolatedCapture)
        val failure = assertFailsWith<InvalidUserDataException> { settings(Settings.ISOLATED_CAPTURE to "1") }
        assertContains(failure.message.orEmpty(), "yoriwake.isolatedCapture=1")
    }

    @Test
    fun `alwaysRun is split on commas and blanks are dropped`() {
        assertEquals(listOf("a.B", "c.*"), settings(Settings.ALWAYS_RUN to " a.B, ,c.* ").alwaysRun)
    }
}
