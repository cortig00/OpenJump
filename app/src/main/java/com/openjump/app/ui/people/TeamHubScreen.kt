package com.openjump.app.ui.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.settings.ExperienceMode
import com.openjump.app.ui.components.EmptyState
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class TeamHubViewModel(
    repository: com.openjump.app.data.JumpRepository,
    encoderRepository: com.openjump.app.data.EncoderRepository,
    athleteRepository: com.openjump.app.data.AthleteRepository? = null,
    groupRepository: com.openjump.app.data.GroupRepository? = null,
) : ViewModel() {
    val recentActivity: StateFlow<List<MeasurementHistoryItem>> = combine(
        repository.recentFive(),
        encoderRepository.recentFive(),
    ) { jumps, encoderSessions ->
        com.openjump.app.ui.history.mergeMeasurementHistory(
            jumps, encoderSessions, RECENT_LIMIT,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeAthletesCount: StateFlow<Int?> = (athleteRepository?.active()?.map<_, Int?> { it.size }
        ?: kotlinx.coroutines.flow.flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeGroupsCount: StateFlow<Int?> = (groupRepository?.active()?.map<_, Int?> { it.size }
        ?: kotlinx.coroutines.flow.flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        private const val RECENT_LIMIT = 3
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                TeamHubViewModel(
                    repository = app.repository,
                    encoderRepository = app.encoderRepository,
                    athleteRepository = app.athleteRepository,
                    groupRepository = app.groupRepository,
                )
            }
        }
    }
}

/** Team is always the management hub; athlete profiles are opened only by navigation. */
@OptIn(ExperimentalMaterial3Api::class)
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
    activeAthletesCount: Int? = null,
    activeGroupsCount: Int? = null,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.team_title),
                showBrandLogo = true,
                actions = {
                    ExperienceModePicker(
                        mode = mode,
                        onModeSelected = onModeSelected,
                        modifier = Modifier.padding(end = Spacing.sm),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (activeAthletesCount != null && activeGroupsCount != null) {
                item {
                    TeamSummaryCard(
                        activeAthletesCount = activeAthletesCount,
                        activeGroupsCount = activeGroupsCount,
                        onAthletes = onAthletes,
                        onGroups = onGroups,
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.team_tools), style = OpenJumpTypes.SectionTitle)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        ToolCard(
                            icon = R.drawable.ic_people,
                            title = R.string.athletes_title,
                            description = R.string.team_tool_athletes_description,
                            onClick = onAthletes,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        ToolCard(
                            icon = R.drawable.ic_group,
                            title = R.string.groups_title,
                            description = R.string.team_tool_groups_description,
                            onClick = onGroups,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        ToolCard(
                            icon = R.drawable.ic_testing,
                            title = R.string.testing_title,
                            description = R.string.team_tool_testing_description,
                            onClick = onTesting,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        ToolCard(
                            icon = R.drawable.ic_compare,
                            title = R.string.comparison_title,
                            description = R.string.team_tool_comparison_description,
                            onClick = onComparison,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.team_recent_activity), style = OpenJumpTypes.SectionTitle)
                    if (recentActivity.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = ShapeTokens.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            ),
                        ) {
                            EmptyState(
                                title = stringResource(R.string.team_recent_activity),
                                body = stringResource(R.string.team_recent_empty),
                                modifier = Modifier.padding(horizontal = Spacing.md),
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            recentActivity.forEachIndexed { index, item ->
                                HistoryEntryRow(
                                    item = item,
                                    onClick = {
                                        when (item) {
                                            is MeasurementHistoryItem.Jump -> onMeasurementSelected(item.id)
                                            is MeasurementHistoryItem.Encoder -> onEncoderSelected(item.id)
                                            is MeasurementHistoryItem.Angle -> Unit
                                        }
                                    },
                                    showDivider = index < recentActivity.lastIndex,
                                    recent = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamSummaryCard(
    activeAthletesCount: Int,
    activeGroupsCount: Int,
    onAthletes: () -> Unit,
    onGroups: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(R.string.athlete_profile_summary),
                style = OpenJumpTypes.SectionTitle,
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeamSummaryMetric(
                    label = stringResource(R.string.athletes_title),
                    value = activeAthletesCount,
                    onClick = onAthletes,
                    modifier = Modifier.weight(1f),
                )
                VerticalDivider(
                    modifier = Modifier.padding(horizontal = Spacing.md),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                TeamSummaryMetric(
                    label = stringResource(R.string.groups_title),
                    value = activeGroupsCount,
                    onClick = onGroups,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TeamSummaryMetric(
    label: String,
    value: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = value.toString(),
            style = OpenJumpTypes.MetricValue,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = OpenJumpTypes.MetricLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(description),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
