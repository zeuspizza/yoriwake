package dev.demo.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CounterTest {
    @Test
    void doubles() {
        assertEquals(4, new Counter().twice(2));
    }
}
