package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The selector's contract, rule by rule. Every rule that forces a full run is evaluated before any
 * rule that narrows.
 */
class SelectorTest {

    private fun map(
        dir: File,
        vararg entries: Triple<String, String, String>,
        version: Int? = AgentContract.MAP_SCHEMA_VERSION,
        scope: List<String>? = null,
        loaded: List<String>? = null,
        loadedBy: String = "full",
        loadedScope: List<String>? = listOf("com.acme"),
        /** `jvm\tsequence\ttestId` rows; null gives every record a JVM of its own. */
        positions: List<String>? = null,
        /** `jvm\tsequence\tclass` rows, used with [positions]. */
        firstTouches: List<String> = emptyList(),
        /** As [firstTouches], for the named-touch table. */
        namedTouches: List<String> = emptyList(),
    ): MapReader.Result {
        dir.mkdirs()
        scope?.let { File(dir, "scope").writeText(it.joinToString("\n", postfix = "\n")) }
        loaded?.let {
            File(dir, "loaded.txt").writeText(it.joinToString("\n", postfix = "\n"))
            File(dir, "loaded-provenance").writeText("$loadedBy\n")
            loadedScope?.let { scopes ->
                File(dir, "loaded-scope").writeText(scopes.joinToString("\n", postfix = "\n"))
            }
        }
        File(dir, "coverage.tsv").writeText(
            entries.joinToString("\n", postfix = "\n") { (id, outcome, classes) ->
                "$outcome\t1000000\t$classes\t$id"
            }
        )
        writeSoloOrder(dir)
        positions?.let {
            File(dir, AgentContract.POSITIONS_FILE).writeText(it.joinToString("") { row -> "$row\n" })
            File(dir, AgentContract.FIRST_TOUCH_FILE).writeText(firstTouches.joinToString("") { row -> "$row\n" })
            File(dir, AgentContract.NAMED_TOUCH_FILE).writeText(namedTouches.joinToString("") { row -> "$row\n" })
        }
        version?.let { File(dir, "schema-version").writeText("$it\n") }
        return MapReader.read(dir)
    }

    private fun test(name: String, classes: String, outcome: String = "SUCCESSFUL") =
        Triple("[engine:junit-jupiter]/[class:$name]/[method:t()]", outcome, classes)

    private fun decide(
        map: MapReader.Result,
        changed: List<String> = emptyList(),
        exempt: List<String> = emptyList(),
        own: List<String> = exempt,
        unmappable: List<String> = emptyList(),
        discovered: List<String> = emptyList(),
        provable: List<String> = emptyList(),
        unreadable: List<String> = emptyList(),
        bytes: List<String> = emptyList(),
        accounted: Boolean = false,
    ) = Selector.decide(
        map,
        ChangeSet.of(changed, unmappable).withAbsenceProvable(provable).withUnreadablePaths(unreadable)
            .withChangedBytes(bytes).withAccountedFor(accounted).withExemptTestClasses(exempt)
            .withOwnTestClasses(own),
        discovered,
    )

    // A Spock data-driven feature is reported as a TEST but has its own record holding only
    // framework machinery, while the coverage that reaches the change sits on its iterations.

    private fun feature(spec: String) =
        "[engine:spock]/[spec:$spec]/[feature:\$spock_feature_0_0]"

    @Test
    fun `a parameterised feature whose iterations reach the change is not skipped`(@TempDir dir: File) {
        val id = feature("com.acme.HandlerTest")
        val decision = decide(
            map(
                dir,
                // The container's own record touched none of the classes its iterations did.
                Triple(id, "SUCCESSFUL", "org.junit.platform.engine.TestDescriptor"),
                Triple("$id/[iteration:0]", "SUCCESSFUL", "com.acme.Handler"),
                Triple("$id/[iteration:1]", "SUCCESSFUL", "com.acme.Handler"),
            ),
            changed = listOf("com.acme.Handler"),
        )

        // On its own the exact-id rule answers SKIPPED here.
        assertEquals(Selector.Decision.Reason.SKIPPED, decision.reasonFor(id))

        // `reasonForTest` is what the filter acts on for a descriptor that says it is a test.
        assertEquals(
            Selector.Decision.Reason.REACHES_CHANGE,
            decision.reasonForTest(id),
            "the iterations' coverage reaches the change, so the feature must run",
        )
    }

    @Test
    fun `includes agrees with the rule the filter applies, on every shape`(@TempDir dir: File) {
        // `includes` has no production caller, so a divergence from `reasonForTest` would go
        // unnoticed until a new caller skipped tests with a green suite.
        val feature = "[engine:spock]/[spec:com.acme.HandlerTest]/[feature:\$spock_feature_0_0]"
        val decision = decide(
            map(
                dir,
                Triple(feature, "SUCCESSFUL", "org.junit.platform.engine.TestDescriptor"),
                Triple("$feature/[iteration:0]", "SUCCESSFUL", "com.acme.Handler"),
                test("com.acme.ATest", "com.acme.Handler"),
                test("com.acme.BTest", "com.acme.Other"),
            ),
            changed = listOf("com.acme.Handler"),
        )

        for (id in listOf(feature, "$feature/[iteration:0]",
                          "[engine:junit-jupiter]/[class:com.acme.ATest]/[method:t()]",
                          "[engine:junit-jupiter]/[class:com.acme.BTest]/[method:t()]",
                          "[engine:junit-jupiter]/[class:com.acme.Unseen]/[method:t()]")) {
            assertEquals(
                decision.reasonForTest(id) != Selector.Decision.Reason.SKIPPED,
                decision.includes(id),
                "includes and the filter's own rule disagree about $id",
            )
        }
        // And the container specifically, which is the shape they disagreed on.
        assertTrue(decision.includes(feature), "its iterations reach the change, so it must run")
    }

    @Test
    fun `a leaf with no recorded children never consults the subtree`(@TempDir dir: File) {
        // For every id the map holds with no children, the short-circuit and the exact rule agree.
        val map = map(dir, test("com.acme.ATest", "com.acme.A"), test("com.acme.BTest", "com.acme.B"),
                      test("com.acme.CTest", "com.acme.C"))
        val decision = decide(map, changed = listOf("com.acme.A"))

        for (cls in listOf("ATest", "BTest", "CTest")) {
            val id = "[engine:junit-jupiter]/[class:com.acme.$cls]/[method:t()]"
            assertEquals(
                decision.reasonFor(id), decision.reasonForTest(id),
                "$cls has no recorded children, so both rules must answer the same",
            )
        }
    }

    @Test
    fun `an ordinary test with no children in the map is decided exactly as before`(@TempDir dir: File) {
        // A leaf with no descendants in the map gets the same answer from both rules.
        val decision = decide(
            map(dir, test("com.acme.ATest", "com.acme.A"), test("com.acme.BTest", "com.acme.B")),
            changed = listOf("com.acme.A"),
        )
        val reached = "[engine:junit-jupiter]/[class:com.acme.ATest]/[method:t()]"
        val untouched = "[engine:junit-jupiter]/[class:com.acme.BTest]/[method:t()]"

        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonForTest(reached))
        assertEquals(Selector.Decision.Reason.SKIPPED, decision.reasonForTest(untouched))
        assertEquals(decision.reasonFor(untouched), decision.reasonForTest(untouched))
    }

    @Test
    fun `a feature the map has never seen is still NOT_IN_MAP rather than skipped`(@TempDir dir: File) {
        // A container with no record and no recorded children is unknown, and unknown runs.
        val decision = decide(
            map(dir, test("com.acme.ATest", "com.acme.A")),
            changed = listOf("com.acme.A"),
        )

        assertEquals(
            Selector.Decision.Reason.NOT_IN_MAP,
            decision.reasonForTest(feature("com.acme.UnseenTest")),
        )
    }

    @Test
    fun `every forcing rule names itself, so nothing downstream matches English for it`(
        @TempDir dir: File,
    ) {
        // Each forcing rule has a different remedy, so each needs a token a script can branch on.
        val m = map(dir, test("A", "com.acme.A"), test("Startup", "com.acme.Boot"))

        assertEquals(
            Selector.Decision.FullRunKind.MAP_UNUSABLE,
            decide(MapReader.read(File(dir, "absent")), changed = listOf("com.acme.A")).fullRunKind(),
        )
        assertEquals(
            Selector.Decision.FullRunKind.UNMAPPABLE_PATHS,
            decide(m, changed = listOf("com.acme.A"), unmappable = listOf("build.gradle.kts"))
                .fullRunKind(),
        )
        assertEquals(
            Selector.Decision.FullRunKind.EMPTY_CHANGE_SET,
            decide(m, changed = emptyList()).fullRunKind(),
        )
        assertEquals(
            Selector.Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED,
            decide(m, changed = listOf("com.acme.Ghost")).fullRunKind(),
        )
        assertEquals(
            Selector.Decision.FullRunKind.DAEMON_REFUSED,
            Selector.decide(
                m, ChangeSet.of(listOf("com.acme.A"), emptyList()).withDaemonRefusal("the daemon already refused"),
                emptyList(),
            ).fullRunKind(),
        )
    }

    @Test
    fun `a run that narrows names no forcing kind`(@TempDir dir: File) {
        // The token is the machine-readable half of `fullRunReason`, so it has to be absent in
        // exactly the same case: a selecting run has no forcing reason to report.
        val decision = decide(map(dir, test("A", "com.acme.A")), changed = listOf("com.acme.A"))

        assertFalse(decision.isFullRun, decision.fullRunReason())
        assertEquals(null, decision.fullRunKind())
    }

    @Test
    fun `an unusable map forces a full run and says why`(@TempDir dir: File) {
        val decision = decide(MapReader.read(File(dir, "absent")), changed = listOf("com.acme.A"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "no map at")
    }

    @Test
    fun `a wrong-version map forces a full run`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"), version = AgentContract.MAP_SCHEMA_VERSION + 1)

        assertTrue(decide(m, changed = listOf("com.acme.A")).isFullRun)
    }

    @Test
    fun `an unmappable path forces a full run before anything else is considered`(@TempDir dir: File) {
        // Build scripts, resources and dependency versions: coverage says nothing about them, so
        // nothing can be ruled out.
        val m = map(dir, test("A", "com.acme.A"))

        val decision = decide(m, changed = listOf("com.acme.A"), unmappable = listOf("build.gradle.kts"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "build.gradle.kts")
    }

    @Test
    fun `a change touching startup coverage forces a full run`(@TempDir dir: File) {
        val m = map(
            dir,
            test("A", "com.acme.A"),
            Triple(AgentContract.UNATTRIBUTED_RECORD_ID, "NONE", "com.acme.Startup"),
        )

        val decision = decide(m, changed = listOf("com.acme.Startup"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "startup coverage")
    }

    @Test
    fun `a change to a class the map has never seen forces a full run`(@TempDir dir: File) {
        // Uncovered and unrecorded are indistinguishable, and only one of them is safe to act on.
        val m = map(dir, test("A", "com.acme.A"))

        val decision = decide(m, changed = listOf("com.acme.Ghost"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "com.acme.Ghost")
    }

    @Test
    fun `one unknown class among known ones is enough`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"))

        assertTrue(decide(m, changed = listOf("com.acme.A", "com.acme.Ghost")).isFullRun)
    }

    @Test
    fun `a nested type of a class the change names does not force a full run`(@TempDir dir: File) {
        // A file declaring `private class Inner` inside `Outer` yields the prefix com.acme.Inner,
        // while the class compiles to com.acme.Outer$Inner.
        val m = map(dir, test("A", "com.acme.Outer\$Inner"))

        val decision = decide(m, changed = listOf("com.acme.Outer", "com.acme.Inner"))

        assertFalse(decision.isFullRun, decision.fullRunReason())
        assertTrue(decision.includes("[engine:junit-jupiter]/[class:A]/[method:t()]"))
    }

    @Test
    fun `an unrelated class's nested type cannot certify a prefix as known`(@TempDir dir: File) {
        // Why the nested match is anchored on the outer class: com.acme.Other$Comment is coverage
        // of an unrelated file, and matching the simple name alone would skip the full run that
        // an uncovered com.acme.Comment requires.
        val m = map(dir, test("A", "com.acme.Other\$Comment"))

        val decision = decide(m, changed = listOf("com.acme.Comment"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "com.acme.Comment")
    }

    @Test
    fun `a same-named class in another package still forces a full run`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.Outer\$Inner"))

        assertTrue(decide(m, changed = listOf("com.other.Outer", "com.other.Inner")).isFullRun)
    }

    @Test
    fun `a package sharing a name prefix is not the same package`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acmecorp.Outer\$Inner"))

        assertTrue(decide(m, changed = listOf("com.acme.Outer", "com.acme.Inner")).isFullRun)
    }

    @Test
    fun `a deeper package is not matched by a shorter one`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.Outer\$Inner"))

        assertTrue(decide(m, changed = listOf("com.acme.rules.Outer", "com.acme.rules.Inner")).isFullRun)
    }

    @Test
    fun `a deeply nested type is matched at any level`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.Outer\$Mid\$Inner"))

        assertFalse(decide(m, changed = listOf("com.acme.Outer", "com.acme.Mid")).isFullRun)
        assertFalse(decide(m, changed = listOf("com.acme.Outer", "com.acme.Inner")).isFullRun)
    }

    @Test
    fun `a trailing dollar does not match an empty simple name`(@TempDir dir: File) {
        // Java's split drops trailing empty segments and Kotlin's keeps them; scanning avoids the
        // divergence, and an empty simple name must match nothing in either language.
        val m = map(dir, test("A", "com.acme.Outer\$"))

        assertTrue(decide(m, changed = listOf("com.acme.")).isFullRun)
    }

    @Test
    fun `names the classes nothing was selected from`(@TempDir dir: File) {
        val m = map(
            dir,
            Triple("[engine:junit4]/[class:com.acme.ATest]/[method:t()]", "SUCCESSFUL", "com.acme.A"),
            Triple("[engine:junit4]/[class:com.acme.BTest]/[method:t()]", "SUCCESSFUL", "com.acme.B"),
        )

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertEquals(setOf("com.acme.BTest"), decision.classesWithNothingSelected())
    }

    @Test
    fun `an id carrying no class segment stops every class being deselected`(@TempDir dir: File) {
        // Narrow only on shapes the capture recognises: an unreadable shape is typically a JUnit 4
        // runner or rule test that runs other tests inside itself, whose coverage lands on the
        // inner id.
        val m = map(
            dir,
            Triple("[engine:junit4]/[class:com.acme.ATest]/[method:t()]", "SUCCESSFUL", "com.acme.A"),
            Triple("[engine:junit4]/[class:com.acme.BTest]/[method:t()]", "SUCCESSFUL", "com.acme.B"),
            Triple("[engine:junit4]/[runner:something-odd]", "SUCCESSFUL", "com.acme.B"),
        )

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertEquals(emptySet(), decision.classesWithNothingSelected(),
            "one id with no class segment must stop the whole selection, not just itself")
        assertEquals(listOf("[engine:junit4]/[runner:something-odd]"),
            decision.idShapesNotRecognised())
    }

    @Test
    fun `a full run reports no unreadable id shapes, whatever the map holds`(@TempDir dir: File) {
        // The caller prints these as the reason the whole suite runs, so a run forced for another
        // reason must not name them.
        val m = map(
            dir,
            Triple("[engine:junit4]/[class:com.acme.ATest]/[method:t()]", "SUCCESSFUL", "com.acme.A"),
            Triple("[engine:junit4]/[runner:something-odd]", "SUCCESSFUL", "com.acme.B"),
        )

        val forced = decide(m, changed = emptyList(), unmappable = listOf("build.gradle.kts"))

        assertTrue(forced.isFullRun(), "the fixture must actually force, or this proves nothing")
        assertEquals(emptyList(), forced.idShapesNotRecognised())
    }

    @Test
    fun `every id carrying a class segment still deselects normally`(@TempDir dir: File) {
        // The rule above must refuse on an unreadable shape and only then.
        val m = map(
            dir,
            Triple("[engine:junit4]/[class:com.acme.ATest]/[method:t()]", "SUCCESSFUL", "com.acme.A"),
            Triple("[engine:junit4]/[class:com.acme.BTest]/[method:t()]", "SUCCESSFUL", "com.acme.B"),
        )

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertEquals(setOf("com.acme.BTest"), decision.classesWithNothingSelected())
        assertEquals(emptyList(), decision.idShapesNotRecognised())
    }

    @Test
    fun `a full run deselects no class at all`(@TempDir dir: File) {
        // Empty, not "every class": the caller applies no filter rather than an empty one, and an
        // empty include list means "run nothing" in some Gradle versions.
        val m = map(dir, Triple("[engine:junit4]/[class:com.acme.ATest]/[method:t()]", "SUCCESSFUL", "com.acme.A"))

        val decision = decide(m, changed = listOf("com.acme.Ghost"))

        assertTrue(decision.isFullRun)
        assertTrue(decision.classesWithNothingSelected().isEmpty())
    }

    @Test
    fun `a class the map never saw is never deselected`(@TempDir dir: File) {
        // The caller excludes what this returns, so a class absent from the map must be absent
        // here too and still run.
        val m = map(dir, Triple("[engine:junit4]/[class:com.acme.ATest]/[method:t()]", "SUCCESSFUL", "com.acme.A"))

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertFalse(decision.isFullRun)
        assertFalse(decision.classesWithNothingSelected().contains("com.acme.BrandNewTest"))
    }

    @Test
    fun `a class whose test did not pass is not deselected`(@TempDir dir: File) {
        val m = map(
            dir,
            Triple("[engine:junit4]/[class:com.acme.ATest]/[method:t()]", "SUCCESSFUL", "com.acme.A"),
            Triple("[engine:junit4]/[class:com.acme.BTest]/[method:t()]", "FAILED", "com.acme.B"),
        )

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertTrue(decision.classesWithNothingSelected().isEmpty(), "a failing test always re-runs")
    }

    @Test
    fun `an id shape it cannot parse is left alone`(@TempDir dir: File) {
        val m = map(
            dir,
            Triple("no-brackets-here", "SUCCESSFUL", "com.acme.B"),
            Triple("[engine:junit4]/[class:com.acme.ATest]/[method:t()]", "SUCCESSFUL", "com.acme.A"),
        )

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertEquals(emptySet(), decision.classesWithNothingSelected())
    }

    @Test
    fun `a full run includes tests the map has never seen`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"))

        val decision = decide(m, unmappable = listOf("build.gradle.kts"), discovered = listOf("brand-new"))

        assertTrue(decision.includes("brand-new"))
    }

    @Test
    fun `a class in the recorded scope but absent from the map still forces a full run`(
        @TempDir dir: File,
    ) {
        // The scope cannot prove a class untested: a file may declare a differently named type,
        // JaCoCo excludes are not in the scope, interfaces carry no probes, and records merge from
        // runs with an older scope.
        val m = map(dir, test("A", "com.acme.A"), scope = listOf("com.acme"))

        val decision = decide(m, changed = listOf("com.acme.Untested"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "com.acme.Untested")
    }

    @Test
    fun `the recorded scope is readable, because it is provenance worth keeping`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"), scope = listOf("com.acme", "org.other"))

        assertEquals(listOf("com.acme", "org.other"), m.instrumentationScope())
    }

    @Test
    fun `an empty change set forces a full run rather than selecting nothing`(@TempDir dir: File) {
        // The default base is HEAD, so committing and then running tests yields exactly this;
        // reading it as "nothing changed" would run almost nothing.
        val m = map(dir, test("A", "com.acme.A"), test("B", "com.acme.B"))

        val decision = decide(m)

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "empty")
    }

    @Test
    fun `a changed path the build never reads does not force`(@TempDir dir: File) {
        // Gradle never reads a CI workflow file and no compiled class names it, so nothing in this
        // JVM can open it.
        val m = map(dir, test("A", "com.acme.A"))

        val decision = decide(
            m,
            changed = listOf("com.acme.A"),
            unmappable = listOf(".github/workflows/ci.yml"),
            unreadable = listOf(".github/workflows/ci.yml"),
        )

        assertFalse(decision.isFullRun)
    }

    @Test
    fun `an unreadable path among readable ones still leaves the readable ones forcing`(
        @TempDir dir: File,
    ) {
        val m = map(dir, test("A", "com.acme.A"))

        val decision = decide(
            m,
            changed = listOf("com.acme.A"),
            unmappable = listOf(".github/workflows/ci.yml", "build.gradle.kts"),
            unreadable = listOf(".github/workflows/ci.yml"),
        )

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "build.gradle.kts")
        assertFalse(decision.fullRunReason().contains("ci.yml"))
    }

    @Test
    fun `a path the daemon said nothing about keeps forcing`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"))

        assertTrue(
            decide(m, changed = listOf("com.acme.A"), unmappable = listOf("README.md")).isFullRun
        )
    }

    @Test
    fun `a class nothing loaded and nothing executed is untested, when the daemon proved the rest`(
        @TempDir dir: File,
    ) {
        // The only rule that reasons from absence, so it needs every condition: the daemon proved
        // the class instrumented, recordable and named in no resource, and nothing loaded it.
        val m = map(dir, test("A", "com.acme.A"), loaded = listOf("com.acme.A", "com.acme.Helper"))

        val decision = decide(
            m,
            changed = listOf("com.acme.NeverLoaded"),
            provable = listOf("com.acme.NeverLoaded"),
        )

        assertFalse(decision.isFullRun)
    }

    @Test
    fun `a class the daemon could not vouch for still forces, however absent it is`(
        @TempDir dir: File,
    ) {
        // Absent from the union and absent from coverage, and it still forces: the daemon said
        // nothing about whether it was instrumented, recordable, or named in a resource.
        val m = map(dir, test("A", "com.acme.A"), loaded = listOf("com.acme.A", "com.acme.Helper"))

        val decision = decide(m, changed = listOf("com.acme.NeverLoaded"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "com.acme.NeverLoaded")
    }

    @Test
    fun `a class something loaded but nothing executed forces, even when the daemon vouched for it`(
        @TempDir dir: File,
    ) {
        // A test can reflect over a class's fields, annotations and enum constants without
        // executing a line of it. Coverage sees nothing; the loaded union sees the load.
        val m = map(dir, test("A", "com.acme.A"), loaded = listOf("com.acme.A", "com.acme.Introspected"))

        val decision = decide(
            m,
            changed = listOf("com.acme.Introspected"),
            provable = listOf("com.acme.Introspected"),
        )

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "com.acme.Introspected")
    }

    @Test
    fun `without a union, a vouched-for class still forces`(@TempDir dir: File) {
        // Presence in the union is the evidence; its absence is only the last of several
        // conditions. With no union at all there is no evidence of anything.
        val m = map(dir, test("A", "com.acme.A"))

        assertTrue(
            decide(
                m,
                changed = listOf("com.acme.NeverLoaded"),
                provable = listOf("com.acme.NeverLoaded"),
            ).isFullRun
        )
    }

    @Test
    fun `a union from a selecting run cannot license the narrowing either`(@TempDir dir: File) {
        val m = map(
            dir,
            test("A", "com.acme.A"),
            loaded = listOf("com.acme.A"),
            loadedBy = "selecting",
        )

        assertTrue(
            decide(
                m,
                changed = listOf("com.acme.NeverLoaded"),
                provable = listOf("com.acme.NeverLoaded"),
            ).isFullRun
        )
    }

    @Test
    fun `a class outside what the union recorded forces, because absence there means we were not looking`(
        @TempDir dir: File,
    ) {
        val m = map(
            dir,
            test("A", "com.acme.A"),
            loaded = listOf("com.acme.A"),
            loadedScope = listOf("com.acme"),
        )

        assertTrue(
            decide(
                m,
                changed = listOf("org.other.NeverLoaded"),
                provable = listOf("org.other.NeverLoaded"),
            ).isFullRun
        )
    }

    @Test
    fun `a sibling class of the changed file counts as loaded`(@TempDir dir: File) {
        // One source file compiles to several classes. The union holds their runtime names, so
        // `Foo$Inner` being loaded means `Foo.kt` was loaded, and the rule must refuse.
        val m = map(dir, test("A", "com.acme.A"), loaded = listOf("com.acme.A", "com.acme.Thing" + "$" + "Inner"))

        assertTrue(
            decide(
                m,
                changed = listOf("com.acme.Thing"),
                provable = listOf("com.acme.Thing"),
            ).isFullRun
        )
    }

    @Test
    fun `a class the task loaded but never executed still forces`(@TempDir dir: File) {
        // Interfaces, annotations and constant holders carry no probes, so they can be used without
        // appearing in coverage.
        val m = map(dir, test("A", "com.acme.A"), loaded = listOf("com.acme.A", "com.acme.Marker"))

        val decision = decide(m, changed = listOf("com.acme.Marker"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "com.acme.Marker")
    }

    @Test
    fun `a class excluded from instrumentation is loaded, so it still forces`(@TempDir dir: File) {
        // The host's JaCoCo excludes keep a class out of coverage, but the JVM still loads it.
        val m = map(dir, test("A", "com.acme.A"), loaded = listOf("com.acme.A", "com.acme.Excluded"))

        assertTrue(decide(m, changed = listOf("com.acme.Excluded")).isFullRun)
    }

    @Test
    fun `a union recorded by a selecting run is refused`(@TempDir dir: File) {
        // Such a run loaded only what its selected tests needed, so believing it would let the
        // selector skip more on the strength of less.
        val m = map(
            dir,
            test("A", "com.acme.A"),
            loaded = listOf("com.acme.A"),
            loadedBy = "selecting",
        )

        assertTrue(decide(m, changed = listOf("com.acme.NeverLoaded")).isFullRun)
    }

    @Test
    fun `no union recorded means the old answer`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"))

        assertTrue(decide(m, changed = listOf("com.acme.NeverLoaded")).isFullRun)
    }

    @Test
    fun `a class outside the scope the union was recorded under still forces`(@TempDir dir: File) {
        // The union is filtered before it is written, so outside the filter its silence means only
        // that nobody was looking.
        val m = map(
            dir,
            test("A", "com.acme.A"),
            loaded = listOf("com.acme.A"),
            loadedScope = listOf("com.acme"),
        )

        val decision = decide(m, changed = listOf("org.elsewhere.Thing"))

        assertTrue(decision.isFullRun)
        assertContains(decision.fullRunReason(), "org.elsewhere.Thing")
    }

    @Test
    fun `a union with no recorded scope proves nothing`(@TempDir dir: File) {
        // Maps without a recorded scope keep the safe answer.
        val m = map(
            dir,
            test("A", "com.acme.A"),
            loaded = listOf("com.acme.A"),
            loadedScope = null,
        )

        assertTrue(decide(m, changed = listOf("com.acme.NeverLoaded")).isFullRun)
    }

    @Test
    fun `a loaded class is matched by source file, not by exact name`(@TempDir dir: File) {
        // One file compiles to several classes: nested types, and the FileKt class Kotlin puts
        // top-level declarations in. Matching by equality would call a loaded class never-loaded.
        val m = map(dir, test("A", "com.acme.A"), loaded = listOf("com.acme.UtilsKt\u0024doThing\u00241"))

        assertTrue(decide(m, changed = listOf("com.acme.Utils")).isFullRun)
    }

    @Test
    fun `a new test method in a class the map already knows is still run`(@TempDir dir: File) {
        // Matching at class granularity would read a brand new test as known, so it would never run.
        val cls = "[engine:junit-jupiter]/[class:Alpha]"
        val m = map(
            dir,
            Triple("$cls/[method:existing()]", "SUCCESSFUL", "com.acme.A"),
            test("Beta", "com.acme.B"),
        )

        val decision = decide(m, changed = listOf("com.acme.B"))

        assertFalse(decision.isFullRun)
        assertTrue(decision.includes("$cls/[method:brandNew()]"))
    }

    @Test
    fun `a template whose invocations are recorded is not treated as unseen`(@TempDir dir: File) {
        // Capture records one id per iteration while discovery yields the template, and those ids
        // are children of the template's own.
        val template = "[engine:junit-jupiter]/[class:Alpha]/[test-template:t()]"
        val m = map(
            dir,
            Triple("$template/[test-template-invocation:#1]", "SUCCESSFUL", "com.acme.A"),
            test("Beta", "com.acme.B"),
        )

        val decision = decide(m, changed = listOf("com.acme.B"))

        assertFalse(decision.isFullRun)
        assertFalse(decision.includes(template))
    }

    @Test
    fun `a test whose coverage intersects the change is selected`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"), test("B", "com.acme.B"))

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertFalse(decision.isFullRun)
        assertTrue(decision.includes("[engine:junit-jupiter]/[class:A]/[method:t()]"))
        assertFalse(decision.includes("[engine:junit-jupiter]/[class:B]/[method:t()]"))
    }

    @Test
    fun `a kotlin file of top-level functions selects the tests covering its FileKt class`(
        @TempDir dir: File,
    ) {
        // Matching only `Utils` and `Utils$Nested` would select nothing.
        val m = map(dir, test("A", "com.acme.UtilsKt"), test("B", "com.acme.Other"))

        assertTrue(decide(m, changed = listOf("com.acme.Utils")).includes("[engine:junit-jupiter]/[class:A]/[method:t()]"))
    }

    @Test
    fun `a lambda inside a top-level function is still matched`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.UtilsKt\$doThing\$1"))

        assertEquals(1, decide(m, changed = listOf("com.acme.Utils")).selectedCount())
    }

    @Test
    fun `an unrelated class sharing a name prefix is not matched`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.Utils"), test("B", "com.acme.UtilsHelper"))

        val decision = decide(m, changed = listOf("com.acme.Utils"))

        assertEquals(1, decision.selectedCount())
    }

    @Test
    fun `setup coverage selects its own class and not its siblings`(@TempDir dir: File) {
        val alpha = "[engine:junit-jupiter]/[class:Alpha]"
        val m = map(
            dir,
            Triple("$alpha/[method:a()]", "SUCCESSFUL", "com.acme.A"),
            Triple("$alpha/[method:b()]", "SUCCESSFUL", "com.acme.A"),
            Triple("[engine:junit-jupiter]/[class:Beta]/[method:c()]", "SUCCESSFUL", "com.acme.B"),
            Triple("${AgentContract.CLASS_SCOPED_RECORD_PREFIX}$alpha", "NONE", "com.acme.Setup"),
        )

        val decision = decide(m, changed = listOf("com.acme.Setup"))

        assertFalse(decision.isFullRun)
        assertEquals(2, decision.selectedCount())
        assertTrue(decision.includes("$alpha/[method:a()]"))
        assertFalse(decision.includes("[engine:junit-jupiter]/[class:Beta]/[method:c()]"))
    }

    @Test
    fun `a boundary window between two classes selects both`(@TempDir dir: File) {
        val alpha = "[engine:junit-jupiter]/[class:Alpha]"
        val beta = "[engine:junit-jupiter]/[class:Beta]"
        val m = map(
            dir,
            Triple("$alpha/[method:a()]", "SUCCESSFUL", "com.acme.A"),
            Triple("$beta/[method:b()]", "SUCCESSFUL", "com.acme.B"),
            Triple("[engine:junit-jupiter]/[class:Gamma]/[method:c()]", "SUCCESSFUL", "com.acme.C"),
            Triple("${AgentContract.CLASS_SCOPED_RECORD_PREFIX}$alpha|$beta", "NONE", "com.acme.Boundary"),
        )

        assertEquals(2, decide(m, changed = listOf("com.acme.Boundary")).selectedCount())
    }

    @Test
    fun `class-scoped and unattributed records are not counted as tests`(@TempDir dir: File) {
        val m = map(
            dir,
            test("A", "com.acme.A"),
            Triple(AgentContract.UNATTRIBUTED_RECORD_ID, "NONE", "com.acme.Startup"),
            Triple("${AgentContract.CLASS_SCOPED_RECORD_PREFIX}[engine:junit-jupiter]/[class:A]", "NONE", "com.acme.Setup"),
        )

        assertEquals(1, decide(m, changed = listOf("com.acme.A")).knownTests())
    }

    @Test
    fun `a test not known to have passed runs even when its coverage is untouched`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"), test("B", "com.acme.B", outcome = "FAILED"))

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertTrue(decision.includes("[engine:junit-jupiter]/[class:B]/[method:t()]"))
    }

    @Test
    fun `an unrecognised outcome resolves toward running the test`(@TempDir dir: File) {
        // Every other ambiguity in this file resolves that way; an outcome we cannot read must too.
        val m = map(dir, test("A", "com.acme.A"), test("B", "com.acme.B", outcome = "UNKNOWN"))

        assertTrue(decide(m, changed = listOf("com.acme.A")).includes("[engine:junit-jupiter]/[class:B]/[method:t()]"))
    }

    @Test
    fun `a discovered test absent from the map is selected`(@TempDir dir: File) {
        // A newly added test has no record, so no change can ever intersect it.
        val m = map(dir, test("A", "com.acme.A"))
        val newTest = "[engine:junit-jupiter]/[class:Brand]/[method:new()]"

        assertTrue(decide(m, changed = listOf("com.acme.A"), discovered = listOf(newTest)).includes(newTest))
    }

    @Test
    fun `a parameterised invocation is not treated as unmapped when its class is known`(
        @TempDir dir: File,
    ) {
        // Capture records invocations per iteration while discovery yields template ids, so a naive
        // comparison would mark every parameterised test unmapped forever.
        val cls = "[engine:junit-jupiter]/[class:Alpha]"
        val m = map(
            dir,
            Triple("$cls/[test-template:t()]/[test-template-invocation:#1]", "SUCCESSFUL", "com.acme.A"),
            test("Beta", "com.acme.B"),
        )

        // com.acme.B is known to the map, so nothing forces a full run; the template's own coverage
        // is untouched, so the only reason it could be selected is being mistaken for unmapped.
        val decision = decide(m, changed = listOf("com.acme.B"), discovered = listOf("$cls/[test-template:t()]"))

        assertFalse(decision.isFullRun)
        assertFalse(decision.includes("$cls/[test-template:t()]"))
    }

    @Test
    fun `a passing test whose coverage is untouched is not selected`(@TempDir dir: File) {
        // The always-run rules must not quietly become "select everything". Asserted by identity as
        // well as count: a count alone is satisfied by selecting the wrong test.
        val m = map(dir, test("A", "com.acme.A"), test("B", "com.acme.B"))

        val decision = decide(m, changed = listOf("com.acme.A"))

        assertEquals(1, decision.selectedCount())
        assertTrue(decision.includes("[engine:junit-jupiter]/[class:A]/[method:t()]"))
        assertFalse(decision.includes("[engine:junit-jupiter]/[class:B]/[method:t()]"))
    }

    @Test
    fun `no change at all is not a reason to skip`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"), test("B", "com.acme.B", outcome = "FAILED"))

        assertTrue(decide(m).isFullRun)
    }

    @Test
    fun `a full run is distinguishable from having selected every test`(@TempDir dir: File) {
        // They run the same tests for entirely different reasons; conflating them hides staleness.
        val m = map(dir, test("A", "com.acme.A"))

        val forced = decide(m, unmappable = listOf("build.gradle.kts"))
        val selected = decide(m, changed = listOf("com.acme.A"))

        assertTrue(forced.isFullRun)
        assertFalse(selected.isFullRun)
        assertEquals(forced.selectedCount(), selected.selectedCount())
    }

    // Classes whose compiled bytes changed with no source change: a weaver's or post-processor's
    // output, which `git diff` cannot see.

    private fun id(name: String) = "[engine:junit-jupiter]/[class:$name]/[method:t()]"

    @Test
    fun `a class whose bytes changed selects the tests that recorded it and no others`(@TempDir dir: File) {
        val m = map(
            dir, test("A", "com.acme.Woven"), test("B", "com.acme.Other"), test("C", "com.acme.Third"),
        )

        val decision = decide(m, changed = listOf("com.acme.Other"), bytes = listOf("com.acme.Woven"))

        assertFalse(decision.isFullRun, decision.fullRunReason())
        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(id("A")))
        assertTrue(decision.includes(id("B")))
        assertFalse(decision.includes(id("C")), "a class nobody rewrote selected an unrelated test")
    }

    @Test
    fun `a class whose bytes changed and that no test recorded forces a full run`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.Other"))

        val decision = decide(m, changed = listOf("com.acme.Other"), bytes = listOf("com.acme.Ghost"))

        assertTrue(decision.isFullRun)
        assertEquals(Selector.Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED_BYTES, decision.fullRunKind())
        assertContains(decision.fullRunReason(), "com.acme.Ghost")
    }

    @Test
    fun `changed bytes never anchor the nested-class rule`(@TempDir dir: File) {
        // com.acme.Other$Comment certifies com.acme.Comment only if com.acme.Other is in the change
        // set. Rewritten bytes of Other must select Other's tests without becoming that anchor, or
        // an uncovered com.acme.Comment narrows where it has to force.
        val m = map(dir, test("A", "com.acme.Other\$Comment"), test("B", "com.acme.Other"))

        val decision = decide(m, changed = listOf("com.acme.Comment"), bytes = listOf("com.acme.Other"))

        assertTrue(decision.isFullRun, "changed bytes reached the anchor set of the nested-class rule")
        assertEquals(Selector.Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED, decision.fullRunKind())
    }

    @Test
    fun `no changed bytes leaves the decision exactly as it was`(@TempDir dir: File) {
        val m = map(dir, test("A", "com.acme.A"), test("B", "com.acme.B"))
        val before = Selector.decide(m, ChangeSet.of(listOf("com.acme.A"), emptyList()), emptyList())

        val after = decide(m, changed = listOf("com.acme.A"), bytes = emptyList())

        for (name in listOf("A", "B")) {
            assertEquals(before.reasonFor(id(name)), after.reasonFor(id(name)))
        }
        assertEquals(before.selectedCount(), after.selectedCount())
    }

    @Test
    fun `changed bytes a changed source accounts for are left to the change set`(@TempDir dir: File) {
        // A recompiled source moves its own bytes too. Its unexecuted anonymous class is the change
        // set's to judge, and must not force as bytes nobody recorded.
        val m = map(dir, test("A", "com.acme.A"), test("B", "com.acme.B"))

        val decision = decide(m, changed = listOf("com.acme.A"), bytes = listOf("com.acme.A", "com.acme.A\$1"))

        assertFalse(decision.isFullRun, decision.fullRunReason())
        assertEquals(1, decision.selectedCount())
    }

    @Test
    fun `changed bytes in startup coverage force a full run`(@TempDir dir: File) {
        val m = map(
            dir,
            test("A", "com.acme.A"),
            Triple(AgentContract.UNATTRIBUTED_RECORD_ID, "NONE", "com.acme.Startup"),
        )

        val decision = decide(m, changed = listOf("com.acme.A"), bytes = listOf("com.acme.Startup"))

        assertEquals(Selector.Decision.FullRunKind.STARTUP_COVERAGE, decision.fullRunKind())
    }

    @Test
    fun `changed bytes in a class's setup coverage select that class's tests`(@TempDir dir: File) {
        val alpha = "[engine:junit-jupiter]/[class:Alpha]"
        val m = map(
            dir,
            Triple("$alpha/[method:a()]", "SUCCESSFUL", "com.acme.A"),
            test("B", "com.acme.B"),
            Triple("${AgentContract.CLASS_SCOPED_RECORD_PREFIX}$alpha", "NONE", "com.acme.Setup"),
        )

        val decision = decide(m, changed = listOf("com.acme.B"), bytes = listOf("com.acme.Setup"))

        assertTrue(decision.includes("$alpha/[method:a()]"))
    }

    @Test
    fun `changed bytes select on a change set that was accounted for and emptied`(@TempDir dir: File) {
        // Only a path nothing reads changed, so the change set is empty and still means "nothing".
        val m = map(dir, test("A", "com.acme.Woven"), test("B", "com.acme.B"))

        val decision = decide(m, bytes = listOf("com.acme.Woven"), accounted = true)

        assertFalse(decision.isFullRun, decision.fullRunReason())
        assertTrue(decision.includes(id("A")))
        assertFalse(decision.includes(id("B")))
        // And an empty change set nobody accounted for still forces, whatever the bytes say.
        assertEquals(
            Selector.Decision.FullRunKind.EMPTY_CHANGE_SET,
            decide(m, bytes = listOf("com.acme.Woven")).fullRunKind(),
        )
    }

    // Code a JVM runs once is credited to the first test that triggers it, so a later test in the
    // same JVM can depend on a class without recording it.

    private fun placed(name: String) = test(name, "").first

    @Test
    fun `a test that ran after its JVM first loaded a changed class is selected`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                test("com.acme.FirstTest", "com.acme.Rates"),
                test("com.acme.LaterTest", "com.acme.Other"),
                positions = listOf("j\t2\t${placed("com.acme.FirstTest")}", "j\t4\t${placed("com.acme.LaterTest")}"),
                firstTouches = listOf("j\t2\tcom.acme.Rates"),
            ),
            changed = listOf("com.acme.Rates"),
        )

        assertTrue(decision.includes(placed("com.acme.LaterTest")))
        assertEquals(Selector.Decision.Reason.SHARES_JVM_WITH_CHANGE, decision.reasonFor(placed("com.acme.LaterTest")))
        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(placed("com.acme.FirstTest")))
    }

    @Test
    fun `a test that ran before its JVM first touched a changed class stays skipped`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                test("com.acme.EarlierTest", "com.acme.Other"),
                test("com.acme.FirstTest", "com.acme.Rates"),
                positions = listOf("j\t2\t${placed("com.acme.EarlierTest")}", "j\t4\t${placed("com.acme.FirstTest")}"),
                firstTouches = listOf("j\t4\tcom.acme.Rates"),
            ),
            changed = listOf("com.acme.Rates"),
        )

        assertFalse(decision.includes(placed("com.acme.EarlierTest")))
        assertTrue(decision.includes(placed("com.acme.FirstTest")))
    }

    @Test
    fun `a first touch in one JVM selects nothing that ran in another`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                test("com.acme.FirstTest", "com.acme.Rates"),
                test("com.acme.ElsewhereTest", "com.acme.Other"),
                positions = listOf("a\t2\t${placed("com.acme.FirstTest")}", "b\t9\t${placed("com.acme.ElsewhereTest")}"),
                firstTouches = listOf("a\t2\tcom.acme.Rates"),
            ),
            changed = listOf("com.acme.Rates"),
        )

        assertFalse(decision.includes(placed("com.acme.ElsewhereTest")))
    }

    @Test
    fun `a JVM whose touches were not all observed selects every test that ran in it`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                test("com.acme.FirstTest", "com.acme.Rates"),
                test("com.acme.UnrelatedTest", "com.acme.Other"),
                positions = listOf("j\t2\t${placed("com.acme.FirstTest")}", "j\t4\t${placed("com.acme.UnrelatedTest")}"),
                firstTouches = listOf("j\t0\t${AgentContract.FIRST_TOUCH_ANY}"),
            ),
            changed = listOf("com.acme.Rates"),
        )

        assertTrue(decision.includes(placed("com.acme.UnrelatedTest")))
    }

    @Test
    fun `a test the map holds no position for runs whenever something changed`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                test("com.acme.FirstTest", "com.acme.Rates"),
                test("com.acme.UnplacedTest", "com.acme.Other"),
                positions = listOf("j\t2\t${placed("com.acme.FirstTest")}"),
            ),
            changed = listOf("com.acme.Rates"),
        )

        assertTrue(decision.includes(placed("com.acme.UnplacedTest")))
    }

    @Test
    fun `a class whose bytes changed opens the window at its first touch too`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                test("com.acme.FirstTest", "com.acme.Woven"),
                test("com.acme.LaterTest", "com.acme.Other"),
                positions = listOf("j\t2\t${placed("com.acme.FirstTest")}", "j\t4\t${placed("com.acme.LaterTest")}"),
                firstTouches = listOf("j\t2\tcom.acme.Woven"),
            ),
            bytes = listOf("com.acme.Woven"),
            accounted = true,
        )

        assertTrue(decision.includes(placed("com.acme.LaterTest")))
    }

    @Test
    fun `a class some JVM read the class file of is not proven untested by its absence`(@TempDir dir: File) {
        // A class-file reader parses the bytes and loads nothing, so the loaded union lacks it.
        val decision = decide(
            map(
                dir,
                test("com.acme.ReaderTest", "com.acme.ReaderTest"),
                loaded = listOf("com.acme.ReaderTest"),
                positions = listOf("j\t2\t${placed("com.acme.ReaderTest")}"),
                firstTouches = listOf("j\t2\tcom.acme.Codec"),
            ),
            changed = listOf("com.acme.Codec"),
            provable = listOf("com.acme.Codec"),
        )

        assertTrue(decision.isFullRun)
        assertEquals(Selector.Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED, decision.fullRunKind())
    }

    // A test class nothing else names is dated by named touches alone: its discovery-time load,
    // which every test class has, opens no window.

    private val editedTestFirst = listOf("j\t1\tcom.acme.EditedTest")

    @Test
    fun `an exempt test class loaded at discovery opens no window`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                test("com.acme.EditedTest", "com.acme.EditedTest"),
                test("com.acme.LaterTest", "com.acme.LaterTest"),
                positions = listOf("j\t2\t${placed("com.acme.EditedTest")}", "j\t4\t${placed("com.acme.LaterTest")}"),
                firstTouches = editedTestFirst,
            ),
            changed = listOf("com.acme.EditedTest"),
            exempt = listOf("com.acme.EditedTest"),
        )

        assertTrue(decision.includes(placed("com.acme.EditedTest")))
        assertFalse(decision.includes(placed("com.acme.LaterTest")))
    }

    @Test
    fun `an exempt test class opens its window at its first named touch`(@TempDir dir: File) {
        val decision = decide(
            map(
                dir,
                test("com.acme.LookupTest", "com.acme.LookupTest"),
                test("com.acme.EditedTest", "com.acme.EditedTest"),
                positions = listOf("j\t2\t${placed("com.acme.LookupTest")}", "j\t4\t${placed("com.acme.EditedTest")}"),
                firstTouches = editedTestFirst,
                namedTouches = listOf("j\t2\tcom.acme.EditedTest"),
            ),
            changed = listOf("com.acme.EditedTest"),
            exempt = listOf("com.acme.EditedTest"),
        )

        assertTrue(decision.includes(placed("com.acme.LookupTest")))
    }

    @Test
    fun `a class called exempt that the map holds no test of is dated like any class`(@TempDir dir: File) {
        // A helper in the test output: its code may build state later tests consume.
        val decision = decide(
            map(
                dir,
                test("com.acme.FirstTest", "com.acme.Helper"),
                test("com.acme.LaterTest", "com.acme.LaterTest"),
                positions = listOf("j\t2\t${placed("com.acme.FirstTest")}", "j\t4\t${placed("com.acme.LaterTest")}"),
                firstTouches = listOf("j\t2\tcom.acme.Helper"),
            ),
            changed = listOf("com.acme.Helper"),
            exempt = listOf("com.acme.Helper"),
        )

        assertTrue(decision.includes(placed("com.acme.LaterTest")))
    }

    // An edited test class runs its known tests by a rule of its own: JaCoCo may have left the class
    // uninstrumented, and then its tests' coverage holds nothing of it.

    private fun edited(method: String) = "[engine:junit-jupiter]/[class:com.acme.EditedTest]/[method:$method()]"

    /** EditedTest's two tests, LaterTest, then [extra], in one JVM that loaded EditedTest at discovery. */
    private fun editedThenLater(dir: File, editedCovers: String, vararg extra: Triple<String, String, String>) = map(
        dir,
        Triple(edited("a"), "SUCCESSFUL", editedCovers),
        Triple(edited("b"), "SUCCESSFUL", editedCovers),
        test("com.acme.LaterTest", "com.acme.Other"),
        *extra,
        positions = listOf("j\t2\t${edited("a")}", "j\t3\t${edited("b")}", "j\t4\t${placed("com.acme.LaterTest")}") +
            extra.mapIndexed { i, (id, _, _) -> "j\t${5 + i}\t$id" },
        firstTouches = editedTestFirst,
    )

    @Test
    fun `an edited test class with no coverage of itself runs its known tests`(@TempDir dir: File) {
        val decision = decide(
            editedThenLater(dir, "com.acme.Calc"),
            changed = listOf("com.acme.EditedTest"),
            exempt = listOf("com.acme.EditedTest"),
        )

        assertFalse(decision.isFullRun)
        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(edited("a")))
        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(edited("b")))
        assertEquals(Selector.Decision.Reason.SKIPPED, decision.reasonFor(placed("com.acme.LaterTest")))
    }

    @Test
    fun `an edited test class whose tests recorded it selects as through its coverage alone`(@TempDir dir: File) {
        val decision = decide(
            editedThenLater(dir, "com.acme.Calc,com.acme.EditedTest"),
            changed = listOf("com.acme.EditedTest"),
            exempt = listOf("com.acme.EditedTest"),
        )

        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(edited("a")))
        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(edited("b")))
        assertEquals(Selector.Decision.Reason.SKIPPED, decision.reasonFor(placed("com.acme.LaterTest")))
    }

    @Test
    fun `an edited test class another class names runs its known tests whatever its JVM recorded`(
        @TempDir dir: File,
    ) {
        // Not exempt, so dated like any class; here nothing recorded touching it at all.
        val decision = decide(
            map(
                dir,
                Triple(edited("a"), "SUCCESSFUL", "com.acme.Calc"),
                test("com.acme.LaterTest", "com.acme.Other"),
                positions = listOf("j\t2\t${edited("a")}", "j\t4\t${placed("com.acme.LaterTest")}"),
            ),
            changed = listOf("com.acme.EditedTest"),
            own = listOf("com.acme.EditedTest"),
        )

        assertTrue(decision.includes(edited("a")))
        assertFalse(decision.includes(placed("com.acme.LaterTest")))
    }

    @Test
    fun `a test added to an edited test class runs as new beside its known ones`(@TempDir dir: File) {
        val decision = Selector.decide(
            editedThenLater(dir, "com.acme.Calc"),
            ChangeSet.of(listOf("com.acme.EditedTest"), emptyList())
                .withOwnTestClasses(listOf("com.acme.EditedTest"))
                .withExemptTestClasses(listOf("com.acme.EditedTest")),
            listOf(edited("a"), edited("b"), edited("c"), placed("com.acme.LaterTest")),
        )

        assertEquals(Selector.Decision.Reason.NOT_IN_MAP, decision.reasonFor(edited("c")))
        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(edited("a")))
        assertEquals(Selector.Decision.Reason.REACHES_CHANGE, decision.reasonFor(edited("b")))
    }

    @Test
    fun `a nested class's test is a test of the edited outer class`(@TempDir dir: File) {
        val nested = "[engine:junit-jupiter]/[class:com.acme.EditedTest]/[nested-class:Inner]/[method:t()]"
        val decision = decide(
            editedThenLater(dir, "com.acme.Calc", Triple(nested, "SUCCESSFUL", "com.acme.Calc")),
            changed = listOf("com.acme.EditedTest"),
            exempt = listOf("com.acme.EditedTest"),
        )

        assertTrue(decision.includes(nested))
    }

    @Test
    fun `a test id with no class segment is dated by its class's discovery load as before`(@TempDir dir: File) {
        val vintage = "[engine:junit-vintage]/[runner:com.acme.EditedTest]/[test:t(com.acme.EditedTest)]"
        val decision = decide(
            map(
                dir,
                Triple(vintage, "SUCCESSFUL", "com.acme.Calc"),
                positions = listOf("j\t2\t$vintage"),
                firstTouches = editedTestFirst,
            ),
            changed = listOf("com.acme.EditedTest"),
            exempt = listOf("com.acme.EditedTest"),
        )

        assertEquals(Selector.Decision.Reason.SHARES_JVM_WITH_CHANGE, decision.reasonFor(vintage))
    }

    @Test
    fun `an edited test class beside a path the map cannot see still runs everything`(@TempDir dir: File) {
        val decision = decide(
            editedThenLater(dir, "com.acme.Calc"),
            changed = listOf("com.acme.EditedTest"),
            exempt = listOf("com.acme.EditedTest"),
            unmappable = listOf("src/test/resources/fixture.json"),
        )

        assertTrue(decision.isFullRun)
        assertEquals(Selector.Decision.FullRunKind.UNMAPPABLE_PATHS, decision.fullRunKind())
    }
}
