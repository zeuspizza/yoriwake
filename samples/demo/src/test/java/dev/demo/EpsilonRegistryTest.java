package dev.demo;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Validates a registry whose only reference to its class is a string. Hand-rolled parsing keeps the
 * fixture dependency-free.
 */
class EpsilonRegistryTest {
    @Test
    void everyRegisteredTypeExposesTheMethodsTheRegistryPromises() throws Exception {
        String registry = read("registry.yaml");
        String type = valueOf(registry, "type");
        List<String> promised = registry.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- ") && !line.startsWith("- type"))
                .map(line -> line.substring(2).trim())
                .sorted()
                .collect(Collectors.toList());

        assertNotNull(type, "the registry names no type");

        Class<?> registered = Class.forName(type);
        List<String> declared = Arrays.stream(registered.getDeclaredMethods())
                // JaCoCo adds a synthetic `$jacocoInit` to every class it instruments.
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .sorted()
                .collect(Collectors.toList());

        assertEquals(promised, declared);
    }

    private String read(String resource) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource + " is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String valueOf(String yaml, String key) {
        return yaml.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- " + key + ":") || line.startsWith(key + ":"))
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .findFirst()
                .orElse(null);
    }
}
