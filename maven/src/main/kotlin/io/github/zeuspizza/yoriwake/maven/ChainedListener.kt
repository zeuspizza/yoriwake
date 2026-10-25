package io.github.zeuspizza.yoriwake.maven

import org.apache.maven.execution.ExecutionEvent
import org.apache.maven.execution.ExecutionListener

/**
 * Maven's own execution listener with [recording] heard first, on the events it acts on. Every event
 * still reaches [delegate], which prints the build's console.
 */
internal class ChainedListener(private val delegate: ExecutionListener?, private val recording: Recording) : ExecutionListener {

    override fun mojoStarted(event: ExecutionEvent) {
        recording.started(event)
        delegate?.mojoStarted(event)
    }

    override fun mojoSucceeded(event: ExecutionEvent) {
        recording.ended(event, succeeded = true)
        delegate?.mojoSucceeded(event)
    }

    // Surefire fails its mojo when a test fails: the decode runs here too, as Gradle's finalizer does.
    override fun mojoFailed(event: ExecutionEvent) {
        recording.ended(event, succeeded = false)
        delegate?.mojoFailed(event)
    }

    override fun projectDiscoveryStarted(event: ExecutionEvent) { delegate?.projectDiscoveryStarted(event) }
    override fun sessionStarted(event: ExecutionEvent) { delegate?.sessionStarted(event) }
    override fun sessionEnded(event: ExecutionEvent) { delegate?.sessionEnded(event) }
    override fun projectSkipped(event: ExecutionEvent) { delegate?.projectSkipped(event) }
    override fun projectStarted(event: ExecutionEvent) { delegate?.projectStarted(event) }
    override fun projectSucceeded(event: ExecutionEvent) { delegate?.projectSucceeded(event) }
    override fun projectFailed(event: ExecutionEvent) { delegate?.projectFailed(event) }
    override fun mojoSkipped(event: ExecutionEvent) { delegate?.mojoSkipped(event) }
    override fun forkStarted(event: ExecutionEvent) { delegate?.forkStarted(event) }
    override fun forkSucceeded(event: ExecutionEvent) { delegate?.forkSucceeded(event) }
    override fun forkFailed(event: ExecutionEvent) { delegate?.forkFailed(event) }
    override fun forkedProjectStarted(event: ExecutionEvent) { delegate?.forkedProjectStarted(event) }
    override fun forkedProjectSucceeded(event: ExecutionEvent) { delegate?.forkedProjectSucceeded(event) }
    override fun forkedProjectFailed(event: ExecutionEvent) { delegate?.forkedProjectFailed(event) }
}
