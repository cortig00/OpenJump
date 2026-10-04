package com.openjump.app.ui.athletes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.PersonalRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PersonalRecordsViewModel(
    athleteId: Long,
    athletes: AthleteRepository,
    jumps: JumpRepository,
) : ViewModel() {
    val athlete: StateFlow<AthleteEntity?> = athletes.observeById(athleteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val records: StateFlow<List<PersonalRecord>?> = jumps.personalRecords(athleteId)
        .map<List<PersonalRecord>, List<PersonalRecord>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        fun factory(id: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                PersonalRecordsViewModel(id, app.athleteRepository, app.repository)
            }
        }
    }
}
