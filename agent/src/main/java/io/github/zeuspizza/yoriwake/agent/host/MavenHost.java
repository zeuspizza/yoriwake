package io.github.zeuspizza.yoriwake.agent.host;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Maven as the host build. Surefire lets a user property override what the plugin hands the test
 * JVM, and two contract names are also user flags, so every contract property comes from one run
 * file the plugin writes before the fork, never from system properties.
 */
final class MavenHost implements HostBuild {

    private final File runFile;
    private final String workerId;
    private volatile Map<String, String> answers;

    MavenHost(File runFile) {
        this.runFile = runFile;
        // Surefire's fork number repeats when forks are not reused, so it cannot name a JVM.
        ProcessHandle self = ProcessHandle.current();
        this.workerId = self.pid() + "-" + self.info().startInstant().map(i -> i.toEpochMilli()).orElse(0L);
    }

    @Override
    public String workerId() {
        return workerId;
    }

    /** False: Maven has no end-of-run hook for JUnit 4, and on the Platform the listener decides. */
    @Override
    public boolean runFinished() {
        return false;
    }

    @Override
    public String property(String name) {
        Map<String, String> read = answers;
        if (read == null) {
            read = read(runFile);
            answers = read;
        }
        return read.get(name);
    }

    /**
     * Every contract property the run file holds, or, when it cannot be read whole, a refusal that
     * runs every test and records the reason beside the map: no records directory, so no capture.
     */
    static Map<String, String> read(File runFile) {
        Map<String, String> answers = new HashMap<>();
        try {
            String text = new String(Files.readAllBytes(runFile.toPath()), StandardCharsets.UTF_8);
            if (!text.trim().endsWith(AgentContract.RUN_FILE_END)) {
                throw new IOException("it does not end with " + AgentContract.RUN_FILE_END);
            }
            Properties file = new Properties();
            file.load(new StringReader(text));
            for (String name : file.stringPropertyNames()) {
                answers.put(name, file.getProperty(name));
            }
            return answers;
        } catch (IOException | RuntimeException e) {
            answers.clear();
            answers.put(AgentContract.REFUSED_PROPERTY, "the run file " + runFile + " could not be read whole (" + e + ")");
            answers.put(AgentContract.REFUSED_KIND_PROPERTY, AgentContract.CHANGE_SET_UNREADABLE_KIND);
            // With no file named there is no map to write the refusal beside.
            File parent = runFile.getPath().isEmpty() ? null : runFile.getAbsoluteFile().getParentFile();
            if (parent != null) {
                answers.put(AgentContract.MAP_DIR_PROPERTY, parent.getPath());
            }
            return answers;
        }
    }
}
