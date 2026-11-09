package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.change.CoordinateDiff.Shape
import io.github.zeuspizza.yoriwake.gradle.change.CoordinateDiff.Verdict
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail

class CoordinateDiffTest {

    /** A `-U0` diff turning each line of [old] that differs into the matching line of [new]. */
    private fun diff(old: String, new: String): List<String> {
        val before = old.lines()
        val after = new.lines()
        val changed = before.indices.filter { before[it] != after[it] }
        require(before.size == after.size && changed.isNotEmpty())
        return listOf("diff --git a/f b/f", "--- a/f", "+++ b/f") + changed.flatMap { i ->
            listOf("@@ -${i + 1} +${i + 1} @@", "-${before[i]}", "+${after[i]}")
        }
    }

    private fun classify(shape: Shape, old: String, new: String, path: String = "f") =
        CoordinateDiff.classify(path, shape, diff(old, new), old, new)

    private fun accepted(verdict: Verdict) = assertIs<Verdict.Accepted>(verdict).bumps

    private val catalog = """
        [versions]
        lib-json = "1.0"
        kotlin = "2.0.0"

        [libraries]
        lib-json = { module = "dev.lib:lib-json", version.ref = "lib-json" }
        lib-math = { module = "dev.lib:lib-math", version = "1.0" }
        lib-text = { group = "dev.lib", name = "lib-text", version = "1.0" }
        lib-old = "dev.lib:lib-old:1.0"

        [plugins]
        kotlin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
        spotless = { id = "com.diffplug.spotless", version = "6.0" }
    """.trimIndent()

    @Test
    fun `a catalog version value bump is accepted with its key`() {
        val bumps = accepted(classify(Shape.CATALOG, catalog, catalog.replace("lib-json = \"1.0\"", "lib-json = \"1.1\"")))
        assertEquals(listOf("lib-json"), bumps.map { it.versionKey })
    }

    @Test
    fun `a library's own version, in either table form or a notation, is accepted with its coordinate`() {
        val bumped = catalog
            .replace("lib-math\", version = \"1.0\"", "lib-math\", version = \"1.1\"")
            .replace("name = \"lib-text\", version = \"1.0\"", "name = \"lib-text\", version = \"2.0\"")
            .replace("dev.lib:lib-old:1.0", "dev.lib:lib-old:1.2")
        val bumps = accepted(classify(Shape.CATALOG, catalog, bumped))
        assertEquals(listOf("dev.lib:lib-math", "dev.lib:lib-text", "dev.lib:lib-old"), bumps.map { it.coordinate })
        assertEquals(listOf("lib-math", "lib-text", "lib-old"), bumps.map { it.alias })
    }

    @Test
    fun `a version ref renamed is refused, since it is no value change`() {
        assertIs<Verdict.Refused>(classify(Shape.CATALOG, catalog, catalog.replace("version.ref = \"lib-json\"", "version.ref = \"kotlin\"")))
    }

    @Test
    fun `a plugin version is refused, and a key a plugin shares is accepted here and left to the rule after`() {
        assertIs<Verdict.Refused>(classify(Shape.CATALOG, catalog, catalog.replace("version = \"6.0\"", "version = \"6.1\"")))
        assertEquals(
            listOf("kotlin"),
            accepted(classify(Shape.CATALOG, catalog, catalog.replace("kotlin = \"2.0.0\"", "kotlin = \"2.0.1\""))).map { it.versionKey },
        )
    }

    @Test
    fun `a hunk that also changes a comment, or changes a module, is refused`() {
        val commented = catalog.replace("lib-json = \"1.0\"", "lib-json = \"1.0\" # pinned")
        assertIs<Verdict.Refused>(classify(Shape.CATALOG, commented, commented.replace("\"1.0\" # pinned", "\"1.1\" # moved")))
        assertIs<Verdict.Refused>(classify(Shape.CATALOG, catalog, catalog.replace("dev.lib:lib-old:1.0", "dev.lib:lib-new:1.0")))
    }

    @Test
    fun `a rich version or an interpolated one is refused`() {
        assertIs<Verdict.Refused>(classify(Shape.CATALOG, catalog, catalog.replace("lib-json = \"1.0\"", "lib-json = \"[1.0,2.0)\"")))
        assertIs<Verdict.Refused>(classify(Shape.SCRIPT, script, script.replace("dev.lib:lib-json:1.0", "dev.lib:lib-json:\$v")))
    }

    private val script = """
        buildscript {
            dependencies {
                classpath("dev.tools:tool:1.0")
            }
        }
        val v = "1.0"
        dependencies {
            implementation("dev.lib:lib-json:1.0")
            agent("dev.lib:agent:1.0")
            testImplementation(platform("dev.lib:bom:1.0"))
        }
    """.trimIndent()

    @Test
    fun `a notation bump in dependencies is accepted with its configuration`() {
        val bumps = accepted(classify(Shape.SCRIPT, script, script.replace("lib-json:1.0", "lib-json:1.1")))
        assertEquals(listOf("dev.lib:lib-json" to "implementation"), bumps.map { it.coordinate to it.configuration })
        val agent = accepted(classify(Shape.SCRIPT, script, script.replace("agent:1.0", "agent:2.0")))
        assertEquals(listOf("agent"), agent.map { it.configuration })
        val bom = accepted(classify(Shape.SCRIPT, script, script.replace("bom:1.0", "bom:2.0")))
        assertEquals(listOf("dev.lib:bom" to "testImplementation"), bom.map { it.coordinate to it.configuration })
    }

    @Test
    fun `the same bump in buildscript, or a version held in a variable, is refused`() {
        assertIs<Verdict.Refused>(classify(Shape.SCRIPT, script, script.replace("tool:1.0", "tool:1.1")))
        assertIs<Verdict.Refused>(classify(Shape.SCRIPT, script, script.replace("val v = \"1.0\"", "val v = \"1.1\"")))
    }

    @Test
    fun `a line moved into another block is refused`() {
        val old = "dependencies {\n    implementation(\"a:b:1.0\")\n}\nother {\n}"
        val new = "dependencies {\n}\nother {\n    implementation(\"a:b:1.1\")\n}"
        val lines = listOf("@@ -2 +4 @@", "-    implementation(\"a:b:1.0\")", "+    implementation(\"a:b:1.1\")")
        assertIs<Verdict.Refused>(CoordinateDiff.classify("build.gradle.kts", Shape.SCRIPT, lines, old, new))
    }

    @Test
    fun `lockfiles, verification metadata, settings and build logic have no shape`() {
        assertNull(CoordinateDiff.shapeOf("gradle.lockfile"))
        assertNull(CoordinateDiff.shapeOf("app/gradle.lockfile"))
        assertNull(CoordinateDiff.shapeOf("buildscript-gradle.lockfile"))
        assertNull(CoordinateDiff.shapeOf("gradle/verification-metadata.xml"))
        assertNull(CoordinateDiff.shapeOf("settings.gradle.kts"))
        assertNull(CoordinateDiff.shapeOf("buildSrc/build.gradle.kts"))
        assertNull(CoordinateDiff.shapeOf("build-logic/convention/build.gradle.kts"))
        assertNull(CoordinateDiff.shapeOf("plugins/build.gradle.kts", includedBuilds = listOf("plugins")))
        assertEquals(Shape.SCRIPT, CoordinateDiff.shapeOf("app/build.gradle.kts"))
        assertEquals(Shape.CATALOG, CoordinateDiff.shapeOf("gradle/libs.versions.toml"))
    }

    @Test
    fun `a new or deleted file is refused`() {
        val lines = listOf("diff --git a/f b/f", "new file mode 100644", "@@ -0,0 +1 @@", "+x")
        assertIs<Verdict.Refused>(CoordinateDiff.classify("f", Shape.CATALOG, lines, "", "x"))
    }

    private val pom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>dev.sample</groupId>
            <artifactId>parent</artifactId>
            <version>1.0</version>
          </parent>
          <artifactId>app</artifactId>
          <properties>
            <lib.json.version>1.0</lib.json.version>
          </properties>
          <dependencies>
            <!-- a comment -->
            <dependency>
              <groupId>dev.lib</groupId>
              <artifactId>lib-json</artifactId>
              <version>${'$'}{lib.json.version}</version>
            </dependency>
            <dependency>
              <groupId>dev.lib</groupId>
              <artifactId>lib-math</artifactId>
              <version>1.0</version>
            </dependency>
          </dependencies>
          <build>
            <plugins>
              <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.0</version>
              </plugin>
            </plugins>
          </build>
        </project>
    """.trimIndent()

    @Test
    fun `a POM dependency version or property bump is accepted, with its coordinate or key`() {
        val dependency = accepted(classify(Shape.POM, pom, pom.replace("<version>1.0</version>\n    </dependency>", "<version>1.1</version>\n    </dependency>")))
        assertEquals(listOf("dev.lib:lib-math"), dependency.map { it.coordinate })
        val property = accepted(classify(Shape.POM, pom, pom.replace("<lib.json.version>1.0<", "<lib.json.version>1.1<")))
        assertEquals(listOf("lib.json.version"), property.map { it.versionKey })
    }

    @Test
    fun `a POM's plugin or parent version, or a version turned into a property, is refused`() {
        assertIs<Verdict.Refused>(classify(Shape.POM, pom, pom.replace("<version>3.5.0</version>", "<version>3.5.1</version>")))
        assertIs<Verdict.Refused>(classify(Shape.POM, pom, pom.replace("<artifactId>parent</artifactId>\n    <version>1.0</version>", "<artifactId>parent</artifactId>\n    <version>1.1</version>")))
        assertIs<Verdict.Refused>(classify(Shape.POM, pom, pom.replace("<version>1.0</version>\n    </dependency>", "<version>${'$'}{v}</version>\n    </dependency>")))
    }

    @Test
    fun `on Maven only a pom xml has a shape`() {
        assertEquals(Shape.POM, CoordinateDiff.shapeOf("app/pom.xml", maven = true))
        assertNull(CoordinateDiff.shapeOf("gradle/libs.versions.toml", maven = true))
        assertNull(CoordinateDiff.shapeOf("app/pom.xml"))
    }

    /** Real bump commits, and real build-file commits that are not one, each named in `index.tsv`. */
    @Test
    fun `the golden set of real commits classifies as recorded`() {
        val index = resource("index.tsv").lines().filter(String::isNotBlank)
        for (row in index) {
            val (expected, name, _, _, path) = row.split('\t')
            val shape = CoordinateDiff.shapeOf(path) ?: fail("$name: $path has no shape")
            val verdict = CoordinateDiff.classify(path, shape, resource("$name.diff").lines(), resource("$name.old"), resource("$name.new"))
            val got = if (verdict is Verdict.Accepted) "accepted" else "refused"
            assertEquals(expected, got, "$name ($path): $verdict")
        }
    }

    private fun resource(name: String): String =
        javaClass.getResource("/coordinate-diff/$name")?.readText() ?: fail("no resource $name")
}
