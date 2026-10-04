package com.openjump.app.ui.comparison

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.AthleteAvatar
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing

@Composable
fun ComparisonScreen(
    onBack: () -> Unit,
    viewModel: ComparisonViewModel = viewModel(factory = ComparisonViewModel.Factory),
    results: Boolean = false,
    onCompare: () -> Unit = {},
    onEdit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(results, state.rosterLoading, state.rosterError, state.selectedIds) {
        if (results && !state.rosterLoading && !state.rosterError) viewModel.prepareResults()
    }
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(if (results) R.string.comparison_results_title else R.string.comparison_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
                iconBadge = R.drawable.ic_compare,
                actions = {
                    if (results) {
                        TextButton(onClick = onEdit) { Text(stringResource(R.string.comparison_edit_selection)) }
                    }
                },
            )
        },
        bottomBar = {
            if (!results) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onCompare,
                        enabled = comparisonCanStart(state.selectedIds),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.comparison_cta, state.selectedIds.size))
                    }
                }
            }
        },
    ) { padding ->
        if (results) ResultsContent(state, viewModel, padding)
        else SelectionContent(state, viewModel, padding)
    }
}

@Composable
private fun SelectionContent(
    state: ComparisonState,
    viewModel: ComparisonViewModel,
    padding: PaddingValues,
) {
    when {
        state.rosterLoading -> LoadingState(padding)
        state.rosterError -> {
            Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.screenHorizontal), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.comparison_roster_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retryRoster) { Text(stringResource(R.string.common_retry)) }
            }
        }
        state.athletes.isEmpty() -> EmptyState(
            title = stringResource(R.string.comparison_roster_empty_title),
            body = stringResource(R.string.comparison_roster_empty_body),
            modifier = Modifier.fillMaxSize().padding(padding),
        )
        else -> LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item {
                Text(
                    stringResource(R.string.comparison_choose_athletes),
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                    style = OpenJumpTypes.SectionTitle,
                )
            }
            item {
                Text(
                    stringResource(R.string.comparison_selected_count, state.selectedIds.size, ComparisonViewModel.MAX_ATHLETES),
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.athletes, key = { comparisonChoiceKey(it.id) }) { athlete ->
                AthleteChoice(
                    athlete = athlete,
                    selected = athlete.id in state.selectedIds,
                    canSelect = state.selectedIds.size < ComparisonViewModel.MAX_ATHLETES,
                    onToggle = viewModel::toggleAthlete,
                )
            }
        }
    }
}

@Composable
private fun LoadingState(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.comparison_roster_loading))
    }
}

@Composable
private fun ResultsContent(
    state: ComparisonState,
    viewModel: ComparisonViewModel,
    padding: PaddingValues,
) {
    when {
        state.rosterLoading -> {
            LoadingState(padding)
            return
        }
        state.rosterError -> {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(Spacing.screenHorizontal),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.comparison_roster_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retryRoster) { Text(stringResource(R.string.common_retry)) }
            }
            return
        }
        !comparisonCanStart(state.selectedIds) -> {
            EmptyState(
                title = stringResource(R.string.comparison_no_series_title),
                body = stringResource(R.string.comparison_select_two),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return
        }
    }
    val rankedRows = comparisonRankRows(state.rows)
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    val encoderLoadLabels = remember(state.series, unitProfile, locale) {
        MeasurementFormatting.formatMassLabels(
            state.series.filterIsInstance<ComparisonSeries.Encoder>().map { it.loadKg },
            unitProfile,
            locale,
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Text(
                stringResource(R.string.comparison_selected_summary, state.selectedIds.size),
                style = OpenJumpTypes.SectionTitle,
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                comparisonSelectedAthletes(state.athletes, state.selectedIds).forEach { athlete ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            AthleteAvatar(athlete.displayName, athlete.avatarKey, size = 32.dp)
                            Text(athlete.displayName, modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                }
            }
        }
        item {
            when {
                state.optionsLoading -> Text(stringResource(R.string.comparison_series_loading))
                state.optionsError -> Column {
                    Text(stringResource(R.string.comparison_series_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { viewModel.prepareResults(force = true) }) { Text(stringResource(R.string.common_retry)) }
                }
                state.series.isEmpty() && state.resultsPrepared -> EmptyState(
                    title = stringResource(R.string.comparison_no_series_title),
                    body = stringResource(R.string.comparison_no_series),
                )
                else -> SeriesSelector(state, viewModel, encoderLoadLabels)
            }
        }
        if (state.valuesLoading) item { Text(stringResource(R.string.comparison_values_loading)) }
        if (state.valuesError) item {
            Column {
                Text(stringResource(R.string.comparison_values_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    state.selectedSeries?.let { viewModel.selectSeries(it) }
                }) { Text(stringResource(R.string.common_retry)) }
            }
        }
        if (!state.valuesLoading && !state.valuesError && state.selectedSeries != null && state.resultsPrepared) {
            val coverage = comparisonCoverage(state.rows)
            val dataRows = comparisonDataRows(state.rows)
            if (coverage == ComparisonCoverage.NONE) {
                item {
                    EmptyState(
                        title = stringResource(R.string.comparison_no_points_title),
                        body = stringResource(R.string.comparison_no_points),
                    )
                }
            } else {
                item {
                    Text(
                        if (coverage == ComparisonCoverage.PARTIAL) {
                            stringResource(R.string.comparison_partial, dataRows.size, state.rows.size)
                        } else {
                            stringResource(R.string.comparison_complete, state.rows.size)
                        },
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rankedRows, key = { comparisonResultKey(it.athlete.id) }) { row ->
                ComparisonRow(
                    row = row,
                    series = state.selectedSeries,
                    rank = rankedRows.indexOf(row).takeIf { row.stats?.count ?: 0 > 0 }?.plus(1),
                )
            }
        }
    }
}

@Composable
private fun SeriesSelector(
    state: ComparisonState,
    viewModel: ComparisonViewModel,
    encoderLoadLabels: Map<Double, String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.comparison_choose_series), style = OpenJumpTypes.SectionTitle)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(state.series, key = { comparisonSeriesItemKey(it) }) { series ->
                FilterChip(
                    selected = state.selectedSeries == series,
                    onClick = { viewModel.selectSeries(series) },
                    label = { Text(seriesLabel(series, encoderLoadLabels)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun AthleteChoice(athlete: AthleteEntity, selected: Boolean, canSelect: Boolean, onToggle: (Long) -> Unit) {
    val enabled = selected || canSelect
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox) { onToggle(athlete.id) },
        headlineContent = { Text(athlete.displayName) },
        supportingContent = { if (athlete.archivedAt != null) Text(stringResource(R.string.comparison_archived)) },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                AthleteAvatar(athlete.displayName, athlete.avatarKey, size = 40.dp)
            }
        },
    )
}

@Composable
private fun ComparisonRow(row: ComparisonAthlete, series: ComparisonSeries, rank: Int?) {
    val resources = LocalContext.current.resources
    val unitSystem = LocalUnitSystem.current
    val quantity = comparisonQuantity(series)
    val stats = row.stats?.let { canonical ->
        canonical.copy(
            recent = canonical.recent?.let { quantity?.let { q -> MeasurementFormatting.toDisplay(it, q, unitSystem) } ?: it },
            best = canonical.best?.let { quantity?.let { q -> MeasurementFormatting.toDisplay(it, q, unitSystem) } ?: it },
            average = canonical.average?.let { quantity?.let { q -> MeasurementFormatting.toDisplay(it, q, unitSystem) } ?: it },
        )
    }
    val hasData = stats?.count ?: 0 > 0
    val unit = unitLabel(series)
    val container = if (rank == 1) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rank != null) {
                    Text(
                        stringResource(R.string.comparison_position, rank),
                        modifier = Modifier.padding(end = Spacing.sm),
                        style = OpenJumpTypes.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AthleteAvatar(row.athlete.displayName, row.athlete.avatarKey, size = 40.dp)
                Text(
                    row.athlete.displayName,
                    modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                    style = OpenJumpTypes.SectionTitle,
                )
            }
            if (!hasData || stats == null) {
                Text(stringResource(R.string.comparison_no_data), style = OpenJumpTypes.Body)
            } else {
                val fontScale = LocalDensity.current.fontScale
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = maxWidth < 300.dp || fontScale >= 1.5f
                    if (stacked) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            ComparisonStat(
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.comparison_recent),
                                value = stats.recent,
                                unit = unit,
                                prominent = false,
                            )
                            ComparisonStat(
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.comparison_best),
                                value = stats.best,
                                unit = unit,
                                prominent = true,
                            )
                            ComparisonStat(
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.comparison_average),
                                value = stats.average,
                                unit = unit,
                                prominent = false,
                            )
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            ComparisonStat(
                                modifier = Modifier.weight(1f),
                                label = stringResource(R.string.comparison_recent),
                                value = stats.recent,
                                unit = unit,
                                prominent = false,
                            )
                            ComparisonStat(
                                modifier = Modifier.weight(1f),
                                label = stringResource(R.string.comparison_best),
                                value = stats.best,
                                unit = unit,
                                prominent = true,
                            )
                            ComparisonStat(
                                modifier = Modifier.weight(1f),
                                label = stringResource(R.string.comparison_average),
                                value = stats.average,
                                unit = unit,
                                prominent = false,
                            )
                        }
                    }
                }
                Text(
                    resources.getQuantityString(
                        R.plurals.comparison_measurements,
                        stats.count,
                        stats.count,
                    ),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ComparisonStat(
    modifier: Modifier,
    label: String,
    value: Double?,
    unit: String,
    prominent: Boolean,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label, style = OpenJumpTypes.MetricLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value == null) {
            Text(stringResource(R.string.comparison_stat_unavailable), style = OpenJumpTypes.Body)
        } else {
            Text(
                stringResource(R.string.comparison_stat_value, value, unit),
                style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (prominent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun seriesLabel(series: ComparisonSeries, encoderLoadLabels: Map<Double, String>): String {
    val base = when (series) {
        is ComparisonSeries.Jump -> {
            val protocol = ProtocolId.fromStorageKey(series.protocolId)?.let { stringResource(it.titleResource()) } ?: series.protocolId
            when {
                series.side == "LEFT" -> stringResource(R.string.comparison_jump_series, protocol, stringResource(R.string.history_side_left))
                series.side == "RIGHT" -> stringResource(R.string.comparison_jump_series, protocol, stringResource(R.string.history_side_right))
                series.dropHeightCm != null -> stringResource(
                    R.string.comparison_jump_series, protocol,
                    stringResource(R.string.pr_drop_height, MeasurementFormatting.format(
                        series.dropHeightCm, MeasurementQuantity.SHORT_LENGTH_CM,
                        LocalUnitSystem.current, currentAppLocale(), decimals = 2,
                    )),
                )
                else -> protocol
            }
        }
        is ComparisonSeries.Encoder -> stringResource(
            R.string.comparison_encoder_series,
            runCatching { EncoderExercise.valueOf(series.exercise) }.getOrNull()?.let { stringResource(it.titleResource()) } ?: series.exercise,
            encoderLoadLabels[series.loadKg] ?: MeasurementFormatting.format(
                series.loadKg,
                MeasurementQuantity.MASS_KG,
                LocalUnitSystem.current,
                currentAppLocale(),
            ),
        )
    }
    return stringResource(R.string.comparison_series_with_unit, base, unitLabel(series))
}

@Composable
private fun unitLabel(series: ComparisonSeries): String = comparisonQuantity(series)?.let {
    MeasurementFormatting.unit(it, LocalUnitSystem.current).symbol
} ?: series.unit

private fun comparisonQuantity(series: ComparisonSeries): MeasurementQuantity? = when (series) {
    is ComparisonSeries.Encoder -> MeasurementQuantity.SPEED_MPS
    is ComparisonSeries.Jump -> {
        val key = ProtocolCatalog.find(series.protocolId)?.primaryMetric
        val unit = MetricUnit.fromStorageKey(series.unit)
        if (key != null && unit != null) {
            MeasurementFormatting.quantity(MetricValue(key, 0.0, unit))
        } else {
            null
        }
    }
}
