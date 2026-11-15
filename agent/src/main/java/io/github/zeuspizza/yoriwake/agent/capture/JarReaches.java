package io.github.zeuspizza.yoriwake.agent.capture;

import static io.github.zeuspizza.yoriwake.agent.capture.TouchRecorder.MANIFEST;
import static io.github.zeuspizza.yoriwake.agent.capture.TouchRecorder.loaderReadingManifest;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Which library module jars a {@link TouchRecorder}'s JVM reaches, and the names it looks up in
 * them and does not find. A dependency bump moves a jar: a test that reached it, or missed a name a
 * new version may add, can depend on the bump.
 *
 * <p>Fail-open as the recorder is: a failure is the recorder's {@link TouchRecorder#incomplete()}.
 */
final class JarReaches {

    private final TouchRecorder recorder;
    /**
     * The library module jars this JVM records touches of, each under the path the plugin named it
     * by, and under its resolved path; null when none were named or their entries cannot be seen.
     */
    private volatile Map<String, String> moduleJars;
    /** The module path each jar or code-source path seen so far stands for, empty for none. */
    private final Map<String, String> modulePathOf = new java.util.concurrent.ConcurrentHashMap<>();
    /** The module jars reached so far, so each is recorded once without the lock. */
    private final Set<String> reachedJars = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Names looked up in a module jar and not found since the last drain, with their directories. */
    private final Map<String, String> pendingMisses = new HashMap<>();
    /** Class files found or classes defined since the last drain, which cancel a miss of the same name. */
    private final Set<String> answered = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Directories a miss was recorded in; read without the lock, written under it. */
    private final Set<String> missedDirectories = java.util.concurrent.ConcurrentHashMap.newKeySet();

    JarReaches(TouchRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Adds to {@code pending} the misses of this window that stand, under the recorder's lock, and
     * starts the next window.
     */
    void drainMisses(List<String[]> pending) {
        synchronized (recorder.lock) {
            // A class-path search misses in every jar before the one that holds the class, so a class
            // miss that some jar or definition answered in the same window is no miss. A resource is
            // looked for in every jar, and a new version that adds it changes the answer, so its miss
            // always stands. A directory is written once, for the first miss that stands.
            for (Map.Entry<String, String> miss : pendingMisses.entrySet()) {
                String name = miss.getKey();
                if (name.endsWith(".class") && answered.contains(name)) {
                    continue;
                }
                String directory = miss.getValue();
                if (missedDirectories.add(directory)) {
                    pending.add(new String[] {AgentContract.TOUCH_MISSED, directory});
                }
            }
            pendingMisses.clear();
            answered.clear();
        }
    }

    /**
     * Records from here on which of {@code modules} ({@link TouchRecorder#modulesOf}) this JVM
     * reaches, and the absent names it looks up in them. Only once every entry hook is in place: a
     * lookup unseen would leave a jar unreached.
     */
    void record(Map<String, String> modules) {
        moduleJars = modules;
        recorder.touch(AgentContract.TOUCH_JARS_RECORDED, "");
    }

    /** The module path {@code path} stands for, or null when it is no module jar's. */
    String modulePath(String path) {
        Map<String, String> modules = moduleJars;
        if (modules == null || path == null) {
            return null;
        }
        String direct = modules.get(path);
        if (direct != null) {
            return direct;
        }
        String known = modulePathOf.get(path);
        if (known == null) {
            known = "";
            try {
                String resolved = Paths.get(path).toAbsolutePath().toRealPath().toString();
                String module = modules.get(resolved);
                if (module != null) {
                    known = module;
                }
            } catch (IOException | RuntimeException unresolved) {
                // Asked again next time: the file may appear.
                return null;
            }
            modulePathOf.put(path, known);
        }
        return known.isEmpty() ? null : known;
    }

    /**
     * Whether looking up or reading {@code name} is the JDK's class loader opening a jar of the class
     * path (its manifest, for {@code Class-Path} and {@code Multi-Release}) or defining a package from
     * it: the class-path search does that for every jar it walks, whatever the class it looks for. What
     * the loader takes from a manifest is compared by the daemon; a manifest read by anything else is a
     * reach.
     */
    static boolean loaderOpening(String name) {
        return MANIFEST.equals(name) && loaderReadingManifest();
    }

    /** Records that this JVM reached the module jar at {@code module}, once. */
    void reached(String module) {
        if (reachedJars.add(module)) {
            recorder.touch(AgentContract.TOUCH_JAR_REACHED, module);
        }
    }

    /**
     * The entry hooks' sink: {zip, name, entry or null} for every lookup of a jar entry by name. A
     * hit in a module jar reaches it; a miss there is kept until the window drains, and a hit
     * anywhere answers a class miss of the same name.
     */
    void entry(Object looked) {
        if (moduleJars == null || recorder.inside.get() != null) {
            return;
        }
        recorder.inside.set(Boolean.TRUE);
        try {
            Object[] triple = (Object[]) looked;
            String name = (String) triple[1];
            boolean found = triple[2] != null;
            String module = modulePath(((ZipFile) triple[0]).getName());
            if (found) {
                if (module != null && !loaderOpening(name)) {
                    reached(module);
                }
                // Only a library jar's answer is the class path's; a child loader's jar answering the same
                // name leaves the class path's miss standing.
                if (module != null && name.endsWith(".class")) {
                    answered.add(name);
                }
                return;
            }
            if (module == null) {
                return;
            }
            // The JDK's loader asks every jar it opens for its index (JDK 11 and 17); no test asked.
            if (INDEX.equals(name) && byClassPathLoader()) {
                return;
            }
            int slash = name.lastIndexOf('/');
            String directory = slash < 0 ? "/" : name.substring(0, slash + 1);
            if (missedDirectories.contains(directory)) {
                return;
            }
            synchronized (recorder.lock) {
                pendingMisses.putIfAbsent(name, directory);
            }
        } catch (Throwable t) {
            recorder.incomplete = "a jar entry lookup could not be recorded: " + t;
        } finally {
            recorder.inside.remove();
        }
    }

    /**
     * The enumeration hooks' sink: code listed a jar's entries, as a classpath scanner does. Listing
     * a module jar reaches it, whatever the scanner then reads or finds nothing to read.
     */
    void enumerated(Object zip) {
        if (moduleJars == null || recorder.inside.get() != null) {
            return;
        }
        recorder.inside.set(Boolean.TRUE);
        try {
            String module = modulePath(((ZipFile) zip).getName());
            if (module != null) {
                reached(module);
            }
        } catch (Throwable t) {
            recorder.incomplete = "a jar listing could not be recorded: " + t;
        } finally {
            recorder.inside.remove();
        }
    }

    private static final String INDEX = "META-INF/INDEX.LIST";

    /** Whether the JDK's own class-path loader made the hooked call. Anything that cannot tell says no. */
    private static boolean byClassPathLoader() {
        try {
            return StackWalker.getInstance().walk(frames -> frames.limit(24)
                    .anyMatch(frame -> frame.getClassName().startsWith("jdk.internal.loader.")));
        } catch (Throwable t) {
            return false;
        }
    }

    /** A class defined from {@code domain}'s code source: when that is a module jar, the jar was reached. */
    void definedFrom(ProtectionDomain domain, String internalName, boolean byClassPath) {
        if (moduleJars == null) {
            return;
        }
        // Only a definition by the class path's own loaders answers a class-path search.
        if (byClassPath) {
            answered.add(internalName + ".class");
        }
        if (domain == null || recorder.inside.get() != null) {
            return;
        }
        recorder.inside.set(Boolean.TRUE);
        try {
            java.security.CodeSource source = domain.getCodeSource();
            java.net.URL location = source == null ? null : source.getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return;
            }
            // Keyed by the URL, so each code source is turned into a path once.
            String key = "url:" + location;
            String module = modulePathOf.get(key);
            if (module == null) {
                String found = modulePath(new File(location.toURI()).getPath());
                module = found == null ? "" : found;
                modulePathOf.put(key, module);
            }
            if (!module.isEmpty()) {
                reached(module);
            }
        } catch (Throwable t) {
            recorder.incomplete = "a class's code source could not be recorded: " + t;
        } finally {
            recorder.inside.remove();
        }
    }
}
