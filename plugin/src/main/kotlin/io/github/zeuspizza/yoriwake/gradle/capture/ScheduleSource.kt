package io.github.zeuspizza.yoriwake.gradle.capture

import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

/**
 * [Schedule.dueIn] for one map, as a value source: a reusing configuration-cache entry re-obtains
 * it, so an entry stored while not due is discarded the build it falls due. The verdict alone, not
 * the count, which changes every build and would miss the cache on every run.
 */
internal abstract class ScheduleSource : ValueSource<String, ScheduleSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val mapDir: Property<String>
        val builds: Property<Int>
        val days: Property<Int>
    }

    override fun obtain(): String? =
        Schedule.dueIn(File(parameters.mapDir.get()), parameters.builds.orNull, parameters.days.orNull)
}
