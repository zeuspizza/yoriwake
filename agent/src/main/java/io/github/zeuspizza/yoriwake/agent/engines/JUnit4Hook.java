package io.github.zeuspizza.yoriwake.agent.engines;

import io.github.zeuspizza.yoriwake.agent.host.GradleHost;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Gives plain JUnit 4 the per-test boundaries the JUnit Platform provides.
 *
 * <p>Without {@code useJUnitPlatform()}, Gradle runs its own JUnit 4 runner and the
 * {@code ServiceLoader}-discovered listener is never loaded. JUnit 4 exposes no hook either: the
 * {@code RunNotifier} is Gradle's and never handed out. So the notifier is rewritten to call
 * {@link JUnit4Events} at the head of its fire methods.
 *
 * <p>Not a generated {@code RunListener}: it is a class, so it cannot be proxied, and this agent
 * cannot compile against JUnit. The injected calls pass the {@code Description} as {@code Object}.
 */
public final class JUnit4Hook {

    private static final String NOTIFIER = "org/junit/runner/notification/RunNotifier";
    private static final String HOOK = "io/github/zeuspizza/yoriwake/agent/engines/JUnit4Events";

    private JUnit4Hook() {}

    /**
     * Installs the transformer.
     *
     * <p>Wrapped whole: a failure here must cost a map, never the host's build.
     */
    public static void install(Instrumentation instrumentation) {
        try {
            // Loaded now rather than from inside the transformer, which would load it mid-transform.
            GradleHost.isJUnit4Processor("");
            instrumentation.addTransformer(new Transformer());
        } catch (Throwable ignored) {
        }
    }

    private static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                ProtectionDomain domain, byte[] bytes) {
            if (NOTIFIER.equals(name)) {
                try {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, 0);
                    reader.accept(new NotifierVisitor(writer), 0);
                    return writer.toByteArray();
                } catch (Throwable ignored) {
                    // Returning null leaves the class exactly as it was.
                    return null;
                }
            }
            if (GradleHost.isJUnit4Processor(name)) {
                return rewriteProcessor(bytes);
            }
            return null;
        }
    }

    /** Null, leaving the class as it was, when it cannot be rewritten: no marker, so no dating. */
    static byte[] rewriteProcessor(byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return "stop".equals(name) && "()V".equals(descriptor) ? new MarkRunFinished(visitor) : visitor;
                }
            }, 0);
            return writer.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Emits {@code try { System.setProperty(GradleHost.RUN_FINISHED_PROPERTY, "true"); } catch (Throwable t) {}}
     * at the head of the method.
     *
     * <p>Caught because a throw inside Gradle's worker (say, a security manager a test left
     * installed) would fail the build. Frames are written by hand, since computing them would load
     * classes from inside a transformer; both carry the entry locals, so the original frames that
     * follow, each a delta, still mean what they meant.
     */
    private static final class MarkRunFinished extends MethodVisitor {
        MarkRunFinished(MethodVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            Label resume = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitLdcInsn(GradleHost.RUN_FINISHED_PROPERTY);
            super.visitLdcInsn("true");
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, resume);
            super.visitLabel(handler);
            super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
            super.visitInsn(Opcodes.POP);
            super.visitLabel(resume);
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(Math.max(maxStack, 2), maxLocals);
        }
    }

    private static final class NotifierVisitor extends ClassVisitor {
        private boolean framed;

        NotifierVisitor(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            // JUnit 4 itself ships as Java 5 class files, which carry no frames and may not be given any.
            framed = (version & 0xFFFF) >= Opcodes.V1_6;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            String hook = hookFor(name, descriptor);
            return hook == null ? visitor : new CallOnEntry(visitor, hook, framed);
        }

        /** The three notifications that bound a test and say how it ended. */
        private static String hookFor(String name, String descriptor) {
            if ("fireTestStarted".equals(name) && "(Lorg/junit/runner/Description;)V".equals(descriptor)) {
                return "started";
            }
            if ("fireTestFinished".equals(name) && "(Lorg/junit/runner/Description;)V".equals(descriptor)) {
                return "finished";
            }
            if ("fireTestFailure".equals(name) && "(Lorg/junit/runner/notification/Failure;)V".equals(descriptor)) {
                return "failed";
            }
            // An assumption failure means the test did not really run. Recorded as a failure so an
            // env-gated test with near-empty coverage is never deselected as passed.
            if ("fireTestAssumptionFailed".equals(name)
                    && "(Lorg/junit/runner/notification/Failure;)V".equals(descriptor)) {
                return "failed";
            }
            return null;
        }
    }

    /**
     * Emits {@code try { JUnit4Events.<hook>(arg0); } catch (Throwable t) {}} before anything else in
     * the method.
     *
     * <p>Caught here too, because a notifier loaded where this agent is not visible fails to link
     * the call, and that {@code NoClassDefFoundError} would fail the test being notified. Frames by
     * hand, as in {@link MarkRunFinished}.
     */
    private static final class CallOnEntry extends MethodVisitor {
        private final String hook;
        private final boolean framed;

        CallOnEntry(MethodVisitor next, String hook, boolean framed) {
            super(Opcodes.ASM9, next);
            this.hook = hook;
            this.framed = framed;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            Label resume = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, hook, "(Ljava/lang/Object;)V", false);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, resume);
            super.visitLabel(handler);
            if (framed) {
                super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
            }
            super.visitInsn(Opcodes.POP);
            super.visitLabel(resume);
            if (framed) {
                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // An empty method's own max stack is zero, and the argument pushed above needs one.
            super.visitMaxs(Math.max(maxStack, 1), maxLocals);
        }
    }
}
