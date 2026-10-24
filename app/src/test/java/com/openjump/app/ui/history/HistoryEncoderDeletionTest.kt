package com.openjump.app.ui.history

import com.openjump.app.data.RecentEncoderSession
import com.openjump.app.data.RecentMeasurement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F035: empty Encoder rows (projected `validRepetitions == 0` with null `bestMcv`)
 * are the only rows that offer Delete in HISTORY. Non-empty sessions and jumps
 * never do; listed rows persist until a confirmed deletion.
 */
class HistoryEncoderDeletionTest {
    @Test fun `empty encoder row is deletable`() {
        assertTrue(
            isDeletableEmptyEncoderRow(
                MeasurementHistoryItem.Encoder(emptySession(id = 1)),
            ),
        )
    }

    @Test fun `non-empty encoder rows are not deletable`() {
        assertFalse(
            isDeletableEmptyEncoderRow(
                MeasurementHistoryItem.Encoder(emptySession(id = 2).copy(validRepetitions = 3, bestMcv = 0.71)),
            ),
        )
        // Zero valid reps but a surviving best MCV is not the empty-row shape.
        assertFalse(
            isDeletableEmptyEncoderRow(
                MeasurementHistoryItem.Encoder(emptySession(id = 3).copy(bestMcv = 0.42)),
            ),
        )
        // Valid reps with no MCV is not the empty-row shape either.
        assertFalse(
            isDeletableEmptyEncoderRow(
                MeasurementHistoryItem.Encoder(emptySession(id = 4).copy(validRepetitions = 2)),
            ),
        )
    }

    @Test fun `jump rows are never deletable`() {
        assertFalse(
            isDeletableEmptyEncoderRow(
                MeasurementHistoryItem.Jump(
                    RecentMeasurement(
                        id = 9L,
                        dateTime = 1_000L,
                        protocolId = "CMJ",
                        primaryMetricKey = "HEIGHT_CM",
                        primaryMetricValue = 42.0,
                        primaryMetricUnit = "CENTIMETER",
                        side = null,
                    ),
                ),
            ),
        )
    }

    private fun emptySession(id: Long) = RecentEncoderSession(
        id = id,
        dateTime = 1_000L,
        exercise = "BENCH_PRESS",
        loadKg = 60.0,
        validRepetitions = 0,
        bestMcv = null,
    )
}
