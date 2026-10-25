package io.github.zeuspizza.yoriwake.agent.engines;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
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
import org.objectweb.asm.Type;

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

    /**
     * Installs the rewrite of Gradle's JUnit 4 executor that asks {@link JUnit4Selection} whether to
     * leave each class out. Only on a run that narrows plain JUnit 4, so any other run keeps Gradle's
     * bytecode. Wrapped whole, as {@link #install}.
     */
    public static void installExecutorRewrite(Instrumentation instrumentation) {
        try {
            JUnit4Selection.activate();
            // Loaded now rather than from inside the transformer, which would load them mid-transform.
            GradleHost.junit4Executor("");
            JUnit4Selection.executorRewritten(null);
            instrumentation.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                        ProtectionDomain domain, byte[] bytes) {
                    String[] method = name == null ? null : GradleHost.junit4Executor(name);
                    if (method == null) {
                        return null;
                    }
                    byte[] rewritten = rewriteExecutor(bytes, method[0], method[1]);
                    JUnit4Selection.executorRewritten(rewritten != null
                            ? AgentContract.PLAIN_FILTER_HOOKED : AgentContract.PLAIN_FILTER_UNHOOKED);
                    return rewritten;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * {@code bytes} with {@code try { if (leaveOut(runner)) return; } catch (Throwable t) {}} placed
     * where Gradle's executor resumes after its own "every test filtered" return, so a class left out
     * takes the same way out; null, leaving the class as it was, when the method does not have that
     * shape. The runner is the local Gradle stores {@code Request.getRunner()}'s result in.
     */
    static byte[] rewriteExecutor(byte[] bytes, String methodName, String methodDescriptor) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            boolean[] done = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                private boolean framed;

                @Override
                public void visit(int version, int access, String name, String signature, String superName,
                        String[] interfaces) {
                    framed = (version & 0xFFFF) >= Opcodes.V1_6;
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!methodName.equals(name) || !methodDescriptor.equals(descriptor)) {
                        return visitor;
                    }
                    return new LeaveOutAfterFilters(visitor, Type.getReturnType(descriptor), framed, done);
                }
            }, 0);
            return done[0] ? writer.toByteArray() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Follows the executor's method to Gradle's {@code allTestsFiltered} check, its jump and its
     * return, and at the label that jump resumes at, before the next instruction, asks the agent.
     * Frames by hand, as in {@link MarkRunFinished}: both carry the locals of the frame at that
     * label, so the original frames that follow still mean what they meant.
     */
    private static final class LeaveOutAfterFilters extends MethodVisitor {
        private static final String SELECTION = "io.github.zeuspizza.yoriwake.agent.engines.JUnit4Selection";

        private final Type returnType;
        private final boolean framed;
        private final boolean[] done;
        private boolean afterGetRunner;
        private int runner = -1;
        /** 0 before the check, 1 after it, 2 after its jump, 3 after the return, 4 at the label. */
        private int state;
        private Label resumeAt;

        LeaveOutAfterFilters(MethodVisitor next, Type returnType, boolean framed, boolean[] done) {
            super(Opcodes.ASM9, next);
            this.returnType = returnType;
            this.framed = framed;
            this.done = done;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            before();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            afterGetRunner = opcode == Opcodes.INVOKEVIRTUAL && "org/junit/runner/Request".equals(owner)
                    && "getRunner".equals(name);
            if (state == 0 && GradleHost.JUNIT4_ALL_FILTERED.equals(name)
                    && "(Lorg/junit/runner/Runner;Ljava/util/List;)Z".equals(descriptor)) {
                state = 1;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            before();
            super.visitVarInsn(opcode, var);
            if (afterGetRunner && opcode == Opcodes.ASTORE && runner < 0) {
                runner = var;
            }
            afterGetRunner = false;
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            before();
            super.visitJumpInsn(opcode, label);
            afterGetRunner = false;
            if (state == 1 && opcode == Opcodes.IFEQ) {
                resumeAt = label;
                state = 2;
            } else if (state == 1) {
                state = 5;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            before();
            super.visitInsn(opcode);
            afterGetRunner = false;
            if (state == 2 && opcode == returnType.getOpcode(Opcodes.IRETURN)) {
                state = 3;
            } else if (state == 2 && opcode != Opcodes.ACONST_NULL) {
                state = 5;
            }
        }

        @Override
        public void visitLabel(Label label) {
            super.visitLabel(label);
            if (state == 3 && label == resumeAt) {
                state = 4;
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            before();
            afterGetRunner = false;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            before();
            afterGetRunner = false;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            before();
            afterGetRunner = false;
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle handle,
                Object... arguments) {
            before();
            afterGetRunner = false;
            super.visitInvokeDynamicInsn(name, descriptor, handle, arguments);
        }

        @Override
        public void visitLdcInsn(Object value) {
            before();
            afterGetRunner = false;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            before();
            afterGetRunner = false;
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            before();
            afterGetRunner = false;
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            before();
            afterGetRunner = false;
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            before();
            afterGetRunner = false;
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(Math.max(maxStack, 6), maxLocals);
        }

        /** At the resume label, before its first instruction: the call, once. */
        private void before() {
            if (state != 4) {
                return;
            }
            state = 5;
            if (runner < 0) {
                return;
            }
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            Label resume = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            // Class.forName(SELECTION, true, ClassLoader.getSystemClassLoader())
            super.visitLdcInsn(SELECTION);
            super.visitInsn(Opcodes.ICONST_1);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader",
                    "()Ljava/lang/ClassLoader;", false);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
            // .getMethod("leaveOut", new Class[] {Object.class})
            super.visitLdcInsn("leaveOut");
            super.visitInsn(Opcodes.ICONST_1);
            super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
            super.visitInsn(Opcodes.DUP);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
            super.visitInsn(Opcodes.AASTORE);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
            // .invoke(null, new Object[] {runner})
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitInsn(Opcodes.ICONST_1);
            super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            super.visitInsn(Opcodes.DUP);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ALOAD, runner);
            super.visitInsn(Opcodes.AASTORE);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            // Boolean.TRUE.equals(result)
            super.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
            super.visitInsn(Opcodes.SWAP);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "equals", "(Ljava/lang/Object;)Z",
                    false);
            super.visitJumpInsn(Opcodes.IFEQ, resume);
            // The way out Gradle takes for a class its filters emptied.
            if (returnType.getSort() == Type.VOID) {
                super.visitInsn(Opcodes.RETURN);
            } else {
                super.visitInsn(Opcodes.ACONST_NULL);
                super.visitInsn(Opcodes.ARETURN);
            }
            super.visitLabel(end);
            super.visitLabel(handler);
            if (framed) {
                super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
            }
            super.visitInsn(Opcodes.POP);
            super.visitLabel(resume);
            if (framed) {
                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }
            done[0] = true;
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
