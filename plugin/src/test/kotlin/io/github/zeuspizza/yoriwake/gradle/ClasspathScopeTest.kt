package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathScope
import io.github.zeuspizza.yoriwake.gradle.change.establish
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The narrowing rule that starts from presence rather than absence: the cut may remove only files
 * that cannot reach the task's JVM as a class or by path; everything else keeps forcing.
 */
class ClasspathScopeTest {

    private val modules = mapOf(
        "" to ":",
        "core" to ":core",
        "webserver" to ":webserver",
        "jdbc" to ":jdbc",
        "jdbc-h2" to ":jdbc-h2",
        "ui" to ":ui",
    )

    private val onlyCore = setOf(":core")

    /** No compiled class names any path, so only the classpath decides. */
    private val nothingNamed: (Collection<String>) -> Set<String>? = { emptySet() }

    @Test
    fun `drops another module's sources`() {
        val result = ClasspathScope.restrict(
            listOf("ui/src/components/Flow.vue", "webserver/src/main/java/io/kestra/webserver/Api.java"),
            modules,
            onlyCore,
            nothingNamed,
        )

        assertTrue(result.kept.isEmpty())
        assertEquals(2, result.dropped.size)
    }

    @Test
    fun `keeps the sources of a module that is on the classpath`() {
        val result = ClasspathScope.restrict(
            listOf("core/src/main/java/io/kestra/core/utils/WindowsUtils.java"),
            modules,
            onlyCore,
            nothingNamed,
        )

        assertEquals(1, result.kept.size)
    }

    @Test
    fun `keeps another module's build script, because it can change this task's dependencies`() {
        val result = ClasspathScope.restrict(
            listOf("webserver/build.gradle", "settings.gradle", "gradle/libs.versions.toml"),
            modules,
            onlyCore,
            nothingNamed,
        )

        assertEquals(3, result.kept.size)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `keeps anything it cannot attribute to a module`() {
        val result = ClasspathScope.restrict(
            listOf("README.md", ".github/labeler.yml", "somewhere/else/entirely.txt"),
            modules,
            onlyCore,
            nothingNamed,
        )

        assertEquals(3, result.kept.size)
    }

    @Test
    fun `keeps a file in a module directory that is not under src`() {
        // Generated output, a Dockerfile, a module-level config file: the src convention is what
        // this rule understands, and a module that does not follow it keeps forcing.
        val result = ClasspathScope.restrict(listOf("webserver/Dockerfile"), modules, onlyCore, nothingNamed)

        assertEquals(listOf("webserver/Dockerfile"), result.kept)
    }

    @Test
    fun `a nested module name is not swallowed by its neighbour`() {
        // :jdbc and :jdbc-h2 sit side by side. Prefix matching on the directory alone would let
        // "jdbc" own "jdbc-h2/src/...", so a present :jdbc would vouch for an absent :jdbc-h2.
        val result = ClasspathScope.restrict(
            listOf("jdbc-h2/src/main/java/io/kestra/H2.java"),
            modules,
            setOf(":core", ":jdbc"),
            nothingNamed,
        )

        assertEquals(1, result.dropped.size)
    }

    @Test
    fun `a classpath it could not establish drops nothing`() {
        // An empty set means "we could not find out", never "no module is present". The second
        // reading would drop every module's sources at once.
        val paths = listOf("ui/src/components/Flow.vue", "core/src/main/java/A.java")

        val result = ClasspathScope.restrict(paths, modules, emptySet(), nothingNamed)

        assertEquals(paths, result.kept)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `the root project never owns a file by itself`() {
        // Its directory is a prefix of every path in the repository. If it owned them, one absent
        // root project would drop the whole change set.
        val result = ClasspathScope.restrict(listOf("core/src/main/java/A.java"), modules, onlyCore, nothingNamed)

        assertEquals(1, result.kept.size)
    }

    @Test
    fun `a sibling's file a compiled class names is kept`() {
        // A golden file in a module the JVM never loads is still a file a test can open by path.
        // Dropping it on the strength of the classpath would skip the test that reads it.
        val golden = "ui/src/main/resources/golden/flow.json"
        val vue = "ui/src/components/Flow.vue"

        val result = ClasspathScope.restrict(listOf(golden, vue), modules, onlyCore) { setOf(golden) }

        assertEquals(listOf(golden), result.kept)
        assertEquals(listOf(vue), result.dropped)
    }

    @Test
    fun `a sibling path in real bytecode is kept, and so is every path under its ancestors`(
        @TempDir dir: File,
    ) {
        // The rule the lambda stands in for, end to end: a test class holding the relative path it
        // reads, as `Path.of("../ui/src/main/resources/golden/flow.json")` compiles to. It names
        // `src` too, so another module's Vue component is kept with it -- the price of matching a
        // path assembled from parts, paid on purpose.
        File(dir, "pkg").mkdirs()
        File(dir, "pkg/GoldenTest.class")
            .writeBytes("..../ui/src/main/resources/golden/flow.json..".toByteArray())
        val paths = listOf("ui/src/main/resources/golden/flow.json", "webserver/src/components/Flow.vue")

        val result = ClasspathScope.restrict(
            paths, modules, onlyCore, TaskArtifacts(listOf(dir), emptyList())::pathsNamedInClasses,
        )

        assertEquals(paths, result.kept)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `a sibling path no real bytecode names is dropped`(@TempDir dir: File) {
        File(dir, "pkg").mkdirs()
        File(dir, "pkg/ThingTest.class")
            .writeBytes("..dev/core/ThingTest..java/lang/Object..org/junit/jupiter/api/Test..".toByteArray())

        val result = ClasspathScope.restrict(
            listOf("webserver/src/components/Flow.vue"), modules, onlyCore,
            TaskArtifacts(listOf(dir), emptyList())::pathsNamedInClasses,
        )

        assertTrue(result.kept.isEmpty())
        assertEquals(listOf("webserver/src/components/Flow.vue"), result.dropped)
    }

    @Test
    fun `compiled classes it could not search drop nothing`() {
        // "Could not ask which paths are named" is not "none are"; the second reading drops
        // everything.
        val paths = listOf("ui/src/components/Flow.vue", "webserver/src/main/java/Api.java")

        val unanswered = ClasspathScope.restrict(paths, modules, onlyCore) { null }
        val threw = ClasspathScope.restrict(paths, modules, onlyCore) { error("no artifacts") }

        assertEquals(paths, unanswered.kept)
        assertTrue(unanswered.dropped.isEmpty())
        assertEquals(paths, threw.kept)
        assertTrue(threw.dropped.isEmpty())
    }
}
