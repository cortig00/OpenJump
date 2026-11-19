package com.openjump.app.ui.angles

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.angles.AnglePoint
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tira de miniaturas del workspace ([AngleFrameFilmstrip]) sobre la sesión congelada REAL del arnés.
 *
 * Acredita con datos reales, sin AVD en este slice (solo se compila):
 *
 * 1. dos capturas de índices distintos producen dos miniaturas con **píxeles distintos** de verdad
 *    (`captureToImage`), no una etiqueta que diga «hay imagen»;
 * 2. los tags son únicos por fotograma y no exponen identificadores de URI privada;
 * 3. tocar una miniatura cambia el fotograma activo sin perder los ángulos ni las marcas del otro;
 * 4. el binding del fotograma dibujado se ignora si el fotograma fresco es otro (nunca retarget) y un
 *    lease cerrado no produce destino;
 * 5. cancelar la eliminación no cambia nada; confirmarla quita el fotograma con la guarda de la
 *    sesión sin borrar ninguna raíz persistida (no hay filas V2 ni PNG antes del guardado final);
 * 6. alternar selección (churn de la `LazyRow`) devuelve el `leaseCount` de la sesión a su valor
 *    inicial y el bitmap del lease abierto sigue sin reciclarse.
 *
 * Límites honestos: el visor es falso (no hay `Surface`/PTS reales del player), así que no se
 * acredita la ruta de superficie ni el guardado final. El render de la imagen congelada sí es el de
 * producción, por eso la comparación de píxeles es sobre píxeles renderizados reales.
 */
@RunWith(AndroidJUnit4::class)
class AngleFrameFilmstripInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var harness: AngleMultiFrameFlowHarness

    @Before
    fun setUp() {
        harness = AngleMultiFrameFlowHarness(productionRenderer = true)
        harness.openVideo()
        harness.openMultiFrameSession()
    }

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    private fun composeWorkspace() {
        rule.setContent {
            OpenJumpTheme {
                AngleEditorWorkspace(viewModel = harness.viewModel, onCloseRequest = {})
            }
        }
    }

    /**
     * Muestras ARGB de la región con **píxeles codificados** de la miniatura.
     *
     * El fixture sintético solo cambia entre fotogramas en una banda estrecha (x 25%..40%,
     * y 6%..14% del bitmap), así que la ROI central 25–75%/25–50% que se usaba antes caía **fuera**
     * de ese contenido y devolvía exactamente los mismos ARGB para dos fotogramas distintos: la
     * aserción no medía lo que decía medir. La ROI actual (x 8%..65%, y 6%..35%) incluye el código
     * de barras del fixture y **excluye** el cromo de la UI: borde de selección de 2dp, marca de
     * medición de la esquina superior derecha y marca de tiempo inferior. Se comparan listas ARGB
     * reales (no un recuento de colores) y se usa `toArgb()`: `Color.value.toInt()` no devuelve ARGB
     * (los bits bajos son color space, no canal) y dejaba todas las muestras en el mismo entero.
     */
    private fun interiorArgbSamples(tag: String): List<Int> {
        val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        val samples = ArrayList<Int>()
        val xStart = pixels.width * 8 / 100
        val xEnd = pixels.width * 65 / 100
        val yStart = pixels.height * 6 / 100
        val yEnd = pixels.height * 35 / 100
        for (y in yStart until yEnd step 3) {
            for (x in xStart until xEnd step 3) {
                samples.add(pixels[x, y].toArgb())
            }
        }
        return samples
    }

    /** Nº de colores distintos del interior: acredita que hay imagen, no que sea la misma. */
    private fun distinctInteriorColors(tag: String): Int =
        interiorArgbSamples(tag).toHashSet().size

    @Test
    fun laTiraMuestraDosFotogramasConPixelesRealesYSeleccionSinPerderMarcas() {
        val first = harness.captureFrame(3)
        val second = harness.captureFrame(9)
        composeWorkspace()

        // 1. Dos miniaturas, dos tags estables y únicos. La identidad de imagen se compara ANTES de
        // añadir ángulos y sobre muestras ARGB del interior (sin borde, check ni marca de tiempo):
        // contar colores distintos no demuestra que dos fotos sean la misma o distinta.
        val firstTag = angleWorkspaceThumbTag(first)
        val secondTag = angleWorkspaceThumbTag(second)
        rule.onNodeWithTag(ANGLE_WORKSPACE_FILMSTRIP_TAG).assertExists()
        rule.onNodeWithTag(firstTag).assertExists()
        rule.onNodeWithTag(secondTag).assertExists()
        assertNotEquals(firstTag, secondTag)
        val firstDescription = rule.onNodeWithTag(firstTag)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
            .joinToString()
        assertTrue(
            "la descripción debe nombrar el ordinal y el estado, era «$firstDescription»",
            firstDescription.contains("Fotograma") || firstDescription.contains("Frame"),
        )
        assertFalse(
            "la descripción no debe exponer identificadores de URI privada: $firstDescription",
            firstDescription.contains("content://") || firstDescription.contains("/cache/"),
        )
        assertTrue(
            "la miniatura real debe tener imagen (colores=${distinctInteriorColors(firstTag)})",
            distinctInteriorColors(firstTag) > 1,
        )
        assertTrue(
            "la miniatura real debe tener imagen (colores=${distinctInteriorColors(secondTag)})",
            distinctInteriorColors(secondTag) > 1,
        )
        assertNotEquals(
            "dos fotogramas distintos deben pintar píxeles distintos (identidad, no recuento)",
            interiorArgbSamples(firstTag),
            interiorArgbSamples(secondTag),
        )

        val firstAngle = harness.markAngle(first)
        val firstMarkers = harness.frame(first).editor.angles.single().markers
        rule.waitForIdle()

        // 2. Tocar la segunda miniatura cambia el activo sin tocar ángulos ni marcas del primero.
        rule.onNodeWithTag(secondTag).performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            harness.sessionState().activeFrameId == second
        }
        assertEquals(second, harness.sessionState().activeFrameId)
        assertEquals(1, harness.frame(first).editor.angles.size)
        assertEquals(firstMarkers, harness.frame(first).editor.angles.single().markers)
        assertTrue(harness.frame(first).editor.draft.points.isEmpty())

        // 3. Binding del fotograma DIBUJADO: si el fresco es otro, no hay destino (nunca retarget).
        val drawnFirst = angleRenderedFrameBinding(
            activeFrameId = first,
            sourceIdentity = harness.sessionState().sourceIdentity,
            generation = harness.sessionState().generation,
            leaseFrameId = first,
            leaseOpen = true,
        )
        assertNotNull(drawnFirst)
        val freshSecond = angleFreshFrameBinding(harness.sessionState(), second)
        assertNotNull(freshSecond)
        assertEquals(null, angleFrameActionTarget(drawnFirst, freshSecond))
        assertEquals(
            null,
            angleRenderedFrameBinding(
                activeFrameId = first,
                sourceIdentity = harness.sessionState().sourceIdentity,
                generation = harness.sessionState().generation,
                leaseFrameId = first,
                leaseOpen = false,
            ),
        )
        // La acción antigua de A no aparece en B: B sigue sin ángulos ni borrador.
        assertEquals(0, harness.frame(second).editor.angles.size)
        assertTrue(harness.frame(second).editor.draft.points.isEmpty())

        // 4. Churn de selección: el leaseCount vuelve al valor inicial y el bitmap no se recicla.
        val baselineLeases = harness.frame(second).leaseCount
        repeat(4) {
            rule.onNodeWithTag(firstTag).performClick()
            rule.waitUntil(timeoutMillis = 20_000) {
                harness.sessionState().activeFrameId == first
            }
            rule.onNodeWithTag(secondTag).performClick()
            rule.waitUntil(timeoutMillis = 20_000) {
                harness.sessionState().activeFrameId == second
            }
        }
        assertEquals(
            "los leases de la miniatura deben volver a su estado inicial",
            baselineLeases,
            harness.frame(second).leaseCount,
        )
        harness.withFrozenBitmap(second) { bitmap ->
            assertFalse("el bitmap de un lease abierto no puede estar reciclado", bitmap.isRecycled)
        }

        // 5. Eliminar con la UI REAL: el botón de quitar abre el diálogo; cancelar no muta nada y
        // confirmar quita el id correcto conservando el resto. Se selecciona antes el fotograma
        // porque la acción es contextual del SELECCIONADO.
        rule.onNodeWithTag(firstTag).performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            harness.sessionState().activeFrameId == first
        }
        assertTrue(harness.viewModel.multiFrameRequiresConfirmation(first))
        val secondPointsBefore = harness.frame(second).editor.draft.points
        // S4a: quitar es una acción rara contextual dentro del overflow, con el diálogo existente.
        rule.onNodeWithTag(ANGLE_WORKSPACE_OVERFLOW_TAG).performClick()
        rule.onNodeWithTag(ANGLE_WORKSPACE_REMOVE_FRAME_TAG).performClick()
        rule.onNodeWithTag(ANGLE_WORKSPACE_REMOVE_CANCEL_TAG).performClick()
        rule.waitForIdle()
        assertEquals("cancelar no cambia la sesión", 2, harness.sessionState().frames.size)
        assertEquals(first, harness.sessionState().activeFrameId)
        assertEquals(0L, harness.v2RootCount())
        assertEquals(0, harness.publishedImageFileCount())
        rule.onNodeWithTag(ANGLE_WORKSPACE_OVERFLOW_TAG).performClick()
        rule.onNodeWithTag(ANGLE_WORKSPACE_REMOVE_FRAME_TAG).performClick()
        rule.onNodeWithTag(ANGLE_WORKSPACE_REMOVE_CONFIRM_TAG).performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            harness.sessionState().frames.none { it.frameId == first }
        }
        assertEquals(1, harness.sessionState().frames.size)
        assertEquals(null, harness.sessionState().frames.singleOrNull { it.frameId == first })
        // La sesión no puede quedarse apuntando al fotograma eliminado; a cuál pasa lo decide la
        // guarda del ViewModel y el test no lo inventa.
        assertNotEquals(first, harness.sessionState().activeFrameId)
        // Nada persistido ni borrado: cero filas V2, cero PNG y los puntos del otro fotograma intactos.
        assertEquals(0L, harness.v2RootCount())
        assertEquals(0, harness.publishedImageFileCount())
        assertEquals(secondPointsBefore, harness.frame(second).editor.draft.points)
        assertEquals(0, harness.frame(second).editor.angles.size)
        rule.onNodeWithTag(firstTag).assertDoesNotExist()
        rule.onNodeWithTag(ANGLE_WORKSPACE_FILMSTRIP_TAG).assertExists()
        // El botón de captura solo existe con el VÍDEO seleccionado (una foto no captura).
        rule.onNodeWithTag(ANGLE_WORKSPACE_VIDEO_TILE_TAG).performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            harness.sessionState().activeFrameId == null
        }
        rule.onNodeWithTag(ANGLE_WORKSPACE_CAPTURE_TAG).assertIsEnabled()
        assertNotNull(firstAngle)
    }
}
