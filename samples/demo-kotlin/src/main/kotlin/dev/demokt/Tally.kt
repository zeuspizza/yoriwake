package dev.demokt

/**
 * The shapes the default edit shapes have no edit for: a Unit function, functions that return
 * something other than a Boolean, and an override. It declares no Boolean function and no constant,
 * so the default set draws nothing here and its targets on this fixture stay what they were.
 */
class Tally {
    private var count = 0

    fun add(by: Int) {
        count += by
    }

    fun total(): Int = count

    override fun toString(): String {
        return "Tally($count)"
    }
}
