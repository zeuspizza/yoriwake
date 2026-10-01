plugins {
    id("com.android.library")
    jacoco
}

android {
    namespace = "dev.demoandroid"
    // Not the newest SDK, so the fixture runs without an extra SDK download.
    compileSdk = 33
    defaultConfig { minSdk = 24 }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The unit-test task is `testDebugUnitTest`; `test` is only a lifecycle task over the variants.
tasks.withType<Test>().configureEach { useJUnitPlatform() }
