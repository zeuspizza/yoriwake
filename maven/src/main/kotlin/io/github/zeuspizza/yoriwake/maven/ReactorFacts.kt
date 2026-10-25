package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.bytecode.InstrumentationScope
import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject
import java.io.File

/**
 * What the whole reactor declares, whichever modules this invocation builds: `-pl app` derives the
 * same scope as a full build, as a Gradle build derives it from every project.
 */
internal class ReactorFacts(session: MavenSession) {

    /** Every module of the reactor, not only the ones `-pl` or `-rf` selected. */
    val modules: List<MavenProject> = session.allProjects

    /**
     * The JaCoCo includes for every package the reactor's main sources declare; null when they
     * declare none. Kotlin's directory is named as well: its compiler adds it to the project only
     * when it runs, after this is read.
     */
    val scope: List<String>? by lazy {
        val roots = modules.flatMap { module ->
            module.compileSourceRoots.map(::File) + CONVENTIONAL_ROOTS.map { File(module.basedir, it) }
        }.map(File::getAbsoluteFile).distinct().filter(File::isDirectory)
        InstrumentationScope.toIncludePatterns(InstrumentationScope.derive(roots))
    }

    private companion object {
        val CONVENTIONAL_ROOTS = listOf("src/main/java", "src/main/kotlin")
    }
}
