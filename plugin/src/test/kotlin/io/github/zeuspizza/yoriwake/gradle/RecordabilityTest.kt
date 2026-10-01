package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Reads the test's own compiled class files, so the input is real javac/kotlinc output.
class RecordabilityTest {

    private fun bytesOf(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")) {
            "no class file for $name"
        }.use { it.readBytes() }

    private fun verdict(name: String) = Recordability.of(bytesOf(name))

    @Test
    fun `a Java class with a real method is recordable`() {
        assertTrue(verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.HasLogic").recordable)
    }

    @Test
    fun `an enum whose constructor runs code is recordable`() {
        // Touching any constant runs <clinit>, so a probe fires the moment anything reaches it.
        assertTrue(verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.Season").recordable)
    }

    @Test
    fun `a bare interface has no probe to fire`() {
        val verdict = verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.Marker")

        assertFalse(verdict.recordable)
        assertTrue(verdict.reason!!.contains("no executable bytecode"))
    }

    @Test
    fun `an annotation type has no probe to fire`() {
        assertFalse(verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.Tagged").recordable)
    }

    @Test
    fun `a constant holder is refused however much else it contains`() {
        // The constant is copied into consumers whose sources are not in the change set, so
        // absence of a probe cannot rule out a change; other ordinary methods do not help.
        val verdict = verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.HasConstant")

        assertFalse(verdict.recordable)
        assertTrue(verdict.reason!!.contains("compile-time constant"))
    }

    @Test
    fun `a private constant cannot escape its own source file`() {
        // javac copies a constant only into code that can reference it; for a private field that is
        // only nested classes of the same source, which the changed path's prefix already matches.
        val bytes = bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasPrivateConstant")

        assertTrue(Recordability.declaresConstant(bytes), "it does declare one")
        assertFalse(
            Recordability.declaresEscapableConstant(bytes),
            "a private constant has no consumer outside its own source file",
        )
    }

    // A private constant can still escape its class through a field write; the tests below pin
    // which routes the rule sees and which are known limits.

    @Test
    fun `a private constant cached into a field escapes at runtime, and now forces`() {
        // A builder copies the constant into a field and a static initialiser caches one instance,
        // so later tests read the value without executing the class that declares it.
        val bytes = bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasCachedPrivateConstant")

        assertTrue(Recordability.declaresConstant(bytes), "it does declare one")
        assertTrue(
            Recordability.declaresEscapableConstant(bytes),
            "the class writes the constant's value into a field, so the value can outlive the method",
        )
    }

    @Test
    fun `a private constant reaching a field through a call escapes, and forces`() {
        // Autoboxing, a constructor argument and a conditional initialiser all put an instruction
        // between the constant load and the field write; none of them may hide the escape.
        val bytes = bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasBoxedPrivateConstant")

        assertTrue(Recordability.declaresConstant(bytes))
        assertTrue(
            Recordability.declaresEscapableConstant(bytes),
            "the value reaches a field through a call, so it can outlive this class",
        )
    }

    @Test
    fun `a private constant that escapes only by return is exempted -- a KNOWN LIMIT of the rule`() {
        // A caller can keep a returned value indefinitely, a route no field-write rule can observe.
        // Pinned so the limit is stated rather than discovered.
        val bytes = bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasReturnedPrivateConstant")

        assertTrue(Recordability.declaresConstant(bytes))
        assertFalse(
            Recordability.declaresEscapableConstant(bytes),
            "A SHIPPED LIMIT: the value leaves by return, and the rule can only see a field write",
        )
    }

    @Test
    fun `serialVersionUID beside a field written with the same literal does not force`() {
        // A rule matching a constant's value against a field write's operand cannot tell
        // `counter = 1L` from the serial's value, so serialVersionUID must be carved out by name.
        val bytes = bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasSerialAndSameValueField")

        assertTrue(Recordability.declaresConstant(bytes))
        assertFalse(
            Recordability.declaresEscapableConstant(bytes),
            "serialVersionUID is private by convention and forced all 698 of armeria's tests",
        )
    }

    @Test
    fun `the cached constant reaches its field write as a folded literal, so GETSTATIC finds nothing`() {
        // javac folds a compile-time constant inside its own class too, so the rule has no field
        // read to key on -- only the loaded literal reaching a `putfield`.
        val loads = mutableListOf<Any?>()
        var getstatic = 0
        var putfields = 0
        org.objectweb.asm.ClassReader(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasCachedPrivateConstant"))
            .accept(
                object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ) = object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        override fun visitFieldInsn(op: Int, owner: String, fn: String, fd: String) {
                            if (op == org.objectweb.asm.Opcodes.GETSTATIC) getstatic++
                            if (op == org.objectweb.asm.Opcodes.PUTFIELD && fn == "max") putfields++
                        }

                        override fun visitIntInsn(op: Int, operand: Int) {
                            loads += operand
                        }

                        override fun visitLdcInsn(value: Any?) {
                            loads += value
                        }
                    }
                },
                0,
            )

        assertEquals(0, getstatic, "the constant was folded; there is no field read to key on")
        assertTrue(putfields > 0, "the value does reach a field write")
        assertTrue(128 in loads, "and it reaches it as the literal 128, via SIPUSH rather than LDC")
    }

    private fun holder(value: Any, descriptor: String, body: (org.objectweb.asm.MethodVisitor) -> Unit):
        ByteArray {
        val writer = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            "com/acme/Holder", null, "java/lang/Object", null,
        )
        writer.visitField(
            org.objectweb.asm.Opcodes.ACC_PRIVATE or org.objectweb.asm.Opcodes.ACC_STATIC or
                org.objectweb.asm.Opcodes.ACC_FINAL,
            "SIZE", descriptor, null, value,
        ).visitEnd()
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "copy", descriptor, null, null)
            .visitEnd()
        writer.visitField(
            org.objectweb.asm.Opcodes.ACC_PRIVATE or org.objectweb.asm.Opcodes.ACC_STATIC,
            "shared", descriptor, null, null,
        ).visitEnd()
        val method = writer.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC, "fill", "()V", null, null,
        )
        method.visitCode()
        body(method)
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a constant loaded by ICONST is detected, so the check does not depend on LDC`() {
        // javac emits ICONST_* for 0..5 and BIPUSH/SIPUSH below 32,768, so a rule keyed on LDC alone
        // would walk straight past every small int -- which is most of them.
        val bytes = holder(1, "I") { m ->
            m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            m.visitInsn(org.objectweb.asm.Opcodes.ICONST_1)
            m.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, "com/acme/Holder", "copy", "I")
        }

        assertTrue(Recordability.declaresEscapableConstant(bytes))
    }

    @Test
    fun `a String constant loaded by LDC is detected`() {
        // Most real constants (a String, a long, any int at or above 32,768) arrive as LDC; a
        // regression here exempts a class that should force.
        val bytes = holder("fixed", "Ljava/lang/String;") { m ->
            m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            m.visitLdcInsn("fixed")
            m.visitFieldInsn(
                org.objectweb.asm.Opcodes.PUTFIELD, "com/acme/Holder", "copy", "Ljava/lang/String;",
            )
        }

        assertTrue(Recordability.declaresEscapableConstant(bytes))
    }

    @Test
    fun `a constant written into a STATIC field is detected`() {
        // A static field the value was copied into is as reachable by a cached reader as an
        // instance one.
        val bytes = holder(70000, "I") { m ->
            m.visitLdcInsn(70000)
            m.visitFieldInsn(org.objectweb.asm.Opcodes.PUTSTATIC, "com/acme/Holder", "shared", "I")
        }

        assertTrue(Recordability.declaresEscapableConstant(bytes))
    }

    @Test
    fun `a value that never reaches a field does not force, however often it is loaded`() {
        val bytes = holder(1, "I") { m ->
            m.visitInsn(org.objectweb.asm.Opcodes.ICONST_1)
            m.visitInsn(org.objectweb.asm.Opcodes.POP)
        }

        assertFalse(Recordability.declaresEscapableConstant(bytes))
    }

    @Test
    fun `a call between the load and the write does NOT hide the escape`() {
        // A call does not lose the value; it hands it to something (Integer.valueOf, a constructor,
        // a factory) that can store it.
        val bytes = holder(1, "I") { m ->
            m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            m.visitInsn(org.objectweb.asm.Opcodes.ICONST_1)
            m.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false,
            )
            m.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, "com/acme/Holder", "copy", "I")
        }

        assertTrue(Recordability.declaresEscapableConstant(bytes))
    }

    @Test
    fun `control flow between the load and the write does NOT hide the escape`() {
        // `this.limit = flag ? LIMIT : 0` compiles to `sipush; goto; label; putfield`. Only the
        // control-flow route is exercised, so this test fails if a jump or label clears the operand.
        val target = org.objectweb.asm.Label()
        val bytes = holder(1, "I") { m ->
            m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            m.visitInsn(org.objectweb.asm.Opcodes.ICONST_1)
            m.visitJumpInsn(org.objectweb.asm.Opcodes.GOTO, target)
            m.visitLabel(target)
            m.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, "com/acme/Holder", "copy", "I")
        }

        assertTrue(Recordability.declaresEscapableConstant(bytes))
    }

    @Test
    fun `an arithmetic op between the load and the write is a STATED blind spot`() {
        // Once combined with something else, what reaches the field is no longer the constant.
        // Pinned as a known limit rather than left silent.
        val bytes = holder(1, "I") { m ->
            m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            m.visitInsn(org.objectweb.asm.Opcodes.ICONST_1)
            m.visitInsn(org.objectweb.asm.Opcodes.ICONST_2)
            m.visitInsn(org.objectweb.asm.Opcodes.IADD)
            m.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, "com/acme/Holder", "copy", "I")
        }

        assertFalse(
            Recordability.declaresEscapableConstant(bytes),
            "a value folded into a larger expression is a published limit of this rule",
        )
    }

    @Test
    fun `bytecode whose instructions cannot be read forces rather than exempting`() {
        // The instruction walk has more ways to fail than the declaration read, and failing must
        // force. The class writes no field, so only the failure path can answer true.
        val sound = holder(123, "I") { m ->
            m.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, 123)
            m.visitInsn(org.objectweb.asm.Opcodes.POP)
        }
        assertTrue(Recordability.declaresConstant(sound))
        assertFalse(Recordability.declaresEscapableConstant(sound), "intact, and nothing escapes")

        // BIPUSH 123, POP -> 0x10 0x7B 0x57. The POP becomes 0xFF, which is not an opcode, so the
        // instruction walk throws while the constant-pool and field reads are untouched.
        val broken = sound.copyOf()
        val at = (0 until broken.size - 2).first { i ->
            broken[i] == 0x10.toByte() && broken[i + 1] == 0x7B.toByte() &&
                broken[i + 2] == 0x57.toByte()
        }
        broken[at + 2] = 0xFF.toByte()

        assertTrue(Recordability.declaresConstant(broken), "the declaration read still succeeds")
        assertTrue(
            Recordability.declaresEscapableConstant(broken),
            "a class whose code could not be walked must force, not be exempted",
        )
    }

    @Test
    fun `a public constant escapes, and one private sibling does not excuse it`() {
        assertTrue(
            Recordability.declaresEscapableConstant(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasConstant")),
        )
        // The digest covers the whole class, so it cannot tell which constant moved; guessing would
        // risk a silently skipped test.
        assertTrue(
            Recordability.declaresEscapableConstant(
                bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasMixedConstants")
            ),
        )
    }

    @Test
    fun `bytecode that cannot be read declares no escapable constant, and is never asked alone`() {
        // The unsafe direction, stated: callers reach this only after `declaresConstant` said yes on
        // the same bytes, which rubbish cannot.
        val rubbish = byteArrayOf(1, 2, 3, 4)

        assertFalse(Recordability.declaresConstant(rubbish))
        assertFalse(Recordability.declaresEscapableConstant(rubbish))
    }

    @Test
    fun `a static final field that is not a compile-time constant does not disqualify a class`() {
        // Initialised in <clinit> from something other than a literal, so it carries no
        // ConstantValue attribute and is read from the holder at runtime like any other field.
        assertTrue(verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.HasComputedField").recordable)
    }

    @Test
    fun `a Kotlin class is refused, because inline members are copied into their call sites`() {
        val verdict = Recordability.of(bytesOf("io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability"))

        assertFalse(verdict.recordable)
        assertTrue(verdict.reason!!.contains("Kotlin"))
    }

    // Every Kotlin class is refused, though `OrdinaryKotlin` has no inline member or constant. When
    // Kotlin metadata is parsed, only `OrdinaryKotlin` may become recordable here.
    @Test
    fun `every Kotlin shape is refused today, including the one that should not be`() {
        val ordinary = verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.OrdinaryKotlin")
        assertFalse(ordinary.recordable)
        assertTrue(ordinary.reason!!.contains("Kotlin"))

        // Must stay refused: inline bodies and constants are copied into consumers outside the
        // change set.
        assertFalse(verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapesKt").recordable)
        assertFalse(verdict("io.github.zeuspizza.yoriwake.gradle.fixtures.MixedKotlin").recordable)
    }

    @Test
    fun `bytecode that cannot be read is refused rather than assumed`() {
        val verdict = Recordability.of(byteArrayOf(1, 2, 3))

        assertFalse(verdict.recordable)
    }

    // Built with ASM: only raw bytecode can vary a ConstantValue's declared type alone.
    private fun withConstant(className: String, name: String, descriptor: String, value: Any): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC,
            className.replace('.', '/'),
            null,
            "java/lang/Object",
            null,
        )
        writer.visitField(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC or
                org.objectweb.asm.Opcodes.ACC_FINAL,
            name,
            descriptor,
            null,
            value,
        ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `two constants that differ only in declared type get different digests`() {
        // `int 1`, `long 1`, `char 1` and "1" all box to `1`, so a digest of name and value alone
        // reads a type change as no change -- and a match licenses skipping.
        val asInt = Recordability.constantDigest(withConstant("acme.C", "N", "I", 1))
        val asString = Recordability.constantDigest(withConstant("acme.C", "N", "Ljava/lang/String;", "1"))
        val asLong = Recordability.constantDigest(withConstant("acme.C", "N", "J", 1L))
        val asChar = Recordability.constantDigest(withConstant("acme.C", "N", "C", 1))

        assertNotNull(asInt)
        assertEquals(4, setOf(asInt, asString, asLong, asChar).size,
            "four declared types rendered as one digest, so a type change reads as no change")
    }

    @Test
    fun `a value change is still a digest change`() {
        assertNotEquals(
            Recordability.constantDigest(withConstant("acme.C", "N", "I", 1)),
            Recordability.constantDigest(withConstant("acme.C", "N", "I", 2)),
        )
    }

    @Test
    fun `a digest carries neither a separator nor a line break, whatever the constant holds`() {
        // The digest lands in a tab-delimited file and joins entries with `;` and `=`; a constant
        // holding those must not tear the record or make two constant sets collide.
        val nasty = Recordability.constantDigest(
            withConstant("acme.C", "N", "Ljava/lang/String;", "a\tb\nc=d;e")
        )

        assertNotNull(nasty)
        assertTrue(nasty.matches(Regex("[0-9a-f]{64}")), "not a hex digest: $nasty")
    }

    @Test
    fun `a class declaring no constant still has no digest`() {
        // Null must keep meaning "there is nothing to compare", never "it hashed to nothing".
        assertNull(Recordability.constantDigest(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasLogic")))
    }

    // Compiled with real javac: only a compiler produces the LineNumberTable these tests vary.
    private fun javac(source: String, className: String): ByteArray {
        val compiler = requireNotNull(javax.tools.ToolProvider.getSystemJavaCompiler()) {
            "no system Java compiler; this test needs a JDK, not a JRE"
        }
        val directory = java.nio.file.Files.createTempDirectory("yoriwake-digest")
        try {
            val file = directory.resolve("$className.java")
            java.nio.file.Files.writeString(file, source)
            val errors = java.io.StringWriter()
            val code = compiler.run(null, null, java.io.ByteArrayOutputStream(), file.toString())
            check(code == 0) { "javac refused the fixture: $errors" }
            return java.nio.file.Files.readAllBytes(directory.resolve("$className.class"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun digestOf(source: String, className: String = "Subject"): String {
        val digest = Recordability.classDigest(javac(source, className))
        assertNotNull(digest.value, "refused: ${digest.reason}")
        return digest.value
    }

    private val subject = """
        public class Subject {
            public static final int LIMIT = 7;
            public int twice(int n) {
                int doubled = n * 2;
                return doubled;
            }
        }
    """.trimIndent()

    @Test
    fun `the same class compiled twice digests identically`() {
        // A digest recorded by one build is compared by another.
        assertEquals(digestOf(subject), digestOf(subject))
    }

    @Test
    fun `a comment-only edit does not move the digest`() {
        // It shifts every LineNumberTable entry below it but changes nothing a consumer observes.
        val commented = subject.replace(
            "public int twice(int n) {",
            "// a remark that shifts every line below it\n    public int twice(int n) {",
        )

        assertEquals(digestOf(subject), digestOf(commented))
    }

    @Test
    fun `renaming a local variable does not move the digest`() {
        // A local's name is debug data.
        assertEquals(
            digestOf(subject),
            digestOf(subject.replace("doubled", "twiceOverForClarity")),
        )
    }

    @Test
    fun `a one-token change inside a method body moves the digest`() {
        assertNotEquals(digestOf(subject), digestOf(subject.replace("n * 2", "n * 3")))
    }

    @Test
    fun `a changed compile-time constant value moves the digest`() {
        // A consumer that baked in 7 changes only in its bytecode, and this is what sees that.
        assertNotEquals(digestOf(subject), digestOf(subject.replace("LIMIT = 7", "LIMIT = 8")))
    }

    /** The same class with a different `SourceDebugExtension`, and nothing else changed. */
    private fun withSmap(classBytes: ByteArray, smap: String?): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        org.objectweb.asm.ClassReader(classBytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
                override fun visitSource(source: String?, debug: String?) {
                    super.visitSource(source, smap)
                }
            },
            0,
        )
        return writer.toByteArray()
    }

    @Test
    fun `a class whose SourceDebugExtension differs digests differently`() {
        // Kotlin's SMAP is how an inlined body announces itself; `SKIP_DEBUG` would drop it.
        val bytes = bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse")
        val one = Recordability.classDigest(withSmap(bytes, "SMAP\nA.kt\nKotlin\n*S Kotlin\n*E\n"))
        val other = Recordability.classDigest(withSmap(bytes, "SMAP\nB.kt\nKotlin\n*S Kotlin\n*E\n"))

        assertNotNull(one.value)
        assertNotEquals(one.value, other.value)
        // The same SMAP twice must agree, or the assertion above proves nothing.
        assertEquals(
            one.value,
            Recordability.classDigest(withSmap(bytes, "SMAP\nA.kt\nKotlin\n*S Kotlin\n*E\n")).value,
        )
    }

    @Test
    fun `bytes that are not a class are refused, and the refusal names why`() {
        // Never a zero digest: two unreadable classes would then agree, absence reading as equality.
        val truncated = Recordability.classDigest(
            bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasLogic").copyOfRange(0, 40)
        )
        val garbage = Recordability.classDigest(byteArrayOf(1, 2, 3))

        assertNull(truncated.value)
        assertNull(garbage.value)
        assertTrue(truncated.reason!!.contains("could not be read"), truncated.reason)
        assertTrue(garbage.reason!!.contains("could not be read"), garbage.reason)
    }

    // The annotation digest: what a framework's scan or reflective read can see of a class.
    private fun annotationsOf(source: String): String {
        val digest = Recordability.annotationDigest(javac(source, "Service"))
        assertNotNull(digest.value, "refused: ${digest.reason}")
        return digest.value
    }

    private val service = """
        import java.lang.annotation.*;
        @Service.Profile("a")
        public class Service {
            @Retention(RetentionPolicy.RUNTIME) @interface Profile { String value(); }
            @Retention(RetentionPolicy.RUNTIME) @interface Marker { }
            @Retention(RetentionPolicy.CLASS) @interface Hidden { }
            @Marker int count;
            public int twice(int n) { return n * 2; }
            public int thrice(@Marker int n) { return n * 3; }
        }
    """.trimIndent()

    @Test
    fun `an annotation attribute value change moves the annotation digest`() {
        assertNotEquals(
            annotationsOf(service),
            annotationsOf(service.replace("@Service.Profile(\"a\")", "@Service.Profile(\"b\")")),
        )
    }

    @Test
    fun `adding a class-level annotation moves the annotation digest`() {
        assertNotEquals(
            annotationsOf(service),
            annotationsOf(service.replace("@Service.Profile(\"a\")", "@Service.Profile(\"a\") @Service.Marker")),
        )
    }

    @Test
    fun `adding a method-level annotation moves the annotation digest`() {
        assertNotEquals(
            annotationsOf(service),
            annotationsOf(service.replace("public int twice", "@Marker public int twice")),
        )
    }

    @Test
    fun `field, parameter and class-retention annotations move the annotation digest`() {
        assertNotEquals(annotationsOf(service), annotationsOf(service.replace("@Marker int count", "int count")))
        assertNotEquals(annotationsOf(service), annotationsOf(service.replace("(@Marker int n)", "(int n)")))
        // Invisible to reflection, visible to a bytecode scanner.
        assertNotEquals(
            annotationsOf(service),
            annotationsOf(service.replace("public int twice", "@Hidden public int twice")),
        )
    }

    @Test
    fun `a method-body-only change leaves the annotation digest alone`() {
        val edited = service.replace("n * 2", "n + n")
        // The class digest must see the edit, or this proves nothing about the annotation digest.
        assertNotEquals(Recordability.classDigest(javac(service, "Service")).value,
            Recordability.classDigest(javac(edited, "Service")).value)
        assertEquals(annotationsOf(service), annotationsOf(edited))
    }

    @Test
    fun `moving a member does not move the annotation digest, reordering annotations does`() {
        // Reflection promises no member order, so a moved method is not an annotation change. The
        // order of annotations on one element is what `getAnnotations()` returns, which a framework
        // can observe; treating it as a change costs one full run on a rare edit.
        val moved = service
            .replace("    public int twice(int n) { return n * 2; }\n", "")
            .replace("    public int thrice", "    public int twice(int n) { return n * 2; }\n    public int thrice")
        assertEquals(annotationsOf(service), annotationsOf(moved))

        val two = service.replace("@Marker int count", "@Marker @Deprecated int count")
        val swapped = service.replace("@Marker int count", "@Deprecated @Marker int count")
        assertNotEquals(annotationsOf(two), annotationsOf(swapped))
    }

    @Test
    fun `a changed annotation default moves the annotation type's digest`() {
        // Every class that uses the default reads the new value without changing itself.
        val profile = """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Service { String value() default "a"; }
        """.trimIndent()
        assertNotEquals(annotationsOf(profile), annotationsOf(profile.replace("default \"a\"", "default \"b\"")))
    }

    @Test
    fun `a class with no annotations digests as the no-annotations value`() {
        assertEquals(
            Recordability.NO_ANNOTATIONS,
            annotationsOf("public class Service { public int twice(int n) { return n * 2; } }"),
        )
        assertNotEquals(Recordability.NO_ANNOTATIONS, annotationsOf(service))
    }

    /** A class carrying one class-level annotation [descriptor] whose `value` is [value]. */
    private fun annotatedWith(descriptor: String, value: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, "Service", null,
            "java/lang/Object", null)
        writer.visitAnnotation(descriptor, false).apply {
            visitArray("value").apply { visit(null, value); visitEnd() }
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `Kotlin's SMAP annotation does not move the annotation digest`() {
        // It carries line numbers, so every body edit in a class calling an inline function moves it.
        val smap = "Lkotlin/jvm/internal/SourceDebugExtension;"
        assertEquals(
            Recordability.annotationDigest(annotatedWith(smap, "SMAP\nA.kt\n*L\n1#1,39:1\n*E\n")).value,
            Recordability.annotationDigest(annotatedWith(smap, "SMAP\nA.kt\n*L\n1#1,40:1\n*E\n")).value,
        )
        // The same shape under any other descriptor still counts, or the equality above proves nothing.
        assertNotEquals(
            Recordability.annotationDigest(annotatedWith("Lcom/acme/Profile;", "a")).value,
            Recordability.annotationDigest(annotatedWith("Lcom/acme/Profile;", "b")).value,
        )
    }

    @Test
    fun `bytes that are not a class have no annotation digest`() {
        assertNull(Recordability.annotationDigest(byteArrayOf(1, 2, 3)).value)
    }

    @Test
    fun `a class declaring a JUnit test method is told apart from one that declares none`() {
        assertTrue(Recordability.declaresTestMethod(bytesOf(RecordabilityTest::class.java.name)))
        assertFalse(
            Recordability.declaresTestMethod(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasLogic"))
        )
        assertFalse(Recordability.declaresTestMethod(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `class-level annotations are read whatever their retention`() {
        assertEquals(
            listOf("Ljava/lang/Deprecated;"),
            Recordability.classAnnotations(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasVisibleAnnotation")),
        )
        assertEquals(
            listOf("Lio/github/zeuspizza/yoriwake/gradle/fixtures/Tagged;"),
            Recordability.classAnnotations(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasInvisibleAnnotation")),
        )
        assertEquals(emptyList(), Recordability.classAnnotations(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasLogic")))
        assertNull(Recordability.classAnnotations(byteArrayOf(1, 2, 3)))
    }

    // kotlinc writes kotlin.Metadata on every class, and the Kotlin rule already decides Kotlin.
    @Test
    fun `kotlin Metadata is not counted as a class-level annotation`() {
        assertEquals(emptyList(), Recordability.classAnnotations(bytesOf("io.github.zeuspizza.yoriwake.gradle.fixtures.OrdinaryKotlin")))
    }
}

