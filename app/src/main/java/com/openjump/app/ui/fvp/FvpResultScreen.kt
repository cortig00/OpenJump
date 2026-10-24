package com.openjump.app.ui.fvp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.fvp.FvpEngine
import com.openjump.app.fvp.FvpSensitivityAvailability
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.components.DiscardConfirmDialog
import com.openjump.app.ui.components.InfoBanner
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Paso 3 del flujo F-V-P: resultado en vivo (borrador en memoria) o reapertura de un guardado
 * (`assessmentId != null`).
 *
 * One persistent experimental/interpretation context accompanies every page, with the shown
 * execution's quality, actual caveats and explicit critical limits. Complete warning explanations
 * are grouped in Quality rather than repeated before charts and data. No quality is recomputed here.
 * Metric-adjacent extrapolation caveats, F/V ranges, condition count, SI/sign convention and
 * undefined nonphysical intercepts remain unchanged. Graphs, data and traceability have their own
 * sections; a disclosure never conceals the global critical interpretation.
 *
 * El borrado de un guardado lo ejecuta el ViewModel (ambito de pantalla no cancelable) y solo
 * navega si la fila se borro; si falla, se muestra el aviso localizado `fvp_delete_error` y la
 * pantalla no se abandona.
 *
 * P2 (Wave 4a): mientras el borrado esta en curso ([FvpDeleteState.Deleting]) la pantalla BLOQUEA
 * la salida: el Back del sistema se consume, la flecha de la barra se ignora y Editar/Volver a
 * borrar quedan deshabilitados. El ViewModel es de DESTINO: un pop lo destruiria y cancelaria la
 * corrutina, de modo que ni el exito (`onDeleted`) ni el fallo (`fvp_delete_error`) llegarian a
 * verse. La operacion termina siempre en un estado visible.
 *
 * Identidad de la serie (Muse P1-3): la cabecera muestra atleta, protocolo y fecha. El nombre
 * SIEMPRE se resuelve desde el atleta de la serie; si no se conoce, se pinta el marcador neutro
 * compartido `athlete_unassigned` y NUNCA se inventa un nombre.
 *
 * D2 (plan 13): la accion secundaria se nombra por lo que HACE. En el borrador vivo edita los datos
 * de la serie en curso (`fvp_edit`); en un guardado adopta una COPIA con `sessionKey` nueva
 * (`mutations.adopt(storedFvpToDraft(saved))`, la mutacion del dueño del flujo en A2), de modo que se rotula `fvp_duplicate_and_edit`
 * y lleva el aviso `fvp_duplicate_notice`: la evaluacion guardada no se modifica ni se pierde.
 *
 * D3 (plan 13, S2): la pantalla muestra UNA sola ejecucion, nunca una mezcla. La fila guardada abre
 * en la vista ORIGINAL (los numeros PERSISTIDOS y sin puntos: el snapshot no guarda puntos por
 * condicion, de modo que no se fabrica ninguno). Solo cuando el estado declara que el recalculo
 * EXISTE y DIFIERE (`MISMATCH`/`STALE`/`STALE_METHOD`) se ofrece el conmutador
 * `fvp-view-toggle` hacia la vista de RECALCULACION (escalares Y puntos del motor actual, con SU
 * version). `MATCHES` conserva una unica vista con puntos (el repositorio certifica que el recalculo
 * es identico) y `RECALCULATION_UNAVAILABLE` una unica vista original con
 * `fvp_warning_snapshot_unavailable` + `fvp_cannot_compute`. El borrador vivo (sin snapshot) no
 * cambia: su unica ejecucion es la actual y mantiene `fvp-save` / `fvp_edit`.
 *
 * Q2a (plan 13): el estado de calidad derivado (Q1) se pinta SIEMPRE junto a las metricas, FUERA del
 * bloque plegable, con su explicacion en lenguaje llano; el contexto B7 que faltaba (rango de fuerza
 * `[Fmin,Fmax]` en N, recuento de condiciones de la ejecucion mostrada y convencion `Sfv = -dF/dV`)
 * vive en la misma zona siempre visible. Cuando la vista mostrada es el ORIGINAL persistido y la FILA
 * declara `STALE`/`STALE_METHOD`, la etiqueta de calidad lleva ademas la nota de vigencia
 * `fvp_quality_stale_hint` (los veredictos son los que guardo la version que registro la serie); el
 * caso general del recalculo distinto lo sigue declarando el aviso persistido
 * `fvp_warning_snapshot_recalculated`, y la vista de recalculo no lleva nota (sus veredictos son los
 * del motor actual).
 */
@Composable
fun FvpResultScreen(
    onBack: () -> Unit,
    onExit: () -> Unit,
    onEdit: () -> Unit,
    onSaved: (Long) -> Unit,
    onDeleted: () -> Unit,
    assessmentId: Long? = null,
    /** Nombre resuelto del atleta del BORRADOR vivo (`AppNav` lo lee de `live.athleteId`). */
    athleteName: String? = null,
    /**
     * Nombres del roster activo por id: un guardado se identifica por `assessment.athleteId`, no
     * por el atleta seleccionado, de modo que la cabecera nunca atribuye la serie a otro atleta.
     */
    athleteNameById: Map<Long, String> = emptyMap(),
    /**
     * Dueño UNICO del flujo (A2): `AppNav` pasa SIEMPRE la misma instancia que creo una sola vez en
     * el `NavHost`. `null` queda SOLO para montajes aislados de esta pantalla (tests de UI sin
     * dueño); en ese caso se usa la sesion directamente y NO se crea un segundo dueño.
     */
    flowViewModel: FvpFlowViewModel? = null,
    viewModel: FvpViewModel = viewModel(factory = FvpViewModel.Factory),
) {
    // A2: toda mutacion externa del borrador (nota, descarte, duplicar y editar) pasa por el DUEÑO
    // cuando existe, y la salida terminal del guardado pasa por su guard de `sessionKey`.
    val mutations: FvpDraftMutations = flowViewModel ?: FvpDraftSession
    val draft by FvpDraftSession.draft.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val deleteState by viewModel.deleteState.collectAsState()
    // P2 (Wave 4a): el borrado NO se puede cancelar saliendo. El ViewModel vive en el destino, de
    // modo que un pop durante `Deleting` cancelaria la corrutina y ocultaria el resultado. El Back
    // del sistema se consume mientras el estado sea `Deleting`; la flecha de la barra y Editar se
    // bloquean mas abajo y el banner `fvp_deleting` muestra que la operacion sigue viva.
    val deleteInProgress = deleteState == FvpDeleteState.Deleting
    BackHandler(enabled = deleteInProgress) { }

    if (assessmentId == null) {
        val live = draft
        if (live == null) {
            FvpResultUnavailable(
                body = stringResource(R.string.fvp_expired_session_body),
                onBack = onExit,
            )
            return
        }
        val presentation = remember(live) { viewModel.compute(live) }
        val model = remember(live, presentation) {
            // G-SCIENCE (plan 13): los insumos de la sensibilidad temporal salen de los intentos
            // SELECCIONADOS de la serie (el mismo intento del que sale cada altura); la UI solo
            // muestra su DISPONIBILIDAD, nunca los rangos.
            val sensitivityInputs = live.selectedSensitivityInputs()
            FvpPresenter.present(
                result = presentation.result,
                eligibility = presentation.eligibility,
                protocol = live.protocol,
                bodyMassKg = live.bodyMassKg,
                conditionCount = presentation.eligibility.conditionCount,
                timingQuality = presentation.eligibility.timingQuality,
                attemptByCondition = live.selectedAttemptsByCondition(),
                algorithmVersion = presentation.result?.algorithmVersion.orEmpty(),
                pushOffSource = live.pushOffSource,
                flightTimesS = sensitivityInputs.flightTimesS,
                fpsPerCondition = sensitivityInputs.fpsPerCondition,
                // Un borrador vivo no tiene recalculo, pero SI un calculo con el motor actual: es la
                // ejecucion de la que partiria la envolvente (su resultado existe o no la hay).
                hasRecalculation = presentation.result != null,
                // S2 (plan 13): un borrador vivo no tiene snapshot persistido, de modo que su unica
                // ejecucion es la actual. La vista se declara explicita y el flujo no cambia.
                view = FvpResultView.ORIGINAL,
            )
        }
        var confirmDiscard by remember { mutableStateOf(false) }
        var notesText by rememberSaveable(live.sessionKey) { mutableStateOf(live.notes.orEmpty()) }
        // La serie viva aun no tiene fecha persistida: se muestra el instante en que se abre el
        // resultado, que es la misma referencia temporal con la que se sellara al guardar.
        val liveSeriesDateTime = remember(live.sessionKey) { System.currentTimeMillis() }
        FvpResultContent(
            model = model,
            // El borrador vivo tiene UNA sola ejecucion: nunca se ofrece conmutador.
            showViewToggle = false,
            onViewChange = null,
            canCompute = presentation.canCompute,
            // Q2a (plan 13): un borrador vivo no tiene snapshot persistido, de modo que sus
            // veredictos de calidad son los del motor ACTUAL y no llevan nota de vigencia.
            staleQualityVerdicts = false,
            athleteName = athleteName,
            seriesDateTime = liveSeriesDateTime,
            storedNotes = null,
            notesEditable = true,
            notesText = notesText,
            onNotesChange = { text ->
                notesText = text
                mutations.update { it.copy(notes = text.ifBlank { null }) }
            },
            saveState = saveState,
            deleteState = FvpDeleteState.Idle,
            onSave = {
                // A2: la confirmacion captura la IDENTIDAD exacta de la serie guardada. Si mientras
                // el guardado estaba en vuelo el usuario empezo OTRA serie, este callback no puede
                // descartarla ni navegar fuera de ella: `resetIfSessionKey` solo actua si el
                // borrador vivo sigue siendo ESTA serie, y solo entonces se navega. El payload se
                // limpia despues del guardado CONFIRMADO, nunca antes de la base.
                val savedSessionKey = live.sessionKey
                viewModel.save(live) { id ->
                    if (mutations.resetIfSessionKey(savedSessionKey)) {
                        onSaved(id)
                    }
                }
            },
            onEdit = onEdit,
            onDelete = { confirmDiscard = true },
            deleteLabel = stringResource(R.string.common_discard),
            // El borrador vivo se identifica por su `sessionKey`: abrir OTRA serie vuelve a
            // Resumen y no hereda la seccion de la anterior.
            resultIdentity = "live:" + live.sessionKey,
            onBack = onBack,
        )
        if (confirmDiscard) {
            DiscardConfirmDialog(
                title = stringResource(R.string.result_discard_title),
                body = stringResource(R.string.result_discard_body),
                onConfirm = {
                    confirmDiscard = false
                    mutations.reset()
                    onExit()
                },
                onDismiss = { confirmDiscard = false },
            )
        }
        return
    }

    var stored by remember(assessmentId) { mutableStateOf<StoredFvpAssessment?>(null) }
    var loaded by remember(assessmentId) { mutableStateOf(false) }
    LaunchedEffect(assessmentId) {
        stored = runCatching { viewModel.stored(assessmentId) }.getOrNull()
        loaded = true
    }
    val saved = stored
    if (!loaded) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    if (saved == null) {
        FvpResultUnavailable(
            body = stringResource(R.string.error_invalid_measurement),
            onBack = onBack,
        )
        return
    }

    var confirmDelete by remember { mutableStateOf(false) }
    // S2 (plan 13, D3): la fila guardada abre en la vista ORIGINAL (numeros persistidos) y solo se
    // puede conmutar si el estado declara que el recalculo EXISTE y DIFIERE del snapshot. La
    // eleccion vive AQUI y alimenta UNA sola presentacion: el control y los numeros no discrepan.
    // `saved.result != null` es cinturon de seguridad del contrato del repositorio (los estados que
    // encienden el conmutador se derivan de un recalculo existente): sin recalculo no se conmuta.
    var selectedView by rememberSaveable(assessmentId) { mutableStateOf(FvpResultView.ORIGINAL) }
    val viewToggleAvailable = fvpShowsViewToggle(saved.snapshotStatus) && saved.result != null
    val shownView = if (viewToggleAvailable) {
        selectedView
    } else {
        fvpDefaultStoredView(saved.snapshotStatus, hasRecalculation = saved.result != null)
    }
    val model = remember(saved, shownView) {
        // P1 (T1b): los insumos de sensibilidad SIGUEN a la VISTA mostrada. El ORIGINAL empareja
        // cada condicion con el vuelo/fps del intento que la fila REGISTRA (esa es la ejecucion
        // persistida); el RECALCULO los empareja con la RESELECCION CANONICA sobre los intentos
        // guardados, que es el intento del que salen su altura y sus numeros. Sin ese parametro
        // explicito la UI podria mostrar la altura de una toma con el vuelo de OTRA.
        val sensitivityInputs = saved.selectedSensitivityInputs(shownView)
        FvpPresenter.present(
            result = saved.result,
            eligibility = saved.eligibility,
            // El rótulo del protocolo sale de la CLAVE PERSISTIDA (fuente de verdad del guardado);
            // sin recálculo no hay otro origen y una clave desconocida queda 'indefinido', nunca SJ.
            protocol = FvpEngine.parseProtocol(saved.assessment.protocolId),
            bodyMassKg = saved.assessment.bodyMassKg,
            conditionCount = saved.assessment.conditionCount,
            // Sin elegibilidad (fila no recalculable) la calidad temporal es DESCONOCIDA: el
            // presentador jamás debe declarar una calidad que esta versión no puede verificar.
            timingQuality = saved.eligibility?.timingQuality ?: FvpTimingQuality.UNKNOWN,
            attemptByCondition = saved.selectedAttemptsByCondition(),
            storedWarnings = saved.warnings,
            storedSnapshot = saved.storedSnapshot(),
            // El aviso sale del ESTADO del repositorio: un solo dato, sin un `Boolean?` paralelo
            // que podria contradecirlo.
            storedSnapshotStatus = saved.snapshotStatus,
            algorithmVersion = saved.assessment.algorithmVersion,
            pushOffSource = fvpEffectivePushOffSource(
                declaredSource = saved.assessment.pushOffSource,
                landmark = saved.assessment.pushOffLandmark,
                procedure = saved.assessment.pushOffProtocol,
                measuredAt = saved.assessment.pushOffMeasuredAt,
            ),
            // B4b: la procedencia GUARDADA (masa, carga, tiempo y contexto) se declara tal cual,
            // sin promocionar `UNKNOWN`/`NULL`: los avisos acompanan a los numeros de las DOS
            // vistas porque son de la FILA, no de la ejecucion.
            provenanceWarnings = saved.provenanceWarningsForUi(),
            // Una sola ejecucion: la vista elegida (ORIGINAL por defecto).
            view = shownView,
            flightTimesS = sensitivityInputs.flightTimesS,
            fpsPerCondition = sensitivityInputs.fpsPerCondition,
            // G-SCIENCE: hay recalculo (y por tanto serie) cuando el repositorio lo certifica; sin el,
            // la disponibilidad declara NO_RECALCULATION en vez de culpar a un insumo inexistente.
            hasRecalculation = saved.result != null,
        )
    }
    // Q2a (plan 13): la etiqueta de calidad lleva la nota de VIGENCIA solo cuando la vista mostrada
    // es el ORIGINAL persistido y el estado de la FILA declara que sus veredictos son de otra version
    // del motor (`STALE`/`STALE_METHOD`). En la vista de recalculo los veredictos son los actuales y
    // `MISMATCH`/`RECALCULATION_UNAVAILABLE` ya tienen su aviso propio (`fvp_warning_snapshot_*`).
    val staleQualityVerdicts = shownView == FvpResultView.ORIGINAL && (
        saved.snapshotStatus == FvpSnapshotStatus.STALE ||
            saved.snapshotStatus == FvpSnapshotStatus.STALE_METHOD
        )
    FvpResultContent(
        model = model,
        showViewToggle = viewToggleAvailable,
        onViewChange = { selectedView = it },
        // Sin recalculo (fila no computable hoy) se muestra el aviso existente
        // `fvp_cannot_compute`: los numeros persistidos siguen visibles, pero el perfil
        // recalculado no existe y la accion de guardar no aplica.
        canCompute = saved.result != null,
        staleQualityVerdicts = staleQualityVerdicts,
        // El guardado se identifica por SU atleta (`saved.assessment.athleteId`): el roster activo
        // solo aporta el nombre; sin el, marcador neutro (jamas el atleta seleccionado).
        athleteName = saved.assessment.athleteId?.let { athleteNameById[it] },
        seriesDateTime = saved.assessment.dateTime,
        storedNotes = saved.assessment.notes,
        notesEditable = false,
        notesText = saved.assessment.notes.orEmpty(),
        onNotesChange = null,
        saveState = FvpSaveState.Saved,
        deleteState = deleteState,
        onSave = null,
        // Duplicar exige RECALCULO: `storedFvpToDraft` mapea una clave persistida desconocida a
        // SJ/MANUAL, de modo que en una fila no recalculable la accion fabricaria una serie (y una
        // interpretacion) que el dato guardado no respalda. Sin recalculo no se ofrece la accion;
        // el aviso `fvp_cannot_compute` ya explica por que.
        onEdit = if (saved.result != null) {
            {
                // Editar un guardado abre una serie NUEVA con los mismos datos (sessionKey nueva):
                // `FvpRepository.save` es idempotente por sessionKey y no actualiza filas. La
                // mutacion va por el DUEÑO (A2), que supersede la reconciliacion pendiente.
                mutations.adopt(storedFvpToDraft(saved))
                onEdit()
            }
        } else {
            null
        },
        onDelete = { confirmDelete = true },
        deleteLabel = stringResource(R.string.fvp_delete),
        // La fila guardada se identifica por su `assessmentId` (nunca por la sesion viva).
        resultIdentity = "stored:$assessmentId",
        // P2 (Wave 4a): salir durante el borrado queda bloqueado hasta conocer el resultado.
        onBack = { if (!deleteInProgress) onBack() },
    )
    if (confirmDelete) {
        DiscardConfirmDialog(
            title = stringResource(R.string.fvp_delete),
            body = stringResource(R.string.fvp_delete_confirm),
            confirmLabel = stringResource(R.string.fvp_delete),
            onConfirm = {
                confirmDelete = false
                // El borrado vive en el ViewModel (su ambito no se cancela al salir de
                // composicion) y solo navega si la fila se borro de verdad: si no existia o
                // falla, el estado pasa a Error y esta pantalla muestra `fvp_delete_error`.
                viewModel.delete(saved.assessment.id) { onDeleted() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun FvpResultContent(
    model: FvpResultUiModel,
    /**
     * `true` cuando la fila guardada tiene DOS ejecuciones distintas que mostrar (estado
     * `MISMATCH`/`STALE`/`STALE_METHOD` con recalculo): se ofrece el conmutador ORIGINAL /
     * RECALCULACION. `false` = una sola vista, sin control.
     */
    showViewToggle: Boolean,
    /** Cambio de vista pedido por el usuario; solo se usa cuando [showViewToggle]. */
    onViewChange: ((FvpResultView) -> Unit)?,
    canCompute: Boolean,
    /**
     * `true` cuando la vista mostrada es el ORIGINAL persistido y el snapshot de la FILA es
     * `STALE`/`STALE_METHOD`: los veredictos que acompañan a la calidad son los que guardó una
     * versión anterior y el motor actual produce un resultado distinto, de modo que la etiqueta
     * lleva la nota de vigencia `fvp_quality_stale_hint`.
     */
    staleQualityVerdicts: Boolean,
    athleteName: String?,
    seriesDateTime: Long,
    storedNotes: String?,
    notesEditable: Boolean,
    notesText: String,
    onNotesChange: ((String) -> Unit)?,
    saveState: FvpSaveState,
    deleteState: FvpDeleteState,
    onSave: (() -> Unit)?,
    /** `null` = la accion secundaria (editar/duplicar) no se OFRECE para esta fila. */
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
    deleteLabel: String,
    /**
     * Identidad de la evaluacion MOSTRADA (`live:<sessionKey>` / `stored:<assessmentId>`).
     *
     * NO es una seccion ni una vista: no cambia ningun dato ni la fuente ORIGINAL/RECALCULACION.
     * Solo reinicia la seccion y el scroll de cada pagina al abrir OTRA evaluacion, de modo que
     * nunca se hereda la seccion de la serie anterior.
     */
    resultIdentity: String,
    onBack: () -> Unit,
) = key(resultIdentity) {
    // La identidad abarca TODO el pager y su LazyLayout, no solo rememberPagerState: si se
    // conserva el layout anterior, este puede anclar la nueva serie a la vieja clave de pagina.
    // UNICO dueño del indice de seccion: el estado del pager. Las pestanas solo lo leen y lo mueven;
    // no hay un segundo `rememberSaveable` de seccion que pueda discrepar del contenido pintado.
    // `key(resultIdentity)` reinicia seccion y scroll al cambiar de evaluacion; con la misma
    // identidad, el Saver propio del pager restaura la seccion tras recrear la Activity.
    val pagerState = rememberPagerState(pageCount = { FvpResultSection.entries.size })
    val scope = rememberCoroutineScope()
    // Scroll PROPIO de cada seccion: la posicion vertical de Graficas no se mezcla con la de Datos y
    // se conserva al cambiar de pestana. Se reinicia con la identidad de la evaluacion mostrada y,
    // ademas, con la EJECUCION mostrada (`model.view`): al conmutar ORIGINAL/RECALCULACION ninguna
    // seccion reaprovecha el desplazamiento de otra fuente (los numeros y las cifras cambian).
    val viewKey = model.view.name
    val summaryScroll = rememberSaveable(resultIdentity, viewKey, saver = ScrollState.Saver) { ScrollState(0) }
    val chartsScroll = rememberSaveable(resultIdentity, viewKey, saver = ScrollState.Saver) { ScrollState(0) }
    val dataScroll = rememberSaveable(resultIdentity, viewKey, saver = ScrollState.Saver) { ScrollState(0) }
    val qualityScroll = rememberSaveable(resultIdentity, viewKey, saver = ScrollState.Saver) { ScrollState(0) }
    // D2 (plan 13): reabrir un guardado pasa `onSave = null` (no hay nada que guardar: la fila ya
    // existe) y su `onEdit` adopta una copia con `sessionKey` nueva. Esa misma condicion distingue
    // el rotulo: guardado => duplicar y editar (+ aviso de que el original se conserva); borrador
    // vivo => editar los datos de la propia serie.
    val savedResult = onSave == null

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.fvp_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
        // Unica accion PRIMARIA de la pantalla y sus estados de operacion: fija para que Guardar
        // (borrador) y los avisos de guardado/borrado sean alcanzables desde CUALQUIER seccion. Ni
        // las notas ni el resto de acciones viven aqui (no engordar el footer).
        bottomBar = {
            FvpResultFooter(
                canCompute = canCompute,
                saveState = saveState,
                deleteState = deleteState,
                onSave = onSave,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Navigation precedes ALL variable context, including execution/source labels.
            // No page-dependent action or warning can move the tabs.
            FvpResultSectionTabs(
                currentPage = pagerState.currentPage,
                onSectionSelected = { section ->
                    scope.launch { pagerState.animateScrollToPage(fvpPageOf(section)) }
                },
            )
            // El selector de ejecucion va FUERA del pager: cambia TODOS los datos de TODAS las
            // secciones, de modo que se mantiene a la vista (y con su leyenda de origen/version) en
            // cualquier pestana. No es una seccion: `FvpResultSection` nunca absorbe ORIGINAL /
            // RECALCULACION.
            FvpExecutionSelector(
                model = model,
                showViewToggle = showViewToggle,
                onViewChange = onViewChange,
            )
            FvpResultInterpretation(
                model = model,
                canCompute = canCompute,
                staleQualityVerdicts = staleQualityVerdicts,
            )
            HorizontalPager(
                state = pagerState,
                // Sin scroll circular ni flechas extra: las pestanas son la alternativa al gesto y
                // los extremos no envuelven (el pager no da la vuelta).
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("fvp-sections-pager"),
                key = { page -> FvpResultSection.entries[page].name },
            ) { page ->
                val section = fvpSectionAt(page) ?: return@HorizontalPager
                when (section) {
                    FvpResultSection.SUMMARY -> FvpSummarySection(
                        model = model,
                        athleteName = athleteName,
                        seriesDateTime = seriesDateTime,
                        // Slice D: un guardado no es un asistente en curso; el stepper solo
                        // pertenece al borrador VIVO.
                        savedResult = savedResult,
                        scrollState = summaryScroll,
                        onQuality = {
                            scope.launch { pagerState.animateScrollToPage(fvpPageOf(FvpResultSection.QUALITY)) }
                        },
                    ) {
                        // Acciones SECUNDARIAS (editar/duplicar y borrar/descartar) al final del
                        // Resumen: no engordan el footer fijo y conservan sus guardas (deshabilitadas
                        // durante el borrado). Las sigue gobernando el estado de la PANTALLA, no el
                        // ciclo de vida de la pagina.
                        FvpResultActions(
                            deleteState = deleteState,
                            onEdit = onEdit,
                            onDelete = onDelete,
                            deleteLabel = deleteLabel,
                            savedResult = savedResult,
                        )
                    }

                    FvpResultSection.CHARTS -> FvpChartsSection(
                        model = model,
                        // El `key(model.view)` interno reinicia la seleccion de puntos de los
                        // Canvas al conmutar la ejecucion: un punto senalado en una fuente no puede
                        // quedar seleccionado sobre los datos de la otra.
                        view = model.view,
                        scrollState = chartsScroll,
                    )

                    FvpResultSection.DATA -> FvpDataSection(
                        model = model,
                        notesEditable = notesEditable,
                        notesText = notesText,
                        onNotesChange = onNotesChange,
                        storedNotes = storedNotes,
                        scrollState = dataScroll,
                    )

                    FvpResultSection.QUALITY -> FvpQualitySection(
                        model = model,
                        showViewToggle = showViewToggle,
                        scrollState = qualityScroll,
                    )
                }
            }
        }
    }
}

/**
 * Conmutador GLOBAL de ejecucion (ORIGINAL / RECALCULACION) con su leyenda de origen y version.
 *
 * Vive fuera del pager porque cambiar de vista cambia los numeros de TODAS las secciones; dentro de
 * una sola pagina, las demas quedarian mostrando una ejecucion distinta de la declarada. El estado
 * sigue siendo unico (`FvpResultScreen`) y esta pantalla solo lo pinta.
 */
@Composable
private fun FvpExecutionSelector(
    model: FvpResultUiModel,
    showViewToggle: Boolean,
    onViewChange: ((FvpResultView) -> Unit)?,
) {
    if (!showViewToggle || onViewChange == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(top = Spacing.sm)
            .testTag("fvp-view-toggle"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FvpViewToggleOption(
                label = stringResource(R.string.fvp_view_original),
                isSelected = model.view == FvpResultView.ORIGINAL,
                tag = "fvp-view-original",
                onClick = { onViewChange(FvpResultView.ORIGINAL) },
                modifier = Modifier.weight(1f),
            )
            FvpViewToggleOption(
                label = stringResource(R.string.fvp_view_recalculation),
                isSelected = model.view == FvpResultView.RECALCULATION,
                tag = "fvp-view-recalculation",
                onClick = { onViewChange(FvpResultView.RECALCULATION) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(model.view.labelResource()) + " · " +
                stringResource(R.string.fvp_algorithm_version, model.algorithmVersion),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("fvp-view-source"),
        )
    }
}

/**
 * Pestanas de seccion. `ScrollableTabRow` (no `TabRow`): a 150 % de fuente los cuatro titulos
 * pueden no caber y se prefiere el desplazamiento de la fila antes que recortar palabras. Tocar una
 * pestana mueve el MISMO pager que el gesto, de modo que no hay dos estados que discrepen.
 */
@Composable
private fun FvpResultSectionTabs(
    currentPage: Int,
    onSectionSelected: (FvpResultSection) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = currentPage.coerceIn(FvpResultSection.entries.indices),
        modifier = Modifier.fillMaxWidth().testTag("fvp-sections-tabs"),
        edgePadding = Spacing.screenHorizontal,
        divider = {},
    ) {
        FvpResultSection.entries.forEach { section ->
            Tab(
                selected = currentPage == fvpPageOf(section),
                onClick = { onSectionSelected(section) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(section.tabTag),
                text = { Text(stringResource(section.labelRes)) },
            )
        }
    }
}

/**
 * Pagina de seccion: UN contenedor con su PROPIO scroll vertical (plan A). El desplazamiento
 * horizontal lo aporta el pager; aqui solo vive el vertical, de modo que cada seccion recuerda su
 * posicion y ninguna comparte la de otra.
 */
@Composable
private fun FvpResultSectionPage(
    scrollState: ScrollState,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(top = Spacing.md, bottom = Spacing.xl)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

/**
 * Summary shows values, adjacent critical glosses and model ranges/sign/units. The same
 * execution's interpretation stays global; complete warning explanations are grouped in Quality.
 */
@Composable
private fun FvpSummarySection(
    model: FvpResultUiModel,
    athleteName: String?,
    seriesDateTime: Long,
    /**
     * `true` cuando la evaluacion mostrada ya esta GUARDADA (no es el borrador vivo): el resultado
     * guardado no es un paso de un asistente en curso, de modo que su Resumen no repite el stepper.
     * El borrador vivo conserva su progreso compacto sin cambios.
     */
    savedResult: Boolean,
    scrollState: ScrollState,
    onQuality: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val locale = currentAppLocale()
    val measuredRangeText = stringResource(R.string.fvp_measured_range) + ": " +
        measurementRange(model, locale)
    FvpResultSectionPage(scrollState = scrollState, tag = FvpResultSection.SUMMARY.panelTag) {
        if (!savedResult) {
            FvpFlowProgress(currentStep = FvpFlowStep.RESULT)
        }
        // Muse P1-3: cabecera compacta de identidad de la serie (atleta, protocolo, fecha).
        FvpSeriesIdentityHeader(
            athleteName = athleteName,
            protocolLabel = protocolLabel(model.protocolLabelKey),
            seriesDateTime = seriesDateTime,
            bodyMassKg = model.bodyMassKg,
        )
        // Secondary content action, not a conditional control above the navigation.
        TextButton(
            onClick = onQuality,
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("fvp-open-quality"),
        ) {
            Text(stringResource(R.string.fvp_notice_quality_action), style = OpenJumpTypes.Label)
        }
        // Values keep their identity, ranges and critical metric-specific caveats. The global
        // interpretation and Quality details refer to this same model/view, never another execution.
        // Grupo de metricas EQUILIBRADO y LOCAL a FVP: la cifra manda, la unidad va subordinada y
        // no hay hero verde ni etiquetas en mayusculas. La glosa CRITICA breve queda junto a la
        // cifra; las definiciones completas (las glosas largas y notas anteriores) se consultan en
        // una accion de 48 dp, no como parrafo permanente.
        FvpMetricGroup(
            f0 = FvpMetricEntry(forceText(model.f0N, locale), "N"),
            sfv = FvpMetricEntry(sfvText(model.sfvNsPerM, locale), "N·s/m"),
            v0 = FvpMetricEntry(speedText(model.v0Mps, locale), "m/s"),
            pmax = FvpMetricEntry(powerText(model.pmaxW, locale), "W"),
            r2 = FvpMetricEntry(r2Text(model.r2, locale), null),
            measuredRangeText = measuredRangeText,
        )
        // Contexto OPERATIVO de las cifras mostradas (rango, convencion de signo, relativos y
        // unidades del modelo): no es una definicion extensa, se queda junto a los numeros.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // B7 (plan 13, Q2a): rango de FUERZA medido [Fmin,Fmax] de la MISMA ejecucion y
            // recuento de condiciones, de la fuente unica del modelo (snapshot ORIGINAL o motor).
            Text(
                "F " + forceRange(model, locale) + " · " +
                    stringResource(R.string.fvp_conditions_title) + ": " + model.conditionCount,
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-force-range"),
            )
            // B7: convencion de signo de Sfv junto a los valores, no en la ayuda. Una pendiente
            // positiva es no fisica y el motor ya la senala con `NON_PHYSICAL`.
            Text(
                stringResource(R.string.fvp_slope_convention),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-slope-convention"),
            )
            if (model.f0RelNPerKg != null || model.pmaxRelWPerKg != null) {
                Text(
                    stringResource(R.string.fvp_relative_per_mass) + " " +
                        relativeText(model.f0RelNPerKg, "N/kg", locale) + " · " +
                        relativeText(model.pmaxRelWPerKg, "W/kg", locale),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-relative-values"),
                )
            }
            // Punto 3 (Wave 5): la eleccion de unidades del modelo SI es DELIBERADA.
            Text(
                stringResource(R.string.fvp_model_units_note),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-model-units-note"),
            )
        }
        // Interpretation is global; complete, grouped explanations live in Quality.
        // Metric-adjacent extrapolation/ranges and SI/sign context above remain visible.
        actions()
    }
}

/**
 * Graficas: como es el ajuste. Las curvas F–V y P–V existentes, sin cambios de matematica, escala,
 * leyenda ni interaccion. En la vista ORIGINAL de un guardado los puntos no viven en esa fuente: se
 * explica el motivo y NUNCA se fabrican puntos ni ejes (el conmutador global ya esta a la vista).
 */
@Composable
private fun FvpChartsSection(
    model: FvpResultUiModel,
    /** Ejecucion mostrada: reinicia la seleccion de puntos de los Canvas al conmutarla. */
    view: FvpResultView,
    scrollState: ScrollState,
) {
    FvpResultSectionPage(scrollState = scrollState, tag = FvpResultSection.CHARTS.panelTag) {
        if (model.pointsUnavailableFromSource) {
            // P1 (S2): el ORIGINAL de un guardado no tiene puntos porque el SNAPSHOT no los
            // guarda, no porque la serie no tenga mediciones. Se explica el motivo y como verlos
            // EN LUGAR del estado vacio del motor (`fvp_chart_empty` = "Sin puntos
            // medidos todavia", que seria falso); las graficas no se pintan: ni ejes ni
            // series fabricadas.
            Text(
                stringResource(R.string.fvp_points_not_in_saved_snapshot),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-points-not-in-snapshot"),
            )
        } else if (model.points.isNotEmpty() || model.f0N.isFinite()) {
            // La seleccion de cada Canvas es `rememberSaveable` DENTRO del grafico: al cambiar la
            // ejecucion se recompone con una clave distinta y no se hereda el punto senalado de la
            // fuente anterior (no se toca ningun dato ni se recalcula nada por pagina).
            key(view) {
                FvpForceVelocityChart(
                    points = model.plottablePoints,
                    f0N = model.f0N,
                    sfvNsPerM = model.sfvNsPerM,
                    vMinMps = model.vMinMps,
                    vMaxMps = model.vMaxMps,
                    v0Mps = model.v0Mps,
                    protocolLabel = protocolLabel(model.protocolLabelKey),
                )
                FvpPowerVelocityChart(
                    points = model.plottablePoints,
                    f0N = model.f0N,
                    sfvNsPerM = model.sfvNsPerM,
                    pmaxW = model.pmaxW,
                    v0Mps = model.v0Mps,
                    vMinMps = model.vMinMps,
                    vMaxMps = model.vMaxMps,
                    protocolLabel = protocolLabel(model.protocolLabelKey),
                )
            }
        }
    }
}

/**
 * Datos: con que datos se calculo. La tabla de puntos ya existente (metodo, fps, altura, F/V/P,
 * validez y motivo de exclusion) y las notas. El texto de notas se mantiene en la PANTALLA
 * (`notesText`), no en la pagina: cambiar de seccion o de pagina no pierde lo escrito y el
 * guardado/borrado no depende del ciclo de vida del pager.
 */
@Composable
private fun FvpDataSection(
    model: FvpResultUiModel,
    notesEditable: Boolean,
    notesText: String,
    onNotesChange: ((String) -> Unit)?,
    storedNotes: String?,
    scrollState: ScrollState,
) {
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    FvpResultSectionPage(scrollState = scrollState, tag = FvpResultSection.DATA.panelTag) {
        FvpPointsTable(model = model, unitProfile = unitProfile, locale = locale)
        if (notesEditable || !storedNotes.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.fvp_notes), style = OpenJumpTypes.SectionTitle)
                if (notesEditable && onNotesChange != null) {
                    OutlinedTextField(
                        value = notesText,
                        onValueChange = onNotesChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("fvp-notes"),
                        minLines = 2,
                    )
                } else {
                    Text(
                        storedNotes.orEmpty(),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Calidad: que limites tiene esta evaluacion. El detalle REAL que el modelo ya aporta (captura,
 * version del algoritmo, calidad temporal, protocolo, masa, condiciones, puntos y disponibilidad de
 * sensibilidad) mas la lista completa de avisos de la ejecucion mostrada. No se repiten aqui los
 * nodos etiquetados del Resumen (`fvp-quality`, `fvp-sensitivity`, metricas) para que el pager no
 * tenga dos nodos con el mismo tag; no se anade ningun score, semaforo ni prescripcion nuevos.
 */
@Composable
private fun FvpQualitySection(
    model: FvpResultUiModel,
    showViewToggle: Boolean,
    scrollState: ScrollState,
) {
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    FvpResultSectionPage(scrollState = scrollState, tag = FvpResultSection.QUALITY.panelTag) {
        FvpResultNoticeCauses(model)
        FvpResultNoticeGroups(model)
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("fvp-quality-detail"),
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(stringResource(R.string.fvp_section_quality), style = OpenJumpTypes.SectionTitle)
                FvpQualityDetailRow(
                    label = stringResource(R.string.fvp_protocol),
                    value = protocolLabel(model.protocolLabelKey),
                    tag = "fvp-quality-protocol",
                )
                FvpQualityDetailRow(
                    label = stringResource(R.string.fvp_body_mass),
                    value = MeasurementFormatting.format(
                        model.bodyMassKg,
                        MeasurementQuantity.MASS_KG,
                        unitProfile,
                        locale,
                    ),
                    tag = "fvp-quality-body-mass",
                )
                FvpQualityDetailRow(
                    label = stringResource(R.string.fvp_conditions_title),
                    value = model.conditionCount.toString(),
                    tag = "fvp-quality-conditions",
                )
                FvpQualityDetailRow(
                    label = stringResource(R.string.fvp_points_title),
                    value = if (model.pointsUnavailableFromSource) {
                        stringResource(R.string.fvp_undefined_value)
                    } else {
                        model.points.size.toString()
                    },
                    tag = "fvp-quality-points",
                )
                if (model.pointsUnavailableFromSource) {
                    Text(
                        stringResource(R.string.fvp_points_not_in_saved_snapshot),
                        style = OpenJumpTypes.Secondary,
                        modifier = Modifier.testTag("fvp-quality-points-unavailable"),
                    )
                }
                if (!showViewToggle) {
                    // Con conmutador, la version y la fuente ya viajan en la leyenda global
                    // `fvp-view-source`: no se duplica el mismo dato en esta pagina.
                    Text(
                        stringResource(R.string.fvp_algorithm_version, model.algorithmVersion),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("fvp-algorithm-version"),
                    )
                }
                Text(
                    stringResource(model.timingQuality.labelResource()),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-timing-quality"),
                )

            }
        }
        FvpSensitivityNotice(model.sensitivityAvailability)
        FvpCaptureHeader(showWarning = false, testTag = "fvp-result-method")
        Text(
            stringResource(R.string.fvp_capture_notice),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Fila etiqueta/valor del detalle de calidad (valor subordinado a la etiqueta). */
@Composable
private fun FvpQualityDetailRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = OpenJumpTypes.Body,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(tag),
        )
    }
}

/**
 * Acciones SECUNDARIAS del resultado (editar/duplicar y borrar/descartar), al final del Resumen.
 * El guardado y sus estados de operacion son GLOBALES y viven en el footer ([FvpResultFooter]);
 * aqui solo quedan las acciones que no deben ocupar el footer fijo.
 */
@Composable
private fun FvpResultActions(
    deleteState: FvpDeleteState,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
    deleteLabel: String,
    savedResult: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        // D2 (plan 13): la accion secundaria vive pegada a su texto cuando hay aviso (leyenda
        // compacta inmediatamente encima); en el borrador vivo no hay aviso que mostrar. Sin
        // `onEdit` (fila no recalculable) no se pinta ni la leyenda ni el boton: no hay accion
        // que explique.
        val editAction = onEdit
        if (editAction != null) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (savedResult) {
                    Text(
                        stringResource(R.string.fvp_duplicate_notice),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("fvp-duplicate-notice"),
                    )
                }
                OutlinedButton(
                    onClick = editAction,
                    enabled = deleteState != FvpDeleteState.Deleting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (savedResult) R.string.fvp_duplicate_and_edit else R.string.fvp_edit,
                        ),
                    )
                }
            }
        }
        TextButton(
            onClick = onDelete,
            enabled = deleteState != FvpDeleteState.Deleting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fvp-delete"),
        ) {
            Text(deleteLabel)
        }
        Spacer(Modifier.height(Spacing.xs))
    }
}

/**
 * Footer FIJO de la pantalla: la accion primaria (Guardar, solo en borrador) con EXACTAMENTE la
 * misma habilitacion y callback que tenia, y los estados de operacion de guardado/borrado
 * (error de guardado, guardado, borrando, error de borrado) anunciados por live region.
 *
 * Es GLOBAL: se ve desde cualquier seccion y no depende del ciclo de vida de la pagina, de modo que
 * cambiar de pestana no dispara, cancela ni oculta una operacion. Las notas y el resto de acciones
 * (editar/duplicar, borrar/descartar) NO se fijan aqui: engordarian el footer y le quitarian el
 * viewport al contenido.
 */
@Composable
private fun FvpResultFooter(
    canCompute: Boolean,
    saveState: FvpSaveState,
    deleteState: FvpDeleteState,
    onSave: (() -> Unit)?,
) {
    val hasStatus = saveState == FvpSaveState.Error ||
        saveState == FvpSaveState.Saved ||
        deleteState == FvpDeleteState.Deleting ||
        deleteState == FvpDeleteState.Error
    if (onSave == null && !hasStatus) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.sm, bottom = Spacing.sm)
                .testTag("fvp-result-footer"),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            when (saveState) {
                FvpSaveState.Error -> InfoBanner(
                    title = stringResource(R.string.fvp_save_error),
                    // E2 (plan 13): el estado NO transporta texto tecnico; el banner lleva cuerpo
                    // vacio, como el resto de avisos de este modulo (`fvp_delete_error` incluido).
                    body = "",
                    warning = true,
                    // Muse P1-6: un fallo de guardado no puede comunicarse solo por color/texto.
                    modifier = Modifier
                        .testTag("fvp-save-error")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                FvpSaveState.Saved -> if (onSave != null) {
                    InfoBanner(
                        title = stringResource(R.string.fvp_saved),
                        body = "",
                    )
                }
                FvpSaveState.Idle, FvpSaveState.Saving -> Unit
            }
            when (deleteState) {
                // P2 (Wave 4a): estado visible y anunciado mientras la fila se esta borrando; la
                // pantalla no se puede abandonar hasta que llegue exito (onDeleted) o fallo.
                FvpDeleteState.Deleting -> InfoBanner(
                    title = stringResource(R.string.fvp_deleting),
                    body = "",
                    modifier = Modifier
                        .testTag("fvp-deleting")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                FvpDeleteState.Error -> InfoBanner(
                    title = stringResource(R.string.fvp_delete_error),
                    body = "",
                    modifier = Modifier
                        .testTag("fvp-delete-error")
                        // Muse P1-6: el fallo de borrado se anuncia, no solo se pinta.
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    warning = true,
                )
                FvpDeleteState.Idle, FvpDeleteState.Deleted -> Unit
            }
            if (onSave != null) {
                Button(
                    onClick = onSave,
                    enabled = canCompute && saveState != FvpSaveState.Saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("fvp-save"),
                ) {
                    Text(
                        stringResource(
                            if (saveState == FvpSaveState.Saving) R.string.common_saving else R.string.fvp_save,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Opcion del conmutador de ejecucion. La elegida se pinta como boton RELLENO y las dos anuncian su
 * estado a accesibilidad (`selected`), de modo que la vista mostrada no depende solo del color.
 */
@Composable
private fun FvpViewToggleOption(
    label: String,
    isSelected: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val optionModifier = modifier
        .heightIn(min = 48.dp)
        .testTag(tag)
        .semantics { selected = isSelected }
    if (isSelected) {
        Button(onClick = onClick, modifier = optionModifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = optionModifier) { Text(label) }
    }
}

/** Rotulo de la ejecucion mostrada (`fvp_view_original` / `fvp_view_recalculation`). */
private fun FvpResultView.labelResource(): Int = if (this == FvpResultView.ORIGINAL) {
    R.string.fvp_view_original
} else {
    R.string.fvp_view_recalculation
}

/**
 * Disponibilidad de la sensibilidad temporal ante el error de marcado del video (G-SCIENCE).
 *
 * A concise availability statement always accompanies the global quality interpretation.
 * This Quality disclosure retains its complete source-specific label and explanation. No range,
 * width or uncertainty number is exposed: the existing G-SCIENCE restriction is unchanged.
 */
@Composable
private fun FvpSensitivityNotice(availability: FvpSensitivityAvailability) {
    var expanded by rememberSaveable(availability) { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.result_temporal_sensitivity_expanded else R.string.result_temporal_sensitivity_collapsed)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("fvp-sensitivity-detail"),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = state }.testTag("fvp-sensitivity-toggle"),
            ) {
                Text(
                    stringResource(availability.labelResource()),
                    modifier = Modifier.weight(1f).testTag("fvp-sensitivity-label"),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(if (expanded) R.string.result_temporal_sensitivity_hide_details else R.string.result_temporal_sensitivity_show_details),
                    style = OpenJumpTypes.Label,
                )
            }
            if (expanded) {
                Text(
                    stringResource(availability.hintResource()),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-sensitivity-hint"),
                )
            }
        }
    }
}

/** Rotulo localizado de la disponibilidad de sensibilidad temporal (nunca el nombre del enum). */
private fun FvpSensitivityAvailability.labelResource(): Int = when (this) {
    FvpSensitivityAvailability.AVAILABLE_GATED -> R.string.fvp_sensitivity_available_gated
    FvpSensitivityAvailability.MISSING_FLIGHT_TIME -> R.string.fvp_sensitivity_missing_flight_time
    FvpSensitivityAvailability.MISSING_FPS -> R.string.fvp_sensitivity_missing_fps
    FvpSensitivityAvailability.NO_RECALCULATION -> R.string.fvp_sensitivity_no_recalculation
}

/** Explicacion en lenguaje llano del estado: que insumo falta y por que importa. */
private fun FvpSensitivityAvailability.hintResource(): Int = when (this) {
    FvpSensitivityAvailability.AVAILABLE_GATED -> R.string.fvp_sensitivity_available_gated_hint
    FvpSensitivityAvailability.MISSING_FLIGHT_TIME -> R.string.fvp_sensitivity_missing_flight_time_hint
    FvpSensitivityAvailability.MISSING_FPS -> R.string.fvp_sensitivity_missing_fps_hint
    FvpSensitivityAvailability.NO_RECALCULATION -> R.string.fvp_sensitivity_no_recalculation_hint
}

/**
 * Cabecera compacta de identidad de la serie (Muse P1-3): atleta, protocolo y fecha. Es de SOLO
 * lectura: cambiar de atleta/protocolo se hace en los pasos anteriores del flujo.
 *
 * [athleteName] es `null` cuando el nombre no se puede resolver (atleta archivado o sin dueno): se
 * pinta el marcador neutro compartido `athlete_unassigned`, nunca un nombre inventado.
 *
 * [bodyMassKg] es la masa corporal de la SERIE en su canonico (kg) y se pinta en la unidad del
 * perfil (kg/lb) como referencia humana: los valores relativos del modelo siguen siendo N/kg y
 * W/kg (SI), de modo que la cabecera explica de que masa salen sin insinuar que esten en lb.
 */
@Composable
private fun FvpSeriesIdentityHeader(
    athleteName: String?,
    protocolLabel: String,
    seriesDateTime: Long,
    bodyMassKg: Double,
) {
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("fvp-series-header"),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fvp_athlete),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    athleteName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.athlete_unassigned),
                    style = OpenJumpTypes.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("fvp-series-athlete"),
                )
            }
            bodyMassKg.takeIf { it.isFinite() && it > 0.0 }?.let { massKg ->
                Text(
                    stringResource(R.string.fvp_body_mass) + ": " + MeasurementFormatting.format(
                        massKg,
                        MeasurementQuantity.MASS_KG,
                        unitProfile,
                        locale,
                    ),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-series-body-mass"),
                )
            }
            Text(
                protocolLabel + " · " + Formatting.dateTimeToText(seriesDateTime),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-series-protocol-date"),
            )
        }
    }
}

@Composable
private fun FvpPointsTable(
    model: FvpResultUiModel,
    unitProfile: UnitProfile,
    locale: Locale,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.fvp_points_title), style = OpenJumpTypes.SectionTitle)
            if (model.points.isEmpty() && model.pointsUnavailableFromSource) {
                // P1 (S2): la tabla del ORIGINAL guardado esta vacia porque el snapshot no guarda
                // puntos por condicion; sin esta nota el lector concluiria que la serie no tiene
                // mediciones (las tiene, en el recalculo).
                Text(
                    stringResource(R.string.fvp_points_not_in_saved_snapshot),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-points-table-not-in-snapshot"),
                )
            }
            model.points.forEach { point ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(point.label, style = OpenJumpTypes.Body, fontWeight = FontWeight.SemiBold)
                        Text(
                            MeasurementFormatting.formatValue(
                                point.heightM * 100.0,
                                MeasurementQuantity.SHORT_LENGTH_CM,
                                unitProfile,
                                locale,
                                1,
                            ) + " " +
                                MeasurementFormatting.unit(MeasurementQuantity.SHORT_LENGTH_CM, unitProfile).symbol,
                            style = OpenJumpTypes.Secondary,
                        )
                    }
                    Text(
                        "F ${String.format(locale, "%.1f", point.forceN)} N · " +
                            "V ${String.format(locale, "%.2f", point.velocityMps)} m/s · " +
                            "P ${String.format(locale, "%.0f", point.powerW)} W",
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        listOfNotNull(
                            point.method?.let { stringResource(it.labelResource()) },
                            point.sourceFps?.let { String.format(locale, "%.1f", it) + " fps" },
                            stringResource(if (point.valid) R.string.fvp_attempt_valid else R.string.fvp_attempt_invalid),
                            point.exclusionReason,
                        ).joinToString(" · "),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun FvpResultUnavailable(body: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.fvp_title), style = OpenJumpTypes.ScreenTitle)
        Text(body, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.common_back))
        }
    }
}

@Composable
internal fun protocolLabel(protocol: FvpProtocol?): String = when (protocol) {
    FvpProtocol.SJ -> FvpProtocol.SJ.name
    FvpProtocol.CMJ -> stringResource(R.string.fvp_protocol_cmj_conditional)
    // Clave persistida no reconocida por esta versión: rótulo NEUTRO (existente), nunca SJ/CMJ.
    null -> stringResource(R.string.fvp_undefined_value)
}

private fun FvpMeasureMethod.labelResource(): Int = when (this) {
    FvpMeasureMethod.MANUAL -> R.string.fvp_method_manual
    FvpMeasureMethod.FLIGHT_TIME_VIDEO -> R.string.fvp_method_flight_time
    FvpMeasureMethod.IMPORTED -> R.string.fvp_method_imported
}

/**
 * Rango medido `[Vmin,Vmax]` SIEMPRE en SI (m/s), sin conversion de perfil.
 *
 * Regla vinculante (Wave 5): los resultados del MODELO no se convierten al perfil de unidades. La
 * unidad de Sfv (N·s/m) depende de la unidad de velocidad, de modo que pasar V a ft/s sin pasar
 * tambien Sfv y F0 daria un modelo internamente incoherente; ademas los valores persistidos y
 * exportados (y la literatura de referencia) estan en SI. Por eso esta ayuda usa la MISMA base que
 * las tarjetas V0 (m/s) y que los ejes de las graficas, tanto en perfil metrico como en US.
 */
@Composable
private fun measurementRange(
    model: FvpResultUiModel,
    locale: Locale,
): String {
    if (!model.vMinMps.isFinite() || !model.vMaxMps.isFinite()) {
        return stringResource(R.string.fvp_undefined_value)
    }
    val min = String.format(locale, "%.2f", model.vMinMps)
    val max = String.format(locale, "%.2f", model.vMaxMps)
    return "$min–$max m/s"
}

/**
 * Rango de FUERZA medido `[Fmin,Fmax]` de la ejecucion mostrada, SIEMPRE en N (unidad del modelo: los
 * resultados no se convierten al perfil de unidades, regla de la Wave 5). Sin numeros en esa
 * ejecucion se declara `fvp_undefined_value`, nunca un 0.0 sustituto.
 */
@Composable
private fun forceRange(
    model: FvpResultUiModel,
    locale: Locale,
): String {
    if (!model.fMinN.isFinite() || !model.fMaxN.isFinite()) {
        return stringResource(R.string.fvp_undefined_value)
    }
    val min = String.format(locale, "%.1f", model.fMinN)
    val max = String.format(locale, "%.1f", model.fMaxN)
    return "$min–$max N"
}

@Composable
private fun undefinedText(): String = stringResource(R.string.fvp_undefined_value)

@Composable
private fun forceText(value: Double, locale: Locale): String =
    if (value.isFinite()) String.format(locale, "%.1f", value) + " N" else undefinedText()

@Composable
private fun sfvText(value: Double, locale: Locale): String =
    if (value.isFinite()) String.format(locale, "%.2f", value) + " N·s/m" else undefinedText()

@Composable
private fun speedText(value: Double?, locale: Locale): String =
    if (value != null && value.isFinite()) String.format(locale, "%.2f", value) + " m/s" else undefinedText()

@Composable
private fun powerText(value: Double?, locale: Locale): String =
    if (value != null && value.isFinite()) String.format(locale, "%.0f", value) + " W" else undefinedText()

@Composable
private fun r2Text(value: Double?, locale: Locale): String =
    if (value != null && value.isFinite()) String.format(locale, "%.3f", value) else undefinedText()

@Composable
private fun relativeText(value: Double?, unit: String, locale: Locale): String =
    if (value != null && value.isFinite()) String.format(locale, "%.2f", value) + " $unit" else undefinedText()

/** Etiqueta localizada de un aviso de UI; reutilizada por el historial (comparacion con P1-4). */
internal fun FvpUiWarning.messageRes(): Int = when (this) {
    FvpUiWarning.FEW_POINTS -> R.string.fvp_warning_few_points
    FvpUiWarning.DIAGNOSTIC_ONLY -> R.string.fvp_warning_diagnostic_only
    FvpUiWarning.NARROW_VELOCITY_RANGE -> R.string.fvp_warning_narrow_range
    FvpUiWarning.LOW_R2 -> R.string.fvp_warning_low_r2
    FvpUiWarning.NON_PHYSICAL -> R.string.fvp_warning_non_physical
    FvpUiWarning.HIGH_EXTRAPOLATION -> R.string.fvp_warning_high_extrapolation
    FvpUiWarning.FPS_BLOCKED -> R.string.fvp_warning_fps_blocked
    FvpUiWarning.TIMING_CONDITIONAL -> R.string.fvp_warning_timing_conditional
    FvpUiWarning.TIMING_UNKNOWN -> R.string.fvp_warning_timing_unknown
    FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE -> R.string.fvp_warning_push_off_range
    FvpUiWarning.SNAPSHOT_RECALCULATED -> R.string.fvp_warning_snapshot_recalculated
    FvpUiWarning.SNAPSHOT_UNAVAILABLE -> R.string.fvp_warning_snapshot_unavailable
    FvpUiWarning.R2_UNDEFINED -> R.string.fvp_warning_r2_undefined
    FvpUiWarning.PUSH_OFF_MANUAL_SOURCE -> R.string.fvp_warning_push_off_manual_source
    FvpUiWarning.UNCLASSIFIED_WARNING -> R.string.fvp_warning_unclassified
    // B4b: procedencia de la fila guardada (masa, carga, tiempo y contexto de adquisicion).
    FvpUiWarning.BODY_MASS_DECLARED -> R.string.fvp_warning_body_mass_declared
    FvpUiWarning.BODY_MASS_UNKNOWN -> R.string.fvp_warning_body_mass_unknown
    FvpUiWarning.ADDED_LOAD_NOMINAL -> R.string.fvp_warning_added_load_nominal
    FvpUiWarning.ADDED_LOAD_UNKNOWN -> R.string.fvp_warning_added_load_unknown
    FvpUiWarning.TIME_MAPPING_UNVERIFIED -> R.string.fvp_warning_time_mapping_unverified
    FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN -> R.string.fvp_warning_acquisition_context_unknown
    FvpUiWarning.ACQUISITION_CONTEXT_COMPOSITE -> R.string.fvp_warning_acquisition_context_composite
}

/**
 * Texto listo para pintar de un aviso de UI, con los parametros que cada mensaje necesite.
 *
 * PUNTO UNICO de formateo de los avisos `fvp_warning_*` cuyos parametros dependen del perfil de
 * unidades: `fvp_warning_push_off_range` lleva el rango recomendado como parametro (`%1$s`)
 * formateado en la unidad del CAMPO de h_po (longitud corta del perfil: cm/in), de modo que un
 * usuario de EE. UU. lee el limite en pulgadas y no en metros. Llamar a `stringResource(id)` sin
 * argumentos dejaria el `%1$s` LITERAL en pantalla: por eso TODOS los consumidores de avisos
 * (resultado vivo/guardado, setup e historial) pintan con este helper, que ademas es el unico
 * sitio donde se aplica la conversion de perfil a un texto de aviso.
 *
 * Los motivos BLOQUEANTES (`fvp_block_*`) no pasan por aqui: su parametro lo aporta
 * `pushOffDomainRangeText` en el mismo punto donde se calcula el bloqueo.
 */
@Composable
internal fun fvpWarningText(warning: FvpUiWarning): String = when (warning) {
    FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE -> stringResource(
        R.string.fvp_warning_push_off_range,
        pushOffRecommendedRangeText(LocalUnitSystem.current, currentAppLocale()),
    )
    else -> stringResource(warning.messageRes())
}

/** Snapshot persistido de una evaluacion guardada (los valores que se muestran al reabrirla). */
private fun StoredFvpAssessment.storedSnapshot(): FvpStoredSnapshot = FvpStoredSnapshot(
    f0N = assessment.f0N,
    slopeFv = assessment.slopeFv,
    sfvNsPerM = assessment.sfvNsPerM,
    v0Mps = assessment.v0Mps,
    pmaxW = assessment.pmaxW,
    r2 = assessment.r2,
    conditionCount = assessment.conditionCount,
    vMinMps = assessment.vMinMps,
    vMaxMps = assessment.vMaxMps,
    fMinN = assessment.fMinN,
    fMaxN = assessment.fMaxN,
    f0RelNPerKg = assessment.f0RelNPerKg,
    pmaxRelWPerKg = assessment.pmaxRelWPerKg,
)
