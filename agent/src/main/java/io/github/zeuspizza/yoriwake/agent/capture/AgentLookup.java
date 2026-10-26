package io.github.zeuspizza.yoriwake.agent.capture;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Locates the running JaCoCo agent by reflection.
 *
 * <p>Reflection because the agent classes come from the {@code -javaagent} at runtime and are
 * absent from this jar: a direct reference would throw {@code NoClassDefFoundError} inside a test
 * run instead of producing a diagnostic.
 */
public final class AgentLookup {

    static final String RT_CLASS = "org.jacoco.agent.rt.RT";

    /** Each JaCoCo build's agent class, in a package named after that build. */
    private static final Pattern AGENT_CLASS = Pattern.compile("org\\.jacoco\\.agent\\.rt\\.internal_[0-9a-f]+\\.Agent");

    /** This agent's own, from its premain; null where it was not attached. */
    private static volatile Instrumentation instrumentation;

    private AgentLookup() {}

    static void instrumentation(Instrumentation value) {
        instrumentation = value;
    }

    public static Result find() {
        Instrumentation held = instrumentation;
        Result result = find(AgentLookup.class.getClassLoader());
        if (held == null || !(result.isFound() || result.reason().rtAgentNotStarted)) {
            return result;
        }
        // A JaCoCo runtime on the test classpath comes before the -javaagent's jar, so RT can name
        // a copy whose agent never started while the -javaagent's one holds the coverage, or one
        // that offline-instrumented classes started beside it. Either way the started agents decide.
        // Unobserved: the whole loaded set is a lookup of every class when a test asks for it.
        Supplier<Class<?>[]> loaded = () -> TouchRecorder.JVM.unobserved(held::getAllLoadedClasses);
        if (result.isFound() && startedAgents(loaded.get()).isEmpty()) {
            // A JaCoCo whose agent class is named differently: RT's answer is all there is.
            return result;
        }
        return startedAmong(loaded, result.isFound() ? null : result.reason());
    }

    /**
     * The one started JaCoCo agent among [loaded]; missing, with [rtReason] kept, when there is none,
     * and refused when several have started, since coverage would split between them. [rtReason] is
     * null only where RT found a started agent, which is then among them.
     */
    static Result startedAmong(Supplier<Class<?>[]> loaded, Unavailable rtReason) {
        List<Object> started = startedAgents(loaded.get());
        if (started.isEmpty()) {
            return Result.missing(Unavailable.noneStarted(rtReason));
        }
        if (started.size() > 1) {
            return Result.missing(Unavailable.severalStarted(started.size(), describe(started)));
        }
        Object agent = started.get(0);
        try {
            return Result.found(new RescanningAgent(agent, loaded));
        } catch (ReflectiveOperationException e) {
            return Result.missing(Unavailable.notAttached(e));
        }
    }

    private static List<Object> startedAgents(Class<?>[] loaded) {
        List<Object> started = new ArrayList<>();
        for (Class<?> type : loaded) {
            if (!AGENT_CLASS.matcher(type.getName()).matches()) {
                continue;
            }
            try {
                Object agent = type.getMethod("getInstance").invoke(null);
                if (agent != null && !started.contains(agent)) {
                    started.add(agent);
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                // Not started, or not shaped like JaCoCo's agent: either way it holds no coverage.
            }
        }
        return started;
    }

    /** An agent found among the loaded classes, which looks again for others when asked. */
    static final class RescanningAgent extends ReflectiveJacocoAgent {

        private final Object agent;
        private final Supplier<Class<?>[]> loaded;

        RescanningAgent(Object agent, Supplier<Class<?>[]> loaded) throws ReflectiveOperationException {
            super(agent);
            this.agent = agent;
            this.loaded = loaded;
        }

        @Override
        public String anotherStarted() {
            List<Object> others = startedAgents(loaded.get());
            others.removeIf(other -> other == agent);
            return others.isEmpty() ? null : describe(others) + " started besides " + agent.getClass().getName()
                    + ", so its coverage is missing from this capture";
        }
    }

    private static String describe(List<Object> agents) {
        List<String> names = new ArrayList<>();
        for (Object agent : agents) {
            names.add(agent.getClass().getName());
        }
        return String.join(", ", names);
    }

    static Result find(ClassLoader classLoader) {
        Class<?> rt;
        try {
            rt = Class.forName(RT_CLASS, true, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return Result.missing(Unavailable.notOnClasspath(e));
        }

        Object agent;
        try {
            agent = rt.getMethod("getAgent").invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
            return Result.missing(Unavailable.notStarted(cause));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Result.missing(Unavailable.notAttached(e));
        }

        if (agent == null) {
            return Result.missing(
                    Unavailable.notStarted(new IllegalStateException("RT.getAgent() returned null")));
        }

        try {
            return Result.found(new ReflectiveJacocoAgent(agent));
        } catch (ReflectiveOperationException e) {
            return Result.missing(Unavailable.notAttached(e));
        }
    }

    /**
     * Why the agent could not be reached, phrased for a human.
     *
     * <p>{@link #notOnClasspath} means the JaCoCo agent was never attached, a build configuration
     * problem. {@link #notAttached} means the classes are present but the agent did not initialise,
     * typically a classloader-visibility problem.
     */
    public static final class Unavailable {

        private final String diagnostic;

        /** RT loaded and named an agent that never started: another may hold the coverage. */
        final boolean rtAgentNotStarted;

        private Unavailable(String diagnostic) {
            this(diagnostic, false);
        }

        private Unavailable(String diagnostic, boolean rtAgentNotStarted) {
            this.diagnostic = diagnostic;
            this.rtAgentNotStarted = rtAgentNotStarted;
        }

        static Unavailable notOnClasspath(Throwable cause) {
            return new Unavailable(
                    "JaCoCo agent classes (" + RT_CLASS + ") are not visible to this classloader. "
                            + "The test JVM was probably started without -javaagent:jacocoagent.jar. "
                            + "Cause: " + describe(cause));
        }

        static Unavailable notAttached(Throwable cause) {
            return new Unavailable(notRunning(cause));
        }

        /** As {@link #notAttached}, where RT's own agent answered that it never started. */
        static Unavailable notStarted(Throwable cause) {
            return new Unavailable(notRunning(cause), true);
        }

        private static String notRunning(Throwable cause) {
            return RT_CLASS + " is loadable but no agent is running. Cause: " + describe(cause);
        }

        static Unavailable noneStarted(Unavailable rtReason) {
            return new Unavailable(rtReason.diagnostic + " Among the classes this JVM has loaded, no other "
                    + "started JaCoCo agent was found.");
        }

        static Unavailable severalStarted(int count, String agents) {
            return new Unavailable(RT_CLASS + " names no running agent, and " + count + " JaCoCo agents have "
                    + "started in this JVM (" + agents + "): coverage would split between them.");
        }

        private static String describe(Throwable cause) {
            return cause.getClass().getName() + ": " + cause.getMessage();
        }

        public String diagnostic() {
            return diagnostic;
        }
    }

    /** Either a reachable {@link JacocoAgent} or the reason it could not be reached. */
    public static final class Result {

        private final JacocoAgent agent;
        private final Unavailable reason;

        private Result(JacocoAgent agent, Unavailable reason) {
            this.agent = agent;
            this.reason = reason;
        }

        public static Result found(JacocoAgent agent) {
            return new Result(agent, null);
        }

        public static Result missing(Unavailable reason) {
            return new Result(null, reason);
        }

        public boolean isFound() {
            return agent != null;
        }

        public JacocoAgent agent() {
            if (agent == null) {
                throw new IllegalStateException("No agent: " + reason.diagnostic());
            }
            return agent;
        }

        public Unavailable reason() {
            if (reason == null) {
                throw new IllegalStateException("Agent was found; there is no failure reason");
            }
            return reason;
        }
    }

    static class ReflectiveJacocoAgent implements JacocoAgent {

        private final Object delegate;
        private final Method getVersion;
        private final Method getExecutionData;

        ReflectiveJacocoAgent(Object delegate) throws ReflectiveOperationException {
            this.delegate = delegate;
            this.getVersion = delegate.getClass().getMethod("getVersion");
            this.getExecutionData = delegate.getClass().getMethod("getExecutionData", boolean.class);
            this.getVersion.setAccessible(true);
            this.getExecutionData.setAccessible(true);
        }

        @Override
        public String version() {
            try {
                return (String) getVersion.invoke(delegate);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("IAgent.getVersion() failed", e);
            }
        }

        @Override
        public byte[] takeExecutionData() {
            try {
                return (byte[]) getExecutionData.invoke(delegate, true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("IAgent.getExecutionData(true) failed", e);
            }
        }
    }
}
