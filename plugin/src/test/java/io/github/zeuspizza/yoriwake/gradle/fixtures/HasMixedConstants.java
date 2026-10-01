package io.github.zeuspizza.yoriwake.gradle.fixtures;

/**
 * One private constant and one that is not, which must still force. The digest covers the whole
 * class, so which constant changed cannot be told, and guessing would risk a skipped test.
 */
public class HasMixedConstants {
    private static final long SERIAL = 7L;
    public static final String NAME = "fixed";

    public long serial() {
        return SERIAL;
    }
}
