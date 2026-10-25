package io.github.zeuspizza.yoriwake.maven

import org.apache.maven.AbstractMavenLifecycleParticipant
import org.apache.maven.execution.MavenSession
import org.apache.maven.rtinfo.RuntimeInformation
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Where yoriwake joins a Maven build: the POM declares the plugin with `<extensions>true</extensions>`,
 * and Maven calls this once the whole reactor is read, as Gradle's `projectsEvaluated`.
 *
 * It says it attached, or why it does nothing in this build, and changes nothing yet.
 */
@Named("yoriwake")
@Singleton
internal class YoriwakeLifecycle @Inject constructor(
    private val runtime: RuntimeInformation,
) : AbstractMavenLifecycleParticipant() {

    override fun afterProjectsRead(session: MavenSession) {
        if (!enabled(session)) return
        val version = runtime.mavenVersion
        if (!runtime.isMavenVersion("[$MAVEN_FLOOR,)")) {
            log.warn("[yoriwake] maven-version: Maven $version is older than $MAVEN_FLOOR; yoriwake does nothing in this build")
            return
        }
        if (PRE_RELEASE.matches(version) && !allowsPreRelease(session)) {
            log.warn("[yoriwake] maven-version: Maven $version is not a release; yoriwake does nothing in this build")
            return
        }
        log.info("[yoriwake] attached to Maven $version")
    }

    /** For the functional suite, which runs Maven 4 before its release. */
    private fun allowsPreRelease(session: MavenSession) =
        (session.userProperties.getProperty(PRE_RELEASE_PROPERTY) ?: session.systemProperties.getProperty(PRE_RELEASE_PROPERTY)) == "true"

    /** False when the POM declaring the plugin configures `<enabled>false</enabled>`. */
    private fun enabled(session: MavenSession): Boolean {
        val declared = session.projects.firstNotNullOfOrNull { it.getPlugin(PLUGIN_KEY) } ?: return true
        val value = (declared.configuration as? Xpp3Dom)?.getChild("enabled")?.value?.trim()
        return value != "false"
    }

    private companion object {
        const val PLUGIN_KEY = "io.github.zeuspizza:yoriwake-maven-plugin"
        const val MAVEN_FLOOR = "3.9.0"
        const val PRE_RELEASE_PROPERTY = "yoriwake.internal.mavenPreRelease"

        /** An alpha, beta or release candidate: a version with a qualifier after its numbers. */
        val PRE_RELEASE = Regex("""\d+(\.\d+)*-.+""")
        val log = LoggerFactory.getLogger(YoriwakeLifecycle::class.java)
    }
}
