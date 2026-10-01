package io.github.zeuspizza.yoriwake.gradle.facts

import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

// Build-wide facts read once per build, for derivations that must not depend on evaluation
// order.

/**
 * Everything a build-wide derivation needs from every project, read once per build at
 * `projectsEvaluated` so the result does not depend on evaluation order.
 */
internal class ProjectFacts(
    val moduleDirs: Map<String, String>,
    val buildDirs: Map<String, String>,
    val sourceDirs: List<File>,
)

/** [ProjectFacts] for this build, computed once and keyed by the root it was read from. */
internal fun projectFacts(project: Project, memo: BuildMemo?): ProjectFacts {
    val walk = {
        val moduleDirs = mutableMapOf<String, String>()
        val buildDirs = mutableMapOf<String, String>()
        val sourceDirs = mutableListOf<File>()
        project.rootProject.allprojects.forEach { candidate ->
            moduleDirs[
                candidate.projectDir.relativeTo(project.rootDir).invariantSeparatorsPath.trim('/')
            ] = candidate.path
            buildDirs[candidate.path] = candidate.layout.buildDirectory.get().asFile.absolutePath
            val declared = candidate.extensions.findByType(SourceSetContainer::class.java)
                ?.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
                ?.allSource?.srcDirs
                .orEmpty()
            // Android and Kotlin Multiplatform modules have no SourceSetContainer, so the
            // conventional directories stand in. A missed directory is safe: absent from the map
            // forces. The union is build-global, so this can only instrument more.
            sourceDirs += declared.ifEmpty {
                YoriwakePlugin.CONVENTIONAL_SOURCE_DIRS
                    .map { File(candidate.projectDir, it) }
                    .filter { it.isDirectory } +
                    YoriwakePlugin.multiplatformSourceDirs(candidate.projectDir)
            }
        }
        ProjectFacts(moduleDirs, buildDirs, sourceDirs)
    }
    val compute = { memo?.time(YoriwakePlugin.WALK_COUNTER, walk) ?: walk() }
    return if (memo == null) compute() else memo.value(YoriwakePlugin.FACTS_KEY + project.rootDir.path, compute)
}
