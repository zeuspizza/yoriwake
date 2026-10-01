package dev.orderdep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A method-level dependency inside one class: bbb passes only after aaa has run. */
class MethodPairTest {

    private static int stash;

    @Test
    void aaaSets() {
        stash = 42;
        assertEquals(42, stash);
    }

    @Test
    void bbbReads() {
        assertEquals(42, stash);
    }
}
