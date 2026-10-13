package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.select.Glob

/**
 * Changed paths that are no change to selection: an IDE's own state, and what the build declares
 * in `notReadByTests` that neither its tests nor the build read. Every other changed file a
 * coverage map cannot see forces a full run.
 */
internal object NotRead {

    /** Rewritten by an IDE as you work, at any depth, and read by no build or test. */
    private val IDE_STATE_DIRECTORIES = setOf(".idea", ".vscode", ".fleet")

    /** Whether [path] is under an IDE state directory, or an IntelliJ module file. */
    fun isIdeState(path: String): Boolean =
        path.substringAfterLast('/').endsWith(".iml") ||
            path.split('/').dropLast(1).any { it in IDE_STATE_DIRECTORIES }

    /**
     * Whether a root-relative path matches one of [globs] whole, `*` matching any characters,
     * slashes included.
     */
    fun declared(globs: List<String>): (String) -> Boolean {
        val patterns = globs.map(String::trim).filter(String::isNotEmpty).map { Regex(Glob.regex(it)) }
        return { path -> patterns.any { it.matches(path) } }
    }
}
