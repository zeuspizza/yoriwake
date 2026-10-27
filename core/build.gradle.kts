import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The daemon-side logic every host's front end shares: change detection, decoding coverage into the
// map, the audit and the explanation. It names no build tool's API, so each front end, the Gradle
// plugin and the Maven extension, bundles it.
plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // Compiled against only: each front end pins the versions it bundles and relocates, so each
    // front end's own pin is the one that ships. See the plugin's build for why each is pinned.
    compileOnly("org.jacoco:org.jacoco.core:0.8.12")
    compileOnly("org.ow2.asm:asm:9.10.1")
    // Gradle supplies the stdlib to the plugin; the Maven extension bundles its own.
    compileOnly(kotlin("stdlib"))
    // The selector and the record formats; bundled by the front end, never published.
    compileOnly(project(":agent"))
}

// The bytecode floor is the oldest host that loads core: Maven 3.9.0-3.9.5 drop an extension whose
// components are newer than Java 11 without a word. `-Xjdk-release`, as the agent's `release`,
// also rejects calls to APIs newer than the floor.
java { targetCompatibility = JavaVersion.VERSION_11 }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        freeCompilerArgs.add("-Xjdk-release=11")
        allWarningsAsErrors = providers.gradleProperty("yoriwake.kotlinWarningsAsErrors").orNull?.toBooleanStrict() ?: true
        // Gradle 8.14 embeds Kotlin 2.0, as the plugin's own compilation says.
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}
