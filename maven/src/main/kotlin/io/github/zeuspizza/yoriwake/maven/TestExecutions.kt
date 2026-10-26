package io.github.zeuspizza.yoriwake.maven

import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject

/** The Surefire `test` and Failsafe `integration-test` executions a reactor declares, each with its map key. */
internal object TestExecutions {

    /** Each test plugin, the goal that runs its tests, and the JaCoCo goal whose agent reaches them. */
    val TEST_PLUGINS = listOf(
        Triple("org.apache.maven.plugins:maven-surefire-plugin", "test", JacocoAdoption.PREPARE_AGENT),
        Triple("org.apache.maven.plugins:maven-failsafe-plugin", "integration-test", JacocoAdoption.PREPARE_AGENT_INTEGRATION),
    )

    class Found(
        val project: MavenProject,
        val plugin: Plugin,
        val execution: PluginExecution,
        /** The JaCoCo goal whose agent this plugin's JVMs take when the build declares it. */
        val ownAgentGoal: String,
    ) {
        val id: String get() = execution.id

        /** How console lines name it, as Recording does. */
        val name: String
            get() = "${project.artifactId} ${plugin.artifactId.removePrefix("maven-").removeSuffix("-plugin")}:" +
                "${goalOf(plugin)} (${execution.id})"

        /** Its map's key, as Recording keys it. */
        val key: String get() = "${project.groupId}:${project.artifactId}:${plugin.artifactId}:${execution.id}"
    }

    fun of(session: MavenSession, projects: List<MavenProject> = session.projects): List<Found> =
        projects.flatMap { project ->
            TEST_PLUGINS.flatMap { (key, testGoal, ownGoal) ->
                val plugin = project.getPlugin(key) ?: return@flatMap emptyList()
                plugin.executions.filter { testGoal in it.goals }.map { Found(project, plugin, it, ownGoal) }
            }
        }

    private fun goalOf(plugin: Plugin) = TEST_PLUGINS.first { it.first == plugin.key }.second
}
