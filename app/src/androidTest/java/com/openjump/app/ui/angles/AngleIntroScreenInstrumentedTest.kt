package com.openjump.app.ui.angles

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de la preparación de Ángulos (composición real, sin dispositivo físico).
 *
 * Cubren lo que los tests JVM no pueden: que la pantalla no dispare callbacks al entrar, que
 * ayuda/volver no inicien grabación ni importación, que el footer sólo se habilite con un atleta
 * activo seleccionado y `startEnabled`, que el [AthleteSelector] real entregue el id elegido sin
 * abrir base de datos, ViewModel ni sesión, y que el copy respete el gate inyectado
 * `multiFrameEnabled` (con captura de fotograma y varios fotogramas sólo cuando está ON).
 * El harness es una `ComponentActivity` vacía: nunca se
 * monta `MainActivity`, no se usa la DB de usuario y no se ejecuta vídeo ni permiso de cámara.
 *
 * Compiladas, no ejecutadas en esta slice: requieren un AVD, que está fuera del alcance de S1.
 */
@RunWith(AndroidJUnit4::class)
class AngleIntroScreenInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val athleteA = AthleteEntity(id = 11L, displayName = "Atleta A")
    private val athleteB = AthleteEntity(id = 22L, displayName = "Atleta B")
    private val archived = AthleteEntity(id = 33L, displayName = "Atleta Archivado", archivedAt = 1_700_000_000_000L)

    private val recordCalls = mutableListOf<Unit>()
    private val importCalls = mutableListOf<Unit>()
    private val backCalls = mutableListOf<Unit>()
    private val helpCalls = mutableListOf<Unit>()
    private val selectedIds = mutableListOf<Long>()

    private fun setContent(
        athletes: List<AthleteEntity> = listOf(athleteA, athleteB, archived),
        loading: Boolean = false,
        selectedId: Long? = athleteA.id,
        startEnabled: Boolean = true,
        errorMessage: String? = null,
        multiFrameEnabled: Boolean = false,
    ) {
        rule.setContent {
            OpenJumpTheme {
                AngleIntroScreen(
                    onBack = { backCalls += Unit },
                    onHowItWorks = { helpCalls += Unit },
                    onRecord = { recordCalls += Unit },
                    onImport = { importCalls += Unit },
                    onAthleteSelected = { selectedIds += it },
                    activeAthletes = athletes,
                    athletesLoading = loading,
                    selectedAthleteId = selectedId,
                    startEnabled = startEnabled,
                    errorMessage = errorMessage,
                    multiFrameEnabled = multiFrameEnabled,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun entrarEnLaPantallaNoIniciaGrabacionNiImportacion() {
        setContent()
        rule.onNodeWithTag("angle-intro-root").assertIsDisplayed()
        rule.onNodeWithTag("angle-intro-record").assertIsDisplayed()
        assertTrue("record no debe dispararse al entrar: $recordCalls", recordCalls.isEmpty())
        assertTrue("import no debe dispararse al entrar: $importCalls", importCalls.isEmpty())
        assertTrue("onBack no debe dispararse al entrar: $backCalls", backCalls.isEmpty())
        assertTrue("onHowItWorks no debe dispararse al entrar: $helpCalls", helpCalls.isEmpty())
        assertTrue("no debe autoelegir atleta: $selectedIds", selectedIds.isEmpty())
    }

    @Test
    fun volverYAyudaNoInicianGrabacionNiImportacion() {
        setContent()
        rule.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        rule.onNodeWithTag("angle-intro-help").performClick()
        rule.waitForIdle()
        assertEquals("volver debe llegar al llamante una vez", 1, backCalls.size)
        assertEquals("ayuda debe llegar al llamante una vez", 1, helpCalls.size)
        assertTrue("record no debe dispararse: $recordCalls", recordCalls.isEmpty())
        assertTrue("import no debe dispararse: $importCalls", importCalls.isEmpty())
    }

    @Test
    fun conAtletaActivoValidoSeEntreganGrabacionEImportacion() {
        setContent()
        rule.onNodeWithTag("angle-intro-record").performClick()
        rule.onNodeWithTag("angle-intro-import").performClick()
        rule.waitForIdle()
        assertEquals("record debe entregarse una vez", 1, recordCalls.size)
        assertEquals("import debe entregarse una vez", 1, importCalls.size)
    }

    @Test
    fun cargandoDeshabilitaElFooterYLoExplica() {
        setContent(loading = true)
        rule.onNodeWithTag("angle-intro-record").assertIsNotEnabled()
        rule.onNodeWithTag("angle-intro-import").assertIsNotEnabled()
        // La misma cadena aparece en el selector y en el motivo del footer.
        rule.onAllNodesWithText(context.getString(R.string.athlete_selector_loading))
            .onFirst().assertIsDisplayed()
        rule.onNodeWithTag("angle-intro-record").performClick()
        rule.onNodeWithTag("angle-intro-import").performClick()
        assertTrue("nada debe iniciarse cargando", recordCalls.isEmpty() && importCalls.isEmpty())
    }

    @Test
    fun sinAtletaValidoElFooterSeDeshabilitaSinInventarUnAtleta() {
        setContent(athletes = emptyList(), selectedId = null)
        rule.onNodeWithTag("angle-intro-record").assertIsNotEnabled()
        rule.onNodeWithTag("angle-intro-import").assertIsNotEnabled()
        rule.onNodeWithTag("angle-intro-pending-reason").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.angle_intro_select_athlete)).assertExists()
        rule.onNodeWithText("Atleta 1").assertDoesNotExist()
        rule.onNodeWithTag("angle-intro-history-helper").assertDoesNotExist()
        assertTrue("nada debe iniciarse sin atleta", recordCalls.isEmpty() && importCalls.isEmpty())
    }

    @Test
    fun unAtletaArchivadoNoHabilitaElInicio() {
        setContent(selectedId = archived.id)
        rule.onNodeWithTag("angle-intro-record").assertIsNotEnabled()
        rule.onNodeWithTag("angle-intro-import").assertIsNotEnabled()
        rule.onNodeWithTag("angle-intro-pending-reason").assertIsDisplayed()
        assertTrue("nada debe iniciarse con atleta archivado", recordCalls.isEmpty() && importCalls.isEmpty())
    }

    @Test
    fun startEnabledExternoEnFalseMantieneElFooterBloqueadoConRazon() {
        setContent(startEnabled = false)
        rule.onNodeWithTag("angle-intro-record").assertIsNotEnabled()
        rule.onNodeWithTag("angle-intro-import").assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.angle_intro_start_blocked)).assertExists()
    }

    @Test
    fun elSelectorRealEntregaElAtletaBSinBaseDeDatos() {
        setContent()
        // El texto de ayuda confirma que la pantalla muestra el nombre de la entidad seleccionada.
        rule.onNodeWithText(context.getString(R.string.angle_intro_history_helper, athleteA.displayName))
            .assertIsDisplayed()
        rule.onNodeWithTag("angle-intro-athlete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(athleteB.displayName).performClick()
        rule.waitForIdle()
        assertEquals("el selector debe entregar el id real de B", listOf(athleteB.id), selectedIds)
        assertTrue("elegir atleta no debe iniciar nada", recordCalls.isEmpty() && importCalls.isEmpty())
    }

    @Test
    fun fuenteGrandeYErrorLargoNoRompenElFooterNiOcultanElAviso2D() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                OpenJumpTheme {
                    AngleIntroScreen(
                        onBack = {},
                        onHowItWorks = {},
                        onRecord = {},
                        onImport = {},
                        onAthleteSelected = {},
                        activeAthletes = listOf(athleteA),
                        selectedAthleteId = athleteA.id,
                        startEnabled = false,
                        errorMessage = context.getString(R.string.angle_intro_help_precision) + " " +
                            context.getString(R.string.angle_intro_help_safe_capture),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("angle-intro-pending-reason").assertIsDisplayed()
        rule.onNodeWithTag("angle-intro-record").assertHeightIsAtLeast(56.dp).assertIsNotEnabled()
        rule.onNodeWithTag("angle-intro-import").assertHeightIsAtLeast(56.dp).assertIsNotEnabled()
        rule.onNodeWithTag("angle-intro-limit").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("angle-intro-content").assertExists()
    }

    @Test
    fun conGateMultiFrameActivoElPasoUnoEnseniaLaEtiquetaRealDeCaptura() {
        setContent(multiFrameEnabled = true)
        val captureLabel = context.getString(R.string.angle_workspace_capture)
        // El paso 1 enseña la acción REAL: pausar y pulsar la etiqueta localizada de captura.
        rule.onNodeWithText(
            context.getString(R.string.angle_intro_step_pick_body, captureLabel),
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(captureLabel, substring = true).assertExists()
        // Con el gate ON el resumen y el guardado describen varios fotogramas.
        rule.onNodeWithText(context.getString(R.string.angle_intro_overview)).assertExists()
        rule.onNodeWithText(context.getString(R.string.angle_intro_step_save_body)).assertExists()
        // El copy legacy del gate OFF no debe aparecer.
        rule.onNodeWithText(context.getString(R.string.angle_workspace_capture_hint)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.home_angles_description)).assertDoesNotExist()
    }

    @Test
    fun conGateMultiFrameApagadoNoSeEnseniaBotonNiVariosFotogramas() {
        setContent(multiFrameEnabled = false)
        val captureLabel = context.getString(R.string.angle_workspace_capture)
        // Sin el gate no existe la captura multi-fotograma: se reutiliza la pista genérica de pausa
        // y el recordatorio de guardado manual del flujo legacy.
        rule.onNodeWithText(context.getString(R.string.angle_workspace_capture_hint))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.home_angles_description)).assertExists()
        rule.onNodeWithText(context.getString(R.string.angle_intro_help_precision))
            .performScrollTo().assertIsDisplayed()
        // Ni la etiqueta del botón V2 ni el copy de varios fotogramas se enseñan.
        rule.onNodeWithText(captureLabel).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.angle_intro_step_save_body)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.angle_intro_overview)).assertDoesNotExist()
    }

    @Test
    fun conGateMultiFrameApagadoLaAyudaNoEnseniaCapturaCongelada() {
        rule.setContent {
            OpenJumpTheme {
                AngleHowItWorksScreen(onBack = {}, multiFrameEnabled = false)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("angle-how-it-works-root").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.angle_workspace_capture_hint))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.angle_intro_help_safe_capture)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.angle_workspace_capture)).assertDoesNotExist()
    }

    @Test
    fun conGateMultiFrameActivoLaAyudaEnseniaCapturaCongelada() {
        rule.setContent {
            OpenJumpTheme {
                AngleHowItWorksScreen(onBack = {}, multiFrameEnabled = true)
            }
        }
        rule.waitForIdle()
        val captureLabel = context.getString(R.string.angle_workspace_capture)
        rule.onNodeWithText(context.getString(R.string.angle_intro_help_safe_capture))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            context.getString(R.string.angle_intro_step_pick_body, captureLabel),
        ).assertExists()
    }
}
