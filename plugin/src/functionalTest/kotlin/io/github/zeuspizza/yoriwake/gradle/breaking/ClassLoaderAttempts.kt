package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on how a class loader or a hidden-class definition dates the classes a JVM shares. A
 * class a loader of your own defines, or a hidden class your code defines, counts as touched when
 * it is defined, because it can be a second copy of a class the JVM already has; a class defined
 * from bytes whose name cannot be read reaches a class nobody can name, so every class; and a
 * changed class the map has no coverage for forces a full run.
 */
class ClassLoaderAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        "package dev.sample;\npublic class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
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

    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    /** Codec's class file copied to a name that is not a class file: reading it dates nothing. */
    private val copiedCodecBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
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
        }
        """.trimIndent(),
    )

    private val reader = source(
        "src/test/java/dev/sample/A2ReaderTest.java",
        """
        package dev.sample;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertFalse;
        class A2ReaderTest {
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

    // Track: code reading. Boundary: LoadObserver.transform files a definition by a loader that is
    // neither the application nor the platform loader as definedElsewhere, a named touch of the
    // class, because it can be a second copy. A URLClassLoader the test builds defines its own copy
    // of Codec from the project's own classes directory, so the copy dates Codec at that definition.
    // The target is the later test that reads what the copy computed and executes nothing of Codec.
    @Test
    fun `a copy of a changed class defined by a loader of the test's own dates that class`(@TempDir dir: File) {
        val results = source(
            "src/test/java/dev/sample/Results.java",
            "package dev.sample;\npublic final class Results { public static volatile Object encoded; }",
        )
        val readsResult = source(
            "src/test/java/dev/sample/A2ResultTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2ResultTest { @Test void encoded() { assertEquals("cba", Results.encoded); } }
            """,
        )
        val loaderTest = source(
            "src/test/java/dev/sample/A1LoaderTest.java",
            """
            package dev.sample;
            import java.io.File;
            import java.net.URL;
            import java.net.URLClassLoader;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1LoaderTest {
                // A loader that defines dev.sample.Codec itself rather than delegating for it.
                static final class Own extends URLClassLoader {
                    Own(URL[] urls) { super(urls, ClassLoader.getSystemClassLoader()); }
                    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (name.equals("dev.sample.Codec")) {
                            synchronized (getClassLoadingLock(name)) {
                                Class<?> found = findLoadedClass(name);
                                if (found == null) found = findClass(name);
                                if (resolve) resolveClass(found);
                                return found;
                            }
                        }
                        return super.loadClass(name, resolve);
                    }
                }
                @Test void definesOwnCopy() throws Exception {
                    File classes = new File(System.getProperty("codec.classes"));
                    try (Own own = new Own(new URL[]{ classes.toURI().toURL() })) {
                        Class<?> copy = own.loadClass("dev.sample.Codec");
                        assertEquals(A1LoaderTest.class.getClassLoader() != copy.getClassLoader(), true);
                        Results.encoded = copy.getMethod("encode", String.class)
                            .invoke(copy.getConstructor().newInstance(), "abc");
                    }
                }
            }
            """,
        )
        val build = copiedCodecBuild.replace(
            "systemProperty(\"codec.copy\",",
            "systemProperty(\"codec.classes\", layout.buildDirectory.dir(\"classes/java/main\").get().asFile.absolutePath)\n" +
                "            systemProperty(\"codec.copy\",",
        )
        val result = attempt(
            dir, "dev.sample.A2ResultTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, loaderTest, results, readsResult, codecTest, classOrderByName),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: TouchRecorder.defined reads the class name from the bytes with
    // nameIn; when that returns null the bytes reach a class nobody can name, so it records
    // TOUCH_ALL. A test that hands defineHiddenClass bytes that are not a class file makes nameIn
    // fail, so its JVM is unobserved from there, and the later reader is selected.
    @Test
    fun `bytes with no readable class name make the JVM unobserved from that point`(@TempDir dir: File) {
        val definer = source(
            "src/test/java/dev/sample/A1UnnamedTest.java",
            """
            package dev.sample;
            import java.lang.invoke.MethodHandles;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class A1UnnamedTest {
                @Test void defines() {
                    boolean threw = false;
                    try {
                        MethodHandles.lookup().defineHiddenClass(new byte[]{1, 2, 3, 4}, true);
                    } catch (Throwable expected) {
                        threw = true;
                    }
                    assertTrue(threw);
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2ReaderTest", Aim.Row("shares-jvm-unobserved"),
            listOf(codec, other, first, definer, reader, codecTest, classOrderByName),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a class on the bootstrap loader is loaded with a null loader,
    // which LoadObserver.transform does not record, and JaCoCo does not instrument, so the map holds
    // no coverage for it. Selector then cannot tell uncovered from unrecorded and forces a full run
    // (no-coverage-for-changed) rather than letting a test that depends on the change be skipped.
    @Test
    fun `a change to a class on the boot class path forces a full run`(@TempDir dir: File) {
        val bootBuild = """
            plugins {
                java
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            val boot = sourceSets.create("boot")
            val bootJar = tasks.register<Jar>("bootJar") {
                archiveBaseName.set("boot")
                from(boot.output)
            }
            dependencies {
                testImplementation(boot.output)
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            val bootPath = bootJar.flatMap { it.archiveFile }.get().asFile.absolutePath
            tasks.test {
                useJUnitPlatform()
                dependsOn(bootJar)
                inputs.files(bootJar)
                jvmArgs("-Xbootclasspath/a:" + bootPath)
            }
        """.trimIndent()
        val bootCodec = source(
            "src/boot/java/com/acme/BootCodec.java",
            "package com.acme;\npublic final class BootCodec { public static String name() { return \"codec\"; } }",
        )
        val bootFirst = source(
            "src/test/java/dev/sample/B0UnrelatedTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNull;
            class B0UnrelatedTest {
                // Proves BootCodec really came from the bootstrap loader.
                @Test void fromBoot() { assertNull(com.acme.BootCodec.class.getClassLoader()); }
            }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/B1BootTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class B1BootTest {
                @Test void exactName() { assertEquals("codec", com.acme.BootCodec.name()); }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.B1BootTest", Aim.Forced("no-coverage-for-changed"),
            listOf(bootCodec, other, bootFirst, target, classOrderByName),
            buildScript = bootBuild,
        ) { d ->
            val file = File(d, bootCodec.first)
            file.writeText(file.readText().replace("\"codec\"", "\"codec!\""))
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }
}
