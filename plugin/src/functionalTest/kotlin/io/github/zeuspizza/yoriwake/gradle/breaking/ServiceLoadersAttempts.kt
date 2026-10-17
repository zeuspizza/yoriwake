package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on implementations found through `ServiceLoader`. A provider the loader instantiates is
 * executed, and recorded like any class a test runs; one it only lists is loaded without running;
 * the provider-configuration file that names it is a resource, which coverage cannot see.
 */
class ServiceLoadersAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val spi = source(
        "src/main/java/dev/sample/Spi.java",
        "package dev.sample;\npublic interface Spi { String encode(String s); }",
    )

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        """
        package dev.sample;
        public class Codec implements Spi { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }
        """,
    )

    private val upper = source(
        "src/main/java/dev/sample/Upper.java",
        """
        package dev.sample;
        public class Upper implements Spi { public String encode(String s) { return s.toUpperCase(); } }
        """,
    )

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    private val registration = source("src/main/resources/META-INF/services/dev.sample.Spi", "dev.sample.Codec\n")

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

    /** A test that finds the codec through `ServiceLoader` and runs it. */
    private val loaderTest = source(
        "src/test/java/dev/sample/A1ServiceTest.java",
        """
        package dev.sample;
        import java.util.ServiceLoader;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1ServiceTest {
            @Test void reverses() { assertEquals("cba", ServiceLoader.load(Spi.class).findFirst().get().encode("abc")); }
        }
        """,
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    // Track: fixtures. Row: SL1.
    @Test
    fun `a provider ServiceLoader instantiates and the test runs is held by its reach`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1ServiceTest", Aim.Row("reaches-change"),
            listOf(spi, codec, other, registration, first, loaderTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: SL2.
    @Test
    fun `a provider ServiceLoader only lists by type is held by its load`(@TempDir dir: File) {
        val typesTest = source(
            "src/test/java/dev/sample/A1ProviderTypesTest.java",
            """
            package dev.sample;
            import java.util.Arrays;
            import java.util.ServiceLoader;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1ProviderTypesTest {
                @Test void noStrictMode() {
                    assertFalse(ServiceLoader.load(Spi.class).stream().map(ServiceLoader.Provider::type)
                            .anyMatch(type -> Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals("strict"))));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ProviderTypesTest", Aim.Row("shares-jvm-changed-class"),
            listOf(spi, codec, other, registration, first, typesTest, codecTest, classOrderByName),
        ) {
            val file = File(it, codec.first)
            file.writeText(file.readText().replace("public String encode", "public boolean strict() { return true; } public String encode"))
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: coverage is dumped and reset around each test, so a provider
    // ServiceLoader instantiated once, inside an earlier test, is credited to that test. A test that
    // only calls the cached instance's method records that method's probes in its own window.
    @Test
    fun `a provider an earlier test had ServiceLoader instantiate and cache is held by the later caller's reach`(
        @TempDir dir: File,
    ) {
        val registry = source(
            "src/main/java/dev/sample/Registry.java",
            """
            package dev.sample;
            import java.util.ServiceLoader;
            public final class Registry {
                private static final Spi CODEC = ServiceLoader.load(Spi.class).findFirst().orElseThrow();
                private Registry() {}
                public static Spi codec() { return CODEC; }
            }
            """,
        )
        val warm = source(
            "src/test/java/dev/sample/A1WarmRegistryTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class A1WarmRegistryTest { @Test void found() { assertNotNull(Registry.codec()); } }
            """,
        )
        val cached = source(
            "src/test/java/dev/sample/A2CachedProviderTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2CachedProviderTest { @Test void reverses() { assertEquals("cba", Registry.codec().encode("abc")); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2CachedProviderTest", Aim.Row("reaches-change"),
            listOf(spi, codec, other, registry, registration, first, warm, cached, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a changed path maps to classes only when it is a `.java` or
    // `.kt` file under `src/<set>/java` or `src/<set>/kotlin`; anything else is unmappable. A build
    // laid out the Maven way keeps its provider-configuration file beside the sources, under
    // `src/main/java`, and the path still names no class.
    @Test
    fun `a provider-configuration file kept beside the sources forces a full run when it changes`(@TempDir dir: File) {
        val besideSources = source("src/main/java/META-INF/services/dev.sample.Spi", "dev.sample.Codec\n")
        val mavenLayout = minimalBuild + "\n" + """
            sourceSets.main {
                resources.srcDir("src/main/java")
                resources.exclude("**/*.java")
            }
        """.trimIndent()
        val result = attempt(
            dir, "dev.sample.A1ServiceTest", Aim.Forced("unmappable-paths"),
            listOf(spi, codec, upper, other, besideSources, first, loaderTest, classOrderByName),
            buildScript = mavenLayout,
        ) { File(it, besideSources.first).writeText("dev.sample.Upper\n") }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the lazy lookup loads each provider with initialize=false,
    // only to check it is a subtype of the service. Counting the providers through stream() never
    // instantiates one, so nothing of the provider runs and coverage is blind to it; only the load
    // dates the class. The count then fails on a provider that stopped implementing the service.
    @Test
    fun `a provider the lookup only checks for its type is held by its load`(@TempDir dir: File) {
        val presentTest = source(
            "src/test/java/dev/sample/A1ProviderPresentTest.java",
            """
            package dev.sample;
            import java.util.ServiceLoader;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1ProviderPresentTest {
                @Test void present() { assertEquals(1L, ServiceLoader.load(Spi.class).stream().count()); }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ProviderPresentTest", Aim.Row("shares-jvm-changed-class"),
            listOf(spi, codec, other, registration, first, presentTest, codecTest, classOrderByName),
        ) {
            val file = File(it, codec.first)
            file.writeText(file.readText().replace("public class Codec implements Spi", "public class Codec"))
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }
}
