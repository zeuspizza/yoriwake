package io.github.zeuspizza.yoriwake.maven

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * Real Maven over a copy of a fixture, with the plugin as this build published it.
 *
 * Each distribution is downloaded once into the Gradle user home and checked against the SHA-512
 * Apache publishes, pinned here. Every build gets a `user.home` of its own, so nothing in the
 * machine's `~/.m2` reaches it; the local repository is shared, so each library downloads once.
 */
object FunctionalMaven {

    /** The supported matrix: the floor, the latest 3.9.x, the latest 3.x and Maven 4. */
    val MATRIX = listOf("3.9.0", "3.9.16", "3.10.0", "4.0.0-rc-7")

    /** Runs before its release only when a build asks, as this suite does for the matrix. */
    const val PRE_RELEASE = "4.0.0-rc-7"

    /** The newest Maven below the floor, for the decline. */
    const val BELOW_FLOOR = "3.8.8"

    private val sha512 = mapOf(
        "3.8.8" to "332088670d14fa9ff346e6858ca0acca304666596fec86eea89253bd496d3c90deae2be5091be199f48e09d46cec817c6419d5161fb4ee37871503f472765d00",
        "3.9.0" to "1ea149f4e48bc7b34d554aef86f948eca7df4e7874e30caf449f3708e4f8487c71a5e5c072a05f17c60406176ebeeaf56b5f895090c7346f8238e2da06cf6ecd",
        "3.9.16" to "831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6",
        "3.10.0" to "908b1501bfb420bf7c8affb855534a9c407fd6099367bfb9f2f2dcb8e9799102bffb84518cde74c679bd76870247c6528683abdd620581bffa90f95d92d175aa",
        "4.0.0-rc-7" to "17b886c0ffb7ec225e514afa8179b3143d855d196cfbb4607ece6272a9b4f51501a389438ab6841b393a4b1391a1b714f07f450daba1951f53f732b400338184",
    )

    private fun property(name: String) = requireNotNull(System.getProperty(name)) {
        "the build did not pass $name; run this through Gradle"
    }

    private val distributions = File(property("yoriwake.mavenDistributions"))
    private val functionalRepo = File(property("yoriwake.functionalRepo"))
    private val localRepo = File(property("yoriwake.localRepo"))
    val pluginVersion = property("yoriwake.pluginVersion")
    val demo = File(property("yoriwake.demo"))

    class Result(val exitCode: Int, val output: String) {
        fun succeeded(): Result = apply { if (exitCode != 0) fail("Maven failed ($exitCode):\n$output") }
    }

    /** A copy of [fixture] in [dir], to run and edit without touching the original. */
    fun copy(fixture: File, dir: File): File = File(dir, "fixture").also { fixture.copyRecursively(it) }

    /** Runs Maven [version] in [project] with [arguments]; the plugin resolves from this build. */
    fun run(version: String, project: File, vararg arguments: String): Result =
        runPlain(version, project, "-Dyoriwake.internal.mavenPreRelease=true", *arguments)

    /** As [run], without allowing a Maven that is not released. */
    fun runPlain(version: String, project: File, vararg arguments: String): Result {
        val home = File(project.parentFile, "home").apply { mkdirs() }
        val settings = File(home, "settings.xml").apply { writeText(settingsXml()) }
        val windows = System.getProperty("os.name").startsWith("Windows")
        val mvn = File(distribution(version), if (windows) "bin/mvn.cmd" else "bin/mvn")
        val command = listOf(
            mvn.path, "-B", "-s", settings.path, "-Dmaven.repo.local=${localRepo.path}",
            "-Dyoriwake.version=$pluginVersion", *arguments,
        )
        val process = ProcessBuilder(command).directory(project).redirectErrorStream(true).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
            environment()["MAVEN_OPTS"] = "-Duser.home=${home.path}"
            // The machine's ~/.mavenrc would set its own options for every build.
            environment()["MAVEN_SKIP_RC"] = "true"
            environment().remove("MAVEN_ARGS")
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("Maven $version did not finish in ten minutes:\n$output")
        }
        return Result(process.exitValue(), output)
    }

    private fun settingsXml() = """
        <settings>
          <profiles>
            <profile>
              <id>yoriwake-functional</id>
              <pluginRepositories>
                <pluginRepository>
                  <id>yoriwake-functional</id>
                  <url>${functionalRepo.toURI()}</url>
                  <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
                </pluginRepository>
              </pluginRepositories>
            </profile>
          </profiles>
          <activeProfiles><activeProfile>yoriwake-functional</activeProfile></activeProfiles>
        </settings>
    """.trimIndent()

    /**
     * The unpacked distribution of [version], downloaded and checked on first use. It is unpacked
     * into a directory of its own and moved into place whole, so a download or unpacking cut short,
     * or one racing another worktree's, never leaves a half distribution to be reused.
     */
    @Synchronized
    private fun distribution(version: String): File {
        val home = File(distributions, "apache-maven-$version")
        if (home.isDirectory) return home
        val expected = requireNotNull(sha512[version]) { "no SHA-512 pinned for Maven $version" }
        distributions.mkdirs()
        val staging = Files.createTempDirectory(distributions.toPath(), "download-").toFile()
        try {
            val name = "apache-maven-$version-bin.tar.gz"
            val archive = File(staging, name)
            val major = version.substringBefore('.')
            val connection = URI("https://archive.apache.org/dist/maven/maven-$major/$version/binaries/$name").toURL()
                .openConnection().apply { connectTimeout = 30_000; readTimeout = 60_000 }
            connection.getInputStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-512").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != expected) fail("Maven $version's archive has SHA-512 $actual, not the published $expected")
            val unpacked = ProcessBuilder("tar", "xzf", name).directory(staging).inheritIO().start().waitFor()
            val tree = File(staging, home.name)
            check(unpacked == 0 && File(tree, "bin").isDirectory) { "could not unpack $name" }
            try {
                Files.move(tree.toPath(), home.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (raced: java.nio.file.FileAlreadyExistsException) {
                // Another worktree's build put the same distribution in place first.
            } catch (raced: java.nio.file.DirectoryNotEmptyException) {
                // As above, on a platform that reports it this way.
            }
        } finally {
            staging.deleteRecursively()
        }
        return home
    }
}
