package io.github.zeuspizza.yoriwake.agent.capture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The guards of conditional reviewed libraries. A library whose native code is safe unless a call
 * turns a feature on is reviewed on that condition: the call is wrapped, so it reports its argument
 * before the native runs, and the library's load counts as reviewed only while the wrap is in place
 * in the loader that loads it.
 *
 * <p>A native method has no body to add a call to. The JVM's native-method prefix lets a class
 * rename it, keep a Java method of the original name that reports and then calls the renamed one,
 * and still link the renamed one to the original native symbol. That needs the agent's manifest to
 * allow it, and is only done as each guard class is first defined.
 */
final class GuardHooks {

    /** What the JVM strips from a native's name to find its symbol. */
    static final String PREFIX = "$yoriwake$";

    /** Guard class, its native method and descriptor, and what turning it on means. */
    static final String[][] NATIVE_WRAPS = {
        {"org/sqlite/core/NativeDB", "enable_load_extension", "(Z)I",
            "SQLite extension loading was enabled on a connection"},
    };

    /**
     * Guard class, a Java method whose first argument names an operation, its descriptor, what a
     * report means, and the operations it may be given unreported. Checked as the method is entered.
     */
    static final String[][] ENTRY_CHECKS = {
        // Netty's only writer of a submission queue entry, and the opcodes its own code submits.
        {"io/netty/channel/uring/SubmissionQueue", "enqueueSqe", "(BBSIJJIIJSSIJ)J",
            "Netty io_uring was given an operation outside its own: opcode ",
            "0,2,6,9,10,11,13,14,15,16,19,22,23,26,27,30,34,47,48"},
    };

    /** Why no guard can be armed in this JVM; null once the transformer is in place. */
    private volatile String disarmed;
    /** Finds a guard class already defined in a loader; null where no agent installed the guards. */
    private volatile Instrumentation instrumentation;
    /** Each loader's guard classes defined with their wrap in place. */
    private final Map<ClassLoader, Set<String>> armed = Collections.synchronizedMap(new WeakHashMap<>());
    /** The bootstrap loader is null, which a weak map cannot key. */
    private final Set<String> armedInBoot = Collections.synchronizedSet(new HashSet<>());

    GuardHooks(String disarmed) {
        this.disarmed = disarmed;
    }

    /** Guards that are never armed, for a recorder no agent installed. */
    static GuardHooks none(String why) {
        return new GuardHooks(why);
    }

    /**
     * Registers the wrapping transformer and the guard sink. {@code failures} gets the reason when a
     * guard class is defined without its wrap, which hides every later call from the guard.
     */
    static GuardHooks install(Instrumentation instrumentation, Consumer<Object> guard, Consumer<Object> failures) {
        if (!instrumentation.isNativeMethodPrefixSupported()) {
            return new GuardHooks("this JVM cannot wrap a native method");
        }
        GuardHooks hooks = new GuardHooks(null);
        hooks.instrumentation = instrumentation;
        Wrapper wrapper = null;
        try {
            Class.forName(ReadHooks.HOLDER.replace('/', '.'), false, null).getField("GUARD").set(null, guard);
            wrapper = hooks.new Wrapper(ReadHooks.HOLDER, failures);
            instrumentation.addTransformer(wrapper, true);
            instrumentation.setNativeMethodPrefix(wrapper, PREFIX);
        } catch (Throwable t) {
            // A wrap with no prefix to resolve it would leave every guarded native unlinked.
            if (wrapper != null) {
                instrumentation.removeTransformer(wrapper);
            }
            hooks.disarmed = "the native-method guards could not be installed: " + t;
        }
        return hooks;
    }

    /**
     * Null when the guard of {@code wrap} holds for a library {@code caller} loads, or why not: the
     * guard class in the caller's loader is already defined with its wrap, or not defined yet but
     * its class file there declares the native, so the wrap is applied when it is defined.
     */
    String unarmed(Class<?> caller, String[] wrap) {
        if (disarmed != null) {
            return disarmed;
        }
        ClassLoader loader = caller.getClassLoader();
        if (armedIn(loader, wrap[0])) {
            return null;
        }
        Class<?> defined = definedIn(loader, wrap[0]);
        if (defined != null) {
            // Defined already, so its class file says nothing about the bytes the JVM holds.
            return armedIn(defined.getClassLoader(), wrap[0]) ? null
                    : wrap[0].replace('/', '.') + " was defined without its guard";
        }
        String file = wrap[0] + ".class";
        try (InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(file)
                : loader.getResourceAsStream(file)) {
            if (in == null) {
                return wrap[0].replace('/', '.') + " is not found beside " + caller.getName();
            }
            if (!declares(new ClassReader(in), wrap)) {
                return wrap[0].replace('/', '.') + " declares no " + kind(wrap) + " " + wrap[1] + wrap[2];
            }
            return null;
        } catch (Throwable t) {
            return wrap[0].replace('/', '.') + " could not be read: " + t;
        }
    }

    /**
     * Null when the library's version, read from {@code pom} in the code source that holds the
     * guard class beside {@code caller}, is one of {@code versions}; otherwise why not.
     */
    static String unlisted(Class<?> caller, String guardClass, String pom, String[] versions) {
        ClassLoader loader = caller.getClassLoader();
        String file = guardClass + ".class";
        try {
            URL url = loader == null ? ClassLoader.getSystemResource(file) : loader.getResource(file);
            if (url == null) {
                return guardClass.replace('/', '.') + " is not found beside " + caller.getName();
            }
            String spec = url.toString();
            Properties properties = new Properties();
            try (InputStream in = new URL(spec.substring(0, spec.length() - file.length()) + pom).openStream()) {
                properties.load(in);
            }
            String version = properties.getProperty("version");
            for (String listed : versions) {
                if (listed.equals(version)) {
                    return null;
                }
            }
            return "its version " + version + " is not one reviewed";
        } catch (Throwable t) {
            return "its version could not be read: " + t;
        }
    }

    /** The class of that internal name the loader already holds, or null when none or unknown. */
    private Class<?> definedIn(ClassLoader loader, String guardClass) {
        Instrumentation held = instrumentation;
        if (held == null) {
            return null;
        }
        String name = guardClass.replace('/', '.');
        for (Class<?> c : held.getInitiatedClasses(loader)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private boolean armedIn(ClassLoader loader, String guardClass) {
        if (loader == null) {
            return armedInBoot.contains(guardClass);
        }
        Set<String> classes = armed.get(loader);
        return classes != null && classes.contains(guardClass);
    }

    private void arm(ClassLoader loader, String guardClass) {
        if (loader == null) {
            armedInBoot.add(guardClass);
        } else {
            armed.computeIfAbsent(loader, l -> Collections.synchronizedSet(new HashSet<>())).add(guardClass);
        }
    }

    /** What {@code wrap} rewrites: a native for a wrap, a method with a body for an entry check. */
    private static String kind(String[] wrap) {
        return wrap.length == 4 ? "native" : "method";
    }

    /** Whether the class declares the method {@code wrap} rewrites, native or with a body as it needs. */
    private static boolean declares(ClassReader reader, String[] wrap) {
        boolean[] found = {false};
        boolean nativeWanted = wrap.length == 4;
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                boolean bodied = (access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0;
                if ((nativeWanted ? (access & Opcodes.ACC_NATIVE) != 0 : bodied)
                        && name.equals(wrap[1]) && descriptor.equals(wrap[2])) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /** The wrap or entry check of the guard class named {@code className}, or null. */
    static String[] wrapFor(String className) {
        for (String[][] table : new String[][][] {NATIVE_WRAPS, ENTRY_CHECKS}) {
            for (String[] wrap : table) {
                if (wrap[0].equals(className)) {
                    return wrap;
                }
            }
        }
        return null;
    }

    /**
     * {@code bytes} with the native of {@code wrap} renamed under {@link #PREFIX}, private and
     * synthetic so no lookup of public methods reaches it, and a method of the original name and
     * access that reports its argument and then calls it. Throws when the class has no such native.
     */
    static byte[] wrapped(byte[] bytes, String[] wrap, String holder) {
        if (wrap.length == 5) {
            return checked(bytes, wrap, holder);
        }
        ClassReader reader = new ClassReader(bytes);
        // No COMPUTE_FRAMES: that would load classes from inside a transformer. The two frames the
        // wrapper needs are written by hand.
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] found = {false};
        String[] owner = {null};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                owner[0] = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if ((access & Opcodes.ACC_NATIVE) == 0 || !name.equals(wrap[1]) || !descriptor.equals(wrap[2])) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
                found[0] = true;
                int hidden = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                        | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
                super.visitMethod(hidden, PREFIX + name, descriptor, signature, exceptions).visitEnd();
                MethodVisitor body = super.visitMethod(access & ~Opcodes.ACC_NATIVE, name, descriptor, signature,
                        exceptions);
                writeWrapper(body, holder, owner[0], access, name, descriptor, wrap[3]);
                // The original native is replaced, not kept.
                return null;
            }
        }, 0);
        if (!found[0]) {
            throw new IllegalStateException(wrap[0].replace('/', '.') + " declares no native " + wrap[1] + wrap[2]);
        }
        return writer.toByteArray();
    }

    /**
     * {@code Consumer s = GUARD; if (s != null) s.accept(new Object[] {why, Boolean.valueOf(on)});}
     * then the renamed native, for a {@code (Z)I} method, static or not.
     */
    private static void writeWrapper(MethodVisitor body, String holder, String owner, int access, String name,
            String descriptor, String why) {
        String consumer = "java/util/function/Consumer";
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        int argument = isStatic ? 0 : 1;
        body.visitCode();
        Label call = new Label();
        Label done = new Label();
        body.visitFieldInsn(Opcodes.GETSTATIC, holder, "GUARD", "L" + consumer + ";");
        body.visitInsn(Opcodes.DUP);
        body.visitJumpInsn(Opcodes.IFNONNULL, call);
        body.visitInsn(Opcodes.POP);
        body.visitJumpInsn(Opcodes.GOTO, done);
        body.visitLabel(call);
        body.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {consumer});
        body.visitInsn(Opcodes.ICONST_2);
        body.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        body.visitInsn(Opcodes.DUP);
        body.visitInsn(Opcodes.ICONST_0);
        body.visitLdcInsn(why);
        body.visitInsn(Opcodes.AASTORE);
        body.visitInsn(Opcodes.DUP);
        body.visitInsn(Opcodes.ICONST_1);
        body.visitVarInsn(Opcodes.ILOAD, argument);
        body.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        body.visitInsn(Opcodes.AASTORE);
        body.visitMethodInsn(Opcodes.INVOKEINTERFACE, consumer, "accept", "(Ljava/lang/Object;)V", true);
        body.visitLabel(done);
        body.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        if (isStatic) {
            body.visitVarInsn(Opcodes.ILOAD, 0);
            body.visitMethodInsn(Opcodes.INVOKESTATIC, owner, PREFIX + name, descriptor, false);
        } else {
            body.visitVarInsn(Opcodes.ALOAD, 0);
            body.visitVarInsn(Opcodes.ILOAD, 1);
            body.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, PREFIX + name, descriptor, false);
        }
        body.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));
        body.visitMaxs(6, argument + 1);
        body.visitEnd();
    }

    /**
     * {@code bytes} with the method of {@code check} entered through a test of its first argument:
     * one of the operations listed goes on unreported, any other reports before the body runs.
     * Throws when the class has no such method with a body.
     */
    static byte[] checked(byte[] bytes, String[] check, String holder) {
        String[] listed = check[4].split(",");
        int[] allowed = new int[listed.length];
        for (int i = 0; i < listed.length; i++) {
            allowed[i] = Integer.parseInt(listed[i]);
        }
        Arrays.sort(allowed);
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ((access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0 || !name.equals(check[1])
                        || !descriptor.equals(check[2])) {
                    return next;
                }
                found[0] = true;
                int argument = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        writeCheck(mv, holder, argument, check[3], allowed);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(Math.max(maxStack, 6), maxLocals);
                    }
                };
            }
        }, 0);
        if (!found[0]) {
            throw new IllegalStateException(check[0].replace('/', '.') + " declares no method " + check[1] + check[2]);
        }
        return writer.toByteArray();
    }

    /**
     * {@code switch (op & 0xFF) { case allowed...: break; default: Consumer s = GUARD; if (s != null)
     * s.accept(new Object[] {why, Boolean.TRUE, Integer.valueOf(op & 0xFF)}); }} ahead of the body.
     * An allowed operation costs one switch and no call.
     */
    private static void writeCheck(MethodVisitor body, String holder, int argument, String why, int[] allowed) {
        String consumer = "java/util/function/Consumer";
        Label report = new Label();
        Label absent = new Label();
        Label proceed = new Label();
        Label[] targets = new Label[allowed.length];
        Arrays.fill(targets, proceed);
        body.visitVarInsn(Opcodes.ILOAD, argument);
        body.visitIntInsn(Opcodes.SIPUSH, 0xFF);
        body.visitInsn(Opcodes.IAND);
        body.visitLookupSwitchInsn(report, allowed, targets);
        body.visitLabel(report);
        body.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        body.visitFieldInsn(Opcodes.GETSTATIC, holder, "GUARD", "L" + consumer + ";");
        body.visitInsn(Opcodes.DUP);
        body.visitJumpInsn(Opcodes.IFNULL, absent);
        body.visitInsn(Opcodes.ICONST_3);
        body.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        body.visitInsn(Opcodes.DUP);
        body.visitInsn(Opcodes.ICONST_0);
        body.visitLdcInsn(why);
        body.visitInsn(Opcodes.AASTORE);
        body.visitInsn(Opcodes.DUP);
        body.visitInsn(Opcodes.ICONST_1);
        body.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
        body.visitInsn(Opcodes.AASTORE);
        body.visitInsn(Opcodes.DUP);
        body.visitInsn(Opcodes.ICONST_2);
        body.visitVarInsn(Opcodes.ILOAD, argument);
        body.visitIntInsn(Opcodes.SIPUSH, 0xFF);
        body.visitInsn(Opcodes.IAND);
        body.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        body.visitInsn(Opcodes.AASTORE);
        body.visitMethodInsn(Opcodes.INVOKEINTERFACE, consumer, "accept", "(Ljava/lang/Object;)V", true);
        body.visitJumpInsn(Opcodes.GOTO, proceed);
        body.visitLabel(absent);
        body.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {consumer});
        body.visitInsn(Opcodes.POP);
        body.visitLabel(proceed);
        body.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // The body's own first frame, if it has one, must not share this offset.
        body.visitInsn(Opcodes.NOP);
    }

    /** Wraps each guard class as it is defined, in every loader, and on every retransformation. */
    final class Wrapper implements ClassFileTransformer {
        /** The class whose {@code GUARD} field the wrappers call: the read hooks' holder, or a test's. */
        private final String holder;
        private final Consumer<Object> failures;

        Wrapper(String holder, Consumer<Object> failures) {
            this.holder = holder;
            this.failures = failures;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                byte[] bytes) {
            String[] wrap = className == null ? null : wrapFor(className);
            if (wrap == null) {
                return null;
            }
            try {
                byte[] rewritten = wrapped(bytes, wrap, holder);
                arm(loader, className);
                return rewritten;
            } catch (Throwable t) {
                failures.accept(className.replace('/', '.') + " was defined without its guard: " + t);
                return null;
            }
        }
    }
}
