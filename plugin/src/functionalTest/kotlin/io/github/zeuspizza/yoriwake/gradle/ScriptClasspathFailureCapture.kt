package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * When a build script stops compiling against the Gradle API in the middle of a run, records what
 * the daemon that failed saw, before the test's directory and the daemon are gone, then lets the
 * test fail as it would have.
 *
 * Seen only on Gradle 8.14, in long-lived daemons, and never reproduced on demand: every fresh
 * script in one daemon fails to resolve Gradle API classes while the Kotlin DSL's own classes
 * resolve. The compiler reads the generated API jar's entries once and, on any exception, keeps an
 * empty list for it without a word: Gradle 8 discards the compiler's output. So this dumps each
 * live daemon's flight recording (the exceptions it threw in the last minutes, kept while coverage
 * is on), reruns the build in the same daemon at debug level, and keeps the daemon's open files,
 * memory maps, limits and system properties beside it. Registered for the whole suite; a few
 * captures per run are enough.
 */
class ScriptClasspathFailureCapture : TestExecutionExceptionHandler {

    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
        val message = throwable.message.orEmpty()
        val directory = System.getProperty("yoriwake.functional.diagnosticsDir")
        val project = if (directory != null && SIGNATURE in message) projectOf(message) else null
        if (project != null && captured.incrementAndGet() <= LIMIT) {
            runCatching { capture(File(directory), "${captured.get()}-${context.displayName}", project) }
                .onFailure { System.err.println("could not capture the script classpath failure: $it") }
        }
        throw throwable
    }

    /** The project whose script the compiler named, as it prints it: a `file:` URI on every OS. */
    internal fun projectOf(message: String): File? = SCRIPT.find(message)?.value
        ?.let { runCatching { File(URI(it)) }.getOrNull() }
        ?.parentFile?.takeIf(File::isDirectory)

    /** Writes `<test>-rerun.log` and `<test>-daemon.txt` into [directory] for the failed build in [project]. */
    internal fun capture(directory: File, test: String, project: File) {
        val name = test.replace(Regex("[^A-Za-z0-9]+"), "-").take(80)
        directory.mkdirs()
        val home = FunctionalGradle.testKitDir()
        val daemons = File(home, "test-kit-daemon").walkTopDown()
            .mapNotNull { DAEMON_LOG.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() }
            .filter { ProcessHandle.of(it).isPresent }
            .toList()

        // First, while the exception that started it is still in each daemon's recording.
        val bin = File(System.getProperty("java.home"), "bin")
        daemons.forEach { pid ->
            val recording = File(directory, "$name-daemon-$pid.jfr")
            run(bin, "jcmd", "$pid", "JFR.dump", "name=${FunctionalCoverage.FLIGHT_RECORDING}", "filename=${recording.absolutePath}")
            if (recording.isFile) {
                val events = run(bin, "jfr", "print", "--events", "jdk.JavaExceptionThrow", "--stack-depth", "40", recording.absolutePath)
                File(directory, "$name-daemon-$pid-archive-exceptions.txt").writeText(
                    events.split("jdk.JavaExceptionThrow {").filter { event -> ARCHIVE_CODE.any { it in event } }
                        .joinToString("") { "jdk.JavaExceptionThrow {$it" }
                )
            }
            // System properties outlive a build in the daemon.
            File(directory, "$name-daemon-$pid-properties.txt").writeText(run(bin, "jcmd", "$pid", "VM.system_properties"))
        }

        // Bounded: a capture must never hold the shard longer than the failure it records.
        val rerun = Executors.newSingleThreadExecutor { Thread(it, "script-classpath-rerun").apply { isDaemon = true } }
        val output = try {
            rerun.submit<String> {
                GradleRunner.create()
                    .withProjectDir(project)
                    .withPluginClasspath()
                    .withGradleVersion(FunctionalGradle.version)
                    .withTestKitDir(FunctionalGradle.testKitDir())
                    .withArguments("help", "--debug", "--stacktrace", "--no-configuration-cache", "--no-watch-fs")
                    .run().output
            }.get(RERUN_MINUTES, TimeUnit.MINUTES)
        } catch (failure: Exception) {
            "the rerun did not complete: $failure"
        } finally {
            rerun.shutdownNow()
        }
        File(directory, "$name-rerun.log").writeText(output)

        val report = StringBuilder()
        report.appendLine("test: $test")
        report.appendLine("the rerun failed the same way: ${SIGNATURE in output}")
        report.appendLine("live daemons before the rerun: $daemons")
        DAEMON_PID.findAll(output).map { it.groupValues[1] }.distinct().forEach { pid ->
            report.appendLine("rerun in daemon $pid")
        }
        daemons.forEach { pid ->
            val proc = File("/proc/$pid")
            if (!proc.isDirectory) return@forEach
            report.appendLine("daemon $pid")
            report.appendLine("  open files: ${File(proc, "fd").list()?.size}")
            report.appendLine("  memory maps: ${runCatching { File(proc, "maps").readLines().size }.getOrNull()}")
            runCatching { File(proc, "limits").readText() }.onSuccess { report.appendLine(it.prependIndent("  ")) }
            File(proc, "fd").listFiles().orEmpty()
                .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
                .filter { it.endsWith(".jar") }
                .sorted()
                .forEach { report.appendLine("  open: $it") }
        }
        File(home, "caches").walkTopDown()
            // Jars only: the cache's lock file beside them is held by the daemon, and Windows refuses its read.
            .filter { it.isFile && it.parentFile.name == "generated-gradle-jars" && it.extension == "jar" }
            .forEach { jar ->
                val digest = runCatching { sha256(jar) }.getOrElse { "unreadable ($it)" }
                report.appendLine("${jar.path} ${jar.length()} bytes, modified ${jar.lastModified()}, sha-256 $digest")
                val readable = runCatching {
                    ZipFile(jar).use { zip -> "${zip.size()} entries, Project.class ${zip.getEntry(PROJECT) != null}" }
                }.getOrElse { "unreadable: $it" }
                report.appendLine("  read here: $readable")
            }
        File(directory, "$name-daemon.txt").writeText(report.toString())
    }

    /** One of the JDK's own tools, its output and errors together, given a minute at most. */
    private fun run(bin: File, tool: String, vararg arguments: String): String = runCatching {
        val output = File.createTempFile("capture-$tool", ".txt").apply { deleteOnExit() }
        val process = ProcessBuilder(File(bin, tool).path, *arguments)
            .redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(TOOL_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching "$tool did not finish in ${TOOL_SECONDS}s"
        }
        output.readText().also { output.delete() }
    }.getOrElse { "$tool failed: $it" }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SIGNATURE = "Check your module classpath for missing or conflicting dependencies"
        const val LIMIT = 3
        const val PROJECT = "org/gradle/api/Project.class"
        const val RERUN_MINUTES = 5L
        const val TOOL_SECONDS = 60L
        val SCRIPT = Regex("""file:/\S+?\.gradle\.kts""")
        val DAEMON_PID = Regex("""from the daemon \(pid: (\d+)\)""")
        val DAEMON_LOG = Regex("""daemon-(\d+)\.out\.log""")
        // Where the compiler reads a classpath jar, and where it swallows a failure to.
        val ARCHIVE_CODE = listOf("intellij.openapi.vfs", "java.util.zip", "CoreJar")
        val captured = AtomicInteger()
    }
}
