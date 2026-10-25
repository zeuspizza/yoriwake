package io.github.zeuspizza.yoriwake.maven

import org.apache.maven.MavenExecutionException
import org.apache.maven.execution.MavenSession
import org.codehaus.plexus.util.xml.Xpp3Dom

/**
 * What the build asks of yoriwake: the plugin's `<configuration>` in the POM, overridden by a user
 * property (`-Dyoriwake.enabled=false`). The names and the strict boolean parsing are the Gradle
 * plugin's, so one spelling works on both.
 */
internal class Settings(val enabled: Boolean, val alwaysRun: List<String>) {

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
            return Settings(enabled, alwaysRun.map(String::trim).filter(String::isNotEmpty))
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
