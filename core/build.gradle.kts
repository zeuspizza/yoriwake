import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The daemon-side logic every host's front end shares: change detection, decoding coverage into the
// map, the audit and the explanation. It names no build tool's API, so a front end for another
// host can use it as the Gradle plugin does, which bundles it.
plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // The versions the plugin bundles and relocates; see its build for why each is pinned.
    implementation("org.jacoco:org.jacoco.core:0.8.12")
    implementation("org.ow2.asm:asm:9.10.1")
    // The host supplies the stdlib, as for the plugin.
    compileOnly(kotlin("stdlib"))
    // The selector and the record formats; bundled by the front end, never published.
    compileOnly(project(":agent"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = providers.gradleProperty("yoriwake.kotlinWarningsAsErrors").orNull?.toBooleanStrict() ?: true
        // Gradle 8.14 embeds Kotlin 2.0, as the plugin's own compilation says.
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}
