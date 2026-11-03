package io.github.zeuspizza.yoriwake.capturescript;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * A default file-system provider that is not the JDK's, installed with
 * {@code -Djava.nio.file.spi.DefaultFileSystemProvider}. It hands every call to the JDK's; only
 * the file system it reports as default names it as the provider.
 */
public final class ForeignProvider extends FileSystemProvider {

    private final FileSystemProvider jdk;
    private final FileSystem fileSystem;

    public ForeignProvider(FileSystemProvider jdk) {
        this.jdk = jdk;
        this.fileSystem = new Default(jdk.getFileSystem(URI.create("file:///")));
    }

    @Override
    public String getScheme() {
        return jdk.getScheme();
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return jdk.newFileSystem(uri, env);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystem;
    }

    @Override
    public Path getPath(URI uri) {
        return jdk.getPath(uri);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        return jdk.newByteChannel(path, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        return jdk.newDirectoryStream(dir, filter);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        jdk.createDirectory(dir, attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        jdk.delete(path);
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        jdk.copy(source, target, options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        jdk.move(source, target, options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        return jdk.isSameFile(path, path2);
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return jdk.isHidden(path);
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return jdk.getFileStore(path);
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        jdk.checkAccess(path, modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return jdk.getFileAttributeView(path, type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        return jdk.readAttributes(path, type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        return jdk.readAttributes(path, attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        jdk.setAttribute(path, attribute, value, options);
    }

    /** The JDK's default file system, naming this provider as its own. */
    private final class Default extends FileSystem {

        private final FileSystem jdkFileSystem;

        Default(FileSystem jdkFileSystem) {
            this.jdkFileSystem = jdkFileSystem;
        }

        @Override
        public FileSystemProvider provider() {
            return ForeignProvider.this;
        }

        @Override
        public void close() throws IOException {
            jdkFileSystem.close();
        }

        @Override
        public boolean isOpen() {
            return jdkFileSystem.isOpen();
        }

        @Override
        public boolean isReadOnly() {
            return jdkFileSystem.isReadOnly();
        }

        @Override
        public String getSeparator() {
            return jdkFileSystem.getSeparator();
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            return jdkFileSystem.getRootDirectories();
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            return jdkFileSystem.getFileStores();
        }

        @Override
        public Set<String> supportedFileAttributeViews() {
            return jdkFileSystem.supportedFileAttributeViews();
        }

        @Override
        public Path getPath(String first, String... more) {
            return jdkFileSystem.getPath(first, more);
        }

        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            return jdkFileSystem.getPathMatcher(syntaxAndPattern);
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return jdkFileSystem.getUserPrincipalLookupService();
        }

        @Override
        public WatchService newWatchService() throws IOException {
            return jdkFileSystem.newWatchService();
        }
    }
}
