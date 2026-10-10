package io.github.zeuspizza.yoriwake.agent.capture;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The first time this JVM loaded each class, or read each class or source file.
 *
 * <p>Code a JVM runs once, and metadata it reads once, is credited to whichever test triggers it
 * first; a later test can depend on it and record nothing. Nothing about a class can reach a test
 * before the class is loaded or its file read, so the moment of that first touch bounds which
 * tests can depend on it. Coverage says when code first ran; this says when a class first arrived.
 *
 * <p>Fail-open: nothing here may throw into the host. Anything not observed is reported by
 * {@link #incomplete()}, which the record turns into "every class touched", never "none".
 */
public final class TouchRecorder implements CaptureSession.Touches {

    /** The one recorder the agent's premain feeds. */
    public static final TouchRecorder JVM = new TouchRecorder();

    private final Object lock = new Object();
    private final Set<String> seen = new HashSet<>();
    /** Reads and lookups seen since the plan started, so the first one after discovery is kept. */
    private final Set<String> seenNamed = new HashSet<>();
    private boolean planStarted;
    private List<String[]> pending = new ArrayList<>();
    private boolean installed;
    private volatile String incomplete = "the yoriwake agent was not attached to this JVM";
    private volatile String lookupsIncomplete = "the yoriwake agent was not attached to this JVM";
    private final ThreadLocal<Boolean> inside = new ThreadLocal<>();

    TouchRecorder() {}

    /** Starts observing. Called once from premain, and only when this JVM captures. */
    public static void install(Instrumentation instrumentation) {
        JVM.start(instrumentation);
    }

    private void start(Instrumentation instrumentation) {
        synchronized (lock) {
            if (installed) {
                return;
            }
            installed = true;
        }
        try {
            instrumentation.addTransformer(new LoadObserver(this));
            // Loads what the definition and native sinks need, without recording anything.
            calledFromJdk();
            JdkCode.isJdk(java.util.function.Function.identity().getClass());
            nameIn(new byte[0]);
            ReviewedLibraries.reviewed("", System.mapLibraryName("yoriwake"));
            ReadHooks.Installed hooks = ReadHooks.install(instrumentation, this::read, this::lookup, this::defined,
                    this::childProcess, this::nativeCode, this::library);
            incomplete = hooks.reads != null ? hooks.reads : JdkCode.imageAltered();
            lookupsIncomplete = hooks.lookups != null ? hooks.lookups : nativeAgent();
        } catch (Throwable t) {
            incomplete = "could not observe class loading: " + t;
            lookupsIncomplete = incomplete;
        }
    }

    /** A JVMTI agent reaches classes with no Java path at all, so no lookup hook can see it. */
    private static String nativeAgent() {
        try {
            for (String argument : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (argument.startsWith("-agentlib:") || argument.startsWith("-agentpath:")) {
                    return "a native agent is attached: " + argument;
                }
            }
            return null;
        } catch (Throwable t) {
            return "the JVM's own arguments could not be read: " + t;
        }
    }

    @Override
    public List<String[]> drain() {
        synchronized (lock) {
            List<String[]> drained = pending;
            pending = new ArrayList<>();
            return drained;
        }
    }

    @Override
    public String incomplete() {
        return incomplete;
    }

    @Override
    public String lookupsIncomplete() {
        return lookupsIncomplete;
    }

    @Override
    public void planStarted() {
        synchronized (lock) {
            planStarted = true;
        }
    }

    /**
     * The lookup hooks' sink: a class name, or for a call that can reach any class (the loaded set
     * handed out by {@code Instrumentation}) something else.
     */
    void lookup(Object named) {
        if (inside.get() != null) {
            return;
        }
        inside.set(Boolean.TRUE);
        try {
            if (named instanceof String) {
                touchNamed(AgentContract.TOUCH_LOOKUP, (String) named);
            } else {
                touchNamed(AgentContract.TOUCH_LOOKUP, AgentContract.FIRST_TOUCH_ANY);
            }
        } catch (Throwable t) {
            lookupsIncomplete = "a lookup could not be recorded: " + t;
        } finally {
            inside.remove();
        }
    }

    void loaded(String internalName) {
        touch(AgentContract.TOUCH_LOADED, internalName.replace('/', '.'));
    }

    /**
     * A class defined by a class loader other than the JDK's application and platform loaders:
     * possibly a second copy of a class this JVM already has, which reaches that class as surely as
     * a lookup of its name.
     */
    void definedElsewhere(String internalName) {
        touchNamed(AgentContract.TOUCH_DEFINED, internalName.replace('/', '.'));
    }

    /**
     * The definition hooks' sink: the bytes of a hidden or anonymous class, which no transformer
     * is shown. Bytes whose name cannot be read reach a class nobody can name, so every class.
     */
    void defined(Object bytes) {
        if (inside.get() != null) {
            return;
        }
        inside.set(Boolean.TRUE);
        try {
            if (calledFromJdk()) {
                // The JDK's own bootstraps generate these; they copy no class.
                return;
            }
            String name = nameIn(bytes);
            if (name == null) {
                touch(AgentContract.TOUCH_ALL, UNNAMED);
            } else {
                JdkCode.projectDefined(name.replace('/', '.'));
                touchNamed(AgentContract.TOUCH_DEFINED, name.replace('/', '.'));
            }
        } catch (Throwable t) {
            incomplete = "a class definition could not be recorded: " + t;
        } finally {
            inside.remove();
        }
    }

    /**
     * The child-process hook's sink. What a child does (read any class file, run any class) is
     * out of sight, so from here on every class counts as touched. The command is not kept.
     */
    void childProcess(Object ignored) {
        try {
            touch(AgentContract.TOUCH_ALL, "a child process was started");
        } catch (Throwable t) {
            incomplete = "a child process could not be recorded: " + t;
        }
    }

    /**
     * The foreign-function hooks' sink: native code reached without a library load, which can read
     * any class file out of sight, so every class counts as touched from here. The JDK's own use of
     * the API is not the host's.
     */
    void nativeCode(Object ignored) {
        if (inside.get() != null) {
            return;
        }
        inside.set(Boolean.TRUE);
        try {
            if (!calledFromJdk()) {
                touch(AgentContract.TOUCH_ALL, "native code was reached through the foreign-function API");
            }
        } catch (Throwable t) {
            incomplete = "native code could not be recorded: " + t;
        } finally {
            inside.remove();
        }
    }

    /**
     * The library hooks' sink: {caller, file or library name}. Native code can read any class file
     * out of sight, so a library loaded from outside the JDK touches every class from here, unless
     * it is a reviewed one loaded by its own class. That one still reaches any class by name.
     */
    void library(Object loaded) {
        if (inside.get() != null) {
            return;
        }
        inside.set(Boolean.TRUE);
        try {
            Object[] pair = (Object[]) loaded;
            Class<?> caller = (Class<?>) pair[0];
            if (caller != null && JdkCode.isJdk(caller)) {
                // A library the JDK loads for itself.
                return;
            }
            String name = (String) pair[1];
            // load takes an absolute path; loadLibrary a name with no separator in it.
            String file = name.indexOf(File.separatorChar) >= 0 ? new File(name).getName()
                    : System.mapLibraryName(name);
            if (caller != null && ReviewedLibraries.reviewed(caller.getName(), file)) {
                touchNamed(AgentContract.TOUCH_LOOKUP, AgentContract.FIRST_TOUCH_ANY);
            } else {
                touch(AgentContract.TOUCH_ALL, "a native library that is not on the reviewed list was loaded: " + file);
            }
        } catch (Throwable t) {
            incomplete = "a native library load could not be recorded: " + t;
        } finally {
            inside.remove();
        }
    }

    /** The class name a class file declares, or null when it cannot be read. */
    static String nameIn(Object bytes) {
        try {
            return new org.objectweb.asm.ClassReader((byte[]) bytes).getClassName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether the hooked method's immediate caller is the JDK's own code: the first frame past this
     * agent's frames is the hooked method, and the first frame after it of another class is its
     * caller. Anything that cannot tell says no, the side that records.
     *
     * Hidden frames are shown, so a method reference handed to JDK code counts as code of the class
     * that wrote it; reflection and method-handle frames are passed over, because they make a call
     * on someone else's behalf. The JDK's own lambda bootstraps then count as the JDK's: past the
     * metafactory's frames stands the JDK class whose lambda it is.
     *
     * A call that passes over a proxy is never the JDK's: a proxy dispatches to a handle or handler
     * its maker chose, so whoever called the proxy, JDK code included, did not ask for the hooked call.
     */
    static boolean calledFromJdk() {
        try {
            return StackWalker.getInstance(java.util.EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE,
                    StackWalker.Option.SHOW_HIDDEN_FRAMES)).walk(frames -> {
                Class<?> hooked = null;
                boolean throughAProxy = false;
                for (java.util.Iterator<StackWalker.StackFrame> it = frames.iterator(); it.hasNext(); ) {
                    Class<?> frame = it.next().getDeclaringClass();
                    if (hooked == null) {
                        if (!frame.getName().startsWith(AGENT_PACKAGE)) {
                            hooked = frame;
                        }
                    } else if (frame != hooked && !passesOver(frame)) {
                        if (throughAProxy) {
                            return false;
                        }
                        return JdkCode.isJdk(frame);
                    } else if (JdkCode.isProxy(frame)) {
                        throughAProxy = true;
                    }
                }
                return false;
            });
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A frame that calls on another's behalf, by its name and by being what its name says: a class
     * that only carries such a name is a caller like any other.
     */
    static boolean passesOver(Class<?> frame) {
        return invokesForAnother(frame.getName()) && JdkCode.callsForAnother(frame);
    }

    /**
     * Reflection and method-handle machinery: it invokes what its caller asked for, so it is never
     * the caller. All of {@code java.lang.invoke} counts, not a list of its classes: varargs
     * collectors, constant bootstraps, interface proxies and interpreted lambda forms all call a
     * handle on someone else's behalf, and a proxy class only dispatches to its handler.
     */
    static boolean invokesForAnother(String name) {
        return name.startsWith("java.lang.invoke.")
                || name.startsWith("jdk.internal.reflect.")
                || name.startsWith("sun.reflect.")
                || name.startsWith("jdk.MHProxy")
                || name.startsWith("jdk.proxy") || name.startsWith("com.sun.proxy.")
                || name.equals("java.lang.reflect.Method")
                || name.equals("java.lang.reflect.Constructor");
    }

    private static final String AGENT_PACKAGE = "io.github.zeuspizza.yoriwake.";

    private static final String UNNAMED = "a class was defined from bytes whose name could not be read";

    /** The read hooks' sink: a {@link File}, a {@link Path}, or a {@code {ZipFile, ZipEntry}} pair. */
    void read(Object opened) {
        if (inside.get() != null) {
            return;
        }
        inside.set(Boolean.TRUE);
        try {
            if (opened instanceof Object[]) {
                Object[] pair = (Object[]) opened;
                String entry = ((ZipEntry) pair[1]).getName();
                if (isClassOrSource(entry) && !definingAClass()) {
                    touchNamed(AgentContract.TOUCH_READ, entry);
                }
                return;
            }
            String path = opened instanceof File ? ((File) opened).getPath() : String.valueOf(opened);
            if (isClassOrSource(path) && !definingAClass()) {
                touchNamed(AgentContract.TOUCH_READ, path);
            } else if (path.endsWith(".jar") && !openedByJdkZip()) {
                // A scanner reading a jar with its own zip code: every class in it may be read.
                touchNamed(AgentContract.TOUCH_JAR, new File(path).getAbsolutePath());
            }
        } catch (Throwable t) {
            incomplete = "a read could not be recorded: " + t;
        } finally {
            inside.remove();
        }
    }

    private void touch(String kind, String value) {
        synchronized (lock) {
            if (seen.add(kind + '\t' + value)) {
                pending.add(new String[] {kind, value});
            }
        }
    }

    /** Kept from the plan's start on, whether or not discovery already saw the same one. */
    private void touchNamed(String kind, String value) {
        synchronized (lock) {
            boolean fresh = seen.add(kind + '\t' + value);
            if (planStarted ? seenNamed.add(kind + '\t' + value) : fresh) {
                pending.add(new String[] {kind, value});
            }
        }
    }

    /**
     * Whether this read is the JDK defining a class from its file, which the load observer already
     * records as a load. Only the JDK's own loaders count: anything else reading a class file is a
     * reader, and misreading a load as a read only opens a window earlier.
     *
     * Only the JDK's own frames may stand between the read and the definition: a definition calls
     * code it does not own (a loader's {@code getPermissions}, a transformer, a URL handler), and a
     * class file that code reads is its read. Hidden frames are shown, so a hidden class of the
     * project's cannot leave only the JDK's frames in view. Anything that cannot tell says no, the
     * side that records.
     */
    private static boolean definingAClass() {
        try {
            StackWalker walker = StackWalker.getInstance(java.util.EnumSet.of(
                    StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
            return walker.walk(frames -> {
                boolean pastAgent = false;
                for (java.util.Iterator<StackWalker.StackFrame> it = frames.limit(24).iterator(); it.hasNext(); ) {
                    StackWalker.StackFrame frame = it.next();
                    if (!pastAgent && frame.getClassName().startsWith(AGENT_PACKAGE)) {
                        continue;
                    }
                    pastAgent = true;
                    if (!JdkCode.isJdk(frame.getDeclaringClass())) {
                        return false;
                    }
                    if ("defineClass".equals(frame.getMethodName())
                            && (frame.getClassName().startsWith("jdk.internal.loader.")
                                    || frame.getClassName().equals("java.net.URLClassLoader")
                                    || frame.getClassName().equals("java.security.SecureClassLoader")
                                    || frame.getClassName().equals("java.lang.ClassLoader"))) {
                        return true;
                    }
                }
                return false;
            });
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isClassOrSource(String path) {
        return path.endsWith(".class") || path.endsWith(".java") || path.endsWith(".kt")
                || path.endsWith(".groovy") || path.endsWith(".scala");
    }

    /** The JDK's own zip code opens every jar it reads entries from; those entries are hooked. */
    private static boolean openedByJdkZip() {
        return StackWalker.getInstance().walk(frames -> frames.limit(12)
                .anyMatch(frame -> frame.getClassName().startsWith("java.util.zip.ZipFile")));
    }

    /**
     * Sees every class definition, including classes defined from bytes no file holds (a proxy, a
     * generated subclass), which the read hooks cannot see. Returns null always: it observes and
     * never changes a class.
     */
    static final class LoadObserver implements ClassFileTransformer {
        private final TouchRecorder recorder;
        /** The loaders every class path and module path class comes from; null when unknown. */
        private final ClassLoader application;
        private final ClassLoader platform;

        LoadObserver(TouchRecorder recorder) {
            this.recorder = recorder;
            ClassLoader app = null;
            ClassLoader plat = null;
            try {
                app = ClassLoader.getSystemClassLoader();
                plat = ClassLoader.getPlatformClassLoader();
            } catch (Throwable t) {
                // Unknown: every definition then counts as one by another loader, which only dates more.
            }
            this.application = app;
            this.platform = plat;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                ProtectionDomain domain, byte[] bytes) {
            try {
                if (redefined == null && loader != null) {
                    // A class defined with no name given is named by its bytes.
                    String name = className != null ? className : nameIn(bytes);
                    if (name == null) {
                        recorder.touch(AgentContract.TOUCH_ALL, UNNAMED);
                    } else if (loader != application && loader != platform) {
                        recorder.definedElsewhere(name);
                    } else {
                        recorder.loaded(name);
                    }
                }
            } catch (Throwable t) {
                recorder.incomplete = "a class load could not be recorded: " + t;
            }
            return null;
        }
    }
}
