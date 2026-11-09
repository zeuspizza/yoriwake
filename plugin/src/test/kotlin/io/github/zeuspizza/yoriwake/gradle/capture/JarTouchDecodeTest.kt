package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathRecord
import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.ExecutionDataWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the decode makes of the library jars each JVM reached and the absent names it looked up. */
class JarTouchDecodeTest {

    private fun execData(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        ExecutionDataWriter(out).visitClassExecution(ExecutionData(1, name.replace('.', '/'), booleanArrayOf(true)))
        return out.toByteArray()
    }

    private fun worker(mapDir: File, worker: String, tests: Int, vararg touches: String) {
        val dir = File(CoverageDecoder.recordsDir(mapDir), "worker-$worker").apply { mkdirs() }
        val index = StringBuilder()
        for (seq in 1..tests) {
            val blob = execData("com.acme.C$seq")
            File(dir, "%06d.exec".format(seq)).writeBytes(blob)
            index.append("$seq\t1000000\t${blob.size}\tSUCCESSFUL\t[class:T$worker$seq]/[method:t()]\n")
        }
        File(dir, "index.tsv").writeText(index.toString())
        File(dir, AgentContract.RAW_SCHEMA_VERSION_FILE).writeText("${AgentContract.RAW_SCHEMA_VERSION}\n")
        File(dir, AgentContract.TOUCHES_FILE).writeText((touches.toList() + AgentContract.TOUCHES_COMPLETE).joinToString("") { "$it\n" })
    }

    private fun rows(mapDir: File, file: String) =
        File(mapDir, file).readLines().filter(String::isNotBlank).map { Tsv.split(it).drop(1).toList() }

    private val modules = mapOf("/repo/lib-json-1.0.jar" to "dev.lib:lib-json", "/repo/lib-math-1.0.jar" to "dev.lib:lib-math")

    @Test
    fun `each module jar a JVM reached is kept by its identity at its first touch, with the JVM recorded`(@TempDir mapDir: File, @TempDir repo: File) {
        val math = File(repo, "lib-math-1.0.jar").apply {
            java.util.zip.ZipOutputStream(outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("lib/math/Add.class")); it.closeEntry() }
        }
        val modules = mapOf("/repo/lib-json-1.0.jar" to "dev.lib:lib-json", math.absolutePath to "dev.lib:lib-math")
        worker(
            mapDir, "1", 3,
            Tsv.join("1", AgentContract.TOUCH_PLAN_STARTED, ""),
            Tsv.join("1", AgentContract.TOUCH_JARS_RECORDED, ""),
            Tsv.join("2", AgentContract.TOUCH_JAR_REACHED, "/repo/lib-json-1.0.jar"),
            Tsv.join("3", AgentContract.TOUCH_JAR, math.absolutePath),
            Tsv.join("3", AgentContract.TOUCH_MISSED, "lib/math/"),
        )

        CoverageDecoder.decode(mapDir, listOf("com.acme"), jarModules = modules)

        assertEquals(
            listOf(listOf("0", AgentContract.JARS_RECORDED), listOf("2", "dev.lib:lib-json"), listOf("3", "dev.lib:lib-math")),
            rows(mapDir, AgentContract.JAR_TOUCH_FILE).sortedBy { it[1] },
        )
        assertEquals(
            listOf(listOf("0", AgentContract.JARS_RECORDED), listOf("3", "lib/math/")),
            rows(mapDir, AgentContract.MISS_TOUCH_FILE).sortedBy { it[1] },
        )
    }

    @Test
    fun `a jar no module is, or anything unobserved, touches every jar from there`(@TempDir mapDir: File) {
        worker(
            mapDir, "1", 3,
            Tsv.join("1", AgentContract.TOUCH_PLAN_STARTED, ""),
            Tsv.join("1", AgentContract.TOUCH_JARS_RECORDED, ""),
            Tsv.join("2", AgentContract.TOUCH_JAR_REACHED, "/elsewhere/other.jar"),
            Tsv.join("3", AgentContract.TOUCH_ALL, "a child process was started"),
        )

        CoverageDecoder.decode(mapDir, listOf("com.acme"), jarModules = modules)

        assertEquals(listOf("2", AgentContract.FIRST_TOUCH_ANY), rows(mapDir, AgentContract.JAR_TOUCH_FILE).single { it[1] == AgentContract.FIRST_TOUCH_ANY })
        assertEquals(listOf("3", AgentContract.FIRST_TOUCH_ANY), rows(mapDir, AgentContract.MISS_TOUCH_FILE).single { it[1] == AgentContract.FIRST_TOUCH_ANY })
    }

    @Test
    fun `without the module list no JVM's jar touches are known`(@TempDir mapDir: File) {
        worker(mapDir, "1", 2, Tsv.join("1", AgentContract.TOUCH_PLAN_STARTED, ""))

        CoverageDecoder.decode(mapDir, listOf("com.acme"))

        assertEquals(listOf(listOf("0", AgentContract.FIRST_TOUCH_ANY)), rows(mapDir, AgentContract.JAR_TOUCH_FILE))
    }

    @Test
    fun `a retained JVM's jar rows survive a capture that does not date the map`(@TempDir mapDir: File) {
        worker(mapDir, "1", 2, Tsv.join("1", AgentContract.TOUCH_PLAN_STARTED, ""), Tsv.join("1", AgentContract.TOUCH_JARS_RECORDED, ""),
            Tsv.join("2", AgentContract.TOUCH_JAR_REACHED, "/repo/lib-json-1.0.jar"))
        CoverageDecoder.decode(mapDir, listOf("com.acme"), jarModules = modules, datesTheMap = true)
        val first = rows(mapDir, AgentContract.JAR_TOUCH_FILE)

        CoverageDecoder.recordsDir(mapDir).deleteRecursively()
        worker(mapDir, "9", 1, Tsv.join("1", AgentContract.TOUCH_PLAN_STARTED, ""))
        CoverageDecoder.decode(mapDir, listOf("com.acme"), selecting = true, jarModules = modules)

        val after = File(mapDir, AgentContract.JAR_TOUCH_FILE).readLines().filter(String::isNotBlank).map(Tsv::split)
        assertEquals(first.size, after.count { !it[0].endsWith("worker-9") } , after.toString())
    }

    @Test
    fun `the classpath record is written by a dating capture only`(@TempDir mapDir: File) {
        val record = ClasspathRecord.Record(
            listOf(ClasspathRecord.Entry(ClasspathRecord.MODULE, "dev.lib:lib-json", "dependency:x", "named")),
            mapOf("dev.lib:lib-json" to mapOf("lib/json/" to "abc")),
        )
        worker(mapDir, "1", 1, Tsv.join("1", AgentContract.TOUCH_PLAN_STARTED, ""))
        CoverageDecoder.decode(mapDir, listOf("com.acme"), classpathRecord = record, datesTheMap = false)
        assertNull(ClasspathRecord.readMap(mapDir))

        CoverageDecoder.decode(mapDir, listOf("com.acme"), classpathRecord = record, datesTheMap = true)
        assertEquals(record, ClasspathRecord.readMap(mapDir))
    }
}
