import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    `java-gradle-plugin`
    `maven-publish`
    jacoco
    kotlin("jvm") version "2.1.20"
    id("com.gradle.plugin-publish") version "2.2.1"
    // Relocates the plugin's own dependencies so it adds nothing unshaded to a host's buildscript
    // classpath. 9.2.x is the newest line that still runs on this build's Gradle 8.14.
    id("com.gradleup.shadow") version "9.2.2"
    // The SBOM of what the published jar bundles: CycloneDX, merged with the agent's below.
    id("org.cyclonedx.bom") version "3.5.1"
    signing
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
            tags = listOf("testing", "test-selection", "test-impact-analysis", "regression-test-selection", "incremental-testing", "junit", "coverage", "ci", "windows", "kotlin-multiplatform")
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

// A release ships the bytes `verify` built and tested: the publish job downloads that build's local
// repository, signs every file in it and uploads them, building nothing. `-Pyoriwake.repository`
// names the directory; this build's own local repository otherwise.
val releaseRepository = providers.gradleProperty("yoriwake.repository").map { file(it) }
    .orElse(localRepository.map { it.asFile })
// Signing needs the release key, which only the publish job holds. With `yoriwake.sign` and no key
// the signing task fails, so a release can never go out unsigned by accident.
val signRelease = providers.gradleProperty("yoriwake.sign").isPresent

signing {
    isRequired = signRelease
    providers.gradleProperty("signingKey").orNull?.let { key ->
        useInMemoryPgpKeys(key, providers.gradleProperty("signingPassword").orNull)
    }
}
// plugin-publish signs both publications whenever `signing` is applied; only the release asks.
tasks.withType<Sign>().configureEach {
    onlyIf("yoriwake.sign is set") { signRelease }
}

val signReleaseRepository by tasks.registering(Sign::class) {
    description = "Signs every file of the release repository, for the Portal upload."
    sign(*fileTree(releaseRepository) {
        exclude("**/*.asc", "**/*.md5", "**/*.sha1", "**/*.sha256", "**/*.sha512", "**/maven-metadata.xml*")
    }.files.sorted().toTypedArray())
}

// plugin-publish's PublishExistingTask (2.2.1) sends no feature compatibility, and the Portal refuses
// a version that drops a declaration an earlier one made: 0.2.0 declared the configuration cache.
// This is that task's own upload, with the declarations of the descriptor inside the jar it uploads
// added as `publishPlugins` adds them. Its parts are private to plugin-publish, hence reflection.
fun uploadWithDeclaredFeatures(task: com.gradle.publish.PublishExistingTask) {
    val type = com.gradle.publish.PublishExistingTask::class.java
    fun method(name: String, vararg params: Class<*>) = type.getDeclaredMethod(name, *params).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val requests = method("buildPublishRequests").invoke(task) as List<com.gradle.publish.protocols.v1.models.publish.NewVersionRequest>
    val id = org.gradle.api.internal.notations.ComponentIdentifierParserFactory().create()!!
        .parseNotation(task.pluginCoordinates.get()) as org.gradle.api.artifacts.component.ModuleComponentIdentifier
    val dir = task.fileRepositoryRoot.dir("${id.group.replace('.', '/')}/${id.module}/${id.version}")
    val artifacts = method("collectArtifacts", org.gradle.api.artifacts.component.ModuleComponentIdentifier::class.java,
        org.gradle.api.file.Directory::class.java).invoke(task, id, dir.get()) as Map<*, *>
    method("validateArtifacts", Map::class.java, Provider::class.java).invoke(task, artifacts, dir)
    val jar = dir.get().file("${id.module}-${id.version}.jar").asFile
    val descriptor = Properties().apply {
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry("META-INF/gradle-plugins/${task.pluginId.get()}.properties")
                ?: error("$jar has no descriptor for ${task.pluginId.get()}")
            zip.getInputStream(entry).use { load(it) }
        }
    }
    val prefix = "compatibility.feature."
    val features = descriptor.stringPropertyNames().filter { it.startsWith(prefix) }.sorted()
        .associate { it.removePrefix(prefix) to descriptor.getProperty(it) }
    // A descriptor without declarations would upload a version that withdraws them, which the
    // Portal refuses only after the signed files have been sent.
    check(features.isNotEmpty()) { "$jar declares no feature compatibility; the Portal refuses a version that drops one" }
    requests.forEach { request -> features.forEach { (feature, state) -> request.addFeatureCompatibility(feature, state) } }
    logger.lifecycle("Portal feature compatibility: " + features.entries.joinToString { "${it.key}=${it.value}" })
    val publisher = type.getDeclaredField("portalPublisher").apply { isAccessible = true }.get(task)
    val publish = publisher.javaClass.getDeclaredMethod("publishToPortal", List::class.java,
        com.gradle.publish.protocols.v1.models.publish.PublishMavenCoordinates::class.java, Map::class.java)
        .apply { isAccessible = true }
    // The Portal's own refusal, not reflection's wrapper around it.
    try {
        publish.invoke(publisher, requests,
            com.gradle.publish.protocols.v1.models.publish.PublishMavenCoordinates(id.group, id.module, id.version), artifacts)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
    }
}

val publishReleaseRepository by tasks.registering(com.gradle.publish.PublishExistingTask::class) {
    description = "Uploads the signed release repository to the Plugin Portal, building nothing."
    // The task's own upload is replaced by the same upload with the declared features (above).
    actions.clear()
    dependsOn(signReleaseRepository)
    // Without it the signing above is skipped, and an unsigned upload cannot be taken back.
    val signed = signRelease
    doFirst { check(signed) { "a release is uploaded signed: pass -Pyoriwake.sign and the release key" } }
    val plugin = gradlePlugin.plugins.getByName("yoriwake")
    fileRepositoryRoot = layout.dir(releaseRepository)
    pluginId = plugin.id
    displayName = plugin.displayName
    pluginDescription = plugin.description
    tags = plugin.tags.map { it.toList() }
    website = gradlePlugin.website
    vcsUrl = gradlePlugin.vcsUrl
    pluginVersion = version.toString()
    pluginCoordinates = "io.github.zeuspizza:yoriwake-gradle-plugin:$version"
    doLast { uploadWithDeclaredFeatures(this as com.gradle.publish.PublishExistingTask) }
}

// The published jar, with JaCoCo core and ASM relocated. Only `org.jacoco.core` is relocated: the
// agent classes name the host's `org.jacoco.agent.rt.RT` by string, which must stay the host's.
val relocations = mapOf(
    "org.jacoco.core" to "io.github.zeuspizza.yoriwake.shaded.plugin.jacoco.core",
    "org.objectweb.asm" to "io.github.zeuspizza.yoriwake.shaded.plugin.asm",
)

tasks.shadowJar {
    archiveClassifier = ""
    relocations.forEach { (from, to) -> relocate(from, to) }
    // Upstream metadata would name unshaded coordinates this jar does not actually carry.
    exclude("module-info.class", "META-INF/versions/*/module-info.class", "META-INF/maven/**", "about.html")
    from(files(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
}

// The release's SBOM: the libraries the plugin jar bundles, the agent nested with its own, and for
// each the package it was relocated to. The direct BOMs see only the configurations the shadow
// jars are built from; the merge adds what CycloneDX does not know, the nesting and relocation.
// Which package each bundled library's classes live in, so its relocation can be named.
val packageOf = mapOf("org.jacoco:org.jacoco.core" to "org.jacoco.core", "org.ow2.asm" to "org.objectweb.asm")
val sbomFile = layout.buildDirectory.file("sbom/yoriwake-gradle-plugin-$version.cdx.json")

tasks.cyclonedxDirectBom { includeConfigs = listOf("runtimeClasspath") }

val mergeSbom by tasks.registering {
    description = "Writes the release's SBOM: what the plugin jar bundles, with the agent nested."
    dependsOn(tasks.cyclonedxDirectBom, ":agent:cyclonedxDirectBom")
    val pluginBom = layout.buildDirectory.file("reports/cyclonedx-direct/bom.json")
    val agentBom = project(":agent").layout.buildDirectory.file("reports/cyclonedx-direct/bom.json")
    @Suppress("UNCHECKED_CAST")
    val agentRelocations = project(":agent").extra["relocations"] as Map<String, String>
    val pluginRelocations = relocations
    val packages = packageOf
    val v = version.toString()
    val out = sbomFile
    inputs.files(pluginBom, agentBom)
    inputs.property("version", v)
    inputs.property("relocations", pluginRelocations + agentRelocations.mapKeys { "agent:" + it.key })
    outputs.file(out)
    doLast {
        fun components(bom: File) = ((groovy.json.JsonSlurper().parse(bom) as Map<*, *>)["components"] as List<*>?)
            .orEmpty().map { it as Map<*, *> }
        fun library(c: Map<*, *>, moved: Map<String, String>): Map<String, Any?> {
            val pkg = packages["${c["group"]}:${c["name"]}"] ?: packages[c["group"]]
            val to = pkg?.let(moved::get)
            return linkedMapOf(
                "type" to "library", "bom-ref" to c["purl"], "group" to c["group"], "name" to c["name"],
                "version" to c["version"], "purl" to c["purl"], "hashes" to c["hashes"], "licenses" to c["licenses"],
                "properties" to to?.let { listOf(mapOf("name" to "yoriwake:relocated-to", "value" to it)) },
            ).filterValues { it != null }
        }
        fun purl(name: String) = "pkg:maven/io.github.zeuspizza/$name@$v?type=jar"
        val agent = linkedMapOf(
            "type" to "library", "bom-ref" to purl("yoriwake-agent"), "group" to "io.github.zeuspizza",
            "name" to "yoriwake-agent", "version" to v, "purl" to purl("yoriwake-agent"),
            "description" to "The test JVM's agent: nested in the plugin jar as yoriwake-agent.jar, and its classes unpacked into it",
            "components" to components(agentBom.get().asFile).map { library(it, agentRelocations) },
        )
        val bom = linkedMapOf(
            "bomFormat" to "CycloneDX", "specVersion" to "1.6", "version" to 1,
            "metadata" to mapOf(
                "component" to mapOf(
                    "type" to "library", "bom-ref" to purl("yoriwake-gradle-plugin"), "group" to "io.github.zeuspizza",
                    "name" to "yoriwake-gradle-plugin", "version" to v, "purl" to purl("yoriwake-gradle-plugin"),
                ),
            ),
            "components" to components(pluginBom.get().asFile).map { library(it, pluginRelocations) } + agent,
        )
        // GitHub's attestation refuses a CycloneDX document without a serial number. A random one
        // would make two builds of one commit differ, so it is derived from the document itself.
        val content = groovy.json.JsonOutput.toJson(bom)
        val serial = "urn:uuid:" + UUID.nameUUIDFromBytes(content.toByteArray(Charsets.UTF_8))
        val withSerial = linkedMapOf<String, Any?>("bomFormat" to bom["bomFormat"], "specVersion" to bom["specVersion"],
            "serialNumber" to serial) + bom
        out.get().asFile.apply { parentFile.mkdirs() }
            .writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(withSerial)) + "\n")
    }
}

// An SBOM that drifts from the jar is worse than none: the libraries it lists are the ones the
// plugin jar resolves to bundle, and every relocated package it names is one the jars carry.
val checkSbom by tasks.registering {
    description = "Fails if the SBOM and the jars disagree on what they bundle."
    val sbom = sbomFile
    dependsOn(mergeSbom)
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    val resolved = configurations.runtimeClasspath.get().incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.mapNotNull { it.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { "pkg:maven/${it.group}/${it.module}@${it.version}?type=jar" }.toSet()
    }
    // Every package the build relocates anything into, the agent's included.
    @Suppress("UNCHECKED_CAST")
    val targets = (relocations.values + (project(":agent").extra["relocations"] as Map<String, String>).values)
        .map { it.replace('.', '/') + "/" }.toSet()
    inputs.file(sbom)
    inputs.file(jar)
    inputs.property("resolved", resolved)
    inputs.property("targets", targets)
    doLast {
        val bom = groovy.json.JsonSlurper().parse(sbom.get().asFile) as Map<*, *>
        // What GitHub's attestation reads a document as CycloneDX by; without any of them the
        // release's attest job refuses the SBOM after the tag is pushed.
        check(listOf("bomFormat", "specVersion", "serialNumber").all { !(bom[it] as String?).isNullOrEmpty() }) {
            "the SBOM lacks bomFormat, specVersion or serialNumber, which GitHub's attestation requires"
        }
        val listed = (bom["components"] as List<*>).map { it as Map<*, *> }
        val agent = listed.single { (it["name"] as String) == "yoriwake-agent" }
        val libraries = listed.filter { it !== agent }.map { it["purl"] as String }.toSet()
        check(libraries == resolved.get()) {
            "the SBOM lists $libraries, but the plugin jar bundles ${resolved.get()}"
        }
        fun relocatedTo(components: List<*>) = components.flatMap { c ->
            ((c as Map<*, *>)["properties"] as List<*>?).orEmpty().map { (it as Map<*, *>)["value"] as String }
        }.map { it.replace('.', '/') + "/" }.toSet()
        // The relocation targets a jar has classes under; a shaded class under none of them is a
        // library the build bundles without a relocation the SBOM could name.
        fun shadedRoots(entries: List<String>): Set<String> {
            val classes = entries.filter { it.startsWith("io/github/zeuspizza/yoriwake/shaded/") && it.endsWith(".class") }
            val unnamed = classes.filter { c -> targets.none(c::startsWith) }
            check(unnamed.isEmpty()) { "shaded classes outside every relocation: ${unnamed.take(5)}" }
            return targets.filter { t -> classes.any { it.startsWith(t) } }.toSet()
        }
        ZipFile(jar.get().asFile).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            // The agent's classes are unpacked into the plugin jar as well, so its relocations are there too.
            val named = relocatedTo(listed - agent) + relocatedTo(agent["components"] as List<*>)
            check(shadedRoots(entries) == named) {
                "the plugin jar carries ${shadedRoots(entries)}, the SBOM names $named"
            }
            val nested = ZipInputStream(zip.getInputStream(zip.getEntry("yoriwake-agent.jar"))).use { s ->
                generateSequence { s.nextEntry }.map { it.name }.toList()
            }
            val agentComponents = agent["components"] as List<*>
            check(shadedRoots(nested) == relocatedTo(agentComponents)) {
                "the agent jar carries ${shadedRoots(nested)}, the SBOM names ${relocatedTo(agentComponents)}"
            }
        }
    }
}
tasks.check { dependsOn(checkSbom) }

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
    // ReferenceTest and PublishedTokensTest check these docs against the sources' text, so an edit
    // to a doc alone, or a comment-only edit, must rerun the tests.
    inputs.files(rootProject.file("docs/reference.md"), rootProject.file("docs/contract.md"))
        .withPropertyName("checkedDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(layout.projectDirectory.dir("src/main"), rootProject.file("agent/src/main"))
        .withPropertyName("mainSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
                        dependsOn(mergeSbom)
                        systemProperty("yoriwake.sbom", sbomFile.get().asFile.absolutePath)
                        systemProperty("yoriwake.jacocoVersion", jacocoVersion)
                        systemProperty("yoriwake.asmVersion", asmVersion)
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
