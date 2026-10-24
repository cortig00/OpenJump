package com.openjump.app.ui.fvp

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.FvpRepository
import com.openjump.app.settings.MassUnit
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seam de UNIDADES de los FORMULARIOS F-V-P (plan 13, D1/U1/U2) sobre Room EN MEMORIA: se montan las
 * pantallas REALES bajo un perfil de unidades MUTABLE (`LocalUnitSystem`) y se mueven los campos
 * entre metrico, EE. UU. y un perfil PERSONALIZADO (kg + pulgadas) sin salir de la composicion.
 *
 * Lo que cubre y lo que NO:
 * - cubre la forma VISIBLE (rotulo + unidad del perfil + valor formateado exacto) y el canonico
 *   CONFIRMADO tras la accion CONTINUAR real del paso 1 y tras cada edicion del paso 2;
 * - NO es un end-to-end de `MainActivity`/Ajustes ni una validacion fisica: no toca preferencias,
 *   ni Room en disco, ni el ajuste global de unidades (el perfil se aporta por `LocalUnitSystem`);
 * - los textos decimales esperados se construyen con el separador del locale del AVD
 *   ([localeDecimal]) pero con cifras FIJAS calculadas a mano (kg->lb = 0,45359237; cm->in = 2,54),
 *   de modo que la MISMA API bajo prueba no es el unico oraculo.
 */
@RunWith(AndroidJUnit4::class)
class FvpFormUnitsInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: JumpDatabase
    private lateinit var viewModel: FvpViewModel
    private var athleteId: Long = 0L

    /**
     * Perfil de unidades VIVO de la composicion: cambiarlo desde el hilo de UI reproduce el cambio
     * de Ajustes -> Vuelta al flujo sin desmontar la pantalla (regla U2), que es justo la transicion
     * que estas pruebas vigilan.
     */
    private val profile = mutableStateOf(UnitProfile.METRIC)

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Pestillo de montaje: permite DESMONTAR la pantalla en `tearDown` sin cerrar el `ComposeRule`.
     * La base de datos vive en memoria y no se puede cerrar con la composicion aun viva.
     */
    private val contentMounted = mutableStateOf(false)
    private var restorationTester: StateRestorationTester? = null

    @Before
    fun setUp() {
        FvpDraftSession.reset()
        // La introduccion de primer uso ya esta vista: estas pruebas miden unidades/rebases, no la
        // presentacion inicial. El paso 1 tiene PAGINAS (INTRO/DATA/MEDICION): la masa se comprueba
        // en DATA y h_po/componentes/metadatos en MEDICION, navegando con `fvp-setup-continue-data`
        // y con la flecha `common_back` (nunca se asume que ambas paginas esten compuestas a la vez).
        markFvpIntroductionSeen()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        viewModel = FvpViewModel(FvpRepository(database.fvpDao()), JumpRepository(database.jumpDao()))
        athleteId = runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = "FVP FORM", weightKg = 80.0, createdAt = 1L, updatedAt = 1L),
            )
        }
    }

    @After
    fun tearDown() {
        // Desmonta la pantalla (y sus colecciones) ANTES de resetear la sesion y cerrar Room: la
        // base es EN MEMORIA y no debe quedar una composicion viva usandola.
        compose.runOnUiThread { contentMounted.value = false }
        compose.waitForIdle()
        // Aislamiento: sesion en memoria limpiada y base (EN MEMORIA) cerrada; nada persistido.
        FvpDraftSession.reset()
        database.close()
    }

    // ------------------------------------------------------------------------------------------
    // 1) Ida y vuelta metrica/EE. UU./personalizada con el canonico EXACTO de la semilla
    // ------------------------------------------------------------------------------------------

    /**
     * Semilla de ALTA PRECISION y en el LIMITE del dominio (h_po = 0,80 m, extremo superior de
     * `(0,05, 0,80] m`). El texto del campo se re-siembra en cada cambio de perfil y, SIN editarse,
     * debe resolver el canonico EXACTO de la semilla (U1): en EE. UU. 0,80 m se ve "31,4961" in y el
     * re-parseo de esa forma redondeada daria 0,80000094 m, FUERA del dominio (bloquearia CONTINUAR
     * y pintaria error). El recorrido metrico -> EE. UU. -> kg/in personalizado -> metrico debe
     * confirmar SIEMPRE 78,123456 kg / 0,8 m / componentes intactos.
     */
    @Test
    fun seededSetupKeepsExactCanonicalsAcrossMetricUsCustomAndBackTransitions() {
        FvpDraftSession.adopt(seededDraft())
        setSetupContent()
        // La masa vive en DATA y h_po/componentes/hito/procedimiento/fecha en MEDICION: el ida y
        // vuelta de paginas forma parte del contrato (cambiar de pagina NO confirma nada).
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()

        // 1) Metrico (kg/cm): masa en DATA; h_po y componentes en MEDICION.
        assertFieldShows("fvp-setup-mass", R.string.fvp_body_mass, "kg", localeDecimal("78.1235"))
        goToMeasurement()
        ensureOptionalDetailsExpanded()
        assertFieldShows("fvp-setup-pushoff", R.string.fvp_push_off, "cm", localeDecimal("80"))
        assertUnitLabel(R.string.fvp_push_off_crouch_cm, "cm")
        assertUnitLabel(R.string.fvp_push_off_extended_cm, "cm")
        assertPushOffWithinDomain()
        // MEDICION -> DATA con la flecha de la barra (misma accion que el gesto del sistema): el
        // texto de la masa no se pierde ni se re-basa al cambiar de pagina.
        backToData()
        assertFieldShows("fvp-setup-mass", R.string.fvp_body_mass, "kg", localeDecimal("78.1235"))
        goToMeasurement()
        assertSeededCanonicals(continueAndReadDraft("metrico"), "metrico")

        // 2) EE. UU. (lb/in): forma visible = conversion conocida, canonico confirmado intacto.
        switchProfile(UnitProfile.UNITED_STATES)
        assertFieldShows("fvp-setup-pushoff", R.string.fvp_push_off, "in", localeDecimal("31.4961"))
        assertUnitLabel(R.string.fvp_push_off_crouch_cm, "in")
        assertUnitLabel(R.string.fvp_push_off_extended_cm, "in")
        assertPushOffWithinDomain()
        // Evidencia opt-in: semilla de alta precision re-basada en EE. UU.: h_po en pulgadas, en el
        // LIMITE SUPERIOR del dominio (0,80 m = 31,4961 in) y sin marcarse fuera de rango.
        captureScrollToTag("fvp-setup-pushoff", "setup-units-seeded-us-pushoff-in")
        backToData()
        assertFieldShows("fvp-setup-mass", R.string.fvp_body_mass, "lb", localeDecimal("172.2327"))
        goToMeasurement()
        assertSeededCanonicals(continueAndReadDraft("EE. UU."), "EE. UU.")

        // 3) Perfil PERSONALIZADO: masa en kg (unidad METRICA) y longitud corta en pulgadas.
        switchProfile(UnitProfile(mass = MassUnit.KILOGRAM, shortLength = ShortLengthUnit.INCH))
        assertFieldShows("fvp-setup-pushoff", R.string.fvp_push_off, "in", localeDecimal("31.4961"))
        assertPushOffWithinDomain()
        backToData()
        assertFieldShows("fvp-setup-mass", R.string.fvp_body_mass, "kg", localeDecimal("78.1235"))
        // Evidencia opt-in: perfil PERSONALIZADO kg/in: la masa sigue rotulada en kg (no lb) con la
        // semilla de alta precision, mientras la longitud corta va en pulgadas.
        captureScrollToTag("fvp-setup-mass", "setup-units-seeded-custom-kg-in")
        goToMeasurement()
        assertSeededCanonicals(continueAndReadDraft("kg/in"), "kg/in")

        // 4) Vuelta a metrico tras CUATRO re-siembras del mismo borrador.
        switchProfile(UnitProfile.METRIC)
        assertFieldShows("fvp-setup-pushoff", R.string.fvp_push_off, "cm", localeDecimal("80"))
        assertPushOffWithinDomain()
        backToData()
        assertFieldShows("fvp-setup-mass", R.string.fvp_body_mass, "kg", localeDecimal("78.1235"))
        goToMeasurement()
        assertSeededCanonicals(continueAndReadDraft("metrico (vuelta)"), "metrico (vuelta)")
    }

    // ------------------------------------------------------------------------------------------
    // 2) Edicion AUN NO CONFIRMADA + transiciones de perfil (regresion U2/D1)
    // ------------------------------------------------------------------------------------------

    /**
     * REGRESION (U2/D1, plan 13): "no basta anadir locale a una key si eso borra texto". Con la
     * semilla 80 kg / 0,40 m se teclea 82,5 kg (en DATA), 45 cm y los componentes 35/57 cm (en
     * MEDICION) y, SIN pulsar CONTINUAR y sin salir del paso 1, se recorren los perfiles
     * EE. UU. -> kg/in -> metrico.
     *
     * Contrato: el texto tecleado NO se pierde, se re-basa con su canonico exacto y lo CONFIRMADO al
     * CONTINUAR es 82,5 kg / 0,45 m / 35 cm / 57 cm: ni la semilla 80/0,40 re-sembrada desde el
     * borrador, ni una reinterpretacion de la forma visible en libras. Teclear la masa la declara
     * MANUAL, asi que la procedencia `bodyMassFromProfile` no puede seguir reclamando el perfil.
     */
    @Test
    fun uncommittedSetupEditsSurviveUnitProfileChangeBeforeContinue() {
        FvpDraftSession.adopt(seededDraft().copy(bodyMassKg = 80.0, commonPushOffM = 0.4))
        setSetupContent()
        // Masa en DATA.
        assertFieldShows("fvp-setup-mass", R.string.fvp_body_mass, "kg", localeDecimal("80"))
        // h_po y componentes en MEDICION: se navega con el pie de DATA (sin confirmar nada) y los
        // campos se editan en su pagina real.
        goToMeasurement()
        ensureOptionalDetailsExpanded()
        assertFieldShows("fvp-setup-pushoff", R.string.fvp_push_off, "cm", localeDecimal("40"))
        assertEquals(
            "componente de flexion sembrado en cm",
            localeDecimal("34.4567"),
            editableText("fvp-setup-crouch"),
        )
        assertEquals(
            "componente de extension sembrado en cm",
            localeDecimal("56.7891"),
            editableText("fvp-setup-extended"),
        )

        // Edicion tecleada y NO confirmada: el paso 1 solo vuelca el borrador al pulsar CONTINUAR.
        typeInto("fvp-setup-pushoff", localeDecimal("45"))
        typeInto("fvp-setup-crouch", localeDecimal("35"))
        typeInto("fvp-setup-extended", localeDecimal("57"))
        // MEDICION -> DATA (flecha de la barra): la masa editada lo es en SU pagina.
        backToData()
        typeInto("fvp-setup-mass", localeDecimal("82.5"))
        val beforeSwitch = FvpDraftSession.draft.value
        assertNotNull("el borrador sembrado sigue vivo antes de CONTINUAR", beforeSwitch)
        assertEquals(
            "sin CONTINUAR el borrador sigue siendo la semilla (no hay volcado implicito)",
            80.0,
            beforeSwitch!!.bodyMassKg!!,
            0.0,
        )
        assertEquals(
            "sin CONTINUAR el borrador sigue siendo la semilla (no hay volcado implicito)",
            0.4,
            beforeSwitch.commonPushOffM!!,
            0.0,
        )
        assertEquals(
            "sin CONTINUAR los componentes siguen siendo la semilla",
            34.4567,
            beforeSwitch.pushOffCrouchCm!!,
            0.0,
        )

        // 1) EE. UU. sin salir del paso 1: la masa se re-basa en DATA y h_po/componentes en MEDICION.
        switchProfile(UnitProfile.UNITED_STATES)
        assertMassRebased("EE. UU.", mass = "181.8814")
        // Evidencia opt-in: edicion aun NO confirmada re-basada al perfil EE. UU. (masa en lb),
        // capturada ANTES de pulsar CONTINUAR.
        captureScrollToTag("fvp-setup-mass", "setup-units-edits-rebased-us-before-continue")
        goToMeasurement()
        assertPushOffAndComponentsRebased(
            "EE. UU.",
            pushOff = "17.7165",
            crouch = "13.7795",
            extended = "22.4409",
        )
        assertPushOffWithinDomain()

        // 2) Perfil personalizado kg/in y vuelta a metrico: la edicion sigue exacta.
        switchProfile(UnitProfile(mass = MassUnit.KILOGRAM, shortLength = ShortLengthUnit.INCH))
        assertPushOffAndComponentsRebased(
            "kg/in",
            pushOff = "17.7165",
            crouch = "13.7795",
            extended = "22.4409",
        )
        backToData()
        assertMassRebased("kg/in", mass = "82.5")
        goToMeasurement()
        switchProfile(UnitProfile.METRIC)
        assertPushOffAndComponentsRebased(
            "metrico (vuelta)",
            pushOff = "45",
            crouch = "35",
            extended = "57",
        )
        assertPushOffWithinDomain()
        backToData()
        assertMassRebased("metrico (vuelta)", mass = "82.5")
        goToMeasurement()

        val committed = continueAndReadDraft("metrico tras edicion sin confirmar")
        assertEquals(
            "U2/D1: cambiar el perfil de unidades no puede descartar el texto tecleado y aun no " +
                "confirmado. Lo confirmado al CONTINUAR debe ser 82,5 kg (no la semilla 80 kg " +
                "re-sembrada desde el borrador ni una reinterpretacion)",
            82.5,
            committed.bodyMassKg!!,
            0.0,
        )
        assertEquals(
            "U2/D1: h_po tecleado y aun no confirmado (45 cm = 0,45 m) no puede volver a la semilla " +
                "0,40 m (ni reinterpretarse) al cambiar de perfil",
            0.45,
            committed.commonPushOffM!!,
            0.0,
        )
        assertEquals(
            "U2/D1: componente de flexion editado y no confirmado (35 cm): no puede volver a la " +
                "semilla 34,4567 cm al cambiar de perfil",
            35.0,
            committed.pushOffCrouchCm!!,
            0.0,
        )
        assertEquals(
            "U2/D1: componente de extension editado y no confirmado (57 cm): no puede volver a la " +
                "semilla 56,7891 cm al cambiar de perfil",
            57.0,
            committed.pushOffExtendedCm!!,
            0.0,
        )
        assertFalse(
            "teclear la masa la declara MANUAL: no puede seguir reclamando el peso del perfil",
            committed.bodyMassFromProfile,
        )
        // Evidencia opt-in: el mismo formulario DESPUES de CONTINUAR (vuelta a metrico): h_po ya es
        // el canonico confirmado (45 cm) de la edicion tecleada sin confirmar.
        captureScrollToTag("fvp-setup-pushoff", "setup-units-edits-committed-metric-after-continue")
    }

    /**
     * REGRESION U2/D1 (texto PARCIAL): un valor a medio teclear no puede convertirse en vacio, cero
     * ni en la semilla al cambiar de unidades. Se conserva, se enciende el aviso de revision
     * (`unit_input_review_required`) y CONTINUAR queda bloqueado hasta una edicion explicita bajo las
     * unidades actuales (aqui, 180 lb).
     */
    @Test
    fun partialSetupEditsLatchReviewUntilExplicitEditUnderCurrentUnits() {
        FvpDraftSession.adopt(seededDraft().copy(bodyMassKg = 80.0, commonPushOffM = 0.4))
        setSetupContent()
        val partialMass = localeDecimal("82.5").dropLast(1)
        typeInto("fvp-setup-mass", partialMass)
        assertEquals("el valor a medio teclear queda en el campo", partialMass, editableText("fvp-setup-mass"))
        // El latch no es cosmetico: con la masa en revision ni siquiera se pasa a MEDICION (el gate
        // de DATA se apaga; el commit final exige las dos paginas, asi que tambien queda bloqueado).
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsNotEnabled()

        switchProfile(UnitProfile.UNITED_STATES)
        assertEquals(
            "el texto parcial no se borra, no se convierte en cero ni vuelve a la semilla al cambiar " +
                "de unidades",
            partialMass,
            editableText("fvp-setup-mass"),
        )
        assertTrue(
            "el campo en revision muestra el aviso existente de unidades cambiadas",
            compose.onAllNodesWithTag("fvp-setup-mass-review").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "un texto parcial no se pinta como error de valor: es revision, no reinterpretacion",
            compose.onAllNodesWithTag("fvp-setup-mass-error").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsNotEnabled()
        // Evidencia opt-in: texto parcial CONSERVADO con el aviso de revision visible bajo EE. UU.
        captureScrollToTag("fvp-setup-mass-review", "setup-units-partial-edit-review-us")

        // Edicion EXPLICITA bajo las unidades actuales: apaga el aviso y permite pasar a MEDICION.
        typeInto("fvp-setup-mass", localeDecimal("180"))
        assertTrue(
            "el aviso de revision desaparece tras la edicion explicita",
            compose.onAllNodesWithTag("fvp-setup-mass-review").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled()
        goToMeasurement()
        // El h_po intacto SI se re-basa exacto (0,4 m = 15,748 in): el bloqueo era solo del parcial
        // (mientras estuvo encendido no se pudo ni abrir MEDICION; aqui ya se comprueba su forma).
        assertFieldShows("fvp-setup-pushoff", R.string.fvp_push_off, "in", localeDecimal("15.748"))
        assertPushOffWithinDomain()
        // Ida y vuelta de paginas: la edicion explicita (180 lb) tampoco se pierde al volver a DATA.
        backToData()
        assertFieldShows("fvp-setup-mass", R.string.fvp_body_mass, "lb", localeDecimal("180"))
        goToMeasurement()
        val committed = continueAndReadDraft("EE. UU. tras edicion explicita")
        assertEquals(
            "la edicion explicita se interpreta con las unidades ACTUALES (180 lb)",
            180.0 * 0.45359237,
            committed.bodyMassKg!!,
            0.0,
        )
        assertEquals("el h_po intacto se confirma exacto", 0.4, committed.commonPushOffM!!, 0.0)
    }

    /** Exercises the actual rememberSaveable Saver, not process death or an Activity recreation. */
    @Test
    fun saveableRestoreKeepsPartialReviewAndEditedCanonicalAnchor() {
        val tester = StateRestorationTester(compose)
        restorationTester = tester
        FvpDraftSession.adopt(seededDraft().copy(bodyMassKg = 80.0, commonPushOffM = 0.4))
        setSetupContent()
        // El h_po editado se teclea en SU pagina (MEDICION) y la masa en DATA: al restaurar la
        // instancia el estado de AMBAS paginas debe volver del `Saver`.
        goToMeasurement()
        typeInto("fvp-setup-pushoff", localeDecimal("45"))
        backToData()
        val partialMass = localeDecimal("82.5").dropLast(1)
        typeInto("fvp-setup-mass", partialMass)
        switchProfile(UnitProfile.UNITED_STATES)
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        // La pagina guardada se restaura tambien: se vuelve en DATA, no en el inicio del paso.
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
        assertEquals(partialMass, editableText("fvp-setup-mass"))
        compose.onNodeWithTag("fvp-setup-mass-review").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsNotEnabled()
        // The live draft still contains the old seed; the restored edit must come from the Saver.
        assertEquals(0.4, FvpDraftSession.draft.value!!.commonPushOffM!!, 0.0)
        // Edicion explicita que cierra el latch y deja pasar a MEDICION: alli el h_po restaurado
        // sigue siendo el tecleado (45 cm = 17,7165 in), no la semilla 0,40 m.
        typeInto("fvp-setup-mass", "180")
        goToMeasurement()
        assertFieldShows("fvp-setup-pushoff", R.string.fvp_push_off, "in", localeDecimal("17.7165"))
        val committed = continueAndReadDraft("after saveable restoration")
        assertEquals(180.0 * 0.45359237, committed.bodyMassKg!!, 0.0)
        assertEquals(0.45, committed.commonPushOffM!!, 0.0)
    }

    /**
     * Forma VISIBLE de la masa editada/re-basada en su pagina (DATA), con el aviso de revision
     * apagado. La masa NO se compone en MEDICION: su oraculo se comprueba aqui, no alli.
     */
    private fun assertMassRebased(phase: String, mass: String) {
        assertEquals("masa editada re-basada ($phase)", localeDecimal(mass), editableText("fvp-setup-mass"))
        assertTrue(
            "una edicion valida no queda en revision ($phase)",
            compose.onAllNodesWithTag("fvp-setup-mass-review").fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * Forma VISIBLE de h_po y componentes editados en MEDICION (cifras fijas en SU unidad). La masa
     * se comprueba con [assertMassRebased] en DATA.
     */
    private fun assertPushOffAndComponentsRebased(
        phase: String,
        pushOff: String,
        crouch: String,
        extended: String,
    ) {
        assertEquals(
            "h_po editado re-basado ($phase)",
            localeDecimal(pushOff),
            editableText("fvp-setup-pushoff"),
        )
        assertEquals(
            "flexion editada re-basada ($phase)",
            localeDecimal(crouch),
            editableText("fvp-setup-crouch"),
        )
        assertEquals(
            "extension editada re-basada ($phase)",
            localeDecimal(extended),
            editableText("fvp-setup-extended"),
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3) Paso 2 (condiciones): carga/altura reales que si sobreviven a las transiciones
    // ------------------------------------------------------------------------------------------

    /**
     * En el paso 2 cada edicion escribe en el borrador AL MOMENTO, asi que `rememberFvpQuantityText`
     * re-siembra texto y semilla desde el canonico del borrador en cada cambio de perfil: la carga
     * (82,5 kg) y la altura del intento (45 cm = 0,45 m) deben seguir EXACTAS y su forma visible debe
     * ser la conversion conocida en lb/in y en un perfil personalizado kg/in. Los controles se
     * seleccionan por TAG (originales de la pantalla) y se comprueba su ancestro de condicion.
     */
    @Test
    fun conditionLoadAndHeightEditsStayExactAcrossUnitProfiles() {
        FvpDraftSession.adopt(
            seededDraft().copy(
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
                ),
            ),
        )
        setConditionsContent()
        // Controles ORIGINALES del paso 2 dentro de la condicion 0 (nada de coincidencias amplias).
        compose.onNodeWithTag("fvp-condition-load-0").assert(hasAnyAncestor(hasTestTag("fvp-condition-0")))
        compose.onNodeWithTag("fvp-attempt-height-0").assert(hasAnyAncestor(hasTestTag("fvp-condition-0")))
        assertFieldShows("fvp-condition-load-0", R.string.fvp_added_load_kg, "kg", localeDecimal("0"))
        assertFieldShows("fvp-attempt-height-0", R.string.fvp_height_cm, "cm", localeDecimal("40"))

        typeInto("fvp-condition-load-0", localeDecimal("82.5"))
        typeInto("fvp-attempt-height-0", localeDecimal("45"))
        assertConditionCanonicals("metrico")

        switchProfile(UnitProfile.UNITED_STATES)
        assertFieldShows("fvp-condition-load-0", R.string.fvp_added_load_kg, "lb", localeDecimal("181.8814"))
        assertFieldShows("fvp-attempt-height-0", R.string.fvp_height_cm, "in", localeDecimal("17.7165"))
        assertConditionCanonicals("EE. UU.")
        // Evidencia opt-in: paso 2 bajo EE. UU.: la carga editada (82,5 kg exactos) se ve como
        // 181,8814 lb con su fila de procedencia declarada debajo.
        captureScrollToTag("fvp-condition-load-0", "conditions-units-load-exact-us")

        switchProfile(UnitProfile(mass = MassUnit.KILOGRAM, shortLength = ShortLengthUnit.INCH))
        assertFieldShows("fvp-condition-load-0", R.string.fvp_added_load_kg, "kg", localeDecimal("82.5"))
        assertFieldShows("fvp-attempt-height-0", R.string.fvp_height_cm, "in", localeDecimal("17.7165"))
        assertConditionCanonicals("kg/in")

        switchProfile(UnitProfile.METRIC)
        assertFieldShows("fvp-condition-load-0", R.string.fvp_added_load_kg, "kg", localeDecimal("82.5"))
        assertFieldShows("fvp-attempt-height-0", R.string.fvp_height_cm, "cm", localeDecimal("45"))
        assertConditionCanonicals("metrico (vuelta)")
    }

    // ------------------------------------------------------------------------------------------
    // Montaje
    // ------------------------------------------------------------------------------------------

    /** Paso 1 REAL con el perfil de unidades mutable (sin dueno: `FvpDraftSession` recibe el volcado). */
    private fun setSetupContent() = mount {
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

    /** Paso 2 REAL con el fixture en memoria y la sesion como dueno del borrador. */
    private fun setConditionsContent() = mount {
        FvpConditionsScreen(onBack = {}, onCalculate = {}, viewModel = viewModel)
    }

    /**
     * Monta la pantalla bajo el perfil VIVO y deja un pestillo para desmontarla en `tearDown`: el
     * perfil se cambia con `runOnUiThread` y el contenido se retira antes de cerrar Room.
     */
    private fun mount(content: @Composable () -> Unit) {
        compose.runOnUiThread { contentMounted.value = true }
        val root: @Composable () -> Unit = {
            CompositionLocalProvider(LocalUnitSystem provides profile.value) {
                if (contentMounted.value) content()
            }
        }
        val tester = restorationTester
        if (tester == null) compose.setContent(root) else tester.setContent(root)
    }

    private fun sampleAthlete(): AthleteEntity = AthleteEntity(
        id = athleteId,
        displayName = "FVP FORM",
        weightKg = 80.0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    /** Borrador sembrado con valores de alta precision y en el limite superior de h_po. */
    private fun seededDraft(): FvpDraft = FvpDraft(
        athleteId = athleteId,
        protocol = FvpProtocol.SJ,
        bodyMassKg = SEED_MASS_KG,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = SEED_PUSH_OFF_M,
        pushOffLandmark = LANDMARK,
        pushOffProtocol = PROCEDURE,
        pushOffMeasuredAt = FVP_MEASURED_AT,
        pushOffCrouchCm = SEED_CROUCH_CM,
        pushOffExtendedCm = SEED_EXTENDED_CM,
    )

    /** Cambio de perfil de unidades SIN desmontar la pantalla (como al volver de Ajustes). */
    private fun switchProfile(next: UnitProfile) {
        compose.runOnUiThread { profile.value = next }
        compose.waitForIdle()
    }

    /**
     * "Detalles de la medicion (opcional)": los componentes de h_po solo existen ahi y arrancan
     * CONTRADOS. Idempotente: si ya estan desplegados no vuelve a pulsar el plegable (lo cerraria).
     */
    private fun ensureOptionalDetailsExpanded() {
        if (compose.onAllNodesWithTag("fvp-setup-crouch").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithTag("fvp-setup-details-toggle").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /**
     * DATA -> MEDICION con la accion REAL del pie de DATA. El boton vive en el `bottomBar` FIJO (no
     * hay scroll que lo alcance): se pulsa, nunca se le hace `performScrollTo`.
     */
    private fun goToMeasurement() {
        compose.onNodeWithTag("fvp-setup-continue-data").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
    }

    /** MEDICION -> DATA con la FLECHA de la barra superior (misma accion que el gesto del sistema). */
    private fun backToData() {
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fvp-page-data").assertIsDisplayed()
    }

    // ------------------------------------------------------------------------------------------
    // Aserciones
    // ------------------------------------------------------------------------------------------

    /**
     * Forma VISIBLE del campo: rotulo con la unidad del perfil (`"<etiqueta> (<unidad>)"`) y valor
     * tecleado EXACTO (cifra fija independiente del formateo bajo prueba).
     */
    private fun assertFieldShows(tag: String, labelRes: Int, unitSuffix: String, expectedValue: String) {
        val label = context.getString(labelRes)
        assertTrue(
            "el campo $tag debe ir rotulado '$label ($unitSuffix)' en este perfil",
            compose.onAllNodesWithText("$label ($unitSuffix)", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals(
            "el campo $tag debe ensenar la conversion conocida en ($unitSuffix)",
            expectedValue,
            editableText(tag),
        )
    }

    /** Rotulo con la unidad del perfil de un campo SIN tag (componentes de h_po). */
    private fun assertUnitLabel(labelRes: Int, unitSuffix: String) {
        val label = context.getString(labelRes)
        assertTrue(
            "el campo '$label' debe ir rotulado en ($unitSuffix) en este perfil",
            compose.onAllNodesWithText("$label ($unitSuffix)", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /** h_po 0,80 m no puede quedar fuera del dominio por re-parsear la forma visible redondeada. */
    private fun assertPushOffWithinDomain() {
        assertTrue(
            "el limite superior del dominio (0,80 m) no puede marcarse fuera de rango al cambiar de " +
                "perfil de unidades (re-parseo de la forma visible en vez de la semilla exacta)",
            compose.onAllNodesWithTag("fvp-setup-pushoff-error").fetchSemanticsNodes().isEmpty(),
        )
    }

    /** Pulsa CONTINUAR (accion REAL del paso 1, solo en MEDICION) y devuelve el borrador volcado. */
    private fun continueAndReadDraft(phase: String): FvpDraft {
        compose.onNodeWithTag("fvp-page-measurement").assertIsDisplayed()
        compose.onNodeWithTag("fvp-setup-continue").assertIsEnabled()
        compose.onNodeWithTag("fvp-setup-continue").performClick()
        compose.waitForIdle()
        val draft = FvpDraftSession.draft.value
        assertNotNull("CONTINUAR debe volcar el borrador ($phase)", draft)
        return draft!!
    }

    /** Canonico EXACTO de la semilla (sin tolerancia): masa, h_po, componentes y metadatos. */
    private fun assertSeededCanonicals(draft: FvpDraft, phase: String) {
        assertEquals("masa canonica intacta ($phase)", SEED_MASS_KG, draft.bodyMassKg!!, 0.0)
        assertEquals("h_po canonico intacto ($phase)", SEED_PUSH_OFF_M, draft.commonPushOffM!!, 0.0)
        assertEquals("componente de sentadilla intacto ($phase)", SEED_CROUCH_CM, draft.pushOffCrouchCm!!, 0.0)
        assertEquals(
            "componente de extension intacto ($phase)",
            SEED_EXTENDED_CM,
            draft.pushOffExtendedCm!!,
            0.0,
        )
        assertEquals("hito anatomico intacto ($phase)", LANDMARK, draft.pushOffLandmark)
        assertEquals("procedimiento de h_po intacto ($phase)", PROCEDURE, draft.pushOffProtocol)
        assertEquals("fecha de medicion intacta ($phase)", FVP_MEASURED_AT, draft.pushOffMeasuredAt)
    }

    /** Carga y altura del intento EXACTAS tras la transicion de perfil (`phase` para el diagnostico). */
    private fun assertConditionCanonicals(phase: String) {
        val draft = FvpDraftSession.draft.value
        assertNotNull("el borrador del paso 2 debe seguir vivo ($phase)", draft)
        val condition = draft!!.conditions.single()
        assertEquals("carga anadida exacta ($phase)", 82.5, condition.addedLoadKg!!, 0.0)
        val attempt = condition.attempts.single()
        assertEquals("altura del intento exacta ($phase)", 0.45, attempt.heightM!!, 0.0)
        assertTrue("el intento sigue declarado valido ($phase)", attempt.valid)
    }

    // ------------------------------------------------------------------------------------------
    // Interaccion y lectura
    // ------------------------------------------------------------------------------------------

    /**
     * Teclea un valor SUSTITUYENDO el texto previo. Sin desplazar: las acciones semanticas no exigen
     * que el nodo este dentro del viewport (el paso 2 usa una `Column` con `verticalScroll`).
     */
    private fun typeInto(tag: String, value: String) {
        compose.onNodeWithTag(tag).performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(value)
        compose.waitForIdle()
    }

    /** Valor tecleado de un campo de texto etiquetado. */
    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

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
        /** Fecha de medicion fija (UTC) de la serie sembrada. */
        const val FVP_MEASURED_AT = 1_700_000_000_000L
        const val LANDMARK = "TROCANTER"
        const val PROCEDURE = "LANDMARK_EXTENDED_MINUS_CROUCH"

        /**
         * Canonico de alta precision (no representable con los 4 decimales de la forma visible): si
         * el campo intacto se re-parsease, el valor confirmado derivaria.
         */
        const val SEED_MASS_KG = 78.123456

        /** Limite SUPERIOR del dominio de h_po `(0,05, 0,80] m`: el caso que delata el re-parseo. */
        const val SEED_PUSH_OFF_M = 0.80
        const val SEED_CROUCH_CM = 34.4567
        const val SEED_EXTENDED_CM = 56.7891
    }
}
