package io.github.zeuspizza.yoriwake.agent.capture;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.host.HostBuild;
import java.io.File;

/**
 * One {@link CaptureSession} for the whole JVM, for runners that give no plan start or end
 * (JUnit 4, TestNG) and whose events arrive through statics.
 *
 * <p>Opens on the first test boundary and ends at JVM shutdown, since neither runner calls back
 * after its last record; whether the run finished is said by the runner or by the host. Nothing
 * here may load a JUnit Platform class: a plain JUnit 4 or TestNG JVM has none.
 */
public final class JvmSession implements TestEvents {

    /** The one instance the engine adapters feed. */
    public static final JvmSession EVENTS = new JvmSession();

    private static CaptureSession session;
    private static CaptureClaim claim;
    private static boolean unusable;
    private static int failures;

    private JvmSession() {}

    @Override
    public void started(String id) {
        CaptureSession capture = ready();
        if (capture != null) {
            capture.started(id);
        }
    }

    @Override
    public void failed(String id) {
        CaptureSession capture = ready();
        if (capture != null) {
            capture.failed(id);
        }
    }

    @Override
    public void finished(String id, String outcome) {
        CaptureSession capture = ready();
        if (capture != null) {
            capture.finished(id, outcome);
        }
    }

    @Override
    public void skipped(String id) {
        CaptureSession capture = ready();
        if (capture != null) {
            capture.skipped(id);
        }
    }

    @Override
    public void runFinished() {
        CaptureSession capture = current();
        if (capture != null) {
            capture.runFinished();
        }
    }

    private static synchronized CaptureSession current() {
        return session;
    }

    /**
     * The session, opened on the first boundary; null when capture is off for this JVM's life. No
     * agent is silent; an attach that throws is counted.
     */
    private static synchronized CaptureSession ready() {
        if (session != null || unusable) {
            return session;
        }
        unusable = true;
        // Called from TestNG and from bytecode injected into RunNotifier: a throw here fails a
        // host test.
        String output = null;
        try {
            output = System.getProperty(AgentContract.RECORDS_DIR_PROPERTY);
            if (output == null || output.isEmpty()) {
                return null;
            }
            // Another capture already takes this JVM's coverage: the Platform's listener beside the
            // Platform's TestNG engine, which reports every test to both.
            claim = CaptureClaim.take(JvmSession.class);
            if (claim == null) {
                return null;
            }
            AgentLookup.Result found = AgentLookup.find();
            if (!found.isFound()) {
                release();
                return null;
            }
            CaptureSession opened = CaptureSession.open(
                    found.agent(), new File(output), HostBuild.current().workerId(), true);
            Runtime.getRuntime().addShutdownHook(new Thread(JvmSession::shutdown));
            session = opened;
            unusable = false;
        } catch (Throwable t) {
            release();
            recordFailure("opening capture at " + output + ": " + t);
        }
        return session;
    }

    /** Ends the session: JUnit 4's end of run reaches it only through the host. */
    static synchronized void shutdown() {
        if (session == null) {
            return;
        }
        session.endAtShutdown(HostBuild.current().runFinished());
        int total = failures + session.failures();
        if (total > 0) {
            System.out.println("[yoriwake] " + total
                    + " capture failures on this worker; its records are incomplete");
        }
    }

    private static void release() {
        if (claim != null) {
            claim.release();
            claim = null;
        }
    }

    private static void recordFailure(String message) {
        if (failures++ == 0) {
            System.out.println("[yoriwake] capture failure (further ones counted, not printed): " + message);
        }
    }

    /** Installs a session without an agent lookup or a shutdown hook, for tests. */
    static synchronized void install(CaptureSession installed) {
        session = installed;
        unusable = false;
    }

    /** Forgets every session and claim, so one test cannot see another's, for tests. */
    static synchronized void reset() {
        release();
        session = null;
        unusable = false;
        failures = 0;
    }
}
