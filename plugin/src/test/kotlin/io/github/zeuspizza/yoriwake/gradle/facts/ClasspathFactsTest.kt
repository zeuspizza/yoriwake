package io.github.zeuspizza.yoriwake.gradle.facts

import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.establish
import io.github.zeuspizza.yoriwake.gradle.change.scopedChange
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * What the classpath rule answers over fixed inputs, pinned before its logic moves out of the
 * Gradle plugin: null declared modules mean unknown, and an empty answer means drop nothing.
 */
class ClasspathFactsTest {

    /** A module `:app` at `app/` beside `:lib` at `lib/` and `:lib:nested` at `lib/nested/`. */
    private val moduleDirs = mapOf("app" to ":app", "lib" to ":lib", "lib/nested" to ":lib:nested")

    private fun facts(dir: File, classpath: List<File>, declared: Set<String>?): ClasspathFacts {
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        return ClasspathFacts(
            rootDir = dir,
            moduleDirs = moduleDirs,
            buildDirs = mapOf(
                ":app" to File(dir, "app/build").absolutePath,
                ":lib" to File(dir, "lib/build").absolutePath,
                ":lib:nested" to File(dir, "lib/nested/build").absolutePath,
            ),
            declared = project.provider { declared },
            compileTime = project.files(),
            ownPath = ":app",
            testOutputs = project.files(),
            classpath = project.files(classpath),
        )
    }

    /** A valid jar holding one resource, whose classes name nothing. */
    private fun jar(dir: File, path: String) = File(dir, path).apply {
        parentFile.mkdirs()
        java.util.zip.ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
        }
    }

    @Test
    fun `an unknown declared set answers drop nothing`(@TempDir dir: File) {
        assertEquals(emptySet(), modulesOnClasspath(facts(dir, listOf(jar(dir, "lib/build/libs/lib.jar")), null)))
    }

    @Test
    fun `Konsist on the classpath answers drop nothing`(@TempDir dir: File) {
        val classpath = listOf(jar(dir, "lib/build/libs/lib.jar"), jar(dir, "cache/konsist-0.17.3.jar"))

        assertEquals(emptySet(), modulesOnClasspath(facts(dir, classpath, setOf(":lib"))))
    }

    @Test
    fun `an empty classpath answers drop nothing`(@TempDir dir: File) {
        assertEquals(emptySet(), modulesOnClasspath(facts(dir, emptyList(), setOf(":lib"))))
    }

    @Test
    fun `a jar under a module's build directory puts that module on the classpath, with the declared ones and its own`(
        @TempDir dir: File,
    ) {
        val classpath = listOf(jar(dir, "lib/nested/build/libs/nested.jar"))

        assertEquals(setOf(":lib", ":lib:nested", ":app"), modulesOnClasspath(facts(dir, classpath, setOf(":lib"))))
    }

    @Test
    fun `an empty declared set still counts the build directories on the classpath and the module itself`(@TempDir dir: File) {
        val classpath = listOf(jar(dir, "lib/build/libs/lib.jar"))

        assertEquals(setOf(":lib", ":app"), modulesOnClasspath(facts(dir, classpath, emptySet())))
    }

    @Test
    fun `a change set within the module keeps every path`(@TempDir dir: File) {
        val scoped = scopedChange(
            listOf("app/src/main/java/dev/A.java", "app/src/main/resources/a.properties"),
            facts(dir, listOf(jar(dir, "lib/build/libs/lib.jar")), setOf(":lib")),
        )

        assertEquals(0, scoped.dropped)
        assertEquals(setOf("dev.A"), scoped.change.classPrefixes)
        assertEquals(listOf("app/src/main/resources/a.properties"), scoped.change.unmappablePaths)
    }

    @Test
    fun `a nested module off the classpath is dropped while its parent on it is kept`(@TempDir dir: File) {
        val scoped = scopedChange(
            listOf("lib/src/main/java/dev/L.java", "lib/nested/src/main/java/dev/N.java"),
            facts(dir, listOf(jar(dir, "lib/build/libs/lib.jar")), setOf(":lib")),
        )

        assertEquals(1, scoped.dropped)
        assertEquals(setOf("dev.L"), scoped.change.classPrefixes)
    }

    @Test
    fun `a classpath whose classes cannot be read drops nothing`(@TempDir dir: File) {
        val unreadable = File(dir, "lib/build/libs/lib.jar").apply { parentFile.mkdirs(); writeText("not a jar") }
        val scoped = scopedChange(
            listOf("lib/src/main/java/dev/L.java", "lib/nested/src/main/java/dev/N.java"),
            facts(dir, listOf(unreadable), setOf(":lib")),
        )

        assertEquals(0, scoped.dropped)
        assertEquals(setOf("dev.L", "dev.N"), scoped.change.classPrefixes)
    }

    @Test
    fun `an unknown declared set drops no module's change`(@TempDir dir: File) {
        val scoped = scopedChange(
            listOf("lib/nested/src/main/java/dev/N.java"),
            facts(dir, listOf(jar(dir, "lib/build/libs/lib.jar")), null),
        )

        assertEquals(0, scoped.dropped)
        assertEquals(setOf("dev.N"), scoped.change.classPrefixes)
    }

    @Test
    fun `establish over a classpath without classes proves nothing absent`(@TempDir dir: File) {
        val established = establish(
            {}, File(dir, "map"), ChangeDetection.Change(setOf("dev.A"), emptyList()),
            facts(dir, emptyList(), emptySet()),
        )

        assertEquals(emptySet(), established.provable)
        assertEquals(emptySet(), established.testClasses)
    }
}
