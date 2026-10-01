package io.github.zeuspizza.yoriwake.gradle.fixtures;

/** An annotation type: read reflectively, never executed. */
public @interface Tagged {
    String value() default "";
}
