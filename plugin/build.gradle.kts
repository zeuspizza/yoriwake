import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.zip.CRC32

plugins {
    `java-gradle-plugin`
    `maven-publish`
    jacoco
    kotlin("jvm") version "2.1.20"
    id("com.gradle.plugin-publish") version "2.2.1"
    // Relocates the plugin's own dependencies so it adds nothing unshaded to a host's buildscript
    // classpath. 9.2.x is the newest line that still runs on this build's Gradle 8.14.
    id("com.gradleup.shadow") version "9.2.2"
}

group = "io.github.zeuspizza"
// The release workflow passes the tag's version; every other build stays a snapshot, which the
// Portal refuses, so nothing but a tagged release can publish.
version = findProperty("releaseVersion") ?: "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val jacocoVersion = "0.8.12"
val asmVersion = "9.10.1"
// The JDK this build compiles and tests on, and the daemon JDK functional builds run on by default.
val buildJdk = 21

dependencies {
    // Decoding coverage happens here, on the daemon side, never in the agent. The JaCoCo agent jar
    // shades its own copy of org.jacoco.core into a package whose name embeds a build hash, so
    // reflecting into it from the test JVM would couple us to a version-varying internal name.
    // Bundled and relocated by shadowJar below, so it never reaches the published POM.
    implementation("org.jacoco:org.jacoco.core:$jacocoVersion")
    // Gradle supplies the stdlib at runtime; bundling a second copy into a plugin is unsafe, and
    // declaring it would put it in the POM. The API version below keeps us to what 8.14 ships.
    compileOnly(kotlin("stdlib"))

    // ASM, explicitly and ahead of the older copy JaCoCo brings. The daemon-side inline scan uses
    // this ASM, and an ASM that cannot read a class file's major version turns every such class
    // into "unknown", which forces a full run. Raise it whenever a JDK ships: when it lags, the
    // suite still passes and silently stops narrowing. The newest Java it reads is
    // `bytecode/ClassFileSupport`'s ceiling, derived from this ASM.
    implementation("org.ow2.asm:asm:$asmVersion")
    // JaCoCo pulls asm-commons and asm-tree at an older version; the shaded jar must not mix them.
    // One value for all three, so lowering it lowers every ASM module the jar carries.
    constraints {
        implementation("org.ow2.asm:asm-commons:$asmVersion")
        implementation("org.ow2.asm:asm-tree:$asmVersion")
    }

    // compileOnly: the daemon calls Selector before the test JVM starts, but an `implementation`
    // dependency would put the agent in the published POM. unpackAgentClasses bundles it instead.
    compileOnly(project(":agent"))

    // The agent and plugin share property names and a schema version across a module boundary;
    // depending on it in tests turns that contract into assertions.
    testImplementation(project(":agent"))
    // The agent's supertypes are compileOnly there (it must add nothing to a host classpath), so
    // the plugin's tests need them to reference its classes at all.
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // So a capture test can drive the agent's TestNG listener with TestNG's own types.
    testImplementation("org.testng:testng:7.10.2")
    // ProjectBuilder and the task types; TestKit, which used to bring them, is functionalTest's.
    testImplementation(gradleApi())
    // Reads the compiled classes' Kotlin visibility, for the test pinning the public API.
    testImplementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.1.20")
    // A strict JSON parser for the tests pinning explain.json and audit.json: the readers already
    // on this classpath accept raw control characters in a string.
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.18.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The agent jar ships inside the plugin jar, so a host build never declares it and the agent and
// plugin always agree on the record format they share.
val agentJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    agentJar(project(":agent"))
}

val bundleAgent by tasks.registering(Copy::class) {
    from(agentJar)
    into(layout.buildDirectory.dir("bundled-agent"))
    rename { "yoriwake-agent.jar" }
}

// The agent's classes as well as its jar: the nested jar goes to the test JVM via -javaagent, the
// unpacked classes let the daemon call Selector before that JVM starts.
val unpackAgentClasses by tasks.registering(Sync::class) {
    // zipTree over a configuration drops the producing task; declaring it as an input keeps
    // :agent:shadowJar ordered before this task.
    inputs.files(agentJar)
    from({ agentJar.map { zipTree(it) } })
    into(layout.buildDirectory.dir("agent-classes"))
    // The agent's service registrations would register its listener on the daemon's classpath.
    // Its licences stay: the relocated ASM is redistributed in this jar too.
    exclude { it.path.startsWith("META-INF/") && !it.path.startsWith("META-INF/licenses/") }
}

sourceSets.main {
    output.dir(mapOf("builtBy" to bundleAgent), layout.buildDirectory.dir("bundled-agent"))
    output.dir(mapOf("builtBy" to unpackAgentClasses), layout.buildDirectory.dir("agent-classes"))
}

val localRepository = layout.buildDirectory.dir("local-repository")

// Emptied before every publish, so a test reading it sees this build's artifacts and no snapshot
// left by an earlier one.
val cleanLocalRepository by tasks.registering(Delete::class) { delete(localRepository) }
tasks.withType<PublishToMavenRepository>().configureEach {
    if (name.endsWith("ToBuildLocalRepository")) dependsOn(cleanLocalRepository)
}

gradlePlugin {
    website = "https://github.com/zeuspizza/yoriwake"
    vcsUrl = "https://github.com/zeuspizza/yoriwake.git"
    plugins {
        create("yoriwake") {
            id = "io.github.zeuspizza.yoriwake"
            implementationClass = "io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin"
            displayName = "yoriwake"
            description = "Runs the tests a change can reach and sets the rest aside: safety-first test selection and test impact analysis for Gradle, from per-test coverage. Local-first, no service."
            tags = listOf("testing", "test-selection", "test-impact-analysis", "regression-test-selection", "incremental-testing", "junit", "coverage", "ci", "windows")
            // Declare only what a test proves: a declared feature cannot be withdrawn later.
            // Isolated Projects is not declared; the plugin declines it by name.
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

// Published as `yoriwake-gradle-plugin` rather than the directory name; the Portal's marker
// follows the publication, so this rename is all it needs.
publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "yoriwake-gradle-plugin"
        pom {
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            scm {
                url = "https://github.com/zeuspizza/yoriwake"
                connection = "scm:git:https://github.com/zeuspizza/yoriwake.git"
            }
            // Names the project rather than a person, so no personal data reaches a public artifact.
            developers {
                developer {
                    id = "zeuspizza"
                    name = "The yoriwake authors"
                }
            }
        }
    }
    // A repository only this build writes, so the published-plugin test proves these artifacts
    // rather than whatever mavenLocal holds.
    repositories {
        maven {
            name = "buildLocal"
            url = uri(localRepository)
        }
    }
}

// The published jar, with JaCoCo core and ASM relocated. Only `org.jacoco.core` is relocated: the
// agent classes name the host's `org.jacoco.agent.rt.RT` by string, which must stay the host's.
tasks.shadowJar {
    archiveClassifier = ""
    relocate("org.jacoco.core", "io.github.zeuspizza.yoriwake.shaded.plugin.jacoco.core")
    relocate("org.objectweb.asm", "io.github.zeuspizza.yoriwake.shaded.plugin.asm")
    // Upstream metadata would name unshaded coordinates this jar does not actually carry.
    exclude("module-info.class", "META-INF/versions/*/module-info.class", "META-INF/maven/**", "about.html")
    from(files(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
}

tasks.processResources {
    val version = jacocoVersion
    inputs.property("jacocoVersion", version)
    filesMatching("META-INF/licenses/JACOCO-SOURCE.txt") { expand("jacocoVersion" to version) }
}

kotlin {
    jvmToolchain(buildJdk)
    // Public is only what a host build or the init script touches; everything else is internal.
    explicitApi()
}

// The stdlib is Gradle's, not ours, and Gradle 8.14 embeds Kotlin 2.0.21: compiled against 2.0,
// the plugin calls nothing the oldest supported Gradle cannot run. Tests run on our own stdlib.
tasks.compileKotlin {
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // RealProjectProbeTest runs against real checkouts only when told where they are; with no
    // property it skips rather than guessing a location this checkout may not have.
    providers.gradleProperty("yoriwake.probe.checkouts").orNull?.let {
        systemProperty("yoriwake.probe.checkouts", rootProject.file(it).absolutePath)
    }
}

// Every test that runs a real Gradle build through TestKit, apart from `test` so the unit tests
// stay fast and publish nothing. The whole suite runs once per Gradle version: the declared floor
// and a current 9.x, each as its own task with its own TestKit home.
val functionalGradleVersions = mapOf(
    "functionalTest" to "8.14",
    "functionalTestGradle9" to "9.8.0",
    "breakAttempts" to "8.14",
    "breakAttemptsGradle9" to "9.8.0",
)

// The supported-matrix workflow runs one cell per job: one functional task, named here, on one daemon
// JDK, which TestKit's builds inherit from the test JVM. Without either property every version runs
// on the build's own JDK, as on a pull request.
val functionalCellTask = providers.gradleProperty("yoriwake.functional.gradle").orNull?.also {
    require(it in functionalGradleVersions) { "yoriwake.functional.gradle=$it names no functional task" }
}
val functionalCellJdk = providers.gradleProperty("yoriwake.functional.jdk").orNull?.toInt()
// A pull request's CI runs each functional suite as several jobs: `<index>/<count>` runs the test
// classes whose top-level name hashes to `index`, so every class runs in exactly one shard. The
// workflow checks that from the shards' results. Without it the whole suite runs.
val functionalShard = providers.gradleProperty("yoriwake.functional.shard").orNull?.let { value ->
    val parts = value.split("/")
    val index = parts.getOrNull(0)?.toIntOrNull()
    val count = parts.getOrNull(1)?.toIntOrNull()
    require(parts.size == 2 && index != null && count != null && count > 0 && index in 1..count) {
        "yoriwake.functional.shard=$value must be <index>/<count>, with 1 <= index <= count"
    }
    index to count
}
// Coverage of the plugin's code in the TestKit daemons, where its wiring and tasks run. On by
// default, so `./gradlew build` measures it; a matrix cell runs one version and proves the plugin as
// users run it, so it never carries coverage. `-Pyoriwake.functional.coverage=false` turns it off.
val functionalCoverage = functionalCellTask == null &&
    (providers.gradleProperty("yoriwake.functional.coverage").orNull?.toBooleanStrict() ?: true)
// The JDKs a fixture's toolchain may use. TestKit builds have their own Gradle home and cannot see
// the JDKs this build was told about, so the same paths are handed to them.
val toolchainPaths = providers.gradleProperty("org.gradle.java.installations.paths").orNull.orEmpty()

// A Java agent in a TestKit daemon turns the configuration cache off for every build it runs, so
// the daemons load a copy of the plugin's classes instrumented ahead of time, with JaCoCo's runtime
// on their boot classpath. The functional-test JVM itself keeps the compiled classes.
val coverageRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val coverageInstrumenter: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    coverageRuntime("org.jacoco:org.jacoco.agent:$jacocoVersion:runtime") { isTransitive = false }
    coverageInstrumenter("org.jacoco:org.jacoco.cli:$jacocoVersion:nodeps") { isTransitive = false }
}

val instrumentedPluginClasses = layout.buildDirectory.dir("coverage/instrumented-classes")

val instrumentPluginClasses by tasks.registering(JavaExec::class) {
    description = "Instruments the plugin's classes for coverage in the functional suite's TestKit daemons."
    val compiled = tasks.compileKotlin.flatMap { it.destinationDirectory }
    val instrumented = instrumentedPluginClasses
    inputs.dir(compiled)
    outputs.dir(instrumented)
    classpath(coverageInstrumenter)
    mainClass = "org.jacoco.cli.internal.Main"
    argumentProviders += CommandLineArgumentProvider {
        listOf("instrument", compiled.get().asFile.path, "--dest", instrumented.get().asFile.path, "--quiet")
    }
    // A class whose source was deleted must not linger in the copy.
    doFirst { instrumented.get().asFile.deleteRecursively() }
}

if (functionalCoverage) {
    tasks.pluginUnderTestMetadata {
        val compiled = tasks.compileKotlin.get().destinationDirectory.get().asFile
        pluginClasspath.setFrom(instrumentPluginClasses, sourceSets.main.get().runtimeClasspath.filter { it != compiled })
    }
}

// Attempts to make selection skip a test that should run, each kept once it fails to, live in one
// package of the functional sources and run as their own task on both versions: the functional
// suite does not grow with every attempt, and the attempts run where a release or a fix asks.
val breakAttemptsPackage = "io.github.zeuspizza.yoriwake.gradle.breaking"

testing {
    suites {
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter("5.11.4")
            dependencies {
                // Shares property names and record ids with the plugin, as in the unit tests.
                implementation(project(":agent"))
                implementation("org.jetbrains.kotlin:kotlin-test")
                // Receives the daemons' coverage and writes it out after each test class.
                implementation("org.jacoco:org.jacoco.core:$jacocoVersion")
                implementation("org.junit.platform:junit-platform-launcher")
            }
            targets {
                register("functionalTestGradle9")
                register("breakAttempts")
                register("breakAttemptsGradle9")
                all {
                    testTask.configure {
                        // TestKit builds are slow; give them room rather than letting them fail as flakes.
                        maxHeapSize = "1g"
                        // A CI log is all a failed run leaves: print each failure's message, not only its class.
                        testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                        systemProperty("yoriwake.functional.gradleVersion", functionalGradleVersions.getValue(name))
                        dependsOn("publishAllPublicationsToBuildLocalRepository")
                        systemProperty("yoriwake.localRepository", localRepository.get().asFile.absolutePath)
                        // The version this build publishes, release or snapshot, for the published-plugin test.
                        systemProperty("yoriwake.pluginVersion", project.version.toString())
                        // What the identity test compares the TestKit daemon's JDK with.
                        systemProperty("yoriwake.functional.expectedJdk", (functionalCellJdk ?: buildJdk).toString())
                        systemProperty("yoriwake.functional.toolchainPaths", toolchainPaths)
                        providers.gradleProperty("yoriwake.functional.testJvms").orNull?.let {
                            systemProperty("yoriwake.functional.testJvms", it)
                        }
                        providers.gradleProperty("yoriwake.functional.earlyAccessJdk").orNull?.let {
                            systemProperty("yoriwake.functional.earlyAccessJdk", it)
                        }
                        systemProperty("yoriwake.functional.coverage", functionalCoverage.toString())
                        if (functionalCoverage) {
                            // One file per test class, from this task's run only: a rerun never mixes in an older one.
                            val daemonCoverage = layout.buildDirectory.dir("jacoco/daemon-$name")
                            systemProperty("yoriwake.functional.coverageDir", daemonCoverage.get().asFile.absolutePath)
                            val runtime: FileCollection = coverageRuntime
                            inputs.files(runtime).withPropertyName("coverageRuntime")
                            jvmArgumentProviders += CommandLineArgumentProvider {
                                listOf("-Dyoriwake.functional.coverageRuntime=" + runtime.singleFile.absolutePath)
                            }
                            outputs.dir(daemonCoverage).withPropertyName("daemonCoverage")
                            doFirst { daemonCoverage.get().asFile.deleteRecursively() }
                        }
                        functionalCellJdk?.let { jdk ->
                            javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk) }
                        }
                        if (name.startsWith("breakAttempts")) {
                            filter.includeTestsMatching("$breakAttemptsPackage.*")
                        } else {
                            filter.excludeTestsMatching("$breakAttemptsPackage.*")
                        }
                        functionalShard?.takeIf { !name.startsWith("breakAttempts") }?.let { (index, count) ->
                            // By the class file's top-level name, so a nested class runs with the class
                            // around it. CRC32, not hashCode: the same shard on every JVM and every run.
                            // Plain values only in the lambda, for the configuration cache.
                            exclude { file ->
                                val path = file.relativePath.pathString
                                if (file.isDirectory || !path.endsWith(".class")) return@exclude false
                                val topLevel = path.removeSuffix(".class").substringBefore('$').replace('/', '.')
                                val crc = CRC32().apply { update(topLevel.toByteArray()) }.value
                                (crc % count).toInt() + 1 != index
                            }
                        }
                    }
                }
            }
        }
    }
}

// Outside a matrix cell, `functionalTest` alone runs both versions. A `--tests` filter reaches only
// the task it follows, so a filtered run names one version's task and excludes the other.
if (functionalCellTask == null) {
    tasks.named("functionalTest") { finalizedBy("functionalTestGradle9") }
    tasks.named("breakAttempts") { finalizedBy("breakAttemptsGradle9") }
    tasks.check { dependsOn("functionalTest", "functionalTestGradle9") }
} else {
    tasks.check { dependsOn(functionalCellTask) }
}

// The suite reads the plugin's internals, as `test` does.
kotlin.target.compilations.named("functionalTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

gradlePlugin {
    testSourceSets(sourceSets["functionalTest"])
}

// One report and one gate over the unit tests and both functional suites, measured over this
// module's own compiled Kotlin: the bundled agent and relocated ASM would swamp the number.
// Coverage finds code no test executes; the guards, not coverage, prove the safety rules.
val gatedFunctionalTasks = listOf("functionalTest", "functionalTestGradle9")
// CI runs each functional suite in shards, in jobs of their own, and gates in a later job on the
// data they uploaded: this names that directory, and the gate then runs no test task itself.
val coverageData = providers.gradleProperty("yoriwake.coverage.data").orNull?.let { rootProject.file(it) }

listOf(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification).forEach { coverageTask ->
    coverageTask.configure {
        classDirectories.setFrom(tasks.compileKotlin.flatMap { it.destinationDirectory })
        executionData.setFrom()
        if (coverageData != null) {
            executionData.from(fileTree(coverageData) { include("**/*.exec") })
        } else if (functionalCoverage) {
            // With coverage off nothing is wired in: the gate refuses at once rather than after the suites.
            (listOf("test") + gatedFunctionalTasks).forEach { name ->
                dependsOn(name)
                executionData.from(tasks.named<Test>(name).map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! })
            }
            executionData.from(fileTree(layout.buildDirectory.dir("jacoco")) {
                gatedFunctionalTasks.forEach { include("daemon-$it/*.exec") }
            })
        } else if (name == "jacocoTestReport") {
            // A quick loop without functional coverage still gets a report of the unit tests.
            dependsOn(tasks.test)
            executionData.from(tasks.test.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! })
        }
    }
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

// A ratchet: the measured LINE ratio floored to a whole percent, the lower of a local run and the
// pull request's CI run. It catches code no test executes, not unsafe selection. Raise it in the
// change whose tests raise the measurement; lowering it needs a reason in that change.
// With nothing to read the gate would pass as having no source; asked for anyway, it says why.
val functionalCoverageOff by tasks.registering {
    doLast {
        throw GradleException(
            "the coverage gate needs both functional suites run with coverage, and " +
                "-Pyoriwake.functional.coverage=false or -Pyoriwake.functional.gradle turned it off",
        )
    }
}

tasks.jacocoTestCoverageVerification {
    if (!functionalCoverage && coverageData == null) dependsOn(functionalCoverageOff)
    val summaries = coverageData ?: layout.buildDirectory.dir("jacoco").get().asFile
    val pluginClass = "hit io/github/zeuspizza/yoriwake/gradle/YoriwakePlugin"
    // Plain values only in the action, for the configuration cache.
    val suites = gatedFunctionalTasks
    // Without a suite's data the measurement drops for a reason that is not the code's; say which.
    doFirst {
        val missing = suites.filter { task ->
            summaries.walk().none { file ->
                file.name == "summary.txt" && file.parentFile.name == "daemon-$task" && pluginClass in file.readLines()
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException("no daemon coverage from ${missing.joinToString(" and ")} under $summaries: run it with coverage on")
        }
    }
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.94".toBigDecimal()
            }
        }
    }
}

// A matrix cell or a run with coverage off proves the tests without the gate; `build` with
// coverage on is the gate.
if (functionalCoverage) tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
