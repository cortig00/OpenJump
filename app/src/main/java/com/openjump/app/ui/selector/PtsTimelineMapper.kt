package com.openjump.app.ui.selector

import kotlin.math.roundToLong

/**
 * Maps a frame index to a visual timeline using presentation timestamps.
 *
 * The axis is temporal rather than ordinal, so VFR clips and timestamp gaps
 * remain visible. Frame selection always snaps to a timestamp from the index.
 */
class PtsTimelineMapper(frameTimesUs: LongArray) {
    private val timesUs = frameTimesUs.copyOf()
    private val firstPtsUs = timesUs.firstOrNull() ?: 0L
    private val lastPtsUs = timesUs.lastOrNull() ?: firstPtsUs
    private val spanUs = (lastPtsUs - firstPtsUs).coerceAtLeast(0L)

    init {
        require(timesUs.isNotEmpty()) { "La timeline necesita al menos un frame." }
        require(timesUs.asSequence().zipWithNext().all { (a, b) -> a < b }) {
            "Los PTS deben estar ordenados y sin duplicados."
        }
    }

    val frameCount: Int get() = timesUs.size

    fun fractionAtIndex(index: Int): Float = fractionAtPts(timesUs[index.coerceIn(timesUs.indices)])

    fun fractionAtPts(ptsUs: Long): Float {
        if (spanUs == 0L) return 0f
        return ((ptsUs - firstPtsUs).toDouble() / spanUs.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun indexAtFraction(fraction: Float): Int {
        if (timesUs.size == 1 || spanUs == 0L) return 0
        val targetUs = firstPtsUs + (spanUs.toDouble() * fraction.coerceIn(0f, 1f)).roundToLong()
        return indexOfNearest(targetUs)
    }

    private fun indexOfNearest(timeUs: Long): Int {
        var low = 0
        var high = timesUs.lastIndex
        while (low < high) {
            val middle = (low + high) / 2
            if (timesUs[middle] < timeUs) low = middle + 1 else high = middle
        }
        return when {
            low == 0 -> 0
            timeUs - timesUs[low - 1] <= timesUs[low] - timeUs -> low - 1
            else -> low
        }
    }
}
