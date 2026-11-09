package io.github.zeuspizza.yoriwake.gradle.facts

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
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
