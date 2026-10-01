package io.github.zeuspizza.yoriwake.agent.capture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.FileSystems;
import java.nio.file.spi.FileSystemProvider;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Reports every open of a file or jar entry, and every lookup of a class by its name, by rewriting
 * the JDK's entry points for both to call a sink first. A class-file reader (ArchUnit, ClassGraph,
 * a test parsing bytes) never loads what it reads, so loading alone cannot see it; a lookup by
 * name is how code reaches a class it holds no reference to.
 *
 * <p>The JDK's own classes can only call a class its bootstrap loader defined. The sink holder is
 * therefore defined into {@code java.lang} through a lookup, which needs {@code java.lang} opened.
 * It is opened to a one-class module of this agent's own and to nothing else: opening it to the
 * class path's unnamed module would change what the host's own code may reflect on. The class path
 * is never appended to the bootstrap loader, which switches class-data sharing off, and nothing
 * travels through system properties, which host code may serialise.
 *
 * <p>Four more kinds of call reach a class, or everything, around those: a class defined from
 * bytes as a hidden or anonymous class, which no transformer is shown; a child process, which no
 * agent follows; a native library load; and native code reached through the foreign-function API
 * rather than a library load. Each has a sink of its own.
 *
 * <p>The injected call is guarded by a null check and the sink never throws, so an open behaves
 * exactly as before.
 */
final class ReadHooks {

    private static final String HOLDER = "java/lang/YoriwakeReadSink";
    private static final String BRIDGE_MODULE = "io.github.zeuspizza.yoriwake.readbridge";
    private static final String BRIDGE_PACKAGE = "io/github/zeuspizza/yoriwake/readbridge";
    private static final String DEFINER = BRIDGE_PACKAGE + "/Definer";
    private static final String CONSUMER = "java/util/function/Consumer";

    /**
     * Owner, method, descriptor, and what to pass: an argument slot, -1 for {this, argument 1}, -2
     * for null, or -3 for {argument 1, argument 2}. These open files without the file-system provider.
     */
    static final String[][] READS = {
        {"java/io/FileInputStream", "<init>", "(Ljava/io/File;)V", "1"},
        {"java/io/RandomAccessFile", "<init>", "(Ljava/io/File;Ljava/lang/String;)V", "1"},
        {"java/util/zip/ZipFile", "getInputStream",
            "(Ljava/util/zip/ZipEntry;)Ljava/io/InputStream;", "-1"},
    };

    /**
     * Method, descriptor and slot of every way the default file-system provider opens, copies or
     * links a file, hooked on whichever of its classes implements each: {@code Files},
     * {@code FileChannel} and a direct provider call all end there. {@code newByteChannel} must be
     * found; the others are hooked where the provider implements them, since the inherited
     * defaults delegate to it or throw.
     */
    static final String[][] PROVIDER_READS = {
        {"newByteChannel", "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)"
            + "Ljava/nio/channels/SeekableByteChannel;", "1"},
        {"newFileChannel", "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)"
            + "Ljava/nio/channels/FileChannel;", "1"},
        {"newAsynchronousFileChannel", "(Ljava/nio/file/Path;Ljava/util/Set;Ljava/util/concurrent/ExecutorService;"
            + "[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/channels/AsynchronousFileChannel;", "1"},
        {"newInputStream", "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;", "1"},
        {"copy", "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)V", "1"},
        {"move", "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)V", "1"},
        // A link gives a class file a name that no longer says so; its target is what gets read.
        {"createSymbolicLink",
            "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)V", "2"},
        {"createLink", "(Ljava/nio/file/Path;Ljava/nio/file/Path;)V", "2"},
    };

    /**
     * Defining a class no transformer is shown: a hidden class, and on older JDKs an anonymous one.
     * The sink gets the bytes. Each is hooked where this JDK has it.
     */
    static final String[][] DEFINES = {
        {"java/lang/invoke/MethodHandles$Lookup", "defineHiddenClass",
            "([BZ[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)Ljava/lang/invoke/MethodHandles$Lookup;", "1"},
        {"java/lang/invoke/MethodHandles$Lookup", "defineHiddenClassWithClassData",
            "([BLjava/lang/Object;Z[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)"
                + "Ljava/lang/invoke/MethodHandles$Lookup;", "1"},
        {"sun/misc/Unsafe", "defineAnonymousClass", "(Ljava/lang/Class;[B[Ljava/lang/Object;)Ljava/lang/Class;", "2"},
    };

    /** Where every child process starts, {@code ProcessBuilder} and {@code Runtime.exec} alike. */
    static final String[][] PROCESSES = {
        {"java/lang/ProcessImpl", "start", "([Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;"
            + "[Ljava/lang/ProcessBuilder$Redirect;Z)Ljava/lang/Process;", "-2"},
    };

    /**
     * The foreign-function API's ways into native code: the linker every downcall needs, and a
     * library loaded without {@code Runtime}. Hooked where this JDK has the API.
     */
    static final String[][] NATIVES = {
        {"java/lang/foreign/Linker", "nativeLinker", "()Ljava/lang/foreign/Linker;", "-2"},
        {"java/lang/foreign/SymbolLookup", "libraryLookup",
            "(Ljava/lang/String;Ljava/lang/foreign/Arena;)Ljava/lang/foreign/SymbolLookup;", "-2"},
        {"java/lang/foreign/SymbolLookup", "libraryLookup",
            "(Ljava/nio/file/Path;Ljava/lang/foreign/Arena;)Ljava/lang/foreign/SymbolLookup;", "-2"},
    };

    /**
     * Every way a class is found by its name, and the one way code reaches classes it never names:
     * the whole set of loaded classes. Same columns as {@link #READS}; the sink gets the name, or
     * for the last a value that stands for "anything".
     */
    static final String[][] LOOKUPS = {
        {"java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", "0"},
        {"java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", "0"},
        {"java/lang/Class", "forName", "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;", "1"},
        {"java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", "1"},
        {"java/lang/ClassLoader", "findLoadedClass", "(Ljava/lang/String;)Ljava/lang/Class;", "1"},
        {"java/lang/invoke/MethodHandles$Lookup", "findClass", "(Ljava/lang/String;)Ljava/lang/Class;", "1"},
        {"sun/instrument/InstrumentationImpl", "getAllLoadedClasses", "()[Ljava/lang/Class;", "0"},
    };

    /**
     * Where every native library load through {@code System} or {@code Runtime} starts. The sink
     * gets {caller, file or library name}.
     */
    static final String[][] LIBRARIES = {
        {"java/lang/Runtime", "load0", "(Ljava/lang/Class;Ljava/lang/String;)V", "-3"},
        {"java/lang/Runtime", "loadLibrary0", "(Ljava/lang/Class;Ljava/lang/String;)V", "-3"},
    };

    private ReadHooks() {}

    /** Why the hooks are not all in place, per kind; null when they are. */
    static final class Installed {
        final String reads;
        final String lookups;

        Installed(String reads, String lookups) {
            this.reads = reads;
            this.lookups = lookups;
        }
    }

    /**
     * Installs every hook. Reads, definitions, child processes and native code report under
     * {@code reads}, since a miss in any of them hides a class from every rule; lookups under
     * {@code lookups}. Each is null when all of its hooks are in place, or says why not.
     */
    static Installed install(Instrumentation instrumentation, Consumer<Object> reads, Consumer<Object> lookups,
            Consumer<Object> defines, Consumer<Object> processes, Consumer<Object> natives,
            Consumer<Object> libraries) {
        if (!instrumentation.isRetransformClassesSupported()) {
            String why = "this JVM cannot retransform classes";
            return new Installed(why, why);
        }
        List<String[]> readHooks = new ArrayList<>();
        List<String[]> lookupHooks = new ArrayList<>();
        List<String[]> defineHooks = new ArrayList<>();
        List<String[]> processHooks = new ArrayList<>();
        List<String[]> nativeHooks = new ArrayList<>();
        List<String[]> libraryHooks = new ArrayList<>();
        Injector injector;
        String providerWhy;
        try {
            Class<?> holder = defineHolder(instrumentation);
            // Loads everything each sink needs before any hook can reach it.
            reads.accept(new java.io.File("yoriwake-warm-up"));
            lookups.accept("yoriwake.WarmUp");
            holder.getField("SINK").set(null, reads);
            holder.getField("LOOKUP").set(null, lookups);
            holder.getField("DEFINE").set(null, defines);
            holder.getField("PROCESS").set(null, processes);
            holder.getField("NATIVE").set(null, natives);
            holder.getField("LIBRARY").set(null, libraries);
            addAll(readHooks, READS, "SINK", false);
            providerWhy = addProviderHooks(readHooks);
            addAll(lookupHooks, LOOKUPS, "LOOKUP", false);
            addAll(defineHooks, DEFINES, "DEFINE", true);
            addAll(processHooks, PROCESSES, "PROCESS", false);
            addAll(libraryHooks, LIBRARIES, "LIBRARY", false);
            for (String[] hook : NATIVES) {
                // The API is absent before it existed; where its class exists every hook must hold.
                if (load(hook[0]) != null) {
                    nativeHooks.add(withField(hook, "NATIVE"));
                }
            }
            List<String[]> all = new ArrayList<>();
            for (List<String[]> hooks : Arrays.asList(readHooks, lookupHooks, defineHooks, processHooks, nativeHooks,
                    libraryHooks)) {
                all.addAll(hooks);
            }
            injector = new Injector(all);
            instrumentation.addTransformer(injector, true);
        } catch (Throwable t) {
            String why = "the hooks could not be installed: " + t;
            return new Installed(why, why);
        }
        Map<String, String> failed = new HashMap<>();
        for (String owner : injector.wanted.keySet()) {
            try {
                Class<?> target = load(owner);
                if (target == null) {
                    failed.put(owner, "not found");
                } else {
                    instrumentation.retransformClasses(target);
                }
            } catch (Throwable t) {
                failed.put(owner, t.toString());
            }
        }
        String readsWhy = firstNonNull(providerWhy, installed("read", readHooks, injector, failed),
                installed("definition", defineHooks, injector, failed),
                installed("child-process", processHooks, injector, failed),
                installed("native-library", libraryHooks, injector, failed),
                installed("native-code", nativeHooks, injector, failed));
        String lookupsWhy = installed("lookup", lookupHooks, injector, failed);
        return new Installed(readsWhy, lookupsWhy);
    }

    /** Each hook with the holder field its sink sits in; when {@code ifPresent}, only where this JDK has it. */
    private static void addAll(List<String[]> into, String[][] hooks, String field, boolean ifPresent) {
        for (String[] hook : hooks) {
            if (!ifPresent || declares(load(hook[0]), hook[1], hook[2])) {
                into.add(withField(hook, field));
            }
        }
    }

    private static String[] withField(String[] hook, String field) {
        return new String[] {hook[0], hook[1], hook[2], hook[3], field};
    }

    /**
     * Adds {@link #PROVIDER_READS} for every class of the default provider that implements one;
     * returns why the provider cannot be trusted to see every open, or null.
     */
    private static String addProviderHooks(List<String[]> into) {
        Class<?> provider = FileSystems.getDefault().provider().getClass();
        if (provider.getClassLoader() != null) {
            // A provider of the host's own may delegate to the JDK's, which is then reachable unhooked.
            return "the default file-system provider is not the JDK's own: " + provider.getName();
        }
        boolean channel = false;
        for (Class<?> k = provider; k != null && k != FileSystemProvider.class; k = k.getSuperclass()) {
            for (String[] read : PROVIDER_READS) {
                if (declares(k, read[0], read[1])) {
                    into.add(new String[] {Type.getInternalName(k), read[0], read[1], read[2], "SINK"});
                    channel |= "newByteChannel".equals(read[0]);
                }
            }
        }
        return channel ? null : "the default file-system provider " + provider.getName() + " has no newByteChannel";
    }

    /** Whether {@code owner} itself declares a method with this name and descriptor, with a body. */
    private static boolean declares(Class<?> owner, String name, String descriptor) {
        if (owner == null) {
            return false;
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())
                    && !Modifier.isNative(method.getModifiers())
                    && Type.getMethodDescriptor(method).equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    /** A JDK class by internal name, not initialised; null when this JDK has none. */
    private static Class<?> load(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
        } catch (Throwable absent) {
            return null;
        }
    }

    /** Null when every hook of one kind was injected; otherwise why not. */
    private static String installed(String kind, List<String[]> hooks, Injector injector, Map<String, String> failed) {
        int injected = 0;
        for (String[] hook : hooks) {
            if (failed.containsKey(hook[0])) {
                return "could not retransform " + hook[0].replace('/', '.') + " for the " + kind + " hooks: "
                        + failed.get(hook[0]);
            }
            if (injector.found.contains(hook[0] + '.' + hook[1] + hook[2])) {
                injected++;
            }
        }
        return injected == hooks.size() ? null
                : "only " + injected + " of " + hooks.size() + " " + kind + " hooks were installed";
    }

    private static String firstNonNull(String... reasons) {
        for (String reason : reasons) {
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static Class<?> defineHolder(Instrumentation instrumentation) throws Exception {
        try {
            return Class.forName(HOLDER.replace('/', '.'), false, null);
        } catch (ClassNotFoundException notYet) {
            // Defined below.
        }
        ModuleLayer layer = bridgeLayer();
        Module bridge = layer.findModule(BRIDGE_MODULE).orElseThrow(IllegalStateException::new);
        instrumentation.redefineModule(Object.class.getModule(), Collections.<Module>emptySet(),
                Collections.<String, Set<Module>>emptyMap(),
                Collections.singletonMap("java.lang", Collections.singleton(bridge)),
                Collections.<Class<?>>emptySet(), Collections.<Class<?>, java.util.List<Class<?>>>emptyMap());
        Class<?> definer = layer.findLoader(BRIDGE_MODULE).loadClass(DEFINER.replace('/', '.'));
        return (Class<?>) definer.getMethod("define", byte[].class).invoke(null, (Object) holderBytes());
    }

    /** A module of one class whose only method defines a class into {@code java.lang}. */
    private static ModuleLayer bridgeLayer() {
        ModuleDescriptor descriptor = ModuleDescriptor.newModule(BRIDGE_MODULE)
                .exports(BRIDGE_PACKAGE.replace('/', '.'))
                .build();
        byte[] definer = definerBytes();
        ModuleReference reference = new ModuleReference(descriptor, null) {
            @Override
            public ModuleReader open() {
                return new ModuleReader() {
                    @Override
                    public Optional<URI> find(String name) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<InputStream> open(String name) {
                        return (DEFINER + ".class").equals(name)
                                ? Optional.<InputStream>of(new ByteArrayInputStream(definer))
                                : Optional.<InputStream>empty();
                    }

                    @Override
                    public Optional<ByteBuffer> read(String name) {
                        return (DEFINER + ".class").equals(name)
                                ? Optional.of(ByteBuffer.wrap(definer))
                                : Optional.<ByteBuffer>empty();
                    }

                    @Override
                    public Stream<String> list() {
                        return Stream.of(DEFINER + ".class");
                    }

                    @Override
                    public void close() {}
                };
            }
        };
        ModuleFinder finder = new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return BRIDGE_MODULE.equals(name) ? Optional.of(reference) : Optional.<ModuleReference>empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Collections.singleton(reference);
            }
        };
        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration()
                .resolve(finder, ModuleFinder.of(), Collections.singleton(BRIDGE_MODULE));
        return boot.defineModulesWithOneLoader(configuration, ReadHooks.class.getClassLoader());
    }

    /** {@code public static Class<?> define(byte[] b)}: a private lookup in Object, then defineClass. */
    private static byte[] definerBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, DEFINER,
                null, "java/lang/Object", null);
        MethodVisitor define = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "define",
                "([B)Ljava/lang/Class;", null, new String[] {"java/lang/Throwable"});
        define.visitCode();
        define.visitLdcInsn(Type.getObjectType("java/lang/Object"));
        define.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        define.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "privateLookupIn",
                "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;",
                false);
        define.visitVarInsn(Opcodes.ALOAD, 0);
        define.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "defineClass",
                "([B)Ljava/lang/Class;", false);
        define.visitInsn(Opcodes.ARETURN);
        define.visitMaxs(2, 1);
        define.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * {@code public final class java.lang.YoriwakeReadSink} with one {@code public static volatile
     * Consumer} per sink: {@code SINK, LOOKUP, DEFINE, PROCESS, NATIVE, LIBRARY}.
     */
    private static byte[] holderBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_SUPER,
                HOLDER, null, "java/lang/Object", null);
        for (String field : new String[] {"SINK", "LOOKUP", "DEFINE", "PROCESS", "NATIVE", "LIBRARY"}) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, field,
                    "L" + CONSUMER + ";", null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Prepends the sink call to each hooked method, and records which it found. */
    private static final class Injector implements ClassFileTransformer {
        final Set<String> found = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Owner, then name plus descriptor, to {slot, holder field}. */
        final Map<String, Map<String, String[]>> wanted = new HashMap<>();

        Injector(List<String[]> hooks) {
            for (String[] hook : hooks) {
                wanted.computeIfAbsent(hook[0], owner -> new HashMap<>())
                        .put(hook[1] + hook[2], new String[] {hook[3], hook[4]});
            }
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                ProtectionDomain domain, byte[] bytes) {
            Map<String, String[]> methods = redefined == null ? null : wanted.get(className);
            if (methods == null) {
                return null;
            }
            try {
                ClassReader reader = new ClassReader(bytes);
                // No COMPUTE_FRAMES: that would load classes from inside a transformer. The two
                // frames the injected code needs are written by hand.
                ClassWriter writer = new ClassWriter(reader, 0);
                Set<String> here = new HashSet<>();
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        String[] hook = methods.get(name + descriptor);
                        if (hook != null && (access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0) {
                            here.add(className + '.' + name + descriptor);
                            return new SinkCall(visitor, Integer.parseInt(hook[0]), hook[1]);
                        }
                        return visitor;
                    }
                }, 0);
                byte[] rewritten = writer.toByteArray();
                found.addAll(here);
                return rewritten;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * {@code Consumer s = SINK; if (s != null) s.accept(arg);} at the top of the method. In a
     * constructor this runs before {@code super()}, which is legal because it never uses
     * {@code this}.
     */
    private static final class SinkCall extends MethodVisitor {
        private final int slot;
        private final String field;

        SinkCall(MethodVisitor visitor, int slot, String field) {
            super(Opcodes.ASM9, visitor);
            this.slot = slot;
            this.field = field;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label call = new Label();
            Label done = new Label();
            super.visitFieldInsn(Opcodes.GETSTATIC, HOLDER, field, "L" + CONSUMER + ";");
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNONNULL, call);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(call);
            super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {CONSUMER});
            if (slot >= 0) {
                super.visitVarInsn(Opcodes.ALOAD, slot);
            } else if (slot == -2) {
                super.visitInsn(Opcodes.ACONST_NULL);
            } else {
                int first = slot == -3 ? 1 : 0;
                super.visitInsn(Opcodes.ICONST_2);
                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                super.visitInsn(Opcodes.DUP);
                super.visitInsn(Opcodes.ICONST_0);
                super.visitVarInsn(Opcodes.ALOAD, first);
                super.visitInsn(Opcodes.AASTORE);
                super.visitInsn(Opcodes.DUP);
                super.visitInsn(Opcodes.ICONST_1);
                super.visitVarInsn(Opcodes.ALOAD, first + 1);
                super.visitInsn(Opcodes.AASTORE);
            }
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONSUMER, "accept", "(Ljava/lang/Object;)V", true);
            super.visitLabel(done);
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 5, maxLocals);
        }
    }
}
