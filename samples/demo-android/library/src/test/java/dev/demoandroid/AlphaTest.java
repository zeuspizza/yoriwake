package dev.demoandroid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlphaTest {
    @Test
    void twice() {
        assertEquals(4, new Alpha().twice(2));
    }

    @Test
    void label() {
        assertEquals("Alpha", new Alpha().label());
    }
}
