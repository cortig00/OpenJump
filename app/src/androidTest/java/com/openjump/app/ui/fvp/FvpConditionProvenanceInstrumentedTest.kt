package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AssessmentEntity
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AttemptEntity
import com.openjump.app.data.AttemptMetricEntity
import com.openjump.app.data.FVP_REUSE_CONFIRMATION_VERSION
import com.openjump.app.data.FvpAttemptEntity
import com.openjump.app.data.FvpConditionEntity
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import java.io.File
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Procedencia de la CARGA por condicion y confirmacion DURABLE de reutilizacion (B4a-2) sobre la
 * pantalla REAL de condiciones, Room EN MEMORIA y sin `MainActivity`/preferencias.
 *
 * Cubre lo que exige el contrato 18 §2 (y su seccion «Confirmacion de reutilizacion v1») y NADA
 * mas:
 * 1. toda condicion nace `UNKNOWN` y sin fecha de medida de la carga (`addedLoadMeasuredAt` sigue
 *    NULL: no hay selector de fecha en este slice y no se inventa ninguna);
 * 2. la procedencia declarada de la carga llega a la FILA de ESA condicion (no a otra), y una
 *    condicion distinta puede declarar otra procedencia;
 * 3. confirmar la reutilizacion de una medicion guardada escribe `reuseConfirmedAt` (instante real)
 *    + `reuseConfirmationVersion` = `fvp-reuse-1` y los campos de origen que la fuente SI aporta,
 *    dejando `timeMapping` = `UNKNOWN` y los PTS en NULL (la fuente no los trae y aqui no se
 *    inventan);
 * 4. cambiar despues la carga de esa condicion retira la confirmacion EN LA MISMA mutacion del
 *    borrador, y la serie guardada despues ya no la lleva.
 *
 * Lo que NO cubre: consumidores de calidad/comparacion (B4b), fechas de captura (no existen en la
 * fuente) ni validacion biomecanica. En esta estacion solo se verifica la COMPILACION del source
 * set instrumentado (sin dispositivo); la ejecucion corresponde al AVD del padre.
 */
@RunWith(AndroidJUnit4::class)
class FvpConditionProvenanceInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: JumpDatabase
    private lateinit var repository: FvpRepository
    private lateinit var viewModel: FvpViewModel
    private var athleteId: Long = 0L

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        FvpDraftSession.reset()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        repository = FvpRepository(database.fvpDao())
        viewModel = FvpViewModel(repository, JumpRepository(database.jumpDao()))
        athleteId = runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = "FVP CONDITION PROVENANCE", weightKg = 80.0, createdAt = 1L, updatedAt = 1L),
            )
        }
    }

    @After
    fun tearDown() {
        FvpDraftSession.reset()
        database.close()
    }

    /**
     * Default honesto: `UNKNOWN` en TODAS las condiciones, sin fecha de medida de la carga, tanto en
     * el borrador que deja la pantalla como en las filas que persiste el repositorio.
     */
    @Test
    fun conditionLoadSourceDefaultsToUnknownAndPersistsWithoutMeasuredAt() {
        FvpDraftSession.adopt(physicalDraft())
        setConditionsContent()

        compose.onNodeWithTag("fvp-condition-load-source-0-UNKNOWN").performScrollTo().assertIsSelected()
        // Cada carga tiene SU editor (solo uno activo): para leer los chips de la carga 3 hay que
        // abrirla por su fila; no se asume que todas esten compuestas a la vez.
        openLoad(3)
        compose.onNodeWithTag("fvp-condition-load-source-3-UNKNOWN").performScrollTo().assertIsSelected()
        // La carga numerica de la carga 1 NO promueve la procedencia: sigue declarada como
        // desconocida en SU editor.
        openLoad(1)
        compose.onNodeWithTag("fvp-condition-load-source-1-UNKNOWN").assertIsSelected()
        compose.onNodeWithTag("fvp-condition-load-source-1-WEIGHED").assertIsNotSelected()

        val draft = requireNotNull(FvpDraftSession.draft.value)
        assertTrue(
            "ninguna condicion puede nacer con procedencia declarada",
            draft.conditions.all { it.addedLoadSource == "UNKNOWN" },
        )
        assertTrue(
            "este slice no declara fecha de medida de la carga",
            draft.conditions.all { it.addedLoadMeasuredAt == null },
        )

        val conditions = saveAndReadConditions(draft)
        assertEquals(4, conditions.size)
        assertTrue(
            "la procedencia desconocida se persiste tal cual",
            conditions.all { it.addedLoadSource == "UNKNOWN" },
        )
        assertTrue(
            "la fecha de medida de la carga NO se rellena con la hora de guardado",
            conditions.all { it.addedLoadMeasuredAt == null },
        )
    }

    /**
     * La declaracion va a la FILA de la condicion elegida: `WEIGHED` en la segunda y `NOMINAL` en la
     * cuarta, con las otras dos intactas, tanto en el borrador como en lo persistido.
     */
    @Test
    fun declaredLoadSourceReachesOnlyTheChosenConditionRow() {
        FvpDraftSession.adopt(physicalDraft())
        setConditionsContent()

        openLoad(1)
        compose.onNodeWithTag("fvp-condition-load-source-1-WEIGHED").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-condition-load-source-1-WEIGHED").assertIsSelected()
        compose.onNodeWithTag("fvp-condition-load-source-1-UNKNOWN").assertIsNotSelected()
        // Revisar/abrir otra carga cambia el editor ACTIVO, no la declaracion ya hecha.
        openLoad(3)
        compose.onNodeWithTag("fvp-condition-load-source-3-NOMINAL").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-condition-load-source-3-NOMINAL").assertIsSelected()
        compose.onNodeWithTag("fvp-condition-load-source-3-WEIGHED").assertIsNotSelected()
        compose.waitForIdle()
        // Evidencia opt-in de los chips «Added load provenance»: WEIGHED declarado en la condicion 1
        // y NOMINAL en la 3, con las otras dos sin declarar (se vuelve a la 1 para capturarla).
        openLoad(1)
        captureScrollToTag("fvp-condition-load-source-1-WEIGHED", "conditions-load-source-weighed-selected")

        val draft = requireNotNull(FvpDraftSession.draft.value)
        assertEquals("UNKNOWN", draft.conditions[0].addedLoadSource)
        assertEquals("WEIGHED", draft.conditions[1].addedLoadSource)
        assertEquals("UNKNOWN", draft.conditions[2].addedLoadSource)
        assertEquals("NOMINAL", draft.conditions[3].addedLoadSource)

        val conditions = saveAndReadConditions(draft)
        assertEquals("UNKNOWN", conditions[0].addedLoadSource)
        assertEquals("WEIGHED", conditions[1].addedLoadSource)
        assertEquals("UNKNOWN", conditions[2].addedLoadSource)
        assertEquals("NOMINAL", conditions[3].addedLoadSource)
        assertTrue(conditions.all { it.addedLoadMeasuredAt == null })
    }

    /**
     * Confirmar la reutilizacion de una medicion guardada deja la constancia DURABLE (fecha real +
     * version `fvp-reuse-1`) junto a los campos de origen que la fuente aporta, y NO inventa los que
     * no aporta (mapeo temporal y PTS siguen desconocidos).
     */
    @Test
    fun confirmedReusePersistsDurableConfirmationAndSourceSnapshot() {
        val sourceAssessmentId = seedReusableMeasurement(
            sessionKey = "fvp-condition-provenance-source",
            heightCm = 34.0,
            detectedFps = 240,
        )
        FvpDraftSession.adopt(physicalDraft())
        setConditionsContent()

        openReuseSheetForFirstAttempt(sourceAssessmentId)
        compose.onNodeWithTag("fvp-reuse-confirm-$sourceAssessmentId-0").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-reuse-apply-$sourceAssessmentId-0").performScrollTo().performClick()
        compose.waitForIdle()

        val draft = requireNotNull(FvpDraftSession.draft.value)
        val attempt = draft.conditions[0].attempts[0]
        assertNotNull("aplicar la medicion confirmada debe fechar la declaracion", attempt.reuseConfirmedAt)
        assertTrue("la fecha es un instante real (epoch ms)", attempt.reuseConfirmedAt!! > 0L)
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, attempt.reuseConfirmationVersion)
        assertEquals(sourceAssessmentId, attempt.sourceAssessmentId)
        assertEquals(0, attempt.sourceAttemptOrdinal)
        assertEquals("SJ", attempt.sourceProtocolId)
        assertEquals(240.0, attempt.sourceFps!!, 0.0)
        assertEquals(FvpMeasureMethod.FLIGHT_TIME_VIDEO, attempt.method)
        assertEquals(0.34, attempt.heightM!!, 0.0)
        // Lo que la fuente NO aporta queda desconocido: ni mapeo temporal, ni PTS, ni fechas.
        assertEquals("UNKNOWN", attempt.timeMapping)
        assertNull(attempt.timeMappingEvidenceKey)
        assertNull(attempt.takeoffPtsUs)
        assertNull(attempt.landingPtsUs)
        assertNull(attempt.sourceRecordedAt)
        assertNull(attempt.acquiredAt)
        // La pantalla DECLARA la confirmacion durable en el propio intento.
        compose.onNodeWithTag("fvp-reuse-confirmed-0-0").performScrollTo().assertExists()
        // Evidencia opt-in del estado con la confirmacion de reutilizacion visible.
        captureScrollToTag("fvp-reuse-confirmed-0-0", "conditions-reuse-confirmed")

        val attempts = saveAndReadAttempts(draft)
        val saved = attempts.first { it.ordinal == 0 }
        assertEquals(attempt.reuseConfirmedAt, saved.reuseConfirmedAt)
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, saved.reuseConfirmationVersion)
        assertEquals(sourceAssessmentId, saved.sourceAssessmentId)
        assertEquals("UNKNOWN", saved.timeMapping)
        assertNull(saved.takeoffPtsUs)
        assertNull(saved.landingPtsUs)
    }

    /**
     * Invalidacion SINCRONA: cambiar la carga de la condicion confirmada retira la confirmacion en la
     * MISMA mutacion del borrador (sin efecto de UI posterior) y la serie guardada despues ya no la
     * lleva, mientras los snapshots de origen se conservan.
     */
    @Test
    fun changingTheConfirmedConditionLoadClearsTheConfirmationInDraftAndOnSave() {
        val sourceAssessmentId = seedReusableMeasurement(
            sessionKey = "fvp-condition-provenance-invalidation",
            heightCm = 34.0,
            detectedFps = 240,
        )
        FvpDraftSession.adopt(physicalDraft())
        setConditionsContent()

        openReuseSheetForFirstAttempt(sourceAssessmentId)
        compose.onNodeWithTag("fvp-reuse-confirm-$sourceAssessmentId-0").performScrollTo().performClick()
        compose.onNodeWithTag("fvp-reuse-apply-$sourceAssessmentId-0").performScrollTo().performClick()
        compose.waitForIdle()
        assertNotNull(
            "el estado de partida necesita la confirmacion aplicada",
            requireNotNull(FvpDraftSession.draft.value).conditions[0].attempts[0].reuseConfirmedAt,
        )

        // Cambiar la carga de ESA condicion (0 kg -> 5 kg): la declaracion deja de valer.
        compose.onNodeWithTag("fvp-condition-load-0").performScrollTo().performTextClearance()
        compose.onNodeWithTag("fvp-condition-load-0").performTextInput(localeDecimal("5"))
        compose.waitForIdle()

        val mutated = requireNotNull(FvpDraftSession.draft.value)
        assertEquals(5.0, mutated.conditions[0].addedLoadKg!!, 0.0)
        assertNull(
            "cambiar la carga confirmada retira la confirmacion en la misma mutacion",
            mutated.conditions[0].attempts[0].reuseConfirmedAt,
        )
        assertNull(mutated.conditions[0].attempts[0].reuseConfirmationVersion)
        assertEquals(
            "el snapshot de origen sobrevive a la invalidacion",
            sourceAssessmentId,
            mutated.conditions[0].attempts[0].sourceAssessmentId,
        )
        // La pantalla deja de declararla.
        assertEquals(
            0,
            compose.onAllNodesWithTag("fvp-reuse-confirmed-0-0").fetchSemanticsNodes().size,
        )
        // Y la carga declarada por el usuario sigue siendo suya (ninguna procedencia inventada).
        assertEquals("UNKNOWN", mutated.conditions[0].addedLoadSource)

        val attempts = saveAndReadAttempts(mutated)
        val saved = attempts.first { it.ordinal == 0 }
        assertNull("la fila guardada no puede llevar una confirmacion retirada", saved.reuseConfirmedAt)
        assertNull(saved.reuseConfirmationVersion)
        assertEquals(sourceAssessmentId, saved.sourceAssessmentId)
    }

    // ------------------------------------------------------------------------------------------
    // Montaje y utilidades

    /** Pantalla REAL de condiciones sin dueno del flujo: las mutaciones van a `FvpDraftSession`. */
    private fun setConditionsContent() {
        compose.setContent {
            FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
        }
    }

    /**
     * Abre la carga `index` desde su fila resumen (un UNICO editor activo por vez): la fila se
     * desplaza primero, porque la lista puede ser mas alta que la ventana.
     */
    private fun openLoad(index: Int) {
        compose.onNodeWithTag("fvp-load-row-$index").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Abre el selector de saltos guardados del PRIMER intento de la primera condicion. */
    private fun openReuseSheetForFirstAttempt(sourceAssessmentId: Long) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(context.getString(R.string.fvp_reuse_measurement))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(context.getString(R.string.fvp_reuse_measurement))
            .onFirst()
            .performScrollTo()
            .performClick()
        // La hoja abre y las filas se cargan de forma asincrona (Room + ViewModel): esperar la
        // apertura y luego la fila concreta con margen amplio (el AVD puede ir lento bajo carga).
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("fvp-reuse-confirmation-hint").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("fvp-reuse-confirm-$sourceAssessmentId-0")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Serie calculable y persistible (4 condiciones, cargas y alturas distintas) con procedencia de
     * la carga SIN declarar: la pantalla no la cambia por su cuenta.
     */
    private fun physicalDraft(): FvpDraft = FvpDraftSession.begin(athleteId, FvpProtocol.SJ).copy(
        bodyMassKg = 80.0,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.40,
        pushOffLandmark = "TROCANTER",
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = FVP_MEASURED_AT,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.40, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.32, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "40kg",
                addedLoadKg = 40.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.25, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "60kg",
                addedLoadKg = 60.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.18, method = FvpMeasureMethod.FLIGHT_TIME_VIDEO, sourceFps = 240.0),
                ),
            ),
        ),
    )

    /**
     * Siembra una medicion guardada reutilizable (assessment + intento + metrica `HEIGHT_CM`, SIN
     * `FLIGHT_TIME_MS`) y devuelve el id del assessment sembrado: es la MISMA fuente que alimenta el
     * selector de la pantalla, sin campos de procedencia F-V-P.
     */
    private fun seedReusableMeasurement(
        sessionKey: String,
        heightCm: Double,
        detectedFps: Int,
    ): Long = runBlocking {
        val id = database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = sessionKey,
                athleteId = athleteId,
                protocolId = "SJ",
                dateTime = 1_700_000_000_000L,
                primaryMetricKey = "HEIGHT_CM",
                primaryMetricValue = heightCm,
                primaryMetricUnit = "CENTIMETER",
            ),
        )
        val attemptId = database.jumpDao().insertAttempt(
            AttemptEntity(
                assessmentId = id,
                side = null,
                source = "IMPORTED",
                videoUri = null,
                detectedFps = detectedFps,
                dropHeightCm = null,
                distanceCm = null,
            ),
        )
        database.jumpDao().insertMetrics(
            listOf(AttemptMetricEntity(attemptId, "HEIGHT_CM", 0, heightCm, "CENTIMETER")),
        )
        id
    }

    /** Persiste el borrador REAL por el camino de produccion y devuelve las condiciones leidas. */
    private fun saveAndReadConditions(draft: FvpDraft): List<FvpConditionEntity> {
        val id = saveDraft(draft)
        val stored = requireNotNull(runBlocking { repository.byId(id) }) { "la serie debe quedar persistida" }
        return stored.conditions.sortedBy { it.ordinal }
    }

    /** Igual que [saveAndReadConditions], pero devuelve los intentos persistidos. */
    private fun saveAndReadAttempts(draft: FvpDraft): List<FvpAttemptEntity> {
        val id = saveDraft(draft)
        val stored = requireNotNull(runBlocking { repository.byId(id) }) { "la serie debe quedar persistida" }
        return stored.attempts.sortedWith(compareBy({ it.conditionId }, { it.ordinal }))
    }

    private fun saveDraft(draft: FvpDraft): Long {
        val persistable = requireNotNull(draft.toPersistDraftOrNull()) {
            "una serie calculable de la pantalla de condiciones debe ser persistible"
        }
        return runBlocking { repository.save(persistable) }
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

    private companion object {
        /** Fecha de medicion fija (UTC) del h_po declarado de las series de prueba. */
        const val FVP_MEASURED_AT = 1_700_000_000_000L
    }
}
