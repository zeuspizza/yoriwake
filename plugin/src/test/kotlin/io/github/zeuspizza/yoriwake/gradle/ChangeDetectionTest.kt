package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import org.gradle.api.provider.ProviderFactory
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The split between what the coverage map can reason about and what it cannot.
 *
 * Getting this wrong in one direction costs time; in the other it skips tests. A path dropped
 * rather than reported as unmappable turns "I cannot reason about this" into "this changed
 * nothing", and the selector acts on the second by running almost nothing.
 */
class ChangeDetectionTest {

    private val providers: ProviderFactory = ProjectBuilder.builder().build().providers


    @Test
    fun `a java-only change set is not inlinable, and anything else is`() {
        // The input to the missing-SMAP refusal. Only a changed Kotlin source can be the body a
        // caller inlines, so only that case needs Kotlin's SMAP signal to be present.
        assertFalse(
            ChangeDetection.split(listOf("src/main/java/com/acme/Thing.java")).inlinableSourceChanged,
            "a Java-only change set has no inline body to be blind to",
        )
        assertTrue(
            ChangeDetection.split(listOf("src/main/kotlin/com/acme/Thing.kt")).inlinableSourceChanged,
            "a changed Kotlin source can be inlined into a caller",
        )
        assertTrue(
            ChangeDetection.split(
                listOf("src/main/java/com/acme/Thing.java", "src/main/kotlin/com/acme/Other.kt"),
            ).inlinableSourceChanged,
            "one Kotlin source among Java ones is enough to need the signal",
        )
        // An unrecognised language is unmappable and forces a full run on its own, so false is safe.
        val groovy = ChangeDetection.split(listOf("src/main/groovy/com/acme/Thing.groovy"))
        assertFalse(groovy.inlinableSourceChanged)
        assertEquals(
            listOf("src/main/groovy/com/acme/Thing.groovy"),
            groovy.unmappablePaths,
            "a Groovy path must still force, which is what makes the flag above harmless",
        )
        assertFalse(
            ChangeDetection.split(emptyList()).inlinableSourceChanged,
            "an empty change set has no changed source to inline",
        )
    }

    @Test
    fun `a kotlin source path becomes a class prefix`() {
        val change = ChangeDetection.split(listOf("app/src/main/kotlin/com/acme/Rules.kt"))

        assertEquals(setOf("com.acme.Rules"), change.classPrefixes)
        assertTrue(change.unmappablePaths.isEmpty())
    }

    @Test
    fun `java sources and test sources both map`() {
        val change = ChangeDetection.split(
            listOf(
                "app/src/test/kotlin/com/acme/RulesSpec.kt",
                "lib/src/main/java/com/other/Thing.java",
            )
        )

        assertEquals(setOf("com.acme.RulesSpec", "com.other.Thing"), change.classPrefixes)
    }

    @Test
    fun `a custom source set still maps`() {
        // src/integrationTest/... is as legitimate as src/main; refusing to map it would force a
        // full run for every integration-test edit.
        val change = ChangeDetection.split(listOf("app/src/integrationTest/kotlin/com/acme/A.kt"))

        assertEquals(setOf("com.acme.A"), change.classPrefixes)
    }

    @Test
    fun `a single-project build with no module directory still maps`() {
        // Requiring a module segment made every change in a single-project build unmappable, which
        // is safe and useless: it forces a full run for every edit.
        val change = ChangeDetection.split(listOf("src/main/java/dev/sample/Alpha.java"))

        assertEquals(setOf("dev.sample.Alpha"), change.classPrefixes)
    }

    @Test
    fun `windows separators are normalised`() {
        val change = ChangeDetection.split(listOf("""app\src\main\kotlin\com\acme\A.kt"""))

        assertEquals(setOf("com.acme.A"), change.classPrefixes)
    }

    @Test
    fun `a build script is unmappable, not ignored`() {
        val change = ChangeDetection.split(listOf("app/build.gradle.kts"))

        assertTrue(change.classPrefixes.isEmpty())
        assertEquals(listOf("app/build.gradle.kts"), change.unmappablePaths)
    }

    @Test
    fun `a version catalog is unmappable`() {
        assertEquals(
            listOf("gradle/libs.versions.toml"),
            ChangeDetection.split(listOf("gradle/libs.versions.toml")).unmappablePaths,
        )
    }

    @Test
    fun `a resource is unmappable because coverage cannot see it`() {
        assertEquals(
            listOf("app/src/main/resources/config.yml"),
            ChangeDetection.split(listOf("app/src/main/resources/config.yml")).unmappablePaths,
        )
    }

    @Test
    fun `a mapped and an unmappable path in one change are both reported`() {
        // The unmappable one forces a full run, so losing it would be a silent skip.
        val change = ChangeDetection.split(
            listOf("app/src/main/kotlin/com/acme/A.kt", "app/build.gradle.kts")
        )

        assertEquals(setOf("com.acme.A"), change.classPrefixes)
        assertEquals(listOf("app/build.gradle.kts"), change.unmappablePaths)
    }

    @Test
    fun `a source file outside any module directory is unmappable`() {
        // Nothing to anchor a package to; treating it as mappable would invent a class name.
        assertEquals(
            listOf("Stray.kt"),
            ChangeDetection.split(listOf("Stray.kt")).unmappablePaths,
        )
    }

    @Test
    fun `blank paths are ignored entirely`() {
        val change = ChangeDetection.split(listOf("", "   "))

        assertTrue(change.classPrefixes.isEmpty())
        assertTrue(change.unmappablePaths.isEmpty())
    }

    @Test
    fun `git warnings are not mistaken for changed paths`() {
        // git writes advisory warnings to stderr — on Windows, one CRLF notice per staged file.
        // Merged into stdout, each warning would read as an unexplainable "changed path".
        val repo = java.nio.file.Files.createTempDirectory("yoriwake-repo").toFile()
        try {
            fun git(vararg args: String) = ProcessBuilder("git", *args)
                .directory(repo).redirectErrorStream(true).start().waitFor()
            git("init")
            git("config", "core.autocrlf", "true")
            File(repo, "src/main/kotlin/com/acme").mkdirs()
            File(repo, "src/main/kotlin/com/acme/A.kt").writeText("class A\n")
            git("add", ".")
            git("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")
            File(repo, "src/main/kotlin/com/acme/A.kt").writeText("class A { val n = 1 }\n")

            assertEquals(
                listOf("src/main/kotlin/com/acme/A.kt"),
                ChangeDetection.changedPaths(providers, repo, "HEAD"),
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `a file git has never seen is part of the change set`() {
        // `git diff` cannot see an untracked file, and an empty change set would run almost nothing.
        val repo = java.nio.file.Files.createTempDirectory("yoriwake-untracked").toFile()
        try {
            fun git(vararg args: String) = ProcessBuilder("git", *args)
                .directory(repo).redirectErrorStream(true).start().waitFor()
            git("init")
            File(repo, "src/main/kotlin/com/acme").mkdirs()
            File(repo, "src/main/kotlin/com/acme/Old.kt").writeText("class Old")
            git("add", ".")
            git("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")
            File(repo, "src/main/kotlin/com/acme/New.kt").writeText("class New")

            val paths = ChangeDetection.changedPaths(providers, repo, "HEAD")

            assertEquals(listOf("src/main/kotlin/com/acme/New.kt"), paths)
            assertEquals(setOf("com.acme.New"), ChangeDetection.split(paths!!).classPrefixes)
        } finally {
            repo.deleteRecursively()
        }
    }

    private fun sourceTree(vararg files: Pair<String, String>): File {
        val root = java.nio.file.Files.createTempDirectory("yoriwake-decl").toFile()
        files.forEach { (path, content) ->
            File(root, path).apply { parentFile.mkdirs() }.writeText(content)
        }
        return root
    }

    @Test
    fun `a file declaring a differently named type contributes that type`() {
        // From the path alone this file would be com.acme.Foo, a name nothing in the map or the
        // loaded set will ever match.
        val root = sourceTree(
            "src/main/kotlin/com/acme/Foo.kt" to "package com.acme\n\nclass Bar { val n = 1 }\n"
        )
        try {
            val change = ChangeDetection.split(listOf("src/main/kotlin/com/acme/Foo.kt"), root)

            assertTrue(change.classPrefixes.contains("com.acme.Bar"), "got ${change.classPrefixes}")
            // The path-derived name stays: it covers the FooKt class for top-level declarations.
            assertTrue(change.classPrefixes.contains("com.acme.Foo"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `enum, fun interface and typealias are all contributed`() {
        // A missed declaration costs a skipped test: a name nothing matches reads as "never loaded".
        val root = sourceTree(
            "src/main/kotlin/com/acme/Types.kt" to
                "package com.acme\n\nenum class Status { A }\nfun interface Handler { fun run() }\n" +
                "typealias Alias = String\n"
        )
        try {
            val prefixes = ChangeDetection.split(listOf("src/main/kotlin/com/acme/Types.kt"), root)
                .classPrefixes

            assertTrue(prefixes.contains("com.acme.Status"), "got $prefixes")
            assertTrue(prefixes.contains("com.acme.Handler"), "got $prefixes")
            assertTrue(prefixes.contains("com.acme.Alias"), "got $prefixes")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a Java enum is contributed`() {
        val root = sourceTree(
            "src/main/java/com/acme/Kind.java" to "package com.acme;\npublic enum Kind { A, B }\n"
        )
        try {
            assertTrue(
                ChangeDetection.split(listOf("src/main/java/com/acme/Kind.java"), root)
                    .classPrefixes.contains("com.acme.Kind")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a file of only constants is unmappable, because its values are inlined`() {
        // javac inlines static final primitives and Strings into every dependent, so the holder may
        // never be loaded while its dependents change. Only a full run is safe.
        val root = sourceTree(
            "src/main/java/com/acme/Limits.java" to
                "package com.acme;\npublic interface Limits {\n    int MAX = 7;\n}\n"
        )
        try {
            val change = ChangeDetection.split(listOf("src/main/java/com/acme/Limits.java"), root)

            assertEquals(listOf("src/main/java/com/acme/Limits.java"), change.unmappablePaths)
            assertTrue(change.classPrefixes.isEmpty(), "got ${change.classPrefixes}")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an interface with a default method is a normal prefix`() {
        val root = sourceTree(
            "src/main/java/com/acme/Greeter.java" to
                "package com.acme;\npublic interface Greeter {\n    default String hi() { return \"hi\"; }\n}\n"
        )
        try {
            val change = ChangeDetection.split(listOf("src/main/java/com/acme/Greeter.java"), root)

            assertTrue(change.unmappablePaths.isEmpty(), "got ${change.unmappablePaths}")
            assertTrue(change.classPrefixes.contains("com.acme.Greeter"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a package-private Java type is contributed`() {
        val root = sourceTree(
            "src/main/java/com/acme/Api.java" to
                "package com.acme;\npublic class Api {}\nclass Helper {}\n"
        )
        try {
            val prefixes = ChangeDetection.split(listOf("src/main/java/com/acme/Api.java"), root)
                .classPrefixes

            assertTrue(prefixes.containsAll(listOf("com.acme.Api", "com.acme.Helper")), "got $prefixes")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a deleted file falls back to the path, rather than losing the change`() {
        // Unreadable is the ordinary case for a deleted or moved file. The path-derived prefix still
        // stands, and a prefix nothing matches forces a full run rather than skipping one.
        val root = sourceTree("keep.txt" to "x")
        try {
            val change = ChangeDetection.split(listOf("src/main/kotlin/com/acme/Gone.kt"), root)

            assertEquals(setOf("com.acme.Gone"), change.classPrefixes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a moved file reports both the path it left and the path it arrived at`() {
        // With rename detection on, git names only the destination, and the tests covering the class
        // that vanished from the old path are never selected.
        val repo = java.nio.file.Files.createTempDirectory("yoriwake-rename").toFile()
        try {
            fun git(vararg args: String) = ProcessBuilder("git", *args)
                .directory(repo).redirectErrorStream(true).start().waitFor()
            git("init")
            File(repo, "src/main/kotlin/com/acme").mkdirs()
            File(repo, "src/main/kotlin/com/acme/Old.kt").writeText("class Old { val n = 1 }")
            git("add", ".")
            git("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")
            git("mv", "src/main/kotlin/com/acme/Old.kt", "src/main/kotlin/com/acme/New.kt")

            val paths = ChangeDetection.changedPaths(providers, repo, "HEAD").orEmpty()

            assertEquals(
                setOf("src/main/kotlin/com/acme/Old.kt", "src/main/kotlin/com/acme/New.kt"),
                paths.toSet(),
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `a base that git would read as an option is refused`() {
        // git diff --output=<path> writes files. Refused rather than ignored, so a declined base
        // never silently becomes HEAD.
        val repo = java.nio.file.Files.createTempDirectory("yoriwake-dash").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                ChangeDetection.changedPaths(providers, repo, "--output=pwned.txt")
            }
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `the default base is the merge base with the branch it came from`() {
        // HEAD would compare against the working tree only, so committed work would look unchanged.
        val repo = java.nio.file.Files.createTempDirectory("yoriwake-base").toFile()
        try {
            fun git(vararg args: String) = ProcessBuilder("git", *args)
                .directory(repo).redirectErrorStream(true).start().waitFor()
            git("init", "--initial-branch=main")
            File(repo, "src/main/kotlin/com/acme").mkdirs()
            File(repo, "src/main/kotlin/com/acme/A.kt").writeText("class A")
            git("add", ".")
            git("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")
            val mainTip = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(repo).start().inputStream.bufferedReader().readText().trim()

            git("checkout", "-b", "feature")
            File(repo, "src/main/kotlin/com/acme/B.kt").writeText("class B")
            git("add", ".")
            git("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "work")

            val base = ChangeDetection.defaultBase(providers, repo)

            assertEquals(mainTip, base?.ref)
            assertEquals(
                setOf("com.acme.B"),
                ChangeDetection.split(
                    ChangeDetection.changedPaths(providers, repo, base!!.ref).orEmpty()
                ).classPrefixes,
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `a CI-shaped checkout of a project whose default branch is not main resolves no base`() {
        // The shape `actions/checkout` produces: a detached HEAD, no upstream, no origin/HEAD. The
        // name list only rescues a default branch it knows.
        //
        // Every git exit code is checked: a failed setup would leave an empty directory where both
        // assertions pass vacuously.
        val origin = java.nio.file.Files.createTempDirectory("yoriwake-origin").toFile()
        val clone = java.nio.file.Files.createTempDirectory("yoriwake-ci").toFile()
        try {
            fun git(dir: File, vararg args: String) {
                val code = ProcessBuilder("git", *args)
                    .directory(dir).redirectErrorStream(true).start().waitFor()
                check(code == 0) { "git ${args.joinToString(" ")} failed with $code" }
            }
            git(origin, "init", "--initial-branch=trunk")
            File(origin, "src/main/kotlin/com/acme").mkdirs()
            File(origin, "src/main/kotlin/com/acme/A.kt").writeText("class A")
            git(origin, "add", ".")
            git(origin, "-c", "user.email=t@e.com", "-c", "user.name=t", "-c", "commit.gpgsign=false",
                "commit", "-m", "base")

            clone.deleteRecursively()
            val cloned = ProcessBuilder("git", "clone", origin.absolutePath, clone.absolutePath)
                .redirectErrorStream(true).start().waitFor()
            check(cloned == 0) { "git clone failed with $cloned" }
            git(clone, "remote", "set-head", "origin", "--delete")
            val head = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(clone).start().inputStream.bufferedReader().readText().trim()
            check(head.isNotEmpty()) { "no HEAD in the clone" }
            git(clone, "checkout", "--detach", head)

            assertEquals(null, ChangeDetection.defaultBase(providers, clone))

            // That an empty change set forces a full run is pinned in ChangeSelectionTest.
        } finally {
            origin.deleteRecursively()
            clone.deleteRecursively()
        }
    }

    @Test
    fun `a build under a subdirectory of the repo gets paths rebased onto its own root`() {
        // `git diff --name-only` reports paths relative to the repository root even when run from a
        // subdirectory; everything downstream resolves them against the Gradle root.
        // `git ls-files --others` prints cwd-relative paths instead, so only the diff list is rebased.
        val repo = java.nio.file.Files.createTempDirectory("yoriwake-monorepo").toFile()
        try {
            fun git(vararg args: String) {
                val code = ProcessBuilder("git", *args)
                    .directory(repo).redirectErrorStream(true).start().waitFor()
                check(code == 0) { "git ${args.joinToString(" ")} failed with $code" }
            }
            git("init", "--initial-branch=main")
            val build = File(repo, "backend")
            File(build, "src/main/kotlin/com/acme").mkdirs()
            File(build, "src/main/kotlin/com/acme/A.kt").writeText("class A")
            File(repo, "unrelated").mkdirs()
            File(repo, "unrelated/Outside.kt").writeText("class Outside")
            git("add", ".")
            git("-c", "user.email=t@e.com", "-c", "user.name=t", "-c", "commit.gpgsign=false",
                "commit", "-m", "base")
            File(build, "src/main/kotlin/com/acme/A.kt").writeText("class A { fun f() = 1 }")
            File(repo, "unrelated/Outside.kt").writeText("class Outside { fun g() = 2 }")

            // The Gradle root is `backend`, not the repository root.
            val paths = ChangeDetection.changedPaths(providers, build, "HEAD").orEmpty()

            // Rebased onto the build, so `split` can resolve the file and read its declared types.
            assertContains(paths, "src/main/kotlin/com/acme/A.kt")
            // Outside the build: kept verbatim, so it stays unmappable and forces a full run.
            assertContains(paths, "unrelated/Outside.kt")
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `no resolvable branch base yields null so the caller can fall back`() {
        val repo = java.nio.file.Files.createTempDirectory("yoriwake-nobase").toFile()
        try {
            ProcessBuilder("git", "init").directory(repo).redirectErrorStream(true).start().waitFor()

            assertEquals(null, ChangeDetection.defaultBase(providers, repo))
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `git failure yields null rather than an empty change set`() {
        // Empty means "nothing changed", which the selector acts on by running almost nothing.
        // "git could not tell us" must force a full run instead, so the two cannot share a value.
        val notARepo = java.nio.file.Files.createTempDirectory("yoriwake-not-a-repo").toFile()
        try {
            assertEquals(null, ChangeDetection.changedPaths(providers, notARepo, "HEAD"))
        } finally {
            notARepo.delete()
        }
    }

    @Test
    fun `a type whose declaration carries an annotation on the same line is still named`(
        @TempDir dir: File,
    ) {
        // A type no prefix names is one no rule can reason about: it can neither force nor be
        // vouched for.
        val file = File(dir, "src/main/kotlin/com/acme/Ids.kt").apply { parentFile.mkdirs() }
        file.writeText(
            """
            package com.acme

            @JvmInline
            value class Wrapped(val raw: String)

            @Deprecated("moved") class Legacy
            """.trimIndent()
        )

        val change = ChangeDetection.split(listOf("src/main/kotlin/com/acme/Ids.kt"), dir)

        assertContains(change.classPrefixes, "com.acme.Wrapped")
        assertContains(change.classPrefixes, "com.acme.Legacy")
    }
}

/**
 * Declarations inside code samples are data, not declarations.
 *
 * Tests often hold Kotlin samples in raw strings. A class that exists only there is covered by
 * nothing, so naming it in the change set would needlessly force a full run.
 */
class RawStringDeclarationsTest {

    /** Three double quotes, built rather than written, so this file can talk about raw strings. */
    private val q = "\"".repeat(3)

    private fun typesOf(dir: File, source: String): Set<String> {
        val path = "m/src/test/kotlin/dev/sample/Spec.kt"
        File(dir, path).parentFile.mkdirs()
        File(dir, path).writeText(source)
        return ChangeDetection.split(listOf(path), dir).classPrefixes
    }

    @Test
    fun `a class declared inside a raw string is not a declared type`(@TempDir dir: File) {
        val types = typesOf(
            dir,
            "package dev.sample\nclass Spec {\n    val code = $q\n        class Foo\n" +
                "        class Bar\n    $q\n}\n",
        )

        assertTrue("dev.sample.Spec" in types, "the real declaration must survive; got $types")
        assertFalse("dev.sample.Foo" in types, "Foo exists only inside a code sample; got $types")
        assertFalse("dev.sample.Bar" in types, "Bar exists only inside a code sample; got $types")
    }

    @Test
    fun `a declaration after a raw string is still found`(@TempDir dir: File) {
        val types = typesOf(
            dir,
            "package dev.sample\nval code = $q\n    class Foo\n$q\nclass Real\n",
        )

        assertTrue("dev.sample.Real" in types, "got $types")
        assertFalse("dev.sample.Foo" in types, "got $types")
    }

    @Test
    fun `an unbalanced delimiter suppresses nothing`(@TempDir dir: File) {
        // A wrongly suppressed declaration is a silently skipped test, so an unparseable file keeps
        // every candidate.
        val types = typesOf(dir, "package dev.sample\nval code = $q\nclass Foo\n")

        assertTrue("dev.sample.Foo" in types, "an unparsable file must keep every candidate; got $types")
    }

    @Test
    fun `a triple quote in a comment does not mis-pair the real delimiters`(@TempDir dir: File) {
        // Pairing three-quote runs in order, ignoring comments, would shift by one here and
        // swallow `class Real` while keeping the phantom `Foo`.
        val types = typesOf(
            dir,
            "package dev.sample\n" +
                "// a raw string starts with " + q + "\n" +
                "val sample = " + q + "\n    class Foo\n" + q + "\n" +
                "class Real\n" +
                "// and it ends with " + q + "\n" +
                "class AlsoReal\n",
        )

        assertTrue("dev.sample.Real" in types, "a real class must survive; got $types")
        assertTrue("dev.sample.AlsoReal" in types, "a real class must survive; got $types")
        assertFalse("dev.sample.Foo" in types, "Foo is only a code sample; got $types")
    }

    @Test
    fun `a triple quote in a block comment is not a delimiter`(@TempDir dir: File) {
        val types = typesOf(
            dir,
            "package dev.sample\n/* KDoc mentioning " + q + " here */\nclass Real\n",
        )

        assertTrue("dev.sample.Real" in types, "got $types")
    }

    @Test
    fun `an unbalanced block comment suppresses nothing`(@TempDir dir: File) {
        // A comment that never closes means everything after it was read as code. Suppressing on
        // that reading is how a real declaration is lost, so the answer is to suppress nothing.
        val types = typesOf(dir, "package dev.sample\n/* never closed " + q + "\nclass Real\n")

        assertTrue("dev.sample.Real" in types, "got $types")
    }

    @Test
    fun `a declaration on the line after a closing delimiter survives`(@TempDir dir: File) {
        // A range ending one character too late would swallow the next line.
        val types = typesOf(
            dir,
            "package dev.sample\nval a = " + q + "\nclass Foo\n" + q + "\nclass Real\n",
        )

        assertTrue("dev.sample.Real" in types, "got $types")
        assertFalse("dev.sample.Foo" in types, "got $types")
    }

    @Test
    fun `a raw string whose content ends in a quote is consumed whole`(@TempDir dir: File) {
        // The terminator is the last three quotes of a run; stopping at the first shifts every
        // later pairing.
        val types = typesOf(
            dir,
            "package dev.sample\nval a = " + q + "abc\"" + q + "\nclass Real\n" +
                "val b = " + q + "\nclass Foo\n" + q + "\n",
        )

        assertTrue("dev.sample.Real" in types, "got $types")
        assertFalse("dev.sample.Foo" in types, "got $types")
    }

    @Test
    fun `an empty raw string swallows no neighbouring code`(@TempDir dir: File) {
        val types = typesOf(dir, "package dev.sample\nval a = " + q + q + "\nclass Real\n")

        assertTrue("dev.sample.Real" in types, "got $types")
    }

    @Test
    fun `a file with no raw strings keeps every declaration`(@TempDir dir: File) {
        val types = typesOf(dir, "package dev.sample\nclass One\nclass Two\ninterface Three\n")

        assertTrue("dev.sample.One" in types, "got $types")
        assertTrue("dev.sample.Two" in types, "got $types")
        assertTrue("dev.sample.Three" in types, "got $types")
    }

    @Test
    fun `a package statement inside a code sample is not the file's package`(@TempDir dir: File) {
        val types = typesOf(
            dir,
            "val a = " + q + "\npackage wrong.place\nclass Foo\n" + q + "\nclass Real\n",
        )

        assertTrue("Real" in types, "the real class is in the default package here; got $types")
        assertFalse("wrong.place.Real" in types, "got $types")
    }

    @Test
    fun `two separate raw strings are both suppressed`(@TempDir dir: File) {
        val types = typesOf(
            dir,
            "package dev.sample\nval a = $q\nclass Foo\n$q\nclass Middle\nval b = $q\nclass Bar\n$q\n",
        )

        assertTrue("dev.sample.Middle" in types, "got $types")
        assertFalse("dev.sample.Foo" in types, "got $types")
        assertFalse("dev.sample.Bar" in types, "got $types")
    }

}
