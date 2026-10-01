package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import java.io.File

/**
 * Gives a hand-written map the JVM order every real one carries: each record alone in a JVM of
 * its own, which touched nothing. That is the order under which only coverage selects, so a test
 * written before JVM order existed means what it meant then.
 */
fun writeSoloOrder(dir: File) {
    val ids = File(dir, AgentContract.COVERAGE_FILE).takeIf(File::isFile)?.readLines().orEmpty()
        .filter(String::isNotBlank)
        .map { it.substringAfterLast('\t') }
    File(dir, AgentContract.POSITIONS_FILE).writeText(
        ids.mapIndexed { i, id -> "solo-$i\t1\t$id\n" }.joinToString(""),
    )
    File(dir, AgentContract.FIRST_TOUCH_FILE).writeText("")
    File(dir, AgentContract.NAMED_TOUCH_FILE).writeText("")
}
