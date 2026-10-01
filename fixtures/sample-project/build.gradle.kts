/*
 * A minimal build that applies `jacoco` and runs JUnit 5 tests in a forked worker, to answer in
 * seconds whether the agent jar can reach JaCoCo inside a test JVM:
 *
 *     ./gradlew -p fixtures/sample-project test -PyoriwakeAgentJar=<agent jar>
 *
 * The probe's verdict is printed with the test output and written to
 * `build/yoriwake-agent-probe.txt`. `-PyoriwakeOutputDir=<dir>` also turns on per-test capture.
 */
plugins {
    java
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The agent jar under test, supplied as a plain path so this fixture does not depend on how it
    // is built.
    val agentJar = providers.gradleProperty("yoriwakeAgentJar").orNull
    if (agentJar != null) {
        testRuntimeOnly(files(agentJar))
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty(
        "yoriwake.internal.capture.probeFile",
        layout.buildDirectory.file("yoriwake-agent-probe.txt").get().asFile.absolutePath,
    )
    providers.gradleProperty("yoriwakeOutputDir").orNull?.let { systemProperty("yoriwake.internal.capture.outputDir", it) }
    testLogging { showStandardStreams = true }
}
