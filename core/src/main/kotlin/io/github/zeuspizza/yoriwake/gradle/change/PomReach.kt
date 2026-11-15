package io.github.zeuspizza.yoriwake.gradle.change

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Whether a changed POM of a Maven module off this execution's classpath can still reach its tests:
 * the counterpart of [BuildFileReach] for a module that inherits and imports rather than configures.
 *
 * A POM reaches other modules when it is a parent, a BOM or an aggregator (packaging `pom`), a
 * plugin the reactor builds (`maven-plugin`), or declares a build extension, which is handed the
 * whole session; when a value names a path outside its module (`..`, an absolute path, the parent's
 * or the reactor's directory); or when its plugins differ between the revisions, since a mojo can
 * change other projects. Either revision doing so keeps it forcing.
 */
internal object PomReach {

    /** Why the POM keeps forcing at [current] and [base]; null when neither revision reaches out. */
    fun refusal(base: BuildFileReach.Base, current: String): String? {
        val now = parse(current) ?: return "it does not parse"
        reach(now)?.let { return it }
        when (base) {
            is BuildFileReach.Base.Text -> {
                val then = parse(base.text) ?: return "its base revision does not parse"
                reach(then)?.let { return "its base revision $it" }
                if (plugins(then) != plugins(now)) return "it changes its plugins"
            }
            BuildFileReach.Base.Absent -> Unit
            BuildFileReach.Base.Unknown -> return "its base revision cannot be read"
        }
        return null
    }

    /** Why the POM [project] reaches past its module; null when it does not. */
    private fun reach(project: Element): String? {
        val packaging = child(project, "packaging")?.textContent?.trim() ?: "jar"
        if (packaging == "pom" || packaging == "maven-plugin") return "has packaging $packaging"
        if ('$' in packaging || '@' in packaging) return "has packaging $packaging, which is not a literal"
        if (elements(project).any { it.isExtension() }) return "declares a build extension"
        values(project).forEach { value ->
            if (literalPaths(value).any { PARENT_SEGMENT.containsMatchIn(it) }) return "names a path above its module (`..`)"
            if (literalPaths(value).any { ABSOLUTE.containsMatchIn(it) }) return "names an absolute path"
            KEYWORDS.firstOrNull { it.containsMatchIn(value) }?.let { keyword ->
                return "names ${keyword.find(value)!!.value}"
            }
        }
        return null
    }

    /** `<build><extensions><extension>` anywhere, or a plugin whose `<extensions>` is not `false`. */
    private fun Element.isExtension(): Boolean {
        val parent = parentNode as? Element ?: return false
        return (tagName == "extension" && parent.tagName == "extensions" && (parent.parentNode as? Element)?.tagName == "build") ||
            (tagName == "extensions" && parent.tagName == "plugin" && textContent.trim() != "false")
    }

    /** Every text and attribute value outside `<parent>`. */
    private fun values(project: Element): List<String> = buildList {
        fun walk(node: Node) {
            if (node is Element && node.tagName == "parent" && node.parentNode == project) return
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> node.nodeValue.trim().takeIf { it.isNotEmpty() }?.let(::add)
                Node.ELEMENT_NODE -> {
                    val attributes = node.attributes
                    for (i in 0 until attributes.length) add(attributes.item(i).nodeValue)
                }
            }
            var child = node.firstChild
            while (child != null) {
                walk(child)
                child = child.nextSibling
            }
        }
        walk(project)
    }

    /** [value] without URLs, split where a path cannot continue. */
    private fun literalPaths(value: String): List<String> =
        URL.replace(value, " ").split(Regex("""[\s,;"'=]+""")).filter { it.isNotEmpty() }

    /**
     * Each plugin of the build, its management and its profiles, by coordinates and the coordinates
     * of its dependencies, a `${name}` the POM's own `<properties>` defines replaced by its value.
     */
    private fun plugins(project: Element): List<String> {
        val properties = child(project, "properties")?.let(::elementChildren).orEmpty()
            .associate { it.tagName to it.textContent.trim() }
        fun resolved(text: String?) = text?.trim()?.let { EXPRESSION.replace(it) { m -> properties[m.groupValues[1]] ?: m.value } }
        fun coordinates(element: Element, defaultGroup: String?) = listOf(
            resolved(child(element, "groupId")?.textContent) ?: defaultGroup,
            resolved(child(element, "artifactId")?.textContent),
            resolved(child(element, "version")?.textContent),
        ).joinToString(":")
        return elements(project).filter { it.tagName == "plugin" && (it.parentNode as? Element)?.tagName == "plugins" }
            .map { plugin ->
                val dependencies = child(plugin, "dependencies")?.let(::elementChildren).orEmpty()
                    .map { coordinates(it, null) }.sorted()
                coordinates(plugin, "org.apache.maven.plugins") + dependencies
            }
            .sorted()
    }

    private fun parse(text: String): Element? = runCatching {
        // The JDK's own parser, whatever a plugin on Maven's classpath brings.
        val factory = DocumentBuilderFactory.newDefaultInstance()
        // A POM declares no DTD; refusing one keeps an external entity from being read.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(text))).documentElement
    }.getOrNull()?.takeIf { it.tagName == "project" }

    private fun child(element: Element, name: String): Element? = elementChildren(element).firstOrNull { it.tagName == name }

    private fun elementChildren(element: Element): List<Element> = buildList {
        var child = element.firstChild
        while (child != null) {
            if (child is Element) add(child)
            child = child.nextSibling
        }
    }

    /** Every element under [root], at any depth. */
    private fun elements(root: Element): List<Element> = buildList {
        val pending = ArrayDeque(elementChildren(root))
        while (pending.isNotEmpty()) {
            val element = pending.removeFirst()
            add(element)
            pending += elementChildren(element)
        }
    }

    private val PARENT_SEGMENT = Regex("""(?:^|[/\\}])\.\.(?:[/\\]|$)""")

    private val ABSOLUTE = Regex("""^(?:/|[A-Za-z]:[/\\])""")

    private val URL = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S*""")

    private val EXPRESSION = Regex("""\$\{([^}]+)}""")

    /** What names the parent's or the reactor's directory, the session, or the user's: whole identifiers. */
    private val KEYWORDS = listOf(
        "project.parent.basedir", "project.parent.file", "project.parent.build", "parent.basedir",
        "maven.multiModuleProjectDirectory", "rootDirectory", "topDirectory", "session", "rootdir",
        "user.dir", "user.home", "getParent", "highest-basedir", "directory-of",
    ).map { Regex("""(?<![\w-])${Regex.escape(it)}(?![\w-])""") }
}
