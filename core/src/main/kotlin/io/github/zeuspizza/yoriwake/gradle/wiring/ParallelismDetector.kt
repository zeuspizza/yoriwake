package io.github.zeuspizza.yoriwake.gradle.wiring

import java.io.File
import java.util.Properties

/**
 * Finds in-JVM parallel test execution, wherever it was configured.
 *
 * Tests interleaved in one JVM share one JaCoCo agent, so the reset-and-dump pairing would blend
 * their coverage. Parallel forks are safe and must not be restricted: each has its own agent.
 */
internal object ParallelismDetector {

    const val PROPERTY = "junit.jupiter.execution.parallel.enabled"
    const val CONFIG_FILE = "junit-platform.properties"

    /**
     * Where parallelism was enabled, or null when it is not. A description rather than a boolean,
     * because each route needs a different remedy.
     */
    fun detect(
        systemProperties: Map<String, String?>,
        jvmArgs: Collection<String> = emptyList(),
        testRuntimeFiles: Collection<File> = emptyList(),
        testngParallel: String? = null,
        testngThreadCount: Int? = null,
    ): String? {
        // TestNG ignores the Jupiter property. The JaCoCo reset capture depends on is process-global,
        // so our own `synchronized` does not make TestNG threads safe.
        if (testngParallel != null && testngParallel.isNotBlank() && !testngParallel.equals("none", true)) {
            return "TestNG's parallel mode ('$testngParallel')"
        }
        if (testngThreadCount != null && testngThreadCount > 1) {
            return "TestNG's threadCount ($testngThreadCount)"
        }
        if (systemProperties[PROPERTY]?.toBoolean() == true) {
            return "$PROPERTY in the test task's system properties"
        }
        // `jvmArgs("-Djunit...=true")` never appears in Test.systemProperties.
        jvmArgs.firstOrNull { it.startsWith("-D$PROPERTY=") && it.substringAfter('=').toBoolean() }
            ?.let { return "a JVM argument ($it)" }

        return testRuntimeFiles.asSequence().firstNotNullOfOrNull { entry ->
            when {
                entry.isDirectory -> File(entry, CONFIG_FILE)
                    .takeIf { it.isFile && enabledIn(it) }
                    ?.let { "$CONFIG_FILE on the test runtime classpath ($it)" }

                // Multi-module builds usually share junit-platform.properties through a jar.
                entry.isFile && entry.extension == "jar" ->
                    if (enabledInJar(entry)) "$CONFIG_FILE inside ${entry.name}" else null

                else -> null
            }
        }
    }

    /** Reads one properties file; an unreadable file reads as "not enabled" rather than failing. */
    private fun enabledIn(file: File): Boolean =
        runCatching {
            file.inputStream().use { stream -> enabledIn(stream) }
        }.getOrDefault(false)

    private fun enabledIn(stream: java.io.InputStream): Boolean =
        Properties().apply { load(stream) }.getProperty(PROPERTY)?.toBoolean() == true

    /** Reads the config out of a jar, treating an unreadable or malformed jar as "not enabled". */
    private fun enabledInJar(jar: File): Boolean =
        runCatching {
            java.util.zip.ZipFile(jar).use { zip ->
                zip.getEntry(CONFIG_FILE)?.let { entry ->
                    zip.getInputStream(entry).use { enabledIn(it) }
                } ?: false
            }
        }.getOrDefault(false)
}
