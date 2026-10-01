package io.github.zeuspizza.yoriwake.gradle.fixtures;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** A test class under a class-level annotation, as most JUnit 5 test classes are. */
@Tagged("slow")
public class AnnotatedTestClass {
    @Disabled("a fixture read as bytecode, not a test of anything")
    @Test
    void declared() {}
}
