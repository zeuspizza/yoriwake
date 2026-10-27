package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.capture.ClassFileSupport as AgentClassFileSupport
import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileSupport
import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileVersions
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView

/**
 * The refusal for a task whose test JVM is newer than the agent's ASM reads, or whose own classes
 * are newer than the plugin's ASM reads or than the host's JaCoCo officially instruments, or null.
 * Checked before every rule that reads those classes, so the named cause wins over the unreadable
 * digest or missing coverage it would otherwise surface as.
 */
internal fun classFileCeiling(facts: ClasspathView, artifacts: TaskArtifacts): CeilingRefusal? {
    val testJvm = facts.testJvm()
    val classes = classCeiling(facts, artifacts, testJvm)
    val agentCeiling = AgentClassFileSupport.effectiveCeiling(facts.agentCeiling())
    val jvmMajor = testJvm?.let(ClassFileSupport::majorOfJava)
    if (jvmMajor != null && jvmMajor > agentCeiling) return testJvmRefusal(jvmMajor, agentCeiling, classes)
    return classes
}

private fun classCeiling(facts: ClasspathView, artifacts: TaskArtifacts, testJvm: Int?): CeilingRefusal? {
    val versions = artifacts.classFileVersions(testJvm) ?: return null
    // Null when the facts carry no JaCoCo version at all, which leaves JaCoCo's ceiling out.
    val jacocoVersion = facts.jacocoVersion()
    val jacocoCeiling = jacocoVersion
        ?.let { ClassFileSupport.majorOfJava(ClassFileSupport.jacocoCeiling(it.ifEmpty { null }).java) }
        ?: Int.MAX_VALUE
    val ceiling = minOf(ClassFileSupport.asmCeiling(), jacocoCeiling)
    if (versions.maxMajor > ceiling) return ceilingRefusal(versions, jacocoVersion, jacocoCeiling, facts.jacocoSetting())
    return null
}

/** Named first when the classes are too new as well, which the cause then lists. */
private fun testJvmRefusal(jvmMajor: Int, agentCeiling: Int, classes: CeilingRefusal?): CeilingRefusal {
    val java = ClassFileSupport.javaOf(jvmMajor)
    val upTo = ClassFileSupport.javaOf(agentCeiling)
    return CeilingRefusal(
        RefusalKind.TEST_JVM_UNSUPPORTED,
        "the test JVM runs Java $java; the yoriwake agent's ASM reads up to Java $upTo, so what its " +
            "tests read cannot all be seen" + (classes?.let { "; and ${it.cause}" } ?: ""),
        "A yoriwake whose ASM reads Java $java, or a test toolchain of Java $upTo or older, lifts this." +
            (classes?.let { " ${it.remedy}" } ?: ""),
    )
}

/** The line a run prints when `jacoco.toolVersion` is not one the plugin knows, or null. */
internal fun jacocoCeilingNote(facts: ClasspathView): String? =
    facts.jacocoVersion()?.let { ClassFileSupport.jacocoCeiling(it.ifEmpty { null }).note }


/**
 * [jacocoVersion] is the host's JaCoCo version, empty when unreadable and null when JaCoCo's ceiling
 * does not apply; [jacocoCeiling] is that ceiling as a major version, and [jacocoSetting] where the
 * build sets that version.
 */
private fun ceilingRefusal(
    versions: ClassFileVersions,
    jacocoVersion: String?,
    jacocoCeiling: Int,
    jacocoSetting: String,
): CeilingRefusal {
    val java = "Java ${ClassFileSupport.javaOf(versions.maxMajor)}"
    val asm = ClassFileSupport.asmCeiling()
    // Every reader the class is above, each with what lifts it.
    val readers = buildList {
        if (versions.maxMajor > asm) {
            add("the plugin's ASM reads up to Java ${ClassFileSupport.javaOf(asm)}" to "a yoriwake whose ASM reads $java")
        }
        if (jacocoVersion != null && versions.maxMajor > jacocoCeiling) {
            add(
                "JaCoCo ${jacocoVersion.ifEmpty { "(its version unread)" }} ($jacocoSetting) officially " +
                    "instruments up to Java ${ClassFileSupport.javaOf(jacocoCeiling)}" to
                    "a $jacocoSetting that officially supports $java",
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
