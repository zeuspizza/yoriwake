package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.select.PathGlob
import org.gradle.api.InvalidUserDataException

/**
 * The build's `dependsOn` entries: a `Test` task of this project and a glob of paths its tests
 * depend on in ways coverage cannot see. A changed path one matches runs that task in full. Read
 * at configuration, so a wrong entry fails the build rather than protecting nothing.
 */
internal object Declarations {

    /** One entry: the task it names, its glob as written, and the glob over root-relative paths. */
    data class Declaration(val task: String, val glob: String, val resolved: String) : java.io.Serializable

    /**
     * [entries] as `task=glob`, with globs resolved against [projectDir] (relative to the root,
     * `""` for the root project), or a failure naming [owner] and the entry. [testTasks] are this
     * project's `Test` task names: an entry naming none of them would declare for nothing.
     */
    fun parse(entries: List<String>, projectDir: String, owner: String, testTasks: Set<String>): List<Declaration> =
        entries.map { entry ->
            val task = entry.substringBefore('=', "")
            val glob = entry.substringAfter('=', "")
            fun refuse(why: String): Nothing = throw InvalidUserDataException(
                "[yoriwake] $owner: dependsOn holds '$entry', which $why. An entry is a `Test` task's name, `=`, " +
                    "and an Ant-style glob relative to this project, or to the root project with a leading `/`: " +
                    "`*` and `?` within a directory, `**` as a whole segment for any number of directories."
            )
            if (task.isEmpty() || '=' !in entry) refuse("is not `task=glob`")
            if (task !in testTasks) {
                refuse("names no `Test` task of this project (it has ${testTasks.sorted().joinToString().ifEmpty { "none" }})")
            }
            PathGlob.problem(glob)?.let { refuse("has a glob that $it") }
            Declaration(task, glob, PathGlob.resolve(glob, projectDir))
        }
}
