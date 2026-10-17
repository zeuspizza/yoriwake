package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on Kotlin `inline` functions. An inline body is copied into each call site, so a caller
 * runs it without executing the declaring file's class and coverage records no edge. The inline
 * scan reads the SMAP Kotlin writes for debuggers to find who inlined a changed file, and refuses
 * when it cannot finish or the build carries no SMAP at all.
 */
class KotlinInlineAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private fun kotlinTest(name: String, body: String) = source(
        "src/test/kotlin/dev/demokt/$name.kt",
        """
        package dev.demokt
        import org.junit.jupiter.api.Test
        import kotlin.test.assertEquals
        import kotlin.test.assertTrue
        class $name { $body }
        """,
    )

    private val kotlinBuild = """
        plugins {
            kotlin("jvm") version "2.1.20"
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        dependencies {
            testImplementation(platform("org.junit:junit-bom:5.11.4"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            testImplementation(kotlin("test"))
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }
        tasks.test { useJUnitPlatform(); forkEvery = 1 }
    """.trimIndent()

    // `belowTen` is not inline, so a test executing it gives the map a record of InlinedKt.
    private val inlined = source(
        "src/main/kotlin/dev/demokt/Inlined.kt",
        """
        package dev.demokt
        inline fun aboveZero(n: Int): Boolean = n > 0
        fun belowTen(n: Int): Boolean = n < 10
        """,
    )
    private val belowTenTest = kotlinTest("A2BelowTenTest", "@Test fun below() { assertTrue(belowTen(9)) }")
    private val alpha = source("src/main/kotlin/dev/demokt/Alpha.kt", "package dev.demokt\nclass Alpha { fun twice(n: Int): Int = n * 2 }")
    private val alphaTest = kotlinTest("A4AlphaTest", "@Test fun twice() { assertEquals(2, Alpha().twice(1)) }")
    private val inlinedTest = kotlinTest("A1InlinedTest", "@Test fun positive() { assertTrue(aboveZero(1)) }")

    private fun changeAboveZero(dir: File) {
        val file = File(dir, inlined.first)
        file.writeText(file.readText().replace("n > 0", "n > 1"))
    }

    private val alphaJava = oneClass
    private val alphaJavaTest = oneTest

    private fun changeAlphaJava(dir: File) =
        File(dir, oneClass.first).writeText(oneClass.second.replace("n * 2", "n * 2 + 1"))

    // Track: code reading. Boundary: the scan matches an SMAP's file names against the changed
    // prefixes. Here the changed body reaches the test through a second inline function in another
    // file, so the test's SMAP must name the file two inlining steps away, and the intermediate
    // file's own class, which also carries the copy, must be known to the map.
    @Test
    fun `an inline function reached through another inline function selects the test it was copied into`(
        @TempDir dir: File,
    ) {
        val wrapper = source(
            "src/main/kotlin/dev/demokt/Wrapper.kt",
            """
            package dev.demokt
            inline fun positiveAndSmall(n: Int): Boolean = aboveZero(n) && n < 100
            fun wrapperOne(): Int = 1
            """,
        )
        val target = kotlinTest("A1WrappedTest", "@Test fun positive() { assertTrue(positiveAndSmall(1)) }")
        val wrapperTest = kotlinTest("A3WrapperOneTest", "@Test fun one() { assertEquals(1, wrapperOne()) }")
        val result = attempt(
            dir, "dev.demokt.A1WrappedTest", Aim.Row("reaches-change"),
            listOf(inlined, wrapper, target, belowTenTest, wrapperTest, alpha, alphaTest),
            buildScript = kotlinBuild,
        ) { changeAboveZero(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the SMAP signal is judged across every own class, main and
    // test output alike, and a Kotlin output with none anywhere cannot say who inlined a change.
    // A post-processor here strips the SourceDebugExtension attribute from every Kotlin class, as
    // a shrinker that drops debug attributes does; the test still runs the inlined body.
    @Test
    fun `a build whose classes lose their SMAP after compilation refuses on a Kotlin change`(@TempDir dir: File) {
        val strippingBuild = """
            buildscript {
                repositories { mavenCentral() }
                dependencies { classpath("org.ow2.asm:asm:9.7.1") }
            }
        """.trimIndent() + "\n" + kotlinBuild + "\n" + """
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
                val out = destinationDirectory
                doLast {
                    out.get().asFile.walk().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                        val writer = org.objectweb.asm.ClassWriter(0)
                        org.objectweb.asm.ClassReader(file.readBytes()).accept(
                            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
                                override fun visitSource(source: String?, debug: String?) = super.visitSource(source, null)
                            },
                            0,
                        )
                        file.writeBytes(writer.toByteArray())
                    }
                }
            }
        """.trimIndent()
        val result = attempt(
            dir, "dev.demokt.A1InlinedTest", Aim.Forced("smap-absent"),
            listOf(inlined, inlinedTest, belowTenTest, alpha, alphaTest),
            buildScript = strippingBuild,
        ) { changeAboveZero(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the scan walks only module output and refuses when another
    // classpath directory holds a file named like a class, since a consumer could hide there. The
    // directory here is a committed folder of sample class files, not build output at all.
    @Test
    fun `a committed folder of class files on the test classpath stops the inline scan`(@TempDir dir: File) {
        val fixturesBuild = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            "tasks.test { useJUnitPlatform() }\nsourceSets[\"test\"].runtimeClasspath += files(\"fixtures\")",
        )
        val sample = source("fixtures/samples/Sample.class", "not a class file: a sample a bytecode tool reads")
        val result = attempt(
            dir, "dev.sample.AlphaTest", Aim.Forced("scan-exhausted"),
            listOf(alphaJava, alphaJavaTest, secondClass, secondTest, sample, classOrderByName),
            buildScript = fixturesBuild,
        ) { changeAlphaJava(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the classes of a changed source are looked up in the classpath's
    // directories and module jars; with neither, there is nothing to establish them from. The tests
    // here run against packaged jars kept outside every build directory, so the module's own classes
    // reach the test JVM from where no lookup looks.
    @Test
    fun `tests run against jars packaged outside the build directory refuse to narrow`(@TempDir dir: File) {
        val packagedBuild = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            """
            val packMain = tasks.register<Jar>("packMain") {
                from(sourceSets.main.get().output)
                archiveFileName.set("main.jar")
                destinationDirectory.set(layout.projectDirectory.dir(".gradle/packaged"))
            }
            val packTest = tasks.register<Jar>("packTest") {
                from(sourceSets.test.get().output)
                archiveFileName.set("test.jar")
                destinationDirectory.set(layout.projectDirectory.dir(".gradle/packaged"))
            }
            tasks.test {
                useJUnitPlatform()
                classpath = files(packMain, packTest) + classpath.filter { it.name.endsWith(".jar") }
            }
            """.trimIndent(),
        )
        val result = attempt(
            dir, "dev.sample.AlphaTest", Aim.Forced("scan-refused"),
            listOf(alphaJava, alphaJavaTest, secondClass, secondTest, classOrderByName),
            buildScript = packagedBuild,
        ) { changeAlphaJava(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: KI5. Every reader inside the scan is wrapped, so a class file the scan
    // cannot parse is the nearest a suite gets to making it throw; it ends as an unfinished scan.
    @Test
    fun `a class file in the module output the scan cannot parse`(@TempDir dir: File) {
        val brokenBuild = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            """
            val writeBroken = tasks.register("writeBroken") {
                val out = layout.buildDirectory.file("extra/dev/sample/Broken.class")
                outputs.file(out)
                doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3)) }
            }
            sourceSets["test"].runtimeClasspath += files(layout.buildDirectory.dir("extra"))
            tasks.test { useJUnitPlatform(); dependsOn(writeBroken) }
            """.trimIndent(),
        )
        val result = attempt(
            dir, "dev.sample.AlphaTest", Aim.Forced("scan-exhausted"),
            listOf(alphaJava, alphaJavaTest, secondClass, secondTest, classOrderByName),
            buildScript = brokenBuild,
        ) { changeAlphaJava(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the reference names `-Xno-source-debug-extension` as a build
    // with no SMAP. The flag drops only the `@SourceDebugExtension` annotation copy; the attribute
    // the scan reads stays, so the scan answers and the inliner is selected by its own rule.
    @Test
    fun `the no-source-debug-extension flag alone leaves the SMAP the scan reads`(@TempDir dir: File) {
        val flagBuild = kotlinBuild + "\n" + """
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
                compilerOptions.freeCompilerArgs.add("-Xno-source-debug-extension")
            }
        """.trimIndent()
        val result = attempt(
            dir, "dev.demokt.A1InlinedTest", Aim.Forced("smap-absent"),
            listOf(inlined, inlinedTest, belowTenTest, alpha, alphaTest),
            buildScript = flagBuild,
        ) { changeAboveZero(it) }

        assertOutcome(BreakAttempt.Outcome.OFF_TARGET, result)
    }
}
