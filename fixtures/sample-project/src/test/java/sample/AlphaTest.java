package sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlphaTest {
    @Test
    void triples() {
        assertEquals(9, new Alpha().triple(3));
    }
}
