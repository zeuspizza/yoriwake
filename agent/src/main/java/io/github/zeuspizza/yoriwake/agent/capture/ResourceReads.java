package io.github.zeuspizza.yoriwake.agent.capture;

import static io.github.zeuspizza.yoriwake.agent.capture.TouchRecorder.AGENT_PACKAGE;
import static io.github.zeuspizza.yoriwake.agent.capture.TouchRecorder.MANIFEST;
import static io.github.zeuspizza.yoriwake.agent.capture.TouchRecorder.loaderReadingManifest;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The files a {@link TouchRecorder}'s JVM reads: class and source files by name, archives a scanner
 * reads with its own code, and any other file beneath the root project's directory by its path.
 *
 * <p>Fail-open as the recorder is: a failure is the recorder's {@link TouchRecorder#incomplete()}.
 */
final class ResourceReads {

    private final TouchRecorder recorder;
    private final JarReaches jars;
    /** Archives found to hold no class, source or archive, by path, with the length and time they had. */
    private final Map<String, String> classless = new HashMap<>();
    /**
     * The root project's directory, as given and as resolved, each ending in a separator; empty
     * when it was not given, and then no non-class file is recorded.
     */
    volatile List<String> roots = new ArrayList<>();
    /** The paths each path read so far is recorded under, so each is resolved at most once. */
    private final Map<String, List<String>> rootChecked = new java.util.concurrent.ConcurrentHashMap<>();
    /** Whether each directory checked so far is the root under another name. */
    private final Map<String, Boolean> rootAliases = new java.util.concurrent.ConcurrentHashMap<>();
    /** Whether two paths name the same file: a bind mount or a drive alias of the root is one. */
    private final TouchRecorder.SameFile sameFile;

    ResourceReads(TouchRecorder recorder, JarReaches jars, TouchRecorder.SameFile sameFile) {
        this.recorder = recorder;
        this.jars = jars;
        this.sameFile = sameFile;
    }

    /** The read hooks' sink: a {@link File}, a {@link Path}, or a {@code {ZipFile, ZipEntry}} pair. */
    void read(Object opened) {
        if (recorder.inside.get() != null) {
            return;
        }
        recorder.inside.set(Boolean.TRUE);
        try {
            if (opened instanceof Object[]) {
                Object[] pair = (Object[]) opened;
                ZipFile zip = (ZipFile) pair[0];
                ZipEntry entry = (ZipEntry) pair[1];
                String module = jars.modulePath(zip.getName());
                if (module != null && !JarReaches.loaderOpening(entry.getName())) {
                    jars.reached(module);
                }
                if (isClassOrSource(entry.getName())) {
                    if (!definingAClass()) {
                        recorder.touchNamed(AgentContract.TOUCH_READ, entry.getName());
                    }
                } else {
                    String jar = new File(zip.getName()).getAbsolutePath();
                    if (entryHoldsAZip(zip, entry)) {
                        // An archive inside an archive: what reads it scans its entries with its own code.
                        recorder.touchNamed(AgentContract.TOUCH_JAR, jar);
                    }
                    List<String> forms = recordedAs(jar);
                    if (!forms.isEmpty()) {
                        String kind = MANIFEST.equals(entry.getName()) && loaderReadingManifest()
                                ? AgentContract.TOUCH_LOADER_MANIFEST : AgentContract.TOUCH_RESOURCE;
                        for (String form : forms) {
                            recorder.touchNamed(kind, form + "!/" + entry.getName());
                        }
                    }
                }
                return;
            }
            String path = opened instanceof File ? ((File) opened).getPath() : String.valueOf(opened);
            if (isClassOrSource(path)) {
                if (!definingAClass()) {
                    recorder.touchNamed(AgentContract.TOUCH_READ, path);
                }
                // A source file a test opens is also a file it read, by its path: a snippet under
                // `docs/` is one, and its class name, if any, says nothing about where it lives.
                if (!path.endsWith(".class")) {
                    for (String form : recordedAs(new File(path).getAbsolutePath())) {
                        recorder.touchNamed(AgentContract.TOUCH_RESOURCE, form);
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
                recorder.touchNamed(AgentContract.TOUCH_JAR, absolute);
            }
            // An archive read whole is a read of every entry in it.
            for (String form : recordedAs(absolute)) {
                recorder.touchNamed(AgentContract.TOUCH_RESOURCE, form);
            }
        } catch (Throwable t) {
            recorder.incomplete = "a read could not be recorded: " + t;
        } finally {
            recorder.inside.remove();
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
        synchronized (recorder.lock) {
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
        synchronized (recorder.lock) {
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
}
