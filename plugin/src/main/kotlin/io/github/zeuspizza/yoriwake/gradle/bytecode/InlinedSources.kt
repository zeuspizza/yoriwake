package io.github.zeuspizza.yoriwake.gradle.bytecode

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Which other sources were copied into this class by the Kotlin compiler.
 *
 * An `inline` function's body is compiled into every call site, so coverage never sees the edge to
 * the declaring source. Kotlin's `SourceDebugExtension` (SMAP) names every source inlined into the
 * class.
 *
 * A missing SMAP is not proof: a class that inlines nothing and every class compiled with
 * `-Xno-source-debug-extension` look the same. So [scan] answers "nothing named here", and callers
 * decide project-wide whether the signal exists (see [Scan.carriesSmap]).
 */
internal object InlinedSources {

    /**
     * Everything one class file says about code copied into it, from a single ASM pass.
     *
     * [unknown] keeps "could not read this" apart from "nothing named here": callers narrow on the
     * second and must force on the first.
     */
    data class Scan(
        /** Fully qualified names of the sources inlined into this class, excluding its own. */
        val inlined: Set<String> = emptySet(),
        /** Whether the class carried a `SourceDebugExtension` at all. */
        val carriesSmap: Boolean = false,
        /** Whether the class carries `kotlin.Metadata`, and so could have inlined anything. */
        val kotlin: Boolean = false,
        /** Whether the bytecode or its SMAP could not be understood. Callers must force. */
        val unknown: Boolean = false,
    )

    private const val KOTLIN_METADATA = "Lkotlin/Metadata;"

    /** Why [scan] gave up on these bytes, for a refusal message. Diagnostic only. */
    fun diagnose(classBytes: ByteArray): String = runCatching {
        var debug: String? = null
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitSource(source: String?, smap: String?) { debug = smap }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
        )
        val parsed = parseFiles(debug)
        if (parsed.unknown) "the *F section did not parse: " + (debug?.take(160) ?: "<none>")
        else "read cleanly on a second look"
    }.getOrElse { "${it::class.java.simpleName}: ${it.message}" }

    /** Reads one class file. Any failure is [Scan.unknown], never an empty answer. */
    fun scan(classBytes: ByteArray): Scan = runCatching {
        var debug: String? = null
        var isKotlin = false
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitSource(source: String?, smap: String?) {
                    debug = smap
                }

                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                    if (descriptor == KOTLIN_METADATA) {
                        isKotlin = true
                    }
                    return null
                }
                // SKIP_DEBUG must not be set: it suppresses visitSource, which carries the SMAP.
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
        )
        val parsed = parseFiles(debug)
        Scan(
            inlined = parsed.names,
            carriesSmap = !debug.isNullOrBlank(),
            kotlin = isKotlin,
            unknown = parsed.unknown,
        )
    }.getOrElse { Scan(unknown = true) }

    /**
     * The classes whose source was inlined into these bytes, as fully qualified names.
     *
     * Prefer [scan]: this drops the distinction between "nothing named" and "not understood".
     */
    fun of(classBytes: ByteArray): Set<String> = scan(classBytes).inlined

    /** A parsed `*F` section, and whether anything in it was left un-understood. */
    data class Files(val names: Set<String> = emptySet(), val unknown: Boolean = false)

    /**
     * Parses the file section of an SMAP, returning every inlined source's class.
     *
     * JSR-045 `*F` section: `+ <id> <filename>` followed by a path line, or a bare
     * `<id> <filename>`. The own source is found by id 1, not by position, which the format does
     * not fix. Anything unreadable sets [Files.unknown].
     */
    fun parseFiles(smap: String?): Files {
        // No SMAP is not a parse failure; see [Scan.carriesSmap].
        if (smap.isNullOrBlank()) return Files()
        val lines = smap.replace("\r\n", "\n").split('\n')
        if (lines.firstOrNull()?.trim() != "SMAP") return Files(unknown = true)

        val start = lines.indexOfFirst { it.trim() == "*F" }
        if (start < 0) return Files(unknown = true)

        val byId = mutableMapOf<Int, String>()
        var unknown = false
        var closed = false
        var index = start + 1
        while (index < lines.size) {
            val line = lines[index].trim()
            // Any other section header ends the file table. `*E` ends the SMAP.
            if (line.startsWith("*")) {
                closed = true
                break
            }
            if (line.isEmpty()) {
                index++
                continue
            }
            if (line.startsWith("+ ")) {
                val id = idOf(line.removePrefix("+ "))
                val path = lines.getOrNull(index + 1)?.trim()
                if (id == null || path == null || path.startsWith("*") || path.startsWith("+ ")) {
                    unknown = true
                    index++
                    continue
                }
                byId[id] = path.removeSuffix(".kt").replace('/', '.')
                index += 2
            } else {
                // `<id> <filename>` with no path line: only the file name is known.
                val id = idOf(line)
                if (id == null) {
                    unknown = true
                } else {
                    byId[id] = line.substringAfter(' ').trim().removeSuffix(".kt")
                }
                index++
            }
        }
        // A table with no closing section, or naming nothing, was not understood.
        if (!closed || byId.isEmpty()) {
            unknown = true
        }
        return Files(byId.filterKeys { it != 1 }.values.filter { it.isNotEmpty() }.toSet(), unknown)
    }

    /** The leading integer of `<id> <filename>`, or null when it is not one. */
    private fun idOf(entry: String): Int? = entry.trim().substringBefore(' ').toIntOrNull()

    /** Just the names [parseFiles] understood, for callers that only ever over-select. */
    fun parse(smap: String?): Set<String> = parseFiles(smap).names

}
