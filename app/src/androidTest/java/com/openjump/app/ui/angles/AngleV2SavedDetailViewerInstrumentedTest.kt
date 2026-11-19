package com.openjump.app.ui.angles

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comportamiento REAL de [AngleV2SavedFrameSection] (composable de producción) con bitmaps sólidos
 * pequeños y un cargador controlado por `CompletableDeferred`.
 *
 * Lo que estas pruebas acreditan, sin base Room, sin vídeo y sin abrir el almacén:
 * - una sola carga por petición; la ampliación reutiliza la MISMA referencia de bitmap (mismo píxel
 *   renderizado) y Back del sistema cierra sólo el diálogo;
 * - zoom, desplazamiento y reinicio se observan sobre la transformación APLICADA al bitmap del
 *   diálogo ([AngleDetailViewportSemanticsKey]), no sobre una copia calculada por la prueba;
 * - al cambiar raíz, fotograma o integridad declarada no se pinta la imagen anterior, el diálogo se
 *   cierra, el plegable técnico vuelve a cerrado y una carga vieja que termina tarde no puede
 *   publicarse encima de la nueva;
 * - los estados de imagen no disponible se ven SIEMPRE, fuera del plegable, con las mediciones y las
 *   marcas ocultas conservadas; con una imagen declarada de 1280×1 el aviso largo conserva su altura
 *   natural (la misma que el texto equivalente sin restricciones) y la leyenda queda debajo;
 * - una imagen VÁLIDA con aspecto extremo (1×1280 y 1280×1) dentro de una `LazyColumn` real pinta su
 *   caja visible con alto acotado (entre 48 y 480 dp) sin desbordar la medición, y la leyenda, las
 *   marcas y los datos siguen alcanzándose al desplazar la lista;
 * - compartir respeta el bloqueo y entrega el fotograma fresco con el callback vigente, y el detalle
 *   no ofrece acciones de vídeo ni de edición;
 * - a escala de letra 1,5× (tema claro y oscuro), montada en el MISMO host de producción
 *   (`Scaffold` + `LazyColumn` con clave raíz+fotograma y `key(imageRequest)`), los OCHO controles
 *   del pie se ven, están habilitados, miden al menos 48 dp en ambos ejes y quedan dentro de la
 *   ventana del diálogo; el pie mide al menos 56 dp. A zoom 4 los cuatro desplazamientos se
 *   observan sobre el viewport aplicado (signo y tope), reiniciar vuelve a 1× centrado y «Cerrar»
 *   cierra sólo el diálogo.
 *
 * Límites honestos: se ejecuta sobre el bitmap sólido de prueba, así que NO acredita decodificación
 * verificada, presupuesto de imagen, almacén, base, selección de compartir del sistema ni la pantalla
 * completa (cabecera/dueño, snackbars, compartir todas). Esas rutas tienen sus propias pruebas. Aquí
 * tampoco se ejecuta nada en un dispositivo en este slice: el fichero se compila y la ejecución nativa
 * queda pendiente.
 */
@RunWith(AndroidJUnit4::class)
class AngleV2SavedDetailViewerInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val gates = Collections.synchronizedMap(
        mutableMapOf<Long, CompletableDeferred<AngleV2SavedImage>>(),
    )
    private val requestGates = Collections.synchronizedMap(
        mutableMapOf<AngleV2SavedImageRequest, CompletableDeferred<AngleV2SavedImage>>(),
    )

    private lateinit var markLeft: AngleSavedDetailMark
    private lateinit var markRight: AngleSavedDetailMark
    private lateinit var bitmapA: Bitmap
    private lateinit var bitmapB: Bitmap
    private lateinit var bitmapOtherRoot: Bitmap
    private lateinit var bitmapChangedSha: Bitmap
    private lateinit var bitmapChangedBytes: Bitmap

    @Before
    fun setUp() {
        gates.clear()
        requestGates.clear()
        // Fixture corregida: C en (50, 20) hace perpendicular BC a BA y la marca visible mide 90°
        // reales. Antes C era (50, 60), colineal con A y B, así que LAS DOS marcas medían 180°: los
        // grados de la marca izquierda aparecían en dos nodos legítimos y las búsquedas por texto
        // único (`onNodeWithText(degreesText(markLeft))`) fallaban por ambigüedad, no por producto.
        markLeft = angleSavedMark(
            id = 1L,
            ordinal = 0,
            name = "Rodilla izquierda",
            visible = true,
            pointAX = 10.0,
            pointAY = 20.0,
            pointBX = 30.0,
            pointBY = 40.0,
            pointCX = 50.0,
            pointCY = 20.0,
        )
        // La marca oculta se queda colineal (180°) como caso distinto y visible en las lecturas.
        markRight = angleSavedMark(
            id = 2L,
            ordinal = 1,
            name = "Tobillo oculto",
            visible = false,
            pointAX = 70.0,
            pointAY = 80.0,
            pointBX = 90.0,
            pointBY = 100.0,
            pointCX = 110.0,
            pointCY = 120.0,
        )
        // Contrato de la fixture, independiente del render: 90° en la marca visible y 180° en la
        // oculta, con lecturas distintas. Si alguien vuelve a igualarlas, los grados de la marca
        // izquierda reaparecerían dos veces y las consultas por texto de grados dejarían de ser
        // únicas: la prueba fallaría aquí, en la fixture, y no en el composable de producción.
        val leftDegrees = requireNotNull(markLeft.degrees) {
            "la marca de prueba izquierda debe ser medible"
        }
        val rightDegrees = requireNotNull(markRight.degrees) {
            "la marca de prueba derecha debe ser medible"
        }
        assertEquals("grados de markLeft en la fixture de prueba", 90.0, leftDegrees, DEGREES_DELTA)
        assertEquals("grados de markRight en la fixture de prueba", 180.0, rightDegrees, DEGREES_DELTA)
        assertTrue(
            "las dos marcas de prueba deben conservar lecturas distintas, no " +
                "$leftDegrees y $rightDegrees",
            abs(leftDegrees - rightDegrees) > DEGREES_DELTA,
        )
        bitmapA = solidBitmap(COLOR_A)
        bitmapB = solidBitmap(COLOR_B)
        bitmapOtherRoot = solidBitmap(COLOR_OTHER_ROOT)
        bitmapChangedSha = solidBitmap(COLOR_CHANGED_SHA)
        bitmapChangedBytes = solidBitmap(COLOR_CHANGED_BYTES)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Carga única, ampliación con el mismo bitmap, transformación real y Back del sistema.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun cargarUnaVezAbrirElDialogoReutilizaElBitmapYBackCierraSoloElDialogo() {
        val frame = savedFrame(frameId = 11L)
        val state = SectionState(ROOT_A, frame)
        val loads = AtomicInteger()
        rule.setSectionContent(state) {
            loads.incrementAndGet()
            AngleV2SavedImage.Available(bitmapA)
        }

        waitForCardImage()
        assertEquals("la imagen debe cargarse una sola vez", 1, loads.get())
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_A)
        rule.onNodeWithText(strings(R.string.angle_saved_frame, frame.displayFrameNumber)).assertIsDisplayed()

        rule.onNodeWithText(expandLabel(frame)).assertIsDisplayed().performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        assertEquals("abrir la ampliación no vuelve a cargar la imagen", 1, loads.get())
        // La MISMA referencia de bitmap: el bitmap sólido se ve igual en el diálogo.
        assertRendersColor(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG, COLOR_A)
        assertEquals(
            frameLabel(frame),
            rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG)
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .joinToString(),
        )
        assertDialogControlTargets()

        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_zoom_in)).performClick()
        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_zoom_in)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(zoomText(2.0)).assertIsDisplayed()
        assertEquals("el zoom aplicado al bitmap debe ser el pulsado", 2f, dialogViewport().zoom, 0.001f)

        clickPan(R.string.angle_polish_control_pan_right)
        rule.waitForIdle()
        assertTrue(
            "el desplazamiento real debe avanzar hacia la derecha (viewport=${dialogViewport()})",
            dialogViewport().panX > 0f,
        )

        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_fit)).performClick()
        rule.waitForIdle()
        assertEquals("reiniciar devuelve 1× centrado", AngleDetailViewport(), dialogViewport())
        rule.onNodeWithText(zoomText(1.0)).assertIsDisplayed()

        // Back del SISTEMA: cierra el diálogo y nada más.
        pressSystemBack()
        waitForTagGone(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertExists()
        rule.onNodeWithText(frameLabel(frame)).assertIsDisplayed()
        rule.onNodeWithText(markLeft.name).assertIsDisplayed()
        assertEquals("cerrar el diálogo no recarga la imagen", 1, loads.get())

        // Volver a abrir no vuelve a cargar la misma imagen ni conserva zoom/desplazamiento.
        rule.onNodeWithText(expandLabel(frame)).performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        assertEquals(1, loads.get())
        assertEquals(AngleDetailViewport(), dialogViewport())
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Fotograma A visible -> B con carga suspendida (sin imagen/acciones viejas) y llegada tardía.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun cambiarDeFotogramaConCargaSuspendidaNoMuestraLaImagenNiLasAccionesAnteriores() {
        val frameA = savedFrame(frameId = 11L)
        val frameB = savedFrame(frameId = 12L, assignedFrameIndex = 1)
        val state = SectionState(ROOT_A, frameA)
        val loads = Collections.synchronizedList(mutableListOf<Long>())
        rule.setSectionContent(state) { frame ->
            loads.add(frame.frameId)
            gateFor(frame.frameId).await()
        }

        gateFor(frameA.frameId).complete(AngleV2SavedImage.Available(bitmapA))
        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_A)
        assertEquals(listOf(frameA.frameId), loads.toList().distinct())

        rule.runOnIdle { state.frame.value = frameB }
        waitForText(pendingText())
        // Mientras B carga: ni imagen de A, ni sus botones, bajo la leyenda de B.
        assertNoCardImage()
        rule.onNodeWithText(expandLabel(frameA)).assertDoesNotExist()
        rule.onNodeWithText(expandLabel(frameB)).assertDoesNotExist()
        rule.onNodeWithText(frameLabel(frameB)).assertExists()

        gateFor(frameB.frameId).complete(AngleV2SavedImage.Available(bitmapB))
        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_B)
        rule.onNodeWithText(frameLabel(frameB)).assertIsDisplayed()
        rule.onNodeWithText(expandLabel(frameB)).assertExists()

        // Una petición anterior que termina TARDE no puede publicar su imagen encima de la nueva.
        val frameC = savedFrame(frameId = 13L, assignedFrameIndex = 2)
        val frameD = savedFrame(frameId = 14L, assignedFrameIndex = 3)
        rule.runOnIdle { state.frame.value = frameC }
        waitForText(pendingText())
        rule.runOnIdle { state.frame.value = frameD }
        rule.waitForIdle()
        gateFor(frameC.frameId).complete(AngleV2SavedImage.Available(bitmapChangedSha))
        rule.waitForIdle()
        assertNoCardImage()
        gateFor(frameD.frameId).complete(AngleV2SavedImage.Available(bitmapChangedBytes))
        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_CHANGED_BYTES)
        rule.onNodeWithText(expandLabel(frameD)).assertExists()
        assertEquals(
            "cada fotograma se carga una vez, sin publicar el anterior",
            listOf(11L, 12L, 13L, 14L),
            loads.toList(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Misma identidad numérica de fotograma pero otra raíz / otro SHA / otros bytes.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun cambiarRaizShaOBytesConElMismoFrameIdNoPintaLaImagenAnterior() {
        val base = savedFrame(frameId = 11L)
        val state = SectionState(ROOT_A, base)
        val requested = Collections.synchronizedList(mutableListOf<AngleV2SavedImageRequest>())
        rule.setContent {
            OpenJumpTheme {
                AngleV2SavedFrameSection(
                    rootId = state.rootId.value,
                    frame = state.frame.value,
                    imageLoader = { frame ->
                        val request = frame.imageRequest(state.rootId.value)
                        requested.add(request)
                        requestGate(request).await()
                    },
                    shareEnabled = true,
                    onShare = {},
                )
            }
        }

        rule.waitForIdle()
        assertNoCardImage()
        releaseRequest(base.imageRequest(ROOT_A), AngleV2SavedImage.Available(bitmapA))
        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_A)

        // Otra raíz, mismo frameId/imageKey/SHA/bytes: no puede reutilizar el bitmap anterior.
        val otherRoot = ROOT_A + 1
        rule.runOnIdle { state.rootId.value = otherRoot }
        rule.waitForIdle()
        assertNoCardImage()
        releaseRequest(base.imageRequest(otherRoot), AngleV2SavedImage.Available(bitmapOtherRoot))
        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_OTHER_ROOT)

        // Misma raíz y frameId, SHA declarado distinto: otra petición, otra verificación.
        val changedSha = base.copy(imageSha256 = SHA_B)
        rule.runOnIdle { state.frame.value = changedSha }
        rule.waitForIdle()
        assertNoCardImage()
        releaseRequest(changedSha.imageRequest(otherRoot), AngleV2SavedImage.Available(bitmapChangedSha))
        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_CHANGED_SHA)

        // Mismos metadatos salvo el tamaño declarado: la integridad también forma parte de la petición.
        val changedBytes = changedSha.copy(imageByteCount = 4096L)
        rule.runOnIdle { state.frame.value = changedBytes }
        rule.waitForIdle()
        assertNoCardImage()
        releaseRequest(changedBytes.imageRequest(otherRoot), AngleV2SavedImage.Available(bitmapChangedBytes))
        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_CHANGED_BYTES)

        assertEquals(
            "raíz, fotograma e integridad producen peticiones distintas y una carga por petición",
            4,
            requested.toList().size,
        )
        assertEquals(4, requested.toList().distinct().size)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Cambiar la identidad con el diálogo y el plegable abiertos: todo se reinicia.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun alCambiarLaIdentidadSeCierranElDialogoYElPlegableYLaVistaVuelveACentrada() {
        val frameA = savedFrame(frameId = 11L)
        val frameB = savedFrame(frameId = 12L, assignedFrameIndex = 1)
        val state = SectionState(ROOT_A, frameA)
        val loads = AtomicInteger()
        rule.setSectionContent(state) { frame ->
            loads.incrementAndGet()
            AngleV2SavedImage.Available(if (frame.frameId == frameA.frameId) bitmapA else bitmapB)
        }

        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_A)
        rule.onNodeWithTag(ANGLE_V2_SAVED_TECHNICAL_TOGGLE_TAG).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(strings(R.string.angle_viewport_state_expanded), technicalState())
        rule.onNodeWithText(pointsText(markLeft)).assertExists()

        rule.onNodeWithText(expandLabel(frameA)).performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_zoom_in)).performClick()
        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_zoom_in)).performClick()
        clickPan(R.string.angle_polish_control_pan_right)
        rule.waitForIdle()
        assertTrue(dialogViewport().zoom > 1f)
        assertTrue(dialogViewport().panX > 0f)

        rule.runOnIdle { state.frame.value = frameB }
        // El diálogo viejo no sobrevive al cambio de identidad.
        waitForTagGone(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        rule.waitForIdle()
        assertEquals(strings(R.string.angle_viewport_state_normal), technicalState())
        rule.onNodeWithText(pointsText(markLeft)).assertDoesNotExist()

        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_B)
        rule.onNodeWithText(expandLabel(frameB)).performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        assertEquals("la vista nueva empieza centrada a 1×", AngleDetailViewport(), dialogViewport())
        rule.onNodeWithText(zoomText(1.0)).assertIsDisplayed()
        assertRendersColor(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG, COLOR_B)
        assertEquals("un fotograma, una carga (más la nueva), sin duplicados", 2, loads.get())
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Imagen ausente: aviso fuera del plegable; lecturas y marcas ocultas siempre visibles.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun laImagenAusenteSeAvisaFueraDelPlegableYLasLecturasYOcultosSiguenVisibles() {
        val frame = savedFrame(frameId = 11L)
        val state = SectionState(ROOT_A, frame)
        rule.setSectionContent(state) { AngleV2SavedImage.Absent }

        waitForText(strings(R.string.angle_multi_frame_frame_not_captured))
        rule.onNodeWithText(strings(R.string.angle_multi_frame_frame_not_captured)).assertIsDisplayed()
        assertNoCardImage()
        rule.onNodeWithText(expandLabel(frame)).assertDoesNotExist()

        // Mediciones y estado de ocultación siguen visibles sin abrir nada.
        rule.onNodeWithText(strings(R.string.angle_saved_marks_title, 1, 2)).assertIsDisplayed()
        rule.onNodeWithText(markLeft.name).assertIsDisplayed()
        rule.onNodeWithText(markRight.name).assertIsDisplayed()
        rule.onNodeWithText(strings(R.string.angle_hidden)).assertIsDisplayed()
        rule.onNodeWithText(degreesText(markLeft)).assertIsDisplayed()

        // El plegable empieza cerrado: las coordenadas no están.
        assertEquals(strings(R.string.angle_viewport_state_normal), technicalState())
        rule.onNodeWithText(pointsText(markLeft)).assertDoesNotExist()
        rule.onNodeWithText(pointsText(markRight)).assertDoesNotExist()

        // Al abrirlo, cada bloque de coordenadas lleva SU nombre justo encima.
        rule.onNodeWithTag(ANGLE_V2_SAVED_TECHNICAL_TOGGLE_TAG).performClick()
        rule.waitForIdle()
        assertEquals(strings(R.string.angle_viewport_state_expanded), technicalState())
        rule.onNodeWithText(pointsText(markLeft)).assertExists()
        rule.onNodeWithText(pointsText(markRight)).assertExists()
        assertEquals(2, rule.onAllNodesWithText(markLeft.name).fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText(markRight.name).fetchSemanticsNodes().size)
        assertMarkNameAdjacentToItsPoints(markLeft.name, pointsText(markLeft))
        assertMarkNameAdjacentToItsPoints(markRight.name, pointsText(markRight))

        // Cerrar oculta sólo los datos técnicos, no las lecturas.
        rule.onNodeWithTag(ANGLE_V2_SAVED_TECHNICAL_TOGGLE_TAG).performClick()
        rule.waitForIdle()
        assertEquals(strings(R.string.angle_viewport_state_normal), technicalState())
        rule.onNodeWithText(pointsText(markLeft)).assertDoesNotExist()
        rule.onNodeWithText(markLeft.name).assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Imagen corrupta en un fotograma declarado 1280×1: el aviso largo se ve COMPLETO.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun laImagenCorruptaDeUnFotogramaFinoMuestraElAvisoLargoCompletoYConservaLasLecturas() {
        val frame = savedFrame(frameId = 11L, imageWidth = 1280, imageHeight = 1)
        val state = SectionState(ROOT_A, frame)
        val errorText = strings(R.string.angle_v2_saved_image_unavailable)
        rule.setContent {
            OpenJumpTheme {
                Column(Modifier.fillMaxSize()) {
                    // Referencia del MISMO texto, con el mismo estilo y ancho que dentro de la
                    // tarjeta (mismos paddings de producción), sin restricción de aspecto: su
                    // altura es la altura natural del aviso completo.
                    Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                        Text(
                            stringResource(R.string.angle_v2_saved_image_unavailable),
                            style = OpenJumpTypes.Secondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.sm)
                                .testTag(REFERENCE_ERROR_TEXT_TAG),
                        )
                    }
                    AngleV2SavedFrameSection(
                        rootId = state.rootId.value,
                        frame = state.frame.value,
                        imageLoader = { AngleV2SavedImage.Corrupted },
                        shareEnabled = true,
                        onShare = {},
                    )
                }
            }
        }

        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(errorText).fetchSemanticsNodes().size == 2
        }
        rule.waitForIdle()
        val nodes = rule.onAllNodesWithText(errorText).fetchSemanticsNodes()
        val reference = checkNotNull(nodes.minByOrNull { it.boundsInRoot.top })
        val shown = checkNotNull(nodes.maxByOrNull { it.boundsInRoot.top })
        assertTrue(
            "el aviso real debe tener altura y estar dentro de la ventana: ${shown.boundsInRoot}",
            shown.size.height > 0 && shown.boundsInRoot.top >= 0f,
        )
        assertTrue(
            "altura del aviso=${shown.size.height}px frente a la natural=${reference.size.height}px: " +
                "un contenedor recortado o de proporción fija no puede igualarla",
            abs(shown.size.height - reference.size.height) <= 4,
        )
        // La leyenda del fotograma empieza DESPUÉS del aviso: la caja creció con su texto.
        val caption = rule.onNodeWithText(frameLabel(frame)).fetchSemanticsNode()
        assertTrue(
            "la leyenda (${caption.boundsInRoot.top}) debe quedar bajo el aviso completo " +
                "(${shown.boundsInRoot.bottom})",
            caption.boundsInRoot.top >= shown.boundsInRoot.bottom - 1f,
        )
        assertEquals(strings(R.string.angle_viewport_state_normal), technicalState())
        rule.onNodeWithText(markLeft.name).assertExists()
        rule.onNodeWithText(degreesText(markLeft)).assertExists()
        rule.onNodeWithText(strings(R.string.angle_saved_marks_title, 1, 2)).assertExists()
    }

    // ---------------------------------------------------------------------------------------------
    // 6b. Imagen VÁLIDA con aspecto extremo dentro de la lista real: la caja visible se acota.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun laImagenValidaMuyVerticalEnLaListaAcotaSuAltoYConservaElPieYLasMarcas() {
        // Aspecto declarado 1×1280 con un bitmap válido: si el alto se derivara sin límite del
        // aspecto, la tarjeta pediría cientos de miles de píxeles de alto dentro de la lista.
        val frame = savedFrame(frameId = 11L, imageWidth = 1, imageHeight = 1280)
        val tall = Bitmap.createBitmap(1, 1280, Bitmap.Config.ARGB_8888).apply { eraseColor(COLOR_A) }
        composeSectionInLazyList(frame) { AngleV2SavedImage.Available(tall) }

        // Si la medición desbordara las restricciones de Compose, la composición lanzaría y este
        // paso fallaría: la regresión es que una imagen VÁLIDA se puede pintar dentro de la lista.
        waitForCardImage()
        rule.waitForIdle()
        val density = rule.activity.resources.displayMetrics.density
        val image = rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertTrue(
            "el ancho de la imagen debe ser finito y positivo: ${image.size}",
            image.size.width > 0,
        )
        assertTrue(
            "el alto de la caja debe respetar el máximo acotado: ${image.size.height}px",
            image.size.height <= 480f * density + 1f,
        )
        assertTrue(
            "el alto de la caja no puede colapsar bajo el objetivo táctil: ${image.size.height}px",
            image.size.height >= 48f * density - 1f,
        )
        // La imagen sigue siendo la misma y con Fit se ve completa; su leyenda, sus marcas y sus
        // datos siguen existiendo y se alcanzan desplazando la lista (nada queda inaccesible).
        rule.onNodeWithText(frameLabel(frame)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(markLeft.name).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(degreesText(markLeft)).assertExists()
        assertEquals(strings(R.string.angle_viewport_state_normal), technicalState())
    }

    @Test
    fun laImagenValidaMuyApaisadaEnLaListaAcotaSuAltoYConservaLaLeyenda() {
        val frame = savedFrame(frameId = 12L, imageWidth = 1280, imageHeight = 1)
        val wide = Bitmap.createBitmap(1280, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(COLOR_B) }
        composeSectionInLazyList(frame) { AngleV2SavedImage.Available(wide) }

        waitForCardImage()
        rule.waitForIdle()
        val density = rule.activity.resources.displayMetrics.density
        val image = rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertTrue(
            "el ancho de la imagen debe ser finito y positivo: ${image.size}",
            image.size.width > 0,
        )
        assertTrue(
            "el alto de la caja debe respetar el máximo acotado: ${image.size.height}px",
            image.size.height <= 480f * density + 1f,
        )
        assertTrue(
            "la franja fina no puede quedar por debajo del mínimo táctil: ${image.size.height}px",
            image.size.height >= 48f * density - 1f,
        )
        rule.onNodeWithText(frameLabel(frame)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(markLeft.name).performScrollTo().assertIsDisplayed()
        assertEquals(strings(R.string.angle_viewport_state_normal), technicalState())
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Extracción no verificada/discrepante fuera del plegable y leyenda asignada.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun extraccionNoVerificadaYDiscrepanteSeVenFueraDelPlegableYLaLeyendaEsLaAsignada() {
        val unverified = savedFrame(frameId = 11L, assignedFrameIndex = 0, reportedFrameIndex = null)
        val mismatch = savedFrame(frameId = 12L, assignedFrameIndex = 1, reportedFrameIndex = 5)
        val state = SectionState(ROOT_A, unverified)
        val loads = AtomicInteger()
        rule.setSectionContent(state) {
            loads.incrementAndGet()
            AngleV2SavedImage.Unreadable
        }

        waitForText(strings(R.string.angle_v2_saved_image_unavailable))
        // Sin abrir el plegable: extracción no verificada e imagen ilegible se ven igual.
        rule.onNodeWithText(
            strings(R.string.angle_detail_extraction_unverified, unverified.displayFrameNumber),
        ).assertIsDisplayed()
        assertEquals(strings(R.string.angle_viewport_state_normal), technicalState())
        rule.onNodeWithText(markLeft.name).assertIsDisplayed()

        rule.runOnIdle { state.frame.value = mismatch }
        val mismatchText = strings(
            R.string.angle_detail_extraction_mismatch,
            angleSavedFrameDisplayNumber(5),
            mismatch.displayFrameNumber,
        )
        waitForText(mismatchText)
        rule.onNodeWithText(mismatchText).assertIsDisplayed()
        rule.onNodeWithText(strings(R.string.angle_v2_saved_image_unavailable)).assertIsDisplayed()
        // La leyenda sigue siendo el índice ASIGNADO, no el reportado por la extracción.
        rule.onNodeWithText(frameLabel(mismatch)).assertIsDisplayed()
        rule.onNodeWithText(strings(R.string.angle_saved_frame, angleSavedFrameDisplayNumber(5)))
            .assertDoesNotExist()
        assertEquals(strings(R.string.angle_viewport_state_normal), technicalState())
        assertEquals("cada fotograma se carga una vez", 2, loads.get())
    }

    // ---------------------------------------------------------------------------------------------
    // 8. Compartir: bloqueo respetado y callback/fotograma frescos; sin acciones de vídeo/edición.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun compartirRespetaElBloqueoYEntregaSoloElFotogramaFrescoConElCallbackActual() {
        val frameA = savedFrame(frameId = 11L)
        val frameB = savedFrame(frameId = 12L, assignedFrameIndex = 1)
        val state = SectionState(ROOT_A, frameA)
        state.shareEnabled.value = false
        val oldShares = Collections.synchronizedList(mutableListOf<Long>())
        val freshShares = Collections.synchronizedList(mutableListOf<AngleV2SavedDetailFrame>())
        state.onShare.value = { frame -> oldShares.add(frame.frameId) }
        val loads = AtomicInteger()
        rule.setSectionContent(state) { frame ->
            loads.incrementAndGet()
            AngleV2SavedImage.Available(if (frame.frameId == frameA.frameId) bitmapA else bitmapB)
        }

        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_A)
        rule.onNodeWithContentDescription(shareDescription(frameA)).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertEquals("con el bloqueo activo no se despacha nada", 0, oldShares.size)
        assertEquals(1, loads.get())

        // Callback y fotograma NUEVOS en la misma composición: se entrega sólo el fresco.
        rule.runOnIdle {
            state.shareEnabled.value = true
            state.onShare.value = { frame -> freshShares.add(frame) }
            state.frame.value = frameB
        }
        waitForCardImage()
        assertRendersColor(ANGLE_V2_SAVED_CARD_IMAGE_TAG, COLOR_B)
        rule.onNodeWithContentDescription(shareDescription(frameB)).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, freshShares.size)
        assertEquals("se comparte el fotograma exacto y fresco", frameB, freshShares.single())
        assertEquals("el callback anterior no debe recibir nada", 0, oldShares.size)
        assertEquals("compartir no recarga la imagen", 2, loads.get())
        rule.onNodeWithText(strings(R.string.angle_v2_saved_share_image)).assertExists()

        // El detalle es de sólo lectura: nada de editar ni de abrir el vídeo.
        assertEquals(
            0,
            rule.onAllNodesWithText(strings(R.string.angle_saved_edit)).fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            rule.onAllNodesWithText(strings(R.string.angle_mode_edit)).fetchSemanticsNodes().size,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 9a. Escala 1,5× en tema OSCURO: 48 dp por control y 56 dp de pie, sin salirse.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aEscalaDeLetra15EnTemaOscuroLosControlesConservan48dpYElPie56() {
        assertDialogAtFontScale(darkTheme = true)
    }

    // ---------------------------------------------------------------------------------------------
    // 9b. Escala 1,5× en tema CLARO: la misma comprobación, en su PROPIA prueba.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aEscalaDeLetra15EnTemaClaroLosControlesConservan48dpYElPie56() {
        assertDialogAtFontScale(darkTheme = false)
    }

    // ---------------------------------------------------------------------------------------------
    // 10. Gesto REAL de dos dedos (pinza + arrastre) sobre el diálogo de producción.
    // ---------------------------------------------------------------------------------------------

    /**
     * Pinza y arrastre de PUNTEROS REALES sobre la imagen del diálogo: el viewport publicado en la
     * semántica cambia y los PÍXELES del bitmap también (no sólo la matemática pura). Después, el
     * conmutador «Mover» abre y cierra su panel sin reiniciar el zoom: el encuadre se conserva y,
     * como mucho, se reacota el desplazamiento.
     */
    @Test
    fun dosDedosAmplianYArrastranSobreElBitmapYElPanelDeFlechasNoReiniciaElZoom() {
        val frame = savedFrame(frameId = 12L)
        composeSectionInProductionHost(frame, mutableStateOf(false)) {
            AngleV2SavedImage.Available(splitBitmap())
        }

        waitForCardImage()
        rule.onNodeWithText(expandLabel(frame)).performScrollTo().assertIsDisplayed().performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        assertEquals(AngleDetailViewport(), dialogViewport())
        val before = dialogPixels()

        // PINZA: dos punteros que se separan en horizontal sobre la imagen.
        rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).performTouchInput {
            val first = center - Offset(60f, 0f)
            val second = center + Offset(60f, 0f)
            down(0, first)
            down(1, second)
            moveTo(0, first - Offset(140f, 0f))
            moveTo(1, second + Offset(140f, 0f))
            up(0)
            up(1)
        }
        rule.waitForIdle()
        val zoomed = dialogViewport()
        assertTrue("la pinza debe ampliar de verdad: $zoomed", zoomed.zoom > 1f)
        assertTrue("la pinza no puede pasar del máximo: $zoomed", zoomed.zoom <= ANGLE_DETAIL_MAX_ZOOM + 0.001f)
        assertFalse(
            "ampliar debe cambiar los PÍXELES del bitmap, no sólo la semántica",
            before == dialogPixels(),
        )

        // ARRASTRE de un dedo: se aplica al bitmap y respeta el tope de la imagen ampliada.
        val container = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).fetchSemanticsNode()
        val width = container.size.width.toFloat()
        val height = container.size.height.toFloat()
        rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).performTouchInput {
            down(center)
            moveTo(center + Offset(width, 0f))
            up()
        }
        rule.waitForIdle()
        val dragged = dialogViewport()
        assertTrue("el arrastre debe mover la vista: $dragged (antes $zoomed)", dragged.panX > zoomed.panX)
        val maxPanX = angleDetailMaxPan(
            zoom = dragged.zoom,
            containerExtent = width,
            fittedExtent = angleDetailFittedWidth(width, height, frame.imageWidth, frame.imageHeight),
        )
        assertTrue(
            "el arrastre no puede pasar del tope real: $dragged tope=$maxPanX",
            dragged.panX <= maxPanX + 0.5f,
        )

        // Panel de flechas: abrir y cerrar NO reinicia el zoom ni recentra la vista.
        val beforeToggle = dialogViewport()
        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_move)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_PAN_PANEL_TAG).assertIsDisplayed()
        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_move)).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(ANGLE_V2_SAVED_DIALOG_PAN_PANEL_TAG).assertCountEquals(0)
        val afterToggle = dialogViewport()
        assertEquals(
            "abrir/cerrar el panel no puede reiniciar el zoom",
            beforeToggle.zoom,
            afterToggle.zoom,
            0f,
        )
        assertEquals(
            "abrir/cerrar el panel sólo puede reacotar el desplazamiento",
            beforeToggle.panX,
            afterToggle.panX,
            0.5f,
        )
        assertEquals("abrir/cerrar el panel no recentra en vertical", beforeToggle.panY, afterToggle.panY, 0.5f)
    }

    // ---------------------------------------------------------------------------------------------
    // 11. Ráfaga de arrastres SIN recomposición intermedia (regresión material de Review 1).
    // ---------------------------------------------------------------------------------------------

    /**
     * Cuatro eventos de arrastre seguidos, sin recomposición entre ellos (reloj manual): la vista
     * aplicada acumula los CUATRO deltas.
     *
     * Con `rememberUpdatedState` el manejador leía la copia de la última composición; dentro de una
     * ráfaga todos los eventos partían del mismo valor viejo y sólo sobrevivía el último paso. El
     * primero cruza el umbral táctil (128 px) y los otros tres suman 32 px cada uno, así que el
     * estado aplicado debe rondar 224 px —no 32— y los PÍXELES tienen que acompañarlo.
     */
    @Test
    fun unaRafagaDeArrastresSinRecomposicionAcumulaLosDeltasYNoSoloElUltimo() {
        val frame = savedFrame(frameId = 13L)
        composeSectionInProductionHost(frame, mutableStateOf(false)) {
            AngleV2SavedImage.Available(splitBitmap())
        }

        waitForCardImage()
        rule.onNodeWithText(expandLabel(frame)).performScrollTo().assertIsDisplayed().performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        zoomToTwo()

        val container = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).fetchSemanticsNode()
        val width = container.size.width.toFloat()
        val height = container.size.height.toFloat()
        val maxPanX = angleDetailMaxPan(
            zoom = 2f,
            containerExtent = width,
            fittedExtent = angleDetailFittedWidth(width, height, frame.imageWidth, frame.imageHeight),
        )
        assertTrue(
            "hace falta holgura horizontal real para medir la acumulación: tope $maxPanX px",
            maxPanX > BURST_TOTAL_PX + 40f,
        )

        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).performTouchInput {
                down(center)
                moveBy(Offset(BURST_FIRST_PX, 0f))
                repeat(3) { moveBy(Offset(BURST_STEP_PX, 0f)) }
                up()
            }
            // Ni una composición entre eventos: el primer fotograma llega DESPUÉS de la ráfaga.
            rule.mainClock.advanceTimeByFrame()
            val applied = dialogViewport()
            assertEquals("la ráfaga no puede cambiar el zoom", 2f, applied.zoom, 0.001f)
            assertTrue(
                "la ráfaga debe acumular los cuatro deltas (~$BURST_TOTAL_PX px), no el último " +
                    "paso (~$BURST_STEP_PX px): $applied",
                applied.panX >= BURST_TOTAL_PX - BURST_QUANTIZATION_PX &&
                    applied.panX <= BURST_TOTAL_PX + BURST_QUANTIZATION_PX,
            )
            // Píxeles del PRIMER fotograma: la frontera A/B del bitmap está en centro+panX, así que
            // en centro+128 debe verse la mitad izquierda. Con el último paso (32 px) se vería la
            // derecha: la sonda distingue la acumulación real de un solo delta.
            val pixels = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG)
                .captureToImage()
                .toPixelMap()
            val probe = pixels.width / 2 + BURST_FIRST_PX.toInt()
            assertTrue("la sonda debe caer dentro del bitmap: $probe de ${pixels.width}", probe < pixels.width)
            assertColorNear(
                "píxeles del primer fotograma tras la ráfaga (x=$probe)",
                COLOR_A,
                pixels[probe, pixels.height / 2].toArgb(),
            )
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 12. Primer fotograma tras cambiar el área disponible (regresión material de Review 1).
    // ---------------------------------------------------------------------------------------------

    /**
     * La caja de la imagen se mide de nuevo al plegar el panel de flechas: el fotograma que se dibuja
     * en el acto ya lleva la vista reacotada, sin franja vacía en el borde.
     *
     * Imagen CUADRADA a 2× con el panel abierto (caja más baja): el desplazamiento vertical llega a su
     * tope. Al plegarlo la caja crece y el tope legal BAJA; con el clamp diferido a un efecto, el
     * primer fotograma se pintaba con el desplazamiento viejo y dejaba un hueco del color del fondo
     * arriba (justo la mitad de lo que creció la caja). Se comprueba con el reloj manual: UN
     * fotograma, sin esperar a que «asiente» el segundo.
     */
    @Test
    fun elPrimerFotogramaTrasPlegarElPanelYaVaReacotadoYSinFranjaVacia() {
        val frame = savedFrame(frameId = 14L, imageWidth = 64, imageHeight = 64)
        composeSectionInProductionHost(frame, mutableStateOf(false)) {
            AngleV2SavedImage.Available(squareBitmap(COLOR_A))
        }

        waitForCardImage()
        rule.onNodeWithText(expandLabel(frame)).performScrollTo().assertIsDisplayed().performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        zoomToTwo()
        openPanPanel()
        val small = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).fetchSemanticsNode().size
        val smallW = small.width.toFloat()
        val smallH = small.height.toFloat()
        val smallMaxPanY = angleDetailMaxPan(
            zoom = 2f,
            containerExtent = smallH,
            fittedExtent = angleDetailFittedHeight(smallW, smallH, frame.imageWidth, frame.imageHeight),
        )
        assertTrue(
            "la caja con el panel abierto debe dejar holgura vertical real: tope $smallMaxPanY px",
            smallMaxPanY > 24f,
        )

        // Desplazamiento vertical REAL al tope: un arrastre largo se acota al máximo legal.
        rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).performTouchInput {
            down(center)
            moveTo(center + Offset(0f, smallH))
            up()
        }
        rule.waitForIdle()
        assertEquals(
            "el arrastre debe llegar al tope real de la caja baja",
            smallMaxPanY,
            dialogViewport().panY,
            1f,
        )

        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_move)).performClick()
            // UN solo fotograma: en él se mide la caja nueva y se dibuja con lo que haya.
            rule.mainClock.advanceTimeByFrame()
            val pixels = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG)
                .captureToImage()
                .toPixelMap()
            val grown = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).fetchSemanticsNode().size
            assertTrue(
                "la caja debe haber crecido en este mismo fotograma: ${small.height} -> ${grown.height}",
                grown.height > small.height + 8,
            )
            val grownMaxPanY = angleDetailMaxPan(
                zoom = 2f,
                containerExtent = grown.height.toFloat(),
                fittedExtent = angleDetailFittedHeight(
                    grown.width.toFloat(),
                    grown.height.toFloat(),
                    frame.imageWidth,
                    frame.imageHeight,
                ),
            )
            assertTrue(
                "el tope legal vertical debe BAJAR al crecer la caja: $smallMaxPanY -> $grownMaxPanY",
                grownMaxPanY < smallMaxPanY - 24f,
            )
            listOf(pixels.width / 4, pixels.width / 2, pixels.width * 3 / 4).forEach { x ->
                assertColorNear(
                    "el primer fotograma tras crecer la caja no puede dejar hueco arriba (x=$x)",
                    COLOR_A,
                    pixels[x, TOP_EDGE_PROBE_Y].toArgb(),
                )
            }
            // El estado publicado se confirma al asentar; el clamp ya se aplicó en la medida del
            // fotograma anterior, que es el que se acaba de mirar en píxeles.
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val applied = dialogViewport()
            assertEquals("el zoom se conserva al crecer la caja", 2f, applied.zoom, 0.001f)
            assertEquals(
                "la vista aplicada debe ser el clamp de la caja NUEVA",
                grownMaxPanY,
                applied.panY,
                1f,
            )
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    /** Dos pasos reales del botón de ampliar: 1× -> 2× con el paso de la fuente. */
    private fun zoomToTwo() {
        repeat(2) {
            rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_zoom_in)).performClick()
        }
        rule.waitForIdle()
        assertEquals("dos pasos de zoom deben dejar 2×", 2f, dialogViewport().zoom, 0.001f)
    }

    /**
     * Cuerpo COMÚN de 9a/9b, ya a 1,5× y en un solo tema.
     *
     * Antes era un único `@Test` que cambiaba de oscuro a claro: si la fase oscura fallaba, la clara
     * NUNCA se ejecutaba y el tema claro quedaba sin cubrir. Con dos pruebas independientes cada
     * tema se comprueba por separado conservando TODAS las aserciones (pie 56 dp, OCHO controles a
     * 48 dp en ambos ejes dentro de la ventana y del pie, imagen visible, zoom 4 real con sus cuatro
     * topes de desplazamiento, reinicio, «Cerrar», reapertura y Back del sistema).
     */
    private fun assertDialogAtFontScale(darkTheme: Boolean) {
        val frame = savedFrame(frameId = 11L)
        val dark = mutableStateOf(darkTheme)
        composeSectionInProductionHost(frame, dark) { AngleV2SavedImage.Available(bitmapA) }

        waitForCardImage()
        // Mismo camino que la pantalla real: Scaffold -> LazyColumn -> ítem con clave de petición.
        rule.onNodeWithText(expandLabel(frame))
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        assertDialogControlsAtFontScale()
        assertDialogFitsIndependentNativeWindow()
        rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).assertIsDisplayed()

        // Zoom 4 REAL: los cuatro desplazamientos se observan sobre la transformación aplicada al
        // bitmap (viewport publicado), con su signo y su tope real, no sobre una copia de la prueba.
        repeat(6) { rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_zoom_in)).performClick() }
        rule.waitForIdle()
        assertEquals(ANGLE_DETAIL_MAX_ZOOM, dialogViewport().zoom, 0.001f)
        rule.onNodeWithText(zoomText(ANGLE_DETAIL_MAX_ZOOM.toDouble())).assertIsDisplayed()

        val image = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).fetchSemanticsNode()
        val containerWidth = image.size.width.toFloat()
        val containerHeight = image.size.height.toFloat()
        val maxPanX = angleDetailMaxPan(
            zoom = ANGLE_DETAIL_MAX_ZOOM,
            containerExtent = containerWidth,
            fittedExtent = angleDetailFittedWidth(
                containerWidth,
                containerHeight,
                frame.imageWidth,
                frame.imageHeight,
            ),
        )
        val maxPanY = angleDetailMaxPan(
            zoom = ANGLE_DETAIL_MAX_ZOOM,
            containerExtent = containerHeight,
            fittedExtent = angleDetailFittedHeight(
                containerWidth,
                containerHeight,
                frame.imageWidth,
                frame.imageHeight,
            ),
        )
        val stepX = angleDetailPanStep(containerWidth)
        val stepY = angleDetailPanStep(containerHeight)
        assertTrue(
            "a zoom 4 los dos ejes deben poder moverse (maxPanX=$maxPanX, maxPanY=$maxPanY)",
            maxPanX > 0f && maxPanY > 0f,
        )
        assertTrue("el paso de desplazamiento no puede ser cero", stepX > 0f && stepY > 0f)
        val rightClicks = ceil(maxPanX / stepX).toInt() + 1
        val leftClicks = rightClicks * 2
        val upClicks = ceil(maxPanY / stepY).toInt() + 1
        val downClicks = upClicks * 2

        assertEquals(AngleDetailViewport(zoom = ANGLE_DETAIL_MAX_ZOOM), dialogViewport())

        clickPan(R.string.angle_polish_control_pan_right)
        rule.waitForIdle()
        assertTrue(
            "desplazar a la derecha debe avanzar panX>0 (viewport=${dialogViewport()})",
            dialogViewport().panX > 0f,
        )
        repeat(rightClicks) {
            clickPan(R.string.angle_polish_control_pan_right)
        }
        rule.waitForIdle()
        assertEquals(
            "el desplazamiento derecho se acota al máximo real",
            maxPanX,
            dialogViewport().panX,
            0.5f,
        )

        clickPan(R.string.angle_polish_control_pan_left)
        rule.waitForIdle()
        assertTrue(
            "desplazar a la izquierda debe reducir panX (viewport=${dialogViewport()})",
            dialogViewport().panX < maxPanX,
        )
        repeat(leftClicks) {
            clickPan(R.string.angle_polish_control_pan_left)
        }
        rule.waitForIdle()
        assertEquals(
            "el desplazamiento izquierdo se acota al máximo real",
            -maxPanX,
            dialogViewport().panX,
            0.5f,
        )

        clickPan(R.string.angle_polish_control_pan_up)
        rule.waitForIdle()
        assertTrue(
            "desplazar arriba debe llevar panY<0 (viewport=${dialogViewport()})",
            dialogViewport().panY < 0f,
        )
        repeat(upClicks) {
            clickPan(R.string.angle_polish_control_pan_up)
        }
        rule.waitForIdle()
        assertEquals(
            "el desplazamiento arriba se acota al máximo real",
            -maxPanY,
            dialogViewport().panY,
            0.5f,
        )

        val beforeDown = dialogViewport().panY
        clickPan(R.string.angle_polish_control_pan_down)
        rule.waitForIdle()
        assertTrue(
            "desplazar abajo debe aumentar panY (antes=$beforeDown, ahora=${dialogViewport()})",
            dialogViewport().panY > beforeDown,
        )
        repeat(downClicks) {
            clickPan(R.string.angle_polish_control_pan_down)
        }
        rule.waitForIdle()
        assertEquals(
            "el desplazamiento abajo se acota al máximo real",
            maxPanY,
            dialogViewport().panY,
            0.5f,
        )

        // Restablecer devuelve 1× centrado y la etiqueta lo dice.
        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_fit)).performClick()
        rule.waitForIdle()
        assertEquals("reiniciar devuelve 1× centrado", AngleDetailViewport(), dialogViewport())
        rule.onNodeWithText(zoomText(1.0)).assertIsDisplayed()

        // «Cerrar» cierra sólo el diálogo; reabrir y Back del sistema conserva el caso existente.
        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_close)).performClick()
        waitForTagGone(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertExists()
        rule.onNodeWithText(expandLabel(frame)).performScrollTo().performClick()
        waitForTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        assertEquals("reabrir empieza centrado a 1×", AngleDetailViewport(), dialogViewport())
        pressSystemBack()
        waitForTagGone(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
        rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertExists()
    }

    // ---------------------------------------------------------------------------------------------
    // Utilidades de composición y de aserción
    // ---------------------------------------------------------------------------------------------

    /** Estado mutable de la prueba que alimenta la sección real por sus parámetros públicos. */
    private class SectionState(initialRootId: Long, initialFrame: AngleV2SavedDetailFrame) {
        val rootId: MutableState<Long> = mutableStateOf(initialRootId)
        val frame: MutableState<AngleV2SavedDetailFrame> = mutableStateOf(initialFrame)
        val shareEnabled: MutableState<Boolean> = mutableStateOf(true)
        val onShare: MutableState<(AngleV2SavedDetailFrame) -> Unit> = mutableStateOf({})
    }

    private fun ComposeContentTestRule.setSectionContent(
        state: SectionState,
        loader: suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage,
    ) {
        setContent {
            OpenJumpTheme {
                AngleV2SavedFrameSection(
                    rootId = state.rootId.value,
                    frame = state.frame.value,
                    imageLoader = loader,
                    shareEnabled = state.shareEnabled.value,
                    onShare = { frame -> state.onShare.value(frame) },
                )
            }
        }
    }

    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    /** Imagen CUADRADA (aspecto 1:1): el clamp vertical y el borde superior se pueden medir exactos. */
    private fun squareBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_WIDTH, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    /**
     * Sección REAL dentro de una `LazyColumn` real: el escenario de producción donde el alto de un
     * ítem es ilimitado y un alto derivado del aspecto extremo no es representable.
     */
    private fun composeSectionInLazyList(
        frame: AngleV2SavedDetailFrame,
        loader: suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage,
    ) {
        val state = SectionState(ROOT_A, frame)
        rule.setContent {
            OpenJumpTheme {
                LazyColumn(Modifier.fillMaxSize().testTag(LAZY_LIST_TAG)) {
                    item {
                        AngleV2SavedFrameSection(
                            rootId = state.rootId.value,
                            frame = state.frame.value,
                            imageLoader = loader,
                            shareEnabled = true,
                            onShare = {},
                        )
                    }
                }
            }
        }
    }

    /**
     * Sección REAL dentro de la MISMA estructura que la pantalla de producción: `Scaffold`,
     * `LazyColumn` con el `contentPadding` de pantalla, `items` con clave raíz+fotograma y
     * `key(imageRequest)` alrededor de la sección. El tema y la escala de letra se fijan fuera para
     * poder alternar claro/oscuro sin desmontar el host.
     */
    private fun composeSectionInProductionHost(
        frame: AngleV2SavedDetailFrame,
        dark: MutableState<Boolean>,
        loader: suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage,
    ) {
        val frames = listOf(frame)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.5f),
            ) {
                OpenJumpTheme(darkTheme = dark.value) {
                    Scaffold { padding ->
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .testTag(PRODUCTION_LIST_TAG),
                            contentPadding = PaddingValues(
                                start = Spacing.screenHorizontal,
                                end = Spacing.screenHorizontal,
                                top = Spacing.md,
                                bottom = Spacing.xl,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            items(frames, key = { "${ROOT_A}:${it.frameId}" }) { item ->
                                key(item.imageRequest(ROOT_A)) {
                                    AngleV2SavedFrameSection(
                                        rootId = ROOT_A,
                                        frame = item,
                                        imageLoader = loader,
                                        shareEnabled = true,
                                        onShare = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun savedFrame(
        frameId: Long,
        assignedFrameIndex: Int = 0,
        reportedFrameIndex: Int? = 0,
        imageKey: String = "angle-images/frame-$frameId.png",
        imageWidth: Int = BITMAP_WIDTH,
        imageHeight: Int = BITMAP_HEIGHT,
        imageByteCount: Long = 2048L,
        imageSha256: String = SHA_A,
    ): AngleV2SavedDetailFrame = AngleV2SavedDetailFrame(
        ordinal = 0,
        frameId = frameId,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = reportedFrameIndex,
        presentationWidth = 1920,
        presentationHeight = 1080,
        rotationDegrees = 0,
        imageKey = imageKey,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        imageByteCount = imageByteCount,
        imageSha256 = imageSha256,
        imageMimeType = "image/png",
        marks = listOf(markLeft, markRight),
    )

    private fun gateFor(frameId: Long): CompletableDeferred<AngleV2SavedImage> =
        synchronized(gates) { gates.getOrPut(frameId) { CompletableDeferred() } }

    private fun requestGate(
        request: AngleV2SavedImageRequest,
    ): CompletableDeferred<AngleV2SavedImage> =
        synchronized(requestGates) { requestGates.getOrPut(request) { CompletableDeferred() } }

    private fun releaseRequest(request: AngleV2SavedImageRequest, image: AngleV2SavedImage) {
        requestGate(request).complete(image)
    }

    private val locale: Locale
        get() = rule.activity.resources.configuration.locales[0]

    private fun strings(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private fun frameLabel(frame: AngleV2SavedDetailFrame): String =
        strings(R.string.angle_saved_frame, frame.displayFrameNumber)

    private fun expandLabel(frame: AngleV2SavedDetailFrame): String =
        strings(R.string.angle_detail_expand_frame, frame.displayFrameNumber)

    private fun shareDescription(frame: AngleV2SavedDetailFrame): String =
        strings(R.string.angle_v2_saved_share_frame_image, frame.displayFrameNumber)

    private fun pendingText(): String = strings(R.string.angle_multi_frame_image_pending)

    private fun pointsText(mark: AngleSavedDetailMark): String = strings(
        R.string.angle_saved_points,
        formatAngleSavedPoint(mark.pointA.x, locale),
        formatAngleSavedPoint(mark.pointA.y, locale),
        formatAngleSavedPoint(mark.pointB.x, locale),
        formatAngleSavedPoint(mark.pointB.y, locale),
        formatAngleSavedPoint(mark.pointC.x, locale),
        formatAngleSavedPoint(mark.pointC.y, locale),
    )

    private fun degreesText(mark: AngleSavedDetailMark): String {
        val degrees = requireNotNull(mark.degrees) { "la marca de prueba debe ser medible" }
        return strings(R.string.angle_degrees_value, formatAngleSavedDegrees(degrees, locale))
    }

    private fun zoomText(value: Double): String {
        val formatted = java.text.NumberFormat.getNumberInstance(locale).run {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            format(value)
        }
        return strings(R.string.angle_zoom_value, formatted)
    }

    private fun technicalState(): String =
        rule.onNodeWithTag(ANGLE_V2_SAVED_TECHNICAL_TOGGLE_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]

    private fun dialogViewport(): AngleDetailViewport =
        rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG)
            .fetchSemanticsNode()
            .config[AngleDetailViewportSemanticsKey]

    private fun waitForTag(tag: String) {
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun waitForTagGone(tag: String) {
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
        rule.waitForIdle()
    }

    private fun waitForText(text: String) {
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun waitForCardImage() = waitForTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG)

    private fun assertNoCardImage() {
        assertEquals(
            "no debe pintarse ninguna imagen mientras la petición está pendiente o falló",
            0,
            rule.onAllNodesWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).fetchSemanticsNodes().size,
        )
    }

    /** Bitmap NO uniforme (mitad izquierda/derecha): zoom y arrastre cambian píxeles de verdad. */
    private fun splitBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(COLOR_A)
        canvas.drawRect(
            android.graphics.RectF(BITMAP_WIDTH / 2f, 0f, BITMAP_WIDTH.toFloat(), BITMAP_HEIGHT.toFloat()),
            android.graphics.Paint().apply { color = COLOR_B },
        )
        return bitmap
    }

    /** Muestra reducida de los píxeles REALES del bitmap dentro del diálogo. */
    private fun dialogPixels(): List<Int> {
        val pixels = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG).captureToImage().toPixelMap()
        val step = maxOf(1, minOf(pixels.width, pixels.height) / 32)
        return buildList {
            var y = 0
            while (y < pixels.height) {
                var x = 0
                while (x < pixels.width) {
                    add(pixels[x, y].toArgb())
                    x += step
                }
                y += step
            }
        }
    }

    private fun assertRendersColor(tag: String, expected: Int) {
        val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertColorNear(tag, expected, pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }

    /** Color de un punto concreto, con la misma tolerancia por canal que [assertRendersColor]. */
    private fun assertColorNear(what: String, expected: Int, actual: Int) {
        val red = abs(((expected shr 16) and 0xFF) - ((actual shr 16) and 0xFF))
        val green = abs(((expected shr 8) and 0xFF) - ((actual shr 8) and 0xFF))
        val blue = abs((expected and 0xFF) - (actual and 0xFF))
        assertTrue(
            "color renderizado distinto en $what: esperado #${hex(expected)} real #${hex(actual)}",
            red <= COLOR_TOLERANCE && green <= COLOR_TOLERANCE && blue <= COLOR_TOLERANCE,
        )
    }

    private fun hex(color: Int): String = Integer.toHexString(color)

    /** Nodo del nombre del plegable justo encima de las coordenadas de esa misma marca. */
    private fun assertMarkNameAdjacentToItsPoints(name: String, points: String) {
        val nameNodes = rule.onAllNodesWithText(name).fetchSemanticsNodes()
        val pointsNode = rule.onNodeWithText(points).fetchSemanticsNode()
        val closestAbove = nameNodes
            .filter { it.boundsInRoot.bottom <= pointsNode.boundsInRoot.top + 1f }
            .maxByOrNull { it.boundsInRoot.top }
        assertTrue(
            "el nombre de la marca debe estar encima de sus coordenadas ($name / $points)",
            closestAbove != null,
        )
        val gap = pointsNode.boundsInRoot.top - requireNotNull(closestAbove).boundsInRoot.bottom
        assertTrue(
            "el nombre debe quedar pegado a sus coordenadas (hueco ${gap}px)",
            gap <= 24f * rule.activity.resources.displayMetrics.density,
        )
    }

    private fun assertDialogControlTargets() {
        rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_FOOTER_TAG).assertHeightIsAtLeast(56.dp)
        rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_TOPBAR_TAG).assertIsDisplayed()
        // El cierre vive en la barra superior del diálogo: 48 dp reales en ambos ejes.
        rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_close))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        openPanPanel()
        (DIALOG_FOOTER_CONTROL_STRINGS + DIALOG_PAN_CONTROL_STRINGS).forEach { id ->
            rule.onNodeWithContentDescription(strings(id))
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    /** Revela el panel de flechas si aún está cerrado (el conmutador «Mover» anuncia su estado). */
    private fun openPanPanel() {
        val arrows = rule.onAllNodesWithContentDescription(
            strings(R.string.angle_polish_control_pan_left),
        ).fetchSemanticsNodes()
        if (arrows.isEmpty()) {
            rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_move)).performClick()
            rule.waitForIdle()
        }
    }

    /** Pulsa una flecha de desplazamiento revelando antes su panel: la vía sin gestos. */
    private fun clickPan(label: Int) {
        openPanPanel()
        rule.onNodeWithContentDescription(strings(label)).performClick()
        rule.waitForIdle()
    }

    /**
     * Controles a 1,5×: los NUEVE se ven, están habilitados y miden al menos 48 dp en AMBOS ejes.
     *
     * El cierre se comprueba contra la BARRA SUPERIOR (y fuera del pie); los demás, contra el pie.
     * Cada control debe caber en la ventana del diálogo y en su propio contenedor. Un pie con hijos
     * a 0×0, recortados o con el cierre dentro ya no puede pasar esta comprobación.
     */
    private fun assertDialogControlsAtFontScale() {
        val density = rule.activity.resources.displayMetrics.density
        val dialog = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val topBar = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_TOPBAR_TAG)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val footer = rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_FOOTER_TAG)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertTrue(
            "el pie debe medir al menos 56 dp a 1,5×: ${footer.size.height}px",
            footer.size.height >= 56f * density - 1f,
        )
        assertBoundsInside("el pie", footer.boundsInWindow, dialog.boundsInWindow)
        assertBoundsInside("la barra superior", topBar.boundsInWindow, dialog.boundsInWindow)
        val close = rule.onNodeWithContentDescription(strings(R.string.angle_polish_control_close))
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .fetchSemanticsNode()
        assertTrue(
            "el cierre debe medir al menos 48 dp en ambos ejes a 1,5×: ${close.size}",
            close.size.width >= 48f * density - 1f && close.size.height >= 48f * density - 1f,
        )
        assertBoundsInside("el cierre", close.boundsInWindow, dialog.boundsInWindow)
        assertBoundsInside("el cierre", close.boundsInWindow, topBar.boundsInWindow)
        assertTrue(
            "el cierre pertenece a la barra superior, no al pie: ${close.boundsInRoot} " +
                "frente a ${footer.boundsInRoot}",
            close.boundsInRoot.bottom <= footer.boundsInRoot.top + 1f,
        )
        openPanPanel()
        (DIALOG_FOOTER_CONTROL_STRINGS + DIALOG_PAN_CONTROL_STRINGS).forEach { id ->
            assertControlAtFontScale(id, dialog, footer)
        }
    }

    /** Un control por su descripción REAL de icono, con 48 dp y bounds dentro de su contenedor. */
    private fun assertControlAtFontScale(id: Int, dialog: SemanticsNode, container: SemanticsNode) {
        val density = rule.activity.resources.displayMetrics.density
        val label = strings(id)
        val node = rule.onNodeWithContentDescription(label)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .fetchSemanticsNode()
        assertTrue(
            "el control $label debe medir al menos 48 dp de ancho a 1,5×: ${node.size.width}px",
            node.size.width >= 48f * density - 1f,
        )
        assertTrue(
            "el control $label debe medir al menos 48 dp de alto a 1,5×: ${node.size.height}px",
            node.size.height >= 48f * density - 1f,
        )
        assertBoundsInside("el control $label", node.boundsInWindow, dialog.boundsInWindow)
        assertBoundsInside("el control $label", node.boundsInWindow, container.boundsInWindow)
        assertTrue(
            "el control $label no debe salirse de su contenedor por abajo",
            node.boundsInRoot.bottom <= container.boundsInRoot.bottom + 1f,
        )
        assertTrue(
            "el control $label no debe salirse de su contenedor por la derecha",
            node.boundsInRoot.right <= container.boundsInRoot.right + 1f,
        )
        assertTrue(
            "el control $label no debe salirse de su contenedor por la izquierda",
            node.boundsInRoot.left >= container.boundsInRoot.left - 1f,
        )
    }

    private fun assertBoundsInside(label: String, inner: Rect, container: Rect) {
        assertTrue(
            "$label debe quedar dentro de la ventana del diálogo: $inner frente a $container",
            inner.left >= container.left - 1f &&
                inner.top >= container.top - 1f &&
                inner.right <= container.right + 1f &&
                inner.bottom <= container.bottom + 1f,
        )
    }

    /**
     * Cruce INDEPENDIENTE del tamaño REAL de la ventana del diálogo con el marco visible que concede
     * el sistema, leído del árbol nativo de ventanas (no de las cotas de Compose, ni de un valor
     * esperado de la propia prueba).
     *
     * API 29+: `WindowInspector.getGlobalWindowViews()` expone los decor de las ventanas vivas; se
     * selecciona el del DIÁLOGO (visible, distinto del decor de la Activity) y se comparan su tamaño
     * y su posición con su propio `getWindowVisibleDisplayFrame`. Después, TODOS los nodos reales
     * (raíz, imagen, los ocho botones y sus textos) se convierten de coordenadas de ventana a
     * coordenadas de pantalla con el origen del decor seleccionado y deben caber en ese marco. Si la
     * selección de ventana no es inequívoca, la prueba falla en vez de elegir la Activity. En API
     * anteriores se conservan las aserciones heredadas y no se añade nada (sin `Ignore`).
     */
    private fun assertDialogFitsIndependentNativeWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var candidates = 0
        var decorWidth = 0
        var decorHeight = 0
        var decorX = 0
        var decorY = 0
        var visibleFrame = AndroidRect()
        var selectedDecor: View? = null
        rule.runOnIdle {
            val activityDecor = rule.activity.window.decorView
            val windows = WindowInspector.getGlobalWindowViews().filter { view ->
                view !== activityDecor && view.isShown && view.width > 0 && view.height > 0
            }
            candidates = windows.size
            val decor = windows.singleOrNull() ?: return@runOnIdle
            selectedDecor = decor
            val frame = AndroidRect()
            decor.getWindowVisibleDisplayFrame(frame)
            visibleFrame = frame
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            decorX = location[0]
            decorY = location[1]
            decorWidth = decor.width
            decorHeight = decor.height
        }
        assertTrue(
            "debe haber EXACTAMENTE una ventana de diálogo visible distinta de la Activity; " +
                "ventanas candidatas=$candidates",
            candidates == 1,
        )
        val decor = requireNotNull(selectedDecor) {
            "no se pudo seleccionar el decor visible del diálogo (candidatos=$candidates)"
        }
        assertTrue(
            "el marco visible nativo del diálogo debe ser positivo: $visibleFrame",
            visibleFrame.width() > 0 && visibleFrame.height() > 0,
        )
        assertTrue(
            "el decor real del diálogo (${decorWidth}x$decorHeight en $decorX,$decorY) no puede " +
                "superar el marco visible nativo ($visibleFrame) por más de 1 px",
            decorHeight <= visibleFrame.height() + 1 && decorWidth <= visibleFrame.width() + 1,
        )
        assertTrue(
            "el decor del diálogo debe empezar DENTRO del marco visible nativo, no encima de la " +
                "barra: onScreen=$decorX,$decorY marco=$visibleFrame",
            decorY >= visibleFrame.top - 1 && decorX >= visibleFrame.left - 1,
        )
        assertTrue(
            "el decor del diálogo no puede terminar fuera del marco visible nativo: " +
                "onScreen=$decorY+$decorHeight marco=${visibleFrame.bottom}",
            decorY + decorHeight <= visibleFrame.bottom + 1,
        )

        val windowBounds = buildList {
            add(
                "raíz del diálogo" to rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG)
                    .fetchSemanticsNode().boundsInWindow,
            )
            add(
                "imagen del diálogo" to rule.onNodeWithTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG)
                    .fetchSemanticsNode().boundsInWindow,
            )
            openPanPanel()
            DIALOG_CONTROL_STRINGS.forEach { id ->
                val label = strings(id)
                add("control $label" to rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInWindow)
                val controlNodes = rule.onAllNodesWithContentDescription(label, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                assertTrue(
                    "la etiqueta del control $label debe existir una sola vez: ${controlNodes.size}",
                    controlNodes.size == 1,
                )
            }
        }
        windowBounds.forEach { (label, bounds) ->
            val screenLeft = decorX + bounds.left
            val screenTop = decorY + bounds.top
            val screenRight = decorX + bounds.right
            val screenBottom = decorY + bounds.bottom
            assertTrue(
                "$label no puede tener bounds vacíos: ventana=$bounds (decor=$decor)",
                bounds.width > 0f && bounds.height > 0f,
            )
            assertTrue(
                "$label debe caber en el marco visible nativo del sistema ($visibleFrame): " +
                    "pantalla=($screenLeft,$screenTop)-($screenRight,$screenBottom) " +
                    "decor=$decor",
                screenTop >= visibleFrame.top - 1 &&
                    screenBottom <= visibleFrame.bottom + 1 &&
                    screenLeft >= visibleFrame.left - 1 &&
                    screenRight <= visibleFrame.right + 1,
            )
        }
    }

    /**
     * Back del SISTEMA (no un botón de la UI): el evento llega a la ventana en foco, que es la del
     * diálogo, igual que en el dispositivo.
     */
    private fun pressSystemBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private companion object {
        const val ROOT_A = 7L
        const val BITMAP_WIDTH = 64
        const val BITMAP_HEIGHT = 48
        const val TIMEOUT_MILLIS = 10_000L
        const val COLOR_TOLERANCE = 8
        const val DEGREES_DELTA = 1e-6

        /** Primer movimiento de la ráfaga: cruza el umbral táctil y ya se entrega como arrastre. */
        const val BURST_FIRST_PX = 128f

        /** Cada uno de los tres movimientos siguientes dentro de la misma ráfaga. */
        const val BURST_STEP_PX = 32f

        /** Acumulado esperado (128 + 3×32) frente al único último paso. */
        const val BURST_TOTAL_PX = BURST_FIRST_PX + 3 * BURST_STEP_PX

        /** Holgura por si la plataforma descuenta el umbral táctil del primer delta entregado. */
        const val BURST_QUANTIZATION_PX = 44f

        /** Fila del borde superior donde se comprueba que no queda hueco del fondo. */
        const val TOP_EDGE_PROBE_Y = 2
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val REFERENCE_ERROR_TEXT_TAG = "angle-v2-reference-error-text"
        const val LAZY_LIST_TAG = "angle-v2-saved-list"
        const val PRODUCTION_LIST_TAG = "angle-v2-saved-production-list"

        val COLOR_A = 0xFF1E88E5.toInt()
        val COLOR_B = 0xFFE53935.toInt()
        val COLOR_OTHER_ROOT = 0xFF43A047.toInt()
        val COLOR_CHANGED_SHA = 0xFFFDD835.toInt()
        val COLOR_CHANGED_BYTES = 0xFF8E24AA.toInt()

        /** Controles SIEMPRE visibles del pie compacto (el cierre vive en la barra superior). */
        val DIALOG_FOOTER_CONTROL_STRINGS = listOf(
            R.string.angle_polish_control_zoom_out,
            R.string.angle_polish_control_zoom_in,
            R.string.angle_polish_control_fit,
            R.string.angle_polish_control_move,
        )

        /** Flechas del panel secundario, que se revela con el conmutador «Mover». */
        val DIALOG_PAN_CONTROL_STRINGS = listOf(
            R.string.angle_polish_control_pan_left,
            R.string.angle_polish_control_pan_right,
            R.string.angle_polish_control_pan_up,
            R.string.angle_polish_control_pan_down,
        )

        /** Las nueve acciones interactivas del visor, con el cierre incluido. */
        val DIALOG_CONTROL_STRINGS =
            DIALOG_FOOTER_CONTROL_STRINGS + DIALOG_PAN_CONTROL_STRINGS +
                R.string.angle_polish_control_close
    }
}
