package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import java.util.Locale
import java.util.function.Supplier
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.normalization.MetaInfNormalization

/**
 * What a build's `normalization { runtimeClasspath { ... } }` declares, as strings a task action
 * can carry under the configuration cache: the paths it ignores within a classpath entry
 * (`ignore(...)`, and `metaInf`'s `ignoreManifest()` and `ignoreCompletely()`), and the manifest
 * attributes it ignores. Gradle leaves such a change out of the test task's up-to-date check, so a
 * selecting run leaves it out too ([ClasspathFiles.normalized]). Properties-file ignores are not
 * carried: such a change still moves.
 */
internal data class RuntimeNormalization(val resources: List<String>, val attributes: List<String>) : java.io.Serializable {

    /**
     * Gradle's own filters, rebuilt from the strings so they match exactly as its up-to-date check
     * does. Null when Gradle's internal classes are not what this was written against.
     */
    fun filters(): ClasspathFiles.Normalization? = runCatching {
        val loader = Project::class.java.classLoader
        val normalization = Class.forName(DEFAULT, true, loader).getDeclaredConstructor().newInstance()
        val ignore = normalization.javaClass.getMethod("ignore", String::class.java)
        resources.forEach { ignore.invoke(normalization, it) }
        normalization.javaClass.getMethod("metaInf", Action::class.java).invoke(
            normalization,
            Action<MetaInfNormalization> { metaInf -> attributes.forEach(metaInf::ignoreAttribute) },
        )
        val internal = Class.forName(INTERNAL, true, loader)
        val resourceFilter = internal.getMethod("getClasspathResourceFilter").invoke(normalization)
        val attributeFilter = internal.getMethod("getManifestAttributeResourceEntryFilter").invoke(normalization)
        val ignoresResource = Class.forName(RESOURCE_FILTER, true, loader).getMethod("shouldBeIgnored", Supplier::class.java)
        val ignoresEntry = Class.forName(ENTRY_FILTER, true, loader).getMethod("shouldBeIgnored", String::class.java)
        object : ClasspathFiles.Normalization {
            override fun ignoresResource(path: String): Boolean =
                ignoresResource.invoke(resourceFilter, Supplier { path.split('/').toTypedArray() }) as Boolean

            // Gradle lower-cases the names it is given, and the names it compares.
            override fun ignoresAttribute(name: String): Boolean =
                ignoresEntry.invoke(attributeFilter, name.lowercase(Locale.ROOT)) as Boolean
        }
    }.getOrNull()

    /** What [read] found: a declaration, none, or why it could not tell. */
    sealed interface Read {
        data class Declared(val normalization: RuntimeNormalization) : Read
        data object None : Read
        data class Unreadable(val why: String) : Read
    }

    companion object {
        private const val DEFAULT = "org.gradle.normalization.internal.DefaultRuntimeClasspathNormalization"
        private const val INTERNAL = "org.gradle.normalization.internal.RuntimeClasspathNormalizationInternal"
        private const val RESOURCE_FILTER = "org.gradle.api.internal.changedetection.state.ResourceFilter"
        private const val ENTRY_FILTER = "org.gradle.api.internal.changedetection.state.ResourceEntryFilter"

        /**
         * [project]'s runtime classpath normalization as declared so far. Its internal state is read
         * without evaluating it, since Gradle refuses any declaration made after that.
         */
        fun read(project: Project): Read = runCatching {
            val normalization = project.normalization.runtimeClasspath
            fun declared(field: String): List<String> {
                val filter = fieldOf(normalization.javaClass, field).apply { isAccessible = true }.get(normalization)
                val builder = fieldOf(filter.javaClass, "builder").apply { isAccessible = true }.get(filter)
                return (builder.javaClass.getMethod("build").invoke(builder) as Set<*>).map { it.toString() }.sorted()
            }
            val read = RuntimeNormalization(declared("resourceFilter"), declared("manifestAttributeResourceFilter"))
            if (read.resources.isEmpty() && read.attributes.isEmpty()) Read.None else Read.Declared(read)
        }.getOrElse { Read.Unreadable(it.toString()) }

        private fun fieldOf(type: Class<*>, name: String): java.lang.reflect.Field {
            var at: Class<*>? = type
            while (at != null) {
                at.declaredFields.firstOrNull { it.name == name }?.let { return it }
                at = at.superclass
            }
            throw NoSuchFieldException("$name on ${type.name}")
        }
    }
}
