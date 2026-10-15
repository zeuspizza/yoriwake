package io.github.zeuspizza.yoriwake.gradle.bytecode

import org.objectweb.asm.Opcodes

/**
 * The newest class files the plugin's ASM and the host's JaCoCo can be trusted with. A class above
 * either is read or instrumented wrongly or not at all, so selection over it cannot be trusted.
 */
internal object ClassFileSupport {

    /** The ceiling of one JaCoCo version as a Java version, and why it was assumed when it was. */
    data class JacocoCeiling(val java: Int, val note: String?)

    private val asm: Int by lazy { asmCeilingOf(Opcodes::class.java) }

    /**
     * The highest class-file major version the bundled ASM reads: its largest `Opcodes.V*`
     * constant, so an ASM upgrade raises it with nothing written by hand. 0 when that cannot be
     * read, which refuses every class.
     */
    fun asmCeiling(): Int = asm

    internal fun asmCeilingOf(opcodes: Class<*>): Int = runCatching {
        opcodes.fields
            .filter { it.name.matches(VERSION_FIELD) && it.type == Int::class.javaPrimitiveType }
            .maxOf { (it.getInt(null) and 0xFFFF) }
    }.getOrDefault(0)

    private val table: List<Pair<List<Int>, Int>> by lazy {
        ClassFileSupport::class.java.getResourceAsStream(TABLE)!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line -> line.split("\t").let { (version, java) -> parts(version)!! to java.trim().toInt() } }
                .sortedWith { a, b -> compare(a.first, b.first) }
                .toList()
        }
    }

    /**
     * The highest Java version [version] of JaCoCo officially instruments. An unlisted version
     * between rows takes the row below it; one newer than every row takes the newest, and one that
     * cannot be read the lowest, each with a [JacocoCeiling.note] saying so.
     */
    fun jacocoCeiling(version: String?): JacocoCeiling {
        val wanted = version?.let(::parts)
            ?: return JacocoCeiling(table.first().second, "JaCoCo's version ${version ?: "(none)"} could not be read; assuming the oldest one yoriwake knows")
        table.firstOrNull { compare(it.first, wanted) == 0 }?.let { return JacocoCeiling(it.second, null) }
        val newest = table.last()
        if (compare(wanted, newest.first) > 0) {
            return JacocoCeiling(
                newest.second,
                "JaCoCo $version is newer than any yoriwake knows; assuming it instruments up to " +
                    "Java ${newest.second}, as ${newest.first.joinToString(".")} does",
            )
        }
        val below = table.lastOrNull { compare(it.first, wanted) < 0 }
            ?: return JacocoCeiling(table.first().second, "JaCoCo $version is older than any yoriwake knows")
        return JacocoCeiling(below.second, null)
    }

    /** The class-file major version in [bytes]' header; above every ceiling when there is none. */
    fun majorOf(bytes: ByteArray): Int =
        if (bytes.size < 8) Int.MAX_VALUE
        else ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)

    /** The Java version whose class files carry [major]. */
    fun javaOf(major: Int): Int = major - 44

    /** The leading numbers of a version, `0.8.16-SNAPSHOT` as 0, 8, 16; null without any. */
    private fun parts(version: String): List<Int>? =
        Regex("""^\d+(\.\d+)*""").find(version.trim())?.value?.split('.')?.map(String::toInt)

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private val VERSION_FIELD = Regex("""V\d+|V1_\d+""")

    private const val TABLE = "/io/github/zeuspizza/yoriwake/gradle/jacoco-java-support.tsv"
}
