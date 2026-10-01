package io.github.zeuspizza.yoriwake.gradle.bytecode

import java.security.MessageDigest
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.TypePath

/**
 * Feeds one class file into a [MessageDigest], visit by visit. Constructed only by
 * `Recordability.classDigest`, which documents what belongs in a digest.
 *
 * Every callback the reader emits is fed or explicitly suppressed with a reason: one not
 * overridden contributes nothing, silently, and two different classes digest the same.
 * `visitFrame` is absent because the reader is given `SKIP_FRAMES`.
 *
 * Labels are fed by first-seen order, never by identity, whose `toString` names a heap address.
 */
internal class DigestVisitor(private val sink: MessageDigest) : ClassVisitor(Opcodes.ASM9) {

    private val labels = HashMap<Label, Int>()

    /**
     * One record: every part length-prefixed, then a record separator. No separator character is
     * safe, since a String constant can contain any of them.
     */
    private fun feed(vararg parts: Any?) {
        for (part in parts) {
            val bytes = describe(part).toByteArray(Charsets.UTF_8)
            // One reused buffer, because this runs per instruction. Safe: `MessageDigest` reads it
            // synchronously, and one visitor digests one class on one thread.
            length[0] = (bytes.size ushr 24).toByte()
            length[1] = (bytes.size ushr 16).toByte()
            length[2] = (bytes.size ushr 8).toByte()
            length[3] = bytes.size.toByte()
            sink.update(length)
            sink.update(bytes)
        }
        sink.update(RECORD)
    }

    /** See [feed]. Not shared between instances: one visitor digests one class. */
    private val length = ByteArray(4)

    /** The order this label was first mentioned in, assigned on first sight. */
    private fun id(label: Label?): String =
        if (label == null) "null" else "L" + labels.getOrPut(label) { labels.size }

    private fun ids(labels: Array<out Label>?): String =
        labels?.joinToString(",") { id(it) } ?: "null"

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        // The type is part of the description: boxed `int 1`, `long 1` and "1" otherwise render
        // alike, and a change of declared type recompiles every consumer.
        is Array<*> -> value.joinToString(",") { describe(it) }
        is IntArray -> value.joinToString(",")
        is Handle -> "H(${value.tag},${value.owner},${value.name},${value.desc},${value.isInterface})"
        is ConstantDynamic -> "CD(${value.name},${value.descriptor}," +
            "${describe(value.bootstrapMethod)}," +
            (0 until value.bootstrapMethodArgumentCount)
                .joinToString(",") { describe(value.getBootstrapMethodArgument(it)) } + ")"
        is TypePath -> value.toString()
        else -> "${value.javaClass.name}:$value"
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) = feed("class", version, access, name, signature, superName, interfaces)

    /**
     * `debug` is the SMAP, the only place a copied Kotlin `inline` body shows up, which is why the
     * reader does not set `SKIP_DEBUG`.
     */
    override fun visitSource(source: String?, debug: String?) = feed("source", source, debug)

    override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) =
        feed("outer", owner, name, descriptor)

    override fun visitNestHost(nestHost: String?) = feed("nesthost", nestHost)

    override fun visitNestMember(nestMember: String?) = feed("nestmember", nestMember)

    override fun visitPermittedSubclass(permittedSubclass: String?) =
        feed("permits", permittedSubclass)

    override fun visitInnerClass(name: String?, outerName: String?, innerName: String?, access: Int) =
        feed("inner", name, outerName, innerName, access)

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        feed("annotation", descriptor, visible)
        return Values()
    }

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor {
        feed("typeannotation", typeRef, typePath, descriptor, visible)
        return Values()
    }

    /** An unknown attribute, by name only: ASM does not expose its content to a visitor. */
    override fun visitAttribute(attribute: Attribute?) = feed("attribute", attribute?.type)

    override fun visitRecordComponent(
        name: String?,
        descriptor: String?,
        signature: String?,
    ): RecordComponentVisitor {
        feed("record", name, descriptor, signature)
        return Component()
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ): FieldVisitor {
        feed("field", access, name, descriptor, signature, value)
        return Field()
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        feed("method", access, name, descriptor, signature, exceptions)
        return Code()
    }

    /** A `module-info` header only; the descriptor declares no code a test outcome depends on. */
    override fun visitModule(name: String?, access: Int, version: String?): org.objectweb.asm.ModuleVisitor? {
        feed("module", name, access, version)
        return null
    }

    private inner class Values : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) = feed("value", name, value)

        override fun visitEnum(name: String?, descriptor: String?, value: String?) =
            feed("enum", name, descriptor, value)

        override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor {
            feed("nested", name, descriptor)
            return this
        }

        override fun visitArray(name: String?): AnnotationVisitor {
            feed("array", name)
            return this
        }
    }

    private inner class Field : FieldVisitor(Opcodes.ASM9) {
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
            feed("field.annotation", descriptor, visible)
            return Values()
        }

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor {
            feed("field.typeannotation", typeRef, typePath, descriptor, visible)
            return Values()
        }

        override fun visitAttribute(attribute: Attribute?) = feed("field.attribute", attribute?.type)
    }

    private inner class Component : RecordComponentVisitor(Opcodes.ASM9) {
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
            feed("record.annotation", descriptor, visible)
            return Values()
        }

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor {
            feed("record.typeannotation", typeRef, typePath, descriptor, visible)
            return Values()
        }

        override fun visitAttribute(attribute: Attribute?) = feed("record.attribute", attribute?.type)
    }

    private inner class Code : MethodVisitor(Opcodes.ASM9) {
        override fun visitParameter(name: String?, access: Int) = feed("parameter", name, access)

        override fun visitAnnotationDefault(): AnnotationVisitor {
            feed("default")
            return Values()
        }

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
            feed("method.annotation", descriptor, visible)
            return Values()
        }

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor {
            feed("method.typeannotation", typeRef, typePath, descriptor, visible)
            return Values()
        }

        override fun visitAnnotableParameterCount(parameterCount: Int, visible: Boolean) =
            feed("paramcount", parameterCount, visible)

        override fun visitParameterAnnotation(
            parameter: Int,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor {
            feed("param.annotation", parameter, descriptor, visible)
            return Values()
        }

        override fun visitAttribute(attribute: Attribute?) = feed("method.attribute", attribute?.type)

        override fun visitCode() = feed("code")

        override fun visitInsn(opcode: Int) = feed("insn", opcode)

        override fun visitIntInsn(opcode: Int, operand: Int) = feed("int", opcode, operand)

        override fun visitVarInsn(opcode: Int, varIndex: Int) = feed("var", opcode, varIndex)

        override fun visitTypeInsn(opcode: Int, type: String?) = feed("type", opcode, type)

        override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) =
            feed("fieldinsn", opcode, owner, name, descriptor)

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) = feed("methodinsn", opcode, owner, name, descriptor, isInterface)

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?,
        ) = feed("indy", name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)

        override fun visitJumpInsn(opcode: Int, label: Label?) = feed("jump", opcode, id(label))

        override fun visitLabel(label: Label?) = feed("label", id(label))

        override fun visitLdcInsn(value: Any?) = feed("ldc", value)

        override fun visitIincInsn(varIndex: Int, increment: Int) = feed("iinc", varIndex, increment)

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) =
            feed("tableswitch", min, max, id(dflt),
                 labels.joinToString(",") { id(it) })

        override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) =
            feed("lookupswitch", id(dflt), keys, ids(labels))

        override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) =
            feed("multianewarray", descriptor, numDimensions)

        override fun visitInsnAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor {
            feed("insn.annotation", typeRef, typePath, descriptor, visible)
            return Values()
        }

        override fun visitTryCatchBlock(
            start: Label?,
            end: Label?,
            handler: Label?,
            type: String?,
        ) = feed("trycatch", id(start), id(end), id(handler), type)

        override fun visitTryCatchAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor {
            feed("trycatch.annotation", typeRef, typePath, descriptor, visible)
            return Values()
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) = feed("maxs", maxStack, maxLocals)

        /** Suppressed: a comment-only edit shifts lines and changes nothing a consumer observes. */
        override fun visitLineNumber(line: Int, start: Label?) = Unit

        /** Suppressed: local variable names and their ranges. Renaming a local changes no caller. */
        override fun visitLocalVariable(
            name: String?,
            descriptor: String?,
            signature: String?,
            start: Label?,
            end: Label?,
            index: Int,
        ) = Unit

        /** Suppressed: addressed by the local variable ranges [visitLocalVariable] suppresses. */
        override fun visitLocalVariableAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            start: Array<out Label>?,
            end: Array<out Label>?,
            index: IntArray?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? = null
    }

    private companion object {
        /** Closes a record, so a record ending in an empty part is still a distinct record. */
        val RECORD = byteArrayOf(0x1e)
    }
}
