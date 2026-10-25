package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import java.io.File

/** [EnvironmentFingerprint.check], with a comparison that failed read as no fingerprint. */
internal fun checkEnvironment(mapDir: File, view: EnvironmentFingerprint.View, rules: EnvironmentFingerprint.Rules) =
    runCatching { EnvironmentFingerprint.check(mapDir, view, rules) }
        .getOrElse { EnvironmentFingerprint.Check(EnvironmentFingerprint.Verdict.Unrecorded("the environment could not be compared ($it)")) }

/** The refusal a verdict makes a selecting run take, or null when the environments match. */
internal fun environmentRefusal(verdict: EnvironmentFingerprint.Verdict): Pair<RefusalKind, String>? = when (verdict) {
    EnvironmentFingerprint.Verdict.Matches -> null
    is EnvironmentFingerprint.Verdict.Changed -> RefusalKind.ENVIRONMENT_CHANGED to verdict.reason
    is EnvironmentFingerprint.Verdict.Unrecorded -> RefusalKind.ENVIRONMENT_UNRECORDED to verdict.reason
}
