package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.apache.maven.artifact.versioning.ComparableVersion
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
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

    fun of(plugin: Plugin, execution: PluginExecution, session: MavenSession): ScopeOutcome.NotApplied? {
        val version = plugin.version
        if (version != null && ComparableVersion(version) < SUREFIRE_FLOOR) {
            return ScopeOutcome.NotApplied(
                "${plugin.artifactId} $version is older than $SUREFIRE_FLOOR",
                ScopeOutcome.NotApplied.Kind.SUREFIRE_VERSION,
            )
        }
        val forkCount = (execution.configuration as? Xpp3Dom)?.getChild("forkCount")?.value?.trim()
            ?: session.userProperties.getProperty("forkCount")
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

    /** The JVMs `forkCount` asks for: a number, or a multiple of the processors with a `C`. */
    private fun forks(value: String): Int? = if (value.endsWith("C", ignoreCase = true)) {
        value.dropLast(1).toDoubleOrNull()?.let { (it * Runtime.getRuntime().availableProcessors()).toInt() }
    } else {
        value.toIntOrNull()
    }
}
