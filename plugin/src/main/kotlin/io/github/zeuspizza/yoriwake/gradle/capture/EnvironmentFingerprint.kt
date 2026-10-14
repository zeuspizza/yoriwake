package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ENVIRONMENT_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ENVIRONMENT_FINGERPRINTS_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ENV_READS_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ENV_READ_WHOLE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.POSITIONS_FILE
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.agent.select.Glob
import java.io.File
import java.io.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The environment the test JVMs of a map were recorded in: the JDK, the OS and architecture, the
 * system properties the test task passes, and the time zone, the locale and the environment
 * variables a team lists. A map says which tests a change reaches under that environment; in
 * another one a test can take other paths, so a selecting run whose environment differs runs every
 * test and records the map again. Only ever adds full runs.
 *
 * Both sides are computed in the daemon from the test task, before the test JVM starts: whether a
 * run captures is decided there, and a capture writes the fingerprint it was compared against.
 * Values of system properties and variables are kept only as digests salted per map.
 */
internal object EnvironmentFingerprint {

    /** Written by a capturing run before its test JVM starts, folded into the map by its decode. */
    const val PENDING_FILE = "environment.pending"

    /** 32 random bytes in hex, created by the first capture: the salt of every digest in the map. */
    const val SALT_FILE = "fingerprint-salt"

    /** The variables every run compares: the JVM derives its default time zone and locale from them. */
    val FIXED_VARIABLES = listOf("LANG", "LC_ALL", "TZ")

    private const val PROPERTY = "sys:"
    private const val VARIABLE = "env:"
    private val PLAIN = setOf("jdk.version", "jdk.vendor", "jdk.executable", "jdk.unavailable", "os.name", "os.arch")

    /**
     * What the daemon sees of the test JVM before it starts: the launcher's runtime version and
     * vendor (null when it has no metadata), the executable the task names, the daemon's OS and
     * architecture, every argument the JVM starts with, and the task's environment.
     */
    data class View(
        val jdkVersion: String?,
        val jdkVendor: String?,
        val executable: String?,
        val osName: String?,
        val osArch: String?,
        val jvmArgs: List<String>,
        val environment: Map<String, String>,
    ) : Serializable {
        private companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * What a team configured, and the paths that differ between checkouts, runners and users; each
     * is replaced by a placeholder before a value is digested or stored.
     */
    class Rules(
        private val systemPropertyIgnore: List<String>,
        private val environmentInclude: List<String>,
        rootDir: String,
        gradleUserHome: String,
        userHome: String,
    ) : Serializable {
        // Longest first, so a checkout under the home directory is named as the checkout.
        private val placeholders: List<Pair<String, String>> = listOf(
            rootDir to "<root>", gradleUserHome to "<gradle-user-home>", userHome to "<home>",
        )
            .flatMap { (path, name) -> listOf(path, runCatching { File(path).canonicalPath }.getOrDefault(path)).map { it to name } }
            .filter { it.first.length > 1 }
            .distinct()
            .sortedByDescending { it.first.length }

        fun normalize(value: String): String =
            placeholders.fold(value) { text, (path, name) -> text.replace(path, name) }

        fun ignoresProperty(name: String) = systemPropertyIgnore.any { Glob.matches(it.trim(), name) }

        fun comparesVariable(name: String) = name in FIXED_VARIABLES || environmentInclude.any { Glob.matches(it.trim(), name) }

        /** The variables compared whether or not they are set: the fixed ones and those listed by name. */
        fun namedVariables(): Set<String> =
            (FIXED_VARIABLES + environmentInclude.map(String::trim).filter { '*' !in it && it.isNotEmpty() }).toSortedSet()

        private companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** One fingerprint, and the property names an ignore entry left out of it. */
    class Computed(val components: Map<String, String>, val ignored: List<String>)

    sealed class Verdict {
        object Matches : Verdict() {
            override fun toString() = "Matches"
        }

        /** [component] is the first that differs; [reason] names it, and only plain values. */
        data class Changed(val component: String, val reason: String) : Verdict()

        data class Unrecorded(val reason: String) : Verdict()
    }

    class Check(val verdict: Verdict, val ignored: List<String>)

    /** [view]'s fingerprint, each value normalized and, for properties and variables, digested with [salt]. */
    fun compute(view: View, salt: ByteArray, rules: Rules): Computed {
        val components = sortedMapOf<String, String>(ORDER)
        when {
            view.jdkVersion != null || view.jdkVendor != null -> {
                components["jdk.version"] = view.jdkVersion ?: "unknown"
                components["jdk.vendor"] = view.jdkVendor ?: "unknown"
            }
            view.executable != null -> components["jdk.executable"] = rules.normalize(view.executable)
            else -> components["jdk.unavailable"] = "-"
        }
        components["os.name"] = view.osName ?: "unknown"
        components["os.arch"] = view.osArch ?: "unknown"
        val ignored = sortedSetOf<String>()
        // The JVM keeps the last value given for a name.
        val properties = LinkedHashMap<String, String>()
        view.jvmArgs.filter { it.startsWith("-D") }.map { it.removePrefix("-D") }.forEach { body ->
            val name = body.substringBefore('=')
            properties[name] = if ('=' in body) body.substringAfter('=') else ""
        }
        properties.forEach { (name, value) ->
            when {
                name.isEmpty() || name.startsWith("yoriwake.") -> Unit
                rules.ignoresProperty(name) -> ignored += name
                else -> components[PROPERTY + name] = digest(salt, PROPERTY + name, rules.normalize(value))
            }
        }
        (rules.namedVariables() + view.environment.keys.filter(rules::comparesVariable)).forEach { name ->
            components[VARIABLE + name] = digest(salt, VARIABLE + name, view.environment[name]?.let(rules::normalize))
        }
        return Computed(components, ignored.toList())
    }

    /**
     * Compares [view] with the fingerprint of every JVM of the map that ran a test. A JVM without
     * one, a table that cannot be read whole, or no salt is [Verdict.Unrecorded]. Components the
     * current [rules] would not compare are left out of the recorded side, so a name ignored or
     * dropped from the list since is not a change.
     */
    fun check(mapDir: File, view: View, rules: Rules): Check {
        val jvms = runCatching { jvmsWithPositions(mapDir) }.getOrNull()
            ?: return Check(Verdict.Unrecorded("the map's test positions could not be read"), emptyList())
        if (jvms.isEmpty()) return Check(Verdict.Matches, emptyList())
        val salt = readSalt(mapDir)
            ?: return Check(Verdict.Unrecorded(UNRECORDED), emptyList())
        val current = compute(view, salt, rules)
        val recorded = readRecorded(mapDir, jvms)
            ?: return Check(Verdict.Unrecorded(UNRECORDED), current.ignored)
        for (fingerprint in recorded) {
            val compared = fingerprint.filterKeys { component ->
                when {
                    component.startsWith(PROPERTY) -> !rules.ignoresProperty(component.removePrefix(PROPERTY))
                    component.startsWith(VARIABLE) -> rules.comparesVariable(component.removePrefix(VARIABLE))
                    else -> true
                }
            }
            val first = (compared.keys + current.components.keys).sortedWith(ORDER)
                .firstOrNull { compared[it] != current.components[it] } ?: continue
            return Check(Verdict.Changed(first, describe(first, compared[first], current.components[first])), current.ignored)
        }
        return Check(Verdict.Matches, current.ignored)
    }

    /**
     * What the explain output says of the variables the last capture's tests read and [rules] does
     * not compare: the names, a read of the whole environment, or that reads were not observed.
     */
    fun uncomparedReads(mapDir: File, rules: Rules): List<String> {
        val file = File(mapDir, ENV_READS_FILE)
        val read = runCatching { file.takeIf(File::isFile)?.readLines() }.getOrNull()?.filter(String::isNotEmpty)
            ?: return listOf("which environment variables the tests read was not observed")
        val names = read.map(Tsv::unescape)
        return listOfNotNull(
            names.filter { it != ENV_READ_WHOLE && !rules.comparesVariable(it) }.takeIf { it.isNotEmpty() }?.let {
                "read by the tests and not compared: ${it.joinToString(", ")}; list them in environmentInclude to compare them"
            },
            if (ENV_READ_WHOLE in names) {
                "a test read the whole environment; only TZ, LANG, LC_ALL and the variables environmentInclude lists are compared"
            } else {
                null
            },
        )
    }

    /** Writes [view]'s fingerprint for the decode to fold in, creating the map's salt if it has none. */
    fun writePending(mapDir: File, view: View, rules: Rules) {
        mapDir.mkdirs()
        val salt = readSalt(mapDir) ?: ByteArray(32).also { bytes ->
            SecureRandom().nextBytes(bytes)
            writeAtomically(File(mapDir, SALT_FILE), hex(bytes) + "\n")
        }
        val components = compute(view, salt, rules).components
        writeAtomically(File(mapDir, PENDING_FILE), components.entries.joinToString("") { Tsv.join(it.key, it.value) + "\n" })
    }

    /** The pending fingerprint, or null when there is none or it cannot be read whole. */
    fun readPending(mapDir: File): Map<String, String>? = runCatching {
        val text = File(mapDir, PENDING_FILE).takeIf(File::isFile)?.readText() ?: return null
        if (!text.endsWith("\n")) return null
        val components = sortedMapOf<String, String>(ORDER)
        text.trimEnd('\n').split('\n').forEach { line ->
            val parts = Tsv.split(line)
            if (parts.size != 2 || parts[0].isEmpty()) return null
            components[parts[0]] = parts[1]
        }
        components.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Rewrites the map's two tables: the rows of the [retained] JVMs and the blocks they name, and a
     * row per JVM of this capture for [pending]. With no [pending], this capture's JVMs get no row,
     * and the next selecting run runs everything.
     */
    fun write(mapDir: File, retained: Collection<String>, decodedJvms: List<String>, pending: Map<String, String>?) {
        val keep = retained.toHashSet()
        val rows = readTable(File(mapDir, ENVIRONMENT_FILE), 2).orEmpty().filter { it[0] in keep }
        val blocks = readBlocks(mapDir).orEmpty()
        val kept = rows.map { it[1] }.toHashSet()
        val table = blocks.filterKeys { it in kept }.toMutableMap()
        val newRows = rows.map { Tsv.join(it[0], it[1]) }.toMutableList()
        if (pending != null) {
            val id = idOf(pending)
            table[id] = pending
            decodedJvms.forEach { newRows += Tsv.join(it, id) }
        }
        writeAtomically(File(mapDir, ENVIRONMENT_FINGERPRINTS_FILE), table.entries.sortedBy { it.key }.joinToString("") { (id, components) ->
            components.entries.sortedWith(compareBy(ORDER) { it.key }).joinToString("") { Tsv.join(id, it.key, it.value) + "\n" }
        })
        writeAtomically(File(mapDir, ENVIRONMENT_FILE), newRows.distinct().sorted().joinToString("") { "$it\n" })
    }

    private const val UNRECORDED =
        "the map holds no readable environment fingerprint for its test JVMs; a map recorded before " +
            "fingerprints were recorded looks like this"

    /** The JDK's version first, as what differs most often, then the rest of the JDK, the OS, properties and variables. */
    private val ORDER: Comparator<String> = compareBy<String> { component ->
        when {
            component == "jdk.version" -> 0
            component.startsWith("jdk.") -> 1
            component.startsWith("os.") -> 2
            component.startsWith(PROPERTY) -> 3
            else -> 4
        }
    }.thenBy { it }

    private fun describe(component: String, recorded: String?, now: String?): String {
        val what = when {
            recorded == null -> "$component is set now and was not when the map was recorded"
            now == null -> "$component was set when the map was recorded and is not now"
            component in PLAIN -> "$component was $recorded when the map was recorded and is $now now"
            else -> "$component has another value than when the map was recorded"
        }
        return "the test JVM's environment differs from the one the map was recorded in: $what"
    }

    private fun digest(salt: ByteArray, component: String, value: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(component.toByteArray(Charsets.UTF_8))
        digest.update(0)
        if (value == null) {
            digest.update(1)
        } else {
            digest.update(2)
            digest.update(value.toByteArray(Charsets.UTF_8))
        }
        return hex(digest.digest())
    }

    private fun idOf(components: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        components.entries.sortedWith(compareBy(ORDER) { it.key }).forEach {
            digest.update((Tsv.join(it.key, it.value) + "\n").toByteArray(Charsets.UTF_8))
        }
        return hex(digest.digest()).take(16)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private val SALT = Regex("[0-9a-f]{64}\n?")

    private fun readSalt(mapDir: File): ByteArray? = runCatching {
        val text = File(mapDir, SALT_FILE).takeIf(File::isFile)?.readText() ?: return null
        if (!SALT.matches(text)) return null
        text.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull()

    private fun jvmsWithPositions(mapDir: File): Set<String> {
        val file = File(mapDir, POSITIONS_FILE)
        if (!file.isFile) return emptySet()
        return file.readLines().filter(String::isNotBlank).mapTo(HashSet()) { Tsv.split(it).first() }
    }

    /** Every line split into exactly [fields] non-empty fields, or null for a torn or malformed file. */
    private fun readTable(file: File, fields: Int): List<Array<String>>? = runCatching {
        if (!file.isFile) return null
        val text = file.readText()
        if (text.isEmpty()) return emptyList()
        if (!text.endsWith("\n")) return null
        text.trimEnd('\n').split('\n').map { line ->
            Tsv.split(line).takeIf { parts -> parts.size == fields && parts.all(String::isNotEmpty) } ?: return null
        }
    }.getOrNull()

    private fun readBlocks(mapDir: File): Map<String, Map<String, String>>? =
        readTable(File(mapDir, ENVIRONMENT_FINGERPRINTS_FILE), 3)?.groupBy({ it[0] }, { it[1] to it[2] })
            ?.mapValues { (_, components) -> components.toMap() }

    /** The distinct fingerprints of [jvms], or null when any has none or a table cannot be read. */
    private fun readRecorded(mapDir: File, jvms: Set<String>): List<Map<String, String>>? {
        val rows = readTable(File(mapDir, ENVIRONMENT_FILE), 2)?.associate { it[0] to it[1] } ?: return null
        val blocks = readBlocks(mapDir) ?: return null
        val ids = jvms.map { rows[it] ?: return null }.toSortedSet()
        return ids.map { id -> blocks[id]?.takeIf { idOf(it) == id } ?: return null }
    }
}
