package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.FvpAssessmentEntity
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.fvp.FvpProtocol
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.fvp.FvpMeasureMethod
import java.io.File
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Entrada DECLARADA de procedencia en el paso 1 (B4a-1) sobre el paso 1 REAL, Room EN MEMORIA y sin
 * `MainActivity`/preferencias.
 *
 * Cubre las tres cosas que el contrato 18 §2/§3 exige de esta superficie y NADA mas:
 * 1. por defecto la serie nace `UNKNOWN` (procedencia de masa y contexto) y SIN fechas: el flujo no
 *    infiere ni rellena `acquiredAt`/`bodyMassMeasuredAt` con la hora de guardado;
 * 2. una declaracion explicita (`WEIGHED` + `SAME_ACQUISITION` + clave) llega 1:1 a la columna, con
 *    la clave recortada, y NO cambia la procedencia PROFILE/MANUAL de la masa (`bodyMassSource`);
 * 3. `SAME_ACQUISITION` sin clave (tambien con solo espacios) BLOQUEA el avance y no persiste nada.
 *
 * Lo que NO cubre: condiciones/reutilizacion (B4a-2), dias/fechas declaradas (este slice no tiene
 * selector de fecha de adquisicion) y la validacion biomecanica. La persistencia se ejecuta con el
 * camino de produccion (`toPersistDraftOrNull` + `FvpRepository.save`) sobre el borrador REAL que
 * dejo la accion CONTINUAR; el fixture anade solo las condiciones que el paso 1 no declara (el motor
 * exige 2 cargas o mas para ajustar el perfil) y no toca ninguna columna de procedencia.
 *
 * En esta estacion solo se verifica la COMPILACION del source set instrumentado (sin dispositivo).
 */
@RunWith(AndroidJUnit4::class)
class FvpProvenanceUiInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: JumpDatabase
    private lateinit var repository: FvpRepository
    private var athleteId: Long = 0L

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        FvpDraftSession.reset()
        // La introduccion de primer uso ya esta vista: estos casos prueban la procedencia declarada,
        // no la presentacion inicial (preferencia de UI, no dato del atleta ni del borrador).
        markFvpIntroductionSeen()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        repository = FvpRepository(database.fvpDao())
        athleteId = runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = "FVP PROVENANCE", weightKg = 80.0, createdAt = 1L, updatedAt = 1L),
            )
        }
    }

    @After
    fun tearDown() {
        FvpDraftSession.reset()
        database.close()
    }

    /**
     * Default honesto: `UNKNOWN`/`UNKNOWN`/sin clave y sin fechas de adquisicion ni de pesada, tanto
     * en el borrador que deja CONTINUAR como en la fila que persiste el repositorio.
     */
    @Test
    fun setupDefaultsToUnknownProvenanceAndPersistsWithoutDates() {
        setSetupContent()

        compose.onNodeWithTag("fvp-body-mass-measurement-UNKNOWN").performScrollTo().assertIsSelected()
        // El contexto de adquisicion YA NO se edita aqui: su UNICO dueno es Condiciones (el commit
        // de Setup conserva el contexto del borrador vivo de la MISMA identidad). La comprobacion de
        // la superficie UNKNOWN por defecto vive en su propia prueba sobre la pantalla real.

        fillRequiredSetupFields()
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        compose.waitForIdle()

        val draft = requireNotNull(FvpDraftSession.draft.value) { "CONTINUAR debe volcar el borrador" }
        assertEquals("UNKNOWN", draft.bodyMassMeasurement)
        assertEquals("UNKNOWN", draft.acquisitionContextKind)
        assertNull("sin clave declarada no puede haber clave en el borrador", draft.acquisitionContextKey)
        assertNull("el paso 1 no declara fecha de adquisicion", draft.acquiredAt)
        assertNull("el paso 1 no declara fecha de pesada", draft.bodyMassMeasuredAt)

        val row = requireNotNull(saveAndReadRow(draft)) { "la serie debe quedar persistida" }
        assertEquals("UNKNOWN", row.bodyMassMeasurement)
        assertEquals("UNKNOWN", row.acquisitionContextKind)
        assertNull(row.acquisitionContextKey)
        assertNull("la fecha de adquisicion NO se rellena con la hora de guardado", row.acquiredAt)
        assertNull("la fecha de pesada NO se rellena con la hora de guardado", row.bodyMassMeasuredAt)
    }

    /**
     * Declaracion de la PROCEDENCIA DE LA MASA en Setup: `WEIGHED` + masa sugerida por el perfil.
     * El contexto de adquisicion se declara en Condiciones (dueno unico desde el slice B) y se
     * comprueba en su propia prueba; al continuar, este caso verifica que el commit de Setup NO
     * toca el contexto del borrador vivo (aqui, el default honesto `UNKNOWN`).
     */
    @Test
    fun declaredBodyMassProvenanceReachesRoomVerbatimAndKeepsBodyMassSource() {
        setSetupContent()

        compose.onNodeWithTag("fvp-body-mass-measurement-WEIGHED").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-body-mass-measurement-WEIGHED").assertIsSelected()

        fillRequiredSetupFields()
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        compose.waitForIdle()

        val draft = requireNotNull(FvpDraftSession.draft.value) { "CONTINUAR debe volcar el borrador" }
        assertEquals("WEIGHED", draft.bodyMassMeasurement)
        assertEquals("el commit de Setup conserva el contexto del borrador vivo", "UNKNOWN", draft.acquisitionContextKind)
        assertNull("sin clave declarada no puede haber clave en el borrador", draft.acquisitionContextKey)

        val row = requireNotNull(saveAndReadRow(draft)) { "la serie debe quedar persistida" }
        assertEquals("WEIGHED", row.bodyMassMeasurement)
        assertEquals("UNKNOWN", row.acquisitionContextKind)
        assertNull(row.acquisitionContextKey)
        assertEquals(
            "declarar la pesada NO cambia la procedencia PROFILE/MANUAL de la masa",
            "PROFILE",
            row.bodyMassSource,
        )
        assertNull(row.acquiredAt)
        assertNull(row.bodyMassMeasuredAt)
    }

    /**
     * Contexto por defecto en su UNICA pantalla duena: Condiciones muestra `UNKNOWN` seleccionado,
     * sin campo de clave, y la fila persistida conserva ese default (sin inventar fechas).
     */
    @Test
    fun conditionsShowUnknownContextByDefaultAndPersistIt() {
        FvpDraftSession.adopt(completeDraftForConditions())
        setConditionsContent()

        compose.onNodeWithTag("fvp-acquisition-context-UNKNOWN").performScrollTo().assertIsSelected()
        assertEquals(
            "con el contexto sin declarar no debe existir el campo de clave",
            0,
            compose.onAllNodesWithTag("fvp-acquisition-context-key").fetchSemanticsNodes().size,
        )
        val draft = requireNotNull(FvpDraftSession.draft.value)
        assertEquals("UNKNOWN", draft.acquisitionContextKind)
        assertNull(draft.acquisitionContextKey)
        val row = requireNotNull(saveAndReadRow(draft)) { "la serie debe quedar persistida" }
        assertEquals("UNKNOWN", row.acquisitionContextKind)
        assertNull(row.acquisitionContextKey)
        assertNull(row.acquiredAt)
        assertNull(row.bodyMassMeasuredAt)
    }

    /**
     * Declaracion explicita en Condiciones: `SAME_ACQUISITION` + clave con espacios de sobra. Lo que
     * llega a la columna es exactamente la clave recortada y la masa conserva su `PROFILE`.
     */
    @Test
    fun declaredAcquisitionContextReachesRoomVerbatim() {
        FvpDraftSession.adopt(completeDraftForConditions())
        setConditionsContent()

        compose.onNodeWithTag("fvp-acquisition-context-SAME_ACQUISITION").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-acquisition-context-SAME_ACQUISITION").assertIsSelected()
        // El contrato exige la clave SIN espacios al inicio/fin: se teclean a proposito.
        compose.onNodeWithTag("fvp-acquisition-context-key").performScrollTo().performTextInput("  ctx-a  ")
        compose.waitForIdle()
        captureScrollToTag("fvp-acquisition-context-key", "conditions-provenance-declared-same-acquisition")

        val draft = requireNotNull(FvpDraftSession.draft.value)
        assertEquals("SAME_ACQUISITION", draft.acquisitionContextKind)
        assertEquals("ctx-a", draft.acquisitionContextKey)

        val row = requireNotNull(saveAndReadRow(draft)) { "la serie debe quedar persistida" }
        assertEquals("SAME_ACQUISITION", row.acquisitionContextKind)
        assertEquals("la clave se persiste recortada", "ctx-a", row.acquisitionContextKey)
        assertEquals(
            "declarar el contexto NO cambia la procedencia PROFILE/MANUAL de la masa",
            "PROFILE",
            row.bodyMassSource,
        )
        assertNull(row.acquiredAt)
        assertNull(row.bodyMassMeasuredAt)
    }

    /**
     * Ruta de bloqueo EN SU PANTALLA DUENA: `SAME_ACQUISITION` exige una clave no vacia. Sin ella (y
     * con solo espacios) Calcular queda deshabilitado, el motivo exacto se anuncia y el borrador no
     * gana una clave inventada. Teclear una clave real desbloquea. El `UNKNOWN` sigue siendo valido.
     */
    @Test
    fun sameAcquisitionWithoutContextKeyBlocksCalculate() {
        // Aislar el bloqueo de contexto exige una serie calculable: el fixture de reutilizacion
        // tiene UNA carga y seguiria bloqueado por ese motivo aun con una clave valida.
        val base = completeDraftForConditions()
        FvpDraftSession.adopt(base.copy(conditions = listOf(
            base.conditions.single(),
            FvpConditionDraft(conditionKey = "20kg", addedLoadKg = 20.0, attempts = listOf(
                FvpAttemptDraft(heightM = 0.32, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
            )),
            FvpConditionDraft(conditionKey = "40kg", addedLoadKg = 40.0, attempts = listOf(
                FvpAttemptDraft(heightM = 0.25, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
            )),
        )))
        setConditionsContent()

        compose.onNodeWithTag("fvp-acquisition-context-SAME_ACQUISITION").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-acquisition-context-key").performScrollTo().assertIsDisplayed()
        // La falta de clave ya no tiene un aviso global legacy: el error vive EN EL CAMPO (editor
        // nuevo del contexto), con el mismo texto contractual. Se acota al editor para no confundirlo
        // con el motivo del pie, que repite la misma cadena.
        compose.onNode(
            hasText(context.getString(R.string.fvp_acquisition_context_key_required), substring = true) and
                hasAnyAncestor(hasTestTag("fvp-acquisition-context")),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        compose.onNodeWithTag("fvp-conditions-first-reason")
            .assertTextEquals(context.getString(R.string.fvp_acquisition_context_key_required))
        assertNull("el bloqueo no puede inventar una clave en el borrador", FvpDraftSession.draft.value?.acquisitionContextKey)
        assertTrue(
            "un contexto bloqueado no puede persistir ninguna serie",
            runBlocking { database.fvpDao().forAthlete(athleteId).first() }.isEmpty(),
        )
        captureScrollToTag("fvp-acquisition-context-key", "conditions-provenance-key-missing-blocked")

        // Una clave de SOLO espacios tampoco vale: se recorta antes de validar.
        compose.onNodeWithTag("fvp-acquisition-context-key").performTextInput("   ")
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsNotEnabled()
        compose.onNode(
            hasText(context.getString(R.string.fvp_acquisition_context_key_required), substring = true) and
                hasAnyAncestor(hasTestTag("fvp-acquisition-context")),
            useUnmergedTree = true,
        ).assertExists()

        compose.onNodeWithTag("fvp-acquisition-context-key").performTextClearance()
        compose.onNodeWithTag("fvp-acquisition-context-key").performTextInput("ctx-b")
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
        // La clave NO se inventa nunca: el texto del campo es exactamente el tecleado.
        assertEquals("ctx-b", editableText("fvp-acquisition-context-key"))
        assertEquals("ctx-b", FvpDraftSession.draft.value?.acquisitionContextKey)

        // `UNKNOWN` no bloquea y retira la clave del borrador (sigue siendo el default honesto).
        compose.onNodeWithTag("fvp-acquisition-context-UNKNOWN").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-conditions-calculate").assertIsEnabled()
        assertNull("UNKNOWN no puede conservar una clave declarada", FvpDraftSession.draft.value?.acquisitionContextKey)
    }

    // ------------------------------------------------------------------------------------------
    // Montaje y utilidades
    /**
     * Sembrado por IDENTIDAD: una serie guardada de la MISMA atleta+protocolo con procedencia
     * conocida rellena los controles y vuelve a persistir exactamente lo mismo (incluida la clave).
     */
    @Test
    fun seededSeriesKeepsProvenance() {
        FvpDraftSession.adopt(
            FvpDraft(
                sessionKey = "seeded-provenance",
                athleteId = athleteId,
                protocol = FvpProtocol.SJ,
                bodyMassKg = 80.0,
                bodyMassFromProfile = true,
                pushOffMode = FvpPushOffUiMode.COMMON,
                commonPushOffM = 0.4,
                pushOffLandmark = "TROCANTER",
                pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
                bodyMassMeasurement = "WEIGHED",
                acquisitionContextKind = "SAME_ACQUISITION",
                acquisitionContextKey = "ctx-seed",
            ),
        )
        setSetupContent()
        // La masa y su procedencia SI se siembran en Setup; el contexto ya no tiene copia editable
        // aqui (dueno unico: Condiciones), pero el commit NO debe perderlo.
        compose.onNodeWithTag("fvp-body-mass-measurement-WEIGHED").performScrollTo().assertIsSelected()

        // La accion final vive en MEDICION: primero se recorre el gate propio de DATA (solo datos,
        // sin commit parcial).
        goToMeasurement()
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        compose.waitForIdle()
        val draft = requireNotNull(FvpDraftSession.draft.value)
        assertEquals("WEIGHED", draft.bodyMassMeasurement)
        assertEquals("SAME_ACQUISITION", draft.acquisitionContextKind)
        assertEquals("ctx-seed", draft.acquisitionContextKey)
        val row = requireNotNull(saveAndReadRow(draft))
        assertEquals("WEIGHED", row.bodyMassMeasurement)
        assertEquals("SAME_ACQUISITION", row.acquisitionContextKind)
        assertEquals("ctx-seed", row.acquisitionContextKey)
        assertNull(row.acquiredAt)
        assertNull(row.bodyMassMeasuredAt)
    }

    /**
     * Aislamiento por identidad: un borrador de OTRA atleta+protocolo no siembra los controles ni
     * arrastra su clave a la pantalla del atleta actual.
     */
    @Test
    fun foreignDraftDoesNotBleedIntoSetup() {
        FvpDraftSession.adopt(
            FvpDraft(
                sessionKey = "foreign-provenance",
                athleteId = athleteId + 7_000L,
                protocol = FvpProtocol.SJ,
                bodyMassKg = 80.0,
                bodyMassFromProfile = true,
                pushOffMode = FvpPushOffUiMode.COMMON,
                commonPushOffM = 0.4,
                bodyMassMeasurement = "WEIGHED",
                acquisitionContextKind = "SAME_ACQUISITION",
                acquisitionContextKey = "ctx-ajena",
            ),
        )
        setSetupContent()
        // Un borrador ajeno no siembra la procedencia de la masa de esta pantalla.
        compose.onNodeWithTag("fvp-body-mass-measurement-UNKNOWN").performScrollTo().assertIsSelected()
        // Y su declaracion de contexto no puede viajar al borrador de la identidad actual: el
        // commit usa el contexto del borrador vivo SOLO si la identidad coincide.
        fillRequiredSetupFields()
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        compose.waitForIdle()
        val draft = requireNotNull(FvpDraftSession.draft.value)
        assertEquals("UNKNOWN", draft.acquisitionContextKind)
        assertNull(draft.acquisitionContextKey)
    }

    private fun draftWithConfirmedReuseForModeTests(): FvpDraft =
        FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
            bodyMassKg = 80.0,
            bodyMassFromProfile = true,
            pushOffMode = FvpPushOffUiMode.COMMON,
            commonPushOffM = 0.40,
            pushOffLandmark = "TROCANTER",
            pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
            conditions = listOf(
                FvpConditionDraft(
                    conditionKey = "0kg",
                    addedLoadKg = 0.0,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = 0.40,
                            method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                            sourceFps = 240.0,
                        ).withReuseConfirmation(1_700_000_900_000L),
                    ),
                ),
            ),
        )

    /** Sin tocar un campo de serie, CONTINUAR conserva la confirmacion durable. */
    @Test
    fun continuingSetupWithoutSeriesChangesKeepsDurableReuseConfirmation() {
        FvpDraftSession.adopt(draftWithConfirmedReuseForModeTests())
        setSetupContent()
        goToMeasurement()
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(
            1_700_000_900_000L,
            requireNotNull(FvpDraftSession.draft.value).conditions[0].attempts[0].reuseConfirmedAt,
        )
    }

    /**
     * Cableado B4a-2b: cambiar en el setup un campo de SERIE que forma parte de la aceptacion
     * (aqui el modo de h_po, que decide que h_po es el relevante) retira la confirmacion durable en
     * la misma mutacion de CONTINUAR.
     */
    @Test
    fun changingSeriesPushOffModeInSetupClearsDurableReuseConfirmation() {
        FvpDraftSession.adopt(draftWithConfirmedReuseForModeTests())
        setSetupContent()
        // El modo de h_po vive en MEDICION: se recorre el gate de DATA primero.
        goToMeasurement()
        // El chip vive en una fila con scroll horizontal: se invoca su accion de click directamente
        // (mismo handler) en vez de depender de la geometria del scroll.
        compose.onNodeWithTag("fvp-setup-advanced-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-setup-pushoff-mode-PER_CONDITION")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled().performClick()
        compose.waitForIdle()
        val mutated = requireNotNull(FvpDraftSession.draft.value)
        assertNull(
            "cambiar el modo de h_po debe retirar la confirmacion en la misma mutacion",
            mutated.conditions[0].attempts[0].reuseConfirmedAt,
        )
        assertNull(mutated.conditions[0].attempts[0].reuseConfirmationVersion)
        assertEquals(FvpPushOffUiMode.PER_CONDITION, mutated.pushOffMode)
    }

    // ------------------------------------------------------------------------------------------

    /** Paso 1 REAL sin dueno del flujo: la mutacion de CONTINUAR va a `FvpDraftSession`. */
    private fun setSetupContent() {
        compose.setContent {
            FvpSetupScreen(
                activeAthletes = listOf(sampleAthlete()),
                athletesLoading = false,
                selectedAthleteId = athleteId,
                onAthleteSelected = {},
                onBack = {},
                onContinue = {},
                onHistory = {},
            )
        }
    }

    /** Paso 3 REAL (Condiciones) con un borrador YA comprometido y el fixture en memoria. */
    private fun setConditionsContent() {
        val viewModel = FvpViewModel(repository, JumpRepository(database.jumpDao()))
        compose.setContent {
            FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
        }
    }

    /** DATA -> MEDICION por la accion propia de la pagina (no confirma la serie). */
    private fun goToMeasurement() {
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("fvp-page-measurement").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Borrador COMPLETO (setup + condiciones) para montar Condiciones sin pasar por la navegacion. */
    private fun completeDraftForConditions(): FvpDraft = draftWithConfirmedReuseForModeTests().copy(
        acquisitionContextKind = "UNKNOWN",
        acquisitionContextKey = null,
    )

    private fun sampleAthlete(): AthleteEntity = AthleteEntity(
        id = athleteId,
        displayName = "FVP PROVENANCE",
        weightKg = 80.0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    /**
     * Rellena lo unico que el paso 1 exige ademas de las declaraciones: h_po + hito + procedimiento.
     * La masa NO se teclea: llega sugerida por el perfil (80 kg) y asi la declaracion de procedencia
     * se prueba sobre una masa que el flujo marco como `PROFILE`.
     */
    private fun fillRequiredSetupFields() {
        compose.waitForIdle()
        assertTrue(
            "la masa debe llegar sugerida por el perfil del atleta",
            editableText("fvp-setup-mass").isNotEmpty(),
        )
        goToMeasurement()
        compose.onNodeWithTag("fvp-setup-pushoff").performScrollTo()
            .performTextInput(localeDecimal("40"))
        compose.onNodeWithTag("fvp-setup-landmark").performScrollTo().performTextInput("TROCANTER")
        compose.onNodeWithTag("fvp-setup-procedure").performScrollTo()
            .performTextInput("LANDMARK_EXTENDED_MINUS_CROUCH")
        compose.waitForIdle()
    }

    /**
     * Persiste el borrador REAL del paso 1 por el camino de produccion ([toPersistDraftOrNull] +
     * [FvpRepository.save], el mismo que usa el flujo al guardar) y devuelve la fila leida por el
     * DAO. El fixture anade UNICAMENTE las condiciones que el paso 1 no declara: ninguna columna de
     * procedencia pasa por aqui.
     */
    private fun saveAndReadRow(draft: FvpDraft): FvpAssessmentEntity? {
        val computable = draft.copy(
            conditions = listOf(
                FvpConditionDraft(
                    conditionKey = "0kg",
                    addedLoadKg = 0.0,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = 0.40,
                            method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                            sourceFps = 240.0,
                        ),
                    ),
                ),
                FvpConditionDraft(
                    conditionKey = "20kg",
                    addedLoadKg = 20.0,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = 0.32,
                            method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                            sourceFps = 240.0,
                        ),
                    ),
                ),
                FvpConditionDraft(
                    conditionKey = "40kg",
                    addedLoadKg = 40.0,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = 0.25,
                            method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                            sourceFps = 240.0,
                        ),
                    ),
                ),
            ),
        )
        val persistable = requireNotNull(computable.toPersistDraftOrNull()) {
            "el borrador del paso 1 con una serie calculable debe ser persistible"
        }
        runBlocking { repository.save(persistable) }
        return runBlocking { database.fvpDao().bySessionKey(draft.sessionKey) }
    }

    // Capturas opt-in: evidencia visual, NUNCA un requisito de la pasada de correccion por defecto.
    private val captureEvidence
        get() = InstrumentationRegistry.getArguments().getString("fvpCaptureEvidence") == "true"

    /** Deja el nodo `tag` dentro del viewport (si hay scroll) y captura la pantalla completa. */
    private fun captureScrollToTag(tag: String, name: String) {
        if (!captureEvidence) return
        compose.onNodeWithTag(tag).performScrollTo()
        capture(name)
    }

    private fun capture(name: String) {
        if (!captureEvidence) return
        compose.waitForIdle()
        val directory = File(context.cacheDir, "fvp_provenance_ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    /** Valor tecleado de un campo de texto etiquetado. */
    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /**
     * Texto decimal robusto al locale del AVD: se construye con el separador decimal del locale (el
     * mismo que usa la UI) y se comprueba con el parser de la app; si no parsease, se usa el punto,
     * que el parser normaliza igualmente. El test NO depende del locale del aparato.
     */
    private fun localeDecimal(plain: String): String {
        val locale: Locale = context.resources.configuration.locales[0]
        val separator = (NumberFormat.getNumberInstance(locale) as? DecimalFormat)
            ?.decimalFormatSymbols?.decimalSeparator ?: '.'
        val candidate = plain.replace('.', separator)
        return if (MeasurementFormatting.parseNumber(candidate, locale) != null) candidate else plain
    }
}
