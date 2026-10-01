package io.github.zeuspizza.yoriwake.gradle.wiring

import java.io.File

/**
 * Extracts the bundled agent jar so it can be put on a test runtime classpath.
 *
 * Bundled rather than resolved: agent and plugin share a record format, and shipping them together
 * keeps a host from pinning a mismatched agent version.
 */
internal object AgentJar {

    const val RESOURCE = "yoriwake-agent.jar"

    /** Kept out of the map root so a listing there contains maps and nothing else. */
    const val DIRECTORY = "yoriwake-agent"

    /** Whether this plugin build carries an agent at all, asked without touching the file system. */
    fun isBundled(): Boolean = javaClass.classLoader.getResource(RESOURCE) != null

    /**
     * Where the agent will be, without touching the file system: reading or creating this path at
     * configuration makes the configuration cache record the jar as absent and discard the entry.
     */
    fun locationIn(cacheDir: File): File = File(cacheDir, RESOURCE)

    /** Writes the bundled agent to [cacheDir] and returns it, or null when it is not bundled. */
    fun extractTo(cacheDir: File): File? {
        val bundled = javaClass.classLoader.getResourceAsStream(RESOURCE) ?: return null
        val target = locationIn(cacheDir)

        bundled.use { source ->
            val bytes = source.readBytes()
            // Rewrite only on a content change, so the test task's classpath input stays up to date.
            if (!target.isFile || target.length() != bytes.size.toLong() || !target.readBytes().contentEquals(bytes)) {
                target.parentFile.mkdirs()
                target.writeBytes(bytes)
            }
        }
        return target
    }
}
