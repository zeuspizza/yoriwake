package io.github.zeuspizza.yoriwake.capturescript;

import io.github.zeuspizza.yoriwake.agent.engines.JUnit4Events;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import org.gradle.api.internal.tasks.testing.junit.JUnitTestClassExecutor;
import org.junit.runner.Description;

/**
 * Runs in a JVM the agent is attached to through its premain, as a host's test JVM is: the agent
 * installs what the command line asks for before this runs.
 */
public final class PremainScript {

    /** What Gradle's rewritten JUnit 4 processor sets when its {@code stop()} runs. */
    private static final String RUN_FINISHED_PROPERTY = "yoriwake.junit4.runFinished";

    private PremainScript() {}

    public static void main(String[] args) throws Exception {
        NoPlatform.check();
        switch (args[0]) {
            case "idle":
                break;
            case "read":
                read(args[1]);
                break;
            case "execute":
                execute(Arrays.copyOfRange(args, 1, args.length));
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }

    /** One test, through the calls the rewritten {@code RunNotifier} makes, that reads a file. */
    private static void read(String file) throws IOException {
        Description test = Description.createTestDescription("com.acme.ReadTest", "reads");
        JUnit4Events.started(test);
        try (FileInputStream in = new FileInputStream(file)) {
            in.read();
        }
        JUnit4Events.finished(test);
        System.setProperty(RUN_FINISHED_PROPERTY, "true");
    }

    /** Hands each class to Gradle's executor, and says which ran. */
    private static void execute(String[] classes) throws ClassNotFoundException {
        JUnitTestClassExecutor executor = new JUnitTestClassExecutor();
        for (String name : classes) {
            if (executor.execute(name)) {
                System.out.println("ran " + name);
            }
        }
    }
}
