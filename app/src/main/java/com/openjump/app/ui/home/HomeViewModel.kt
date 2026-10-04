package com.openjump.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.JumpRepository
import com.openjump.app.ui.history.MeasurementHistoryItem
import com.openjump.app.ui.history.mergeMeasurementHistory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    repository: JumpRepository,
    encoderRepository: EncoderRepository,
) : ViewModel() {

    /** Global recent activity feed shared by the Home surface. */
    val recentActivity: StateFlow<List<MeasurementHistoryItem>> = combine(
        repository.recentFive(),
        encoderRepository.recentFive(),
    ) { jumps, encoderSessions ->
        mergeMeasurementHistory(
            jumps, encoderSessions, RECENT_ACTIVITY_LIMIT,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    companion object {
        private const val RECENT_ACTIVITY_LIMIT = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                HomeViewModel(app.repository, app.encoderRepository)
            }
        }
    }
}
