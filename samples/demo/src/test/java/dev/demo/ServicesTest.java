package dev.demo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Loads the providers a service file in main resources names. */
class ServicesTest {
    @Test
    void everyProviderTheServiceFileNamesIsLoaded() {
        List<String> loaded = ServiceLoader.load(Runnable.class).stream()
                .map(provider -> provider.type().getName())
                .sorted()
                .collect(Collectors.toList());
        assertEquals(List.of("dev.demo.FirstTask", "dev.demo.SecondTask"), loaded);
    }
}
