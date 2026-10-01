package io.github.zeuspizza.yoriwake.agent.capture;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** Where a {@link ProbeResult} goes once the probe has run. */
public interface ProbeReporter {

    void report(ProbeResult result);

    /**
     * Writes the probe result to stdout, and to a file when {@link #TARGET_PROPERTY} names one.
     *
     * <p>The file because a forked test worker's stdout is easy to lose. No default file: this runs
     * in every test JVM of a host build, which must not gain a stray file.
     */
    final class ToFile implements ProbeReporter {

        static final String TARGET_PROPERTY = "yoriwake.internal.capture.probeFile";

        private final File target;

        public ToFile() {
            this(configuredTarget());
        }

        private static File configuredTarget() {
            String configured = System.getProperty(TARGET_PROPERTY);
            return configured == null || configured.isEmpty() ? null : new File(configured);
        }

        public ToFile(File target) {
            this.target = target;
        }

        @Override
        public void report(ProbeResult result) {
            String line = result.render();
            System.out.println("[yoriwake] " + line);
            if (target == null) {
                return;
            }
            try {
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (FileWriter writer = new FileWriter(target, true)) {
                    writer.write(line + System.lineSeparator());
                }
            } catch (IOException | RuntimeException e) {
                // Reporting must never break the host build; stdout already carried it.
                System.out.println("[yoriwake] could not write " + target + ": " + e);
            }
        }
    }
}
