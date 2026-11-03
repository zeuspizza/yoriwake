package io.github.zeuspizza.yoriwake.capturescript;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.Parameterized;

/** JUnit 4 classes for plain JUnit 4 selection to judge. Never run: only their runners are built. */
public final class JudgedClasses {

    private JudgedClasses() {}

    public static class AlphaTest {
        @Test
        public void a() {}

        @Test
        @Ignore
        public void ignored() {}
    }

    public static class BetaTest {
        @Test
        public void b() {}
    }

    @RunWith(Parameterized.class)
    public static class ParameterizedAlphaTest {
        @Parameterized.Parameters
        public static Collection<Object[]> data() {
            return Arrays.asList(new Object[][] {{1}});
        }

        public ParameterizedAlphaTest(int value) {}

        @Test
        public void p() {}
    }

    @RunWith(Undescribable.class)
    public static class UndescribableTest {
        @Test
        public void u() {}
    }

    /**
     * A runner that describes itself to JUnit and to nothing else. JUnit's builder asks for the
     * description while building it, and puts its error runner in place of one that fails then.
     */
    public static final class Undescribable extends Runner {
        private final Class<?> testClass;

        public Undescribable(Class<?> testClass) {
            this.testClass = testClass;
        }

        @Override
        public Description getDescription() {
            if (!new Throwable().getStackTrace()[1].getClassName().startsWith("org.junit.")) {
                throw new IllegalStateException("no description");
            }
            return Description.createSuiteDescription(testClass);
        }

        @Override
        public void run(RunNotifier notifier) {}
    }
}
