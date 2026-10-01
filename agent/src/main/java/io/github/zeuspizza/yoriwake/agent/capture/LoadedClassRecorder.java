package io.github.zeuspizza.yoriwake.agent.capture;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.engines.JUnit4Hook;
import io.github.zeuspizza.yoriwake.agent.host.HostBuild;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records every class the test JVM loaded, which coverage cannot report.
 *
 * <p>Coverage omits classes that were loaded but never executed and types with no probes, so absence
 * from coverage proves nothing and forces a full run. Absence from the loaded set is real evidence:
 * no test in the task can depend on a class the task never loaded.
 *
 * <p>Not per test: a class loads once per JVM, so only the first test to touch it would be credited.
 */
public final class LoadedClassRecorder {

    private LoadedClassRecorder() {}

    /**
     * Attached with {@code -javaagent}, purely to obtain {@link Instrumentation}.
     *
     * <p>Everything here is wrapped: a premain that throws stops the JVM from starting, while
     * failing to record only leaves the selector conservative.
     */
    public static void premain(String argument, Instrumentation instrumentation) {
        try {
            // Only a capturing JVM: a selecting one writes no records for touches to date.
            String records = System.getProperty(AgentContract.RECORDS_DIR_PROPERTY);
            if (records != null && !records.isEmpty()) {
                TouchRecorder.install(instrumentation);
            }
            // Only where no Platform listener runs. The vintage engine also drives RunNotifier, so
            // an unconditional install would race two capture paths on one dump-and-reset and
            // produce records that cover nothing, which lets a covering test be skipped.
            if (Boolean.getBoolean(AgentContract.JUNIT4_HOOK_PROPERTY)) {
                JUnit4Hook.install(instrumentation);
            }

            String output = System.getProperty(AgentContract.LOADED_DIR_PROPERTY);
            if (output == null || output.isEmpty()) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> write(instrumentation, output)));
        } catch (Throwable ignored) {
            // A recorder that cannot start must not stop the JVM that hosts it.
        }
    }

    /**
     * Every class the JVM has loaded, unfiltered.
     *
     * <p>Not filtered to a package scope: outside a filter, absence would only mean nobody was
     * looking, and could not be treated as proof.
     */
    private static void write(Instrumentation instrumentation, String output) {
        try {
            List<String> names = new ArrayList<>();
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                names.add(loaded.getName());
            }
            Collections.sort(names);

            File directory = new File(output);
            directory.mkdirs();
            writeLines(new File(directory, AgentContract.LOADED_WORKER_PREFIX + HostBuild.current().workerId() + AgentContract.LOADED_WORKER_SUFFIX), names);
        } catch (Throwable ignored) {
            // Absence of this file leaves the selector conservative, not wrong.
        }
    }

    private static void writeLines(File target, List<String> lines) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary.toPath(), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
        if (!temporary.renameTo(target)) {
            target.delete();
            if (!temporary.renameTo(target)) {
                temporary.delete();
                throw new IOException("could not write " + target);
            }
        }
    }

    /**
     * Marks this worker's run as one that reached its end.
     *
     * <p>Shared by every capture path: a union or a map from a run that was killed part-way is not
     * evidence, and the decoder counts this marker to tell the two apart.
     */
    static void markComplete() {
        String output = System.getProperty(AgentContract.LOADED_DIR_PROPERTY);
        if (output == null || output.isEmpty()) {
            return;
        }
        try {
            File directory = new File(output);
            directory.mkdirs();
            new File(directory, AgentContract.LOADED_WORKER_PREFIX + HostBuild.current().workerId() + AgentContract.COMPLETE_SUFFIX).createNewFile();
        } catch (Throwable ignored) {
            // A missing marker makes the run untrusted, which is the safe direction.
        }
    }
}
