package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.agent.select.AlwaysRun
import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.SettingNames
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.capture.Schedule
import io.github.zeuspizza.yoriwake.gradle.change.LargeChange
import org.apache.maven.MavenExecutionException
import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

/**
 * What the build asks of yoriwake: the plugin's `<configuration>` in the POM, overridden by a user
 * property (`-Dyoriwake.enabled=false`). The configuration names and the strict boolean parsing
 * are the Gradle plugin's, so one spelling works on both; `yoriwake.enabled` has no Gradle
 * counterpart, which switches off with `-Pyoriwake.disabled`.
 */
internal class Settings(
    val enabled: Boolean,
    /** Branches on which a selecting run runs everything, from `<fullRunBranches>`; no `-D` form, as on Gradle. */
    val fullRunBranches: List<String> = emptyList(),
    /** Above this many changed paths, a selecting run runs everything without analysing them. */
    val largeChangeThreshold: Int = LargeChange.DEFAULT_THRESHOLD,
    /** A selecting run runs everything once this many builds or days passed since the last full run. */
    val schedule: Schedule.Limits = Schedule.Limits(null, null),
) {

    /** What a module's environment fingerprint compares beyond the fixed variables, and leaves out. */
    class Environment(val systemPropertyIgnore: List<String>, val environmentInclude: List<String>)

    companion object {
        const val PLUGIN_KEY = "io.github.zeuspizza:yoriwake-maven-plugin"
        const val ENABLED = "yoriwake.enabled"
        const val ALWAYS_RUN = "yoriwake.alwaysRun"

        fun of(session: MavenSession): Settings {
            val configuration = session.projects.firstNotNullOfOrNull { it.getPlugin(PLUGIN_KEY) }?.configuration as? Xpp3Dom
            val enabled = session.userProperties.getProperty(ENABLED)?.let { parseFlag(ENABLED, it) }
                ?: configuration?.getChild("enabled")?.value?.let { parseFlag("<enabled>", it.trim()) }
                ?: true
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
            fun atLeastOne(name: String): Int? = configuration?.getChild(name)?.value?.trim()?.let { text ->
                text.toIntOrNull()?.takeIf { it >= 1 } ?: throw MavenExecutionException(
                    "[yoriwake] $owner: $name is $text; it counts builds or days between full runs, so it must be " +
                        "1 or more. Leave it unset to never schedule a full run.",
                    null as Throwable?,
                )
            }
            val schedule = Schedule.Limits(atLeastOne("fullRunEveryBuilds"), atLeastOne("fullRunEveryDays"))
            return Settings(enabled, fullRunBranches, largeChange, schedule)
        }

        /**
         * The tests [project]'s executions always run: `-Dyoriwake.alwaysRun` and the module's own
         * `<alwaysRun>`, read per module since the reactor-wide settings come from its first module. A
         * pattern that would pin every test fails the build, as on Gradle.
         */
        fun alwaysRunOf(project: MavenProject, session: MavenSession): List<String> {
            val configuration = project.getPlugin(PLUGIN_KEY)?.configuration as? Xpp3Dom
            return checked(session.userProperties.getProperty(ALWAYS_RUN)?.split(',').orEmpty(), "-D$ALWAYS_RUN") +
                checked(
                    configuration?.getChild("alwaysRun")?.children?.map { it.value.orEmpty() }.orEmpty(),
                    "${project.artifactId}'s <alwaysRun>",
                )
        }

        /**
         * [project]'s `<systemPropertyIgnore>` and `<environmentInclude>`, one name per child element,
         * read where `<alwaysRun>` is. A module with no plugin entry of its own (its parent is not the
         * reactor's root) takes the root's, so a variable the root lists is still compared there.
         * An entry that names nothing or everything fails the build.
         */
        fun environmentOf(project: MavenProject, session: MavenSession): Environment {
            val configuration = moduleConfiguration(project, session)
            fun names(list: String): List<String> {
                val element = configuration?.getChild(list) ?: return emptyList()
                if (element.childCount == 0 && !element.value.isNullOrBlank()) {
                    throw MavenExecutionException(
                        "[yoriwake] ${project.artifactId}: $list holds text, not names; list one name per child element, " +
                            "as <$list><name>${element.value.trim()}</name></$list>.",
                        null as Throwable?,
                    )
                }
                val names = element.children.map { it.value.orEmpty() }
                EnvironmentFingerprint.invalidNames(names, list, project.artifactId)?.let {
                    throw MavenExecutionException(it, null as Throwable?)
                }
                return names.map(String::trim)
            }
            return Environment(names("systemPropertyIgnore"), names("environmentInclude"))
        }

        /**
         * Whether [project]'s recording runs turn in-JVM parallel execution off and record one test at
         * a time: `-Dyoriwake.recordSequentially` over the module's `<recordSequentially>`.
         */
        fun recordSequentiallyOf(project: MavenProject, session: MavenSession): Boolean =
            session.userProperties.getProperty(SettingNames.RECORD_SEQUENTIALLY)?.let { parseFlag("-D${SettingNames.RECORD_SEQUENTIALLY}", it) }
                ?: moduleConfiguration(project, session)?.getChild("recordSequentially")?.value
                    ?.let { parseFlag("${project.artifactId}'s <recordSequentially>", it.trim()) }
                ?: false

        /** The module's own plugin entry, else the reactor root's, never a sibling's that `-pl` happened to put first. */
        private fun moduleConfiguration(project: MavenProject, session: MavenSession): Xpp3Dom? {
            val root = session.request.multiModuleProjectDirectory?.absoluteFile
            val top = session.allProjects.firstOrNull { it.basedir?.absoluteFile == root } ?: session.topLevelProject
            return (project.getPlugin(PLUGIN_KEY) ?: top?.getPlugin(PLUGIN_KEY))?.configuration as? Xpp3Dom
        }

        private fun checked(patterns: List<String>, source: String): List<String> {
            val kept = patterns.map(String::trim).filter(String::isNotEmpty)
            try {
                AlwaysRun.from(kept.joinToString(","))
            } catch (refused: IllegalArgumentException) {
                // The agent's advice names Gradle's switch; Maven's is the enabled property.
                val message = refused.message.orEmpty().replace("-Pyoriwake.disabled", "-D$ENABLED=false")
                throw MavenExecutionException("[yoriwake] $source: $message", null as Throwable?)
            }
            return kept
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
