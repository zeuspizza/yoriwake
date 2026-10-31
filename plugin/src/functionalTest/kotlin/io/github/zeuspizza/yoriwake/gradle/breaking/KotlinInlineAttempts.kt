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

    private val forkedKotlinBuild = """
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
            buildScript = forkedKotlinBuild,
        ) { changeAboveZero(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
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
        """.trimIndent() + "\n" + forkedKotlinBuild + "\n" + """
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

        assertOutcome(Outcome.HELD_BY_FORCE, result)
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

        assertOutcome(Outcome.HELD_BY_FORCE, result)
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

        assertOutcome(Outcome.HELD_BY_FORCE, result)
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

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the reference names `-Xno-source-debug-extension` as a build
    // with no SMAP. The flag drops only the `@SourceDebugExtension` annotation copy; the attribute
    // the scan reads stays, so the scan answers and the inliner is selected by its own rule.
    @Test
    fun `the no-source-debug-extension flag alone leaves the SMAP the scan reads`(@TempDir dir: File) {
        val flagBuild = forkedKotlinBuild + "\n" + """
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
                compilerOptions.freeCompilerArgs.add("-Xno-source-debug-extension")
            }
        """.trimIndent()
        val result = attempt(
            dir, "dev.demokt.A1InlinedTest", Aim.Forced("smap-absent"),
            listOf(inlined, inlinedTest, belowTenTest, alpha, alphaTest),
            buildScript = flagBuild,
        ) { changeAboveZero(it) }

        assertOutcome(Outcome.OFF_TARGET, result)
    }

    // Track: code reading. Boundary: the scan matches the classes an SMAP names against the
    // prefixes of the changed sources, which come from the path and the file's type declarations.
    // Here the inline function sits in a companion object of a class its file is not named after,
    // so the SMAP names `Range$Companion` and only the declared type can match it.
    @Test
    fun `an inline function in a companion object of a class its file is not named after selects its caller`(
        @TempDir dir: File,
    ) {
        val limits = source(
            "src/main/kotlin/dev/demokt/Limits.kt",
            """
            package dev.demokt
            class Range(val low: Int) {
                companion object { inline fun positive(n: Int): Boolean = n > 0 }
                fun contains(n: Int): Boolean = n >= low
            }
            fun limitOf(n: Int): Int = n
            """,
        )
        // Executes the file's own facade class, so the changed file is known to the map by its path.
        val limitsTest = kotlinTest("A3LimitsTest", "@Test fun limit() { assertEquals(1, limitOf(1)) }")
        val target = kotlinTest("A1CompanionTest", "@Test fun positive() { assertTrue(Range.positive(1)) }")
        val rangeTest = kotlinTest("A2RangeTest", "@Test fun contains() { assertTrue(Range(0).contains(1)) }")
        val result = attempt(
            dir, "dev.demokt.A1CompanionTest", Aim.Row("reaches-change"),
            listOf(limits, target, rangeTest, limitsTest, alpha, alphaTest),
            buildScript = forkedKotlinBuild,
        ) {
            val file = File(it, limits.first)
            file.writeText(file.readText().replace("n > 0", "n > 1"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the scan reads every file named like a class under the own
    // output roots, resources included, and stops at a 4 MB read cap. A bytecode tool's tests keep
    // a large sample named `.class` among their resources.
    @Test
    fun `a large class-named sample among the test resources stops the inline scan`(@TempDir dir: File) {
        val sampleBuild = forkedKotlinBuild + "\n" + """
            tasks.processTestResources {
                doLast {
                    val sample = destinationDir.resolve("samples/Huge.class")
                    sample.parentFile.mkdirs()
                    sample.writeBytes(ByteArray(5 * 1024 * 1024))
                }
            }
        """.trimIndent()
        val result = attempt(
            dir, "dev.demokt.A1InlinedTest", Aim.Forced("scan-exhausted"),
            listOf(
                inlined, inlinedTest, belowTenTest, alpha, alphaTest,
                // A resource of its own, or the copy task has no source and its action never runs.
                "src/test/resources/samples/README.txt" to "Samples a bytecode reader is tested on.\n",
            ),
            buildScript = sampleBuild,
        ) { changeAboveZero(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the classes of a changed source are looked up in the classpath's
    // directories and in own jars, which are recognised by a `.jar` name; the JDK's class loader
    // takes any zip. Tests run against the module's classes packed as `.zip` files in the build
    // directory, with no classes directory on the classpath.
    @Test
    fun `tests run against classes packed as zip files in the build directory refuse to narrow`(@TempDir dir: File) {
        val zippedBuild = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            """
            val packMain = tasks.register<Zip>("packMain") {
                from(sourceSets.main.get().output)
                archiveFileName.set("main.zip")
                destinationDirectory.set(layout.buildDirectory.dir("packed"))
            }
            val packTest = tasks.register<Zip>("packTest") {
                from(sourceSets.test.get().output)
                archiveFileName.set("test.zip")
                destinationDirectory.set(layout.buildDirectory.dir("packed"))
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
            buildScript = zippedBuild,
        ) { changeAlphaJava(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a Kotlin output with no SMAP anywhere cannot say who inlined a
    // change, and Kotlin writes one only into a class that inlined something. Here only Java tests
    // call the Kotlin code, as plain methods, so no class carries one though nothing was stripped.
    @Test
    fun `a Kotlin module only Java tests call carries no SMAP and refuses on a Kotlin change`(@TempDir dir: File) {
        val javaCaller = source(
            "src/test/java/dev/demokt/A1JavaCallerTest.java",
            """
            package dev.demokt;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class A1JavaCallerTest { @Test void positive() { assertTrue(InlinedKt.aboveZero(1)); } }
            """,
        )
        val javaAlpha = source(
            "src/test/java/dev/demokt/A4JavaAlphaTest.java",
            """
            package dev.demokt;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A4JavaAlphaTest { @Test void twice() { assertEquals(2, new Alpha().twice(1)); } }
            """,
        )
        val result = attempt(
            dir, "dev.demokt.A1JavaCallerTest", Aim.Forced("smap-absent"),
            listOf(inlined, javaCaller, alpha, javaAlpha),
            buildScript = forkedKotlinBuild,
        ) { changeAboveZero(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the SMAP names the class that holds the inlined body, and the
    // scan matches it against the changed file's prefixes. `@file:JvmName` gives the file's functions
    // a class named after neither, so the scan finds no caller: the caller, recompiled with the new
    // body, is held by the bytes comparison instead, which this attempt keeps watching.
    @Test
    fun `an inline function in a file renamed by JvmName selects the test it was copied into`(@TempDir dir: File) {
        val renamed = source(
            "src/main/kotlin/dev/demokt/Inlined.kt",
            """
            @file:JvmName("Checks")
            package dev.demokt
            inline fun aboveZero(n: Int): Boolean = n > 0
            fun belowTen(n: Int): Boolean = n < 10
            class Inlined { fun one(): Int = 1 }
            """,
        )
        // Executes the class named after the file, so the changed file is known to the map by its path.
        val inlinedClassTest = kotlinTest("A3InlinedClassTest", "@Test fun one() { assertEquals(1, Inlined().one()) }")
        val result = attempt(
            dir, "dev.demokt.A1InlinedTest", Aim.Row("reaches-change"),
            listOf(renamed, inlinedTest, belowTenTest, inlinedClassTest, alpha, alphaTest),
            buildScript = forkedKotlinBuild,
        ) { changeAboveZero(it) }

        assertOutcome(Outcome.OFF_TARGET, result)
    }
}
