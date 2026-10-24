package com.openjump.app.ui.comparison

import androidx.lifecycle.SavedStateHandle
import com.openjump.app.data.AthleteEncoderRecord
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AthleteSeriesStats
import com.openjump.app.data.PersonalRecordCandidate
import org.junit.Assert.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonStateTest {
    @Test
    fun selectionAllowsTwoToFourAndRejectsFifth() {
        var selected = emptySet<Long>()
        selected = comparisonSelectionAfterToggle(selected, 1L)
        assertEquals(setOf(1L), selected)
        assertEquals(false, comparisonCanStart(selected))
        selected = comparisonSelectionAfterToggle(selected, 2L)
        assertEquals(setOf(1L, 2L), selected)
        assertEquals(true, comparisonCanStart(selected))
        selected = comparisonSelectionAfterToggle(selected, 3L)
        selected = comparisonSelectionAfterToggle(selected, 4L)
        assertEquals(setOf(1L, 2L, 3L, 4L), selected)
        assertEquals(true, comparisonCanStart(selected))
        assertEquals(selected, comparisonSelectionAfterToggle(selected, 5L))
        assertEquals(setOf(1L, 2L, 4L), comparisonSelectionAfterToggle(selected, 3L))
        assertEquals(false, comparisonCanStart(setOf(1L, 2L, 3L, 4L, 5L)))
    }

    @Test
    fun comparisonKeysAreDisjointAcrossLazyListSections() {
        val id = 9L
        val series = ComparisonSeries.Jump("CMJ", null, "CENTIMETER")
        assertNotEquals(comparisonChoiceKey(id), comparisonResultKey(id))
        assertNotEquals(comparisonChoiceKey(id), comparisonSeriesItemKey(series))
        assertNotEquals(comparisonSeriesItemKey(series), comparisonResultKey(id))
    }

    @Test
    fun coverageRetainsRowsButRankingUsesOnlyRowsWithData() {
        val athletes = listOf(athlete(1), athlete(2), athlete(3), athlete(4))
        val stats = AthleteSeriesStats(2, 20.0, 22.0, 21.0)
        val rows = athletes.mapIndexed { index, athlete ->
            ComparisonAthlete(athlete, stats.takeIf { index < 3 })
        }
        assertEquals(ComparisonCoverage.PARTIAL, comparisonCoverage(rows))
        assertEquals(3, comparisonDataRows(rows).size)
        assertEquals(4, rows.size)
        assertEquals(ComparisonCoverage.NONE, comparisonCoverage(rows.map { it.copy(stats = null) }))
        assertEquals(ComparisonCoverage.COMPLETE, comparisonCoverage(rows.map { it.copy(stats = stats) }))
    }

    @Test
    fun rankingUsesBestDescendingThenNameAndPutsNoDataLast() {
        val rows = listOf(
            ComparisonAthlete(athlete(3).copy(displayName = "Zoe"), AthleteSeriesStats(2, 9.0, 20.0, 14.5)),
            ComparisonAthlete(athlete(1).copy(displayName = "Ana"), AthleteSeriesStats(3, 18.0, 20.0, 19.0)),
            ComparisonAthlete(athlete(2).copy(displayName = "No data"), null),
        )

        assertEquals(listOf(1L, 3L, 2L), comparisonRankRows(rows).map { it.athlete.id })
    }

    @Test
    fun allNoDataRowsRemainAvailableForCards() {
        val rows = listOf(
            ComparisonAthlete(athlete(1), null),
            ComparisonAthlete(athlete(2), AthleteSeriesStats(0, null, null, null)),
        )
        assertEquals(ComparisonCoverage.NONE, comparisonCoverage(rows))
        assertEquals(listOf(1L, 2L), comparisonRankRows(rows).map { it.athlete.id })
    }

    @Test
    fun statsCountControlsCoverageEvenWithoutEvolutionPoints() {
        val rows = listOf(
            ComparisonAthlete(athlete(1), AthleteSeriesStats(75, 44.0, 49.0, 46.0)),
            ComparisonAthlete(athlete(2), AthleteSeriesStats(0, null, null, null)),
        )
        assertEquals(ComparisonCoverage.PARTIAL, comparisonCoverage(rows))
        assertEquals(listOf(1L), comparisonDataRows(rows).map { it.athlete.id })
    }

    @Test
    fun seriesOptionsUseUnionAndDeduplicateOnlyApprovedIdentity() {
        val left = candidate(1L, "UNILATERAL", "LEFT", 30.0, 100L)
        val sameLeft = left.copy(assessmentId = 2L, value = 31.0)
        val right = left.copy(assessmentId = 3L, side = "RIGHT")
        val bilateral = left.copy(assessmentId = 5L, protocolId = "ASYMMETRY", side = null)
        val squat = AthleteEncoderRecord(4L, "SQUAT", 60.0, 0.8, 200L)

        val options = comparisonSeriesOptions(
            listOf(
                listOf(left) to emptyList(),
                listOf(sameLeft, right, bilateral) to listOf(squat),
                emptyList<PersonalRecordCandidate>() to emptyList(),
            ),
        )

        assertEquals(3, options.size)
        assertTrue(options.none { it is ComparisonSeries.Jump && it.protocolId == "ASYMMETRY" })
        assertEquals(3, options.distinctBy(::comparisonSeriesKey).size)
        assertEquals(setOf("LEFT", "RIGHT"), options.filterIsInstance<ComparisonSeries.Jump>().map { it.side }.toSet())
        assertEquals(listOf("SQUAT"), options.filterIsInstance<ComparisonSeries.Encoder>().map { it.exercise })
        assertEquals(listOf(60.0), options.filterIsInstance<ComparisonSeries.Encoder>().map { it.loadKg })
    }

    @Test
    fun jumpOptionsAndStatsUseExactlyTheRecordsAndProgressConditions() {
        val candidates = listOf(
            candidate(1, "DROP_JUMP", null, 1.8, 10, box = 30.0),
            candidate(2, "DROP_JUMP", null, 2.5, 20, box = 40.0),
            candidate(3, "UNILATERAL", "LEFT", 20.0, 30),
            candidate(4, "UNILATERAL", "LEFT", 25.0, 40, ordinal = 1, fromBilateral = true),
            candidate(4, "UNILATERAL", "RIGHT", 18.0, 40, ordinal = 2, fromBilateral = true),
            candidate(5, "DROP_JUMP", null, -2.0, 50, box = 30.0),
            candidate(6, "UNILATERAL", null, 30.0, 60),
        )
        val options = comparisonSeriesOptions(listOf(candidates to emptyList()))
            .filterIsInstance<ComparisonSeries.Jump>()
        assertEquals(4, options.size)
        val box30 = options.single { it.dropHeightCm == 30.0 }
        val box40 = options.single { it.dropHeightCm == 40.0 }
        assertNotEquals(comparisonSeriesKey(box30), comparisonSeriesKey(box40))
        assertEquals(1.8, comparisonJumpStats(candidates, box30).best!!, 0.0)
        assertEquals(2.5, comparisonJumpStats(candidates, box40).best!!, 0.0)
        val left = options.single { it.side == "LEFT" }
        val stats = comparisonJumpStats(candidates, left)
        assertEquals(2, stats.count)
        assertEquals(25.0, stats.best!!, 0.0)
        assertEquals(25.0, stats.recent!!, 0.0)
        assertEquals(22.5, stats.average!!, 0.0)
        assertEquals(1, comparisonJumpStats(candidates, options.single { it.side == "RIGHT" }).count)
    }

    @Test
    fun encoderOptionsKeepLoadsSeparateAndCollapseEquivalentRepresentations() {
        val options = comparisonSeriesOptions(
            listOf(
                emptyList<PersonalRecordCandidate>() to listOf(
                    AthleteEncoderRecord(3L, "SQUAT", 100.0, 0.8, 300L),
                    AthleteEncoderRecord(2L, "SQUAT", 60.0000005, 0.7, 200L),
                ),
                emptyList<PersonalRecordCandidate>() to listOf(
                    AthleteEncoderRecord(1L, "SQUAT", 60.0, 0.9, 100L),
                    AthleteEncoderRecord(4L, "BENCH_PRESS", 60.0, 0.6, 400L),
                ),
            ),
        ).filterIsInstance<ComparisonSeries.Encoder>()

        assertEquals(listOf("BENCH_PRESS", "SQUAT", "SQUAT"), options.map { it.exercise })
        assertEquals(listOf(60.0, 60.0, 100.0), options.map { it.loadKg })
        assertNotEquals(comparisonSeriesKey(ComparisonSeries.Encoder("SQUAT", 60.0)), comparisonSeriesKey(ComparisonSeries.Encoder("SQUAT", 60.0000005)))
        assertNotEquals(comparisonSeriesKey(ComparisonSeries.Encoder("SQUAT", 60.0)), comparisonSeriesKey(ComparisonSeries.Encoder("SQUAT", 100.0)))
    }

    @Test
    fun comparisonOptionsUseNonOverlappingGlobalLoadRepresentatives() {
        val options = comparisonSeriesOptions(
            listOf(
                emptyList<PersonalRecordCandidate>() to listOf(
                    AthleteEncoderRecord(1L, "SQUAT", 60.0000018, 0.1, 30L),
                    AthleteEncoderRecord(2L, "SQUAT", 60.0000009, 0.9, 20L),
                    AthleteEncoderRecord(3L, "SQUAT", 60.0, 0.2, 10L),
                ),
            ),
        ).filterIsInstance<ComparisonSeries.Encoder>()

        assertEquals(listOf(60.0, 60.0000018), options.map { it.loadKg })
    }

    @Test
    fun encoderStatsCallbackReceivesLoadAndKeepsPartialCoverage() {
        var requestedLoad: Double? = null
        val viewModel = ComparisonViewModel(
            data = dataAccess(
                athletes = listOf(athlete(1), athlete(2)),
                recordsBlock = { id ->
                    emptyList<PersonalRecordCandidate>() to listOf(
                        AthleteEncoderRecord(id, "SQUAT", if (id == 1L) 60.0 else 100.0, 0.8, id),
                    )
                },
                statsBlock = { id, series ->
                    if (series is ComparisonSeries.Encoder) requestedLoad = series.loadKg
                    if (id == 1L) AthleteSeriesStats(1, 0.8, 0.8, 0.8) else null
                },
            ),
            savedStateHandle = SavedStateHandle(
                mapOf(ComparisonViewModel.SELECTED_IDS_KEY to longArrayOf(1L, 2L)),
            ),
            dispatcher = Dispatchers.Unconfined,
        )

        viewModel.prepareResults()

        assertEquals(60.0, requestedLoad)
        assertEquals(2, viewModel.state.value.rows.size)
        assertEquals(ComparisonCoverage.PARTIAL, comparisonCoverage(viewModel.state.value.rows))
        assertEquals(null, viewModel.state.value.rows.single { it.athlete.id == 2L }.stats)
    }

    @Test
    fun selectedAthletesFollowRosterOrderInsteadOfSetOrder() {
        val roster = listOf(athlete(3), athlete(1), athlete(2))
        assertEquals(listOf(3L, 2L), comparisonSelectedAthletes(roster, linkedSetOf(2L, 3L)).map { it.id })
    }

    @Test
    fun viewModelRestoresOnlyValidSavedAthletes() {
        val savedState = SavedStateHandle(
            mapOf(ComparisonViewModel.SELECTED_IDS_KEY to longArrayOf(9L, 2L, 1L)),
        )
        val viewModel = ComparisonViewModel(
            data = dataAccess(athletes = listOf(athlete(1), athlete(2))),
            savedStateHandle = savedState,
            dispatcher = Dispatchers.Unconfined,
        )

        assertEquals(setOf(1L, 2L), viewModel.state.value.selectedIds)
        assertEquals(listOf(1L, 2L), savedState.get<LongArray>(ComparisonViewModel.SELECTED_IDS_KEY)!!.toList())
    }

    @Test
    fun rosterErrorCanRetryWithoutEscapingViewModelScope() {
        var attempts = 0
        val viewModel = ComparisonViewModel(
            data = dataAccess(
                athletesBlock = {
                    attempts++
                    if (attempts == 1) error("database unavailable") else listOf(athlete(1), athlete(2))
                },
            ),
            dispatcher = Dispatchers.Unconfined,
        )
        assertEquals(true, viewModel.state.value.rosterError)

        viewModel.retryRoster()

        assertEquals(false, viewModel.state.value.rosterError)
        assertEquals(listOf(1L, 2L), viewModel.state.value.athletes.map { it.id })
    }

    @Test
    fun resultsKeepNoDataAthleteWhileRankingRowsRemainPartial() {
        val jumpRecord = candidate(1L, "CMJ", null, 30.0, 100L)
        val viewModel = ComparisonViewModel(
            data = dataAccess(
                athletes = listOf(athlete(1), athlete(2)),
                recordsBlock = { id -> if (id == 1L) listOf(jumpRecord) to emptyList() else emptyList<PersonalRecordCandidate>() to emptyList() },
                statsBlock = { id, _ -> if (id == 1L) AthleteSeriesStats(1, 30.0, 30.0, 30.0) else null },
            ),
            savedStateHandle = SavedStateHandle(
                mapOf(ComparisonViewModel.SELECTED_IDS_KEY to longArrayOf(1L, 2L)),
            ),
            dispatcher = Dispatchers.Unconfined,
        )

        viewModel.prepareResults()

        assertEquals(false, viewModel.state.value.optionsLoading)
        assertEquals(false, viewModel.state.value.valuesLoading)
        assertEquals(2, viewModel.state.value.rows.size)
        assertEquals(ComparisonCoverage.PARTIAL, comparisonCoverage(viewModel.state.value.rows))
        assertEquals(listOf(1L), comparisonDataRows(viewModel.state.value.rows).map { it.athlete.id })
    }

    @Test
    fun cancelledValuesCannotPublishAfterSelectionChanges() {
        val jumpRecord = candidate(1L, "CMJ", null, 30.0, 100L)
        val pending = CompletableDeferred<AthleteSeriesStats?>()
        val viewModel = ComparisonViewModel(
            data = dataAccess(
                athletes = listOf(athlete(1), athlete(2)),
                recordsBlock = { listOf(jumpRecord) to emptyList() },
                statsBlock = { _, _ -> pending.await() },
            ),
            savedStateHandle = SavedStateHandle(
                mapOf(ComparisonViewModel.SELECTED_IDS_KEY to longArrayOf(1L, 2L)),
            ),
            dispatcher = Dispatchers.Unconfined,
        )
        viewModel.prepareResults()
        assertEquals(true, viewModel.state.value.valuesLoading)

        viewModel.toggleAthlete(2L)
        pending.complete(AthleteSeriesStats(1, 30.0, 30.0, 30.0))

        assertEquals(setOf(1L), viewModel.state.value.selectedIds)
        assertEquals(false, viewModel.state.value.resultsPrepared)
        assertEquals(emptyList<ComparisonAthlete>(), viewModel.state.value.rows)
    }

    private fun dataAccess(
        athletes: List<AthleteEntity> = emptyList(),
        athletesBlock: (suspend () -> List<AthleteEntity>)? = null,
        recordsBlock: suspend (Long) -> Pair<List<PersonalRecordCandidate>, List<AthleteEncoderRecord>> = { emptyList<PersonalRecordCandidate>() to emptyList() },
        statsBlock: suspend (Long, ComparisonSeries) -> AthleteSeriesStats? = { _, _ -> null },
    ) = ComparisonDataAccess(
        athletes = athletesBlock ?: { athletes },
        records = recordsBlock,
        stats = statsBlock,
    )

    private fun candidate(id: Long, protocol: String, side: String?, value: Double, date: Long, box: Double? = null,
        ordinal: Int = 0, fromBilateral: Boolean = false) = PersonalRecordCandidate(
        id, ordinal, date, protocol, side, box,
        if (protocol == "DROP_JUMP") "RSI" else "HEIGHT_CM", value,
        if (protocol == "DROP_JUMP") "METER_PER_SECOND" else "CENTIMETER", fromBilateral,
    )

    private fun athlete(id: Long) = AthleteEntity(id = id, displayName = "Athlete $id")
}
