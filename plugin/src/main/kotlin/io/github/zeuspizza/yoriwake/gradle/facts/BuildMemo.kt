package io.github.zeuspizza.yoriwake.gradle.facts

import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.report.Payback
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * What the plugin works out once per build instead of once per configured `Test` task.
 *
 * A [BuildService]: the only build-scoped state compatible with Isolated Projects. Never a task
 * input or `usesService` target, so it stays out of task fingerprints.
 */
internal abstract class BuildMemo : BuildService<BuildServiceParameters.None> {

    private val instances = ConcurrentHashMap<String, Provider<String>>()
    private val counters = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val values = ConcurrentHashMap<String, Any>()
    private val firstFailure = AtomicReference<String?>(null)
    private val reported = AtomicBoolean(false)

    // The provider is memoised, not its value: obtaining one runs a subprocess, which must not
    // hold the map's bin lock. Gradle caches per instance, so one instance means one subprocess.
    fun provider(key: String, create: () -> Provider<String>): Provider<String> =
        instances.computeIfAbsent(key) { create() }

    // Keys must carry the root project directory: the service is shared across included builds,
    // and an unkeyed value would hand one build another's scope, silently skipping tests.
    fun <T> value(key: String, compute: () -> T): T {
        val stored = values.computeIfAbsent(key) { compute() ?: NOTHING }
        @Suppress("UNCHECKED_CAST")
        return if (stored === NOTHING) null as T else stored as T
    }

    fun count(key: String, n: Long = 1) {
        counters.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong() }.addAndGet(n)
    }

    fun <T> time(key: String, body: () -> T): T {
        val started = System.nanoTime()
        try {
            return body()
        } finally {
            count(key)
            // Nanoseconds, divided once when read: per-call millisecond truncation can round a
            // many-project total to zero, which reads as a configuration-cache hit.
            counters.computeIfAbsent("$key$NANOS") { java.util.concurrent.atomic.AtomicLong() }
                .addAndGet(System.nanoTime() - started)
        }
    }

    fun counts(): Map<String, Long> = counters.entries.associate { (key, value) ->
        if (key.endsWith(NANOS)) {
            key.removeSuffix(NANOS) + MILLIS to value.get() / 1_000_000
        } else {
            key to value.get()
        }
    }

    // Null rather than 0 on a configuration-cache hit, where the plugin did not run at all.
    fun configureNanos(): Long? =
        counters["${YoriwakePlugin.CONFIGURE_COUNTER}$NANOS"]?.get()?.takeIf { it > 0 }

    /** `Test` tasks this build configured, the denominator of every per-task figure. */
    fun testTasksConfigured(): Int? =
        counters[YoriwakePlugin.TEST_TASKS_COUNTER]?.get()?.toInt()?.takeIf { it > 0 }

    /** Both halves of the configure axis, or null when this build configured nothing. */
    internal fun configureCost(): Payback.ConfigureCost? {
        val nanos = configureNanos() ?: return null
        val tasks = testTasksConfigured() ?: return null
        return Payback.ConfigureCost(nanos, tasks)
    }

    /** Writes the counts for a test to read, overwriting so the last task leaves the totals. */
    fun writeCountsTo(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(counts().toSortedMap().entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }
    }

    fun recordFailure(description: String) {
        firstFailure.compareAndSet(null, description)
    }

    fun failed(): Boolean = firstFailure.get() != null

    /** The failure to print, on the first call only, so many projects do not repeat it. */
    fun failureToReport(): String? =
        firstFailure.get()?.takeIf { reported.compareAndSet(false, true) }

    companion object {

        /**
         * Build-scoped, or null when this build cannot share one: a plugin loaded through several
         * classloaders registers the name against another class, and falls back to unmemoised git.
         */
        fun of(project: Project): BuildMemo? = providerOf(project)?.orNull

        /** For a task action: `get()` there returns the running build's instance. */
        fun providerOf(project: Project) = runCatching {
            project.gradle.sharedServices.registerIfAbsent(NAME, BuildMemo::class.java) {}
        }.getOrNull()

        private const val NAME = "yoriwakeBuildMemo"

        /** Stands in for a computed null, which a `ConcurrentHashMap` cannot hold. */
        private val NOTHING = Any()

        /** Suffix for a timer's milliseconds in the counts file; readers match it literally. */
        const val MILLIS = ".millis"

        internal const val NANOS = ".nanos"
    }
}
