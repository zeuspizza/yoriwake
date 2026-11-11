package io.github.zeuspizza.yoriwake.agent.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Every way a line number can reach Java code on this JDK is hooked, or written down here as
 * unreachable from Java with why. The candidates are found mechanically: every method and field of
 * the modules that build or hand out stack trace elements whose descriptor names one, and every
 * constructor of the element itself. A JDK that adds one fails this test until it is decided.
 */
class JdkStackScanTest {

    private static final String ELEMENT = "Ljava/lang/StackTraceElement;";

    private static final String[] MODULES = {"java.base", "java.management", "jdk.management", "jdk.jfr"};

    /** Why each member that is not hooked cannot hand a line number to Java code unhooked. */
    private static final Map<String, String> UNREACHABLE = new HashMap<>();

    static {
        String filled = "filled in natively for the elements a hooked method returns";
        String fromHooked = "reads elements a hooked method or constructor handed out";
        String built = "takes elements built elsewhere, where a hooked constructor or method saw them";
        String empty = "an empty sentinel, naming no class";
        String internal = "called only by a hooked method, whose return hands its result out";
        UNREACHABLE.put("java/lang/StackFrameInfo.ste" + ELEMENT, "the cache toStackTraceElement fills");
        UNREACHABLE.put("java/lang/StackTraceElement.initStackTraceElements([Ljava/lang/StackTraceElement;Ljava/lang/Object;I)V", filled);
        UNREACHABLE.put("java/lang/StackTraceElement.initStackTraceElements([Ljava/lang/StackTraceElement;Ljava/lang/Throwable;)V", filled);
        UNREACHABLE.put("java/lang/StackTraceElement.initStackTraceElement(Ljava/lang/StackTraceElement;Ljava/lang/StackFrameInfo;)V", filled);
        // Each fills in elements natively, called only by a hooked method that returns them.
        UNREACHABLE.put("java/lang/StackTraceElement.of(Ljava/lang/Object;I)[" + ELEMENT, internal);
        UNREACHABLE.put("java/lang/StackTraceElement.of(Ljava/lang/Throwable;I)[" + ELEMENT, internal);
        UNREACHABLE.put("java/lang/StackTraceElement.of(Ljava/lang/StackFrameInfo;)" + ELEMENT, internal);
        UNREACHABLE.put("java/lang/StackTraceElement.of([" + ELEMENT + ")[" + ELEMENT, internal);
        UNREACHABLE.put("java/lang/StackTraceElement.finishInit([Ljava/lang/StackTraceElement;)[Ljava/lang/StackTraceElement;", internal);
        UNREACHABLE.put("java/lang/System$1.finishInit([Ljava/lang/StackTraceElement;)V", internal);
        UNREACHABLE.put("jdk/internal/access/JavaLangAccess.finishInit([Ljava/lang/StackTraceElement;)V", internal);
        UNREACHABLE.put("java/lang/StackWalker$StackFrame.toStackTraceElement()" + ELEMENT,
                "abstract: StackFrameInfo implements it, hooked");
        UNREACHABLE.put("java/lang/Thread.EMPTY_STACK_TRACE[" + ELEMENT, empty);
        UNREACHABLE.put("java/lang/Thread.dumpThreads([Ljava/lang/Thread;)[[" + ELEMENT, internal);
        UNREACHABLE.put("java/lang/Thread.getStackTrace0()[" + ELEMENT, internal);
        UNREACHABLE.put("java/lang/Throwable$SentinelHolder.STACK_TRACE_ELEMENT_SENTINEL" + ELEMENT, empty);
        UNREACHABLE.put("java/lang/Throwable$SentinelHolder.STACK_TRACE_SENTINEL[" + ELEMENT, empty);
        UNREACHABLE.put("java/lang/Throwable.UNASSIGNED_STACK[" + ELEMENT, empty);
        UNREACHABLE.put("java/lang/Throwable.stackTrace[" + ELEMENT,
                "filled only by getOurStackTrace, hooked, or setStackTrace, which takes elements built elsewhere");
        UNREACHABLE.put("java/lang/Throwable.printEnclosedStackTrace(Ljava/lang/Throwable$PrintStreamOrWriter;["
                + ELEMENT + "Ljava/lang/String;Ljava/lang/String;Ljava/util/Set;)V", fromHooked);
        UNREACHABLE.put("java/lang/Throwable.getStackTrace()[" + ELEMENT, fromHooked);
        UNREACHABLE.put("java/lang/Throwable.setStackTrace([" + ELEMENT + ")V", built);
        UNREACHABLE.put("jdk/internal/vm/Continuation.lambda$getStackTrace$0(Ljava/util/stream/Stream;)[" + ELEMENT, internal);
        UNREACHABLE.put("jdk/internal/vm/Continuation.lambda$getStackTrace$1(I)[" + ELEMENT, internal);
        UNREACHABLE.put("jdk/internal/vm/Continuation.lambda$getStackTrace$0(I)[" + ELEMENT, internal);
        UNREACHABLE.put("jdk/internal/vm/Continuation.lambda$getStackTrace$1(Ljava/util/stream/Stream;)[" + ELEMENT, internal);
        UNREACHABLE.put("jdk/internal/vm/Continuation.getStackTrace()[" + ELEMENT,
                "the JVM refuses to retransform it; it builds its elements through StackFrameInfo, hooked");
        UNREACHABLE.put("jdk/internal/vm/TranslatedException.getMyStackTrace()[" + ELEMENT, fromHooked);
        UNREACHABLE.put("jdk/internal/vm/ThreadSnapshot.EMPTY_STACK[" + ELEMENT, empty);
        UNREACHABLE.put("jdk/internal/vm/ThreadSnapshot.stackTrace[" + ELEMENT, "read through stackTrace(), hooked");
        UNREACHABLE.put("java/lang/management/MonitorInfo.stackFrame" + ELEMENT, "set by the hooked constructor");
        UNREACHABLE.put("java/lang/management/MonitorInfo.getLockedStackFrame()" + ELEMENT, fromHooked);
        UNREACHABLE.put("java/lang/management/ThreadInfo.stackTrace[" + ELEMENT, "set by the hooked constructors");
        UNREACHABLE.put("java/lang/management/ThreadInfo.NO_STACK_TRACE[" + ELEMENT, empty);
        UNREACHABLE.put("java/lang/management/ThreadInfo.initialize(Ljava/lang/Thread;ILjava/lang/Object;Ljava/lang/Thread;"
                + "JJJJ[" + ELEMENT + "[Ljava/lang/management/MonitorInfo;[Ljava/lang/management/LockInfo;)V",
                "called only by the hooked constructors");
        UNREACHABLE.put("java/lang/management/ThreadInfo.getStackTrace()[" + ELEMENT, fromHooked);
        UNREACHABLE.put("sun/management/MonitorInfoCompositeData.getLockedStackFrame(Ljavax/management/openmbean/CompositeData;)"
                + ELEMENT, built);
        UNREACHABLE.put("sun/management/StackTraceElementCompositeData.ste" + ELEMENT, built);
        UNREACHABLE.put("sun/management/StackTraceElementCompositeData.<init>(" + ELEMENT + ")V", built);
        UNREACHABLE.put("sun/management/StackTraceElementCompositeData.getStackTraceElement()" + ELEMENT, built);
        UNREACHABLE.put("sun/management/StackTraceElementCompositeData.from(Ljavax/management/openmbean/CompositeData;)"
                + ELEMENT, built);
        UNREACHABLE.put("sun/management/StackTraceElementCompositeData.toCompositeData(" + ELEMENT
                + ")Ljavax/management/openmbean/CompositeData;", built);
        UNREACHABLE.put("sun/management/ThreadInfoCompositeData.stackTrace()[" + ELEMENT, built);
    }

    /** Every member of this JDK's image that could hand out a stack trace element. */
    private static List<String> candidates() throws IOException {
        List<String> found = new ArrayList<>();
        FileSystem image = FileSystems.getFileSystem(URI.create("jrt:/"));
        for (String module : MODULES) {
            Path root = image.getPath("/modules", module);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator) {
                    new ClassReader(Files.readAllBytes(file)).accept(new ClassVisitor(Opcodes.ASM9) {
                        private String owner;

                        @Override
                        public void visit(int version, int access, String name, String signature, String superName,
                                String[] interfaces) {
                            owner = name;
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                                Object value) {
                            if (descriptor.contains(ELEMENT)) {
                                found.add(owner + "." + name + descriptor);
                            }
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                String[] exceptions) {
                            boolean ownConstructor = "java/lang/StackTraceElement".equals(owner) && "<init>".equals(name)
                                    && (access & Opcodes.ACC_PRIVATE) == 0;
                            boolean frameLine = "jdk/jfr/consumer/RecordedFrame".equals(owner) && "getLineNumber".equals(name);
                            if (descriptor.contains(ELEMENT) || ownConstructor || frameLine) {
                                found.add(owner + "." + name + descriptor);
                            }
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("every member that hands out a stack trace element is hooked or known unreachable")
    void everyPathIsHooked() throws IOException {
        Set<String> hooked = new HashSet<>();
        for (String[] hook : ReadHooks.STACKS) {
            hooked.add(hook[0] + "." + hook[1] + hook[2]);
        }
        List<String> undecided = new ArrayList<>();
        for (String member : candidates()) {
            if (!hooked.contains(member) && !UNREACHABLE.containsKey(member)) {
                undecided.add(member);
            }
        }
        assertEquals(new ArrayList<String>(), undecided, "hook each, or write down why it hands no element to Java code");
    }
}
