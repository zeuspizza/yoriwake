package io.github.zeuspizza.yoriwake.maven.breaking

import io.github.zeuspizza.yoriwake.gradle.breaking.Aim
import io.github.zeuspizza.yoriwake.gradle.breaking.Outcome
import io.github.zeuspizza.yoriwake.gradle.breaking.assertOutcome
import io.github.zeuspizza.yoriwake.maven.FunctionalMaven
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Attempts on the Maven integration's own surface: where it declines an execution, how it reads
 * what Surefire hands the test JVM, and how a selecting, complement or forced run reaches the
 * shared rules. Each stands just past where the Maven side reads its input, through what a POM, the
 * project's `.mvn/` directory, its sources or its tests can hold.
 */
class MavenAndSurefireAttempts : MavenBreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    /** Beta's behaviour changes, so BetaTest fails; AlphaTest never loads Beta. */
    private fun breakBeta(project: File) {
        File(project, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return n * 3 + 1; } }",
        )
    }

    /** Alpha rewritten to behave as before: a changed path that fails no test. */
    private fun rewriteAlpha(project: File, path: String = "src/main/java/dev/sample/Alpha.java") {
        File(project, path).writeText("package dev.sample;\npublic class Alpha { public int twice(int n) { return n + n; } }")
    }

    private val twoClasses = listOf(oneClass, oneTest, secondClass, secondTest)

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun String.replacing(old: String, new: String): String =
        replaceFirst(old, new).also { check(it != this) { "the POM does not hold $old" } }

    /** [pom] with [xml] added to its `<properties>`. */
    private fun withProperties(pom: String, xml: String) = pom.replacing("<properties>", "<properties>\n    $xml")

    /** [pom] with [xml] added to the Surefire configuration [minimalPom] gives. */
    private fun withSurefire(pom: String, xml: String) = pom.replacing(surefireConfiguration, surefireConfiguration + xml)

    /** [pom] with [xml] as the yoriwake plugin's configuration. */
    private fun withYoriwake(pom: String, xml: String) =
        pom.replacing("<extensions>true</extensions>", "<extensions>true</extensions>\n        <configuration>$xml</configuration>")

    /** [pom] with [xml] (profiles, say) before it ends. */
    private fun beforeEnd(pom: String, xml: String) = pom.replacing("</project>", "  $xml\n</project>")

    /** [pom] with [xml] as one more test dependency. */
    private fun withDependency(pom: String, xml: String) = pom.replacing(
        "<artifactId>junit-jupiter</artifactId>\n      <scope>test</scope>\n    </dependency>",
        "<artifactId>junit-jupiter</artifactId>\n      <scope>test</scope>\n    </dependency>\n    $xml",
    )

    private fun git(project: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
    }

    /** Develocity's Maven extension in `.mvn/`, pointed at a server that does not resolve. */
    private val develocity = listOf(
        ".mvn/extensions.xml" to "<extensions><extension><groupId>com.gradle</groupId><artifactId>develocity-maven-extension</artifactId>" +
            "<version>2.6.1</version></extension></extensions>\n",
        ".mvn/develocity.xml" to "<develocity><server><url>https://develocity.invalid</url></server>" +
            "<buildScan><publishing><onlyIf>false</onlyIf></publishing></buildScan></develocity>\n",
    )

    /** Every build runs the tests itself when Develocity's features find no server. */
    private val fallback = ".mvn/maven.config" to "-Ddevelocity.testing.fallbackToRegularExecutionOnMissingPrerequisites=true\n"

    // Track: code reading. Boundary: a module's own `<alwaysRun>` is read from its plugin entry and
    // matched as a glob over the test's class name. ReaderTest reaches Alpha only through a copy
    // under the build directory the map cannot see, so the pin alone keeps it.
    @Test
    fun `a test a module's alwaysRun glob pins runs though its read is invisible to the map`(@TempDir dir: File) {
        // One JVM for every class, so the pinned test's row is decided beside the others'; AaBetaTest
        // runs in it before anything loads Alpha, and is left out.
        val pom = withYoriwake(copyingPom, "<alwaysRun><test>dev.sample.Read*</test></alwaysRun>")
            .replacing(surefireConfiguration, "<runOrder>alphabetical</runOrder><forkCount>1</forkCount><reuseForks>true</reuseForks>")

        val result = attempt(
            dir, "dev.sample.ReaderTest", Aim.Row("always-run"),
            listOf(oneClass, oneTest, secondClass, "src/test/java/dev/sample/AaBetaTest.java" to secondTest.second.replace("class BetaTest", "class AaBetaTest"), reader),
            pom,
        ) { rewriteAlpha(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the test JVM reads the run file when it first asks a contract
    // property, each JVM afresh. Here a test of an earlier JVM of the same execution deletes it
    // once it sees Beta behave differently, so every later JVM finds no run file.
    @Test
    fun `a run file an earlier test JVM deleted refuses the later ones`(@TempDir dir: File) {
        val cleaner = source(
            "src/test/java/dev/sample/AaCleanerTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.util.stream.Stream;
            import org.junit.jupiter.api.Test;
            class AaCleanerTest {
                @Test void clearsStaleRunFiles() throws Exception {
                    if (new Beta().thrice(1) == 3) return;
                    try (Stream<Path> files = Files.walk(Paths.get("").toAbsolutePath())) {
                        for (Path file : (Iterable<Path>) files::iterator) {
                            if (file.getFileName().toString().equals("run.properties")) Files.delete(file);
                        }
                    }
                }
            }
            """,
        )

        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("change-set-unreadable"), twoClasses + cleaner) { breakBeta(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: `-Dyoriwake.complement=<dir>` looks the record up at
    // `<dir>/<map directory>`, so the directory must hold a copy of `.yoriwake/yoriwake`. Here it
    // holds a copy of `.yoriwake/` itself, one level up, and the property file the target reads
    // changes between the selecting run and the complement.
    @Test
    fun `a complement handed a copy of the whole yoriwake directory finds no record`(@TempDir dir: File) {
        val handoff = File(dir, "handoff")
        val result = complementOverFile(
            dir, Aim.Forced("complement-no-record"),
            complement = listOf("verify", "-Dyoriwake.complement=${handoff.path}"),
        ) { project -> File(project, ".yoriwake").copyRecursively(handoff) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the complement stamp carries the system properties Surefire
    // reads from `systemPropertiesFile`, which an execution names through the
    // `surefire.systemPropertiesFile` property as well as its own element. Here the POM names an
    // ignored file through the property, and the file changes between the selecting run and the
    // complement.
    @Test
    fun `a systemPropertiesFile the POM names by property and that changed is a record mismatch`(@TempDir dir: File) {
        val result = complementOverFile(dir, Aim.Forced("complement-record-mismatch"), complement = listOf("verify", "-Dyoriwake.complement"))

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    /**
     * A selecting run after a committed rewrite of Beta runs BetaTest, which reads `flavor` from an
     * ignored `flavor.properties` the POM names by the `surefire.systemPropertiesFile` property;
     * [between] runs after it, then the file says `b`, so BetaTest fails, and [complement] runs.
     */
    private fun complementOverFile(dir: File, aim: Aim, complement: List<String>, between: (File) -> Unit = {}) = attempt(
        dir, "dev.sample.BetaTest", aim,
        listOf(
            oneClass, oneTest, secondClass,
            ".gitignore" to "target/\nflavor.properties\n",
            "flavor.properties" to "flavor=a\n",
            source(
                "src/test/java/dev/sample/BetaTest.java",
                """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class BetaTest {
                    @Test void passes() { assertEquals(3, new Beta().thrice(1)); assertEquals("a", System.getProperty("flavor")); }
                }
                """,
            ),
        ),
        withProperties(minimalPom, "<surefire.systemPropertiesFile>flavor.properties</surefire.systemPropertiesFile>"),
        select = complement,
        primer = Primer(selecting) { project, _ ->
            between(project)
            File(project, "flavor.properties").writeText("flavor=b\n")
        },
        commit = true,
    ) { project ->
        File(project, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return n + n + n; } }",
        )
    }

    // Track: code reading. Boundary: the classpath table follows a dependency jar's manifest
    // `Class-Path` and reads what it names by content. Here a released jar in the local repository
    // names a second jar beside it, which is replaced in place with other bytes.
    @Test
    fun `a jar a repository jar's manifest names, replaced in place, runs every test`(@TempDir dir: File) {
        val home = File(FunctionalMaven.localRepo, "dev/sample/breakdeps")
        val version = File(home, "lib/1.0")
        fun data(value: String) = jar(File(version, "lib-data.jar"), null, mapOf("value.txt" to value))
        try {
            jar(File(version, "lib-1.0.jar"), "lib-data.jar", mapOf("dev/sample/breakdeps/README" to "data beside\n"))
            File(version, "lib-1.0.pom").writeText(
                "<project><modelVersion>4.0.0</modelVersion><groupId>dev.sample.breakdeps</groupId><artifactId>lib</artifactId><version>1.0</version></project>\n",
            )
            data("a")
            val pom = withDependency(
                minimalPom,
                "<dependency><groupId>dev.sample.breakdeps</groupId><artifactId>lib</artifactId><version>1.0</version><scope>test</scope></dependency>",
            )
            val valueTest = source(
                "src/test/java/dev/sample/ValueTest.java",
                """
                package dev.sample;
                import java.io.InputStream;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ValueTest {
                    @Test void reads() throws Exception {
                        try (InputStream in = ValueTest.class.getClassLoader().getResourceAsStream("value.txt")) {
                            assertEquals("a", new String(in.readAllBytes()));
                        }
                    }
                }
                """,
            )

            val result = attempt(dir, "dev.sample.ValueTest", Aim.Forced("dependencies-changed"), twoClasses + valueTest, pom) { project ->
                rewriteAlpha(project)
                data("b")
            }

            assertOutcome(Outcome.HELD_BY_FORCE, result)
        } finally {
            home.deleteRecursively()
        }
    }

    private fun jar(file: File, classPath: String?, entries: Map<String, String>) {
        file.parentFile.mkdirs()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            classPath?.let { mainAttributes[Attributes.Name.CLASS_PATH] = it }
        }
        JarOutputStream(file.outputStream(), manifest).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    // Track: code reading. Boundary: Develocity's Test Distribution is read from the execution's
    // `<properties>` as Maven interpolated them. Here a POM property switches it on, set in
    // pluginManagement's Surefire entry rather than the execution.
    @Test
    fun `Test Distribution a POM property switches on declines the execution`(@TempDir dir: File) {
        val pom = withProperties(
            minimalPom.replacing(
                "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>",
                "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>" +
                    "<configuration><properties><distribution><enabled>\${td}</enabled></distribution></properties></configuration></plugin>",
            ),
            "<td>false</td>",
        )

        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("develocity-test-distribution"), twoClasses + develocity + fallback, pom) { project ->
            File(project, "pom.xml").edit { it.replace("<td>false</td>", "<td>true</td>") }
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: `pts.enabled` is read from the invocation's user properties,
    // then Maven's own system properties. Here `.mvn/jvm.config` sets it as a system property of
    // Maven's JVM, so no command line names it.
    @Test
    fun `Predictive Test Selection switched on in jvm config declines the execution`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("develocity-test-selection"), twoClasses + develocity + fallback) { project ->
            File(project, ".mvn/jvm.config").writeText("-Dpts.enabled=true\n")
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a Develocity setting that is an expression nobody set cannot
    // be read as true or false. Here it is Test Distribution's, not Predictive Test Selection's.
    @Test
    fun `a Test Distribution setting nobody set declines the execution`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("develocity-undetermined"), twoClasses + develocity + fallback) { project ->
            File(project, "pom.xml").edit {
                it.replace(
                    "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>",
                    "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>" +
                        "<configuration><properties><distribution><enabled>\${td.nobody.sets}</enabled></distribution></properties></configuration></plugin>",
                )
            }
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: Develocity's workspace id is passed over as build state, by
    // its path, on the assumption that no test reads it. Here a test reads it, and it is the only
    // thing that changed.
    @Test
    fun `a test that reads Develocity's workspace id finds an empty change set`(@TempDir dir: File) {
        val workspace = source(
            "src/test/java/dev/sample/WorkspaceTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class WorkspaceTest {
                @Test void readsTheId() throws Exception {
                    Path id = Paths.get(".mvn/.develocity/develocity-workspace-id");
                    if (!Files.exists(id)) { Files.createDirectories(id.getParent()); Files.writeString(id, "one"); }
                    assertEquals("one", Files.readString(id));
                }
            }
            """,
        )

        val result = attempt(dir, "dev.sample.WorkspaceTest", Aim.Forced("empty-change-set"), twoClasses + workspace) { project ->
            File(project, ".mvn/.develocity/develocity-workspace-id").writeText("two")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the system properties the fingerprint compares include the
    // file `systemPropertiesFile` names, the POM's `surefire.systemPropertiesFile` property
    // standing in for the element. Here that file is ignored by git, so only its value changes.
    @Test
    fun `another value in a systemPropertiesFile the POM names by property runs every test`(@TempDir dir: File) {
        val modeTest = source(
            "src/test/java/dev/sample/ModeTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ModeTest { @Test void readsMode() { assertEquals("a", System.getProperty("mode")); } }
            """,
        )
        val pom = withProperties(minimalPom, "<surefire.systemPropertiesFile>local.properties</surefire.systemPropertiesFile>")

        val result = attempt(
            dir, "dev.sample.ModeTest", Aim.Forced("environment-changed"),
            twoClasses + modeTest + (".gitignore" to "target/\nlocal.properties\n") + ("local.properties" to "mode=a\n"), pom,
        ) { project ->
            File(project, "local.properties").writeText("mode=b\n")
            rewriteAlpha(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: `fullRunBranches` is read from the plugin's configuration as
    // Maven merged it, and an entry is a glob. Here the entry sits in pluginManagement, and the
    // branch is created after the capture.
    @Test
    fun `a branch a pluginManagement fullRunBranches glob lists runs everything`(@TempDir dir: File) {
        val pom = minimalPom.replacing(
            "<plugin><artifactId>maven-install-plugin</artifactId><version>3.1.3</version></plugin>",
            "<plugin><artifactId>maven-install-plugin</artifactId><version>3.1.3</version></plugin>\n" +
                "        <plugin><groupId>io.github.zeuspizza</groupId><artifactId>yoriwake-maven-plugin</artifactId>" +
                "<configuration><fullRunBranches><branch>release/*</branch></fullRunBranches></configuration></plugin>",
        )

        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("full-run-branch"), twoClasses, pom) { project ->
            git(project, "checkout", "-q", "-b", "release/2.0")
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the full-run flag is read from the invocation's user
    // properties. Here `.mvn/maven.config` passes it, so the command line does not.
    @Test
    fun `a full run asked for in maven config runs everything`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("full-run-requested"), twoClasses) { project ->
            File(project, ".mvn").mkdirs()
            File(project, ".mvn/maven.config").writeText("-Dyoriwake.fullRun\n")
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: `fullRunEveryBuilds` counts the builds since the last full run,
    // a narrowed selecting run among them. Here one selecting run narrows before the build that
    // falls due.
    @Test
    fun `the build after a narrowed one falls due under fullRunEveryBuilds`(@TempDir dir: File) {
        val pom = withYoriwake(minimalPom, "<fullRunEveryBuilds>2</fullRunEveryBuilds>")

        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("full-run-scheduled"), twoClasses, pom, primer = Primer(selecting)) {
            breakBeta(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: Surefire's in-JVM parallelism is looked for among the user
    // properties it promotes to the fork. Here `.mvn/maven.config` switches Jupiter's on.
    @Test
    fun `parallel execution switched on in maven config is declined`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("in-jvm-parallelism"), twoClasses) { project ->
            File(project, ".mvn").mkdirs()
            File(project, ".mvn/maven.config").writeText("-Djunit.jupiter.execution.parallel.enabled=true\n")
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: `jacoco.skip` is read as Maven resolves a parameter's default,
    // the project's properties last. Here a profile that a file's presence activates sets it, and
    // the file is ignored by git.
    @Test
    fun `jacoco skip set by a profile a file activates is declined`(@TempDir dir: File) {
        val pom = beforeEnd(
            minimalPom,
            "<profiles><profile><id>no-coverage</id><activation><file><exists>\${project.basedir}/no-coverage</exists></file></activation>" +
                "<properties><jacoco.skip>true</jacoco.skip></properties></profile></profiles>",
        )

        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Forced("jacoco-disabled"), twoClasses + (".gitignore" to "target/\nno-coverage\n"), pom,
        ) { project ->
            File(project, "no-coverage").writeText("")
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a selecting run runs everything above `largeChangeThreshold`
    // changed paths, new untracked files counted. Here the change is one path above it, two of its
    // three paths new files.
    @Test
    fun `one changed path above largeChangeThreshold runs everything`(@TempDir dir: File) {
        val pom = withYoriwake(minimalPom, "<largeChangeThreshold>2</largeChangeThreshold>")

        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("large-change"), twoClasses, pom) { project ->
            breakBeta(project)
            File(project, "src/main/java/dev/sample/Gamma.java").writeText("package dev.sample;\npublic class Gamma {}")
            File(project, "src/main/java/dev/sample/Delta.java").writeText("package dev.sample;\npublic class Delta {}")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a trusted-map list names a map by its directory name, compared
    // exactly. Here it names the map in other letter case, which the file system would open as the
    // same directory.
    @Test
    fun `a trusted-map list naming the map in other case runs everything`(@TempDir dir: File) {
        val list = File(dir, "trusted.tsv")

        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Forced("map-unverified"), twoClasses,
            select = selecting + "-Dyoriwake.trustedMaps=${list.path}",
        ) { project ->
            val map = File(project, ".yoriwake/yoriwake").listFiles()!!.single(File::isDirectory)
            val digest = File(map, "map-digest").readText().trim().substringAfter(' ')
            list.writeText("${map.name.uppercase()}\t$digest\n")
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the Apache build cache's switch is read from the invocation's
    // properties. Here `.mvn/maven.config` turned it off for the capture, and the change deletes
    // that file, so the cache is on.
    @Test
    fun `the Apache build cache turned on by deleting maven config declines`(@TempDir dir: File) {
        val cache = ".mvn/extensions.xml" to "<extensions><extension><groupId>org.apache.maven.extensions</groupId>" +
            "<artifactId>maven-build-cache-extension</artifactId><version>1.3.0</version></extension></extensions>\n"
        val off = ".mvn/maven.config" to "-Dmaven.build.cache.enabled=false\n"

        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Forced("maven-build-cache"), twoClasses + cache + off,
            select = selecting + "-Dmaven.build.cache.skipSave=true",
        ) { project ->
            File(project, ".mvn/maven.config").delete()
            breakBeta(project)
            // The cache outlasts this project: a source no earlier build saw keeps an entry an
            // earlier run of this attempt saved from answering for the full run.
            File(project, "src/main/java/dev/sample/Beta.java").appendText("\n// ${System.nanoTime()}\n")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the floor is a version range from 3.9.0; 3.8.8 is the newest
    // release below it.
    @Test
    fun `the newest Maven below the floor declines the build`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("maven-version"), twoClasses, maven = FunctionalMaven.BELOW_FLOOR) {
            breakBeta(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: before anything runs, a JaCoCo agent execution counts unless
    // it is bound to phase `none`. Here it is bound to `verify`, after the tests, so it passes that
    // check and only the argLine the fork gets can tell that no agent reaches it.
    @Test
    fun `a JaCoCo agent bound after the tests is declined`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("no-jacoco-plugin"), twoClasses) { project ->
            File(project, "pom.xml").edit {
                it.replacing("<goals><goal>prepare-agent</goal></goals>", "<phase>verify</phase><goals><goal>prepare-agent</goal></goals>")
            }
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    /** A module of [groupId]'s reactor with [dependencies] and the rest of its POM in [rest]. */
    private fun module(groupId: String, name: String, dependencies: String, rest: String = "") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent><groupId>$groupId</groupId><artifactId>sample</artifactId><version>1.0</version></parent>
          <artifactId>$name</artifactId>
          <dependencies>$dependencies</dependencies>
          $rest
        </project>
    """.trimIndent() + "\n"

    /** [root] as the parent POM of [modules]. */
    private fun reactor(root: String, vararg modules: String) = root.replacing(
        "<version>1.0</version>\n",
        "<version>1.0</version>\n  <packaging>pom</packaging>\n  <modules>${modules.joinToString("") { "<module>$it</module>" }}</modules>\n",
    )

    // Track: code reading. Boundary: a module of the reactor this invocation did not build is
    // found among the execution's resolved artifacts. Here it is reached only transitively,
    // through a module the invocation does build.
    @Test
    fun `a reactor module reached transitively from a repository is declined`(@TempDir dir: File) {
        val group = "dev.sample.fromrepo"
        val dependency = { name: String -> "<dependency><groupId>$group</groupId><artifactId>$name</artifactId><version>1.0</version></dependency>" }
        val alphaUser = source(
            "b/src/test/java/dev/sample/AlphaUserTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AlphaUserTest { @Test void doubles() { assertEquals(2, new Alpha().twice(1)); } }
            """,
        )
        val sources = listOf(
            "a/pom.xml" to module(group, "a", ""),
            "c/pom.xml" to module(group, "c", dependency("a")),
            "b/pom.xml" to module(group, "b", dependency("c")),
            "a/${oneClass.first}" to oneClass.second,
            "b/${secondClass.first}" to secondClass.second, "b/${secondTest.first}" to secondTest.second, alphaUser,
        )
        val root = reactor(minimalPom.replacing("<groupId>dev.sample</groupId>", "<groupId>$group</groupId>"), "a", "c", "b")
        try {
            val result = attempt(
                dir, "dev.sample.AlphaUserTest", Aim.Forced("reactor-module-from-repository"), sources, root,
                select = selecting + listOf("-pl", "b,c"),
                primer = Primer(listOf("install", "-DskipTests")),
            ) {
                File(it, "a/src/main/java/dev/sample/Alpha.java").writeText(
                    "package dev.sample;\npublic class Alpha { public int twice(int n) { return n * 2 + 1; } }",
                )
            }

            assertOutcome(Outcome.HELD_BY_FORCE, result)
        } finally {
            File(FunctionalMaven.localRepo, group.replace('.', '/')).deleteRecursively()
        }
    }

    // Track: code reading. Boundary: the map's capture commit must share history with the base.
    // Here the history is started again on an orphan branch holding the same files, so the capture
    // commit is still in the repository but related to nothing on it.
    @Test
    fun `a map captured before the history was started again refuses`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("stamp-unrelatable"), twoClasses) { project ->
            git(project, "checkout", "-q", "--orphan", "fresh")
            git(project, "add", "-A", "--", ".", ":(exclude).yoriwake")
            git(project, "commit", "-q", "-m", "fresh history")
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: `forkCount` is a number or a multiple of the processors with a
    // `C`; only a multiple of zero runs in Maven's own JVM. Here it is `0C`.
    @Test
    fun `forkCount 0C runs in Maven's own JVM and is declined`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("surefire-in-process"), twoClasses) { project ->
            File(project, "pom.xml").writeText(minimalPom.replace(surefireConfiguration, "<forkCount>0C</forkCount>"))
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a legacy provider is found among the Surefire plugin's
    // dependencies as Maven merged them. Here the JUnit 4.7 provider is declared in
    // pluginManagement only, over a JUnit 4 suite.
    @Test
    fun `a legacy provider declared in pluginManagement is declined`(@TempDir dir: File) {
        val pom = minimalPom
            .replacing(
                "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>",
                "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version><dependencies><dependency>" +
                    "<groupId>org.apache.maven.surefire</groupId><artifactId>surefire-junit47</artifactId><version>3.5.6</version>" +
                    "</dependency></dependencies></plugin>",
            )
            .replacing(
                "<groupId>org.junit.jupiter</groupId>\n      <artifactId>junit-jupiter</artifactId>",
                "<groupId>junit</groupId>\n      <artifactId>junit</artifactId>\n      <version>4.13.2</version>",
            )
        fun junit4(name: String, body: String) = source(
            "src/test/java/dev/sample/$name.java",
            "package dev.sample;\nimport org.junit.Test;\nimport static org.junit.Assert.assertEquals;\npublic class $name { @Test public void passes() { $body } }",
        )
        val sources = listOf(oneClass, secondClass, junit4("AlphaTest", "assertEquals(2, new Alpha().twice(1));"), junit4("BetaTest", "assertEquals(3, new Beta().thrice(1));"))

        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("surefire-legacy-provider"), sources, pom) { breakBeta(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: Surefire's version is compared as Maven orders versions, so a
    // milestone of 3.0.0 is older than 3.0.0. Here it is 3.0.0-M9, the last before the release.
    @Test
    fun `Surefire's last milestone before 3_0_0 is declined`(@TempDir dir: File) {
        val pom = minimalPom.replacing(
            "<artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>",
            "<artifactId>maven-surefire-plugin</artifactId><version>3.0.0-M9</version>",
        )

        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("surefire-version"), twoClasses, pom) { breakBeta(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: tests named for the run are read from the execution's `test`
    // parameter, its POM value before its user property. Here the POM names them, so no `-Dtest`
    // is passed.
    @Test
    fun `tests named in the POM's test parameter all run in a selecting run`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("tests-named"), twoClasses) { project ->
            File(project, "pom.xml").writeText(withSurefire(minimalPom, "<test>*Test</test>"))
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: only Develocity's workspace id itself is passed over as build
    // state, matched by its whole path. Here an untracked file beside it carries the id's name with
    // a suffix.
    @Test
    fun `a file beside Develocity's workspace id named like it is an unmappable path`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("unmappable-paths"), twoClasses) { project ->
            File(project, ".mvn/.develocity").mkdirs()
            File(project, ".mvn/.develocity/develocity-workspace-id.old").writeText("an older id")
            breakBeta(project)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: MV7. With Develocity loaded, a selecting run narrows and a complement
    // runs only what it left out, each storing into Develocity's local build cache. A file BetaTest
    // reads by path, which no build input names and git ignores, then changes (`.mvn/.develocity/`
    // is ignored too, or committing the change would track Develocity's workspace id), and a full
    // run asked for on the same classes and configuration must run every test itself rather than
    // take the complement's result.
    @Test
    fun `a full run after a narrowed complement with Develocity's cache runs every test itself`(@TempDir dir: File) {
        val clean = listOf("clean", "verify")
        val betaTest = source(
            "src/test/java/dev/sample/BetaTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest {
                @Test void passes() throws Exception { assertEquals(3, new Beta().thrice(1)); assertEquals("a", Files.readString(Paths.get("data.txt"))); }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Forced("full-run-requested"),
            listOf(oneClass, oneTest, secondClass, betaTest, ".gitignore" to "target/\ndata.txt\n.mvn/.develocity/\n", "data.txt" to "a") + develocity,
            select = clean + listOf("-Dyoriwake.select", "-Dyoriwake.base=HEAD", "-Dyoriwake.fullRun"),
            primer = Primer(clean + selecting.drop(1)) { project, output ->
                check("selecting against " in output) { output }
                val complement = FunctionalMaven.run(defaultMaven, project, *clean.toTypedArray(), "-Dyoriwake.complement", "-Dmaven.test.failure.ignore=true").output
                check("leaving out the 1 tests" in complement) { complement }
                File(project, "data.txt").writeText("b")
            },
            commit = true,
        ) { project ->
            File(project, "src/main/java/dev/sample/Beta.java").writeText(
                "package dev.sample;\npublic class Beta { public int thrice(int n) { return n + n + n; } }",
            )
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: MV30. A module whose only sources are its tests, in a reactor that
    // declares no main source at all, and a JaCoCo configuration with no includes: no scope can be
    // derived, and the execution is declined.
    @Test
    fun `a reactor whose sources declare no package is declined`(@TempDir dir: File) {
        val helper = "src/test/java/dev/sample/Helper.java"
        val sources = listOf(
            helper to "package dev.sample;\nclass Helper { int twice(int n) { return n * 2; } }",
            source(
                "src/test/java/dev/sample/AlphaTest.java",
                "package dev.sample;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nclass AlphaTest { @Test void passes() { assertEquals(1, 1); } }",
            ),
            source(
                "src/test/java/dev/sample/HelperTest.java",
                "package dev.sample;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nclass HelperTest { @Test void passes() { assertEquals(2, new Helper().twice(1)); } }",
            ),
        )

        val result = attempt(dir, "dev.sample.HelperTest", Aim.Forced("no-packages-derived"), sources) { project ->
            File(project, helper).writeText("package dev.sample;\nclass Helper { int twice(int n) { return n * 2 + 1; } }")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: MV31. A file whose name holds U+FFFD, ignored by git, created after the
    // capture: the working tree cannot be listed against the capture's, so no change set can be
    // computed, and the selecting run refuses and runs everything.
    @Test
    fun `a file whose name cannot be listed, created after the capture, leaves no change set`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Forced("no-change-set"), twoClasses + ("fixtures/.gitignore" to "*.tmp\n"),
        ) { project ->
            breakBeta(project)
            File(project, "fixtures/\uFFFD.tmp").writeText("x")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: an edit to another module is dropped for an execution that
    // neither has it on its classpath nor names it by coordinates or by a path in its fork's
    // arguments, added classpath, system property variables or environment. Here a test loads a
    // sibling's classes from a path Surefire hands it through the deprecated `<systemProperties>`.
    @Test
    fun `a sibling's classes loaded from a path in Surefire's systemProperties keep its edits`(@TempDir dir: File) {
        val loader = source(
            "b/src/test/java/dev/sample/LoaderTest.java",
            """
            package dev.sample;
            import java.io.File;
            import java.net.URL;
            import java.net.URLClassLoader;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class LoaderTest {
                @Test void doubles() throws Exception {
                    File classes = new File(System.getProperty("alpha.classes"));
                    try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toURI().toURL() }, LoaderTest.class.getClassLoader())) {
                        Class<?> alpha = loader.loadClass("dev.sample.Alpha");
                        assertEquals(2, alpha.getMethod("twice", int.class).invoke(alpha.getConstructor().newInstance(), 1));
                    }
                }
            }
            """,
        )
        val b = module(
            "dev.sample", "b", "",
            "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration>" +
                "<systemProperties><alpha.classes>\${project.basedir}/../a/target/classes</alpha.classes></systemProperties>" +
                "</configuration></plugin></plugins></build>",
        )
        val sources = listOf(
            "a/pom.xml" to module("dev.sample", "a", ""),
            "b/pom.xml" to b,
            "a/${oneClass.first}" to oneClass.second, "a/${oneTest.first}" to oneTest.second,
            "b/${secondClass.first}" to secondClass.second, "b/${secondTest.first}" to secondTest.second, loader,
        )

        val result = attempt(dir, "dev.sample.LoaderTest", Aim.Row("reaches-change"), sources, reactor(minimalPom, "a", "b")) {
            File(it, "a/src/main/java/dev/sample/Alpha.java").writeText(
                "package dev.sample;\npublic class Alpha { public int twice(int n) { return n * 2 + 1; } }",
            )
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: JaCoCo's scope is derived from the package declarations of the
    // reactor's main sources; a file that holds no code (an empty or wholly commented-out one)
    // declares nothing and does not widen the scope. A reactor whose only main source is a retired,
    // commented-out class derives no package, and the execution is declined.
    @Test
    fun `a reactor whose only main source is commented out derives no package and is declined`(@TempDir dir: File) {
        val helper = "src/test/java/dev/sample/Helper.java"
        val sources = listOf(
            "src/main/java/dev/sample/Retired.java" to "// package dev.sample;\n// public class Retired { }\n",
            helper to "package dev.sample;\nclass Helper { int twice(int n) { return n * 2; } }",
            source(
                "src/test/java/dev/sample/AlphaTest.java",
                "package dev.sample;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nclass AlphaTest { @Test void passes() { assertEquals(1, 1); } }",
            ),
            source(
                "src/test/java/dev/sample/HelperTest.java",
                "package dev.sample;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nclass HelperTest { @Test void passes() { assertEquals(2, new Helper().twice(1)); } }",
            ),
        )

        val result = attempt(dir, "dev.sample.HelperTest", Aim.Forced("no-packages-derived"), sources) { project ->
            File(project, helper).writeText("package dev.sample;\nclass Helper { int twice(int n) { return n * 2 + 1; } }")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the working tree's listing reads the tracked files that differ
    // from HEAD as well as the untracked ones, and a name that decoded to U+FFFD stops it, since it
    // would read as a file that is always absent. Here the name is committed with the base, clean
    // at the capture, and edited afterwards, so it reaches the listing as a changed tracked path.
    @Test
    fun `a tracked file whose name cannot be listed, edited after the capture, leaves no change set`(@TempDir dir: File) {
        val odd = "fixtures/\uFFFD.txt"
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("no-change-set"), twoClasses + (odd to "x\n")) { project ->
            breakBeta(project)
            File(project, odd).writeText("y\n")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }
}
