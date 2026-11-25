package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A changed source file is named by the classes the build compiled from it, so a name no class
 * carries (a flattened nested type, a typealias, a comment word, a moved path) forces nothing, and
 * a class no declaration names (a renamed or multifile facade) is still judged.
 */
class CompiledNamesTest {

    private fun classBytes(name: String, sourceFile: String?): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
        if (sourceFile != null) writer.visitSource(sourceFile, null)
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A module at [dir] with one source file and the classes its build wrote. */
    private class Module(val dir: File, val path: String) {
        val build = File(dir, "build")
        val classes = File(build, "classes/main")
    }

    private fun module(dir: File, path: String, text: String, vararg classes: String, sourceFile: String? = path.substringAfterLast('/')): Module {
        File(dir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent() + "\n") }
        val module = Module(dir, path)
        classes.forEach { name ->
            File(module.classes, name.replace('.', '/') + ".class").apply {
                parentFile.mkdirs()
                writeBytes(classBytes(name, sourceFile))
            }
        }
        module.classes.mkdirs()
        return module
    }

    /** [readAsSource]: the path names a test JVM read as a source file; none by default. */
    private fun resolve(
        module: Module,
        classpath: List<File> = listOf(module.classes),
        readAsSource: Set<String> = emptySet(),
    ): CompiledNames.Resolved {
        val artifacts = TaskArtifacts(classpath, listOf(module.build.absolutePath))
        return CompiledNames.resolve(
            ChangeDetection.split(listOf(module.path), module.dir), module.dir,
            { _, packageName, fileName -> artifacts.classesCompiledFrom(module.build.absolutePath, packageName, fileName) },
            { names -> names.filterTo(mutableSetOf()) { it in readAsSource } },
        )
    }

    @Test
    fun `a nested interface is named by its binary name, never flattened`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/java/pkg/Outer.java",
            """
            package pkg;
            public class Outer {
                public interface Listener { void on(); }
            }
            """,
            "pkg.Outer", "pkg.Outer\$Listener",
        )

        assertEquals(setOf("pkg.Outer", "pkg.Outer\$Listener"), resolve(module).change.classPrefixes)
    }

    @Test
    fun `a local class is named as javac numbers it`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/java/pkg/Outer.java",
            """
            package pkg;
            public class Outer {
                public Object make() {
                    class Closed { }
                    return new Closed();
                }
            }
            """,
            "pkg.Outer", "pkg.Outer\$1Closed",
        )

        assertEquals(setOf("pkg.Outer", "pkg.Outer\$1Closed"), resolve(module).change.classPrefixes)
    }

    @Test
    fun `a nested name two types share names both`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/java/pkg/A.java",
            """
            package pkg;
            public class A {
                static class Inner { }
            }
            class B {
                static class Inner { }
            }
            """,
            "pkg.A", "pkg.A\$Inner", "pkg.B", "pkg.B\$Inner",
        )

        assertEquals(setOf("pkg.A", "pkg.A\$Inner", "pkg.B", "pkg.B\$Inner"), resolve(module).change.classPrefixes)
    }

    @Test
    fun `a typealias no class carries is dropped and the class stays`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/kotlin/pkg/Foo.kt",
            """
            package pkg

            typealias Handler = (String) -> Unit

            class Foo {
                fun run(handler: Handler) = handler("x")
            }
            """,
            "pkg.Foo",
        )

        val resolved = resolve(module)

        assertEquals(setOf("pkg.Foo"), resolved.change.classPrefixes)
        assertEquals(1, resolved.dropped)
    }

    @Test
    fun `a comment line that reads like a declaration names nothing`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/java/pkg/Token.java",
            """
            package pkg;
            /**
             class so we can tell them apart
             */
            public class Token { public int kind() { return 1; } }
            """,
            "pkg.Token",
        )

        assertEquals(setOf("pkg.Token"), resolve(module).change.classPrefixes)
    }

    @Test
    fun `a moved Kotlin file is named by the class it declares, and its path stays for the inline scan`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/kotlin/pkg/FooMoved.kt",
            """
            package pkg

            class Foo {
                fun run() = 1
            }
            """,
            "pkg.Foo",
        )

        val change = resolve(module).change

        assertEquals(setOf("pkg.Foo"), change.classPrefixes)
        assertEquals(setOf("pkg.FooMoved"), change.inlineSources)
    }

    @Test
    fun `a path name a test JVM read the source file by stays, though no class carries it`(@TempDir dir: File) {
        val module = module(dir, "src/main/kotlin/pkg/utils.kt", "package pkg\n\nfun help() = 1", "pkg.UtilsKt")

        assertEquals(setOf("pkg.UtilsKt", "pkg.utils"), resolve(module, readAsSource = setOf("pkg.utils")).change.classPrefixes)
    }

    @Test
    fun `a module packaged as a jar has its classes listed from the jar`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/java/pkg/Outer.java",
            "package pkg;\npublic class Outer {\n    interface Listener { }\n}",
        )
        val jar = File(module.build, "libs/module.jar").apply { parentFile.mkdirs() }
        java.util.zip.ZipOutputStream(jar.outputStream()).use { zip ->
            for (name in listOf("pkg.Outer", "pkg.Outer\$Listener", "other.Outer")) {
                zip.putNextEntry(java.util.zip.ZipEntry(name.replace('.', '/') + ".class"))
                zip.write(classBytes(name, "Outer.java"))
                zip.closeEntry()
            }
        }

        assertEquals(setOf("pkg.Outer", "pkg.Outer\$Listener"), resolve(module, classpath = listOf(jar)).change.classPrefixes)
    }

    @Test
    fun `a facade JvmName renames is named, though no declaration names it`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/kotlin/pkg/Helpers.kt",
            """
            @file:JvmName("Utils")
            package pkg

            fun help() = 1
            """,
            "pkg.Utils",
        )

        assertEquals(setOf("pkg.Utils"), resolve(module).change.classPrefixes)
    }

    @Test
    fun `a lowercase Kotlin file is named by the file class kotlinc capitalises`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/kotlin/pkg/utils.kt",
            """
            package pkg

            fun help() = 1
            """,
            "pkg.UtilsKt",
        )

        val change = resolve(module).change

        assertEquals(setOf("pkg.UtilsKt"), change.classPrefixes)
        assertEquals(setOf("pkg.utils"), change.inlineSources)
    }

    @Test
    fun `a multifile part is named by its own class, not by the file`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/kotlin/pkg/Fold.kt",
            """
            @file:JvmMultifileClass
            @file:JvmName("RaiseKt")
            package pkg

            fun fold() = 1
            """,
            "pkg.RaiseKt__FoldKt",
        )

        assertEquals(setOf("pkg.RaiseKt__FoldKt"), resolve(module).change.classPrefixes)
    }

    @Test
    fun `the resolved names are each path's, so the line-only rule reads them`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/java/pkg/Outer.java",
            "package pkg;\npublic class Outer {\n    interface Listener { }\n}",
            "pkg.Outer", "pkg.Outer\$Listener",
        )

        assertEquals(
            mapOf(module.path to setOf("pkg.Outer", "pkg.Outer\$Listener")),
            resolve(module).change.prefixesByPath,
        )
    }

    @Test
    fun `a changed file is named by its own module's classes, not by a same-named file's in another`(@TempDir dir: File) {
        fun compiled(module: String, vararg names: String) = File(dir, "$module/build/classes/java/main").apply {
            names.forEach { name ->
                File(this, name.replace('.', '/') + ".class").apply { parentFile.mkdirs() }.writeBytes(classBytes(name, "Util.java"))
            }
        }
        File(dir, "app/src/main/java/dev/Util.java").apply { parentFile.mkdirs() }
            .writeText("package dev;\npublic class Util {\n    static class Mine { }\n}\n")
        val classpath = listOf(compiled("app", "dev.Util", "dev.Util\$Mine"), compiled("lib", "dev.Util", "dev.Util\$Theirs", "dev.Other"))
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val facts = ClasspathFacts(
            rootDir = dir,
            moduleDirs = mapOf("app" to ":app", "lib" to ":lib"),
            buildDirs = mapOf(":app" to File(dir, "app/build").absolutePath, ":lib" to File(dir, "lib/build").absolutePath),
            declared = project.provider { setOf(":lib") },
            compileTime = project.files(),
            ownPath = ":app",
            testOutputs = project.files(),
            classpath = project.files(classpath),
        )

        val scoped = scopedChange(listOf("app/src/main/java/dev/Util.java"), facts)

        assertEquals(setOf("dev.Util", "dev.Util\$Mine"), scoped.change.classPrefixes)
    }

    @Test
    fun `a file whose classes cannot be listed keeps the names its text gives`(@TempDir dir: File) {
        val source = "package pkg;\npublic class Outer {\n    interface Listener { }\n}"
        val text = setOf("pkg.Outer", "pkg.Listener")

        // Deleted: nothing to read a package from.
        val deleted = Module(dir, "src/main/java/pkg/Gone.java")
        assertEquals(setOf("pkg.Gone"), resolve(deleted).change.classPrefixes)

        // Compiled without a SourceFile attribute.
        val stripped = module(File(dir, "stripped"), "src/main/java/pkg/Outer.java", source, "pkg.Outer", "pkg.Outer\$Listener", sourceFile = null)
        assertEquals(text, resolve(stripped).change.classPrefixes)

        // Its module's output is not on the classpath.
        val elsewhere = module(File(dir, "elsewhere"), "src/main/java/pkg/Outer.java", source, "pkg.Outer", "pkg.Outer\$Listener")
        assertEquals(text, resolve(elsewhere, classpath = emptyList()).change.classPrefixes)

        // A class in its package directory cannot be read.
        val unreadable = module(File(dir, "unreadable"), "src/main/java/pkg/Outer.java", source, "pkg.Outer", "pkg.Outer\$Listener")
        File(unreadable.classes, "pkg/Broken.class").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(text, resolve(unreadable).change.classPrefixes)
    }

    @Test
    fun `a file of constants alone is kept aside with the names it would have`(@TempDir dir: File) {
        val module = module(dir, "src/main/java/pkg/Limits.java", "package pkg;\npublic interface Limits {\n    int MAX = 7;\n}")

        val change = ChangeDetection.split(listOf(module.path), dir)

        assertEquals(listOf(module.path), change.unmappablePaths)
        assertEquals(mapOf(module.path to setOf("pkg.Limits")), change.codeLess)
    }

    @Test
    fun `a file of constants alone whose class is listed is judged as that class`(@TempDir dir: File) {
        val module = module(dir, "src/main/java/pkg/Limits.java", "package pkg;\npublic interface Limits {\n    int MAX = 7;\n}", "pkg.Limits")

        val change = resolve(module).change

        assertEquals(emptyList(), change.unmappablePaths)
        assertEquals(setOf("pkg.Limits"), change.classPrefixes)
        assertEquals(mapOf(module.path to setOf("pkg.Limits")), change.prefixesByPath)
        assertEquals(false, change.inlinableSourceChanged)
    }

    @Test
    fun `a file of constants alone whose class is not listed stays unmappable`(@TempDir dir: File) {
        val module = module(dir, "src/main/java/pkg/Limits.java", "package pkg;\npublic interface Limits {\n    int MAX = 7;\n}")

        val change = resolve(module).change

        assertEquals(listOf(module.path), change.unmappablePaths)
        assertTrue(change.classPrefixes.isEmpty(), "got ${change.classPrefixes}")
    }

    @Test
    fun `an annotation type is judged as its class`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/java/pkg/Marker.java",
            "package pkg;\npublic @interface Marker {\n    String value() default \"\";\n}",
            "pkg.Marker",
        )

        val change = resolve(module).change

        assertEquals(emptyList(), change.unmappablePaths)
        assertEquals(setOf("pkg.Marker"), change.classPrefixes)
    }

    @Test
    fun `a Kotlin file the scan reads as code-less is routed as its file class, still inlinable`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/kotlin/pkg/utils.kt",
            """
            package pkg

            typealias Predicate<T> = (T) -> Boolean

            const val NAME: String = "x"

            fun <P1, T> constant(t: T): (P1) -> T = { _: P1 -> t }
            """,
            "pkg.UtilsKt",
        )
        val split = ChangeDetection.split(listOf(module.path), dir)
        check(split.unmappablePaths == listOf(module.path)) { "the scan reads this file as code: ${split.classPrefixes}" }

        val change = resolve(module).change

        assertEquals(emptyList(), change.unmappablePaths)
        assertEquals(setOf("pkg.UtilsKt"), change.classPrefixes)
        assertEquals(setOf("pkg.utils"), change.inlineSources)
        assertTrue(change.inlinableSourceChanged)
    }

    /** [module], moved from [oldPath] (no longer on disk), resolved with git's pair when [paired]. */
    private fun resolveMoved(module: Module, oldPath: String, paired: Boolean = true, inChange: Boolean = true): CompiledNames.Resolved {
        val artifacts = TaskArtifacts(listOf(module.classes), listOf(module.build.absolutePath))
        val paths = if (inChange) listOf(oldPath, module.path) else listOf(module.path)
        return CompiledNames.resolve(
            ChangeDetection.split(paths, module.dir), module.dir,
            { _, packageName, fileName -> artifacts.classesCompiledFrom(module.build.absolutePath, packageName, fileName) },
            { emptySet() },
            if (paired) mapOf(module.path to oldPath) else emptyMap(),
        )
    }

    private val movedFile = """
        package pkg

        class Foo

        fun twice(n: Int): Int = n * 2
    """

    @Test
    fun `a moved Kotlin file's path name and file class are judged as the file it moved from`(@TempDir dir: File) {
        val module = module(dir, "src/main/kotlin/pkg/FooMoved.kt", movedFile, "pkg.Foo", "pkg.FooMovedKt", "pkg.FooMovedKt\$twice\$1")

        val change = resolveMoved(module, "src/main/kotlin/pkg/Foo.kt").change

        // The daemon's own rules still read the new file's classes by their own names.
        assertTrue("pkg.FooMoved" in change.classPrefixes, change.classPrefixes.toString())
        assertEquals(mapOf("pkg.FooMoved" to "pkg.Foo"), change.movedNames)
        assertEquals("pkg.Foo", change.movedFrom("pkg.FooMoved"))
        assertEquals("pkg.FooKt", change.movedFrom("pkg.FooMovedKt"))
        assertEquals("pkg.FooKt\$twice\$1", change.movedFrom("pkg.FooMovedKt\$twice\$1"))
        assertEquals("pkg.Foo", change.movedFrom("pkg.Foo"))
        assertEquals("pkg.FooMovedKtx", change.movedFrom("pkg.FooMovedKtx"))
    }

    @Test
    fun `a new file git does not pair with a deleted one keeps its own names`(@TempDir dir: File) {
        val module = module(dir, "src/main/kotlin/pkg/FooMoved.kt", movedFile, "pkg.Foo", "pkg.FooMovedKt")

        assertEquals(emptyMap(), resolveMoved(module, "src/main/kotlin/pkg/Foo.kt", paired = false).change.movedNames)
    }

    @Test
    fun `a moved file whose old path is not in the change set keeps its own names`(@TempDir dir: File) {
        val module = module(dir, "src/main/kotlin/pkg/FooMoved.kt", movedFile, "pkg.Foo", "pkg.FooMovedKt")

        assertEquals(emptyMap(), resolveMoved(module, "src/main/kotlin/pkg/Foo.kt", inChange = false).change.movedNames)
    }

    @Test
    fun `a moved file that declares a class of its new name keeps that class's own name`(@TempDir dir: File) {
        val module = module(
            dir, "src/main/kotlin/pkg/FooMoved.kt",
            """
            package pkg

            class FooMoved

            fun twice(n: Int): Int = n * 2
            """,
            "pkg.FooMoved", "pkg.FooMovedKt",
        )

        assertEquals(emptyMap(), resolveMoved(module, "src/main/kotlin/pkg/Foo.kt").change.movedNames)
    }
}
