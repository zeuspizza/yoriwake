package dev.demo.app;

import dev.demo.lib.Greeter;

public class App {
    public String welcome() {
        return new Greeter().greet("app");
    }
}
