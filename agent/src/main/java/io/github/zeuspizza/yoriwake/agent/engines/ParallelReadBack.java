package io.github.zeuspizza.yoriwake.agent.engines;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.platform.launcher.TestPlan;

/**
 * Whether the test JVM runs its tests one at a time, read from the configuration the engines
 * themselves read. The plugin decides from the build script, but a task action or a JVM argument
 * provider can switch parallel execution on after it looked; per-test capture from interleaved
 * tests would attribute one test's coverage to another, so what this JVM sees has the last word.
 */
final class ParallelReadBack {

    static final String JUPITER = "junit.jupiter.execution.parallel.enabled";
    static final String VINTAGE = "junit.vintage.execution.parallel.enabled";
    static final String TESTNG = "testng.parallel";

    private ParallelReadBack() {}

    /**
     * Why {@code plan}'s tests may run concurrently, or null when they run one at a time. A Platform too
     * old to show its configuration answers only when {@code armed}: the plugin asked for sequential
     * execution and needs it confirmed; otherwise the plugin's own reading stands.
     */
    static String unconfirmed(TestPlan plan, boolean armed) {
        return unconfirmed(() -> plan.getConfigurationParameters()::get, armed);
    }

    static String unconfirmed(Supplier<Function<String, Optional<String>>> configuration, boolean armed) {
        Function<String, Optional<String>> parameters;
        try {
            parameters = configuration.get();
        } catch (LinkageError e) {
            return armed
                ? "this JUnit Platform does not show its configuration parameters (1.8 or later does)"
                : null;
        } catch (RuntimeException e) {
            return armed ? "the test plan's configuration could not be read: " + e : null;
        }
        return unconfirmed(parameters);
    }

    static String unconfirmed(Function<String, Optional<String>> parameters) {
        if (enabled(parameters.apply(JUPITER))) {
            return "JUnit Jupiter's parallel execution is on in the test JVM (" + JUPITER + ")";
        }
        if (enabled(parameters.apply(VINTAGE))) {
            return "the JUnit Vintage engine's parallel execution is on in the test JVM (" + VINTAGE + ")";
        }
        Optional<String> testng = parameters.apply(TESTNG);
        if (testng.isPresent() && !testng.get().trim().equalsIgnoreCase("none")) {
            return "TestNG's parallel mode is " + testng.get().trim() + " in the test JVM (" + TESTNG + ")";
        }
        return null;
    }

    // As the engines parse it, whitespace aside: any other value leaves the switch off.
    private static boolean enabled(Optional<String> value) {
        return value.isPresent() && value.get().trim().equalsIgnoreCase("true");
    }
}
