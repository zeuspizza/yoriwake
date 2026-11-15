package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What selection decides for each shape of change the benchmark's mutation families draw, pinned
 * before the benchmark measures it: narrowed to the tests that ran the changed code, or a full run
 * of a named kind.
 *
 * Each fixture has a test the change reaches and, where the decision can narrow, one it cannot.
 * Class order is pinned by name and the changed class is the one the last test uses, so a test that
 * ran before the change was loaded is the one a narrowed run provably leaves out.
 */
class ChangeShapesFunctionalTest : FunctionalTestSupport() {

    /** Captures [sources], applies [edit] on top of the commit, and selects against HEAD. */
    private fun selectAfter(
        dir: File,
        buildScript: String,
        vararg sources: Pair<String, String>,
        edit: () -> Unit,
        run: GradleRunner.() -> BuildResult = GradleRunner::build,
    ): String {
        build(dir, "build.gradle.kts" to buildScript, classOrderByName, *sources)
        ignoreBuildOutputs(dir)
        // Where Kotlin's compiler keeps its session state; a Kotlin project ignores it.
        File(dir, ".gitignore").appendText(System.lineSeparator() + ".kotlin/")
        git(dir, "init")
        commit(dir, "base")
        runner(dir, "test").build()
        edit()
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").run().output
    }

    private fun assertNarrowedTo(dir: File, expected: Set<String>, output: String) {
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(expected, ranTests(dir), output)
    }

    private fun assertFullRun(dir: File, kind: String, expected: Set<String>, output: String) {
        assertEquals("full-run", decisionNotes(dir)["outcome"], output)
        assertEquals(kind, decisionNotes(dir)["full-run-kind"], output)
        assertEquals(expected, ranTests(dir), output)
    }

    private fun edit(dir: File, path: String, from: String, to: String) {
        val file = File(dir, path)
        val text = file.readText()
        check(from in text) { "$path does not contain $from" }
        file.writeText(text.replace(from, to))
    }

    private val both = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")

    @Test
    fun `a void method returning early at its entry runs the test that called it`(@TempDir dir: File) {
        val beta = "src/main/java/dev/sample/Beta.java" to """
            package dev.sample;
            public class Beta {
                public int last;
                public void remember(int n) { last = n; }
            }
        """.trimIndent()
        val betaTest = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest {
                @Test void remembers() { Beta b = new Beta(); b.remember(4); assertEquals(4, b.last); }
            }
        """.trimIndent()

        // The early return breaks BetaTest, so a skipped BetaTest would show as a passing build.
        val output = selectAfter(dir, minimalBuild, oneClass, oneTest, beta, betaTest, edit = {
            edit(dir, beta.first, "{ last = n; }", "{ if (true) { return; } last = n; }")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a Kotlin function returning Int that throws at its entry runs its caller's test`(@TempDir dir: File) {
        // Alpha inlines a function of the project's own. With no inlined body anywhere in the
        // output the inline scan refuses and everything runs, which would hide the decision pinned
        // here.
        val alpha = "src/main/kotlin/dev/sample/Alpha.kt" to """
            package dev.sample
            inline fun doubled(n: Int): Int = n * 2
            class Alpha { fun twice(n: Int): Int = doubled(n) }
        """.trimIndent()
        val beta = "src/main/kotlin/dev/sample/Beta.kt" to """
            package dev.sample
            class Beta {
                fun thrice(n: Int): Int {
                    return n * 3
                }
            }
        """.trimIndent()
        val alphaTest = "src/test/kotlin/dev/sample/AlphaTest.kt" to """
            package dev.sample
            import org.junit.jupiter.api.Test
            import kotlin.test.assertEquals
            class AlphaTest { @Test fun twice() { assertEquals(2, Alpha().twice(1)) } }
        """.trimIndent()
        val betaTest = "src/test/kotlin/dev/sample/BetaTest.kt" to """
            package dev.sample
            import org.junit.jupiter.api.Test
            import kotlin.test.assertEquals
            class BetaTest { @Test fun thrice() { assertEquals(3, Beta().thrice(1)) } }
        """.trimIndent()

        val output = selectAfter(dir, kotlinBuild, alpha, beta, alphaTest, betaTest, edit = {
            edit(dir, beta.first, "return n * 3", "if (true) { throw IllegalStateException(\"entry\") }\n        return n * 3")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a Kotlin function given a new suspend lambda runs its caller's test`(@TempDir dir: File) {
        // The lambda compiles to a class the map never saw, which carries only the annotations
        // the compiler writes on every such class. Alpha is as above, for the inline scan.
        val alpha = "src/main/kotlin/dev/sample/Alpha.kt" to """
            package dev.sample
            inline fun doubled(n: Int): Int = n * 2
            class Alpha { fun twice(n: Int): Int = doubled(n) }
        """.trimIndent()
        val beta = "src/main/kotlin/dev/sample/Beta.kt" to """
            package dev.sample
            import kotlin.coroutines.*
            class Beta {
                fun thrice(n: Int): Int {
                    var result = 0
                    val block: suspend () -> Int = { n * 3 }
                    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
                    return result
                }
            }
        """.trimIndent()
        val alphaTest = "src/test/kotlin/dev/sample/AlphaTest.kt" to """
            package dev.sample
            import org.junit.jupiter.api.Test
            import kotlin.test.assertEquals
            class AlphaTest { @Test fun twice() { assertEquals(2, Alpha().twice(1)) } }
        """.trimIndent()
        val betaTest = "src/test/kotlin/dev/sample/BetaTest.kt" to """
            package dev.sample
            import org.junit.jupiter.api.Test
            import kotlin.test.assertEquals
            class BetaTest { @Test fun thrice() { assertEquals(3, Beta().thrice(1)) } }
        """.trimIndent()

        val output = selectAfter(dir, kotlinBuild, alpha, beta, alphaTest, betaTest, edit = {
            edit(dir, beta.first, "{ n * 3 }", "{\n            val inner: suspend () -> Int = { n * 4 }\n            inner()\n        }")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
        assertEquals(null, decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `an overriding toString deleted runs the test that formatted the class`(@TempDir dir: File) {
        val beta = "src/main/java/dev/sample/Beta.java" to """
            package dev.sample;
            public class Beta {
                @Override public String toString() { return "beta"; }
            }
        """.trimIndent()
        val betaTest = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest { @Test void formats() { assertEquals("beta", String.valueOf(new Beta())); } }
        """.trimIndent()

        // Dispatch falls back to Object's toString, so BetaTest fails if it runs.
        val output = selectAfter(dir, minimalBuild, oneClass, oneTest, beta, betaTest, edit = {
            edit(dir, beta.first, "@Override public String toString() { return \"beta\"; }", "")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a toString override added to a class without one runs the test that formatted it`(@TempDir dir: File) {
        val beta = "src/main/java/dev/sample/Beta.java" to """
            package dev.sample;
            public class Beta {
            }
        """.trimIndent()
        val betaTest = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class BetaTest {
                @Test void formats() { assertTrue(String.valueOf(new Beta()).startsWith("dev.sample.Beta@")); }
            }
        """.trimIndent()

        val output = selectAfter(dir, minimalBuild, oneClass, oneTest, beta, betaTest, edit = {
            edit(dir, beta.first, "public class Beta {", "public class Beta {\n    @Override public String toString() { return \"fixed\"; }")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    // Groovy 4.0.22 cannot read Java 25 class files, so the fixture keeps to 21 on any daemon JDK.
    private val groovyBuild = minimalBuild
        .replace("java\n", "groovy\n")
        .replace("dependencies {", "dependencies {\n    implementation(\"org.apache.groovy:groovy:4.0.22\")")
        .plus("\njava { toolchain { languageVersion = JavaLanguageVersion.of(21) } }")

    private fun groovyBeta(body: String) = "src/main/groovy/dev/sample/Beta.groovy" to body.trimIndent()

    @Test
    fun `a Groovy method body edited runs the test that called it`(@TempDir dir: File) {
        val beta = groovyBeta(
            """
            package dev.sample
            class Beta { int thrice(int n) { n * 3 } }
            """
        )

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, beta, secondTest, edit = {
            edit(dir, beta.first, "n * 3", "n * 4")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a Groovy closure body edited runs the test that ran the closure`(@TempDir dir: File) {
        // The closure compiles to a class of its own, named after the class that declares it.
        val beta = groovyBeta(
            """
            package dev.sample
            class Beta { int thrice(int n) { [n].collect { it * 3 }.sum() } }
            """
        )

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, beta, secondTest, edit = {
            edit(dir, beta.first, "it * 3", "it * 4")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a Groovy method body given a new closure runs everything`(@TempDir dir: File) {
        // groovyc marks the methods it generates with an annotation, so the closure's new class
        // carries annotations the map never recorded.
        val beta = groovyBeta(
            """
            package dev.sample
            class Beta { int thrice(int n) { n * 3 } }
            """
        )

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, beta, secondTest, edit = {
            edit(dir, beta.first, "n * 3", "[n].collect { it * 4 }.sum()")
        }, run = GradleRunner::buildAndFail)

        assertFullRun(dir, "daemon-refused", both, output)
        assertEquals("annotations-unrecorded", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a Groovy trait method edited runs the test that reached it through its class`(@TempDir dir: File) {
        // The trait's body runs in its helper class; the implementing class only forwards to it.
        val tripler = "src/main/groovy/dev/sample/Tripler.groovy" to """
            package dev.sample
            trait Tripler { int thrice(int n) { n * 3 } }
        """.trimIndent()
        val beta = groovyBeta(
            """
            package dev.sample
            class Beta implements Tripler { }
            """
        )

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, tripler, beta, secondTest, edit = {
            edit(dir, tripler.first, "n * 3", "n * 4")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a Groovy script's method edited runs the test that called it`(@TempDir dir: File) {
        // A script declares no class: the one it compiles to is named after the file.
        val beta = groovyBeta(
            """
            package dev.sample
            int thrice(int n) { n * 3 }
            """
        )

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, beta, secondTest, edit = {
            edit(dir, beta.first, "n * 3", "n * 4")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a Groovy class declared beside another in one file runs the test that called it`(@TempDir dir: File) {
        val shapes = "src/main/groovy/dev/sample/Beta.groovy" to """
            package dev.sample
            class Gamma { int one() { 1 } }
            class Beta { int thrice(int n) { new Gamma().one() * n * 3 } }
        """.trimIndent()

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, shapes, secondTest, edit = {
            edit(dir, shapes.first, "n * 3", "n * 4")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a Groovy file named after none of its classes runs everything`(@TempDir dir: File) {
        // Its path names a class nothing compiles to unless the file also holds a script, which
        // the declarations alone cannot rule out, and no test ever recorded that name.
        val shapes = "src/main/groovy/dev/sample/Shapes.groovy" to """
            package dev.sample
            class Beta { int thrice(int n) { n * 3 } }
        """.trimIndent()

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, shapes, secondTest, edit = {
            edit(dir, shapes.first, "n * 3", "n * 4")
        }, run = GradleRunner::buildAndFail)

        assertFullRun(dir, "no-coverage-for-changed", both, output)
    }

    @Test
    fun `a Groovy method reached only through methodMissing runs the test that called it`(@TempDir dir: File) {
        val beta = groovyBeta(
            """
            package dev.sample
            class Beta { def methodMissing(String name, args) { args[0] * 3 } }
            """
        )
        val betaTest = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.codehaus.groovy.runtime.InvokerHelper;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest {
                @Test void passes() { assertEquals(3, InvokerHelper.invokeMethod(new Beta(), "thrice", 1)); }
            }
        """.trimIndent()

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, beta, betaTest, edit = {
            edit(dir, beta.first, "args[0] * 3", "args[0] * 4")
        }, run = GradleRunner::buildAndFail)

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a Groovy constant a Java test copied runs that test`(@TempDir dir: File) {
        // groovyc writes a ConstantValue for a static final primitive or String, so javac copies the
        // value into the test and the test never loads Beta. The test's own class file, compiled
        // with the new value, is what names it; the moved constant alone refuses nothing.
        val beta = groovyBeta(
            """
            package dev.sample
            class Beta { public static final int FACTOR = 3 }
            """
        )
        val betaTest = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest { @Test void passes() { assertEquals(3, Beta.FACTOR); } }
        """.trimIndent()

        val output = selectAfter(dir, groovyBuild, oneClass, oneTest, beta, betaTest, edit = {
            edit(dir, beta.first, "FACTOR = 3", "FACTOR = 4")
        }, run = GradleRunner::buildAndFail)

        assertTrue("dev.sample.BetaTest" in ranTests(dir), output)
        assertNotEquals("constant-changed", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a global AST transform edited runs everything`(@TempDir dir: File) {
        // The transform runs in the compiler, never in a test, so no test recorded its class.
        val transform = listOf(
            "settings.gradle.kts" to "rootProject.name = \"sample\"\ninclude(\"transform\")",
            "transform/build.gradle.kts" to """
                plugins { groovy }
                repositories { mavenCentral() }
                dependencies { implementation("org.apache.groovy:groovy:4.0.22") }
                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
            """.trimIndent(),
            "transform/src/main/groovy/dev/transform/TagTransform.groovy" to """
                package dev.transform
                import java.lang.reflect.Modifier
                import org.codehaus.groovy.ast.ASTNode
                import org.codehaus.groovy.ast.ClassHelper
                import org.codehaus.groovy.ast.ClassNode
                import org.codehaus.groovy.ast.Parameter
                import org.codehaus.groovy.ast.expr.ConstantExpression
                import org.codehaus.groovy.ast.stmt.ReturnStatement
                import org.codehaus.groovy.control.CompilePhase
                import org.codehaus.groovy.control.SourceUnit
                import org.codehaus.groovy.transform.ASTTransformation
                import org.codehaus.groovy.transform.GroovyASTTransformation

                @GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
                class TagTransform implements ASTTransformation {
                    void visit(ASTNode[] nodes, SourceUnit source) {
                        source.AST.classes.findAll { it.packageName == 'dev.sample' }.each { ClassNode c ->
                            c.addMethod('tag', Modifier.PUBLIC, ClassHelper.STRING_TYPE, Parameter.EMPTY_ARRAY,
                                ClassNode.EMPTY_ARRAY, new ReturnStatement(new ConstantExpression('v1')))
                        }
                    }
                }
            """.trimIndent(),
            "transform/src/main/resources/META-INF/services/org.codehaus.groovy.transform.ASTTransformation" to
                "dev.transform.TagTransform\n",
        )
        val beta = groovyBeta(
            """
            package dev.sample
            class Beta { }
            """
        )
        val betaTest = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest { @Test void passes() { assertEquals("v1", new Beta().tag()); } }
        """.trimIndent()
        val build = groovyBuild.replace("dependencies {", "dependencies {\n    compileOnly(project(\":transform\"))")

        val output = selectAfter(dir, build, *transform.toTypedArray(), oneClass, oneTest, beta, betaTest, edit = {
            edit(dir, transform[2].first, "'v1'", "'v2'")
        }, run = GradleRunner::buildAndFail)

        assertFullRun(dir, "no-coverage-for-changed", both, output)
    }

    private val spockBuild = groovyBuild.replace(
        "dependencies {",
        "dependencies {\n    testImplementation(\"org.spockframework:spock-core:2.3-groovy-4.0\")",
    )

    private fun spockSpec(name: String, subject: String, expected: Int) =
        "src/test/groovy/dev/sample/${name}Spec.groovy" to """
            package dev.sample

            import spock.lang.Specification

            class ${name}Spec extends Specification {
                def "computes"() {
                    expect:
                    $subject == $expected
                }
            }
        """.trimIndent()

    private val gammaClass = "src/main/java/dev/sample/Gamma.java" to """
        package dev.sample;
        public class Gamma { public int four(int n) { return n * 4; } }
    """.trimIndent()

    private val gammaSpec = spockSpec("Gamma", "new Gamma().four(1)", 4)

    /** Three specifications in one JVM, in name order; the edit is to the last one. */
    private fun selectAfterGammaSpecEdit(dir: File, from: String, to: String): String =
        selectAfter(
            dir, spockBuild, oneClass, secondClass, gammaClass,
            spockSpec("Alpha", "new Alpha().twice(1)", 2), spockSpec("Beta", "new Beta().thrice(1)", 3), gammaSpec,
            edit = { edit(dir, gammaSpec.first, from, to) },
        )

    @Test
    fun `a line added above a Spock feature runs that specification and the one before it`(@TempDir dir: File) {
        // Spock records each feature's line in an annotation on its method, which a test class this
        // task runs is exempt from. The specification's own code runs between features, in the
        // window it shares with the specification that ended there; the first one never ran it.
        val output = selectAfterGammaSpecEdit(dir, "    def \"computes\"()", "    // moved down a line\n    def \"computes\"()")

        assertNarrowedTo(dir, setOf("dev.sample.BetaSpec", "dev.sample.GammaSpec"), output)
    }

    @Test
    fun `a line added in a Spock feature body runs that specification and the one before it`(@TempDir dir: File) {
        val output = selectAfterGammaSpecEdit(dir, "expect:", "expect:\n        1 == 1")

        assertNarrowedTo(dir, setOf("dev.sample.BetaSpec", "dev.sample.GammaSpec"), output)
    }

    @Test
    fun `a change to code a Spock global extension ran at discovery runs everything`(@TempDir dir: File) {
        // A global extension starts before the test plan, where no specification owns the coverage.
        val extension = "src/test/groovy/dev/sample/AlphaExtension.groovy" to """
            package dev.sample

            import org.spockframework.runtime.extension.IGlobalExtension

            class AlphaExtension implements IGlobalExtension {
                @Override
                void start() { new Alpha().twice(1) }
            }
        """.trimIndent()
        val registration = "src/test/resources/META-INF/services/org.spockframework.runtime.extension.IGlobalExtension" to
            "dev.sample.AlphaExtension\n"
        val output = selectAfter(
            dir, spockBuild, oneClass, secondClass, extension, registration,
            spockSpec("Beta", "new Beta().thrice(1)", 3), spockSpec("Gamma", "new Beta().thrice(2)", 6),
            edit = { edit(dir, oneClass.first, "return n * 2;", "return n + n;") },
        )

        assertFullRun(dir, "startup-coverage", setOf("dev.sample.BetaSpec", "dev.sample.GammaSpec"), output)
    }

    @Test
    fun `a trailing comment on a line of a main class narrows to the tests that ran the class`(@TempDir dir: File) {
        // The compiled bytes do not change, but the source file did, and the source is what the
        // change set names.
        val output = selectAfter(dir, minimalBuild, oneClass, oneTest, secondClass, secondTest, edit = {
            edit(dir, secondClass.first, "return n * 3; } }", "return n * 3; } } // three times")
        })

        assertNarrowedTo(dir, setOf("dev.sample.BetaTest"), output)
    }

    @Test
    fun `a value edited in a main resource runs everything`(@TempDir dir: File) {
        val beta = "src/main/java/dev/sample/Beta.java" to """
            package dev.sample;
            import java.io.InputStream;
            import java.util.Properties;
            public class Beta {
                public String greeting() throws Exception {
                    Properties p = new Properties();
                    try (InputStream in = Beta.class.getResourceAsStream("/app.properties")) { p.load(in); }
                    return p.getProperty("greeting");
                }
            }
        """.trimIndent()
        val betaTest = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class BetaTest { @Test void greets() throws Exception { assertFalse(new Beta().greeting().isEmpty()); } }
        """.trimIndent()
        val properties = "src/main/resources/app.properties" to "greeting=hello\n"

        val output = selectAfter(dir, minimalBuild, oneClass, oneTest, beta, betaTest, properties, edit = {
            edit(dir, properties.first, "hello", "hi")
        })

        assertFullRun(dir, "unmappable-paths", both, output)
    }

    @Test
    fun `a line removed from a service file runs everything`(@TempDir dir: File) {
        val tick = "src/main/java/dev/sample/Tick.java" to """
            package dev.sample;
            public class Tick implements Runnable { public void run() { } }
        """.trimIndent()
        val tock = "src/main/java/dev/sample/Tock.java" to """
            package dev.sample;
            public class Tock implements Runnable { public void run() { } }
        """.trimIndent()
        val services = "src/main/resources/META-INF/services/java.lang.Runnable" to
            "dev.sample.Tick\ndev.sample.Tock\n"
        val betaTest = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import java.util.ServiceLoader;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class BetaTest { @Test void loads() { assertTrue(ServiceLoader.load(Runnable.class).iterator().hasNext()); } }
        """.trimIndent()

        val output = selectAfter(dir, minimalBuild, oneClass, oneTest, tick, tock, services, betaTest, edit = {
            edit(dir, services.first, "dev.sample.Tock\n", "")
        })

        assertFullRun(dir, "unmappable-paths", both, output)
    }

    @Test
    fun `a system property added to the test task runs everything`(@TempDir dir: File) {
        val output = selectAfter(dir, minimalBuild, oneClass, oneTest, secondClass, secondTest, edit = {
            edit(dir, "build.gradle.kts", "tasks.test { useJUnitPlatform() }",
                "tasks.test { useJUnitPlatform(); systemProperty(\"mode\", \"fast\") }")
        })

        // The new property is in the test JVM's environment fingerprint, and that refusal is decided
        // before the build script's path is looked at.
        assertFullRun(dir, "daemon-refused", both, output)
        assertEquals("environment-changed", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a version changed in the version catalog runs everything`(@TempDir dir: File) {
        val catalogBuild = minimalBuild.replace(
            "platform(\"org.junit:junit-bom:5.11.4\")", "platform(libs.junit.bom)",
        )
        // The catalog's path runs everything whether or not the build reads it, so an unwired
        // catalog would still pass.
        check("libs.junit.bom" in catalogBuild) { "the build does not read the catalog" }
        val catalog = "gradle/libs.versions.toml" to """
            [versions]
            junit = "5.11.4"
            [libraries]
            junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
        """.trimIndent() + "\n"

        val output = selectAfter(dir, catalogBuild, catalog, oneClass, oneTest, secondClass, secondTest, edit = {
            edit(dir, catalog.first, "\"5.11.4\"", "\"5.11.3\"")
        })

        assertFullRun(dir, "unmappable-paths", both, output)
    }
}
