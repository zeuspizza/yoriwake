package io.github.zeuspizza.yoriwake.gradle.change

/**
 * The entries, or a failure naming `pathIgnore`, the entry and the rule it broke: the
 * declaration is the build's own input, so a wrong one fails the build rather than the run.
 */
internal fun PathIgnore.validated(entries: List<String>, owner: String): List<String> = entries.onEach { entry ->
    problem(entry)?.let { why ->
        throw org.gradle.api.InvalidUserDataException(
            "[yoriwake] $owner: pathIgnore holds '$entry', which $why. Entries are Ant-style globs over " +
                "paths relative to the root project, as Gradle's include and exclude take them: `*` and " +
                "`?` within a directory, `**` as a whole segment for any number of directories."
        )
    }
}
