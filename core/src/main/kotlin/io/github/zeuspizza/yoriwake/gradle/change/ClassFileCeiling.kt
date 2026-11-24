package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.capture.ClassFileSupport as AgentClassFileSupport
import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileSupport
import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileVersions
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView

/**
 * The refusal for a task whose test JVM is newer than the agent's ASM reads, or whose own classes
 * are newer than the plugin's ASM reads or than the JaCoCo officially instruments that recorded the
 * map or captures this run, or null. Checked before every rule that reads those classes, so the
 * named cause wins over the unreadable digest or missing coverage it would otherwise surface as.
 * [mapJacoco] is the oldest JaCoCo that recorded the map's records; null takes the host's.
 */
internal fun classFileCeiling(facts: ClasspathView, artifacts: TaskArtifacts, mapJacoco: String? = null): CeilingRefusal? {
    val testJvm = facts.testJvm()
    val classes = classCeiling(facts, artifacts, testJvm, mapJacoco)
    val agentCeiling = AgentClassFileSupport.effectiveCeiling(facts.agentCeiling())
    val jvmMajor = testJvm?.let(ClassFileSupport::majorOfJava)
    if (jvmMajor != null && jvmMajor > agentCeiling) return testJvmRefusal(jvmMajor, agentCeiling, classes)
    return classes
}

private fun classCeiling(facts: ClasspathView, artifacts: TaskArtifacts, testJvm: Int?, mapJacoco: String?): CeilingRefusal? {
    val versions = artifacts.classFileVersions(testJvm) ?: return null
    // Null when the facts carry no JaCoCo version at all, which leaves JaCoCo's ceiling out.
    val jacocoVersion = facts.jacocoVersion()?.let { configured ->
        JudgedJacoco(configured, facts.capturingJacocoVersion() ?: configured, mapJacoco)
    }
    // A map is only as good as the oldest JaCoCo that recorded it, and this run's records only as
    // good as the JaCoCo capturing them: the older of the two decides.
    val jacocoCeiling = jacocoVersion?.let { minOf(majorCeiling(it.capturing), majorCeiling(it.recorded)) }
        ?: Int.MAX_VALUE
    val ceiling = minOf(ClassFileSupport.asmCeiling(), jacocoCeiling)
    if (versions.maxMajor > ceiling) return ceilingRefusal(versions, jacocoVersion, jacocoCeiling, facts.jacocoSetting())
    return null
}

/**
 * The JaCoCo versions a class is judged by: [configured] is the host's (empty when unreadable),
 * [capturing] the one whose agent captures this run, and [mapRecorded] the oldest that recorded
 * the map, null when the map does not say.
 */
private class JudgedJacoco(val configured: String, val capturing: String, val mapRecorded: String?) {
    /** What the map is judged by: a map that does not say is taken as recorded by the host's. */
    val recorded: String get() = mapRecorded ?: configured
}

private fun majorCeiling(version: String): Int = ClassFileSupport.majorOfJava(ClassFileSupport.jacocoJava(version))

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

/**
 * The line a run prints when the host's JaCoCo version (`jacoco.toolVersion` on Gradle, the
 * jacoco-maven-plugin version on Maven) is not one yoriwake knows, or null.
 */
internal fun jacocoCeilingNote(facts: ClasspathView): String? =
    facts.jacocoVersion()?.let { ClassFileSupport.jacocoCeiling(it.ifEmpty { null }).note }


/**
 * [jacocoVersion] is what JaCoCo's ceiling was judged by, null when it does not apply;
 * [jacocoCeiling] is that ceiling as a major version, and [jacocoSetting] where the build sets the
 * host's version.
 */
private fun ceilingRefusal(
    versions: ClassFileVersions,
    jacocoVersion: JudgedJacoco?,
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
            val capturingShort = versions.maxMajor > majorCeiling(jacocoVersion.capturing)
            val recordedShort = versions.maxMajor > majorCeiling(jacocoVersion.recorded)
            // A map recorded by another JaCoCo than this run's is lifted only by recording anew.
            val recordAgain = recordedShort && jacocoVersion.recorded != jacocoVersion.capturing
            if (capturingShort) {
                add(
                    "JaCoCo ${jacocoVersion.capturing.ifEmpty { "(its version unread)" }} ($jacocoSetting) officially " +
                        "instruments up to Java ${ClassFileSupport.jacocoJava(jacocoVersion.capturing)}" to
                        "a $jacocoSetting that officially supports $java" + if (recordAgain) " and a recording run" else "",
                )
            }
            if (recordedShort && (!capturingShort || recordAgain)) {
                val by = jacocoVersion.mapRecorded?.let { "the map was recorded by JaCoCo $it" }
                    ?: "the map does not say which JaCoCo recorded it, so it is taken as recorded by " +
                        "JaCoCo ${jacocoVersion.configured.ifEmpty { "(its version unread)" }} ($jacocoSetting)"
                add(
                    "$by, which officially instruments up to Java ${ClassFileSupport.jacocoJava(jacocoVersion.recorded)}" to
                        if (capturingShort) "" else "a recording run with this run's JaCoCo ${jacocoVersion.capturing}",
                )
            }
        }
    }
    val remedy = readers.map { it.second }.filter(String::isNotEmpty).joinToString(" and ")
        .replaceFirstChar(Char::uppercase) + " lifts this."
    return CeilingRefusal(
        RefusalKind.CLASS_FILE_UNSUPPORTED,
        "${versions.firstAtMax} is compiled for $java; ${readers.joinToString(", and ") { it.first }}, " +
            "so its digest or its coverage cannot be trusted",
        remedy,
    )
}
