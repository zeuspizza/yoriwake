package io.github.zeuspizza.yoriwake.agent.capture;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Child processes whose sources were reviewed and found to read no class file or jar and to run no
 * code of the host's. A child process's reads are out of the agent's sight, so every other child
 * touches every class from its start. {@code docs/reference.md} lists the review and the versions it
 * covered.
 *
 * <p>The one entry is Byte Buddy's attacher, which {@code ByteBuddyAgent} starts when the JDK does
 * not let a JVM attach to itself, as Mockito's inline mock maker has it do. The child only asks the
 * JDK to load a Java agent into this JVM, whose system class loader then loads that agent's classes
 * where the hooks see them. It matches on the class that started it, on Byte Buddy's version read
 * beside that class, and on the whole command; a JVM whose environment adds options to every
 * launcher the child inherits does not match.
 */
final class ReviewedChildren {

    private static final String CALLER = "net.bytebuddy.agent.ByteBuddyAgent";
    private static final String VERSION_FILE = "META-INF/maven/net.bytebuddy/byte-buddy-agent/pom.properties";
    /** Every release whose attacher command and {@code Attacher} were checked to be the ones matched here. */
    private static final String[] VERSIONS = {
        "1.14.0", "1.14.1", "1.14.2", "1.14.3", "1.14.4", "1.14.5", "1.14.6", "1.14.7", "1.14.8", "1.14.9",
        "1.14.10", "1.14.11", "1.14.12", "1.14.13", "1.14.14", "1.14.15", "1.14.16", "1.14.17", "1.14.18",
        "1.14.19", "1.15.0", "1.15.1", "1.15.2", "1.15.3", "1.15.4", "1.15.5", "1.15.7", "1.15.8", "1.15.9",
        "1.15.10", "1.15.11", "1.16.0", "1.16.1", "1.17.0", "1.17.1", "1.17.2", "1.17.3", "1.17.4", "1.17.5",
        "1.17.6", "1.17.7", "1.17.8", "1.18.0", "1.18.1", "1.18.2", "1.18.3", "1.18.4", "1.18.5", "1.18.7",
        "1.18.8", "1.18.9", "1.18.10", "1.18.11", "1.18.12", "1.18.13", "1.18.14", "1.18.15", "1.18.16",
        "1.18.17",
    };
    private static final List<String> ATTACH_TYPES =
        Arrays.asList("com.sun.tools.attach.VirtualMachine", "com.ibm.tools.attach.VirtualMachine");

    // Read when the agent starts, so a test that later changes a property cannot make another JDK's
    // launcher, another directory or another JVM look like this one's.
    private static final String JAVA = System.getProperty("java.home") + File.separatorChar + "bin"
        + File.separatorChar + (System.getProperty("os.name", "").toLowerCase(java.util.Locale.US)
            .contains("windows") ? "java.exe" : "java");
    private static final File TEMP = canonical(System.getProperty("java.io.tmpdir"));
    private static final String PID = Long.toString(ProcessHandle.current().pid());
    /** Variables every launcher reads, which could add an agent or a class path to the child. */
    private static final boolean LAUNCHER_OPTIONS = System.getenv("JAVA_TOOL_OPTIONS") != null
        || System.getenv("JDK_JAVA_OPTIONS") != null || System.getenv("_JAVA_OPTIONS") != null;

    private ReviewedChildren() {}

    /**
     * The first class below the hooked call that is neither the agent's nor the JDK's, the one that
     * asked for the child, or null when none is found.
     */
    static Class<?> caller() {
        try {
            return StackWalker.getInstance(java.util.EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE,
                    StackWalker.Option.SHOW_HIDDEN_FRAMES)).walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(frame -> !frame.getName().startsWith(TouchRecorder.AGENT_PACKAGE) && !JdkCode.isJdk(frame))
                .findFirst().orElse(null));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether {@code command}, a child's command line that {@code caller} started, is reviewed. */
    static boolean reviewed(Class<?> caller, Object command) {
        return reviewed(caller, command, LAUNCHER_OPTIONS);
    }

    /** As {@link #reviewed(Class, Object)}, with whether this JVM's environment sets launcher options. */
    static boolean reviewed(Class<?> caller, Object command, boolean launcherOptions) {
        try {
            if (launcherOptions) {
                return false;
            }
            if (caller == null || !(caller.getName().equals(CALLER) || caller.getName().startsWith(CALLER + "$"))) {
                return false;
            }
            if (!(command instanceof String[])) {
                return false;
            }
            String[] c = (String[]) command;
            // java -D<dump file> -cp <attacher> Attacher <attach API> <pid> <Java agent> <native> <argument>
            if (c.length != 10 || !JAVA.equals(c[0]) || !c[1].startsWith("-Dnet.bytebuddy.agent.attacher.dump=")
                    || !"-cp".equals(c[2]) || !"net.bytebuddy.agent.Attacher".equals(c[4])
                    || !ATTACH_TYPES.contains(c[5]) || !PID.equals(c[6]) || !(c[9].isEmpty() || c[9].startsWith("="))) {
                return false;
            }
            // A native agent would run native code in this JVM, out of the hooks' sight.
            if (!"false".equals(c[8])) {
                return false;
            }
            java.net.URL source = caller.getProtectionDomain().getCodeSource().getLocation();
            File home = canonical(new File(source.toURI()).getPath());
            if (!ownOrTemporary(c[3], home, "byteBuddyAttacher") || !ownOrTemporary(c[7], home, "byteBuddyAgent")) {
                return false;
            }
            return reviewedVersion(home, source);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether the version file at the root of {@code home}, the jar (or directory) {@code source}
     * names, is one reviewed. Read from the root, not beside the class: on Java 9 and later the jar's
     * {@code ByteBuddyAgent} is the copy under {@code META-INF/versions/9}.
     */
    private static boolean reviewedVersion(File home, java.net.URL source) throws Exception {
        java.net.URL file = home.isDirectory() ? new java.net.URL(source, VERSION_FILE)
            : new java.net.URL("jar:" + source + "!/" + VERSION_FILE);
        java.net.URLConnection connection = file.openConnection();
        // A cached jar would stay open, and a test that rewrites it would be read from the old copy.
        connection.setUseCaches(false);
        java.util.Properties properties = new java.util.Properties();
        try (java.io.InputStream in = connection.getInputStream()) {
            properties.load(in);
        }
        return Arrays.asList(VERSIONS).contains(properties.getProperty("version"));
    }

    /** Whether {@code path} is Byte Buddy's own jar, or a jar Byte Buddy wrote to the temporary directory. */
    private static boolean ownOrTemporary(String path, File home, String prefix) {
        if (path.isEmpty() || path.indexOf(File.pathSeparatorChar) >= 0) {
            return false;
        }
        File file = canonical(path);
        String name = file.getName();
        return file.equals(home)
            || TEMP != null && TEMP.equals(file.getParentFile()) && name.startsWith(prefix) && name.endsWith(".jar");
    }

    private static File canonical(String path) {
        try {
            return path == null ? null : new File(path).getCanonicalFile();
        } catch (java.io.IOException unresolved) {
            return null;
        }
    }
}
