package io.github.zeuspizza.yoriwake.gradle.fixtures;

/** Real logic under a runtime-visible annotation, the one annotation every JDK ships. */
@Deprecated
public class HasVisibleAnnotation {
    public static int twice(int n) {
        return n * 2;
    }
}
