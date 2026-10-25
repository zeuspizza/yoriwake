package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File
import java.security.MessageDigest

/**
 * Which build-produced files are copies of a tracked text file that git checked out with `\r\n`,
 * so [ClasspathFiles] can digest them in their `\n` form: two checkouts of one commit that differ
 * only in `core.autocrlf` then record the same digest for them, as git's own diff already treats
 * them as equal.
 *
 * A file qualifies only on git's own evidence: its `\n` form is, byte for byte, the index blob of a
 * tracked regular file at a path ending with the file's own path, and that file in the working tree
 * holds exactly the copy's bytes, so the `\r\n` is what git's checkout wrote. Git's attributes and
 * settings are never imitated here. Anything else, and any git failure, keeps the file's bytes
 * digest: a generated, binary or pinned file still compares by bytes, and a failure can only widen.
 */
internal class TrackedText(
    private val rootDir: File,
    private val git: (List<String>) -> String? = { ChangeDetection.rawGit(rootDir, it) },
) {
    /** Blob id to the tracked regular files holding it, relative to [rootDir]; null when git could not say. */
    private val index: Map<String, List<String>>? by lazy {
        val listed = git(listOf("ls-files", "-z", "--stage")) ?: return@lazy null
        val byBlob = mutableMapOf<String, MutableList<String>>()
        for (record in listed.split('\u0000')) {
            val tab = record.indexOf('\t')
            if (tab < 0) continue
            // `<mode> <object> <stage>`; a path in a merge conflict has no stage-0 blob to match, and
            // a link or a submodule is no text file.
            val fields = record.substring(0, tab).split(' ')
            if (fields.size != 3 || fields[2] != "0" || fields[0] !in REGULAR) continue
            byBlob.getOrPut(fields[1]) { mutableListOf() } += record.substring(tab + 1)
        }
        byBlob
    }

    /**
     * The SHA-256 of [bytes] with every `\r\n` made `\n`, when they are such a copy of a tracked file
     * at a path ending with [relative]; otherwise null. A file without `\r\n` is null at once, so its
     * digest stays exactly what it was.
     */
    fun digest(bytes: ByteArray, relative: String): String? {
        if (!hasCrlf(bytes)) return null
        val lf = toLf(bytes)
        val paths = index?.get(blobId(lf)) ?: return null
        val checkedOut = paths.any { path ->
            (path == relative || path.endsWith("/$relative")) &&
                runCatching { File(rootDir, path).readBytes().contentEquals(bytes) }.getOrDefault(false)
        }
        return if (checkedOut) hex(MessageDigest.getInstance("SHA-256").digest(lf)) else null
    }

    private companion object {
        val REGULAR = setOf("100644", "100755")
        const val CR: Byte = '\r'.code.toByte()
        const val LF: Byte = '\n'.code.toByte()

        fun hasCrlf(bytes: ByteArray): Boolean {
            for (i in 0 until bytes.size - 1) {
                if (bytes[i] == CR && bytes[i + 1] == LF) return true
            }
            return false
        }

        fun toLf(bytes: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(bytes.size)
            var i = 0
            while (i < bytes.size) {
                if (bytes[i] == CR && i + 1 < bytes.size && bytes[i + 1] == LF) {
                    i++
                    continue
                }
                out.write(bytes[i].toInt())
                i++
            }
            return out.toByteArray()
        }

        /** Git's SHA-1 object id of a blob with this content; a SHA-256 repository matches none. */
        fun blobId(content: ByteArray): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
            sha1.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
            return hex(sha1.digest(content))
        }

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
