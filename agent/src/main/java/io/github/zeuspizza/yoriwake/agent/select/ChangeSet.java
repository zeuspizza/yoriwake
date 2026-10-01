package io.github.zeuspizza.yoriwake.agent.select;

import java.util.Collection;
import java.util.Collections;

/**
 * Everything {@link Selector#decide} is told about a change, as one value. Defaults are the
 * conservative reading of "nothing more is known": not accounted for, nothing provable, no refusal.
 *
 * <p>Immutable; each {@code with...} returns a copy. A plain class rather than a {@code record}:
 * this jar's bytecode floor is the oldest JVM a host tests on.
 */
public final class ChangeSet {

    private final Collection<String> changedClasses;
    private final Collection<String> unmappablePaths;
    private final boolean accountedFor;
    private final Collection<String> absenceProvable;
    private final Collection<String> unreadablePaths;
    private final Collection<String> ownTestClasses;
    private final Collection<String> exemptTestClasses;
    private final Collection<String> changedBytes;
    private final String daemonRefusal;

    private ChangeSet(
            Collection<String> changedClasses,
            Collection<String> unmappablePaths,
            boolean accountedFor,
            Collection<String> absenceProvable,
            Collection<String> unreadablePaths,
            Collection<String> ownTestClasses,
            Collection<String> exemptTestClasses,
            Collection<String> changedBytes,
            String daemonRefusal) {
        this.changedClasses = changedClasses;
        this.unmappablePaths = unmappablePaths;
        this.accountedFor = accountedFor;
        this.absenceProvable = absenceProvable;
        this.unreadablePaths = unreadablePaths;
        this.ownTestClasses = ownTestClasses;
        this.exemptTestClasses = exemptTestClasses;
        this.changedBytes = changedBytes;
        this.daemonRefusal = daemonRefusal;
    }

    /**
     * @param changedClasses fully-qualified names derived from changed source files
     * @param unmappablePaths changed paths the map cannot reason about at all
     */
    public static ChangeSet of(Collection<String> changedClasses, Collection<String> unmappablePaths) {
        return new ChangeSet(changedClasses, unmappablePaths, false, Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), null);
    }

    /**
     * The change set was not empty, and every path in it was shown unable to reach this JVM: its
     * module is absent from this task's classpath, or nothing in the build reads it and no compiled
     * class names it.
     */
    public ChangeSet withAccountedFor(boolean accountedFor) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                unreadablePaths, ownTestClasses, exemptTestClasses, changedBytes, daemonRefusal);
    }

    /** Prefixes for which the daemon's AbsenceEvidence holds; see {@code Selector.untested}. */
    public ChangeSet withAbsenceProvable(Collection<String> absenceProvable) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                unreadablePaths, ownTestClasses, exemptTestClasses, changedBytes, daemonRefusal);
    }

    /** Changed paths that are not build inputs and no compiled class names. */
    public ChangeSet withUnreadablePaths(Collection<String> unreadablePaths) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                unreadablePaths, ownTestClasses, exemptTestClasses, changedBytes, daemonRefusal);
    }

    /**
     * Changed classes whose every compiled form lives in this task's test output. They never force,
     * because discovery runs them anyway; the absence rule cannot serve them, since a test class
     * that ever ran is always in the loaded-class union.
     */
    public ChangeSet withOwnTestClasses(Collection<String> ownTestClasses) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                unreadablePaths, ownTestClasses, exemptTestClasses, changedBytes, daemonRefusal);
    }

    /**
     * Changed test classes of this task that nothing else names; see
     * {@code AgentContract.EXEMPT_TEST_CLASSES_PROPERTY}. Their discovery-time loading opens no
     * JVM-shared window; only a lookup, a read or another class's execution of them does.
     */
    public ChangeSet withExemptTestClasses(Collection<String> exemptTestClasses) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                unreadablePaths, ownTestClasses, exemptTestClasses, changedBytes, daemonRefusal);
    }

    /**
     * Compiled classes whose bytes differ from the capture's, by exact binary name. Tests that
     * recorded one are selected, and one no test recorded forces. Kept apart from the change set,
     * which is also the anchor set of the nested-class rule: a name added there could certify a
     * changed prefix that nothing covers.
     */
    public ChangeSet withChangedBytes(Collection<String> changedBytes) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                unreadablePaths, ownTestClasses, exemptTestClasses, changedBytes, daemonRefusal);
    }

    /**
     * Why nothing may be deselected, or null. The daemon knows things this side cannot see, such
     * as an unfinished SMAP scan or a changed compile-time constant.
     */
    public ChangeSet withDaemonRefusal(String daemonRefusal) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                unreadablePaths, ownTestClasses, exemptTestClasses, changedBytes, daemonRefusal);
    }

    public Collection<String> changedClasses() {
        return changedClasses;
    }

    public Collection<String> unmappablePaths() {
        return unmappablePaths;
    }

    public boolean accountedFor() {
        return accountedFor;
    }

    public Collection<String> absenceProvable() {
        return absenceProvable;
    }

    public Collection<String> unreadablePaths() {
        return unreadablePaths;
    }

    public Collection<String> ownTestClasses() {
        return ownTestClasses;
    }

    public Collection<String> exemptTestClasses() {
        return exemptTestClasses;
    }

    public Collection<String> changedBytes() {
        return changedBytes;
    }

    public String daemonRefusal() {
        return daemonRefusal;
    }
}
