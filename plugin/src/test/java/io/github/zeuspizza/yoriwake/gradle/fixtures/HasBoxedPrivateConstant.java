package io.github.zeuspizza.yoriwake.gradle.fixtures;

/**
 * A private compile-time constant that reaches a field through a call, so the constant load is not
 * immediately before the field write:
 *
 * <ul>
 *   <li>{@code boxed}: autoboxing, {@code bipush 64; invokestatic Integer.valueOf; putfield}.
 *   <li>{@code limits}: a constructor argument, {@code sipush 128; invokespecial Limits.&lt;init&gt;}.
 *   <li>{@code conditional}: a conditional initialiser, {@code sipush; goto; label; putfield}.
 * </ul>
 *
 * <p>An analysis gap here must resolve to "escapes", never to "no escape", which means "skip".
 */
public class HasBoxedPrivateConstant {
    private static final int LIMIT = 64;
    private static final int DEFAULT_MAX = 128;
    private static final int FALLBACK = 7;

    /** Autoboxed: the value reaches the field through {@code Integer.valueOf}. */
    private final Integer boxed = LIMIT;

    /** A constructor argument: the value reaches a field of ANOTHER object. */
    private final Limits limits = new Limits(DEFAULT_MAX);

    private int conditional;

    public static final class Limits {
        private final int max;

        Limits(int max) {
            this.max = max;
        }

        public int max() {
            return max;
        }
    }

    public void configure(boolean useDefault) {
        this.conditional = useDefault ? FALLBACK : 0;
    }

    public Integer boxed() {
        return boxed;
    }

    public Limits limits() {
        return limits;
    }
}
