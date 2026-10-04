package com.openjump.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.GroupEntity
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.OpenJumpDateField
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HistoryScreen(
    onMeasurementSelected: (Long) -> Unit,
    onEncoderSessionSelected: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val athletes by viewModel.athletes.collectAsState()
    val groups by viewModel.groups.collectAsState()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    if (showFilters) {
        HistoryFilterSheet(
            initial = state.filters,
            athletes = athletes,
            groups = groups,
            onDismiss = { showFilters = false },
            onApply = { viewModel.setFilters(it); showFilters = false },
        )
    }

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.history_title),
                showBrandLogo = true,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Text(
                    stringResource(R.string.history_subtitle),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.filters.search,
                        onValueChange = { viewModel.setFilters(state.filters.copy(search = it)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.history_search)) },
                    )
                    OutlinedButton(onClick = { showFilters = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(
                            if (state.filters.activeCount > 0) {
                                stringResource(R.string.history_filters_count, state.filters.activeCount)
                            } else {
                                stringResource(R.string.history_filters)
                            },
                        )
                    }
                }
                if (state.filters.activeCount > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = Spacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.history_active_filters, state.filters.activeCount),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::clearFilters, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.history_clear_filters))
                        }
                    }
                }
            }
            if (state.loading && state.items.isEmpty()) {
                item { Text(stringResource(R.string.history_loading), modifier = Modifier.padding(vertical = Spacing.lg)) }
            } else if (state.error && state.items.isEmpty()) {
                item {
                    EmptyState(stringResource(R.string.history_error_title), stringResource(R.string.history_error_body))
                    Button(
                        onClick = viewModel::loadMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.history_retry)) }
                }
            } else if (state.items.isEmpty()) {
                item { EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body)) }
            } else {
                itemsIndexed(state.items, key = { _, item ->
                    when (item) {
                        is MeasurementHistoryItem.Jump -> "jump-${item.id}"
                        is MeasurementHistoryItem.Encoder -> "encoder-${item.id}"
                    }
                }) { index, item ->
                    HistoryEntryRow(
                        item,
                        onClick = {
                            when (item) {
                                is MeasurementHistoryItem.Jump -> onMeasurementSelected(item.id)
                                is MeasurementHistoryItem.Encoder -> onEncoderSessionSelected(item.id)
                            }
                        },
                        showDivider = index < state.items.lastIndex,
                    )
                }
                historyListFooter(state, onLoadMore = viewModel::loadMore)
            }
        }
    }
}

private data class FilterOption(val key: String, val label: String)

/**
 * Footer for the loaded list. An append-page failure ([HistoryScreenState.error] with
 * retained items) surfaces an inline explanation plus an explicit retry instead of
 * falling back to the ambiguous "load more" button, so a partial list is never
 * presented as complete. No paging refactor: retry reuses the pending cursor.
 */
internal fun LazyListScope.historyListFooter(
    state: HistoryScreenState,
    onLoadMore: () -> Unit,
) {
    if (state.error) {
        item(key = "history-append-error") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    stringResource(R.string.history_error_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.history_error_body),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onLoadMore,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.history_retry))
                }
            }
        }
    } else if (state.canLoadMore) {
        item(key = "history-load-more") {
            Button(
                onClick = onLoadMore,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (state.loading) stringResource(R.string.history_loading) else stringResource(R.string.history_load_more))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryFilterSheet(
    initial: HistoryFilters,
    athletes: List<AthleteEntity>,
    groups: List<GroupEntity>,
    onDismiss: () -> Unit,
    onApply: (HistoryFilters) -> Unit,
) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val initialQuantity = historyFilterQuantity(initial.protocolOrExercise)
    var family by rememberSaveable(initial) { mutableStateOf(initial.family) }
    var athleteId by remember(initial) { mutableStateOf(initial.athleteId) }
    var unassignedOnly by remember(initial) { mutableStateOf(initial.unassignedOnly) }
    var groupId by remember(initial) { mutableStateOf(initial.groupId) }
    var series by rememberSaveable(initial) { mutableStateOf(initial.protocolOrExercise) }
    var dateFrom by remember(initial) { mutableStateOf(initial.dateFrom?.let(::epochDate)) }
    var dateTo by remember(initial) { mutableStateOf(initial.dateTo?.let(::epochDate)) }
    fun initialText(value: Double?): String = value?.let { number ->
        initialQuantity?.let { MeasurementFormatting.formatInputValue(number, it, unitSystem, locale) } ?: number.toString()
    }.orEmpty()
    var minInput by rememberSaveable(initial, stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial(initialText(initial.metricMin), unitSystem, initial.metricMin))
    }
    var maxInput by rememberSaveable(initial, stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial(initialText(initial.metricMax), unitSystem, initial.metricMax))
    }
    val min = minInput.text
    val max = maxInput.text
    val today = remember { LocalDate.now() }

    val athleteOptions = listOf(
        FilterOption("all", stringResource(R.string.history_all_athletes)),
        FilterOption("unassigned", stringResource(R.string.athlete_unassigned)),
    ) + athletes.map { FilterOption("athlete-${it.id}", it.displayName) }
    val selectedAthleteKey = when {
        unassignedOnly -> "unassigned"
        athleteId != null -> "athlete-$athleteId"
        else -> "all"
    }
    val groupOptions = listOf(FilterOption("all", stringResource(R.string.history_all_groups))) +
        groups.map { FilterOption("group-${it.id}", it.name) }
    val selectedGroupKey = groupId?.let { "group-$it" } ?: "all"
    val seriesOptions = buildList {
        add(FilterOption("", stringResource(R.string.history_series_all)))
        if (family != HistoryFamily.ENCODER) {
            ProtocolCatalog.available.forEach {
                add(FilterOption(it.id.storageKey, stringResource(it.id.titleResource())))
            }
        }
        if (family != HistoryFamily.JUMP) {
            EncoderExercise.entries.forEach {
                add(FilterOption(it.name, stringResource(it.titleResource())))
            }
        }
    }
    val selectedSeries = series.takeIf { key -> seriesOptions.any { it.key == key } }.orEmpty()
    val filterQuantity = historyFilterQuantity(series)
    val filterUnit = filterQuantity?.let { MeasurementFormatting.unit(it, unitSystem).symbol }
    val minInvalid = filterQuantity?.let { quantity ->
        UnitAwareNumericInputState.hasInvalidCurrentValue(minInput, quantity, locale)
    } == true
    val maxInvalid = filterQuantity?.let { quantity ->
        UnitAwareNumericInputState.hasInvalidCurrentValue(maxInput, quantity, locale)
    } == true
    LaunchedEffect(unitSystem, filterQuantity) {
        filterQuantity?.let { quantity ->
            minInput = UnitAwareNumericInputState.rebase(minInput, unitSystem, quantity, locale)
            maxInput = UnitAwareNumericInputState.rebase(maxInput, unitSystem, quantity, locale)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(stringResource(R.string.history_filters), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.history_filters_description),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                FilterSection(title = stringResource(R.string.history_family)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        HistoryFamily.entries.forEach { option ->
                            FilterChip(
                                selected = family == option,
                                onClick = {
                                    family = option
                                    if (!seriesAllowedForFamily(series, option)) {
                                        series = ""
                                        minInput = UnitAwareNumericInputState.initial("", unitSystem)
                                        maxInput = UnitAwareNumericInputState.initial("", unitSystem)
                                    }
                                },
                                label = { Text(option.label()) },
                            )
                        }
                    }
                }
                FilterSection(title = stringResource(R.string.history_athlete)) {
                    FilterDropdown(
                        label = stringResource(R.string.history_athlete),
                        selectedKey = selectedAthleteKey,
                        options = athleteOptions,
                        onSelected = { key ->
                            athleteId = key.removePrefix("athlete-").toLongOrNull()
                            unassignedOnly = key == "unassigned"
                        },
                    )
                }
                FilterSection(title = stringResource(R.string.history_group)) {
                    FilterDropdown(
                        label = stringResource(R.string.history_group),
                        selectedKey = selectedGroupKey,
                        options = groupOptions,
                        onSelected = { groupId = it.removePrefix("group-").toLongOrNull() },
                    )
                }
                FilterSection(title = stringResource(R.string.history_protocol_exercise)) {
                    FilterDropdown(
                        label = stringResource(R.string.history_protocol_exercise),
                        selectedKey = selectedSeries,
                        options = seriesOptions,
                        onSelected = {
                            if (series != it) {
                                minInput = UnitAwareNumericInputState.initial("", unitSystem)
                                maxInput = UnitAwareNumericInputState.initial("", unitSystem)
                            }
                            series = it
                        },
                    )
                }
                FilterSection(title = stringResource(R.string.history_date)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OpenJumpDateField(
                            date = dateFrom,
                            onDateChange = {
                                dateFrom = it
                                if (it != null && dateTo != null && it > dateTo) dateTo = it
                            },
                            label = stringResource(R.string.history_date_from),
                            modifier = Modifier.weight(1f),
                            maxDate = today,
                        )
                        OpenJumpDateField(
                            date = dateTo,
                            onDateChange = {
                                dateTo = it
                                if (it != null && dateFrom != null && it < dateFrom) dateFrom = it
                            },
                            label = stringResource(R.string.history_date_to),
                            modifier = Modifier.weight(1f),
                            maxDate = today,
                        )
                    }
                }
                FilterSection(title = stringResource(R.string.history_metric_min)) {
                    if (series.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            OutlinedTextField(
                                min,
                                { value -> filterQuantity?.let { quantity -> minInput = UnitAwareNumericInputState.edited(value, unitSystem, quantity, locale) } },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        filterUnit?.let { unit ->
                                            "${stringResource(R.string.history_metric_min)} ($unit)"
                                        } ?: stringResource(R.string.history_metric_min),
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isError = minInput.requiresReview || minInvalid,
                                supportingText = when {
                                    minInput.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                                    minInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                                    else -> null
                                },
                            )
                            OutlinedTextField(
                                max,
                                { value -> filterQuantity?.let { quantity -> maxInput = UnitAwareNumericInputState.edited(value, unitSystem, quantity, locale) } },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        filterUnit?.let { unit ->
                                            "${stringResource(R.string.history_metric_max)} ($unit)"
                                        } ?: stringResource(R.string.history_metric_max),
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isError = maxInput.requiresReview || maxInvalid,
                                supportingText = when {
                                    maxInput.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                                    maxInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                                    else -> null
                                },
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.history_metric_requires_series),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.screenHorizontal).navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        family = HistoryFamily.ALL
                        athleteId = null
                        unassignedOnly = false
                        groupId = null
                        series = ""
                        dateFrom = null
                        dateTo = null
                        minInput = UnitAwareNumericInputState.initial("", unitSystem)
                        maxInput = UnitAwareNumericInputState.initial("", unitSystem)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.history_reset_filters)) }
                Button(
                    onClick = {
                        val minValue = if (series.isNotBlank()) {
                            filterQuantity?.let { quantity ->
                                UnitAwareNumericInputState.canonical(minInput, quantity, locale, unitSystem)
                            }
                        } else null
                        val maxValue = if (series.isNotBlank()) {
                            filterQuantity?.let { quantity ->
                                UnitAwareNumericInputState.canonical(maxInput, quantity, locale, unitSystem)
                            }
                        } else null
                        onApply(
                            initial.copy(
                                family = family,
                                athleteId = athleteId,
                                unassignedOnly = unassignedOnly,
                                groupId = groupId,
                                protocolOrExercise = series,
                                dateFrom = dateFrom?.let(::startOfDay),
                                dateTo = dateTo?.let(::endOfDay),
                                metricMin = if (minValue != null && maxValue != null) minOf(minValue, maxValue) else minValue,
                                metricMax = if (minValue != null && maxValue != null) maxOf(minValue, maxValue) else maxValue,
                            ),
                        )
                    },
                    enabled = !minInput.requiresReview && !maxInput.requiresReview && !minInvalid && !maxInvalid,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.history_show_results)) }
            }
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = OpenJumpTypes.SectionTitle)
        content()
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    selectedKey: String,
    options: List<FilterOption>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.key == selectedKey }?.label ?: options.first().label
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                contentDescription = label
                stateDescription = selectedLabel
            },
        ) {
            Text(selectedLabel, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 360.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { RadioButton(selected = option.key == selectedKey, onClick = null) },
                    onClick = {
                        onSelected(option.key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryFamily.label() = stringResource(
    when (this) {
        HistoryFamily.ALL -> R.string.history_family_all
        HistoryFamily.JUMP -> R.string.history_family_jump
        HistoryFamily.ENCODER -> R.string.history_family_encoder
    },
)

internal fun historyFilterQuantity(series: String): MeasurementQuantity? {
    if (series.isBlank()) return null
    if (EncoderExercise.entries.any { it.name == series }) return MeasurementQuantity.SPEED_MPS
    return ProtocolCatalog.find(series)?.primaryMetric?.let(MeasurementFormatting::quantity)
}

private fun seriesAllowedForFamily(series: String, family: HistoryFamily): Boolean {
    if (series.isBlank() || family == HistoryFamily.ALL) return true
    val isJumpSeries = ProtocolCatalog.available.any { it.id.storageKey == series }
    return when (family) {
        HistoryFamily.ALL -> true
        HistoryFamily.JUMP -> isJumpSeries
        HistoryFamily.ENCODER -> !isJumpSeries && series !in setOf("HORIZONTAL", "VERTICAL")
    }
}

private fun startOfDay(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun endOfDay(date: LocalDate): Long =
    date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

private fun epochDate(value: Long): LocalDate =
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate()
