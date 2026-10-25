package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

// ChangeDetection's git questions as Gradle asks them at configuration time: through a value
// source, memoised for the build, its failures recorded for the run to name.

internal fun ChangeDetection.defaultBase(providers: ProviderFactory, projectDir: File, memo: BuildMemo? = null): ChangeDetection.Base? =
    // A candidate that does not resolve is the fallback working, not git failing.
    defaultBase(cachedRunner(providers, projectDir, memo, recordFailures = false))

internal fun ChangeDetection.changedPaths(
    providers: ProviderFactory,
    projectDir: File,
    against: String,
    memo: BuildMemo? = null,
): List<String>? = changedPaths(cachedRunner(providers, projectDir, memo), projectDir, against)

internal fun ChangeDetection.trackedPaths(
    providers: ProviderFactory,
    projectDir: File,
    against: String,
    memo: BuildMemo? = null,
): List<String>? = trackedPaths(cachedRunner(providers, projectDir, memo), projectDir, against)

internal fun ChangeDetection.outsideRoot(providers: ProviderFactory, projectDir: File, against: String, memo: BuildMemo? = null): List<String>? =
    outsideRoot(cachedRunner(providers, projectDir, memo), projectDir, against)

internal fun ChangeDetection.head(providers: ProviderFactory, projectDir: File, memo: BuildMemo? = null): String? =
    head(cachedRunner(providers, projectDir, memo))

/** The merge base of [a] and [b]; diffing from it reports at least as much as from [a]. */
internal fun ChangeDetection.commonAncestor(
    providers: ProviderFactory,
    projectDir: File,
    a: String,
    b: String,
    memo: BuildMemo? = null,
): String? {
    if (a.startsWith("-") || b.startsWith("-")) {
        return null
    }
    return cachedRunner(providers, projectDir, memo)
        .run(listOf("merge-base", "--end-of-options", a, b))
        ?.firstOrNull()?.takeIf(String::isNotBlank)
}

/** Runs git at configuration time, through the memoised value source. */
internal fun ChangeDetection.cachedRunner(
    providers: ProviderFactory,
    projectDir: File,
    memo: BuildMemo?,
    recordFailures: Boolean = true,
): ChangeDetection.Runner = ChangeDetection.Runner { arguments ->
    cachedRawGit(providers, projectDir, memo, arguments, recordFailures)
        ?.lines()?.filter(String::isNotBlank)
}

/** git's whole output through the memoised value source: one process per build. */
internal fun ChangeDetection.cachedRawGit(
    providers: ProviderFactory,
    projectDir: File,
    memo: BuildMemo?,
    arguments: List<String>,
    recordFailures: Boolean = true,
): String? {
    // Keyed by strings: the configuration cache does not preserve `File` reference equality,
    // so a `File` key would miss and spawn a subprocess on every ask.
    val key = (listOf(projectDir.path) + arguments).joinToString("\u0000")
    val create = {
        providers.of(GitOutput::class.java) {
            it.parameters.workingDir.set(projectDir)
            it.parameters.arguments.set(arguments.toList())
        }
    }
    val ask = { (memo?.provider(key, create) ?: create()).get() }
    val output = runCatching {
        memo?.time("git.${arguments.first()}", ask) ?: ask()
    }.getOrNull() ?: return null
    if (output == FAILED || output == TIMED_OUT) {
        if (recordFailures) memo?.recordFailure(
            if (output == TIMED_OUT) {
                "git ${arguments.first()} did not answer within ${TIMEOUT_SECONDS}s"
            } else {
                "git ${arguments.joinToString(" ")} could not answer"
            }
        )
        return null
    }
    return output
}

/** Runs git in a [ValueSource]; `providers.exec` offers no timeout for a hung git. */
internal abstract class GitOutput : ValueSource<String, GitOutput.Parameters> {

    interface Parameters : ValueSourceParameters {
        val workingDir: DirectoryProperty
        val arguments: ListProperty<String>
    }

    override fun obtain(): String = ChangeDetection.exec(parameters.workingDir.get().asFile, parameters.arguments.get())
}
