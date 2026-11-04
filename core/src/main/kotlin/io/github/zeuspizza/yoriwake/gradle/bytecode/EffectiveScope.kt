package io.github.zeuspizza.yoriwake.gradle.bytecode

/**
 * What JaCoCo was actually instrumenting on the run that produced a map's records.
 *
 * Unlike [InstrumentationScope], which is what the sources declare, this is read back from the live
 * task at execution time, includes and excludes both: without the host's excludes, an excluded
 * class would look identical to one nothing tests.
 *
 * Empty includes means "everything"; an unknown scope is represented by writing no file.
 */
internal data class EffectiveScope(val includes: List<String>, val excludes: List<String>) {

    /**
     * One line per pattern, tagged and sorted, so a reordering by another plugin does not read as a
     * changed scope.
     */
    fun serialize(): String =
        (listOf(HEADER) +
            includes.sorted().map { "$INCLUDE\t$it" } +
            excludes.sorted().map { "$EXCLUDE\t$it" })
            .joinToString("\n", postfix = "\n")

    companion object {
        /**
         * First line of the file, so an include-everything scope never serializes to an empty file
         * that reads as "unknown". Versioned so a format change reads as a different scope.
         */
        const val HEADER = "yoriwake-effective-scope 1"

        const val INCLUDE = "include"
        const val EXCLUDE = "exclude"

        /** Reads back what [serialize] wrote, or null for anything else. */
        fun parse(serialized: String): EffectiveScope? = parseAll(serialized).singleOrNull()

        /**
         * Every scope block in the file, in the order they were recorded.
         *
         * A map can hold records captured under several scopes, so a class is only provably watched
         * if every block includes it; collapsing them would name a scope for records it never covered.
         */
        fun parseAll(serialized: String): List<EffectiveScope> {
            val scopes = mutableListOf<EffectiveScope>()
            var includes = mutableListOf<String>()
            var excludes = mutableListOf<String>()
            var started = false
            serialized.trim().lines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                if (line == HEADER) {
                    if (started) scopes += EffectiveScope(includes, excludes)
                    includes = mutableListOf()
                    excludes = mutableListOf()
                    started = true
                    return@forEach
                }
                if (!started) return emptyList()
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2) return emptyList()
                when (parts[0]) {
                    INCLUDE -> includes += parts[1]
                    EXCLUDE -> excludes += parts[1]
                    else -> return emptyList()
                }
            }
            if (started) scopes += EffectiveScope(includes, excludes)
            return scopes
        }

        /**
         * Reads the effective scope off a task's JaCoCo extension, or null when there is none.
         * Reflective because the plugin does not depend on the JaCoCo plugin.
         */
        fun readFrom(jacoco: Any?): EffectiveScope? {
            if (jacoco == null) return null
            val includes = patterns(jacoco, "getIncludes") ?: return null
            val excludes = patterns(jacoco, "getExcludes") ?: return null
            return EffectiveScope(includes, excludes)
        }

        /**
         * The scope JaCoCo's agent is handed on a JVM's command line, from its `includes` and
         * `excludes` options, as a host without a JaCoCo extension (Maven) hands it. Null when no
         * argument loads JaCoCo's agent, more than one does, or its options do not read.
         */
        fun fromAgentArguments(arguments: List<String>): EffectiveScope? {
            val agent = arguments.filter { it.startsWith("-javaagent:") && JACOCO_AGENT.containsMatchIn(it) }
                .singleOrNull() ?: return null
            val options = agent.substring(JACOCO_AGENT.find(agent)!!.range.last + 1).substringAfter('=', "")
            val values = mutableMapOf<String, String>()
            if (options.isNotEmpty()) {
                for (option in options.split(',')) {
                    val (key, value) = option.split('=', limit = 2).takeIf { it.size == 2 } ?: return null
                    values[key] = value
                }
            }
            fun list(key: String) = values[key]?.split(':')?.filter(String::isNotEmpty).orEmpty()
            return EffectiveScope(list("includes"), list("excludes"))
        }

        /** JaCoCo's agent jar, by its file name as `prepare-agent` resolves it. */
        private val JACOCO_AGENT = Regex("""(?:jacocoagent|org\.jacoco\.agent)[^/\\=]*\.jar""")

        private fun patterns(jacoco: Any, getter: String): List<String>? = runCatching {
            val value = jacoco.javaClass.methods.first { it.name == getter }.invoke(jacoco)
            @Suppress("UNCHECKED_CAST")
            (value as? Collection<String>)?.toList()
        }.getOrNull()
    }
}
