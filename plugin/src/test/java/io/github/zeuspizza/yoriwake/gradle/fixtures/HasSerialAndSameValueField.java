package io.github.zeuspizza.yoriwake.gradle.fixtures;

import java.io.Serializable;

/**
 * {@code serialVersionUID = 1L} beside an unrelated field written with the same literal {@code 1L}.
 * The serial must never force: it is read reflectively and never copied. A rule matching a
 * constant's value against a field write's operand cannot tell the two apart, so the serial is
 * exempted by name.
 */
public class HasSerialAndSameValueField implements Serializable {
    private static final long serialVersionUID = 1L;

    private long counter = 1L;

    public long bump() {
        counter += 1L;
        return counter;
    }
}
