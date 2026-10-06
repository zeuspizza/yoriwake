package io.github.zeuspizza.yoriwake.agent.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Runs in a JVM that opens java.lang to the class path and leaves the image as shipped. */
class JdkNestTest {

    @Test
    @DisplayName("a hidden class defined from outside the JDK into a JDK nest is not the JDK's own")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nest() throws Throwable {
        assumeTrue(Runtime.version().feature() >= 15, "hidden classes arrived in JDK 15");
        // From the class path, a private lookup in java.lang lacks the module access a hidden
        // class needs; a class defined into java.lang has it.
        Class<?> trampoline = MethodHandles.privateLookupIn(Object.class, MethodHandles.lookup())
                .defineClass(ImageFixtures.trampolineBytes("java/lang/YoriwakeTrampoline"));
        MethodHandles.Lookup inObject = (MethodHandles.Lookup) trampoline.getMethod("lookup").invoke(null);

        byte[] nestmate = ImageFixtures.classBytes("java/lang/YoriwakeNestmate");
        new TouchRecorder().defined(nestmate);
        Class<?> option = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
        Object options = Array.newInstance(option, 1);
        Array.set(options, 0, Enum.valueOf((Class) option, "NESTMATE"));
        Method define = MethodHandles.Lookup.class.getMethod("defineHiddenClass", byte[].class, boolean.class,
                options.getClass());
        Class<?> hidden = ((MethodHandles.Lookup) define.invoke(inObject, nestmate, true, options)).lookupClass();

        assertSame(Object.class, hidden.getNestHost());
        assertFalse(JdkCode.isJdk(hidden));
    }

    @Test
    @DisplayName("an image neither patched nor upgraded is observed as complete")
    void complete() {
        assertNull(JdkCode.imageAltered());
    }
}
