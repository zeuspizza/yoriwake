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
     * version file beside it, and the versions reviewed, comma-separated. For an entry identified
     * by its bytes, {@code identity:<group>}, the artifact, and the versions reviewed.
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
        // netty-tcnative's BoringSSL build only: its OpenSSL build ships files of the same names and
        // reads a configuration file and modules from paths the environment names. Told apart by the
        // loaded bytes against the resource of the jar that declares the artifact.
        {"io.netty.util.internal.NativeLibraryUtil", "libnetty_tcnative(_[a-z0-9_]+)?[0-9]*\\.(so|dylib|jnilib)",
            "identity:io.netty", "netty-tcnative-boringssl-static", "2.0.84.Final"},
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
