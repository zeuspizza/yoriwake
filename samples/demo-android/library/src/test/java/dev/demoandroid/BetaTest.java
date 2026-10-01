package dev.demoandroid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BetaTest {
    @Test
    void twice() {
        assertEquals(4, new Beta().twice(2));
    }

    @Test
    void label() {
        assertEquals("Beta", new Beta().label());
    }
}
