package io.github.zeuspizza.yoriwake.maven

import java.io.File

/** When a Maven execution's loaded-class union may be trusted, and under which test filter. */
internal object UnionTrust {
    /**
     * Plugins whose goals start no process that outlives them: Maven's packaging, test and
     * deployment plugins, JaCoCo's agent setup, and the checks and helpers beside them. Any
     * other mojo before a Failsafe execution keeps its union from being trusted.
     */
    val STARTS_NO_PROCESS = setOf(
        "org.apache.maven.plugins:maven-clean-plugin", "org.apache.maven.plugins:maven-resources-plugin",
        "org.apache.maven.plugins:maven-compiler-plugin", "org.apache.maven.plugins:maven-surefire-plugin",
        "org.apache.maven.plugins:maven-failsafe-plugin", "org.apache.maven.plugins:maven-jar-plugin",
        "org.apache.maven.plugins:maven-war-plugin", "org.apache.maven.plugins:maven-source-plugin",
        "org.apache.maven.plugins:maven-javadoc-plugin", "org.apache.maven.plugins:maven-shade-plugin",
        "org.apache.maven.plugins:maven-assembly-plugin", "org.apache.maven.plugins:maven-enforcer-plugin",
        "org.apache.maven.plugins:maven-dependency-plugin", "org.apache.maven.plugins:maven-remote-resources-plugin",
        "org.apache.maven.plugins:maven-install-plugin", "org.apache.maven.plugins:maven-deploy-plugin",
        "org.jacoco:jacoco-maven-plugin", "org.codehaus.mojo:build-helper-maven-plugin",
        "org.codehaus.mojo:flatten-maven-plugin", "org.codehaus.mojo:animal-sniffer-maven-plugin",
        Settings.PLUGIN_KEY,
    )

    /**
     * Why a Failsafe execution's union may not list every class its tests reach, or null when it
     * may (or [failsafe] is false): [unvettedMojo], a mojo before it not in [STARTS_NO_PROCESS],
     * which may have left code running in another JVM, or a container launcher on its
     * [classpath], which starts one over the Docker socket.
     */
    fun failsafeDoubt(failsafe: Boolean, unvettedMojo: String?, classpath: List<File>): String? {
        if (!failsafe) return null
        unvettedMojo?.let { return "$it ran before it and may run code in another JVM, which no union sees" }
        return classpath.firstOrNull { it.name.startsWith(CONTAINER_LAUNCHER) }
            ?.let { "${it.name} is on its classpath and may run code in a container, which no union sees" }
    }

    /** The file-name start of Testcontainers' jars, which start containers over the Docker socket. */
    const val CONTAINER_LAUNCHER = "testcontainers"

    /**
     * The union filter of a Failsafe run whose union cannot be trusted: no capture writes it, so
     * no union matches it.
     */
    const val UNVETTED = "unvetted"

    /**
     * The digest of an execution's own test filter: `groups`, `excludedGroups`, the JUnit Platform's
     * `includeTags`/`excludeTags` properties, JUnit 5 engines and TestNG suite files, as resolved
     * from the POM, a profile or `-D`; null when none is set. A union recorded under a filter is
     * trusted only by a run under the same one. [value] and [list] read the execution's
     * configuration, [platformProperty] its `<properties>`, [property] a user or project property,
     * each resolved and blank as null.
     */
    fun filterOf(
        value: (String) -> String?,
        list: (String) -> List<String>,
        platformProperty: (String) -> String?,
        property: (String) -> String?,
    ): String? {
        fun listed(name: String, fallback: String) =
            (list(name).takeIf(List<String>::isNotEmpty) ?: listOfNotNull(property(fallback))).sorted()
        val lines = listOf("groups", "excludedGroups").mapNotNull { name -> (value(name) ?: property(name))?.let { "$name $it" } } +
            listOf("includeTags", "excludeTags").mapNotNull { name -> platformProperty(name)?.let { "$name $it" } } +
            listOf("includeJUnit5Engines", "excludeJUnit5Engines").mapNotNull { name ->
                listed(name, "surefire.$name").takeIf(List<String>::isNotEmpty)?.let { "$name $it" }
            } +
            listOfNotNull(listed("suiteXmlFiles", "surefire.suiteXmlFiles").takeIf(List<String>::isNotEmpty)?.let { "suiteXmlFiles $it" })
        if (lines.isEmpty()) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        lines.forEach { digest.update("$it\n".toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
