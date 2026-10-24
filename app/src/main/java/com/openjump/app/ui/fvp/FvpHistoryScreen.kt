package com.openjump.app.ui.fvp

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.FvpAssessmentSummary
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.fvp.FvpQualityState
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.InfoBanner
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import java.util.Locale

/**
 * Historial F-V-P del atleta seleccionado: lista reactiva (Flow de Room) y comparacion de dos
 * series SOLO si son compatibles. La regla vive en [fvpComparisonPolicy] (mismo atleta, misma
 * familia de version del algoritmo, mismo protocolo/modo de h_po, mismo PROCEDIMIENTO declarado,
 * misma cobertura de cargas con multiplicidad, misma asociacion carga-h_po en `PER_CONDITION`,
 * misma asociacion carga-metodo y PERFIL comparable: nunca una serie diagnostica o incompleta).
 *
 * C2 (plan 13, D5): la masa corporal y el VALOR de h_po en modo `COMMON` ya NO bloquean; se pintan
 * SIEMPRE como CONTEXTO ([FvpComparisonContext], calculado por la politica pura) tambien cuando
 * algo bloquea. Si la masa difiere, los deltas PRIMARIOS son los relativos (N/kg, W/kg) y los
 * absolutos (N, W) se etiquetan como escalados por masa; la linea de h_po lleva su procedencia y
 * la frase de acoplamiento (h_po multiplica la fuerza de TODOS los puntos). Se anade la cobertura
 * de velocidad medida (con el aviso de rango estrecho de B4 como contexto), el recuento de
 * condiciones, el estado de calidad y la version de algoritmo Y metodo de cada serie, con nota
 * prominente ([FvpComparisonContext.versionNoteRequired]) si difiere CUALQUIERA de las dos.
 *
 * Correcciones de la revision P2 sobre la primera version de C2: la nota de masa solo aparece si de
 * verdad se pinta un delta relativo (P2-2); la linea de version habla como el resultado
 * (`fvp_quality_stale_hint`) sin el token interno `STALE_METHOD` (P2-3); con `PER_CONDITION` en
 * alguna serie se LISTA el `(carga, h_po)` de cada condicion, que es lo que explica el bloqueo
 * (P2-6); la diferencia de h_po se calcula sobre los valores canonicalizados a la rejilla (P2-7) y,
 * sin relativos medibles, se declara que no estan disponibles en vez de ensenar solo el grupo
 * escalado por masa (P2-9).
 *
 * Gate B7 / P1-4: la comparacion muestra SIEMPRE el contexto de calidad de cada serie (R^2 o
 * "indefinido", calidad temporal y avisos) y las diferencias se etiquetan como neutras
 * (`fvp_compare_delta_note`) siempre que se pinte algun delta: nunca como mejora/empeoramiento.
 * Si algo bloquea, se anade UNA linea por motivo bloqueante y NO se pinta ningun delta.
 */
@Composable
fun FvpHistoryScreen(
    athleteId: Long?,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    /**
     * Nombre del atleta SELECCIONADO: contexto de la LISTA (cabecera) unicamente. Ninguna fila lo
     * usa como propietario: cada guardado se abre por su `assessmentId` y el resultado resuelve el
     * atleta DE LA SERIE, nunca el seleccionado.
     */
    athleteName: String? = null,
    viewModel: FvpViewModel = viewModel(factory = FvpViewModel.Factory),
) {
    val historyFlow = remember(athleteId) {
        athleteId?.let { viewModel.historyFor(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val summaries by historyFlow.collectAsState(initial = null)
    // La lista se abre para EXPLORAR. El modo de seleccion solo se activa con la accion Comparar y
    // todo su estado se reinicia al cambiar de atleta (no se hereda una seleccion de otra lista).
    var compareMode by remember(athleteId) { mutableStateOf(false) }
    var selectedIds by remember(athleteId) { mutableStateOf<List<Long>>(emptyList()) }
    var reportOpen by remember(athleteId) { mutableStateOf(false) }
    var comparison by remember(athleteId) { mutableStateOf<FvpComparison?>(null) }
    var methodHelpOpen by remember { mutableStateOf(false) }
    val loaded = summaries

    // Reconciliacion con las filas VIGENTES: jamas se conserva -ni se carga- un id que ya no esta
    // en la lista del atleta mostrado. Si la seleccion deja de ser valida, el informe se cierra.
    LaunchedEffect(loaded, selectedIds) {
        if (loaded == null) return@LaunchedEffect
        val valid = loaded.mapTo(mutableSetOf()) { it.id }
        val pruned = selectedIds.filter { it in valid }
        if (pruned != selectedIds) {
            selectedIds = pruned
            reportOpen = false
        }
    }
    // El informe comparativo NO se prepara al marcar la segunda fila: solo cuando el usuario lo
    // pide con el boton explicito. Sin dos ids vigentes no se lee ninguna fila guardada.
    LaunchedEffect(reportOpen, selectedIds) {
        comparison = null
        if (!reportOpen || selectedIds.size != 2) return@LaunchedEffect
        val ids = selectedIds
        val first = runCatching { viewModel.stored(ids[0]) }.getOrNull()
        val second = runCatching { viewModel.stored(ids[1]) }.getOrNull()
        comparison = if (first == null || second == null) {
            FvpComparison.Unavailable
        } else {
            FvpComparison.Loaded(first, second, policy = fvpComparisonPolicy(first, second))
        }
    }

    BackHandler(enabled = compareMode && !reportOpen) {
        compareMode = false
        selectedIds = emptyList()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(
                    if (compareMode) R.string.fvp_compare_title else R.string.fvp_history_title,
                ),
                onNavigationClick = {
                    if (compareMode) {
                        // Cancelar: sale del modo y descarta la seleccion (vuelve a explorar).
                        compareMode = false
                        selectedIds = emptyList()
                        reportOpen = false
                    } else {
                        onBack()
                    }
                },
                navigationContentDescription = stringResource(
                    if (compareMode) R.string.common_cancel else R.string.common_back,
                ),
                actions = {
                    if (!compareMode) {
                        TextButton(
                            onClick = { compareMode = true },
                            // Comparar exige dos series: con menos, la accion no promete nada.
                            enabled = (loaded?.size ?: 0) >= 2,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-history-compare"),
                        ) {
                            Text(stringResource(R.string.fvp_history_compare))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Gate cientifico COMPACTO y persistente: se pinta con la lista cargada, vacia o en
            // carga (fuera del `when`). El texto completo del metodo, sus limites y el aviso de
            // estimacion siguen accesibles por una accion de 48 dp, nunca como parrafo permanente.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // Rectangulo redondeado, no capsula: con 150 % y varias lineas la capsula
                // recortaba las esquinas del texto. Se conservan aviso y colores semanticos.
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = ShapeTokens.medium,
                    color = OpenJumpTheme.colors.warningContainer,
                ) {
                    Text(
                        text = stringResource(R.string.fvp_context_experimental),
                        style = OpenJumpTypes.Secondary,
                        color = OpenJumpTheme.colors.warning,
                        modifier = Modifier.padding(Spacing.sm),
                    )
                }
                TextButton(
                    onClick = { methodHelpOpen = true },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-history-method-help"),
                ) {
                    Text(stringResource(R.string.fvp_capture_method_toggle))
                }
            }
            // Contexto del atleta SOLO para la lista, con fallback honesto: ninguna fila lo toma
            // como propietario ni se usa para abrir un guardado.
            if (athleteId != null) {
                Text(
                    athleteName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.athlete_unassigned),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-history-athlete"),
                )
            }
            if (compareMode) {
                FvpHistoryCompareControls(
                    selectedCount = selectedIds.size,
                    onOpenReport = { reportOpen = true },
                )
            }
            when {
                loaded == null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
                loaded.isEmpty() -> EmptyState(
                    title = stringResource(R.string.fvp_empty_state_title),
                    body = stringResource(R.string.fvp_empty_state_body),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(loaded, key = { it.id }) { summary ->
                        FvpHistoryRow(
                            summary = summary,
                            compareMode = compareMode,
                            selected = summary.id in selectedIds,
                            onOpen = { onOpen(summary.id) },
                            onToggleCompare = {
                                // Limite DURO de dos series: una tercera fila no entra sin quitar
                                // antes una de las elegidas.
                                selectedIds = when {
                                    summary.id in selectedIds -> selectedIds - summary.id
                                    selectedIds.size < 2 -> selectedIds + summary.id
                                    else -> selectedIds
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item { Spacer(Modifier.height(Spacing.lg)) }
                }
            }
        }
    }

    // Informe comparativo en superficie MODAL de pantalla completa y scroll propio: la lista no se
    // sustituye ni queda detras de un informe insertado automaticamente. Back/Cerrar vuelve a la
    // seleccion; Cancelar (barra superior de la lista) descarta la seleccion y vuelve a explorar.
    if (reportOpen && selectedIds.size == 2) {
        Dialog(
            onDismissRequest = { reportOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FvpComparisonReportDialog(
                comparison = comparison,
                onClose = { reportOpen = false },
            )
        }
    }
    if (methodHelpOpen) {
        AlertDialog(
            onDismissRequest = { methodHelpOpen = false },
            title = { Text(stringResource(R.string.fvp_capture_method_toggle)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        stringResource(R.string.fvp_experimental_label),
                        style = OpenJumpTypes.Body,
                        modifier = Modifier.testTag("fvp-history-method-help-label"),
                    )
                    Text(
                        stringResource(R.string.fvp_estimate_notice_body),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("fvp-history-method-help-body"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { methodHelpOpen = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-history-method-help-close"),
                ) {
                    Text(stringResource(R.string.common_back))
                }
            },
        )
    }
}

/**
 * Controles del modo de seleccion (0/1/2, limite dos): recuento visible y boton EXPLICITO que abre
 * el informe. Dos filas habilitan la APERTURA del informe, nunca la validez de los deltas: eso lo
 * decide `fvpComparisonPolicy` dentro del informe.
 */
@Composable
private fun FvpHistoryCompareControls(
    selectedCount: Int,
    onOpenReport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(R.string.fvp_compare_select_hint),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("fvp-history-compare-hint"),
            )
            Text(
                "$selectedCount/2",
                style = OpenJumpTypes.Label,
                modifier = Modifier.testTag("fvp-history-compare-count"),
            )
        }
        Button(
            onClick = onOpenReport,
            enabled = selectedCount == 2,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fvp-history-compare-open"),
        ) {
            Text(stringResource(R.string.fvp_compare_open_report, selectedCount))
        }
    }
}

/**
 * Informe comparativo en una superficie modal de pantalla completa con scroll propio. Reutiliza la
 * tarjeta y la politica EXISTENTES tal cual: contexto completo, avisos y guardas de deltas no
 * cambian; lo unico que cambia es DONDE se pinta (bajo pedido, no delante de la lista).
 */
@Composable
private fun FvpComparisonReportDialog(
    comparison: FvpComparison?,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize().testTag("fvp-compare-report")) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                OpenJumpTopAppBar(
                    title = stringResource(R.string.fvp_compare_title),
                    onNavigationClick = onClose,
                    navigationContentDescription = stringResource(R.string.common_back),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                when (comparison) {
                    null -> Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    FvpComparison.Unavailable -> InfoBanner(
                        title = stringResource(R.string.fvp_cannot_compute),
                        body = "",
                        warning = true,
                    )
                    is FvpComparison.Loaded -> if (comparison.policy.canCompareDeltas) {
                        FvpComparisonCard(comparison)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            // Mensaje general de siempre + UNA linea por motivo EXACTO dentro de
                            // la tarjeta; ningun delta cuando algo bloquea.
                            InfoBanner(
                                title = stringResource(R.string.fvp_incompatible_series),
                                body = stringResource(R.string.fvp_incompatible_series_body),
                                warning = true,
                            )
                            FvpComparisonCard(comparison)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun FvpHistoryRow(
    summary: FvpAssessmentSummary,
    compareMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleCompare: () -> Unit,
) {
    val locale = Locale.getDefault()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(
                if (compareMode) {
                    // Modo seleccion: TODA la fila marca/desmarca (maximo dos), con casilla visible
                    // como indicador; el gesto principal sigue siendo uno solo.
                    Modifier
                        .toggleable(
                            value = selected,
                            role = Role.Checkbox,
                            onValueChange = { onToggleCompare() },
                        )
                        .testTag("fvp-history-select-${summary.id}")
                } else {
                    // Modo normal: TODA la fila ABRE el guardado; sin casillas ni "Ver" repetido.
                    Modifier
                        .clickable(role = Role.Button, onClick = onOpen)
                        .testTag("fvp-history-open-${summary.id}")
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (compareMode) {
            Checkbox(checked = selected, onCheckedChange = null)
        }
        Column(
            modifier = Modifier.weight(1f).padding(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Formatting.dateTimeToText(summary.dateTime),
                    style = OpenJumpTypes.Body,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).testTag("fvp-history-date-${summary.id}"),
                )
                Text(
                    summary.protocolId,
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-history-protocol-${summary.id}"),
                )
            }
            // Cifras de la fila alineadas: F0 y Pmax con sus unidades SI (mismo formateo que antes).
            Text(
                "F0 " + String.format(locale, "%.1f", summary.f0N) + " N · Pmax " + (
                    summary.pmaxW?.let { String.format(locale, "%.0f", it) + " W" } ?: "—"
                    ),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-history-metrics-${summary.id}"),
            )
        }
        if (!compareMode) {
            Text(
                "›",
                style = OpenJumpTypes.SectionTitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FvpComparisonCard(comparison: FvpComparison.Loaded) {
    val context = comparison.policy.context
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.fvp_compare_title), style = OpenJumpTypes.SectionTitle)
            listOf(comparison.first, comparison.second).forEach { stored ->
                Text(
                    Formatting.dateTimeToText(stored.assessment.dateTime) +
                        " · F0 " + String.format(Locale.getDefault(), "%.1f", stored.assessment.f0N) + " N" +
                        " · V0 " + (
                            stored.assessment.v0Mps?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—"
                            ) + " m/s" +
                        " · Pmax " + (
                            stored.assessment.pmaxW?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "—"
                            ) + " W",
                    style = OpenJumpTypes.Body,
                )
                // Contexto de calidad OBLIGATORIO de cada serie antes de cualquier diferencia, con
                // las DOS versiones exactas visibles (algoritmo y metodo, correccion exigida por la
                // revision de C2: una diferencia solo de metodo tambien impide comparar en firme).
                Text(
                    stringResource(R.string.fvp_metric_r2) + " " + r2Text(stored.assessment.r2) +
                        // Sin elegibilidad (fila no recalculable) la calidad temporal es
                        // DESCONOCIDA: ruta neutra existente, nunca una calidad inventada.
                        " · " + stringResource(
                            (stored.eligibility?.timingQuality ?: FvpTimingQuality.UNKNOWN).labelResource(),
                        ) +
                        " · " + stringResource(R.string.fvp_algorithm_version, stored.assessment.algorithmVersion) +
                        " · " + stringResource(R.string.fvp_method_version, stored.assessment.methodVersion),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val warningLabels = (
                    storedWarningsForUi(
                        storedWarningNames = stored.warnings,
                        pushOffSource = fvpEffectivePushOffSource(
                            declaredSource = stored.assessment.pushOffSource,
                            landmark = stored.assessment.pushOffLandmark,
                            procedure = stored.assessment.pushOffProtocol,
                            measuredAt = stored.assessment.pushOffMeasuredAt,
                        ),
                    ) + stored.provenanceWarningsForUi()
                    ).distinct().sortedBy { it.ordinal }.map { fvpWarningText(it) }
                if (warningLabels.isNotEmpty()) {
                    Text(
                        stringResource(R.string.fvp_compare_warnings) + ": " + warningLabels.joinToString(" · "),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // C2: el contexto se pinta SIEMPRE (tambien cuando algo bloquea los deltas). P2-6: las
            // etiquetas de serie (fecha) permiten identificar de quien es cada tabla de h_po por
            // condicion sin depender del orden de las cabeceras.
            FvpComparisonContextRows(
                context = context,
                seriesLabels = listOf(
                    Formatting.dateTimeToText(comparison.first.assessment.dateTime),
                    Formatting.dateTimeToText(comparison.second.assessment.dateTime),
                ),
            )
            if (!comparison.policy.canCompareDeltas) {
                // Motivo EXACTO por bloqueo (orden del enum, determinista) y NINGUN delta:
                // el contexto de arriba se mantiene.
                comparison.policy.blocks.forEach { block ->
                    Text(
                        stringResource(block.reasonResource()),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Column
            }
            if (context.versionNoteRequired) {
                // Nota prominente por CUALQUIERA de las dos versiones (algoritmo o metodo). P2-3: el
                // texto habla como el resultado guardado (la valoracion es la de la version que
                // registro la serie), SIN el token interno `STALE_METHOD`.
                Text(
                    stringResource(
                        R.string.fvp_compare_version_differs,
                        context.algorithmVersionFirst,
                        context.methodVersionFirst,
                        context.algorithmVersionSecond,
                        context.methodVersionSecond,
                    ),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FvpComparisonDeltas(context)
        }
    }
}

/**
 * Filas de CONTEXTO de C2, comunes a las dos ramas: masa corporal (con los deltas relativos ya
 * persistidos), h_po en modo `COMMON` con su procedencia y la frase de acoplamiento, h_po POR
 * CONDICION cuando alguna serie es `PER_CONDITION` (P2-6: el dato que explica el bloqueo), cobertura
 * de velocidad medida (con el aviso de rango estrecho de B4 cuando corresponde), recuento de
 * condiciones y estado de calidad de cada serie. Ninguna de estas filas es un delta de rendimiento.
 *
 * [seriesLabels] son las etiquetas (fecha) de la primera y la segunda serie, en el mismo orden que
 * las cabeceras de la tarjeta: solo se usan para identificar las tablas de h_po por condicion.
 */
@Composable
private fun FvpComparisonContextRows(
    context: FvpComparisonContext,
    seriesLabels: List<String>,
) {
    Text(
        stringResource(R.string.fvp_body_mass) + ": " +
            numberPair(context.bodyMassKg, "%.1f", " kg") +
            deltaSuffix(context.bodyMassKg.delta, decimals = 1, unit = " kg"),
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // P2-2: la nota solo puede afirmar que los relativos son la lectura primaria cuando de verdad
    // se pinta alguna linea de delta relativa. En la rama bloqueada (o sin relativos medibles) no
    // hay ninguna: afirmarlo seria falso.
    if (context.massDiffers && context.relativeDeltasShown) {
        Text(
            stringResource(R.string.fvp_compare_mass_differs_note),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // h_po solo tiene un valor unico en modo COMMON; en PER_CONDITION lo bloqueante es la
    // asociacion carga-h_po y aqui no se inventa un h_po medio.
    context.pushOffM?.let { pushOff ->
        Text(
            stringResource(R.string.fvp_push_off) + ": " +
                numberPair(pushOff, "%.2f", " m") +
                // P2-7: la diferencia se calcula con los MISMOS valores canonicalizados que compara
                // la regla (rejilla de 0,5 cm): dentro de la rejilla es exactamente 0 (nunca
                // "-0.0 cm" por el ruido de ida y vuelta metrico/imperial) y fuera de ella el
                // signo es real.
                deltaSuffix(
                    delta = context.canonicalPushOffM?.delta,
                    decimals = 1,
                    unit = " cm",
                    scale = 100.0,
                ),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.fvp_compare_push_off_coupling),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // P2-6: con PER_CONDITION en alguna de las dos series, el h_po de cada carga es el dato que
    // explica el bloqueo (o la diferencia de modos). Solo lectura: no entra en ninguna regla.
    listOf(context.perLoadPushOffFirst, context.perLoadPushOffSecond)
        .forEachIndexed { index, entries ->
            if (entries != null) {
                Text(
                    stringResource(
                        R.string.fvp_compare_per_load_push_off,
                        seriesLabels.getOrElse(index) { "" },
                        perLoadPushOffText(entries),
                    ),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    Text(
        stringResource(R.string.fvp_push_off_landmark) + ": " +
            provenancePair(context.pushOffProvenanceFirst, context.pushOffProvenanceSecond),
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.fvp_compare_context_velocity_coverage) + ": " +
            velocityPair(context.velocityFirst, context.velocitySecond),
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.fvp_compare_velocity_note),
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (context.narrowRangeWarningApplies) {
        // Aviso de rango estrecho de B4: contexto VISIBLE, nunca un delta de rendimiento.
        Text(
            fvpWarningText(FvpUiWarning.NARROW_VELOCITY_RANGE),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        stringResource(R.string.fvp_conditions_title) + ": " +
            context.conditionCountFirst + " · " + context.conditionCountSecond,
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.fvp_compare_context_quality) + ": " +
            stringResource(context.qualityFirst.labelResource()) + " · " +
            stringResource(context.qualitySecond.labelResource()),
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Deltas DESCRIPTIVOS: nota neutral SIEMPRE que se pinte alguno, relativos como linea primaria
 * cuando la masa difiere y absolutos de fuerza/potencia bajo su etiqueta de escalado por masa. Si
 * la masa difiere y no hay NINGUN relativo medible (P2-9), se declara que no estan disponibles en
 * vez de ensenar solo el grupo escalado como si fuera toda la lectura.
 */
@Composable
private fun FvpComparisonDeltas(context: FvpComparisonContext) {
    if (!context.neutralCaptionRequired) return
    Text(
        stringResource(R.string.fvp_compare_delta_note),
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    context.deltaLines.filter { it.primary }.forEach { delta -> FvpComparisonDeltaText(delta) }
    // P2-9 (defensivo): sin ninguna linea primaria relativa, el hueco se declara explicitamente.
    if (context.relativeDeltasUnavailable) {
        Text(
            stringResource(R.string.fvp_compare_relative_unavailable),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val massScaled = context.deltaLines.filter { it.massScaled }
    if (massScaled.isNotEmpty()) {
        // Los absolutos NO son la misma afirmacion que los relativos: etiqueta explicita.
        Text(
            stringResource(R.string.fvp_compare_delta_mass_scaled),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        massScaled.forEach { delta -> FvpComparisonDeltaText(delta) }
    }
}

/** Una linea de delta localizada, con el papel de [FvpComparisonDeltaLine] (nunca como mejora). */
@Composable
private fun FvpComparisonDeltaText(delta: FvpComparisonDeltaLine) {
    val text = when (delta.kind) {
        FvpComparisonDeltaKind.F0_RELATIVE -> stringResource(
            R.string.fvp_compare_delta_f0_relative,
            signed(delta.value, 2),
        )
        FvpComparisonDeltaKind.PMAX_RELATIVE -> stringResource(
            R.string.fvp_compare_delta_pmax_relative,
            signed(delta.value, 2),
        )
        FvpComparisonDeltaKind.F0_ABSOLUTE -> "ΔF0: " + signed(delta.value, 1) + " N"
        FvpComparisonDeltaKind.PMAX_ABSOLUTE -> "ΔPmax: " + signed(delta.value, 0) + " W"
        FvpComparisonDeltaKind.V0_ABSOLUTE -> "ΔV0: " + signed(delta.value, 2) + " m/s"
    }
    Text(text, style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Valor con signo de un delta (`null` -> marcador localizado de indefinido). */
private fun signed(value: Double, decimals: Int): String =
    String.format(Locale.getDefault(), "%+." + decimals + "f", value)

/** Par de valores de las dos series (`—` si alguno no es medible) en la unidad dada. */
private fun numberPair(value: FvpComparisonValue, pattern: String, unit: String): String {
    val first = value.first?.let { String.format(Locale.getDefault(), pattern, it) } ?: "—"
    val second = value.second?.let { String.format(Locale.getDefault(), pattern, it) } ?: "—"
    return "$first$unit · $second$unit"
}

/** Sufijo con la diferencia (`—` si no es medible); [scale] convierte a la unidad mostrada (cm). */
private fun deltaSuffix(
    delta: Double?,
    decimals: Int,
    unit: String,
    scale: Double = 1.0,
): String = " · Δ " + (delta?.let { signed(it * scale, decimals) + unit } ?: "—")

/**
 * Pares `(carga, h_po)` de una serie en una sola linea: `0 kg → 0.46 m · 20 kg → 0.42 m`. El h_po
 * llega CANONICALIZADO a la rejilla de entrada (la politica lo expone asi): es el valor con el que
 * se compara, no el ruido persistido. Lista vacia -> marcador localizado de indefinido.
 */
@Composable
private fun perLoadPushOffText(entries: List<FvpPerLoadPushOffEntry>): String {
    if (entries.isEmpty()) return stringResource(R.string.fvp_undefined_value)
    return entries.joinToString(" · ") { entry ->
        String.format(Locale.getDefault(), "%.0f", entry.loadKg) + " kg → " +
            String.format(Locale.getDefault(), "%.2f", entry.pushOffM) + " m"
    }
}

/** Hito y fecha de medicion de cada serie, con el origen B1 EFECTIVO (medido o estimado). */
@Composable
private fun provenancePair(first: FvpPushOffProvenance, second: FvpPushOffProvenance): String =
    provenanceText(first) + " · " + provenanceText(second)

@Composable
private fun provenanceText(provenance: FvpPushOffProvenance): String {
    val source = stringResource(
        if (fvpEffectivePushOffSource(
                declaredSource = provenance.source,
                landmark = provenance.landmark,
                procedure = provenance.procedure,
                measuredAt = provenance.measuredAt,
            ) == FVP_PUSH_OFF_SOURCE_MEASURED
        ) {
            R.string.fvp_compare_push_off_source_measured
        } else {
            R.string.fvp_compare_push_off_source_manual
        },
    )
    return stringResource(
        R.string.fvp_compare_push_off_provenance,
        source,
        provenance.landmark?.takeIf { it.isNotBlank() } ?: stringResource(R.string.fvp_undefined_value),
        provenance.measuredAt?.let { Formatting.dateTimeToText(it) }
            ?: stringResource(R.string.fvp_undefined_value),
    )
}

/** Cobertura `[vMin, vMax]` medida de cada serie (`—` si no es medible). */
private fun velocityPair(
    first: FvpSeriesVelocityCoverage,
    second: FvpSeriesVelocityCoverage,
): String = velocityText(first) + " · " + velocityText(second)

private fun velocityText(coverage: FvpSeriesVelocityCoverage): String {
    val minMps = coverage.vMinMps?.let { String.format(Locale.getDefault(), "%.2f", it) }
    val maxMps = coverage.vMaxMps?.let { String.format(Locale.getDefault(), "%.2f", it) }
    return if (minMps != null && maxMps != null) "[$minMps, $maxMps] m/s" else "—"
}

/**
 * Texto localizado del motivo de bloqueo. `NOT_A_PROFILE` declara que alguna serie es
 * diagnostica/incompleta y NO se puede comparar como perfil; los demas nombran el metadato que
 * difiere, sin tecnicismos. `DIFFERENT_PUSH_OFF_PROCEDURE` habla del PROCEDIMIENTO declarado (el
 * valor de h_po ya no bloquea en `COMMON`); la cobertura de cargas y la asociacion carga-h_po por
 * condicion tienen su propio motivo. B4b anade los motivos de PROCEDENCIA (contexto de adquisicion
 * y mapeo temporal): la comparacion sigue siendo DESCRIPTIVA (contexto visible) pero no publica
 * deltas ni ganador.
 */
private fun FvpComparisonBlock.reasonResource(): Int = when (this) {
    FvpComparisonBlock.DIFFERENT_ATHLETE -> R.string.fvp_compare_block_different_athlete
    FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM -> R.string.fvp_compare_block_unknown_algorithm
    FvpComparisonBlock.DIFFERENT_PROTOCOL -> R.string.fvp_compare_block_different_protocol
    FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE ->
        R.string.fvp_compare_block_different_push_off_procedure
    FvpComparisonBlock.NOT_A_PROFILE -> R.string.fvp_compare_block_not_a_profile
    FvpComparisonBlock.DIFFERENT_MEASURE_METHODS -> R.string.fvp_compare_block_different_methods
    FvpComparisonBlock.DIFFERENT_LOAD_COVERAGE -> R.string.fvp_compare_block_different_load_coverage
    FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF ->
        R.string.fvp_compare_block_different_per_load_push_off
    FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT ->
        R.string.fvp_compare_block_different_acquisition_context
    FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT_KEY ->
        R.string.fvp_compare_block_different_acquisition_context_key
    FvpComparisonBlock.TIME_MAPPING_NOT_VERIFIED ->
        R.string.fvp_compare_block_time_mapping_not_verified
}

/** Etiqueta localizada del estado de calidad DERIVADO (mismas claves que la vista del resultado). */
private fun FvpQualityState.labelResource(): Int = when (this) {
    FvpQualityState.INCOMPLETE -> R.string.fvp_quality_incomplete
    FvpQualityState.DIAGNOSTIC -> R.string.fvp_quality_diagnostic
    FvpQualityState.CONDITIONAL -> R.string.fvp_quality_conditional
    FvpQualityState.PROFILE -> R.string.fvp_quality_profile
}

/** R^2 de una serie guardada, o el marcador localizado de valor indefinido (nunca 0.0). */
@Composable
private fun r2Text(value: Double?): String = value
    ?.takeIf { it.isFinite() }
    ?.let { String.format(Locale.getDefault(), "%.3f", it) }
    ?: stringResource(R.string.fvp_undefined_value)

/** Estado de la comparacion pedida por el usuario. */
internal sealed interface FvpComparison {
    data object Unavailable : FvpComparison

    data class Loaded(
        val first: StoredFvpAssessment,
        val second: StoredFvpAssessment,
        val policy: FvpComparisonPolicy,
    ) : FvpComparison
}
