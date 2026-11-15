package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.BuildFileReach
import io.github.zeuspizza.yoriwake.gradle.change.BuildFileReach.Base
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * When a sibling module's build script, or a script that reads it, can carry its change to a test
 * this task runs: either revision reaching another project, the build at large or a path above its
 * module, a changed plugin, or a reader naming the module.
 */
class BuildFileReachTest {

    @Test
    fun `a script that only configures its own module reaches nothing`() {
        listOf(
            """dependencies { implementation(project(":core")) }""",
            """dependencies { api project(path: ':x', configuration: 'y') }""",
            """dependencies { testImplementation(testFixtures(project(":core"))) }""",
            """dependencies { "implementation"(project(":core")) }""",
            "import org.gradle.api.tasks.testing.Test",
            "dependencies { implementation(libs.gradle.plugin) }",
            """tasks.test { useJUnitPlatform(); systemProperty("x", "1") }""",
            """tasks.named("jar").configure { enabled = true }""",
        ).forEach { text -> assertNull(BuildFileReach.reach(text), text) }
    }

    @Test
    fun `a script that reaches another project, the build or a path above its module refuses`() {
        listOf(
            """project(':x') { apply plugin: 'java' }""",
            """project(":x").tasks.named("test")""",
            """val a = project(":alpha")""",
            """with(project(":alpha")) { version = "2" }""",
            """findProject(":alpha")?.afterEvaluate { }""",
            "allprojects { }",
            "subprojects { }",
            "gradle.projectsEvaluated { }",
            "project.gradle.projectsEvaluated { }",
            "apply from: 'common.gradle'",
            """apply(from = "common.gradle")""",
            """File(rootDir, "build/x")""",
            """file("../alpha/src/test/resources/gen.txt")""",
            """jvmArgs("-Dfixtures=../alpha/src/test/resources")""",
            """configure(listOf(project)) { }""",
            "evaluationDependsOn(\":alpha\")",
            "project.parent?.version",
            """System.setProperty("x", "y")""",
        ).forEach { text -> assertNotNull(BuildFileReach.reach(text), text) }
    }

    @Test
    fun `a plugins block that differs between revisions refuses, and one that does not passes`() {
        val base = "plugins { java }\ndependencies { implementation(\"a:b:1\") }\n"
        val bumped = "plugins { java }\ndependencies { implementation(\"a:b:2\") }\n"

        assertNull(refusal(base, bumped))
        assertNotNull(refusal(base, "plugins { java; id(\"local.convention\") }\n"))
        assertNotNull(refusal("apply plugin: 'java'\n", "apply plugin: 'java'\napply plugin: 'local'\n"))
        assertNotNull(refusal(base, base + "apply<JavaPlugin>()\n"))
        assertNotNull(refusal(base, base + "apply {\n    plugin(\"local\")\n}\n"))
        assertNotNull(refusal(base, base + "apply(mapOf(\"plugin\" to \"local\"))\n"))
        assertNotNull(refusal("buildscript { dependencies { classpath(\"a:b:1\") } }\n", "buildscript { dependencies { classpath(\"a:b:2\") } }\n"))
    }

    @Test
    fun `the base revision's reach refuses too, and a new module's script is read alone`() {
        val reaching = "project(\":alpha\") { tasks.withType<Test> { environment(\"X\", \"1\") } }\n"

        assertNotNull(refusal(reaching, "plugins { java }\n"))
        assertNull(refusal(Base.Absent, "plugins { java }\n"))
        assertNotNull(refusal(Base.Unknown, "plugins { java }\n"))
    }

    @Test
    fun `every base revision is read, and one only the drift since the capture names refuses`() {
        val reaching = "allprojects { }\n"

        assertNotNull(refusal(listOf(Base.Text(SCRIPT), Base.Text(reaching)), SCRIPT))
        assertNull(refusal(listOf(Base.Text(SCRIPT), Base.Absent), SCRIPT))
        assertNotNull(refusal(listOf(Base.Text(SCRIPT), Base.Unknown), SCRIPT))
        assertNotNull(refusal(emptyList(), SCRIPT))
    }

    @Test
    fun `a build file that cannot be read refuses`() {
        assertNotNull(refusal(Base.Text(""), current = null))
    }

    @Test
    fun `a settings script that only declares the module does not name it`() {
        listOf(
            "include(\"alpha\", \"beta\")",
            "include(\":alpha\",\n    \":beta\")",
            "include ':alpha',\n    ':beta'",
            "includeFlat(\"beta\")",
            "include(\"beta\")\nproject(\":beta\").projectDir = file(\"modules/beta\")",
            "include 'beta'\nproject(':beta').buildFileName = 'beta.gradle'",
        ).forEach { settings -> assertNull(refusal(Base.Text(SCRIPT), SCRIPT, settings = settings), settings) }
    }

    @Test
    fun `a settings script or a reading build file that names the module refuses`() {
        assertNotNull(refusal(Base.Text(SCRIPT), SCRIPT, settings = "include(\"beta\")\nval x = file(\"beta/x.txt\")"))
        assertNotNull(refusal(Base.Text(SCRIPT), SCRIPT, alpha = "tasks.test { jvmArgs(\"-Dv=\${project(\":beta\").version}\") }"))
        assertNotNull(refusal(Base.Text(SCRIPT), SCRIPT, root = "val schema = file(\"beta/src/main/resources/schema.sql\")"))
        assertNotNull(refusal(Base.Text(SCRIPT), SCRIPT, alpha = "dependencies { implementation(projects.beta) }"))
        assertNotNull(refusal(Base.Text(SCRIPT), SCRIPT, settings = null))
    }

    @Test
    fun `a module that holds the tested module never drops its build file`() {
        val view = view(mapOf("a" to ":a", "a/b" to ":a:b"), ":a:b")

        assertNotNull(BuildFileReach.refusal("a/build.gradle.kts", ":a", true, view, { SCRIPT }, { true }, { listOf(Base.Text(SCRIPT)) }))
    }

    private fun refusal(base: String, current: String?) = refusal(Base.Text(base), current)

    private fun refusal(base: Base, current: String?, settings: String? = "include(\"alpha\", \"beta\")", alpha: String = SCRIPT, root: String = "") =
        refusal(listOf(base), current, settings, alpha, root)

    private fun refusal(
        bases: List<Base>,
        current: String?,
        settings: String? = "include(\"alpha\", \"beta\")",
        alpha: String = SCRIPT,
        root: String = "",
    ): String? {
        val files = mapOf(
            "settings.gradle.kts" to settings,
            "alpha/build.gradle.kts" to alpha,
            "beta/build.gradle.kts" to current,
            "build.gradle.kts" to root,
        )
        val view = view(mapOf("" to ":", "alpha" to ":alpha", "beta" to ":beta"), ":alpha")
        return BuildFileReach.refusal(
            "beta/build.gradle.kts", ":beta", true, view,
            { files[it] }, { path -> path in files && (path != "settings.gradle.kts" || settings != null) }, { bases },
        )
    }

    private fun view(dirs: Map<String, String>, own: String) = object : ClasspathView {
        override val rootDir = File("/repo")
        override val moduleDirs = dirs
        override val buildDirs = emptyMap<String, String>()
        override val dependencyHomes = emptyMap<String, File>()
        override val ownPath = own
        override val buildFiles = dirs.entries.associate { (dir, path) -> path to if (dir.isEmpty()) "build.gradle.kts" else "$dir/build.gradle.kts" }
        override fun classpathFiles() = emptySet<File>()
        override fun testOutputFiles() = emptySet<File>()
        override fun compileTimeFiles() = emptySet<File>()
        override fun declaredModules() = emptySet<String>()
        override fun jacocoVersion(): String? = null
        override fun jacocoSetting() = ""
        override fun testJvm(): Int? = null
        override fun agentCeiling(): String? = null
    }

    private companion object {
        const val SCRIPT = "plugins { java }\n"
    }
}
