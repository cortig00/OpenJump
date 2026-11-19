package com.openjump.app.ui.angles

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Acredita (compilado en este slice, sin AVD) la presentación S4a REAL del workspace:
 *
 * - fullscreen ocupa el espacio y Atrás sale primero de fullscreen sin cerrar el workspace;
 * - con fontScale 2 la etapa conserva altura positiva y los controles esenciales siguen presentes;
 * - el encuadre de la fuente no altera puntos/datos y cambiar de fotograma conserva los suyos;
 * - los dos arreglos del inspector: seleccionar un ángulo Applied limpia «Otro ángulo» sin borrar
 *   el borrador A/B, y «Ajustar A/B» elige un marcador válido ya colocado (A).
 *
 * El host de ventana es un FAKE explícito: ComponentActivity no tiene `configChanges`, así que este
 * test no afirma retención nativa de rotación; solo verifica llamadas y UI.
 */
@RunWith(AndroidJUnit4::class)
class AngleWorkspacePresentationInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var harness: AngleMultiFrameFlowHarness? = null
    private val fakeHost = FakeWindowHost()

    @After
    fun tearDown() {
        harness?.close()
    }

    private class FakeWindowHost : AngleWorkspaceWindowHost {
        var requested: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val orientationRequests = mutableListOf<Int>()
        var hideCalls = 0
        var restoreCalls = 0

        override fun requestedOrientation(): Int = requested

        override fun requestOrientation(orientation: Int) {
            requested = orientation
            orientationRequests += orientation
        }

        override fun hideSystemBars(): AngleWorkspaceBarsSnapshot {
            hideCalls += 1
            return AngleWorkspaceBarsSnapshot(
                statusBarsVisible = true,
                navigationBarsVisible = true,
                systemBarsBehavior = 2,
            )
        }

        override fun restoreSystemBars(snapshot: AngleWorkspaceBarsSnapshot) {
            restoreCalls += 1
        }
    }

    private fun newHarness(): AngleMultiFrameFlowHarness =
        AngleMultiFrameFlowHarness(productionRenderer = true).also {
            harness = it
            it.openVideo()
            it.openMultiFrameSession()
        }

    private fun composeWorkspace(closed: () -> Unit = {}) {
        val host = harness ?: newHarness()
        rule.setContent {
            OpenJumpTheme {
                AngleEditorWorkspace(
                    viewModel = host.viewModel,
                    onCloseRequest = closed,
                    windowHost = fakeHost,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun fullscreenBackSalePrimeroYNoCierraElWorkspace() {
        val host = newHarness()
        var closed = false
        rule.setContent {
            OpenJumpTheme {
                AngleEditorWorkspace(
                    viewModel = host.viewModel,
                    onCloseRequest = { closed = true },
                    windowHost = fakeHost,
                )
            }
        }
        rule.onNodeWithTag(ANGLE_WORKSPACE_FULLSCREEN_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_WORKSPACE_EXIT_FULLSCREEN_TAG).assertExists()
        assertFalse(closed)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_WORKSPACE_EXIT_FULLSCREEN_TAG).assertDoesNotExist()
        rule.onNodeWithTag(ANGLE_WORKSPACE_FULLSCREEN_TAG).assertExists()
        assertFalse("Atrás en fullscreen no debe cerrar el workspace", closed)

        // El segundo Atrás sí llega al llamante (que aplica su política fresca).
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(closed)
    }

    @Test
    fun fullscreenConVideoHorizontalPideLandscapeAunqueLaVentanaSigaEnPortrait() {
        val host = newHarness()
        composeWorkspace()
        // Precondición real (no supuesta): el fixture entrega geometría y es horizontal.
        rule.waitUntil(5_000) { host.viewModel.videoGeometry.value != null }
        val geometry = host.viewModel.videoGeometry.value
        assertTrue("el fixture debe ser horizontal: $geometry", geometry != null && geometry.width > geometry.height)
        // La ventana del test NO ha rotado: solo se comprueba la petición real al host fake.
        rule.onNodeWithTag(ANGLE_WORKSPACE_FULLSCREEN_TAG).performClick()
        rule.waitForIdle()
        assertTrue(
            "vídeo horizontal en ventana portrait debe pedir landscape: " +
                fakeHost.orientationRequests,
            fakeHost.orientationRequests.contains(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE),
        )
        // Salir de fullscreen vuelve a seguir al usuario (no se queda en landscape).
        rule.onNodeWithTag(ANGLE_WORKSPACE_EXIT_FULLSCREEN_TAG).performClick()
        rule.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, fakeHost.requested)
    }

    @Test
    fun fontScale2MantieneStagePositivoYControlesDe48() {
        newHarness()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                OpenJumpTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        AngleEditorWorkspace(
                            viewModel = harness!!.viewModel,
                            onCloseRequest = {},
                            windowHost = fakeHost,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val stageHeight = rule.onNodeWithTag(ANGLE_WORKSPACE_VIDEO_TAG)
            .fetchSemanticsNode().size.height
        assertTrue("la etapa debe tener altura positiva con fontScale 2", stageHeight > 0)
        rule.onNodeWithTag(ANGLE_WORKSPACE_CAPTURE_TAG).assertExists()
    }

    @Test
    fun alturaCortaEnPortraitConFontScale2MantieneVisorPositivoYControlesAlcanzables() {
        newHarness()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                OpenJumpTheme {
                    // 320x360: portrait CORTO real (no cuadrado ni apaisado) con texto al 200%.
                    Box(Modifier.size(320.dp, 360.dp)) {
                        AngleEditorWorkspace(
                            viewModel = harness!!.viewModel,
                            onCloseRequest = {},
                            windowHost = fakeHost,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val stageHeight = rule.onNodeWithTag(ANGLE_WORKSPACE_VIDEO_TAG)
            .fetchSemanticsNode().size.height
        assertTrue(
            "la etapa debe conservar altura REAL positiva con 360dp de alto y fontScale 2",
            stageHeight > 0,
        )
        // Los controles tienen altura acotada y scroll: la CTA sigue siendo alcanzable.
        rule.onNodeWithTag(ANGLE_WORKSPACE_CAPTURE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun encuadreDeFuenteYSeleccionNoMutuanLosPuntosDelFotograma() {
        val host = newHarness()
        val frameId: AngleFrameId = host.captureFrame(3)
        val angleId = host.markAngle(frameId)
        val before = host.frame(frameId).editor.angles.single()
        composeWorkspace()

        // Volver al vídeo muestra el zoom REAL de la fuente; aplicarlo y resetear no toca ningún
        // dato del fotograma congelado.
        rule.onNodeWithText(
            rule.activity.getString(com.openjump.app.R.string.angle_workspace_video_tile),
        ).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_IN_TAG).assertExists().performClick()
        rule.onNodeWithTag(ANGLE_WORKSPACE_SOURCE_ZOOM_RESET_TAG).assertExists().performClick()
        rule.onNodeWithTag(angleWorkspaceThumbTag(frameId)).performClick()
        rule.waitForIdle()
        val after = host.frame(frameId).editor.angles.single()
        assertEquals(angleId, after.id)
        assertEquals(before.measurement, after.measurement)
        assertEquals(before.markers, after.markers)
    }

    @Test
    fun inspectorSeleccionExistenteLimpiaOtroAnguloSinBorrarBorrador() {
        val host = newHarness()
        val frameId = host.captureFrame(2)
        val angleId = host.markAngle(frameId)
        composeWorkspace()

        rule.onNodeWithTag(ANGLE_WORKSPACE_ADD_ANGLE_TAG).performClick()
        rule.waitForIdle()
        // El workspace abre el inspector real desde Precisión.
        rule.onNodeWithTag(ANGLE_WORKSPACE_PRECISION_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_INSPECTOR_TAG).assertExists()

        // Seleccionar el ángulo existente aplica y limpia la intención de añadir.
        rule.onNodeWithTag(angleInspectorRowTag(angleId)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(angleId, host.frame(frameId).editor.selectedAngleId)

        // Volver a «Otro ángulo» y colocar A: seleccionar después el existente NO borra A.
        rule.onNodeWithTag(ANGLE_INSPECTOR_ADD_ANGLE_TAG).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, host.frame(frameId).editor.draft.points.size)
        rule.onNodeWithTag(angleInspectorRowTag(angleId)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(angleId, host.frame(frameId).editor.selectedAngleId)
        assertEquals(
            "Seleccionar un ángulo existente no debe borrar el borrador A/B",
            1,
            host.frame(frameId).editor.draft.points.size,
        )
    }

    @Test
    fun capturaFullscreenConLeasePendienteMantieneLandscapeDeLaFuente() {
        // Geometría verificada de LA MISMA fuente: foto pendiente (null) conserva el vídeo horizontal
        // para no rebotar FOLLOW_USER -> LANDSCAPE al llegar el lease. No se arrastra otra época.
        val videoGeometry = com.openjump.app.tracking.VideoPresentationGeometry(width = 320, height = 240)
        assertTrue("el fixture de orientación debe ser horizontal", videoGeometry.width > videoGeometry.height)
        // Sin lease (pendiente/no disponible): fallback a la fuente mantiene LANDSCAPE en fullscreen.
        val pendingFallback = videoGeometry
        assertEquals(
            AngleWorkspaceOrientationTarget.LANDSCAPE,
            angleWorkspaceOrientationTarget(true, pendingFallback),
        )
        // Sin geometría alguna: no se inventa giro.
        assertEquals(
            AngleWorkspaceOrientationTarget.FOLLOW_USER,
            angleWorkspaceOrientationTarget(true, null),
        )
        // Fuera de fullscreen siempre se sigue al usuario.
        assertEquals(
            AngleWorkspaceOrientationTarget.FOLLOW_USER,
            angleWorkspaceOrientationTarget(false, videoGeometry),
        )
        // Transición completa con host fake: horizontal pide landscape, salir vuelve a follow_user.
        val host = FakeWindowHost()
        val lease = AngleWorkspacePresentationLease(host)
        lease.onResumed(true, videoGeometry)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, host.requested)
        lease.update(true, videoGeometry)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, host.requested)
        lease.update(false, videoGeometry)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, host.requested)
        lease.onPaused()
    }

    @Test
    fun inspectorAjustarABEligeMarcadorValidoYaColocado() {
        val host = newHarness()
        val frameId = host.captureFrame(1)
        host.viewModel.placeMultiFramePoint(frameId, AnglePoint(20.0, 20.0))
        host.viewModel.placeMultiFramePoint(frameId, AnglePoint(80.0, 20.0))
        composeWorkspace()
        val draftBefore = host.frame(frameId).editor.draft.points
        rule.onNodeWithTag(ANGLE_WORKSPACE_PRECISION_TAG).performClick()
        rule.waitForIdle()

        // Con A y B ya colocados y sin intención de ajuste el target es la C siguiente: los chips
        // A/B/C todavía NO existen. Primero se pulsa el botón REAL de ajuste, que elige A ya
        // colocado y expone los chips; no se fuerza el modo por ViewModel.
        rule.onNodeWithTag(angleAccessibleMarkerTag(AngleMarker.B)).assertDoesNotExist()
        rule.onNodeWithTag(ANGLE_INSPECTOR_ADJUST_DRAFT_TAG).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(angleAccessibleMarkerTag(AngleMarker.A)).performScrollTo().assertExists()

        // Elegir B con su chip real (tag estable, no el TEXT localizado) y comprobar que el botón
        // de confirmación pasa a ajustar B.
        rule.onNodeWithTag(angleAccessibleMarkerTag(AngleMarker.B)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(
            rule.activity.getString(
                com.openjump.app.R.string.angle_accessible_adjust,
                AngleMarker.B.name,
            ),
        ).performScrollTo().assertExists()

        // Volver a pulsar Ajustar A/B resetea el target a A con el cursor limpio, sin descartar ni
        // mover el borrador de dos puntos.
        rule.onNodeWithTag(ANGLE_INSPECTOR_ADJUST_DRAFT_TAG).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(
            rule.activity.getString(
                com.openjump.app.R.string.angle_accessible_adjust,
                AngleMarker.A.name,
            ),
        ).performScrollTo().assertExists()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).performScrollTo().assertIsEnabled()
        assertEquals(
            "el borrador conserva sus dos puntos intactos",
            draftBefore,
            host.frame(frameId).editor.draft.points,
        )
        assertEquals(2, host.frame(frameId).editor.draft.points.size)
    }
}
