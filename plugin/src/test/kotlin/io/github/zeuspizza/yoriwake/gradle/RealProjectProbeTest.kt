package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.report.audit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

// `yoriwakeAudit` against every captured map under `-Pyoriwake.probe.checkouts=<dir>`, skipped when
// the property is unset. Asserts only what holds of any map; the output is the printed report.
class RealProjectProbeTest {

    /** Null when unset, which skips rather than guessing a location. */
    private val checkouts =
        System.getProperty(CHECKOUTS_PROPERTY)?.let { File(it).absoluteFile.normalize() }

    // Real maps have shapes no fixture author imagined. The printed numbers are for comparison with
    // an independent reading of the same file.
    @Test
    fun `report the audit for every map under the probe directory`() {
        assumeTrue(checkouts != null, SKIP)
        val checkouts = checkouts!!
        assumeTrue(checkouts.isDirectory, "$checkouts is not a directory")
        val maps = checkouts.listFiles().orEmpty()
            .flatMap { File(it, ".gradle/yoriwake").listFiles().orEmpty().toList() }
            .filter { it.isDirectory && File(it, AgentContract.COVERAGE_FILE).isFile }
            .sortedBy { it.absolutePath }
        assumeTrue(maps.isNotEmpty(), "no captured maps under $checkouts")

        maps.forEach { mapDir ->
            val result = Audit.audit(mapDir, ":probe", "test")
            println("[audit] ${mapDir.parentFile.parentFile.parentFile.name}/${mapDir.name}")
            println("[audit]   ${result.state.token} -- ${result.headline}")
            result.distribution?.let { d ->
                println(
                    "[audit]   tests=${d.tests} classes=${d.classes} " +
                        "unattributable=${d.unattributableTests} mean=${d.meanShare} " +
                        "median=${d.medianShare} p90=${d.p90Share} max=${d.maxShare} " +
                        "hubs>50%=${d.hubClassesOverHalf} startup=${d.startupClasses}"
                )
            }

            // Invariants that hold of any map.
            result.distribution?.let { d ->
                assertTrue(d.tests > 0, "$mapDir concluded with no tests")
                assertTrue(d.meanShare > 0.0 && d.meanShare <= 1.0, "$mapDir mean ${d.meanShare}")
                assertTrue(d.medianShare > 0.0 && d.medianShare <= 1.0, "$mapDir median ${d.medianShare}")
                assertTrue(d.maxShare > 0.0 && d.maxShare <= 1.0, "$mapDir max ${d.maxShare}")
                assertTrue(d.medianShare <= d.p90Share, "$mapDir median above p90")
                assertTrue(d.p90Share <= d.maxShare, "$mapDir p90 above max")
                assertTrue(
                    d.unattributableShare < Audit.DEFAULT_UNATTRIBUTABLE_CEILING,
                    "$mapDir concluded above the unattributable ceiling",
                )
            }
            // A refusal must never carry a distribution, and a conclusion must always carry one.
            assertTrue(
                (result.state == Audit.State.NO_CONCLUSION) == (result.distribution == null),
                "$mapDir: state ${result.state} and distribution disagree",
            )
        }
    }

    private companion object {
        /** Set by the test task from the Gradle property of the same name. */
        const val CHECKOUTS_PROPERTY = "yoriwake.probe.checkouts"
        const val SKIP = "set -Pyoriwake.probe.checkouts=<dir> to run"
    }
}
