package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView
import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.io.File

/**
 * What the shared rules ask of an execution's classpath, from Maven's model of the reactor.
 * [configuration] is the execution's own, whose fork arguments and added classpath can reach
 * another module's files; [failsafe] is whether Failsafe runs it, which names the properties it reads.
 */
internal fun classpathView(
    session: MavenSession,
    root: File,
    project: MavenProject,
    classpath: List<File>,
    configuration: Xpp3Dom?,
    failsafe: Boolean,
): ClasspathView =
    object : ClasspathView {
        override val rootDir = root
        override val moduleDirs = session.allProjects.associate { module ->
            module.basedir.relativeTo(root).invariantSeparatorsPath to module.id
        }
        override val buildDirs = session.allProjects.associate { it.id to it.build.directory }
        override val dependencyHomes = dependencyHomes(session)
        override val ownPath: String = project.id
        override fun classpathFiles(): Set<File> = classpath.toSet()
        override fun testOutputFiles(): Set<File> = setOf(File(project.build.testOutputDirectory))
        override fun compileTimeFiles(): Set<File> =
            runCatching { project.compileClasspathElements.map(::File).toSet() }.getOrDefault(emptySet())
        override fun declaredModules(): Set<String>? = reactorModulesNamed(session, root, project, configuration, failsafe)
        override fun jacocoVersion(): String? = project.getPlugin(JacocoAdoption.JACOCO)?.version.orEmpty()
        override fun jacocoSetting(): String = "jacoco-maven-plugin `<version>`"
        override fun testJvm(): Int? = null
        override fun agentCeiling(): String? = null
    }

/**
 * The ids of the reactor modules [project]'s build names, other than itself: by coordinates in a
 * dependency, a plugin, an extension or any plugin's configuration, or by a path into the module
 * in the execution's fork arguments or added classpath. Naming one more than the build uses only
 * drops less. Null when coordinates or a fork value it reads cannot be resolved here.
 */
internal fun reactorModulesNamed(
    session: MavenSession,
    root: File,
    project: MavenProject,
    configuration: Xpp3Dom?,
    failsafe: Boolean,
): Set<String>? {
    val reactor = session.allProjects.associateBy { "${it.groupId}:${it.artifactId}" }
    val named = mutableSetOf<MavenProject>()
    fun coordinates(groupId: String?, artifactId: String?) {
        reactor["$groupId:$artifactId"]?.let(named::add)
    }
    project.artifacts.forEach { coordinates(it.groupId, it.artifactId) }
    project.dependencies.forEach { coordinates(it.groupId, it.artifactId) }
    project.build.extensions.orEmpty().forEach { coordinates(it.groupId, it.artifactId) }
    val configurations = mutableListOf<Xpp3Dom>()
    project.buildPlugins.forEach { plugin ->
        coordinates(plugin.groupId, plugin.artifactId)
        plugin.dependencies.forEach { coordinates(it.groupId, it.artifactId) }
        (plugin.configuration as? Xpp3Dom)?.let(configurations::add)
        plugin.executions.forEach { execution -> (execution.configuration as? Xpp3Dom)?.let(configurations::add) }
    }
    val written = reactor.map { (key, module) -> Regex("""(^|[\s,])${Regex.escape(key)}(:|$|[\s,])""") to module }
    if (!configurations.all { namedIn(it, reactor, written, named) { value -> resolve(project, session, value) } }) return null

    // What the fork is handed by path: its arguments, classes, working directory, system properties
    // and environment, and what every JVM reads. A system properties file's values are literal; a
    // file whose path cannot be resolved, or that cannot be read, hands over properties unknown here.
    configuration?.getChild(ForkSystemProperty.SYSTEM_PROPERTIES_FILE)?.value?.let { resolve(project, session, it) ?: return null }
    val forked = forkSystemProperties(project, session, configuration, failsafe)
    if (forked.any { it.name == ForkSystemProperty.UNREADABLE_FILE }) return null
    val added = configuration?.getChild("additionalClasspathElements")
    val values = listOfNotNull(
        configuration?.getChild("argLine")?.value,
        added?.value,
        configuration?.getChild("classesDirectory")?.value,
        configuration?.getChild("testClassesDirectory")?.value,
        configuration?.getChild("workingDirectory")?.value,
        toolOptionsOf(configuration),
        configuration?.getChild("environmentVariables")?.getChild("JDK_JAVA_OPTIONS")?.value ?: System.getenv("JDK_JAVA_OPTIONS"),
    ) + added?.children.orEmpty().mapNotNull { it.value } +
        forked.filter { it.fromPom }.map { it.value } +
        configuration?.getChild("environmentVariables")?.children.orEmpty().mapNotNull { it.value } +
        // Surefire hands the invocation's user properties to the fork as system properties.
        session.userProperties.stringPropertyNames().map { session.userProperties.getProperty(it) }
    // yoriwake's own agent and run file, which wiring adds under the reactor root.
    val ours = File(root, Recording.MAPS_HOME).toPath().normalize()
    val dirs = session.allProjects.map { it to it.basedir.toPath().normalize() }
    for ((value, literal) in values.map { it to false } + forked.filterNot { it.fromPom }.map { it.value to true }) {
        val text = if (literal) value else resolveForFork(project, session, value) ?: return null
        PATH.findAll(text).forEach { piece ->
            val path = File(piece.value).let { if (it.isAbsolute) it else File(project.basedir, piece.value) }.toPath().normalize()
            // A directory above a module, such as the reactor root, hands over every module under it.
            if (!path.startsWith(ours)) {
                dirs.filter { (_, dir) -> path.startsWith(dir) || dir.startsWith(path) }.forEach { (module, _) -> named += module }
            }
        }
    }
    return named.mapTo(mutableSetOf()) { it.id } - project.id
}

/** The `JAVA_TOOL_OPTIONS` the fork reads: its configured environment's, else Maven's own. */
internal fun toolOptionsOf(configuration: Xpp3Dom?): String? =
    configuration?.getChild("environmentVariables")?.getChild("JAVA_TOOL_OPTIONS")?.value ?: System.getenv("JAVA_TOOL_OPTIONS")

/**
 * Adds every reactor module that a `groupId` and `artifactId` pair under one element names, at any
 * depth, or that a `groupId:artifactId` string in a value names. False when a pair would not resolve.
 */
private fun namedIn(
    node: Xpp3Dom,
    reactor: Map<String, MavenProject>,
    written: List<Pair<Regex, MavenProject>>,
    named: MutableSet<MavenProject>,
    resolve: (String) -> String?,
): Boolean {
    val groupId = node.getChild("groupId")?.value
    val artifactId = node.getChild("artifactId")?.value
    if (groupId != null && artifactId != null) {
        val key = "${resolve(groupId) ?: return false}:${resolve(artifactId) ?: return false}"
        reactor[key]?.let(named::add)
    }
    // `<artifact>g:a:v</artifact>` and its kin; an unresolved part only leaves a module unnamed here.
    node.value?.takeIf { ':' in it }?.let { value ->
        val text = EXPRESSION.replace(value) { m -> resolve(m.value).orEmpty() }
        written.forEach { (pattern, module) -> if (pattern.containsMatchIn(text)) named += module }
    }
    return node.children.all { namedIn(it, reactor, written, named, resolve) }
}

/** [value] with its `${x}` and `@{x}` resolved, or null when one of them names nothing set. */
private fun resolve(project: MavenProject, session: MavenSession, value: String): String? {
    var unresolved = false
    val text = EXPRESSION.replace(value) { m -> Declines.property(project, session, m.groupValues[1]) ?: "".also { unresolved = true } }
    return text.takeUnless { unresolved }
}

/**
 * As [resolve], for a value the fork receives: a value that is one unset expression is no value, as
 * Maven then passes no parameter, and an unset `argLine` is left out, as wiring leaves it out.
 */
private fun resolveForFork(project: MavenProject, session: MavenSession, value: String): String? {
    val trimmed = value.trim()
    EXPRESSION.matchEntire(trimmed)?.let { m -> return Declines.property(project, session, m.groupValues[1]).orEmpty() }
    val text = if (Declines.property(project, session, "argLine") != null) trimmed
    else trimmed.replace("\${argLine}", "").replace("@{argLine}", "")
    return resolve(project, session, text)
}

private val EXPRESSION = Regex("""[$@]\{([^}]+)}""")

/** A path in a command line or a list: anything holding a separator, up to a delimiter. */
private val PATH = Regex("""(?:[A-Za-z]:)?[^\s=,;:"']*[/\\][^\s=,;:"']*""")

/** Where a Maven test classpath takes dependencies from; see [ClasspathView.dependencyHomes]. */
internal fun dependencyHomes(session: MavenSession): Map<String, File> = mapOf(
    ClasspathFiles.MAVEN_REPOSITORY to File(session.localRepository?.basedir ?: File(System.getProperty("user.home"), ".m2/repository").path),
    ClasspathFiles.USER_HOME to File(System.getProperty("user.home").orEmpty()),
)
