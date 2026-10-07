package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.security.MessageDigest

/**
 * Whether a map's content is one a trusted run vouched for. A pull request can restore a map from a
 * cache scope it wrote itself, which no reviewer sees; on a run given a trusted-map list, a map
 * narrows only when the list names its exact digest. Without a list nothing is checked.
 */
internal object MapProvenance {

    /**
     * Every map file a selecting run or the test JVM reads to decide what runs: the files the decode
     * writes. The digest and the check read this one list, so they cannot drift apart.
     */
    val DIGESTED: List<String> = listOf(
        AgentContract.COVERAGE_FILE,
        AgentContract.POSITIONS_FILE,
        AgentContract.FIRST_TOUCH_FILE,
        AgentContract.NAMED_TOUCH_FILE,
        AgentContract.JVM_MODE_FILE,
        AgentContract.SCOPE_FILE,
        AgentContract.EFFECTIVE_SCOPE_FILE,
        AgentContract.LOADED_FILE,
        AgentContract.LOADED_PROVENANCE_FILE,
        AgentContract.LOADED_SCOPE_FILE,
        CoverageDecoder.CONSTANTS_FILE,
        CoverageDecoder.CLASS_DIGESTS_FILE,
        CoverageDecoder.ANNOTATION_DIGESTS_FILE,
        CoverageDecoder.RESOURCE_DIGESTS_FILE,
        CoverageDecoder.CAPTURE_COMMIT_FILE,
        WorkingTree.SNAPSHOT_FILE,
        AgentContract.MAP_SCHEMA_VERSION_FILE,
    ).sorted()

    /** Each file's name, length and bytes in a fixed order; an absent file contributes its name and `-`. */
    fun digest(mapDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        DIGESTED.forEach { name ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            val file = File(mapDir, name)
            if (!file.isFile) {
                digest.update('-'.code.toByte())
                return@forEach
            }
            digest.update(file.length().toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Written after every decode that writes a digested file, for a caller to list the map by. */
    fun writeDigest(mapDir: File) {
        writeAtomically(File(mapDir, AgentContract.MAP_DIGEST_FILE), "sha256 ${digest(mapDir)}\n")
    }

    private val HEX = Regex("[0-9a-f]{64}")

    /**
     * `-Pyoriwake.trustedMaps`'s lines, `<map directory name>\t<sha256 hex>`, as a map. A malformed
     * line is the caller's mistake and fails the build; a map it does not name is not an error.
     */
    fun parseTrustedList(text: String, origin: String): Map<String, String> =
        text.lines().filter(String::isNotBlank).associate { line ->
            val fields = line.trim().split('\t')
            require(fields.size == 2 && fields[0].isNotEmpty() && HEX.matches(fields[1])) {
                "[yoriwake] $origin: \"$line\" is not a `<map directory name><tab><sha256 hex>` line."
            }
            fields[0] to fields[1]
        }

    sealed interface Verdict {
        /** The refusal a run makes on this verdict, as its kind and reason; null when trusted. */
        val refusal: Pair<RefusalKind, String>?

        data class Trusted(val digest: String) : Verdict {
            override val refusal: Pair<RefusalKind, String>? get() = null
        }

        data class Unverified(val reason: String) : Verdict {
            override val refusal get() = RefusalKind.MAP_UNVERIFIED to reason
        }

        data class Untrusted(val reason: String) : Verdict {
            override val refusal get() = RefusalKind.MAP_UNTRUSTED to reason
        }
    }

    /** [listed] is the digest the trusted list names for this map directory, or null when it names none. */
    fun verify(mapDir: File, listed: String?): Verdict {
        val digest = runCatching { digest(mapDir) }.getOrElse {
            return Verdict.Unverified("the map's files could not be read to check them ($it)")
        }
        if (listed == null) {
            return Verdict.Unverified(
                "the trusted-map list does not name the map ${mapDir.name}, so no trusted run vouched for it"
            )
        }
        if (listed != digest) {
            return Verdict.Untrusted(
                "the map is not what the trusted-map list vouches for (digest $digest, listed $listed)"
            )
        }
        return Verdict.Trusted(digest)
    }

    /** Removes an untrusted map, so the capture that follows holds only what it observes. */
    fun clear(mapDir: File) {
        (DIGESTED + AgentContract.MAP_DIGEST_FILE).forEach { File(mapDir, it).delete() }
    }

    /**
     * [verify] as the Test task's input, so a result cached under another verdict is never reused:
     * obtained when Gradle fingerprints the task, after any earlier task wrote the map.
     */
    internal abstract class Source : ValueSource<String, Source.Parameters> {

        interface Parameters : ValueSourceParameters {
            val mapDir: Property<String>
            /** Unset when the list names no digest for this map. */
            val listed: Property<String>
        }

        override fun obtain(): String =
            when (val verdict = verify(File(parameters.mapDir.get()), parameters.listed.orNull)) {
                is Verdict.Trusted -> "trusted ${verdict.digest}"
                else -> "untrusted"
            }
    }
}
