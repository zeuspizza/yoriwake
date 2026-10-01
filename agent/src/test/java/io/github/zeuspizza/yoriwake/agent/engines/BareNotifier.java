package io.github.zeuspizza.yoriwake.agent.engines;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

/**
 * A notifier whose hooked methods need no operand stack of their own, and one whose first
 * instruction is a branch target -- shapes a real RunNotifier does not have. Java, because javac
 * compiles an empty body to a bare return with a max stack of zero.
 */
public class BareNotifier {
    public int loops;

    public void fireTestStarted(Description description) {}

    public void fireTestFinished(Description description) {}

    public void fireTestFailure(Failure failure) {
        while (loops < 3) {
            loops++;
        }
    }

    public void fireTestAssumptionFailure(Failure failure) {}
}
