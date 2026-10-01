package dev.orderdep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Leaves the counter at 1 and passes in any order: the polluter, not the victim. */
class AaaFirstTest {

    @Test
    void incrementsOnce() {
        assertEquals(1, Counter.increment());
    }
}
