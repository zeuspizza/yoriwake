package io.github.zeuspizza.yoriwake.agent.capture;

import io.github.zeuspizza.yoriwake.agent.host.HostBuild;
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    /** Archives found to hold no class, source or archive, by path, with the length and time they had. */
    private final Map<String, String> classless = new HashMap<>();
    /** Written under {@link #lock}; volatile so the environment sink reads it without the lock. */
    private volatile boolean planStarted;
    private List<String[]> pending = new ArrayList<>();
    private boolean installed;
    private volatile String incomplete = "the yoriwake agent was not attached to this JVM";
    /** Why this JVM is newer than the agent reads; kept apart, so no later failure renames it. */
    private volatile String unsupported;
    private volatile String lookupsIncomplete = "the yoriwake agent was not attached to this JVM";
    /** Variables read by name, and {@link AgentContract#ENV_READ_WHOLE} for the whole environment. */
    private final Set<String> environmentReads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile String environmentIncomplete = "the yoriwake agent was not attached to this JVM";
    private final ThreadLocal<Boolean> inside = new ThreadLocal<>();
    /**
     * The guards of conditional reviewed libraries; null, and none armed, until the agent installs
     * them. Not built eagerly: they need the agent's ASM, which a recorder made without the agent
     * jar does not have.
     */
    volatile GuardHooks guards;
    /**
     * The root project's directory, as given and as resolved, each ending in a separator; empty
     * when it was not given, and then no non-class file is recorded.
     */
    private volatile List<String> roots = new ArrayList<>();
    /** The paths each path read so far is recorded under, so each is resolved at most once. */
    private final Map<String, List<String>> rootChecked = new java.util.concurrent.ConcurrentHashMap<>();
    /** Whether each directory checked so far is the root under another name. */
    private final Map<String, Boolean> rootAliases = new java.util.concurrent.ConcurrentHashMap<>();
    /** Whether two paths name the same file: a bind mount or a drive alias of the root is one. */
    private final SameFile sameFile;

    /** {@link java.nio.file.Files#isSameFile}, replaceable where no test can mount a directory. */
    interface SameFile {
        boolean test(Path a, Path b) throws IOException;
    }

    TouchRecorder() {
        sameFile = java.nio.file.Files::isSameFile;
    }

    /** A recorder that records the non-class files read beneath {@code root}, as an attached one does. */
    TouchRecorder(String root) {
        this(root, java.nio.file.Files::isSameFile);
    }

    TouchRecorder(String root, SameFile sameFile) {
        this.sameFile = sameFile;
        roots = rootsOf(root);
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
        roots = rootsOf(HostBuild.current().property(AgentContract.CAPTURE_ROOT_PROPERTY));
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
            ReadHooks.Installed hooks = ReadHooks.install(instrumentation, this::read, this::lookup, this::defined,
                    this::childProcess, this::nativeCode, this::library, this::resolved, this::environmentRead);
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
            List<String[]> drained = pending;
            pending = new ArrayList<>();
            return drained;
        }
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
            String unmet = entry != null ? unmet(caller, entry) : null;
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

    /** Null when a conditional entry's guard holds for this load, or why not; always null for any other. */
    private String unmet(Class<?> caller, String[] entry) {
        if (entry.length == 2) {
            return null;
        }
        GuardHooks installed = guards;
        String unarmed = installed == null ? "the yoriwake agent did not install the native-method guards"
                : installed.unarmed(caller, GuardHooks.wrapFor(entry[2]));
        return unarmed != null ? unarmed : GuardHooks.unlisted(caller, entry[2], entry[3], entry[4].split(","));
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
                ZipFile zip = (ZipFile) pair[0];
                ZipEntry entry = (ZipEntry) pair[1];
                if (isClassOrSource(entry.getName())) {
                    if (!definingAClass()) {
                        touchNamed(AgentContract.TOUCH_READ, entry.getName());
                    }
                } else {
                    String jar = new File(zip.getName()).getAbsolutePath();
                    if (entryHoldsAZip(zip, entry)) {
                        // An archive inside an archive: what reads it scans its entries with its own code.
                        touchNamed(AgentContract.TOUCH_JAR, jar);
                    }
                    List<String> forms = recordedAs(jar);
                    if (!forms.isEmpty()) {
                        String kind = MANIFEST.equals(entry.getName()) && loaderReadingManifest()
                                ? AgentContract.TOUCH_LOADER_MANIFEST : AgentContract.TOUCH_RESOURCE;
                        for (String form : forms) {
                            touchNamed(kind, form + "!/" + entry.getName());
                        }
                    }
                }
                return;
            }
            String path = opened instanceof File ? ((File) opened).getPath() : String.valueOf(opened);
            if (isClassOrSource(path)) {
                if (!definingAClass()) {
                    touchNamed(AgentContract.TOUCH_READ, path);
                }
                // A source file a test opens is also a file it read, by its path: a snippet under
                // `docs/` is one, and its class name, if any, says nothing about where it lives.
                if (!path.endsWith(".class")) {
                    for (String form : recordedAs(new File(path).getAbsolutePath())) {
                        touchNamed(AgentContract.TOUCH_RESOURCE, form);
                    }
                }
                return;
            }
            File file = new File(path);
            String absolute = file.getAbsolutePath();
            boolean archive = path.endsWith(".jar") || ZipContent.holdsAZip(file);
            // The JDK's zip code opening an archive reads its entries through the entry hook.
            if (archive && openedByJdkZip()) {
                return;
            }
            if (archive && (path.endsWith(".jar") || mayHoldClasses(file))) {
                // A scanner reading an archive with its own zip code: every class in it may be read.
                // Known by content, not name: a war, an aar, or a zip of the classes is one too.
                touchNamed(AgentContract.TOUCH_JAR, absolute);
            }
            // An archive read whole is a read of every entry in it.
            for (String form : recordedAs(absolute)) {
                touchNamed(AgentContract.TOUCH_RESOURCE, form);
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

    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    /** {@link #roots} for a root directory as the plugin gives it. */
    static List<String> rootsOf(String root) {
        List<String> found = new ArrayList<>();
        if (root == null || root.isEmpty()) {
            return found;
        }
        File dir = new File(root).getAbsoluteFile();
        found.add(dir.getPath() + File.separator);
        try {
            // Resolves links on every platform, which a canonical path does not on Windows.
            String resolved = dir.toPath().toRealPath().toString() + File.separator;
            if (!found.contains(resolved)) {
                found.add(resolved);
            }
            // Spells out a short (8.3) name the real path keeps on Windows.
            String canonical = dir.getCanonicalPath() + File.separator;
            if (!found.contains(canonical)) {
                found.add(canonical);
            }
        } catch (IOException | RuntimeException e) {
            // The path as given still matches what is opened by it.
        }
        return found;
    }

    /**
     * The paths a read of an absolute path is recorded under; none when it is not beneath the
     * root. The decoder keys each form to the file it names, so every name the read went by that
     * could match the walked file is kept:
     * <ul>
     * <li>the path as given, when it or its resolved form is beneath the root;</li>
     * <li>besides, the resolved form, when it names the file differently below the root (another
     * letter case, a short name, a link inside the root). Resolved now, while a link the test may
     * delete still exists;</li>
     * <li>for a path beneath the root under another name (a bind mount, a drive alias), the root
     * as given with the rest of the path;</li>
     * <li>the path as given when whether it is beneath the root cannot be told, which the decoder
     * counts as a read of every file.</li>
     * </ul>
     */
    private List<String> recordedAs(String absolute) {
        List<String> prefixes = roots;
        if (prefixes.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        // Resolved outside the map's lock: the answer is the same whichever thread computes it.
        List<String> known = rootChecked.get(absolute);
        if (known == null) {
            known = forms(absolute, prefixes);
            // A file not there yet, or one reached through a link inside the root, is asked about
            // again: the file may appear, or the link be created or pointed elsewhere.
            if (known == null) {
                return java.util.Collections.emptyList();
            }
            if (known.size() == 1) {
                rootChecked.putIfAbsent(absolute, known);
            }
        }
        return known;
    }

    /** {@link #recordedAs}'s answer, or null when the file is not there to read. */
    private List<String> forms(String absolute, List<String> prefixes) {
        String resolved;
        try {
            resolved = new File(absolute).toPath().toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            resolved = null;
        }
        if (resolved != null && File.separatorChar == '\\' && resolved.indexOf('~') >= 0) {
            // Windows keeps a short (8.3) name in the real path; the canonical path spells it out.
            try {
                resolved = new File(resolved).getCanonicalPath();
            } catch (IOException | RuntimeException e) {
                // The real path still names the file.
            }
        }
        String below = below(absolute, prefixes);
        if (below != null) {
            String resolvedBelow = resolved == null ? null : below(resolved, prefixes);
            return resolvedBelow == null || resolvedBelow.equals(below)
                    ? java.util.Collections.singletonList(absolute)
                    : java.util.Arrays.asList(absolute, resolved);
        }
        if (resolved != null && below(resolved, prefixes) != null) {
            // Through a link to the root, or a short name above it: the resolved path is the one
            // the decoder can key, even once the link is gone.
            return java.util.Arrays.asList(absolute, resolved);
        }
        if (resolved == null) {
            // Nothing was read, and a file that is gone forces anyway.
            return null;
        }
        try {
            // From the resolved path, so a link outside the root into an alias of it is followed.
            String aliased = throughAlias(resolved, prefixes.get(0));
            return aliased == null ? java.util.Collections.<String>emptyList() : java.util.Collections.singletonList(aliased);
        } catch (IOException | RuntimeException e) {
            return java.util.Collections.singletonList(absolute);
        }
    }

    /** What follows the first of {@code prefixes} that {@code path} starts with; null for none. */
    private static String below(String path, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return path.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * The path rewritten onto {@code root} when one of its directories is the root under another
     * name; null when none is.
     */
    private String throughAlias(String absolute, String root) throws IOException {
        Path rootDir = Paths.get(root);
        Path path = Paths.get(absolute);
        for (Path dir = path.getParent(); dir != null; dir = dir.getParent()) {
            String key = dir.toString();
            Boolean same = rootAliases.get(key);
            if (same == null) {
                same = sameFile.test(dir, rootDir);
                rootAliases.putIfAbsent(key, same);
            }
            if (same) {
                return root + dir.relativize(path);
            }
        }
        return null;
    }

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

    private static boolean isClassOrSource(String path) {
        return path.endsWith(".class") || path.endsWith(".java") || path.endsWith(".kt")
                || path.endsWith(".groovy") || path.endsWith(".scala");
    }

    /**
     * Whether an archive found by its content can hold a class: a class or source entry, an archive
     * entry, or no listing at all. One that holds none (a spreadsheet, a document, a zip of data)
     * names no class, and is not reported, so one gone by the time the records are read cannot
     * count as every class. Checked again when its length or time changes.
     */
    private boolean mayHoldClasses(File file) {
        String path = file.getAbsolutePath();
        String stamp = file.length() + "/" + file.lastModified();
        synchronized (lock) {
            if (stamp.equals(classless.get(path))) {
                return false;
            }
        }
        try (ZipFile zip = new ZipFile(file)) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                if (isClassOrSource(entry.getName()) || holdsAZip(zip, entry)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException unlisted) {
            return true;
        }
        synchronized (lock) {
            classless.put(path, stamp);
        }
        return false;
    }

    /**
     * Whether an entry the JDK's zip code hands out holds a zip archive itself, such as a jar nested
     * in a war. Read through a zip of its own: calling back into the hooked one, a jar file, would
     * re-enter its signature verification.
     */
    private static boolean entryHoldsAZip(ZipFile hooked, ZipEntry entry) {
        long size = entry.getSize();
        if (entry.isDirectory() || (size >= 0 && size < ZipContent.END_RECORD)) {
            return false;
        }
        try (ZipFile own = new ZipFile(hooked.getName())) {
            ZipEntry same = own.getEntry(entry.getName());
            return same != null && holdsAZip(own, same);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** Whether an entry of a zip this class opened itself holds a zip archive. */
    private static boolean holdsAZip(ZipFile zip, ZipEntry entry) throws IOException {
        long size = entry.getSize();
        if (entry.isDirectory() || (size >= 0 && size < ZipContent.END_RECORD)) {
            return false;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in != null && ZipContent.startsAZip(in.readNBytes(4));
        }
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
