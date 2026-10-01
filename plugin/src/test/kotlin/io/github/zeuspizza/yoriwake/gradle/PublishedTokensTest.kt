package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.Rule
import io.github.zeuspizza.yoriwake.agent.select.Selector
import io.github.zeuspizza.yoriwake.agent.select.Verdict
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.change.DigestSilence
import io.github.zeuspizza.yoriwake.gradle.change.INLINE_REFUSAL_KEY
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.wiring.FilterVerdict
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Every refusal, reason, verdict and decline token that can reach `explain.json`, `audit.json`,
 * `decisions.tsv`, `task-facts`, `jvm-mode.tsv` or the console, pinned byte for byte. Scripts and people match on
 * these spellings, so a rename is a breaking change even when every other test stays green.
 *
 * One `category<TAB>token` per line: the same spelling can mean different things in two outputs.
 */
class PublishedTokensTest {

    private fun produced(): List<String> = buildList {
        fun add(category: String, tokens: Iterable<String>) = tokens.forEach { add("$category\t$it") }

        add("full-run-kind", Selector.Decision.FullRunKind.values().map { it.token() })
        add("row-verdict", Verdict.values().map { it.inclusionToken() }.distinct())
        add("row-reason", Verdict.values().map { it.reasonToken() })
        add(
            "row-rule",
            Rule.values().map { it.token() } + listOf(AgentContract.RULE_NONE, AgentContract.RULE_UNKNOWN),
        )
        add(
            "rules-basis",
            listOf(
                AgentContract.RULES_COMPLETE, AgentContract.RULES_FROM_REFUSED_INPUTS,
                AgentContract.RULES_NO_CHANGE_SET, AgentContract.RULES_NO_MAP, AgentContract.RULES_FAILED,
            ),
        )
        add("console-tally", Selector.Decision.Reason.values().map { it.token() } + AgentContract.RULE_ENGINE_RUNS_EVERYTHING)
        add(
            "run-outcome",
            listOf(
                AgentContract.RUN_NARROWED, AgentContract.RUN_FULL, AgentContract.RUN_NOT_REQUESTED,
                AgentContract.RUN_NOT_DECIDED,
            ),
        )
        add("refusal-kind", RefusalKind.entries.map { it.token })
        add("decline-kind", ScopeOutcome.NotApplied.Kind.entries.map { it.token })
        add("audit-blocker", Audit.BlockerKind.entries.map { it.token })
        add("audit-state", Audit.State.entries.map { it.token })
        add("digest-silence", DigestSilence.entries.map { it.token })
        add("filter-verdict", listOf(FilterVerdict.UNFILTERED, FilterVerdict.FILTERED))
        add("framework", listOf(Audit.TaskFacts.PLATFORM))
        add("refusals-key", listOf(INLINE_REFUSAL_KEY))
        add(
            "capture-mode",
            listOf(AgentContract.MODE_ISOLATED, AgentContract.MODE_SHARED, CoverageDecoder.MIXED_MODES),
        )
    }

    @Test
    fun `every published token is spelled exactly as the golden list`() {
        val golden = checkNotNull(javaClass.getResourceAsStream("/golden/tokens.txt")) { "no golden tokens" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .lines().filter(String::isNotEmpty)
        val produced = produced()
        assertEquals(produced.size, produced.toSet().size, "a token is produced twice in one category")
        assertEquals(golden, produced.sorted())
    }
}
