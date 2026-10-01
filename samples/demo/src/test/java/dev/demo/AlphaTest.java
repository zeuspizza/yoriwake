package dev.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AlphaTest {
    @Test
    void doubles() {
        assertEquals(2, new Alpha().twice(1));
    }

    @Test
    void doublesAgain() {
        assertEquals(8, new Alpha().twice(4));
    }

    @Test
    void hasALabel() {
        assertEquals("Alpha", new Alpha().label());
    }
}
