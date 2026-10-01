package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The init script, applied to builds that do not declare the plugin. */
class InitScriptFunctionalTest : FunctionalTestSupport() {

    /**
     * The shipped init script, with only its dependency line pointed at the plugin under test.
     *
     * Its `initscript` block resolves the plugin from mavenLocal, which a functional test must not
     * depend on; everything below that line is the shipped file's own text.
     */
    private fun initScriptUnderTest(dir: File): File {
        val source = File("../scripts/yoriwake.init.gradle.kts").absoluteFile
        assertTrue(source.isFile, "the init script is not where this test looks for it: $source")
        val dependency =
            """classpath("io.github.zeuspizza:yoriwake-gradle-plugin:${'$'}{System.getProperty("yoriwake.initScriptVersion") ?: "0.1.0"}")"""
        val text = source.readText()
        assertTrue(dependency in text, "the init script's dependency line moved")
        val classpath = javaClass.classLoader
            .getResourceAsStream("plugin-under-test-metadata.properties")!!
            .use { java.util.Properties().apply { load(it) } }
            .getProperty("implementation-classpath")
            .split(File.pathSeparator)
            .joinToString(", ") { "\"" + it.replace("\\", "\\\\") + "\"" }
        return File(dir, "yoriwake-under-test.init.gradle.kts").apply {
            writeText(text.replace(dependency, "classpath(files($classpath))"))
        }
    }

    @Test
    fun `the init script declines Isolated Projects instead of failing the build it is applied to`(
        @TempDir dir: File,
    ) {
        // The init script's gate has to decline before its own `allprojects { plugins.withId(...)
        // }`, which is itself a cross-project read Isolated Projects forbids. A single-project
        // fixture cannot fail here, so this one has two modules.
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha", "beta")
            """.trimIndent()
        )
        listOf("alpha", "beta").forEach { module ->
            File(dir, "$module/build.gradle.kts").apply { parentFile.mkdirs() }
                .writeText("plugins { java }")
        }
        File(dir, "gradle.properties").writeText(
            "org.gradle.unsafe.isolated-projects=true\norg.gradle.isolated-projects=true\n"
        )

        val output = runner(dir, "tasks", "--all", "-I", initScriptUnderTest(dir).absolutePath)
            .build().output

        assertContains(output, "Isolated Projects is enabled")
        // And it declined by not applying: no plugin, so no tasks of ours anywhere in the build.
        assertFalse(
            output.contains("yoriwakeAudit"),
            "the init script applied the plugin under Isolated Projects",
        )
    }

    @Test
    fun `the init script resolves the version its property names, and the release by default`(
        @TempDir dir: File,
    ) {
        // The shipped file unmodified, offline, against an empty mavenLocal: resolution fails, and
        // the failure names the coordinate the script asked for.
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "plain"""")
        val script = File("../scripts/yoriwake.init.gradle.kts").absoluteFile
        val emptyRepo = File(dir, "m2").apply { mkdirs() }
        fun requested(vararg extra: String) = runner(
            dir, "help", "--offline", "-I", script.absolutePath,
            "-Dmaven.repo.local=${'$'}{emptyRepo.absolutePath}", *extra,
        ).buildAndFail().output

        assertContains(requested(), Regex("""io\.github\.zeuspizza:yoriwake-gradle-plugin:0\.1\.0(?![-\w])"""))
        assertContains(
            requested("-Dyoriwake.initScriptVersion=0.0.0-named"),
            "io.github.zeuspizza:yoriwake-gradle-plugin:0.0.0-named",
        )
    }

    @Test
    fun `the init script applies the plugin to an ordinary multi-project build`(
        @TempDir dir: File,
    ) {
        // The other half of the gate: a build without the feature must still be configured, or the
        // test above would pass just as well with the script doing nothing at all.
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha")
            """.trimIndent()
        )
        File(dir, "alpha/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(minimalBuild)
        File(dir, "alpha/${oneClass.first}").apply { parentFile.mkdirs() }.writeText(oneClass.second)
        File(dir, "alpha/${oneTest.first}").apply { parentFile.mkdirs() }.writeText(oneTest.second)

        val output = runner(dir, "tasks", "--all", "-I", initScriptUnderTest(dir).absolutePath)
            .build().output

        assertContains(output, "yoriwakeAuditTest")
        assertFalse(output.contains("Isolated Projects is enabled"))
    }

    @Test
    fun `the init script applies jacoco when yoriwake disabled is false`(@TempDir dir: File) {
        // The host applies no jacoco of its own, so only the init script can have added it.
        build(
            dir,
            "build.gradle.kts" to minimalBuild.replace("    jacoco\n", "")
                .replace("    id(\"io.github.zeuspizza.yoriwake\")\n", ""),
            oneClass, oneTest,
        )

        val output = runner(dir, "tasks", "--all", "-I", initScriptUnderTest(dir).absolutePath,
                            "-Pyoriwake.disabled=false").build().output

        assertContains(output, "jacocoTestReport")
        assertContains(output, "yoriwakeAuditTest")
    }
}
