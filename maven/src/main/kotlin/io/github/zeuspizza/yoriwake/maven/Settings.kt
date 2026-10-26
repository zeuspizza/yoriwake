package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.change.LargeChange
import org.apache.maven.MavenExecutionException
import org.apache.maven.execution.MavenSession
import org.codehaus.plexus.util.xml.Xpp3Dom

/**
 * What the build asks of yoriwake: the plugin's `<configuration>` in the POM, overridden by a user
 * property (`-Dyoriwake.enabled=false`). The names and the strict boolean parsing are the Gradle
 * plugin's, so one spelling works on both.
 */
internal class Settings(
    val enabled: Boolean,
    val alwaysRun: List<String>,
    /** Branches on which a selecting run runs everything, from `<fullRunBranches>`; no `-D` form, as on Gradle. */
    val fullRunBranches: List<String> = emptyList(),
    /** How many changed paths make a selecting run run everything without analysing them. */
    val largeChangeThreshold: Int = LargeChange.DEFAULT_THRESHOLD,
) {

    companion object {
        const val PLUGIN_KEY = "io.github.zeuspizza:yoriwake-maven-plugin"
        const val ENABLED = "yoriwake.enabled"
        const val ALWAYS_RUN = "yoriwake.alwaysRun"

        fun of(session: MavenSession): Settings {
            val configuration = session.projects.firstNotNullOfOrNull { it.getPlugin(PLUGIN_KEY) }?.configuration as? Xpp3Dom
            val enabled = session.userProperties.getProperty(ENABLED)?.let { parseFlag(ENABLED, it) }
                ?: configuration?.getChild("enabled")?.value?.let { parseFlag("<enabled>", it.trim()) }
                ?: true
            val alwaysRun = session.userProperties.getProperty(ALWAYS_RUN)?.split(',')
                ?: configuration?.getChild("alwaysRun")?.children?.map { it.value.orEmpty() }
                ?: emptyList()
            val owner = "the yoriwake plugin's <configuration>"
            val fullRunBranches = try {
                RunPlan.fullRunBranches(
                    configuration?.getChild("fullRunBranches")?.children?.map { it.value.orEmpty() }.orEmpty(),
                    owner, "<fullRunBranches><branch>main</branch></fullRunBranches>",
                )
            } catch (rejected: RunPlan.Rejected) {
                throw MavenExecutionException(rejected.message, null as Throwable?)
            }
            val largeChange = configuration?.getChild("largeChangeThreshold")?.value?.trim()?.let { text ->
                text.toIntOrNull()?.takeIf { it >= 1 } ?: throw MavenExecutionException(
                    "[yoriwake] $owner: largeChangeThreshold is $text; it counts changed paths, so it must be 1 or " +
                        "more. Set it above the repository's file count to never skip the analysis.",
                    null as Throwable?,
                )
            } ?: LargeChange.DEFAULT_THRESHOLD
            return Settings(enabled, alwaysRun.map(String::trim).filter(String::isNotEmpty), fullRunBranches, largeChange)
        }

        /** As the Gradle plugin's: the bare property is true, anything but true or false fails the build. */
        fun parseFlag(name: String, value: String): Boolean = when {
            value.isEmpty() || value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> throw MavenExecutionException(
                "[yoriwake] $name=$value is not a boolean. Use true or false.",
                null as Throwable?,
            )
        }
    }
}
