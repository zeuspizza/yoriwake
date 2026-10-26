package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.capture.MapLocation
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.report.audit
import io.github.zeuspizza.yoriwake.gradle.report.blockers
import io.github.zeuspizza.yoriwake.gradle.report.write
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

/**
 * `mvn yoriwake:audit`: whether selection would pay off for each Surefire and Failsafe execution of
 * the reactor, read from its map alone, as Gradle's `yoriwakeAudit` does. Runs no test and changes no
 * map; writes `audit.json` beside each map. `-Dyoriwake.execution=<id>` audits one execution id.
 */
@Mojo(name = "audit", aggregator = true, threadSafe = true)
class AuditMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    /** Only the executions with this id; every one when unset. */
    @Parameter(property = "yoriwake.execution")
    private var execution: String? = null

    override fun execute() {
        val root = (session.request.multiModuleProjectDirectory ?: session.topLevelProject.basedir).absoluteFile
        val home = File(root, Recording.MAPS_HOME)
        val audited = TestExecutions.of(session).filter { execution == null || it.id == execution }
        if (audited.isEmpty()) {
            log.info("[yoriwake] no Surefire or Failsafe execution${execution?.let { " with id $it" } ?: ""} to audit")
            return
        }
        for (each in audited) {
            val mapDir = MapLocation.forTask(home, each.key)
            val blockers = Audit.blockers(Audit.TaskFacts.read(mapDir))
            val result = Audit.audit(mapDir, each.name, "mvn verify", blockers = blockers)
            Audit.write(mapDir, result)
            log.info("[yoriwake] ${each.name}: ${result.state.token} -- ${result.headline}")
            result.lines.forEach { log.info("[yoriwake]   $it") }
        }
    }
}
