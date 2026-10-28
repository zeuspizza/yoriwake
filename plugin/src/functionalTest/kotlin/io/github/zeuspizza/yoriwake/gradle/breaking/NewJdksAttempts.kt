package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on classes newer than a reader of them: the plugin's ASM, the host's JaCoCo. A class
 * above either may be digested or instrumented wrongly with nothing to say so, so a selecting run
 * over it runs every test.
 */
class NewJdksAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        """
        package dev.sample;
        public class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }
        """,
    )

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    /** A class no test loads, so a header no JVM here reads never fails a test. */
    private val late = source(
        "src/main/java/dev/sample/Late.java",
        "package dev.sample;\npublic class Late { public int once(int n) { return n; } }",
    )

    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    private val codecTest = source(
        "src/test/java/dev/sample/A1CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    private val sources = listOf(codec, other, late, first, codecTest, classOrderByName)

    // Track: fixtures. Row: NJ1.
    @Test
    fun `a class of the task's output above the plugin's ASM runs every test`(@TempDir dir: File) {
        val build = minimalBuild + "\n" + """
            tasks.compileJava {
                val out = destinationDirectory
                doLast {
                    val file = out.get().file("dev/sample/Late.class").asFile
                    val bytes = file.readBytes()
                    bytes[6] = 0
                    bytes[7] = 72
                    file.writeBytes(bytes)
                }
            }
        """.trimIndent()
        val result = attempt(dir, "dev.sample.A1CodecTest", Aim.Forced("class-file-unsupported"), sources, buildScript = build) {
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: NJ2.
    @Test
    fun `classes newer than the host's JaCoCo officially instruments run every test`(@TempDir dir: File) {
        val build = minimalBuild + "\njacoco { toolVersion = \"0.8.10\" }\n" +
            "tasks.withType<JavaCompile>().configureEach { options.release.set(21) }"
        val result = attempt(dir, "dev.sample.A1CodecTest", Aim.Forced("class-file-unsupported"), sources, buildScript = build) {
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a multi-release jar's versioned classes count only for a
    // release the test JVM loads, at or below its own. One versioned for exactly the test JVM's
    // release is loaded in place of the base class, so its header counts.
    @Test
    fun `a versioned class for the test JVM's own release above the plugin's ASM runs every test`(@TempDir dir: File) {
        val build = minimalBuild + "\n" + """
            val versionedLate = tasks.register("versionedLate") {
                val release = JavaVersion.current().majorVersion
                val compiled = tasks.compileJava.flatMap { it.destinationDirectory }
                val out = layout.buildDirectory.dir("gen/late")
                inputs.dir(compiled)
                outputs.dir(out)
                doLast {
                    val bytes = compiled.get().file("dev/sample/Late.class").asFile.readBytes()
                    bytes[6] = 0
                    bytes[7] = 72
                    out.get().asFile.resolve("META-INF/versions/" + release + "/dev/sample/Late.class")
                        .apply { parentFile.mkdirs() }.writeBytes(bytes)
                }
            }
            val lateJar = tasks.register<Jar>("lateJar") {
                from(versionedLate)
                archiveFileName.set("late.jar")
                destinationDirectory.set(layout.buildDirectory.dir("gen/jar"))
                manifest { attributes("Multi-Release" to "true") }
            }
            dependencies { testRuntimeOnly(files(lateJar)) }
        """.trimIndent()
        val result = attempt(dir, "dev.sample.A1CodecTest", Aim.Forced("class-file-unsupported"), sources, buildScript = build) {
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the daemon refuses a test JVM above the agent's ASM from the
    // task's own arguments, argument files included; the agent judges its own JVM again when it
    // starts. A ceiling passed in an argument file reaches the test JVM by the launcher alone.
    @Test
    fun `a test JVM above the agent's ASM named in a JVM argument file runs every test`(@TempDir dir: File) {
        val build = minimalBuild + "\n" + """
            val stampArgs = tasks.register("stampArgs") {
                val out = layout.buildDirectory.file("gen/test.args")
                outputs.file(out)
                doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText("-D${AgentContract.AGENT_ASM_CEILING_PROPERTY}=60\n") }
            }
            tasks.test {
                dependsOn(stampArgs)
                jvmArgs("@" + layout.buildDirectory.file("gen/test.args").get().asFile.absolutePath)
            }
        """.trimIndent()
        val result = attempt(dir, "dev.sample.A1CodecTest", Aim.Forced(AgentContract.TEST_JVM_UNSUPPORTED_KIND), sources, buildScript = build) {
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }
}
