package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.DigestScan
import io.github.zeuspizza.yoriwake.gradle.bytecode.InlineScan
import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.bytecode.scanOrExplain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The file lookups every narrowing rule rests on; the failure that matters is a confident wrong
// answer, not an exception.
class TaskArtifactsTest {

    private fun classFile(root: File, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        val file = File(root, name.replace('.', '/') + ".class")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    private fun jar(target: File, vararg entries: Pair<String, ByteArray>): File {
        target.parentFile.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return target
    }

    @Test
    fun `finds the class a prefix names, and the types compiled beside it`(@TempDir dir: File) {
        classFile(dir, "com.acme.Thing")
        classFile(dir, "com.acme.Thing\$Inner")
        classFile(dir, "com.acme.ThingKt")
        classFile(dir, "com.acme.Unrelated")

        val found = TaskArtifacts(listOf(dir), emptyList()).classesFor("com.acme.Thing")

        assertEquals(
            setOf("com.acme.Thing", "com.acme.Thing\$Inner", "com.acme.ThingKt"),
            found!!.map { it.first }.toSet(),
        )
    }

    @Test
    fun `finds a nested type the change set names on its own`(@TempDir dir: File) {
        // A type declared inside another top-level class's source is named by its own path but
        // compiled as `Outer$GroupValue`.
        classFile(dir, "com.acme.Outer\$GroupValue")

        val found = TaskArtifacts(listOf(dir), emptyList()).classesFor("com.acme.GroupValue")

        assertEquals(listOf("com.acme.Outer\$GroupValue"), found!!.map { it.first })
    }

    @Test
    fun `finds classes inside a jar this repository built`(@TempDir dir: File) {
        // Some modules put their packaged jar, not a classes directory, on the test classpath.
        val build = File(dir, "module/build").apply { mkdirs() }
        val jar = jar(File(build, "libs/module.jar"), "com/acme/Packaged.class" to byteArrayOf(9))
        val classes = File(dir, "classes").apply { mkdirs() }

        val found = TaskArtifacts(listOf(classes, jar), listOf(build.absolutePath))
            .classesFor("com.acme.Packaged")

        assertEquals(listOf("com.acme.Packaged"), found!!.map { it.first })
    }

    @Test
    fun `a jar outside this repository's build directories is not searched`(@TempDir dir: File) {
        // Opening every dependency jar on every selecting run costs more than it is worth.
        val elsewhere = jar(File(dir, "cache/other.jar"), "com/acme/Packaged.class" to byteArrayOf(9))
        val classes = File(dir, "classes").apply { mkdirs() }

        val found = TaskArtifacts(listOf(classes, elsewhere), listOf(File(dir, "module/build").absolutePath))
            .classesFor("com.acme.Packaged")

        assertTrue(found!!.isEmpty())
    }

    @Test
    fun `no classpath directory at all is a refusal, not an empty answer`() {
        // Empty means "compiles to nothing" and licenses a skip; null means "could not look" and
        // must force.
        assertNull(TaskArtifacts(emptyList(), emptyList()).classesFor("com.acme.Thing"))
    }

    @Test
    fun `a resource naming a class is found, and one naming a different class is not`(@TempDir dir: File) {
        File(dir, "flows").apply { mkdirs() }
        File(dir, "flows/example.yaml").writeText("type: com.acme.Named\n")

        val named = TaskArtifacts(listOf(dir), emptyList())
            .namedInResources(listOf("com.acme.Named", "com.acme.Absent"))

        assertEquals(setOf("com.acme.Named"), named)
    }

    @Test
    fun `class files are not searched for class names`(@TempDir dir: File) {
        // Otherwise every referencing class would "name" its dependency and the rule would refuse
        // everything; bytecode references are coverage's question.
        classFile(dir, "com.acme.Referrer", "com.acme.Named".toByteArray())

        assertTrue(TaskArtifacts(listOf(dir), emptyList()).namedInResources(listOf("com.acme.Named")).isEmpty())
    }

    @Test
    fun `a resource inside a jar this repository built is searched`(@TempDir dir: File) {
        val build = File(dir, "module/build").apply { mkdirs() }
        val jar = jar(File(build, "libs/module.jar"), "registry.yaml" to "com.acme.Named".toByteArray())

        val named = TaskArtifacts(listOf(File(dir, "classes").apply { mkdirs() }, jar), listOf(build.absolutePath))
            .namedInResources(listOf("com.acme.Named"))

        assertEquals(setOf("com.acme.Named"), named)
    }

    @Test
    fun `asking about nothing walks nothing`(@TempDir dir: File) {
        assertTrue(TaskArtifacts(listOf(dir), emptyList()).namedInResources(emptyList()).isEmpty())
        assertTrue(TaskArtifacts(listOf(dir), emptyList()).pathsNamedInClasses(emptyList()).isEmpty())
    }

    @Test
    fun `classes are in the test output only when every one of them is`(@TempDir dir: File) {
        val main = File(dir, "main").apply { mkdirs() }
        val test = File(dir, "test").apply { mkdirs() }
        classFile(test, "com.acme.ThingTest")
        classFile(test, "com.acme.ThingTest\$Fixture")
        classFile(main, "com.acme.Thing")
        val artifacts = TaskArtifacts(listOf(main, test), emptyList(), listOf(test))

        assertTrue(artifacts.onlyInTestOutput(artifacts.classesFor("com.acme.ThingTest")))
        assertFalse(artifacts.onlyInTestOutput(artifacts.classesFor("com.acme.Thing")))
    }

    @Test
    fun `a task with no test output answers no, rather than yes by vacuity`(@TempDir dir: File) {
        val test = File(dir, "test").apply { mkdirs() }
        classFile(test, "com.acme.ThingTest")
        val artifacts = TaskArtifacts(listOf(test), emptyList(), emptyList())

        assertFalse(artifacts.onlyInTestOutput(artifacts.classesFor("com.acme.ThingTest")))
        assertFalse(artifacts.onlyInTestOutput(emptyList()))
        assertFalse(artifacts.onlyInTestOutput(null))
    }

    // When the lookup cannot look, it must answer "everything is named": the caller reads an
    // absent name as permission to skip tests.

    @Test
    fun `a classpath with nothing to search names every path, rather than none`(@TempDir dir: File) {
        // A classpath of only jars must not drop every changed path on a scan that never ran.
        val artifacts = TaskArtifacts(emptyList(), emptyList())

        assertEquals(
            setOf(".github/workflows/ci.yml", "README.md"),
            artifacts.pathsNamedInClasses(listOf(".github/workflows/ci.yml", "README.md")),
        )
    }

    @Test
    fun `a path named inside this repository's own jar is found`(@TempDir dir: File) {
        // A module that packages its classes must still have a class able to vouch for a path.
        val build = File(dir, "module/build").apply { mkdirs() }
        val jar = jar(File(build, "libs/module.jar"), "pkg/Reader.class" to "x.github/workflows/ci.ymlx".toByteArray())

        val named = TaskArtifacts(listOf(File(dir, "classes").apply { mkdirs() }, jar), listOf(build.absolutePath))
            .pathsNamedInClasses(listOf(".github/workflows/ci.yml", "docs/other.md"))

        assertEquals(setOf(".github/workflows/ci.yml"), named)
    }

    @Test
    fun `a class file too large to read counts as naming everything`(@TempDir dir: File) {
        // "Too big to read" must not read as "the name does not appear".
        val classes = File(dir, "pkg").apply { mkdirs() }
        File(classes, "Big.class").writeBytes(ByteArray(5 * 1024 * 1024))
        // A separate oversized resource, because the resource scan skips class files by design.
        File(classes, "big.bin").writeBytes(ByteArray(5 * 1024 * 1024))

        val artifacts = TaskArtifacts(listOf(dir), emptyList())

        assertEquals(setOf("docs/api.md"), artifacts.pathsNamedInClasses(listOf("docs/api.md")))
        assertEquals(setOf("com.acme.Named"), artifacts.namedInResources(listOf("com.acme.Named")))
    }

    @Test
    fun `a sibling output directory whose name merely starts with the test output is not test output`(
        @TempDir dir: File,
    ) {
        // `.../java/test` is a string prefix of `.../java/testFixtures`; a raw startsWith would
        // exempt every testFixtures class from forcing.
        val test = File(dir, "classes/java/test").apply { mkdirs() }
        val fixtures = File(dir, "classes/java/testFixtures").apply { mkdirs() }
        classFile(fixtures, "com.acme.SharedFixture")
        val artifacts = TaskArtifacts(listOf(test, fixtures), emptyList(), listOf(test))

        assertFalse(artifacts.onlyInTestOutput(artifacts.classesFor("com.acme.SharedFixture")))
    }

    @Test
    fun `a non-ASCII path is treated as named, because the scan cannot search for it`(@TempDir dir: File) {
        // A constant pool holds modified UTF-8, so a non-ASCII needle may never match -- and
        // not-found means droppable.
        val classes = File(dir, "pkg").apply { mkdirs() }
        classFile(classes, "Reader", "nothing relevant".toByteArray())

        val named = TaskArtifacts(listOf(dir), emptyList()).pathsNamedInClasses(listOf("docs/caf\u00e9.md"))

        assertEquals(setOf("docs/caf\u00e9.md"), named)
    }

    @Test
    fun `a dependency jar that names one of our classes is found`(@TempDir dir: File) {
        // A service file or framework registration inside a dependency can name application
        // classes, so the resource scan covers the whole test-runtime classpath.
        val dependency = jar(File(dir, "cache/dep.jar"), "META-INF/services/x.Service" to "com.acme.Named".toByteArray())
        val classes = File(dir, "classes").apply { mkdirs() }

        val named = TaskArtifacts(listOf(classes, dependency), emptyList())
            .namedInResources(listOf("com.acme.Named", "com.acme.Absent"))

        assertEquals(setOf("com.acme.Named"), named)
    }

    // classesInlining: an empty answer means "nothing inlines your change" and the caller narrows
    // on it, so every case the scan cannot vouch for must refuse instead.

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")) {
            "no class file for $name"
        }.use { it.readBytes() }

    /** A class carrying neither `kotlin.Metadata` nor an SMAP, which is every javac output. */
    private fun plainClass(name: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC,
            name.replace('.', '/'),
            null,
            "java/lang/Object",
            null,
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun output(dir: File) = TaskArtifacts(listOf(dir), emptyList(), listOf(dir))

    /** The classes an inline scan found, insisting it did not refuse. */
    private fun InlineScan.classes(): Set<String> {
        assertTrue(this is InlineScan.Found, "expected a finished scan, got $this")
        return (this as InlineScan.Found).classes
    }

    private fun assertRefused(scan: InlineScan, message: String? = null): InlineScan.Refused {
        assertTrue(scan is InlineScan.Refused, message ?: "expected a refusal, got $scan")
        // Checked on every refusal: a blank reason sends the reader to the plugin's source.
        val refused = scan as InlineScan.Refused
        assertTrue(
            refused.reason.isNotBlank(),
            "a refusal with no reason is the defect this assertion exists to stop: $refused",
        )
        return refused
    }

    @Test
    fun `names the class that inlined a changed source`(@TempDir dir: File) {
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"))
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing"))

        val found = output(dir).classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"))

        assertEquals(setOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"), found.classes())
    }

    @Test
    fun `answers empty when the output has the signal and nothing inlined the change`(@TempDir dir: File) {
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"))

        // The SMAP is present, so the empty answer is a fact about this change, safe to narrow on.
        assertEquals(
            emptySet(),
            output(dir).classesInlining(listOf("com.acme.NobodyInlinesThis")).classes(),
        )
    }

    @Test
    fun `refuses when a Kotlin output carries no SMAP anywhere`(@TempDir dir: File) {
        // What `-Xno-source-debug-extension` produces. Per class it looks like inlining nothing,
        // so the whole output is asked, and the answer is "unknown".
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing"))

        val refused = assertRefused(
            output(dir).classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"))
        )

        // The walk finished; this is not a budget to raise, the build lacks the signal.
        assertEquals(InlineScan.Kind.SMAP_ABSENT, refused.kind)
        assertContains(refused.reason, "Kotlin")
        assertContains(refused.reason, "SourceDebugExtension")
    }

    @Test
    fun `the missing-SMAP refusal counts what it saw, so the cause is readable without a source read`(
        @TempDir dir: File,
    ) {
        // The counts tell a mostly-Java build with a Kotlin extension from a Kotlin build compiled
        // with -Xno-source-debug-extension; the two want opposite remedies.
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing"))
        classFile(dir, "com.acme.One", plainClass("com.acme.One"))
        classFile(dir, "com.acme.Two", plainClass("com.acme.Two"))

        val refused = assertRefused(
            output(dir).classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"))
        )

        assertContains(refused.reason, "3 classes scanned")
        assertContains(refused.reason, "1 of them Kotlin")
    }

    @Test
    fun `a classpath that will not resolve is a different refusal from a scan that did not finish`() {
        // yoriwakeAudit runs no tasks, and an Android unit-test classpath maps entries from a
        // transform's output, so resolving can fail; that is not an unfinished scan.
        val unresolvable = scanOrExplain(
            resolve = { throw IllegalStateException("Querying the mapped value of task ':app:tx'") },
            scan = { error("the scan must not be reached when the classpath did not resolve") },
        )

        val refused = assertRefused(unresolvable)
        assertEquals(InlineScan.Kind.CLASSPATH_UNRESOLVED, refused.kind)
        assertContains(refused.reason, "Querying the mapped value")

        // A scan that genuinely fails after a classpath that resolved is still INCOMPLETE.
        val scanThrew = scanOrExplain(
            resolve = { TaskArtifacts(emptyList(), emptyList()) },
            scan = { throw IllegalArgumentException("Unsupported class file major version 70") },
        )
        assertEquals(InlineScan.Kind.INCOMPLETE, assertRefused(scanThrew).kind)

        // And a scan that answers is passed straight through.
        val found = scanOrExplain(
            resolve = { TaskArtifacts(emptyList(), emptyList()) },
            scan = { InlineScan.Found(setOf("com.acme.Thing")) },
        )
        assertEquals(InlineScan.Found(setOf("com.acme.Thing")), found)
    }

    @Test
    fun `a scan that could not read something is a different kind from a build with no signal`(
        @TempDir dir: File,
    ) {
        // Both force; the kinds differ because an unreadable file and a build without SMAP need
        // different remedies.
        val unreadable = File(dir, "com/acme/Broken.class").also {
            it.parentFile.mkdirs()
            it.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        assertTrue(unreadable.isFile)

        val refused = assertRefused(output(dir).classesInlining(listOf("com.acme.Thing")))

        assertEquals(InlineScan.Kind.INCOMPLETE, refused.kind)
        // Unreadable bytes are usually ASM not knowing a class-file version, not an SMAP defect, so
        // the reason names the version.
        assertFalse(
            refused.reason.contains("SMAP"),
            "a class file that could not be read was reported as an SMAP defect: ${refused.reason}",
        )
        assertTrue(
            refused.reason.contains("class file v"),
            "a refusal over unreadable bytes must name the class-file version, which is the fact a "
                + "reader can act on: ${refused.reason}",
        )
    }

    @Test
    fun `a Kotlin class that inlines nothing does not force a Java change set forever`(
        @TempDir dir: File,
    ) {
        // A Kotlin class that inlines nothing carries no SMAP. A changed Java source cannot be an
        // inlined body -- a caller's bytecode names the Java class, so coverage records the edge.
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing"))
        classFile(dir, "com.acme.Thing", plainClass("com.acme.Thing"))

        assertEquals(
            emptySet(),
            output(dir).classesInlining(listOf("com.acme.Thing"), inlinableSourceChanged = false)
                .classes(),
        )
    }

    @Test
    fun `a Kotlin change set still refuses when the output carries no SMAP`(@TempDir dir: File) {
        // A changed Kotlin source can be the inline body, so without SMAP nobody can say who
        // inlined it.
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing"))

        assertRefused(
            output(dir).classesInlining(
                listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"), inlinableSourceChanged = true,
            )
        )
    }

    @Test
    fun `the strict behaviour is what a caller gets by default`(@TempDir dir: File) {
        // Callers that omit the flag must get the stricter answer.
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesNothing"))

        assertRefused(output(dir).classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes")))
    }

    @Test
    fun `a Java-only output answers rather than refusing`(@TempDir dir: File) {
        // Java has no inline functions, so no SMAP is expected; its analogue, the compile-time
        // constant, is handled by Recordability.
        classFile(dir, "com.acme.Thing", plainClass("com.acme.Thing"))

        assertEquals(emptySet(), output(dir).classesInlining(listOf("com.acme.Other")).classes())
    }

    @Test
    fun `refuses when there is nothing to scan at all`() {
        assertRefused(TaskArtifacts(emptyList(), emptyList()).classesInlining(listOf("com.acme.Thing")))
    }

    @Test
    fun `refuses when a class file cannot be understood`(@TempDir dir: File) {
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"))
        classFile(dir, "com.acme.Broken", byteArrayOf(1, 2, 3))

        assertRefused(output(dir).classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes")))
    }

    @Test
    fun `refuses when a class file is too large to read`(@TempDir dir: File) {
        classFile(dir, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"))
        // Skipping a class for its size would be an unfinished scan reported as finished.
        val huge = File(dir, "com/acme/Huge.class")
        huge.parentFile.mkdirs()
        huge.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(5) { out.write(chunk) }
        }

        assertRefused(output(dir).classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes")))
    }

    @Test
    fun `finds a consumer packaged in one of this build's own jars`(@TempDir dir: File) {
        // A module that packages its output keeps its consumers out of reach of a directory walk.
        val build = File(dir, "module/build")
        val jar = jar(
            File(build, "libs/module.jar"),
            "io/github/zeuspizza/yoriwake/gradle/fixtures/InlinesSomethingElse.class" to
                fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"),
        )

        val found = TaskArtifacts(listOf(jar), listOf(build.absolutePath))
            .classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"))

        assertEquals(setOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"), found.classes())
    }

    @Test
    fun `refuses when one of this build's own jars cannot be opened`(@TempDir dir: File) {
        val build = File(dir, "module/build")
        val jar = File(build, "libs/module.jar")
        jar.parentFile.mkdirs()
        jar.writeText("not a zip")

        assertRefused(
            TaskArtifacts(listOf(jar), listOf(build.absolutePath))
                .classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"))
        )
    }

    @Test
    fun `refuses when a classpath directory nobody walked could hold a consumer`(@TempDir dir: File) {
        // The walk covers only module build dirs; a classpath directory from an included build or
        // another plugin could hold a consumer, so it refuses rather than report nothing.
        val own = File(dir, "module/build/classes/kotlin/test")
        classFile(own, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"))
        val foreign = File(dir, "included-build/out/classes")
        classFile(foreign, "com.acme.CouldInlineAnything", plainClass("com.acme.CouldInlineAnything"))

        val found = TaskArtifacts(listOf(own, foreign), listOf(File(dir, "module/build").absolutePath), listOf(own))
            .classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"))

        assertRefused(found, "a directory that was not walked cannot be reported as holding nothing")
    }

    @Test
    fun `a classpath directory with no classes in it is no reason to refuse`(@TempDir dir: File) {
        // A resource-only directory holds no consumer, so refusing on it would force for nothing.
        val own = File(dir, "module/build/classes/kotlin/test")
        classFile(own, "io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse",
            fixtureBytes("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"))
        val resources = File(dir, "elsewhere/resources").apply { mkdirs() }
        File(resources, "application.yml").writeText("nothing: here")

        val found = TaskArtifacts(
            listOf(own, resources), listOf(File(dir, "module/build").absolutePath), listOf(own),
        ).classesInlining(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"))

        assertEquals(setOf("io.github.zeuspizza.yoriwake.gradle.fixtures.InlinesSomethingElse"), found.classes())
    }

    // constantDigests feeds the only rule that narrows on a positive match (recorded digest equals
    // current, therefore skip), so a wrong answer loses a failure, not a saving.

    private fun constantClass(name: String, field: String, value: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            name.replace('.', '/'), null, "java/lang/Object", null,
        )
        writer.visitField(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC or
                org.objectweb.asm.Opcodes.ACC_FINAL,
            field, "Ljava/lang/String;", null, value,
        ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `digests every class that declares a constant, and no class that does not`(@TempDir dir: File) {
        classFile(dir, "com.acme.Holder", constantClass("com.acme.Holder", "NAME", "fixed"))
        classFile(dir, "com.acme.Plain", plainClass("com.acme.Plain"))

        val digests = output(dir).constantDigests()

        assertEquals(setOf("com.acme.Holder"), digests!!.keys)
        assertEquals(
            Recordability.constantDigest(constantClass("com.acme.Holder", "NAME", "fixed")),
            digests["com.acme.Holder"],
        )
    }

    @Test
    fun `the names recorded are the names the lookup will ask about`(@TempDir dir: File) {
        // The recording keys by walking the output; the selector looks up by `classesFor` names. If
        // they disagree, every lookup misses and the feature silently switches off.
        classFile(dir, "com.acme.Holder", constantClass("com.acme.Holder", "NAME", "fixed"))
        classFile(dir, "com.acme.Holder\$Nested", constantClass("com.acme.Holder\$Nested", "N", "x"))
        val artifacts = output(dir)

        val recorded = artifacts.constantDigests()!!.keys
        val looked = artifacts.classesFor("com.acme.Holder")!!.map { it.first }.toSet()

        assertEquals(looked, recorded, "a recorded name the lookup never asks for is a dead record")
    }

    @Test
    fun `a constant holder packaged in one of this build's own jars is recorded`(@TempDir dir: File) {
        // Own output can reach the classpath as a jar; everything the lookup can find there must be
        // something the recording wrote.
        val jarDir = File(dir, "libs").apply { mkdirs() }
        val packaged = jar(
            File(jarDir, "own.jar"),
            "com/acme/Packaged.class" to constantClass("com.acme.Packaged", "LIMIT", "10"),
        )
        val artifacts = TaskArtifacts(listOf(packaged), listOf(dir.absolutePath), emptyList())

        val recorded = artifacts.constantDigests()
        val looked = artifacts.classesFor("com.acme.Packaged")!!.map { it.first }.toSet()

        assertNotNull(recorded, "an own jar is walkable; refusing here would force forever")
        assertEquals(
            looked, recorded!!.keys,
            "a class the lookup finds in an own jar must be one the recording wrote, or every " +
                "holder reads as unrecorded and forces",
        )
    }

    @Test
    fun `an own jar that cannot be opened refuses rather than recording part of the build`(
        @TempDir dir: File,
    ) {
        // Partial is worse than nothing: a missing digest is indistinguishable from a class that
        // declares no constant, and that reads as licence to skip.
        val jarDir = File(dir, "libs").apply { mkdirs() }
        val corrupt = File(jarDir, "own.jar").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }

        val artifacts = TaskArtifacts(listOf(corrupt), listOf(dir.absolutePath), emptyList())

        assertNull(artifacts.constantDigests(), "an unreadable own jar is an unfinished walk")
    }

    @Test
    fun `a class that cannot be read gives up the whole map rather than part of it`(@TempDir dir: File) {
        // A missing digest looks like a class with no constant; null makes the caller record
        // nothing, which forces.
        classFile(dir, "com.acme.Holder", constantClass("com.acme.Holder", "NAME", "fixed"))

        // Permissions, not a file lock: a lock is only advisory on POSIX. Root can read anything,
        // so the assumption below skips rather than assert what the OS will not arrange.
        val unreadable = File(dir, "com/acme/Locked.class")
        unreadable.parentFile.mkdirs()
        unreadable.writeBytes(plainClass("com.acme.Locked"))
        unreadable.setReadable(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !unreadable.canRead(),
            "this user can read a file with its read bit cleared (root?), so there is no way to " +
                "make a class unreadable here",
        )

        try {
            assertNull(output(dir).constantDigests())
        } finally {
            // Or @TempDir cannot clean up after itself.
            unreadable.setReadable(true)
        }
    }

    @Test
    fun `a class too large to read gives up the whole map`(@TempDir dir: File) {
        classFile(dir, "com.acme.Holder", constantClass("com.acme.Holder", "NAME", "fixed"))
        val huge = File(dir, "com/acme/Huge.class")
        huge.parentFile.mkdirs()
        huge.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(5) { out.write(chunk) }
        }

        assertNull(output(dir).constantDigests())
    }

    @Test
    fun `nothing of this build's own to walk is a refusal, not an empty map`(@TempDir dir: File) {
        // An empty map would say "no class declares a constant", licensing narrowing on every holder.
        assertNull(TaskArtifacts(emptyList(), emptyList()).constantDigests())
    }

    // classDigests: complete or refused. A class missing from a partial map looks like a match,
    // which selects fewer tests.

    private fun DigestScan.found(): Map<String, String> {
        assertTrue(this is DigestScan.Found, "expected a finished walk, got $this")
        return (this as DigestScan.Found).digests
    }

    private fun DigestScan.refusal(): DigestScan.Refused {
        assertTrue(this is DigestScan.Refused, "expected a refusal, got $this")
        return this as DigestScan.Refused
    }

    @Test
    fun `digests every class of the output, constant or not`(@TempDir dir: File) {
        classFile(dir, "com.acme.Holder", constantClass("com.acme.Holder", "NAME", "fixed"))
        classFile(dir, "com.acme.Plain", plainClass("com.acme.Plain"))

        val digests = output(dir).classDigests().found()

        assertEquals(setOf("com.acme.Holder", "com.acme.Plain"), digests.keys,
            "a class with no constant still has bytecode a consumer can be recompiled against")
        assertEquals(
            Recordability.classDigest(plainClass("com.acme.Plain")).value,
            digests["com.acme.Plain"],
        )
    }

    @Test
    fun `both modules of a two-module build are digested`(@TempDir dir: File) {
        val one = File(dir, "one/build/classes").apply { mkdirs() }
        val two = File(dir, "two/build/classes").apply { mkdirs() }
        classFile(one, "com.acme.One", plainClass("com.acme.One"))
        classFile(two, "com.acme.Two", plainClass("com.acme.Two"))

        val artifacts = TaskArtifacts(
            listOf(one, two),
            listOf(File(dir, "one/build").absolutePath, File(dir, "two/build").absolutePath),
            listOf(one),
        )

        assertEquals(setOf("com.acme.One", "com.acme.Two"), artifacts.classDigests().found().keys)
    }

    @Test
    fun `the names recorded are the names the lookup will ask about, jar included`(@TempDir dir: File) {
        // The walk's reach is asserted against the lookup's reach, jars included.
        val jarDir = File(dir, "libs").apply { mkdirs() }
        val packaged = jar(
            File(jarDir, "own.jar"),
            "com/acme/Packaged.class" to plainClass("com.acme.Packaged"),
        )
        classFile(dir, "com.acme.Loose", plainClass("com.acme.Loose"))
        val artifacts = TaskArtifacts(listOf(dir, packaged), listOf(dir.absolutePath), listOf(dir))

        val walked = artifacts.classDigests().found().keys
        val looked = (artifacts.classesFor("com.acme.Packaged")!! + artifacts.classesFor("com.acme.Loose")!!)
            .map { it.first }.toSet()

        assertEquals(looked, walked,
            "a class the lookup finds and the walk did not is a class whose change is invisible")
    }

    @Test
    fun `a build whose only output is a jar is not reported as empty`(@TempDir dir: File) {
        val jarDir = File(dir, "libs").apply { mkdirs() }
        val packaged = jar(
            File(jarDir, "own.jar"),
            "com/acme/Packaged.class" to plainClass("com.acme.Packaged"),
        )

        val artifacts = TaskArtifacts(listOf(packaged), listOf(dir.absolutePath), emptyList())

        assertEquals(setOf("com.acme.Packaged"), artifacts.classDigests().found().keys)
    }

    @Test
    fun `a module with no build directory refuses, and a module with no classes does not`(
        @TempDir dir: File,
    ) {
        // Both would be an empty map, and they mean opposite things.
        val nothingToWalk = TaskArtifacts(emptyList(), emptyList()).classDigests()
        assertEquals(DigestScan.Kind.NOTHING_TO_SCAN, nothingToWalk.refusal().kind)

        val empty = File(dir, "empty").apply { mkdirs() }
        assertEquals(
            emptyMap(), TaskArtifacts(listOf(empty), listOf(dir.absolutePath), listOf(empty))
                .classDigests().found(),
            "a module that compiled nothing KNOWS there is nothing, which is not the same as not knowing",
        )
    }

    @Test
    fun `a classpath directory nobody walked refuses the whole walk`(@TempDir dir: File) {
        // `classesFor` searches every classpath directory but this walk only own output, so a
        // foreign directory holding classes refuses rather than being silently omitted.
        val own = File(dir, "own/build/classes").apply { mkdirs() }
        classFile(own, "com.acme.Own", plainClass("com.acme.Own"))
        val stranger = File(dir, "elsewhere").apply { mkdirs() }
        classFile(stranger, "com.acme.Stranger", plainClass("com.acme.Stranger"))

        val refusal = TaskArtifacts(
            listOf(own, stranger), listOf(File(dir, "own/build").absolutePath), listOf(own),
        ).classDigests().refusal()

        assertEquals(DigestScan.Kind.UNACCOUNTED_OUTPUT, refusal.kind)
        assertContains(refusal.reason, "elsewhere")
    }

    @Test
    fun `a resource-only classpath directory is no reason to refuse the digest walk`(
        @TempDir dir: File,
    ) {
        val own = File(dir, "own/build/classes").apply { mkdirs() }
        classFile(own, "com.acme.Own", plainClass("com.acme.Own"))
        val resources = File(dir, "resources").apply { mkdirs() }
        File(resources, "application.yml").writeText("nothing here declares a class")

        val artifacts = TaskArtifacts(
            listOf(own, resources), listOf(File(dir, "own/build").absolutePath), listOf(own),
        )

        assertEquals(setOf("com.acme.Own"), artifacts.classDigests().found().keys)
    }

    @Test
    fun `a class file that cannot be understood refuses rather than yielding a partial map`(
        @TempDir dir: File,
    ) {
        classFile(dir, "com.acme.Good", plainClass("com.acme.Good"))
        classFile(dir, "com.acme.Broken", byteArrayOf(1, 2, 3))

        val refusal = output(dir).classDigests().refusal()

        assertEquals(DigestScan.Kind.UNREADABLE, refusal.kind)
        assertTrue(refusal.counts.unreadable > 0, "a refusal that read nothing counts nothing")
    }

    @Test
    fun `a class too large to read refuses`(@TempDir dir: File) {
        classFile(dir, "com.acme.Good", plainClass("com.acme.Good"))
        val huge = File(dir, "com/acme/Huge.class")
        huge.parentFile.mkdirs()
        huge.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(5) { out.write(chunk) }
        }

        assertEquals(DigestScan.Kind.UNREADABLE, output(dir).classDigests().refusal().kind)
    }

    @Test
    fun `an own jar that cannot be opened refuses`(@TempDir dir: File) {
        val jarDir = File(dir, "libs").apply { mkdirs() }
        val corrupt = File(jarDir, "own.jar").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }

        val refusal = TaskArtifacts(listOf(corrupt), listOf(dir.absolutePath), emptyList())
            .classDigests().refusal()

        assertEquals(DigestScan.Kind.UNREADABLE, refusal.kind)
    }

    @Test
    fun `a jar entry that cannot be understood refuses rather than skipping the class`(
        @TempDir dir: File,
    ) {
        val jarDir = File(dir, "libs").apply { mkdirs() }
        val packaged = jar(
            File(jarDir, "own.jar"),
            "com/acme/Good.class" to plainClass("com.acme.Good"),
            "com/acme/Broken.class" to byteArrayOf(1, 2, 3),
        )

        val refusal = TaskArtifacts(listOf(packaged), listOf(dir.absolutePath), emptyList())
            .classDigests().refusal()

        assertEquals(DigestScan.Kind.UNREADABLE, refusal.kind)
    }

    @Test
    fun `a spent file budget refuses, and says the budget is what spent it`(@TempDir dir: File) {
        // A budget is a number somebody can raise; every other refusal is a file to look at.
        classFile(dir, "com.acme.One", plainClass("com.acme.One"))
        classFile(dir, "com.acme.Two", plainClass("com.acme.Two"))

        val refusal = TaskArtifacts(listOf(dir), emptyList(), listOf(dir), maxFiles = 0)
            .classDigests().refusal()

        assertEquals(DigestScan.Kind.BUDGET_SPENT, refusal.kind)
        assertContains(refusal.reason, "budget")
    }

    @Test
    fun `a finished walk counts what it digested`(@TempDir dir: File) {
        // These counts are what the explanation carries.
        classFile(dir, "com.acme.One", plainClass("com.acme.One"))
        classFile(dir, "com.acme.Two", plainClass("com.acme.Two"))

        val scan = output(dir).classDigests()

        assertTrue(scan is DigestScan.Found)
        assertEquals(2, (scan as DigestScan.Found).counts.considered)
        assertEquals(2, scan.counts.digested)
        assertEquals(0, scan.counts.unreadable)
    }
}
