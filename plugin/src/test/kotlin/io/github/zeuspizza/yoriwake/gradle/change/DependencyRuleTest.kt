package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.change.DependencyRule.Verdict
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DependencyRuleTest {

    @TempDir
    lateinit var root: File

    @TempDir
    lateinit var mapDir: File

    private val catalog = """
        [versions]
        lib-json = "1.0"
        kotlin = "2.0.0"

        [libraries]
        lib-json = { module = "dev.lib:lib-json", version.ref = "lib-json" }
        lib-math = { module = "dev.lib:lib-math", version = "1.0" }
        lib-agent = { module = "dev.lib:lib-agent", version = "1.0" }

        [plugins]
        kotlin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
    """.trimIndent()

    private val script = """
        dependencies {
            implementation(libs.lib.json)
            implementation("dev.lib:lib-math:1.0")
            agent("dev.lib:lib-agent:1.0")
        }
    """.trimIndent()

    private fun git(vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")}: $output" }
    }

    private fun write(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun jar(path: String, vararg entries: String): File {
        val file = File(root, path).apply { parentFile.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { zip.putNextEntry(ZipEntry(it)); zip.write(path.toByteArray()); zip.closeEntry() }
        }
        return file
    }

    private lateinit var jsonOld: File
    private lateinit var jsonNew: File
    private lateinit var math: File
    private lateinit var own: File

    @BeforeEach
    fun repository() {
        git("init", "-q")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        git("config", "commit.gpgsign", "false")
        write("gradle/libs.versions.toml", catalog)
        write("app/build.gradle.kts", script)
        write("settings.gradle.kts", "include(\"app\")\n")
        git("add", "-A")
        git("commit", "-q", "-m", "base")
        jsonOld = jar("repo/lib-json-1.0.jar", "lib/json/Json.class")
        jsonNew = jar("repo/lib-json-1.1.jar", "lib/json/Json.class", "lib/json/Extra.class")
        math = jar("repo/lib-math-1.0.jar", "lib/math/Add.class")
        own = File(root, "app/build/classes").apply { mkdirs() }
    }

    private val identities get() = mapOf(
        jsonOld to "dev.lib:lib-json", jsonNew to "dev.lib:lib-json", math to "dev.lib:lib-math",
    )

    private fun record(vararg classpath: File, identities: Map<File, String> = this.identities) =
        ClasspathRecord.of(classpath.toList(), identities, listOf(File(root, "app/build").path), root, emptyMap())

    private fun recordInMap(record: ClasspathRecord.Record) =
        ClasspathRecord.write(File(mapDir, ClasspathRecord.FILE), File(mapDir, ClasspathRecord.DIRS_FILE), record)

    private fun decide(
        paths: List<String>,
        current: ClasspathRecord.Record?,
        jvmArguments: List<String> = emptyList(),
        configurations: Set<String>? = setOf("implementation", "runtimeClasspath", "testRuntimeClasspath"),
    ) = DependencyRule.decide(
        DependencyRule.Inputs(
            root, paths, "HEAD", mapDir,
            DependencyRule.Context(
                git = ChangeDetection.directRunner(root),
                raw = { ChangeDetection.rawGit(root, it) },
                current = { current },
                jvmArguments = { jvmArguments },
                configurations = { configurations },
                ownBuildFile = { it == "app/build.gradle.kts" },
            ),
        )
    )

    private fun bumpJson() = write("gradle/libs.versions.toml", catalog.replace("lib-json = \"1.0\"", "lib-json = \"1.1\""))

    @Test
    fun `a catalog bump is accepted, naming the moved module and the directories its names changed`() {
        recordInMap(record(own, jsonOld, math))
        bumpJson()

        val verdict = assertIs<Verdict.Accepted>(decide(listOf("gradle/libs.versions.toml"), record(own, jsonNew, math)))

        assertEquals(listOf("gradle/libs.versions.toml"), verdict.paths)
        assertEquals(listOf("dev.lib:lib-json"), verdict.modules)
        assertEquals(listOf("lib/json/"), verdict.directories)
        assertEquals(true, verdict.discount("repo/lib-json-1.0.jar!/lib/json/Json.class"))
        assertEquals(true, verdict.discount("repo/lib-json-1.1.jar!/lib/json/Extra.class"))
        assertEquals(true, verdict.discount(ClasspathFiles.DEPENDENCY_ORDER))
        assertEquals(false, verdict.discount("repo/lib-math-1.0.jar!/lib/math/Add.class"))
    }

    @Test
    fun `a change set without a build file is none of this rule's`() {
        assertEquals(Verdict.NotApplicable, decide(listOf("app/src/main/java/A.java"), null))
    }

    @Test
    fun `a key a plugin shares stays a path coverage cannot see, naming it`() {
        write("gradle/libs.versions.toml", catalog.replace("kotlin = \"2.0.0\"", "kotlin = \"2.0.1\""))

        val verdict = assertIs<Verdict.Unaccepted>(decide(listOf("gradle/libs.versions.toml"), null))
        assertContains(verdict.why, "plugin")
    }

    @Test
    fun `a version a build script reads as a value stays unaccepted, naming the script`() {
        write("app/build.gradle.kts", "$script\nval v = libs.versions.lib.json.get()\n")
        git("add", "-A")
        git("commit", "-q", "-m", "reads")
        bumpJson()

        val verdict = assertIs<Verdict.Unaccepted>(decide(listOf("gradle/libs.versions.toml"), null))
        assertContains(verdict.why, "app/build.gradle.kts")

        write("app/build.gradle.kts", "$script\nval v = versionCatalog.findVersion(\"lib-json\")\n")
        assertIs<Verdict.Unaccepted>(decide(listOf("gradle/libs.versions.toml"), null))
    }

    @Test
    fun `a library alias named in settings or a buildscript block stays unaccepted`() {
        bumpJson()
        write("settings.gradle.kts", "include(\"app\")\nval x = libs.lib.json\n")
        assertIs<Verdict.Unaccepted>(decide(listOf("gradle/libs.versions.toml"), null))

        write("settings.gradle.kts", "include(\"app\")\n")
        write("build.gradle.kts", "buildscript {\n    dependencies { classpath(libs.lib.json) }\n}\n")
        assertIs<Verdict.Unaccepted>(decide(listOf("gradle/libs.versions.toml"), null))
    }

    @Test
    fun `a map without the classpath record forces as unrecorded`() {
        bumpJson()

        val verdict = assertIs<Verdict.Forced>(decide(listOf("gradle/libs.versions.toml"), record(own, jsonNew, math)))
        assertEquals(RefusalKind.CLASSPATH_UNRECORDED, verdict.kind)
    }

    @Test
    fun `a module new to the classpath forces as added`() {
        recordInMap(record(own, jsonOld))
        bumpJson()

        val verdict = assertIs<Verdict.Forced>(decide(listOf("gradle/libs.versions.toml"), record(own, jsonNew, math)))
        assertEquals(RefusalKind.DEPENDENCY_ADDED, verdict.kind)
        assertContains(verdict.reason, "dev.lib:lib-math")
    }

    @Test
    fun `a changed order forces as reordered`() {
        recordInMap(record(own, jsonOld, math))
        bumpJson()

        val verdict = assertIs<Verdict.Forced>(decide(listOf("gradle/libs.versions.toml"), record(own, math, jsonNew)))
        assertEquals(RefusalKind.CLASSPATH_REORDERED, verdict.kind)
    }

    @Test
    fun `a bumped module that now holds a class a later entry also holds forces as shadowing`() {
        val shadowing = jar("repo/lib-json-1.2.jar", "lib/json/Json.class", "lib/math/Add.class")
        recordInMap(record(own, jsonOld, math))
        bumpJson()

        val verdict = assertIs<Verdict.Forced>(
            decide(listOf("gradle/libs.versions.toml"), record(own, shadowing, math, identities = identities + (shadowing to "dev.lib:lib-json")))
        )
        assertEquals(RefusalKind.DEPENDENCY_SHADOWS, verdict.kind)
    }

    @Test
    fun `a bumped coordinate no compared classpath holds forces as unlocated`() {
        recordInMap(record(own, jsonOld, math))
        write("gradle/libs.versions.toml", catalog.replace("lib-agent\", version = \"1.0\"", "lib-agent\", version = \"1.1\""))

        val verdict = assertIs<Verdict.Forced>(decide(listOf("gradle/libs.versions.toml"), record(own, jsonOld, math)))
        assertEquals(RefusalKind.DEPENDENCY_UNLOCATED, verdict.kind)
    }

    @Test
    fun `a notation bump in a configuration no classpath extends forces as unlocated`() {
        val agent = jar("repo/lib-agent-1.0.jar", "agent/A.class")
        val withAgent = identities + (agent to "dev.lib:lib-agent")
        recordInMap(record(own, jsonOld, math, agent, identities = withAgent))
        write("app/build.gradle.kts", script.replace("lib-agent:1.0", "lib-agent:1.1"))

        val verdict = assertIs<Verdict.Forced>(decide(listOf("app/build.gradle.kts"), record(own, jsonOld, math, agent, identities = withAgent)))
        assertEquals(RefusalKind.DEPENDENCY_UNLOCATED, verdict.kind)
        assertContains(verdict.reason, "`agent`")
    }

    @Test
    fun `a notation bump in a module script is accepted`() {
        val mathNew = jar("repo/lib-math-1.1.jar", "lib/math/Add.class")
        recordInMap(record(own, jsonOld, math))
        write("app/build.gradle.kts", script.replace("lib-math:1.0", "lib-math:1.1"))

        val verdict = assertIs<Verdict.Accepted>(
            decide(listOf("app/build.gradle.kts"), record(own, jsonOld, mathNew, identities = identities + (mathNew to "dev.lib:lib-math")))
        )
        assertEquals(listOf("dev.lib:lib-math"), verdict.modules)
        assertEquals(emptyList(), verdict.directories)
    }

    @Test
    fun `a bumped jar named in the test JVM's arguments forces`() {
        recordInMap(record(own, jsonOld, math))
        bumpJson()

        val verdict = assertIs<Verdict.Forced>(
            decide(listOf("gradle/libs.versions.toml"), record(own, jsonNew, math), jvmArguments = listOf("-javaagent:${jsonNew.absolutePath}"))
        )
        assertEquals(RefusalKind.DEPENDENCY_IN_JVM_ARGUMENTS, verdict.kind)
    }

    @Test
    fun `a module gone from the classpath is named as moved`() {
        recordInMap(record(own, jsonOld, math))
        write("app/build.gradle.kts", script.replace("lib-math:1.0", "lib-math:1.1"))

        val verdict = assertIs<Verdict.Accepted>(decide(listOf("app/build.gradle.kts"), record(own, jsonOld)))
        assertEquals(listOf("dev.lib:lib-math"), verdict.modules)
    }

    @Test
    fun `a build script no module of this build owns, such as a test's fixture, is none of this rule's`() {
        write("app/src/test/resources/fixture/build.gradle.kts", script)
        git("add", "-A")
        git("commit", "-q", "-m", "fixture")
        write("app/src/test/resources/fixture/build.gradle.kts", script.replace("lib-math:1.0", "lib-math:1.1"))

        assertEquals(Verdict.NotApplicable, decide(listOf("app/src/test/resources/fixture/build.gradle.kts"), null))
    }

    @Test
    fun `a library entry naming the key in another form stays unaccepted`() {
        write("gradle/libs.versions.toml", catalog + "\n\n[libraries.lib-table]\nmodule = \"dev.lib:lib-table\"\nversion.ref = 'lib-json'\n")
        git("add", "-A")
        git("commit", "-q", "-m", "table")
        write("gradle/libs.versions.toml", File(root, "gradle/libs.versions.toml").readText().replace("lib-json = \"1.0\"", "lib-json = \"1.1\""))

        assertIs<Verdict.Unaccepted>(decide(listOf("gradle/libs.versions.toml"), null))
    }

    @Test
    fun `a bumped jar named in the JVM's arguments by its file name alone forces`() {
        recordInMap(record(own, jsonOld, math))
        bumpJson()

        val verdict = assertIs<Verdict.Forced>(
            decide(listOf("gradle/libs.versions.toml"), record(own, jsonNew, math), jvmArguments = listOf("-javaagent:lib/${jsonOld.name}"))
        )
        assertEquals(RefusalKind.DEPENDENCY_IN_JVM_ARGUMENTS, verdict.kind)
    }

    @Test
    fun `a bumped jar whose manifest class path changed forces`() {
        val withClassPath = File(root, "repo/lib-json-1.4.jar").also { target ->
            java.util.jar.JarOutputStream(target.outputStream(), java.util.jar.Manifest().apply {
                mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[java.util.jar.Attributes.Name.CLASS_PATH] = "extra.jar"
            }).use { it.putNextEntry(ZipEntry("lib/json/Json.class")); it.closeEntry() }
        }
        recordInMap(record(own, jsonOld, math))
        bumpJson()

        val verdict = assertIs<Verdict.Forced>(
            decide(listOf("gradle/libs.versions.toml"), record(own, withClassPath, math, identities = identities + (withClassPath to "dev.lib:lib-json")))
        )
        assertEquals(RefusalKind.DEPENDENCY_ADDED, verdict.kind)
    }

    @Test
    fun `a build file that is no bump stays unaccepted`() {
        write("app/build.gradle.kts", script.replace("implementation(libs.lib.json)", "api(libs.lib.json)"))

        assertIs<Verdict.Unaccepted>(decide(listOf("app/build.gradle.kts"), null))
    }

    private val pom = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>dev.sample</groupId>
          <artifactId>app</artifactId>
          <version>1.0</version>
          <properties>
            <lib.json.version>1.0</lib.json.version>
            <unused.version>1.0</unused.version>
          </properties>
          <dependencies>
            <dependency>
              <groupId>dev.lib</groupId>
              <artifactId>lib-json</artifactId>
              <version>${'$'}{lib.json.version}</version>
            </dependency>
          </dependencies>
        </project>
    """.trimIndent()

    private fun decidePom(current: ClasspathRecord.Record?, outsideParent: Boolean = false) = DependencyRule.decide(
        DependencyRule.Inputs(
            root, listOf("pom.xml"), "HEAD", mapDir,
            DependencyRule.Context(
                git = ChangeDetection.directRunner(root),
                raw = { ChangeDetection.rawGit(root, it) },
                current = { current },
                jvmArguments = { emptyList() },
                configurations = { null },
                ownBuildFile = { it == "pom.xml" },
                maven = DependencyRule.Maven(poms = { listOf("pom.xml") }, outsideParent = { outsideParent }),
            ),
        )
    )

    private fun pomRepository(text: String = pom) {
        write("pom.xml", text)
        git("add", "-A")
        git("commit", "-q", "-m", "pom")
    }

    private fun bumpProperty(text: String = pom) = write("pom.xml", text.replace("<lib.json.version>1.0<", "<lib.json.version>1.1<"))

    @Test
    fun `a POM property only dependency versions read is accepted`() {
        pomRepository()
        recordInMap(record(own, jsonOld, math))
        bumpProperty()

        val verdict = assertIs<Verdict.Accepted>(decidePom(record(own, jsonNew, math)))
        assertEquals(listOf("dev.lib:lib-json"), verdict.modules)
    }

    @Test
    fun `a POM property a plugin also reads, no POM reads, or a parent outside may read stays unaccepted`() {
        val plugin = pom.replace("</project>", "  <build><plugins><plugin><artifactId>x</artifactId>\n<version>${'$'}{lib.json.version}</version>\n</plugin></plugins></build>\n</project>")
        pomRepository(plugin)
        bumpProperty(plugin)
        assertContains(assertIs<Verdict.Unaccepted>(decidePom(null)).why, "outside a dependency's version")

        pomRepository()
        write("pom.xml", pom.replace("<unused.version>1.0<", "<unused.version>1.1<"))
        assertContains(assertIs<Verdict.Unaccepted>(decidePom(null)).why, "no POM of the checkout")

        bumpProperty()
        assertContains(assertIs<Verdict.Unaccepted>(decidePom(null, outsideParent = true)).why, "outside the checkout")
    }

    @Test
    fun `a POM that imports a BOM leaves a property bump unaccepted`() {
        val bom = pom.replace("<dependencies>", "<dependencyManagement><dependencies><dependency><groupId>a</groupId><artifactId>b</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>\n  <dependencies>")
        pomRepository(bom)
        bumpProperty(bom)

        assertContains(assertIs<Verdict.Unaccepted>(decidePom(null)).why, "BOM")
    }

    @Test
    fun `a subdirectory a new version adds changes its parent's digest, which a scan of the parent looks up`() {
        val before = ClasspathRecord.directories(jar("d/one.jar", "com/x/A.class"))
        val after = ClasspathRecord.directories(jar("d/two.jar", "com/x/A.class", "com/x/sub/B.class"))

        assertEquals(before["com/"], after["com/"])
        kotlin.test.assertNotEquals(before["com/x/"], after["com/x/"])
    }
}
