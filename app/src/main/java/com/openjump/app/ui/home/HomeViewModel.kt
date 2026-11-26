package com.openjump.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.HistoryQuery
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.angles.observeAngleHistoryAtomically
import com.openjump.app.ui.history.AngleAnalysisHistoryEntry
import com.openjump.app.ui.history.AngleHistorySource
import com.openjump.app.ui.history.MeasurementHistoryItem
import com.openjump.app.ui.history.mergeMeasurementHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: JumpRepository,
    encoderRepository: EncoderRepository,
    private val angleRecentReader: (suspend () -> List<AngleAnalysisHistoryEntry>)? = null,
) : ViewModel() {

    private val recentAngles = MutableStateFlow<List<AngleAnalysisHistoryEntry>>(emptyList())

    init {
        refresh()
    }

    /** Global recent activity feed shared by the Home surface (Jumps + Encoder + Angles). */
    val recentActivity: StateFlow<List<MeasurementHistoryItem>> = combine(
        repository.recentFive(),
        encoderRepository.recentFive(),
        recentAngles,
    ) { jumps, encoderSessions, angles ->
        mergeMeasurementHistory(
            jumps = jumps,
            encoderSessions = encoderSessions,
            limit = RECENT_ACTIVITY_LIMIT,
            angleAnalyses = angles,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun refresh() {
        viewModelScope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    angleRecentReader?.invoke() ?: loadRecentAngles(repository)
                }
            }.getOrNull() ?: return@launch
            recentAngles.value = loaded
        }
    }

    companion object {
        private const val RECENT_ACTIVITY_LIMIT = 3
        private const val RECENT_ANGLES_FETCH_LIMIT = 5

        private suspend fun loadRecentAngles(repository: JumpRepository): List<AngleAnalysisHistoryEntry> {
            val query = HistoryQuery()
            val legacy = repository.angleHistoryPage(query, null, RECENT_ANGLES_FETCH_LIMIT).map {
                AngleAnalysisHistoryEntry(
                    id = it.id,
                    dateTime = it.dateTime,
                    athleteId = it.athleteId,
                    athleteName = it.athleteName,
                    markCount = it.markCount,
                    videoUri = it.videoUri,
                )
            }
            val multiFrame = observeAngleHistoryAtomically {
                repository.angleV2HistoryPage(query, null, RECENT_ANGLES_FETCH_LIMIT)
            }.let { observation ->
                observation.value.map {
                    AngleAnalysisHistoryEntry(
                        id = it.id,
                        dateTime = it.dateTime,
                        athleteId = it.athleteId,
                        athleteName = it.athleteName,
                        markCount = it.markCount,
                        videoUri = null,
                        source = AngleHistorySource.MULTI_FRAME,
                        restoreToken = observation.restoreToken,
                    )
                }
            }
            return legacy + multiFrame
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                HomeViewModel(app.repository, app.encoderRepository)
            }
        }
    }
}
