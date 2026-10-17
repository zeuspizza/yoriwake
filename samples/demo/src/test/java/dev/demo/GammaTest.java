package dev.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GammaTest {
    @Test
    void doubles() {
        assertEquals(2, new Gamma().twice(1));
    }

    @Test
    void doublesAgain() {
        assertEquals(8, new Gamma().twice(4));
    }

    @Test
    void hasALabel() {
        assertEquals("Gamma", new Gamma().label());
    }

    @Test
    void shiftsItsOffset() {
        Gamma gamma = new Gamma();
        gamma.shift(3);
        assertEquals(3, gamma.offset());
    }

    @Test
    void printsItsLabel() {
        assertEquals("Gamma", new Gamma().toString());
    }

    @Test
    void hashesByIdentity() {
        Gamma gamma = new Gamma();
        assertEquals(System.identityHashCode(gamma), gamma.hashCode());
    }
}
