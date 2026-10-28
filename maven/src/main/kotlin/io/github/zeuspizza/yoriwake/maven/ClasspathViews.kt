package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView
import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject
import java.io.File

/** What the shared rules ask of an execution's classpath, from Maven's model of the reactor. */
internal fun classpathView(session: MavenSession, root: File, project: MavenProject, classpath: List<File>): ClasspathView =
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
        override fun declaredModules(): Set<String>? = null
        override fun jacocoVersion(): String? = project.getPlugin(JacocoAdoption.JACOCO)?.version.orEmpty()
        override fun jacocoSetting(): String = "jacoco-maven-plugin `<version>`"
        override fun testJvm(): Int? = null
        override fun agentCeiling(): String? = null
    }

/** Where a Maven test classpath takes dependencies from; see [ClasspathView.dependencyHomes]. */
internal fun dependencyHomes(session: MavenSession): Map<String, File> = mapOf(
    ClasspathFiles.MAVEN_REPOSITORY to File(session.localRepository?.basedir ?: File(System.getProperty("user.home"), ".m2/repository").path),
    ClasspathFiles.USER_HOME to File(System.getProperty("user.home").orEmpty()),
)
