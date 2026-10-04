package com.openjump.app.encoder

import com.openjump.app.protocol.VideoSource
import com.openjump.app.video.VideoFrameIndex
import kotlin.math.abs
import kotlin.math.roundToLong

object PhysicalTimeline {
    data class Resolved(
        val timesUs: List<Long?>,
        val effectiveFps: Double?,
        val medianDeltaUs: Long?,
        val gapsBeforeUs: List<Long?>,
    )

    fun resolve(sourcePtsUs: List<Long>, decision: VideoTimingDecision): Resolved {
        require(sourcePtsUs.zipWithNext().all { (a, b) -> b > a }) {
            "Los PTS deben crecer estrictamente."
        }
        if (!decision.isReliable) {
            return Resolved(
                timesUs = List(sourcePtsUs.size) { null },
                effectiveFps = null,
                medianDeltaUs = null,
                gapsBeforeUs = List(sourcePtsUs.size) { null },
            )
        }
        if (sourcePtsUs.isEmpty()) return Resolved(emptyList(), null, null, emptyList())
        val factor = decision.slowMotionFactor
        val first = sourcePtsUs.first()
        val times = sourcePtsUs.map { ((it - first) / factor).roundToLong() }
        require(times.zipWithNext().all { (a, b) -> b > a }) {
            "El factor temporal colapsa timestamps consecutivos."
        }
        val deltas = times.zipWithNext { a, b -> b - a }
        val median = medianLong(deltas)
        val gaps = times.mapIndexed { index, time ->
            if (index == 0) null else time - times[index - 1]
        }
        return Resolved(
            timesUs = times,
            effectiveFps = if (median != null && median > 0L) 1_000_000.0 / median else null,
            medianDeltaUs = median,
            gapsBeforeUs = gaps,
        )
    }

    /**
     * Resolves the initial physical-time choice once a video index is available.
     * PTS remain authoritative; metadata can only provide evidence for a standard
     * slow-motion factor, never replace the observed timeline.
     */
    fun automaticTimingDecision(source: VideoSource?, index: VideoFrameIndex): VideoTimingDecision {
        if (source != VideoSource.IMPORTED) return VideoTimingDecision(VideoTimingMode.REAL_TIME)
        return suggestedSlowMotionFactor(index)?.let {
            VideoTimingDecision(VideoTimingMode.SLOW_MOTION, it)
        } ?: VideoTimingDecision(VideoTimingMode.REAL_TIME)
    }

    fun suggestedSlowMotionFactor(index: VideoFrameIndex): Double? {
        val observed = index.detectedFps.toDouble().takeIf { it > 0.0 } ?: return null
        // Capture rate is authoritative when present. Nominal FPS is only a
        // fallback because the two declarations can describe different things.
        val declared = index.metadataCaptureFrameRate ?: index.nominalFrameRate ?: return null
        val ratio = declared / observed
        if (ratio < 1.5) return null
        return listOf(2.0, 4.0, 8.0)
            .minByOrNull { abs(it - ratio) }
            ?.takeIf { abs(it - ratio) / it <= 0.12 }
    }

    private fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
    }
}
