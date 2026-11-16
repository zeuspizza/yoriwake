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
        jvmArguments: List<String>? = emptyList(),
        configurations: Set<String>? = setOf("implementation", "runtimeClasspath", "testRuntimeClasspath"),
        ownBuildFile: (String) -> Boolean = { it == "app/build.gradle.kts" },
        git: ChangeDetection.Runner = ChangeDetection.directRunner(root),
    ) = DependencyRule.decide(
        DependencyRule.Inputs(
            root, paths, "HEAD", mapDir,
            DependencyRule.Context(
                git = git,
                raw = { ChangeDetection.rawGit(root, it) },
                current = { current },
                jvmArguments = { jvmArguments ?: error("unreadable") },
                configurations = { configurations },
                ownBuildFile = ownBuildFile,
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

    @Test
    fun `a classpath that cannot be read now, or JVM arguments that cannot, force`() {
        recordInMap(record(own, jsonOld, math))
        bumpJson()

        assertEquals(RefusalKind.CLASSPATH_UNRECORDED, assertIs<Verdict.Forced>(decide(listOf("gradle/libs.versions.toml"), null)).kind)
        assertEquals(
            RefusalKind.DEPENDENCY_IN_JVM_ARGUMENTS,
            assertIs<Verdict.Forced>(decide(listOf("gradle/libs.versions.toml"), record(own, jsonNew, math), jvmArguments = null)).kind,
        )
    }

    @Test
    fun `a module whose digest could not be read counts as moved`() {
        val recorded = record(own, jsonOld, math)
        val unreadable = recorded.copy(entries = recorded.entries.map { if (it.identity == "dev.lib:lib-math") it.copy(digest = ClasspathFiles.UNREADABLE) else it })
        // Unreadable at both ends, which is no evidence the jar stayed the same.
        recordInMap(unreadable)
        write("app/build.gradle.kts", script.replace("lib-math:1.0", "lib-math:1.1"))

        val verdict = assertIs<Verdict.Accepted>(decide(listOf("app/build.gradle.kts"), unreadable))
        assertEquals(listOf("dev.lib:lib-math"), verdict.modules)
    }

    // ---------------------------------------------------------------- build script values

    private val d = "$"

    /** A root Groovy script declaring `mathVersion` in `ext`, read by a notation, plus [more]. */
    private fun groovyValue(more: String = "", reader: String = "implementation \"dev.lib:lib-math:${d}mathVersion\"") = """
        ext {
            mathVersion = "1.0"
        }
        dependencies {
            $reader
        }
    """.trimIndent() + "\n" + more

    private val mathNew get() = jar("repo/lib-math-1.1.jar", "lib/math/Add.class")

    /** Commits [files] as the base, applies [bumped], and decides the change set of the changed ones. */
    private fun decideValue(
        files: Map<String, String>,
        bumped: Map<String, String>,
        configurations: Set<String> = setOf("implementation"),
        git: ChangeDetection.Runner = ChangeDetection.directRunner(root),
    ): Verdict {
        files.forEach { (path, text) -> write(path, text) }
        git("add", "-A")
        git("commit", "-q", "-m", "values")
        val newMath = mathNew
        recordInMap(record(own, jsonOld, math))
        bumped.forEach { (path, text) -> write(path, text) }
        return decide(
            bumped.keys.toList(), record(own, jsonOld, newMath, identities = identities + (newMath to "dev.lib:lib-math")),
            configurations = configurations,
            git = git,
            ownBuildFile = { it == "build.gradle" || it == "build.gradle.kts" || it.endsWith("/build.gradle") || it == "app/build.gradle.kts" },
        )
    }

    private fun bumpGroovy(text: String) = text.replace("mathVersion = \"1.0\"", "mathVersion = \"1.1\"")

    @Test
    fun `an ext value only dependency notations read is accepted, and moves what they declare`() {
        // A lookup by a literal name reads that name only.
        val text = groovyValue("if (project.hasProperty(\"jmh\")) {\n    println(findProperty(\"jmh\"))\n}")
        val verdict = assertIs<Verdict.Accepted>(decideValue(mapOf("build.gradle" to text), mapOf("build.gradle" to bumpGroovy(text))))
        assertEquals(listOf("build.gradle"), verdict.paths)
        assertEquals(listOf("dev.lib:lib-math"), verdict.modules)
    }

    @Test
    fun `a Kotlin top-level val read in a notation is accepted, its name in the group or artifact being no reader`() {
        val text = "val math = \"1.0\"\ndependencies {\n    implementation(\"dev.math:lib-math:${d}math\")\n}\n"
        val newMath = jar("repo/math-1.1.jar", "lib/math/Add.class")
        write("app/build.gradle.kts", text)
        git("add", "-A")
        git("commit", "-q", "-m", "values")
        val identities = identities + (math to "dev.math:lib-math") + (newMath to "dev.math:lib-math")
        recordInMap(record(own, jsonOld, math, identities = identities))
        write("app/build.gradle.kts", text.replace("\"1.0\"", "\"1.1\""))
        val verdict = assertIs<Verdict.Accepted>(decide(listOf("app/build.gradle.kts"), record(own, jsonOld, newMath, identities = identities)))
        assertEquals(listOf("dev.math:lib-math"), verdict.modules)
    }

    @Test
    fun `a value also read as a system property, a tool's version or a plugin's classpath stays unaccepted, naming it`() {
        for (more in listOf(
            "test {\n    systemProperty(\"math.version\", mathVersion)\n}",
            "checkstyle {\n    toolVersion = mathVersion\n}",
            "buildscript {\n    dependencies {\n        classpath \"dev.tools:plugin:${d}mathVersion\"\n    }\n}",
            "subprojects {\n    dependencies {\n        implementation \"dev.lib:lib-math:${d}mathVersion\"\n    }\n}",
        )) {
            val text = groovyValue(more)
            val verdict = assertIs<Verdict.Unaccepted>(decideValue(mapOf("build.gradle" to text), mapOf("build.gradle" to bumpGroovy(text))), more)
            assertContains(verdict.why, "`mathVersion`")
            git("reset", "-q", "--hard")
        }
    }

    @Test
    fun `a git that cannot list the build's scripts leaves a bump unaccepted, never read as nothing reading it`() {
        // Each listing failing alone: the included builds', build logic's and the values' readers'.
        val direct = ChangeDetection.directRunner(root)
        fun failing(pattern: String) = ChangeDetection.Runner { arguments ->
            if (arguments.first() == "ls-files" && pattern in arguments) null else direct.run(arguments)
        }
        for (pattern in listOf("*settings.gradle", "buildSrc/*")) {
            recordInMap(record(own, jsonOld, math))
            bumpJson()
            val verdict = assertIs<Verdict.Unaccepted>(
                decide(listOf("gradle/libs.versions.toml"), record(own, jsonNew, math), git = failing(pattern)), pattern,
            )
            assertEquals("gradle/libs.versions.toml", verdict.path)
            assertContains(verdict.why, "could not list")
        }
        val text = groovyValue()
        val verdict = assertIs<Verdict.Unaccepted>(
            decideValue(mapOf("build.gradle" to text), mapOf("build.gradle" to bumpGroovy(text)), git = failing("gradle.properties"))
        )
        assertContains(verdict.why, "could not list")
    }

    @Test
    fun `a value read in buildSrc stays unaccepted`() {
        val text = groovyValue()
        val verdict = assertIs<Verdict.Unaccepted>(
            decideValue(
                mapOf("build.gradle" to text, "buildSrc/src/main/kotlin/Versions.kt" to "val v = project.extra.get(\"mathVersion\")\n"),
                mapOf("build.gradle" to bumpGroovy(text)),
            )
        )
        assertContains(verdict.why, "buildSrc")
    }

    @Test
    fun `a value read by a notation of an applied script, or through the root project's ext, is accepted`() {
        val text = groovyValue("apply from: \"gradle/deps.gradle\"", reader = "")
        val verdict = decideValue(
            mapOf(
                "build.gradle" to text,
                "gradle/deps.gradle" to "dependencies {\n    implementation \"dev.lib:lib-math:${d}mathVersion\"\n}\n",
                "app/build.gradle" to "dependencies {\n    implementation \"dev.lib:lib-math:${d}{rootProject.ext.mathVersion}\"\n}\n",
            ),
            mapOf("build.gradle" to bumpGroovy(text)),
        )
        assertEquals(listOf("dev.lib:lib-math"), assertIs<Verdict.Accepted>(verdict).modules)
    }

    @Test
    fun `a value no script reads stays unaccepted`() {
        val text = groovyValue(reader = "")
        val verdict = assertIs<Verdict.Unaccepted>(decideValue(mapOf("build.gradle" to text), mapOf("build.gradle" to bumpGroovy(text))))
        assertContains(verdict.why, "no build script")
    }

    @Test
    fun `an extra accessor in a notation is accepted, and one anywhere else stays unaccepted`() {
        val kotlin = "extra[\"math\"] = \"1.0\"\ndependencies {\n    implementation(\"dev.lib:lib-math:${d}{extra[\"math\"]}\")\n}\n"
        assertIs<Verdict.Accepted>(decideValue(mapOf("build.gradle.kts" to kotlin), mapOf("build.gradle.kts" to kotlin.replace("= \"1.0\"", "= \"1.1\""))))
        git("reset", "-q", "--hard")
        val outside = kotlin + "tasks.test {\n    environment(\"MATH\", property(\"math\"))\n}\n"
        assertIs<Verdict.Unaccepted>(decideValue(mapOf("build.gradle.kts" to outside), mapOf("build.gradle.kts" to outside.replace("= \"1.0\"", "= \"1.1\""))))
    }

    @Test
    fun `the build's properties read in bulk, or a property looked up by a computed name, leave a value unaccepted`() {
        for (more in listOf(
            "processResources {\n    expand(project.properties)\n}",
            "def lib = \"math\"\nprintln(property(\"${d}{lib}Version\"))",
            "println(findProperty(\"math\" + \"Version\"))",
            "def k = \"mathVersion\"\nprintln(ext[k])",
            "def k = \"mathVersion\"\nprintln(ext.get(k))",
            "def k = \"mathVersion\"\nprintln(project.extensions.extraProperties.get(k))",
            "def lib = \"math\"\nprintln(project.\"${d}{lib}Version\")",
        )) {
            val text = groovyValue(more)
            val verdict = assertIs<Verdict.Unaccepted>(decideValue(mapOf("build.gradle" to text), mapOf("build.gradle" to bumpGroovy(text))), more)
            assertContains(verdict.why, "`mathVersion`")
            git("reset", "-q", "--hard")
        }
    }

    @Test
    fun `a value read by a notation in a configuration no classpath extends forces as unlocated`() {
        val text = groovyValue(reader = "agent \"dev.lib:lib-math:${d}mathVersion\"")
        val verdict = assertIs<Verdict.Forced>(decideValue(mapOf("build.gradle" to text), mapOf("build.gradle" to bumpGroovy(text))))
        assertEquals(RefusalKind.DEPENDENCY_UNLOCATED, verdict.kind)
    }

    @Test
    fun `a bump beside another build file's change leaves the whole change set unaccepted`() {
        val text = groovyValue()
        val module = "dependencies {\n    implementation \"dev.lib:lib-math:1.0\"\n}\ntest {\n    jvmArgs \"-Xmx1g\"\n}\n"
        val verdict = decideValue(
            mapOf("build.gradle" to text, "app/build.gradle" to module),
            mapOf("build.gradle" to bumpGroovy(text), "app/build.gradle" to module.replace("-Xmx1g", "-Xmx2g")),
        )
        assertEquals("app/build.gradle", assertIs<Verdict.Unaccepted>(verdict).path)
    }

    @Test
    fun `a gradle properties file naming the value leaves it unaccepted`() {
        val text = groovyValue()
        val verdict = assertIs<Verdict.Unaccepted>(
            decideValue(mapOf("build.gradle" to text, "gradle.properties" to "mathVersion=2.0\n"), mapOf("build.gradle" to bumpGroovy(text)))
        )
        assertContains(verdict.why, "gradle.properties")
    }

    @Test
    fun `braces inside a multi-line string do not hide the block a reader is in`() {
        val text = groovyValue("subprojects {\n    def banner = \"\"\"\n}\n\"\"\"\n    dependencies {\n        implementation \"dev.lib:lib-math:${d}mathVersion\"\n    }\n}")
        assertIs<Verdict.Unaccepted>(decideValue(mapOf("build.gradle" to text), mapOf("build.gradle" to bumpGroovy(text))))
    }
}
