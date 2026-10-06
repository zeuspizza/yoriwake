package standin.boot;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Code on the boot class path: defined by the JDK's own loader, from a class file the runtime image
 * does not hold.
 */
public final class BootCaller {

    private BootCaller() {}

    /** Hands {@code value} to {@code sink} through JDK code, so this class is that code's caller. */
    public static void call(Consumer<Object> sink, Object value) {
        Optional.of(value).ifPresent(sink);
    }
}
