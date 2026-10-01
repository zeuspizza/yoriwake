package io.github.zeuspizza.yoriwake.capturescript;

/** Fails the script when the JUnit Platform is on the classpath it was meant to run without. */
final class NoPlatform {

    private NoPlatform() {}

    static void check() {
        try {
            Class.forName("org.junit.platform.launcher.TestExecutionListener");
        } catch (ClassNotFoundException expected) {
            return;
        }
        throw new IllegalStateException("the JUnit Platform is on this JVM's classpath");
    }
}
