package io.github.zeuspizza.yoriwake.maven

/** When a Maven execution's loaded-class union may be trusted, and under which test filter. */
internal object UnionTrust {
    /**
     * Why a Failsafe execution's union may not list every class its tests reach, or null when
     * [failsafe] is false: its tests commonly drive an application another JVM runs (a server
     * started by `docker compose`, a CI service, a process the test starts), and nothing the test
     * JVM sees tells such a server from none.
     */
    fun failsafeDoubt(failsafe: Boolean): String? {
        if (!failsafe) return null
        return "integration tests commonly drive an application another JVM runs, which no union sees"
    }

    /**
     * The union filter of a Failsafe run, whose union is never trusted: no capture writes it, so
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
