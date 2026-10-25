package io.github.zeuspizza.yoriwake.gradle.capture

import java.io.File
import java.util.UUID

/**
 * The JVM arguments a test JVM starts with once the `java` launcher has replaced each `@file`
 * argument with the arguments that file holds, read by the launcher's own rules. A system property
 * passed that way is set in the test JVM like one passed directly, so the fingerprint must see it.
 */
internal object JvmArgFiles {

    /**
     * [arguments] with every argument file expanded, a relative one resolved against [workingDir],
     * the test JVM's. A file that cannot be read becomes a property no other run can match, so the
     * environment reads as changed: what it would have set is unknown.
     */
    fun expand(arguments: List<String>, workingDir: File?): List<String> {
        val expanded = ArrayList<String>(arguments.size)
        var expanding = true
        for (argument in arguments) {
            when {
                !expanding || !argument.startsWith("@") || argument.length == 1 -> {
                    expanded += argument
                    if (argument == "--disable-@files") expanding = false
                }
                // `@@x` is the literal argument `@x`.
                argument.startsWith("@@") -> expanded += argument.substring(1)
                else -> expanded += read(argument.substring(1), workingDir)
            }
        }
        return expanded
    }

    private fun read(path: String, workingDir: File?): List<String> {
        val file = File(path).let { if (it.isAbsolute || workingDir == null) it else File(workingDir, path) }
        // Every byte reaches the digest as it is, whatever the file's encoding.
        val text = runCatching { file.readBytes().toString(Charsets.ISO_8859_1) }.getOrNull()
            ?: return listOf("-D@$path=${UUID.randomUUID()}")
        return tokens(text)
    }

    /**
     * The arguments an argument file holds, as OpenJDK's launcher splits them (`args.c`): whitespace
     * separates them and a line break always ends one; `"` and `'` quote, also mid-argument; inside
     * quotes a backslash escapes, and before a line break joins the next line without its leading
     * whitespace; `#` outside quotes comments out the rest of the line. An argument cut short by `#`
     * is kept, as JDK 27 keeps it: earlier launchers drop it, and comparing one property too many
     * only ever runs more.
     */
    fun tokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var started = false
        var quote: Char? = null
        fun end() {
            if (started) tokens += token.toString()
            token.setLength(0)
            started = false
            quote = null
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val open = quote
            when {
                open != null && (c == '\n' || c == '\r') -> end()
                open != null && c == '\\' -> {
                    val next = text.getOrNull(i + 1)
                    if (next == '\n' || next == '\r') {
                        i += 1
                        while (i + 1 < text.length && text[i + 1] in WHITESPACE) i++
                    } else if (next != null) {
                        token.append(ESCAPES[next] ?: next)
                        i++
                    }
                }
                open != null && c == open -> quote = null
                open != null -> token.append(c)
                c in WHITESPACE -> end()
                c == '#' -> {
                    end()
                    while (i + 1 < text.length && text[i + 1] != '\n' && text[i + 1] != '\r') i++
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    started = true
                }
                else -> {
                    token.append(c)
                    started = true
                }
            }
            i++
        }
        end()
        return tokens
    }

    private const val WHITESPACE = " \t\u000c\n\r"

    private val ESCAPES = mapOf('n' to '\n', 'r' to '\r', 't' to '\t', 'f' to '\u000c')
}
