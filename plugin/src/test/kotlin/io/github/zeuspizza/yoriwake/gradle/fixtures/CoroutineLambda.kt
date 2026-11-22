package io.github.zeuspizza.yoriwake.gradle.fixtures

/**
 * A `sequence {}` builder: kotlinc compiles its lambda to `YieldsTwice$values$1` and writes
 * `DebugMetadata` on that class unasked, as on every coroutine lambda.
 */
class YieldsTwice {
    fun values(): List<Int> = sequence {
        yield(1)
        yield(2)
    }.toList()
}
