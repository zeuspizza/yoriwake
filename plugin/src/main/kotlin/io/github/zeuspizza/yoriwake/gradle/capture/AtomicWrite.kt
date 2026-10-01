package io.github.zeuspizza.yoriwake.gradle.capture

import java.io.File

/**
 * Writes a file so a reader sees the whole of it or the whole of the previous one.
 *
 * A torn plain write leaves a shorter file that still parses (a truncated `key=value` file simply
 * has fewer facts), so damage would read as "not recorded".
 *
 * Throws [MapNotWritten] on failure; callers decide what an unwritten file means.
 */
internal fun writeAtomically(target: File, content: String) {
    val temporary = File(target.parentFile, target.name + ".tmp")
    temporary.writeText(content)
    if (temporary.renameTo(target)) {
        return
    }
    target.delete()
    if (temporary.renameTo(target)) {
        return
    }
    // Callers leave the previous file alone rather than fail the build. On Windows the usual cause
    // is a virus scanner holding the target open.
    temporary.delete()
    throw MapNotWritten(target)
}
