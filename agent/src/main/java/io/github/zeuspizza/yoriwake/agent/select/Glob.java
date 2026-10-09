package io.github.zeuspizza.yoriwake.agent.select;

import java.util.regex.Pattern;

/**
 * A name pattern in which {@code *} matches any characters and nothing else is special, matched
 * against the whole name.
 *
 * <p>Shared by the always-run patterns and the plugin's full-run branches, so the two cannot drift
 * into different dialects.
 */
public final class Glob {

    private Glob() {
    }

    /** The regex for {@code glob}; {@link java.util.regex.Matcher#matches()} anchors it whole. */
    public static String regex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String literal : glob.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        return regex.toString();
    }

    /** Whether {@code name} matches {@code glob} whole. */
    public static boolean matches(String glob, String name) {
        return Pattern.matches(regex(glob), name);
    }
}
