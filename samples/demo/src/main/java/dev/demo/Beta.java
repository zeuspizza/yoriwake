package dev.demo;

/** One of three independent units, so a change to one must not select the others' tests. */
public class Beta {
    public int twice(int n) {
        return n * 2;
    }

    public String label() {
        return "Beta";
    }

    // Final, so the default edit shapes, which read `public int name(`, draw nothing new here: the
    // default set's targets on this fixture stay what they were.
    @Override
    public final int hashCode() {
        return 7;
    }
}
