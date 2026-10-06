package io.github.zeuspizza.yoriwake.agent.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What each JDK lets the judgment see, asserted on the JDK the test runs on. */
class JdkBranchTest {

    private static final int FEATURE = Runtime.version().feature();

    @Test
    @DisplayName("a class on the boot class path is not the JDK's own")
    void bootClassPath() {
        assertNull(standin.boot.BootCaller.class.getClassLoader());
        assertFalse(JdkCode.isJdk(standin.boot.BootCaller.class));
    }

    @Test
    @DisplayName("the JDK's own lambdas are the JDK's only where the JDK names their nest host")
    void lambdas() throws Throwable {
        Class<?> identity = Function.identity().getClass();
        Class<?> form = (Class<?>) MethodHandles.lookup().findStatic(JdkBranchTest.class, "lambdaFormAbove",
                MethodType.methodType(Class.class)).invokeWithArguments();
        if (FEATURE < 15) {
            assertThrows(NoSuchMethodException.class, () -> Class.class.getMethod("isHidden"));
            assertFalse(JdkCode.isJdk(identity), "a VM-anonymous class has no class file");
            assertFalse(JdkCode.isJdk(form), form.getName());
            assertFalse(JdkCode.isJdk(anonymousInObject()));
        } else {
            assertTrue(JdkCode.isJdk(identity));
            assertTrue(JdkCode.isJdk(form), form.getName());
        }
    }

    /** The first lambda form the JDK spun on the stack, named like a hidden class on every JDK. */
    private static Class<?> lambdaFormAbove() {
        return StackWalker.getInstance(EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE,
                StackWalker.Option.SHOW_HIDDEN_FRAMES)).walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(type -> type.getName().startsWith("java.lang.invoke.LambdaForm$")
                                && type.getName().indexOf('/') >= 0)
                        .findFirst().get());
    }

    @Test
    @DisplayName("a method-handle proxy and an interface proxy are passed over")
    void proxies() throws Exception {
        MethodHandle run = MethodHandles.lookup().findStatic(JdkBranchTest.class, "nothing",
                MethodType.methodType(void.class));
        Class<?> handleProxy = MethodHandleProxies.asInterfaceInstance(Runnable.class, run).getClass();
        if (FEATURE >= 22) {
            assertTrue(handleProxy.getName().startsWith("jdk.MHProxy"), handleProxy.getName());
        }
        assertTrue(JdkCode.callsForAnother(handleProxy), handleProxy.getName());

        Object proxy = Proxy.newProxyInstance(JdkBranchTest.class.getClassLoader(), new Class<?>[] {Runnable.class},
                (self, method, arguments) -> null);
        assertTrue(JdkCode.callsForAnother(proxy.getClass()), proxy.getClass().getName());
    }

    private static void nothing() {}

    /** A VM-anonymous class hosted by Object, which takes Object's loader, module and package. */
    private static Class<?> anonymousInObject() throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method define = unsafe.getClass().getMethod("defineAnonymousClass", Class.class, byte[].class,
                Object[].class);
        Class<?> anonymous = (Class<?>) define.invoke(unsafe, Object.class,
                ImageFixtures.classBytes("java/lang/YoriwakeAnonymous"), null);
        assertNull(anonymous.getClassLoader());
        return anonymous;
    }
}
