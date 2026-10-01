import java.util.Collections
import java.util.zip.ZipFile

plugins {
    java
    jacoco
    kotlin("jvm") version "2.1.20"
    // Shades ASM into the agent jar under a renamed package.
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    mavenCentral()
}

val jacocoVersion = "0.8.12"

// ASM is shaded in but not published as a dependency: a consumer would otherwise pull an unshaded
// copy, and the plugin would bundle two jars under one name.
val bundled: Configuration by configurations.creating
configurations.compileOnly { extendsFrom(bundled) }

dependencies {
    // The host build supplies these at runtime: the launcher runs its tests, and the JaCoCo runtime
    // comes from the attached -javaagent. Compile-only, so nothing lands on the host's classpath.
    compileOnly(platform("org.junit:junit-bom:5.11.4"))
    compileOnly("org.junit.platform:junit-platform-launcher")
    compileOnly("org.jacoco:org.jacoco.agent:$jacocoVersion:runtime")

    // The one runtime dependency, relocated. JUnit 4 has no ServiceLoader discovery and Gradle never
    // exposes its RunNotifier, so hooking it means rewriting one method. ASM 9.10 reads Java 26.
    bundled("org.ow2.asm:asm:9.10.1")
    // Never shipped: only TestNG's own ServiceLoader loads the TestNG listener, so TestNG is present
    // whenever that class resolves.
    compileOnly("org.testng:testng:7.10.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Lets unit tests drive the TestNG listener and the JUnit 4 rewrite.
    testImplementation("org.testng:testng:7.10.2")
    testRuntimeOnly("org.ow2.asm:asm:9.10.1")
    // The real RunNotifier to rewrite, and ASM's verifier to check the rewrite.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.ow2.asm:asm-util:9.10.1")
}


// The jar doubles as a -javaagent so it can obtain Instrumentation.
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "io.github.zeuspizza.yoriwake.agent.capture.LoadedClassRecorder",
            // The read hooks rewrite six JDK entry points that are loaded before premain runs.
            "Can-Retransform-Classes" to "true",
        )
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

// The bytecode floor is the host's test JVM, not this toolchain: every class here must load on a
// Java 11 test JVM, or the host's test executor fails to start. `release` rather than
// `targetCompatibility`, because only `release` also rejects calls to APIs newer than the floor.
// Test sources are not lowered; they never reach a host.
tasks.compileJava {
    options.release = 11
}

kotlin {
    jvmToolchain(21)
}

// Scripted capture runs for CaptureCharacterizationTest: a stand-in JaCoCo runtime and one driver
// per engine, each run in a JVM of its own so capture ends the way a host's does. Never shipped.
val captureScript: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    "captureScriptImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "captureScriptImplementation"("org.junit.platform:junit-platform-launcher")
    "captureScriptImplementation"("junit:junit:4.13.2")
    "captureScriptImplementation"("org.testng:testng:7.10.2")
}

tasks.test {
    useJUnitPlatform()
    // AgentContractTest checks this page, so an edit to it alone must rerun the tests.
    inputs.file(rootProject.file("docs/contract.md"))
        .withPropertyName("contractDoc")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // A plain JUnit 4 or TestNG JVM has no Platform, and the agent must still capture there.
    val withPlatform = captureScript.runtimeClasspath
    val withoutPlatform = withPlatform.filter { jar ->
        listOf("junit-platform-", "junit-jupiter", "opentest4j-").none { jar.name.startsWith(it) }
    }
    inputs.files(withPlatform).withPropertyName("captureScriptClasspath").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Dyoriwake.script.classpath.platform=${withPlatform.asPath}",
            "-Dyoriwake.script.classpath.plain=${withoutPlatform.asPath}",
        )
    }
}

// The jar lands on a host's test classpath, so every class must live under
// `io.github.zeuspizza.yoriwake.` or it may collide with what the host resolves. The same constraint
// keeps this module in Java: a host without kotlin-stdlib cannot construct a Kotlin listener.
val verifyNoRuntimeDependencies by tasks.registering {
    // Declared as an input so the task depends on shadowJar; read only inside doLast, a stale jar
    // would be verified instead of the current one.
    val shadedJar = tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(shadedJar)
    doLast {
        val zip = ZipFile(shadedJar.get().asFile)
        val leaked: List<String> = zip.use { archive ->
            Collections.list(archive.entries())
                .map { entry -> entry.name }
                .filter { name -> name.endsWith(".class") && !name.startsWith("io/github/zeuspizza/yoriwake/") }
        }
        require(leaked.isEmpty()) {
            "The agent jar must carry nothing a host build could collide with, but it contains " +
                leaked.size + " such classes, e.g. " + leaked.take(5)
        }
    }
}

tasks.check {
    dependsOn(verifyNoRuntimeDependencies)
}


// ASM is relocated into our own package: a -javaagent jar joins the system classpath, so an
// unrelocated copy would conflict with the host's.
tasks.shadowJar {
    configurations = listOf(bundled)
    archiveClassifier = ""
    relocate("org.objectweb.asm", "io.github.zeuspizza.yoriwake.shaded.asm")
    minimize()
    // The jar is redistributed on its own, so it carries the notices itself. ASM's BSD text comes
    // from src/main/resources.
    from(files(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }

// With `jar` disabled, the shaded jar is the artifact the plugin consumes and bundles.
configurations.configureEach {
    if (name == "apiElements" || name == "runtimeElements") {
        // Cleared first: the shadow plugin may have registered the jar already, and a duplicate
        // fails the consumer's copy.
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.named("shadowJar")) { classifier = "" }
    }
}

// Coverage as XML so the gate below and scripts can read it. It flags unexecuted code; it is not
// evidence of safety.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Lower than the plugin's floor: the JUnit 4 hook, TestNG listener, loaded-class recorder and
// test-boundary code run only in a host's test JVM, which no coverage agent here observes.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                minimum = "0.68".toBigDecimal()
            }
        }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
