package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import org.gradle.api.InvalidUserDataException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The names a build lists in `environmentInclude` and `systemPropertyIgnore`. */
class ValidNamesTest {

    @Test
    fun `names and patterns are kept, trimmed`() {
        assertEquals(listOf("build.*", "DB_MODE"), validNames(listOf(" build.* ", "DB_MODE"), "systemPropertyIgnore", ":app"))
    }

    @Test
    fun `an empty entry or one that matches everything fails, naming the property`() {
        listOf(" ", "*", "**").forEach { entry ->
            val failure = assertThrows<InvalidUserDataException> { validNames(listOf(entry), "environmentInclude", ":app") }
            assertContains(failure.message.orEmpty(), "environmentInclude")
            assertContains(failure.message.orEmpty(), ":app")
        }
    }

    @Test
    fun `the check both hosts share accepts a pattern and refuses an empty entry or only stars`() {
        assertEquals(null, EnvironmentFingerprint.invalidNames(listOf("DB_*", "build.number"), "environmentInclude", "lib"))
        assertContains(EnvironmentFingerprint.invalidNames(listOf("DB_*", ""), "environmentInclude", "lib").orEmpty(), "names nothing")
        assertContains(EnvironmentFingerprint.invalidNames(listOf("**"), "systemPropertyIgnore", "lib").orEmpty(), "lib: systemPropertyIgnore")
    }
}
