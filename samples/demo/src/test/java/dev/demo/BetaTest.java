package dev.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BetaTest {
    @Test
    void doubles() {
        assertEquals(2, new Beta().twice(1));
    }

    @Test
    void doublesAgain() {
        assertEquals(8, new Beta().twice(4));
    }

    @Test
    void hasALabel() {
        assertEquals("Beta", new Beta().label());
    }
}
