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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HistoryScreen(
    onMeasurementSelected: (Long) -> Unit,
    onEncoderSessionSelected: (Long) -> Unit,
    onAngleAnalysisSelected: (AngleAnalysisHistoryEntry) -> Unit,
    onAngleMultiFrameSelected: (AngleAnalysisHistoryEntry) -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val athletes by viewModel.athletes.collectAsState()
    val groups by viewModel.groups.collectAsState()
    // F035 + Ángulos: objetivo pendiente de confirmación (cancelar = null, sin efecto), objetivos
    // con borrado en vuelo y último fallo para reintentar. La lista se conserva hasta que el
    // repositorio confirme el borrado de la raíz.
    val deletingTargets by viewModel.deletingTargets.collectAsState()
    val deleteFailedTarget by viewModel.deleteFailedTarget.collectAsState()
    // V2: confirmación preparada (cuenta REAL de fotogramas del agregado) y aviso posterior. La
    // prueba exacta no llega a la composición ni se guarda: sólo la tiene el controlador en memoria.
    val v2DeleteConfirmation by viewModel.v2DeleteConfirmation.collectAsState()
    val v2DeleteNotice by viewModel.v2DeleteNotice.collectAsState()
    val v2DeleteAvailable = viewModel.angleV2DeleteAvailable
    // Se guarda la clave (familia + id) y no un id suelto: sobrevive a la recreación sin poder
    // confundir un análisis con una sesión de otra familia que comparta número.
    var pendingDeleteKey by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDelete = pendingDeleteKey?.let(HistoryDeleteTarget::fromKey)
    var showFilters by rememberSaveable { mutableStateOf(false) }
    // Identidad EXPLÍCITA del estado de scroll por consulta efectiva (`familia + HistoryQuery`): el
    // mismo filtro conserva la posición al volver de un detalle, al guardar o al borrar, y un cambio
    // real de consulta reinicia el desplazamiento a propósito en vez de reutilizar la posición de
    // otra lista. El valor se guarda/restaura con el estado de la entrada de navegación.
    val listState = rememberSaveable(
        historyScrollStateKey(state.filters),
        saver = LazyListState.Saver,
    ) { LazyListState() }
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
            state = listState,
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
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        singleLine = true,
                        // Pastilla compartida con Filtros (F-015): misma forma/tipo y
                        // altura, sin cambiar la lógica de filtrado. F-009 se
                        // conserva: etiqueta en una línea con elipsis.
                        shape = ShapeTokens.full,
                        // En pantallas compactas la etiqueta se partía en dos líneas
                        // dentro del campo. Se fuerza una sola línea con elipsis sin
                        // cambiar el copy. Ver hallazgo F-009.
                        label = {
                            Text(
                                stringResource(R.string.history_search),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                    OutlinedButton(
                        onClick = { showFilters = true },
                        modifier = Modifier.heightIn(min = 56.dp),
                        shape = ShapeTokens.full,
                    ) {
                        Text(
                            if (state.filters.activeCount > 0) {
                                stringResource(R.string.history_filters_count, state.filters.activeCount)
                            } else {
                                stringResource(R.string.history_filters)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
            if (state.items.isEmpty() && state.loading) {
                // Sin filas y con una lectura en curso: ni «No results» prematuro ni etiqueta antes
                // del retardo visible. El espacio queda vacío hasta que el ViewModel decida.
                if (state.showInitialLoader) {
                    item {
                        Text(
                            stringResource(R.string.history_loading),
                            modifier = Modifier.padding(vertical = Spacing.lg),
                        )
                    }
                }
            } else if (state.error && state.items.isEmpty()) {
                item {
                    EmptyState(stringResource(R.string.history_error_title), stringResource(R.string.history_error_body))
                    Button(
                        // Sin filas no hay nada que paginar: el botón repite la lectura que falló
                        // (primera página del filtro actual), no un append.
                        onClick = viewModel::retry,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.history_retry)) }
                }
            } else if (state.items.isEmpty()) {
                item { EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body)) }
            } else {
                itemsIndexed(state.items, key = { _, item ->
                    // La fuente forma parte de la clave: `angle-7` y `angle-v2-7` son filas de
                    // tablas distintas que pueden coexistir en la misma página.
                    item.historyRowKey()
                }) { index, item ->
                    val deleteTarget = historyDeleteTarget(item)
                    // La acción V2 sólo se ofrece con prueba + dueño de aplicación cableados; el
                    // objetivo (familia ANGLE_V2 + id) ya lo decidió historyDeleteTarget a partir de
                    // la fuente de la fila y de la generación que observó la página.
                    val v2Entry = (item as? MeasurementHistoryItem.Angle)?.entry
                        ?.takeIf { it.source == AngleHistorySource.MULTI_FRAME && v2DeleteAvailable }
                    HistoryEntryRow(
                        item,
                        onClick = {
                            // Una lista retenida de otra generación de restauración no navega: el id
                            // publicado puede describir ya otra fila. La puerta es en memoria y sólo
                            // bloquea listas realmente obsoletas.
                            if (viewModel.allowRetainedRowAction()) {
                                when (item) {
                                    is MeasurementHistoryItem.Jump -> onMeasurementSelected(item.id)
                                    is MeasurementHistoryItem.Encoder -> onEncoderSessionSelected(item.id)
                                    // Cada fuente de Ángulos tiene SU destino: el detalle guardado
                                    // legacy lee `angle_analyses` y no puede recibir un id de
                                    // `angle_analysis_v2_roots` (mismo número, otra tabla). La fila V2
                                    // usa un callback distinto y no cae nunca en la ruta legacy.
                                    is MeasurementHistoryItem.Angle -> when (item.entry.source) {
                                        AngleHistorySource.LEGACY -> onAngleAnalysisSelected(item.entry)
                                        AngleHistorySource.MULTI_FRAME -> onAngleMultiFrameSelected(item.entry)
                                    }
                                }
                            }
                        },
                        showDivider = index < state.items.lastIndex,
                        // Dos caminos de borrado, nunca un id suelto: el legacy confirma (familia +
                        // id) a través del ViewModel y la raíz V2 prepara una prueba exacta con la
                        // generación de la fila. Sin esa generación no hay acción que ofrecer.
                        onDelete = when {
                            deleteTarget == null -> null
                            deleteTarget.family == HistoryDeleteFamily.ANGLE_V2 ->
                                v2Entry?.let { entry ->
                                    {
                                        // La prueba V2 sigue llevando el token de su fila; la puerta
                                        // de generación sólo evita abrirla desde una lista obsoleta.
                                        if (viewModel.allowRetainedRowAction()) viewModel.requestV2Delete(entry)
                                    }
                                }
                            else -> ({
                                if (viewModel.prepareLegacyDelete(deleteTarget)) pendingDeleteKey = deleteTarget.key
                            })
                        },
                        deleting = deleteTarget != null &&
                            deleteTarget.family != HistoryDeleteFamily.ANGLE_V2 &&
                            deleteTarget in deletingTargets,
                    )
                }
                historyListFooter(
                    state,
                    onLoadMore = viewModel::loadMore,
                    // Reintento de la intención EXACTA que falló (portada o cursor pendiente).
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    // Confirmación explícita del objetivo pendiente; cancelar sólo cierra el diálogo. Un fallo
    // conserva la lista retenida y reintenta el MISMO objetivo (familia incluida).
    HistoryDeleteDialogs(
        pending = pendingDelete,
        failed = deleteFailedTarget,
        onConfirm = { target ->
            pendingDeleteKey = null
            viewModel.confirmLegacyDelete(target)
        },
        onCancelPending = {
            pendingDeleteKey = null
            viewModel.cancelLegacyDelete()
        },
        onRetry = viewModel::retryLegacyDelete,
        onDismissFailure = viewModel::acknowledgeDeleteFailure,
    )

    // Familia V2: confirmación explícita de la prueba preparada y avisos no destructivos. Cancelar no
    // ejecuta nada; un fallo antes del commit se reintenta con la MISMA prueba.
    HistoryV2DeletionDialogs(
        confirmation = v2DeleteConfirmation,
        notice = v2DeleteNotice,
        onConfirm = viewModel::confirmV2Delete,
        onCancel = viewModel::cancelV2Delete,
        onDismissNotice = viewModel::dismissV2DeleteNotice,
        onRetry = viewModel::retryV2Delete,
    )
}

private data class FilterOption(val key: String, val label: String)

/**
 * Footer for the loaded list. An append-page failure ([HistoryScreenState.error] with
 * retained items) surfaces an inline explanation plus an explicit retry instead of
 * falling back to the ambiguous "load more" button, so a partial list is never
 * presented as complete. No paging refactor: retry reuses the pending cursor.
 *
 * [onRetry] repeats the exact intent that failed. A silent same-filter refresh is not an
 * append: it keeps the rows and the button label untouched while it is in flight.
 */
internal fun LazyListScope.historyListFooter(
    state: HistoryScreenState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit = onLoadMore,
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
                    onClick = onRetry,
                    enabled = !state.loading && !state.refreshing,
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
                enabled = !state.loading && !state.refreshing,
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
        // Ángulos no tiene protocolo ni ejercicio: con esa familia la única opción es «Todos».
        if (family != HistoryFamily.ANGLE) {
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
                // Serie y valor no aplican a la familia Ángulos: se ocultan en vez de ofrecer
                // filtros que ninguna fila de ángulos podría cumplir.
                if (family != HistoryFamily.ANGLE) {
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
                if (family != HistoryFamily.ANGLE) {
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
        HistoryFamily.ANGLE -> R.string.history_family_angle
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
        // Un análisis de ángulos no tiene protocolo ni ejercicio: la serie no aplica.
        HistoryFamily.ANGLE -> false
    }
}

/**
 * Clave del estado de scroll guardable: la consulta EFECTIVA (`familia + HistoryQuery` ya
 * normalizada por `toQuery()`). Un espacio extra en la búsqueda no crea una identidad nueva; un
 * cambio real de familia, atleta, grupo, serie, fechas o métrica sí, y reinicia el desplazamiento a
 * propósito. Las filas de otra consulta nunca pueden reutilizar la posición de la anterior.
 */
internal fun historyScrollStateKey(filters: HistoryFilters): String =
    "${filters.family.name}|${filters.toQuery()}"

private fun startOfDay(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun endOfDay(date: LocalDate): Long =
    date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

private fun epochDate(value: Long): LocalDate =
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate()
