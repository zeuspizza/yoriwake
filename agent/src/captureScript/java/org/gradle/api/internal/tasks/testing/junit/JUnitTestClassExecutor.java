package org.gradle.api.internal.tasks.testing.junit;

import java.util.ArrayList;
import java.util.List;
import org.junit.runner.Request;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;

/**
 * Stands in for Gradle 8's JUnit 4 executor under its real name, so the agent attached to the JVM
 * rewrites it as it rewrites Gradle's: the class runs unless its filters, or the agent, empty it.
 */
public final class JUnitTestClassExecutor {

    /** Whether the class ran, rather than being left out. */
    public boolean execute(String name) throws ClassNotFoundException {
        ran = false;
        runTestClass(name);
        return ran;
    }

    private boolean ran;

    private void runTestClass(String name) throws ClassNotFoundException {
        Class<?> testClass = Class.forName(name);
        List<Filter> filters = new ArrayList<>();
        Request request = Request.aClass(testClass);
        Runner runner = request.getRunner();
        if (runner instanceof Filterable && !filters.isEmpty()) {
            Filterable filterable = (Filterable) runner;
            for (Filter filter : filters) {
                try {
                    filterable.filter(filter);
                } catch (NoTestsRemainException e) {
                    return;
                }
            }
        } else if (allTestsFiltered(runner, filters)) {
            return;
        }
        ran = true;
    }

    private boolean allTestsFiltered(Runner runner, List<Filter> filters) {
        return false;
    }
}
