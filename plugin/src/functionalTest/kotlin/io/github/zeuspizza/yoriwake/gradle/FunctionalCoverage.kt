package io.github.zeuspizza.yoriwake.gradle

import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.SessionInfo
import org.jacoco.core.runtime.RemoteControlReader
import org.jacoco.core.runtime.RemoteControlWriter
import org.jacoco.core.tools.ExecFileLoader
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage of the plugin's code where it really runs: in the TestKit daemons. A Java agent there
 * would turn the configuration cache off for every build, so the daemons load a JaCoCo-instrumented
 * copy of the plugin's classes, with JaCoCo's runtime on their boot classpath, and send their data
 * here over loopback. It is written once per test class, so the data says which class reached what.
 *
 * Off unless the build asks for it; then the TestKit home's `gradle.properties` names only the
 * daemon's memory, and the daemons are what a user's build starts, with more heap and retrying a
 * failed download as this repository's own build does.
 */
object FunctionalCoverage {

    /**
     * What a daemon gets when no `org.gradle.jvmargs` is set, with 2 GB of heap instead of 512 MB.
     * Gradle 8's Kotlin DSL rebuilds an index of its 185 MB generated API jar in every build that
     * compiles a script, and in 512 MB that churn ends in a full collection now and then. A full
     * collection clears every soft reference, and one landing while the compiler indexes that jar
     * leaves an index with an empty root: every Gradle API class reads as missing, for every new
     * script in that daemon, until the daemon disposes its compiler. A heap dump of such a daemon
     * showed the index with two roots, the one kept having no children.
     */
    private val DAEMON_DEFAULTS = "-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx" +
        (System.getenv("STRESS_DAEMON_XMX") ?: "2g") +
        (System.getenv("STRESS_GC_LOG_DIR")?.let { " -Xlog:gc:file=$it/gc-%p.log" } ?: "")

    /**
     * The repository's own download retry, in every home: a fresh home resolves each fixture's
     * dependencies from Maven Central, and one rate-limited request otherwise failed every later
     * build of the shard.
     */
    private const val REPOSITORY_RETRY = "systemProp.org.gradle.internal.repository.max.tentatives=5\n" +
        "systemProp.org.gradle.internal.repository.initial.backoff=2000\n"

    /** The name of the flight recording each daemon keeps while coverage is on. */
    const val FLIGHT_RECORDING = "functional"

    private val enabled = System.getProperty("yoriwake.functional.coverage") == "true"

    /** Started by the first build's home, before that build runs, and kept for the whole run. */
    @Volatile
    private var started: CoverageServer? = null

    @Synchronized
    private fun server(): CoverageServer = started ?: run {
        val directory = File(requireNotNull(System.getProperty("yoriwake.functional.coverageDir")) {
            "coverage is on but the build named no directory for it"
        })
        CoverageServer(directory, sessionId = directory.name).also { it.start(); started = it }
    }

    private val runtimeJar: String by lazy {
        requireNotNull(System.getProperty("yoriwake.functional.coverageRuntime")) {
            "coverage is on but the build named no JaCoCo runtime"
        }
    }

    /**
     * Writes the TestKit home's `gradle.properties`, every time, so a home left by an earlier run
     * never starts a daemon that reports to a port nobody listens on any more.
     */
    fun configure(testKitHome: File) {
        testKitHome.mkdirs()
        val text = if (enabled) {
            val server = server()
            val arguments = listOf(
                "-Xbootclasspath/a:$runtimeJar",
                "-Djacoco-agent.output=tcpclient",
                "-Djacoco-agent.address=127.0.0.1",
                "-Djacoco-agent.port=${server.port}",
                "-Djacoco-agent.sessionid=${server.sessionId}",
                "-Djacoco-agent.jmx=false",
                // The last minutes of exceptions thrown in the daemon, for ScriptClasspathFailureCapture
                // to dump: Gradle 8 discards the Kotlin compiler's own warnings.
                "-XX:StartFlightRecording=name=$FLIGHT_RECORDING,maxage=5m,jdk.JavaExceptionThrow#enabled=true",
                "-Xlog:jfr+startup=warning",
            ).joinToString(" ") { "\"$it\"" }
            "org.gradle.jvmargs=" + "$DAEMON_DEFAULTS $arguments".replace("\\", "\\\\") + "\n"
        } else {
            "org.gradle.jvmargs=$DAEMON_DEFAULTS\n"
        }
        File(testKitHome, "gradle.properties").writeText(text + REPOSITORY_RETRY)
    }

    /** Collects what every connected daemon has recorded since the last dump, under [name]. */
    internal fun dump(name: String) {
        started?.dump(name)
    }
}

/** Dumps after each top-level test class and once more when the run ends. Registered as a service. */
class FunctionalCoverageListener : TestExecutionListener {

    override fun executionFinished(identifier: TestIdentifier, result: TestExecutionResult) {
        val source = identifier.source.orElse(null) as? ClassSource ?: return
        // A nested class finishes inside its outer one, whose dump covers it.
        if ('$' in source.className) return
        FunctionalCoverage.dump(source.className.substringAfterLast('.'))
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        FunctionalCoverage.dump("end-of-run")
    }
}

/**
 * Accepts every JaCoCo runtime that connects, asks each for its data on [dump], and writes
 * `<name>.exec` and `summary.txt` into [directory]. Data a runtime sends without being asked, as
 * it does when its JVM exits, goes to `unsolicited-<n>.exec`. A runtime that drops its connection
 * loses only what it recorded since its last dump. Only classes with hits are written: one missing
 * from a file reports exactly as one present with none.
 */
internal class CoverageServer(
    private val directory: File,
    val sessionId: String,
    private val replyTimeoutMillis: Long = 60_000,
) {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val connections = CopyOnWriteArrayList<Connection>()
    private val connected = AtomicInteger()
    private val hitClasses = sortedSetOf<String>()
    private var dumps = 0
    private var unsolicited = 0

    val port: Int get() = socket.localPort

    /** How many runtimes have connected so far, including those since gone. */
    val accepted: Int get() = connected.get()

    fun start() {
        directory.mkdirs()
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                connections += Connection(client).also { it.start() }
                connected.incrementAndGet()
            }
        }, "functional-coverage-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun dump(name: String) {
        val replies = connections.map { it.request() }
        // One deadline for the dump, so a runtime that never answers delays it once, not once each.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(replyTimeoutMillis)
        val merged = ExecFileLoader()
        replies.forEach { reply ->
            val wait = (deadline - System.nanoTime()).coerceAtLeast(0)
            val batch = runCatching { reply.get(wait, TimeUnit.NANOSECONDS) }.getOrNull() ?: return@forEach
            batch.executionDataStore.contents.forEach(merged.executionDataStore::put)
            batch.sessionInfoStore.infos.forEach(merged.sessionInfoStore::visitSessionInfo)
        }
        write(File(directory, "$name.exec"), merged)
        dumps++
        writeSummary()
    }

    fun close() {
        socket.close()
        connections.forEach { it.close() }
    }

    @Synchronized
    private fun keepUnsolicited(batch: ExecFileLoader) {
        unsolicited++
        write(File(directory, "unsolicited-$unsolicited.exec"), batch)
        writeSummary()
    }

    private fun write(file: File, batch: ExecFileLoader) {
        val hits = ExecFileLoader()
        batch.sessionInfoStore.infos.forEach(hits.sessionInfoStore::visitSessionInfo)
        batch.executionDataStore.contents.filter { it.hasHits() }.forEach(hits.executionDataStore::put)
        hits.save(file, false)
        hits.executionDataStore.contents.forEach { hitClasses += it.name }
    }

    /**
     * Plain text, because the build script that gates on it has no JaCoCo reader of its own. The
     * gate reads only the `hit` lines; the counts are for whoever reads a run's artifacts.
     */
    private fun writeSummary() {
        val lines = listOf(
            "dumps $dumps",
            "connections $accepted",
            "unsolicited $unsolicited",
            "classes-with-hits ${hitClasses.size}",
        ) + hitClasses.map { "hit $it" }
        File(directory, "summary.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private inner class Connection(private val client: Socket) {
        private val writer = RemoteControlWriter(client.getOutputStream())
        private val reader = RemoteControlReader(client.getInputStream())
        private var batch = ExecFileLoader()
        private var pending: CompletableFuture<ExecFileLoader>? = null
        private var closed = false

        fun start() {
            reader.setSessionInfoVisitor { info: SessionInfo -> batch.sessionInfoStore.visitSessionInfo(info) }
            reader.setExecutionDataVisitor { data: ExecutionData -> batch.executionDataStore.put(data) }
            Thread(::readAll, "functional-coverage-connection").apply { isDaemon = true }.start()
        }

        /** Asks for the data recorded since the last request, and for it to be reset. */
        fun request(): CompletableFuture<ExecFileLoader> {
            val reply = CompletableFuture<ExecFileLoader>()
            synchronized(this) {
                // Gone between the list being read and now: nothing will answer.
                if (closed) return reply.apply { complete(ExecFileLoader()) }
                pending = reply
            }
            runCatching { writer.visitDumpCommand(true, true) }.onFailure { reply.complete(ExecFileLoader()) }
            return reply
        }

        private fun readAll() {
            while (true) {
                // True after the reply to a request; false when the runtime closed the connection.
                val replied = runCatching { reader.read() }.getOrDefault(false)
                val received = batch
                batch = ExecFileLoader()
                val waiting = synchronized(this) { pending.also { pending = null } }
                when {
                    waiting != null -> waiting.complete(received)
                    received.executionDataStore.contents.isNotEmpty() -> keepUnsolicited(received)
                }
                if (!replied) break
            }
            synchronized(this) {
                closed = true
                pending?.complete(ExecFileLoader())
                pending = null
            }
            connections.remove(this)
            close()
        }

        fun close() {
            runCatching { client.close() }
        }
    }
}
