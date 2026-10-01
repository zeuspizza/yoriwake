package dev.demo;

/** One of three independent units, so a change to one must not select the others' tests. */
public class Alpha {
    public int twice(int n) {
        return n * 2;
    }

    public String label() {
        return "Alpha";
    }
}
