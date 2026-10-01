package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.engines.JUnit4Events
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What the plugin and the agent share that the compiler cannot check: tokens, escaping, and the
// bytecode the agent writes into JUnit's classes.
class ModuleContractTest {

    @Test
    fun `every refusal token the plugin can send is one the agent and the readers know`() {
        // A token no reader recognises arrives as a refusal nobody can act on.
        val sent = RefusalKind.entries.map { it.token }
        assertEquals(sent.size, sent.toSet().size, "two refusals share a token: $sent")
        sent.forEach { token ->
            assertTrue(
                token.isNotBlank() && token == token.lowercase() && " " !in token,
                "`$token` is not the shape every other token on this channel has",
            )
        }
        // The coarse token the agent pairs them with says which side refused.
        assertEquals(
            "daemon-refused",
            io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED.token(),
        )
    }

    @Test
    fun `what the agent escapes, its reader unescapes`() {
        // A refusal reason is prose, and prose from a Windows build carries backslashes.
        val prose = "C:\\builds\\app declared a constant\tand the map disagreed\nover two lines"
        val escaped = io.github.zeuspizza.yoriwake.agent.contract.Tsv.escape(prose)
        assertEquals(prose, io.github.zeuspizza.yoriwake.agent.contract.Tsv.unescape(escaped))
    }

    @Test
    fun `the bytecode the hook writes names methods that actually exist on JUnit4Events`() {
        // JUnit4Hook writes INVOKESTATIC calls into JUnit's own class, which no compiler checks;
        // reflection makes a rename fail this build instead of a host's test run.
        for (hook in listOf("started", "finished", "failed")) {
            val method = JUnit4Events::class.java.getMethod(hook, Any::class.java)
            assertEquals(Void.TYPE, method.returnType, "$hook must return void")
            assertTrue(
                java.lang.reflect.Modifier.isStatic(method.modifiers),
                "$hook must be static; the injected call is INVOKESTATIC",
            )
            assertTrue(
                java.lang.reflect.Modifier.isPublic(method.modifiers),
                "$hook must be public; it is called from a class in another package",
            )
        }
    }
}
