package dev.demo;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Depends on Delta without executing a line of it: loaded by name and read, never called. */
class DeltaShapeTest {
    @Test
    void exposesExactlyTheMethodsItsContractPromises() throws Exception {
        Class<?> delta = Class.forName("dev.demo.Delta");

        List<String> methods = Arrays.stream(delta.getDeclaredMethods())
                // JaCoCo adds a synthetic `$jacocoInit` to every class it instruments.
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .sorted()
                .collect(Collectors.toList());

        assertEquals(List.of("label", "twice"), methods);
    }
}
