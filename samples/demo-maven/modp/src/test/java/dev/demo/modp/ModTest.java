package dev.demo.modp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModTest {
    @Test
    void one() {
        assertEquals(1, new Mod().one());
    }
}
