package io.github.zeuspizza.yoriwake.agent.select;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An Ant-style glob over a path relative to the root project, as Gradle's include and exclude take
 * them: {@code *} and {@code ?} within one directory, {@code **} as a whole segment for any number
 * of directories, none included. Matched whole and case-exact.
 *
 * <p>Implemented once here, where a test's tag is read, and called from the plugin, so a declaration
 * cannot mean one thing in the build and another in the test JVM.
 */
public final class PathGlob {

    private PathGlob() {
    }

    /**
     * Why {@code glob} is not one this grammar reads, or null when it is. A leading {@code /}
     * anchors it at the root project. A glob that matches every path is refused: declaring
     * everything is selection switched off while still reporting that it narrowed.
     */
    public static String problem(String glob) {
        if (glob == null || glob.trim().isEmpty()) {
            return "is empty";
        }
        String relative = glob.startsWith("/") ? glob.substring(1) : glob;
        List<String> segments = Arrays.asList(relative.split("/", -1));
        if (segments.stream().allMatch(s -> s.equals("**") || s.isEmpty())) {
            return "matches every path";
        }
        if (glob.startsWith("!")) {
            return "starts with `!`, and negation is not supported";
        }
        if (glob.indexOf('\\') >= 0) {
            return "holds a backslash; separate directories with `/`";
        }
        if (glob.chars().anyMatch(c -> "[]{}".indexOf(c) >= 0)) {
            return "holds `[`, `]`, `{` or `}`, and character classes and braces are not supported";
        }
        if (segments.stream().anyMatch(s -> s.equals(".") || s.equals("..") || s.isEmpty())) {
            return "has an empty, `.` or `..` segment";
        }
        if (segments.stream().anyMatch(s -> s.contains("**") && !s.equals("**"))) {
            return "has `**` inside a segment, where it means only `*`; use it as a whole segment";
        }
        return null;
    }

    /**
     * {@code glob} as a glob over root-relative paths: its leading {@code /} dropped, or else
     * prefixed with {@code projectDir}, the declaring project's directory relative to the root
     * ({@code ""} for the root project).
     */
    public static String resolve(String glob, String projectDir) {
        if (glob.startsWith("/")) {
            return glob.substring(1);
        }
        return projectDir.isEmpty() ? glob : projectDir + "/" + glob;
    }

    /** Whether the root-relative {@code path} matches the root-relative {@code glob} whole. */
    public static boolean matches(String glob, String path) {
        return matchFrom(glob.split("/", -1), 0, path.split("/", -1), 0);
    }

    private static boolean matchFrom(String[] pattern, int p, String[] segments, int s) {
        if (p == pattern.length) {
            return s == segments.length;
        }
        if (pattern[p].equals("**")) {
            for (int next = s; next <= segments.length; next++) {
                if (matchFrom(pattern, p + 1, segments, next)) {
                    return true;
                }
            }
            return false;
        }
        return s < segments.length && segmentMatches(pattern[p], segments[s])
                && matchFrom(pattern, p + 1, segments, s + 1);
    }

    private static boolean segmentMatches(String glob, String segment) {
        if (glob.indexOf('*') < 0 && glob.indexOf('?') < 0) {
            return glob.equals(segment);
        }
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(segment).matches();
    }
}
