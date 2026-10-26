package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

/**
 * Develocity's Test Distribution and Predictive Test Selection on a Surefire or Failsafe execution.
 * Either runs or chooses the execution's tests where yoriwake cannot see them, so the execution is
 * declined before any step of ours, and its map is left as it was. Both are switched on per goal,
 * under the execution's `<properties>` (`distribution`, `predictiveSelection`), and Predictive Test
 * Selection also by `-Dpts.enabled`. A value that cannot be read declines too.
 */
internal object DevelocityDetection {

    fun decline(project: MavenProject, execution: PluginExecution, session: MavenSession): ScopeOutcome.NotApplied? {
        if (!BuildCache.develocityLoaded()) return null
        val properties = (execution.configuration as? Xpp3Dom)?.getChild("properties")
        fun enabled(element: String): Boolean? {
            val raw = properties?.getChild(element)?.getChild("enabled")?.value?.trim() ?: return false
            val value = if (raw.startsWith("\${") && raw.endsWith("}")) {
                Declines.property(project, session, raw.removePrefix("\${").removeSuffix("}")) ?: return null
            } else {
                raw
            }
            return when {
                value.equals("true", ignoreCase = true) -> true
                value.equals("false", ignoreCase = true) -> false
                else -> null
            }
        }
        val distribution = enabled("distribution")
        val selection = (session.userProperties.getProperty("pts.enabled") ?: session.systemProperties.getProperty("pts.enabled"))
            ?.let { it.equals("true", ignoreCase = true) } ?: enabled("predictiveSelection")
        return when {
            distribution == true -> ScopeOutcome.NotApplied(
                "Develocity's Test Distribution runs its tests on other machines",
                ScopeOutcome.NotApplied.Kind.DEVELOCITY_TEST_DISTRIBUTION,
            )
            selection == true -> ScopeOutcome.NotApplied(
                "Develocity's Predictive Test Selection chooses its tests",
                ScopeOutcome.NotApplied.Kind.DEVELOCITY_TEST_SELECTION,
            )
            distribution == null || selection == null -> ScopeOutcome.NotApplied(
                "Develocity is loaded and whether its Test Distribution or Predictive Test Selection is on could not be read",
                ScopeOutcome.NotApplied.Kind.DEVELOCITY_UNDETERMINED,
            )
            else -> null
        }
    }
}
