package io.github.zeuspizza.yoriwake.capturescript;

import java.util.concurrent.CountDownLatch;

/** Runs a test's start on another thread and its finish there later, in a fixed order. */
final class Threads {

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch mayFinish = new CountDownLatch(1);
    private final Thread thread;

    Threads(Runnable start, Runnable finish) {
        thread = new Thread(() -> {
            start.run();
            started.countDown();
            await(mayFinish);
            finish.run();
        }, "second-test-thread");
    }

    /** Starts the other thread's test and returns once it has started. */
    void start() {
        thread.start();
        await(started);
    }

    /** Lets the other thread's test finish and returns once it has. */
    void finish() {
        mayFinish.countDown();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
