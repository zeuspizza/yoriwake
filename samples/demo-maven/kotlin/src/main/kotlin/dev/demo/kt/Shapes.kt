package dev.demo.kt

// Inlined into its callers, so a change here reaches a test only through the caller's own bytecode.
inline fun square(n: Int): Int = n * n

class Area {
    fun ofSquare(side: Int): Int = square(side)
}
