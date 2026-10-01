package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.InstrumentationScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstrumentationScopeTest {

    private fun source(root: File, path: String, declaredPackage: String?) {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                if (declaredPackage != null) appendLine("package $declaredPackage")
                appendLine("class Thing")
            }
        )
    }

    @Test
    fun `a file that declares nothing at all does not collapse the build's scope`(@TempDir dir: File) {
        // A wholly commented-out or empty file compiles to no class, so it cannot put one in the
        // default package; treating it as such would widen every module's scope to `*`.
        val src = File(dir, "src").apply { mkdirs() }
        source(src, "main/kotlin/com/acme/Thing.kt", "com.acme")

        val commentedOut = File(src, "main/kotlin/com/acme/AllComments.kt")
        commentedOut.writeText(
            "//package com.acme.inspections\n//\n//import x.Y\n//class Gone\n"
        )
        File(src, "main/kotlin/com/acme/Empty.kt").writeText("")
        File(src, "main/kotlin/com/acme/BlockCommented.kt").writeText(
            "/*\npackage com.acme.hidden\nclass AlsoGone\n*/\n"
        )

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a source file that cannot be read still collapses the build's scope`(@TempDir dir: File) {
        // An unreadable file may declare a class, so it widens the scope rather than narrowing it.
        // A dangling symlink is used because `setReadable(false)` does not stop a root user.
        val src = File(dir, "src").apply { mkdirs() }
        source(src, "main/kotlin/com/acme/Thing.kt", "com.acme")
        java.nio.file.Files.createSymbolicLink(
            File(src, "main/kotlin/com/acme/Ghost.kt").toPath(),
            File(dir, "does-not-exist.kt").toPath(),
        )

        assertEquals(setOf(InstrumentationScope.DEFAULT_PACKAGE), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `the scope comes from package declarations, not directory shape`(@TempDir dir: File) {
        // Not inferred from directories: a srcDir of `src` would yield `main.java.com.acme`, a
        // well-formed pattern matching no class.
        val src = File(dir, "src").apply { mkdirs() }
        source(src, "main/java/com/acme/Thing.kt", "com.acme")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `sibling packages are kept separate rather than merged into a parent`(@TempDir dir: File) {
        // Merging them into `com.acme` would widen the scope to packages this build never declared.
        val src = File(dir, "src").apply { mkdirs() }
        source(src, "a/Rules.kt", "com.acme.rules")
        source(src, "b/Core.kt", "com.acme.core")

        assertEquals(setOf("com.acme.core", "com.acme.rules"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `unrelated package roots are both kept`(@TempDir dir: File) {
        // A pattern that misses one of these silently leaves that package out of the map.
        val src = File(dir, "src").apply { mkdirs() }
        source(src, "a/A.kt", "com.acme.core")
        source(src, "b/B.kt", "dev.other")

        assertEquals(setOf("com.acme.core", "dev.other"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a package and its own subpackage collapse to the parent`(@TempDir dir: File) {
        val src = File(dir, "src").apply { mkdirs() }
        source(src, "a/A.kt", "com.acme")
        source(src, "b/B.kt", "com.acme.deeper")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a package is not collapsed into an unrelated one sharing a name prefix`(@TempDir dir: File) {
        // `com.acmecorp` does not belong under `com.acme`; collapsing it would widen the scope.
        val src = File(dir, "src").apply { mkdirs() }
        source(src, "a/A.kt", "com.acme")
        source(src, "b/B.kt", "com.acmecorp")

        assertEquals(setOf("com.acme", "com.acmecorp"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a file in the default package widens the scope to everything`(@TempDir dir: File) {
        // Nothing narrower covers it, and narrowing anyway would make it invisible to the map.
        // What decides is whether any code was seen, not whether a package line was found.
        val src = File(dir, "src").apply { mkdirs() }
        source(src, "Thing.kt", declaredPackage = null)
        source(src, "a/A.kt", "com.acme")

        assertEquals(setOf(InstrumentationScope.DEFAULT_PACKAGE), InstrumentationScope.derive(listOf(src)))
        assertEquals(listOf("*"), InstrumentationScope.toIncludePatterns(setOf(InstrumentationScope.DEFAULT_PACKAGE)))
    }

    @Test
    fun `a module-info does not widen the scope`(@TempDir dir: File) {
        // It declares a module, not a type, so widening for it buys no coverage.
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "module-info.java").writeText("module org.acme { exports com.acme; }")
        source(src, "a/A.kt", "com.acme")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a real default-package class still widens the scope even beside a module-info`(@TempDir dir: File) {
        // Narrowing away from a real default-package class makes it look untested: a silent skip.
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "module-info.java").writeText("module org.acme { exports com.acme; }")
        source(src, "Thing.kt", declaredPackage = null)
        source(src, "a/A.kt", "com.acme")

        assertEquals(setOf(InstrumentationScope.DEFAULT_PACKAGE), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a resources directory contributes nothing`(@TempDir dir: File) {
        // A source set's srcDirs include its resources directory.
        val resources = File(dir, "resources/dev/config").apply { mkdirs() }
        File(resources, "app.properties").writeText("x=1")

        assertTrue(InstrumentationScope.derive(listOf(File(dir, "resources"))).isEmpty())
    }

    @Test
    fun `java and kotlin sources both contribute`(@TempDir dir: File) {
        val kotlin = File(dir, "kotlin").apply { mkdirs() }
        val java = File(dir, "java").apply { mkdirs() }
        source(kotlin, "K.kt", "com.acme.k")
        source(java, "J.java", "com.acme.j")

        assertEquals(setOf("com.acme.j", "com.acme.k"), InstrumentationScope.derive(listOf(kotlin, java)))
    }

    @Test
    fun `a package declaration with unusual spacing is still read`(@TempDir dir: File) {
        val src = File(dir, "src").apply { mkdirs() }
        val file = File(src, "A.kt")
        file.writeText("   package   com . acme  \nclass A")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a package declaration after a licence header is still read`(@TempDir dir: File) {
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "A.kt").writeText("/*\n * Copyright\n */\n\npackage com.acme\n\nclass A")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a package declaration behind a licence header longer than fifty lines is still read`(@TempDir dir: File) {
        // A long licence header must not push the package line past any fixed scan bound.
        val src = File(dir, "src").apply { mkdirs() }
        val header = (1..200).joinToString("\n") { " * Copyright line $it" }
        File(src, "A.java").writeText("/*\n$header\n */\npackage com.acme;\nclass A {}")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `one unreadable package declaration does not widen the whole build to everything`(@TempDir dir: File) {
        // One file read as the default package collapses every other project's scope with it.
        val src = File(dir, "src").apply { mkdirs() }
        val header = (1..200).joinToString("\n") { " * Copyright line $it" }
        File(src, "far/package-info.java").apply { parentFile.mkdirs() }
            .writeText("/*\n$header\n */\npackage com.acme.far;")
        source(src, "near/Near.java", "com.acme.near")

        assertEquals(setOf("com.acme.far", "com.acme.near"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `an annotation before the package declaration does not hide it`(@TempDir dir: File) {
        // How a package-info.java usually carries nullability defaults, and how a Kotlin file
        // carries @file:JvmName.
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "package-info.java").writeText("@NonNullApi\n@NonNullFields\npackage com.acme;")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `an annotation spanning several lines before the package declaration does not hide it`(
        @TempDir dir: File,
    ) {
        // Multi-line file annotations may precede the package line, so the scan cannot stop at the
        // first non-comment token.
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "A.kt").writeText(
            "@file:Suppress(\n  \"INVISIBLE_MEMBER\",\n  \"INVISIBLE_REFERENCE\",\n)\n\npackage com.acme\n\nclass A"
        )

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `prose inside a licence header does not become the scope`(@TempDir dir: File) {
        // A comment line reading `package private helpers` must not scope the build to `private`,
        // which would make every class invisible to the map.
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "A.java").writeText("/*\npackage private helpers\n*/\npackage com.acme;\nclass A {}")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a commented-out package declaration is not read`(@TempDir dir: File) {
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "A.java").writeText("// package com.commented;\npackage com.acme;\nclass A {}")

        assertEquals(setOf("com.acme"), InstrumentationScope.derive(listOf(src)))
    }

    @Test
    fun `a non-existent source directory contributes nothing rather than failing`(@TempDir dir: File) {
        assertTrue(InstrumentationScope.derive(listOf(File(dir, "missing"))).isEmpty())
    }

    @Test
    fun `deriving does not follow a directory chain past a sane depth`(@TempDir dir: File) {
        // walkTopDown follows symlinks without cycle detection; the depth cap bounds a cycle.
        var deep = File(dir, "src")
        repeat(200) { deep = File(deep, "x") }
        deep.mkdirs()
        File(deep, "A.kt").writeText("package too.deep\nclass A")

        assertTrue(InstrumentationScope.derive(listOf(File(dir, "src"))).isEmpty())
    }

    @Test
    fun `package roots become jacoco include patterns`() {
        assertEquals(
            listOf("com.acme.*", "dev.other.*"),
            InstrumentationScope.toIncludePatterns(setOf("dev.other", "com.acme")),
        )
    }

    @Test
    fun `an empty scope yields null rather than throwing`() {
        // A module with tests but no main sources is legitimate; the plugin must not fail its build.
        assertNull(InstrumentationScope.toIncludePatterns(emptySet()))
    }
}
