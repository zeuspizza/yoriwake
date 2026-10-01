import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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
    // suite still passes and silently stops narrowing. 9.10.1 is the first to read Java 26.
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
            description = "Predictive test selection for Gradle. Local-first, no service."
            tags = listOf("testing", "test-selection", "junit", "coverage")
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
    jvmToolchain(21)
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
)

testing {
    suites {
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter("5.11.4")
            dependencies {
                // Shares property names and record ids with the plugin, as in the unit tests.
                implementation(project(":agent"))
                implementation("org.jetbrains.kotlin:kotlin-test")
            }
            targets {
                register("functionalTestGradle9")
                all {
                    testTask.configure {
                        // TestKit builds are slow; give them room rather than letting them fail as flakes.
                        maxHeapSize = "1g"
                        systemProperty("yoriwake.functional.gradleVersion", functionalGradleVersions.getValue(name))
                        dependsOn("publishAllPublicationsToBuildLocalRepository")
                        systemProperty("yoriwake.localRepository", localRepository.get().asFile.absolutePath)
                    }
                }
            }
        }
    }
}

// `functionalTest` alone runs both versions. A `--tests` filter reaches only the task it follows,
// so a filtered run names one version's task and excludes the other.
tasks.named("functionalTest") { finalizedBy("functionalTestGradle9") }
tasks.check { dependsOn("functionalTest", "functionalTestGradle9") }

// The suite reads the plugin's internals, as `test` does.
kotlin.target.compilations.named("functionalTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

gradlePlugin {
    testSourceSets(sourceSets["functionalTest"])
}

// XML so the gate below and any script can read it.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Measured over this module's own compiled Kotlin only: the bundled agent and relocated ASM would
// swamp the number. YoriwakePlugin and the wiring and tasks it registers are Gradle code that only
// the functional test exercises, in a separate JVM no coverage agent here sees.
val coveredCode = fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
    exclude("io/github/zeuspizza/yoriwake/gradle/YoriwakePlugin*")
    exclude("io/github/zeuspizza/yoriwake/gradle/wiring/TestTaskWiring*")
    exclude("io/github/zeuspizza/yoriwake/gradle/wiring/SelectionWiring*")
    exclude("io/github/zeuspizza/yoriwake/gradle/tasks/*")
    exclude("io/github/zeuspizza/yoriwake/gradle/wiring/JacocoScoping*")
}

tasks.jacocoTestReport { classDirectories.setFrom(coveredCode) }

// A ratchet set just under current coverage: it catches code no test executes, not unsafe
// selection. Raise it deliberately when coverage rises.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(coveredCode)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // What stays uncovered needs a real Gradle Project to run. Making `establish` take
                // TaskArtifacts instead of ClasspathFacts would let unit tests reach it.
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
