package io.github.zeuspizza.yoriwake.agent.capture;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;
import org.objectweb.asm.Opcodes;

/**
 * The newest class files the agent's ASM reads. A test JVM above it has JDK classes the read hooks
 * cannot be injected into, so what its tests read cannot all be seen.
 */
public final class ClassFileSupport {

    private static final Pattern VERSION_FIELD = Pattern.compile("V\\d+|V1_\\d+");

    private static final int CEILING = ceilingOf(Opcodes.class);

    private ClassFileSupport() {}

    /**
     * The highest class-file major version the agent's ASM reads: its largest {@code Opcodes.V*}
     * constant, so an ASM upgrade raises it with nothing written by hand. 0 when that cannot be read.
     */
    public static int asmCeiling() {
        return CEILING;
    }

    /**
     * The ceiling this JVM is judged against: the agent's, or a lower one {@link
     * AgentContract#AGENT_ASM_CEILING_PROPERTY} names.
     */
    public static int effectiveCeiling(String override) {
        if (override == null) {
            return CEILING;
        }
        try {
            return Math.min(CEILING, Integer.parseInt(override.trim()));
        } catch (NumberFormatException unreadable) {
            return CEILING;
        }
    }

    /**
     * Why a JVM of this {@code java.specification.version} is newer than the agent's ASM reads,
     * starting with the token; null when it is not, or when its version cannot be read.
     * {@code override} is {@link AgentContract#AGENT_ASM_CEILING_PROPERTY}'s value, or null.
     */
    public static String testJvmUnsupported(String specification, String override) {
        int feature = featureOf(specification);
        int ceiling = effectiveCeiling(override);
        if (feature < 0 || feature + 44 <= ceiling) {
            return null;
        }
        return AgentContract.TEST_JVM_UNSUPPORTED_KIND + ": this test JVM runs Java " + feature
                + "; the yoriwake agent's ASM reads up to Java " + (ceiling - 44)
                + ", so what its tests read cannot all be seen. Every test runs. A yoriwake whose ASM"
                + " reads Java " + feature + ", or a test JVM of Java " + (ceiling - 44)
                + " or older, lifts this.";
    }

    /**
     * The largest {@code V*} version constant of an ASM {@code Opcodes} class, masked to its major
     * version; 0 when it cannot be read. The plugin passes its own ASM's.
     */
    public static int ceilingOf(Class<?> opcodes) {
        try {
            int highest = 0;
            for (Field field : opcodes.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class
                        && VERSION_FIELD.matcher(field.getName()).matches()) {
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
            if (specification == null) {
                return -1;
            }
            String s = specification.trim();
            return Integer.parseInt(s.startsWith("1.") ? s.substring(2) : s);
        } catch (Throwable t) {
            return -1;
        }
    }
}
