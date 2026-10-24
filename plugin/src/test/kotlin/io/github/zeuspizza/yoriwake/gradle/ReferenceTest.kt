package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.tasks.AuditTask
import io.github.zeuspizza.yoriwake.gradle.tasks.DecodeTask
import io.github.zeuspizza.yoriwake.gradle.tasks.ExplainTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps docs/reference.md and docs/contract.md true about the names a build script, a command line
 * or a script reading the map uses: properties, flags, the extension, quoted tokens, files, tasks
 * and printed lines. Meanings are not checked, only that each name the docs quote is the code's.
 *
 * Names come from the classes where they can; source text is read only where the claim is about
 * text, such as a printed line or which code reads a property.
 */
class ReferenceTest {

    private val root = listOf(File(".."), File(".")).first { File(it, "docs/reference.md").isFile }
    private val reference = File(root, "docs/reference.md").readText()
    private val contract = File(root, "docs/contract.md").readText()
    private val docs = mapOf("reference.md" to reference, "contract.md" to contract)

    /** Each `.kt` and `.java` file under [dirs], by its path from the repository root. */
    private fun sources(vararg dirs: String): Map<String, String> =
        dirs.flatMap { dir -> File(root, dir).walk().filter { it.isFile && it.extension in setOf("kt", "java") } }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    private val pluginSources = sources("plugin/src/main")
    private val mainText = sources("plugin/src/main", "agent/src/main").values.joinToString("\n")

    /** The published tokens, as (category, token). */
    private val golden: List<Pair<String, String>> =
        checkNotNull(javaClass.getResourceAsStream("/golden/tokens.txt")) { "no golden tokens" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .lines().filter(String::isNotEmpty)
            .map { line -> line.substringBefore('\t') to line.substringAfter('\t') }
    private val tokens = golden.map { it.second }.toSet()

    /** Whether [name] opens a string literal in the main sources and is not the start of a longer name. */
    private fun inLiteral(name: String) =
        Regex("\"" + Regex.escape(name) + "(?![A-Za-z0-9._-])").containsMatchIn(mainText)

    /** The rows of the table whose header line is [header], each as its cells without backticks. */
    private fun table(doc: String, header: String): List<List<String>> {
        val lines = doc.lines()
        val start = lines.indexOfFirst { it.trim() == header }
        assertTrue(start >= 0, "no table headed $header")
        return lines.drop(start + 2).takeWhile { it.startsWith("|") }
            .map { row -> row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("`", "") } }
    }

    /** Inline code spans outside fenced blocks: what the prose quotes. */
    private fun quoted(doc: String): List<String> =
        Regex("`([^`\n]+)`").findAll(fenced.replace(doc, "")).map { it.groupValues[1] }.toList()

    private val fenced = Regex("^[ \t]*```.*?^[ \t]*```", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))

    /** Every flag Settings reads, by name. */
    private val flags: Set<String> = Settings::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.isAccessible = true; it.get(null) as String }
        .toSet()

    private val propertiesTable: List<String> =
        table(reference, "| Property | Meaning |").map { it[0].substringBefore('[').substringBefore('=') }

    @Test
    fun `every property the table lists is read`() {
        val unknown = propertiesTable - flags
        assertTrue(unknown.isEmpty(), "reference.md's Properties table lists flags Settings does not read: $unknown")
    }

    @Test
    fun `every flag the plugin reads is listed`() {
        // Internal flags are unsupported, and the reference says so without listing them.
        val missing = flags.filterNot { it.startsWith("yoriwake.internal.") } - propertiesTable.toSet()
        assertTrue(missing.isEmpty(), "Settings reads flags reference.md's Properties table does not list: $missing")
    }

    @Test
    fun `every -P flag the docs name is read`() {
        val unknown = docs.flatMap { (doc, text) ->
            Regex("(?<![A-Za-z0-9])-P([A-Za-z][A-Za-z0-9._]*)").findAll(text)
                .map { it.groupValues[1].trimEnd('.') }.filter { it !in flags }.map { "$doc: -P$it" }
        }.distinct()
        assertTrue(unknown.isEmpty(), "the docs name -P flags Settings does not read: $unknown")
    }

    @Test
    fun `only Settings reads Gradle properties`() {
        // A reader elsewhere is a flag the Properties checks above never see.
        val readers = listOf("gradleProperty(", "findProperty(", "hasProperty(", "gradlePropertiesPrefixedBy(")
        val elsewhere = pluginSources.filterKeys { !it.endsWith("/Settings.kt") }
            .flatMap { (path, text) -> readers.filter { it in text }.map { "$path: $it" } }
        assertTrue(elsewhere.isEmpty(), "Gradle properties read outside Settings: $elsewhere")
    }

    /** -D properties the reference names that belong to the JDK, each with why it is named. */
    private val jdkProperties = mapOf(
        "user.timezone" to "the test JVM's time zone, which the environment fingerprint compares only when set",
    )

    @Test
    fun `every -D flag the reference names is in the code`() {
        val pluginText = pluginSources.values.joinToString("\n")
        // Named whole, in a string or as `-D<name>` in a message: Develocity's `pts.enabled` is read
        // through Develocity's own extension, and the decline names it.
        val unknown = Regex("(?<![A-Za-z0-9])-D([A-Za-z][A-Za-z0-9._]*)").findAll(reference)
            .map { it.groupValues[1].trimEnd('.') }.distinct()
            .filter { it !in jdkProperties }
            .filterNot { Regex("(\"|-D)" + Regex.escape(it) + "(?![A-Za-z0-9._-])").containsMatchIn(pluginText) }
            .toList()
        assertTrue(unknown.isEmpty(), "reference.md names -D properties the plugin never names: $unknown")
    }

    /** The daemon's own system properties the plugin may read, each with why. */
    private val daemonProperties = mapOf(
        "\"user.home\"" to "replaced by a placeholder before a fingerprint value is digested",
        "\"os.name\"" to "part of the environment fingerprint",
        "\"os.arch\"" to "part of the environment fingerprint",
    )

    @Test
    fun `the plugin reads no undocumented -D flag`() {
        // The call is matched, not its argument, so a read through a named constant is caught too.
        val call = Regex("""(System\.getProperty|System\.getProperties|providers\.systemProperty)\(([^)]*)\)""")
        val reads = pluginSources.flatMap { (path, text) ->
            call.findAll(text).filter { it.groupValues[2].trim() !in daemonProperties }.map { "$path: ${it.value}" }
        }
        assertTrue(reads.isEmpty(), "the plugin reads system properties of the daemon the reference does not name: $reads")
    }

    /** Property, type and default, from the reference's extension table. */
    private val extensionTable: Map<String, Pair<String, String>> =
        table(reference, "| Property | Type | Default | Meaning |").associate { it[0] to (it[1] to it[2]) }

    /** Each property of the extension, with its type as a build script assigns it. */
    private val extensionTypes: Map<String, String> = YoriwakeExtension::class.java.methods
        // Not synthetic: a deprecated property also compiles to a `get<name>$annotations` method.
        .filter { it.declaringClass == YoriwakeExtension::class.java && it.name.startsWith("get") && !it.isSynthetic }
        .associate { getter ->
            val type = getter.genericReturnType as ParameterizedType
            val element = kotlinName(type.actualTypeArguments.single() as Class<*>)
            val name = getter.name.removePrefix("get").replaceFirstChar(Char::lowercase)
            name to if (ListProperty::class.java.isAssignableFrom(type.rawType as Class<*>)) "List<$element>" else element
        }

    private fun kotlinName(type: Class<*>) = when (type) {
        java.lang.Integer::class.java -> "Int"
        else -> type.simpleName
    }

    @Test
    fun `each extension property has the table's type`() {
        val wrong = extensionTable.mapNotNull { (name, row) ->
            val actual = extensionTypes[name]
            if (actual == row.first) null else "$name: the table says ${row.first}, the extension has ${actual ?: "no such property"}"
        }
        assertTrue(wrong.isEmpty(), "reference.md's extension table: $wrong")
    }

    @Test
    fun `every extension property is listed`() {
        val missing = extensionTypes.keys - extensionTable.keys
        assertTrue(missing.isEmpty(), "extension properties reference.md's extension table does not list: $missing")
    }

    @Test
    fun `each extension default is the table's`() {
        // Created as the plugin creates it, so a convention set in the extension is what is read.
        val extension = ProjectBuilder.builder().build().extensions.create("yoriwake", YoriwakeExtension::class.java)
        val wrong = extensionTable.keys.intersect(extensionTypes.keys).mapNotNull { name ->
            val value = YoriwakeExtension::class.java.getMethod("get" + name.replaceFirstChar(Char::uppercase)).invoke(extension)
            val actual = when (value) {
                is ListProperty<*> -> value.orNull?.let { if (it.isEmpty()) "empty" else it.joinToString(", ") } ?: "unset"
                is Property<*> -> value.orNull?.toString() ?: "unset"
                else -> "not a property"
            }
            val documented = extensionTable.getValue(name).second
            if (actual == documented) null else "$name: the table says $documented, the extension has $actual"
        }
        assertTrue(wrong.isEmpty(), "reference.md's extension table: $wrong")
    }

    @Test
    fun `every extension example names only listed properties`() {
        val assigned = Regex("""(?<![A-Za-z0-9])([a-z][A-Za-z0-9]*)\s*(?:=(?!=)|\.add\(|\.addAll\()""")
        val blocks = Regex("""yoriwake\s*\{([^{}]*)\}""").findAll(reference).toList()
        assertTrue(blocks.isNotEmpty(), "reference.md has no yoriwake { } example to check")
        val unknown = blocks.asSequence()
            .flatMap { block ->
                val body = block.groupValues[1].lines().joinToString("\n") { it.substringBefore("//") }
                assigned.findAll(body).map { it.groupValues[1] }
            }
            .filter { it !in extensionTable }.distinct().toList()
        assertTrue(unknown.isEmpty(), "reference.md's yoriwake { } examples set properties its extension table lacks: $unknown")
    }

    /** Names the docs quote that are not this project's, each with why it is quoted. */
    private val foreignNames = mapOf(
        "java-library" to "Gradle's own plugin, which applies java",
        "gradle-git-properties" to "a third-party plugin that writes git.properties onto the classpath",
    )

    @Test
    fun `every quoted name exists in the code`() {
        val kebab = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)+")
        val unknown = docs.flatMap { (doc, text) ->
            quoted(text).filter { kebab.matches(it) }.distinct()
                .filterNot { it in tokens || it in foreignNames || inLiteral(it) }.map { "$doc: $it" }
        }
        assertTrue(unknown.isEmpty(), "the docs quote names neither a published token nor a string in the code: $unknown")
    }

    /** The bullets of the reference's list of files in the map directory meant to be read. */
    private fun mapFiles(): List<String> {
        val lines = reference.lines()
        val start = lines.indexOfFirst { it.startsWith("Files in the map directory that are meant to be read") }
        assertTrue(start >= 0, "reference.md has no list of the map directory's files")
        return lines.drop(start + 1).dropWhile(String::isBlank).takeWhile { it.startsWith("- ") || it.startsWith("  ") }
            .mapNotNull { Regex("^- `([^`]+)`").find(it)?.groupValues?.get(1) }
    }

    @Test
    fun `every quoted file exists in the code`() {
        val files = docs.flatMap { (doc, text) ->
            val quotes = quoted(text)
            val named = quotes.filter { Regex("[A-Za-z0-9_.-]+\\.(tsv|json)").matches(it) }
            // The last segment a path spells out, not a placeholder such as <task> or *.
            val segments = quotes.filter { it.startsWith(".gradle/yoriwake") }.map { path ->
                path.split("/").last { it.isNotEmpty() && !Regex("[<>*…]").containsMatchIn(it) }
            }
            (named + segments).map { doc to it }
        } + mapFiles().map { "reference.md" to it }
        val unknown = files.distinct().filterNot { inLiteral(it.second) }.map { "${it.first}: ${it.second}" }
        assertTrue(files.isNotEmpty(), "no file names found in the docs")
        assertTrue(unknown.isEmpty(), "the docs quote files no string in the code names: $unknown")
    }

    /** The kinds the plugin registers per test task: Audit in `yoriwakeAudit<Task>`. */
    private val registered: Set<String> =
        Regex("""fun nameFor\([^)]*\)\s*=\s*"yoriwake([A-Z][A-Za-z]*)\$\{""").findAll(pluginSources.values.joinToString("\n"))
            .map { it.groupValues[1] }.toSet()

    private val tasksTable: List<String> = table(reference, "| Task | Meaning |").map { it[0] }

    @Test
    fun `every registered task is listed`() {
        // The source scan must find at least the tasks the plugin's companions name, or it reads nothing.
        val named = listOf(AuditTask.nameFor(""), ExplainTask.nameFor(""), DecodeTask.nameFor(""))
            .map { it.removePrefix("yoriwake") }.toSet()
        assertTrue(registered.containsAll(named), "nameFor scan found $registered, the companions name $named")
        val missing = registered.map { "yoriwake$it<Task>" } - tasksTable.toSet()
        assertTrue(missing.isEmpty(), "reference.md's Tasks table does not list $missing")
    }

    @Test
    fun `every task the docs name is registered`() {
        val task = Regex("yoriwake([A-Z][a-z]+)(?:<Task>|[A-Z][A-Za-z]*)?")
        val unknown = docs.flatMap { (doc, text) ->
            (quoted(text) + tasksTable).mapNotNull { task.matchEntire(it)?.groupValues?.get(1) }.distinct()
                .filter { it !in registered }.map { "$doc: yoriwake$it" }
        }.distinct()
        assertTrue(unknown.isEmpty(), "the docs name tasks the plugin does not register: $unknown")
    }

    @Test
    fun `every printed line is printed`() {
        // A message split over lines with `" +` reads as one string here.
        val text = mainText.replace(Regex("\"\\s*\\+\\s*\n\\s*\""), "")
        val tally = golden.filter { it.first == "console-tally" }.map { it.second }.toSet()
        val placeholder = Regex("""…|<[^>]*>|(?<![A-Za-z])[NM](?![A-Za-z])|:test""")
        // A tally key is built from its token, so the token is what the line's spelling rests on.
        val tallyKey = Regex("""(?<![A-Za-z0-9-])([a-z][a-z0-9-]*)=""")
        val lines = table(reference, "| Line | Meaning |").map { it[0] }
        assertTrue(lines.isNotEmpty(), "reference.md's What it prints table is empty")
        val missing = lines.flatMap { line ->
            placeholder.split(line)
                .flatMap { fragment ->
                    fragment.replace(tallyKey) { if (it.groupValues[1] in tally) "\u0000" else it.value }.split('\u0000')
                }
                .map(String::trim).filter { it.length >= 4 && it !in text }
                .map { "$line: \"$it\"" }
        }
        assertTrue(missing.isEmpty(), "reference.md's What it prints table quotes text the code does not print: $missing")
    }
}
