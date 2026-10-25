package io.github.zeuspizza.yoriwake.agent.engines;

import java.util.ArrayList;
import java.util.List;
import org.junit.runner.Request;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;

/** Gradle's JUnit 4 executors, before and after Gradle 9, as far as their rewrite reads them. */
public final class ExecutorStandIns {

    private ExecutorStandIns() {}

    /** Gradle 8's: runs the class itself, and returns early when its filters emptied it. */
    public static class Gradle8 {
        public final List<String> ran = new ArrayList<>();

        public void execute(String name) throws Exception {
            runTestClass(name);
        }

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
            ran.add(name);
        }

        private boolean allTestsFiltered(Runner runner, List<Filter> filters) {
            return false;
        }
    }

    /** Gradle 9's: builds the request, null when its filters emptied the class. */
    public static class Gradle9 {
        public Request request(Class<?> testClass) {
            return buildFilteredRequest(testClass);
        }

        private Request buildFilteredRequest(Class<?> testClass) {
            Request request = Request.aClass(testClass);
            Runner runner = request.getRunner();
            List<Filter> filters = new ArrayList<>();
            if (runner instanceof Filterable && !filters.isEmpty()) {
                Filterable filterable = (Filterable) runner;
                for (Filter filter : filters) {
                    try {
                        filterable.filter(filter);
                    } catch (NoTestsRemainException e) {
                        return null;
                    }
                }
            } else if (allTestsFiltered(runner, filters)) {
                return null;
            }
            return request;
        }

        private boolean allTestsFiltered(Runner runner, List<Filter> filters) {
            return false;
        }
    }

    /** An executor whose method has none of the shape the rewrite looks for. */
    public static class Unknown {
        private void runTestClass(String name) {
        }
    }
}
