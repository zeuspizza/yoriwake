package io.github.zeuspizza.yoriwake.agent.host;

/**
 * Gradle as the host build. The only place the agent names Gradle's internals, which no Gradle
 * release promises to keep.
 */
public final class GradleHost implements HostBuild {

    static final GradleHost INSTANCE = new GradleHost();

    /** Set by Gradle on each forked test worker; absent outside a Gradle test task. */
    static final String WORKER_ID_PROPERTY = "org.gradle.test.worker";

    /**
     * Gradle's JUnit 4 processors, before and after Gradle 9 renamed them.
     *
     * <p>JUnit 4 has no end-of-run event under Gradle. The processor's {@code stop()} runs on the
     * test thread after the last test, and a JVM that dies first never reaches it. A processor with
     * neither name is not rewritten, and its maps are never dated.
     */
    private static final String[] JUNIT4_PROCESSORS = {
        "org/gradle/api/internal/tasks/testing/junit/AbstractJUnitTestClassProcessor",
        "org/gradle/api/internal/tasks/testing/junit/AbstractJUnitTestDefinitionProcessor",
    };

    /**
     * Set by the rewritten processor's {@code stop()}, read at shutdown.
     *
     * <p>A property rather than a call into this agent: Gradle's processor classloader filters the
     * application classpath, so a call from there would throw {@code NoClassDefFoundError} into
     * Gradle's worker.
     */
    public static final String RUN_FINISHED_PROPERTY = "yoriwake.junit4.runFinished";

    private GradleHost() {}

    /** The process id when Gradle names no worker, so two such JVMs never share a directory. */
    @Override
    public String workerId() {
        String worker = System.getProperty(WORKER_ID_PROPERTY);
        return worker == null || worker.isEmpty() ? String.valueOf(ProcessHandle.current().pid()) : worker;
    }

    @Override
    public boolean runFinished() {
        return Boolean.getBoolean(RUN_FINISHED_PROPERTY);
    }

    /** Whether a class, by internal name, is one of Gradle's JUnit 4 processors. */
    public static boolean isJUnit4Processor(String internalName) {
        for (String processor : JUNIT4_PROCESSORS) {
            if (processor.equals(internalName)) {
                return true;
            }
        }
        return false;
    }
}
