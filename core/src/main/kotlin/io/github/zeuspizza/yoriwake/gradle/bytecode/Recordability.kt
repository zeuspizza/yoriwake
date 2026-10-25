package io.github.zeuspizza.yoriwake.gradle.bytecode

import java.security.MessageDigest
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Whether a probe would have fired had a test depended on a class, decided from its bytecode.
 * No for classes without code, with compile-time constants (JLS 13.1), in Kotlin (`inline` bodies
 * are copied into call sites), or that cannot be read.
 */
internal object Recordability {

    /** Why a class cannot support reasoning from absence, or null when it can. */
    data class Verdict(val recordable: Boolean, val reason: String?) {
        companion object {
            val YES = Verdict(true, null)
            fun no(reason: String) = Verdict(false, reason)
        }
    }

    private const val KOTLIN_METADATA = "Lkotlin/Metadata;"

    /** Reads one compiled class; any failure is a "no". */
    fun of(classBytes: ByteArray): Verdict = runCatching {
        val visitor = Inspector()
        ClassReader(classBytes).accept(visitor, ClassReader.SKIP_FRAMES)
        when {
            visitor.kotlin -> Verdict.no("it is Kotlin, whose inline members are copied into their call sites")
            visitor.constants -> Verdict.no("it declares a compile-time constant, which is inlined into every consumer")
            !visitor.executable -> Verdict.no("it carries no executable bytecode, so it has no probe to fire")
            else -> Verdict.YES
        }
    }.getOrElse { Verdict.no("its bytecode could not be read: $it") }

    /**
     * Whether this class declares a `ConstantValue`. Separate from [of]: a holder with records
     * would otherwise narrow to them, missing consumers that baked its value in.
     */
    fun declaresConstant(classBytes: ByteArray): Boolean = runCatching {
        val visitor = Inspector()
        ClassReader(classBytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        visitor.constants
    }.getOrElse { false }

    /**
     * Whether a constant could reach a consumer the change set does not cover: it is not private,
     * or its value is written into a field a cached object can carry out. `serialVersionUID` is
     * exempt by name. A value match, since javac folds constants inside their own class too.
     * Not sound: blind to a value folded into arithmetic or returned rather than stored.
     *
     * The instruction walk answers true on failure, so code that could not be walked forces.
     */
    fun declaresEscapableConstant(classBytes: ByteArray): Boolean {
        val declared = runCatching {
            val visitor = Inspector()
            ClassReader(classBytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
            visitor.declared.toList()
        }.getOrElse { return false }

        if (declared.any { !it.private }) {
            return true
        }
        // An empty set means there is nothing to look for, so the code walk is skipped.
        val carriable = declared.filterNot { it.isSerialVersionUid }.mapTo(mutableSetOf()) { it.value }
        if (carriable.isEmpty()) {
            return false
        }
        return runCatching {
            val writes = FieldWrites(carriable)
            ClassReader(classBytes).accept(writes, ClassReader.SKIP_FRAMES)
            writes.escaped
        }.getOrElse { true }
    }

    /** A stable digest of this class's constants, or null when it declares none. */
    fun constantDigest(classBytes: ByteArray): String? = runCatching {
        val visitor = Inspector()
        ClassReader(classBytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        if (!visitor.constants) null else hash(visitor.constantValues.sorted().joinToString(";"))
    }.getOrNull()

    /** A class's digest, or why it has none. Exactly one of the two is non-null. */
    data class Digest(val value: String?, val reason: String?) {
        companion object {
            fun of(value: String) = Digest(value, null)

            /** Never a zero or empty digest: two unreadable classes would then compare equal. */
            fun refused(reason: String) = Digest(null, reason)
        }
    }

    /**
     * A digest of the class file minus line numbers and local variables, so a map can record
     * bytecode changes `git diff` of a consumer cannot see. Never a `ClassWriter` round trip: ASM
     * drops unknown attributes, which could make two different classes collide.
     */
    fun classDigest(classBytes: ByteArray): Digest = runCatching {
        val sink = MessageDigest.getInstance(ALGORITHM)
        // Not SKIP_DEBUG: it would suppress `visitSource` and take the SMAP with it.
        ClassReader(classBytes).accept(DigestVisitor(sink), ClassReader.SKIP_FRAMES)
        Digest.of(hex(sink.digest()))
    }.getOrElse { Digest.refused("its bytecode could not be read: $it") }

    /**
     * A digest of every annotation the class declares, with its values, and nothing else: what a
     * framework's scan or reflective read can see without executing the class. A body-only edit
     * leaves it alone.
     */
    fun annotationDigest(classBytes: ByteArray): Digest = runCatching {
        val visitor = AnnotationDigestVisitor()
        ClassReader(classBytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        Digest.of(hash(visitor.canonical()))
    }.getOrElse { Digest.refused("its bytecode could not be read: $it") }

    /**
     * The descriptors of the annotations on the class itself: runtime-visible or not, declaration
     * or type use. Null when the bytes cannot be read. `kotlin.Metadata` is left out: kotlinc
     * writes it on every Kotlin class, so counting it would be a second Kotlin rule, and the
     * Kotlin rule in [of] already decides Kotlin.
     */
    fun classAnnotations(classBytes: ByteArray): List<String>? = runCatching {
        val found = mutableListOf<String>()
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String,
                visible: Boolean,
            ): org.objectweb.asm.AnnotationVisitor? {
                if (descriptor != KOTLIN_METADATA) found += descriptor
                return null
            }

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: org.objectweb.asm.TypePath?,
                descriptor: String,
                visible: Boolean,
            ): org.objectweb.asm.AnnotationVisitor? {
                found += descriptor
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        found.toList()
    }.getOrNull()

    /**
     * Whether this class declares a method a JUnit or TestNG engine runs as a test. Only the
     * engines' own annotations count: a meta-annotation, another engine or unreadable bytes answer
     * false.
     */
    fun declaresTestMethod(classBytes: ByteArray): Boolean = runCatching {
        var found = false
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): org.objectweb.asm.AnnotationVisitor? {
                    if (descriptor in TEST_ANNOTATIONS) found = true
                    return null
                }
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        found
    }.getOrElse { false }

    private val TEST_ANNOTATIONS = setOf(
        "Lorg/junit/jupiter/api/Test;",
        "Lorg/junit/jupiter/api/RepeatedTest;",
        "Lorg/junit/jupiter/api/TestFactory;",
        "Lorg/junit/jupiter/api/TestTemplate;",
        "Lorg/junit/jupiter/params/ParameterizedTest;",
        "Lorg/junit/Test;",
        "Lorg/testng/annotations/Test;",
    )

    /** [annotationDigest] of a class that declares no annotation anywhere. */
    val NO_ANNOTATIONS: String by lazy { hash("") }

    private const val ALGORITHM = "SHA-256"

    /** Hex SHA-256, so a digest can contain neither a separator nor a line break. */
    private fun hash(joined: String): String =
        hex(MessageDigest.getInstance(ALGORITHM).digest(joined.toByteArray(Charsets.UTF_8)))

    /** The one hex encoding, so [hash] and [classDigest] cannot drift into two digest formats. */
    private fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }

    /** One declared compile-time constant; [value] is never null (it is the `ConstantValue`). */
    private data class Constant(
        val name: String,
        val descriptor: String,
        val value: Any,
        val private: Boolean,
        val static: Boolean,
        val final: Boolean,
    ) {
        /** Matched in full so the carve-out stays narrow. */
        val isSerialVersionUid: Boolean
            get() = private && static && final &&
                name == SERIAL_VERSION_UID && descriptor == LONG_DESCRIPTOR
    }

    private const val SERIAL_VERSION_UID = "serialVersionUID"
    private const val LONG_DESCRIPTOR = "J"

    /**
     * Whether any method writes one of these values into a field. Every constant-load family is
     * tracked, not just `LDC`; boxed values also match on type (`int` 1 is not `long` 1).
     */
    private class FieldWrites(private val values: Set<Any>) : ClassVisitor(Opcodes.ASM9) {
        var escaped = false

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
            private var pushed: Any? = null

            override fun visitLdcInsn(value: Any?) {
                pushed = value
            }

            override fun visitIntInsn(opcode: Int, operand: Int) {
                pushed = if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) operand else null
            }

            override fun visitInsn(opcode: Int) {
                pushed = when (opcode) {
                    Opcodes.ICONST_M1 -> -1
                    Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                    Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> opcode - Opcodes.ICONST_0
                    Opcodes.LCONST_0, Opcodes.LCONST_1 -> (opcode - Opcodes.LCONST_0).toLong()
                    Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 ->
                        (opcode - Opcodes.FCONST_0).toFloat()
                    Opcodes.DCONST_0, Opcodes.DCONST_1 -> (opcode - Opcodes.DCONST_0).toDouble()
                    else -> null
                }
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                // PUTSTATIC too: a static field outlives everything and is just as reachable.
                if ((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) && pushed in values) {
                    escaped = true
                }
                pushed = null
            }

            override fun visitVarInsn(opcode: Int, varIndex: Int) {
                pushed = null
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                pushed = null
            }

            // A call hands the value somewhere rather than losing it: autoboxing, a constructor
            // argument or a factory all pass through here.
            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                // Deliberately NOT cleared.
            }

            override fun visitInvokeDynamicInsn(
                name: String,
                descriptor: String,
                handle: org.objectweb.asm.Handle,
                vararg arguments: Any?,
            ) {
                pushed = null
            }

            // Nor does control flow: `flag ? LIMIT : 0` is `sipush; goto; label; putfield`.
            override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {
                // Deliberately NOT cleared.
            }

            override fun visitLabel(label: org.objectweb.asm.Label) {
                // Deliberately NOT cleared.
            }

            override fun visitIincInsn(varIndex: Int, increment: Int) {
                pushed = null
            }

            override fun visitTableSwitchInsn(
                min: Int,
                max: Int,
                dflt: org.objectweb.asm.Label,
                vararg labels: org.objectweb.asm.Label,
            ) {
                pushed = null
            }

            override fun visitLookupSwitchInsn(
                dflt: org.objectweb.asm.Label,
                keys: IntArray,
                labels: Array<out org.objectweb.asm.Label>,
            ) {
                pushed = null
            }

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                pushed = null
            }
        }
    }

    private class Inspector : ClassVisitor(Opcodes.ASM9) {
        var executable = false
        var constants = false

        val declared = mutableListOf<Constant>()
        var kotlin = false
        val constantValues = mutableListOf<String>()

        override fun visitAnnotation(descriptor: String, visible: Boolean): org.objectweb.asm.AnnotationVisitor? {
            if (descriptor == KOTLIN_METADATA) {
                kotlin = true
            }
            return null
        }

        /** A non-null `value` is the `ConstantValue` attribute, which is what inlining runs on. */
        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            if (value != null) {
                constants = true
                // Collected, not judged: this SKIP_CODE read cannot see instructions.
                declared += Constant(
                    name = name,
                    descriptor = descriptor,
                    value = value,
                    private = access and Opcodes.ACC_PRIVATE != 0,
                    static = access and Opcodes.ACC_STATIC != 0,
                    final = access and Opcodes.ACC_FINAL != 0,
                )
                // The descriptor too: `int 1`, `long 1` and "1" all render as `1`.
                constantValues += "$name:$descriptor=$value"
            }
            return null
        }

        /** Any method with a body, constructors and `<clinit>` included. */
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            val bodyless = access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0
            if (!bodyless) {
                executable = true
            }
            return null
        }
    }
}
