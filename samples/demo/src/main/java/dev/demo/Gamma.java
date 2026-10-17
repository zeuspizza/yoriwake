package dev.demo;

/**
 * One of three independent units, so a change to one must not select the others' tests. It also
 * carries a void method, for an edit to a body that returns nothing, and an override.
 */
public class Gamma {
    private int offset;

    public void shift(int by) {
        offset += by;
    }

    // Package-private, so the default edit shapes, which read `public int name(`, draw nothing new
    // here: the default set's targets on this fixture stay what they were.
    int offset() {
        return offset;
    }

    public int twice(int n) {
        return n * 2;
    }

    public String label() {
        return "Gamma";
    }

    // Final, so the default edit shapes, which read `public String name(`, draw nothing new here.
    @Override
    public final String toString() {
        return "Gamma";
    }
}
