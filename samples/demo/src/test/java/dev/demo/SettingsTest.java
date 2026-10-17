package dev.demo;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Reads a value from a main resource, which no class of the fixture loads. */
class SettingsTest {
    @Test
    void theGreetingIsTheOneTheResourceDeclares() throws Exception {
        Properties settings = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("demo.properties")) {
            assertNotNull(stream, "demo.properties is missing");
            settings.load(stream);
        }
        assertEquals("hello", settings.getProperty("greeting"));
    }
}
