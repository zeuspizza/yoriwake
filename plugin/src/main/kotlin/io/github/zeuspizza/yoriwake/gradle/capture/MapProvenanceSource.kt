package io.github.zeuspizza.yoriwake.gradle.capture

import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

/**
 * [MapProvenance.verify] as the Test task's input, so a result cached under another verdict is
 * never reused: obtained when Gradle fingerprints the task, after any earlier task wrote the map.
 */
internal abstract class MapProvenanceSource : ValueSource<String, MapProvenanceSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val mapDir: Property<String>
        /** Unset when the list names no digest for this map. */
        val listed: Property<String>
    }

    override fun obtain(): String =
        when (val verdict = MapProvenance.verify(File(parameters.mapDir.get()), parameters.listed.orNull)) {
            is MapProvenance.Verdict.Trusted -> "trusted ${verdict.digest}"
            else -> "untrusted"
        }
}
