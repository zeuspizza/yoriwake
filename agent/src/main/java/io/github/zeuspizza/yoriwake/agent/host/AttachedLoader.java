package io.github.zeuspizza.yoriwake.agent.host;

/**
 * Whether a class of this agent is the copy the JVM attached, or a second copy that another class
 * loader defined from the same jar.
 *
 * <p>A loader built from the test classpath (spring-core-test's forked class loader, an isolating
 * test runner) defines the agent's classes again and may run a nested JUnit launcher that discovers
 * them. Each copy has statics of its own, so a copy that selected, captured or wrote a record would
 * judge a nested run the map never described and publish under the attached copy's file names.
 * Only the attached copy acts; every other copy stands down and changes nothing about what runs.
 *
 * <p>The attached copy is the one the system class loader resolves: a {@code -javaagent} jar is
 * appended to that loader's class path, and the plugin puts the same jar on the test runtime
 * classpath the worker runs from. There is one system loader per JVM, so the answer needs no state
 * a copy could hold a version of its own. Asked per class, because a loader may define some of the
 * agent's classes itself and delegate others.
 */
public final class AttachedLoader {

    private AttachedLoader() {}

    /**
     * True when {@code type} is the class the system class loader resolves under its name. False
     * for a copy, and whenever that cannot be established: a copy that stands down only runs more.
     */
    public static boolean attached(Class<?> type) {
        try {
            return Class.forName(type.getName(), false, ClassLoader.getSystemClassLoader()) == type;
        } catch (Throwable unknown) {
            return false;
        }
    }
}
