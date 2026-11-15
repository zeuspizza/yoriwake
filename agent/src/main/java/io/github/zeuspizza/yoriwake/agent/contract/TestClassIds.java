package io.github.zeuspizza.yoriwake.agent.contract;

/**
 * Which test class a test id belongs to: the one reading capture, selection and the decoder share,
 * so a window written for a class is read back as that class's.
 *
 * <p>A class is a {@code [class:...]} segment, or a Spock specification's {@code [spec:...]}
 * segment directly under the Spock engine. Another engine's {@code [spec:...]} (Kotest's) is not a
 * class: what runs between its specs is not known to be theirs.
 */
public final class TestClassIds {

    private static final String CLASS_SEGMENT = "[class:";
    private static final String SPOCK_ENGINE = "[engine:spock]/";
    private static final String SPOCK_SPEC = SPOCK_ENGINE + "[spec:";

    private TestClassIds() {}

    /**
     * The id up to and including its outermost class segment: a prefix, because a class-scoped
     * record is expanded to the test ids starting with it. Null when the id has none.
     */
    public static String scopeOf(String id) {
        if (id == null) {
            return null;
        }
        // The outermost class: its @BeforeAll runs once before the first nested class, so scoping
        // to the innermost class would skip every sibling that shares that setup.
        int start = earliest(id.indexOf(CLASS_SEGMENT), specSegmentAt(id.indexOf(SPOCK_SPEC)));
        if (start < 0) {
            return null;
        }
        // Tracks depth because JUnit does not escape ']' inside a segment value.
        int depth = 0;
        for (int i = start; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return id.substring(0, i + 1);
                }
            }
        }
        return null;
    }

    /** The class name a scope from {@link #scopeOf} ends with, or null when it carries none. */
    public static String nameOf(String scope) {
        if (scope == null || !scope.endsWith("]")) {
            return null;
        }
        int classAt = scope.lastIndexOf(CLASS_SEGMENT);
        int specAt = specSegmentAt(scope.lastIndexOf(SPOCK_SPEC));
        int start = Math.max(classAt, specAt);
        if (start < 0) {
            return null;
        }
        int nameAt = scope.indexOf(':', start) + 1;
        String name = scope.substring(nameAt, scope.length() - 1);
        return name.isEmpty() ? null : name;
    }

    private static int specSegmentAt(int engineAt) {
        return engineAt < 0 ? -1 : engineAt + SPOCK_ENGINE.length();
    }

    private static int earliest(int a, int b) {
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }
}
