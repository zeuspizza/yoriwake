package dev.demoandroid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GammaTest {
    @Test
    void twice() {
        assertEquals(4, new Gamma().twice(2));
    }

    @Test
    void label() {
        assertEquals("Gamma", new Gamma().label());
    }
}
