// Puts a JUnit 4 suite onto the JUnit Platform, so predictive test selection can see it at all.
//
//     ./gradlew -I scripts/junit-vintage.init.gradle.kts test
//
// Selection runs as a JUnit Platform `PostDiscoveryFilter`. A suite on JUnit 4's own runner never
// reaches the Platform, so nothing in it can be deselected, however good the coverage map is.
//
// The permanent fix is two lines in your build: `useJUnitPlatform()` and a `testRuntimeOnly` on
// `junit-vintage-engine`. This script applies the same change without editing the build.
//
// It does not make JUnit 3 `TestCase`s or a custom `Runner` that bypasses the Platform selectable;
// where it does not apply, the run simply forces.
initscript {
    repositories { mavenCentral() }
}

// Pinned rather than floating, so two runs resolve the same engine.
val vintageVersion = "5.11.4"
val platformVersion = "1.11.4"

allprojects {
    // Lazy on purpose: an init script's afterEvaluate runs before the Android plugin creates its
    // unit-test tasks, so an eager check would find none and configure nothing.
    tasks.withType(Test::class.java).configureEach {
        useJUnitPlatform()
    }

    // Vintage runs the JUnit 4 tests; the launcher is what the Platform needs to start at all.
    // Matched by shape because Android's configurations are per variant -- `testDebugRuntimeOnly`,
    // `testPlayDebugRuntimeOnly` -- and there is no single `testRuntimeOnly`.
    val owner = this
    configurations.all {
        val configurationName = name
        val wanted = configurationName.startsWith("test") &&
            (configurationName.endsWith("RuntimeOnly") || configurationName.endsWith("Implementation"))
        if (!wanted) return@all
        // `owner.dependencies`, not `dependencies`: inside `configurations.all` the receiver is the
        // Configuration, whose `dependencies` is a DependencySet taking Dependency objects, and the
        // notation form silently resolves to the wrong one.
        owner.dependencies.add(
            configurationName,
            "org.junit.vintage:junit-vintage-engine:$vintageVersion",
        )
        owner.dependencies.add(
            configurationName,
            "org.junit.platform:junit-platform-launcher:$platformVersion",
        )
    }
}
