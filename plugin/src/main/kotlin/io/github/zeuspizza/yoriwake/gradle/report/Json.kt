package io.github.zeuspizza.yoriwake.gradle.report

/**
 * JSON string encoding for `explain.json` and `audit.json`.
 *
 * Hand-rolled so the plugin adds no dependency to the host build.
 */
internal object Json {

    /** [value] as a JSON string literal, or `null` when there is none. */
    fun string(value: String?): String = if (value == null) "null" else "\"${escape(value)}\""

    /** The values as a one-line JSON array, the layout both files already use for a sample. */
    fun strings(values: Iterable<String>): String = values.joinToString(", ", "[", "]") { string(it) }

    /** RFC 8259 section 7: the quote, the backslash, and every control character below U+0020. */
    fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
    }
}
