// Kotlin's Gradle plugin in one class loader for both modules: declared with a version in each, it
// loads twice and each copy keeps its own compiler session.
plugins {
    kotlin("jvm") version "2.1.20" apply false
}
