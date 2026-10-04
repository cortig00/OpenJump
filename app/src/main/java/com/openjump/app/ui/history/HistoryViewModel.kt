package com.openjump.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AthleteHistoryCursor
import com.openjump.app.data.AthleteHistoryPage
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.GroupEntity
import com.openjump.app.data.GroupRepository
import com.openjump.app.data.HistoryQuery
import com.openjump.app.data.JumpRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which persisted measurement roots participate in a history query. */
enum class HistoryFamily { ALL, JUMP, ENCODER }

data class HistoryFilters(
    val search: String = "",
    val family: HistoryFamily = HistoryFamily.ALL,
    val athleteId: Long? = null,
    val unassignedOnly: Boolean = false,
    val groupId: Long? = null,
    val protocolOrExercise: String = "",
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val metricMin: Double? = null,
    val metricMax: Double? = null,
) {
    fun toQuery() = HistoryQuery(
        search = search.trim().ifBlank { null },
        athleteId = athleteId,
        unassignedOnly = unassignedOnly,
        groupId = groupId,
        protocolOrExercise = protocolOrExercise.trim().ifBlank { null },
        dateFrom = dateFrom,
        dateTo = dateTo,
        metricMin = metricMin,
        metricMax = metricMax,
    )

    val activeCount: Int get() = listOf(
        search.isNotBlank(), family != HistoryFamily.ALL, athleteId != null || unassignedOnly, groupId != null,
        protocolOrExercise.isNotBlank(), dateFrom != null, dateTo != null,
        metricMin != null, metricMax != null,
    ).count { it }
}

data class HistoryScreenState(
    val filters: HistoryFilters = HistoryFilters(),
    val items: List<MeasurementHistoryItem> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val canLoadMore: Boolean = true,
)

class HistoryViewModel(
    private val repository: JumpRepository,
    private val encoderRepository: EncoderRepository,
    private val athleteRepository: AthleteRepository,
    private val groupRepository: GroupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryScreenState())
    val state: StateFlow<HistoryScreenState> = _state.asStateFlow()
    private val _athletes = MutableStateFlow<List<AthleteEntity>>(emptyList())
    val athletes: StateFlow<List<AthleteEntity>> = _athletes.asStateFlow()
    private val _groups = MutableStateFlow<List<GroupEntity>>(emptyList())
    val groups: StateFlow<List<GroupEntity>> = _groups.asStateFlow()
    private var cursor: AthleteHistoryCursor? = null
    private var requestId = 0L

    init {
        // Lists are small roster metadata; measurement roots remain strictly query-backed.
        viewModelScope.launch { athleteRepository.all().collect { _athletes.value = it } }
        viewModelScope.launch { groupRepository.all().collect { _groups.value = it } }
    }

    fun setFilters(filters: HistoryFilters) {
        requestId++
        cursor = null
        _state.value = HistoryScreenState(filters = filters, loading = true)
        loadPage(requestId)
    }

    fun clearFilters() = setFilters(HistoryFilters())

    fun loadMore() {
        if (_state.value.loading || !_state.value.canLoadMore) return
        _state.value = _state.value.copy(loading = true, error = false)
        loadPage(requestId)
    }

    /** Reload persisted rows on return from a measurement, save, or roster change. */
    fun refresh() = setFilters(_state.value.filters)

    private fun loadPage(id: Long) = viewModelScope.launch {
        val filters = _state.value.filters
        val append = _state.value.items.isNotEmpty()
        try {
            val (jump, encoder) = coroutineScope {
                val jump = if (filters.family != HistoryFamily.ENCODER) {
                    async { repository.historyPage(filters.toQuery(), cursor, BATCH_SIZE) }
                } else null
                val encoder = if (filters.family != HistoryFamily.JUMP) {
                    async { encoderRepository.historyPage(filters.toQuery(), cursor, BATCH_SIZE) }
                } else null
                Pair(jump?.await().orEmpty(), encoder?.await().orEmpty())
            }
            if (id != requestId) return@launch
            val merged = mergeAthleteHistory(jump, encoder, PAGE_SIZE)
            val nextCursor = merged.lastOrNull()?.let { AthleteHistoryCursor(it.dateTime, it.id, it.kind) }
            if (nextCursor != null) cursor = nextCursor
            _state.value = _state.value.copy(
                items = if (append) _state.value.items + toItems(merged) else toItems(merged),
                loading = false,
                error = false,
                canLoadMore = historyCanLoadMore(merged.size, PAGE_SIZE),
            )
        } catch (_: Throwable) {
            if (id == requestId) _state.value = _state.value.copy(loading = false, error = true)
        }
    }

    private fun toItems(page: List<AthleteHistoryPage>): List<MeasurementHistoryItem> = page.map { item ->
        when (item.kind) {
            "JUMP" -> MeasurementHistoryItem.Jump(item.toRecentMeasurement())
            else -> MeasurementHistoryItem.Encoder(item.toRecentEncoderSession())
        }
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val BATCH_SIZE = 40
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                HistoryViewModel(app.repository, app.encoderRepository, app.athleteRepository, app.groupRepository)
            }
        }
    }
}

private fun AthleteHistoryPage.toRecentMeasurement() = com.openjump.app.data.RecentMeasurement(
    id = id, dateTime = dateTime, athleteId = athleteId, athleteName = athleteName,
    protocolId = protocolId.orEmpty(), primaryMetricKey = primaryMetricKey.orEmpty(),
    primaryMetricValue = primaryMetricValue ?: 0.0, primaryMetricUnit = primaryMetricUnit.orEmpty(), side = side,
    hasNotes = hasNotes,
)

private fun AthleteHistoryPage.toRecentEncoderSession() = com.openjump.app.data.RecentEncoderSession(
    id = id, dateTime = dateTime, athleteId = athleteId, athleteName = athleteName,
    exercise = exercise.orEmpty(), loadKg = loadKg ?: 0.0, validRepetitions = validRepetitions ?: 0,
    bestMcv = bestMcv, hasNotes = hasNotes,
)

/** Merge only already bounded DAO pages; the order is shared with athlete profiles. */
internal fun mergeAthleteHistory(
    jumps: List<AthleteHistoryPage>,
    encoder: List<AthleteHistoryPage>,
    limit: Int,
): List<AthleteHistoryPage> = (jumps + encoder).sortedWith(
    compareByDescending<AthleteHistoryPage> { it.dateTime }
        .thenByDescending { it.id }
        .thenBy { it.kind },
).take(limit)

internal fun historyCanLoadMore(emittedSize: Int, pageSize: Int): Boolean = emittedSize == pageSize
