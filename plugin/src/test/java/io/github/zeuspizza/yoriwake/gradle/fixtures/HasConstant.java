package io.github.zeuspizza.yoriwake.gradle.fixtures;

/** Carries a compile-time constant, which is copied into consumers whose sources do not change. */
public class HasConstant {
    public static final String NAME = "fixed";

    public int twice(int n) {
        return n * 2;
    }
}
