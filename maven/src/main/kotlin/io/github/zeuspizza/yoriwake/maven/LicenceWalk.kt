package io.github.zeuspizza.yoriwake.maven

// Which licence plugins of the reactor root would check the files under `.yoriwake/`, decided from
// their declarations alone, so a unit test can load it without Maven on its class path.

/** Licence plugins, by key, whose goals list every file under the module they run in. */
internal val TREE_WALKING_LICENCE_PLUGINS = setOf("com.mycila:license-maven-plugin")

/** One set of files a licence plugin checks: its include and exclude patterns, as declared. */
internal class LicenceFileSet(val includes: List<String>, val excludes: List<String>)

/** A file of each kind `.yoriwake/` holds, relative to the module, to match a set's includes against. */
private val STATE_FILES = listOf(
    ".yoriwake/yoriwake-capture-stats.pending",
    ".yoriwake/yoriwake/module/run.properties",
    ".yoriwake/yoriwake/module/index.tsv",
    ".yoriwake/yoriwake/module/raw/worker/overhead.txt",
)

/**
 * The keys among [plugins], each mapped to the file sets its configuration declares, of the
 * licence plugins with a set that includes files under `.yoriwake` (every file, when it names no
 * include) and excludes nothing there.
 */
internal fun licencePluginsListingState(plugins: Map<String, List<LicenceFileSet>>): List<String> =
    plugins.filter { (key, sets) -> key in TREE_WALKING_LICENCE_PLUGINS && sets.any(::listsState) }.keys.toList()

private fun listsState(set: LicenceFileSet): Boolean {
    if (set.excludes.any { ".yoriwake" in it }) return false
    val includes = set.includes.ifEmpty { listOf("**") }.map(::antPattern)
    return STATE_FILES.any { file -> includes.any { it.matches(file) } }
}

/** An Ant-style path pattern, as Maven plugins read `<include>`: `**` spans directories, `*` and `?` do not. */
private fun antPattern(pattern: String): Regex {
    val text = pattern.trim().replace('\\', '/').let { if (it.endsWith("/")) "$it**" else it }
    val regex = StringBuilder()
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**/", i) -> { regex.append("(?:.*/)?"); i += 3 }
            text.startsWith("**", i) -> { regex.append(".*"); i += 2 }
            text[i] == '*' -> { regex.append("[^/]*"); i++ }
            text[i] == '?' -> { regex.append("[^/]"); i++ }
            else -> { regex.append(Regex.escape(text[i].toString())); i++ }
        }
    }
    return Regex(regex.toString())
}
