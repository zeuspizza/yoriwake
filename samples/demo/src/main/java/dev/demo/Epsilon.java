package dev.demo;

/**
 * A class reachable only by name, from `src/test/resources/registry.yaml`. Nothing references or
 * loads it in bytecode, so only the resource scan protects `EpsilonRegistryTest`.
 */
public class Epsilon {
    public int twice(int n) {
        return n * 2;
    }

    public String label() {
        return "Epsilon";
    }
}
