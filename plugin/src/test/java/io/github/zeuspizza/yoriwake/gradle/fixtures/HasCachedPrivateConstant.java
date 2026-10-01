package io.github.zeuspizza.yoriwake.gradle.fixtures;

/**
 * A private compile-time constant whose value escapes its class at runtime, inside a cached object:
 * a builder run once in a static initialiser, whose result later tests read without running it.
 * Privacy prevents compile-time copies only; here the value leaves as data.
 *
 * <p>The field write is a folded literal ({@code sipush 128; putfield}) with no {@code GETSTATIC}, so
 * a rule keyed on {@code GETSTATIC} would find nothing here.
 */
public class HasCachedPrivateConstant {
    private static final int DEFAULT_MAX = 128;

    private int max = DEFAULT_MAX;

    /** The cached object every later reader sees without running this class. */
    public static final class Limits {
        private final int max;

        Limits(int max) {
            this.max = max;
        }

        public int max() {
            return max;
        }
    }

    public HasCachedPrivateConstant withMax(int max) {
        this.max = max;
        return this;
    }

    public Limits build() {
        return new Limits(max);
    }
}
