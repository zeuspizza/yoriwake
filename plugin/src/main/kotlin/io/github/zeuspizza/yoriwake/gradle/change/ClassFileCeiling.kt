package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileSupport
import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileVersions
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts

/** Why a run over classes too new for a reader of them cannot narrow, and what lifts it. */
internal class CeilingRefusal(val kind: RefusalKind, val cause: String, val remedy: String) {
    val reason: String get() = "$cause. The whole suite runs. $remedy"
}

/**
 * The refusal for a task whose own classes are newer than the plugin's ASM reads or than the host's
 * JaCoCo officially instruments, or null. Checked before every rule that reads those classes, so the
 * named cause wins over the unreadable digest or missing coverage it would otherwise surface as.
 */
internal fun classFileCeiling(facts: ClasspathFacts, artifacts: TaskArtifacts): CeilingRefusal? {
    val testJvm = facts.testJvm?.let { runCatching { it.orNull }.getOrNull() }
    val versions = artifacts.classFileVersions(testJvm) ?: return null
    val jacoco = facts.jacocoVersion?.let { version ->
        runCatching { version.orNull }.getOrNull().let { it to ClassFileSupport.jacocoCeiling(it) }
    }
    val jacocoCeiling = jacoco?.let { it.second.java + JAVA_TO_MAJOR } ?: Int.MAX_VALUE
    val ceiling = minOf(ClassFileSupport.asmCeiling(), jacocoCeiling)
    if (versions.maxMajor > ceiling) return ceilingRefusal(versions, jacoco?.first, jacoco?.let { jacocoCeiling })
    return null
}

/** The line a run prints when `jacoco.toolVersion` is not one the plugin knows, or null. */
internal fun jacocoCeilingNote(facts: ClasspathFacts): String? =
    facts.jacocoVersion?.let { ClassFileSupport.jacocoCeiling(runCatching { it.orNull }.getOrNull()).note }

/** [jacocoCeiling] is the host JaCoCo's ceiling as a major version, null when it does not apply. */
private fun ceilingRefusal(versions: ClassFileVersions, jacocoVersion: String?, jacocoCeiling: Int?): CeilingRefusal {
    val java = "Java ${ClassFileSupport.javaOf(versions.maxMajor)}"
    val asm = ClassFileSupport.asmCeiling()
    // Every reader the class is above, each with what lifts it.
    val readers = buildList {
        if (versions.maxMajor > asm) {
            add("the plugin's ASM reads up to Java ${ClassFileSupport.javaOf(asm)}" to "a yoriwake whose ASM reads $java")
        }
        if (jacocoCeiling != null && versions.maxMajor > jacocoCeiling) {
            add(
                "JaCoCo ${jacocoVersion ?: "(its version unread)"} (`jacoco.toolVersion`) officially " +
                    "instruments up to Java ${ClassFileSupport.javaOf(jacocoCeiling)}" to
                    "a `jacoco.toolVersion` that officially supports $java",
            )
        }
    }
    val remedy = readers.joinToString(" and ") { it.second }.replaceFirstChar(Char::uppercase) + " lifts this."
    return CeilingRefusal(
        RefusalKind.CLASS_FILE_UNSUPPORTED,
        "${versions.firstAtMax} is compiled for $java; ${readers.joinToString(", and ") { it.first }}, " +
            "so its digest or its coverage cannot be trusted",
        remedy,
    )
}

private const val JAVA_TO_MAJOR = 44
