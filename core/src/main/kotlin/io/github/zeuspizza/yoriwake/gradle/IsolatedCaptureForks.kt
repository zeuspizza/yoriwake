package io.github.zeuspizza.yoriwake.gradle

/**
 * `yoriwake.isolatedCaptureForks`: how many test JVMs a run recording in isolation may start at a
 * time, a count or the machine's processors. It only ever raises the build's own setting.
 */
internal class IsolatedCaptureForks private constructor(private val count: Int?) : java.io.Serializable {

    /** The count, or for `cores` the processors of the machine running the tests, read when asked. */
    fun resolve(): Int = count ?: Runtime.getRuntime().availableProcessors()

    companion object {
        const val CORES = "cores"

        /** Null for a value that is neither a count of 1 or more nor `cores` in any case. */
        fun parse(value: String): IsolatedCaptureForks? =
            if (value.equals(CORES, ignoreCase = true)) IsolatedCaptureForks(null)
            else value.toIntOrNull()?.takeIf { it >= 1 }?.let(::IsolatedCaptureForks)

        /** What a malformed [value] of the property, spelled [flag] (`-P` or `-D`), is told. */
        fun malformed(flag: String, value: String) =
            "[yoriwake] $flag${SettingNames.ISOLATED_CAPTURE_FORKS}=$value is neither a count nor $CORES. Pass a " +
                "number of test JVMs, 1 or more, or $CORES for the machine's processors."
    }
}
