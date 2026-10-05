package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

/**
 * A capture that does not date the map, then a change that undoes what it saw.
 *
 * `Router` reaches `Legacy` at the captured commit; a later commit removes that branch, a run that
 * cannot date the map records `RouterTest` there, and the branch comes back. `RouterTest` then
 * executes `Legacy` again, so an edit to `Legacy` must run it. One JVM per class, so no shared-JVM
 * rule selects it on its own.
 */
class RevertAfterUndatedCaptureFunctionalTest : FunctionalTestSupport() {

    private val build = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        tasks.test {
            useJUnitPlatform { if (project.hasProperty("fastOnly")) includeTags("fast") }
            forkEvery = 1
            if (project.hasProperty("narrow")) filter.includeTestsMatching("*RouterTest")
        }
        """.trimIndent(),
    )

    private fun main(name: String, body: String) = "src/main/java/dev/sample/$name.java" to "package dev.sample;\n$body"

    private fun test(name: String, body: String) = "src/test/java/dev/sample/$name.java" to """
        package dev.sample;
        import org.junit.jupiter.api.*;
        import static org.junit.jupiter.api.Assertions.*;
        $body
    """.trimIndent()

    private val withBranch = "public class Router { public int route() { return Legacy.value(); } }"
    private val withoutBranch = "public class Router { public int route() { return 0; } }"

    private val routerFile = "src/main/java/dev/sample/Router.java"

    /** The fixture committed twice (so the captured commit has a parent) and captured at the second. */
    private fun capturedAtS(dir: File) {
        build(
            dir,
            "build.gradle.kts" to build,
            main("Legacy", "public class Legacy { public static int value() { return 1; } }"),
            main("Router", withBranch),
            main("Other", "public class Other { public int one() { return 1; } }"),
            test("RouterTest", "@Tag(\"fast\") class RouterTest { @Test void routes() { new Router().route(); } }"),
            test("LegacyTest", "class LegacyTest { @Test void value() { assertEquals(1, Legacy.value()); } }"),
            test("OtherTest", "class OtherTest { @Test void one() { assertEquals(1, new Other().one()); } }"),
        )
        committed(dir)
        File(dir, "src/main/java/dev/sample/Other.java")
            .writeText("package dev.sample;\npublic class Other { public int one() { return 2 - 1; } }")
        commit(dir, "S")
        runner(dir, "test").build()
    }

    private fun setRouter(dir: File, body: String) = File(dir, routerFile).writeText("package dev.sample;\n$body")

    /** Commit R: the branch to Legacy is gone. */
    private fun commitR(dir: File) {
        setRouter(dir, withoutBranch)
        commit(dir, "R")
    }

    private fun filteredRun(dir: File) = runner(dir, "test", "--tests", "dev.sample.RouterTest").build()

    /** An edit to Legacy, then a selecting run; what it ran. */
    private fun editLegacyAndSelect(dir: File): Set<String> {
        File(dir, "src/main/java/dev/sample/Legacy.java")
            .writeText("package dev.sample;\npublic class Legacy { public static int value() { return 2 - 1; } }")
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select").build()
        return ranTests(dir)
    }

    private fun assertRouterTestRan(ran: Set<String>) =
        assertTrue("dev.sample.RouterTest" in ran, "RouterTest executes the edited Legacy again; ran $ran")

    /** Git with an identity, which commits, reverts, stashes and cherry-picks need. */
    private fun gitAs(dir: File, vararg args: String) {
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", *args)
    }

    @Test
    fun `a revert after a filtered run runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        commitR(dir)
        filteredRun(dir)
        gitAs(dir, "revert", "--no-edit", "HEAD")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a committed hand undo after a filtered run runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        commitR(dir)
        filteredRun(dir)
        gitAs(dir, "checkout", "HEAD~1", "--", routerFile)
        commit(dir, "undo by hand")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `an uncommitted hand undo after a filtered run runs the test it hid`(@TempDir dir: File) {
        // Visible in the diff from HEAD, so the change set names Router whatever the map holds.
        capturedAtS(dir)
        commitR(dir)
        filteredRun(dir)
        gitAs(dir, "checkout", "HEAD~1", "--", routerFile)

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a reset to the captured commit after a filtered run runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        commitR(dir)
        filteredRun(dir)
        gitAs(dir, "reset", "--hard", "HEAD~1")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a switch back after a filtered run on another branch runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        gitAs(dir, "checkout", "-b", "b")
        commitR(dir)
        filteredRun(dir)
        gitAs(dir, "checkout", "-")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a rewrite that recreates the captured tree after a filtered run runs the test it hid`(
        @TempDir dir: File,
    ) {
        capturedAtS(dir)
        commitR(dir)
        filteredRun(dir)
        // A new commit with the captured commit's tree: the stamp still resolves, and nothing differs from it.
        gitAs(dir, "reset", "--hard", "HEAD~2")
        gitAs(dir, "cherry-pick", "HEAD@{2}")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a stash after a filtered run over an uncommitted edit runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        setRouter(dir, withoutBranch)
        filteredRun(dir)
        gitAs(dir, "stash")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a filtered run between a stash and its pop runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        setRouter(dir, withoutBranch)
        gitAs(dir, "stash")
        filteredRun(dir)
        gitAs(dir, "stash", "pop")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a revert after a fail-fast capture runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        commitR(dir)
        // Ordered after RouterTest, which is re-recorded at R before the run stops.
        val failing = File(dir, "src/test/java/dev/sample/ZFailTest.java")
        failing.writeText(test("ZFailTest", "class ZFailTest { @Test void fails() { fail(); } }").second)
        runner(dir, "test", "--fail-fast").buildAndFail()
        failing.delete()
        gitAs(dir, "revert", "--no-edit", "HEAD")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a revert after a capture whose fork halted runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        // Committed with R, so the revert takes it away again and nothing about it is left over.
        File(dir, "src/test/java/dev/sample/ZHaltTest.java").writeText(
            test("ZHaltTest", "class ZHaltTest { @Test void dies() { Runtime.getRuntime().halt(1); } }").second,
        )
        commitR(dir)
        runner(dir, "test").buildAndFail()
        gitAs(dir, "revert", "--no-edit", "HEAD")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a revert after a run the build script filters runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        commitR(dir)
        runner(dir, "test", "-Pnarrow").build()
        gitAs(dir, "revert", "--no-edit", "HEAD")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a revert after a filtered selecting run that captured instead runs the test it hid`(
        @TempDir dir: File,
    ) {
        capturedAtS(dir)
        // A class the map has never seen, committed with R, forces the selecting run to run, and
        // capture, everything the filter admits; the revert takes it away again.
        File(dir, "src/main/java/dev/sample/Gamma.java")
            .writeText("package dev.sample;\npublic class Gamma { public int four() { return 4; } }")
        commitR(dir)
        runner(dir, "test", "--tests", "dev.sample.RouterTest", "-Pyoriwake.select").build()
        gitAs(dir, "revert", "--no-edit", "HEAD")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a revert after a tag-filtered capture runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        commitR(dir)
        runner(dir, "test", "-PfastOnly").build()
        gitAs(dir, "revert", "--no-edit", "HEAD")

        assertRouterTestRan(editLegacyAndSelect(dir))
    }

    @Test
    fun `a map restored from another branch's capture runs the test it hid`(@TempDir dir: File) {
        capturedAtS(dir)
        val mapRoot = File(dir, ".gradle/yoriwake")
        val mainMap = File(dir, "main-map").also { mapRoot.copyRecursively(it) }
        gitAs(dir, "checkout", "-b", "b")
        commitR(dir)
        runner(dir, "test").build()
        val branchMap = File(dir, "branch-map").also { mapRoot.copyRecursively(it) }
        gitAs(dir, "checkout", "-")
        mapRoot.deleteRecursively()
        branchMap.copyRecursively(mapRoot)
        mainMap.deleteRecursively()
        branchMap.deleteRecursively()

        assertRouterTestRan(editLegacyAndSelect(dir))
    }
}
