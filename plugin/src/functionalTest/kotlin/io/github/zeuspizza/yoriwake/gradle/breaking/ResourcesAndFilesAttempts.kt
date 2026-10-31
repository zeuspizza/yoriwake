package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on reading a class's file rather than running the class, and on the files around the
 * classes: resources, fixtures, generated files on the classpath and the working tree. A read of a
 * class file dates the test by that class, wherever the read is made from, unless it is the JDK
 * reading the file to define the class, which the class's load already records.
 */
class ResourcesAndFilesAttempts : BreakAttempt() {

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

    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    // Track: code reading. Boundary: the read happens under the JDK's defineClass frame, because
    // the JDK calls the loader's getPermissions while it defines Other. The loader is the test's
    // own code, standing between the read and the definition, so the read is the test's.
    @Test
    fun `a class file read from a loader's getPermissions is held by the read`(@TempDir dir: File) {
        val reader = source(
            "src/test/java/dev/sample/A1PermissionsReaderTest.java",
            """
            package dev.sample;
            import java.io.FileInputStream;
            import java.io.IOException;
            import java.io.UncheckedIOException;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.security.CodeSource;
            import java.security.PermissionCollection;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1PermissionsReaderTest {
                static String bytes;
                @Test void noAppend() throws Exception {
                    URL here = Other.class.getProtectionDomain().getCodeSource().getLocation();
                    Path codecFile = Paths.get(here.toURI()).resolve("dev/sample/Codec.class");
                    try (URLClassLoader loader = new URLClassLoader(new URL[] {here}, null) {
                        @Override protected PermissionCollection getPermissions(CodeSource cs) {
                            try (FileInputStream in = new FileInputStream(codecFile.toFile())) {
                                bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            return super.getPermissions(cs);
                        }
                    }) {
                        loader.loadClass("dev.sample.Other");
                    }
                    assertFalse(bytes.contains("append"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1PermissionsReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, reader, codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
    /** A test that runs the codec itself, second by name: anything that skips it skipped a reach. */
    private val codecRunner = source(
        "src/test/java/dev/sample/A1CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    /** [minimalBuild] with [extra] inside the test task's block and [after] below it. */
    private fun buildWith(extra: String, after: String = "") = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test {\n    useJUnitPlatform()\n" + extra.trimIndent().prependIndent("    ") + "\n}\n" + after.trimIndent(),
    )

    /** The directory the main classes are on the classpath from, as Java code in a test computes it. */
    private val mainDirJava = "Paths.get(Other.class.getProtectionDomain().getCodeSource().getLocation().toURI())"

    // Track: fixtures. Row: RF16.
    @Test
    fun `a class file read from a loader's definePackage override is held by the read`(@TempDir dir: File) {
        val reader = source(
            "src/test/java/dev/sample/A1DefinePackageReaderTest.java",
            """
            package dev.sample;
            import java.io.FileInputStream;
            import java.io.IOException;
            import java.io.UncheckedIOException;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.util.jar.Manifest;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1DefinePackageReaderTest {
                static String bytes;
                @Test void noAppend() throws Exception {
                    Path codecFile = $mainDirJava.resolve("dev/sample/Codec.class");
                    URL jar = Paths.get(System.getProperty("main.jar")).toUri().toURL();
                    try (URLClassLoader loader = new URLClassLoader(new URL[] {jar}, null) {
                        @Override protected Package definePackage(String name, Manifest man, URL url) {
                            try (FileInputStream in = new FileInputStream(codecFile.toFile())) {
                                bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            return super.definePackage(name, man, url);
                        }
                    }) {
                        loader.loadClass("dev.sample.Other");
                    }
                    assertFalse(bytes.contains("append"));
                }
            }
            """,
        )
        val jarBuild = buildWith(
            """
            dependsOn(tasks.jar)
            systemProperty("main.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1DefinePackageReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, reader, codecTest, classOrderByName), buildScript = jarBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: RF17.
    @Test
    fun `a class file read from a permission collection's setReadOnly is held by the read`(@TempDir dir: File) {
        val reader = source(
            "src/test/java/dev/sample/A1ReadOnlyReaderTest.java",
            """
            package dev.sample;
            import java.io.FileInputStream;
            import java.io.IOException;
            import java.io.UncheckedIOException;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.security.CodeSource;
            import java.security.Permission;
            import java.security.PermissionCollection;
            import java.util.Collections;
            import java.util.Enumeration;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1ReadOnlyReaderTest {
                static String bytes;
                @Test void noAppend() throws Exception {
                    URL here = Other.class.getProtectionDomain().getCodeSource().getLocation();
                    Path codecFile = Paths.get(here.toURI()).resolve("dev/sample/Codec.class");
                    PermissionCollection none = new PermissionCollection() {
                        @Override public void add(Permission permission) {}
                        @Override public boolean implies(Permission permission) { return false; }
                        @Override public Enumeration<Permission> elements() { return Collections.emptyEnumeration(); }
                        @Override public void setReadOnly() {
                            try (FileInputStream in = new FileInputStream(codecFile.toFile())) {
                                bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            super.setReadOnly();
                        }
                    };
                    try (URLClassLoader loader = new URLClassLoader(new URL[] {here}, null) {
                        @Override protected PermissionCollection getPermissions(CodeSource cs) { return none; }
                    }) {
                        loader.loadClass("dev.sample.Other");
                    }
                    assertFalse(bytes.contains("append"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ReadOnlyReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, reader, codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: RF18.
    @Test
    fun `a class file a loader reads while the JVM resolves a superclass is held by the read`(@TempDir dir: File) {
        val sub = source(
            "src/main/java/dev/sample/Sub.java",
            "package dev.sample;\npublic class Sub extends Codec {}",
        )
        val reader = source(
            "src/test/java/dev/sample/A1SuperclassReaderTest.java",
            """
            package dev.sample;
            import java.io.FileInputStream;
            import java.io.IOException;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1SuperclassReaderTest {
                static String bytes;
                @Test void noAppend() throws Exception {
                    Path main = $mainDirJava;
                    ClassLoader loader = new ClassLoader(null) {
                        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                            if (!name.startsWith("dev.sample.")) throw new ClassNotFoundException(name);
                            try (FileInputStream in = new FileInputStream(main.resolve(name.replace('.', '/') + ".class").toFile())) {
                                byte[] read = in.readAllBytes();
                                if (name.equals("dev.sample.Codec")) bytes = new String(read, StandardCharsets.ISO_8859_1);
                                return defineClass(name, read, 0, read.length);
                            } catch (IOException e) {
                                throw new ClassNotFoundException(name, e);
                            }
                        }
                    };
                    loader.loadClass("dev.sample.Sub");
                    assertFalse(bytes.contains("append"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1SuperclassReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, sub, first, reader, codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: RF19.
    @Test
    fun `a class file a URL handler reads while its loader defines a class is held by the read`(@TempDir dir: File) {
        val reader = source(
            "src/test/java/dev/sample/A1HandlerReaderTest.java",
            """
            package dev.sample;
            import java.io.IOException;
            import java.io.InputStream;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.net.URLConnection;
            import java.net.URLStreamHandler;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.util.concurrent.atomic.AtomicInteger;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1HandlerReaderTest {
                static String bytes;
                @Test void noAppend() throws Exception {
                    Path main = $mainDirJava;
                    AtomicInteger opened = new AtomicInteger();
                    URLStreamHandler handler = new URLStreamHandler() {
                        @Override protected URLConnection openConnection(URL u) {
                            return new URLConnection(u) {
                                @Override public void connect() {}
                                @Override public InputStream getInputStream() throws IOException {
                                    String path = u.getPath().substring("/classes/".length());
                                    // The loader opens the stream once to find the class and again,
                                    // inside its definition, to read it: the second is the one under test.
                                    if (path.equals("dev/sample/Other.class") && opened.incrementAndGet() > 1) {
                                        bytes = new String(Files.readAllBytes(main.resolve("dev/sample/Codec.class")),
                                                StandardCharsets.ISO_8859_1);
                                    }
                                    return Files.newInputStream(main.resolve(path));
                                }
                            };
                        }
                    };
                    @SuppressWarnings("deprecation")
                    URL root = new URL("yoriwake-test", "", -1, "/classes/", handler);
                    try (URLClassLoader loader = new URLClassLoader(new URL[] {root}, null)) {
                        loader.loadClass("dev.sample.Other");
                    }
                    assertFalse(bytes.contains("append"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1HandlerReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, reader, codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: RF20.
    @Test
    fun `a class file another agent's transformer reads during a definition is held by the read`(@TempDir dir: File) {
        val trigger = source("src/main/java/dev/sample/Trigger.java", "package dev.sample;\npublic class Trigger {}")
        val probe = source(
            "src/probe/java/dev/probe/Premain.java",
            """
            package dev.probe;
            import java.lang.instrument.ClassFileTransformer;
            import java.lang.instrument.Instrumentation;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import java.security.ProtectionDomain;
            public class Premain {
                public static void premain(String args, Instrumentation instrumentation) {
                    instrumentation.addTransformer(new ClassFileTransformer() {
                        @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                                ProtectionDomain domain, byte[] bytes) {
                            if ("dev/sample/Trigger".equals(name)) {
                                try {
                                    System.setProperty("probe.codec", new String(
                                            Files.readAllBytes(Paths.get(System.getProperty("codec.class"))),
                                            StandardCharsets.ISO_8859_1));
                                } catch (Exception e) {
                                    System.setProperty("probe.codec", "append: " + e);
                                }
                            }
                            return null;
                        }
                    });
                }
            }
            """,
        )
        val reader = source(
            "src/test/java/dev/sample/A1TransformerReaderTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1TransformerReaderTest {
                @Test void noAppend() throws Exception {
                    Class.forName("dev.sample.Trigger");
                    assertFalse(System.getProperty("probe.codec").contains("append"));
                }
            }
            """,
        )
        val agentBuild = buildWith(
            """
            dependsOn(probeJar)
            jvmArgs("-javaagent:" + layout.buildDirectory.file("probe/probe.jar").get().asFile.absolutePath)
            systemProperty("codec.class", layout.buildDirectory.file("classes/java/main/dev/sample/Codec.class").get().asFile.absolutePath)
            """,
        ).replace(
            "tasks.test {",
            """
            val probe by sourceSets.creating
            val probeJar = tasks.register<Jar>("probeJar") {
                from(probe.output)
                archiveFileName.set("probe.jar")
                destinationDirectory.set(layout.buildDirectory.dir("probe"))
                manifest { attributes("Premain-Class" to "dev.probe.Premain") }
            }
            tasks.test {
            """.trimIndent(),
        )
        val result = attempt(
            dir, "dev.sample.A1TransformerReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, trigger, probe, first, reader, codecTest, classOrderByName), buildScript = agentBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: RF21.
    @Test
    fun `a class file a module reader reads while a layer's loader defines a class is held by the read`(
        @TempDir dir: File,
    ) {
        val moduleClass = source("src/main/java/m/Mod.java", "package m;\npublic class Mod {}")
        val reader = source(
            "src/test/java/dev/sample/A1ModuleReaderTest.java",
            """
            package dev.sample;
            import java.io.IOException;
            import java.lang.module.Configuration;
            import java.lang.module.ModuleDescriptor;
            import java.lang.module.ModuleFinder;
            import java.lang.module.ModuleReader;
            import java.lang.module.ModuleReference;
            import java.net.URI;
            import java.nio.ByteBuffer;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.util.Optional;
            import java.util.Set;
            import java.util.stream.Stream;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1ModuleReaderTest {
                static String bytes;
                @Test void noAppend() throws Exception {
                    Path main = $mainDirJava;
                    ModuleDescriptor descriptor = ModuleDescriptor.newModule("m").packages(Set.of("m")).build();
                    ModuleReference reference = new ModuleReference(descriptor, main.toUri()) {
                        @Override public ModuleReader open() {
                            return new ModuleReader() {
                                @Override public Optional<URI> find(String name) {
                                    Path file = main.resolve(name);
                                    return Files.isRegularFile(file) ? Optional.of(file.toUri()) : Optional.empty();
                                }
                                @Override public Optional<ByteBuffer> read(String name) throws IOException {
                                    Path file = main.resolve(name);
                                    if (!Files.isRegularFile(file)) return Optional.empty();
                                    if (name.equals("m/Mod.class")) {
                                        bytes = new String(Files.readAllBytes(main.resolve("dev/sample/Codec.class")),
                                                StandardCharsets.ISO_8859_1);
                                    }
                                    return Optional.of(ByteBuffer.wrap(Files.readAllBytes(file)));
                                }
                                @Override public Stream<String> list() { return Stream.empty(); }
                                @Override public void close() {}
                            };
                        }
                    };
                    ModuleFinder finder = new ModuleFinder() {
                        @Override public Optional<ModuleReference> find(String name) {
                            return "m".equals(name) ? Optional.of(reference) : Optional.empty();
                        }
                        @Override public Set<ModuleReference> findAll() { return Set.of(reference); }
                    };
                    Configuration configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), Set.of("m"));
                    ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
                    layer.findLoader("m").loadClass("m.Mod");
                    assertFalse(bytes.contains("append"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ModuleReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, moduleClass, first, reader, codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the classpath files are digested in yoriwake's own doFirst
    // action, which Gradle runs before the build script's own doFirst actions. A build that stamps
    // `git describe --dirty` into a test resource from its test task's doFirst writes the file after
    // the digest, so the selecting run compares the placeholder processTestResources restored.
    @Test
    fun `a build-info file the test task's doFirst stamps from git runs the test that reads it`(@TempDir dir: File) {
        val placeholder = source("src/test/resources/build-info.properties", "describe=unknown\n")
        val reader = source(
            "src/test/java/dev/sample/A1BuildInfoTest.java",
            """
            package dev.sample;
            import java.io.InputStream;
            import java.util.Properties;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1BuildInfoTest {
                @Test void builtFromACleanTree() throws Exception {
                    Properties info = new Properties();
                    try (InputStream in = getClass().getResourceAsStream("/build-info.properties")) { info.load(in); }
                    assertFalse(info.getProperty("describe").endsWith("-dirty"), info.toString());
                }
            }
            """,
        )
        val stampingBuild = buildWith(
            """
            val info = layout.buildDirectory.file("resources/test/build-info.properties").get().asFile
            val root = rootDir
            doFirst {
                val git = ProcessBuilder("git", "describe", "--always", "--dirty").directory(root).redirectErrorStream(true).start()
                val describe = git.inputStream.bufferedReader().readText().trim()
                git.waitFor()
                info.writeText("describe=" + describe + "\n")
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1BuildInfoTest", Aim.Forced("classpath-files-changed"),
            listOf(codec, other, first, placeholder, reader, codecTest, classOrderByName), buildScript = stampingBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the classpath-files walk stops at 200,000 files and then
    // records no table, so the next selecting run must force as unrecorded rather than compare
    // against nothing. A generated jar of 200,001 entries stands one file past the budget.
    @Test
    fun `a classpath one file over the digest budget runs everything as unrecorded`(@TempDir dir: File) {
        // `java.util` would resolve `java` to the project's java extension, so the zip types are imported.
        val budgetBuild = "import java.util.zip.ZipEntry\nimport java.util.zip.ZipOutputStream\n" + buildWith(
            "",
            after = """
            val manyJar = tasks.register("manyJar") {
                val out = layout.buildDirectory.file("many/many.jar")
                outputs.file(out)
                doLast {
                    val jar = out.get().asFile
                    jar.parentFile.mkdirs()
                    ZipOutputStream(jar.outputStream().buffered()).use { zip ->
                        for (i in 0..200_000) {
                            zip.putNextEntry(ZipEntry("many/f" + i + ".txt"))
                            zip.write('x'.code)
                            zip.closeEntry()
                        }
                    }
                }
            }
            dependencies { testRuntimeOnly(files(manyJar)) }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1CodecTest", Aim.Forced("classpath-files-unrecorded"),
            listOf(codec, other, first, codecRunner, classOrderByName), buildScript = budgetBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a read counts as a read of every class in an archive only when
    // the opened path ends in `.jar`; a scanner reading a `.zip` of the compiled classes with its
    // own zip code opens a file that names no class, and reads entries no hook sees.
    @Test
    fun `a class read out of a zip of the classes with the test's own zip code is held by the read`(@TempDir dir: File) {
        val scanner = source(
            "src/test/java/dev/sample/A1ZipScanTest.java",
            """
            package dev.sample;
            import java.io.FileInputStream;
            import java.nio.charset.StandardCharsets;
            import java.util.zip.ZipEntry;
            import java.util.zip.ZipInputStream;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class A1ZipScanTest {
                @Test void noAppend() throws Exception {
                    String codec = null;
                    try (ZipInputStream zip = new ZipInputStream(new FileInputStream(System.getProperty("classes.zip")))) {
                        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                            if (entry.getName().equals("dev/sample/Codec.class")) {
                                codec = new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1);
                            }
                        }
                    }
                    assertNotNull(codec);
                    assertFalse(codec.contains("append"));
                }
            }
            """,
        )
        val zipBuild = buildWith(
            """
            dependsOn(classesZip)
            systemProperty("classes.zip", layout.buildDirectory.file("dist/classes.zip").get().asFile.absolutePath)
            """,
        ).replace(
            "tasks.test {",
            """
            val classesZip = tasks.register<Zip>("classesZip") {
                from(sourceSets.main.get().output.classesDirs)
                archiveFileName.set("classes.zip")
                destinationDirectory.set(layout.buildDirectory.dir("dist"))
            }
            tasks.test {
            """.trimIndent(),
        )
        val result = attempt(
            dir, "dev.sample.A1ZipScanTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, scanner, codecTest, classOrderByName), buildScript = zipBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a changed path that is not a build input stops forcing when no
    // compiled class names it, or any directory above it, since a test can only read a file it can
    // name. A test that walks the project for its Markdown files names none of them.
    @Test
    fun `a Markdown file a docs test finds by walking the project runs that test when it changes`(@TempDir dir: File) {
        val notes = source("notes.md", "# Notes\n\nAll done.\n")
        val docs = source(
            "src/test/java/dev/sample/A1DocsTest.java",
            """
            package dev.sample;
            import java.io.IOException;
            import java.nio.file.FileVisitResult;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.nio.file.SimpleFileVisitor;
            import java.nio.file.attribute.BasicFileAttributes;
            import java.util.ArrayList;
            import java.util.List;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class A1DocsTest {
                @Test void noOpenItems() throws Exception {
                    List<Path> docs = new ArrayList<>();
                    Files.walkFileTree(Paths.get("").toAbsolutePath(), new SimpleFileVisitor<Path>() {
                        @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                            String name = d.getFileName().toString();
                            return name.startsWith(".") || name.equals("build") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            if (f.getFileName().toString().endsWith(".md")) docs.add(f);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    assertTrue(!docs.isEmpty());
                    for (Path doc : docs) {
                        assertFalse(Files.readString(doc).contains("TODO"), doc.toString());
                    }
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1DocsTest", Aim.Forced("unmappable-paths"),
            listOf(codec, other, first, notes, docs, codecTest, classOrderByName),
        ) {
            changeCodec(it)
            File(it, "notes.md").appendText("\nTODO: one more thing.\n")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: generated classpath files count by content, a jar by each
    // entry's content, never by timestamp or whole-file digest. A resource regenerated every build
    // with the same bytes, and a jar of it rebuilt with fresh entry timestamps, move nothing, so the
    // change narrows on what the target executes.
    @Test
    fun `a resource regenerated with the same bytes leaves the test that runs a changed class selected by its reach`(
        @TempDir dir: File,
    ) {
        val reader = source(
            "src/test/java/dev/sample/A1CodecTest.java",
            """
            package dev.sample;
            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1CodecTest {
                @Test void reverses() throws Exception {
                    try (InputStream in = getClass().getResourceAsStream("/greeting.txt")) {
                        assertEquals("hello\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    assertEquals("cba", new Codec().encode("abc"));
                }
            }
            """,
        )
        val regeneratingBuild = buildWith(
            "",
            after = """
            val stampGreeting = tasks.register("stampGreeting") {
                val out = layout.buildDirectory.file("gen/greeting/greeting.txt")
                outputs.file(out)
                outputs.upToDateWhen { false }
                doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText("hello\n") }
            }
            val greetingJar = tasks.register<Jar>("greetingJar") {
                dependsOn(stampGreeting)
                from(layout.buildDirectory.dir("gen/greeting"))
                archiveFileName.set("greeting.jar")
                destinationDirectory.set(layout.buildDirectory.dir("gen/jar"))
                outputs.upToDateWhen { false }
            }
            sourceSets.test { resources.srcDir(layout.buildDirectory.dir("gen/greeting")) }
            tasks.processTestResources { dependsOn(stampGreeting) }
            dependencies { testRuntimeOnly(files(greetingJar)) }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1CodecTest", Aim.Row("reaches-change"),
            listOf(codec, other, first, reader, classOrderByName), buildScript = regeneratingBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the stale check drops paths in a `.gradle` directory as the
    // build's own state, but never a tracked one. A test fixture of a Gradle project, committed
    // with its `.gradle` folder under the test resources, is edited and a build task restores it
    // after configuration; a check that read the folder name as noise would select on a change set
    // the tests no longer see.
    @Test
    fun `a tracked fixture in a dot-gradle folder a build task restores after configuration runs everything as stale`(
        @TempDir dir: File,
    ) {
        val fixture = "src/test/resources/project/.gradle/fixture.properties"
        val result = attempt(
            dir, "dev.sample.A1CodecTest", Aim.Forced("change-set-stale"),
            listOf(
                codec, other, first, codecRunner, fixture to "mode=plain\n",
                // The fixture's own ignore file keeps its folder, as a committed fixture needs.
                "src/test/resources/project/.gitignore" to "!.gradle/\n", classOrderByName,
            ),
            buildScript = buildWith(
                "",
                after = """
                val restoreFixture by tasks.registering(Exec::class) {
                    commandLine("git", "checkout", "--", "$fixture")
                }
                tasks.processTestResources { dependsOn(restoreFixture) }
                """,
            ),
        ) { dir ->
            changeCodec(dir)
            File(dir, fixture).writeText("mode=strict\n")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    /**
     * Kotlin for a task action that sets `state` to `clean` when the working tree has no change and
     * to `dirty` otherwise: a build-produced file that moves with no tracked change behind it.
     */
    private val treeState = """
        val git = ProcessBuilder("git", "status", "--porcelain").directory(root).redirectErrorStream(true).start()
        val state = if (git.inputStream.bufferedReader().readText().isBlank()) "clean" else "dirty"
        git.waitFor()
    """.trimIndent()

    /** A test resource directory `info.properties` is generated into, holding `state=` [treeState]. */
    private val infoBuild = buildWith(
        "",
        after = """
        val stampInfo = tasks.register("stampInfo") {
            val out = layout.buildDirectory.file("gen/info/info.properties")
            val root = rootDir
            outputs.file(out)
            outputs.upToDateWhen { false }
            doLast {
        """.trimIndent() + "\n" + treeState.prependIndent("        ") + "\n" + """
                out.get().asFile.apply { parentFile.mkdirs() }.writeText("state=" + state + "\n")
            }
        }
        """.trimIndent(),
    )

    // Track: code reading. Boundary: a jar entry read is recorded under the name the JDK's zip code
    // hands out. A multi-release jar's versioned entry answers to its base name, while the classpath
    // walk digests it under its versioned path, so the read and the moved file never meet.
    @Test
    fun `a versioned resource of a multi-release jar runs the test that reads it when it changed`(@TempDir dir: File) {
        val reader = source(
            "src/test/java/dev/sample/A1VersionedResourceTest.java",
            """
            package dev.sample;
            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1VersionedResourceTest {
                @Test void builtFromACleanTree() throws Exception {
                    try (InputStream in = getClass().getResourceAsStream("/greeting.txt")) {
                        assertEquals("clean", new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
                    }
                }
            }
            """,
        )
        val versionedBuild = buildWith(
            "",
            after = """
            val stampVersioned = tasks.register("stampVersioned") {
                val out = layout.buildDirectory.dir("gen/versioned")
                val root = rootDir
                outputs.dir(out)
                outputs.upToDateWhen { false }
                doLast {
            """.trimIndent() + "\n" + treeState.prependIndent("        ") + "\n" + """
                    val base = out.get().asFile
                    base.resolve("greeting.txt").apply { parentFile.mkdirs() }.writeText("base\n")
                    base.resolve("META-INF/versions/9/greeting.txt").apply { parentFile.mkdirs() }.writeText(state + "\n")
                }
            }
            val versionedJar = tasks.register<Jar>("versionedJar") {
                from(stampVersioned)
                archiveFileName.set("versioned.jar")
                destinationDirectory.set(layout.buildDirectory.dir("gen/jar"))
                manifest { attributes("Multi-Release" to "true") }
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }
            dependencies { testRuntimeOnly(files(versionedJar)) }
            """.trimIndent(),
        )
        val result = attempt(
            dir, "dev.sample.A1VersionedResourceTest", Aim.Row("shares-jvm-moved-classpath-file"),
            listOf(codec, other, first, reader, codecTest, classOrderByName), buildScript = versionedBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a changed archive in a classpath directory runs everything,
    // since it is digested whole but read by its entries; it is told an archive by its name or by
    // its first two bytes. An archive behind a launch script starts with the script, so it is
    // handed over as a plain file, and a test that opens it with the JDK's zip code reads only its
    // entries.
    @Test
    fun `a zip behind a launch script in a resource directory runs the test that reads it when it changed`(
        @TempDir dir: File,
    ) {
        val reader = source(
            "src/test/java/dev/sample/A1LauncherTest.java",
            """
            package dev.sample;
            import java.io.File;
            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;
            import java.util.zip.ZipFile;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1LauncherTest {
                @Test void builtFromACleanTree() throws Exception {
                    File launcher = new File(getClass().getResource("/launcher").toURI());
                    try (ZipFile zip = new ZipFile(launcher);
                            InputStream in = zip.getInputStream(zip.getEntry("greeting.txt"))) {
                        assertEquals("clean", new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
                    }
                }
            }
            """,
        )
        val launcherBuild = "import java.util.zip.ZipEntry\nimport java.util.zip.ZipOutputStream\n" + buildWith(
            "",
            after = """
            val stampLauncher = tasks.register("stampLauncher") {
                val out = layout.buildDirectory.file("gen/launcher/launcher")
                val root = rootDir
                outputs.file(out)
                outputs.upToDateWhen { false }
                doLast {
            """.trimIndent() + "\n" + treeState.prependIndent("        ") + "\n" + """
                    val file = out.get().asFile
                    file.parentFile.mkdirs()
                    file.outputStream().use { stream ->
                        stream.write("#!/bin/sh\n# runs the archive appended to this script\n".toByteArray())
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("greeting.txt").apply { time = 0L })
                            zip.write((state + "\n").toByteArray())
                            zip.closeEntry()
                        }
                    }
                }
            }
            sourceSets.test { resources.srcDir(layout.buildDirectory.dir("gen/launcher")) }
            tasks.processTestResources { dependsOn(stampLauncher) }
            """.trimIndent(),
        )
        val result = attempt(
            dir, "dev.sample.A1LauncherTest", Aim.Forced("classpath-files-changed"),
            listOf(codec, other, first, reader, codecTest, classOrderByName), buildScript = launcherBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a moved file selects from its first read in each JVM on, not
    // only the test that read it. A later test that uses the value an earlier test read and cached
    // reads no file itself, and runs at or after that read.
    @Test
    fun `a generated value an earlier test cached runs the later test that uses it`(@TempDir dir: File) {
        val info = source(
            "src/test/java/dev/sample/Info.java",
            """
            package dev.sample;
            import java.io.IOException;
            import java.io.InputStream;
            import java.util.Properties;
            final class Info {
                private static String state;
                static synchronized String state() throws IOException {
                    if (state == null) {
                        Properties properties = new Properties();
                        try (InputStream in = Info.class.getResourceAsStream("/info.properties")) { properties.load(in); }
                        state = properties.getProperty("state");
                    }
                    return state;
                }
            }
            """,
        )
        val warmup = source(
            "src/test/java/dev/sample/A1InfoWarmupTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class A1InfoWarmupTest { @Test void reads() throws Exception { assertNotNull(Info.state()); } }
            """,
        )
        val cached = source(
            "src/test/java/dev/sample/A2CachedInfoTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2CachedInfoTest { @Test void builtFromACleanTree() throws Exception { assertEquals("clean", Info.state()); } }
            """,
        )
        val build = infoBuild + "\n" + """
            sourceSets.test { resources.srcDir(layout.buildDirectory.dir("gen/info")) }
            tasks.processTestResources { dependsOn(stampInfo) }
        """.trimIndent()
        val result = attempt(
            dir, "dev.sample.A2CachedInfoTest", Aim.Row("shares-jvm-moved-classpath-file"),
            listOf(codec, other, first, info, warmup, cached, codecTest, classOrderByName), buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: an archive the JDK's zip code opens is skipped as a whole read,
    // since its entries are hooked; the zip file system reads the jar through its own code over a
    // channel, so the jar counts as read whole, and every entry of it with it.
    @Test
    fun `a generated jar a test reads through the zip file system runs that test when an entry changed`(
        @TempDir dir: File,
    ) {
        val reader = source(
            "src/test/java/dev/sample/A1ZipFileSystemTest.java",
            """
            package dev.sample;
            import java.nio.file.FileSystem;
            import java.nio.file.FileSystems;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class A1ZipFileSystemTest {
                @Test void builtFromACleanTree() throws Exception {
                    try (FileSystem jar = FileSystems.newFileSystem(Paths.get(System.getProperty("info.jar")), (ClassLoader) null)) {
                        String text = new String(Files.readAllBytes(jar.getPath("/info.properties")), "UTF-8");
                        assertTrue(text.contains("state=clean"), text);
                    }
                }
            }
            """,
        )
        val build = infoBuild.replace(
            "tasks.test {\n    useJUnitPlatform()\n",
            "tasks.test {\n    useJUnitPlatform()\n    dependsOn(\"infoJar\")\n" +
                "    systemProperty(\"info.jar\", layout.buildDirectory.file(\"gen/jar/info.jar\").get().asFile.absolutePath)\n",
        ) + "\n" + """
            val infoJar = tasks.register<Jar>("infoJar") {
                from(stampInfo)
                archiveFileName.set("info.jar")
                destinationDirectory.set(layout.buildDirectory.dir("gen/jar"))
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }
            dependencies { testRuntimeOnly(files(infoJar)) }
        """.trimIndent()
        val result = attempt(
            dir, "dev.sample.A1ZipFileSystemTest", Aim.Row("shares-jvm-moved-classpath-file"),
            listOf(codec, other, first, reader, codecTest, classOrderByName), buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    /** A jar at [file] whose manifest's `Class-Path` is [classPath] (none when null), holding [entries]. */
    private fun manifestJar(file: File, classPath: String?, vararg entries: Pair<String, String>) {
        file.parentFile.mkdirs()
        val manifest = java.util.jar.Manifest()
        manifest.mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
        if (classPath != null) manifest.mainAttributes[java.util.jar.Attributes.Name.CLASS_PATH] = classPath
        java.util.jar.JarOutputStream(file.outputStream(), manifest).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    /** A test, first by name, that asserts [key] of the classpath resource [resource] is [expected]. */
    private fun propertyTest(name: String, resource: String, key: String, expected: String) = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import java.io.InputStream;
        import java.util.Properties;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class $name {
            @Test void reads() throws Exception {
                Properties properties = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/$resource")) {
                    assertNotNull(in, "$resource is not on the class path");
                    properties.load(in);
                }
                assertEquals("$expected", properties.getProperty("$key"));
            }
        }
        """,
    )

    /** [file] as a `Class-Path` token: an absolute `file:` URL, ending in `/` for a directory. */
    private fun urlOf(file: File, directory: Boolean = false): String =
        file.absoluteFile.toURI().toString().trimEnd('/') + if (directory) "/" else ""

    // Track: code reading. Boundary: a file a dependency's manifest names gets no row when it is
    // already on the class path; what changes there is the class rules' to judge. Here a vendor jar
    // outside the root names the module's own classes directory, so a changed class must still
    // select by its reach and not move the dependency rows.
    @Test
    fun `a dependency whose manifest names the module's own classes leaves a changed class to its reach`(
        @TempDir dir: File,
        @TempDir vendor: File,
    ) {
        // Canonical, as Gradle lists it: a temporary directory behind a link would name another path.
        manifestJar(File(vendor, "vendor.jar"), urlOf(File(dir.canonicalFile, "build/classes/java/main"), directory = true))
        val build = buildWith("", after = "dependencies { testRuntimeOnly(files(\"${File(vendor, "vendor.jar").invariantSeparatorsPath}\")) }")
        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Row("reaches-change"),
            listOf(codec, other, first, codecTest, classOrderByName), buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a changed path no coverage map can see forces unless it is an
    // IDE's state or declared not read; nothing on Gradle's classpath names this one. A committed
    // `libs/helper.jar` reaches the tests only through a vendor jar's manifest, outside the root.
    @Test
    fun `a committed jar only a dependency's manifest names forces the run when it changes`(
        @TempDir dir: File,
        @TempDir vendor: File,
    ) {
        val helper = File(dir, "libs/helper.jar")
        manifestJar(helper, null, "helper.properties" to "mode=a\n")
        manifestJar(File(vendor, "vendor.jar"), urlOf(helper))
        val build = buildWith("", after = "dependencies { testRuntimeOnly(files(\"${File(vendor, "vendor.jar").invariantSeparatorsPath}\")) }")
        val result = attempt(
            dir, "dev.sample.A1HelperTest", Aim.Forced("unmappable-paths"),
            listOf(codec, other, first, propertyTest("A1HelperTest", "helper.properties", "mode", "a"), codecTest, classOrderByName),
            buildScript = build,
        ) { manifestJar(helper, null, "helper.properties" to "mode=b\n") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the build-output walk follows a walked jar's manifest wherever
    // it points, and a moved file outside the root is not handed to the read records. A jar the
    // build writes names a configuration directory beside the checkout.
    @Test
    fun `a directory outside the root a build jar's manifest names forces the run when a file in it changes`(
        @TempDir dir: File,
        @TempDir outside: File,
    ) {
        val settings = File(outside, "conf/settings.properties")
        settings.parentFile.mkdirs()
        settings.writeText("mode=a\n")
        val build = buildWith(
            "",
            after = """
            val configJar = tasks.register<Jar>("configJar") {
                archiveFileName.set("config.jar")
                destinationDirectory.set(layout.buildDirectory.dir("config"))
                manifest { attributes("Class-Path" to "${urlOf(settings.parentFile, directory = true)}") }
            }
            dependencies { testRuntimeOnly(files(configJar)) }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1SettingsTest", Aim.Forced("classpath-files-changed"),
            listOf(codec, other, first, propertyTest("A1SettingsTest", "settings.properties", "mode", "a"), codecTest, classOrderByName),
            buildScript = build,
        ) {
            changeCodec(it)
            settings.writeText("mode=b\n")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: what a walked jar's manifest names is walked with its class
    // files, which the walk otherwise skips, and they count against the 200,000-file budget. A
    // zip of 200,001 class files, named only by a build jar's manifest, spends it where the same
    // zip on the classpath directly would not.
    @Test
    fun `a zip of class files only a build jar's manifest names spends the digest budget`(@TempDir dir: File) {
        val budgetBuild = "import java.util.zip.ZipEntry\nimport java.util.zip.ZipOutputStream\n" + buildWith(
            "",
            after = """
            val manyZip = tasks.register("manyZip") {
                val out = layout.buildDirectory.file("pointer/many.zip")
                outputs.file(out)
                doLast {
                    val zip = out.get().asFile
                    zip.parentFile.mkdirs()
                    ZipOutputStream(zip.outputStream().buffered()).use { stream ->
                        for (i in 0..200_000) {
                            stream.putNextEntry(ZipEntry("many/C" + i + ".class"))
                            stream.write('x'.code)
                            stream.closeEntry()
                        }
                    }
                }
            }
            val pointerJar = tasks.register<Jar>("pointerJar") {
                dependsOn(manyZip)
                archiveFileName.set("pointer.jar")
                destinationDirectory.set(layout.buildDirectory.dir("pointer"))
                manifest { attributes("Class-Path" to "many.zip") }
            }
            dependencies { testRuntimeOnly(files(pointerJar)) }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Forced("classpath-files-unrecorded"),
            listOf(codec, other, first, codecTest, classOrderByName), buildScript = budgetBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the stale check drops untracked paths under another Gradle
    // build's `build/` as that build's output, but never a tracked one. A TestKit fixture project
    // under the test resources commits a file in its own `build/`, which a build task restores
    // after configuration.
    @Test
    fun `a tracked file in a fixture build's output that a task restores after configuration runs everything as stale`(
        @TempDir dir: File,
    ) {
        val fixture = "src/test/resources/sample-build/build/expected.txt"
        val result = attempt(
            dir, "dev.sample.A1CodecTest", Aim.Forced("change-set-stale"),
            listOf(
                codec, other, first, codecRunner, classOrderByName,
                "src/test/resources/sample-build/settings.gradle" to "rootProject.name = 'fixture'\n",
                // The fixture's own ignore file keeps its output folder, as a committed fixture needs.
                "src/test/resources/sample-build/.gitignore" to "!build/\n",
                fixture to "plain\n",
            ),
            buildScript = buildWith(
                "",
                after = """
                val restoreFixture by tasks.registering(Exec::class) {
                    commandLine("git", "checkout", "--", "$fixture")
                }
                tasks.processTestResources { dependsOn(restoreFixture) }
                """,
            ),
        ) { dir ->
            changeCodec(dir)
            File(dir, fixture).writeText("strict\n")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a generated jar a build jar's manifest names is walked entry by
    // entry, and a moved entry is handed to the read records under `<jar>!/<entry>`; the read is
    // made by the JDK's class loader through the manifest, not by the test opening the jar.
    @Test
    fun `a generated resource in a jar only a build jar's manifest names runs the test that reads it`(@TempDir dir: File) {
        val build = infoBuild + "\n" + """
            val infoJar = tasks.register<Jar>("infoJar") {
                from(stampInfo)
                archiveFileName.set("info.jar")
                destinationDirectory.set(layout.buildDirectory.dir("gen/jar"))
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }
            val pointerJar = tasks.register<Jar>("pointerJar") {
                dependsOn(infoJar)
                archiveFileName.set("pointer.jar")
                destinationDirectory.set(layout.buildDirectory.dir("gen/jar"))
                manifest { attributes("Class-Path" to "info.jar") }
            }
            dependencies { testRuntimeOnly(files(pointerJar)) }
        """.trimIndent()
        val result = attempt(
            dir, "dev.sample.A1InfoTest", Aim.Row("shares-jvm-moved-classpath-file"),
            listOf(codec, other, first, propertyTest("A1InfoTest", "info.properties", "state", "clean"), codecTest, classOrderByName),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
