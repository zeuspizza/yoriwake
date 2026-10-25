package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.apache.maven.artifact.versioning.ComparableVersion
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

/**
 * Why a Surefire or Failsafe execution cannot be selected, decided before anything is changed. A
 * declined execution is left exactly as the host configured it and runs every test.
 */
internal object Declines {

    /** The oldest Surefire and Failsafe the Maven host was proven on. */
    private val SUREFIRE_FLOOR = ComparableVersion("3.0.0")

    /** Surefire's providers that never run the JUnit Platform's discovery. */
    private val LEGACY_PROVIDERS = setOf("surefire-junit3", "surefire-junit4", "surefire-junit47", "surefire-testng")

    fun of(project: MavenProject, plugin: Plugin, execution: PluginExecution, session: MavenSession): ScopeOutcome.NotApplied? {
        val version = plugin.version
        if (version != null && ComparableVersion(version) < SUREFIRE_FLOOR) {
            return ScopeOutcome.NotApplied(
                "${plugin.artifactId} $version is older than $SUREFIRE_FLOOR",
                ScopeOutcome.NotApplied.Kind.SUREFIRE_VERSION,
            )
        }
        val forkCount = (execution.configuration as? Xpp3Dom)?.getChild("forkCount")?.value?.trim()
            ?.takeUnless { it.startsWith("\${") }
            ?: property(project, session, "forkCount")
        if (forkCount != null && forks(forkCount) == 0) {
            return ScopeOutcome.NotApplied(
                "forkCount=$forkCount runs the tests inside Maven's own JVM, which no agent can join",
                ScopeOutcome.NotApplied.Kind.SUREFIRE_IN_PROCESS,
            )
        }
        // Only the provider the POM names: the one Surefire would pick from the test classpath is
        // known once that classpath is resolved, when the execution starts.
        plugin.dependencies.firstOrNull { it.artifactId in LEGACY_PROVIDERS }?.let {
            return ScopeOutcome.NotApplied(
                "the POM gives ${plugin.artifactId} the ${it.artifactId} provider, which runs no JUnit Platform discovery",
                ScopeOutcome.NotApplied.Kind.SUREFIRE_LEGACY_PROVIDER,
            )
        }
        return null
    }

    /**
     * The JVMs `forkCount` asks for: a number, or a multiple of the processors with a `C`, which
     * Surefire rounds up to one unless the multiple is zero.
     */
    private fun forks(value: String): Int? = if (value.endsWith("C", ignoreCase = true)) {
        value.dropLast(1).toDoubleOrNull()?.let { multiple ->
            val threads = multiple * Runtime.getRuntime().availableProcessors()
            if (threads > 0) maxOf(1, threads.toInt()) else 0
        }
    } else {
        value.toIntOrNull()
    }

    /**
     * A property as Maven resolves a plugin parameter's default: the command line's `-D`, then the
     * JVM's system properties (`MAVEN_OPTS`), then the project's own, inherited ones included.
     */
    fun property(project: MavenProject, session: MavenSession, name: String): String? =
        session.userProperties.getProperty(name)
            ?: session.systemProperties.getProperty(name)
            ?: project.properties.getProperty(name)
}
