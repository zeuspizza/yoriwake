package dev.demokt

/**
 * An inline function's body is copied into each call site, so coverage records InlinedTest
 * executing only itself and this file looks untouched. A rule that skips tests for a changed class
 * with no coverage would skip InlinedTest, the one test a change here breaks.
 */
inline fun aboveZero(n: Int): Boolean = n > 0

/** Not inline, so a change here can be skippable where the above is not. */
fun belowTen(n: Int): Boolean = n < 10
