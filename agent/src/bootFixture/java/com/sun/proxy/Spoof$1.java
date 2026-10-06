package com.sun.proxy;

import java.util.Optional;
import java.util.function.Consumer;

/** Named like a generated proxy, on the boot class path, and not one. */
public final class Spoof$1 implements Consumer<Object> {

    private final Consumer<Object> sink;

    public Spoof$1(Consumer<Object> sink) {
        this.sink = sink;
    }

    @Override
    public void accept(Object value) {
        Optional.of(value).ifPresent(sink);
    }
}
