package io.github.zeuspizza.yoriwake.gradle

/** The `-P` flags the shared logic names in what it tells the user; the front end reads them. */
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
