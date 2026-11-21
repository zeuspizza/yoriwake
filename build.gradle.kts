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
    // CI runs with the build cache to reuse compiled outputs; a test run must prove the tree in
    // front of it, never replay a result an earlier tree produced.
    tasks.withType<Test>().configureEach {
        outputs.doNotCacheIf("a test run proves this tree, not an earlier one") { true }
    }
    // A coverage gate reads more than its declared inputs (the refusal's summaries), so a cached
    // pass could skip a refusal.
    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
        outputs.doNotCacheIf("a coverage gate judges this tree's run, not an earlier one") { true }
    }
}
