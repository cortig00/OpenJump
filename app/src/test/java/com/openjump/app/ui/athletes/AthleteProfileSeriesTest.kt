package com.openjump.app.ui.athletes

import com.openjump.app.data.AthleteEncoderRecord
import com.openjump.app.data.AthleteJumpRecord
import com.openjump.app.data.bestEncoderRecords
import com.openjump.app.data.encoderRecordClusters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AthleteProfileSeriesTest {
    @Test
    fun strictToleranceBoundaryCreatesSeparateClustersAndSeries() {
        val records = listOf(
            AthleteEncoderRecord(1L, "SQUAT", 0.0, 0.8, 10L),
            AthleteEncoderRecord(2L, "SQUAT", 0.000001, 0.7, 20L),
        )

        assertEquals(listOf(1, 1), encoderRecordClusters(records).map { it.size })
        assertEquals(
            listOf(0.0, 0.000001),
            availableSeries(emptyList<AthleteJumpRecord>(), records)
                .filterIsInstance<EvolutionSelection.Encoder>()
                .map { it.loadKg },
        )
    }

    @Test
    fun loadClustersAreGreedyAnchoredAndBestSelectionDoesNotChangeMembership() {
        val records = listOf(
            AthleteEncoderRecord(3L, "SQUAT", 60.0000018, 0.8, 30L),
            AthleteEncoderRecord(1L, "SQUAT", 60.0, 0.7, 10L),
            AthleteEncoderRecord(2L, "SQUAT", 60.0000009, 0.9, 20L),
        )

        val clusters = encoderRecordClusters(records)
        val best = bestEncoderRecords(records)

        assertEquals(listOf(2, 1), clusters.map { it.size })
        assertEquals(listOf(60.0, 60.0000018), best.map { it.loadKg })
        assertEquals(listOf(0.9, 0.8), best.map { it.bestMcv })
    }

    @Test
    fun availableSeriesKeepsExerciseAndLoadAsIdentity() {
        val records = listOf(
            AthleteEncoderRecord(1L, "SQUAT", 60.0, 0.8, 100L),
            AthleteEncoderRecord(2L, "SQUAT", 60.0000005, 0.9, 200L),
            AthleteEncoderRecord(3L, "SQUAT", 100.0, 0.7, 300L),
            AthleteEncoderRecord(4L, "BENCH_PRESS", 60.0, 0.6, 400L),
        )

        val encoderSeries = availableSeries(emptyList<AthleteJumpRecord>(), records)
            .filterIsInstance<EvolutionSelection.Encoder>()

        assertEquals(
            listOf("BENCH_PRESS", "SQUAT", "SQUAT"),
            encoderSeries.map { it.exercise },
        )
        assertEquals(listOf(60.0, 60.0, 100.0), encoderSeries.map { it.loadKg })
        assertNotEquals(encoderSeries[1], encoderSeries[2])
    }
}
