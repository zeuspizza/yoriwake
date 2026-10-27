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
    private final Collection<String> ownTestClasses;
    private final Collection<String> exemptTestClasses;
    private final Collection<String> changedBytes;
    private final Collection<String> movedFiles;
    private final Collection<String> ignoredPaths;
    private final String daemonRefusal;
    private final String testJvmUnsupported;
    private final String baseFlag;

    private ChangeSet(
            Collection<String> changedClasses,
            Collection<String> unmappablePaths,
            boolean accountedFor,
            Collection<String> absenceProvable,
            Collection<String> ownTestClasses,
            Collection<String> exemptTestClasses,
            Collection<String> changedBytes,
            Collection<String> movedFiles,
            Collection<String> ignoredPaths,
            String daemonRefusal,
            String testJvmUnsupported,
            String baseFlag) {
        this.changedClasses = changedClasses;
        this.unmappablePaths = unmappablePaths;
        this.accountedFor = accountedFor;
        this.absenceProvable = absenceProvable;
        this.ownTestClasses = ownTestClasses;
        this.exemptTestClasses = exemptTestClasses;
        this.changedBytes = changedBytes;
        this.movedFiles = movedFiles;
        this.ignoredPaths = ignoredPaths;
        this.daemonRefusal = daemonRefusal;
        this.testJvmUnsupported = testJvmUnsupported;
        this.baseFlag = baseFlag;
    }

    /**
     * @param changedClasses fully-qualified names derived from changed source files
     * @param unmappablePaths changed paths the map cannot reason about at all
     */
    public static ChangeSet of(Collection<String> changedClasses, Collection<String> unmappablePaths) {
        return new ChangeSet(changedClasses, unmappablePaths, false, Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), null, null, null);
    }

    /**
     * The change set was not empty, and every path in it was accounted for: a class source whose
     * module is absent from this task's classpath and that no compiled class names, an IDE's state,
     * or a path the build declares no test reads.
     */
    public ChangeSet withAccountedFor(boolean accountedFor) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    /** Prefixes for which the daemon's AbsenceEvidence holds; see {@code Selector.untested}. */
    public ChangeSet withAbsenceProvable(Collection<String> absenceProvable) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    /**
     * Changed classes whose every compiled form lives in this task's test output. They never force,
     * because discovery runs them anyway; the absence rule cannot serve them, since a test class
     * that ever ran is always in the loaded-class union.
     */
    public ChangeSet withOwnTestClasses(Collection<String> ownTestClasses) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    /**
     * Changed test classes of this task that nothing else names; see
     * {@code AgentContract.EXEMPT_TEST_CLASSES_PROPERTY}. Their discovery-time loading opens no
     * JVM-shared window; only a lookup, a read or another class's execution of them does.
     */
    public ChangeSet withExemptTestClasses(Collection<String> exemptTestClasses) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    /**
     * Compiled classes whose bytes differ from the capture's, by exact binary name. Tests that
     * recorded one are selected, and one no test recorded forces. Kept apart from the change set,
     * which is also the anchor set of the nested-class rule: a name added there could certify a
     * changed prefix that nothing covers.
     */
    public ChangeSet withChangedBytes(Collection<String> changedBytes) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    /**
     * Build-produced classpath files whose content differs from the capture's, by the map's paths.
     * Each runs the tests from its first read on in every JVM that read it; a JVM whose reads were
     * not recorded runs whole.
     */
    public ChangeSet withMovedFiles(Collection<String> movedFiles) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    /**
     * Changed paths the build declares no test reads, taken out of the change set. A test runs from
     * its JVM's first read of one on, where the map recorded that JVM's reads.
     */
    public ChangeSet withIgnoredPaths(Collection<String> ignoredPaths) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    /**
     * Why nothing may be deselected, or null. The daemon knows things this side cannot see, such
     * as an unfinished SMAP scan or a changed compile-time constant.
     */
    public ChangeSet withDaemonRefusal(String daemonRefusal) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
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

    public Collection<String> ownTestClasses() {
        return ownTestClasses;
    }

    public Collection<String> exemptTestClasses() {
        return exemptTestClasses;
    }

    public Collection<String> changedBytes() {
        return changedBytes;
    }

    public Collection<String> movedFiles() {
        return movedFiles;
    }

    public Collection<String> ignoredPaths() {
        return ignoredPaths;
    }

    public String daemonRefusal() {
        return daemonRefusal;
    }

    /**
     * Why the test JVM deciding is newer than the agent's ASM reads, or null. Set only in that JVM:
     * the daemon judges the test JVM from its launcher, never from its own.
     */
    public ChangeSet withTestJvmUnsupported(String testJvmUnsupported) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    public String testJvmUnsupported() {
        return testJvmUnsupported;
    }

    /**
     * The base flag as the build's command line spells it, for the advice an empty change set
     * gives; null for Gradle's. The host knows its spelling, and the JVM deciding may not.
     */
    public ChangeSet withBaseFlag(String baseFlag) {
        return new ChangeSet(changedClasses, unmappablePaths, accountedFor, absenceProvable,
                ownTestClasses, exemptTestClasses, changedBytes, movedFiles, ignoredPaths, daemonRefusal, testJvmUnsupported, baseFlag);
    }

    public String baseFlag() {
        return baseFlag;
    }
}
