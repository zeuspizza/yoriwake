package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

/**
 * Scopes the host's own `jacoco-maven-plugin` agent to the reactor's packages, as the Gradle plugin
 * scopes the `jacoco` extension: the host's `includes` are adopted, never replaced, and classes with
 * no location are instrumented with the JDK's reflection loaders kept out.
 */
internal object JacocoAdoption {

    const val JACOCO = "org.jacoco:jacoco-maven-plugin"

    /** The goal whose agent reaches Surefire's JVMs, or Failsafe's. */
    const val PREPARE_AGENT = "prepare-agent"
    const val PREPARE_AGENT_INTEGRATION = "prepare-agent-integration"

    /** JaCoCo's default `exclclassloader`, which naming any loader replaces. */
    private const val DEFAULT_EXCLUDED_LOADER = "sun.reflect.DelegatingClassLoader"
    private val REFLECTION_ACCESSOR_CLASSLOADERS = listOf(DEFAULT_EXCLUDED_LOADER, "jdk.internal.reflect.DelegatingClassLoader")

    /** The execution of [goal] in [project], with its configuration as the POM merged it. */
    fun execution(project: MavenProject, goal: String): PluginExecution? =
        project.getPlugin(JACOCO)?.executions?.firstOrNull { goal in it.goals }

    /**
     * Whether [project]'s agent for [goal] can reach a test JVM at all, as a decline; null when it
     * can. Asked before anything is changed.
     */
    fun decline(project: MavenProject, goal: String, session: MavenSession): ScopeOutcome.NotApplied? {
        val execution = execution(project, goal) ?: return ScopeOutcome.NotApplied(
            "${project.artifactId} has no jacoco-maven-plugin $goal execution, so no coverage is recorded at all",
            ScopeOutcome.NotApplied.Kind.NO_JACOCO_PLUGIN,
        )
        val configured = (execution.configuration as? Xpp3Dom)?.getChild("skip")?.value?.trim()
        val skip = configured?.takeUnless { it.startsWith("\${") }
            ?: session.userProperties.getProperty("jacoco.skip")
            ?: project.properties.getProperty("jacoco.skip")
        if (skip.equals("true", ignoreCase = true)) {
            return ScopeOutcome.NotApplied(
                "${project.artifactId}'s jacoco-maven-plugin $goal is skipped, so no coverage agent reaches the test JVM",
                ScopeOutcome.NotApplied.Kind.JACOCO_DISABLED,
            )
        }
        return null
    }

    /** Scopes [project]'s agent for [goal]; [decline] has said it reaches a test JVM. */
    fun adopt(project: MavenProject, goal: String, derived: List<String>?): ScopeOutcome {
        val execution = requireNotNull(execution(project, goal))
        val configuration = (execution.configuration as? Xpp3Dom) ?: Xpp3Dom("configuration").also { execution.configuration = it }

        // Before either early return below, as on Gradle: a build with its own includes would
        // otherwise keep classes with no location out of every record.
        val excluded = configuration.getChild("exclClassLoaders")?.value?.split(':')?.filter(String::isNotBlank)
            ?: listOf(DEFAULT_EXCLUDED_LOADER)
        set(configuration, "exclClassLoaders", (excluded + REFLECTION_ACCESSOR_CLASSLOADERS).distinct().joinToString(":"))
        set(configuration, "inclNoLocationClasses", "true")

        val existing = configuration.getChild("includes")?.children?.mapNotNull { it.value?.trim() }?.filter(String::isNotEmpty)
        if (!existing.isNullOrEmpty()) return ScopeOutcome.AdoptedFromHost(existing)
        if (derived == null) {
            return ScopeOutcome.NotApplied(
                "no packages were found in this reactor's sources, so no scope could be derived",
                ScopeOutcome.NotApplied.Kind.NO_PACKAGES_DERIVED,
            )
        }
        configuration.getChild("includes")?.let { configuration.removeChild(configuration.children.indexOf(it)) }
        configuration.addChild(Xpp3Dom("includes").apply { derived.forEach { addChild(Xpp3Dom("include").apply { value = it }) } })
        return ScopeOutcome.Applied(derived)
    }

    private fun set(configuration: Xpp3Dom, name: String, value: String) {
        val child = configuration.getChild(name) ?: Xpp3Dom(name).also(configuration::addChild)
        child.value = value
    }
}
