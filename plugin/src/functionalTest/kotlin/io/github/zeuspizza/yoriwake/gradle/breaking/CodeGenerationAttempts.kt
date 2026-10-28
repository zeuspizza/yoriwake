package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on code and resources the build generates: a resource a build step writes into a source
 * directory, before or during the selecting build, and a class an annotation processor generates
 * from a source or another file.
 */
class CodeGenerationAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

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

    private val alpha = source(
        "src/main/java/dev/sample/Alpha.java",
        """
        package dev.sample;
        public class Alpha {
            public int twice(int n) { return n * 2; }
            public String name() { return "alpha"; }
        }
        """,
    )

    private val alphaTest = source(
        "src/test/java/dev/sample/A2AlphaTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A2AlphaTest { @Test void doubles() { assertEquals(6, new Alpha().twice(3)); } }
        """,
    )

    private val alphaNameTest = source(
        "src/test/java/dev/sample/A3AlphaNameTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A3AlphaNameTest { @Test void named() { assertEquals("alpha", new Alpha().name()); } }
        """,
    )

    private fun breakAlpha(dir: File) {
        val file = File(dir, alpha.first)
        file.writeText(file.readText().replace("n * 2", "n * 2 + 1"))
    }

    private fun withTask(body: String) = minimalBuild.replace("tasks.test { useJUnitPlatform() }", body.trimIndent())

    /** Reads the resource at [resource], "2" when there is none, as the factor Alpha must double 1 to. */
    private fun generatedTest(resource: String) = source(
        "src/test/java/dev/sample/A2GeneratedTest.java",
        """
        package dev.sample;
        import java.io.InputStream;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A2GeneratedTest {
            @Test void factor() throws Exception {
                String value = "2";
                try (InputStream in = A2GeneratedTest.class.getResourceAsStream("$resource")) {
                    if (in != null) value = new String(in.readAllBytes()).trim();
                }
                assertEquals(Integer.parseInt(value), new Alpha().twice(1));
            }
        }
        """,
    )

    /** A task, run on its own, that writes `-Pvalue` to [path]. */
    private fun generator(path: String) = withTask(
        """
        val generate by tasks.registering {
            val out = layout.projectDirectory.file("$path")
            val value = providers.gradleProperty("value")
            doLast {
                out.asFile.parentFile.mkdirs()
                out.asFile.writeText(value.get())
            }
        }
        tasks.test { useJUnitPlatform() }
        """,
    )

    // Track: code reading. Boundary: the stale check compares the change set of the configured
    // build with the one the task sees, both ways. A task that reverts a tracked edit after
    // configuration takes a path out of the change set rather than adding one; a check that only
    // looked for new paths would select on a change set the tests no longer see.
    @Test
    fun `a tracked edit a build task reverts after configuration refuses the run as stale`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("change-set-stale"),
            listOf(other, first, alpha, alphaTest, alphaNameTest, "src/main/resources/banner.txt" to "hello\n", classOrderByName),
            buildScript = withTask(
                """
                val restoreBanner by tasks.registering(Exec::class) {
                    commandLine("git", "checkout", "--", "src/main/resources/banner.txt")
                }
                tasks.processResources { dependsOn(restoreBanner) }
                tasks.test { useJUnitPlatform() }
                """,
            ),
        ) { dir ->
            breakAlpha(dir)
            File(dir, "src/main/resources/banner.txt").writeText("bye\n")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: AP1.
    @Test
    fun `a resource the selecting build generates into a source directory refuses the run as stale`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2AlphaTest", Aim.Forced("change-set-stale"),
            listOf(other, first, alpha, alphaTest, alphaNameTest, classOrderByName),
            buildScript = withTask(
                """
                val regenerate by tasks.registering {
                    val alpha = layout.projectDirectory.file("src/main/java/dev/sample/Alpha.java")
                    val out = layout.projectDirectory.file("src/main/resources/gen.txt")
                    inputs.file(alpha)
                    doLast { if ("+ 1" in alpha.asFile.readText()) out.asFile.apply { parentFile.mkdirs() }.writeText("regenerated\n") }
                }
                tasks.processResources { dependsOn(regenerate) }
                tasks.test { useJUnitPlatform() }
                """,
            ),
        ) { breakAlpha(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: WorkingTree passes over a `build` directory as build output
    // only when the directory above it holds a build script, and counts ignored files. A resource
    // generated under `src/main/resources/build/`, ignored by the usual `build/` pattern, looks like
    // build output twice over, yet it is a source the tests read.
    @Test
    fun `an ignored resource an earlier build generated under a build directory in the sources forces the run`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A2GeneratedTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, alpha, generatedTest("/build/gen.txt"), alphaNameTest, classOrderByName),
            buildScript = generator("src/main/resources/build/gen.txt"),
        ) { dir ->
            runner(dir, "generate", "-Pvalue=3").build()
            // Something git shows, beside the ignored file it does not: a class change that selects only A0.
            val file = File(dir, other.first)
            file.writeText(file.readText().replace("return 1;", "return 2 - 1;"))
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: AP2.
    @Test
    fun `a resource an earlier build generated into a source directory forces the run`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2GeneratedTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, alpha, generatedTest("/gen.txt"), alphaNameTest, classOrderByName),
            buildScript = generator("src/main/resources/gen.txt"),
        ) { dir -> runner(dir, "generate", "-Pvalue=3").build() }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    /** A processor project generating `<Type>Value.get()` from `@Value`, or from the file `-AvalueFile` names. */
    private val processor = listOf(
        "settings.gradle.kts" to "rootProject.name = \"sample\"\ninclude(\"processor\")\n",
        "processor/build.gradle.kts" to "plugins { java }\n",
        source(
            "processor/src/main/java/dev/proc/Value.java",
            """
            package dev.proc;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
            public @interface Value { int value(); }
            """,
        ),
        source(
            "processor/src/main/java/dev/proc/ValueProcessor.java",
            """
            package dev.proc;
            import java.io.Writer;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.*;
            @SupportedAnnotationTypes("dev.proc.Value")
            @SupportedOptions("valueFile")
            public class ValueProcessor extends AbstractProcessor {
                @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
                    for (Element e : env.getElementsAnnotatedWith(Value.class)) {
                        TypeElement type = (TypeElement) e;
                        String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
                        String name = type.getSimpleName() + "Value";
                        try {
                            String file = processingEnv.getOptions().get("valueFile");
                            int v = file != null ? Integer.parseInt(Files.readString(Path.of(file)).trim()) : e.getAnnotation(Value.class).value();
                            try (Writer w = processingEnv.getFiler().createSourceFile(pkg + "." + name, e).openWriter()) {
                                w.write("package " + pkg + ";\npublic final class " + name + " { public static int get() { return " + v + "; } }\n");
                            }
                        } catch (java.io.IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                    return true;
                }
            }
            """,
        ),
        "processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor" to "dev.proc.ValueProcessor\n",
    )

    private val annotatedAlpha = alpha.first to alpha.second.replace("public class Alpha", "@dev.proc.Value(2) public class Alpha")

    private val valueTest = source(
        "src/test/java/dev/sample/A2AlphaValueTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A2AlphaValueTest { @Test void value() { assertEquals(2, AlphaValue.get()); } }
        """,
    )

    private fun processed(extra: String = "") = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        dependencies {
            compileOnly(project(":processor"))
            annotationProcessor(project(":processor"))
        }
        $extra
        tasks.test { useJUnitPlatform() }
        """.trimIndent(),
    )

    // Track: fixtures. Row: AP3.
    @Test
    fun `a class an annotation processor regenerates from a changed source selects the test that runs it`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A2AlphaValueTest", Aim.Row("changed-bytes"),
            processor + listOf(other, first, annotatedAlpha, valueTest, alphaNameTest, classOrderByName),
            buildScript = processed(),
        ) { dir ->
            val file = File(dir, alpha.first)
            file.writeText(file.readText().replace("@dev.proc.Value(2)", "@dev.proc.Value(3)"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: AP4.
    @Test
    fun `a class an annotation processor generates from another file forces the run when that file changes`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A2AlphaValueTest", Aim.Forced("unmappable-paths"),
            processor + listOf(other, first, annotatedAlpha, valueTest, alphaNameTest, "src/main/proc/value.txt" to "2\n", classOrderByName),
            buildScript = processed(
                """
                tasks.compileJava {
                    val values = file("src/main/proc/value.txt")
                    inputs.file(values)
                    options.compilerArgs.add("-AvalueFile=" + values.absolutePath)
                }
                """.trimIndent(),
            ),
        ) { dir -> File(dir, "src/main/proc/value.txt").writeText("3\n") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: changed bytes are found by digesting every own class against
    // the map, and a generated class has no source of its own to name it. A processor run on the
    // test sources writes its class into the test output, not the main one: a digest walk over
    // main outputs only would miss it, and the test that runs it would be left out. The map is
    // recorded in isolation, so no other class's JVM sees the generated class load.
    @Test
    fun `a class a processor on the test sources regenerates selects the test that runs it`(@TempDir dir: File) {
        // The annotation sits on its own line: on the class's line, the declaration scan does not
        // see the class, and the edit forces as a path coverage cannot see.
        val fixture = source(
            "src/test/java/dev/sample/Fixture.java",
            "package dev.sample;\n@dev.proc.Value(2)\npublic class Fixture { public int one() { return 1; } }",
        )
        val fixtureValueTest = source(
            "src/test/java/dev/sample/A2FixtureValueTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2FixtureValueTest { @Test void value() { assertEquals(2, FixtureValue.get()); } }
            """,
        )
        val fixtureTest = source(
            "src/test/java/dev/sample/A3FixtureTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A3FixtureTest { @Test void one() { assertEquals(1, new Fixture().one()); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2FixtureValueTest", Aim.Row("changed-bytes"),
            processor + listOf(
                other, first, fixture, fixtureValueTest, fixtureTest, classOrderByName,
                "gradle.properties" to "yoriwake.isolatedCapture=true\n",
            ),
            buildScript = minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                """
                dependencies {
                    testCompileOnly(project(":processor"))
                    testAnnotationProcessor(project(":processor"))
                }
                tasks.test { useJUnitPlatform() }
                """.trimIndent(),
            ),
        ) { dir ->
            val file = File(dir, fixture.first)
            file.writeText(file.readText().replace("@dev.proc.Value(2)", "@dev.proc.Value(3)"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
