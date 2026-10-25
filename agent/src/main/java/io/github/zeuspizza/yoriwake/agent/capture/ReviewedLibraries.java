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
     * Loading class, then the file name; for a conditional entry, then the guard classes, the
     * version file beside the first, and the versions reviewed, each list comma-separated.
     */
    private static final String[][] REVIEWED = {
        // Netty's loader defines this helper into the transport's class loader and loads through it.
        {"io.netty.util.internal.NativeLibraryUtil", "libnetty_transport_native_epoll(_[a-z0-9_]+)?[0-9]*\\.so"},
        {"io.netty.util.internal.NativeLibraryUtil",
            "libnetty_transport_native_kqueue(_[a-z0-9_]+)?[0-9]*\\.(so|dylib|jnilib)"},
        // Netty's own io_uring transport lets any caller submit an operation, an open among them:
        // reviewed safe for the operations Netty itself submits. Before the incubator's, whose
        // static-name fallback with digits this also matches, and which then has no guard class.
        {"io.netty.util.internal.NativeLibraryUtil", "libnetty_transport_native_io_uring42(_[a-z0-9_]+)?[0-9]*\\.so",
            "io/netty/channel/uring/SubmissionQueue",
            "META-INF/maven/io.netty/netty-transport-classes-io_uring/pom.properties",
            "4.2.18.Final,4.2.19.Final"},
        // The incubator's transport.
        {"io.netty.util.internal.NativeLibraryUtil", "libnetty_transport_native_io_uring_[a-z0-9_]+\\.so"},
        {"io.netty.util.internal.NativeLibraryUtil",
            "libnetty_resolver_dns_native_macos(_(x86_64|aarch_64))?[0-9]*\\.(dylib|jnilib)"},
        {"org.conscrypt.NativeLibraryUtil", "libconscrypt_openjdk_jni(-[a-z]+-[a-z0-9_]+)?[0-9]*\\.(so|dylib|jnilib)"},
        // Amazon Corretto Crypto Provider: the random part of its extracted copy is in the directory.
        {"com.amazon.corretto.crypto.provider.Loader", "libamazonCorrettoCryptoProvider\\.(so|dylib)"},
        {"org.xerial.snappy.SnappyLoader",
            "(snappy-[0-9A-Za-z.]*-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-)?"
                + "libsnappyjava\\.(so|dylib|jnilib)"},
        // The version runs straight into the random digits of the extracted copy.
        {"com.github.luben.zstd.util.Native", "libzstd-jni-[0-9]+\\.[0-9]+\\.[0-9]+-[0-9]+\\.(so|dylib)"},
        {"net.jpountz.util.Native", "liblz4-java(-[0-9]+)?\\.(so|dylib)"},
        {"org.rocksdb.NativeLibraryLoader", "librocksdbjni(-[a-z0-9_-]+)?[0-9]*\\.(so|dylib|jnilib)"},
        // The host's compression libraries RocksDB loads by name before its own, each at a version
        // reviewed, as the JDK resolves it: Ubuntu 22.04's and 24.04's, and Homebrew's. Any other
        // version, a name without one (macOS's own libz.dylib), and bzip2 run wide.
        {"org.rocksdb.RocksDB", "libz\\.so\\.1\\.(2\\.11|3)|libsnappy\\.so\\.1\\.1\\.(8|10)|liblz4\\.so\\.1\\.9\\.[34]"
            + "|libzstd\\.so\\.1\\.(4\\.8|5\\.5)"
            + "|libz\\.1\\.3\\.2\\.dylib|libsnappy\\.1\\.3\\.1\\.dylib|liblz4\\.1\\.10\\.0\\.dylib|libzstd\\.1\\.5\\.7\\.dylib"},
        // Safe unless a connection enables extension loading, which lets SQL load any library.
        {"org.sqlite.SQLiteJDBCLoader",
            "(sqlite-([0-9.]+|unknown)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-)?"
                + "libsqlitejdbc\\.(so|dylib|jnilib)",
            "org/sqlite/core/NativeDB", "META-INF/maven/org.xerial/sqlite-jdbc/pom.properties", "3.53.4.0"},
        // Gradle's native-platform reads no file's content, but a link it makes and a working
        // directory it changes hide later reads: safe while both calls are guarded. Its jar has no
        // version file; the hash of its natives tells 0.22-milestone-29 and -30 apart from the rest.
        {"net.rubygrapefruit.platform.internal.NativeLibraryLoader", "libnative-platform\\.(so|dylib)",
            "net/rubygrapefruit/platform/internal/jni/PosixFileFunctions,"
                + "net/rubygrapefruit/platform/internal/jni/PosixProcessFunctions",
            "net/rubygrapefruit/platform/internal/jni/NativeVersion.class#VERSION",
            "660c5614fbf4a5f2a75b1949f50a0caf5cd603558e7d8a26e50b933a0cfb79d1,"
                + "6f2b8059d26491bb262815fb44547f418c8258d5d0a14091d1a14ff26d0373f0"},
        // Its file-watching library, which only watches directories it is given.
        {"org.gradle.fileevents.FileEvents", "libgradle-fileevents\\.(so|dylib)"},
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
