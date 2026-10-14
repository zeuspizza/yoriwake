package io.github.zeuspizza.yoriwake.gradle.wiring

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
}
