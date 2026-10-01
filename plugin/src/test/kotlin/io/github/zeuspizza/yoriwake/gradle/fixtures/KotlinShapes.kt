package io.github.zeuspizza.yoriwake.gradle.fixtures

/**
 * Kotlin shapes Recordability must tell apart, as real compiled bytecode, so narrowing the Kotlin
 * refusal has a test that fails first.
 */

/** Body copied into every call site. Its absence from coverage proves nothing. Must stay refused. */
inline fun copiedIntoCallers(n: Int): Boolean = n > 0

/** Copied exactly as a Java `static final` String is. Must stay refused. */
const val INLINED_CONSTANT: Int = 10

/** Ordinary Kotlin: no inline member, no compile-time constant, and a probe that fires. */
class OrdinaryKotlin {
    fun twice(n: Int): Int = n * 2
}

/** Also declares an inline member. Recordability judges the class, so this must stay refused. */
class MixedKotlin {
    fun plain(n: Int): Int = n + 1

    @Suppress("NOTHING_TO_INLINE")
    inline fun alsoCopied(n: Int): Boolean = n > 0
}

/**
 * Calls the inline function, so its body is copied in here with a SourceDebugExtension naming
 * KotlinShapes.kt: the consumer side of the edge coverage cannot see.
 */
class InlinesSomethingElse {
    fun ask(n: Int): Boolean = copiedIntoCallers(n)
}

/** Inlines nothing. Its class file carries no SMAP at all, which must read as "nothing named". */
class InlinesNothing {
    fun ask(n: Int): Boolean = n > 0
}
