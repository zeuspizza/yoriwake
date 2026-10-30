package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import java.util.ServiceLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptClasspathFailureCaptureTest : FunctionalTestSupport() {

    /** The compiler's line, naming [script] as a `file:` URI the way it prints one on every OS. */
    private fun compilerFailure(script: File) = "e: ${script.toPath().toUri()}:4:5: Cannot access class " +
        "'org.gradle.plugin.use.PluginDependencySpec'. Check your module classpath for missing or " +
        "conflicting dependencies"

    @Test
    fun `a script classpath failure leaves a debug rerun and the generated API jars`(@TempDir dir: File) {
        val project = build(File(dir, "project").apply { mkdirs() }, "build.gradle.kts" to minimalBuild)
        // A real failure is captured right after its build, in a daemon that is still up.
        runner(project, "help").build()
        val captures = File(dir, "captures")
        val capture = ScriptClasspathFailureCapture()

        assertEquals(project.canonicalFile, capture.projectOf(compilerFailure(File(project, "build.gradle.kts")))?.canonicalFile)
        capture.capture(captures, "Some test > a case", project)

        val rerun = File(captures, "Some-test-a-case-rerun.log").readText()
        assertContains(rerun, "[DEBUG]")
        val report = File(captures, "Some-test-a-case-daemon.txt").readText()
        assertContains(report, "the rerun failed the same way: false")
        // Gradle 8 compiles scripts against a Gradle API jar it generates; Gradle 9 generates none.
        if (FunctionalGradle.version.startsWith("8.")) {
            assertTrue(Regex("""gradle-api-\S+\.jar \d+ bytes, modified \d+, sha-256 [0-9a-f]{64}""") in report, report)
            assertContains(report, "Project.class true")
        }
        val files = captures.listFiles()!!.map(File::getName)
        val properties = files.first { it.endsWith("-properties.txt") }
        assertContains(File(captures, properties).readText(), "java.home")
        // The daemons keep a flight recording only while coverage is on.
        if (System.getProperty("yoriwake.functional.coverage") == "true") {
            assertTrue(files.any { it.endsWith(".jfr") }, files.toString())
            assertTrue(files.any { it.endsWith("-archive-exceptions.txt") }, files.toString())
        }
    }

    @Test
    fun `the handler is registered for the suite and rethrows every failure unchanged`(@TempDir dir: File) {
        assertTrue(ServiceLoader.load(Extension::class.java).any { it is ScriptClasspathFailureCapture })

        val context = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExtensionContext::class.java)) { _, method, _ ->
            if (method.name == "getDisplayName") "a case" else null
        } as ExtensionContext
        val unrelated = AssertionError("expected 3 tests, ran 2")
        val unparsable = AssertionError("Check your module classpath for missing or conflicting dependencies")
        val gone = AssertionError(compilerFailure(File(dir, "deleted/build.gradle.kts")))

        for (failure in listOf(unrelated, unparsable, gone)) {
            val thrown = runCatching { ScriptClasspathFailureCapture().handleTestExecutionException(context, failure) }
            assertSame(failure, thrown.exceptionOrNull())
        }
    }
}
