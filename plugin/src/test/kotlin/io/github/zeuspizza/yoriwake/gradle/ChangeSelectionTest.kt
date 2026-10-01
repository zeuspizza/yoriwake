package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.ChangeSet
import io.github.zeuspizza.yoriwake.gradle.bytecode.InstrumentationScope
import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.capture.decideCapture
import io.github.zeuspizza.yoriwake.gradle.capture.validCaptureStamp
import io.github.zeuspizza.yoriwake.gradle.capture.widenToMapAge
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathScope
import io.github.zeuspizza.yoriwake.gradle.change.DigestSilence
import io.github.zeuspizza.yoriwake.gradle.change.DigestRule
import io.github.zeuspizza.yoriwake.gradle.change.Established
import io.github.zeuspizza.yoriwake.gradle.change.INLINE_SCAN_REFUSAL
import io.github.zeuspizza.yoriwake.gradle.change.InlineWidening
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.ScopedChange
import io.github.zeuspizza.yoriwake.gradle.change.accountedFor
import io.github.zeuspizza.yoriwake.gradle.change.annotationRefusal
import io.github.zeuspizza.yoriwake.gradle.change.compareDigests
import io.github.zeuspizza.yoriwake.gradle.change.constantHolderRefusal
import io.github.zeuspizza.yoriwake.gradle.change.digestRule
import io.github.zeuspizza.yoriwake.gradle.change.namedInBuildScripts
import io.github.zeuspizza.yoriwake.gradle.change.widenForInlining
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.classpathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.isClasspathLike
import io.github.zeuspizza.yoriwake.gradle.facts.modulesOnClasspath
import io.github.zeuspizza.yoriwake.gradle.facts.projectFacts
import io.github.zeuspizza.yoriwake.gradle.facts.reachesOutsideModule
import io.github.zeuspizza.yoriwake.gradle.facts.resolvedProjectDependencies
import io.github.zeuspizza.yoriwake.gradle.report.Json
import io.github.zeuspizza.yoriwake.gradle.report.selectionShare
import io.github.zeuspizza.yoriwake.gradle.report.writeExplanation
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The parts of the change-selection support that need no real Gradle build.
class ChangeSelectionTest {

    private fun change(prefixes: Set<String> = emptySet(), unmappable: List<String> = emptyList()) =
        ChangeDetection.Change(prefixes, unmappable)

    private fun scoped(
        prefixes: Set<String> = emptySet(),
        unmappable: List<String> = emptyList(),
        dropped: Int = 0,
    ) = ScopedChange(change(prefixes, unmappable), dropped)

    private fun established(unreadable: Set<String> = emptySet()) =
        Established(emptySet(), emptySet(), unreadable)

    private fun declaring(file: File, packageName: String) {
        file.parentFile.mkdirs()
        file.writeText("package $packageName\nclass Thing\n")
    }

    @Test
    fun `a multiplatform module contributes its own packages, not only its siblings'`(
        @TempDir dir: File,
    ) {
        // A multiplatform module has neither a SourceSetContainer nor `src/main/*`. A scope made
        // only of siblings' packages would write a map for classes it never saw, which is worse
        // than declining.
        val root = ProjectBuilder.builder().withProjectDir(dir).build()
        val core = ProjectBuilder.builder()
            .withName("core").withParent(root).withProjectDir(File(dir, "core")).build()
        ProjectBuilder.builder()
            .withName("integration").withParent(root).withProjectDir(File(dir, "integration")).build()

        declaring(File(dir, "core/src/jvmMain/kotlin/own/jvm/Thing.kt"), "own.jvm")
        declaring(File(dir, "core/src/commonMain/kotlin/own/common/Shared.kt"), "own.common")
        declaring(File(dir, "integration/src/main/kotlin/other/pkg/Sibling.kt"), "other.pkg")

        val scope = InstrumentationScope.derive(projectFacts(core, null).sourceDirs)

        assertContains(scope, "own.jvm", "the JVM target's own packages are missing from $scope")
        // commonMain too: a JVM target compiles both, and a scope holding only one of them
        // describes half of what the task runs.
        assertContains(scope, "own.common", "the shared source set is missing from $scope")
        // The union is build-global, so the sibling stays.
        assertContains(scope, "other.pkg")
    }

    @Test
    fun `a multiplatform module this plugin cannot scope is not attached to`(@TempDir dir: File) {
        // The attach gate must agree with the scope: a module that attaches but contributes none
        // of its own packages writes a map for classes it never saw.
        val root = ProjectBuilder.builder().withProjectDir(dir).build()
        val unscopable = ProjectBuilder.builder()
            .withName("unscopable").withParent(root).withProjectDir(File(dir, "unscopable")).build()
        val intermediate = ProjectBuilder.builder()
            .withName("intermediate").withParent(root)
            .withProjectDir(File(dir, "intermediate")).build()

        File(dir, "unscopable/src/jvmTest/kotlin").mkdirs()
        // The JVM half of the main sources is an intermediate source set.
        File(dir, "intermediate/src/androidAndJvmMain/kotlin").mkdirs()
        File(dir, "intermediate/src/jvmTest/kotlin").mkdirs()

        assertTrue(YoriwakePlugin.hasJvmTarget(unscopable), "it does have JVM tests")
        assertFalse(
            YoriwakePlugin.canAttachMultiplatform(unscopable),
            "a module with no <target>Main source directory contributes none of its own packages, "
                + "so attaching to it would scope it from directories that are not its own",
        )
        assertTrue(
            YoriwakePlugin.canAttachMultiplatform(intermediate),
            "an intermediate source set is still this module's own main sources",
        )
    }

    @Test
    fun `a multiplatform module with no JVM target is not mistaken for one that has`(
        @TempDir dir: File,
    ) {
        // The attach condition. A module with no JVM target has no JVM tests to select, so it must
        // decline by name rather than attach and silently do nothing.
        val root = ProjectBuilder.builder().withProjectDir(dir).build()
        val jvm = ProjectBuilder.builder()
            .withName("jvm").withParent(root).withProjectDir(File(dir, "jvm")).build()
        val native = ProjectBuilder.builder()
            .withName("native").withParent(root).withProjectDir(File(dir, "native")).build()

        File(dir, "jvm/src/jvmMain/kotlin").mkdirs()
        File(dir, "native/src/nativeMain/kotlin").mkdirs()
        File(dir, "native/src/commonMain/kotlin").mkdirs()
        // Main sources in an intermediate source set (`androidAndJvmMain`), so the only directory
        // with the `jvm` prefix is the test one.
        val testsOnly = ProjectBuilder.builder()
            .withName("testsOnly").withParent(root).withProjectDir(File(dir, "testsOnly")).build()
        File(dir, "testsOnly/src/androidAndJvmMain/kotlin").mkdirs()
        File(dir, "testsOnly/src/jvmTest/kotlin").mkdirs()

        assertTrue(YoriwakePlugin.hasJvmTarget(jvm), "a module with src/jvmMain has a JVM target")
        assertTrue(
            YoriwakePlugin.hasJvmTarget(testsOnly),
            "a module whose only jvm-prefixed directory is src/jvmTest still has JVM tests to select",
        )

        assertFalse(
            YoriwakePlugin.hasJvmTarget(native),
            "a module with only native and common sources has no JVM target this plugin can see",
        )
    }

    @Test
    fun `a change set that emptied because everything was explained is accounted for`() {
        // The exception to "empty forces". The files really did change and every one of them was
        // explained -- off this task's classpath, or unreadable by anything in it.
        val accounted = accountedFor(
            paths = listOf(".github/workflows/ci.yml"),
            scoped = scoped(unmappable = listOf(".github/workflows/ci.yml")),
            established = established(unreadable = setOf(".github/workflows/ci.yml")),
        )

        assertTrue(accounted)
    }

    @Test
    fun `git having nothing to say is not the same as everything being explained`() {
        // The default base is HEAD, so committing and then testing yields an empty diff, which
        // must not read as "nothing needs to run".
        assertFalse(accountedFor(paths = emptyList(), scoped = scoped(), established = established()))
    }

    @Test
    fun `one unexplained path is enough to keep the change set unaccounted for`() {
        val accounted = accountedFor(
            paths = listOf(".github/workflows/ci.yml", "build.gradle.kts"),
            scoped = scoped(unmappable = listOf(".github/workflows/ci.yml", "build.gradle.kts")),
            established = established(unreadable = setOf(".github/workflows/ci.yml")),
        )

        assertFalse(accounted)
    }

    @Test
    fun `a changed class left in the set means it was not fully explained`() {
        val accounted = accountedFor(
            paths = listOf("core/src/main/java/com/acme/Thing.java"),
            scoped = scoped(prefixes = setOf("com.acme.Thing")),
            established = established(),
        )

        assertFalse(accounted)
    }

    @Test
    fun `a path a build script names is reported, and one it does not is not`(@TempDir dir: File) {
        // An openapi.yaml or a .proto changes what compiles while no compiled class ever names it,
        // so the build scripts are the only place that dependency is visible.
        File(dir, "build.gradle.kts").writeText(
            """
            tasks.register("generate") {
                inputs.file("api/openapi.yaml")
            }
            """.trimIndent()
        )

        val named = namedInBuildScripts(dir, listOf("api/openapi.yaml", "docs/unrelated.md"))

        assertEquals(setOf("api/openapi.yaml"), named)
    }

    @Test
    fun `a script in a subdirectory is read too`(@TempDir dir: File) {
        File(dir, "service").mkdirs()
        File(dir, "service/build.gradle").writeText("// reads schema/events.proto")

        assertEquals(setOf("schema/events.proto"), namedInBuildScripts(dir, listOf("schema/events.proto")))
    }

    @Test
    fun `asking about no paths reads no scripts`(@TempDir dir: File) {
        File(dir, "build.gradle.kts").writeText("// anything")

        assertTrue(namedInBuildScripts(dir, emptyList()).isEmpty())
    }

    @Test
    fun `a version catalog counts as a build script`(@TempDir dir: File) {
        File(dir, "gradle").mkdirs()
        File(dir, "gradle/libs.versions.toml").writeText("""schema = { file = "schema/events.proto" }""")

        assertEquals(setOf("schema/events.proto"), namedInBuildScripts(dir, listOf("schema/events.proto")))
    }

    @Test
    fun `a build script naming only a directory protects the files inside it`(@TempDir dir: File) {
        // `inputs.dir("testdata")` or a system property names the directory, never the changed
        // file, and no class names it either; unnamed, golden-file tests would be skipped.
        File(dir, "build.gradle.kts").writeText("""tasks.test { inputs.dir("testdata") }""")

        assertEquals(setOf("testdata/case1.json"), namedInBuildScripts(dir, listOf("testdata/case1.json")))
    }

    @Test
    fun `deep module scripts and convention plugins are read`(@TempDir dir: File) {
        File(dir, "a/b/c").mkdirs()
        File(dir, "a/b/c/build.gradle.kts").writeText("// reads schema/deep.proto")
        File(dir, "build-logic/src/main/kotlin").mkdirs()
        File(dir, "build-logic/settings.gradle.kts").writeText("")
        File(dir, "build-logic/src/main/kotlin/Codegen.kt").writeText("""val spec = "api/openapi.yaml"""")
        File(dir, "buildSrc/src/main/kotlin").mkdirs()
        File(dir, "buildSrc/src/main/kotlin/conventions.gradle.kts").writeText("// reads fixtures/golden.txt")

        assertEquals(
            setOf("schema/deep.proto", "api/openapi.yaml", "fixtures/golden.txt"),
            namedInBuildScripts(dir, listOf("schema/deep.proto", "api/openapi.yaml", "fixtures/golden.txt")),
        )
    }

    @Test
    fun `an ordinary module's sources are not read as build logic`(@TempDir dir: File) {
        File(dir, "app/src/main/kotlin").mkdirs()
        File(dir, "app/src/main/kotlin/App.kt").writeText("""val doc = "docs/unrelated.md"""")

        assertTrue(namedInBuildScripts(dir, listOf("docs/unrelated.md")).isEmpty())
    }

    private val base = ChangeDetection.Base("base0000", "an explicit base")

    @Test
    fun `a map with no stamp refuses, and no map at all keeps the base`() {
        // A stampless map has an unknown age: selecting against the unwidened base misses every
        // change between the capture and the base. With no map the run forces anyway.
        val refused = widenToMapAge(base, null, mapPresent = true) { _, _ -> error("git was asked") }
        assertEquals(RefusalKind.STAMP_ABSENT, (refused as MapAge.Unknown).kind)
        assertEquals(MapAge.Known(base), widenToMapAge(base, null, mapPresent = false) { _, _ -> null })
    }

    @Test
    fun `a stamp is widened to, refused when unrelatable, and left alone when it is the base`() {
        val widened = widenToMapAge(base, "abc1234", mapPresent = true) { _, _ -> "anc0000" }
        assertEquals("anc0000", (widened as MapAge.Known).base.ref)
        val unrelatable = widenToMapAge(base, "abc1234", mapPresent = true) { _, _ -> null }
        assertEquals(RefusalKind.STAMP_UNRELATABLE, (unrelatable as MapAge.Unknown).kind)
        assertEquals(MapAge.Known(base, "abc1234"), widenToMapAge(base, "abc1234", mapPresent = true) { _, _ -> base.ref })
    }

    @Test
    fun `a quote or a backslash in a reason does not produce broken JSON`() {
        // The reason is free prose; hand-rolled JSON (the plugin adds no dependency to a foreign
        // build) is only safe if it escapes.
        assertEquals("""a \"quoted\" name""", Json.escape("""a "quoted" name"""))
        assertEquals("""C:\\path""", Json.escape("""C:\path"""))
    }

    @Test
    fun `the decision is written where something other than a person can read it`(@TempDir dir: File) {
        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(listOf("com.acme.Thing"), emptyList()),
            emptyList(),
        )

        writeExplanation(dir, ":core:test", "HEAD~1", scoped(prefixes = setOf("com.acme.Thing")), established(), decision)

        val json = File(dir, YoriwakePlugin.EXPLANATION_FILE).readText()
        assertContains(json, """"version": 1""")
        assertContains(json, """"task": ":core:test"""")
        assertContains(json, """"base": "HEAD~1"""")
        assertContains(json, """"fullRun": true""")
        assertContains(json, """"changedClasses": 1""")
    }

    private fun bytesOf(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")) {
            "no class file for $name"
        }.use { it.readBytes() }

    @Test
    fun `a constant whose recorded value is unchanged does not force`() {
        // The rule asks whether a constant changed, not whether one exists; the broader question
        // forces on method changes that touch no constant.
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasConstant"
        val recorded = mapOf(name to Recordability.constantDigest(bytesOf(name))!!)

        val refusal = constantHolderRefusal(setOf(name), recorded) { prefix ->
            listOf(prefix to bytesOf(prefix))
        }

        assertNull(refusal, "the recorded value is identical, so nothing was copied anywhere new")
    }

    @Test
    fun `a constant whose recorded value differs still forces`() {
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasConstant"

        val refusal = constantHolderRefusal(setOf(name), mapOf(name to "LIMIT=999")) { prefix ->
            listOf(prefix to bytesOf(prefix))
        }

        assertNotNull(refusal)
        assertTrue(refusal.forces)
        assertContains(refusal.refusal!!, "differs from the one the map was captured under")
    }

    @Test
    fun `a constant the map never recorded forces, because absence is not proof`() {
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasConstant"

        val refusal = constantHolderRefusal(setOf(name), mapOf("some.other.Class" to "X=1")) { prefix ->
            listOf(prefix to bytesOf(prefix))
        }

        assertNotNull(refusal)
        assertContains(refusal.refusal!!, "the map did not record")
    }

    @Test
    fun `a changed class that declares a compile-time constant refuses to narrow`() {
        val refusal = constantHolderRefusal(setOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasConstant")) { prefix ->
            listOf(prefix to bytesOf(prefix))
        }

        assertNotNull(refusal)
        assertTrue(refusal.forces)
        assertFalse(refusal.scanExhausted, "a constant holder is not an exhausted scan")
        assertContains(refusal.refusal!!, "declares a compile-time constant")
    }

    @Test
    fun `a static final field computed at runtime is not a compile-time constant`() {
        // The exact question is the ConstantValue attribute, not the modifiers. A field initialised
        // in <clinit> is read from the holder at runtime, so coverage does record the edge.
        val refusal = constantHolderRefusal(setOf("io.github.zeuspizza.yoriwake.gradle.fixtures.HasComputedField")) { prefix ->
            listOf(prefix to bytesOf(prefix))
        }

        assertNull(refusal)
    }

    @Test
    fun `a changed class whose bytecode could not be found refuses rather than narrowing`() {
        // classesFor answers null when it could not look; collapsing that to false would narrow on
        // bytecode nobody had read.
        val refusal = constantHolderRefusal(setOf("com.acme.Thing")) { null }

        assertNotNull(refusal)
        assertTrue(refusal.forces)
        assertTrue(refusal.scanExhausted)
        assertEquals(INLINE_SCAN_REFUSAL, refusal.refusal)
    }

    @Test
    fun `a lookup that throws refuses rather than narrowing`() {
        // A throw must not read as "nothing declares a constant".
        val refusal = constantHolderRefusal(setOf("com.acme.Thing")) { error("disk gone") }

        assertNotNull(refusal)
        assertTrue(refusal.forces)
        assertEquals(INLINE_SCAN_REFUSAL, refusal.refusal)
    }

    private fun stamp(dir: File, value: String) =
        File(dir, CoverageDecoder.CAPTURE_COMMIT_FILE).also { it.parentFile.mkdirs() }.writeText(value)

    @Test
    fun `a full run whose map would learn nothing does not instrument`(@TempDir dir: File) {
        stamp(dir, "0123456789abcdef")

        val decision = decideCapture(dir, mapUsable = true, fullRun = true, learnable = emptySet())

        assertFalse(decision.capture)
        assertTrue(decision.mapCurrent)
    }

    @Test
    fun `a full run with something to learn instruments`(@TempDir dir: File) {
        stamp(dir, "0123456789abcdef")

        val decision = decideCapture(dir, mapUsable = true, fullRun = true, learnable = setOf("com.acme.Thing"))

        assertTrue(decision.capture)
        assertFalse(decision.mapCurrent)
    }

    @Test
    fun `a narrowed run never instruments, whatever the map knows`(@TempDir dir: File) {
        stamp(dir, "0123456789abcdef")

        assertFalse(decideCapture(dir, true, fullRun = false, learnable = setOf("com.acme.Thing")).capture)
        assertFalse(decideCapture(dir, true, fullRun = false, learnable = emptySet()).capture)
    }

    @Test
    fun `a stamp that is not a commit hash is not an age, so the map is not current`(@TempDir dir: File) {
        // widenBaseToMapAge does not widen on a stamp that is not a sha, so the capture decision
        // must not call such a map current, or it is never refreshed. In CI the stamp comes from a
        // restored cache.
        stamp(dir, "not-a-sha\n")

        val decision = decideCapture(dir, mapUsable = true, fullRun = true, learnable = emptySet())

        assertFalse(decision.mapCurrent, "a corrupt stamp is an unknown age, never a current map")
        assertTrue(decision.capture)
    }

    @Test
    fun `both readers agree on which stamps are ages`() {
        assertEquals("0123456", validCaptureStamp(" 0123456\n"))
        assertNull(validCaptureStamp("not-a-sha"))
        assertNull(validCaptureStamp(""))
        assertNull(validCaptureStamp(null))
    }

    @Test
    fun `an unusable map with a leftover stamp is not a current map`(@TempDir dir: File) {
        // A map MapReader refuses still has its capture-commit file beside it; read as current, it
        // would never be rebuilt.
        stamp(dir, "0123456789abcdef")

        val decision = decideCapture(dir, mapUsable = false, fullRun = true, learnable = emptySet())

        assertFalse(decision.mapCurrent)
        assertTrue(decision.capture, "an unreadable map must be rebuilt by the next full run")
    }

    /** A map of two JVMs, one per test, recorded in the given modes. */
    private fun recordedIn(dir: File, vararg modes: String) {
        stamp(dir, "0123456789abcdef")
        File(dir, AgentContract.POSITIONS_FILE).writeText(
            modes.indices.joinToString("") { "run/worker-$it\t1\t[engine:junit-jupiter]/[class:T$it]/[method:m()]\n" }
        )
        File(dir, AgentContract.JVM_MODE_FILE).writeText(
            modes.withIndex().joinToString("") { (i, mode) -> "run/worker-$i\t$mode\n" }
        )
    }

    @Test
    fun `a full run over a map recorded in isolation, wholly or in part, does not instrument`(@TempDir dir: File) {
        for (modes in listOf(arrayOf("isolated", "isolated"), arrayOf("isolated", "shared"))) {
            recordedIn(dir, *modes)

            val decision = decideCapture(dir, mapUsable = true, fullRun = true, learnable = setOf("com.acme.Thing"))

            assertFalse(decision.capture, "a fallback would overwrite a map recorded ${modes.toList()}")
            assertFalse(decision.mapCurrent)
            assertContains(decision.reason, "leaves it alone and nothing is instrumented")
        }
    }

    @Test
    fun `a full run over a shared map, or an unusable one labelled isolated, still instruments`(@TempDir dir: File) {
        recordedIn(dir, "shared", "shared")
        assertTrue(decideCapture(dir, mapUsable = true, fullRun = true, learnable = setOf("com.acme.Thing")).capture)

        recordedIn(dir, "isolated", "isolated")
        assertTrue(
            decideCapture(dir, mapUsable = false, fullRun = true, learnable = setOf("com.acme.Thing")).capture,
            "a map selection cannot read holds nothing isolation could keep, so a full run rebuilds it",
        )
    }

    @Test
    fun `the capture decision is written where yoriwakeExplain's readers can see it`(@TempDir dir: File) {
        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(listOf("com.acme.Thing"), emptyList()),
            emptyList(),
        )

        writeExplanation(
            dir, ":core:test", "HEAD~1", scoped(prefixes = setOf("com.acme.Thing")), established(),
            decision, InlineWidening.widened(emptyList(), emptySet()),
            decideCapture(dir, mapUsable = false, fullRun = true, learnable = setOf("com.acme.Thing")),
        )

        val json = File(dir, YoriwakePlugin.EXPLANATION_FILE).readText()
        assertContains(json, """"capture": true""")
        assertContains(json, """"mapCurrent": false""")
        assertContains(json, """"captureReason": "running everything, so this run also captures""")
    }

    private val nested = mapOf("" to ":", "a" to ":a", "a/b" to ":a:b", "c" to ":c")

    @Test
    fun `a change in a nested module is outside its parent, so the parent resolves`() {
        // `:a:b` sits inside `:a`, so a raw path-prefix test would read its file as belonging to
        // `:a`, skip resolution and drop `:a:b`'s change as off-classpath.
        assertTrue(
            reachesOutsideModule("a", ":a", listOf("a/b/src/main/java/X.java"), nested),
            "a change in the nested module :a:b is not a change inside :a",
        )
    }

    @Test
    fun `a change in the module's own directory does not make it resolve`() {
        // Resolving is the most expensive step of selection, and a change entirely inside this
        // module cannot need it.
        assertFalse(reachesOutsideModule("a", ":a", listOf("a/src/main/java/X.java"), nested))
    }

    @Test
    fun `the nested module itself owns its own files`() {
        assertFalse(reachesOutsideModule("a/b", ":a:b", listOf("a/b/src/main/java/X.java"), nested))
    }

    @Test
    fun `a sibling module is outside`() {
        assertTrue(reachesOutsideModule("a", ":a", listOf("c/src/main/java/X.java"), nested))
    }

    @Test
    fun `a path no module owns is not inside this module either`() {
        // A file at the repository root belongs to no module's directory. Unknown resolves to the
        // expensive, complete answer, as everything unknown does here.
        assertTrue(reachesOutsideModule("a", ":a", listOf("README.md"), nested))
    }

    @Test
    fun `a directory whose name merely starts with this module's is not inside it`() {
        // `a-extras/` is not `a/`.
        assertTrue(reachesOutsideModule("a", ":a", listOf("a-extras/src/main/java/X.java"),
            nested + mapOf("a-extras" to ":a-extras")))
    }

    @Test
    fun `a root project whose directory is the repository root always resolves`() {
        assertTrue(reachesOutsideModule("", ":", listOf("a/src/main/java/X.java"), nested))
    }

    @Test
    fun `a Windows-separated path is attributed to the same module`() {
        assertFalse(reachesOutsideModule("a", ":a", listOf("a\\src\\main\\java\\X.java"), nested))
    }

    @Test
    fun `nothing changed means nothing outside, so nothing is resolved`() {
        assertFalse(reachesOutsideModule("a", ":a", emptyList(), nested))
    }

    @Test
    fun `explain names which refusal it was, so nobody has to match English for it`(@TempDir dir: File) {
        // `constant-unrecorded` is fixed by recapturing and `constant-changed` never is, so the
        // refusals carry distinct tokens rather than prose.
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasConstant"
        val unrecorded = constantHolderRefusal(setOf(name), emptyMap()) { p -> listOf(p to bytesOf(p)) }!!
        val changed = constantHolderRefusal(setOf(name), mapOf(name to "not-the-digest")) { p ->
            listOf(p to bytesOf(p))
        }!!

        assertEquals(RefusalKind.CONSTANT_UNRECORDED, unrecorded.refusalKind)
        assertEquals(RefusalKind.CONSTANT_CHANGED, changed.refusalKind)

        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(listOf(name), emptyList()),
            emptyList(),
        )
        writeExplanation(dir, ":core:test", "HEAD~1", scoped(prefixes = setOf(name)), established(),
            decision, changed)

        val json = File(dir, YoriwakePlugin.EXPLANATION_FILE).readText()
        assertContains(json, """"refusalKind": "constant-changed"""")
        assertTrue(
            json.contains(""""refusalDetail": "$name""""),
            "the per-branch detail belongs in its own field, not concatenated into the sentence: $json",
        )
    }

    @Test
    fun `a selection share never carries a decimal comma`() {
        // The share must print with a dot whatever the default locale (Italian prints "3,7%").
        // Tested here because `-Duser.language` does not reach the Gradle daemon.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ITALY)
            assertEquals("(3.7%)", selectionShare(29, 792))
            assertEquals("(100.0%)", selectionShare(1, 1))
            // A map with no known tests is 0%, not a division by zero.
            assertEquals("(0.0%)", selectionShare(0, 0))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `a private constant does not force the whole suite`() {
        // javac copies a constant only into code that can reference it, so a private one (such as
        // serialVersionUID) has no stale consumer elsewhere.
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasPrivateConstant"

        // Both the branches that force on a public holder: an unrecorded digest, and one that moved.
        assertNull(
            constantHolderRefusal(setOf(name), emptyMap()) { p -> listOf(p to bytesOf(p)) },
            "an unrecorded PRIVATE constant still cannot reach a consumer",
        )
        assertNull(
            constantHolderRefusal(setOf(name), mapOf(name to "not-the-digest")) { p ->
                listOf(p to bytesOf(p))
            },
            "a private constant that genuinely moved still cannot reach a consumer",
        )
    }

    // A private constant can still escape through a field write; two shapes remain known limits.

    @Test
    fun `a private constant cached into a field forces, which closes the measured miss`() {
        // The value leaves the class inside a cached object, so no consumer names the holder and
        // no coverage edge reaches it.
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasCachedPrivateConstant"

        val changed = constantHolderRefusal(setOf(name), mapOf(name to "not-the-digest")) { p ->
            listOf(p to bytesOf(p))
        }
        assertNotNull(changed, "a private constant whose value moved, and did escape, must force")
        assertEquals(RefusalKind.CONSTANT_CHANGED, changed.refusalKind)
        assertEquals(name, changed.refusalDetail)

        // The visibility check must not suppress the unrecorded-digest branch.
        val unrecorded = constantHolderRefusal(setOf(name), emptyMap()) { p -> listOf(p to bytesOf(p)) }
        assertNotNull(unrecorded)
        assertEquals(RefusalKind.CONSTANT_UNRECORDED, unrecorded.refusalKind)
    }

    @Test
    fun `a private constant that escaped but did not move still does not force`() {
        // An exact digest match is still the only thing that licenses skipping.
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasCachedPrivateConstant"
        val recorded = mapOf(name to Recordability.constantDigest(bytesOf(name))!!)

        assertNull(
            constantHolderRefusal(setOf(name), recorded) { p -> listOf(p to bytesOf(p)) },
            "the recorded value is identical, so nothing was copied anywhere new",
        )
    }

    @Test
    fun `a private constant that escapes only by return does not force -- a KNOWN LIMIT`() {
        // A known limit: the value leaves through a method return and writes no field, so no rule
        // keyed on a field write can see it.
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasReturnedPrivateConstant"

        assertNull(
            constantHolderRefusal(setOf(name), mapOf(name to "not-the-digest")) { p ->
                listOf(p to bytesOf(p))
            },
        )
    }

    @Test
    fun `serialVersionUID beside a same-valued field does not force`() {
        // A value-matching rule cannot tell `counter = 1L` from the serial's own `1L`, so this
        // forces the moment the carve-out by name is missing.
        val name = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasSerialAndSameValueField"

        assertNull(
            constantHolderRefusal(setOf(name), mapOf(name to "not-the-digest")) { p ->
                listOf(p to bytesOf(p))
            },
            "serialVersionUID is read reflectively; no compile-time copy and no field write carries it out",
        )
    }

    @Test
    fun `one non-private constant still forces, whatever else the class declares`() {
        // Per class, not per constant: the digest covers the whole class and cannot tell which
        // constant moved.
        val mixed = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasMixedConstants"

        val refusal = constantHolderRefusal(setOf(mixed), mapOf(mixed to "not-the-digest")) { p ->
            listOf(p to bytesOf(p))
        }
        assertNotNull(refusal, "a class with a public constant must still force")
        assertEquals(RefusalKind.CONSTANT_CHANGED, refusal.refusalKind)
    }

    @Test
    fun `a class the scan could not read still forces, private or not`() {
        // `declaresEscapableConstant` answers false on unparseable bytecode (unsafe), but such a
        // class fails `classesOf` or `declaresConstant` first and forces there.
        assertNotNull(
            constantHolderRefusal(setOf("com.acme.Unreadable"), emptyMap()) { null },
            "a class that could not be established must force",
        )
    }

    @Test
    fun `a scan that could not finish is a different kind from a constant that moved`() {
        val refusal = constantHolderRefusal(setOf("com.acme.Gone"), emptyMap()) { null }!!

        assertEquals(RefusalKind.SCAN_REFUSED, refusal.refusalKind)
        assertTrue(refusal.scanExhausted)
    }

    @Test
    fun `a build with no SMAP signal is not reported as a spent budget`(@TempDir dir: File) {
        // Kotlin output with no SMAP anywhere is not a spent budget; no number can change it.
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val output = File(dir, "core/build/classes").also { it.mkdirs() }
        File(output, "io/github/zeuspizza/yoriwake/gradle/fixtures").mkdirs()
        File(output, "io/github/zeuspizza/yoriwake/gradle/fixtures/InlinesNothing.class").writeBytes(
            checkNotNull(
                javaClass.classLoader
                    .getResourceAsStream("io/github/zeuspizza/yoriwake/gradle/fixtures/InlinesNothing.class")
            ).use { it.readBytes() }
        )

        val widening = widenForInlining(
            listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"),
            ClasspathFacts(
                rootDir = dir,
                moduleDirs = mapOf("core" to ":core"),
                buildDirs = mapOf(":core" to File(dir, "core/build").absolutePath),
                declared = project.provider { setOf(":core") },
                compileTime = project.files(),
                ownPath = ":core",
                testOutputs = project.files(output),
                classpath = project.files(output),
            ),
            recordedAnnotations = emptyMap(),
        )

        assertTrue(widening.forces)
        assertEquals(RefusalKind.SMAP_ABSENT, widening.refusalKind)
        assertNotNull(widening.refusalDetail, "the refusal that forces forever must name its cause")

        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes"), emptyList()),
            emptyList(),
        )
        writeExplanation(dir, ":core:test", "HEAD~1",
            scoped(prefixes = setOf("io.github.zeuspizza.yoriwake.gradle.fixtures.KotlinShapes")), established(),
            decision, widening)

        val json = File(dir, YoriwakePlugin.EXPLANATION_FILE).readText()
        assertContains(json, """"refusalKind": "smap-absent"""")
        assertFalse(
            json.contains(""""refusalDetail": null"""),
            "the one refusal that forces on every build of a project reported no detail at all: $json",
        )
    }

    @Test
    fun `explain names which kind of full run this was`(@TempDir dir: File) {
        // `reason` is prose; readers that need to know why a run forced match the token instead.
        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(listOf("com.acme.Thing"), emptyList()),
            emptyList(),
        )

        writeExplanation(dir, ":core:test", "HEAD~1", scoped(prefixes = setOf("com.acme.Thing")),
            established(), decision)

        val json = File(dir, YoriwakePlugin.EXPLANATION_FILE).readText()
        assertContains(json, """"fullRunKind": "map-unusable"""")
    }

    @Test
    fun `a run that did not refuse names no kind at all`(@TempDir dir: File) {
        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(listOf("com.acme.Thing"), emptyList()),
            emptyList(),
        )

        writeExplanation(dir, ":core:test", "HEAD~1", scoped(prefixes = setOf("com.acme.Thing")),
            established(), decision)

        val json = File(dir, YoriwakePlugin.EXPLANATION_FILE).readText()
        assertContains(json, """"refusalKind": null""")
        assertContains(json, """"refusalDetail": null""")
    }

    @Test
    fun `variant-aware classpath names are recognised, not just the six plain ones`() {
        // Android Gradle Plugin's classpaths are per-variant, so a fixed list of names matches none.
        listOf(
            "runtimeClasspath", "testRuntimeClasspath", "compileClasspath", "testCompileClasspath",
            "debugRuntimeClasspath", "releaseCompileClasspath", "debugUnitTestRuntimeClasspath",
            "freeStagingDebugRuntimeClasspath", "annotationProcessor", "kaptAnnotationProcessor",
        ).forEach { name ->
            assertTrue(isClasspathLike(name), "$name should be read as a classpath")
        }
        // And still narrow: resolution is not free, and these name nothing about dependencies.
        listOf("archives", "default", "implementation", "api", "coreLibraryDesugaring")
            .forEach { name ->
                assertFalse(isClasspathLike(name), "$name should not be resolved")
            }
    }

    @Test
    fun `a classpath question that could not be asked drops nothing`() {
        // Unknown must not collapse to empty, which reads as "depends on nothing" and drops every
        // sibling's change as off-classpath.
        val project = ProjectBuilder.builder().build()
        val library = File(project.projectDir, "library/build")
        library.mkdirs()
        val onClasspath = File(project.projectDir, "cache/transformed/lib.jar")
        onClasspath.parentFile.mkdirs()
        onClasspath.writeText("")

        fun facts(declared: Set<String>?) = ClasspathFacts(
            rootDir = project.projectDir,
            moduleDirs = mapOf("library" to ":library"),
            buildDirs = mapOf(":library" to library.absolutePath),
            declared = project.provider { declared },
            compileTime = project.files(),
            ownPath = ":app",
            testOutputs = project.files(),
            classpath = project.files(onClasspath),
        )

        // Unknown -> empty -> restrict keeps everything.
        assertEquals(emptySet(), modulesOnClasspath(facts(null)))
        // A source-reading library on the classpath reads siblings by path: drop nothing.
        val konsist = File(project.projectDir, "cache/konsist-0.17.3.jar").apply { writeText("") }
        assertEquals(
            emptySet(),
            modulesOnClasspath(facts(emptySet()).copy(classpath = project.files(onClasspath, konsist))),
        )
        // Answered "none" -> the module itself is still present.
        assertContains(modulesOnClasspath(facts(emptySet())), ":app")

        val restriction = ClasspathScope.restrict(
            listOf("library/src/main/kotlin/com/acme/Lib.kt"),
            mapOf("library" to ":library"),
            emptySet(),
        ) { emptySet() }
        assertEquals(listOf("library/src/main/kotlin/com/acme/Lib.kt"), restriction.kept)
        assertEquals(emptyList(), restriction.dropped)
    }

    @Test
    fun `the init script reads the plugin's host-plugin list rather than copying it`() {
        // The host-plugin list has one home; the init script must read it, not copy it.
        val script = File("../scripts/yoriwake.init.gradle.kts").let {
            if (it.isFile) it else File("scripts/yoriwake.init.gradle.kts")
        }
        assertTrue(script.isFile, "init script not found at ${script.absolutePath}")
        val text = script.readText()
        assertContains(text, "YoriwakePlugin.HOST_PLUGINS")
        assertFalse(
            "\"com.android.library\"" in text,
            "the init script has a literal copy of the host-plugin list again",
        )
        // And Android is genuinely covered, so a later edit cannot quietly drop it.
        assertContains(YoriwakePlugin.HOST_PLUGINS, "com.android.application")
        assertContains(YoriwakePlugin.HOST_PLUGINS, "com.android.library")
    }

    @Test
    fun `a project dependency declared only through ksp or kapt is still seen`() {
        // kapt and KSP configurations end in neither RuntimeClasspath nor CompileClasspath and run
        // in no JavaCompile, so a `ksp(project(":codegen"))` dependency must be found another way.
        val root = ProjectBuilder.builder().build()
        val codegen = ProjectBuilder.builder().withName("codegen").withParent(root).build()
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        val ksp = app.configurations.create("ksp")
        ksp.dependencies.add(app.dependencies.project(mapOf("path" to codegen.path)))

        val declared = resolvedProjectDependencies(app)

        assertNotNull(declared, "a declared project dependency must never read as unknown")
        assertContains(declared, codegen.path)
    }

    @Test
    fun `these configuration names are read as classpaths`() {
        listOf("kapt", "kaptDebug", "ksp", "kspDebugKotlin").forEach {
            assertTrue(isClasspathLike(it), "$it should be read as a classpath")
        }
    }

    @Test
    fun `an unknown change set resolves the classpath, an empty one does not`(@TempDir tmp: java.io.File) {
        // Empty asserts "nothing changed outside this module" and can drop a sibling's change;
        // null means unknown and must buy the complete answer.
        val rootDir = java.io.File(tmp, "root").apply { mkdirs() }
        val root = ProjectBuilder.builder().withProjectDir(rootDir).build()
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        val codegen = ProjectBuilder.builder().withName("codegen").withParent(root).build()
        // The real plugin, so the `Test` task has the source sets and output directories
        // `classpathFacts` reads. A bare registered task has none and throws on the first of them.
        app.plugins.apply("java")
        app.dependencies.add("implementation", app.dependencies.project(mapOf("path" to codegen.path)))
        val test = app.tasks.named("test", org.gradle.api.tasks.testing.Test::class.java).get()

        val unknown = classpathFacts(app, test, changedPaths = null).declared.get()
        val empty = classpathFacts(app, test, changedPaths = emptyList()).declared.get()

        assertContains(
            assertNotNull(unknown), codegen.path,
            "an unknown change set did not resolve the classpath, so a sibling's changes can be dropped",
        )
        assertEquals(
            emptySet(), empty,
            "an empty change set paid for a resolution it asserted it did not need",
        )
    }

    @Test
    fun `one walk answers for every project in the build, and only for that build`(@TempDir tmp: java.io.File) {
        // Every module must reach the same answer whatever the evaluation order, and an included
        // build must never get the root build's module map.
        val rootDir = java.io.File(tmp, "root").apply { mkdirs() }
        val root = ProjectBuilder.builder().withProjectDir(rootDir).build()
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        val lib = ProjectBuilder.builder().withName("lib").withParent(root).build()
        java.io.File(app.projectDir, "src/main/java").mkdirs()
        java.io.File(lib.projectDir, "src/main/kotlin").mkdirs()
        val memo = BuildMemo.of(root)

        val fromApp = projectFacts(app, memo)
        val fromLib = projectFacts(lib, memo)

        assertSame(fromApp, fromLib, "two modules of one build derived the build's facts twice")
        assertEquals(setOf("", "app", "lib"), fromApp.moduleDirs.keys)
        assertEquals(setOf(":", ":app", ":lib"), fromApp.moduleDirs.values.toSet())
        // No SourceSetContainer, as on an Android module, so the conventional directories are the
        // whole answer.
        assertTrue(
            fromApp.sourceDirs.any { it.endsWith(java.io.File("app", "src/main/java")) },
            "the conventional source directory fallback did not survive being hoisted",
        )
        assertTrue(fromApp.sourceDirs.any { it.endsWith(java.io.File("lib", "src/main/kotlin")) })

        val otherDir = java.io.File(tmp, "other").apply { mkdirs() }
        val other = ProjectBuilder.builder().withProjectDir(otherDir).build()
        assertTrue(
            projectFacts(other, BuildMemo.of(other)) !== fromApp,
            "a second build was handed the first build's module map",
        )
    }

    // The digest rule only widens: with the flag off nothing changes, and with it on nothing
    // forces a build that would not have forced.

    /** A one-module build whose output holds these classes, and a map directory beside it. */
    private fun oneModule(dir: File, vararg classes: Pair<String, ByteArray>): ClasspathFacts {
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val output = File(dir, "core/build/classes").also { it.mkdirs() }
        classes.forEach { (name, bytes) ->
            val file = File(output, name.replace('.', '/') + ".class")
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        return ClasspathFacts(
            rootDir = dir,
            moduleDirs = mapOf("core" to ":core"),
            buildDirs = mapOf(":core" to File(dir, "core/build").absolutePath),
            declared = project.provider { setOf(":core") },
            compileTime = project.files(),
            ownPath = ":core",
            testOutputs = project.files(output),
            classpath = project.files(output),
        )
    }

    private fun javaClassBytes(name: String, field: String, value: Int): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            name.replace('.', '/'), null, "java/lang/Object", null,
        )
        writer.visitField(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC or
                org.objectweb.asm.Opcodes.ACC_FINAL,
            field, "I", null, value,
        ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a class whose bytecode moved since the capture is handed to the selector apart from the change set`(
        @TempDir dir: File,
    ) {
        // `com.acme.Consumer`'s source did not change but its bytecode did, because the constant it
        // baked in moved; `git diff` cannot see that.
        val facts = oneModule(dir, "com.acme.Consumer" to javaClassBytes("com.acme.Consumer", "N", 2))
        val recorded = mapOf(
            "com.acme.Consumer" to Recordability.classDigest(
                javaClassBytes("com.acme.Consumer", "N", 1)
            ).value!!
        )

        val widening = widenForInlining(
            listOf("com.acme.Changed"), facts,
            digestRule = DigestRule(recorded, bytesAreFresh = true),
            recordedAnnotations = emptyMap(),
        )

        assertFalse(widening.forces, "a class the selector can judge must not force here")
        assertEquals(setOf("com.acme.Consumer"), widening.digest!!.added)
        assertEquals(setOf("com.acme.Consumer"), widening.changedBytes)
        assertEquals(listOf("com.acme.Changed"), widening.prefixes)
    }

    @Test
    fun `a digest-derived name never reaches the change set the selector anchors on`(
        @TempDir dir: File,
    ) {
        // `Selector.isNestedUnder` uses the change set as an anchor: digest-derived names in it
        // would let an unrelated nested class certify a prefix nothing covers, skipping a test.
        // So they travel in a set of their own.
        val facts = oneModule(dir, "com.acme.Other" to javaClassBytes("com.acme.Other", "N", 2))
        val recorded = mapOf(
            "com.acme.Other" to Recordability.classDigest(
                javaClassBytes("com.acme.Other", "N", 1)
            ).value!!
        )

        val widening = widenForInlining(
            listOf("com.acme.Comment"), facts,
            digestRule = DigestRule(recorded, bytesAreFresh = true),
            recordedAnnotations = emptyMap(),
        )

        assertEquals(setOf("com.acme.Other"), widening.digest!!.added,
            "the rule stopped seeing the recompiled consumer, which is the evidence it exists for")
        assertEquals(listOf("com.acme.Comment"), widening.prefixes,
            "a digest-derived name reached the change set, which is also the anchor set for " +
                "Selector.isNestedUnder -- it can certify a prefix nothing covers and suppress a full run")
        assertEquals(setOf("com.acme.Other"), widening.changedBytes)
    }

    @Test
    fun `a class whose bytecode is exactly what the map recorded is not added`(@TempDir dir: File) {
        // If everything were added, the rule would just run every test.
        val bytes = javaClassBytes("com.acme.Consumer", "N", 1)
        val facts = oneModule(dir, "com.acme.Consumer" to bytes)
        val recorded = mapOf("com.acme.Consumer" to Recordability.classDigest(bytes).value!!)

        val widening = widenForInlining(
            listOf("com.acme.Changed"), facts,
            digestRule = DigestRule(recorded, bytesAreFresh = true),
            recordedAnnotations = emptyMap(),
        )

        assertEquals(emptySet(), widening.digest!!.added)
        assertEquals(listOf("com.acme.Changed"), widening.prefixes)
        assertNull(widening.digest!!.silence, "a comparison that ran must not read as a silence")
        assertEquals(emptySet(), widening.changedBytes)
        assertFalse(widening.forces)
    }

    @Test
    fun `a class the map recorded and the output no longer holds is added, not read as unchanged`(
        @TempDir dir: File,
    ) {
        // A deletion or move: absent from the output is no evidence of a match, so it is added.
        val facts = oneModule(dir, "com.acme.Kept" to javaClassBytes("com.acme.Kept", "N", 1))
        val recorded = mapOf(
            "com.acme.Kept" to Recordability.classDigest(javaClassBytes("com.acme.Kept", "N", 1)).value!!,
            "com.acme.Deleted" to "whatever-it-was",
        )

        val widening = widenForInlining(
            listOf("com.acme.Changed"), facts,
            digestRule = DigestRule(recorded, bytesAreFresh = true),
            recordedAnnotations = emptyMap(),
        )

        assertEquals(setOf("com.acme.Deleted"), widening.digest!!.added)
        assertEquals(setOf("com.acme.Deleted"), widening.changedBytes)
        assertFalse(widening.forces)
    }

    @Test
    fun `a missing table forces, and says it was the table`(@TempDir dir: File) {
        // Nothing to compare against is not agreement: a rewritten class cannot be ruled out.
        val facts = oneModule(dir, "com.acme.Consumer" to javaClassBytes("com.acme.Consumer", "N", 1))

        for (prefixes in listOf(listOf("com.acme.Changed"), emptyList())) {
            val widening = widenForInlining(
                prefixes, facts,
                digestRule = DigestRule(recorded = null, bytesAreFresh = true),
                recordedAnnotations = emptyMap(),
            )

            assertTrue(widening.forces, "a map with no digest table narrowed on $prefixes")
            assertEquals(RefusalKind.BYTES_UNRECORDED, widening.refusalKind)
            assertEquals(DigestSilence.TABLE_ABSENT.token, widening.digest!!.silence)
        }
    }

    @Test
    fun `a walk that could not finish forces, even on an empty change set`(@TempDir dir: File) {
        // Empty, because a change set naming a class sends the inline scan into the same file first.
        val facts = oneModule(dir)
        File(dir, "core/build/classes/com/acme").mkdirs()
        File(dir, "core/build/classes/com/acme/Broken.class").writeBytes(byteArrayOf(1, 2, 3))

        val widening = widenForInlining(
            emptyList(), facts,
            digestRule = DigestRule(mapOf("com.acme.Broken" to "recorded"), bytesAreFresh = true),
            recordedAnnotations = emptyMap(),
        )

        assertTrue(widening.forces, "an unfinished walk read as agreement")
        assertEquals(RefusalKind.SCAN_EXHAUSTED, widening.refusalKind)
    }

    @Test
    fun `a walk that could not finish is told apart from a missing table, and neither adds anything`(
        @TempDir dir: File,
    ) {
        // Both add nothing, but the record says which: one is fixed by a capture, the other by
        // looking at a file. Asked of the comparison directly, because the inline scan refuses on
        // an unreadable class first.
        val output = File(dir, "classes").also { it.mkdirs() }
        File(output, "com/acme").mkdirs()
        File(output, "com/acme/Broken.class").writeBytes(byteArrayOf(1, 2, 3))
        val artifacts = TaskArtifacts(listOf(output), emptyList(), listOf(output))

        val refused = compareDigests(
            DigestRule(mapOf("com.acme.Broken" to "recorded"), bytesAreFresh = true), artifacts,
        )
        val absent = compareDigests(DigestRule(recorded = null, bytesAreFresh = true), artifacts)

        assertEquals(emptySet(), refused.added)
        assertEquals(emptySet(), absent.added)
        assertEquals(DigestSilence.WALK_UNREADABLE.token, refused.silence)
        assertEquals(DigestSilence.TABLE_ABSENT.token, absent.silence)
        assertNotNull(refused.detail, "a silence that names no cause sends a reader nowhere")
        assertNull(absent.detail)
    }

    @Test
    fun `the explanation carries the rule's counters and whether it ran at all`(@TempDir dir: File) {
        val bytes = javaClassBytes("com.acme.Consumer", "N", 2)
        val facts = oneModule(dir, "com.acme.Consumer" to bytes)
        val recorded = mapOf(
            "com.acme.Consumer" to Recordability.classDigest(
                javaClassBytes("com.acme.Consumer", "N", 1)
            ).value!!
        )
        val widening = widenForInlining(
            listOf("com.acme.Changed"), facts,
            digestRule = DigestRule(recorded, bytesAreFresh = false),
            recordedAnnotations = emptyMap(),
        )
        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(widening.prefixes, emptyList()),
            emptyList(),
        )

        writeExplanation(dir, ":core:test", "HEAD~1", scoped(prefixes = setOf("com.acme.Changed")),
                         established(), decision, widening)
        val json = File(dir, YoriwakePlugin.EXPLANATION_FILE).readText()

        assertContains(json, """"digestOn": true""")
        assertContains(json, """"digestAdded": 1""")
        assertContains(json, """"digestRecorded": 1""")
        assertContains(json, """"digestAddedSample": ["com.acme.Consumer"]""")
        // A bare `yoriwakeExplain` digests whatever the build directory held, and the record says
        // so rather than letting a reader assume it agreed with the run.
        assertContains(json, """"digestBytesStale": true""")
    }

    @Test
    fun `an explanation with no comparison says it did not run`(@TempDir dir: File) {
        val facts = oneModule(dir, "com.acme.Consumer" to javaClassBytes("com.acme.Consumer", "N", 1))
        val widening = widenForInlining(listOf("com.acme.Changed"), facts, recordedAnnotations = emptyMap())
        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(widening.prefixes, emptyList()),
            emptyList(),
        )

        writeExplanation(dir, ":core:test", "HEAD~1", scoped(prefixes = setOf("com.acme.Changed")),
                         established(), decision, widening)

        assertContains(File(dir, YoriwakePlugin.EXPLANATION_FILE).readText(), """"digestOn": false""")
    }

    @Test
    fun `a run that forces still records what the digest rule saw`(@TempDir dir: File) {
        // A forced run is where the record matters: would the digest rule have named the consumers
        // this forcing protects?
        val holder = javaClassBytes("com.acme.Holder", "LIMIT", 11)
        val facts = oneModule(dir, "com.acme.Holder" to holder)
        // The map recorded a different value, so the constant rule forces.
        val recorded = mapOf(
            "com.acme.Holder" to Recordability.classDigest(
                javaClassBytes("com.acme.Holder", "LIMIT", 10)
            ).value!!
        )

        val widening = widenForInlining(
            listOf("com.acme.Holder"), facts,
            recordedConstants = mapOf("com.acme.Holder" to "a-different-constant-digest"),
            digestRule = DigestRule(recorded, bytesAreFresh = true),
            recordedAnnotations = emptyMap(),
        )

        assertTrue(widening.forces, "the constant refusal must still fire")
        assertEquals(RefusalKind.CONSTANT_CHANGED, widening.refusalKind)
        assertEquals(setOf("com.acme.Holder"), widening.digest!!.added,
            "the forced run recorded nothing about what the digest rule could see")
    }

    @Test
    fun `an empty change set records what the rule saw and is handed none of it`(@TempDir dir: File) {
        // An empty change set nobody accounted for forces on its own account; digest-derived
        // prefixes must not make it non-empty and let the build narrow where it forced before.
        val facts = oneModule(dir, "com.acme.Consumer" to javaClassBytes("com.acme.Consumer", "N", 2))
        val recorded = mapOf(
            "com.acme.Consumer" to Recordability.classDigest(
                javaClassBytes("com.acme.Consumer", "N", 1)
            ).value!!
        )

        val widening = widenForInlining(
            emptyList(), facts,
            digestRule = DigestRule(recorded, bytesAreFresh = true),
            recordedAnnotations = emptyMap(),
        )

        assertEquals(emptyList(), widening.prefixes,
            "the rule handed an empty change set prefixes, which lets a forced run narrow")
        assertEquals(setOf("com.acme.Consumer"), widening.digest!!.added,
            "the run that the rule is most interesting on recorded nothing about it")
        assertEquals(setOf("com.acme.Consumer"), widening.changedBytes)
    }

    /** A class carrying [profile] as a class-level annotation value, or no annotation when null. */
    private fun profiled(name: String, profile: String?): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            name.replace('.', '/'), null, "java/lang/Object", null,
        )
        if (profile != null) {
            writer.visitAnnotation("Lcom/acme/Profile;", true).apply {
                visit("value", profile)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun annotationsOf(bytes: ByteArray) = Recordability.annotationDigest(bytes).value!!

    @Test
    fun `a changed annotation forces a full run and names it`() {
        val name = "com.acme.Service"
        val recorded = mapOf(name to annotationsOf(profiled(name, "a")))

        val refusal = annotationRefusal(setOf(name), recorded) { listOf(name to profiled(name, "b")) }

        assertNotNull(refusal, "an annotation change narrowed, and a test that only scans the class is skipped")
        assertEquals(RefusalKind.ANNOTATIONS_CHANGED, refusal.refusalKind)
        assertEquals(name, refusal.refusalDetail)
    }

    @Test
    fun `identical annotations do not force`() {
        val name = "com.acme.Service"
        val recorded = mapOf(name to annotationsOf(profiled(name, "a")))

        assertNull(annotationRefusal(setOf(name), recorded) { listOf(name to profiled(name, "a")) })
    }

    @Test
    fun `a map without an annotation table forces on every changed class`() {
        val name = "com.acme.Service"

        val refusal = annotationRefusal(setOf(name), null) { listOf(name to profiled(name, null)) }

        assertNotNull(refusal, "a map from before the table narrowed on annotations it never recorded")
        assertEquals(RefusalKind.ANNOTATIONS_UNRECORDED, refusal.refusalKind)
    }

    @Test
    fun `a class the table does not hold forces only when it carries annotations`() {
        // The table holds every own class, so a missing one is new since the capture: unannotated,
        // no scan can have found it by its annotations.
        val name = "com.acme.Added"

        assertNull(annotationRefusal(setOf(name), emptyMap()) { listOf(name to profiled(name, null)) })
        assertEquals(
            RefusalKind.ANNOTATIONS_UNRECORDED,
            annotationRefusal(setOf(name), emptyMap()) { listOf(name to profiled(name, "a")) }?.refusalKind,
        )
    }

    @Test
    fun `deleting an annotated class forces, deleting an unannotated one does not`() {
        val name = "com.acme.Gone"

        val annotated = annotationRefusal(setOf(name), mapOf(name to annotationsOf(profiled(name, "a")))) {
            emptyList()
        }
        assertEquals(RefusalKind.ANNOTATIONS_CHANGED, annotated?.refusalKind)
        assertNull(annotationRefusal(setOf(name), mapOf(name to Recordability.NO_ANNOTATIONS)) { emptyList() })
    }

    @Test
    fun `a nested class belongs to the changed source it was compiled from`() {
        val nested = "com.acme.Service\$Config"
        val refusal = annotationRefusal(setOf("com.acme.Service"), mapOf(nested to annotationsOf(profiled(nested, "a")))) {
            emptyList()
        }
        assertEquals(RefusalKind.ANNOTATIONS_CHANGED, refusal?.refusalKind)
        // A same-prefixed sibling is another source file.
        assertNull(
            annotationRefusal(setOf("com.acme.Service"), mapOf("com.acme.ServiceTwo" to annotationsOf(profiled("com.acme.ServiceTwo", "a")))) {
                emptyList()
            }
        )
    }

    @Test
    fun `classes that cannot be established refuse rather than pass`() {
        assertEquals(
            RefusalKind.SCAN_REFUSED,
            annotationRefusal(setOf("com.acme.Service"), emptyMap()) { null }?.refusalKind,
        )
        assertEquals(
            RefusalKind.SCAN_REFUSED,
            annotationRefusal(setOf("com.acme.Service"), emptyMap()) { listOf(it to byteArrayOf(1, 2, 3)) }?.refusalKind,
        )
    }

    @Test
    fun `the annotation refusal reaches explain json by its token`(@TempDir dir: File) {
        val name = "com.acme.Service"
        val changed = annotationRefusal(setOf(name), mapOf(name to annotationsOf(profiled(name, "a")))) {
            listOf(name to profiled(name, "b"))
        }!!
        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            io.github.zeuspizza.yoriwake.agent.select.MapReader.read(File(dir, "absent")),
            ChangeSet.of(listOf(name), emptyList()),
            emptyList(),
        )
        writeExplanation(dir, ":core:test", "HEAD~1", scoped(prefixes = setOf(name)), established(),
            decision, changed)

        assertContains(File(dir, YoriwakePlugin.EXPLANATION_FILE).readText(), """"refusalKind": "annotations-changed"""")
    }

    @Test
    fun `widening forces on an annotation change through the one helper every site calls`(@TempDir dir: File) {
        val name = "com.acme.Service"
        val facts = oneModule(dir, name to profiled(name, "b"))

        val changed = widenForInlining(
            listOf(name), facts, inlinableSourceChanged = false,
            recordedAnnotations = mapOf(name to annotationsOf(profiled(name, "a"))),
        )
        val unchanged = widenForInlining(
            listOf(name), facts, inlinableSourceChanged = false,
            recordedAnnotations = mapOf(name to annotationsOf(profiled(name, "b"))),
        )

        assertEquals(RefusalKind.ANNOTATIONS_CHANGED, changed.refusalKind)
        assertFalse(unchanged.forces, "identical annotations forced: ${unchanged.refusal}")
    }

    /** A class declaring one JUnit Jupiter `@Test` method per name in [tests]. */
    private fun testClass(name: String, vararg tests: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8, 0, name.replace('.', '/'), null, "java/lang/Object", null,
        )
        for (test in tests) {
            writer.visitMethod(0, test, "()V", null, null).apply {
                visitAnnotation("Lorg/junit/jupiter/api/Test;", true).visitEnd()
                visitCode()
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(0, 1)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a new test method in a test class this task runs does not force`() {
        val name = "com.acme.ServiceTest"
        val recorded = mapOf(name to annotationsOf(testClass(name, "a")))

        assertNull(
            annotationRefusal(setOf(name), recorded, ownTestClasses = setOf(name)) {
                listOf(name to testClass(name, "a", "b"))
            },
            "the class's own tests are selected anyway, so its annotations cannot hide one",
        )
    }

    @Test
    fun `a new test class this task runs does not force`() {
        val name = "com.acme.AddedTest"

        assertNull(annotationRefusal(setOf(name), emptyMap(), ownTestClasses = setOf(name)) {
            listOf(name to testClass(name, "a"))
        })
    }

    @Test
    fun `an annotation change on a test helper that declares no test still forces`() {
        // In this task's test output, but nothing selects it: a scan may be what reads it.
        val name = "com.acme.TestConfig"
        val recorded = mapOf(name to annotationsOf(profiled(name, "a")))

        val refusal = annotationRefusal(setOf(name), recorded, ownTestClasses = setOf(name)) {
            listOf(name to profiled(name, "b"))
        }

        assertEquals(RefusalKind.ANNOTATIONS_CHANGED, refusal?.refusalKind)
        assertEquals(RefusalKind.ANNOTATIONS_UNRECORDED, annotationRefusal(setOf(name), emptyMap(), ownTestClasses = setOf(name)) {
            listOf(name to profiled(name, "a"))
        }?.refusalKind)
    }

    @Test
    fun `a test class this task does not run still forces on an annotation change`() {
        // Another task's test class on this task's classpath: its tests are not this run's.
        val name = "com.acme.OtherTaskTest"
        val recorded = mapOf(name to annotationsOf(testClass(name, "a")))

        val refusal = annotationRefusal(setOf(name), recorded, ownTestClasses = emptySet()) {
            listOf(name to testClass(name, "a", "b"))
        }

        assertEquals(RefusalKind.ANNOTATIONS_CHANGED, refusal?.refusalKind)
    }

    @Test
    fun `a nested class of a test class is judged on its own`() {
        val outer = "com.acme.ServiceTest"
        val nested = "com.acme.ServiceTest\$Config"
        val recorded = mapOf(
            outer to annotationsOf(testClass(outer, "a")),
            nested to annotationsOf(profiled(nested, "a")),
        )

        val refusal = annotationRefusal(setOf(outer), recorded, ownTestClasses = setOf(outer)) {
            listOf(outer to testClass(outer, "a", "b"), nested to profiled(nested, "b"))
        }

        assertEquals(RefusalKind.ANNOTATIONS_CHANGED, refusal?.refusalKind)
        assertEquals(nested, refusal?.refusalDetail)
    }
}
