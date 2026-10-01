rootProject.name = "demo"

// The plugin is resolved from the local repository the CI job publishes it to, so the sample
// exercises the same artifact a user would consume rather than a project dependency.
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
