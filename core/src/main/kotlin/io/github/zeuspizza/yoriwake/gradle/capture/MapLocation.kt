package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import java.io.File

/**
 * Where a test task's coverage map lives.
 *
 * Under `.gradle/`, not `build/`: a map costs a full suite run to rebuild and must survive `clean`.
 * One directory per test task, so two tasks never share a record sequence space.
 */
internal object MapLocation {

    /** The map directory for one test task. */
    fun forTask(gradleUserSubdir: File, taskPath: String): File =
        File(File(gradleUserSubdir, AgentContract.MAP_ROOT_DIR), sanitize(taskPath))

    /**
     * Turns a task path into a directory name that no other task path can produce.
     *
     * The readable part is lossy (`:` is illegal on Windows), so a hash of the full task path is
     * appended: otherwise `:app:test` and a root task `app-test` collide, as do `:App:test` and
     * `:app:test` on a case-insensitive filesystem.
     */
    fun sanitize(taskPath: String): String {
        val readable = taskPath.trim(':')
            .replace(':', '-')
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .ifEmpty { "root" }
        return "$readable-${fingerprint(taskPath)}"
    }

    /** Short, stable, and only ever used to disambiguate — not for integrity. */
    private fun fingerprint(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
