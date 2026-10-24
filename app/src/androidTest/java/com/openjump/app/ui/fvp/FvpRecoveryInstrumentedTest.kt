package com.openjump.app.ui.fvp

import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.MainActivity
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.data.FVP_METHOD_VERSION
import com.openjump.app.data.FvpAssessmentEntity
import com.openjump.app.settings.OnboardingCompletion
import com.openjump.fvp.FvpMeasureMethod
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R3-A (mitad ANDROID de la recuperacion F-V-P, plan 13): mide y prueba la recuperacion sobre el
 * estado que Android conserva DE VERDAD, no sobre un `SavedStateHandle(mapOf(...))`.
 *
 * QUE HACE DE VERDAD ESTE TEST (y por que no es un mock):
 * - arranca la `MainActivity` real y navega el `OpenJumpNavHost` de produccion (`AppNav`, no un
 *   `NavHost` copiado), entrando por la tarjeta `fvp_title` de Home;
 * - obtiene el dueño `FvpFlowViewModel` del `ViewModelStore` de la Activity con la MISMA clave
 *   por defecto que usa Compose (`androidx.lifecycle.ViewModelProvider.DefaultKey:<FQCN>`, la misma
 *   que `viewModel(factory = FvpFlowViewModel.Factory)` en `AppNav`) y con el MISMO
 *   `FvpFlowViewModel.Factory` (base real de datos: Room);
 * - provoca los cambios del borrador SOLO por metodos del dueño (`begin`/`update`/`adopt`), que son
 *   los unicos escritores del payload;
 * - captura el estado AGREGADO real de la Activity con
 *   `Instrumentation.callActivityOnSaveInstanceState` (el mismo `Bundle` que el sistema usa), lo
 *   serializa con `Parcel.writeBundle` + `dataSize()` y lee el payload EXACTO que el dueño dejo en
 *   el `SavedStateHandle` a traves del proveedor del registro (`SavedStateHandlesProvider`), no de
 *   una codificacion de fixture;
 * - comprueba un proveedor VECINO registrado en el `SavedStateRegistry` (nonce) que viaja en la
 *   MISMA foto agregada;
 * - simula memoria fresca: limpia el `ViewModelStore` de la Activity (en `ActivityScenario.recreate()`
 *   los ViewModels se conservan; aqui se vacian) y resetea SOLO `FvpDraftSession`, de modo que el
 *   dueño nuevo es una instancia NUEVA que restaura el payload desde el registro real y espera la
 *   reconciliacion (`reconciliationPending`).
 *
 * LO QUE ESTE TEST NO AFIRMA (importante):
 * - `Activity.recreate()` NO es muerte real de proceso. Aqui sobreviven el proceso, `OpenJumpApp`,
 *   Room y las preferencias; solo se descartan los ViewModels y la sesion en memoria. La muerte real
 *   (`am kill`) es el protocolo separado de V2 y NO se reclama aqui.
 * - El `Bundle` agregado se mide con `Parcel.writeBundle` en el propio proceso: es la cota de
 *   ingenieria de 262 144 bytes, NO una garantia universal de transporte Binder.
 * - Los datos sinteticos de este test son SOLO del test (atleta con nombre UUID, filas con
 *   `sessionKey` propios): se borran en `tearDown` y se restaura la seleccion de atleta previa. No
 *   se usa `pm clear`, force-stop ni borrado de datos de la app.
 *
 * Si el registro real no pudiera restaurar el payload (p. ej. por un cambio de AndroidX), el test
 * DEBE fallar: sustituirlo por un `SavedStateHandle` construido a mano no probaria nada.
 */
@RunWith(AndroidJUnit4::class)
class FvpRecoveryInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: OpenJumpApp
    private var athleteId: Long = 0L
    private var previousSelectedAthleteId: Long? = null
    private var previousSelectionCaptured = false
    private val additionalFixtureAthletes = mutableListOf<Long>()

    /** Clave real de Compose/`ViewModelProvider` para el dueño del flujo F-V-P. */
    private val ownerDefaultKey =
        "androidx.lifecycle.ViewModelProvider.DefaultKey:${FvpFlowViewModel::class.java.canonicalName}"

    private val nonceRegistryKey = "openjump.test.fvp.recovery.nonce"

    private val setupContinueTag = "fvp-setup-continue"
    private val setupDataContinueTag = "fvp-setup-continue-data"

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenJumpApp
        previousSelectedAthleteId = app.selectedAthleteStore.selectedAthleteId.value
        previousSelectionCaptured = true
        // La pantalla de entrada arranca en INTRO la primera vez. Estos casos prueban la recuperacion
        // del dueno y la navegacion real, no la presentacion inicial: la preferencia de UI se marca
        // para que el setup caiga directamente en DATA (no se toca ningun dato ni el onboarding).
        markFvpIntroductionSeen()
        // El estado de onboarding NO se modifica: si el aparato no tiene el onboarding completado,
        // Home no existe y el test no puede medir nada (se declara como supuesto, no como exito).
        assertEquals(
            "Precondicion del AVD: onboarding completado; no se permite omitir esta suite.",
            OnboardingCompletion.COMPLETED,
            app.onboardingPreferencesStore.completion.value,
        )
        compose.runOnUiThread { FvpDraftSession.reset() }
        athleteId = runBlocking {
            app.athleteRepository.create(
                displayName = "FVP R3-A ${UUID.randomUUID()}",
                weightKg = 80.0,
            )
        }
        // La pantalla de setup solo siembra el borrador de la identidad del atleta SELECCIONADO; la
        // seleccion previa se restaura en tearDown (unico cambio de preferencia de este test).
        assertTrue(runBlocking { app.selectedAthleteStore.select(app.athleteRepository, athleteId) })
        compose.waitUntil(30_000) { app.selectedAthleteStore.selectedAthleteId.value == athleteId }
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { FvpDraftSession.reset() }
        runBlocking {
            // Limpieza SOLO de las filas propias de este test (atleta sintetico + sus FVP) y
            // restauracion de la seleccion previa. Nada de datos preexistentes.
            if (athleteId > 0L) {
                app.database.fvpDao().deleteForAthlete(athleteId)
                app.database.athleteDao().deleteAthleteById(athleteId)
                assertNull("El fixture propio no debe quedar en Room", app.athleteRepository.byId(athleteId))
            }
            additionalFixtureAthletes.forEach { id ->
                app.database.fvpDao().deleteForAthlete(id)
                app.database.athleteDao().deleteAthleteById(id)
                assertNull(app.athleteRepository.byId(id))
            }
            if (previousSelectionCaptured) {
                val previous = previousSelectedAthleteId
                if (previous != null && app.athleteRepository.activeById(previous) != null) {
                    app.selectedAthleteStore.select(app.athleteRepository, previous)
                } else {
                    app.selectedAthleteStore.clear()
                }
            }
        }
    }

    /**
     * Caso NORMAL + presupuestos SEPARADOS + proveedor vecino + navegacion real + identidad exacta.
     */
    @Test
    fun setupDraftSurvivesAggregateSavedStateRestoreWithExactIdentityAndNavigation() {
        val nonce = "nonce-${UUID.randomUUID()}"
        registerNeighborNonceProvider(nonce)

        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        assertOwnerLivesUnderComposeDefaultKey()

        val sessionKey = beginRestorableDraft(owner).sessionKey
        val aggregate = captureAggregateState()
        val storedPayload = requireNotNull(storedOwnerPayload(aggregate)) {
            "Falta payload real: notice=${owner.recoveryNotice.value}, " +
                "handleKeys=${savedOwnerHandleValues(aggregate).keys}"
        }
        assertEquals(
            "El payload vigente no puede llevar la marca de no vigencia.",
            false,
            storedOwnerInvalidated(aggregate),
        )

        // Presupuesto 1: JSON UTF-8 del payload REALMENTE almacenado por el dueño (no un encode de
        // fixture aparte).
        reportBudget("normal", storedPayload, aggregate)
        val payloadUtf8Bytes = storedPayload.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "JSON UTF-8 del payload real = $payloadUtf8Bytes bytes, presupuesto " +
                "${FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES}",
            payloadUtf8Bytes <= FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        // Presupuesto 2: estado AGREGADO de la Activity (NavHost + SavedStateRegistry + vecinos).
        val aggregateParcelBytes = parcelBytes(aggregate)
        assertTrue(
            "Estado agregado parcelado = $aggregateParcelBytes bytes, presupuesto " +
                MAX_AGGREGATE_PARCEL_BYTES,
            aggregateParcelBytes <= MAX_AGGREGATE_PARCEL_BYTES,
        )

        val savedState = requireNotNull(FvpDraftStateCodec.decode(storedPayload)) {
            "El payload guardado por el dueño debe decodificar con el codec de produccion."
        }
        assertEquals(FvpFlowStep.SETUP, savedState.step)
        assertEquals(sessionKey, savedState.draft.sessionKey)

        val freshOwner = recreateWithClearedViewModelStore(owner)
        awaitReconciled(freshOwner)
        awaitSetupScreenDisplayed()

        val restored = requireNotNull(FvpDraftSession.draft.value) {
            "El dueño nuevo debe haber adoptado el borrador restaurado tras reconciliar con Room."
        }
        assertEquals(
            "La identidad persistida (sessionKey) debe conservarse EXACTA a traves del registro real.",
            sessionKey,
            restored.sessionKey,
        )
        assertEquals(
            "El contenido restaurado debe ser el del payload que el dueño guardo de verdad.",
            canonical(savedState.draft),
            canonical(restored),
        )
        assertEquals(FvpRecoveryNotice.None, freshOwner.recoveryNotice.value)
        assertNeighborNonceRestored(nonce)
    }

    /**
     * Caso CERCA DEL PRESUPUESTO: se calibra el fixture con el codec puro (para no truncar ni
     * rechazar) y la asercion se hace sobre el payload REAL del registro.
     */
    @Test
    fun nearBudgetDraftIsStoredAndRestoredWithinSeparateBudgets() {
        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        val started = compose.runOnUiThread { owner.begin(athleteId) }
        val calibrated = nearBudgetDraft(started.sessionKey)
        val calibratedBytes = encodedUtf8Bytes(calibrated)
        assertTrue(
            "El fixture calibrado debe quedar cerca del presupuesto (=$calibratedBytes bytes).",
            calibratedBytes > FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES - 4_096,
        )
        compose.runOnUiThread { owner.adopt(calibrated) }

        val aggregate = captureAggregateState()
        val storedPayload = requireNotNull(storedOwnerPayload(aggregate))
        reportBudget("near-budget", storedPayload, aggregate)
        val storedBytes = storedPayload.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "Payload real cerca del presupuesto = $storedBytes bytes, " +
                "presupuesto ${FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES}",
            storedBytes <= FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        assertTrue(
            "El caso debe ser de verdad 'cerca del presupuesto', no un borrador pequeño: $storedBytes",
            storedBytes > 60_000,
        )
        assertEquals(
            "El payload real debe coincidir en tamaño con el estado calibrado (mismo estado).",
            calibratedBytes,
            storedBytes,
        )
        val aggregateParcelBytes = parcelBytes(aggregate)
        assertTrue(
            "Estado agregado parcelado = $aggregateParcelBytes bytes, presupuesto " +
                MAX_AGGREGATE_PARCEL_BYTES,
            aggregateParcelBytes <= MAX_AGGREGATE_PARCEL_BYTES,
        )

        val savedState = requireNotNull(FvpDraftStateCodec.decode(storedPayload))
        val freshOwner = recreateWithClearedViewModelStore(owner)
        awaitReconciled(freshOwner)
        awaitSetupScreenDisplayed()
        val restored = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(started.sessionKey, restored.sessionKey)
        assertEquals(canonical(savedState.draft), canonical(restored))
    }

    /**
     * Caso MULTIBYTE: el presupuesto se mide en BYTES UTF-8 (no en caracteres) y el texto exacto
     * sobrevive al registro real.
     */
    @Test
    fun multibyteDraftMeasuresUtf8BytesAndRestoresExactText() {
        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        val sessionKey = beginRestorableDraft(owner) { draft ->
            draft.copy(
                notes = MULTIBYTE_NOTES,
                conditions = draft.conditions.map { condition ->
                    condition.copy(
                        attempts = condition.attempts.map { attempt ->
                            attempt.copy(notes = MULTIBYTE_NOTES)
                        },
                    )
                },
            )
        }.sessionKey

        val aggregate = captureAggregateState()
        val storedPayload = requireNotNull(storedOwnerPayload(aggregate))
        reportBudget("multibyte", storedPayload, aggregate)
        assertTrue(
            "El payload debe contener texto multibyte literal (sin perder el UTF-8).",
            storedPayload.contains(MULTIBYTE_NOTES),
        )
        val storedBytes = storedPayload.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "Medicion en BYTES UTF-8 ($storedBytes) > caracteres (${storedPayload.length}) en un " +
                "payload multibyte.",
            storedBytes > storedPayload.length,
        )
        assertTrue(
            "El caso multibyte debe seguir dentro del presupuesto: $storedBytes",
            storedBytes <= FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        val aggregateParcelBytes = parcelBytes(aggregate)
        assertTrue(
            "Estado agregado parcelado = $aggregateParcelBytes bytes, presupuesto " +
                MAX_AGGREGATE_PARCEL_BYTES,
            aggregateParcelBytes <= MAX_AGGREGATE_PARCEL_BYTES,
        )

        val freshOwner = recreateWithClearedViewModelStore(owner)
        awaitReconciled(freshOwner)
        awaitSetupScreenDisplayed()
        val restored = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(sessionKey, restored.sessionKey)
        assertEquals(MULTIBYTE_NOTES, restored.notes)
        assertTrue(
            "Cada intento debe conservar el texto multibyte EXACTO.",
            restored.conditions.all { condition ->
                condition.attempts.all { attempt -> attempt.notes == MULTIBYTE_NOTES }
            },
        )
    }

    /**
     * Caso RECHAZADO (oversized): el dueño conserva el payload ANTERIOR byte a byte pero lo marca
     * como NO vigente; una reconstruccion NO resucita el contenido viejo.
     */
    @Test
    fun oversizedDraftKeepsPreviousPayloadInertAndDoesNotResurrectAfterRecreation() {
        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        val sessionKey = beginRestorableDraft(owner).sessionKey
        val previousPayload = requireNotNull(storedOwnerPayload(captureAggregateState()))

        compose.runOnUiThread { owner.update { it.copy(notes = "x".repeat(OVERSIZED_NOTE_CHARS)) } }
        assertEquals(
            "Un borrador por encima del presupuesto debe avisar por MOTIVO (sin truncar).",
            FvpRecoveryNotice.RefusedToStore(FvpRecoveryRefusal.TOO_LARGE),
            owner.recoveryNotice.value,
        )
        val liveDraft = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(
            "El borrador VIVO no se toca en un rechazo (solo queda sin recuperacion).",
            OVERSIZED_NOTE_CHARS,
            liveDraft.notes?.length,
        )

        val aggregate = captureAggregateState()
        assertEquals(
            "Ante un rechazo se conservan EXACTAMENTE los bytes anteriores (D6).",
            previousPayload,
            storedOwnerPayload(aggregate),
        )
        reportBudget("refused-retained", requireNotNull(storedOwnerPayload(aggregate)), aggregate)
        assertTrue(
            "El payload conservado debe quedar marcado como NO vigente (inerte).",
            storedOwnerInvalidated(aggregate),
        )
        assertTrue(
            "El agregado del caso rechazado sigue dentro del presupuesto.",
            parcelBytes(aggregate) <= MAX_AGGREGATE_PARCEL_BYTES,
        )

        val freshOwner = recreateWithClearedViewModelStore(owner)
        awaitReconciled(freshOwner)
        awaitSetupScreenDisplayed()
        assertNull(
            "El contenido viejo marcado como NO vigente no puede resucitar como borrador.",
            FvpDraftSession.draft.value,
        )
        assertEquals(
            "Sin recuperacion usable, el dueño lo declara (no inventa un borrador valido).",
            FvpRecoveryNotice.Unrecoverable,
            freshOwner.recoveryNotice.value,
        )
        val afterRecreate = captureAggregateState()
        assertEquals(
            "Los bytes inertes se conservan (no se borran) pero siguen marcados.",
            previousPayload,
            storedOwnerPayload(afterRecreate),
        )
        assertTrue(storedOwnerInvalidated(afterRecreate))
        assertFalse(
            "El sessionKey abandonado no puede volver a la sesion viva.",
            FvpDraftSession.draft.value?.sessionKey == sessionKey,
        )
    }

    /**
     * Guardado INCIERTO ya confirmado ([FvpRecoveryReconciliation.ALREADY_SAVED]) con la verdad
     * persistida REAL (Room): la fila manda y el borrador no se adopta.
     */
    @Test
    fun alreadySavedSessionKeyIsNotAdoptedAfterRecreation() {
        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        val sessionKey = beginRestorableDraft(owner).sessionKey
        seedPersistedRow(sessionKey = sessionKey, ownerAthleteId = athleteId, displaySuffix = "ALREADY_SAVED")
        requireNotNull(storedOwnerPayload(captureAggregateState()))

        val freshOwner = recreateWithClearedViewModelStore(owner)
        awaitReconciled(freshOwner)
        awaitSetupScreenDisplayed()
        assertNull(
            "Con el sessionKey YA persistedo por el mismo dueño, no hay borrador que recuperar.",
            FvpDraftSession.draft.value,
        )
        assertEquals(
            "ALREADY_SAVED no publica aviso (la serie SI esta guardada).",
            FvpRecoveryNotice.None,
            freshOwner.recoveryNotice.value,
        )
        assertNull(
            "La recuperacion queda limpia tras resolverse la reconciliacion.",
            storedOwnerPayload(captureAggregateState()),
        )
    }

    @Test
    fun aSessionKeyOwnedByAnotherAthleteIsRejectedWithoutChangingTheStoredRow() {
        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        val key = beginRestorableDraft(owner).sessionKey
        val otherAthlete = runBlocking {
            app.athleteRepository.create(displayName = "FVP R3-A other ${UUID.randomUUID()}", weightKg = 70.0)
        }
        additionalFixtureAthletes += otherAthlete
        seedPersistedRow(key, otherAthlete, "MISMATCHED_OWNER")
        val before = requireNotNull(runBlocking { app.fvpRepository.bySessionKey(key) })

        val fresh = recreateWithClearedViewModelStore(owner)
        awaitReconciled(fresh)
        awaitSetupScreenDisplayed()
        assertNull(FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, fresh.recoveryNotice.value)
        assertNull(storedOwnerPayload(captureAggregateState()))
        val after = requireNotNull(runBlocking { app.fvpRepository.bySessionKey(key) })
        assertEquals("No se reescribe la fila ajena", before, after)
        assertEquals(otherAthlete, after.athleteId)
    }

    @Test
    fun explicitDiscardClearsRegistryAndCannotRestoreTheOldDraft() {
        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        beginRestorableDraft(owner)
        assertTrue(storedOwnerPayload(captureAggregateState()) != null)
        compose.runOnUiThread { owner.reset() }
        val terminal = captureAggregateState()
        assertNull(storedOwnerPayload(terminal))
        assertFalse(storedOwnerInvalidated(terminal))

        val fresh = recreateWithClearedViewModelStore(owner)
        awaitReconciled(fresh)
        awaitSetupScreenDisplayed()
        assertNull(FvpDraftSession.draft.value)
        assertNull(storedOwnerPayload(captureAggregateState()))
        assertEquals(FvpRecoveryNotice.None, fresh.recoveryNotice.value)
    }

    /**
     * Atleta borrado/archivado ([FvpRecoveryReconciliation.ATHLETE_GONE]) con la verdad persistida
     * REAL: la sesion no se recupera y se avisa.
     */
    @Test
    fun archivedAthleteRecoveryIsNotAdoptedAndClearsRecovery() {
        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        val sessionKey = beginRestorableDraft(owner).sessionKey
        runBlocking { app.athleteRepository.archive(athleteId) }
        requireNotNull(storedOwnerPayload(captureAggregateState()))

        val freshOwner = recreateWithClearedViewModelStore(owner)
        awaitReconciled(freshOwner)
        awaitSetupScreenDisplayed()
        assertNull(
            "Un atleta archivado no puede recibir el borrador recuperado.",
            FvpDraftSession.draft.value,
        )
        assertEquals(
            "Atleta inutilizable => recuperacion no disponible y avisada.",
            FvpRecoveryNotice.Unrecoverable,
            freshOwner.recoveryNotice.value,
        )
        assertNull(
            "La recuperacion queda limpia tras resolverse la reconciliacion.",
            storedOwnerPayload(captureAggregateState()),
        )
        assertNotEquals(
            "El sessionKey no puede reaparecer en la sesion viva.",
            sessionKey,
            FvpDraftSession.draft.value?.sessionKey,
        )
    }

    /**
     * NAVEGACION real: se avanza por el gate de setup hasta `fvp/conditions` (ruta que EXIGE un
     * borrador vivo) y la recreacion con ViewModelStore limpio debe reproducir la MISMA ruta con el
     * borrador adoptado y el paso persistido en el payload.
     */
    @Test
    fun conditionsRouteAndRestoredStepComeBackThroughTheRealNavHost() {
        goHomeToFvpSetup()
        val owner = flowOwnerFromActivity()
        beginRestorableDraft(owner)
        compose.waitForIdle()

        // El formulario ya estaba compuesto antes del fixture: sus rememberSaveable no se
        // re-siembran por una mutacion posterior con la misma identidad. Se editan los campos reales.
        mapOf(
            "fvp-setup-mass" to "80",
        ).forEach { (tag, text) ->
            compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
            compose.onNodeWithTag(tag).performTextInput(text)
        }
        // DATA solo lleva atleta, protocolo y masa: se pasa a MEDICION antes de tocar h_po, hito y
        // procedimiento (la accion final sigue siendo la unica que confirma la serie).
        compose.onNodeWithTag(setupDataContinueTag).assertIsEnabled().performClick()
        mapOf(
            "fvp-setup-pushoff" to "40",
            "fvp-setup-landmark" to "TROCANTER",
            "fvp-setup-procedure" to "LANDMARK_EXTENDED_MINUS_CROUCH",
        ).forEach { (tag, text) ->
            compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
            compose.onNodeWithTag(tag).performTextInput(text)
        }
        compose.onNodeWithTag(setupContinueTag).assertIsEnabled().performClick()
        val addCondition = compose.activity.getString(R.string.fvp_add_condition)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(addCondition).fetchSemanticsNodes().isNotEmpty()
        }
        // Las dos condiciones del fixture ya existen: no afirmar que las creo esta pantalla.
        assertEquals(2, FvpDraftSession.draft.value?.conditions?.size)
        compose.waitForIdle()

        val aggregate = captureAggregateState()
        val storedPayload = requireNotNull(storedOwnerPayload(aggregate))
        val savedState = requireNotNull(FvpDraftStateCodec.decode(storedPayload))
        assertEquals(
            "El paso REAL del flujo debe viajar en el payload de recuperacion.",
            FvpFlowStep.CONDITIONS,
            savedState.step,
        )

        val freshOwner = recreateWithClearedViewModelStore(owner)
        awaitReconciled(freshOwner)
        // `fvp/conditions` solo se compone con un borrador vivo: verlo despues de la recreacion
        // prueba a la vez NAVEGACION restaurada por el NavHost de produccion y ADOPCION del dueño.
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(addCondition).fetchSemanticsNodes().isNotEmpty()
        }
        val restored = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(savedState.draft.sessionKey, restored.sessionKey)
        assertEquals(canonical(savedState.draft), canonical(restored))
    }

    // --- Navegacion real (AppNav) ---------------------------------------------------------------

    private fun goHomeToFvpSetup() {
        compose.waitForIdle()
        assertOwnerLivesUnderComposeDefaultKey() // ANTES de que el test pueda crear el dueño.
        val title = compose.activity.getString(R.string.fvp_title)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(setupDataContinueTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitSetupScreenDisplayed() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(setupDataContinueTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitReconciled(owner: FvpFlowViewModel) {
        compose.waitUntil(30_000) { !owner.reconciliationPending.value }
        compose.waitForIdle()
    }

    // --- Dueño real (ViewModelStore de la Activity) ----------------------------------------------

    /**
     * Dueño del flujo obtenido del `ViewModelStore` de la Activity con la MISMA clave por defecto que
     * usa Compose (`AppNav`): si Compose ya lo creo, devuelve ESA instancia; si no, la crea con el
     * mismo `Factory` y la composicion la reutilizara.
     */
    private fun flowOwnerFromActivity(): FvpFlowViewModel {
        lateinit var owner: FvpFlowViewModel
        compose.activityRule.scenario.onActivity { activity ->
            owner = ViewModelProvider(activity, FvpFlowViewModel.Factory)[FvpFlowViewModel::class.java]
        }
        return owner
    }

    private fun assertOwnerLivesUnderComposeDefaultKey() {
        compose.activityRule.scenario.onActivity { activity ->
            val keys = activityViewModelStore(activity).keys()
            assertTrue(
                "El dueño F-V-P creado por Compose debe vivir en el ViewModelStore de la Activity " +
                    "bajo la clave por defecto $ownerDefaultKey; claves reales=$keys",
                keys.contains(ownerDefaultKey),
            )
        }
    }

    /**
     * `ViewModelStore` REAL de la Activity. Se accede por el tipo de interfaz `ViewModelStoreOwner`
     * (getter Java) para no depender de como declara androidx su propio miembro.
     */
    private fun activityViewModelStore(activity: MainActivity): ViewModelStore {
        val owner: ViewModelStoreOwner = activity
        return owner.viewModelStore
    }

    /**
     * Simula MEMORIA FRESCA de ViewModels antes de recrear: `ActivityScenario.recreate()` conserva
     * los ViewModels (cambio de configuracion), de modo que se limpia el `ViewModelStore` de la
     * Activity y se resetea SOLO `FvpDraftSession` (la sesion por proceso). El dueño nuevo debe ser
     * una instancia distinta que restaura desde el registro real.
     */
    private fun recreateWithClearedViewModelStore(previous: FvpFlowViewModel): FvpFlowViewModel {
        compose.activityRule.scenario.onActivity { activity ->
            activityViewModelStore(activity).clear()
            FvpDraftSession.reset()
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val fresh = flowOwnerFromActivity()
        assertNotSame(
            "La recreacion con el ViewModelStore limpiado debe crear una instancia NUEVA del dueño.",
            previous,
            fresh,
        )
        return fresh
    }

    // --- Fotografia AGREGADA real de la Activity ------------------------------------------------

    /**
     * Ejecuta la MISMA llamada que el sistema usa para guardar el estado de una Activity
     * (`Activity.performSaveInstanceState` via `Instrumentation`): devuelve el `Bundle` agregado real
     * (jerarquia de vistas, `SavedStateRegistry` con Compose/NavHost y proveedores vecinos).
     * Es una captura adicional desde RESUMED, no la foto onStop del sistema: ComponentActivity baja
     * su Lifecycle a CREATED. El recomposer del ComposeTestRule permite seguir inspeccionando;
     * la restauracion usa la foto posterior de scenario.recreate(), no inyecta este Bundle.
     */
    private fun captureAggregateState(): Bundle {
        val outState = Bundle()
        compose.activityRule.scenario.onActivity { activity ->
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, outState)
        }
        return outState
    }

    private fun parcelBytes(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Payload EXACTO que el dueño dejo en su `SavedStateHandle`, leido del estado agregado real:
     * `SavedStateRegistry` -> `SavedStateHandlesProvider` -> bundle del handle de este ViewModel.
     *
     * Se inspecciona en SOLO LECTURA con APIs publicas de `Bundle` porque no existe API publica para
     * extraer el `SavedStateHandle` de un ViewModel ajeno ni para enumerar el registro; no se usa
     * reflexion ni se MODIFICA el handle (el unico escritor sigue siendo el dueño).
     */
    private fun storedOwnerPayload(aggregateState: Bundle): String? =
        savedOwnerHandleValues(aggregateState)[FVP_RECOVERY_PAYLOAD_KEY] as? String

    private fun storedOwnerInvalidated(aggregateState: Bundle): Boolean =
        savedOwnerHandleValues(aggregateState)[FVP_RECOVERY_INVALIDATED_KEY] as? Boolean == true

    private fun savedOwnerHandleValues(aggregateState: Bundle): Map<String, Any?> {
        val registry = requireNotNull(aggregateState.getBundle(SAVED_STATE_REGISTRY_KEY)) {
            "No hay registry; aggregateKeys=${aggregateState.keySet()}"
        }
        val handles = requireNotNull(registry.getBundle(SAVED_STATE_HANDLES_PROVIDER_KEY)) {
            "No hay handles provider; registryKeys=${registry.keySet()}"
        }
        val handleBundle = requireNotNull(handles.getBundle(ownerDefaultKey)) {
            "No hay handle del dueño; providerKeys=${handles.keySet()}"
        }
        return savedStateHandleValues(handleBundle)
    }

    private fun reportBudget(case: String, payload: String, aggregate: Bundle) {
        android.util.Log.i(
            "FvpRecoveryBudget",
            "case=$case jsonUtf8=${payload.toByteArray(Charsets.UTF_8).size} " +
                "aggregateParcel=${parcelBytes(aggregate)} invalidated=${storedOwnerInvalidated(aggregate)}",
        ) // Solo metadatos numericos: nunca contenido del borrador ni nombres de atletas.
    }

    /**
     * Formato serializado de un `SavedStateHandle` (el que produce su `SavedStateProvider`): dos
     * listas paralelas `keys`/`values`. Solo lectura; el `Bundle` es una copia del estado guardado.
     */
    @Suppress("DEPRECATION")
    private fun savedStateHandleValues(handleBundle: Bundle): Map<String, Any?> {
        val keys = requireNotNull(handleBundle.get(HANDLE_KEYS_BUNDLE_KEY) as? List<*>) {
            "Formato de keys inesperado; handleKeys=${handleBundle.keySet()}"
        }
        val values = requireNotNull(handleBundle.get(HANDLE_VALUES_BUNDLE_KEY) as? List<*>) {
            "Formato de values inesperado; handleKeys=${handleBundle.keySet()}"
        }
        return keys.mapIndexedNotNull { index, key ->
            val name = key as? String ?: return@mapIndexedNotNull null
            name to values.getOrNull(index)
        }.toMap()
    }

    private fun registerNeighborNonceProvider(nonce: String) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.savedStateRegistry.registerSavedStateProvider(
                nonceRegistryKey,
                object : SavedStateRegistry.SavedStateProvider {
                    override fun saveState(): Bundle = Bundle().apply { putString(nonceRegistryKey, nonce) }
                },
            )
        }
    }

    private fun assertNeighborNonceRestored(nonce: String) {
        compose.activityRule.scenario.onActivity { activity ->
            val restored = activity.savedStateRegistry.consumeRestoredStateForKey(nonceRegistryKey)
                ?.getString(nonceRegistryKey)
            assertEquals(
                "Un proveedor VECINO del registro debe sobrevivir a la MISMA foto agregada.",
                nonce,
                restored,
            )
        }
    }

    // --- Fixtures (SOLO datos del test) ----------------------------------------------------------

    private fun beginRestorableDraft(
        owner: FvpFlowViewModel,
        transform: (FvpDraft) -> FvpDraft = { it },
    ): FvpDraft = compose.runOnUiThread {
        val started = owner.begin(athleteId)
        val updated = requireNotNull(
            owner.update { draft ->
                transform(
                    draft.copy(
                        bodyMassKg = 80.0,
                        bodyMassFromProfile = false,
                        pushOffMode = FvpPushOffUiMode.COMMON,
                        commonPushOffM = 0.4,
                        pushOffLandmark = "TROCANTER",
                        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
                        pushOffMeasuredAt = MEASURED_AT_MS,
                        conditions = listOf(
                            FvpConditionDraft(
                                conditionKey = "0kg",
                                addedLoadKg = 0.0,
                                pushOffM = 0.4,
                                attempts = listOf(
                                    FvpAttemptDraft(
                                        heightM = 0.30,
                                        method = FvpMeasureMethod.MANUAL,
                                        flightTimeS = 0.247,
                                        notes = "intento 0kg",
                                    ),
                                ),
                            ),
                            FvpConditionDraft(
                                conditionKey = "20kg",
                                addedLoadKg = 20.0,
                                pushOffM = 0.4,
                                attempts = listOf(
                                    FvpAttemptDraft(
                                        heightM = 0.26,
                                        method = FvpMeasureMethod.MANUAL,
                                        flightTimeS = 0.230,
                                        notes = "intento 20kg",
                                    ),
                                ),
                            ),
                        ),
                        notes = "nota sintetica R3-A",
                    ),
                )
            },
        )
        assertEquals(started.sessionKey, updated.sessionKey)
        updated
    }

    /**
     * Fixture calibrado para quedar justo por debajo del presupuesto: se busca por biseccion el
     * maximo numero de intentos con notas de 64 caracteres cuyo JSON UTF-8 NO supere 65 536 bytes.
     * La calibracion usa el codec puro; las ASERCIONES se hacen sobre el payload real del registro.
     */
    private fun nearBudgetDraft(sessionKey: String): FvpDraft {
        fun attempts(count: Int): List<FvpAttemptDraft> = (0 until count).map { index ->
            FvpAttemptDraft(
                heightM = 0.30,
                method = FvpMeasureMethod.MANUAL,
                flightTimeS = 0.247,
                notes = "x".repeat(64) + index.toString().takeLast(1),
            )
        }

        fun candidate(count: Int): FvpDraft {
            val first = count / 2
            val second = count - first
            return FvpDraft(
                sessionKey = sessionKey,
                athleteId = athleteId,
                bodyMassKg = 80.0,
                bodyMassFromProfile = false,
                pushOffMode = FvpPushOffUiMode.COMMON,
                commonPushOffM = 0.4,
                pushOffLandmark = "TROCANTER",
                pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
                pushOffMeasuredAt = MEASURED_AT_MS,
                conditions = listOf(
                    FvpConditionDraft(
                        conditionKey = "0kg",
                        addedLoadKg = 0.0,
                        pushOffM = 0.4,
                        attempts = attempts(first),
                    ),
                    FvpConditionDraft(
                        conditionKey = "20kg",
                        addedLoadKg = 20.0,
                        pushOffM = 0.4,
                        attempts = attempts(second),
                    ),
                ),
                notes = "near-budget R3-A",
            )
        }

        var low = 1
        var high = 2
        while (encodedUtf8Bytes(candidate(high)) <= FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES) {
            low = high
            high *= 2
            require(high < 1_000_000) { "Fixtures de near-budget fuera de rango." }
        }
        while (low + 1 < high) {
            val mid = (low + high) / 2
            if (encodedUtf8Bytes(candidate(mid)) <= FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES) {
                low = mid
            } else {
                high = mid
            }
        }
        return candidate(low)
    }

    private fun encodedUtf8Bytes(draft: FvpDraft): Int =
        FvpDraftStateCodec
            .encode(FvpRecoveryState(draft = draft, step = FvpFlowStep.SETUP))
            ?.toByteArray(Charsets.UTF_8)
            ?.size
            ?: Int.MAX_VALUE

    /**
     * Fila persistida REAL en Room que ocupa el `sessionKey` (verdad persistida que consulta el
     * `FvpFlowViewModel.Factory` de produccion). Solo se rellenan las columnas obligatorias: la
     * reconciliacion solo lee `sessionKey` y su dueño.
     */
    private fun seedPersistedRow(sessionKey: String, ownerAthleteId: Long, displaySuffix: String) {
        val rowId = runBlocking {
            app.database.fvpDao().insertAssessmentRaw(
                FvpAssessmentEntity(
                    sessionKey = sessionKey,
                    athleteId = ownerAthleteId,
                    protocolId = "SJ",
                    dateTime = MEASURED_AT_MS,
                    bodyMassKg = 80.0,
                    bodyMassSource = "MANUAL",
                    pushOffMode = "COMMON",
                    pushOffM = 0.4,
                    pushOffSource = "MANUAL",
                    algorithmVersion = "fvp-poc-r3a-$displaySuffix",
                    methodVersion = FVP_METHOD_VERSION,
                    f0N = 0.0,
                    slopeFv = 0.0,
                    sfvNsPerM = 0.0,
                    v0Mps = null,
                    pmaxW = null,
                    r2 = null,
                    conditionCount = 0,
                    vMinMps = 0.0,
                    vMaxMps = 0.0,
                    fMinN = 0.0,
                    fMaxN = 0.0,
                    f0RelNPerKg = null,
                    pmaxRelWPerKg = null,
                    warningsCsv = "",
                ),
            )
        }
        assertTrue("La fila persistida del test debe insertarse.", rowId > 0L)
    }

    private fun canonical(draft: FvpDraft): FvpDraft = draft.copy(
        conditions = draft.conditions.map { condition ->
            condition.copy(
                draftKey = "",
                attempts = condition.attempts.map { attempt -> attempt.copy(draftKey = "") },
            )
        },
    )

    private companion object {
        const val MEASURED_AT_MS = 1_700_000_000_000L

        /** Cota de ingenieria del estado AGREGADO de la Activity (no garantia universal de Binder). */
        const val MAX_AGGREGATE_PARCEL_BYTES = 262_144

        const val OVERSIZED_NOTE_CHARS = 70_000

        const val MULTIBYTE_NOTES = "áéíóúüñç€漢字🔥 medición multibyte"

        // Claves INTERNAS del formato de SavedState (androidx.savedstate / androidx.lifecycle). Se
        // documentan aqui porque no existe API publica de enumeracion del registro guardado.
        const val SAVED_STATE_REGISTRY_KEY = "androidx.lifecycle.BundlableSavedStateRegistry.key"
        const val SAVED_STATE_HANDLES_PROVIDER_KEY = "androidx.lifecycle.internal.SavedStateHandlesProvider"
        const val HANDLE_KEYS_BUNDLE_KEY = "keys"
        const val HANDLE_VALUES_BUNDLE_KEY = "values"
    }
}
