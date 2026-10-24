package io.github.zeuspizza.yoriwake.agent.select;

/**
 * A test's declared dependency: {@code @Tag("yoriwake-depends-on:<glob>")} on a test class or
 * method says the test depends on the paths the glob matches, in a way coverage cannot see (a file
 * handed to a container, a migration a database runs). A changed path it matches always selects
 * the test.
 *
 * <p>It can only ever include, as {@link AlwaysRun} does. A tag, not an annotation: a JUnit Platform
 * {@code @Tag} needs no new dependency in the host build.
 */
public final class DependsOn {

    /** Namespaced so it cannot collide with a tag the host build already filters on. */
    public static final String TAG_PREFIX = "yoriwake-depends-on:";

    private DependsOn() {
    }

    /** The glob a tag declares, or null when it is not a declaration. */
    public static String globOf(String tag) {
        return tag != null && tag.startsWith(TAG_PREFIX) ? tag.substring(TAG_PREFIX.length()) : null;
    }
}
