import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The Maven front end: a build extension declared once in a POM, sharing core with the Gradle
// plugin. Maven supplies its own API and nothing else, so everything it runs on is bundled and
// relocated here, the Kotlin standard library included.
plugins {
    kotlin("jvm")
    `maven-publish`
    id("com.gradleup.shadow") version "9.2.2"
    // The plugin descriptor Maven reads before it loads a `<plugin>` declared with `<extensions>`.
    id("org.gradlex.maven-plugin-development") version "1.0.3"
}

group = "io.github.zeuspizza"
version = findProperty("releaseVersion") ?: "0.1.0-SNAPSHOT"
base.archivesName = "yoriwake-maven-plugin"

repositories {
    mavenCentral()
}

// The oldest Maven this supports, and the API compiled against: a newer one could compile calls
// the floor lacks.
val mavenFloor = "3.9.0"

dependencies {
    implementation(project(":core"))
    // The record format and contract names; the agent itself is bundled below, never a dependency.
    compileOnly(project(":agent"))
    implementation(kotlin("stdlib"))
    // What core compiles against, at the versions the Gradle plugin bundles; relocated below.
    implementation("org.jacoco:org.jacoco.core:0.8.12")
    implementation("org.ow2.asm:asm:9.10.1")
    constraints {
        implementation("org.ow2.asm:asm-commons:9.10.1")
        implementation("org.ow2.asm:asm-tree:9.10.1")
    }
    compileOnly("org.apache.maven:maven-core:$mavenFloor")
    compileOnly("org.apache.maven:maven-plugin-api:$mavenFloor")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.1")
    compileOnly("javax.inject:javax.inject:1")
}

// Maven 3.9.0-3.9.5 skip an extension component compiled for anything newer than Java 11, and
// say nothing: the build runs as if the extension were absent.
java { targetCompatibility = JavaVersion.VERSION_11 }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        freeCompilerArgs.add("-Xjdk-release=11")
        allWarningsAsErrors = providers.gradleProperty("yoriwake.kotlinWarningsAsErrors").orNull?.toBooleanStrict() ?: true
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}

// Core's declarations are `internal`, as for the Gradle plugin.
val coreProject = project(":core")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    friendPaths.from(
        coreProject.tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory },
        coreProject.tasks.named<Jar>("jar").flatMap { it.archiveFile },
    )
}

// Bundled into the jar, so the descriptor names none.
val descriptorDependencies: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

mavenPlugin {
    artifactId = "yoriwake-maven-plugin"
    dependencies = descriptorDependencies
    name = "yoriwake"
    description = "Predictive test selection for Maven builds."
    goalPrefix = "yoriwake"
}

// The goals are Kotlin classes; the descriptor scans only Java's output by default.
tasks.named<org.gradlex.maven.plugin.development.task.GenerateMavenPluginDescriptorTask>("generateMavenPluginDescriptor") {
    classesDirs.setFrom(tasks.compileKotlin.flatMap { it.destinationDirectory })
    sourcesDirs.setFrom(layout.projectDirectory.dir("src/main/kotlin"))
}

// The agent jar ships inside the plugin jar, as in the Gradle plugin, so a POM never names it.
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

// The agent's classes as well as its jar: the jar goes to the test JVM, the classes let the decode
// read the records the agent writes, as in the Gradle plugin.
val unpackAgentClasses by tasks.registering(Sync::class) {
    inputs.files(agentJar)
    from({ agentJar.map { zipTree(it) } })
    into(layout.buildDirectory.dir("agent-classes"))
    // Its service registrations would put its listener on Maven's own class path.
    exclude { it.path.startsWith("META-INF/") && !it.path.startsWith("META-INF/licenses/") }
}

sourceSets.main {
    output.dir(mapOf("builtBy" to bundleAgent), layout.buildDirectory.dir("bundled-agent"))
    output.dir(mapOf("builtBy" to unpackAgentClasses), layout.buildDirectory.dir("agent-classes"))
}

// Every package bundled from a dependency moves under the plugin's own, so nothing of it can meet
// another plugin's copy in a Maven class realm.
val shadedPrefix = "io.github.zeuspizza.yoriwake.shaded.maven"

tasks.shadowJar {
    archiveClassifier = ""
    from(tasks.named("generateMavenPluginDescriptor"))
    // Classes only: core names the host's own Kotlin annotations in string constants, which must
    // keep naming `kotlin`, not this copy.
    relocate("kotlin", "$shadedPrefix.kotlin") { skipStringConstants = true }
    relocate("org.intellij.lang.annotations", "$shadedPrefix.annotations")
    relocate("org.jetbrains.annotations", "$shadedPrefix.annotations")
    relocate("org.jacoco.core", "$shadedPrefix.jacoco.core")
    relocate("org.objectweb.asm", "$shadedPrefix.asm")
    // The bundled libraries' own Maven metadata would name them as if they were this artifact.
    exclude("META-INF/*.kotlin_module", "META-INF/versions/**", "module-info.class", "META-INF/maven/org.*/**")
}
tasks.jar { archiveClassifier = "plain" }

// A relocation that rewrote core's descriptor strings would read every host class as not Kotlin,
// and nothing would fail: the jar is read back for the strings core matches on.
val checkShadedStrings by tasks.registering {
    description = "Fails if shading rewrote the Kotlin type names core matches host classes by."
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jar)
    doLast {
        val expected = mapOf(
            "io/github/zeuspizza/yoriwake/gradle/bytecode/InlinedSources" to "Lkotlin/Metadata;",
            "io/github/zeuspizza/yoriwake/gradle/bytecode/Recordability" to "Lkotlin/Metadata;",
            "io/github/zeuspizza/yoriwake/gradle/bytecode/AnnotationDigestVisitor" to "Lkotlin/jvm/internal/SourceDebugExtension;",
        )
        ZipFile(jar.get().asFile).use { zip ->
            for ((type, literal) in expected) {
                val bytes = zip.getInputStream(requireNotNull(zip.getEntry("$type.class")) { "$type is not in the jar" }).readBytes()
                check(literal in String(bytes, Charsets.ISO_8859_1)) { "$type no longer holds \"$literal\": shading rewrote it" }
            }
        }
    }
}
tasks.check { dependsOn(checkShadedStrings) }
tasks.assemble { dependsOn(tasks.shadowJar) }

val functionalRepository = layout.buildDirectory.dir("functional-repo")

// Maven Central takes a version only with sources and javadoc beside the jar. The javadoc jar is
// empty: the plugin's API is its configuration, which docs/reference.md documents.
val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier = "sources"
    from(sourceSets.main.map { it.allSource })
}
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier = "javadoc"
}

// What the release ships: the release workflow copies it beside the Gradle plugin's repository.
val localRepository = layout.buildDirectory.dir("local-repository")

publishing {
    publications {
        create<MavenPublication>("mavenPlugin") {
            artifactId = "yoriwake-maven-plugin"
            artifact(tasks.shadowJar)
            artifact(sourcesJar)
            artifact(javadocJar)
            pom {
                packaging = "maven-plugin"
                name = "yoriwake"
                description = "Predictive test selection for Maven builds."
                url = "https://github.com/zeuspizza/yoriwake"
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
                // Names the project rather than a person, as the Gradle plugin's POM does.
                developers {
                    developer {
                        id = "zeuspizza"
                        name = "The yoriwake authors"
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "functional"
            url = uri(functionalRepository)
        }
        maven {
            name = "buildLocal"
            url = uri(localRepository)
        }
    }
}

val cleanLocalRepository by tasks.registering(Delete::class) { delete(localRepository) }
tasks.named("publishMavenPluginPublicationToBuildLocalRepository") { dependsOn(cleanLocalRepository) }
// Part of `build`, so the release's one build leaves the Maven plugin as Central takes it.
tasks.build { dependsOn("publishMavenPluginPublicationToBuildLocalRepository") }

// Real Maven distributions over the fixtures: one fixture copy per test, one distribution per
// entry of the supported matrix, downloaded once into the Gradle user home and checked against
// the SHA-512 Apache publishes.
val functionalTest by sourceSets.creating
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "functionalTestImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "functionalTestImplementation"("org.junit.jupiter:junit-jupiter")
    "functionalTestImplementation"(kotlin("test"))
    "functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// The suite's local repository keeps a release version once resolved; a rebuild at that version must reach it.
val cleanFunctionalRepository by tasks.registering(Delete::class) {
    delete(functionalRepository, layout.buildDirectory.dir("functional-m2/io/github/zeuspizza/yoriwake-maven-plugin"))
}
tasks.named("publishMavenPluginPublicationToFunctionalRepository") { dependsOn(cleanFunctionalRepository) }

tasks.register<Test>("functionalTest") {
    description = "Runs real Maven builds over fixtures with the published plugin."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn("publishMavenPluginPublicationToFunctionalRepository")
    val repo = functionalRepository
    val dists = gradle.gradleUserHomeDir.resolve("yoriwake-maven-distributions")
    inputs.dir(repo).withPropertyName("functionalRepository")
    inputs.dir(rootProject.layout.projectDirectory.dir("samples/demo-maven")).withPropertyName("demo")
    systemProperty("yoriwake.functionalRepo", repo.get().asFile.absolutePath)
    systemProperty("yoriwake.localRepo", layout.buildDirectory.dir("functional-m2").get().asFile.absolutePath)
    systemProperty("yoriwake.pluginVersion", version.toString())
    systemProperty("yoriwake.mavenDistributions", dists.absolutePath)
    systemProperty("yoriwake.demo", rootProject.file("samples/demo-maven").absolutePath)
    // One Maven build at a time: they share a local repository, so each library is downloaded once.
    maxParallelForks = 1
}
