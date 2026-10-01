package io.github.zeuspizza.yoriwake.gradle.fixtures;

/**
 * A private compile-time constant whose value leaves only through a method return, which a caller
 * can cache. No field-write rule sees this; it pins a known limit of the plugin.
 */
public class HasReturnedPrivateConstant {
    private static final int LIMIT = 64;

    public int limit() {
        return LIMIT;
    }
}
