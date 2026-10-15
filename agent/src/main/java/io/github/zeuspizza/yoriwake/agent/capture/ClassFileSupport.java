package io.github.zeuspizza.yoriwake.agent.capture;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.objectweb.asm.Opcodes;

/**
 * The newest class files the agent's ASM reads. A test JVM above it has JDK classes the read hooks
 * cannot be injected into, so what its tests read cannot all be seen.
 */
public final class ClassFileSupport {

    private static final int CEILING = ceilingOf(Opcodes.class);

    private ClassFileSupport() {}

    /**
     * The highest class-file major version the agent's ASM reads: its largest {@code Opcodes.V*}
     * constant, so an ASM upgrade raises it with nothing written by hand. 0 when that cannot be read.
     */
    public static int asmCeiling() {
        return CEILING;
    }

    static int ceilingOf(Class<?> opcodes) {
        try {
            int highest = 0;
            for (Field field : opcodes.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class
                        && field.getName().matches("V\\d+|V1_\\d+")) {
                    highest = Math.max(highest, field.getInt(null) & 0xFFFF);
                }
            }
            return highest;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The feature version a {@code java.specification.version} names, in its {@code 1.8} or
     * {@code 21} form; -1 when it names none. Parsed rather than asked of {@code Runtime.version()},
     * which Java 8 lacks.
     */
    public static int featureOf(String specification) {
        try {
            String s = specification.trim();
            return Integer.parseInt(s.startsWith("1.") ? s.substring(2) : s);
        } catch (Throwable t) {
            return -1;
        }
    }
}
