package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on what a framework builds once and hands to every later test: a context, a mock, a
 * scan's view of annotations. The test that uses it executes little or none of the class it was
 * built from, so the JVM-wide rule, the annotation refusal and the engines that run everything are
 * what keep it selected.
 */
class FrameworkCachesAttempts : BreakAttempt() {

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

    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    /**
     * A test between the first one and the test that touches the change: the record taken between
     * two test classes is scoped to both, so whatever the next class does on entry would otherwise
     * select the first.
     */
    private val spacer = source(
        "src/test/java/dev/sample/A1SpacerTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1SpacerTest { @Test void spaces() { assertEquals(1, new Other().one()); } }
        """,
    )

    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", (Object) new Codec().encode("abc")); } }
        """,
    )

    /** [minimalBuild] with Codec's class file copied to a name that is not a class file, and [extra] in the test task. */
    private fun copiedCodecBuild(dependencies: String = "", extra: String = "") = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        dependencies { $dependencies }
        val copyCodec = tasks.register<Copy>("copyCodec") {
            dependsOn(tasks.compileJava)
            from(layout.buildDirectory.file("classes/java/main/dev/sample/Codec.class"))
            rename { "Codec.bytes" }
            into(layout.buildDirectory.dir("copies"))
        }
        tasks.test {
            useJUnitPlatform()
            dependsOn(copyCodec)
            systemProperty("codec.copy", layout.buildDirectory.file("copies/Codec.bytes").get().asFile.absolutePath)
            $extra
        }
        """.trimIndent(),
    )

    private fun copyReader(name: String) = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertFalse;
        class $name {
            @Test void noAppend() throws Exception {
                byte[] bytes = Files.readAllBytes(Paths.get(System.getProperty("codec.copy")));
                assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("append"));
            }
        }
        """,
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    // Track: fixtures. Row: FC2.
    @Test
    fun `a native library loaded by a class appended to the bootstrap search at run time selects the tests after it`(
        @TempDir dir: File,
    ) {
        val bootBuild = copiedCodecBuild(
            dependencies = """testImplementation("net.bytebuddy:byte-buddy-agent:1.15.11")""",
            extra = """
                dependsOn(bootJar)
                inputs.files(bootJar)
                jvmArgs("-Djdk.attach.allowAttachSelf=true")
                systemProperty("boot.jar", bootJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
            """.trimIndent(),
        ).replace(
            "dependencies { ",
            """
            val boot = sourceSets.create("boot")
            val bootJar = tasks.register<Jar>("bootJar") {
                archiveBaseName.set("boot")
                from(boot.output)
            }
            dependencies { testCompileOnly(boot.output);
            """.trimIndent(),
        )
        val loader = source(
            "src/boot/java/com/acme/nativecodec/Loader.java",
            """
            package com.acme.nativecodec;
            public final class Loader {
                public static boolean load(java.io.File file) {
                    try { System.load(file.getAbsolutePath()); return true; }
                    catch (UnsatisfiedLinkError absent) { return false; }
                }
            }
            """,
        )
        val appending = source(
            "src/test/java/dev/sample/A1AppendTest.java",
            """
            package dev.sample;
            import java.io.File;
            import java.util.jar.JarFile;
            import net.bytebuddy.agent.ByteBuddyAgent;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertNull;
            class A1AppendTest {
                @Test void loads() throws Exception {
                    ByteBuddyAgent.install().appendToBootstrapClassLoaderSearch(new JarFile(System.getProperty("boot.jar")));
                    assertNull(com.acme.nativecodec.Loader.class.getClassLoader());
                    assertFalse(com.acme.nativecodec.Loader.load(
                            new File(System.getProperty("java.io.tmpdir"), "libacmecodec.so")));
                }
            }
            """,
        )

        val result = attempt(
            dir, "dev.sample.A2ReaderTest", Aim.Row("shares-jvm-unobserved"),
            listOf(codec, other, first, loader, appending, copyReader("A2ReaderTest"), codecTest, classOrderByName),
            buildScript = bootBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the annotation digest covers every annotation a class
    // declares, members and annotation defaults included. A scan that reads an annotation left at
    // its default sees the default, which lives in the annotation type, not where it is used.
    @Test
    fun `a changed annotation default forces a full run`(@TempDir dir: File) {
        val config = source(
            "src/main/java/dev/sample/Config.java",
            """
            package dev.sample;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            public class Config {
                @Retention(RetentionPolicy.RUNTIME) public @interface Mode { String value() default "dev"; }
                public int one() { return 1; }
            }
            """,
        )
        val service = source(
            "src/main/java/dev/sample/Service.java",
            "package dev.sample;\n@Config.Mode public class Service { public int two() { return 2; } }",
        )
        val users = source(
            "src/test/java/dev/sample/A8UsersTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A8UsersTest { @Test void runs() { assertEquals(3, new Config().one() + new Service().two()); } }
            """,
        )
        val scan = source(
            "src/test/java/dev/sample/A1ScanTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1ScanTest { @Test void devMode() { assertEquals("dev", Service.class.getAnnotation(Config.Mode.class).value()); } }
            """,
        )

        val result = attempt(
            dir, "dev.sample.A1ScanTest", Aim.Forced("annotations-changed"),
            listOf(other, config, service, first, scan, users, classOrderByName),
        ) {
            val file = File(it, config.first)
            file.writeText(file.readText().replace("default \"dev\"", "default \"prod\""))
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a test class that declares a test and that nothing else names
    // is exempt from its own discovery-time load, and is dated only by what reaches it after
    // discovery: a lookup by name, a read, an execution from another class's window. A cache keyed
    // by that test class, which a later test finds by a name built at run time and never executes,
    // reaches it through the lookup alone; the byte search for names cannot see the name.
    @Test
    fun `a context cached under a test class selects a later test that looks it up by name`(@TempDir dir: File) {
        val cache = source(
            "src/test/java/dev/sample/ContextCache.java",
            """
            package dev.sample;
            import java.util.HashMap;
            import java.util.Map;
            import java.util.function.Supplier;
            public final class ContextCache {
                private static final Map<Class<?>, String> built = new HashMap<>();
                public static synchronized String get(Class<?> key, Supplier<String> build) {
                    return built.computeIfAbsent(key, k -> build.get());
                }
            }
            """,
        )
        val owner = source(
            "src/test/java/dev/sample/A2ContextTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class A2ContextTest {
                @Test void builds() { assertNotNull(ContextCache.get(A2ContextTest.class, () -> "hello")); }
            }
            """,
        )
        val reuser = source(
            "src/test/java/dev/sample/A3ReuseTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A3ReuseTest {
                @Test void reuses() throws Exception {
                    Class<?> owner = Class.forName(A3ReuseTest.class.getPackageName() + ".A2Context" + "Test");
                    assertEquals("hello", ContextCache.get(owner, () -> "hello"));
                }
            }
            """,
        )

        val result = attempt(
            dir, "dev.sample.A3ReuseTest", Aim.Row("shares-jvm-changed-class"),
            listOf(other, first, spacer, cache, owner, reuser, classOrderByName),
        ) {
            val file = File(it, owner.first)
            file.writeText(file.readText().replace("\"hello\"", "\"hullo\""))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a library load is judged by the class Runtime names as its
    // caller. Through a method reference that JDK stream code calls, that caller is a class the JDK
    // generated for the reference, and only resolving it to the class that wrote the reference
    // keeps the load from being taken for the JDK's own.
    @Test
    fun `a native library loaded through a method reference JDK code calls selects the tests after it`(
        @TempDir dir: File,
    ) {
        val loading = source(
            "src/test/java/dev/sample/A1LibraryTest.java",
            """
            package dev.sample;
            import java.io.File;
            import java.util.function.Consumer;
            import java.util.stream.Stream;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertThrows;
            class A1LibraryTest {
                @Test void loads() {
                    Consumer<String> load = System::load;
                    String absent = new File(System.getProperty("java.io.tmpdir"), "libacmecodec.so").getAbsolutePath();
                    assertThrows(UnsatisfiedLinkError.class, () -> Stream.of(absent).forEach(load));
                }
            }
            """,
        )

        val result = attempt(
            dir, "dev.sample.A2ReaderTest", Aim.Row("shares-jvm-unobserved"),
            listOf(codec, other, first, loading, copyReader("A2ReaderTest"), codecTest, classOrderByName),
            buildScript = copiedCodecBuild(),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a Kotest test is never left out, at any depth, because Kotest
    // runs its own spec list. Each test class here runs in a JVM of its own, so nothing but that
    // verdict can select the spec, which reaches the change through no coverage of its own.
    @Test
    fun `a Kotest spec beside a Jupiter test that is left out still runs`(@TempDir dir: File) {
        val kotestBuild = """
            plugins {
                kotlin("jvm") version "2.1.20"
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            kotlin { jvmToolchain(21) }
            val copyCodec = tasks.register<Copy>("copyCodec") {
                dependsOn(tasks.compileKotlin)
                from(layout.buildDirectory.file("classes/kotlin/main/dev/sample/Codec.class"))
                rename { "Codec.bytes" }
                into(layout.buildDirectory.dir("copies"))
            }
            tasks.test {
                useJUnitPlatform()
                forkEvery = 1
                dependsOn(copyCodec)
                systemProperty("codec.copy", layout.buildDirectory.file("copies/Codec.bytes").get().asFile.absolutePath)
                systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
                systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
            }
        """.trimIndent()
        val ktCodec = source(
            "src/main/kotlin/dev/sample/Codec.kt",
            "package dev.sample\n\nclass Codec { fun encode(s: String) = same(s).reversed() }\n",
        )
        val same = source("src/main/kotlin/dev/sample/Same.kt", "package dev.sample\n\ninline fun same(s: String) = s\n")
        val ktOther = source("src/main/kotlin/dev/sample/Other.kt", "package dev.sample\n\nclass Other { fun one() = 1 }\n")
        val jupiter = source(
            "src/test/kotlin/dev/sample/A0UnrelatedTest.kt",
            """
            package dev.sample

            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Test

            class A0UnrelatedTest { @Test fun runsFirst() { assertEquals(1, Other().one()) } }
            """,
        )
        val spec = source(
            "src/test/kotlin/dev/sample/A1ReaderSpec.kt",
            """
            package dev.sample

            import io.kotest.core.spec.style.StringSpec
            import io.kotest.matchers.shouldBe
            import java.io.File

            class A1ReaderSpec : StringSpec({
                "reads the copy" { String(File(System.getProperty("codec.copy")).readBytes(), Charsets.ISO_8859_1).contains("-x9") shouldBe false }
            })
            """,
        )
        val ktCodecTest = source(
            "src/test/kotlin/dev/sample/A9CodecTest.kt",
            """
            package dev.sample

            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Test

            class A9CodecTest { @Test fun reverses() { assertEquals("cba", Codec().encode("abc")) } }
            """,
        )

        val result = attempt(
            dir, "dev.sample.A1ReaderSpec", Aim.Row("engine-runs-everything"),
            listOf(ktCodec, same, ktOther, jupiter, spec, ktCodecTest),
            buildScript = kotestBuild,
        ) {
            val file = File(it, ktCodec.first)
            file.writeText(file.readText().replace("same(s).reversed()", "same(s).reversed() + \"-x9\""))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
