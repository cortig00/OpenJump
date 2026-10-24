package com.openjump.app.ui.fvp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.fvp.RECOMMENDED_MAX_PUSH_OFF_M
import com.openjump.app.fvp.RECOMMENDED_MIN_PUSH_OFF_M
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.components.AthleteSelector
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.MAX_JUMP_HEIGHT_M
import com.openjump.fvp.MAX_PUSH_OFF_M
import com.openjump.fvp.MIN_PUSH_OFF_M
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Entrada del flujo F-V-P, con TRES paginas LOCALES de un unico dueño de estado:
 * [FvpSetupPage.INTRO] (explicacion breve de primer uso, saltable y reabrible),
 * [FvpSetupPage.DATA] (atleta, protocolo, masa y su procedencia) y [FvpSetupPage.MEASUREMENT]
 * (h_po declarado, hito, procedimiento, fecha y componentes opcionales).
 *
 * Las paginas NO son destinos de navegacion: no hay rutas nuevas ni un segundo dueño. Todos los
 * `rememberSaveable`, sus `Saver` y la re-base de unidades siguen declarados AQUI, de modo que
 * cambiar de pagina (o volver) conserva exactamente el texto tecleado, las unidades y la
 * procedencia. DATA valida SOLO sus datos antes de pasar a MEDICION y no confirma nada en el
 * borrador; la unica confirmacion (`commitSetupDraft`) sigue en la accion final de MEDICION con los
 * gates completos de siempre.
 *
 * Decisiones vinculantes:
 * - SJ por defecto; CMJ se ofrece marcado como condicional (nunca se mezclan en una misma serie).
 * - La masa corporal se prellena desde el perfil como SUGERENCIA editable; no se vincula en vivo y
 *   la procedencia pasa a `MANUAL` en cuanto se edita.
 * - h_po SIEMPRE declarado (hito + procedimiento + fecha); no existe ningun campo de estatura y el
 *   avance queda bloqueado sin h_po comun en modo COMMON y sin hito/procedimiento (gate B1: la
 *   fecha se rellena por defecto con hoy y siempre es visible/editable).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FvpSetupScreen(
    activeAthletes: List<AthleteEntity>,
    athletesLoading: Boolean,
    selectedAthleteId: Long?,
    onAthleteSelected: (Long) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onHistory: () -> Unit,
    /**
     * Dueño UNICO del estado recuperable del flujo (D6, R2). En la app real `AppNav` pasa SIEMPRE
     * la instancia que creo una sola vez en el `NavHost`; `null` queda para montajes aislados de
     * esta pantalla (tests de UI que no arrancan el dueño), y entonces no hay aviso que mostrar.
     */
    flowViewModel: FvpFlowViewModel? = null,
    /**
     * Preferencia de UI (no evidencia) que recuerda si la introduccion ya se vio. `null` usa el
     * fichero privado propio ([FvpIntroductionPreferences]); los tests montan un doble en memoria.
     * No es un modo de compatibilidad: las paginas siempre son INTRO/DATA/MEASUREMENT.
     */
    introductionStore: FvpIntroductionStore? = null,
) {
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    // Preferencia de presentacion: se LEE una vez por instancia (y se restaura con el estado
    // guardado). No toca el borrador, ni Room, ni el backup de datos.
    val context = LocalContext.current
    val introStore = introductionStore ?: remember(context) { FvpIntroductionPreferences(context) }
    var introductionSeen by rememberSaveable { mutableStateOf(introStore.hasSeenIntroduction()) }
    var pageName by rememberSaveable {
        mutableStateOf(
            if (introductionSeen) FvpSetupPage.DATA.name else FvpSetupPage.INTRO.name,
        )
    }
    val page = runCatching { FvpSetupPage.valueOf(pageName) }.getOrDefault(FvpSetupPage.DATA)
    // Pagina a la que vuelve la introduccion reabierta como AYUDA (null = intro de primer uso).
    var introReturnName by rememberSaveable { mutableStateOf<String?>(null) }
    val introReturnPage = introReturnName?.let { name ->
        runCatching { FvpSetupPage.valueOf(name) }.getOrNull()
    }
    // Aviso del dueño (p. ej. "este borrador no tiene recuperacion"): la pantalla NO lo deduce por
    // su cuenta ni lo reconstruye, solo lo pinta.
    val recoveryNotice = if (flowViewModel != null) {
        val notice by flowViewModel.recoveryNotice.collectAsState()
        notice
    } else {
        FvpRecoveryNotice.None
    }
    // A2: TODA mutacion externa del borrador pasa por el DUEÑO cuando existe (`AppNav` lo pasa
    // siempre). El fallback directo a la sesion queda SOLO para montajes aislados de esta pantalla
    // (tests de UI sin dueño) y NUNCA crea un segundo dueño.
    val mutations: FvpDraftMutations = flowViewModel ?: FvpDraftSession
    // Se observa el StateFlow (nunca `.value` dentro de composicion: lint
    // StateFlowValueCalledInComposition); los `rememberSaveable` de abajo lo usan solo como semilla.
    val liveDraft by FvpDraftSession.draft.collectAsState()

    var setupAthleteId by rememberSaveable { mutableStateOf(selectedAthleteId) }
    // El protocolo es una ELECCION de la serie (chips), no un campo del borrador: se siembra del
    // borrador vivo SOLO si es de ESTE atleta, porque el protocolo forma parte de la identidad de
    // la serie y no puede venir de otro atleta (el selector de atleta no debe cambiar el protocolo
    // elegido: esta clave no incluye el atleta a proposito).
    var protocolName by rememberSaveable {
        mutableStateOf(
            liveDraft?.takeIf { it.athleteId == setupAthleteId }?.protocol?.name ?: FvpProtocol.SJ.name,
        )
    }
    val protocol = runCatching { FvpProtocol.valueOf(protocolName) }.getOrDefault(FvpProtocol.SJ)
    // Semilla UNICA de los campos del paso 1: el borrador vivo SOLO si pertenece a la identidad de
    // ESTA serie (atleta elegido + protocolo elegido). Un borrador de otro atleta/protocolo sigue
    // vivo en el proceso (p. ej. se abrio una serie guardada de A y despues se entra al paso 1 para
    // B) pero NO es de esta serie: no puede sembrar ningun campo (masa, h_po, hito, procedimiento,
    // fecha, componentes ni modo de h_po) ni sostener la procedencia de la masa, porque
    // `commitSetupDraft` empieza una serie NUEVA en ese caso.
    val seriesSeed = liveDraft.seriesSeedFor(setupAthleteId, protocol)
    // Una sustitucion de sesion (p. ej. duplicar otra serie del mismo atleta/protocolo)
    // no puede recibir campos locales de la anterior. El commit propio actualiza la clave
    // ANTES de navegar, conservando en cambio el texto exacto al volver a la MISMA sesion.
    var formSessionKey by rememberSaveable(setupAthleteId, protocol) {
        mutableStateOf(seriesSeed?.sessionKey)
    }
    var formEpoch by rememberSaveable(setupAthleteId, protocol) { mutableStateOf(0) }
    if (formSessionKey != seriesSeed?.sessionKey) {
        formSessionKey = seriesSeed?.sessionKey
        formEpoch += 1
        if (page != FvpSetupPage.INTRO) pageName = FvpSetupPage.DATA.name
        if (introReturnName != null) introReturnName = FvpSetupPage.DATA.name
    }
    // Peso ACTUAL del perfil del atleta elegido, normalizado (`null` si no es utilizable): fuente
    // unica de la sugerencia de masa y de la procedencia honesta.
    val selectedProfileMassKg = fvpUsableProfileMassKg(
        activeAthletes.firstOrNull { it.id == setupAthleteId }?.weightKg,
    )

    // Campos numericos: identidad de la serie (`setupAthleteId` + `protocol`) como CLAVE de
    // re-siembra (un borrador de otra identidad no siembra nada y una serie de este atleta se
    // siembra desde [seriesSeed]). El perfil de unidades y el locale NO son claves: cambiarlos no
    // puede re-sembrar desde el borrador, porque eso descartaria en silencio el texto tecleado y
    // aun no confirmado (regresion U2/D1). La transicion se resuelve RE-BASANDO cada campo con su
    // canonico exacto ([FvpSetupNumber.rebase]) en el efecto de abajo.
    var massInput by rememberSaveable(setupAthleteId, protocol, formEpoch, stateSaver = FvpSetupNumber.Saver) {
        mutableStateOf(
            FvpSetupNumber.seeded(seriesSeed?.bodyMassKg, FvpSetupNumberQuantity.MASS_KG, unitProfile, locale),
        )
    }
    // `massTouched` significa UNICAMENTE "el usuario ha tecleado en el campo". La procedencia de
    // una serie ya sembrada (duplicada) no depende de esta bandera: la resuelve
    // `fvpBodyMassProvenanceIsFromProfile` con la procedencia de ORIGEN del borrador. Se re-inicia
    // con la identidad para que la sugerencia del peso del atleta nuevo no quede suprimida por una
    // edicion hecha en la serie de otro atleta.
    var massTouched by rememberSaveable(setupAthleteId, protocol, formEpoch) { mutableStateOf(false) }
    // B4a-1: procedencia DECLARADA de la masa. Se siembra SOLO desde un borrador de ESTA identidad
    // ([seriesSeed]) y NUNCA se infiere del valor escrito, del perfil, de la fecha de guardado ni de
    // los fps: una serie nueva arranca en `UNKNOWN` (desconocido) y lo que ya venia desconocido jamas
    // se promueve por continuar.
    var bodyMassMeasurementName by rememberSaveable(setupAthleteId, protocol, formEpoch) {
        mutableStateOf(
            FvpBodyMassMeasurementUi.fromStorageKey(seriesSeed?.bodyMassMeasurement)?.storageKey
                ?: FvpBodyMassMeasurementUi.UNKNOWN.storageKey,
        )
    }
    // HANDOFF DE CONTEXTO A CONDICIONES (slice B, explicito): el contexto de adquisicion
    // (`acquisitionContextKind` + `acquisitionContextKey`) DEJA de tener copia editable aqui. Su
    // dueño pasa a la pantalla de Condiciones y su gate SAME_ACQUISITION (clave declarada
    // obligatoria) se aplicara ALLI, antes de calcular. Esta pantalla ya no lo escribe: el commit
    // final NO toca el contexto, de modo que volver atras no puede sobrescribir lo que el usuario
    // declare despues en Condiciones. Mientras el slice B no exista, el contexto de la serie sigue
    // siendo el que ya tenga el borrador vivo (`UNKNOWN` en una serie nueva) y no se infiere nada.
    var pushOffModeName by rememberSaveable(setupAthleteId, protocol, formEpoch) {
        mutableStateOf(seriesSeed?.pushOffMode?.storageKey ?: FvpPushOffUiMode.COMMON.storageKey)
    }
    var pushOffInput by rememberSaveable(setupAthleteId, protocol, formEpoch, stateSaver = FvpSetupNumber.Saver) {
        mutableStateOf(
            FvpSetupNumber.seeded(seriesSeed?.commonPushOffM, FvpSetupNumberQuantity.PUSH_OFF_M, unitProfile, locale),
        )
    }
    var landmark by rememberSaveable(setupAthleteId, protocol, formEpoch) {
        mutableStateOf(seriesSeed?.pushOffLandmark.orEmpty())
    }
    var procedure by rememberSaveable(setupAthleteId, protocol, formEpoch) {
        mutableStateOf(seriesSeed?.pushOffProtocol.orEmpty())
    }
    // La fecha de medicion SIEMPRE tiene valor (hoy por defecto) y es visible/editable con el
    // `DatePicker` existente: es uno de los tres metadatos de h_po medido (gate B1, P1-1).
    var measuredAt by rememberSaveable(setupAthleteId, protocol, formEpoch) {
        mutableStateOf(seriesSeed?.pushOffMeasuredAt ?: todayUtcMillis())
    }
    var crouchInput by rememberSaveable(setupAthleteId, protocol, formEpoch, stateSaver = FvpSetupNumber.Saver) {
        mutableStateOf(
            FvpSetupNumber.seeded(
                seriesSeed?.pushOffCrouchCm,
                FvpSetupNumberQuantity.SHORT_LENGTH_CM,
                unitProfile,
                locale,
            ),
        )
    }
    var extendedInput by rememberSaveable(setupAthleteId, protocol, formEpoch, stateSaver = FvpSetupNumber.Saver) {
        mutableStateOf(
            FvpSetupNumber.seeded(
                seriesSeed?.pushOffExtendedCm,
                FvpSetupNumberQuantity.SHORT_LENGTH_CM,
                unitProfile,
                locale,
            ),
        )
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    // Los COMPONENTES opcionales arrancan CONTRADOS: la ruta por defecto conserva h_po, hito,
    // procedimiento y fecha (decision P1-A1 del informe de UX). La fecha ya NO depende de este
    // plegable: se muestra siempre junto a hito/procedimiento.
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable(setupAthleteId, protocol, formEpoch) {
        mutableStateOf(pushOffModeName == FvpPushOffUiMode.PER_CONDITION.storageKey)
    }
    // Ayuda de procedencia de la masa BAJO DEMANDA: el dato y su pregunta quedan visibles; la
    // explicacion larga y el ejemplo no forman un muro de texto.
    var massHelpExpanded by rememberSaveable { mutableStateOf(false) }

    // Transicion de UNIDADES o de LOCALE: cada campo se re-basa con su canonico exacto (el texto
    // viejo se parsea con la base VIEJA y se formatea con la nueva). Un texto parcial/invalido se
    // conserva y enciende el latch de revision; no se convierte en vacio, cero ni en la semilla.
    LaunchedEffect(unitProfile, locale) {
        massInput = FvpSetupNumber.rebase(massInput, unitProfile, locale)
        pushOffInput = FvpSetupNumber.rebase(pushOffInput, unitProfile, locale)
        crouchInput = FvpSetupNumber.rebase(crouchInput, unitProfile, locale)
        extendedInput = FvpSetupNumber.rebase(extendedInput, unitProfile, locale)
    }
    // Los componentes viven en el desplegable: si alguno queda en revision hay que ENSENAR el
    // aviso, no dejar el boton bloqueado por un campo oculto sin explicacion.
    LaunchedEffect(crouchInput.requiresReview, extendedInput.requiresReview) {
        if (crouchInput.requiresReview || extendedInput.requiresReview) detailsExpanded = true
    }

    LaunchedEffect(selectedAthleteId, activeAthletes) {
        if (activeAthletes.none { it.id == setupAthleteId }) {
            setupAthleteId = selectedAthleteId?.takeIf { id -> activeAthletes.any { it.id == id } }
        }
    }
    // One-shot suggestion per SELECTION identity (atleta + protocolo): never a live link to the
    // profile weight, and NEVER over a draft of this same identity (D2): "duplicar y editar"
    // conserva la masa de la serie guardada, aunque el peso del perfil del atleta haya cambiado
    // desde entonces. Un borrador de OTRA identidad no llega aqui (la semilla ya viene acotada).
    LaunchedEffect(setupAthleteId, protocol, formEpoch) {
        val profileMassKg = selectedProfileMassKg ?: return@LaunchedEffect
        if (!fvpShouldSuggestProfileMass(seriesSeed, profileMassKg, massTouched)) return@LaunchedEffect
        // La sugerencia SUSTITUYE texto y semilla A LA VEZ para que un campo sin tocar resuelva
        // exactamente el peso del perfil (misma semantica `bodyMassFromProfile`).
        massInput = FvpSetupNumber.seeded(profileMassKg, FvpSetupNumberQuantity.MASS_KG, unitProfile, locale)
    }

    val massRequiresReview = massInput.requiresReview
    val massBasisPending = massInput.isBasisPending(unitProfile, locale)
    val bodyMassKg = massInput.canonical(unitProfile, locale)
    val pushOffRequiresReview = pushOffInput.requiresReview
    val pushOffBasisPending = pushOffInput.isBasisPending(unitProfile, locale)
    val crouchRequiresReview = crouchInput.requiresReview
    val extendedRequiresReview = extendedInput.requiresReview
    // Procedencia de la masa sin tocar el campo: con un borrador de ESTA identidad se CONSERVA la de
    // origen (serie duplicada: nunca el peso de hoy) y en una serie NUEVA solo se marca `PROFILE`
    // si el valor ES el peso actual del perfil del atleta elegido (un valor heredado de otra serie
    // no puede reclamar el perfil: seria una procedencia falsa). Teclear el campo la pasa a
    // `MANUAL`.
    val bodyMassFromProfile = fvpBodyMassProvenanceIsFromProfile(
        matchingDraft = seriesSeed,
        currentProfileMassKg = selectedProfileMassKg,
        bodyMassKg = bodyMassKg,
        massTouchedByUser = massTouched,
    )
    // B4a-1: procedencia declarada que se va a guardar. `UNKNOWN` es el default honesto y una clave
    // guardada que no pertenece al vocabulario de la capa de datos NO se reinterpreta (se queda
    // como desconocido).
    val bodyMassMeasurement = FvpBodyMassMeasurementUi.fromStorageKey(bodyMassMeasurementName)
        ?: FvpBodyMassMeasurementUi.UNKNOWN
    val pushOffMode = FvpPushOffUiMode.fromStorageKey(pushOffModeName) ?: FvpPushOffUiMode.COMMON
    val commonPushOffM = pushOffInput.canonical(unitProfile, locale)
    val crouchCm = crouchInput.canonical(unitProfile, locale)
    val extendedCm = extendedInput.canonical(unitProfile, locale)
    val athleteSelected = setupAthleteId != null && activeAthletes.any { it.id == setupAthleteId }
    // Campo de masa corporal: error si falta, no es finita o no es > 0. La elegibilidad ya rechaza
    // masa <= 0; aqui se avisa EN EL CAMPO en vez de diferirlo al paso 2. Un campo en revision (texto
    // parcial bajo unas unidades anteriores) no es "masa invalida": se queda con el latch y su
    // propio aviso, sin pintar dos errores a la vez ni juzgar la ventana de re-base.
    val bodyMassInvalid = !massRequiresReview && !massBasisPending &&
        (bodyMassKg == null || !bodyMassKg.isFinite() || bodyMassKg <= 0.0)
    val pushOffMissing = pushOffMode == FvpPushOffUiMode.COMMON && commonPushOffM == null &&
        !pushOffRequiresReview && !pushOffBasisPending
    // h_po declarado dentro del dominio de ingenieria (`FvpCalculator`): (0,05, 0,80] m.
    val usablePushOffM = commonPushOffM?.takeIf { isUsablePushOff(it) }
    // Con el campo VACIO no se pinta error de rango: el pie de pantalla conserva la ayuda de
    // "falta el valor". Escribir algo que no parsea o queda fuera del dominio SÍ es un error.
    val pushOffOutOfDomain = pushOffMode == FvpPushOffUiMode.COMMON && !pushOffRequiresReview &&
        !pushOffBasisPending && pushOffInput.text.isNotBlank() && usablePushOffM == null
    // Fuera de la ventana RECOMENDADA (0,20-0,60 m): ayuda NO bloqueante, nunca un error.
    val pushOffOutsideRecommended = pushOffMode == FvpPushOffUiMode.COMMON && usablePushOffM != null &&
        (usablePushOffM < RECOMMENDED_MIN_PUSH_OFF_M || usablePushOffM > RECOMMENDED_MAX_PUSH_OFF_M)
    // Gate B1/P1-1: h_po solo puede declararse MEDIDO con hito anatomico Y procedimiento no
    // vacios (la fecha ya viene puesta por defecto). Sin ellos no se avanza: la serie quedaria con
    // origen MANUAL y no cumple el requisito de h_po medido.
    val pushOffMetadataMissing = landmark.isBlank() || procedure.isBlank()
    // Un campo en REVISION (o esperando la re-base del cambio de unidades) bloquea el avance: el
    // contrato U2 exige una edicion explicita bajo las unidades actuales antes de confirmar nada.
    // El h_po comun solo bloquea cuando su valor forma parte de la serie (modo COMMON); en modo
    // PER_CONDITION el contenido de ese campo no se confirma y no puede bloquear a ciegas.
    val pushOffNeedsReview = pushOffMode == FvpPushOffUiMode.COMMON &&
        (pushOffRequiresReview || pushOffBasisPending)
    // Componentes opcionales: el mismo latch de revision que ya bloquea el avance (se conserva la
    // expresion de `canContinue` en una sola declaracion por cada campo).
    val crouchBasisPending = crouchInput.isBasisPending(unitProfile, locale)
    val extendedBasisPending = extendedInput.isBasisPending(unitProfile, locale)
    // Gate de DATA: SOLO los datos de esta pagina (atleta, protocolo elegido y masa utilizable).
    // No incluye metadatos de h_po ni nada de MEDICION: pasar de pagina no confirma ni exige la
    // serie completa, y por tanto no hay commit parcial.
    val dataComplete = athleteSelected && !athletesLoading && !bodyMassInvalid &&
        !massRequiresReview && !massBasisPending
    // Gate de MEDICION: los gates COMPLETOS de siempre (B1) menos el de la clave de contexto, que se
    // traslada a Condiciones con el contexto (slice B, ver comentario del estado editable).
    val measurementComplete = !pushOffOutOfDomain && !pushOffMissing && !pushOffMetadataMissing &&
        !pushOffNeedsReview && !crouchRequiresReview && !extendedRequiresReview &&
        !crouchBasisPending && !extendedBasisPending
    // La accion FINAL (commit + CONTINUAR a Saltos) exige las dos paginas: si el usuario vuelve a
    // DATA y deja la masa invalida, no se confirma una serie que ya no cumple el gate.
    val canContinue = dataComplete && measurementComplete
    // Pie ACCIONABLE: el primer pendiente se deriva de los MISMOS booleanos resueltos arriba (no hay
    // un segundo gate ni se recalcula nada) para decir un motivo corto y exacto y ofrecer una accion
    // secundaria que DESPLAZA hasta ese campo. Revisar no muta el borrador, no llama a CONTINUAR y
    // no cambia `canContinue`.
    val firstPending = if (athletesLoading) null else fvpFirstSetupPending(
        athleteSelected = athleteSelected,
        massNeedsReview = massRequiresReview || massBasisPending,
        bodyMassInvalid = bodyMassInvalid,
        pushOffNeedsReview = pushOffNeedsReview,
        pushOffOutOfDomain = pushOffOutOfDomain,
        pushOffMissing = pushOffMissing,
        pushOffMetadataMissing = pushOffMetadataMissing,
        landmarkMissing = landmark.isBlank(),
        // El gate de la clave de contexto NO se evalua en esta pantalla: su dueño pasa a
        // Condiciones (slice B) junto con el contexto declarado. Se pasa `false` de forma explicita
        // para que el orden del pie no cambie y el handoff quede visible.
        acquisitionContextKeyMissing = false,
        crouchNeedsReview = crouchRequiresReview || crouchBasisPending,
        extendedNeedsReview = extendedRequiresReview || extendedBasisPending,
    )
    // Anclas de desplazamiento: llevar al campo SIN pedir foco ni abrir el teclado (no se toca la
    // composicion de texto ni se mueve el cursor de ningun campo).
    val athleteAnchor = remember { BringIntoViewRequester() }
    val bodyMassAnchor = remember { BringIntoViewRequester() }
    val pushOffAnchor = remember { BringIntoViewRequester() }
    val landmarkAnchor = remember { BringIntoViewRequester() }
    val procedureAnchor = remember { BringIntoViewRequester() }
    val crouchAnchor = remember { BringIntoViewRequester() }
    val extendedAnchor = remember { BringIntoViewRequester() }
    // Contador de UI (no recuperable): tras restaurar la instancia NO se desplaza solo; la
    // accion solo actua cuando el usuario la pulsa.
    var reviewRequest by remember { mutableStateOf(0) }
    LaunchedEffect(reviewRequest) {
        if (reviewRequest == 0) return@LaunchedEffect
        val target = firstPending ?: return@LaunchedEffect
        // Un componente opcional no se puede revisar mientras esta plegado: se despliega PRIMERO.
        if (target.expandsOptionalComponents) detailsExpanded = true
        // Dos frames: el contenido recien desplegado ya esta compuesto y medido. Solo scroll.
        withFrameNanos { }
        withFrameNanos { }
        val anchor = when (target) {
            FvpSetupPending.ATHLETE -> athleteAnchor
            FvpSetupPending.BODY_MASS -> bodyMassAnchor
            FvpSetupPending.PUSH_OFF -> pushOffAnchor
            FvpSetupPending.PUSH_OFF_METADATA -> landmarkAnchor
            FvpSetupPending.PUSH_OFF_PROCEDURE -> procedureAnchor
            FvpSetupPending.OPTIONAL_CROUCH -> crouchAnchor
            FvpSetupPending.OPTIONAL_EXTENDED -> extendedAnchor
            // Su campo ya no se compone en esta pantalla (handoff a Condiciones, slice B): no hay
            // ancla que desplazar y la accion de revisar no se ofrece para este pendiente.
            FvpSetupPending.ACQUISITION_CONTEXT_KEY -> null
        }
        anchor?.bringIntoView()
    }

    // NAVEGACION LOCAL DEL PASO: el dueño del estado es esta misma pantalla.
    // - MEDICION vuelve a DATA sin confirmar nada;
    // - DATA sale del flujo (su pagina anterior real es el destino que abrio el paso);
    // - INTRO reabierta como ayuda vuelve a la pagina desde la que se abrio.
    // El boton de la barra superior y el gesto/tecla del sistema hacen EXACTAMENTE lo mismo.
    val navigateBack: () -> Unit = {
        when (page) {
            FvpSetupPage.INTRO -> {
                val returnPage = introReturnPage
                if (returnPage != null) {
                    pageName = returnPage.name
                    introReturnName = null
                } else {
                    onBack()
                }
            }
            FvpSetupPage.DATA -> onBack()
            FvpSetupPage.MEASUREMENT -> pageName = FvpSetupPage.DATA.name
        }
    }
    BackHandler { navigateBack() }
    val openDataFromIntro: () -> Unit = {
        if (!introductionSeen) {
            introStore.markIntroductionSeen()
            introductionSeen = true
        }
        introReturnName = null
        pageName = FvpSetupPage.DATA.name
    }
    val pendingOnThisPage = firstPending?.let { pending ->
        when (page) {
            FvpSetupPage.DATA -> pending == FvpSetupPending.ATHLETE || pending == FvpSetupPending.BODY_MASS
            FvpSetupPage.MEASUREMENT ->
                pending != FvpSetupPending.ATHLETE && pending != FvpSetupPending.BODY_MASS
            FvpSetupPage.INTRO -> false
        }
    } ?: false
    val pageBlocked = when (page) {
        FvpSetupPage.INTRO -> false
        FvpSetupPage.DATA -> !dataComplete
        FvpSetupPage.MEASUREMENT -> !canContinue
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.fvp_title),
                onNavigationClick = navigateBack,
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    if (page != FvpSetupPage.INTRO) {
                        // Reabrir la introduccion es una AYUDA: no reinicia el flujo, no cambia la
                        // preferencia y su accion vuelve a la pagina actual.
                        IconButton(
                            onClick = {
                                introReturnName = page.name
                                pageName = FvpSetupPage.INTRO.name
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-intro-action"),
                        ) {
                            Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.fvp_intro_reopen))
                        }
                    }
                    TextButton(
                        onClick = onHistory,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-history-action"),
                    ) {
                        Text(stringResource(R.string.history_title))
                    }
                },
            )
        },
        bottomBar = {
            if (page != FvpSetupPage.INTRO) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                ) {
                    // Pie COMPACTO: un unico motivo breve de incompletitud, no el muro de avisos que
                    // empujaba los campos. Cada bloqueo concreto vive junto a SU campo, sigue anunciado
                    // por live region y sigue siendo alcanzable con scroll. El gate de la accion es el
                    // de SU pagina (DATA: solo datos; MEDICION: gates completos + commit final).
                    if (pageBlocked) {
                        Text(
                            text = firstPending?.takeIf { pendingOnThisPage }?.let { stringResource(it.reasonRes) }
                                ?: stringResource(R.string.fvp_setup_incomplete_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(bottom = Spacing.xs)
                                // Muse P1-6: el estado bloqueante no puede ser solo visual.
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("fvp-setup-incomplete-hint"),
                        )
                        if (pendingOnThisPage) {
                            TextButton(
                                onClick = { reviewRequest += 1 },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("fvp-setup-review-pending"),
                            ) {
                                Text(stringResource(R.string.fvp_setup_review_action))
                            }
                        }
                    }
                    if (page == FvpSetupPage.DATA) {
                        // DATA -> MEDICION: valida SOLO los datos de esta pagina y NO confirma nada
                        // en el borrador (ningun commit parcial de la serie).
                        Button(
                            onClick = { pageName = FvpSetupPage.MEASUREMENT.name },
                            enabled = dataComplete,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .testTag("fvp-setup-continue-data"),
                        ) {
                            Text(stringResource(R.string.fvp_setup_continue_measurement))
                        }
                    } else {
                        Button(
                            onClick = {
                                val athleteId = setupAthleteId
                                val mass = bodyMassKg
                                if (athleteId != null && mass != null) {
                                    val committed = commitSetupDraft(
                                        mutations = mutations,
                                        expectedSessionKey = formSessionKey,
                                        athleteId = athleteId,
                                        protocol = protocol,
                                        bodyMassKg = mass,
                                        bodyMassFromProfile = bodyMassFromProfile,
                                        pushOffMode = pushOffMode,
                                        commonPushOffM = commonPushOffM,
                                        landmark = landmark,
                                        procedure = procedure,
                                        measuredAt = measuredAt,
                                        crouchCm = crouchCm,
                                        extendedCm = extendedCm,
                                        bodyMassMeasurement = bodyMassMeasurement.storageKey,
                                    )
                                    if (committed) {
                                        formSessionKey = FvpDraftSession.draft.value
                                            .seriesSeedFor(athleteId, protocol)?.sessionKey
                                        onContinue()
                                    }
                                }
                            },
                            enabled = canContinue,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("fvp-setup-continue"),
                        ) {
                            Text(stringResource(R.string.fvp_setup_continue_saltos))
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (page) {
            FvpSetupPage.INTRO -> FvpIntroductionPage(
                revisit = introReturnPage != null,
                onPrepare = openDataFromIntro,
                onSkip = openDataFromIntro,
                onBack = navigateBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            FvpSetupPage.DATA -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(top = Spacing.md, bottom = Spacing.xl)
                    .testTag("fvp-page-data"),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                FvpFlowProgress(currentStep = FvpFlowStep.SETUP)
                // Cabecera COMPACTA compartida con Condiciones: aviso breve experimental/no medicion
                // directa siempre visible + ayuda "Metodo y limites" plegada con los textos completos
                // ya existentes.
                FvpCaptureHeader()
                // Aviso de recuperacion (D6): visible donde empieza el flujo, con copy localizado y
                // honesto. Nunca afirma que la recuperacion funcione; dice que NO esta disponible.
                // Queda FUERA del plegable de metodologia: es estado de la sesion, no documentacion.
                fvpRecoveryNoticeText(recoveryNotice)?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenJumpTheme.colors.warning,
                        modifier = Modifier
                            .fillMaxWidth()
                            // El aviso no puede ser solo visual (mismo criterio que los avisos B1).
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("fvp-recovery-notice"),
                    )
                }
                Column(
                    modifier = Modifier
                        .bringIntoViewRequester(athleteAnchor)
                        .testTag("fvp-setup-athlete-section"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    AthleteSelector(
                        athletes = activeAthletes,
                        selectedAthleteId = setupAthleteId,
                        loading = athletesLoading,
                        compact = true,
                        softSurface = true,
                        onAthleteSelected = {
                            setupAthleteId = it
                            onAthleteSelected(it)
                        },
                    )
                    if (!athletesLoading && !athleteSelected) {
                        Text(
                            stringResource(
                                if (activeAthletes.isEmpty()) R.string.athlete_selector_empty
                                else R.string.protocol_select_athlete_error,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FvpProtocolSelector(
                    protocol = protocol,
                    onSelected = { protocolName = it.name },
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FvpQuantityField(
                        quantity = MeasurementQuantity.MASS_KG,
                        value = massInput.text,
                        onValueChange = {
                            massInput = FvpSetupNumber.edited(massInput, it, unitProfile, locale)
                            massTouched = true
                        },
                        label = stringResource(R.string.fvp_body_mass),
                        isError = massRequiresReview || bodyMassInvalid,
                        testTag = "fvp-setup-mass",
                        modifier = Modifier.bringIntoViewRequester(bodyMassAnchor),
                    )
                    when {
                        massRequiresReview -> Text(
                            stringResource(R.string.unit_input_review_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("fvp-setup-mass-review"),
                        )
                        bodyMassInvalid -> Text(
                            stringResource(R.string.fvp_block_invalid_body_mass),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("fvp-setup-mass-error"),
                        )
                    }
                    if (bodyMassFromProfile) {
                        Text(
                            stringResource(R.string.fvp_body_mass_from_profile),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // B4a-1: la procedencia de la masa se DECLARA; no se deduce del perfil ni del valor.
                    FvpProvenanceChoiceRow(
                        testTag = "fvp-body-mass-measurement",
                        label = stringResource(R.string.fvp_body_mass_measurement),
                        options = listOf(
                            FvpBodyMassMeasurementUi.UNKNOWN.storageKey to
                                stringResource(R.string.fvp_body_mass_measurement_unknown),
                            FvpBodyMassMeasurementUi.WEIGHED.storageKey to
                                stringResource(R.string.fvp_body_mass_measurement_weighed),
                            FvpBodyMassMeasurementUi.DECLARED.storageKey to
                                stringResource(R.string.fvp_body_mass_measurement_declared),
                        ),
                        selectedKey = bodyMassMeasurement.storageKey,
                        onSelect = { bodyMassMeasurementName = it },
                    )
                    // Ayuda BAJO DEMANDA: la pregunta de procedencia y la sugerencia breve del perfil
                    // quedan visibles; la explicacion larga y el ejemplo se consultan a un toque.
                    TextButton(
                        onClick = { massHelpExpanded = !massHelpExpanded },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("fvp-body-mass-measurement-help-toggle"),
                    ) {
                        Text(
                            stringResource(
                                if (massHelpExpanded) R.string.result_temporal_sensitivity_hide_details
                                else R.string.fvp_setup_help,
                            ),
                        )
                    }
                    if (massHelpExpanded) {
                        Text(
                            stringResource(R.string.fvp_body_mass_measurement_help),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("fvp-body-mass-measurement-help"),
                        )
                        // Ejemplo ADYACENTE: decir que "sin declarar" es una respuesta legitima evita
                        // convertir UNKNOWN en un error que el usuario crea tener que resolver.
                        FvpInlineExample(
                            stringResource(R.string.fvp_body_mass_measurement_example),
                            modifier = Modifier.testTag("fvp-body-mass-measurement-example"),
                        )
                    }
                }
            }
            FvpSetupPage.MEASUREMENT -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(top = Spacing.md, bottom = Spacing.xl)
                    .testTag("fvp-page-measurement"),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                FvpFlowProgress(currentStep = FvpFlowStep.MEASUREMENT)
                FvpCaptureWarning()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FvpSectionTitle(stringResource(R.string.fvp_push_off))
                    if (pushOffMode == FvpPushOffUiMode.COMMON) {
                        FvpQuantityField(
                            quantity = MeasurementQuantity.SHORT_LENGTH_CM,
                            value = pushOffInput.text,
                            onValueChange = {
                                pushOffInput = FvpSetupNumber.edited(pushOffInput, it, unitProfile, locale)
                            },
                            label = stringResource(R.string.fvp_push_off),
                            isError = pushOffRequiresReview || pushOffOutOfDomain,
                            testTag = "fvp-setup-pushoff",
                            modifier = Modifier.bringIntoViewRequester(pushOffAnchor),
                        )
                        when {
                            pushOffRequiresReview -> Text(
                                stringResource(R.string.unit_input_review_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("fvp-setup-pushoff-review"),
                            )
                            pushOffOutOfDomain -> Text(
                                stringResource(
                                    R.string.fvp_block_invalid_push_off,
                                    pushOffDomainRangeText(unitProfile, locale),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("fvp-setup-pushoff-error"),
                            )
                            pushOffOutsideRecommended -> Text(
                                fvpWarningText(FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("fvp-setup-pushoff-hint"),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.fvp_push_off_brief),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("fvp-setup-push-off-approximation"),
                    )
                    FvpPushOffGuidance()
                    if (pushOffMode == FvpPushOffUiMode.PER_CONDITION) {
                        // Sin campo comun no se inventa un valor: cada condicion declara SU h_po en la
                        // pantalla de Saltos (mismo gate de siempre, no se relaja ni se autocompleta).
                        Text(
                            stringResource(R.string.fvp_setup_per_condition_hint),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("fvp-setup-per-condition-hint"),
                        )
                    }
                    TextField(
                        shape = ShapeTokens.medium,
                        colors = fvpInputColors(),
                        value = landmark,
                        onValueChange = { landmark = it },
                        label = { Text(stringResource(R.string.fvp_setup_landmark_label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .bringIntoViewRequester(landmarkAnchor)
                            .testTag("fvp-setup-landmark"),
                    )
                    // Ejemplo adyacente al campo: que punto elegir y como repetirlo.
                    FvpInlineExample(
                        stringResource(R.string.fvp_push_off_landmark_example),
                        modifier = Modifier.testTag("fvp-setup-landmark-example"),
                    )
                    TextField(
                        shape = ShapeTokens.medium,
                        colors = fvpInputColors(),
                        value = procedure,
                        onValueChange = { procedure = it },
                        label = { Text(stringResource(R.string.fvp_push_off_protocol)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .bringIntoViewRequester(procedureAnchor)
                            .testTag("fvp-setup-procedure"),
                    )
                    // Ejemplo adyacente al campo: la resta concreta que describe el procedimiento.
                    FvpInlineExample(
                        stringResource(R.string.fvp_push_off_procedure_example),
                        modifier = Modifier.testTag("fvp-setup-procedure-example"),
                    )
                    // El aviso LARGO del gate B1 vive junto a los campos que lo resuelven (hito y
                    // procedimiento), no en el pie: se sigue anunciando (live region) y sigue siendo
                    // alcanzable con scroll, pero ya no empuja los campos fuera del primer viewport.
                    if (pushOffMetadataMissing) {
                        Text(
                            stringResource(R.string.fvp_push_off_metadata_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                // Muse P1-6: el error bloqueante no puede ser solo visual.
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("fvp-setup-metadata-warning"),
                        )
                    }
                    // La FECHA de medicion deja de estar dentro del desplegable opcional: es uno de los
                    // tres metadatos de h_po medido (gate B1) y tiene que verse SIEMPRE, aunque los
                    // componentes opcionales sigan plegados. Mismo valor, mismo `DatePicker`, mismo tag.
                    OutlinedButton(
                        shape = ShapeTokens.medium,
                        border = null,
                        contentPadding = PaddingValues(Spacing.md),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fvp-setup-measured-at"),
                    ) {
                        Text(
                            stringResource(R.string.fvp_push_off_measured_at) + ": " + formatDate(measuredAt),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Solo los COMPONENTES (crouch/extended) son opcionales y viven plegados. Siguen
                    // con su estado/`Saver` y su autoexpansion cuando alguno queda en revision.
                    TextButton(
                        onClick = { advancedExpanded = !advancedExpanded },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-setup-advanced-toggle"),
                    ) { Text(stringResource(R.string.fvp_measurement_advanced)) }
                    if (advancedExpanded) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        FilterChip(
                            colors = fvpSelectionColors(),
                            selected = pushOffMode == FvpPushOffUiMode.COMMON,
                            onClick = { pushOffModeName = FvpPushOffUiMode.COMMON.storageKey },
                            label = { Text(stringResource(R.string.fvp_push_off_common)) },
                            modifier = Modifier
                                .heightIn(min = Spacing.xxxl)
                                .testTag("fvp-setup-pushoff-mode-COMMON"),
                        )
                        FilterChip(
                            colors = fvpSelectionColors(),
                            selected = pushOffMode == FvpPushOffUiMode.PER_CONDITION,
                            onClick = { pushOffModeName = FvpPushOffUiMode.PER_CONDITION.storageKey },
                            label = { Text(stringResource(R.string.fvp_push_off_per_condition)) },
                            modifier = Modifier
                                .heightIn(min = Spacing.xxxl)
                                .testTag("fvp-setup-pushoff-mode-PER_CONDITION"),
                        )
                    }
                    }
                    FvpSetupDetailsSection(
                        expanded = detailsExpanded,
                        onToggle = { detailsExpanded = !detailsExpanded },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FvpQuantityField(
                                quantity = MeasurementQuantity.SHORT_LENGTH_CM,
                                value = crouchInput.text,
                                onValueChange = {
                                    crouchInput = FvpSetupNumber.edited(crouchInput, it, unitProfile, locale)
                                },
                                label = stringResource(R.string.fvp_push_off_crouch_cm),
                                modifier = Modifier
                                    .weight(1f)
                                    .bringIntoViewRequester(crouchAnchor),
                                isError = crouchRequiresReview,
                                supportingText = if (crouchRequiresReview) {
                                    stringResource(R.string.unit_input_review_required)
                                } else {
                                    null
                                },
                                testTag = "fvp-setup-crouch",
                            )
                            FvpQuantityField(
                                quantity = MeasurementQuantity.SHORT_LENGTH_CM,
                                value = extendedInput.text,
                                onValueChange = {
                                    extendedInput = FvpSetupNumber.edited(extendedInput, it, unitProfile, locale)
                                },
                                label = stringResource(R.string.fvp_push_off_extended_cm),
                                modifier = Modifier
                                    .weight(1f)
                                    .bringIntoViewRequester(extendedAnchor),
                                isError = extendedRequiresReview,
                                supportingText = if (extendedRequiresReview) {
                                    stringResource(R.string.unit_input_review_required)
                                } else {
                                    null
                                },
                                testTag = "fvp-setup-extended",
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = measuredAt ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // El `DatePicker` siempre trae una fecha seleccionada (hoy por defecto); si
                        // el estado viniera vacio se conserva la fecha vigente, nunca un null.
                        measuredAt = pickerState.selectedDateMillis ?: measuredAt
                        showDatePicker = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.common_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/*
 * Ventana RECOMENDADA de h_po [m]: declaracion UNICA en `FvpEligibility`
 * (`RECOMMENDED_MIN_PUSH_OFF_M` / `RECOMMENDED_MAX_PUSH_OFF_M`, 0,20-0,60 m), importada en este
 * archivo. Aqui solo alimenta una ayuda no bloqueante; el bloqueo lo decide el dominio de
 * ingenieria, no la ventana. No debe reintroducirse una copia local: divergiria en silencio del
 * limite que evalua la elegibilidad (`FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE`).
 */

/**
 * h_po declarado dentro del dominio valido [m] de la capa de calculo (`FvpCalculator`):
 * `(MIN_PUSH_OFF_M, MAX_PUSH_OFF_M]` = `(0,05, 0,80]`. `null`, no finito o fuera del dominio se
 * considera inutilizable: nunca se avanza con el.
 */
internal fun isUsablePushOff(value: Double?): Boolean = value != null &&
    value.isFinite() && value > MIN_PUSH_OFF_M && value <= MAX_PUSH_OFF_M

/**
 * Medianoche UTC del dia actual en el dispositivo: el mismo instante que devuelve el
 * `DatePicker` de Material 3 para "hoy", de modo que la fecha por defecto no se desplaza de dia
 * en husos negativos.
 */
internal fun todayUtcMillis(): Long = Instant.ofEpochMilli(System.currentTimeMillis())
    .atZone(ZoneOffset.UTC)
    .toLocalDate()
    .atStartOfDay(ZoneOffset.UTC)
    .toInstant()
    .toEpochMilli()

/**
 * Procedencia DECLARADA de la masa corporal (B4a-1): vocabulario EXACTO de
 * `fvp_assessments.bodyMassMeasurement` (contrato 18 §2). Es INDEPENDIENTE de `bodyMassSource`
 * (PROFILE/MANUAL): declarar una pesada no convierte la masa en "del perfil" ni al reves.
 */
internal enum class FvpBodyMassMeasurementUi(val storageKey: String) {
    UNKNOWN("UNKNOWN"),
    WEIGHED("WEIGHED"),
    DECLARED("DECLARED"),
    ;

    companion object {
        /** Clave de almacenamiento conocida, o `null` si no pertenece al vocabulario. */
        fun fromStorageKey(key: String?): FvpBodyMassMeasurementUi? =
            entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Contexto de adquisicion DECLARADO (B4a-1): vocabulario EXACTO de
 * `fvp_assessments.acquisitionContextKind`. `UNKNOWN` es el default y nunca se infiere por cercania
 * de fechas ni por los fps de los intentos.
 */
internal enum class FvpAcquisitionContextUi(val storageKey: String) {
    UNKNOWN("UNKNOWN"),
    SAME_ACQUISITION("SAME_ACQUISITION"),
    HISTORICAL_COMPOSITE("HISTORICAL_COMPOSITE"),
    ;

    companion object {
        /** Clave de almacenamiento conocida, o `null` si no pertenece al vocabulario. */
        fun fromStorageKey(key: String?): FvpAcquisitionContextUi? =
            entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Cota de la clave opaca de contexto del contrato 18 §2 (`<= 128` caracteres). Es la MISMA cota que
 * aplican las capas que ya validan este campo (`FvpRepository.requireProvenanceKeyOrNull`, la
 * validacion del codec de recuperacion y el modelo de backup): el campo de la UI no puede aceptar
 * lo que el guardado rechazaria.
 */
internal const val FVP_ACQUISITION_CONTEXT_KEY_MAX_CHARS = 128

/**
 * Clave de contexto que se PERSISTE, o `null` si no hay ninguna declarada: se recortan los espacios
 * de los extremos (contrato: no vacia, sin espacios al inicio/fin y sin superar la cota) y con un
 * contexto `UNKNOWN` no se guarda clave, aunque hubiera texto tecleado para una eleccion anterior
 * (con `UNKNOWN` el campo ni se muestra y no se puede revisar lo que se guardaria).
 */
internal fun fvpAcquisitionContextKeyToPersist(
    kind: FvpAcquisitionContextUi,
    raw: String,
): String? = if (kind == FvpAcquisitionContextUi.UNKNOWN) {
    null
} else {
    raw.trim().takeIf { it.isNotEmpty() }
}

/**
 * Vuelca el paso 1 en la sesion en memoria. Cambiar de atleta o de protocolo empieza una serie
 * NUEVA (sessionKey nueva): una serie nunca mezcla protocolos ni atletas.
 *
 * La mutacion va por el DUEÑO ([FvpDraftMutations], A2): el dueño supersede de forma sincrona la
 * reconciliacion pendiente, invalida la recuperabilidad y guarda el estado COMPLETO resultante. Con
 * un montaje aislado sin dueño, [mutations] es la propia sesion.
 *
 * B4a-2b: los campos que forman parte de la aceptacion COMPLETA de la reutilizacion (contrato 18
 * §2) que ESTE paso posee se escriben por los helpers sincronicos del borrador
 * ([withSeriesProtocol], [withSeriesPushOffM]) y no con `copy(...)` directo: asi volver al paso 1 y
 * pulsar CONTINUAR sin tocar nada conserva las confirmaciones, y cambiar de verdad protocolo o h_po
 * de serie (solo en modo COMMON) las retira.
 *
 * CONTEXTO DE ADQUISICION (handoff a Condiciones, slice B): este commit NO escribe
 * `acquisitionContextKind`/`acquisitionContextKey` ni llama a `withAcquisitionContext`. El contexto
 * editable deja de vivir en esta pantalla y pasa a Condiciones; si aqui se volviera a escribir con
 * una copia local, volver atras despues de declararlo en Condiciones lo sobrescribiria (UNKNOWN o
 * una clave vieja). Al no tocarlo, una serie que ya tiene contexto conserva EXACTAMENTE el del
 * borrador vivo y una serie nueva se queda con el `UNKNOWN` por defecto: no se infiere nada.
 */
private fun commitSetupDraft(
    mutations: FvpDraftMutations,
    expectedSessionKey: String?,
    athleteId: Long,
    protocol: FvpProtocol,
    bodyMassKg: Double,
    bodyMassFromProfile: Boolean,
    pushOffMode: FvpPushOffUiMode,
    commonPushOffM: Double?,
    landmark: String,
    procedure: String,
    measuredAt: Long,
    crouchCm: Double?,
    extendedCm: Double?,
    bodyMassMeasurement: String,
): Boolean {
    val live = FvpDraftSession.draft.value
    // Segunda barrera: no escribir ni navegar si la sesion cambio antes de recomponer.
    if (live.seriesSeedFor(athleteId, protocol)?.sessionKey != expectedSessionKey) return false
    if (live == null || live.athleteId != athleteId || live.protocol != protocol) {
        mutations.begin(athleteId, protocol)
    }
    mutations.update { draft ->
        draft
            .copy(
                athleteId = athleteId,
                bodyMassKg = bodyMassKg,
                bodyMassFromProfile = bodyMassFromProfile,
                // El MODO de h_po va por su helper (antes de [withSeriesPushOffM]): cambiarlo
                // cambia QUE h_po es el relevante e invalida las confirmaciones; el helper de h_po
                // decide despues con el modo RESULTANTE si el valor comun entra en la serie.

                pushOffLandmark = landmark.trim(),
                pushOffProtocol = procedure.trim(),
                pushOffMeasuredAt = measuredAt,
                pushOffCrouchCm = crouchCm,
                pushOffExtendedCm = extendedCm,
                // B4a-1: procedencia declarada, copia 1:1. NI `bodyMassSource` (PROFILE/MANUAL es
                // otra cosa) NI las fechas de adquisicion/pesada se tocan aqui: siguen como estaban
                // (`null` en una serie nueva) porque este paso no las declara.
                bodyMassMeasurement = bodyMassMeasurement,
            )
            // B4a-2b: mismo valor final que el `copy` directo anterior, pero retirando las
            // confirmaciones afectadas SOLO si el valor cambia de verdad (idempotente al
            // re-confirmar sin tocar nada).
            .withSeriesProtocol(protocol)
            .withSeriesPushOffMode(pushOffMode)
            .withSeriesPushOffM(commonPushOffM)
        // El contexto declarado NO se escribe aqui a proposito: su dueño es Condiciones (slice B)
        // y reescribirlo al volver atras pisaria lo que se haya declarado alli. En una serie nueva
        // el borrador ya arranca en `UNKNOWN`, que es el default honesto.
    }
    return true
}

/**
 * Fila de chips de UNA declaracion de procedencia (etiqueta opcional + opciones). A diferencia de
 * los chips de modo de h_po (que caben en una fila), estas opciones se envuelven con `FlowRow`:
 * una opcion oculta tras un scroll horizontal no se puede pulsar y con fontScale alto quedaria
 * inalcanzable. Minimo 48 dp por opcion; cada una expone su clave de almacenamiento en el `testTag`
 * y su etiqueta LOCALIZADA en el texto, nunca el nombre del enum en crudo (P1-3).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FvpProvenanceChoiceRow(
    testTag: String,
    label: String?,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (label != null) {
            Text(
                label,
                style = OpenJumpTypes.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            // Las opciones se envuelven en varias lineas: ninguna queda fuera de la pantalla por
            // un scroll horizontal oculto (el usuario debe poder elegir las tres sin gestos).
        ) {
            options.forEach { (key, optionLabel) ->
                FilterChip(
                    colors = fvpSelectionColors(),
                    selected = key == selectedKey,
                    onClick = { onSelect(key) },
                    label = { Text(optionLabel) },
                    modifier = Modifier
                        .heightIn(min = Spacing.xxxl)
                        .testTag("$testTag-$key")
                        // La opcion elegida se declara tambien en semantica (no solo en color).
                        .semantics { selected = key == selectedKey },
                )
            }
        }
    }
}

/**
 * Seccion expandible del setup para los componentes OPCIONALES de la medicion (crouch/extended).
 *
 * La fecha de medicion ya NO vive aqui: es metadato obligatorio de h_po y se muestra siempre junto a
 * hito/procedimiento. Reutiliza el patron ya existente en la app (`ResultTemporalSensitivity`): fila
 * clicable de 48 dp minimos, `AnimatedVisibility` y `stateDescription` para lectores de pantalla.
 * La ruta por defecto conserva h_po, hito, procedimiento y fecha.
 */
@Composable
private fun FvpSetupDetailsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val stateLabel = stringResource(
        if (expanded) R.string.result_temporal_sensitivity_expanded
        else R.string.result_temporal_sensitivity_collapsed,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onToggle() }
                    .semantics {
                        role = Role.Button
                        stateDescription = stateLabel
                    }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    .testTag("fvp-setup-details-toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fvp_setup_components_optional),
                    modifier = Modifier.weight(1f),
                    style = OpenJumpTypes.SectionTitle,
                )
                Text(
                    stringResource(
                        if (expanded) R.string.result_temporal_sensitivity_hide_details
                        else R.string.result_temporal_sensitivity_show_details,
                    ),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun FvpSectionTitle(text: String) {
    Text(text, style = OpenJumpTypes.SectionTitle, fontWeight = FontWeight.SemiBold)
}

/** Campo numerico consciente del perfil de unidades; el valor canonico se parsea fuera. */
@Composable
internal fun FvpQuantityField(
    quantity: MeasurementQuantity,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    testTag: String? = null,
) {
    val unit = MeasurementFormatting.unit(quantity, LocalUnitSystem.current)
    TextField(
        shape = ShapeTokens.medium,
        colors = fvpInputColors(),
        value = value,
        onValueChange = onValueChange,
        label = { Text("$label (${unit.symbol})") },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { text -> { Text(text) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    )
}

/** Texto de entrada en unidades del perfil a partir de un valor canonico (o vacio si es null). */
internal fun formatCanonical(
    value: Double?,
    quantity: MeasurementQuantity,
    unitProfile: UnitProfile,
    locale: Locale,
): String = value?.takeIf { it.isFinite() }
    ?.let { MeasurementFormatting.formatInputValue(it, quantity, unitProfile, locale) }
    .orEmpty()

/** 1 m = 100 cm: factor unico entre el canonico de h_po (metros) y su longitud corta (cm/in). */
internal const val CENTIMETERS_PER_METER = 100.0

/**
 * h_po se TECLEA y se MUESTRA como LONGITUD CORTA del perfil (cm/in), nunca como la unidad de
 * distancia horizontal del perfil (que en el perfil de EE. UU. es el pie): 0,40 m son "40" cm o
 * "15,748" in, jamas "1,31" ft.
 *
 * La conversion pasa siempre por el canonico en METROS, que es lo unico que se guarda, se exporta y
 * se valida: ni el valor persistido ni el dominio de elegibilidad `(MIN_PUSH_OFF_M, MAX_PUSH_OFF_M]`
 * cambian de unidad.
 */
internal fun formatPushOffInput(pushOffM: Double?, unitProfile: UnitProfile, locale: Locale): String =
    formatCanonical(pushOffM?.times(CENTIMETERS_PER_METER), MeasurementQuantity.SHORT_LENGTH_CM, unitProfile, locale)

/** Texto de entrada de h_po (longitud corta del perfil: cm/in) -> canonico en METROS. */
internal fun parsePushOffInput(text: String, unitProfile: UnitProfile, locale: Locale): Double? =
    MeasurementFormatting.parseCanonical(text, MeasurementQuantity.SHORT_LENGTH_CM, unitProfile, locale)
        ?.div(CENTIMETERS_PER_METER)

/**
 * Rango ADVISORY (sin marcadores de apertura) de h_po [m] escrito en la MISMA unidad que el campo
 * (longitud corta del perfil: cm/in) y con la precision con la que ese campo muestra sus valores.
 *
 * Sirve para la ventana RECOMENDADA, que es una ayuda no bloqueante: aqui los dos extremos se
 * redondean al numero mas cercano y se leen como un rango normal. Los limites de DOMINIO usan
 * [domainRangeText], que redondea HACIA DENTRO y marca la apertura del extremo inferior.
 *
 * La conversion pasa por el canonico en centimetros (misma unidad de campo que el resto del
 * archivo) para que las cifras no contradigan la unidad del campo: un usuario de EE. UU. con el
 * campo en pulgadas no puede leer un limite en metros.
 */
internal fun pushOffRangeText(minM: Double, maxM: Double, unitProfile: UnitProfile, locale: Locale): String {
    val unit = MeasurementFormatting.unit(MeasurementQuantity.SHORT_LENGTH_CM, unitProfile)
    val min = MeasurementFormatting.formatValue(
        minM.times(CENTIMETERS_PER_METER),
        MeasurementQuantity.SHORT_LENGTH_CM,
        unitProfile,
        locale,
        unit.decimals,
    )
    val max = MeasurementFormatting.formatValue(
        maxM.times(CENTIMETERS_PER_METER),
        MeasurementQuantity.SHORT_LENGTH_CM,
        unitProfile,
        locale,
        unit.decimals,
    )
    return "$min–$max ${unit.symbol}"
}

/**
 * Decimales de la division decimal exacta que convierte el canonico a la unidad del campo. Es una
 * precision de trabajo (muy por encima de los 1-2 decimales que se muestran), no la precision
 * mostrada: el redondeo HACIA DENTRO se aplica despues, ya en decimal.
 */
private const val EXACT_DIVISION_SCALE = 12

/**
 * Valor de longitud corta en la unidad del CAMPO como [BigDecimal] EXACTO, a partir del canonico en
 * metros.
 *
 * La conversion se hace en decimal (nunca en binario) porque un limite que cae EXACTAMENTE sobre la
 * rejilla de visualizacion no debe desplazarse por el error de coma flotante: 0,05 m son 5,0 cm
 * exactos (y hay que subirlos a 5,1), y 0,80 m son 80,0 cm exactos. El factor "cuantos centimetros
 * canonicos vale una unidad del campo" se DERIVA de [MeasurementFormatting] (`toCanonical(1.0)`),
 * de modo que sigue existiendo una sola definicion de la pulgada en la app.
 */
private fun shortLengthDisplayExact(canonicalM: Double, unitProfile: UnitProfile): BigDecimal {
    val centimeters = BigDecimal.valueOf(canonicalM).movePointRight(2)
    val canonicalCmPerDisplayUnit = BigDecimal.valueOf(
        MeasurementFormatting.toCanonical(1.0, MeasurementQuantity.SHORT_LENGTH_CM, unitProfile),
    )
    return centimeters.divide(canonicalCmPerDisplayUnit, EXACT_DIVISION_SCALE, RoundingMode.HALF_UP)
}

/** Render del valor ya redondeado con los decimales FIJOS del campo y el separador decimal local. */
private fun renderFixedDecimals(value: BigDecimal, decimals: Int, locale: Locale): String {
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return value.setScale(decimals, RoundingMode.HALF_UP).toPlainString().replace('.', separator)
}

/**
 * Limite INFERIOR de un dominio ABIERTO `(a, b]`: se redondea HACIA DENTRO (hacia arriba) con los
 * decimales que muestra el campo, de modo que el numero anunciado se puede teclear y el dominio lo
 * ACEPTA (es estrictamente mayor que `a`).
 *
 * Si el limite cae EXACTAMENTE sobre la rejilla visible (0,05 m = 5,0 cm; 0,05 m = 1,968... in no),
 * el redondeo hacia arriba no mueve la cifra y anunciarla tal cual seria anunciar el propio extremo
 * EXCLUIDO: se sube un escalon de la rejilla (5,0 -> 5,1 cm). El CERO es la excepcion: es el origen
 * natural de una longitud y no tiene un limite inferior positivo que anunciar (0,1 cm seria un minimo
 * inventado); se anuncia `0,0` y el parentesis indica que queda EXCLUIDO.
 */
private fun openLowerBoundText(canonicalM: Double, unitProfile: UnitProfile, decimals: Int, locale: Locale): String {
    val exact = shortLengthDisplayExact(canonicalM, unitProfile)
    val rounded = exact.setScale(decimals, RoundingMode.CEILING)
    val inward = if (exact.signum() > 0 && rounded.compareTo(exact) == 0) {
        rounded.add(BigDecimal.ONE.scaleByPowerOfTen(-decimals))
    } else {
        rounded
    }
    return renderFixedDecimals(inward, decimals, locale)
}

/**
 * Limite SUPERIOR de un dominio CERRADO `(a, b]`: se redondea HACIA DENTRO (hacia abajo) con los
 * decimales que muestra el campo, de modo que el numero anunciado se puede teclear y el dominio lo
 * ACEPTA (es menor o igual que `b`). Si el limite cae justo sobre la rejilla (0,80 m = 80,0 cm) se
 * anuncia tal cual: el extremo superior es inclusivo.
 */
private fun closedUpperBoundText(canonicalM: Double, unitProfile: UnitProfile, decimals: Int, locale: Locale): String {
    val rounded = shortLengthDisplayExact(canonicalM, unitProfile).setScale(decimals, RoundingMode.FLOOR)
    return renderFixedDecimals(rounded, decimals, locale)
}

/**
 * Rango de un DOMINIO de ingenieria con extremo inferior ABIERTO y superior CERRADO, en la unidad
 * del CAMPO y con su precision: `(min–max] unidad` (parentesis/bracket ASCII y guion en raya del
 * resto del archivo). Los dos extremos se redondean HACIA DENTRO, asi que el usuario ve cifras que
 * puede teclear y que el dominio acepta, y los marcadores le dicen que el inferior queda excluido.
 *
 * La rejilla visible es la del campo (`unit.decimals`: 1 decimal en cm y en in).
 */
private fun domainRangeText(minM: Double, maxM: Double, unitProfile: UnitProfile, locale: Locale): String {
    val unit = MeasurementFormatting.unit(MeasurementQuantity.SHORT_LENGTH_CM, unitProfile)
    val min = openLowerBoundText(minM, unitProfile, unit.decimals, locale)
    val max = closedUpperBoundText(maxM, unitProfile, unit.decimals, locale)
    return "($min–$max] ${unit.symbol}"
}

/**
 * Dominio de ingenieria de h_po `(MIN_PUSH_OFF_M, MAX_PUSH_OFF_M]` en la unidad del campo,
 * redondeado hacia dentro con la precision visible: `(5,1–80,0] cm` / `(2,0–31,4] in`.
 */
internal fun pushOffDomainRangeText(unitProfile: UnitProfile, locale: Locale): String =
    domainRangeText(MIN_PUSH_OFF_M, MAX_PUSH_OFF_M, unitProfile, locale)

/**
 * Ventana recomendada de h_po (la MISMA que usa la elegibilidad) en la unidad del campo. Es un
 * rango ADVISORY: conserva la forma plana `20,0–60,0 cm`, sin marcadores de apertura.
 */
internal fun pushOffRecommendedRangeText(unitProfile: UnitProfile, locale: Locale): String =
    pushOffRangeText(RECOMMENDED_MIN_PUSH_OFF_M, RECOMMENDED_MAX_PUSH_OFF_M, unitProfile, locale)

/**
 * Dominio de la ALTURA de salto `(0, MAX_JUMP_HEIGHT_M]` en la unidad del CAMPO de altura
 * (longitud corta: cm/in): `(0,0–120,0] cm` / `(0,0–47,2] in`. Mismo criterio que h_po: el limite
 * que lee el usuario debe estar en la unidad que escribe y el extremo inferior (0) va marcado como
 * EXCLUIDO, porque el dominio exige altura > 0; la altura se guarda y se valida en metros.
 */
internal fun jumpHeightRangeText(unitProfile: UnitProfile, locale: Locale): String =
    domainRangeText(0.0, MAX_JUMP_HEIGHT_M, unitProfile, locale)

/**
 * Fecha de una medicion de h_po. `DatePicker` entrega medianoche UTC del dia elegido: se formatea
 * en UTC para mostrar exactamente el dia seleccionado (sin desplazarlo en husos negativos),
 * mientras el valor persistido sigue siendo ese instante UTC.
 */
@Composable
internal fun formatDate(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneOffset.UTC)
    .toLocalDate()
    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentAppLocale()))

/**
 * Copy localizado del aviso de recuperacion ([FvpRecoveryNotice]), o `null` si no hay nada que
 * avisar. Es la UNICA traduccion del estado del dueño a texto: la comparten la pantalla de setup y
 * el guard de sesion de `AppNav`, de modo que el mismo estado nunca se cuenta de dos formas.
 *
 * Todos los textos dicen lo que NO hay (recuperacion no disponible / no se pudo recuperar la
 * sesion anterior): ninguno afirma que la recuperacion funcione.
 */
@Composable
internal fun fvpRecoveryNoticeText(notice: FvpRecoveryNotice): String? = when (notice) {
    FvpRecoveryNotice.None -> null
    is FvpRecoveryNotice.RefusedToStore -> when (notice.reason) {
        FvpRecoveryRefusal.TOO_LARGE -> stringResource(R.string.fvp_recovery_refused_too_large)
        FvpRecoveryRefusal.NOT_RECOVERABLE -> stringResource(R.string.fvp_recovery_refused_not_storable)
    }
    FvpRecoveryNotice.Unrecoverable -> stringResource(R.string.fvp_recovery_unrecoverable_session)
}
