rootProject.name = "demo-kotlin"

// Same resolution as the Java sample: the plugin comes from the local repository CI publishes to,
// so the fixture exercises the artifact a user would consume.
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
