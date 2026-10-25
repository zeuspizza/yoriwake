package dev.demo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppTest {
    @Test
    void welcomes() {
        assertEquals("Hello, app", new App().welcome());
    }
}
