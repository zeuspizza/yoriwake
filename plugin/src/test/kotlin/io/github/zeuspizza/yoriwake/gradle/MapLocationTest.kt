package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.MapLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapLocationTest {

    @Test
    fun `a task path becomes a directory name with no path separators`(@TempDir dir: File) {
        // ':' is legal in a Gradle task path and illegal in a Windows path. Left verbatim this
        // fails at directory creation with an error that says nothing about task paths.
        val map = MapLocation.forTask(dir, ":app:test")

        assertTrue(map.name.startsWith("app-test-"), map.name)
        assertFalse(map.path.contains(':') && map.path.indexOf(':') > 2)
    }

    @Test
    fun `task paths that differ only in punctuation do not collide`() {
        // The readable part is lossy by necessity, and ':app:test' and a root task named 'app-test'
        // both reduce to 'app-test'. Sharing a directory, one task's records would silently be
        // read as the other's.
        assertTrue(MapLocation.sanitize(":app:test") != MapLocation.sanitize(":app-test"))
        assertTrue(MapLocation.sanitize(":a b:test") != MapLocation.sanitize(":a_b:test"))
    }

    @Test
    fun `task paths differing only in case do not collide`() {
        // Case-insensitive filesystems would otherwise land both in the same directory.
        assertTrue(MapLocation.sanitize(":App:test") != MapLocation.sanitize(":app:test"))
    }

    @Test
    fun `the same task path always yields the same directory`() {
        // The map must be found again next run; an unstable name means a permanently cold map.
        assertEquals(MapLocation.sanitize(":app:test"), MapLocation.sanitize(":app:test"))
    }

    @Test
    fun `the readable part survives so a human can find the directory`() {
        assertTrue(MapLocation.sanitize(":app:integrationTest").startsWith("app-integrationTest-"))
    }

    @Test
    fun `the root project's test task does not produce an empty directory name`() {
        assertTrue(MapLocation.sanitize(":test").startsWith("test-"))
    }

    @Test
    fun `two test tasks in one project get different directories`(@TempDir dir: File) {
        val unit = MapLocation.forTask(dir, ":app:test")
        val integration = MapLocation.forTask(dir, ":app:integrationTest")

        assertTrue(unit.absolutePath != integration.absolutePath)
    }

    @Test
    fun `unusual characters in a task name are replaced rather than passed through`() {
        assertTrue(MapLocation.sanitize(":app:test foo").startsWith("app-test_foo-"))
    }

    @Test
    fun `a path of only separators still yields a usable name`() {
        assertTrue(MapLocation.sanitize("::").startsWith("root-"))
    }

    @Test
    fun `the map lives under the yoriwake root`(@TempDir dir: File) {
        val map = MapLocation.forTask(dir, ":test")

        assertEquals(AgentContract.MAP_ROOT_DIR, map.parentFile.name)
    }
}
