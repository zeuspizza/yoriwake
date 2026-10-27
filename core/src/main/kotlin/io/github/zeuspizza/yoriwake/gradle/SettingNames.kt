package io.github.zeuspizza.yoriwake.gradle

/**
 * The property names the shared logic names in what it tells the user; each front end reads them
 * and spells them as its host does (`-P` on Gradle, `-D` on Maven).
 */
internal object SettingNames {
    const val SELECT = "yoriwake.select"
    const val OBSERVE = "yoriwake.observe"
    const val COMPLEMENT = "yoriwake.complement"
    const val FULL_RUN = "yoriwake.fullRun"
    const val BASE = "yoriwake.base"
    const val TRUSTED_MAPS = "yoriwake.trustedMaps"
    const val ISOLATED_CAPTURE = "yoriwake.isolatedCapture"
    const val RECORD_SEQUENTIALLY = "yoriwake.recordSequentially"
}
