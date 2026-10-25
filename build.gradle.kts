// Kotlin's Gradle plugin in one class loader for both modules: declared with a version in each, it
// loads twice and each copy keeps its own compiler session.
plugins {
    kotlin("jvm") version "2.1.20" apply false
}

// The same commit builds the same bytes, so a published jar can be rebuilt from its tag and
// compared: no file timestamps in any archive, entries in a fixed order.
subprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
