package io.github.zeuspizza.yoriwake.agent.capture;

import java.util.regex.Pattern;

/**
 * Native libraries whose sources were reviewed and found to open no file that can be a class file
 * or a jar, other than a path a test passes as data. Native code can read any file without a call
 * the agent sees, so a library that code outside the JDK loads touches every class from its load,
 * unless it is one of these, loaded by the class that ships with it. {@code docs/reference.md}
 * lists each review and the version it covered.
 *
 * <p>An entry matches on the whole file name, as the library's own loader names its extracted copy
 * or as {@code System.loadLibrary} maps the library's name, and on the class that called
 * {@code System.load} or {@code System.loadLibrary}: the entry's class or a class nested in it. A
 * shaded or renamed copy, or the same file loaded by any other class, does not match.
 *
 * <p>A conditional entry was reviewed safe unless a call turns a feature on. It counts only while
 * {@link GuardHooks} wraps that call in the loader that loads the library, and only at the exact
 * versions reviewed, read from the version file beside the guard class: a release that adds
 * another way to turn the feature on is not covered by the review of one that had none.
 */
final class ReviewedLibraries {

    /**
     * Loading class, then the file name; for a conditional entry, then the guard class, the
     * version file beside it, and the versions reviewed, comma-separated.
     */
    private static final String[][] REVIEWED = {
        // Netty's loader defines this helper into the transport's class loader and loads through it.
        {"io.netty.util.internal.NativeLibraryUtil", "libnetty_transport_native_epoll(_[a-z0-9_]+)?[0-9]*\\.so"},
        {"io.netty.util.internal.NativeLibraryUtil",
            "libnetty_transport_native_kqueue(_[a-z0-9_]+)?[0-9]*\\.(so|dylib|jnilib)"},
        // The incubator's transport. Netty's own, named io_uring42, lets any caller submit an open.
        {"io.netty.util.internal.NativeLibraryUtil", "libnetty_transport_native_io_uring_[a-z0-9_]+\\.so"},
        {"org.conscrypt.NativeLibraryUtil", "libconscrypt_openjdk_jni(-[a-z]+-[a-z0-9_]+)?[0-9]*\\.(so|dylib|jnilib)"},
        {"org.xerial.snappy.SnappyLoader",
            "(snappy-[0-9A-Za-z.]*-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-)?"
                + "libsnappyjava\\.(so|dylib|jnilib)"},
        // The version runs straight into the random digits of the extracted copy.
        {"com.github.luben.zstd.util.Native", "libzstd-jni-[0-9]+\\.[0-9]+\\.[0-9]+-[0-9]+\\.(so|dylib)"},
        {"net.jpountz.util.Native", "liblz4-java(-[0-9]+)?\\.(so|dylib)"},
        {"org.rocksdb.NativeLibraryLoader", "librocksdbjni(-[a-z0-9_-]+)?[0-9]*\\.(so|dylib|jnilib)"},
        // Safe unless a connection enables extension loading, which lets SQL load any library.
        {"org.sqlite.SQLiteJDBCLoader",
            "(sqlite-([0-9.]+|unknown)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-)?"
                + "libsqlitejdbc\\.(so|dylib|jnilib)",
            "org/sqlite/core/NativeDB", "META-INF/maven/org.xerial/sqlite-jdbc/pom.properties", "3.53.4.0"},
    };

    private static final Pattern[] PATTERNS = new Pattern[REVIEWED.length];

    static {
        for (int i = 0; i < REVIEWED.length; i++) {
            PATTERNS[i] = Pattern.compile(REVIEWED[i][1]);
        }
    }

    private ReviewedLibraries() {}

    /** Whether {@code loadingClass}, a binary name, loading a file named {@code fileName} is reviewed. */
    static boolean reviewed(String loadingClass, String fileName) {
        return entry(loadingClass, fileName) != null;
    }

    /** The entry {@code loadingClass} loading a file named {@code fileName} matches, or null. */
    static String[] entry(String loadingClass, String fileName) {
        for (int i = 0; i < REVIEWED.length; i++) {
            String owner = REVIEWED[i][0];
            boolean own = loadingClass.equals(owner) || loadingClass.startsWith(owner + "$");
            if (own && PATTERNS[i].matcher(fileName).matches()) {
                return REVIEWED[i];
            }
        }
        return null;
    }
}
