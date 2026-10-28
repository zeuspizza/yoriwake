package io.github.zeuspizza.yoriwake.maven.breaking

import io.github.zeuspizza.yoriwake.gradle.breaking.Aim
import io.github.zeuspizza.yoriwake.gradle.breaking.Observed
import io.github.zeuspizza.yoriwake.gradle.breaking.Result
import io.github.zeuspizza.yoriwake.gradle.breaking.classify
import io.github.zeuspizza.yoriwake.gradle.breaking.decisions
import io.github.zeuspizza.yoriwake.gradle.breaking.forcedKinds
import io.github.zeuspizza.yoriwake.gradle.breaking.passedIn
import io.github.zeuspizza.yoriwake.maven.FunctionalMaven
import java.io.File

/**
 * An attempt to make a Maven selecting run skip a test that should have run, and what became of
 * it, proved and classified exactly as on Gradle: the change must fail the target when every test
 * runs, the selecting run must have left something out or recorded why it could not, and a run
 * that held must have held on the verdict the attempt aimed at.
 *
 * A decline counts as forced: the execution runs every test as configured and says so in a
 * `declined, <token>:` line, which is where its kind is read.
 */
abstract class MavenBreakAttempt {

    /** The Maven every attempt runs on unless it names another, as the functional suite does. */
    protected val defaultMaven = FunctionalMaven.MATRIX[1]

    /** The selecting run's arguments. */
    protected val selecting = listOf("verify", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")

    /**
     * A build run after the change and before the selecting run, with [arguments]; [after] sees
     * the project and the build's output once it has run.
     */
    class Primer(val arguments: List<String>, val after: (project: File, output: String) -> Unit = { _, _ -> })

    /** Surefire's configuration in [minimalPom]: classes run by name, each in a JVM of its own. */
    protected val surefireConfiguration = "<runOrder>alphabetical</runOrder><reuseForks>false</reuseForks>"

    protected val minimalPom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>dev.sample</groupId>
          <artifactId>sample</artifactId>
          <version>1.0</version>
          <properties>
            <maven.compiler.release>17</maven.compiler.release>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
          </properties>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>org.junit</groupId>
                <artifactId>junit-bom</artifactId>
                <version>5.11.4</version>
                <type>pom</type>
                <scope>import</scope>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.junit.jupiter</groupId>
              <artifactId>junit-jupiter</artifactId>
              <scope>test</scope>
            </dependency>
          </dependencies>
          <build>
            <pluginManagement>
              <plugins>
                <plugin><artifactId>maven-clean-plugin</artifactId><version>3.4.0</version></plugin>
                <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
                <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                <plugin><artifactId>maven-install-plugin</artifactId><version>3.1.3</version></plugin>
              </plugins>
            </pluginManagement>
            <plugins>
              <plugin>
                <groupId>io.github.zeuspizza</groupId>
                <artifactId>yoriwake-maven-plugin</artifactId>
                <version>${'$'}{yoriwake.version}</version>
                <extensions>true</extensions>
              </plugin>
              <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.12</version>
                <executions>
                  <execution>
                    <id>prepare-agent</id>
                    <goals><goal>prepare-agent</goal></goals>
                  </execution>
                </executions>
              </plugin>
              <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>$surefireConfiguration</configuration>
              </plugin>
            </plugins>
          </build>
        </project>
    """.trimIndent() + "\n"

    protected val oneTest = "src/test/java/dev/sample/AlphaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AlphaTest {
            @Test void passes() { assertEquals(2, new Alpha().twice(1)); }
        }
    """.trimIndent()

    protected val oneClass = "src/main/java/dev/sample/Alpha.java" to """
        package dev.sample;
        public class Alpha { public int twice(int n) { return n * 2; } }
    """.trimIndent()

    protected val secondClass = "src/main/java/dev/sample/Beta.java" to """
        package dev.sample;
        public class Beta { public int thrice(int n) { return n * 3; } }
    """.trimIndent()

    protected val secondTest = "src/test/java/dev/sample/BetaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class BetaTest {
            @Test void passes() { assertEquals(3, new Beta().thrice(1)); }
        }
    """.trimIndent()

    /**
     * A documented limit: a file under the build directory, off the test class path, read by
     * path, is build state and never a change. The resources plugin copies Alpha's source there,
     * so a change to Alpha reaches [reader] through it, and ReaderTest, in a JVM of its own, never
     * touches Alpha.
     */
    protected val copyingPom = minimalPom.replace(
        "\n    </plugins>\n  </build>",
        """
          <plugin>
            <artifactId>maven-resources-plugin</artifactId>
            <executions>
              <execution>
                <id>copy-alpha</id>
                <phase>process-test-resources</phase>
                <goals><goal>copy-resources</goal></goals>
                <configuration>
                  <outputDirectory>${'$'}{project.build.directory}/copied</outputDirectory>
                  <resources><resource><directory>src/main/java/dev/sample</directory><includes><include>Alpha.java</include></includes></resource></resources>
                </configuration>
              </execution>
            </executions>
          </plugin>
        """.trimIndent().prependIndent("      ").let { "\n$it\n    </plugins>\n  </build>" },
    ).also { check(it != minimalPom) { "the copying POM did not find where its plugins end" } }

    /** Reads the copy of Alpha's source [copyingPom] leaves under `target/copied`. */
    protected val reader = "src/test/java/dev/sample/ReaderTest.java" to """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        class ReaderTest {
            @Test void doubles() throws Exception {
                assertTrue(Files.readString(Paths.get("target/copied/Alpha.java")).contains("n * 2"));
            }
        }
    """.trimIndent()

    /**
     * Writes [pom] and [sources] into a project under [dir], commits them, records the map with
     * `verify` and checks that [target] (a test class holding one test method) passes; applies
     * [change] (committed when [commit] says so), runs [primer] if there is one, runs a selecting
     * build with [select], then `verify` with yoriwake and the build caches off to learn whether the
     * change fails the target at all, and classifies what the selecting build did. Every build runs
     * on [maven]. A `.gitignore` among [sources] replaces the one that ignores `target/`.
     */
    protected fun attempt(
        dir: File,
        target: String,
        aim: Aim,
        sources: List<Pair<String, String>>,
        pom: String = minimalPom,
        maven: String = defaultMaven,
        select: List<String> = selecting,
        primer: Primer? = null,
        commit: Boolean = false,
        change: (File) -> Unit,
    ): Result {
        val project = File(dir, "project")
        write(project, ".gitignore" to "target/\n", "pom.xml" to pom, *sources.toTypedArray())
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "base")
        FunctionalMaven.run(maven, project, "verify").succeeded()
        val captured = ranTests(project)
        check(passed(project, target) == true) { "$target did not pass when the map was recorded; ran $captured" }
        // Selection keeps or skips each method, but a report speaks for the whole class: a skipped
        // method would hide behind a sibling that ran and passed.
        val targetCases = Regex("<testcase ").findAll(reports(project).first { it.name == "TEST-$target.xml" }.readText()).count()
        check(targetCases == 1) {
            "$target ran $targetCases test cases; a target holds one test method, so a skipped one cannot hide behind a sibling that ran"
        }

        deleteReports(project)
        change(project)
        check(git(project, "status", "--porcelain", "--", ".", ":(exclude).yoriwake").isNotBlank()) {
            "the change left the working tree as it was"
        }
        if (commit) {
            git(project, "add", "-A", "--", ".", ":(exclude).yoriwake")
            git(project, "commit", "-q", "-m", "the change")
        }

        primer?.let {
            val output = FunctionalMaven.run(maven, project, *it.arguments.toTypedArray()).output
            it.after(project, output)
            deleteReports(project)
        }

        val output = FunctionalMaven.run(maven, project, *select.toTypedArray()).output
        val selected = ranTests(project)
        val targetResult = passed(project, target)
        val (notes, rows) = decisions(File(project, ".yoriwake/yoriwake"), target)

        deleteReports(project)
        FunctionalMaven.run(maven, project, "verify", "-Dyoriwake.enabled=false", *CACHES_OFF)
        val failsUnderFullRun = passed(project, target) == false

        val declined = DECLINED.findAll(output).map { match -> match.groupValues.drop(1).first(String::isNotEmpty) }.toList()
        return classify(
            Observed(
                targetFailsUnderFullRun = failsUnderFullRun,
                targetRan = targetResult != null,
                targetFailed = targetResult == false,
                leftOut = captured - selected,
                forcedKinds = (forcedKinds(notes) + declined).distinct(),
                targetRows = rows,
            ),
            aim,
        )
    }

    private fun write(project: File, vararg files: Pair<String, String>) {
        for ((path, text) in files) File(project, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    /** Every Surefire and Failsafe report of the project's modules. */
    private fun reports(project: File): List<File> =
        project.walkTopDown().onEnter { it.name != ".git" && it.name != ".yoriwake" }
            .filter { it.isFile && it.name.startsWith("TEST-") && it.name.endsWith(".xml") }
            .filter { it.parentFile.name in REPORT_DIRECTORIES && it.parentFile.parentFile.name == "target" }
            .toList()

    private fun deleteReports(project: File) {
        reports(project).map { it.parentFile }.distinct().forEach { it.deleteRecursively() }
    }

    /** The test classes some report shows ran, skipped classes left out. */
    private fun ranTests(project: File): Set<String> =
        reports(project).filter { passedIn(it) != null }.map { it.name.removePrefix("TEST-").removeSuffix(".xml") }.toSet()

    private fun passed(project: File, testClass: String): Boolean? =
        reports(project).filter { it.name == "TEST-$testClass.xml" }.map(::passedIn).let { results ->
            if (results.all { it == null }) null else results.none { it == false }
        }

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    private companion object {
        val REPORT_DIRECTORIES = setOf("surefire-reports", "failsafe-reports")

        /** An execution's decline, and Maven's own, which declines every execution of the build. */
        val DECLINED = Regex("""\[yoriwake] [^\n]* declined, ([a-z0-9-]+):|\[yoriwake] (maven-version):""")

        /** A result one of them restored would answer for the run instead of the change. */
        val CACHES_OFF = arrayOf("-Ddevelocity.cache.local.enabled=false", "-Ddevelocity.cache.remote.enabled=false")
    }
}
