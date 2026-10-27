package io.github.zeuspizza.yoriwake.gradle.capture

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * The failures the host's own JUnit XML report holds (Gradle's, or Surefire's or Failsafe's),
 * which the build's JVM writes from the events it was sent: a test JVM stopped before it wrote its
 * records cannot lose them. Read by class only, the top-level class of each test, since the
 * report's names are not the agent's test ids.
 */
internal object JUnitXmlFailures {

    /**
     * The top-level class of every test reported failed or in error under [dir]. Empty when there
     * is no report. A report that cannot be read is passed to [unreadable] and adds nothing, which
     * leaves the agent's own records as the account of it.
     *
     * Streamed, not parsed whole: a report inlines everything its tests printed, and this runs in
     * the build's own JVM.
     */
    fun classes(dir: File?, unreadable: (File, Throwable) -> Unit = { _, _ -> }): Set<String> {
        val reports = dir?.listFiles { file -> file.isFile && file.name.endsWith(".xml") }.orEmpty()
        val found = LinkedHashSet<String>()
        for (report in reports.sortedBy(File::getName)) {
            runCatching { found += failedIn(report) }.onFailure { unreadable(report, it) }
        }
        return found
    }

    private fun failedIn(report: File): Set<String> {
        val factory = XMLInputFactory.newInstance().apply {
            // The host's own output, but read without a DTD or entities all the same.
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val failed = LinkedHashSet<String>()
        report.inputStream().buffered().use { input ->
            val reader = factory.createXMLStreamReader(input)
            try {
                var testClass: String? = null
                while (reader.hasNext()) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
                    when (reader.localName) {
                        "testcase" -> testClass = reader.getAttributeValue(null, "classname")
                        "failure", "error" -> testClass?.substringBefore('$')?.takeIf(String::isNotEmpty)?.let(failed::add)
                    }
                }
            } finally {
                reader.close()
            }
        }
        return failed
    }
}
