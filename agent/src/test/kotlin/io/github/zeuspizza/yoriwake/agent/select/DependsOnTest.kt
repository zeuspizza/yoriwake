package io.github.zeuspizza.yoriwake.agent.select

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Declared dependencies: a glob over changed paths, on a test's tag or a task's declaration, that
 * adds its declarers when a path it matches changes. A glob that matches less than it says only
 * runs fewer of the declarers it was written for, so the grammar is checked case by case.
 */
class DependsOnTest {

    @Test
    fun `a star stays within a directory, a double star crosses any number, a question mark is one character`() {
        assertTrue(PathGlob.matches("docker/*.yml", "docker/compose.yml"))
        assertFalse(PathGlob.matches("docker/*.yml", "docker/db/compose.yml"))
        assertTrue(PathGlob.matches("docker/**", "docker/db/init/01.sql"))
        assertTrue(PathGlob.matches("docker/**/init.sql", "docker/init.sql"))
        assertTrue(PathGlob.matches("docker/**/init.sql", "docker/db/init.sql"))
        assertTrue(PathGlob.matches("db/V?__*.sql", "db/V2__add.sql"))
        assertFalse(PathGlob.matches("db/V?__*.sql", "db/V12__add.sql"))
        assertFalse(PathGlob.matches("docker/**", "dockerfile"))
    }

    @Test
    fun `a glob is relative to its project, or to the root with a leading slash`() {
        assertEquals("app/src/main/resources/db/**", PathGlob.resolve("src/main/resources/db/**", "app"))
        assertEquals("src/main/resources/db/**", PathGlob.resolve("src/main/resources/db/**", ""))
        assertEquals("docker/**", PathGlob.resolve("/docker/**", "app"))
    }

    @Test
    fun `a glob that matches every path, or reads as another dialect, is refused with its reason`() {
        for (glob in listOf("", " ", "**", "/**", "/", "**/**")) {
            assertNotNull(PathGlob.problem(glob), "'$glob' was accepted")
        }
        for (glob in listOf("docker/{a,b}.yml", "a\\b", "../x", "docker/./x", "do**cker/x", "!docker/**")) {
            assertNotNull(PathGlob.problem(glob), "'$glob' was accepted")
        }
        for (glob in listOf("docker/**", "/docker/**", "db/V?__*.sql", "fixture.json")) {
            assertNull(PathGlob.problem(glob), "'$glob' was refused")
        }
    }

    @Test
    fun `a test's tag declares the glob after the prefix, and any other tag declares nothing`() {
        assertEquals("/docker/**", DependsOn.globOf("yoriwake-depends-on:/docker/**"))
        assertNull(DependsOn.globOf("yoriwake-always-run"))
        assertNull(DependsOn.globOf("slow"))
    }
}
