package io.github.zeuspizza.yoriwake.agent.platform;

/**
 * Why this JVM's tests may run concurrently, as its test plan's configuration showed it, or null
 * when they run one at a time or nobody looked.
 *
 * <p>Written by the attached copy of {@code PlatformEvents}, read by each decision record at exit.
 */
public final class Concurrency {

    private Concurrency() {}

    private static volatile String unconfirmed;

    public static void unconfirmed(String reason) {
        unconfirmed = reason;
    }

    public static String unconfirmed() {
        return unconfirmed;
    }
}
