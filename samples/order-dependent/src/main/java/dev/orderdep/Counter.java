package dev.orderdep;

/** Shared mutable static state: the conventional shape of an order-dependent test pair. */
public final class Counter {

    private static int count;

    private Counter() {
    }

    public static int increment() {
        return ++count;
    }

    public static int value() {
        return count;
    }
}
