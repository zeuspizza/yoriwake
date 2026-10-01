package io.github.zeuspizza.yoriwake.gradle.fixtures;

/**
 * A compile-time constant that cannot escape its own source file: javac copies a constant only into
 * code that can reference it, and no other top-level class can reference a private field.
 * {@code serialVersionUID} is this shape.
 */
public class HasPrivateConstant {
    private static final long SERIAL = 7L;

    /** Nested, and therefore able to see SERIAL -- but compiled from THIS source file. */
    static final class Nested {
        long serial() {
            return SERIAL;
        }
    }

    public long twice() {
        return SERIAL * 2;
    }
}
