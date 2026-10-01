package io.github.zeuspizza.yoriwake.gradle.fixtures;

/** A static final field with no ConstantValue attribute: read from the holder at runtime. */
public class HasComputedField {
    public static final String NAME = String.valueOf(System.nanoTime());

    public int twice(int n) {
        return n * 2;
    }
}
