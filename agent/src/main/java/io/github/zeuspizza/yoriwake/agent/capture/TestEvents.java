package io.github.zeuspizza.yoriwake.agent.capture;

/**
 * What an engine adapter reports about the tests it sees run, by test id. Each engine's own
 * callbacks translate to these; everything else about capture is engine-neutral.
 */
public interface TestEvents {

    /** A test began; its coverage window opens. */
    void started(String id);

    /** The open test did not pass. Its window stays open until it finishes. */
    void failed(String id);

    /**
     * The test ended, and its window closes. A null outcome means it passed, unless
     * {@link #failed} was reported for it.
     */
    void finished(String id, String outcome);

    /** A test that never ran, recorded so it stays selectable once it does. */
    void skipped(String id);

    /** The runner's own end of run. Ignored while a test is open: that is a nested run ending. */
    void runFinished();
}
