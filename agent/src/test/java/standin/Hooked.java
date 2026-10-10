package standin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Stands where a hooked JDK method stands in a real call: outside the agent's packages, between
 * the agent's sink and whoever called the hooked method.
 */
public final class Hooked {

    private Hooked() {}

    public static void call(Runnable sink) {
        sink.run();
    }

    public static void callAll(Runnable... sinks) {
        for (Runnable sink : sinks) {
            sink.run();
        }
    }

    /** A method reference to the hooked method, handed to JDK code that then makes the call. */
    public static void referencedThroughTheJdk(Runnable sink) {
        Optional.of(sink).ifPresent(Hooked::call);
    }

    /** Callers outside the stand-in, so the stand-in's own frames cannot pass for the caller. */
    public static final class Caller {

        private Caller() {}

        public static void invokeExact(Runnable sink) throws Throwable {
            MethodHandle call = MethodHandles.lookup().findStatic(Hooked.class, "call",
                    MethodType.methodType(void.class, Runnable.class));
            call.invokeExact(sink);
        }

        public static void varargs(Runnable sink) throws Throwable {
            MethodHandle callAll = MethodHandles.lookup().findStatic(Hooked.class, "callAll",
                    MethodType.methodType(void.class, Runnable[].class));
            callAll.invokeWithArguments(sink);
        }

        @SuppressWarnings("unchecked")
        public static void throughAnInterfaceProxy(Runnable sink) throws Throwable {
            MethodHandle call = MethodHandles.lookup().findStatic(Hooked.class, "call",
                    MethodType.methodType(void.class, Runnable.class))
                    .asType(MethodType.methodType(void.class, Object.class));
            MethodHandleProxies.asInterfaceInstance(Consumer.class, call).accept(sink);
        }

        /** The same proxy, called by JDK code rather than by this class. */
        @SuppressWarnings("unchecked")
        public static void throughAnInterfaceProxyTheJdkCalls(Runnable sink) throws Throwable {
            MethodHandle call = MethodHandles.lookup().findStatic(Hooked.class, "call",
                    MethodType.methodType(void.class, Runnable.class))
                    .asType(MethodType.methodType(void.class, Object.class));
            Optional.of(sink).ifPresent(MethodHandleProxies.asInterfaceInstance(Consumer.class, call));
        }

        /** JDK code constructs a provider through reflection, with no proxy, and its constructor runs the sink. */
        public static void constructedByTheJdk(Runnable sink) {
            Provider.sink = sink;
            try {
                ServiceLoader.load(Service.class, Hooked.class.getClassLoader()).findFirst();
            } finally {
                Provider.sink = null;
            }
        }
    }

    /** The service {@link Provider} is registered for. */
    public interface Service {}

    /** Stands where the hooked method stands: its constructor runs the sink. */
    public static final class Provider implements Service {
        static Runnable sink;

        public Provider() {
            sink.run();
        }
    }
}
