package io.github.zeuspizza.yoriwake.gradle.fixtures;

/** Real logic under a class-retention annotation, which reflection cannot see and a processor can. */
@Tagged("wired")
public class HasInvisibleAnnotation {
    public static int twice(int n) {
        return n * 2;
    }
}
