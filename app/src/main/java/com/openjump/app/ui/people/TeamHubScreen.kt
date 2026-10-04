package com.openjump.app.ui.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.settings.ExperienceMode
import com.openjump.app.ui.components.ExperienceModePicker
import com.openjump.app.ui.components.OpenJumpIconBadge
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.history.MeasurementHistoryItem
import com.openjump.app.ui.history.HistoryEntryRow
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class TeamHubViewModel(
    repository: com.openjump.app.data.JumpRepository,
    encoderRepository: com.openjump.app.data.EncoderRepository,
) : ViewModel() {
    val recentActivity: StateFlow<List<MeasurementHistoryItem>> = combine(
        repository.recentFive(),
        encoderRepository.recentFive(),
    ) { jumps, encoderSessions ->
        com.openjump.app.ui.history.mergeMeasurementHistory(
            jumps, encoderSessions, RECENT_LIMIT,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        private const val RECENT_LIMIT = 3
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                TeamHubViewModel(app.repository, app.encoderRepository)
            }
        }
    }
}

/** Team is always the management hub; athlete profiles are opened only by navigation. */
@Composable
fun TeamHubScreen(
    onAthletes: () -> Unit,
    onGroups: () -> Unit,
    onTesting: () -> Unit,
    onComparison: () -> Unit,
    mode: ExperienceMode = ExperienceMode.COACH,
    onModeSelected: (ExperienceMode) -> Unit = {},
    recentActivity: List<MeasurementHistoryItem> = emptyList(),
    onMeasurementSelected: (Long) -> Unit = {},
    onEncoderSelected: (Long) -> Unit = {},
) {
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.team_title),
                showBrandLogo = true,
                actions = { ExperienceModePicker(mode, onModeSelected) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.team_tools), style = OpenJumpTypes.SectionTitle)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ToolCard(R.drawable.ic_people, R.string.athletes_title, R.string.team_tool_athletes_description, onAthletes, Modifier.weight(1f))
                        ToolCard(R.drawable.ic_group, R.string.groups_title, R.string.team_tool_groups_description, onGroups, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ToolCard(R.drawable.ic_testing, R.string.testing_title, R.string.team_tool_testing_description, onTesting, Modifier.weight(1f))
                        ToolCard(R.drawable.ic_compare, R.string.comparison_title, R.string.team_tool_comparison_description, onComparison, Modifier.weight(1f))
                    }
                }
            }
            item { Text(stringResource(R.string.team_recent_activity), style = OpenJumpTypes.SectionTitle) }
            if (recentActivity.isEmpty()) {
                item { Text(stringResource(R.string.team_recent_empty), style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(recentActivity, key = {
                    val type = when (it) {
                        is MeasurementHistoryItem.Jump -> "jump"
                        is MeasurementHistoryItem.Encoder -> "encoder"
                    }
                    "$type-${it.id}-${it.dateTime}"
                }) { item ->
                    HistoryEntryRow(item, onClick = {
                        when (item) {
                            is MeasurementHistoryItem.Jump -> onMeasurementSelected(item.id)
                            is MeasurementHistoryItem.Encoder -> onEncoderSelected(item.id)
                        }
                    }, showDivider = item != recentActivity.last(), recent = true)
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    icon: Int,
    title: Int,
    description: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 136.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = ShapeTokens.medium,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenJumpIconBadge(
                    icon = icon,
                    size = 36.dp,
                    iconSize = 20.dp,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(description),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
