package dev.demokt

/** An ordinary unit. A change here must not select the other units' tests. */
class Alpha {
    fun twice(n: Int): Int = n * 2
}

fun alphaPositive(n: Int): Boolean = n > 0
