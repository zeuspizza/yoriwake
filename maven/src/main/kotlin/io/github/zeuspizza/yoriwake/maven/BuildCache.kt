package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginManagement
import org.codehaus.plexus.classworlds.realm.ClassRealm
import org.codehaus.plexus.util.xml.Xpp3Dom

/**
 * The Maven build caches, which could restore a wired execution instead of running it. The Apache
 * extension skips the execution before it starts, and forcing only the test goals leaves them
 * running over classes it did not compile, so a wired execution is declined while it is on.
 * Develocity restores a test goal after it starts; marking the test goals not cacheable is its
 * opt-out, and the compile goals keep their cache.
 */
internal object BuildCache {

    private const val APACHE = "org.apache.maven.extensions:maven-build-cache-extension"
    private val DEVELOCITY = listOf("com.gradle:develocity-maven-extension", "com.gradle:gradle-enterprise-maven-extension")

    /** The ids of the class realms this Maven loaded, or null when they cannot be listed. */
    private fun realms(): List<String>? =
        runCatching { (BuildCache::class.java.classLoader as ClassRealm).world.realms.map { it.id } }.getOrNull()

    /**
     * Why every wired execution is declined, or null. Realms that cannot be listed count as the
     * extension loaded; `-Dmaven.build.cache.enabled=false` turns it off.
     */
    fun decline(session: MavenSession): ScopeOutcome.NotApplied? {
        val loaded = realms()?.any { APACHE in it } ?: true
        val property = session.userProperties.getProperty("maven.build.cache.enabled")
            ?: session.systemProperties.getProperty("maven.build.cache.enabled")
        if (!loaded || property.equals("false", ignoreCase = true)) return null
        return ScopeOutcome.NotApplied(
            "the Apache Maven build cache can restore it without running it; pass -Dmaven.build.cache.enabled=false " +
                "to capture or select",
            ScopeOutcome.NotApplied.Kind.MAVEN_BUILD_CACHE,
        )
    }

    /** Whether Develocity's Maven extension, or its Gradle Enterprise predecessor, is loaded; unknown counts as loaded. */
    fun develocityLoaded(): Boolean = realms()?.any { realm -> DEVELOCITY.any { it in realm } } ?: true

    /** With Develocity loaded, marks every project's Surefire and Failsafe goals as not cacheable. */
    fun optOutDevelocity(session: MavenSession) {
        if (!develocityLoaded()) return
        for (project in session.projects) {
            val build = project.build ?: continue
            val management = build.pluginManagement ?: PluginManagement().also { build.pluginManagement = it }
            for (artifactId in listOf("develocity-maven-extension", "gradle-enterprise-maven-extension")) {
                val plugins = Xpp3Dom("plugins")
                for (tests in listOf("maven-surefire-plugin", "maven-failsafe-plugin")) {
                    plugins.addChild(Xpp3Dom("plugin").apply {
                        addChild(Xpp3Dom("artifactId").apply { value = tests })
                        addChild(Xpp3Dom("outputs").apply {
                            addChild(Xpp3Dom("notCacheableBecause").apply { value = "yoriwake wires this goal" })
                        })
                    })
                }
                val root = if (artifactId.startsWith("develocity")) "develocity" else "gradleEnterprise"
                val ours = Xpp3Dom("configuration").apply { addChild(Xpp3Dom(root).apply { addChild(plugins) }) }
                // Into the build's own entry when it has one, so a second entry cannot shadow either.
                val existing = management.plugins.firstOrNull { it.groupId == "com.gradle" && it.artifactId == artifactId }
                if (existing != null) {
                    existing.configuration = Xpp3Dom.mergeXpp3Dom(ours, existing.configuration as? Xpp3Dom)
                } else {
                    management.addPlugin(Plugin().apply {
                        groupId = "com.gradle"
                        this.artifactId = artifactId
                        configuration = ours
                    })
                }
            }
        }
    }
}
