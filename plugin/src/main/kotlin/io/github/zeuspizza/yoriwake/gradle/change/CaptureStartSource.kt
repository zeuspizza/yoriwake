package io.github.zeuspizza.yoriwake.gradle.change

import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

/**
 * [CaptureStart.read] at configuration time. A value source, so a reusing build re-obtains it before
 * any task runs, compilation included, and reconfigures when it moved.
 */
internal abstract class CaptureStartSource : ValueSource<String, CaptureStartSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val rootDir: Property<String>
    }

    override fun obtain(): String = CaptureStart.read(File(parameters.rootDir.get())).encode()
}
