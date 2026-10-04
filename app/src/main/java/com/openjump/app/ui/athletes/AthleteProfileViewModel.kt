package com.openjump.app.ui.athletes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AthleteEncoderRecord
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AthleteHistoryCursor
import com.openjump.app.data.AthleteHistoryPage
import com.openjump.app.data.AthletePermanentDeleteResult
import com.openjump.app.data.AthleteProtocolCount
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.JumpRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AthleteProfileViewModel(
    private val athleteId: Long,
    private val athletes: AthleteRepository,
    private val jumps: JumpRepository,
    private val encoder: EncoderRepository,
) : ViewModel() {
    val athlete: StateFlow<AthleteEntity?> = athletes.observeById(athleteId).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), null,
    )
    val jumpSummary = jumps.athleteSummary(athleteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val encoderSummary = encoder.athleteSummary(athleteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val protocolCounts: StateFlow<List<AthleteProtocolCount>> = jumps.athleteProtocolCounts(athleteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val encoderRecords: StateFlow<List<AthleteEncoderRecord>> = encoder.athleteEncoderRecords(athleteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _history = kotlinx.coroutines.flow.MutableStateFlow<List<AthleteHistoryPage>>(emptyList())
    val history: StateFlow<List<AthleteHistoryPage>> = _history
    private val _pagination = kotlinx.coroutines.flow.MutableStateFlow(AthleteHistoryPagination())
    val pagination: StateFlow<AthleteHistoryPagination> = _pagination
    private var cursor: AthleteHistoryCursor? = null
    private var historyRequestId = 0L

    fun refreshHistory() {
        historyRequestId++
        cursor = null
        _history.value = emptyList()
        _pagination.value = AthleteHistoryPagination()
        loadMore()
    }

    fun update(
        displayName: String,
        birthDate: Long?,
        sex: String?,
        notes: String?,
        avatarKey: String?,
        weightKg: Double?,
        heightCm: Double?,
        onComplete: (Boolean) -> Unit = {},
    ) = viewModelScope.launch {
        val current = athlete.value
        if (current == null) {
            onComplete(false)
        } else {
            val updated = try {
                athletes.update(
                    current.copy(
                        displayName = displayName.trim(),
                        birthDate = birthDate,
                        sex = sex?.trim()?.ifBlank { null },
                        notes = notes?.trim()?.ifBlank { null },
                        avatarKey = avatarKey,
                        weightKg = weightKg,
                        heightCm = heightCm,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            onComplete(updated)
        }
    }

    fun archive() = viewModelScope.launch { athletes.archive(athleteId) }
    fun restore() = viewModelScope.launch { athletes.unarchive(athleteId) }

    fun permanentlyDelete(onComplete: (AthletePermanentDeleteResult) -> Unit) = viewModelScope.launch {
        val result = try {
            athletes.permanentlyDelete(athleteId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AthletePermanentDeleteResult.NOT_FOUND
        }
        onComplete(result)
    }

    fun loadMore() {
        if (_pagination.value.loading || !_pagination.value.canLoadMore) return
        _pagination.value = _pagination.value.copy(loading = true)
        val requestId = historyRequestId
        val nextCursor = cursor
        viewModelScope.launch {
            try {
                val jumpPage = jumps.athleteHistoryPage(athleteId, nextCursor)
                val encoderPage = encoder.athleteHistoryPage(athleteId, nextCursor)
                if (requestId != historyRequestId) return@launch
                val merged = mergeAthleteHistory(jumpPage, encoderPage, PAGE_SIZE)
                if (merged.isEmpty()) {
                    _pagination.value = AthleteHistoryPagination(canLoadMore = false)
                } else {
                    _history.value = _history.value + merged
                    val last = merged.last()
                    cursor = AthleteHistoryCursor(last.dateTime, last.id, last.kind)
                    _pagination.value = AthleteHistoryPagination(canLoadMore = merged.size == PAGE_SIZE)
                }
            } finally {
                if (requestId == historyRequestId) _pagination.value = _pagination.value.copy(loading = false)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        fun factory(id: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                AthleteProfileViewModel(id, app.athleteRepository, app.repository, app.encoderRepository)
            }
        }
    }
}

data class AthleteHistoryPagination(
    val loading: Boolean = false,
    val canLoadMore: Boolean = true,
)

internal fun mergeAthleteHistory(
    jumps: List<AthleteHistoryPage>,
    encoder: List<AthleteHistoryPage>,
    limit: Int,
): List<AthleteHistoryPage> = (jumps + encoder).sortedWith(
    compareByDescending<AthleteHistoryPage> { it.dateTime }
        .thenByDescending { it.id }
        .thenBy { it.kind },
).take(limit)

sealed interface EvolutionSelection {
    data class Jump(val protocolId: String, val side: String?) : EvolutionSelection
    data class Encoder(val exercise: String, val loadKg: Double) : EvolutionSelection
}
