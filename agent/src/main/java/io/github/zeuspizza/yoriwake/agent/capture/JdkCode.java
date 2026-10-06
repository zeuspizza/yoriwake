package io.github.zeuspizza.yoriwake.agent.capture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.module.ModuleFinder;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Whether a class is the JDK's own code: defined by the bootstrap or platform loader, in a
 * {@code java.*} or {@code jdk.*} module, from a class file the runtime image holds. A loader alone
 * cannot tell: the boot class path, a patched module and an upgraded one put other code on the
 * JDK's loaders. Anything that cannot be established is the project's, the side that records.
 */
final class JdkCode {

    private JdkCode() {}

    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

    /** Absent before JDK 15, where nothing is hidden. */
    private static final MethodHandle IS_HIDDEN = isHiddenHandle();

    /** Hidden classes code outside the JDK defined, by the name in their bytes. */
    private static final Set<String> PROJECT_DEFINED = ConcurrentHashMap.newKeySet();

    private static final ClassValue<Boolean> JDK = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return judge(type,
                    declared -> declared.getResource("/" + declared.getName().replace('.', '/') + ".class"));
        }
    };

    static boolean isJdk(Class<?> type) {
        return JDK.get(type);
    }

    /** {@link #isJdk} with the class-file lookup given, so a failing lookup can be tried. */
    static boolean judge(Class<?> type, Function<Class<?>, URL> lookup) {
        try {
            Class<?> declared = declaredBy(type);
            return declared != null && fromImage(declared, lookup);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean fromImage(Class<?> type, Function<Class<?>, URL> lookup) {
        ClassLoader loader = type.getClassLoader();
        // Image modules the application loader defines, such as jdk.compiler, are the project's.
        if (loader != null && loader != PLATFORM) {
            return false;
        }
        Module module = type.getModule();
        if (!module.isNamed() || !imageModule(module.getName())) {
            return false;
        }
        URL file = lookup.apply(type);
        return file != null && "jrt".equals(file.getProtocol());
    }

    static boolean imageModule(String name) {
        return name.startsWith("java.") || name.startsWith("jdk.");
    }

    /**
     * The class whose file stands for this one. A hidden class has none, so its nest host stands
     * for it, unless code outside the JDK defined it: such code can join a JDK class's nest. Before
     * JDK 15 nothing is hidden, so a VM-anonymous class, with no file of its own, is the project's.
     */
    private static Class<?> declaredBy(Class<?> type) throws Throwable {
        if (!hidden(type)) {
            return type;
        }
        if (PROJECT_DEFINED.contains(baseName(type))) {
            return null;
        }
        Class<?> host = type.getNestHost();
        if (host == type && type.getClassLoader() == null && "java.lang.invoke".equals(type.getPackageName())) {
            // The JDK's lambda forms, each its own nest host. Only java.base's own code, or code
            // java.base was opened to, can define into this package; the latter's definitions
            // through the hooks were excluded above.
            return MethodHandle.class;
        }
        return host != type && !hidden(host) ? host : null;
    }

    /** A hidden class is named by its bytes, then a slash and a suffix the JVM picks. */
    private static String baseName(Class<?> type) {
        String name = type.getName();
        int slash = name.indexOf('/');
        return slash < 0 ? name : name.substring(0, slash);
    }

    private static boolean hidden(Class<?> type) throws Throwable {
        return IS_HIDDEN != null && (boolean) IS_HIDDEN.invokeExact(type);
    }

    private static MethodHandle isHiddenHandle() {
        try {
            return MethodHandles.publicLookup().findVirtual(Class.class, "isHidden",
                    MethodType.methodType(boolean.class));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called before the hidden class exists, so no judgment of it can be cached first. */
    static void projectDefined(String name) {
        PROJECT_DEFINED.add(name);
    }

    /**
     * Whether a frame named as reflection, method-handle or proxy machinery is that machinery: the
     * JDK's own, a {@code java.lang.reflect.Proxy} class, or a method-handle proxy, whose module
     * only the JDK can create.
     */
    static boolean callsForAnother(Class<?> frame) {
        return isJdk(frame) || java.lang.reflect.Proxy.isProxyClass(frame) || methodHandleProxy(frame);
    }

    private static boolean methodHandleProxy(Class<?> frame) {
        Module module = frame.getModule();
        return frame.getName().startsWith("jdk.MHProxy") && module.isNamed() && module.getLayer() == null;
    }

    /**
     * Why this JVM's image is not the JDK's as shipped, or null when it is. Code inside a patched
     * or upgraded image module reaches that module's internals without passing a hook, so no
     * judgment of callers can see what it does.
     */
    static String imageAltered() {
        return imageAltered(() -> ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    static String imageAltered(Callable<List<String>> reader) {
        List<String> arguments;
        try {
            arguments = reader.call();
        } catch (Throwable t) {
            return "the JVM's own arguments could not be read: " + t;
        }
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if ((argument.equals("--patch-module") || argument.equals("--upgrade-module-path"))
                    && i + 1 < arguments.size()) {
                argument = argument + "=" + arguments.get(++i);
            }
            if (patchesSystemModule(argument)) {
                return "an image module is patched: " + argument;
            }
            if (argument.startsWith("--upgrade-module-path")) {
                return "an image module is upgraded: " + argument;
            }
        }
        return null;
    }

    /** A patch whose module cannot be read from it counts as a patch of a system module. */
    private static boolean patchesSystemModule(String argument) {
        if (!argument.startsWith("--patch-module")) {
            return false;
        }
        String prefix = "--patch-module=";
        int equals = argument.indexOf('=', prefix.length());
        if (!argument.startsWith(prefix) || equals <= prefix.length()) {
            return true;
        }
        try {
            return ModuleFinder.ofSystem().find(argument.substring(prefix.length(), equals)).isPresent();
        } catch (Throwable t) {
            return true;
        }
    }
}
