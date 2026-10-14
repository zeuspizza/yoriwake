package io.github.zeuspizza.yoriwake.gradle

import java.io.File
import java.nio.file.Files

/**
 * A directory holding a `git` to put first on PATH: it appends each call's arguments to [log] when
 * given one, exits 128 when its arguments contain any of [failOn] or begin with any of
 * [failCommands], and otherwise runs the real git with the same arguments, input and output.
 *
 * On Windows it is a compiled `git.exe`: there ProcessBuilder("git") resolves only `git.exe`, so a
 * script named `git` or `git.cmd` would be passed over and the real git would answer instead.
 */
internal fun gitShim(
    dir: File,
    failOn: List<String> = emptyList(),
    failCommands: List<String> = emptyList(),
    log: File? = null,
): File {
    dir.mkdirs()
    if (windows) {
        compiledShim.copyTo(File(dir, "git.exe"), overwrite = true)
        File(dir, "shim.conf").writeText(
            (listOf("real=${realGit()}") + listOfNotNull(log?.let { "log=${it.absolutePath}" }) +
                failOn.map { "fail=$it" } + failCommands.map { "failcommand=$it" }).joinToString("\n")
        )
    } else {
        File(dir, "git").apply {
            writeText(
                "#!/bin/sh\n" +
                    (log?.let { "echo \"\$*\" >> '${it.absolutePath}'\n" } ?: "") +
                    failOn.joinToString("") { "case \"\$*\" in *'$it'*) exit 128;; esac\n" } +
                    failCommands.joinToString("") { "if [ \"\$1\" = '$it' ]; then exit 128; fi\n" } +
                    "exec '${realGit()}' \"\$@\"\n"
            )
            setExecutable(true)
        }
    }
    return dir
}

/**
 * This JVM's environment with [bin] first on the path, for a build's daemon. Whole, not the path
 * alone: Windows needs `SystemRoot` and the rest to start a process, and names the variable `Path`.
 */
internal fun withFirstOnPath(bin: File): Map<String, String> {
    val environment = System.getenv().toMutableMap()
    val key = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    environment[key] = bin.absolutePath + File.pathSeparator + environment[key].orEmpty()
    return environment
}

/** The git this JVM would run, by the same lookup the plugin's ProcessBuilder makes. */
internal fun realGit(): String {
    val name = if (windows) "git.exe" else "git"
    return System.getenv("PATH").split(File.pathSeparator).map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }?.absolutePath
        ?: error("no $name on PATH for the shim to delegate to")
}

private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** Compiled once per JVM, by the C# compiler every Windows 10 and later ships with .NET Framework. */
private val compiledShim: File by lazy {
    val framework = File(System.getenv("WINDIR") ?: "C:\\Windows", "Microsoft.NET")
    val csc = listOf("Framework64", "Framework").map { File(framework, "$it\\v4.0.30319\\csc.exe") }
        .firstOrNull(File::isFile) ?: error("no csc.exe under $framework to compile the git shim")
    val work = Files.createTempDirectory("yoriwake-gitshim").toFile()
    val source = File(work, "GitShim.cs")
    source.writeBytes(checkNotNull(GitShimSource::class.java.getResourceAsStream("/gitshim/GitShim.cs")).use { it.readBytes() })
    val exe = File(work, "git.exe")
    val process = ProcessBuilder(csc.absolutePath, "/nologo", "/out:${exe.absolutePath}", source.absolutePath)
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0 && exe.isFile) { "compiling the git shim failed: $output" }
    exe
}

private object GitShimSource
