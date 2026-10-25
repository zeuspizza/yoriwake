package io.github.zeuspizza.yoriwake.gradle.change

/** Why a run over classes too new for a reader of them cannot narrow, and what lifts it. */
internal class CeilingRefusal(val kind: RefusalKind, val cause: String, val remedy: String) {
    val reason: String get() = "$cause. The whole suite runs. $remedy"
}
