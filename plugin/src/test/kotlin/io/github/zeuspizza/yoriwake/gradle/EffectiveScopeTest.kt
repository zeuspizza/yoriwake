package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.EffectiveScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The scope record exists to be compared, so every test here is about two runs agreeing or not.
 */
class EffectiveScopeTest {

    @Test
    fun `records includes and excludes, both of them`() {
        val serialized = EffectiveScope(listOf("com.acme.*"), listOf("com.acme.generated.*")).serialize()

        assertEquals(
            listOf(
                EffectiveScope.HEADER,
                "include\tcom.acme.*",
                "exclude\tcom.acme.generated.*",
            ).joinToString("\n", postfix = "\n"),
            serialized,
        )
    }

    @Test
    fun `a reordering by some other plugin is not a change of scope`() {
        // JaCoCo's pattern lists are sets in all but type; comparing them as written would force a
        // full run whenever another plugin appends in a different order.
        assertEquals(
            EffectiveScope(listOf("a.*", "b.*"), listOf("x.*", "y.*")).serialize(),
            EffectiveScope(listOf("b.*", "a.*"), listOf("y.*", "x.*")).serialize(),
        )
    }

    @Test
    fun `an exclude added since capture makes the scope compare unequal`() {
        // A host that excludes a package must not produce a map indistinguishable from one where
        // nothing tested it.
        val before = EffectiveScope(listOf("com.acme.*"), emptyList()).serialize()
        val after = EffectiveScope(listOf("com.acme.*"), listOf("com.acme.legacy.*")).serialize()

        assert(before != after)
    }

    @Test
    fun `instrumenting everything is a scope, not the absence of one`() {
        // Empty includes means "the whole classpath", which is a real answer and must not serialize
        // to nothing -- a reader treats an empty record as "we do not know" and refuses.
        val serialized = EffectiveScope(emptyList(), emptyList()).serialize()

        assertEquals(EffectiveScope.HEADER + "\n", serialized)
    }

    @Test
    fun `a scope that cannot be read off the task is null, never an empty one`() {
        assertNull(EffectiveScope.readFrom(null))
        assertNull(EffectiveScope.readFrom(Any()))
    }

    @Test
    fun `reads includes and excludes off whatever object the jacoco extension is`() {
        val extension = object {
            fun getIncludes(): List<String> = listOf("com.acme.*")
            fun getExcludes(): List<String> = listOf("com.acme.generated.*")
        }

        assertEquals(
            EffectiveScope(listOf("com.acme.*"), listOf("com.acme.generated.*")),
            EffectiveScope.readFrom(extension),
        )
    }

    private val agent = "-javaagent:/x/org.jacoco.agent-0.8.12-runtime.jar"

    @Test
    fun `reads includes and excludes off the JaCoCo agent a command line hands the JVM`() {
        val scope = EffectiveScope.fromAgentArguments(
            listOf("-Xmx1g", "$agent=destfile=/t/jacoco.exec,includes=dev.demo.*:dev.demo.lib.*,excludes=dev.demo.gen.*"),
        )

        assertEquals(EffectiveScope(listOf("dev.demo.*", "dev.demo.lib.*"), listOf("dev.demo.gen.*")), scope)
    }

    @Test
    fun `an agent without includes instruments everything`() {
        assertEquals(EffectiveScope(emptyList(), emptyList()), EffectiveScope.fromAgentArguments(listOf("$agent=destfile=/t/jacoco.exec")))
        assertEquals(EffectiveScope(emptyList(), emptyList()), EffectiveScope.fromAgentArguments(listOf(agent)))
    }

    @Test
    fun `an agent whose path holds a space reads the same`() {
        // Surefire hands the fork a quoted argument whole, its quotes removed.
        val scope = EffectiveScope.fromAgentArguments(
            listOf("-javaagent:/my repo/org.jacoco.agent-0.8.12-runtime.jar=destfile=/t/jacoco.exec,includes=dev.demo.*"),
        )

        assertEquals(EffectiveScope(listOf("dev.demo.*"), emptyList()), scope)
    }

    @Test
    fun `no agent, two agents or an option that does not read is no scope`() {
        assertNull(EffectiveScope.fromAgentArguments(listOf("-Xmx1g", "-javaagent:/x/other-agent.jar=includes=a.*")))
        assertNull(EffectiveScope.fromAgentArguments(listOf("$agent=includes=a.*", "$agent=includes=b.*")))
        assertNull(EffectiveScope.fromAgentArguments(listOf("$agent=destfile=/t/jacoco.exec,dumponexit")))
    }
}
