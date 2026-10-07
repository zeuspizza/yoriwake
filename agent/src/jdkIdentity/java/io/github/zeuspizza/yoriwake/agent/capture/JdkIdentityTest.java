package io.github.zeuspizza.yoriwake.agent.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Runs in a JVM that patches java.base and upgrades java.compiler. */
class JdkIdentityTest {

    @Test
    @DisplayName("a class patched into java.base is not the JDK's own")
    void patched() throws Exception {
        Class<?> patched = Class.forName("java.util.YoriwakePatched");
        assertNull(patched.getClassLoader());
        assertEquals("java.base", patched.getModule().getName());
        assertFalse(JdkCode.isJdk(patched));
        assertTrue(JdkCode.isJdk(ArrayList.class), "the module's other classes are still the image's");
    }

    @Test
    @DisplayName("a class of an upgraded image module is not the JDK's own")
    void upgraded() throws Exception {
        Class<?> upgraded = Class.forName("javax.tools.YoriwakeUpgraded");
        assertSame(ClassLoader.getPlatformClassLoader(), upgraded.getClassLoader());
        assertEquals("java.compiler", upgraded.getModule().getName());
        assertFalse(JdkCode.isJdk(upgraded));
        assertFalse(JdkCode.isJdk(javax.tools.ToolProvider.class), "every class of the module comes from the upgrade");
    }

    @Test
    @DisplayName("a patched image is read from this JVM's own arguments")
    void altered() {
        String altered = JdkCode.imageAltered();
        assertTrue(altered != null && altered.startsWith("an image module is patched: --patch-module=java.base="),
                String.valueOf(altered));
    }
}
