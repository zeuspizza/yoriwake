package io.github.zeuspizza.yoriwake.agent.select;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tests the user has told us never to skip, by tag or by name pattern.
 *
 * <p>Coverage cannot see a dependency held in shared state, so an order-dependent test needs a
 * way to be pinned without switching selection off for the whole task.
 *
 * <p>It can only ever include: a match is an unconditional include, so no configuration here can
 * drop a test. No annotation is shipped; a JUnit Platform {@code @Tag} or a pattern needs no new
 * dependency in the host build.
 */
public final class AlwaysRun {

    /**
     * The opt-out tag: {@code @Tag("yoriwake-always-run")} on a class or a method.
     *
     * <p>Namespaced so it cannot collide with a tag the host build already filters on.
     */
    public static final String TAG = "yoriwake-always-run";

    private static final AlwaysRun NOTHING = new AlwaysRun(
            Collections.<String>emptyList(), Collections.<Pattern>emptyList());

    private final List<String> sources;
    private final List<Pattern> patterns;
    private final Set<String> matched = Collections.synchronizedSet(new LinkedHashSet<String>());

    private AlwaysRun(List<String> sources, List<Pattern> patterns) {
        this.sources = sources;
        this.patterns = patterns;
    }

    /**
     * Parses a comma-separated list of globs.
     *
     * @throws IllegalArgumentException for a pattern that would pin the entire suite, which is
     *     selection switched off while still reporting that it narrowed.
     */
    public static AlwaysRun from(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return NOTHING;
        }
        List<String> sources = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        for (String part : raw.split(",")) {
            String glob = part.trim();
            // An empty entry (`a,,b`) would match every test; a stray comma must not switch
            // selection off.
            if (glob.isEmpty()) {
                continue;
            }
            if (glob.replace("*", "").isEmpty()) {
                throw new IllegalArgumentException(
                        "'" + glob + "' as an always-run pattern would pin every test in the suite, "
                                + "which is not a configuration -- it is selection switched off, and "
                                + "it would keep reporting that it narrowed. Use -Pyoriwake.disabled if "
                                + "that is what you want, or name the tests you mean.");
            }
            sources.add(glob);
            patterns.add(compile(glob));
        }
        return sources.isEmpty() ? NOTHING : new AlwaysRun(sources, patterns);
    }

    /**
     * Whether this name is pinned. {@code name} is {@code com.acme.Test} or
     * {@code com.acme.Test.method}.
     */
    public boolean matches(String name) {
        if (name == null || patterns.isEmpty()) {
            return false;
        }
        boolean hit = false;
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).matcher(name).matches()) {
                matched.add(sources.get(i));
                hit = true;
            }
        }
        return hit;
    }

    /**
     * The patterns that have matched nothing so far.
     *
     * <p>A pattern with a typo matches nothing while the build stays green, so the user would
     * believe their test is pinned. Tracked per pattern, so one working pin cannot vouch for a
     * broken one beside it.
     */
    public Set<String> unmatched() {
        Set<String> left = new LinkedHashSet<>(sources);
        synchronized (matched) {
            left.removeAll(matched);
        }
        return left;
    }

    /** How many patterns were configured, matched or not. */
    public int size() {
        return sources.size();
    }

    /**
     * Globs, not regexes: a class name is full of dots and they must be literal.
     *
     * <p>A pattern naming a class pins its methods too, via the optional trailing group; otherwise
     * the pin would read as working and protect nothing. The suffix must begin at a dot, so naming
     * a method still pins only that method.
     */
    private static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String literal : glob.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        return Pattern.compile(regex.append("(?:\\..*)?").toString());
    }
}
