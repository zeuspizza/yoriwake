package io.github.zeuspizza.yoriwake.gradle.facts

import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.provider.Provider
import java.io.File

// Which files of a project's runtime classpaths are library modules, and which configurations its
// classpaths are resolved from: what a dependency bump is judged by.

/**
 * Each library module file Gradle resolves onto this project's runtime classpaths, with its identity:
 * `group:name`, then `:classifier` and `@extension` when not a plain jar. A file repository's jars
 * are modules too, wherever they lie; a `files(...)` dependency or a project's output is none. A
 * configuration that fails to resolve is left out, so its files read as no module's.
 */
internal fun moduleIdentities(project: Project): Provider<Map<File, String>> = project.provider {
    val identities = mutableMapOf<File, String>()
    project.configurations.filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
        .forEach { configuration ->
            runCatching {
                configuration.incoming.artifactView { it.lenient(true) }.artifacts.artifacts.forEach { artifact ->
                    val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@forEach
                    identities[artifact.file.absoluteFile] = identityOf(id.group, id.module, id.version, artifact.file.name)
                }
            }
        }
    identities
}

/**
 * The artifacts of this project's runtime classpaths, held as Gradle's own collections: resolved
 * when [projectArtifacts] reads them in a task action, so a transform's output is the one under the
 * running Gradle user home, after the task that produces its input has run.
 */
internal fun runtimeArtifacts(project: Project): Provider<List<ArtifactCollection>> = project.provider {
    project.configurations.filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
        .map { configuration -> configuration.incoming.artifactView { it.lenient(true) }.artifacts }
}

/**
 * The files of [classpath] in Gradle's transform cache under [gradleUserHome] that [artifacts]
 * resolves to a project, of this build or one it includes. Nothing is resolved when no file of
 * [classpath] lies in that cache; a collection that fails to resolve gives none.
 */
internal fun projectArtifacts(artifacts: Provider<List<ArtifactCollection>>, classpath: Collection<File>, gradleUserHome: File): Set<File> {
    val transformed = classpath.map(File::getAbsoluteFile).filter { file ->
        file.relativeToOrNull(gradleUserHome.absoluteFile)?.invariantSeparatorsPath
            ?.let { !it.startsWith("..") && TRANSFORM_CACHE.containsMatchIn(it) } == true
    }.toSet()
    if (transformed.isEmpty()) return emptySet()
    val collections = runCatching { artifacts.get() }.getOrDefault(emptyList())
    return collections.flatMapTo(mutableSetOf()) { collection ->
        runCatching {
            collection.artifacts.filter { it.id.componentIdentifier is ProjectComponentIdentifier }.map { it.file.absoluteFile }
        }.getOrDefault(emptyList())
    }.filterTo(mutableSetOf()) { it in transformed }
}

private val TRANSFORM_CACHE = Regex("^caches/[^/]+/transforms/")

/** [group]:[module], with the classifier and extension [fileName] carries beyond `module-version`. */
internal fun identityOf(group: String, module: String, version: String, fileName: String): String {
    val extension = fileName.substringAfterLast('.', "")
    val stem = fileName.removeSuffix(".$extension")
    val classifier = stem.removePrefix("$module-$version").takeIf { it != stem }?.removePrefix("-").orEmpty()
    return "$group:$module" + (if (classifier.isEmpty()) "" else ":$classifier") + (if (extension == "jar") "" else "@$extension")
}

/**
 * The configurations this project's classpaths are resolved from, with every configuration each
 * extends: a dependency declared in one of them can reach a test JVM's classpath.
 */
internal fun classpathConfigurations(project: Project): Provider<Set<String>> = project.provider {
    project.configurations.filter { it.isCanBeResolved && isClasspathLike(it.name) }
        .flatMapTo(sortedSetOf()) { configuration -> configuration.hierarchy.map { it.name } }
}

/** Whether a changed path is the build script of a module whose directory is one of [moduleDirs]. */
internal fun moduleScript(moduleDirs: Set<String>): (String) -> Boolean = { path ->
    path.substringAfterLast('/') in setOf("build.gradle", "build.gradle.kts") && path.substringBeforeLast('/', "") in moduleDirs
}
