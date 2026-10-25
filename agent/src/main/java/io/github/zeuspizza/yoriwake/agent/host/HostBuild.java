package io.github.zeuspizza.yoriwake.agent.host;

/**
 * What the build that forked this test JVM tells the agent. The only facts capture takes from its
 * host, so another build tool would need only another implementation.
 */
public interface HostBuild {

    /**
     * This JVM's name among its task's test workers: distinct per JVM, and the one name every
     * file this JVM writes carries, so its records and its loaded-class list pair up.
     */
    String workerId();

    /**
     * Whether the host said this JVM's run reached its end, for runners that give no end of run of
     * their own. False when it cannot tell, which leaves the map undated rather than wrongly dated.
     */
    boolean runFinished();

    /**
     * A contract property, as the host hands it to this JVM, or null when it does not. Every
     * contract read goes through here, so a host can keep a user's own property from answering.
     */
    String property(String name);

    /** The host this agent runs under. */
    static HostBuild current() {
        return HostChoice.current();
    }
}
