package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A licence check that walks the root module's tree sees `.yoriwake/`: the build names the exclude
 * to add, and with it the check passes on a recording build and on a selecting build after it.
 */
class LicenceGoalFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]
    private val warning = "[yoriwake] com.mycila:license-maven-plugin lists every file under "

    private fun git(project: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
    }

    /**
     * The demo, committed, its root running the licence plugin's `check` with `failIfUnknown`, given
     * [excludes]. The check lists only `.yoriwake/`, so its outcome is the state directory's alone.
     */
    private fun demo(dir: File, excludes: String): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        val pom = File(project, "pom.xml")
        pom.writeText(
            pom.readText().replace(
                "</extensions>\n      </plugin>",
                """</extensions>
      </plugin>
      <plugin>
        <groupId>com.mycila</groupId>
        <artifactId>license-maven-plugin</artifactId>
        <version>5.1.2</version>
        <inherited>false</inherited>
        <configuration>
          <failIfUnknown>true</failIfUnknown>
          <licenseSets><licenseSet>
            <inlineHeader>Copyright demo</inlineHeader>
            <includes><include>.yoriwake/**</include></includes>
            $excludes
          </licenseSet></licenseSets>
        </configuration>
        <executions><execution><phase>validate</phase><goals><goal>check</goal></goals></execution></executions>
      </plugin>""",
            ),
        )
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
    }

    @Test
    fun `a licence check that walks the tree passes once it excludes the state directory`(@TempDir dir: File) {
        val project = demo(dir, "<excludes><exclude>.yoriwake/**</exclude></excludes>")

        val recording = FunctionalMaven.run(maven, project, "verify").succeeded().output
        assertTrue(File(project, ".yoriwake/yoriwake").isDirectory, recording)
        val greeter = File(project, "lib/src/main/java/dev/demo/lib/Greeter.java")
        greeter.writeText(greeter.readText().replace("\"Hello, \"", "\"Hello,\" + \" \""))
        git(project, "commit", "-q", "-am", "an edit")
        val selecting = FunctionalMaven.run(maven, project, "verify", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1").succeeded().output

        for (output in listOf(recording, selecting)) assertFalse(warning in output, output)
        assertContains(selecting, "[yoriwake] lib surefire:test (default-test) selecting against ")
    }

    @Test
    fun `a licence plugin without the exclude is named with the exclude to add`(@TempDir dir: File) {
        val project = demo(dir, "")

        val result = FunctionalMaven.run(maven, project, "verify")

        assertNotEquals(0, result.exitCode, result.output)
        assertContains(result.output, "Failed to execute goal com.mycila:license-maven-plugin:5.1.2:check")
        val line = result.output.lines().single { warning in it }
        assertContains(line, "add <exclude>.yoriwake/**</exclude> to its configuration")
        assertTrue(result.output.indexOf(warning) < result.output.indexOf("--- license:5.1.2:check"), result.output)
    }
}
