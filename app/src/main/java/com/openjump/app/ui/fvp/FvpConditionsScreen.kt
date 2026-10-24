@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openjump.app.ui.fvp

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.ReusableJumpMeasurement
import com.openjump.app.fvp.FvpEligibilityProblem
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.fvp.fvpTimingQuality
import com.openjump.app.fvp.needsThirdAttempt
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.InfoBanner
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.StatusPill
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.fvp.FvpMeasureMethod
import java.math.BigDecimal

/**
 * Paso 2 del flujo F-V-P: condiciones de carga con sus intentos.
 *
 * Reglas vinculantes:
 * - clave por condicion por defecto "<carga>kg" (se actualiza mientras el usuario no la edite);
 * - el h_po por condicion solo se pide en modo PER_CONDITION;
 * - "reutilizar salto guardado" RELLENA SOLO altura + fps + origen del intento elegido: la carga y
 *   el h_po siguen siendo declarados por el usuario (nada de valores por defecto inventados);
 * - aviso de tercer intento cuando los dos primeros difieren mas del 5 % (regla de elegibilidad);
 * - B4a-2: la procedencia de la carga anadida se DECLARA por condicion (`UNKNOWN` por defecto) y la
 *   confirmacion de reutilizacion es DURABLE (`reuseConfirmedAt` + `reuseConfirmationVersion`).
 *   Toda mutacion que cambie carga, procedencia de carga, h_po relevante, metodo/altura o fuente
 *   reutilizada pasa por los helpers de mutacion que retiran las confirmaciones afectadas EN LA
 *   MISMA mutacion del borrador (no en un efecto de UI posterior).
 */
@Composable
fun FvpConditionsScreen(
    onBack: () -> Unit,
    onCalculate: () -> Unit,
    /**
     * Dueño UNICO del flujo (A2): `AppNav` pasa SIEMPRE la misma instancia que creo una sola vez
     * en el `NavHost`. `null` queda SOLO para montajes aislados de esta pantalla (tests de UI sin
     * dueño); en ese caso se usa la sesion directamente y NO se crea un segundo dueño.
     */
    flowViewModel: FvpFlowViewModel? = null,
    viewModel: FvpViewModel = viewModel(factory = FvpViewModel.Factory),
) {
    // A2: toda mutacion externa del borrador pasa por el DUEÑO cuando existe.
    val mutations: FvpDraftMutations = flowViewModel ?: FvpDraftSession
    val draft by FvpDraftSession.draft.collectAsState()
    val current = draft
    if (current == null) {
        FvpExpiredDraftBody(onBack = onBack)
        return
    }
    val presentation = remember(current) { viewModel.compute(current) }
    val missingAttemptHeight = current.conditions.any { condition -> condition.attempts.any { it.heightM == null } }
    // Gate B3: un intento declarado NO valido necesita un motivo de exclusion no vacio. Si falta, el
    // campo se marca y el calculo queda bloqueado, igual que una altura vacia: el motivo no puede
    // ser opcional en la practica ni quedar silenciado.
    val missingExclusionReason = current.conditions.any { condition ->
        condition.attempts.any { it.valid.not() && it.exclusionReason.isNullOrBlank() }
    }
    // HANDOFF DEL CONTEXTO (slice B): la declaracion "SAME_ACQUISITION" exige clave no vacia. Es
    // una comprobacion PROPIA de esta pantalla (el contexto deja de vivir en el paso 1), se SUMA a
    // los gates existentes en `canCalculate` y NO toca la formula de elegibilidad ni el dominio:
    // `UNKNOWN` (el default) sigue siendo una respuesta valida y no bloquea nada.
    val acquisitionKind = FvpAcquisitionContextUi.fromStorageKey(current.acquisitionContextKind)
        ?: FvpAcquisitionContextUi.UNKNOWN
    val acquisitionContextKeyMissing = acquisitionKind == FvpAcquisitionContextUi.SAME_ACQUISITION &&
        current.acquisitionContextKey.isNullOrBlank()
    val canCalculate = presentation.canCompute && !missingAttemptHeight && !missingExclusionReason &&
        !acquisitionContextKeyMissing
    // Destinos de revision (plan `fvp-guidance-ux.md`, slice B): se derivan de los MISMOS motivos
    // que ya forman el gate y de las identidades estables del borrador (`draftKey`). No deciden
    // elegibilidad, no mutan el borrador y no cambian `canCalculate`. Se recalculan con el borrador
    // ACTUAL, de modo que borrar una condicion no deja ningun destino obsoleto. El motivo de la
    // clave de contexto (UI-only) va PRIMERO porque su campo esta por encima de las cargas.
    val pendings = remember(current, presentation, acquisitionContextKeyMissing) {
        buildList {
            if (acquisitionContextKeyMissing) add(fvpAcquisitionContextKeyPending())
            addAll(
                fvpConditionPendings(
                    conditions = current.conditions,
                    pushOffMode = current.pushOffMode,
                    blocking = presentation.eligibility.blocking,
                    missingAttemptHeight = missingAttemptHeight,
                    missingExclusionReason = missingExclusionReason,
                ),
            )
        }
    }
    val firstPending = pendings.firstOrNull()
    val identicalHeightsPending = pendings.any {
        it.problem == FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS
    }
    // Marcas LOCALES por campo (solo presentacion): reutilizan las mismas reglas que la
    // elegibilidad y el mismo texto de motivo. No sustituyen al listado global.
    val duplicateConditionKeys = remember(current.conditions) {
        current.conditions.groupingBy { it.conditionKey }.eachCount()
            .filterValues { count -> count > 1 }
            .keys
    }
    val duplicateLoads = remember(current.conditions) {
        current.conditions.mapNotNull { condition ->
            condition.addedLoadKg?.takeIf { load -> load.isFinite() }
        }.groupingBy { load -> load }.eachCount()
            .filterValues { count -> count > 1 }
            .keys
    }
    // Anclas de desplazamiento: desplazar NO pide foco ni abre el teclado, y la accion solo actua
    // cuando el usuario la pulsa (nunca al componer ni al restaurar la instancia).
    val anchors = remember { FvpConditionAnchors() }
    val latestPending by rememberUpdatedState(firstPending)
    var reviewedAnchorKey by remember { mutableStateOf<String?>(null) }
    var reviewRequest by remember { mutableStateOf(0) }
    // ÚNICO editor activo, identificado por la identidad ESTABLE de la condicion (`draftKey`), no
    // por su indice visible: borrar una fila anterior no cambia quien esta abierto.
    var activeConditionDraftKey by rememberSaveable(current.sessionKey) { mutableStateOf<String?>(null) }
    // Al montar/restaurar: si no hay una activa valida se abre la PRIMERA (comportamiento previo de
    // la pantalla al entrar con condiciones ya declaradas). Tras un borrado, el manejador elige la
    // siguiente/la anterior de forma explicita; este efecto solo cubre el arranque y las
    // reconciliaciones externas (nunca pisa una activa que sigue existiendo).
    LaunchedEffect(current.sessionKey, current.conditions.map { it.draftKey }) {
        if (current.conditions.none { it.draftKey == activeConditionDraftKey }) {
            activeConditionDraftKey = current.conditions.firstOrNull()?.draftKey
        }
    }
    LaunchedEffect(reviewRequest) {
        if (reviewRequest == 0) return@LaunchedEffect
        val pending = latestPending ?: return@LaunchedEffect
        // Si el destino esta en una condicion INACTIVA, el clic ya cambio la activa ANTES de este
        // efecto; el ancla solo existe cuando su editor vuelve a componerse. Se esperan unos frames
        // a que aparezca (sin bucle infinito) y despues SOLO se desplaza: nunca se pide foco.
        var anchor = anchors.requesterFor(pending)
        var waited = 0
        while (anchor == null && waited < 4) {
            withFrameNanos { }
            anchor = anchors.requesterFor(pending)
            waited += 1
        }
        anchor?.bringIntoView()
    }

    LaunchedEffect(current.sessionKey) {
        if (current.conditions.isEmpty()) {
            mutations.update { live ->
                if (live == null || live.conditions.isNotEmpty()) live
                // La condicion inicial (y cada "Añadir condición") nace con UN intento vacio:
                // un toque menos por carga y sin callejon sin salida (UX A2).
                else live.copy(
                    conditions = listOf(
                        FvpConditionDraft(
                            conditionKey = defaultConditionKey(0.0),
                            attempts = listOf(FvpAttemptDraft()),
                        ),
                    ),
                )
            }
        }
    }

    // Back fisico y barra usan el callback de AppNav que conserva la entrada.
    BackHandler(onBack = onBack)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.fvp_step_saltos),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            ) {
                // Pie COMPACTO: un UNICO motivo (el primero del listado completo) y una accion
                // secundaria que desplaza al campo afectado. No sustituye al listado global, que
                // sigue completo y anunciado mas abajo; no cambia `canCalculate` ni muta nada.
                if (firstPending != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            text = localizedPendingReason(firstPending),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("fvp-conditions-first-reason"),
                        )
                        if (firstPending.hasReviewAnchor) {
                            TextButton(
                                onClick = {
                                    // Revisar una condicion INACTIVA la abre ANTES de desplazar:
                                    // el ancla solo se compone con su editor activo (misma
                                    // identidad `draftKey`, nunca por indice).
                                    firstPending.conditionDraftKey?.let { key ->
                                        activeConditionDraftKey = key
                                    }
                                    reviewedAnchorKey = firstPending.anchorKey
                                    reviewRequest += 1
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("fvp-conditions-review"),
                            ) {
                                Text(stringResource(R.string.fvp_setup_review_action))
                            }
                        }
                    }
                    if (identicalHeightsPending) {
                        // Orientacion cientifica: se revisan las mediciones ORIGINALES; nunca se
                        // sugiere tocar valores para conseguir un ajuste (que no existe).
                        Text(
                            stringResource(R.string.fvp_conditions_identical_heights_guidance),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("fvp-conditions-identical-heights-guidance"),
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                }
                OutlinedButton(
                    onClick = {
                        // Un toque menos por carga: la condicion nace con su primer intento y se
                        // ABRE como editor activo (la nueva carga es la que se va a rellenar).
                        val added = FvpConditionDraft(attempts = listOf(FvpAttemptDraft()))
                        activeConditionDraftKey = added.draftKey
                        mutations.update { live ->
                            live?.copy(conditions = live.conditions + added) ?: live
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fvp-conditions-add"),
                ) {
                    Text(stringResource(R.string.fvp_add_condition))
                }
                Spacer(Modifier.height(Spacing.sm))
                Button(
                    onClick = onCalculate,
                    enabled = canCalculate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("fvp-conditions-calculate"),
                ) {
                    Text(stringResource(R.string.fvp_calculate))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            FvpFlowProgress(currentStep = FvpFlowStep.CONDITIONS)
            // Misma cabecera COMPACTA que el paso 1: aviso breve experimental/no medicion directa
            // siempre visible + ayuda "Metodo y limites" plegada (textos completos ya existentes).
            // El estado del plegable es solo de UI: no toca valores, seleccion ni `canCalculate`.
            FvpCaptureHeader(testTag = "fvp-capture-header")
            // ORIGEN DE INTENTOS, compacto y ARRIBA: el contexto de adquisicion ya no vive en el
            // paso 1 (slice B). Declarar `SAME_ACQUISITION` sin clave bloquea el calculo AQUI,
            // junto al campo, sin tocar la formula de elegibilidad (UNKNOWN sigue siendo valido).
            FvpAcquisitionContextEditor(
                draft = current,
                mutations = mutations,
                anchors = anchors,
            )
            // Resumen COMPACTO de cada carga (estado de PRESENTACION, no calidad cientifica) + UN
            // editor activo identificado por `draftKey`. El `SaveableStateHolder` conserva el texto
            // parcial y la semilla de cada condicion/intento al cambiar de editor, borrar filas
            // anteriores o rotar: no se re-siembra ni se pierde lo tecleado.
            val saveableStateHolder = key(current.sessionKey) { rememberSaveableStateHolder() }
            Text(
                stringResource(R.string.fvp_loads_title),
                style = OpenJumpTypes.SectionTitle,
                modifier = Modifier.testTag("fvp-loads-title"),
            )
            current.conditions.forEachIndexed { index, condition ->
                val active = condition.draftKey == activeConditionDraftKey
                val hasBlocking = pendings.any { it.conditionDraftKey == condition.draftKey }
                // Conservar el ciclo de vida del editor y sus anclas al borrar una fila anterior.
                // Sin esta key, onDispose de la antigua posicion puede olvidar las anclas de la
                // condicion que acaba de moverse a esa posicion.
                key(condition.draftKey) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (active) {
                                        Modifier.testTag("fvp-load-active-$index")
                                    } else {
                                        Modifier
                                    },
                                ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            FvpConditionSummaryRow(
                                index = index,
                                condition = condition,
                                status = fvpConditionSummaryStatus(condition, hasBlocking),
                                active = active,
                                onOpen = { activeConditionDraftKey = condition.draftKey },
                            )
                            if (active) {
                                // El provider debe abandonar composicion con el editor, no con
                                // la fila-resumen; asi guarda tambien texto que no parsea.
                                saveableStateHolder.SaveableStateProvider(condition.draftKey) {
                                FvpConditionEditor(
                                    index = index,
                                    condition = condition,
                                    pushOffPerCondition = current.pushOffMode == FvpPushOffUiMode.PER_CONDITION,
                                    athleteId = current.athleteId,
                                    mutations = mutations,
                                    viewModel = viewModel,
                                    anchors = anchors,
                                    reviewedAnchorKey = reviewedAnchorKey,
                                    duplicateConditionKeys = duplicateConditionKeys,
                                    duplicateLoads = duplicateLoads,
                                    onRemove = {
                                        saveableStateHolder.removeState(condition.draftKey)
                                        // Borrar el editor ACTIVO elige de forma HONESTA la carga
                                        // siguiente en la misma posicion; si no queda ninguna, la
                                        // anterior. Borrar otra fila no cambia la activa.
                                        if (activeConditionDraftKey == condition.draftKey) {
                                            val remaining = current.conditions
                                                .filterNot { it.draftKey == condition.draftKey }
                                            activeConditionDraftKey = remaining.getOrNull(index)?.draftKey
                                                ?: remaining.lastOrNull()?.draftKey
                                        }
                                        mutations.update { live ->
                                            live?.copy(
                                                conditions = live.conditions.filterNot {
                                                    it.draftKey == condition.draftKey
                                                },
                                            ) ?: live
                                        }
                                    },
                                )
                                }
                            }
                        }
                }
            }
            // Recomendaciones ANTES de calcular, despues de la lista: informativas (no bloquean por
            // si solas) y sin convertirse en un muro al entrar. El minimo DEl dominio no cambia.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    stringResource(R.string.fvp_condition_count_hint),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-conditions-count-hint"),
                )
                Text(
                    stringResource(R.string.fvp_attempt_count_hint),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-attempts-count-hint"),
                )
            }
            // El listado GLOBAL de bloqueos se explica DESPUES de los editores: en la primera
            // carga el input es el protagonista y el diagnostico completo queda en el scroll (no
            // se oculta ni se simplifica). Sigue anunciado y `canCalculate` no cambia.
            if (!canCalculate) {
                InfoBanner(
                    title = stringResource(R.string.fvp_cannot_compute),
                    body = localizedPendingReasons(pendings),
                    warning = true,
                    // Muse P1-6: el bloqueo no puede comunicarse solo por color/texto visual.
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("fvp-conditions-blocking"),
                )
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

/**
 * Fila COMPACTA del resumen de una carga: titulo, clave declarada y estado de PRESENTACION
 * (`pending`/`registered`/`review`, en texto, nunca solo color). Pulsarla abre SU editor (la
 * identidad es `draftKey`; el indice solo etiqueta la fila para pruebas y lectores de pantalla).
 *
 * El estado no es una calificacion cientifica: "review" significa unicamente que hay un motivo
 * bloqueante cuyo destino es ESTA condicion, y "registered" que ya hay un intento valido con
 * altura utilizable. La elegibilidad no se toca.
 */
@Composable
private fun FvpConditionSummaryRow(
    index: Int,
    condition: FvpConditionDraft,
    status: FvpConditionSummaryStatus,
    active: Boolean,
    onOpen: () -> Unit,
) {
    val statusRes = when (status) {
        FvpConditionSummaryStatus.PENDING -> R.string.common_pending
        FvpConditionSummaryStatus.REGISTERED -> R.string.fvp_load_status_registered
        FvpConditionSummaryStatus.REVIEW -> R.string.fvp_load_status_review
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.xxxl)
            .semantics { selected = active }
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag("fvp-load-row-$index"),
        shape = ShapeTokens.medium,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    stringResource(R.string.fvp_load_number, index + 1),
                    style = OpenJumpTypes.Label,
                )
                Text(
                    condition.conditionKey.ifBlank { stringResource(R.string.common_value_unavailable) },
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-load-key-$index"),
                )
            }
            Text(
                stringResource(statusRes),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-load-status-$index"),
            )
            Text(
                stringResource(R.string.fvp_load_open),
                style = OpenJumpTypes.Label,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun FvpConditionEditor(
    index: Int,
    condition: FvpConditionDraft,
    pushOffPerCondition: Boolean,
    athleteId: Long,
    mutations: FvpDraftMutations,
    viewModel: FvpViewModel,
    anchors: FvpConditionAnchors,
    reviewedAnchorKey: String?,
    duplicateConditionKeys: Set<String>,
    duplicateLoads: Set<Double>,
    onRemove: () -> Unit,
) {
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    // Las anclas de esta condicion (y de sus intentos) se olvidan al desaparecer el editor: un
    // destino borrado no queda vivo para una pulsacion posterior.
    DisposableEffect(condition.draftKey) {
        onDispose { anchors.forget(condition.draftKey) }
    }
    // Un campo virgen vacio es un dato pendiente, no un error de escritura. Los mismos motivos
    // siguen bloqueando; el error local aparece tras editar o al pulsar Revisar para ese campo.
    var keyTouched by rememberSaveable(condition.draftKey) { mutableStateOf(false) }
    var loadTouched by rememberSaveable(condition.draftKey) { mutableStateOf(false) }
    var pushOffTouched by rememberSaveable(condition.draftKey) { mutableStateOf(false) }
    val keyShowError = keyTouched || condition.conditionKey.isNotBlank() ||
        reviewedAnchorKey == fvpConditionAnchorKey(condition.draftKey, "key")
    val loadShowError = loadTouched || condition.addedLoadKg != null ||
        reviewedAnchorKey == fvpConditionAnchorKey(condition.draftKey, "load")
    val pushOffShowError = pushOffTouched || condition.pushOffM != null ||
        reviewedAnchorKey == fvpConditionAnchorKey(condition.draftKey, "pushoff")
    val keyBlank = condition.conditionKey.isBlank()
    val keyDuplicated = condition.conditionKey in duplicateConditionKeys
    val loadInvalid = !fvpAddedLoadUsable(condition.addedLoadKg)
    val loadDuplicated = condition.addedLoadKg?.let { it in duplicateLoads } == true
    val pushOffMissing = condition.pushOffM == null
    val pushOffInvalid = condition.pushOffM != null && !fvpPushOffUsable(condition.pushOffM)
    val loadText = rememberFvpQuantityText(condition.draftKey to "load", condition.addedLoadKg, MeasurementQuantity.MASS_KG)
    // h_po se teclea en la unidad de LONGITUD CORTA del perfil (cm/in), no en la de distancia
    // horizontal (ft en EE. UU.); el canonico guardado sigue siendo metros (`parsePushOffInput`).
    val pushOffText = rememberFvpQuantityText(
        condition.draftKey to "pushoff",
        condition.pushOffM?.times(CENTIMETERS_PER_METER),
        MeasurementQuantity.SHORT_LENGTH_CM,
        seedValue = condition.pushOffM,
    )
    val validHeights = condition.attempts
        .filter { it.valid }
        .mapNotNull { it.heightM }
        .filter { it.isFinite() && it > 0.0 }
    val thirdAttemptHint = validHeights.size == 2 && needsThirdAttempt(validHeights[0], validHeights[1])

    Column(
        modifier = Modifier.fillMaxWidth().testTag("fvp-condition-$index"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FvpSectionTitle(stringResource(R.string.fvp_load_number, index + 1))
            TextButton(
                onClick = onRemove,
                modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-condition-remove-$index"),
            ) {
                Text(stringResource(R.string.fvp_remove_condition))
            }
        }
        androidx.compose.material3.TextField(
            shape = ShapeTokens.medium,
            colors = fvpInputColors(),
            value = condition.conditionKey,
            onValueChange = { text ->
                keyTouched = true
                mutations.update { live ->
                    live?.copy(
                        conditions = live.conditions.map {
                            if (it.draftKey == condition.draftKey) it.copy(conditionKey = text) else it
                        },
                    ) ?: live
                }
            },
            label = { Text(stringResource(R.string.fvp_condition_key)) },
            isError = keyShowError && (keyBlank || keyDuplicated),
            supportingText = when {
                !keyShowError -> null
                keyBlank -> {
                    { Text(stringResource(R.string.fvp_block_invalid_condition_key)) }
                }
                keyDuplicated -> {
                    { Text(stringResource(R.string.fvp_block_duplicate_condition_key)) }
                }
                else -> null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("fvp-condition-key-$index")
                .bringIntoViewRequester(
                    anchors.requester(fvpConditionAnchorKey(condition.draftKey, "key")),
                ),
        )
        FvpQuantityField(
            quantity = MeasurementQuantity.MASS_KG,
            value = loadText.text.value,
            onValueChange = { text ->
                loadTouched = true
                loadText.text.value = text
                // Campo sin editar (o devuelto a su texto de semilla): se conserva la carga EXACTA
                // del borrador, para que la clave por defecto ("20kg") no derive con el redondeo de
                // la forma visible (176,3698 lb -> 19,99999... kg).
                val parsedLoad = resolveSeededMassKg(
                    text,
                    loadText.seedText.value,
                    loadText.seedValue.value,
                    unitProfile,
                    locale,
                )
                // B4a-2: la mutacion va por el helper del borrador, que conserva la clave por
                // defecto y RETIRA las confirmaciones afectadas si la carga cambia de verdad.
                mutations.update { live ->
                    live?.withConditionLoad(condition.draftKey, parsedLoad) ?: live
                }
            },
            label = stringResource(R.string.fvp_added_load_kg),
            testTag = "fvp-condition-load-$index",
            isError = loadShowError && (loadInvalid || loadDuplicated),
            supportingText = when {
                !loadShowError -> null
                loadInvalid -> stringResource(R.string.fvp_block_invalid_added_load)
                loadDuplicated -> stringResource(R.string.fvp_block_duplicate_load)
                else -> null
            },
            modifier = Modifier.bringIntoViewRequester(
                anchors.requester(fvpConditionAnchorKey(condition.draftKey, "load")),
            ),
        )
        // B4a-2: procedencia DECLARADA de la carga de ESTA condicion, junto a la carga anadida.
        // `UNKNOWN` por defecto: el valor de la carga no la promueve (contrato 18 §2).
        FvpConditionLoadSourceRow(
            index = index,
            // Se resuelve por el vocabulario de almacenamiento (mismo patron que el paso 1): una
            // clave desconocida se muestra como `UNKNOWN` en vez de dejar la fila sin seleccion.
            selectedKey = FvpAddedLoadSourceUi.fromStorageKey(condition.addedLoadSource)?.storageKey
                ?: FvpAddedLoadSourceUi.UNKNOWN.storageKey,
            onSelect = { sourceKey ->
                mutations.update { live ->
                    live?.withConditionAddedLoadSource(condition.draftKey, sourceKey) ?: live
                }
            },
        )
        if (pushOffPerCondition) {
            FvpQuantityField(
                quantity = MeasurementQuantity.SHORT_LENGTH_CM,
                value = pushOffText.text.value,
                onValueChange = { text ->
                    pushOffTouched = true
                    pushOffText.text.value = text
                    val parsedPushOff = resolveSeededPushOffM(
                        text,
                        pushOffText.seedText.value,
                        pushOffText.seedValue.value,
                        unitProfile,
                        locale,
                    )
                    mutations.update { live ->
                        live?.withConditionPushOffM(condition.draftKey, parsedPushOff) ?: live
                    }
                },
                label = stringResource(R.string.fvp_push_off),
                testTag = "fvp-condition-pushoff-$index",
                isError = pushOffShowError && (pushOffMissing || pushOffInvalid),
                supportingText = when {
                    !pushOffShowError -> null
                    pushOffMissing -> stringResource(R.string.fvp_block_missing_push_off)
                    pushOffInvalid -> stringResource(
                        R.string.fvp_block_invalid_push_off,
                        pushOffDomainRangeText(unitProfile, locale),
                    )
                    else -> null
                },
                modifier = Modifier.bringIntoViewRequester(
                    anchors.requester(fvpConditionAnchorKey(condition.draftKey, "pushoff")),
                ),
            )
        }
        if (thirdAttemptHint) {
            InfoBanner(
                title = stringResource(R.string.fvp_third_attempt_hint),
                body = "",
                warning = true,
            )
        }
        Text(
            stringResource(R.string.fvp_attempts),
            style = OpenJumpTypes.SectionTitle,
            modifier = Modifier.bringIntoViewRequester(
                anchors.requester(fvpConditionAnchorKey(condition.draftKey, "attempts")),
            ),
        )
        condition.attempts.forEachIndexed { attemptIndex, attempt ->
            key(attempt.draftKey) {
                FvpAttemptEditor(
                    conditionDraftKey = condition.draftKey,
                    attempt = attempt,
                    attemptIndex = attemptIndex,
                    athleteId = athleteId,
                    mutations = mutations,
                    viewModel = viewModel,
                    conditionIndex = index,
                    anchors = anchors,
                    reviewedAnchorKey = reviewedAnchorKey,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        OutlinedButton(
            onClick = {
                mutations.update { live ->
                    live?.copy(
                        conditions = live.conditions.map {
                            if (it.draftKey == condition.draftKey) {
                                it.copy(attempts = it.attempts + FvpAttemptDraft())
                            } else {
                                it
                            }
                        },
                    ) ?: live
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.fvp_add_attempt))
        }
    }
}

@Composable
private fun FvpAttemptEditor(
    conditionDraftKey: String,
    attempt: FvpAttemptDraft,
    attemptIndex: Int,
    athleteId: Long,
    mutations: FvpDraftMutations,
    viewModel: FvpViewModel,
    /** Indice de la condicion en la pantalla: identifica la fila en los `testTag` de UI. */
    conditionIndex: Int,
    anchors: FvpConditionAnchors,
    reviewedAnchorKey: String?,
) {
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    // La altura se valida en el MISMO dominio para TODOS los intentos, tambien los excluidos
    // (`INVALID_HEIGHT` cubre altura ausente/fuera de rango de cualquier intento).
    val heightInvalid = !fvpHeightUsable(attempt.heightM)
    var heightTouched by rememberSaveable(attempt.draftKey) { mutableStateOf(false) }
    val heightShowError = heightTouched || attempt.heightM != null ||
        reviewedAnchorKey == fvpAttemptAnchorKey(conditionDraftKey, attempt.draftKey, "height")
    val heightText = rememberFvpQuantityText(
        attempt.draftKey to "height",
        attempt.heightM?.times(CENTIMETERS_PER_METER),
        MeasurementQuantity.SHORT_LENGTH_CM,
        seedValue = attempt.heightM,
    )
    var reuseSheetOpen by rememberSaveable(attempt.draftKey) { mutableStateOf(false) }
    val quality = fvpTimingQuality(attempt.sourceFps)
    // El selector de saltos guardados ESCRIBE la altura en el borrador: el campo de texto debe
    // reflejarla sin pisar lo que el usuario esta tecleando (solo cuando difieren de verdad). El
    // re-sembrado actualiza TAMBIEN la semilla, para que el texto nuevo resuelva el valor exacto.
    LaunchedEffect(attempt.heightM) {
        val reseed = reconcileHeightField(
            heightText.text.value,
            heightText.seedText.value,
            heightText.seedValue.value,
            attempt.heightM,
            unitProfile,
            locale,
        ) ?: return@LaunchedEffect
        heightText.reseed(reseed.text, reseed.canonicalValue)
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${attemptIndex + 1}", style = OpenJumpTypes.SectionTitle)
            TextButton(
                onClick = {
                    mutations.update { live ->
                        live?.copy(
                            conditions = live.conditions.map { condition ->
                                if (condition.draftKey != conditionDraftKey) {
                                    condition
                                } else {
                                    condition.copy(
                                        attempts = condition.attempts.filterNot { it.draftKey == attempt.draftKey },
                                    )
                                }
                            },
                        ) ?: live
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-attempt-remove-$attemptIndex"),
            ) {
                // Muse P1-2: el intento tiene su PROPIA etiqueta destructiva ("Quitar intento");
                // antes reutilizaba "Quitar condición" y el control mentía sobre lo que borraba.
                Text(stringResource(R.string.fvp_remove_attempt))
            }
        }
        FvpQuantityField(
            quantity = MeasurementQuantity.SHORT_LENGTH_CM,
            value = heightText.text.value,
            onValueChange = { text ->
                heightTouched = true
                heightText.text.value = text
                val parsedHeightM = resolveSeededHeightM(
                    text,
                    heightText.seedText.value,
                    heightText.seedValue.value,
                    unitProfile,
                    locale,
                )
                // B4a-2: cambiar la altura RETIRA la confirmacion de ESE intento en la misma
                // mutacion (la declaracion cubre la altura reutilizada).
                mutations.update { live ->
                    live?.withAttemptHeight(conditionDraftKey, attempt.draftKey, parsedHeightM) ?: live
                }
            },
            label = stringResource(R.string.fvp_height_cm),
            testTag = "fvp-attempt-height-$attemptIndex",
            isError = heightShowError && heightInvalid,
            supportingText = if (heightShowError && heightInvalid) {
                stringResource(R.string.fvp_block_invalid_height, jumpHeightRangeText(unitProfile, locale))
            } else {
                null
            },
            modifier = Modifier.bringIntoViewRequester(
                anchors.requester(fvpAttemptAnchorKey(conditionDraftKey, attempt.draftKey, "height")),
            ),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FvpMeasureMethod.entries.forEach { method ->
                FilterChip(
                    colors = fvpSelectionColors(),
                    selected = attempt.method == method,
                    onClick = {
                        mutations.update { live ->
                            live?.withAttemptMethod(conditionDraftKey, attempt.draftKey, method) ?: live
                        }
                    },
                    label = { Text(stringResource(method.labelResource())) },
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                )
            }
        }
        val validityLabel = stringResource(R.string.fvp_attempt_valid)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = Spacing.sm)) {
                Text(
                    stringResource(if (attempt.valid) R.string.fvp_attempt_valid else R.string.fvp_attempt_invalid),
                    style = OpenJumpTypes.Label,
                )
                // Muse P1-6/A2: la calidad temporal se muestra SIEMPRE. Con fps desconocidos la
                // linea neutral `fvp_quality_timing_unknown` evita que un intento tecleado a mano
                // quede sin etiquetar hasta la pantalla de resultado.
                Text(
                    if (attempt.sourceFps != null) {
                        String.format(locale, "%.1f", attempt.sourceFps) +
                            " fps · " + stringResource(quality.labelResource())
                    } else {
                        stringResource(R.string.fvp_quality_timing_unknown)
                    },
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("fvp-attempt-valid-$attemptIndex")
                    .semantics { contentDescription = validityLabel },
                checked = attempt.valid,
                onCheckedChange = { valid ->
                    updateAttempt(mutations, conditionDraftKey, attempt.draftKey) {
                        it.copy(valid = valid, exclusionReason = if (valid) null else it.exclusionReason)
                    }
                },
            )
        }
        if (!attempt.valid) {
            val exclusionMissing = attempt.exclusionReason.isNullOrBlank()
            val exclusionError: (@Composable () -> Unit)? = if (exclusionMissing) {
                { Text(stringResource(R.string.fvp_exclusion_reason_required)) }
            } else {
                null
            }
            androidx.compose.material3.TextField(
                shape = ShapeTokens.medium,
                colors = fvpInputColors(),
                value = attempt.exclusionReason.orEmpty(),
                onValueChange = { text ->
                    updateAttempt(mutations, conditionDraftKey, attempt.draftKey) {
                        it.copy(exclusionReason = text.ifBlank { null })
                    }
                },
                label = { Text(stringResource(R.string.fvp_exclusion_reason)) },
                isError = exclusionMissing,
                supportingText = exclusionError,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .bringIntoViewRequester(
                        anchors.requester(fvpAttemptAnchorKey(conditionDraftKey, attempt.draftKey, "reason")),
                    )
                    .testTag("fvp-attempt-exclusion-reason"),
            )
        }
        TextButton(
            onClick = { reuseSheetOpen = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.fvp_reuse_measurement))
        }
        // B4a-2: la confirmacion de reutilizacion es DURABLE, asi que se DECLARA en el intento (con
        // su fecha, que es la del evento de declaracion) y no desaparece al cerrar el selector. Si
        // alguna mutacion de la pantalla la retira, la linea desaparece en la misma recomposicion.
        val reuseConfirmedAt = attempt.reuseConfirmedAt
        if (reuseConfirmedAt != null) {
            Text(
                stringResource(R.string.fvp_reuse_confirmed_at, Formatting.dateTimeToText(reuseConfirmedAt)),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-reuse-confirmed-$conditionIndex-$attemptIndex"),
            )
        }
    }

    if (reuseSheetOpen) {
        FvpReusableMeasurementSheet(
            athleteId = athleteId,
            viewModel = viewModel,
            onDismiss = { reuseSheetOpen = false },
            onSelected = { measurement, confirmedAtMillis ->
                // Solo altura + tiempo de vuelo + metodo + fps/origen: la carga y el h_po siguen
                // siendo declarados por el usuario (nada de valores por defecto inventados). El
                // mapeo es puro (helper `withReusedMeasurement`, cubierto por tests JVM) y la
                // confirmacion del checklist queda DURABLE con la MISMA mutacion (`reuseConfirmedAt`
                // = instante real de la declaracion + `reuseConfirmationVersion`), sin inventar
                // PTS ni mapeo temporal: la fuente no los aporta y quedan `UNKNOWN`/NULL.
                mutations.update { live ->
                    live?.withAttemptConfirmedReuse(
                        conditionDraftKey = conditionDraftKey,
                        attemptDraftKey = attempt.draftKey,
                        measurement = measurement,
                        confirmedAtMillis = confirmedAtMillis,
                    ) ?: live
                }
                // Los fps de origen de la SERIE se derivan de los intentos seleccionados
                // (`seriesSourceFps()`): no se escribe ningun campo de serie aqui (P1-2/P2).
                reuseSheetOpen = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FvpReusableMeasurementSheet(
    athleteId: Long,
    viewModel: FvpViewModel,
    onDismiss: () -> Unit,
    onSelected: (ReusableJumpMeasurement, Long) -> Unit,
) {
    var measurements by remember { mutableStateOf<List<ReusableJumpMeasurement>?>(null) }
    LaunchedEffect(athleteId) {
        measurements = runCatching { viewModel.reusableMeasurements(athleteId) }.getOrDefault(emptyList())
    }
    // B5: la confirmacion explicita vive SOLO mientras el selector esta abierto y nace en "nada
    // confirmado" (D7: la evidencia que falta no se asume). `rowKey` es la fila confirmada: al
    // confirmar otra medicion, la anterior deja de estar confirmada (la declaracion no se hereda).
    var confirmation by remember(athleteId) { mutableStateOf(FvpReuseConfirmation()) }
    // Edad de las mediciones: un unico `now` estable mientras el selector esta abierto (no avanza
    // entre recomposiciones ni cambia fila a fila).
    val now = remember { System.currentTimeMillis() }
    val locale = currentAppLocale()
    val unitProfile = LocalUnitSystem.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.fvp_reuse_measurement), style = OpenJumpTypes.SectionTitle)
            InfoBanner(
                title = stringResource(R.string.fvp_capture_notice),
                // Muse P2-9: el cuerpo describe QUE se reutiliza (solo altura + fps de origen);
                // antes reutilizaba una nota de procedencia de la masa que no venia al caso.
                body = stringResource(R.string.fvp_reuse_measurement_notice),
            )
            // B5: por que se exige la declaracion (una medicion guardada es de OTRA sesion: su
            // fecha y su calidad temporal condicionan la comparacion).
            Text(
                stringResource(R.string.fvp_reuse_confirmation_hint),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-reuse-confirmation-hint"),
            )
            val loaded = measurements
            when {
                loaded == null -> CircularProgressIndicator(Modifier.padding(Spacing.lg))
                loaded.isEmpty() -> Text(
                    stringResource(R.string.history_empty_title) + ": " + stringResource(R.string.history_empty_body),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.lg),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = Spacing.xs),
                ) {
                    items(loaded, key = { "${it.assessmentId}-${it.attemptOrdinal}" }) { measurement ->
                        val rowKey = "${measurement.assessmentId}-${measurement.attemptOrdinal}"
                        // Solo la FILA confirmada puede reutilizarse: la casilla de otra medicion no
                        // habilita este boton ni hereda la confirmacion anterior.
                        val confirmed = confirmation.allows(rowKey)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(vertical = Spacing.xs)
                                .testTag("fvp-reuse-$rowKey"),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    MeasurementFormatting.format(
                                        measurement.heightM * 100.0,
                                        MeasurementQuantity.SHORT_LENGTH_CM,
                                        unitProfile,
                                        locale,
                                    ),
                                )
                                TextButton(
                                    onClick = {
                                        // La declaracion es de ESA fila: sin ella el boton ya
                                        // estaba deshabilitado y aqui no se confirma nada por
                                        // omision (D7, la evidencia que falta no se asume). El
                                        // instante pasa a la capa de datos como `reuseConfirmedAt`.
                                        if (confirmed) onSelected(measurement, System.currentTimeMillis())
                                    },
                                    // Gate B5: sin la declaracion explicita de ESTA fila el boton
                                    // queda deshabilitado; no hay confirmacion por omision.
                                    enabled = confirmed,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("fvp-reuse-apply-$rowKey"),
                                ) {
                                    Text(stringResource(R.string.fvp_reuse_measurement))
                                }
                            }
                            // Contexto real de la medicion guardada: fecha Y antiguedad declaradas
                            // (nunca inferidas ni silenciadas).
                            Text(
                                Formatting.dateTimeToText(measurement.dateTime) +
                                    " · " + reuseAgeCaption(reuseAgeInDays(measurement.dateTime, now)),
                                style = OpenJumpTypes.Secondary,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("fvp-reuse-date-$rowKey"),
                            )
                            val reuseTimingQuality = fvpTimingQuality(
                                measurement.detectedFps.takeIf { fps -> fps > 0 }?.toDouble(),
                            )
                            Text(
                                // Los fps se muestran SOLO cuando son validos (> 0): un 0 de
                                // origen se lee como 'desconocido' y no como '0 fps', igual que
                                // en la linea del intento tras reutilizar el salto.
                                (measurement.detectedFps.takeIf { fps -> fps > 0 }?.let { "$it fps · " } ?: "") +
                                    stringResource(reuseTimingQuality.labelResource()) +
                                    (measurement.side?.let { " · $it" } ?: ""),
                                style = OpenJumpTypes.Secondary,
                                modifier = Modifier.testTag("fvp-reuse-timing-$rowKey"),
                            )
                            // Transparencia: sin tiempo de vuelo UTILIZABLE no se copiaria ninguno
                            // y el intento se quedaria sin el que ya tuviera escrito. La nota usa
                            // la MISMA decision que la escritura ([reusableFlightTimeS]), asi que
                            // aparece exactamente cuando de verdad no se lleva nada.
                            if (measurement.reusableFlightTimeS() == null) {
                                Text(
                                    stringResource(R.string.fvp_reuse_no_flight_time),
                                    style = OpenJumpTypes.Secondary,
                                    color = OpenJumpTheme.colors.warning,
                                    modifier = Modifier.testTag("fvp-reuse-no-flight-time-$rowKey"),
                                )
                            }
                            // Declaracion del usuario (B5): tecnica, carga y h_po comparables con la
                            // serie, y calidad temporal aceptable. El objetivo tactil es la FILA
                            // entera (>= 48 dp), no solo el cuadro de la casilla.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .toggleable(
                                        value = confirmed,
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            confirmation = confirmation.withCheck(rowKey, checked)
                                        },
                                    )
                                    .testTag("fvp-reuse-confirm-$rowKey"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                Checkbox(checked = confirmed, onCheckedChange = null)
                                Text(
                                    stringResource(R.string.fvp_reuse_confirmation),
                                    style = OpenJumpTypes.Secondary,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

@Composable
private fun FvpExpiredDraftBody(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Gate cientifico: la etiqueta experimental es persistente TAMBIEN en esta rama (la ruta
        // muestra copy del modulo F-V-P aunque no haya flujo vivo), igual que en el resto del flujo.
        StatusPill(
            text = stringResource(R.string.fvp_experimental_label),
            containerColor = OpenJumpTheme.colors.warningContainer,
            contentColor = OpenJumpTheme.colors.warning,
        )
        Text(stringResource(R.string.session_expired_title), style = OpenJumpTypes.ScreenTitle)
        Text(
            stringResource(R.string.fvp_expired_session_body),
            style = OpenJumpTypes.Secondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.common_back))
        }
    }
}

private fun updateAttempt(
    mutations: FvpDraftMutations,
    conditionDraftKey: String,
    attemptDraftKey: String,
    transform: (FvpAttemptDraft) -> FvpAttemptDraft,
) {
    // Se conserva SOLO para las ediciones que no forman parte de la aceptacion de reutilizacion
    // (validez y motivo de exclusion): las que si lo son (altura, metodo, fuente reutilizada) pasan
    // por los helpers del borrador, que retiran las confirmaciones afectadas en la misma mutacion.
    mutations.update { live ->
        live?.copy(
            conditions = live.conditions.map { condition ->
                if (condition.draftKey != conditionDraftKey) {
                    condition
                } else {
                    condition.copy(
                        attempts = condition.attempts.map {
                            if (it.draftKey == attemptDraftKey) transform(it) else it
                        },
                    )
                }
            },
        ) ?: live
    }
}

/**
 * Procedencia DECLARADA de la carga anadida (B4a-2): vocabulario EXACTO de
 * `fvp_conditions.addedLoadSource` (contrato 18 §2). `UNKNOWN` es el default y NUNCA se promueve por
 * existir un valor numerico de carga, por el perfil del atleta ni por los fps de los intentos.
 */
internal enum class FvpAddedLoadSourceUi(val storageKey: String) {
    UNKNOWN("UNKNOWN"),
    WEIGHED("WEIGHED"),
    NOMINAL("NOMINAL"),
    ;

    companion object {
        /** Clave de almacenamiento conocida, o `null` si no pertenece al vocabulario. */
        fun fromStorageKey(key: String?): FvpAddedLoadSourceUi? =
            entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Fila de chips de la procedencia de la carga de UNA condicion. Cada opcion expone su clave de
 * almacenamiento en el `testTag` (`fvp-condition-load-source-<indice>-<CLAVE>`) y su etiqueta
 * LOCALIZADA en el texto, nunca el nombre del enum en crudo. Se envuelve con `FlowRow` y respeta el
 * minimo tactil de 48 dp; la opcion elegida se declara tambien en semantica (no solo en color).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FvpConditionLoadSourceRow(
    index: Int,
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        // Pregunta humana (plan slice B): la procedencia es una DECLARACION, no algo que se deduzca
        // del valor de la carga.
        Text(
            stringResource(R.string.fvp_added_load_source_question),
            style = OpenJumpTypes.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            listOf(
                FvpAddedLoadSourceUi.UNKNOWN.storageKey to stringResource(R.string.fvp_added_load_source_unknown),
                FvpAddedLoadSourceUi.WEIGHED.storageKey to stringResource(R.string.fvp_added_load_source_weighed),
                FvpAddedLoadSourceUi.NOMINAL.storageKey to stringResource(R.string.fvp_added_load_source_nominal),
            ).forEach { (key, optionLabel) ->
                FilterChip(
                    colors = fvpSelectionColors(),
                    selected = key == selectedKey,
                    onClick = { onSelect(key) },
                    label = { Text(optionLabel) },
                    modifier = Modifier
                        .heightIn(min = Spacing.xxxl)
                        .testTag("fvp-condition-load-source-$index-$key")
                        .semantics { selected = key == selectedKey },
                )
            }
        }
        Text(
            stringResource(R.string.fvp_added_load_source_help),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("fvp-condition-load-source-help-$index"),
        )
        // UNKNOWN es una respuesta VALIDA: el valor de la carga no la promueve ni la invalida.
        Text(
            stringResource(R.string.fvp_added_load_source_example),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("fvp-condition-load-source-example-$index"),
        )
    }
}

/**
 * Estado de texto de un campo numerico F-V-P: se re-inicializa desde el valor canonico del
 * borrador cuando cambia la identidad del campo o el perfil de unidades (nunca reinterpreta texto
 * viejo con una base de unidades nueva).
 *
 * [seedText]/[seedValue] son el texto y el canonico ALMACENADO con los que se sembro el campo
 * (altura y h_po por condicion se teclean en longitud corta y se guardan en metros): mientras el
 * texto no se edite, `resolveSeeded*` devuelve [seedValue] EXACTO, nunca el re-parseo de la forma
 * redondeada visible.
 */
private class FvpQuantityTextField(
    val text: MutableState<String>,
    val seedText: MutableState<String>,
    val seedValue: MutableState<Double?>,
) {
    /** Re-siembra texto y semilla con una escritura que viene del borrador, no del teclado. */
    fun reseed(newText: String, newValue: Double?) {
        text.value = newText
        seedText.value = newText
        seedValue.value = newValue
    }
}

@Composable
private fun rememberFvpQuantityText(
    key: Any,
    canonicalValue: Double?,
    quantity: MeasurementQuantity,
    seedValue: Double? = canonicalValue,
): FvpQuantityTextField {
    val profile = LocalUnitSystem.current
    val locale = currentAppLocale()
    // Mismas claves de `rememberSaveable` para texto y semilla: se sincronizan SIEMPRE. El texto
    // inicial se formatea DENTRO de los `rememberSaveable`: solo cuando el campo se inicializa
    // (primera composicion) o se re-inicializa porque cambian las claves (perfil de unidades,
    // identidad del campo, restauracion del estado guardado), nunca en cada recomposicion.
    val inputs = arrayOf(
        key,
        quantity,
        profile.shortLength.id,
        profile.horizontalDistance.id,
        profile.mass.id,
    )
    val text = rememberSaveable(*inputs) {
        mutableStateOf(formatCanonical(canonicalValue, quantity, profile, locale))
    }
    val seedText = rememberSaveable(*inputs) {
        mutableStateOf(formatCanonical(canonicalValue, quantity, profile, locale))
    }
    val seed = rememberSaveable(*inputs) { mutableStateOf(seedValue) }
    return FvpQuantityTextField(text, seedText, seed)
}

/**
 * Motivos BLOQUEANTES localizados para el aviso "no se puede calcular" (P1-3).
 *
 * Nunca se muestran los nombres crudos del enum (`MISSING_PUSH_OFF`, ...): cada problema tiene su
 * clave `fvp_block_*` en los 8 locales. La altura inutilizable y el motivo de exclusion de un
 * intento NO valido se etiquetan aparte porque el bloqueo se detecta en el borrador (campos vacios)
 * antes de llegar a la elegibilidad.
 */
@Composable
private fun localizedPendingReason(pending: FvpConditionPending): String {
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    // Motivo UI-only: declarar SAME_ACQUISITION sin clave se bloquea en ESTA pantalla (no es una
    // regla nueva de elegibilidad). Se muestra el texto EXACTO ya existente en los 8 locales.
    if (pending.contextKeyMissing) {
        return stringResource(R.string.fvp_acquisition_context_key_required)
    }
    // Los limites se escriben en la unidad del CAMPO correspondiente (cm/in), no en metros.
    return when (pending.problem) {
        FvpEligibilityProblem.INVALID_PUSH_OFF ->
            stringResource(pending.problem.labelResource(), pushOffDomainRangeText(unitProfile, locale))
        FvpEligibilityProblem.INVALID_HEIGHT ->
            stringResource(pending.problem.labelResource(), jumpHeightRangeText(unitProfile, locale))
        null -> stringResource(R.string.fvp_cannot_compute)
        else -> stringResource(pending.problem.labelResource())
    }
}

/**
 * Texto COMPLETO del listado global de bloqueos, en el MISMO orden que [fvpConditionPendings].
 * La primera entrada es, por construccion, el motivo exacto que muestra el pie.
 */
@Composable
private fun localizedPendingReasons(pendings: List<FvpConditionPending>): String =
    pendings.map { localizedPendingReason(it) }.joinToString(", ")

internal fun FvpEligibilityProblem.labelResource(): Int = when (this) {
    FvpEligibilityProblem.INVALID_BODY_MASS -> R.string.fvp_block_invalid_body_mass
    FvpEligibilityProblem.TOO_FEW_CONDITIONS -> R.string.fvp_block_too_few_conditions
    FvpEligibilityProblem.INVALID_CONDITION_KEY -> R.string.fvp_block_invalid_condition_key
    FvpEligibilityProblem.DUPLICATE_CONDITION_KEY -> R.string.fvp_block_duplicate_condition_key
    FvpEligibilityProblem.DUPLICATE_LOAD -> R.string.fvp_block_duplicate_load
    FvpEligibilityProblem.INVALID_ADDED_LOAD -> R.string.fvp_block_invalid_added_load
    FvpEligibilityProblem.MISSING_PUSH_OFF -> R.string.fvp_block_missing_push_off
    FvpEligibilityProblem.INVALID_PUSH_OFF -> R.string.fvp_block_invalid_push_off
    FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT -> R.string.fvp_block_condition_without_valid_attempt
    FvpEligibilityProblem.INVALID_HEIGHT -> R.string.fvp_block_invalid_height
    FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS -> R.string.fvp_block_identical_heights
    // Gate B3 en el dominio: el motivo obligatorio de un intento NO valido ya se explica en el
    // campo del intento; se reutiliza el MISMO texto (no se crea copia nueva) para no duplicarlo.
    FvpEligibilityProblem.EXCLUSION_REASON_MISSING -> R.string.fvp_exclusion_reason_required
}

/** Clave por defecto de una condicion: "<carga>kg"; vacia si la carga aun no es valida. */
internal fun defaultConditionKey(loadKg: Double?): String {
    if (loadKg == null || !loadKg.isFinite() || loadKg < 0.0) return ""
    val plain = BigDecimal.valueOf(loadKg).stripTrailingZeros().toPlainString()
    return "${plain}kg"
}

private fun FvpMeasureMethod.labelResource(): Int = when (this) {
    FvpMeasureMethod.MANUAL -> R.string.fvp_method_manual
    FvpMeasureMethod.FLIGHT_TIME_VIDEO -> R.string.fvp_method_flight_time
    FvpMeasureMethod.IMPORTED -> R.string.fvp_method_imported
}

internal fun FvpTimingQuality.labelResource(): Int = when (this) {
    FvpTimingQuality.ALLOW -> R.string.fvp_quality_timing_allow
    FvpTimingQuality.CONDITIONAL -> R.string.fvp_quality_timing_conditional
    FvpTimingQuality.BLOCKED -> R.string.fvp_quality_timing_blocked
    FvpTimingQuality.UNKNOWN -> R.string.fvp_quality_timing_unknown
}

/** Milisegundos de un dia completo (unidad de [reuseAgeInDays]). */
private const val MILLIS_PER_DAY: Long = 86_400_000L

/**
 * Edad de una medicion guardada en dias ENTEROS transcurridos entre [dateTime] y [now].
 *
 * Reglas (puras, sin reloj propio ni zona horaria: `now` lo aporta el llamante):
 * - mismo instante o menos de un dia completo -> 0 dias;
 * - exactamente un dia o mas -> dias completos (`transcurrido / 86_400_000`);
 * - desfase de reloj (medicion posterior a [now]) -> 0: la edad NUNCA es negativa.
 *
 * La antiguedad es una medida de TIEMPO TRANSCURRIDO, no de cambio de fecha en el calendario.
 */
internal fun reuseAgeInDays(dateTime: Long, now: Long): Long {
    val elapsedMs = now - dateTime
    if (elapsedMs <= 0L) return 0L
    return elapsedMs / MILLIS_PER_DAY
}

/**
 * Confirmacion explicita B5 para reutilizar un salto guardado (plan 13, lane A).
 *
 * La declaracion pertenece a UNA fila ([rowKey] = assessment + ordinal del intento): reutilizar
 * exige que esa fila concreta este aceptada. Estado inicial `rowKey = null, accepted = false` =
 * nada confirmado (D7: la evidencia que falta no se asume). Al confirmar otra fila, la anterior
 * deja de estarlo: la confirmacion NO se hereda entre mediciones.
 */
internal data class FvpReuseConfirmation(
    val rowKey: String? = null,
    val accepted: Boolean = false,
) {
    /** Solo la fila declarada por el usuario queda habilitada para reutilizarse. */
    fun allows(rowKey: String): Boolean = accepted && this.rowKey == rowKey

    /** Declaracion del usuario para [rowKey] (marcar o desmarcar mueve la confirmacion a esa fila). */
    fun withCheck(rowKey: String, checked: Boolean): FvpReuseConfirmation =
        FvpReuseConfirmation(rowKey = rowKey, accepted = checked)
}

/**
 * Tiempo de vuelo UTILIZABLE de una medicion guardada, en segundos, o `null` cuando esa medicion no
 * lo tenia o el valor no es utilizable (no finito o <= 0).
 *
 * Es la MISMA decision que aplica [FvpAttemptDraft.withReusedMeasurement] al escribir el intento y
 * la que usa la UI para declarar que no se llevara ningun tiempo de vuelo: una unica fuente de
 * verdad, de modo que la nota de pantalla no puede mentir sobre lo que se copia.
 */
internal fun ReusableJumpMeasurement.reusableFlightTimeS(): Double? =
    flightTimeS?.takeIf { seconds -> seconds.isFinite() && seconds > 0.0 }

/**
 * Escritura del intento al elegir un salto guardado: SOLO altura, metodo, tiempo de vuelo, fps de
 * origen y procedencia (assessment/ordinal/protocolo). La carga y el h_po siguen siendo del usuario
 * (nada de valores por defecto inventados).
 *
 * [flightTimeS] se copia del salto guardado TAL CUAL llega saneado (`null` cuando el intento
 * guardado no lo tenia, o cuando el valor no es utilizable): nunca se deriva de la altura ni se
 * conserva un tiempo de vuelo de otra medicion. La decision vive en [reusableFlightTimeS].
 *
 * B4a-2 (contrato 18 §2): la fuente reutilizada forma parte de la confirmacion, de modo que
 * reutilizar OTRA fuente retira la confirmacion (`reuseConfirmedAt`/`reuseConfirmationVersion`) en
 * esta misma mutacion. Re-verificar la MISMA fuente con el MISMO snapshot no la borra por
 * accidente: solo cambia cuando el intento cambia de verdad. La fuente no aporta PTS ni mapeo
 * temporal, asi que esos campos NO se inventan aqui (`UNKNOWN`/NULL si no venian).
 */
internal fun FvpAttemptDraft.withReusedMeasurement(
    measurement: ReusableJumpMeasurement,
): FvpAttemptDraft {
    val sourceChanged = sourceAssessmentId != measurement.assessmentId ||
        sourceAttemptOrdinal != measurement.attemptOrdinal
    val reusedBase = copy(
        heightM = measurement.heightM.takeIf { value -> value.isFinite() && value > 0.0 },
        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
        flightTimeS = measurement.reusableFlightTimeS(),
        sourceFps = measurement.detectedFps.takeIf { fps -> fps > 0 }?.toDouble(),
        sourceAssessmentId = measurement.assessmentId,
        sourceAttemptOrdinal = measurement.attemptOrdinal,
        sourceProtocolId = measurement.protocolId,
    )
    // Cambiar de FUENTE invalida toda la evidencia temporal del origen anterior: los PTS pertenecen
    // al timeline del medio de origen y no pueden sobrevivir a otra fuente; el mapeo temporal, su
    // clave y las referencias de registro/adquisicion del origen se vuelven desconocidos. Nada se
    // inventa: quedan NULL/UNKNOWN. Re-verificar la MISMA fuente NO los toca.
    val reused = if (sourceChanged) {
        reusedBase.copy(
            takeoffPtsUs = null,
            takeoffPreviousPtsUs = null,
            takeoffNextPtsUs = null,
            landingPtsUs = null,
            landingPreviousPtsUs = null,
            landingNextPtsUs = null,
            timeMapping = "UNKNOWN",
            timeMappingEvidenceKey = null,
            sourceRecordedAt = null,
            sourceAthleteId = null,
            acquiredAt = null,
        )
    } else {
        reusedBase
    }
    return if (reused.hasSameReuseSnapshotAs(this)) reused else reused.withoutReuseConfirmation()
}

/**
 * `true` cuando [other] y este intento guardan EXACTAMENTE el mismo snapshot reutilizable (altura,
 * metodo, tiempo de vuelo, fps y fuente). Es la condicion que distingue una re-verificacion de la
 * misma medicion de un cambio real, y NO mira los campos de confirmacion: retirarla es cosa de
 * [withoutReuseConfirmation].
 */
private fun FvpAttemptDraft.hasSameReuseSnapshotAs(other: FvpAttemptDraft): Boolean =
    heightM == other.heightM &&
        method == other.method &&
        flightTimeS == other.flightTimeS &&
        sourceFps == other.sourceFps &&
        sourceAssessmentId == other.sourceAssessmentId &&
        sourceAttemptOrdinal == other.sourceAttemptOrdinal &&
        sourceProtocolId == other.sourceProtocolId

/**
 * Recurso localizado de la leyenda de edad de una medicion guardada: 0 / 1 / N dias.
 *
 * La eleccion es exhaustiva y NO depende del reloj (la decision numerica es [reuseAgeInDays]). Vive
 * fuera del composable para poder cubrir los tres tramos en JVM. OJO: el tramo 0 significa "menos
 * de 24 h TRANSCURRIDAS" (`fvp_reuse_age_today` dice "hace menos de 24 h"), no "hoy" de calendario:
 * junto a la fecha real de una medicion de ayer por la noche esa leyenda se contradiria.
 */
internal fun reuseAgeLabelRes(days: Long): Int = when {
    days <= 0L -> R.string.fvp_reuse_age_today
    days == 1L -> R.string.fvp_reuse_age_one_day
    else -> R.string.fvp_reuse_age_days
}

/**
 * Leyenda localizada de la edad: envoltorio fino de [reuseAgeLabelRes] (la seleccion 0/1/N vive
 * fuera del composable). Solo el tramo de N dias lleva el numero como argumento de formato.
 */
@Composable
internal fun reuseAgeCaption(days: Long): String {
    val labelRes = reuseAgeLabelRes(days)
    return if (labelRes == R.string.fvp_reuse_age_days) {
        stringResource(labelRes, days.toString())
    } else {
        stringResource(labelRes)
    }
}
