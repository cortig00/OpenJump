package com.openjump.app.ui.selector

import com.openjump.app.tracking.TrackingFrameResult

/** Pure gate for the explicit encoder review step. */
internal data class EncoderReviewInput(
    val phase: TrackingPhase,
    val results: List<TrackingFrameResult>,
    val canonicalResults: List<TrackingFrameResult>,
    val exportBlocksViewer: Boolean,
) {
    val hasUsablePoint: Boolean get() = results.any { it.result.sample.point != null }
    val isCanonical: Boolean get() = results == canonicalResults
}

internal data class EncoderReviewPolicy(
    val visible: Boolean,
    val canAnalyze: Boolean,
) {
    companion object {
        fun resolve(input: EncoderReviewInput): EncoderReviewPolicy {
            val terminal = input.phase == TrackingPhase.COMPLETED || input.phase == TrackingPhase.LOST
            val usable = terminal && input.hasUsablePoint && input.isCanonical
            return EncoderReviewPolicy(visible = usable, canAnalyze = usable && !input.exportBlocksViewer)
        }
    }
}

/** Prevents a stale/cancelled worker from committing a terminal snapshot twice. */
internal class EncoderTrackingCompletionCoordinator {
    private val completed = mutableSetOf<Pair<String, Long>>()

    fun complete(
        sessionKey: String,
        expectedGeneration: Long,
        currentGeneration: Long,
        phase: TrackingPhase,
        results: List<TrackingFrameResult>,
        calibrationConfirmed: Boolean,
        commit: (List<TrackingFrameResult>) -> Unit,
        publish: (TrackingPhase) -> Unit,
    ): Boolean = complete(
        sessionKey = sessionKey,
        expectedGeneration = expectedGeneration,
        currentGeneration = currentGeneration,
        phase = phase,
        results = results,
        calibrationConfirmed = calibrationConfirmed,
        commit = commit,
        publish = publish,
        currentSessionKey = sessionKey,
    )

    fun complete(
        sessionKey: String,
        expectedGeneration: Long,
        currentGeneration: Long,
        phase: TrackingPhase,
        results: List<TrackingFrameResult>,
        calibrationConfirmed: Boolean,
        commit: (List<TrackingFrameResult>) -> Unit,
        publish: (TrackingPhase) -> Unit,
        currentSessionKey: String,
    ): Boolean {
        val identity = sessionKey to expectedGeneration
        if (expectedGeneration != currentGeneration || sessionKey != currentSessionKey ||
            phase !in setOf(TrackingPhase.COMPLETED, TrackingPhase.LOST) ||
            !calibrationConfirmed || results.none { it.result.sample.point != null } || identity in completed
        ) return false
        completed += identity
        commit(results)
        publish(phase)
        return true
    }
}
