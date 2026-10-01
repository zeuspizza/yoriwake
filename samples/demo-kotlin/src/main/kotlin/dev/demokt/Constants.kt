package dev.demokt

/**
 * A compile-time constant holder. `const val` is copied into every consumer, so the holder need
 * never be loaded and its absence from coverage proves nothing.
 */
const val LIMIT: Int = 10

fun withinLimit(n: Int): Boolean = n < LIMIT
