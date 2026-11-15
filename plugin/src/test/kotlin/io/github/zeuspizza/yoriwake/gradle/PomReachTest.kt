package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.BuildFileReach.Base
import io.github.zeuspizza.yoriwake.gradle.change.PomReach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * When a sibling module's POM can carry its change to a test of another module: a parent, BOM,
 * aggregator or reactor-built plugin, a build extension, a path outside its module, or a change to
 * its plugins, in either revision.
 */
class PomReachTest {

    private fun pom(body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <parent><groupId>dev.demo</groupId><artifactId>demo</artifactId><version>1.0</version><relativePath>../pom.xml</relativePath></parent>
          <artifactId>lib</artifactId>
          <url>https://example.com/lib</url>
          $body
        </project>
    """.trimIndent()

    private val plain = pom("<dependencies><dependency><groupId>a</groupId><artifactId>b</artifactId><version>1</version></dependency></dependencies>")

    private fun refusal(base: String, current: String) = PomReach.refusal(Base.Text(base), current)

    @Test
    fun `an edit that changes a dependency, a comment or the description passes`() {
        listOf(
            pom("<dependencies><dependency><groupId>a</groupId><artifactId>b</artifactId><version>2</version></dependency></dependencies>"),
            pom("<dependencies><dependency><groupId>a</groupId><artifactId>b</artifactId><version>1</version></dependency>" +
                "<dependency><groupId>c</groupId><artifactId>d</artifactId><version>1</version></dependency></dependencies>"),
            plain.replace("<artifactId>lib</artifactId>", "<artifactId>lib</artifactId><!-- allprojects ../x session -->"),
            plain.replace("<artifactId>lib</artifactId>", "<artifactId>lib</artifactId><description>Its library.</description>"),
            pom("<dependencies><dependency><groupId>a</groupId><artifactId>b</artifactId><version>${'$'}{project.parent.version}</version></dependency></dependencies>"),
        ).forEach { current -> assertNull(refusal(plain, current), current) }
    }

    @Test
    fun `a parent, a BOM, an aggregator, a reactor-built plugin or an unresolved packaging refuses`() {
        listOf("pom", "maven-plugin", "${'$'}{packaging}").forEach { packaging ->
            assertNotNull(refusal(plain, pom("<packaging>$packaging</packaging>")), packaging)
        }
    }

    @Test
    fun `a build extension refuses`() {
        assertNotNull(refusal(plain, pom("<build><extensions><extension><groupId>a</groupId><artifactId>b</artifactId><version>1</version></extension></extensions></build>")))
        assertNotNull(
            refusal(
                plain.replace("</dependencies>", "</dependencies><build><plugins><plugin><groupId>a</groupId><artifactId>p</artifactId><version>1</version></plugin></plugins></build>"),
                plain.replace("</dependencies>", "</dependencies><build><plugins><plugin><groupId>a</groupId><artifactId>p</artifactId><version>1</version><extensions>true</extensions></plugin></plugins></build>"),
            )
        )
    }

    @Test
    fun `a value naming a path outside the module, or the parent's, the reactor's or the session's directory, refuses`() {
        listOf(
            "<properties><x>${'$'}{project.parent.basedir}/x</x></properties>",
            "<properties><x>${'$'}{maven.multiModuleProjectDirectory}/x</x></properties>",
            "<properties><x>${'$'}{project.rootDirectory}/shared</x></properties>",
            "<properties><x>../shared</x></properties>",
            "<properties><x>/abs/x</x></properties>",
            "<build><plugins><plugin><groupId>a</groupId><artifactId>p</artifactId><version>1</version><configuration>" +
                "<target><copy todir=\"${'$'}{project.parent.basedir}/x\"/></target></configuration></plugin></plugins></build>",
            "<build><plugins><plugin><groupId>a</groupId><artifactId>p</artifactId><version>1</version><configuration>" +
                "<scripts><script><![CDATA[ session.projects.each { } ]]></script></scripts></configuration></plugin></plugins></build>",
        ).forEach { body ->
            // Unchanged between the revisions, so only the value can refuse.
            assertNotNull(refusal(pom(body), pom(body)), body)
        }
    }

    @Test
    fun `a plugin added, removed or of another version refuses, through a property too`() {
        val plugin = { version: String ->
            "<build><plugins><plugin><groupId>a</groupId><artifactId>p</artifactId><version>$version</version></plugin></plugins></build>"
        }
        assertNotNull(refusal(plain, pom(plugin("1"))))
        assertNotNull(refusal(pom(plugin("1")), plain))
        assertNotNull(refusal(pom(plugin("1")), pom(plugin("2"))))
        assertNotNull(
            refusal(
                pom("<properties><p.version>1</p.version></properties>" + plugin("${'$'}{p.version}")),
                pom("<properties><p.version>2</p.version></properties>" + plugin("${'$'}{p.version}")),
            )
        )
    }

    @Test
    fun `a POM that does not parse refuses, and a new module's is read alone`() {
        assertNotNull(refusal(plain, "<project><artifactId>"))
        assertNull(PomReach.refusal(Base.Absent, plain))
        assertNotNull(PomReach.refusal(Base.Absent, pom("<packaging>pom</packaging>")))
        assertNotNull(PomReach.refusal(Base.Unknown, plain))
    }
}
