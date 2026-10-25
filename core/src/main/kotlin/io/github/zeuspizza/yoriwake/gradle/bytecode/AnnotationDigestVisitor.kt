package io.github.zeuspizza.yoriwake.gradle.bytecode

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath

/**
 * Collects every annotation a class declares, visible or not, with its attribute values: on the
 * class, its fields, methods, parameters and record components, type annotations and annotation
 * defaults included. Constructed only by `Recordability.annotationDigest`.
 *
 * Elements are keyed and sorted, because reflection promises no member order and moving a method
 * is not an annotation change. Annotations on one element keep their class-file order: that is
 * what `getAnnotations()` returns, so a framework can observe it.
 *
 * Annotations inside method bodies (instruction, try-catch, local variable) are left out: no
 * reflective read reaches them, and their offsets move with every body edit. So is Kotlin's SMAP
 * annotation; see [visitAnnotation].
 */
internal class AnnotationDigestVisitor : ClassVisitor(Opcodes.ASM9) {

    private val elements = sortedMapOf<String, MutableList<String>>()

    /** The canonical text; empty when the class carries no annotation at all. */
    fun canonical(): String = elements.entries.joinToString("") { (key, records) ->
        frame(key) + frame(records.joinToString(""))
    }

    /** Length-prefixed, so no attribute value can forge a boundary. */
    private fun frame(part: String): String = "${part.length}:$part"

    private fun record(element: String, vararg head: Any?): Values {
        val parts = StringBuilder()
        head.forEach { parts.append(frame(describe(it))) }
        return Values(parts) { elements.getOrPut(element) { mutableListOf() } += frame(parts.toString()) }
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is TypePath -> value.toString()
        is Type -> "T:" + value.descriptor
        is BooleanArray -> value.joinToString(",", "Z[", "]")
        is ByteArray -> value.joinToString(",", "B[", "]")
        is CharArray -> value.joinToString(",", "C[", "]") { it.code.toString() }
        is ShortArray -> value.joinToString(",", "S[", "]")
        is IntArray -> value.joinToString(",", "I[", "]")
        is LongArray -> value.joinToString(",", "J[", "]")
        is FloatArray -> value.joinToString(",", "F[", "]")
        is DoubleArray -> value.joinToString(",", "D[", "]")
        // The type is part of it: `int 1` and "1" must not agree.
        else -> "${value.javaClass.name}:$value"
    }

    /** One annotation's attribute values in order, nested annotations and arrays bracketed. */
    private inner class Values(
        private val out: StringBuilder,
        private val done: () -> Unit,
    ) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) {
            out.append(frame("value")).append(frame(describe(name))).append(frame(describe(value)))
        }

        override fun visitEnum(name: String?, descriptor: String?, value: String?) {
            out.append(frame("enum")).append(frame(describe(name)))
                .append(frame(describe(descriptor))).append(frame(describe(value)))
        }

        override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor {
            out.append(frame("nested")).append(frame(describe(name))).append(frame(describe(descriptor)))
            return Values(out) { out.append(frame("end")) }
        }

        override fun visitArray(name: String?): AnnotationVisitor {
            out.append(frame("array")).append(frame(describe(name)))
            return Values(out) { out.append(frame("end")) }
        }

        override fun visitEnd() = done()
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? =
        // Kotlin's copy of the SMAP, line numbers and all: it moves with every body edit in a class
        // that calls an inline function, no framework decides anything on it, and the class digest
        // and the inline scan already read the SMAP.
        if (descriptor == KOTLIN_SMAP) null else record("class", "annotation", descriptor, visible)

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor = record("class", "typeannotation", typeRef, typePath, descriptor, visible)

    override fun visitRecordComponent(
        name: String?,
        descriptor: String?,
        signature: String?,
    ): RecordComponentVisitor {
        val element = "record $name $descriptor"
        return object : RecordComponentVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor =
                record(element, "annotation", descriptor, visible)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String?,
                visible: Boolean,
            ): AnnotationVisitor = record(element, "typeannotation", typeRef, typePath, descriptor, visible)
        }
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ): FieldVisitor {
        val element = "field $name $descriptor"
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor =
                record(element, "annotation", descriptor, visible)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String?,
                visible: Boolean,
            ): AnnotationVisitor = record(element, "typeannotation", typeRef, typePath, descriptor, visible)
        }
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val element = "method $name$descriptor"
        return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitAnnotationDefault(): AnnotationVisitor = record(element, "default")

            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor =
                record(element, "annotation", descriptor, visible)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String?,
                visible: Boolean,
            ): AnnotationVisitor = record(element, "typeannotation", typeRef, typePath, descriptor, visible)

            override fun visitParameterAnnotation(
                parameter: Int,
                descriptor: String?,
                visible: Boolean,
            ): AnnotationVisitor = record(element, "parameter", parameter, descriptor, visible)
        }
    }

    private companion object {
        const val KOTLIN_SMAP = "Lkotlin/jvm/internal/SourceDebugExtension;"
    }
}
