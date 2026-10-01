package sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BetaTest {
    @Test
    void shouts() {
        assertEquals("HI!", new Beta().shout("hi"));
    }
}
