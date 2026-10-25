package io.github.zeuspizza.yoriwake.agent.capture;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which artifact a loaded native library is, by its bytes: equal to a {@code META-INF/native/}
 * resource of a jar whose Maven metadata names the artifact. Its file name is only a hint, since
 * builds that open files a review never covered ship libraries of the same name.
 */
final class JarIdentity {

    /** Where Netty's loader takes the library it extracts. */
    private static final String NATIVE_DIR = "META-INF/native/";
    private static final String[] EXTENSIONS = {".so", ".dylib", ".jnilib"};

    private JarIdentity() {}

    /**
     * Null when {@code path}, the file the JDK loaded, equals a resource {@code caller}'s loader
     * finds, in a jar whose only metadata naming {@code group} names {@code artifact} at one of
     * {@code versions}; otherwise why not. {@code environment} reads a variable without recording
     * it; {@code resources} asks the loader, as the host's own code would.
     */
    static String unmet(Class<?> caller, String path, String group, String artifact, String[] versions,
            Function<String, String> environment, Function<String, Enumeration<URL>> resources) {
        File loaded = new File(path);
        if (!loaded.isAbsolute()) {
            return "the file the JDK loaded is not known, so it cannot be compared";
        }
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            for (String variable : new String[] {"DYLD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES",
                    "DYLD_FALLBACK_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH"}) {
                if (environment.apply(variable) != null) {
                    return variable + " is set, which can load another library than the file compared";
                }
            }
        }
        String why = "no resource a jar beside " + caller.getName() + " holds is the file loaded";
        for (String name : candidates(loaded.getName())) {
            Enumeration<URL> found = resources.apply(NATIVE_DIR + name);
            while (found != null && found.hasMoreElements()) {
                URL url = found.nextElement();
                String outcome = matches(url, loaded, group, artifact, versions);
                if (outcome == null) {
                    return null;
                }
                if (!outcome.isEmpty()) {
                    // Equal bytes from a jar that is not the reviewed artifact decide: no other can.
                    return outcome;
                }
            }
        }
        return why;
    }

    /**
     * The resource names the loaded file can have come from. An extracted copy carries random
     * digits after the name, which can run into the architecture's own ({@code aarch_64}), so each
     * shorter form is tried; a wrong guess finds nothing equal and forces.
     */
    static List<String> candidates(String file) {
        List<String> names = new ArrayList<>();
        String base = file;
        for (String extension : EXTENSIONS) {
            if (base.endsWith(extension)) {
                base = base.substring(0, base.length() - extension.length());
                break;
            }
        }
        for (String stem = base; !stem.isEmpty(); ) {
            for (String extension : EXTENSIONS) {
                names.add(stem + extension);
            }
            if (!Character.isDigit(stem.charAt(stem.length() - 1))) {
                break;
            }
            stem = stem.substring(0, stem.length() - 1);
        }
        return names;
    }

    /**
     * Null when the resource at {@code url} has the loaded file's bytes and its jar is the reviewed
     * artifact; empty when the bytes differ; otherwise why the equal resource does not count.
     */
    private static String matches(URL url, File loaded, String group, String artifact, String[] versions) {
        if (!"jar".equals(url.getProtocol())) {
            return url + " is not in a jar, so no artifact vouches for it";
        }
        String spec = url.getPath();
        int bang = spec.indexOf("!/");
        if (!spec.startsWith("file:") || bang < 0) {
            return url + " is not in a jar on this machine";
        }
        File jar = new File(URLDecoder.decode(spec.substring("file:".length(), bang), StandardCharsets.UTF_8));
        String entryName = spec.substring(bang + 2);
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.getSize() != loaded.length()) {
                return "";
            }
            try (InputStream a = new BufferedInputStream(zip.getInputStream(entry));
                    InputStream b = new BufferedInputStream(new FileInputStream(loaded))) {
                for (int x = a.read(), y = b.read(); ; x = a.read(), y = b.read()) {
                    if (x != y) {
                        return "";
                    }
                    if (x < 0) {
                        break;
                    }
                }
            }
            String declared = null;
            String version = null;
            for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                ZipEntry each = all.nextElement();
                String name = each.getName();
                if (!name.startsWith("META-INF/maven/" + group + "/") || !name.endsWith("/pom.properties")) {
                    continue;
                }
                Properties properties = new Properties();
                try (InputStream in = zip.getInputStream(each)) {
                    properties.load(in);
                }
                if (declared != null) {
                    return jar.getName() + " declares more than one artifact of " + group;
                }
                declared = properties.getProperty("artifactId");
                version = properties.getProperty("version");
            }
            if (!artifact.equals(declared)) {
                return jar.getName() + " is " + (declared == null ? "no artifact of " + group : declared) + ", not "
                        + artifact;
            }
            for (String listed : versions) {
                if (listed.equals(version)) {
                    return null;
                }
            }
            return artifact + " " + version + " is not a version reviewed";
        } catch (Throwable t) {
            return "the library could not be compared with " + jar.getName() + ": " + t;
        }
    }
}
