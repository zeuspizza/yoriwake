package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Code a test JVM runs once is credited to the first test that triggers it, and some tests depend
 * on a class without executing it. Either way the dependent test holds no coverage edge to the
 * class, so every test that ran at or after the class was first loaded, or its class file first
 * read, in the same JVM is selected when it changes. Tests that ran before that point are not.
 *
 * Each fixture pins class order by name. Its `*0` test runs first and never touches the changed
 * class, so it must stay skipped: a rule that merely ran everything would select it too.
 */
class JvmSharedFunctionalTest : FunctionalTestSupport() {

    private val orderedBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        tasks.test {
            useJUnitPlatform()
            systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\${'$'}ClassName")
        }
        """.trimIndent(),
    )

    private fun main(name: String, body: String) =
        "src/main/java/dev/sample/$name.java" to "package dev.sample;\n$body"

    private fun test(name: String, body: String, imports: String = "") =
        "src/test/java/dev/sample/$name.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            $imports
            class $name {
                $body
            }
        """.trimIndent()

    private val other = main("Other", "public class Other { public int one() { return 1; } }")

    private fun first(prefix: String) =
        test("${prefix}0UnrelatedTest", "@Test void runsFirst() { assertEquals(1, new Other().one()); }")

    /** Captures [sources], applies [edit] to [edited], runs a selecting build, returns what ran. */
    private fun selectedAfter(
        dir: File,
        edited: String,
        edit: (String) -> String,
        vararg sources: Pair<String, String>,
        buildScript: String = orderedBuild,
        /** Test classes the capture must run; null when an engine reports tests without a class. */
        testCount: Int? = sources.count { it.first.startsWith("src/test/java/") },
        /** Extra arguments for the capturing run only. */
        capture: List<String> = emptyList(),
        /** Applied to the map directory between the capture and the selecting run. */
        editMap: (File) -> Unit = {},
    ): Set<String> {
        build(dir, "build.gradle.kts" to buildScript, other, *sources)
        committed(dir)
        runner(dir, "test", *capture.toTypedArray()).build()
        val all = ranTests(dir)
        testCount?.let { assertEquals(it, all.size, "the capture ran $all") }
        if (capture == isolated) {
            assertEquals(emptyMap(), jvmsHoldingSeveralClasses(dir), "an isolated capture shared a JVM")
            assertEquals(setOf("isolated"), jvmModes(dir).values.toSet())
        }
        editMap(mapDir(dir))

        val file = File(dir, edited)
        val before = file.readText()
        file.writeText(edit(before))
        check(file.readText() != before) { "the edit changed nothing in $edited" }
        File(dir, "build/test-results").deleteRecursively()
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        return ranTests(dir)
    }

    private fun names(vararg simple: String) = simple.map { "dev.sample.$it" }.toSet()

    @Test
    fun `a change to a static initialiser selects the later test that reads the state it built`(
        @TempDir dir: File,
    ) {
        val ran = staticInitialiser(dir)

        assertEquals(names("A1RatesFirstTest", "A2RatesLaterTest"), ran)
    }

    private fun staticInitialiser(dir: File, capture: List<String> = emptyList()) = selectedAfter(
        dir, "src/main/java/dev/sample/Rates.java", { it.replace("\"eur\", 100", "\"eur\", 101") },
        main(
            "Rates",
            """
            import java.util.HashMap;
            import java.util.Map;
            public class Rates {
                public static final Map<String, Integer> TABLE = build();
                private static Map<String, Integer> build() {
                    Map<String, Integer> m = new HashMap<>();
                    m.put("eur", 100);
                    return m;
                }
                public static int rate(String k) { return TABLE.get(k); }
            }
            """.trimIndent(),
        ),
        first("A"),
        test("A1RatesFirstTest", "@Test void hasEur() { assertTrue(Rates.rate(\"eur\") > 0); }"),
        // Reads the initialised table without running a line of Rates.
        test("A2RatesLaterTest", "@Test void known() { assertTrue(Rates.TABLE.containsKey(\"eur\")); }"),
        capture = capture,
    )

    @Test
    fun `a change to a factory of a context built once selects every later test that used it`(
        @TempDir dir: File,
    ) {
        val ran = contextBuiltOnce(dir)

        assertEquals(names("B1ContextFirstTest", "B2ContextLaterTest"), ran)
    }

    private fun contextBuiltOnce(dir: File, capture: List<String> = emptyList()) = selectedAfter(
        dir, "src/main/java/dev/sample/AppConfig.java", { it.replace("\"hello\"", "\"hi\"") },
        main(
            "Greeter",
            """
            public class Greeter {
                private final String word;
                public Greeter(String word) { this.word = word; }
                public String greet(String name) { return word + " " + name; }
            }
            """.trimIndent(),
        ),
        main("AppConfig", "public class AppConfig { public Greeter greeter() { return new Greeter(\"hello\"); } }"),
        main(
            "AppContext",
            """
            public final class AppContext {
                private static AppContext cached;
                private final Greeter greeter;
                private AppContext(AppConfig config) { this.greeter = config.greeter(); }
                public static synchronized AppContext get() {
                    if (cached == null) cached = new AppContext(new AppConfig());
                    return cached;
                }
                public Greeter greeter() { return greeter; }
            }
            """.trimIndent(),
        ),
        first("B"),
        test("B1ContextFirstTest", "@Test void loads() { assertNotNull(AppContext.get().greeter()); }"),
        // Uses the bean; the factory that made it ran in the test before.
        test("B2ContextLaterTest", "@Test void greets() { assertNotNull(AppContext.get().greeter().greet(\"ann\")); }"),
        capture = capture,
    )

    @Test
    fun `a test that only introspects a class another test executes is selected when it changes`(
        @TempDir dir: File,
    ) {
        val ran = introspection(dir)

        assertEquals(names("C1PointShapeTest", "C2PointUseTest"), ran)
    }

    private fun introspection(dir: File, capture: List<String> = emptyList()) = selectedAfter(
        dir, "src/main/java/dev/sample/Point.java",
        { it.replace("private final int y;", "private final int y;\n    private int z;") },
        main(
            "Point",
            """
            public class Point {
                private final int x;
                private final int y;
                public Point(int x, int y) { this.x = x; this.y = y; }
                public int sum() { return x + y; }
            }
            """.trimIndent(),
        ),
        first("C"),
        // Runs before anything executes Point, and executes none of it.
        test(
            "C1PointShapeTest",
            "@Test void fields() { assertTrue(Arrays.stream(Point.class.getDeclaredFields()).count() > 0); }",
            imports = "import java.util.Arrays;",
        ),
        test("C2PointUseTest", "@Test void sums() { assertEquals(3, new Point(1, 2).sum()); }"),
        capture = capture,
    )

    @Test
    fun `a test that reads a class file is selected when the class it reads changes`(@TempDir dir: File) {
        val ran = classFileReader(dir)

        assertEquals(names("D1NoDateRuleTest", "D2CodecTest"), ran)
    }

    private fun classFileReader(dir: File, capture: List<String> = emptyList()) = selectedAfter(
        dir, "src/main/java/dev/sample/Codec.java", { it.replace("reverse()", "reverse().append(\"\")") },
        main(
            "Codec",
            "public class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
        ),
        first("D"),
        // An ArchUnit-style rule over the main classes: parses bytes, loads nothing.
        test(
            "D1NoDateRuleTest",
            """
            @Test void noMainClassUsesJavaUtilDate() throws Exception {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("dev/sample/Codec.class")) {
                    String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    assertFalse(bytes.contains("java/util/Date"));
                }
            }
            """.trimIndent(),
            imports = "import java.io.InputStream;\nimport java.nio.charset.StandardCharsets;",
        ),
        test("D2CodecTest", "@Test void reverses() { assertEquals(\"cba\", new Codec().encode(\"abc\")); }"),
        capture = capture,
    )

    @Test
    fun `a test that ran before the changed class was first loaded in its JVM stays skipped`(
        @TempDir dir: File,
    ) {
        val ran = firstLoad(dir)

        assertEquals(names("E1AdderTest", "E2LaterTest"), ran)
    }

    private fun firstLoad(
        dir: File,
        capture: List<String> = emptyList(),
        editMap: (File) -> Unit = {},
    ) = selectedAfter(
        dir, "src/main/java/dev/sample/Adder.java", { it.replace("a + b", "b + a") },
        main("Adder", "public class Adder { public int add(int a, int b) { return a + b; } }"),
        first("E"),
        test("E1AdderTest", "@Test void adds() { assertEquals(3, new Adder().add(1, 2)); }"),
        // Never touches Adder, but ran after it was loaded, so it cannot be ruled out.
        test("E2LaterTest", "@Test void later() { assertEquals(1, new Other().one()); }"),
        capture = capture,
        editMap = editMap,
    )

    // A test class nothing else names is instantiated by its engine alone, so loading it at
    // discovery opens no window; only a lookup by name, a read or another class's execution does.

    private fun unrelated(name: String) = test(name, "@Test void runs() { assertEquals(1, new Other().one()); }")

    private val editTest = { text: String -> text.replace("1, new", "1, 0 + new") }

    @Test
    fun `an edit to a test class nothing else names selects its own tests, not every test in its JVM`(
        @TempDir dir: File,
    ) {
        val ran = selectedAfter(
        dir, "src/test/java/dev/sample/F2EditedTest.java", editTest,
            first("F"), unrelated("F1MiddleTest"), unrelated("F2EditedTest"), unrelated("F3LaterTest"),
        )

        // F1MiddleTest through the window it shares with F2EditedTest, which constructs F2 there.
        assertEquals(names("F1MiddleTest", "F2EditedTest"), ran)
    }

    @Test
    fun `a test class production code names is not exempt, so its edit selects every test in its JVM`(
        @TempDir dir: File,
    ) {
        val ran = selectedAfter(
        dir, "src/test/java/dev/sample/H2EditedTest.java", editTest,
            main(
                "Registry",
                """public class Registry { public static String find() throws Exception {
                    return Class.forName("dev.sample.H2EditedTest").getSimpleName(); } }""",
            ),
            first("H"),
            test("H1RegistryTest", "@Test void finds() throws Exception { assertEquals(\"H2EditedTest\", Registry.find()); }"),
            unrelated("H2EditedTest"), unrelated("H3LaterTest"),
        )

        assertEquals(names("H0UnrelatedTest", "H1RegistryTest", "H2EditedTest", "H3LaterTest"), ran)
    }

    @Test
    fun `a test class looked up by a name built at runtime is dated at that lookup`(@TempDir dir: File) {
        val ran = selectedAfter(
        dir, "src/test/java/dev/sample/I3EditedTest.java", editTest,
            first("I"),
            // No compiled class holds the name, so only the lookup hook can see this.
            test(
                "I1LookupTest",
                """@Test void counts() throws Exception {
                    String name = new StringBuilder("tseTdetidE3I.elpmas.ved").reverse().toString();
                    assertTrue(Class.forName(name).getDeclaredMethods().length > 0);
                }""",
            ),
            unrelated("I2MiddleTest"), unrelated("I3EditedTest"), unrelated("I4LaterTest"),
        )

        assertEquals(names("I1LookupTest", "I2MiddleTest", "I3EditedTest", "I4LaterTest"), ran)
    }

    @Test
    fun `a default-package test class whose name starts with L is dated at its lookup`(@TempDir dir: File) {
        val login = "src/test/java/LoginTest.java" to """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class LoginTest {
                @Test void runs() { assertEquals(1, new dev.sample.Other().one()); }
            }
        """.trimIndent()
        val ran = selectedAfter(
            dir, "src/test/java/LoginTest.java", editTest,
            // A main class in the default package widens the instrumentation scope to every class,
            // which is what puts a default-package test class in the map at all.
            "src/main/java/Legacy.java" to "public class Legacy { public int two() { return 2; } }",
            // Sorts before every dev.sample class, so it runs first; N1 looks it up afterwards.
            login,
            first("N"),
            test(
                "N1LookupTest",
                """@Test void finds() throws Exception {
                    String name = new StringBuilder("tseTnigoL").reverse().toString();
                    assertTrue(Class.forName(name).getDeclaredMethods().length > 0);
                }""",
            ),
            unrelated("N2LaterTest"),
        )

        assertTrue(ran.containsAll(setOf("LoginTest") + names("N1LookupTest", "N2LaterTest")), "$ran")
    }

    @Test
    fun `a JVM that also runs an engine not known to own its classes dates test classes as any class`(
        @TempDir dir: File,
    ) {
        val engine = test(
            "J9FixtureEngine",
            "",
            imports = "import org.junit.platform.engine.*;\nimport org.junit.platform.engine.support.descriptor.*;",
        ).let { (path, _) ->
            path to """
                package dev.sample;
                import org.junit.platform.engine.*;
                import org.junit.platform.engine.support.descriptor.*;
                /** An engine that builds its test from nothing it was asked about. */
                public class J9FixtureEngine implements TestEngine {
                    public String getId() { return "fixture-engine"; }
                    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId id) {
                        EngineDescriptor engine = new EngineDescriptor(id, "fixture");
                        engine.addChild(new AbstractTestDescriptor(id.append("test", "one"), "one") {
                            public Type getType() { return Type.TEST; }
                        });
                        return engine;
                    }
                    public void execute(ExecutionRequest request) {
                        TestDescriptor root = request.getRootTestDescriptor();
                        EngineExecutionListener listener = request.getEngineExecutionListener();
                        listener.executionStarted(root);
                        for (TestDescriptor child : root.getChildren()) {
                            listener.executionStarted(child);
                            new Other().one();
                            listener.executionFinished(child, TestExecutionResult.successful());
                        }
                        listener.executionFinished(root, TestExecutionResult.successful());
                    }
                }
            """.trimIndent()
        }
        val ran = selectedAfter(
        dir, "src/test/java/dev/sample/J2EditedTest.java", editTest,
            first("J"), unrelated("J1MiddleTest"), unrelated("J2EditedTest"), unrelated("J3LaterTest"),
            engine,
            "src/test/resources/META-INF/services/org.junit.platform.engine.TestEngine" to "dev.sample.J9FixtureEngine\n",
            buildScript = orderedBuild.replace(
                "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")",
                "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n    testImplementation(\"org.junit.platform:junit-platform-engine\")",
            ),
            testCount = null,
        )

        // J0UnrelatedTest ran before anything touched J2EditedTest: only the fallback selects it.
        assertTrue(ran.containsAll(names("J0UnrelatedTest", "J1MiddleTest", "J2EditedTest", "J3LaterTest")), "$ran")
    }

    // Channels the agent cannot follow into, or used to miss: each fixture's dependent test reaches
    // the changed class only through that channel, and a later test executes the class, so the map
    // knows it and a change narrows. The `*0` test before the channel is used must stay skipped.

    @Test
    fun `a test that starts a child process selects every later test in its JVM`(@TempDir dir: File) {
        val ran = selectedAfter(
            dir, "src/main/java/dev/sample/Tool.java", { it.replace("\"tool\"", "\"tool!\"") },
            main("Tool", "public class Tool { public static void main(String[] a) { System.out.print(\"tool\"); } }"),
            first("K"),
            // Runs Tool in a JVM of its own, which no agent observes.
            test(
                "K1ChildJvmTest",
                """
                @Test void runsTheTool() throws Exception {
                    String java = ProcessHandle.current().info().command().orElseThrow();
                    String main = new StringBuilder("looT.elpmas.ved").reverse().toString();
                    Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), main).start();
                    String out = new String(child.getInputStream().readAllBytes());
                    assertEquals(0, child.waitFor());
                    assertTrue(out.startsWith("tool"), out);
                }
                """.trimIndent(),
            ),
            test("K2ToolTest", "@Test void runs() { Tool.main(new String[0]); }"),
            test("K3LaterTest", "@Test void later() { assertEquals(1, new Other().one()); }"),
        )

        assertEquals(names("K1ChildJvmTest", "K2ToolTest", "K3LaterTest"), ran)
    }

    @Test
    fun `a class loader that defines its own copy of a test class dates that class`(@TempDir dir: File) {
        val ran = selectedAfter(
            dir, "src/test/java/dev/sample/L3EditedTest.java", editTest,
            first("L"),
            // Child-first for one name built at runtime: the copy is defined by the JDK's own
            // URLClassLoader code, which reads the class file under a defineClass frame.
            test(
                "L1CopyLoaderTest",
                """
                @Test void copies() throws Exception {
                    String name = new StringBuilder("tseTdetidE3L.elpmas.ved").reverse().toString();
                    URL here = getClass().getProtectionDomain().getCodeSource().getLocation();
                    try (URLClassLoader copy = new URLClassLoader(new URL[] {here}, getClass().getClassLoader()) {
                        @Override public Class<?> loadClass(String n) throws ClassNotFoundException {
                            return n.equals(name) ? findClass(n) : super.loadClass(n);
                        }
                    }) {
                        Class<?> k = copy.loadClass(name);
                        assertNotSame(getClass().getClassLoader(), k.getClassLoader());
                        assertTrue(k.getDeclaredMethods().length > 0);
                    }
                }
                """.trimIndent(),
                imports = "import java.net.URL;\nimport java.net.URLClassLoader;",
            ),
            unrelated("L2MiddleTest"), unrelated("L3EditedTest"), unrelated("L4LaterTest"),
        )

        assertEquals(names("L1CopyLoaderTest", "L2MiddleTest", "L3EditedTest", "L4LaterTest"), ran)
    }

    @Test
    fun `a hidden copy of a class defined through Method invoke dates that class`(@TempDir dir: File) {
        val ran = hiddenCopy(
            dir, "W",
            """
            Method define = MethodHandles.Lookup.class.getMethod(
                    "defineHiddenClass", byte[].class, boolean.class, MethodHandles.Lookup.ClassOption[].class);
            MethodHandles.Lookup hidden = (MethodHandles.Lookup) define.invoke(
                    MethodHandles.lookup(), bytes, true, new MethodHandles.Lookup.ClassOption[0]);
            """.trimIndent(),
        )

        assertEquals(names("W1HiddenCopyTest", "W2CodecTest"), ran)
    }

    @Test
    fun `a hidden copy of a class defined through a method handle dates that class`(@TempDir dir: File) {
        val ran = hiddenCopy(
            dir, "X",
            """
            MethodHandle define = MethodHandles.lookup().findVirtual(MethodHandles.Lookup.class, "defineHiddenClass",
                    MethodType.methodType(MethodHandles.Lookup.class, byte[].class, boolean.class,
                            MethodHandles.Lookup.ClassOption[].class));
            MethodHandles.Lookup hidden = (MethodHandles.Lookup) define.invoke(
                    MethodHandles.lookup(), bytes, true, new MethodHandles.Lookup.ClassOption[0]);
            """.trimIndent(),
        )

        assertEquals(names("X1HiddenCopyTest", "X2CodecTest"), ran)
    }

    @Test
    fun `the foreign-function API reached through a method reference JDK code calls selects every later test in its JVM`(
        @TempDir dir: File,
    ) {
        // The test JVM is Java 21, where the foreign-function API is a preview. The method
        // reference's own class is hidden, so the class that wrote it, not the stream, is the caller.
        val previewBuild = orderedBuild.replace("useJUnitPlatform()", "useJUnitPlatform()\n    jvmArgs(\"--enable-preview\")") +
            "\ntasks.withType<JavaCompile>().configureEach { options.compilerArgs.add(\"--enable-preview\"); options.release = 21 }\n"
        val ran = selectedAfter(
            dir, "src/main/java/dev/sample/Tool.java", { it.replace("\"tool\"", "\"tool!\"") },
            main("Tool", "public class Tool { public static String name() { return \"tool\"; } }"),
            first("Y"),
            test(
                "Y1NativeTest",
                """
                @Test void links() {
                    Supplier<Linker> linker = Linker::nativeLinker;
                    assertNotNull(Stream.generate(linker).limit(1).findFirst().get());
                }
                """.trimIndent(),
                imports = "import java.lang.foreign.Linker;\nimport java.util.function.Supplier;\nimport java.util.stream.Stream;",
            ),
            test("Y2ToolTest", "@Test void runs() { assertTrue(Tool.name().startsWith(\"tool\")); }"),
            test("Y3LaterTest", "@Test void later() { assertEquals(1, new Other().one()); }"),
            buildScript = previewBuild,
        )

        assertEquals(names("Y1NativeTest", "Y2ToolTest", "Y3LaterTest"), ran)
    }

    /**
     * A test that defines a hidden copy of Codec with [define], which leaves the copy's lookup in
     * `hidden`, then runs it; then a test that runs Codec itself. The build copies Codec's class
     * file to a name that is not a class file, so reading the bytes dates nothing and only the
     * definition can: a copy runs Codec's code, and coverage records none of it.
     */
    private fun hiddenCopy(dir: File, prefix: String, define: String) = selectedAfter(
        dir, "src/main/java/dev/sample/Codec.java", { it.replace("reverse()", "reverse().append(\"\")") },
        main(
            "Codec",
            "public class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
        ),
        first(prefix),
        test(
            "${prefix}1HiddenCopyTest",
            """
            @Test void copiesCodec() throws Throwable {
                byte[] bytes = Files.readAllBytes(Paths.get(System.getProperty("codec.copy")));
            """.trimIndent() + "\n" + define.prependIndent("    ") + "\n" + """
                Class<?> copy = hidden.lookupClass();
                assertEquals("cba", copy.getMethod("encode", String.class).invoke(copy.getConstructor().newInstance(), "abc"));
            }
            """.trimIndent(),
            imports = listOf(
                "java.lang.invoke.MethodHandle", "java.lang.invoke.MethodHandles", "java.lang.invoke.MethodType",
                "java.lang.reflect.Method", "java.nio.file.Files", "java.nio.file.Paths",
            ).joinToString("\n") { "import $it;" },
        ),
        test("${prefix}2CodecTest", "@Test void reverses() { assertEquals(\"cba\", new Codec().encode(\"abc\")); }"),
        buildScript = copiedCodecBuild,
    )

    private val copiedCodecBuild = orderedBuild + "\n" + """
        val copyCodec = tasks.register<Copy>("copyCodec") {
            dependsOn(tasks.compileJava)
            from(layout.buildDirectory.file("classes/java/main/dev/sample/Codec.class"))
            rename { "Codec.bytes" }
            into(layout.buildDirectory.dir("copies"))
        }
        tasks.test {
            dependsOn(copyCodec)
            systemProperty("codec.copy", layout.buildDirectory.file("copies/Codec.bytes").get().asFile.absolutePath)
        }
    """.trimIndent()

    @Test
    fun `a test that reads a class file through the file-system provider is selected when it changes`(
        @TempDir dir: File,
    ) {
        val ran = selectedAfter(
            dir, "src/main/java/dev/sample/Codec.java", { it.replace("reverse()", "reverse().append(\"\")") },
            main(
                "Codec",
                "public class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
            ),
            first("M"),
            // The provider's own channel, which no method of Files is on the way to.
            test(
                "M1ProviderReadTest",
                """
                @Test void noMainClassUsesJavaUtilDate() throws Exception {
                    Path file = Paths.get(getClass().getClassLoader().getResource("dev/sample/Codec.class").toURI());
                    try (SeekableByteChannel channel = FileSystems.getDefault().provider()
                            .newByteChannel(file, EnumSet.of(StandardOpenOption.READ))) {
                        ByteBuffer bytes = ByteBuffer.allocate((int) channel.size());
                        while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
                        assertFalse(new String(bytes.array(), StandardCharsets.ISO_8859_1).contains("java/util/Date"));
                    }
                }
                """.trimIndent(),
                imports = listOf(
                    "java.nio.ByteBuffer", "java.nio.channels.SeekableByteChannel", "java.nio.charset.StandardCharsets",
                    "java.nio.file.FileSystems", "java.nio.file.Path", "java.nio.file.Paths",
                    "java.nio.file.StandardOpenOption", "java.util.EnumSet",
                ).joinToString("\n") { "import $it;" },
            ),
            test("M2CodecTest", "@Test void reverses() { assertEquals(\"cba\", new Codec().encode(\"abc\")); }"),
        )

        assertEquals(names("M1ProviderReadTest", "M2CodecTest"), ran)
    }

    /**
     * A test that asks [loader] to load a native library file named [file], then a test that runs
     * Tool, then one that does not. The file does not exist: the load is seen as it starts, so the
     * fixture needs no compiler, and the failed load is caught.
     */
    private fun nativeLoad(dir: File, prefix: String, loader: String, file: String): Set<String> {
        val pkg = loader.substringBeforeLast('.')
        val simple = loader.substringAfterLast('.')
        return selectedAfter(
            dir, "src/main/java/dev/sample/Tool.java", { it.replace("\"tool\"", "\"tool!\"") },
            main("Tool", "public class Tool { public static String name() { return \"tool\"; } }"),
            "src/test/java/${pkg.replace('.', '/')}/$simple.java" to """
                package $pkg;
                public final class $simple {
                    public static boolean load(java.io.File file) {
                        try { System.load(file.getAbsolutePath()); return true; }
                        catch (UnsatisfiedLinkError absent) { return false; }
                    }
                }
            """.trimIndent(),
            first(prefix),
            test(
                "${prefix}1NativeTest",
                """@Test void loads() {
                    assertFalse($loader.load(new java.io.File(System.getProperty("java.io.tmpdir"), "$file")));
                }""",
            ),
            test("${prefix}2ToolTest", "@Test void runs() { assertTrue(Tool.name().startsWith(\"tool\")); }"),
            test("${prefix}3LaterTest", "@Test void later() { assertEquals(1, new Other().one()); }"),
            // The loader is not a test class.
            testCount = 4,
        )
    }

    @Test
    fun `a test that loads a native library not on the reviewed list selects every later test in its JVM`(
        @TempDir dir: File,
    ) {
        val ran = nativeLoad(dir, "P", "com.acme.nativecodec.Loader", "libacmecodec.so")

        assertEquals(names("P1NativeTest", "P2ToolTest", "P3LaterTest"), ran)
    }

    @Test
    fun `a reviewed native library loaded by its own class narrows as before`(@TempDir dir: File) {
        val ran = nativeLoad(
            dir, "Q", "org.xerial.snappy.SnappyLoader",
            "snappy-1.1.10.8-2f9b1c3a-5d4e-4f60-9a7b-0c1d2e3f4a5b-libsnappyjava.so",
        )

        assertEquals(names("Q2ToolTest", "Q3LaterTest"), ran)
    }

    @Test
    fun `a native library a JDK module on the platform loader loads narrows as before`(@TempDir dir: File) {
        val ran = selectedAfter(
            dir, "src/main/java/dev/sample/Tool.java", { it.replace("\"tool\"", "\"tool!\"") },
            main("Tool", "public class Tool { public static String name() { return \"tool\"; } }"),
            first("R"),
            // jdk.security.auth is defined by the platform loader, and its class loads libjaas.
            test(
                "R1JdkLibraryTest",
                """@Test void loadsJaas() throws Exception {
                    String name = System.getProperty("os.name").startsWith("Windows")
                        ? "com.sun.security.auth.module.NTSystem" : "com.sun.security.auth.module.UnixSystem";
                    Class<?> system = Class.forName(name);
                    assertSame(ClassLoader.getPlatformClassLoader(), system.getClassLoader());
                    assertNotNull(system.getConstructor().newInstance());
                }""",
            ),
            test("R2ToolTest", "@Test void runs() { assertTrue(Tool.name().startsWith(\"tool\")); }"),
            test("R3LaterTest", "@Test void later() { assertEquals(1, new Other().one()); }"),
        )

        assertEquals(names("R2ToolTest", "R3LaterTest"), ran)
    }

    // Fallbacks for an edit to a test class: something that can reach any class by name dates every
    // test class from that point. `F` above is the same fixture with neither.

    private fun editedTestClassWith(dir: File, prefix: String, buildScript: String, vararg extra: Pair<String, String>) =
        selectedAfter(
            dir, "src/test/java/dev/sample/${prefix}3EditedTest.java", editTest,
            first(prefix), *extra, unrelated("${prefix}2MiddleTest"), unrelated("${prefix}3EditedTest"),
            unrelated("${prefix}4LaterTest"),
            buildScript = buildScript,
        )

    /** A JDK debugger agent, the one native agent every JDK ships. */
    private val jdwpOptions = "transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0"

    @Test
    fun `a native agent given with -agentlib dates every test class from the start`(@TempDir dir: File) {
        val ran = editedTestClassWith(
            dir, "S",
            orderedBuild.replace("useJUnitPlatform()", "useJUnitPlatform()\n    jvmArgs(\"-agentlib:jdwp=$jdwpOptions\")"),
            unrelated("S1FirstTest"),
        )

        assertEquals(names("S0UnrelatedTest", "S1FirstTest", "S2MiddleTest", "S3EditedTest", "S4LaterTest"), ran)
    }

    @Test
    fun `a native agent given with -agentpath dates every test class from the start`(@TempDir dir: File) {
        val ran = editedTestClassWith(
            dir, "T",
            orderedBuild.replace(
                "useJUnitPlatform()",
                """useJUnitPlatform()
                val jdwp = listOf("lib", "bin").map { File(System.getProperty("java.home"), it + "/" + System.mapLibraryName("jdwp")) }
                    .first { it.isFile }
                jvmArgs("-agentpath:" + jdwp.absolutePath + "=$jdwpOptions")""",
            ),
            unrelated("T1FirstTest"),
        )

        assertEquals(names("T0UnrelatedTest", "T1FirstTest", "T2MiddleTest", "T3EditedTest", "T4LaterTest"), ran)
    }

    @Test
    fun `a test that asks for every loaded class dates every test class from that point`(@TempDir dir: File) {
        // A Java agent of the build's own hands its Instrumentation to the tests.
        val probe = "src/probe/java/probe/Probe.java" to """
            package probe;
            public final class Probe {
                public static volatile java.lang.instrument.Instrumentation INSTRUMENTATION;
                public static void premain(String args, java.lang.instrument.Instrumentation inst) { INSTRUMENTATION = inst; }
            }
        """.trimIndent()
        val ran = editedTestClassWith(
            dir, "U",
            orderedBuild.replace(
                "tasks.test {",
                """
                val probe by sourceSets.creating
                val probeJar by tasks.registering(Jar::class) {
                    from(probe.output)
                    archiveFileName.set("probe-agent.jar")
                    manifest { attributes("Premain-Class" to "probe.Probe") }
                }
                tasks.test {
                    dependsOn(probeJar)
                    val jar = probeJar.flatMap { it.archiveFile }
                    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:" + jar.get().asFile.absolutePath) })
                """.trimIndent(),
            ),
            probe,
            test(
                "U1AllClassesTest",
                """@Test void scans() throws Exception {
                    Object inst = ClassLoader.getSystemClassLoader().loadClass("probe.Probe").getField("INSTRUMENTATION").get(null);
                    assertTrue(((java.lang.instrument.Instrumentation) inst).getAllLoadedClasses().length > 0);
                }""",
            ),
        )

        assertEquals(names("U1AllClassesTest", "U2MiddleTest", "U3EditedTest", "U4LaterTest"), ran)
    }

    @Test
    fun `a child JVM started with Runtime exec that reads a class file selects every later test in its JVM`(
        @TempDir dir: File,
    ) {
        val ran = selectedAfter(
            dir, "src/main/java/dev/sample/Codec.java", { it.replace("reverse()", "reverse().append(\"\")") },
            main(
                "Codec",
                "public class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
            ),
            // Run only in the child: reads Codec's class file and prints its size.
            "src/test/java/dev/sample/ClassFileSize.java" to """
                package dev.sample;
                public final class ClassFileSize {
                    public static void main(String[] a) throws Exception {
                        try (java.io.InputStream in = ClassFileSize.class.getClassLoader().getResourceAsStream(a[0])) {
                            System.out.print(in.readAllBytes().length);
                        }
                    }
                }
            """.trimIndent(),
            first("V"),
            test(
                "V1ChildReadsTest",
                """
                @Test void sizesTheClassFile() throws Exception {
                    String java = ProcessHandle.current().info().command().orElseThrow();
                    String main = new StringBuilder("eziSeliFssalC.elpmas.ved").reverse().toString();
                    Process child = Runtime.getRuntime().exec(new String[] {
                        java, "-cp", System.getProperty("java.class.path"), main, "dev/sample/Codec.class"});
                    String out = new String(child.getInputStream().readAllBytes());
                    assertEquals(0, child.waitFor());
                    assertTrue(Integer.parseInt(out) > 0, out);
                }
                """.trimIndent(),
            ),
            test("V2CodecTest", "@Test void reverses() { assertEquals(\"cba\", new Codec().encode(\"abc\")); }"),
            test("V3LaterTest", "@Test void later() { assertEquals(1, new Other().one()); }"),
            // The child's main class is not a test class.
            testCount = 4,
        )

        assertEquals(names("V1ChildReadsTest", "V2CodecTest", "V3LaterTest"), ran)
    }

    // Recorded with -Pyoriwake.isolatedCapture, each test class runs in a JVM of its own, so every
    // static initialiser, cached context and class read it depends on it triggers itself. The same
    // rule then stays inside one class, and the selecting run still shares one JVM.

    private val isolated = listOf("-Pyoriwake.isolatedCapture")

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    /** The JVMs of the map's positions that ran tests of more than one class, with those classes. */
    private fun jvmsHoldingSeveralClasses(dir: File): Map<String, Set<String>> =
        File(mapDir(dir), "positions.tsv").readLines().filter(String::isNotBlank)
            .map { it.split("\t") }
            .groupBy({ it[0] }, { Regex("""\[class:([^\]]+)]""").find(it[2])?.groupValues?.get(1) ?: it[2] })
            .mapValues { it.value.toSet() }
            .filterValues { it.size > 1 }

    /** Each JVM of the map's positions, by the mode `jvm-mode.tsv` records for it. */
    private fun jvmModes(dir: File): Map<String, String> {
        val file = File(mapDir(dir), "jvm-mode.tsv")
        if (!file.isFile) return emptyMap()
        return file.readLines().filter(String::isNotBlank).map { it.split("\t") }.associate { it[0] to it[1] }
    }

    @Test
    fun `an isolated map still selects the later test that reads what a static initialiser built`(
        @TempDir dir: File,
    ) {
        assertEquals(names("A1RatesFirstTest", "A2RatesLaterTest"), staticInitialiser(dir, isolated))
    }

    @Test
    fun `an isolated map still selects every test that used a context built once`(@TempDir dir: File) {
        assertEquals(names("B1ContextFirstTest", "B2ContextLaterTest"), contextBuiltOnce(dir, isolated))
    }

    @Test
    fun `an isolated map still selects a test that only introspects the changed class`(@TempDir dir: File) {
        assertEquals(names("C1PointShapeTest", "C2PointUseTest"), introspection(dir, isolated))
    }

    @Test
    fun `an isolated map still selects a test that reads the changed class file`(@TempDir dir: File) {
        assertEquals(names("D1NoDateRuleTest", "D2CodecTest"), classFileReader(dir, isolated))
    }

    @Test
    fun `with an isolated map a change reached from one test class selects it and not the classes after it`(
        @TempDir dir: File,
    ) {
        assertEquals(names("E1AdderTest"), firstLoad(dir, isolated))
    }

    @Test
    fun `a shared capture labels its JVM shared and a map with no label selects as a shared one`(
        @TempDir dir: File,
    ) {
        val ran = firstLoad(dir) { mapDir ->
            val modes = File(mapDir, "jvm-mode.tsv")
            assertEquals(setOf("shared"), jvmModes(dir).values.toSet(), "a shared capture is not labelled shared")
            assertTrue(modes.delete())
        }

        assertEquals(names("E1AdderTest", "E2LaterTest"), ran)
    }

    @Test
    fun `a shared JVM labelled isolated still selects every test after the first touch`(@TempDir dir: File) {
        // The label is a report, never a licence: what narrows is which classes shared a JVM.
        val ran = firstLoad(dir) { mapDir ->
            val modes = File(mapDir, "jvm-mode.tsv")
            modes.writeText(modes.readText().replace("\tshared", "\tisolated"))
        }

        assertEquals(names("E1AdderTest", "E2LaterTest"), ran)
    }
}
