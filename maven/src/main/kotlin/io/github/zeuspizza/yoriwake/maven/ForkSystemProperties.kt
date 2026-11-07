package io.github.zeuspizza.yoriwake.maven

import org.apache.maven.execution.MavenSession
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.io.File
import java.util.Properties
import java.util.UUID

/** One system property an execution's configuration hands the test JVM, and where it was set. */
internal class ForkSystemProperty(val source: String, val name: String, val value: String) {
    /** Whether [value] is as the POM wrote it, its expressions still to resolve; a file's is literal. */
    val fromPom get() = source != SYSTEM_PROPERTIES_FILE

    companion object {
        const val SYSTEM_PROPERTIES = "systemProperties"
        const val SYSTEM_PROPERTIES_FILE = "systemPropertiesFile"
        const val SYSTEM_PROPERTY_VARIABLES = "systemPropertyVariables"

        /** The name of the one entry a named file that cannot be read gives. */
        const val UNREADABLE_FILE = "@systemPropertiesFile"
    }
}

/**
 * The system properties Surefire or Failsafe hands the fork from [configuration], in the order it
 * applies them, a later one winning: `<systemProperties>`, the file `systemPropertiesFile` names,
 * then `systemPropertyVariables`. The invocation's user properties, which it adds last, are left
 * to the caller. A named file that exists and cannot be read gives one entry of a value no other
 * run shares, so nothing compares equal to a run whose properties are unknown; an absent one gives
 * nothing, as Surefire then hands the fork none of it.
 */
internal fun forkSystemProperties(
    project: MavenProject,
    session: MavenSession,
    configuration: Xpp3Dom?,
    failsafe: Boolean,
): List<ForkSystemProperty> {
    val properties = mutableListOf<ForkSystemProperty>()
    // Both forms Maven reads into a `Properties` parameter.
    configuration?.getChild(ForkSystemProperty.SYSTEM_PROPERTIES)?.children.orEmpty().forEach { child ->
        val name = if (child.name == "property") child.getChild("name")?.value else child.name
        val value = if (child.name == "property") child.getChild("value")?.value else child.value
        if (name != null) properties += ForkSystemProperty(ForkSystemProperty.SYSTEM_PROPERTIES, name, value.orEmpty())
    }
    systemPropertiesFile(project, session, configuration, failsafe)?.takeIf(File::exists)?.let { file ->
        val read = runCatching { file.inputStream().use { Properties().apply { load(it) } } }.getOrNull()
        if (read == null) {
            properties += ForkSystemProperty(ForkSystemProperty.SYSTEM_PROPERTIES_FILE, ForkSystemProperty.UNREADABLE_FILE, UUID.randomUUID().toString())
        } else {
            read.stringPropertyNames().sorted().forEach {
                properties += ForkSystemProperty(ForkSystemProperty.SYSTEM_PROPERTIES_FILE, it, read.getProperty(it))
            }
        }
    }
    configuration?.getChild(ForkSystemProperty.SYSTEM_PROPERTY_VARIABLES)?.children.orEmpty().forEach {
        properties += ForkSystemProperty(ForkSystemProperty.SYSTEM_PROPERTY_VARIABLES, it.name, it.value.orEmpty())
    }
    return properties
}

/** The file `systemPropertiesFile` names, from the POM or the invocation, a relative path against the module as Maven resolves it. */
private fun systemPropertiesFile(project: MavenProject, session: MavenSession, configuration: Xpp3Dom?, failsafe: Boolean): File? {
    val property = if (failsafe) "failsafe.systemPropertiesFile" else "surefire.systemPropertiesFile"
    val path = (configuration?.getChild(ForkSystemProperty.SYSTEM_PROPERTIES_FILE)?.value ?: "\${$property}").let { value ->
        Regex("""\$\{([^}]+)}""").replace(value) { m -> Declines.property(project, session, m.groupValues[1]).orEmpty() }
    }.trim().takeIf(String::isNotEmpty) ?: return null
    return File(path).let { if (it.isAbsolute) it else File(project.basedir, path) }
}
