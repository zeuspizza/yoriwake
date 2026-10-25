import java.util.Collections
import java.util.zip.ZipFile

plugins {
    java
    jacoco
    kotlin("jvm")
    // Shades ASM into the agent jar under a renamed package.
    id("com.gradleup.shadow") version "8.3.6"
    // The SBOM of what the shaded jar carries; the plugin merges it into the release's.
    id("org.cyclonedx.bom") version "3.5.1"
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
    // exposes its RunNotifier, so hooking it means rewriting one method. The newest Java it reads
    // is `capture/ClassFileSupport`'s ceiling, derived from this ASM.
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
            // Conditional reviewed libraries wrap a native method to see each call to it.
            "Can-Set-Native-Method-Prefix" to "true",
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
    // A warning nobody reads is a warning nobody acts on. The guard script and the benchmark's
    // publish build deliberately broken sources and pass `-Pyoriwake.kotlinWarningsAsErrors=false`.
    compilerOptions {
        allWarningsAsErrors = providers.gradleProperty("yoriwake.kotlinWarningsAsErrors").orNull?.toBooleanStrict() ?: true
    }
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

// Classes on the boot class path, which the JDK's own loader defines from no file of the image.
val bootFixture: SourceSet by sourceSets.creating
tasks.named<JavaCompile>(bootFixture.compileJavaTaskName) { options.release = 11 }
val bootFixtureJar by tasks.registering(Jar::class) {
    from(bootFixture.output)
    archiveFileName = "boot-fixture.jar"
    destinationDirectory = layout.buildDirectory.dir("bootFixture")
}
dependencies { testCompileOnly(bootFixture.output) }

fun Test.withBootFixture() {
    val jar = bootFixtureJar.flatMap { it.archiveFile }
    inputs.file(jar).withPropertyName("bootFixture").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Xbootclasspath/a:" + jar.get().asFile.path) }
}

// Which classes count as the JDK's depends on the JDK, so these run on the one
// `-Pyoriwake.agent.jdk` names, compiled for the oldest test JVM the agent supports.
val agentJdk = providers.gradleProperty("yoriwake.agent.jdk").orElse("21")
val identityLauncher = javaToolchains.launcherFor { languageVersion = agentJdk.map { JavaLanguageVersion.of(it) } }

val jdkIdentity: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + bootFixture.output
    runtimeClasspath += sourceSets.main.get().output
}
tasks.named<JavaCompile>(jdkIdentity.compileJavaTaskName) { options.release = 11 }
dependencies {
    "jdkIdentityImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "jdkIdentityImplementation"("org.junit.jupiter:junit-jupiter")
    "jdkIdentityRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "jdkIdentityImplementation"("org.ow2.asm:asm:9.10.1")
}

val imageFixtures by tasks.registering(JavaExec::class) {
    javaLauncher = identityLauncher
    classpath = jdkIdentity.runtimeClasspath
    mainClass = "io.github.zeuspizza.yoriwake.agent.capture.ImageFixtures"
    val out = layout.buildDirectory.dir(agentJdk.map { "imageFixtures/$it" })
    outputs.dir(out)
    argumentProviders += CommandLineArgumentProvider { listOf(out.get().asFile.path) }
}

fun Test.onAgentJdk(testClasses: List<String>) {
    testClassesDirs = jdkIdentity.output.classesDirs
    classpath = jdkIdentity.runtimeClasspath
    javaLauncher = identityLauncher
    useJUnitPlatform()
    filter { testClasses.forEach { includeTestsMatching("io.github.zeuspizza.yoriwake.agent.capture.$it") } }
    // No coverage agent: it would put a class of its own on the boot loader, and nothing reads it.
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
}

val jdkIdentityTest by tasks.registering(Test::class) {
    onAgentJdk(listOf("JdkIdentityTest"))
    val fixtures = imageFixtures.map { it.outputs.files.singleFile }
    inputs.files(imageFixtures).withPropertyName("imageFixtures")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("--patch-module=java.base=" + fixtures.get().resolve("patch"),
            "--upgrade-module-path=" + fixtures.get().resolve("upgrade"))
    }
}

val jdkNestTest by tasks.registering(Test::class) {
    onAgentJdk(listOf("JdkNestTest", "JdkBranchTest"))
    withBootFixture()
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

tasks.check { dependsOn(jdkIdentityTest, jdkNestTest) }

// The JUnit 4 hook, TestNG listener, loaded-class recorder and test-boundary code run only in the
// scripted capture JVMs, so those JVMs report their coverage too: to a file of their own, read by
// the gate beside the tests' own.
val captureCoverageAgent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    captureCoverageAgent("org.jacoco:org.jacoco.agent:$jacocoVersion:runtime") { isTransitive = false }
}
val captureScriptCoverage = layout.buildDirectory.file("jacoco/captureScript.exec")

tasks.test {
    useJUnitPlatform()
    withBootFixture()
    // JaCoCo appends to its file, so a rerun starts from none.
    val captureCoverage = captureScriptCoverage
    val coverageAgent: FileCollection = captureCoverageAgent
    inputs.files(coverageAgent).withPropertyName("captureCoverageAgent")
    outputs.file(captureCoverage).withPropertyName("captureScriptCoverage")
    doFirst { captureCoverage.get().asFile.delete() }
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Dyoriwake.script.coverageAgent=-javaagent:" + coverageAgent.singleFile.absolutePath +
                "=destfile=" + captureCoverage.get().asFile.absolutePath +
                ",includes=io.github.zeuspizza.yoriwake.agent.*,jmx=false",
        )
    }
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
// Read by the plugin's SBOM, which names where each bundled library now lives.
val relocations by extra(mapOf("org.objectweb.asm" to "io.github.zeuspizza.yoriwake.shaded.asm"))

tasks.shadowJar {
    configurations = listOf(bundled)
    archiveClassifier = ""
    relocations.forEach { (from, to) -> relocate(from, to) }
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
    executionData.from(captureScriptCoverage)
    reports {
        xml.required = true
        html.required = true
    }
}

// A ratchet, set as the plugin's is: the measured LINE ratio floored to a whole percent, raised in
// the change whose tests raise it. It flags unexecuted code; the guards prove the capture rules.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    executionData.from(captureScriptCoverage)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                minimum = "0.81".toBigDecimal()
            }
        }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

// Only what the shaded jar carries; every other configuration is the build's own tooling.
tasks.cyclonedxDirectBom { includeConfigs = listOf(bundled.name) }
