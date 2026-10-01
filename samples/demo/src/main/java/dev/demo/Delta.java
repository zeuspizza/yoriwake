package dev.demo;

/**
 * A class nothing executes and one test inspects. No probe fires here, yet `DeltaShapeTest` reads
 * its members by reflection, so a change to its shape must still select that test. The loaded-class
 * record protects it: reflection loads the class without running it.
 */
public class Delta {
    public int twice(int n) {
        return n * 2;
    }

    public String label() {
        return "Delta";
    }
}
