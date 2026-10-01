package dev.demokt

/** A second independent unit, so narrowing has something to narrow away from. */
class Beta {
    fun label(): String = "Beta"
}

fun betaEven(n: Int): Boolean = n % 2 == 0
