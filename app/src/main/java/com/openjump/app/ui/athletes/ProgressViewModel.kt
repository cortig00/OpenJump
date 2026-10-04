package com.openjump.app.ui.athletes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.AthleteEncoderRecord
import com.openjump.app.data.AthleteEvolutionPoint
import com.openjump.app.data.ProgressSeries
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ProgressViewModel(
    athleteId: Long,
    athletes: AthleteRepository,
    jumps: JumpRepository,
    private val encoder: EncoderRepository,
) : ViewModel() {
    val athlete = athletes.observeById(athleteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val jumps: StateFlow<List<ProgressSeries>?> = jumps.progressSeries(athleteId)
        .map<List<ProgressSeries>, List<ProgressSeries>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val encoderRecords: StateFlow<List<AthleteEncoderRecord>?> = encoder.athleteEncoderRecords(athleteId)
        .map<List<AthleteEncoderRecord>, List<AthleteEncoderRecord>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val id = athleteId

    suspend fun encoderPoints(exercise: String, loadKg: Double): List<AthleteEvolutionPoint> =
        encoder.athleteEncoderEvolution(id, exercise, loadKg)

    companion object {
        fun factory(id: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                ProgressViewModel(id, app.athleteRepository, app.repository, app.encoderRepository)
            }
        }
    }
}
