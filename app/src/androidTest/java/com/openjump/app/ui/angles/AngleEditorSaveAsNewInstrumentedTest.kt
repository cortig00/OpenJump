package com.openjump.app.ui.angles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerTransportMode
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas del botón accesible **«guardar como análisis nuevo»** de la pantalla real
 * del editor de ángulos.
 *
 * Componen [AngleEditorContent] (la misma parte visual que usa [AngleEditorScreen] en producción,
 * con las mismas acciones y el mismo estado de recuperación que el ViewModel publica) y fijan lo que
 * los tests JVM con fakes no pueden ver:
 *
 * - el botón solo existe cuando hay una recuperación pendiente (`Deleted`/`Unverified`/
 *   `Conflict`); en el estado normal no se ofrece ninguna copia y el guardado normal sigue siendo el
 *   único camino;
 * - su etiqueta visible y su descripción para TalkBack son las localizadas, no texto cableado;
 * - tocarlo emite **la copia** (`onSaveAsNew`) y nunca el guardado normal (`onSave`);
 * - mientras el ViewModel guarda (`AngleSaveState.Saving`) queda deshabilitado, igual que el
 *   guardado normal;
 * - un fallo del guardado de la copia (`AngleSaveState.Failed`) no lo retira: sigue habilitado y un
 *   segundo toque reintenta. Esto reproduce el contrato que fija `AngleEditorSaveRecoveryTest`: el
 *   estado de recuperación no se limpia al publicar un fallo, solo al adoptar la edición de verdad.
 *
 * El estado del editor se construye con la API pública de dominio (`placePoint` + `select`), nunca a
 * mano. No abren un ViewModel, no ejecutan vídeo real, no tocan Room y no validan aspecto píxel a
 * píxel: la persistencia real (copia verificada sin recrear la raíz) la cubre la regresión JVM del
 * ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class AngleEditorSaveAsNewInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = "video:content://openjump/angle-save-as-new-instrumented"
    private val generation = 11L
    private val frameIndex = 3
    private val sourcePtsUs = 100_000L
    private val anchor = AngleFrameAnchor(source, frameIndex = frameIndex, sourcePtsUs = sourcePtsUs)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFFE53935.toInt())

    private val a = AnglePoint(20.0, 20.0)
    private val b = AnglePoint(50.0, 50.0)
    private val c = AnglePoint(80.0, 20.0)

    /** Etiqueta visible y descripción accesible, resueltas con los recursos reales del dispositivo. */
    private val saveAsNewLabel: String
        get() = context.getString(R.string.angle_save_as_new)
    private val saveAsNewDescription: String
        get() = context.getString(R.string.angle_save_as_new_description)

    /**
     * Estado mutable que el test controla y la composición observa.
     *
     * [saveState] y [recovery] son dos observables distintos, igual que en el ViewModel: el último
     * intento de guardado puede ser un fallo mientras la recuperación sigue pendiente, y por eso
     * «guardar como nuevo» debe seguir disponible para reintentar.
     */
    private class SaveAsNewHarness {
        var saveState by mutableStateOf<AngleSaveState>(AngleSaveState.Idle)
        var recovery by mutableStateOf<AngleSaveRecovery>(AngleSaveRecovery.None)
        var saveAsNewRequests = 0
        var saveRequests = 0
    }

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    /** Editor con un ángulo completo (A, B, C) seleccionado: hay ancla y hay algo persisting. */
    private fun editorWithAngle(): AngleEditorState {
        var state = AngleEditorState.initial(source, generation, defaults)
        listOf(a, b, c).forEach { point ->
            state = applied(state.placePoint(point, confirmation))
        }
        return applied(state.select(AngleId(1L)))
    }

    private fun compose(harness: SaveAsNewHarness, editor: AngleEditorState = editorWithAngle()) {
        rule.setContent {
            OpenJumpTheme {
                AngleEditorContent(
                    editor = editor,
                    videoState = AngleVideoState.Ready(
                        frameCount = 12,
                        frameIndex = frameIndex,
                        sourcePtsUs = sourcePtsUs,
                    ),
                    saveState = harness.saveState,
                    displayState = FrameDisplayState(
                        requestedIndex = frameIndex,
                        renderedIndex = frameIndex,
                        renderedPtsUs = sourcePtsUs,
                        mode = ViewerTransportMode.PAUSED,
                    ),
                    player = null,
                    videoGeometry = VideoPresentationGeometry(200, 100),
                    loading = false,
                    errorMessage = null,
                    confirmation = confirmation,
                    actions = AngleEditorContentActions(
                        onSelectAngle = {},
                        onPlacePoint = { false },
                        onMoveMarker = { _, _, _ -> false },
                        onMoveDraftMarker = { _, _ -> false },
                        onCancelDraft = {},
                        onSetVisible = { _, _ -> },
                        onRename = { _, _ -> },
                        onSetColor = { _, _ -> },
                        onDelete = {},
                        onSave = { harness.saveRequests += 1 },
                        onSaveAsNew = { harness.saveAsNewRequests += 1 },
                        onCancel = {},
                    ),
                    onRefreshFrame = {},
                    saveRecovery = harness.recovery,
                )
            }
        }
    }

    private fun onRecoveryButton() =
        rule.onNodeWithContentDescription(saveAsNewDescription)

    // --- visibilidad: solo con recuperación pendiente -------------------------------------------

    @Test
    fun en_estado_normal_no_se_ofrece_guardar_como_analisis_nuevo() {
        val harness = SaveAsNewHarness()
        compose(harness)

        // Sin recuperación no hay copia: el guardado normal es seguro y un duplicado no se pide solo.
        onRecoveryButton().assertDoesNotExist()
        rule.onNodeWithTag(ANGLE_SAVE_AS_NEW_TAG).assertDoesNotExist()
        rule.onNodeWithText(saveAsNewLabel).assertDoesNotExist()

        // La barra inferior sí está compuesta y el guardado normal sigue disponible: el negativo de
        // arriba no se debe a que la pantalla no se dibujó.
        rule.onNodeWithText(context.getString(R.string.common_save)).assertExists().assertIsEnabled()
    }

    @Test
    fun con_el_analisis_borrado_el_boton_accesible_se_ofrece_habilitado() {
        val harness = SaveAsNewHarness().apply {
            recovery = AngleSaveRecovery.Deleted(analysisId = 7L)
        }
        compose(harness)

        onRecoveryButton().assertExists().assertIsEnabled()
        rule.onNodeWithTag(ANGLE_SAVE_AS_NEW_TAG).assertExists().assertIsEnabled()
        // Texto visible real y descripción para TalkBack llegan a la semántica.
        rule.onNodeWithText(saveAsNewLabel).assertExists()
        onRecoveryButton().assertExists()
    }

    @Test
    fun con_la_escritura_sin_verificar_el_boton_accesible_se_ofrece_habilitado() {
        val harness = SaveAsNewHarness().apply {
            recovery = AngleSaveRecovery.Unverified(analysisId = 7L, revision = 9)
        }
        compose(harness)

        onRecoveryButton().assertExists().assertIsEnabled()
        rule.onNodeWithText(saveAsNewLabel).assertExists()
    }

    @Test
    fun con_el_conflicto_de_cas_el_boton_accesible_se_ofrece_habilitado() {
        val harness = SaveAsNewHarness().apply {
            recovery = AngleSaveRecovery.Conflict(analysisId = 7L, storedRevision = 4)
            saveState = AngleSaveState.Conflict(analysisId = 7L, storedRevision = 4)
        }
        compose(harness)

        // El conflicto se anuncia como tal (no se informa de un guardado correcto).
        rule.onNodeWithText(context.getString(R.string.angle_save_conflict)).assertExists()
        // Y la única salida sin pérdida —conservar la edición como análisis nuevo— está disponible.
        onRecoveryButton().assertExists().assertIsEnabled()
        rule.onNodeWithTag(ANGLE_SAVE_AS_NEW_TAG).assertExists().assertIsEnabled()
        rule.onNodeWithText(saveAsNewLabel).assertExists()
    }

    // --- intención: copia explícita, nunca guardado normal --------------------------------------

    @Test
    fun tocar_guardar_como_nuevo_emite_la_copia_y_no_el_guardado_normal() {
        val harness = SaveAsNewHarness().apply {
            recovery = AngleSaveRecovery.Deleted(analysisId = 7L)
        }
        compose(harness)

        onRecoveryButton().performClick()

        rule.runOnIdle {
            assertEquals("el toque debe pedir la copia", 1, harness.saveAsNewRequests)
            assertEquals("el toque no puede sobrescribir la raíz", 0, harness.saveRequests)
        }
    }

    @Test
    fun tocar_guardar_como_nuevo_con_conflicto_emite_la_copia_y_no_el_guardado_normal() {
        val harness = SaveAsNewHarness().apply {
            recovery = AngleSaveRecovery.Conflict(analysisId = 7L, storedRevision = 4)
        }
        compose(harness)

        onRecoveryButton().performClick()

        rule.runOnIdle {
            assertEquals("el conflicto debe resolverse con la copia", 1, harness.saveAsNewRequests)
            assertEquals("el guardado normal no puede pisar la revisión ajena", 0, harness.saveRequests)
        }
    }

    // --- ciclo de guardado: deshabilitado mientras guarda ---------------------------------------

    @Test
    fun mientras_guarda_el_boton_accesible_queda_deshabilitado() {
        val harness = SaveAsNewHarness().apply {
            recovery = AngleSaveRecovery.Deleted(analysisId = 7L)
            saveState = AngleSaveState.Saving
        }
        compose(harness)

        onRecoveryButton().assertExists().assertIsNotEnabled()
        rule.onNodeWithTag(ANGLE_SAVE_AS_NEW_TAG).assertIsNotEnabled()
        // El ciclo de guardado es compartido: el guardado normal también está ocupado.
        rule.onNodeWithText(context.getString(R.string.common_saving))
            .assertExists()
            .assertIsNotEnabled()
    }

    // --- fallo y reintento: la recuperación no se retira con un fallo ----------------------------

    @Test
    fun tras_fallar_la_copia_borrada_el_boton_sigue_disponible_y_reintenta() {
        val harness = SaveAsNewHarness().apply {
            recovery = AngleSaveRecovery.Deleted(analysisId = 7L)
        }
        compose(harness)

        onRecoveryButton().performClick()
        rule.runOnIdle {
            assertEquals("primer intento de copia", 1, harness.saveAsNewRequests)
            // El ViewModel publica el último intento como fallo y conserva la recuperación: así lo
            // fija `AngleEditorSaveRecoveryTest`. La pantalla no debe retirar la única salida.
            harness.saveState = AngleSaveState.Failed("no se pudo verificar la copia")
        }

        rule.onNodeWithText(context.getString(R.string.angle_save_failed)).assertExists()
        onRecoveryButton().assertExists().assertIsEnabled()

        onRecoveryButton().performClick()

        rule.runOnIdle {
            assertEquals("el reintento debe volver a pedir la copia", 2, harness.saveAsNewRequests)
            assertEquals("nunca se cae al guardado normal", 0, harness.saveRequests)
        }
    }

    @Test
    fun tras_fallar_la_copia_sin_verificar_el_boton_sigue_disponible_y_reintenta() {
        val harness = SaveAsNewHarness().apply {
            recovery = AngleSaveRecovery.Unverified(analysisId = 7L, revision = 9)
        }
        compose(harness)

        onRecoveryButton().performClick()
        rule.runOnIdle {
            assertEquals("primer intento de copia", 1, harness.saveAsNewRequests)
            harness.saveState = AngleSaveState.Failed("la foto no coincidió con lo enviado")
        }

        rule.onNodeWithText(context.getString(R.string.angle_save_failed)).assertExists()
        onRecoveryButton().assertExists().assertIsEnabled()

        onRecoveryButton().performClick()

        rule.runOnIdle {
            assertEquals("el reintento debe volver a pedir la copia", 2, harness.saveAsNewRequests)
            assertEquals("nunca se cae al guardado normal", 0, harness.saveRequests)
        }
    }
}
