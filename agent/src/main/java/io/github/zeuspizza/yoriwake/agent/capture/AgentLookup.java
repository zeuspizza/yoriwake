package io.github.zeuspizza.yoriwake.agent.capture;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Locates the running JaCoCo agent by reflection.
 *
 * <p>Reflection because the agent classes come from the {@code -javaagent} at runtime and are
 * absent from this jar: a direct reference would throw {@code NoClassDefFoundError} inside a test
 * run instead of producing a diagnostic.
 */
public final class AgentLookup {

    static final String RT_CLASS = "org.jacoco.agent.rt.RT";

    private AgentLookup() {}

    public static Result find() {
        return find(AgentLookup.class.getClassLoader());
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
            return Result.missing(Unavailable.notAttached(cause));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Result.missing(Unavailable.notAttached(e));
        }

        if (agent == null) {
            return Result.missing(
                    Unavailable.notAttached(new IllegalStateException("RT.getAgent() returned null")));
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

        private Unavailable(String diagnostic) {
            this.diagnostic = diagnostic;
        }

        static Unavailable notOnClasspath(Throwable cause) {
            return new Unavailable(
                    "JaCoCo agent classes (" + RT_CLASS + ") are not visible to this classloader. "
                            + "The test JVM was probably started without -javaagent:jacocoagent.jar. "
                            + "Cause: " + describe(cause));
        }

        static Unavailable notAttached(Throwable cause) {
            return new Unavailable(
                    RT_CLASS + " is loadable but no agent is running. Cause: " + describe(cause));
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

    static final class ReflectiveJacocoAgent implements JacocoAgent {

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
