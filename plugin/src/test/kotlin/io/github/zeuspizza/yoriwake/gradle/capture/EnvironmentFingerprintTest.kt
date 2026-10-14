package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint.Verdict
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The environment fingerprint: what a capture records of the test JVM's environment, and whether a
 * selecting run's environment is the same. A difference, or a fingerprint that cannot be read, runs
 * every test; no value of a system property or variable is ever kept in plain text.
 */
class EnvironmentFingerprintTest {

    @TempDir
    lateinit var dir: File

    private val mapDir by lazy { File(dir, "map").apply { mkdirs() } }

    private fun rules(
        root: String = "/work/checkout",
        ignore: List<String> = emptyList(),
        include: List<String> = emptyList(),
    ) = EnvironmentFingerprint.Rules(ignore, include, root, "/home/ci/.gradle", "/home/ci")

    private fun view(
        jdk: String? = "21.0.8+9-LTS",
        vendor: String? = "Microsoft",
        executable: String? = null,
        jvmArgs: List<String> = listOf("-Dfile.encoding=UTF-8", "-Dfeature.x=on"),
        environment: Map<String, String> = mapOf("LANG" to "en_US.UTF-8", "HOME" to "/home/ci"),
    ) = EnvironmentFingerprint.View(jdk, vendor, executable, "Linux", "amd64", jvmArgs, environment)

    /** A map whose one JVM ran a test and was recorded under [recorded]. */
    private fun recordedUnder(recorded: EnvironmentFingerprint.View, rules: EnvironmentFingerprint.Rules = rules()) {
        File(mapDir, AgentContract.POSITIONS_FILE).writeText(Tsv.join("run/worker-1", "1", "[engine:junit-jupiter]/[class:A]") + "\n")
        EnvironmentFingerprint.writePending(mapDir, recorded, rules)
        val pending = EnvironmentFingerprint.readPending(mapDir)
        EnvironmentFingerprint.write(mapDir, retained = emptyList(), decodedJvms = listOf("run/worker-1"), pending = pending)
    }

    private fun mapFiles() = mapDir.walk().filter(File::isFile).joinToString("\n") { it.name + ":\n" + it.readText() }

    @Test
    fun `the same environment matches`() {
        recordedUnder(view())
        assertEquals(Verdict.Matches, EnvironmentFingerprint.check(mapDir, view(), rules()).verdict)
    }

    @Test
    fun `another JDK names the component and both versions`() {
        recordedUnder(view(jdk = "17.0.9+9"))
        val verdict = EnvironmentFingerprint.check(mapDir, view(jdk = "21.0.2+13"), rules()).verdict
        assertIs<Verdict.Changed>(verdict)
        assertEquals("jdk.version", verdict.component)
        assertContains(verdict.reason, "17.0.9+9")
        assertContains(verdict.reason, "21.0.2+13")
    }

    @Test
    fun `a system property with another value is named and neither value is printed`() {
        recordedUnder(view(jvmArgs = listOf("-Dfeature.x=on")))
        val verdict = EnvironmentFingerprint.check(mapDir, view(jvmArgs = listOf("-Dfeature.x=off")), rules()).verdict
        assertIs<Verdict.Changed>(verdict)
        assertEquals("sys:feature.x", verdict.component)
        assertFalse(Regex("""\b(on|off)\b""").containsMatchIn(verdict.reason), verdict.reason)
    }

    @Test
    fun `a system property added or removed changes the environment`() {
        recordedUnder(view(jvmArgs = listOf("-Da=1")))
        val added = EnvironmentFingerprint.check(mapDir, view(jvmArgs = listOf("-Da=1", "-Db=2")), rules()).verdict
        assertEquals("sys:b", (added as Verdict.Changed).component)
        val removed = EnvironmentFingerprint.check(mapDir, view(jvmArgs = emptyList()), rules()).verdict
        assertEquals("sys:a", (removed as Verdict.Changed).component)
    }

    @Test
    fun `the plugin's own properties are not part of the environment`() {
        recordedUnder(view(jvmArgs = listOf("-Dyoriwake.select=false", "-Dyoriwake.map.dir=/a")))
        val verdict = EnvironmentFingerprint.check(
            mapDir, view(jvmArgs = listOf("-Dyoriwake.select=true", "-Dyoriwake.run.token=x")), rules(),
        ).verdict
        assertEquals(Verdict.Matches, verdict)
    }

    @Test
    fun `the last of two arguments for one property is the value the JVM sees`() {
        recordedUnder(view(jvmArgs = listOf("-Da=1", "-Da=2")))
        assertEquals(Verdict.Matches, EnvironmentFingerprint.check(mapDir, view(jvmArgs = listOf("-Da=2")), rules()).verdict)
    }

    @Test
    fun `an ignored property is left out and reported, and a later ignore covers the recorded one`() {
        recordedUnder(view(jvmArgs = listOf("-Dbuild.number=41")))
        val check = EnvironmentFingerprint.check(
            mapDir, view(jvmArgs = listOf("-Dbuild.number=42", "-Drebuild.number=1")), rules(ignore = listOf("build.*")),
        )
        // rebuild.number is not ignored, and was not recorded.
        assertEquals("sys:rebuild.number", (check.verdict as Verdict.Changed).component)
        assertEquals(listOf("build.number"), check.ignored)
    }

    @Test
    fun `the locale and time zone are compared, and an unset variable differs from an empty one`() {
        recordedUnder(view(environment = mapOf("LANG" to "en_US.UTF-8")))
        val changed = EnvironmentFingerprint.check(mapDir, view(environment = mapOf("LANG" to "de_DE.UTF-8")), rules()).verdict
        assertEquals("env:LANG", (changed as Verdict.Changed).component)
        val empty = EnvironmentFingerprint.check(
            mapDir, view(environment = mapOf("LANG" to "en_US.UTF-8", "TZ" to "")), rules(),
        ).verdict
        assertEquals("env:TZ", (empty as Verdict.Changed).component)
    }

    @Test
    fun `a variable nobody listed is not compared`() {
        recordedUnder(view(environment = mapOf("LANG" to "C", "DB_MODE" to "a", "GITHUB_RUN_ID" to "1")))
        val verdict = EnvironmentFingerprint.check(
            mapDir, view(environment = mapOf("LANG" to "C", "DB_MODE" to "b", "GITHUB_RUN_ID" to "2")), rules(),
        ).verdict
        assertEquals(Verdict.Matches, verdict)
        assertFalse(mapFiles().contains("DB_MODE"))
    }

    @Test
    fun `a listed variable is compared by name or pattern, set or unset`() {
        val include = listOf("DB_MODE", "CACHE_*")
        recordedUnder(view(environment = mapOf("DB_MODE" to "a", "CACHE_DIR" to "x")), rules(include = include))
        val mode = EnvironmentFingerprint.check(
            mapDir, view(environment = mapOf("DB_MODE" to "b", "CACHE_DIR" to "x")), rules(include = include),
        ).verdict
        assertEquals("env:DB_MODE", (mode as Verdict.Changed).component)
        val unset = EnvironmentFingerprint.check(mapDir, view(environment = mapOf("CACHE_DIR" to "x")), rules(include = include)).verdict
        assertEquals("env:DB_MODE", (unset as Verdict.Changed).component)
        val gone = EnvironmentFingerprint.check(mapDir, view(environment = mapOf("DB_MODE" to "a")), rules(include = include)).verdict
        assertEquals("env:CACHE_DIR", (gone as Verdict.Changed).component)
        // Listed only now: compared, and recorded with no value, so it differs.
        val now = EnvironmentFingerprint.check(
            mapDir, view(environment = mapOf("DB_MODE" to "a", "CACHE_DIR" to "x", "EXTRA" to "1")),
            rules(include = include + "EXTRA"),
        ).verdict
        assertEquals("env:EXTRA", (now as Verdict.Changed).component)
    }

    @Test
    fun `no value is written in plain text, and each map has its own salt`() {
        val secret = "s3cr3t-value"
        recordedUnder(
            view(jvmArgs = listOf("-Dtoken=$secret"), environment = mapOf("LANG" to secret, "DB_PASS" to secret)),
            rules(include = listOf("DB_PASS")),
        )
        assertFalse(mapFiles().contains(secret), mapFiles())
        val first = File(mapDir, AgentContract.ENVIRONMENT_FINGERPRINTS_FILE).readText()
        val other = File(dir, "other").apply { mkdirs() }
        EnvironmentFingerprint.writePending(other, view(jvmArgs = listOf("-Dtoken=$secret")), rules())
        val digestOf = { text: String -> text.lines().first { "sys:token" in it }.substringAfterLast('\t') }
        assertNotEquals(digestOf(first), digestOf(File(other, EnvironmentFingerprint.PENDING_FILE).readText()))
    }

    @Test
    fun `paths of the checkout, the Gradle user home and the user's home are not a change`() {
        recordedUnder(
            view(jvmArgs = listOf("-Dfixtures=/work/checkout/src/test", "-Dcache=/home/ci/.gradle/caches"),
                executable = "/home/ci/jdks/21/bin/java", jdk = null, vendor = null),
            rules(root = "/work/checkout"),
        )
        val elsewhere = EnvironmentFingerprint.Rules(emptyList(), emptyList(), "/runner/_work/repo", "/Users/dev/.gradle", "/Users/dev")
        val verdict = EnvironmentFingerprint.check(
            mapDir,
            view(jvmArgs = listOf("-Dfixtures=/runner/_work/repo/src/test", "-Dcache=/Users/dev/.gradle/caches"),
                executable = "/Users/dev/jdks/21/bin/java", jdk = null, vendor = null),
            elsewhere,
        ).verdict
        assertEquals(Verdict.Matches, verdict)
        val stored = File(mapDir, AgentContract.ENVIRONMENT_FINGERPRINTS_FILE).readText()
        assertContains(stored, "<home>/jdks/21/bin/java")
        assertFalse(stored.contains("/home/ci"))
    }

    @Test
    fun `a map with no fingerprint, or a JVM without one, is unrecorded`() {
        File(mapDir, AgentContract.POSITIONS_FILE).writeText(Tsv.join("run/worker-1", "1", "t") + "\n")
        assertIs<Verdict.Unrecorded>(EnvironmentFingerprint.check(mapDir, view(), rules()).verdict)

        recordedUnder(view())
        File(mapDir, AgentContract.POSITIONS_FILE).appendText(Tsv.join("run/worker-2", "1", "u") + "\n")
        assertIs<Verdict.Unrecorded>(EnvironmentFingerprint.check(mapDir, view(), rules()).verdict)
    }

    @Test
    fun `a fingerprint file cut short, or a missing salt, is unrecorded`() {
        recordedUnder(view())
        val table = File(mapDir, AgentContract.ENVIRONMENT_FINGERPRINTS_FILE)
        val whole = table.readText()
        table.writeText(whole.dropLast(5))
        assertIs<Verdict.Unrecorded>(EnvironmentFingerprint.check(mapDir, view(), rules()).verdict)
        table.writeText(whole)
        File(mapDir, EnvironmentFingerprint.SALT_FILE).delete()
        assertIs<Verdict.Unrecorded>(EnvironmentFingerprint.check(mapDir, view(), rules()).verdict)
    }

    @Test
    fun `a map whose JVMs ran nothing has nothing to compare`() {
        assertEquals(Verdict.Matches, EnvironmentFingerprint.check(mapDir, view(), rules()).verdict)
    }

    @Test
    fun `JVMs recorded under two environments refuse while either differs`() {
        recordedUnder(view(jdk = "17.0.9"))
        val seventeen = File(mapDir, AgentContract.ENVIRONMENT_FILE).readLines()
        EnvironmentFingerprint.writePending(mapDir, view(jdk = "21.0.2"), rules())
        EnvironmentFingerprint.write(
            mapDir, retained = listOf("run/worker-1"), decodedJvms = listOf("later/worker-1"),
            pending = EnvironmentFingerprint.readPending(mapDir),
        )
        File(mapDir, AgentContract.POSITIONS_FILE).appendText(Tsv.join("later/worker-1", "1", "u") + "\n")
        assertTrue(File(mapDir, AgentContract.ENVIRONMENT_FILE).readLines().containsAll(seventeen))
        assertIs<Verdict.Changed>(EnvironmentFingerprint.check(mapDir, view(jdk = "21.0.2"), rules()).verdict)
        assertIs<Verdict.Changed>(EnvironmentFingerprint.check(mapDir, view(jdk = "17.0.9"), rules()).verdict)
    }

    @Test
    fun `the variables the tests read and nobody listed are named, and a whole-environment read is said`() {
        File(mapDir, AgentContract.ENV_READS_FILE).writeText("*\nDB_MODE\nLANG\nCACHE_DIR\n")
        val lines = EnvironmentFingerprint.uncomparedReads(mapDir, rules(include = listOf("CACHE_*")))
        assertEquals(2, lines.size, "$lines")
        assertContains(lines[0], "not compared: DB_MODE;")
        assertContains(lines[1], "read the whole environment")
        File(mapDir, AgentContract.ENV_READS_FILE).writeText("LANG\n")
        assertEquals(emptyList(), EnvironmentFingerprint.uncomparedReads(mapDir, rules()))
        File(mapDir, AgentContract.ENV_READS_FILE).delete()
        assertContains(EnvironmentFingerprint.uncomparedReads(mapDir, rules()).single(), "not observed")
    }

    @Test
    fun `a capture with no pending fingerprint leaves its JVMs unrecorded`() {
        File(mapDir, AgentContract.POSITIONS_FILE).writeText(Tsv.join("run/worker-1", "1", "t") + "\n")
        EnvironmentFingerprint.write(mapDir, retained = emptyList(), decodedJvms = listOf("run/worker-1"), pending = null)
        assertIs<Verdict.Unrecorded>(EnvironmentFingerprint.check(mapDir, view(), rules()).verdict)
    }
}
