package com.openjump.app.ui.comparison

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AthleteEncoderRecord
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.encoderLoadIdentity
import com.openjump.app.data.encoderSeriesRepresentatives
import com.openjump.app.data.PersonalRecordCandidate
import com.openjump.app.data.AthleteSeriesStats
import com.openjump.app.data.personalRecordSeries
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.AthleteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ComparisonAthlete(
    val athlete: AthleteEntity,
    val stats: AthleteSeriesStats? = null,
)

sealed interface ComparisonSeries {
    val unit: String
    data class Jump(
        val protocolId: String,
        val side: String?,
        override val unit: String,
        val dropHeightCm: Double? = null,
    ) : ComparisonSeries
    data class Encoder(val exercise: String, val loadKg: Double, override val unit: String = "METER_PER_SECOND") : ComparisonSeries
}

enum class ComparisonCoverage { NONE, PARTIAL, COMPLETE }

internal class ComparisonDataAccess(
    val athletes: suspend () -> List<AthleteEntity>,
    val records: suspend (Long) -> Pair<List<PersonalRecordCandidate>, List<AthleteEncoderRecord>>,
    val stats: suspend (Long, ComparisonSeries) -> AthleteSeriesStats?,
)

private fun repositoryComparisonDataAccess(
    athletesRepository: AthleteRepository,
    jumps: JumpRepository,
    encoder: EncoderRepository,
) = ComparisonDataAccess(
    athletes = { athletesRepository.all().first() },
    records = { id ->
        coroutineScope {
            val jump = async { jumps.comparisonCandidates(id).first() }
            val encoderRecords = async { encoder.athleteEncoderRecords(id).first() }
            jump.await() to encoderRecords.await()
        }
    },
    stats = { id, series ->
        when (series) {
            is ComparisonSeries.Jump -> comparisonJumpStats(jumps.comparisonCandidates(id).first(), series)
            is ComparisonSeries.Encoder -> encoder.athleteEncoderStats(id, series.exercise, series.loadKg)
        }
    },
)

data class ComparisonState(
    val athletes: List<AthleteEntity> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val series: List<ComparisonSeries> = emptyList(),
    val selectedSeries: ComparisonSeries? = null,
    val rows: List<ComparisonAthlete> = emptyList(),
    val rosterLoading: Boolean = true,
    val rosterError: Boolean = false,
    val optionsLoading: Boolean = false,
    val optionsError: Boolean = false,
    val valuesLoading: Boolean = false,
    val valuesError: Boolean = false,
    val resultsPrepared: Boolean = false,
)

class ComparisonViewModel internal constructor(
    private val data: ComparisonDataAccess,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    constructor(
        athletesRepository: AthleteRepository,
        jumps: JumpRepository,
        encoder: EncoderRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) : this(repositoryComparisonDataAccess(athletesRepository, jumps, encoder), savedStateHandle)
    private val _state = MutableStateFlow(
        ComparisonState(
            selectedIds = savedStateHandle.get<LongArray>(SELECTED_IDS_KEY)
                ?.toSet()
                ?.take(MAX_ATHLETES)
                ?.toSet()
                .orEmpty(),
        ),
    )
    val state: StateFlow<ComparisonState> = _state.asStateFlow()
    private var optionsRequestId = 0L
    private var valuesRequestId = 0L
    private var optionsJob: Job? = null
    private var valuesJob: Job? = null

    init {
        viewModelScope.launch(dispatcher) { loadRoster() }
    }

    private suspend fun loadRoster() {
        _state.value = _state.value.copy(rosterLoading = true, rosterError = false)
        try {
            val athletes = data.athletes()
            val validSelectedIds = comparisonSelectedAthletes(athletes, _state.value.selectedIds)
                .take(MAX_ATHLETES)
                .map(AthleteEntity::id)
                .toSet()
            savedStateHandle[SELECTED_IDS_KEY] = validSelectedIds.toLongArray()
            _state.value = _state.value.copy(
                athletes = athletes,
                selectedIds = validSelectedIds,
                rosterLoading = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            _state.value = _state.value.copy(rosterLoading = false, rosterError = true)
        }
    }

    fun retryRoster() {
        viewModelScope.launch(dispatcher) { loadRoster() }
    }

    fun toggleAthlete(id: Long) {
        val current = _state.value
        if (current.athletes.none { it.id == id }) return
        val immutable = comparisonSelectionAfterToggle(current.selectedIds, id)
        if (immutable == current.selectedIds) return
        savedStateHandle[SELECTED_IDS_KEY] = immutable.toLongArray()
        optionsJob?.cancel()
        valuesJob?.cancel()
        optionsRequestId++
        valuesRequestId++
        _state.value = current.copy(
            selectedIds = immutable,
            series = emptyList(),
            selectedSeries = null,
            rows = emptyList(),
            optionsLoading = false,
            optionsError = false,
            valuesLoading = false,
            valuesError = false,
            resultsPrepared = false,
        )
    }

    /** Starts the Results destination's options/value pipeline exactly once per selection. */
    fun prepareResults(force: Boolean = false) {
        val current = _state.value
        if (!comparisonCanStart(current.selectedIds) || current.rosterLoading || current.rosterError ||
            (!force && current.resultsPrepared) || current.optionsLoading
        ) return
        val requestId = ++optionsRequestId
        val expectedIds = current.selectedIds
        optionsJob?.cancel()
        valuesJob?.cancel()
        optionsJob = viewModelScope.launch(dispatcher) {
            _state.value = _state.value.copy(
                optionsLoading = true,
                optionsError = false,
                valuesError = false,
                rows = emptyList(),
                resultsPrepared = true,
            )
            try {
                val ids = selectedRosterIds(expectedIds)
                val results = coroutineScope {
                    ids.map { id -> async { data.records(id) } }.map { it.await() }
                }
                val options = comparisonSeriesOptions(results)
                if (!isCurrentOptions(requestId, expectedIds)) return@launch
                val selected = options.firstOrNull()
                _state.value = _state.value.copy(
                    series = options,
                    selectedSeries = selected,
                    optionsLoading = false,
                    optionsError = false,
                    valuesLoading = selected != null,
                )
                if (selected == null) {
                    _state.value = _state.value.copy(valuesLoading = false)
                } else {
                    loadValues(selected, ++valuesRequestId, expectedIds)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrentOptions(requestId, expectedIds)) {
                    _state.value = _state.value.copy(
                        optionsLoading = false,
                        optionsError = true,
                        valuesLoading = false,
                    )
                }
            }
        }
    }

    fun selectSeries(series: ComparisonSeries) {
        val current = _state.value
        if (series !in current.series || current.selectedIds.size < 2) return
        valuesJob?.cancel()
        val requestId = ++valuesRequestId
        val selectedIds = current.selectedIds
        _state.value = current.copy(
            selectedSeries = series,
            rows = emptyList(),
            valuesLoading = true,
            valuesError = false,
        )
        loadValues(series, requestId, selectedIds)
    }

    private fun loadValues(
        series: ComparisonSeries,
        requestId: Long,
        expectedIds: Set<Long>,
    ) {
        valuesJob = viewModelScope.launch(dispatcher) {
            try {
                val rows = coroutineScope {
                    selectedRosterIds(expectedIds).map { id -> async {
                        val stats = data.stats(id, series)
                        val athlete = _state.value.athletes.firstOrNull { it.id == id }
                        athlete?.let { ComparisonAthlete(it, stats) }
                    } }.mapNotNull { it.await() }
                }
                if (isCurrentValues(requestId, expectedIds, series)) {
                    _state.value = _state.value.copy(
                        rows = rows,
                        valuesLoading = false,
                        valuesError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrentValues(requestId, expectedIds, series)) {
                    _state.value = _state.value.copy(
                        rows = emptyList(),
                        valuesLoading = false,
                        valuesError = true,
                    )
                }
            }
        }
    }

    private fun selectedRosterIds(selectedIds: Set<Long>): List<Long> =
        comparisonSelectedAthletes(_state.value.athletes, selectedIds).map(AthleteEntity::id)

    private fun isCurrentOptions(requestId: Long, expectedIds: Set<Long>): Boolean =
        requestId == optionsRequestId && _state.value.selectedIds == expectedIds

    private fun isCurrentValues(requestId: Long, expectedIds: Set<Long>, series: ComparisonSeries): Boolean =
        requestId == valuesRequestId && _state.value.selectedIds == expectedIds && _state.value.selectedSeries == series

    override fun onCleared() {
        optionsJob?.cancel()
        valuesJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val MAX_ATHLETES = 4
        const val SELECTED_IDS_KEY = "comparison.selected_ids"
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                ComparisonViewModel(
                    app.athleteRepository,
                    app.repository,
                    app.encoderRepository,
                    createSavedStateHandle(),
                )
            }
        }
    }
}

internal fun comparisonSeriesKey(series: ComparisonSeries): String = when (series) {
    is ComparisonSeries.Jump -> "JUMP:${series.protocolId}:${series.side.orEmpty()}:${series.unit}:${series.dropHeightCm?.toBits()?.toString(16).orEmpty()}"
    is ComparisonSeries.Encoder -> "ENCODER:${series.exercise}:${encoderLoadIdentity(series.loadKg)}:${series.unit}"
}

internal fun comparisonChoiceKey(athleteId: Long): String = "choice-$athleteId"
internal fun comparisonSeriesItemKey(series: ComparisonSeries): String = "series-${comparisonSeriesKey(series)}"
internal fun comparisonResultKey(athleteId: Long): String = "result-$athleteId"

internal fun comparisonCanStart(selectedIds: Set<Long>): Boolean =
    selectedIds.size in 2..ComparisonViewModel.MAX_ATHLETES

internal fun comparisonSelectedAthletes(
    athletes: List<AthleteEntity>,
    selectedIds: Set<Long>,
): List<AthleteEntity> = athletes.filter { it.id in selectedIds }

internal fun comparisonSeriesOptions(
    records: List<Pair<List<PersonalRecordCandidate>, List<AthleteEncoderRecord>>>,
): List<ComparisonSeries> {
    val jumpOptions = records.flatMap { (jump, _) ->
        jump.mapNotNull { candidate -> personalRecordSeries(candidate)?.let { condition ->
            ComparisonSeries.Jump(
                condition.protocolId.storageKey, condition.side?.storageKey,
                condition.unit.storageKey, condition.dropHeightCm,
            )
        } }
    }
    val encoderOptions = encoderSeriesRepresentatives(records.flatMap { (_, encoder) -> encoder })
        .map { record -> ComparisonSeries.Encoder(record.exercise, record.loadKg) }
    return (jumpOptions + encoderOptions)
        .distinctBy(::comparisonSeriesKey)
        .sortedWith(
            compareBy<ComparisonSeries> {
                when (it) {
                    is ComparisonSeries.Jump -> "0:${comparisonSeriesKey(it)}"
                    is ComparisonSeries.Encoder -> "1:${it.exercise}"
                }
            }.thenBy { if (it is ComparisonSeries.Encoder) it.loadKg else 0.0 },
        )
}

/** Comparison uses the same candidate eligibility and exact condition as Records/Progress. */
internal fun comparisonJumpStats(
    candidates: List<PersonalRecordCandidate>,
    series: ComparisonSeries.Jump,
): AthleteSeriesStats {
    val matching = candidates.filter { candidate ->
        personalRecordSeries(candidate)?.let { condition ->
            condition.protocolId.storageKey == series.protocolId &&
                condition.side?.storageKey == series.side &&
                condition.unit.storageKey == series.unit &&
                condition.dropHeightCm == series.dropHeightCm
        } == true
    }
    val latest = matching.maxWithOrNull(compareBy<PersonalRecordCandidate> { it.dateTime }
        .thenBy { it.assessmentId }.thenBy { it.attemptOrdinal })
    return AthleteSeriesStats(
        count = matching.size,
        recent = latest?.value,
        best = matching.maxOfOrNull { it.value },
        average = matching.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
        recentDateTime = latest?.dateTime,
        recentId = latest?.assessmentId,
    )
}

internal fun comparisonCoverage(rows: List<ComparisonAthlete>): ComparisonCoverage {
    val withData = rows.count { it.stats?.count ?: 0 > 0 }
    return when {
        withData == 0 -> ComparisonCoverage.NONE
        withData == rows.size -> ComparisonCoverage.COMPLETE
        else -> ComparisonCoverage.PARTIAL
    }
}

internal fun comparisonDataRows(rows: List<ComparisonAthlete>): List<ComparisonAthlete> =
    rows.filter { it.stats?.count ?: 0 > 0 }

/** Ranks higher-is-better series, leaving no-data rows after ranked rows. */
internal fun comparisonRankRows(rows: List<ComparisonAthlete>): List<ComparisonAthlete> =
    rows.sortedWith(Comparator { left, right ->
        val leftHasData = left.stats?.count ?: 0 > 0
        val rightHasData = right.stats?.count ?: 0 > 0
        when {
            leftHasData != rightHasData -> if (leftHasData) -1 else 1
            leftHasData && left.stats?.best != right.stats?.best ->
                right.stats?.best?.compareTo(left.stats?.best ?: Double.NEGATIVE_INFINITY) ?: 0
            left.athlete.displayName != right.athlete.displayName -> left.athlete.displayName.compareTo(right.athlete.displayName)
            else -> left.athlete.id.compareTo(right.athlete.id)
        }
    })

internal fun comparisonSelectionAfterToggle(selectedIds: Set<Long>, id: Long, max: Int = ComparisonViewModel.MAX_ATHLETES): Set<Long> =
    when {
        id in selectedIds -> selectedIds - id
        selectedIds.size < max -> selectedIds + id
        else -> selectedIds
    }
