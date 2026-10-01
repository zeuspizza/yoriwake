plugins {
    java
}

repositories { mavenCentral() }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Without the yoriwake plugin: the order-dependence check this fixture serves reads only JUnit's
// result XML, so the fixture need not wait on a plugin publication.
//
// One JVM and no in-JVM parallelism, or each class gets its own copy of the shared static and the
// order dependence disappears.
tasks.test {
    useJUnitPlatform()
    forkEvery = 0
    maxParallelForks = 1
    testLogging { events("passed", "failed") }

    // The baseline order is pinned: Gradle's class discovery order follows the filesystem and
    // JUnit's default method order is unspecified, so an unpinned baseline can drift. Real projects
    // have no such pin.
    systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$ClassName")
    systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$MethodName")
}
