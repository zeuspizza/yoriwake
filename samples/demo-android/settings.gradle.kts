// A minimal real Android build, to prove the plugin attaches to a `com.android.library` module,
// scopes JaCoCo, captures a map and narrows. AGP applies `java-base` but not `java`, has no
// SourceSetContainer and names its classpaths per variant, which no unit test here can reproduce.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "demo-android"
include(":library")
