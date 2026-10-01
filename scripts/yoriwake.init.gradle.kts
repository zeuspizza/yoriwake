/*
 * Applies the plugin to a build that knows nothing about it, with `-I <this file>`, so the tool can
 * be tried on a real repository without editing its build.
 *
 * It applies plugin version 0.1.0, from mavenLocal if present there and otherwise from the Gradle
 * Plugin Portal. To apply another version, such as a snapshot published with
 * `:plugin:publishToMavenLocal`, pass it as a system property:
 *
 *     ./gradlew -I yoriwake.init.gradle.kts -Dyoriwake.initScriptVersion=0.1.0-SNAPSHOT test
 */
initscript {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath("io.github.zeuspizza:yoriwake-gradle-plugin:${System.getProperty("yoriwake.initScriptVersion") ?: "0.1.0"}")
    }
}

// Every plugin that brings JVM tests, not only `java` (Android applies `java-base` alone). Read from
// the plugin rather than copied, so the two lists cannot drift.
val jvmTestPlugins = io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.HOST_PLUGINS

// `allprojects { plugins.withId(...) }` is a cross-project read that fails configuration under
// Isolated Projects, so this script must decline before the plugin can. BuildFeatures is asked
// reflectively because it arrived in Gradle 8.5; an older Gradle cannot have the feature either.
val isolatedProjects = runCatching {
    val services = gradle.javaClass.getMethod("getServices").invoke(gradle)
    // ServiceRegistry has `get(Class)` and `get(Type)` and `getMethods()` order is unspecified, so
    // pick the Class overload explicitly.
    val get = services.javaClass.methods
        .filter { it.name == "get" && it.parameterCount == 1 }
        .minByOrNull { if (it.parameterTypes[0] == Class::class.java) 0 else 1 }!!
    val features = get.invoke(services, Class.forName("org.gradle.api.configuration.BuildFeatures"))
    val isolated = features.javaClass.methods.first { it.name == "getIsolatedProjects" }.invoke(features)
    val active = isolated.javaClass.methods.first { it.name == "getActive" }.invoke(isolated)
    active.javaClass.methods.first { it.name == "get" }.invoke(active) as? Boolean ?: false
}.getOrElse {
    // Logged, not swallowed: a wrong false here attaches to a build that cannot serve it.
    logger.info("[yoriwake] could not ask whether Isolated Projects is active: $it")
    false
}

if (isolatedProjects) {
    logger.warn(
        "[yoriwake] Isolated Projects is enabled, so predictive test selection is not applied to this " +
            "build. Every way this tool has of seeing the whole build is a cross-project read that " +
            "Isolated Projects forbids. Your build is otherwise untouched and every run is a full " +
            "run. Turn Isolated Projects off for a build that should select."
    )
}

if (!isolatedProjects) allprojects {
    val configured = mutableSetOf<String>()
    fun configureOnce(reason: String) {
        if (!configured.add("yoriwake")) return
        logger.info("[yoriwake] configuring $path via $reason")
        // The plugin reads JaCoCo but never attaches it, so a host without jacoco would get no map.
        // Not when disabled: a disabled run must not gain an unscoped JaCoCo agent it never asked
        // for. Asked of the plugin itself because `-P` does not reach `startParameter` on every
        // Gradle version.
        if (!io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.isDisabled(this)) {
            apply(plugin = "jacoco")
        }
        apply<io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin>()
    }

    jvmTestPlugins.forEach { id ->
        plugins.withId(id) { configureOnce(id) }
    }

    // Kotlin Multiplatform, on the plugin's own condition: a module this script skips never reaches
    // the plugin's gate. Read before `withId` fires so it sees this module, not the callback's
    // receiver.
    val canAttachMultiplatform = io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.canAttachMultiplatform(this)
    plugins.withId(io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.KOTLIN_MULTIPLATFORM_PLUGIN) {
        if (canAttachMultiplatform) configureOnce(io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.KOTLIN_MULTIPLATFORM_PLUGIN)
    }

    // Say so when declining: a tool that quietly does nothing looks like a working one. The message
    // must not suggest `withJava()`; applying it from an init script breaks multiplatform builds.
    afterEvaluate {
        if (configured.isEmpty() && tasks.withType(Test::class.java).isNotEmpty()) {
            logger.lifecycle(
                "[yoriwake] $path has Test tasks but applies none of $jvmTestPlugins, and is not a " +
                    "Kotlin Multiplatform module this plugin can both see tests in and scope, so " +
                    "predictive test selection is NOT configured here and nothing will be " +
                    "captured or selected. This is a limitation of the plugin, not a problem with " +
                    "your build, and the build is unaffected."
            )
        }
    }
}
