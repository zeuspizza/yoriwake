package io.github.zeuspizza.yoriwake.gradle

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import io.github.zeuspizza.yoriwake.agent.select.ChangeSet
import io.github.zeuspizza.yoriwake.agent.select.MapReader
import io.github.zeuspizza.yoriwake.agent.select.Selector
import io.github.zeuspizza.yoriwake.gradle.capture.CaptureDecision
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.DigestWidening
import io.github.zeuspizza.yoriwake.gradle.change.Established
import io.github.zeuspizza.yoriwake.gradle.change.ForcingPaths
import io.github.zeuspizza.yoriwake.gradle.change.InlineWidening
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.ScopedChange
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.report.Payback
import io.github.zeuspizza.yoriwake.gradle.report.write
import io.github.zeuspizza.yoriwake.gradle.report.writeExplanation
import io.github.zeuspizza.yoriwake.gradle.report.writeUnanswered
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * `explain.json` and `audit.json` are read by scripts, never by a person, so they must be JSON for
 * any name a build can contain -- and their layout must not drift, because those scripts were
 * written against it.
 */
class JsonTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "no golden file $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    // A relative path, because the reason names it and a golden cannot hold a temporary directory.
    private fun fullRun(): Selector.Decision =
        Selector.decide(
            MapReader.read(File("no-map-here")),
            ChangeSet.of(listOf("com.acme.Thing"), emptyList()),
            emptyList(),
        )

    private fun digest(added: Set<String>, silence: String?) = DigestWidening(
        added = added, silence = silence, detail = "two of three digested", considered = 3,
        digested = 2, unreadable = 1, recorded = 4, bytesAreFresh = false,
    )

    @Test
    fun `explain json for a refused widening keeps its layout`(@TempDir dir: File) {
        writeExplanation(
            dir, ":core:test", "HEAD~1",
            ScopedChange(
                ChangeDetection.Change(
                    setOf("com.acme.Thing", "com.acme.Other"), listOf("docs/README.md", "build.gradle.kts"),
                ),
                2,
            ),
            Established(
                setOf("com.acme.Untested"), setOf("com.acme.ThingTest"), setOf("src/main/resources/x.txt"),
                mapOf("com.acme.Thing" to "a constant changed", "com.acme.Other" to "no bytes"),
            ),
            fullRun(),
            InlineWidening.refuse(
                "a constant changed", scanExhausted = true,
                kind = RefusalKind.CONSTANT_CHANGED, detail = "com.acme.Thing.LIMIT",
                digest = digest(setOf("com.acme.Y", "com.acme.X"), "nothing was recorded"),
            ),
            CaptureDecision(capture = true, fullRun = true, mapCurrent = false, reason = "running everything"),
            ForcingPaths.Classified(mapOf("docs/README.md" to ForcingPaths.Origin.UNTRACKED), emptyMap(), emptyMap(), 1),
        )

        assertEquals(golden("explain-refused.json"), File(dir, YoriwakePlugin.EXPLANATION_FILE).readText())
    }

    @Test
    fun `explain json for a widened change keeps its layout`(@TempDir dir: File) {
        writeExplanation(
            dir, ":app:test", "origin/main",
            ScopedChange(ChangeDetection.Change(setOf("com.acme.Thing"), emptyList()), 0),
            Established(emptySet(), emptySet(), emptySet()),
            fullRun(),
            InlineWidening.widened(
                listOf("com.acme.Thing"), setOf("com.acme.InlineB", "com.acme.InlineA"),
                digest(emptySet(), null),
            ),
        )

        assertEquals(golden("explain-widened.json"), File(dir, YoriwakePlugin.EXPLANATION_FILE).readText())
    }

    @Test
    fun `explain json for an unanswered run keeps its layout`(@TempDir dir: File) {
        writeUnanswered(
            dir, ":core:test", "HEAD~1", Selector.Decision.FullRunKind.DAEMON_REFUSED,
            "git could not report a change set", refusalKind = "change-set-unknown",
        )

        assertEquals(golden("explain-unanswered.json"), File(dir, YoriwakePlugin.EXPLANATION_FILE).readText())
    }

    private fun result(name: String = "com.acme.Hub", text: String = "a line") = Audit.Result(
        state = Audit.State.NARROWING_ONLY,
        headline = "NARROWING ONLY: $text",
        lines = listOf(text, "27.3% of tests"),
        counts = Audit.Counts(lines = 12, malformed = 1, testRecords = 10, nonTestRecords = 1),
        distribution = Audit.Distribution(
            tests = 10, classes = 4, unattributableTests = 1, unattributableShare = 0.1, meanShare = 0.25,
            medianTestsPerClass = 2, medianShare = 0.2, p90Share = 0.6, p99Share = 0.9, maxShare = 0.9,
            hubClassesOverHalf = 1, startupClasses = 0,
            hubs = listOf(Audit.Hub(name, 9, 0.9), Audit.Hub("com.acme.Other", 6, 0.6)),
        ),
        provenance = Audit.Provenance(captureCommit = "abcdef1", ageDays = 3),
        blockers = listOf(
            Audit.Blocker("not-on-junit-platform", "the task runs TestNG", "Move to the JUnit Platform."),
            Audit.Blocker(name, "a detail", null),
        ),
        recordedTime = Payback.RecordedTime(
            nanos = 2_500_000_000, records = 10, withoutDuration = 1, workers = 2, duplicated = 1,
        ),
    )

    @Test
    fun `audit json for a conclusion keeps its layout`(@TempDir dir: File) {
        Audit.write(dir, result())

        assertEquals(golden("audit.json"), File(dir, Audit.AUDIT_FILE).readText())
    }

    @Test
    fun `audit json for a refusal keeps its layout`(@TempDir dir: File) {
        Audit.write(
            dir,
            result().copy(
                state = Audit.State.NO_CONCLUSION, distribution = null, blockers = emptyList(),
                recordedTime = Payback.RecordedTime(0, 0, 0, null),
                provenance = Audit.Provenance(null, null),
            ),
        )

        assertEquals(golden("audit-refusal.json"), File(dir, Audit.AUDIT_FILE).readText())
    }

    /** Every character RFC 8259 requires escaped, and the two a naive escaper already handles. */
    private val hostile = "a\nb\tc\"d\\e\u0001f\u001fg\r\b\u000c"

    /**
     * A strict parse: Jackson rejects raw control characters in a string by default, which the
     * lenient readers already on the classpath would let through.
     */
    private fun strings(json: String): List<String> =
        JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).createParser(json).use { p ->
            buildList {
                while (true) {
                    val token = p.nextToken() ?: break
                    if (token == JsonToken.VALUE_STRING) add(p.text)
                }
            }
        }

    @Test
    fun `explain json stays valid JSON and round-trips any name`(@TempDir dir: File) {
        writeExplanation(
            dir, hostile, hostile,
            ScopedChange(ChangeDetection.Change(setOf(hostile), listOf(hostile)), 0),
            Established(emptySet(), emptySet(), emptySet(), mapOf(hostile to hostile)),
            fullRun(),
            InlineWidening.widened(emptyList(), setOf(hostile), digest(setOf(hostile), hostile)),
            CaptureDecision(capture = false, fullRun = true, mapCurrent = false, reason = hostile),
        )

        val values = strings(File(dir, YoriwakePlugin.EXPLANATION_FILE).readText())
        // task, base, the path sample, the forcing path, captureReason, inliners, digestSilence,
        // the digest sample, and the refusal's class and reason.
        assertEquals(10, values.count { it == hostile }, values.toString())
    }

    @Test
    fun `an unanswered explain json stays valid JSON and round-trips any name`(@TempDir dir: File) {
        writeUnanswered(dir, hostile, hostile, Selector.Decision.FullRunKind.DAEMON_REFUSED, hostile, hostile)

        val values = strings(File(dir, YoriwakePlugin.EXPLANATION_FILE).readText())
        assertEquals(4, values.count { it == hostile }, values.toString())
    }

    @Test
    fun `audit json stays valid JSON and round-trips any name`(@TempDir dir: File) {
        Audit.write(dir, result(name = hostile, text = hostile).copy(provenance = Audit.Provenance(hostile, 1)))

        val values = strings(File(dir, Audit.AUDIT_FILE).readText())
        // the line, the hub, the blocker's token and the capture commit; the headline embeds it.
        assertEquals(4, values.count { it == hostile }, values.toString())
        assertEquals(1, values.count { it == "NARROWING ONLY: $hostile" }, values.toString())
    }
}
