package io.github.zeuspizza.yoriwake.agent.capture;

import io.github.zeuspizza.yoriwake.agent.host.HostBuild;
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

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

    final Object lock = new Object();
    private final Set<String> seen = new HashSet<>();
    /** Reads and lookups seen since the plan started, so the first one after discovery is kept. */
    private final Set<String> seenNamed = new HashSet<>();
    /** Written under {@link #lock}; volatile so the environment sink reads it without the lock. */
    private volatile boolean planStarted;
    private List<String[]> pending = new ArrayList<>();
    private boolean installed;
    volatile String incomplete = "the yoriwake agent was not attached to this JVM";
    /** Why this JVM is newer than the agent reads; kept apart, so no later failure renames it. */
    private volatile String unsupported;
    private volatile String lookupsIncomplete = "the yoriwake agent was not attached to this JVM";
    /** Variables read by name, and {@link AgentContract#ENV_READ_WHOLE} for the whole environment. */
    private final Set<String> environmentReads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile String environmentIncomplete = "the yoriwake agent was not attached to this JVM";
    final ThreadLocal<Boolean> inside = new ThreadLocal<>();
    /**
     * The guards of conditional reviewed libraries; null, and none armed, until the agent installs
     * them. Not built eagerly: they need the agent's ASM, which a recorder made without the agent
     * jar does not have.
     */
    volatile GuardHooks guards;
    /** The module jars this JVM reaches, and the names it misses in them. */
    private final JarReaches jars = new JarReaches(this);
    /** The class, source and other files this JVM reads. */
    private final ResourceReads reads;
    /** Classes a stack trace element named so far, so a trace logged again costs no lock. */
    private final Set<String> stackSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Set when a stack trace's failure could not even be recorded; the next drain records it. */
    private volatile boolean stacksLost;
    /** The trace each thread last recorded, so the same trace read again is not walked again. */
    private final ThreadLocal<Object> lastTrace = new ThreadLocal<>();

    /** {@link java.nio.file.Files#isSameFile}, replaceable where no test can mount a directory. */
    interface SameFile {
        boolean test(Path a, Path b) throws IOException;
    }

    TouchRecorder() {
        reads = new ResourceReads(this, jars, java.nio.file.Files::isSameFile);
    }

    /** A recorder that records the non-class files read beneath {@code root}, as an attached one does. */
    TouchRecorder(String root) {
        this(root, java.nio.file.Files::isSameFile);
    }

    TouchRecorder(String root, SameFile sameFile) {
        reads = new ResourceReads(this, jars, sameFile);
        reads.roots = ResourceReads.rootsOf(root);
    }

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
        List<String> roots = ResourceReads.rootsOf(HostBuild.current().property(AgentContract.CAPTURE_ROOT_PROPERTY));
        reads.roots = roots;
        if (!roots.isEmpty()) {
            touch(AgentContract.TOUCH_RESOURCES_RECORDED, roots.get(0));
        }
        try {
            instrumentation.addTransformer(new LoadObserver(this));
            // Loads what the definition and native sinks need, without recording anything.
            calledFromJdk();
            JdkCode.isJdk(java.util.function.Function.identity().getClass());
            nameIn(new byte[0]);
            ZipContent.holdsAZip(new File(""));
            ReviewedLibraries.reviewed("", System.mapLibraryName("yoriwake"));
            guard(new Object[] {"", Boolean.FALSE});
            // JDK classes the agent's ASM cannot read take no hooks; said here, under its own name.
            unsupported = ClassFileSupport.testJvmUnsupported(
                    System.getProperty("java.specification.version"),
                    HostBuild.current().property(AgentContract.AGENT_ASM_CEILING_PROPERTY));
            if (unsupported != null) {
                lookupsIncomplete = unsupported;
                environmentIncomplete = unsupported;
                return;
            }
            Map<String, String> modules = modulesOf(HostBuild.current().property(AgentContract.JAR_MODULES_PROPERTY));
            // Bound to the sinks themselves, not this class's delegates: the stack walks those sinks
            // make count frames from the hook, so a frame between them would push the JDK's own out.
            ReadHooks.Installed hooks = ReadHooks.install(instrumentation, reads::read, this::lookup, this::defined,
                    this::childProcess, this::nativeCode, this::library, this::resolved, this::environmentRead,
                    jars::entry, jars::enumerated, this::stack);
            if (modules != null && hooks.entries == null) {
                recordJars(modules);
            }
            // A native agent reads frames through JVMTI, which no hook sees.
            String stacksUnseen = hooks.stacks != null ? hooks.stacks : nativeAgent();
            if (stacksUnseen == null) {
                touch(AgentContract.TOUCH_STACKS_RECORDED, "");
            } else {
                touch(AgentContract.TOUCH_STACK_ALL, stacksUnseen);
            }
            resolving = hooks.resolving;
            guards = GuardHooks.install(instrumentation, this::guard, this::guardLost);
            incomplete = hooks.reads != null ? hooks.reads : JdkCode.imageAltered();
            lookupsIncomplete = hooks.lookups != null ? hooks.lookups : nativeAgent();
            environmentIncomplete = hooks.environment;
        } catch (Throwable t) {
            incomplete = "could not observe class loading: " + t;
            lookupsIncomplete = incomplete;
            environmentIncomplete = incomplete;
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
            jars.drainMisses(pending);
            if (stacksLost) {
                pending.add(new String[] {AgentContract.TOUCH_STACK_ALL, "a stack trace could not be recorded"});
            }
            List<String[]> drained = pending;
            pending = new ArrayList<>();
            return drained;
        }
    }

    /**
     * The module jars named in the file at {@code path}, one {@code path \t identity} row each, under
     * the path as named and as resolved; null when there is no such file or it cannot be read.
     */
    static Map<String, String> modulesOf(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            Map<String, String> modules = new HashMap<>();
            for (String line : java.nio.file.Files.readAllLines(Paths.get(path), java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] row = io.github.zeuspizza.yoriwake.agent.contract.Tsv.split(line);
                if (row.length != 2) {
                    return null;
                }
                modules.put(row[0], row[0]);
                try {
                    modules.put(Paths.get(row[0]).toRealPath().toString(), row[0]);
                } catch (IOException | RuntimeException unresolved) {
                    // The path as named still matches a jar opened by it.
                }
            }
            return modules;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** {@link JarReaches#record}. */
    void recordJars(Map<String, String> modules) {
        jars.record(modules);
    }

    /** The entry hooks' sink: {@link JarReaches#entry}. */
    void entry(Object looked) {
        jars.entry(looked);
    }

    /** The enumeration hooks' sink: {@link JarReaches#enumerated}. */
    void enumerated(Object zip) {
        jars.enumerated(zip);
    }

    /** {@link JarReaches#definedFrom}. */
    void definedFrom(ProtectionDomain domain, String internalName, boolean byClassPath) {
        jars.definedFrom(domain, internalName, byClassPath);
    }

    @Override
    public String incomplete() {
        return unsupported != null ? unsupported : incomplete;
    }

    @Override
    public String lookupsIncomplete() {
        return lookupsIncomplete;
    }

    /**
     * The names of the environment variables this JVM's tests read, or null when its reads were not
     * all observed or no test plan started. Never a value.
     */
    @Override
    public Set<String> environmentReads() {
        return planStarted && environmentIncomplete == null ? new TreeSet<>(environmentReads) : null;
    }

    /**
     * The environment hooks' sink: a variable's name, or null for the whole environment. Any other
     * value is the warm-up, and records nothing. Only reads once the test plan started are the
     * tests': before it, the test worker reads its own.
     */
    void environmentRead(Object name) {
        // Hosts read the same few variables over and over: a name already kept costs a lookup, no lock.
        String key = name == null ? AgentContract.ENV_READ_WHOLE : name instanceof String ? (String) name : null;
        if (key == null || !planStarted || environmentReads.contains(key) || inside.get() != null) {
            return;
        }
        inside.set(Boolean.TRUE);
        try {
            environmentReads.add(key);
        } catch (Throwable t) {
            environmentIncomplete = "a read of the environment could not be recorded: " + t;
        } finally {
            inside.remove();
        }
    }

    @Override
    public void planStarted() {
        synchronized (lock) {
            planStarted = true;
        }
    }

    /**
     * Runs the agent's own work on this thread unobserved: a class it looks up or a file it reads
     * is not something a test depends on.
     */
    <T> T unobserved(Supplier<T> work) {
        if (inside.get() != null) {
            return work.get();
        }
        inside.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            inside.remove();
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

    /**
     * The stack hooks' sink: a stack trace element, an array of them, a map of threads to them, or
     * the name of the class an element is being built for. Anything else hands out a line number
     * with no element (a recording's frame), and from then on every class's lines count as seen.
     */
    void stack(Object value) {
        if (value == null || inside.get() != null) {
            return;
        }
        inside.set(Boolean.TRUE);
        try {
            if (value instanceof String) {
                stackClass((String) value);
            } else if (value instanceof StackTraceElement) {
                stackClass(((StackTraceElement) value).getClassName());
            } else if (value instanceof StackTraceElement[]) {
                stackClasses((StackTraceElement[]) value);
            } else if (value instanceof Map) {
                for (Object trace : ((Map<?, ?>) value).values()) {
                    if (trace instanceof StackTraceElement[]) {
                        stackClasses((StackTraceElement[]) trace);
                    }
                }
            } else {
                touch(AgentContract.TOUCH_STACK_ALL, "a line number was read from a " + value.getClass().getName());
            }
        } catch (Throwable t) {
            try {
                touch(AgentContract.TOUCH_STACK_ALL, "a stack trace could not be recorded: " + t);
            } catch (Throwable lost) {
                // Nothing may reach the JDK method: the next drain records it instead.
                stacksLost = true;
            }
        } finally {
            inside.remove();
        }
    }

    private void stackClasses(StackTraceElement[] trace) {
        // A throwable hands out the same trace each time it is printed or read again.
        if (lastTrace.get() == trace) {
            return;
        }
        lastTrace.set(trace);
        for (StackTraceElement element : trace) {
            if (element != null) {
                stackClass(element.getClassName());
            }
        }
    }

    /** One class named by a frame. A coroutine's element may carry its module, as {@code module/class}. */
    private void stackClass(String name) {
        if (name == null) {
            return;
        }
        String binary = name.indexOf('/') < 0 ? name : name.substring(name.lastIndexOf('/') + 1);
        if (!stackSeen.contains(binary) && stackSeen.add(binary)) {
            touch(AgentContract.TOUCH_STACK, binary);
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
                // Named, so a run that widens can be traced to the library that called.
                Class<?> from = callerOfHooked(new boolean[1]);
                touch(AgentContract.TOUCH_ALL, "native code was reached through the foreign-function API"
                        + (from != null ? ": from " + from.getName() : ""));
            }
        } catch (Throwable t) {
            incomplete = "native code could not be recorded: " + t;
        } finally {
            inside.remove();
        }
    }

    /**
     * Whether native loads arrive at {@link #resolved}, as the JDK opened them; until then, and on
     * a JVM where that hook could not be installed, every attempt at {@link #library} counts.
     */
    volatile boolean resolving;

    /** The library hooks' sink, at every attempt: {caller, file or library name}. */
    void library(Object loaded) {
        if (!resolving) {
            judgeLoad(loaded);
        }
    }

    /**
     * The resolution hooks' sink: {caller, the path the JDK resolved}, only for a library it loaded,
     * so a by-name attempt that found no file is not a load.
     */
    void resolved(Object loaded) {
        judgeLoad(loaded);
    }

    /**
     * Native code can read any class file out of sight, so a library loaded from outside the JDK
     * touches every class from here, unless it is a reviewed one loaded by its own class. That one
     * still reaches any class by name.
     */
    private void judgeLoad(Object loaded) {
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
            // load takes an absolute path; loadLibrary a name with no separator in it. '/' makes a
            // path everywhere, Windows' load taking it as readily as '\'; '\' only where it is the
            // separator, since elsewhere it is part of a file name and cutting there could make an
            // unreviewed file read as a reviewed one.
            int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf(File.separatorChar));
            String file = separator >= 0 ? name.substring(separator + 1) : System.mapLibraryName(name);
            String[] entry = caller != null ? ReviewedLibraries.entry(caller.getName(), file) : null;
            String unmet = entry != null ? unmet(caller, entry, name) : null;
            if (entry != null && unmet == null) {
                touchNamed(AgentContract.TOUCH_LOOKUP, AgentContract.FIRST_TOUCH_ANY);
            } else if (entry != null) {
                touch(AgentContract.TOUCH_ALL, "a reviewed native library was loaded without its condition: " + file
                        + ", since " + unmet);
            } else {
                touch(AgentContract.TOUCH_ALL, "a native library that is not on the reviewed list was loaded: " + file
                        + (caller != null ? " from " + caller.getName() : ""));
            }
        } catch (Throwable t) {
            incomplete = "a native library load could not be recorded: " + t;
        } finally {
            inside.remove();
        }
    }

    /** Null when a conditional entry's condition holds for this load of {@code loaded}, or why not; always null for any other. */
    private String unmet(Class<?> caller, String[] entry, String loaded) {
        if (entry.length == 2) {
            return null;
        }
        if (entry[2].startsWith("identity:")) {
            return JarIdentity.unmet(caller, loaded, entry[2].substring("identity:".length()), entry[3],
                    entry[4].split(","), System::getenv, name -> {
                        // Asked as the host's own code would: what its loader does here is recorded.
                        inside.remove();
                        try {
                            ClassLoader loader = caller.getClassLoader();
                            return loader == null ? ClassLoader.getSystemResources(name) : loader.getResources(name);
                        } catch (java.io.IOException unreadable) {
                            return null;
                        } finally {
                            inside.set(Boolean.TRUE);
                        }
                    });
        }
        GuardHooks installed = guards;
        if (installed == null) {
            return "the yoriwake agent did not install the native-method guards";
        }
        String[] guardClasses = entry[2].split(",");
        for (String guardClass : guardClasses) {
            String unarmed = installed.unarmed(caller, GuardHooks.wrapFor(guardClass));
            if (unarmed != null) {
                return unarmed;
            }
        }
        return GuardHooks.unlisted(caller, guardClasses[0], entry[3], entry[4].split(","));
    }

    /**
     * The guards' sink: {what turning it on means, whether this call turns it on}, and for an entry
     * check the operation it was given, which ends the reason. Turned on, the
     * reviewed library can reach native code nobody reviewed, so every class counts as touched from
     * here. Recorded even inside another sink: the call is the library's, not the agent's.
     */
    void guard(Object call) {
        try {
            Object[] pair = (Object[]) call;
            if (Boolean.TRUE.equals(pair[1])) {
                touch(AgentContract.TOUCH_ALL, pair.length > 2 ? pair[0] + String.valueOf(pair[2]) : (String) pair[0]);
            }
        } catch (Throwable t) {
            incomplete = "a guarded native call could not be recorded: " + t;
        }
    }

    /** A guard class defined without its wrap: none of its calls can be seen from here. */
    void guardLost(Object why) {
        try {
            touch(AgentContract.TOUCH_ALL, (String) why);
        } catch (Throwable t) {
            incomplete = "a guard class defined without its guard could not be recorded: " + t;
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
        boolean[] throughAProxy = {false};
        Class<?> caller = callerOfHooked(throughAProxy);
        return caller != null && !throughAProxy[0] && JdkCode.isJdk(caller);
    }

    /**
     * The class that called the hooked method, past the frames that call on another's behalf, or
     * null when none is found. {@code throughAProxy[0]} says whether a proxy stood between them.
     */
    static Class<?> callerOfHooked(boolean[] throughAProxy) {
        try {
            return StackWalker.getInstance(java.util.EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE,
                    StackWalker.Option.SHOW_HIDDEN_FRAMES)).walk(frames -> {
                Class<?> hooked = null;
                for (java.util.Iterator<StackWalker.StackFrame> it = frames.iterator(); it.hasNext(); ) {
                    Class<?> frame = it.next().getDeclaringClass();
                    if (hooked == null) {
                        if (!frame.getName().startsWith(AGENT_PACKAGE)) {
                            hooked = frame;
                        }
                    } else if (frame != hooked && !passesOver(frame)) {
                        return frame;
                    } else if (JdkCode.isProxy(frame)) {
                        throughAProxy[0] = true;
                    }
                }
                return null;
            });
        } catch (Throwable t) {
            return null;
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

    static final String AGENT_PACKAGE = "io.github.zeuspizza.yoriwake.";

    private static final String UNNAMED = "a class was defined from bytes whose name could not be read";

    /** The read hooks' sink: {@link ResourceReads#read}. */
    void read(Object opened) {
        reads.read(opened);
    }

    void touch(String kind, String value) {
        synchronized (lock) {
            if (seen.add(kind + '\t' + value)) {
                pending.add(new String[] {kind, value});
            }
        }
    }

    /** Kept from the plan's start on, whether or not discovery already saw the same one. */
    void touchNamed(String kind, String value) {
        synchronized (lock) {
            boolean fresh = seen.add(kind + '\t' + value);
            if (planStarted ? seenNamed.add(kind + '\t' + value) : fresh) {
                pending.add(new String[] {kind, value});
            }
        }
    }

    static final String MANIFEST = "META-INF/MANIFEST.MF";

    /**
     * Whether this manifest read is the JDK's application or platform class loader opening a jar
     * (the scan for {@code Class-Path} and {@code Multi-Release}) or defining a package from it.
     * Only the JDK's own frames may stand between the read and that point. A
     * {@code URLClassLoader} does not count: a subclass may override {@code definePackage} and be
     * handed every attribute. Anything that cannot tell says no, the side that counts the read.
     */
    static boolean loaderReadingManifest() {
        try {
            StackWalker walker = StackWalker.getInstance(java.util.EnumSet.of(
                    StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
            return walker.walk(frames -> {
                boolean pastAgent = false;
                boolean classPath = false;
                for (java.util.Iterator<StackWalker.StackFrame> it = frames.limit(48).iterator(); it.hasNext(); ) {
                    StackWalker.StackFrame step = it.next();
                    if (!pastAgent && step.getClassName().startsWith(AGENT_PACKAGE)) {
                        continue;
                    }
                    pastAgent = true;
                    if (!JdkCode.isJdk(step.getDeclaringClass())) {
                        return false;
                    }
                    String owner = step.getClassName();
                    if (owner.equals("java.net.URLClassLoader")) {
                        return false;
                    }
                    if (owner.equals("java.util.jar.JarFile") && step.getMethodName().equals("checkForSpecialAttributes")) {
                        return true;
                    }
                    // Reads the manifest for its Class-Path, but caches it in the jar for a later
                    // definePackage: it counts only on the way to the JDK's own loader, below.
                    if (owner.equals("jdk.internal.loader.URLClassPath$JarLoader")
                            && step.getMethodName().equals("getClassPath")) {
                        classPath = true;
                        continue;
                    }
                    if (owner.equals("jdk.internal.loader.BuiltinClassLoader")
                            && (classPath || step.getMethodName().equals("defineClass")
                                    || step.getMethodName().equals("defineOrCheckPackage"))) {
                        return true;
                    }
                }
                return false;
            });
        } catch (Throwable t) {
            return false;
        }
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
                    } else {
                        if (loader != application && loader != platform) {
                            recorder.definedElsewhere(name);
                        } else {
                            recorder.loaded(name);
                        }
                        recorder.definedFrom(domain, name, loader == application || loader == platform);
                    }
                }
            } catch (Throwable t) {
                recorder.incomplete = "a class load could not be recorded: " + t;
            }
            return null;
        }
    }
}
