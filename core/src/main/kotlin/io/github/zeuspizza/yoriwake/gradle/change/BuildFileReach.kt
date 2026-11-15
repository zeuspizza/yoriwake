package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView
import java.io.File

/**
 * Whether a changed build file or `src/` file of a module off this task's classpath can still reach
 * the test, through what a build script does rather than through a class.
 *
 * A module's build script can configure another project, the build at large, or files outside its
 * module; and a script that applies a plugin runs that plugin's code, which can configure every
 * `Test` task. Either revision of the script doing so keeps it forcing. So does a script of the
 * tested module, its ancestors, the root or the settings naming the module, as one reads that
 * module's files or values.
 *
 * Text is matched as written, comments included: a comment that names a reach keeps the file, which
 * is the safe direction.
 */
internal object BuildFileReach {

    /** A file's text at the base the change set was taken against. */
    sealed interface Base {
        data class Text(val text: String) : Base

        /** The base has no such file: a new module. */
        data object Absent : Base

        /** git could not say. */
        data object Unknown : Base
    }

    /**
     * Why [path], a changed file of [module] (its build file when [buildFile]), keeps forcing; null
     * when neither revision of the build file reaches out or changes its plugins, and no script the
     * tested module reads names the module.
     *
     * @param read a repository-relative file's text in the working tree; null when it cannot be read
     * @param base a repository-relative file's text at the base
     */
    fun refusal(
        path: String,
        module: String,
        buildFile: Boolean,
        view: ClasspathView,
        read: (String) -> String?,
        exists: (String) -> Boolean,
        base: (String) -> Base,
    ): String? {
        val dir = view.moduleDirs.entries.firstOrNull { it.value == module }?.key ?: return "its module has no directory"
        if (buildFile) {
            if (holds(module, dir, view)) return "its module holds the tested module"
            val current = read(path) ?: return "it cannot be read"
            reach(current)?.let { return it }
            when (val then = base(path)) {
                is Base.Text -> {
                    reach(then.text)?.let { return "its base revision $it" }
                    if (pluginsDiffer(then.text, current)) return "it changes the plugins it applies"
                }
                Base.Absent -> Unit
                Base.Unknown -> return "its base revision cannot be read"
            }
        }
        return readerRefusal(module, dir, view, read, exists)
    }

    /** Whether [module], at [dir], is an ancestor of the tested module, by path or by directory. */
    private fun holds(module: String, dir: String, view: ClasspathView): Boolean {
        val ownDir = view.moduleDirs.entries.firstOrNull { it.value == view.ownPath }?.key
        return view.ownPath.startsWith("$module:") || (ownDir != null && ownDir.startsWith("$dir/"))
    }

    /**
     * Why a script the tested module reads names [module]: the settings script beyond declaring it,
     * or the build file of the tested module, an ancestor of it, or the root. A missing build file
     * is an empty script; one that exists and cannot be read, or a missing settings script, refuses.
     */
    private fun readerRefusal(
        module: String,
        dir: String,
        view: ClasspathView,
        read: (String) -> String?,
        exists: (String) -> Boolean,
    ): String? {
        val settings = SETTINGS.filter(exists)
        if (settings.isEmpty()) return "the build has no settings script to read"
        settings.forEach { script ->
            val text = read(script) ?: return "$script cannot be read"
            if (names(text, module, dir, settings = true)) return "$script names its module"
        }
        val readers = ancestors(view.ownPath).mapNotNull { view.buildFiles[it] }.distinct()
        readers.forEach { script ->
            if (!exists(script)) return@forEach
            val text = read(script) ?: return "$script cannot be read"
            if (names(text, module, dir, settings = false)) return "$script names its module"
        }
        return null
    }

    /** [path] and every Gradle path above it, the root `:` last. */
    private fun ancestors(path: String): List<String> {
        val found = mutableListOf(path)
        var current = path
        while (current.lastIndexOf(':') > 0) {
            current = current.substring(0, current.lastIndexOf(':'))
            found += current
        }
        if (path != ":") found += ":"
        return found
    }

    /** Why [text] reaches another project, the build at large, or files outside its module; else null. */
    fun reach(text: String): String? {
        REACHING.forEach { (pattern, what) -> pattern.find(text)?.let { return "names ${what ?: it.value.trim()}" } }
        if (literals(text).any { PARENT_SEGMENT.containsMatchIn(it) }) return "names a path above its module (`..`)"
        PROJECT_CALL.findAll(text).forEach { call ->
            if (!asDependency(text, call.range.first, call.range.last)) return "reaches another project with `project(...)`"
        }
        return null
    }

    /** Whether the `plugins {}` block, the `buildscript {}` block or a line applying a plugin differs. */
    fun pluginsDiffer(base: String, current: String): Boolean = plugins(base) != plugins(current)

    private fun plugins(text: String): List<String> {
        val parts = mutableListOf<String>()
        listOf(PLUGINS_BLOCK, BUILDSCRIPT_BLOCK).forEach { block ->
            block.findAll(text).forEach { start ->
                val open = start.range.last
                parts += text.substring(open, closing(text, open, '{', '}')).collapse()
            }
        }
        APPLIED.findAll(text).forEach { parts += it.value.collapse() }
        return parts
    }

    /**
     * Whether [text] names [module] or its directory [dir]: by its Gradle path, its directory as a
     * path, or its typesafe accessor (`projects.beta`). In a settings script, declaring the module
     * (`include`, `includeFlat`, and a descriptor's `projectDir`, `buildFileName` or `name`) is not
     * naming it, since every settings script declares every module.
     */
    fun names(text: String, module: String, dir: String, settings: Boolean): Boolean {
        val read = if (settings) withoutDeclarations(text) else text
        val needles = listOf(
            Regex("""(?<![\w:-])${Regex.escape(module)}(?![\w-])"""),
            Regex("""(?<![\w.-])${Regex.escape(dir)}(?![\w-])"""),
            Regex("""(?<![\w])projects\.${Regex.escape(accessor(module))}(?![\w])"""),
        )
        return needles.any { it.containsMatchIn(read) }
    }

    /** The typesafe project accessor of a Gradle path: `:my-lib:core` is `myLib.core`. */
    private fun accessor(module: String): String = module.trim(':').split(':').joinToString(".") { segment ->
        segment.split('-', '_').filter { it.isNotEmpty() }.mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar(Char::uppercaseChar)
        }.joinToString("")
    }

    private fun withoutDeclarations(text: String): String {
        var out = text
        // `include(":a", ":b")` and its multi-line form, through its closing parenthesis.
        while (true) {
            val call = INCLUDE_CALL.find(out) ?: break
            val open = call.range.last
            out = out.removeRange(call.range.first, closing(out, open, '(', ')'))
        }
        // `include ':a', ':b'`, continued on the next line after a trailing comma.
        out = INCLUDE_GROOVY.replace(out, "")
        return DESCRIPTOR.replace(out, "")
    }

    /**
     * Whether the `project(...)` call from [start] to the `(` at [open] is the whole argument of a
     * dependency-configuration call: `implementation(project(":core"))`, `api project(path: ':x')`.
     */
    private fun asDependency(text: String, start: Int, open: Int): Boolean {
        val before = text.substring(0, start).trimEnd()
        val configuration = KOTLIN_CONFIGURATION.find(before)?.groupValues?.get(1)
            ?: GROOVY_CONFIGURATION.find(text.substring(0, start))?.groupValues?.get(1)
            ?: return false
        if (CONFIGURATIONS.none { configuration.lowercase().endsWith(it) }) return false
        val end = closing(text, open, '(', ')')
        val after = text.substring(end).trimStart(' ', '\t')
        return after.isEmpty() || after[0] in ")\n\r,;}"
    }

    /**
     * The index just past the [close] matching the [open] at [at], skipping string literals; the
     * text's length when there is none.
     */
    private fun closing(text: String, at: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = at
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == open -> depth++
                c == close -> if (--depth == 0) return i + 1
            }
            i++
        }
        return text.length
    }

    /** The string literals in [text], single, double and triple quoted. */
    private fun literals(text: String): List<String> = LITERAL.findAll(text).map { it.value }.toList()

    private fun String.collapse() = replace(Regex("""\s+"""), " ").trim()

    /** Every settings script Gradle reads at the root, relative to it. */
    private val SETTINGS = listOf("settings.gradle.kts", "settings.gradle")

    private fun identifier(name: String) = Regex("""(?<![\w$])$name(?![\w$])""")

    /** What reaches past the module, each with how a refusal names it (null: as matched). */
    private val REACHING: List<Pair<Regex, String?>> = listOf(
        "allprojects", "subprojects", "rootProject", "parent", "childProjects", "findProject",
        "evaluationDependsOn", "getByPath", "findByPath", "rootDir", "settingsDir", "settingsDirectory",
        "parentFile", "getAllprojects", "getSubprojects", "getRootProject", "getParent", "getChildProjects",
        "getRootDir", "getSettingsDir", "getParentFile", "getGradle",
    ).map { identifier(it) to null } + listOf(
        // A top-level `configure(...)` reaches the projects it is handed; `x.configure {}` is a task's own.
        Regex("""(?<![\w$.])configure(?![\w$])""") to null,
        // `gradle.` as a receiver, not `org.gradle.` or a catalog alias `libs.gradle.x`.
        Regex("""(?:(?<![\w$.])|(?<=\bproject\.))gradle\s*\.""") to "`gradle.`",
        Regex("""\bapply\s*\(?\s*from\b""") to "`apply from`",
        Regex("""\bSystem\s*\.\s*(?:setProperty|setProperties|getProperties)\b""") to "the JVM's system properties",
    )

    private val PARENT_SEGMENT = Regex("""(?:^|[/\\"'])\.\.(?:[/\\"']|$)""")

    private val LITERAL = Regex(""""{3}[\s\S]*?"{3}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'""")

    private val PROJECT_CALL = Regex("""(?<![\w$])project\s*\(""")

    /** The call a `project(...)` is the argument of, in Kotlin: `implementation(` or `"implementation"(`. */
    private val KOTLIN_CONFIGURATION = Regex("""(?:^|[^\w$.])"?(\w+)"?\s*\($""")

    /** The call a `project(...)` is the argument of, in Groovy: `implementation `. */
    private val GROOVY_CONFIGURATION = Regex("""(?:^|[^\w$.])(\w+)[ \t]+$""")

    /** How a dependency configuration's name ends, compared lower-cased. */
    private val CONFIGURATIONS = listOf(
        "implementation", "api", "compileonly", "runtimeonly", "compileonlyapi", "annotationprocessor",
        "kapt", "ksp", "classpath", "compile", "runtime", "testfixtures", "platform", "enforcedplatform",
    )

    private val PLUGINS_BLOCK = Regex("""(?<![\w$.])plugins\s*\{""")

    private val BUILDSCRIPT_BLOCK = Regex("""(?<![\w$.])buildscript\s*\{""")

    private val APPLIED = Regex(
        """(?:\bapply\s*\(?\s*plugin\b|\bpluginManager\s*\.\s*apply\b|(?<![\w$])plugins\s*\.\s*(?:apply|withId)\b)[^\n]*"""
    )

    private val INCLUDE_CALL = Regex("""(?<![\w$.])include(?:Flat)?\s*\(""")

    private val INCLUDE_GROOVY = Regex("""(?m)(?<![\w$.])include(?:Flat)?[ \t]+(?:[^\n]*,[ \t]*\r?\n)*[^\n]*""")

    private val DESCRIPTOR = Regex(
        """(?m)^.*\b(?:project|findProject)\s*\(\s*["'][^"']*["']\s*\)\s*!?!?\??\.\s*(?:projectDir|buildFileName|name)\s*=.*$"""
    )

    /** Repository-relative text through the working tree, null when it cannot be read. */
    fun reader(rootDir: File): (String) -> String? = { path -> runCatching { File(rootDir, path).readText() }.getOrNull() }

    /** The base revision of a repository-relative file, through git. */
    fun base(rootDir: File, against: String?): (String) -> Base = { path ->
        if (against == null) Base.Unknown
        else {
            // `./`: the path is relative to the root project, which git runs in, not to the repository.
            val text = ChangeDetection.rawGit(rootDir, listOf("show", "$against:./$path"))
            when {
                text != null -> Base.Text(text)
                ChangeDetection.rawGit(rootDir, listOf("ls-tree", "--name-only", against, "--", path))?.isBlank() == true -> Base.Absent
                else -> Base.Unknown
            }
        }
    }
}
