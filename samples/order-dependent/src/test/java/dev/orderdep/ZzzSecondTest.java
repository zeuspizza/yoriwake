package dev.orderdep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Passes only when AaaFirstTest has already run in this JVM. Reversed or alone it fails, unlike a
 * broken test, which fails in every order.
 */
class ZzzSecondTest {

    @Test
    void seesTheEarlierIncrement() {
        assertEquals(1, Counter.value());
    }
}
