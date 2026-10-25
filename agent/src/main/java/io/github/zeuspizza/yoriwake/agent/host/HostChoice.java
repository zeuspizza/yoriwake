package io.github.zeuspizza.yoriwake.agent.host;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.io.File;

/**
 * Which host this JVM runs under, decided once. The agent's premain decides it from the options
 * the host put on the command line, before a test runner can apply properties of its own.
 */
public final class HostChoice {

    private static volatile HostBuild chosen;

    private HostChoice() {}

    /** Decides the host now, if nothing decided it yet. Called first in premain. */
    public static void decide() {
        current();
    }

    static HostBuild current() {
        HostBuild host = chosen;
        if (host == null) {
            synchronized (HostChoice.class) {
                host = chosen;
                if (host == null) {
                    host = of(System.getProperty(AgentContract.HOST_PROPERTY), System.getProperty(AgentContract.RUN_FILE_PROPERTY));
                    chosen = host;
                }
            }
        }
        return host;
    }

    /** Gradle unless the host names Maven: an absent or unknown name leaves Gradle's path as it is. */
    static HostBuild of(String host, String runFile) {
        if (AgentContract.HOST_MAVEN.equals(host)) {
            return new MavenHost(new File(runFile == null ? "" : runFile));
        }
        return GradleHost.INSTANCE;
    }
}
