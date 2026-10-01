// The plugin is not declared here: it is applied with `-I scripts/yoriwake.init.gradle.kts`, and
// declaring it as well applies it from two classloaders, which fails with "Cannot add extension with
// name 'yoriwake'".
//
// Run standalone with `-I ../../scripts/yoriwake.init.gradle.kts
// -Dyoriwake.initScriptVersion=0.1.0-SNAPSHOT` from this directory, after `:plugin:publishToMavenLocal`.
plugins {
    kotlin("jvm") version "2.1.20"
    jacoco
}

repositories { mavenCentral() }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
