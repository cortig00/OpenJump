package com.openjump.app.ui.angles

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regresiones NATIVAS del cuerpo REAL de producción [AngleV2SavedDetailContentHost] (el mismo que
 * monta la pantalla con contenido ya cargado) sobre tres fotogramas inmutables de 64×48 con colores
 * distintos.
 *
 * Sin VM, sin Room, sin `MainActivity` y sin vídeo: lo que se acredita es el comportamiento de la
 * composición real, no una maqueta.
 *
 * - La galería entrega a Análisis la MISMA imagen ya decodificada al seleccionar una celda visible:
 *   el proveedor de imágenes NO se vuelve a llamar y se pinta el color del fotograma elegido.
 * - Cambiar de raíz o de fotograma deja la imagen en espera (nunca la anterior) y una respuesta
 *   tardía de la petición vieja no puede publicarse encima.
 * - Con el lote de «compartir todas» en curso, el botón de compartir la imagen actual está
 *   DESHABILITADO en la UI (misma puerta que el lote), no un no-op silencioso.
 * - El plegable técnico de Información conserva TODAS las coordenadas y cada bloque lleva al lado el
 *   nombre real (y el estado de oculta) de su marca.
 *
 * Estas pruebas se compilan con el resto del módulo androidTest pero NO se ejecutan en la slice de
 * compilación focal: requieren el AVD del padre.
 */
@RunWith(AndroidJUnit4::class)
class AngleV2SavedDetailSectionsInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var markVisible: AngleSavedDetailMark
    private lateinit var markHidden: AngleSavedDetailMark
    private lateinit var bitmapA: Bitmap
    private lateinit var bitmapB: Bitmap
    private lateinit var bitmapC: Bitmap

    /** Llamadas al proveedor por `frameId`, para probar que seleccionar no vuelve a decodificar. */
    private val calls: MutableMap<Long, AtomicInteger> =
        Collections.synchronizedMap(mutableMapOf<Long, AtomicInteger>())

    private val gates: MutableMap<Long, CompletableDeferred<AngleV2SavedImage>> =
        Collections.synchronizedMap(mutableMapOf())

    private val images: MutableMap<Long, Bitmap> = mutableMapOf()

    /** Identificador ÚNICO de esta ejecución para el directorio de evidencia visual. */
    private val evidenceRunId: String = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        calls.clear()
        gates.clear()
        images.clear()
        markVisible = angleSavedMark(
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
        markHidden = angleSavedMark(
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
        // Contrato de la fixture: lecturas distintas para que una prueba de texto no pase por azar.
        assertTrue(
            "las marcas de la fixture deben ser medibles y distintas",
            requireNotNull(markVisible.degrees) != requireNotNull(markHidden.degrees),
        )
        bitmapA = solidBitmap(COLOR_A)
        bitmapB = solidBitmap(COLOR_B)
        bitmapC = solidBitmap(COLOR_C)
        images[FRAME_A] = bitmapA
        images[FRAME_B] = bitmapB
        images[FRAME_C] = bitmapC
    }

    // 1. Galería → Análisis: misma imagen ya decodificada, sin segunda lectura ni segundo bitmap.
    @Test
    fun gallerySelection_reusesAvailableImageWithoutSecondProviderCall_andPaintsItsColor() {
        val frames = listOf(
            frame(FRAME_A, assignedFrameIndex = 0, color = COLOR_A),
            frame(FRAME_B, assignedFrameIndex = 1, color = COLOR_B),
            frame(FRAME_C, assignedFrameIndex = 2, color = COLOR_C),
        )
        composeHost(contentOf(ROOT_A, frames))

        assertRendersColor(COLOR_A)
        assertEquals("Análisis decodifica una vez el fotograma seleccionado", 1, callCount(FRAME_A))

        rule.onNodeWithTag(tabTag("frames")).performClick()
        rule.waitForIdle()
        // Las celdas visibles de la galería ya tienen su imagen disponible: se espera la imagen
        // REAL de la celda B (su descripción accesible única, que sólo existe cuando la celda pinta
        // la imagen Disponible) y se comprueba su píxel central, no una leyenda de espera ni el
        // número de fotograma de la leyenda.
        assertEquals("la galería decodifica la celda visible B", 1, callCount(FRAME_B))
        awaitGalleryCellImage(2)
        assertGalleryCellRendersColor(2, COLOR_B)

        rule.onNodeWithText(frameLabelAt(2)).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(tabTag("analysis")).assertIsSelected()
        assertRendersColor(COLOR_B)
        assertEquals(
            "seleccionar una celda ya decodificada NO vuelve a leer su imagen",
            1,
            callCount(FRAME_B),
        )

        // Ida y vuelta de pestañas: la imagen del fotograma elegido no se vuelve a pedir.
        rule.onNodeWithTag(tabTag("info")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(tabTag("analysis")).performClick()
        rule.waitForIdle()
        assertRendersColor(COLOR_B)
        assertEquals(1, callCount(FRAME_B))
    }

    // 1b. La transferencia al contenedor ocurre SÓLO al pulsar la celda pulsada: que otra celda
    // visible termine de cargar DESPUÉS no puede quedar como candidata (antes era último-en-escribir).
    @Test
    fun laGaleriaTransfiereSoloLaCeldaPulsadaAunqueOtraTermineDeCargarDespues() {
        val frames = listOf(
            frame(FRAME_A, assignedFrameIndex = 0, color = COLOR_A),
            frame(FRAME_B, assignedFrameIndex = 1, color = COLOR_B),
            frame(FRAME_C, assignedFrameIndex = 2, color = COLOR_C),
        )
        // Libro de registro PROPIO de este caso: cada invocación del proveedor tiene identidad,
        // `Job` y puerta INDEPENDIENTE. Una lectura adicional ya no puede quedar oculta por una
        // puerta compartida completada, ni resolverse sola: queda como duplicado concurrente.
        val ledger = HandoffLedger(FRAME_B, FRAME_C)
        composeHostGated(contentOf(ROOT_A, frames), ledgerGatedLoader(ledger))
        settleHandoffLedger()
        handoffProbe("after-compose", ledger)

        // A: se libera la invocación ACTIVA real de Análisis (su puerta, no una del fotograma) y se
        // comprueba que se pinta su color.
        val invocationA = ledger.requireSingleLive(FRAME_A, "A inicial")
        ledger.assertNoDuplicatesLatched("A inicial")
        releaseInvocation(invocationA, AngleV2SavedImage.Available(bitmapA))
        ledger.assertPreviousAttemptsCancelled(FRAME_A, invocationA, "A inicial")
        assertRendersColor(COLOR_A)

        rule.onNodeWithTag(tabTag("frames")).performClick()
        settleHandoffLedger()
        handoffProbe("after-frames-tab", ledger)

        // Snapshot EXACTO antes de liberar NADA: UNA invocación viva para B y otra para C, y los
        // arranques quedan CONGELADOS aquí. Si Compose relanzó una celda, sus intentos anteriores
        // tienen que estar CANCELADOS y no pueden haber devuelto imagen.
        val invocationB = ledger.requireSingleLive(FRAME_B, "B antes de liberar")
        val invocationC = ledger.requireSingleLive(FRAME_C, "C antes de liberar")
        ledger.assertPreviousAttemptsCancelled(FRAME_B, invocationB, "B antes de liberar")
        ledger.assertPreviousAttemptsCancelled(FRAME_C, invocationC, "C antes de liberar")
        ledger.assertNoDuplicatesLatched("B/C antes de liberar")
        val startB = ledger.starts(FRAME_B)
        val startC = ledger.starts(FRAME_C)
        assertEquals("B debe tener UNA sola lectura viva antes de liberar", 1, ledger.liveCount(FRAME_B))
        assertEquals("C debe tener UNA sola lectura viva antes de liberar", 1, ledger.liveCount(FRAME_C))
        handoffProbe("frozen-pre-release", ledger)

        // B termina ANTES, con SU puerta y SU bitmap: la celda B tiene que pintar la imagen REAL de
        // B (su descripción accesible única de Disponible, que sólo existe con el bitmap pintado).
        releaseInvocation(invocationB, AngleV2SavedImage.Available(bitmapB))
        handoffProbe("after-release-b", ledger)
        ledger.assertFrozenStarts("after-release-b", startB, startC)
        awaitGalleryCellImage(2)
        assertGalleryCellRendersColor(2, COLOR_B)

        // C termina LA ÚLTIMA, también con SU puerta y SU bitmap: el píxel REAL de C acredita que su
        // imagen quedó Disponible después de la de B, así que «la última en escribir» es C.
        releaseInvocation(invocationC, AngleV2SavedImage.Available(bitmapC))
        handoffProbe("after-release-c", ledger)
        ledger.assertFrozenStarts("after-release-c", startB, startC)
        awaitGalleryCellImage(3)
        assertLastGalleryCellRendersColor(3, COLOR_C)

        // Desplazar y pulsar van SEPARADOS: ningún desplazamiento puede arrancar otra lectura (la
        // celda sale y vuelve a entrar en composición) y la traza queda emitida por si falla.
        rule.onNodeWithText(frameLabelAt(2)).performScrollTo()
        rule.waitForIdle()
        handoffProbe("after-scroll-idle", ledger)
        ledger.assertFrozenStarts("after-scroll-idle", startB, startC)
        ledger.assertNoDuplicatesLatched("after-scroll-idle")
        awaitGalleryCellImage(2)
        assertGalleryCellRendersColor(2, COLOR_B)
        handoffProbe("before-click", ledger)

        rule.onNodeWithText(frameLabelAt(2)).performClick()
        rule.waitForIdle()
        // Conteo ANTES de cualquier píxel: si el toque arrancó otra lectura, se falla aquí con el
        // libro ya volcado, sin esperar un tiempo de carga genérico.
        handoffProbe("after-click", ledger)
        ledger.assertFrozenStarts("after-click", startB, startC)
        ledger.assertNoDuplicatesLatched("after-click")

        rule.onNodeWithTag(tabTag("analysis")).assertIsSelected()
        assertRendersColor(COLOR_B)
        ledger.assertFrozenStarts("analysis", startB, startC)

        rule.onNodeWithTag(tabTag("info")).performClick()
        rule.waitForIdle()
        handoffProbe("after-info", ledger)
        rule.onNodeWithTag(tabTag("analysis")).performClick()
        rule.waitForIdle()
        handoffProbe("after-analysis-return", ledger)
        assertRendersColor(COLOR_B)

        // Métrica causal (no indulgencia): lo que se exige es UN retorno Disponible por fotograma
        // —el de su invocación viva— y NINGÚN arranque nuevo desde la línea base congelada antes de
        // liberar. Los intentos previos, si los hubo, quedaron CANCELADOS y sin devolver imagen.
        assertEquals("B debe completar UNA sola lectura Disponible", 1, ledger.successfulReturns(FRAME_B))
        assertEquals("C debe completar UNA sola lectura Disponible", 1, ledger.successfulReturns(FRAME_C))
        ledger.assertFrozenStarts("final", startB, startC)
        ledger.assertPreviousAttemptsCancelled(FRAME_B, invocationB, "final B")
        ledger.assertPreviousAttemptsCancelled(FRAME_C, invocationC, "final C")
        ledger.assertNoDuplicatesLatched("final")
    }

    // 2. Cambio de raíz y de fotograma: espera síncrona, respuesta tardía rechazada, color correcto.
    @Test
    fun frameAndRootChange_showLoadingAndRejectLateResponses() {
        val root = mutableStateOf(ROOT_A)
        val frames = mutableStateOf(listOf(frame(FRAME_A, assignedFrameIndex = 0)))
        rule.setContent {
            OpenJumpTheme {
                val current = frames.value
                AngleV2SavedDetailContentHost(
                    content = AngleV2SavedDetailContent(
                        rootId = root.value,
                        createdAt = 0L,
                        athleteId = null,
                        frames = current,
                    ),
                    imageLoader = gatedLoader(),
                    shareEnabled = true,
                    shareAllBusy = false,
                    onShare = {},
                    onShareAll = {},
                    padding = PaddingValues(0.dp),
                )
            }
        }
        rule.waitForIdle()
        assertImagePending()

        // Cambio de RAÍZ con la petición anterior EN VUELO: el contenedor nuevo nace en espera y la
        // respuesta tardía de la raíz vieja no puede publicarse encima.
        root.value = ROOT_B
        frames.value = listOf(
            frame(FRAME_D, assignedFrameIndex = 0, color = COLOR_C),
            frame(FRAME_E, assignedFrameIndex = 1, color = COLOR_B),
        )
        rule.waitForIdle()
        assertImagePending()
        release(FRAME_A, AngleV2SavedImage.Available(solidBitmap(COLOR_LATE)))
        assertImagePending()
        release(FRAME_D, AngleV2SavedImage.Available(bitmapC))
        assertRendersColor(COLOR_C)

        // Cambio de FOTOGRAMA en otra raíz con SUS cargas en vuelo: la respuesta del fotograma
        // anterior tampoco se publica, y el nuevo pinta su propio color.
        root.value = ROOT_C
        frames.value = listOf(
            frame(FRAME_F, assignedFrameIndex = 0),
            frame(FRAME_G, assignedFrameIndex = 1, color = COLOR_B),
        )
        rule.waitForIdle()
        assertImagePending()
        rule.onNodeWithContentDescription(openInAnalysisLabel(2)).performClick()
        rule.waitForIdle()
        assertImagePending()
        release(FRAME_F, AngleV2SavedImage.Available(solidBitmap(COLOR_LATE)))
        assertImagePending()
        release(FRAME_G, AngleV2SavedImage.Available(bitmapB))
        assertRendersColor(COLOR_B)
    }

    // 3. Lote en curso: la acción de compartir la imagen actual se deshabilita en la UI.
    @Test
    fun shareAllBusy_disablesCurrentShareButton_withoutCallingOnShare() {
        val busy = mutableStateOf(true)
        val shares = AtomicInteger()
        val frames = listOf(
            frame(FRAME_A, assignedFrameIndex = 0, sha = SHA_A),
            frame(FRAME_B, assignedFrameIndex = 1, sha = SHA_B),
        )
        rule.setContent {
            OpenJumpTheme {
                AngleV2SavedDetailContentHost(
                    content = contentOf(ROOT_A, frames),
                    imageLoader = { frame -> AngleV2SavedImage.Available(images.getValue(frame.frameId)) },
                    shareEnabled = true,
                    shareAllBusy = busy.value,
                    onShare = { shares.incrementAndGet() },
                    onShareAll = {},
                    padding = PaddingValues(0.dp),
                )
            }
        }
        rule.waitForIdle()
        val description = shareDescription(1)
        rule.onNodeWithContentDescription(description).assertIsNotEnabled()
        assertEquals("un toque bloqueado no comparte nada", 0, shares.get())

        busy.value = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription(description).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals("al liberar el lote, la acción vuelve y comparte el fotograma actual", 1, shares.get())
    }

    // 4. Información técnica: nombres (y estado oculto) al lado de SUS coordenadas, sin perder datos.
    @Test
    fun infoTechnical_namesAdjacentToTheirOwnCoordinates_includingHiddenMark() {
        val frames = listOf(frame(FRAME_A, assignedFrameIndex = 0, sha = SHA_A))
        composeHost(contentOf(ROOT_A, frames))

        rule.onNodeWithTag(tabTag("info")).performClick()
        rule.waitForIdle()
        // El plegable REAL de Información del detalle guardado se abre por su TÍTULO a todo el
        // ancho: la etiqueta vieja pertenecía a la sección pública heredada y en este host no
        // existe. No se añade ninguna etiqueta de prueba al código de producción.
        rule.onNodeWithText(strings(R.string.angle_polish_info_technical))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        // Las DOS marcas conservan sus coordenadas y cada bloque lleva su nombre.
        rule.onAllNodesWithText(markVisible.name).assertCountEquals(1)
        rule.onAllNodesWithText(markHidden.name).assertCountEquals(1)
        rule.onAllNodesWithText(pointsText(markVisible)).assertCountEquals(1)
        rule.onAllNodesWithText(pointsText(markHidden)).assertCountEquals(1)
        rule.onAllNodesWithText(strings(R.string.angle_hidden)).assertCountEquals(1)
        rule.onNodeWithText(markVisible.name).assertExists()
        rule.onNodeWithText(pointsText(markHidden)).assertExists()
    }

    // 5. Un solo fotograma: ni pestaña de galería ni lote. La compartición vive en la CABECERA,
    //    exactamente una vez (descripción del ÚNICO fotograma), y el panel de Análisis ya NO la
    //    duplica con su botón inferior de texto.
    @Test
    fun unSoloFotogramaComparteDesdeLaCabeceraUnaVezSinDuplicarEnAnalisis() {
        val frames = listOf(frame(FRAME_A, assignedFrameIndex = 0))
        val singleCalls = AtomicInteger()
        val shareAllCalls = AtomicInteger()
        val busy = mutableStateOf(false)
        rule.setContent {
            OpenJumpTheme {
                AngleV2SavedDetailContentHost(
                    content = contentOf(ROOT_A, frames),
                    imageLoader = { f -> AngleV2SavedImage.Available(images.getValue(f.frameId)) },
                    shareEnabled = true,
                    shareAllBusy = busy.value,
                    onShare = { singleCalls.incrementAndGet() },
                    onShareAll = { shareAllCalls.incrementAndGet() },
                    padding = PaddingValues(0.dp),
                )
            }
        }
        rule.waitForIdle()
        // Sin galería no hay pestaña «Fotogramas» ni selector de fichas de imagen.
        rule.onAllNodesWithTag(tabTag("frames")).assertCountEquals(0)
        rule.onAllNodesWithTag(ANGLE_V2_SAVED_GALLERY_IMAGE_TAG).assertCountEquals(0)
        // El lote no se ofrece (exigiría más de uno); la del ÚNICO fotograma sí, UNA sola vez.
        rule.onAllNodesWithContentDescription(strings(R.string.angle_v2_saved_share_all_description, 1))
            .assertCountEquals(0)
        rule.onAllNodesWithContentDescription(shareDescription(1)).assertCountEquals(1)
        // No queda el botón inferior de texto de compartir: la cabecera ya comparte ESE PNG.
        rule.onAllNodesWithText(strings(R.string.angle_v2_saved_share_image)).assertCountEquals(0)
        val share = rule.onNodeWithContentDescription(shareDescription(1))
            .assertIsEnabled()
            .fetchSemanticsNode()
            .boundsInRoot
        val tabs = rule.onNodeWithTag(tabTag("analysis")).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "con un solo fotograma la acción debe estar ARRIBA de las pestañas: $share vs $tabs",
            share.bottom <= tabs.top + 1f,
        )
        assertTrue(
            "y en el borde final de la cabecera: $share",
            share.left > rule.activity.resources.displayMetrics.widthPixels / 2f,
        )
        assertTrue(
            "la acción de la cabecera debe medir al menos 48 dp: $share",
            share.width / densityScale >= 47f && share.height / densityScale >= 47f,
        )
        rule.onNodeWithContentDescription(shareDescription(1)).performClick()
        rule.waitForIdle()
        assertEquals("la cabecera comparte el ÚNICO fotograma una sola vez", 1, singleCalls.get())
        assertEquals("con un solo fotograma nadie dispara el lote", 0, shareAllCalls.get())

        // Puerta cerrada: la MISMA acción de la cabecera queda deshabilitada y no dispara nada.
        busy.value = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription(shareDescription(1)).assertIsNotEnabled()
        assertEquals("una acción deshabilitada no comparte", 1, singleCalls.get())
    }

    // 6. Raíz sin fotogramas: vacío PROPIO del detalle guardado (no la instrucción del editor) y
    //    navegación real a Información con su contexto, sin imagen vieja.
    @Test
    fun unaRaizSinFotogramasMuestraElVacioPropioYSuContextoEnInformacionSinImagenVieja() {
        val frames = mutableStateOf(listOf(frame(FRAME_A, assignedFrameIndex = 0)))
        val rootAthleteName = "Ana"
        rule.setContent {
            OpenJumpTheme {
                AngleV2SavedDetailContentHost(
                    content = AngleV2SavedDetailContent(
                        rootId = ROOT_A,
                        createdAt = 1_600_000_000_000L,
                        athleteId = 5L,
                        frames = frames.value,
                        athleteName = rootAthleteName,
                    ),
                    imageLoader = { f -> AngleV2SavedImage.Available(images.getValue(f.frameId)) },
                    shareEnabled = true,
                    shareAllBusy = false,
                    onShare = {},
                    onShareAll = {},
                    padding = PaddingValues(0.dp),
                )
            }
        }
        rule.waitForIdle()
        assertRendersColor(COLOR_A)

        frames.value = emptyList()
        rule.waitForIdle()
        // El vacío es el de un análisis YA guardado y de sólo lectura, no el del editor de vídeo.
        rule.onNodeWithText(strings(R.string.angle_polish_empty_frames)).assertIsDisplayed()
        rule.onAllNodesWithText(strings(R.string.angle_multi_frame_frames_empty)).assertCountEquals(0)
        rule.onAllNodesWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertCountEquals(0)

        // Información sigue siendo navegable y enseña el contexto REAL de la raíz; ninguna imagen.
        rule.onNodeWithTag(tabTag("info")).performClick()
        rule.waitForIdle()
        val contextHeader = strings(R.string.angle_polish_info_context)
        rule.onNodeWithText(contextHeader).performScrollTo().performClick()
        rule.waitForIdle()
        // La cabecera REAL del detalle pinta el NOMBRE como identidad principal, SIN prefijo de
        // propiedad, mientras el contexto REAL de la raíz dentro del plegable abierto conserva su
        // etiqueta prefijada de siempre. No se busca «Ana» a secas y se da por bueno cualquiera de
        // los dos: se acredita CADA uno en SU región, que el contexto abierto sigue visible y que
        // no hay copia del editor ni imagen vieja.
        val headerName = rule.onAllNodesWithText(rootAthleteName)
        headerName.assertCountEquals(1)
        val prefixedOwner = rule.onAllNodesWithText(
            strings(R.string.angle_detail_owner_name, rootAthleteName),
        )
        prefixedOwner.assertCountEquals(1)
        prefixedOwner[0].performScrollTo()
        rule.waitForIdle()
        val headerNameBounds = headerName[0].fetchSemanticsNode().boundsInRoot
        val contextOwnerBounds = prefixedOwner[0].fetchSemanticsNode().boundsInRoot
        val tabsBounds = rule.onNodeWithTag(tabTag("info")).fetchSemanticsNode().boundsInRoot
        val disclosureBounds = rule.onNodeWithText(contextHeader).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "la identidad de la cabecera debe tener caja real: $headerNameBounds",
            headerNameBounds.width > 0f && headerNameBounds.height > 0f,
        )
        assertTrue(
            "el nombre sin prefijo de la cabecera debe quedar ENCIMA de las pestañas: " +
                "$headerNameBounds vs $tabsBounds",
            headerNameBounds.bottom <= tabsBounds.top + 1f,
        )
        assertTrue(
            "el contexto del dueño debe abrirse DEBAJO de su plegable: " +
                "$contextOwnerBounds vs $disclosureBounds",
            contextOwnerBounds.top >= disclosureBounds.bottom - 1f,
        )
        assertTrue(
            "el contexto del dueño debe seguir visible en la ventana: $contextOwnerBounds",
            contextOwnerBounds.bottom <= rule.activity.resources.displayMetrics.heightPixels + 1f,
        )
        rule.onAllNodesWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertCountEquals(0)
    }

    // 7. Host de producción con aspecto extremo y error real: aviso legible y navegación intacta.
    @Test
    fun elHostDeProduccionSobreviveAUnAspectoExtremoConAvisoLegibleYElSelectorSigueNavegando() {
        val extreme = frame(FRAME_A, assignedFrameIndex = 0).copy(imageWidth = 1, imageHeight = 1280)
        val frames = listOf(extreme, frame(FRAME_B, assignedFrameIndex = 1, color = COLOR_B))
        rule.setContent {
            OpenJumpTheme {
                AngleV2SavedDetailContentHost(
                    content = contentOf(ROOT_A, frames),
                    imageLoader = { f ->
                        if (f.frameId == FRAME_A) {
                            AngleV2SavedImage.Corrupted
                        } else {
                            AngleV2SavedImage.Available(images.getValue(f.frameId))
                        }
                    },
                    shareEnabled = true,
                    shareAllBusy = false,
                    onShare = {},
                    onShareAll = {},
                    padding = PaddingValues(0.dp),
                )
            }
        }
        rule.waitForIdle()
        // El aviso REAL de la imagen no disponible se lee y ocupa un hueco válido en la ventana: un
        // alto derivado del aspecto 1×1280 no puede empujarlo fuera ni dejar la pantalla en blanco.
        val notice = rule.onNodeWithText(strings(R.string.angle_v2_saved_image_unavailable))
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertTrue("el aviso no puede quedar sin ancho: ${notice.boundsInRoot}", notice.boundsInRoot.width > 0f)
        assertTrue(
            "el aviso debe caber en la ventana: ${notice.boundsInRoot}",
            notice.boundsInRoot.bottom <=
                rule.activity.resources.displayMetrics.heightPixels + 1f,
        )
        rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertDoesNotExist()

        // El selector de fotogramas sigue navegando a un fotograma con imagen válida.
        rule.onNodeWithContentDescription(openInAnalysisLabel(2)).performClick()
        rule.waitForIdle()
        assertRendersColor(COLOR_B)
    }

    // 8. Evidencia visual REAL del host de producción: tres fotogramas, fuente 1.5, tema oscuro y
    //    claro. Las capturas se escriben DURANTE la ejecución, en la caché EXTERNA del paquete de la
    //    app bajo prueba (fuera de todo hash interno).
    @Test
    fun evidenciaVisualDelHostDeProduccionTresFotogramasFuente15OscuroYClaro() {
        val dark = mutableStateOf(true)
        val frames = listOf(
            frame(FRAME_VISUAL_A, assignedFrameIndex = 0, color = COLOR_A),
            frame(FRAME_VISUAL_B, assignedFrameIndex = 17, color = COLOR_B),
            frame(FRAME_VISUAL_C, assignedFrameIndex = 119, sha = SHA_C, color = COLOR_C),
        )
        val content = AngleV2SavedDetailContent(
            rootId = ROOT_A,
            createdAt = VISUAL_CREATED_AT,
            athleteId = VISUAL_ATHLETE_ID,
            frames = frames,
            athleteName = VISUAL_ATHLETE_NAME,
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.5f),
            ) {
                OpenJumpTheme(darkTheme = dark.value) {
                    // El host REAL no pinta el fondo de la ventana (lo hace el Scaffold de la
                    // pantalla); sin esta superficie la captura saldría transparente o
                    // blanco sobre blanco en el tema claro. Es una Surface Material 3 real, sin
                    // barras ni controles añadidos: el fondo opaco que acredita contraste.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        AngleV2SavedDetailContentHost(
                            content = content,
                            imageLoader = { f ->
                                AngleV2SavedImage.Available(images.getValue(f.frameId))
                            },
                            shareEnabled = true,
                            shareAllBusy = false,
                            onShare = {},
                            onShareAll = {},
                            padding = PaddingValues(0.dp),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        // La cabecera ya NO usa una etiqueta de texto larga para el lote NI mezcla el control con
        // los conteos: el nombre (identidad principal, sin prefijo) y la fecha ocupan una columna,
        // el control neutro de 48 dp tiene su PROPIO espacio al borde final de esa fila y los DOS
        // conteos reales van en una sola línea debajo de la fecha. Eso es lo que a fuente 1.5
        // mantiene el reparto compacto en vez de una tercera línea de controles.
        val description = strings(R.string.angle_v2_saved_share_all_description, 3)
        rule.onAllNodesWithText(strings(R.string.angle_v2_saved_share_all)).assertCountEquals(0)
        val shareBounds = rule.onNodeWithContentDescription(description)
            .assertIsEnabled()
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        val nameBounds = rule.onNodeWithText(VISUAL_ATHLETE_NAME)
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        val dateBounds = rule.onNodeWithText(dateText(VISUAL_CREATED_AT))
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        val countsBounds = rule.onNodeWithText(headerCountsText(3, 6))
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "el control del lote debe medir al menos 48 dp: $shareBounds",
            shareBounds.width / densityScale >= 47f &&
                shareBounds.height / densityScale >= 47f,
        )
        assertTrue(
            "el control del lote debe quedar al borde final, en su propio espacio: " +
                "$shareBounds vs nombre $nameBounds y fecha $dateBounds",
            shareBounds.left >= maxOf(nameBounds.right, dateBounds.right) - 1f,
        )
        assertTrue(
            "el control del lote debe compartir la fila de identidad: $shareBounds vs $nameBounds",
            shareBounds.top < nameBounds.bottom && shareBounds.bottom > nameBounds.top,
        )
        assertTrue(
            "la fecha debe quedar debajo del nombre: $dateBounds vs $nameBounds",
            dateBounds.top >= nameBounds.bottom - 1f,
        )
        // Los conteos viven en la MISMA columna de identidad que el nombre y la fecha (al lado del
        // avatar), no debajo del círculo: van bajo la fecha y a la izquierda del control.
        assertTrue(
            "los conteos deben quedar debajo de la fecha y no mezclarse con el control: " +
                "$countsBounds vs $dateBounds vs $shareBounds",
            countsBounds.top >= dateBounds.bottom - 1f && countsBounds.right <= shareBounds.left + 1f,
        )
        // aparece DOS veces (cabecera del selector y leyenda de la tarjeta), así que la identidad se
        // acredita por la descripción accesible ÚNICA de la imagen realmente pintada.
        rule.onNodeWithContentDescription(frameImageLabel(1)).assertExists()
        rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertExists()
        captureAnglePolishEvidence("01-dark-analysis")

        // Galería en oscuro: los TRES fotogramas con sus números REALES (1/18/120).
        rule.onNodeWithTag(tabTag("frames")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(frameLabelAt(1)).assertExists()
        rule.onNodeWithText(frameLabelAt(18)).performScrollTo().assertExists()
        rule.onNodeWithText(frameLabelAt(120)).performScrollTo().assertExists()
        captureAnglePolishEvidence("02-dark-gallery")

        // Tercer fotograma seleccionado: Análisis pinta SU imagen y SU leyenda.
        rule.onNodeWithText(frameLabelAt(120)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(tabTag("analysis")).assertIsSelected()
        rule.onNodeWithContentDescription(frameImageLabel(120)).assertExists()
        rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).performScrollTo()
        assertRendersColor(COLOR_C)
        captureAnglePolishEvidence("03-dark-selected-third-analysis")

        // Información en oscuro: los tres plegables neutros cerrados (sin clave ni SHA por defecto).
        rule.onNodeWithTag(tabTag("info")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(strings(R.string.angle_polish_info_context)).assertExists()
        rule.onNodeWithText(strings(R.string.angle_polish_info_image)).assertExists()
        rule.onNodeWithText(strings(R.string.angle_polish_info_technical)).assertExists()
        rule.onAllNodesWithText(SHA_C, substring = true).assertCountEquals(0)
        captureAnglePolishEvidence("04-dark-info")

        // Mismo contenido en CLARO: la selección del tercer fotograma se conserva.
        dark.value = false
        rule.waitForIdle()
        captureAnglePolishEvidence("05-light-info")
        rule.onNodeWithTag(tabTag("frames")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(frameLabelAt(120)).performScrollTo().assertExists()
        captureAnglePolishEvidence("06-light-gallery")
        rule.onNodeWithTag(tabTag("analysis")).performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription(frameImageLabel(120)).assertExists()
        rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertExists()
        assertRendersColor(COLOR_C)
        captureAnglePolishEvidence("07-light-analysis")
    }

    // 9. Host de producción con imagen VÁLIDA de aspecto extremo (1×1280): alto acotado, imagen
    //    disponible (ningún aviso de error), contexto del fotograma y selección real.
    @Test
    fun elHostDeProduccionAcotaUnaImagenValidaMuyAltaSinPerderElContextoNiLaSeleccion() {
        val tall = frame(FRAME_TALL, assignedFrameIndex = 0).copy(imageWidth = 1, imageHeight = 1280)
        images[FRAME_TALL] = aspectBitmap(1, 1280, COLOR_A)
        val other = frame(FRAME_B, assignedFrameIndex = 17, color = COLOR_B)
        composeHost(contentOf(ROOT_A, listOf(tall, other)))

        assertBoundedCardImage(tall)
        rule.onNodeWithContentDescription(openInAnalysisLabel(18)).performClick()
        rule.waitForIdle()
        assertBoundedCardImage(other)
    }

    // 10. Host de producción con imagen VÁLIDA de aspecto extremo a lo ancho (1280×1): alto mínimo
    //     legible, sin recorte destructivo, contexto del fotograma y selección real.
    @Test
    fun elHostDeProduccionAcotaUnaImagenValidaMuyAnchaSinPerderElContextoNiLaSeleccion() {
        val wide = frame(FRAME_WIDE, assignedFrameIndex = 0).copy(imageWidth = 1280, imageHeight = 1)
        images[FRAME_WIDE] = aspectBitmap(1280, 1, COLOR_C)
        val other = frame(FRAME_B, assignedFrameIndex = 17, color = COLOR_B)
        composeHost(contentOf(ROOT_A, listOf(wide, other)))

        assertBoundedCardImage(wide)
        rule.onNodeWithContentDescription(openInAnalysisLabel(18)).performClick()
        rule.waitForIdle()
        assertBoundedCardImage(other)
    }

    // 11. Cabecera compacta a fuente 1.5: el nombre va primero y SIN prefijo de propiedad, la fecha
    //     debajo como dato secundario, los DOS conteos reales en UNA sola línea y el control del
    //     lote en su propio espacio de 48 dp al borde final. Un nombre largo envuelve sin recortarse
    //     ni solaparse, y los estados de la compartición (habilitada, puerta cerrada y lote en
    //     curso con progreso) siguen siendo los mismos, sin abrir ningún selector del sistema.
    @Test
    fun summaryHeader_isCompactAndKeepsSharingStateAtLargeFont() {
        val dark = mutableStateOf(true)
        val longOwner = mutableStateOf(false)
        val shareEnabled = mutableStateOf(true)
        val busy = mutableStateOf(false)
        val shareAllCalls = AtomicInteger()
        val shareSingleCalls = AtomicInteger()
        // DOS fotogramas con UNA marca cada uno: los conteos reales de la raíz son 2 y 2.
        val frames = listOf(
            frame(FRAME_HEADER_A, assignedFrameIndex = 0, color = COLOR_A)
                .copy(marks = listOf(markVisible)),
            frame(FRAME_HEADER_B, assignedFrameIndex = 1, color = COLOR_B)
                .copy(marks = listOf(markVisible)),
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.5f),
            ) {
                OpenJumpTheme(darkTheme = dark.value) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        AngleV2SavedDetailContentHost(
                            content = AngleV2SavedDetailContent(
                                rootId = ROOT_A,
                                createdAt = VISUAL_CREATED_AT,
                                athleteId = VISUAL_ATHLETE_ID,
                                frames = frames,
                                athleteName = if (longOwner.value) {
                                    LONG_ATHLETE_NAME
                                } else {
                                    HEADER_ATHLETE_NAME
                                },
                                // Avatar REAL del atleta dueño, de una clave del catálogo existente.
                                athleteAvatarKey = HEADER_AVATAR_KEY,
                            ),
                            imageLoader = { f ->
                                AngleV2SavedImage.Available(images.getValue(f.frameId))
                            },
                            shareEnabled = shareEnabled.value,
                            shareAllBusy = busy.value,
                            onShare = { shareSingleCalls.incrementAndGet() },
                            onShareAll = { shareAllCalls.incrementAndGet() },
                            padding = PaddingValues(0.dp),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val description = strings(R.string.angle_v2_saved_share_all_description, 2)
        // Identidad principal: el nombre real del atleta, SIN el prefijo de propiedad que sí
        // conserva el contexto de Información. No se busca el nombre como subcadena: el texto
        // prefijado NO debe existir en la cabecera.
        val nameBounds = rule.onNodeWithText(HEADER_ATHLETE_NAME)
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        // El avatar de la CABECERA es el del atleta dueño de la raíz: 40 dp, a la izquierda de la
        // columna de identidad (nombre/fecha/conteos) y en la misma fila que el nombre.
        val avatarBounds = rule.onNodeWithTag("angle-detail-owner-avatar")
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "el avatar del dueño debe medir 40 dp: $avatarBounds",
            abs(avatarBounds.width / densityScale - 40f) <= 2f &&
                abs(avatarBounds.height / densityScale - 40f) <= 2f,
        )
        assertTrue(
            "el avatar debe ir a la izquierda de la identidad: $avatarBounds vs $nameBounds",
            avatarBounds.right <= nameBounds.left + 1f,
        )
        assertTrue(
            "el avatar debe compartir la fila del nombre: $avatarBounds vs $nameBounds",
            avatarBounds.top < nameBounds.bottom && avatarBounds.bottom > nameBounds.top,
        )
        rule.onAllNodesWithText(strings(R.string.angle_detail_owner_name, HEADER_ATHLETE_NAME))
            .assertCountEquals(0)
        val dateBounds = rule.onNodeWithText(dateText(VISUAL_CREATED_AT))
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        // UNA sola línea de conteos con los DOS plurales reales: ni dos nodos sueltos ni el
        // recuento de otra región.
        val counts = headerCountsText(2, 2)
        rule.onAllNodesWithText(counts).assertCountEquals(1)
        val countsBounds = rule.onNodeWithText(counts).fetchSemanticsNode().boundsInRoot
        // El control del lote: descripción ÚNICA (el icono no la duplica), 48 dp reales, al borde
        // final y en su propia fila, sin mezclarse con los conteos.
        rule.onAllNodesWithContentDescription(description).assertCountEquals(1)
        val shareBounds = rule.onNodeWithContentDescription(description)
            .assertIsEnabled()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "el control del lote debe medir al menos 48 dp: $shareBounds",
            shareBounds.width / densityScale >= 47f && shareBounds.height / densityScale >= 47f,
        )
        assertTrue(
            "el control del lote debe quedar en el borde final: $shareBounds",
            shareBounds.left > rule.activity.resources.displayMetrics.widthPixels / 2f,
        )
        assertTrue(
            "la identidad no puede solaparse con el control del lote: " +
                "nombre $nameBounds, fecha $dateBounds, conteos $countsBounds vs $shareBounds",
            maxOf(nameBounds.right, dateBounds.right, countsBounds.right) <= shareBounds.left + 1f,
        )
        assertTrue(
            "el control del lote debe compartir la fila de identidad: $shareBounds vs $nameBounds",
            shareBounds.top < nameBounds.bottom && shareBounds.bottom > nameBounds.top,
        )
        assertTrue(
            "el nombre debe ser la identidad principal y la fecha ir debajo: $nameBounds vs $dateBounds",
            dateBounds.top >= nameBounds.bottom - 1f && nameBounds.height > dateBounds.height,
        )
        assertTrue(
            "los conteos deben ir debajo de la fecha y a la izquierda del control: " +
                "$countsBounds vs $dateBounds vs $shareBounds",
            countsBounds.top >= dateBounds.bottom - 1f && countsBounds.right <= shareBounds.left + 1f,
        )
        captureAnglePolishEvidence("08-header-twoframes-dark")

        // Nombre LARGO en CLARO: envuelve en varias líneas sin recortarse, sin salirse de la ventana
        // y sin empujar el control fuera de su columna.
        longOwner.value = true
        dark.value = false
        rule.waitForIdle()
        val longBounds = rule.onNodeWithText(LONG_ATHLETE_NAME)
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        val longShareBounds = rule.onNodeWithContentDescription(description)
            .assertIsEnabled()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "el nombre largo debe envolver (más de una línea) en vez de recortarse: $longBounds",
            longBounds.height / densityScale >= 40f,
        )
        assertTrue(
            "el nombre largo no puede salirse de la ventana: $longBounds",
            longBounds.right <= rule.activity.resources.displayMetrics.widthPixels + 1f,
        )
        assertTrue(
            "el nombre largo no puede solaparse con el control del lote: $longBounds vs $longShareBounds",
            longBounds.right <= longShareBounds.left + 1f,
        )
        assertTrue(
            "el control debe seguir en su espacio con el nombre largo: $longShareBounds",
            longShareBounds.width / densityScale >= 47f &&
                longShareBounds.left > rule.activity.resources.displayMetrics.widthPixels / 2f,
        )
        captureAnglePolishEvidence("09-header-twoframes-light-longowner")

        // Lote EN CURSO: el mismo control queda deshabilitado y anuncia que prepara (el indicador
        // de progreso sustituye al glifo). Sin selector del sistema: no se lanza ninguna Intent.
        busy.value = true
        rule.waitForIdle()
        val busyNode = rule.onNodeWithContentDescription(description)
            .assertIsNotEnabled()
            .fetchSemanticsNode()
        assertEquals(
            "el estado ocupado debe anunciarse en el propio control",
            strings(R.string.angle_v2_saved_share_all_busy),
            busyNode.config[SemanticsProperties.StateDescription],
        )
        assertEquals("con el lote en curso no se prepara nada nuevo", 0, shareAllCalls.get())
        assertEquals("y la compartición del fotograma tampoco se dispara", 0, shareSingleCalls.get())
        busy.value = false
        rule.waitForIdle()

        // Puerta compartida cerrada por el otro vuelo: el control sigue ahí pero no dispara; al
        // liberarla vuelve y comparte UNA sola vez.
        shareEnabled.value = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription(description).assertIsNotEnabled()
        assertEquals("una puerta cerrada no comparte", 0, shareAllCalls.get())
        shareEnabled.value = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription(description).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals("al liberar la puerta el lote se dispara una sola vez", 1, shareAllCalls.get())
        assertEquals(
            "la compartición de la CABECERA con varios fotogramas es la del LOTE, no la del actual",
            0,
            shareSingleCalls.get(),
        )
    }

    // 12. Pestañas de ancho COMPLETO con reparto IGUAL (3 → un tercio, 2 → mitad) y avatar REAL
    //     del atleta dueño de la raíz: se pinta un bitmap de verdad, cambia con la clave y vuelve a
    //     iniciales con una clave desconocida. Fuente 1.5, los dos temas y capturas nuevas.
    @Test
    fun fullWidthTabsAndRootAthleteAvatarForOneAndManyFrames() {
        val avatarKey = mutableStateOf(HEADER_AVATAR_KEY)
        val frames = mutableStateOf(
            listOf(
                frame(FRAME_HEADER_A, assignedFrameIndex = 0, color = COLOR_A),
                frame(FRAME_HEADER_B, assignedFrameIndex = 1, color = COLOR_B),
            ),
        )
        val dark = mutableStateOf(true)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.5f),
            ) {
                OpenJumpTheme(darkTheme = dark.value) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        AngleV2SavedDetailContentHost(
                            content = AngleV2SavedDetailContent(
                                rootId = ROOT_A,
                                createdAt = VISUAL_CREATED_AT,
                                athleteId = VISUAL_ATHLETE_ID,
                                frames = frames.value,
                                athleteName = VISUAL_ATHLETE_NAME,
                                athleteAvatarKey = avatarKey.value,
                            ),
                            imageLoader = { f ->
                                AngleV2SavedImage.Available(images.getValue(f.frameId))
                            },
                            shareEnabled = true,
                            shareAllBusy = false,
                            onShare = {},
                            onShareAll = {},
                            padding = PaddingValues(0.dp),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val viewportWidth = rule.activity.resources.displayMetrics.widthPixels.toFloat()

        // DOS fotogramas → TRES pestañas de un tercio, con la etiqueta centrada y seleccionable.
        assertEqualWidthTabs(listOf("analysis", "frames", "info"), viewportWidth, slots = 3)
        // El avatar REAL del dueño se pinta desde su clave de catálogo (no un genérico).
        val firstAvatar = avatarPixels()
        captureAnglePolishEvidence("10-tabs-avatar-three-dark")

        // Cambiar la clave del dueño cambia los PÍXELES pintados, no sólo una descripción.
        avatarKey.value = "avatar_frog_ninja"
        rule.waitForIdle()
        val secondAvatar = avatarPixels()
        assertTrue(
            "cambiar la clave del dueño debe repintar el avatar con otros píxeles",
            !firstAvatar.contentEquals(secondAvatar),
        )

        // Clave desconocida: respaldo por INICIALES del nombre real, sin recurso nuevo ni fallo.
        avatarKey.value = "avatar_retirado_desconocido"
        rule.waitForIdle()
        rule.onNodeWithTag("angle-detail-owner-avatar").assertExists()
        rule.onNodeWithText("L").assertExists()

        // UN fotograma → DOS pestañas de media anchura, sin pestaña de galería (tema claro).
        frames.value = listOf(frames.value.first())
        dark.value = false
        rule.waitForIdle()
        rule.onAllNodesWithTag(tabTag("frames")).assertCountEquals(0)
        assertEqualWidthTabs(listOf("analysis", "info"), viewportWidth, slots = 2)
        captureAnglePolishEvidence("11-tabs-avatar-two-light")
    }

    // ---------------------------------------------------------------------------------------------
    // Infraestructura de la prueba (sin tocar el código de producción).
    // ---------------------------------------------------------------------------------------------

    private fun composeHost(content: AngleV2SavedDetailContent) {
        rule.setContent {
            OpenJumpTheme {
                AngleV2SavedDetailContentHost(
                    content = content,
                    // Proveedor INMEDIATO de la fixture: entrega la MISMA imagen ya disponible y
                    // anota la lectura, para que el contrato «una lectura por fotograma» sea
                    // observable también en los casos que no necesitan cargas controladas.
                    imageLoader = { frame ->
                        calls.getOrPut(frame.frameId) { AtomicInteger() }.incrementAndGet()
                        AngleV2SavedImage.Available(images.getValue(frame.frameId))
                    },
                    shareEnabled = true,
                    shareAllBusy = false,
                    onShare = {},
                    onShareAll = {},
                    padding = PaddingValues(0.dp),
                )
            }
        }
        rule.waitForIdle()
    }

    /**
     * Igual que [composeHost] pero con el cargador CONTROLADO: la prueba decide cuándo termina.
     * [loader] permite que un caso use su propio cargador contador; el resto conserva el de siempre.
     */
    private fun composeHostGated(
        content: AngleV2SavedDetailContent,
        loader: suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage = gatedLoader(),
    ) {
        rule.setContent {
            OpenJumpTheme {
                AngleV2SavedDetailContentHost(
                    content = content,
                    imageLoader = loader,
                    shareEnabled = true,
                    shareAllBusy = false,
                    onShare = {},
                    onShareAll = {},
                    padding = PaddingValues(0.dp),
                )
            }
        }
        rule.waitForIdle()
    }

    /** Cargador controlado: cuenta la llamada y espera a que la prueba libere esa petición. */
    private fun gatedLoader(): suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage = { frame ->
        calls.getOrPut(frame.frameId) { AtomicInteger() }.incrementAndGet()
        gate(frame.frameId).await()
    }

    private fun gate(frameId: Long): CompletableDeferred<AngleV2SavedImage> =
        synchronized(gates) { gates.getOrPut(frameId) { CompletableDeferred() } }

    private fun release(frameId: Long, image: AngleV2SavedImage) {
        gate(frameId).complete(image)
        rule.waitForIdle()
    }

    private fun callCount(frameId: Long): Int = calls[frameId]?.get() ?: 0

    /**
     * Cargador controlado PROPIO del caso de transferencia, gobernado por el [HandoffLedger].
     *
     * Cada invocación recibe su identidad, su `Job` y una puerta INDEPENDIENTE creada al arrancar,
     * así que una lectura adicional no puede quedar oculta por una puerta ya completada ni
     * resolverse sin que la prueba la libere. Si Compose pide cancelación, se anota CANCELLED y se
     * relanza; una imagen que llegue a una invocación ya relanzada NO se devuelve. Sólo este caso lo
     * usa: el cargador global no cambia de semántica.
     */
    private fun ledgerGatedLoader(
        ledger: HandoffLedger,
    ): suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage = loader@{ frame ->
        calls.getOrPut(frame.frameId) { AtomicInteger() }.incrementAndGet()
        val entry = ledger.start(frame.frameId, currentCoroutineContext()[Job])
        try {
            val image = entry.gate.await()
            // Si esta carga ya fue relanzada, la imagen que acaba de llegar no puede publicarse.
            currentCoroutineContext().ensureActive()
            ledger.returned(entry)
            image
        } catch (cancellation: CancellationException) {
            ledger.cancelled(entry)
            throw cancellation
        } finally {
            ledger.finished(entry)
        }
    }

    /**
     * Asienta el libro antes de leerlo: pasadas ACOTADAS de Compose (`waitForIdle`), sin sleeps ni
     * esperas por reloj. Relanzar un efecto o recomponer es trabajo de UI, no una condición
     * temporal; si tras estas pasadas no hay UNA sola invocación viva por fotograma, la aserción lo
     * dice con el libro volcado en lugar de esperar más o ampliar el margen.
     */
    private fun settleHandoffLedger() {
        repeat(HANDOFF_SETTLE_PASSES) { rule.waitForIdle() }
    }

    /**
     * Libera EXACTAMENTE la puerta de ESA invocación (no una puerta compartida por fotograma) con la
     * MISMA referencia de bitmap de la fixture. Si la invocación ya no estaba viva, falla aquí: no se
     * puede acreditar una transferencia sobre una invocación que Compose relanzó.
     */
    private fun releaseInvocation(entry: HandoffEntry, image: AngleV2SavedImage) {
        assertTrue(
            "la invocación #${entry.id} de ${entry.frameId} ya no estaba viva al liberarla",
            entry.gate.complete(image),
        )
        rule.waitForIdle()
    }

    /**
     * Traza compacta del caso controlado de transferencia, impresa en el momento (no acumulada), así
     * que sobrevive a cualquier fallo posterior de la prueba. Una línea por etapa con los arranques
     * vigentes de B y C, sus invocaciones vivas y el volcado del libro.
     */
    private fun handoffProbe(stage: String, ledger: HandoffLedger) {
        println(
            "ANGLE_HANDOFF_PROBE stage=$stage" +
                " Bcalls=${ledger.starts(FRAME_B)} Ccalls=${ledger.starts(FRAME_C)}" +
                " Blive=${ledger.liveCount(FRAME_B)} Clive=${ledger.liveCount(FRAME_C)}" +
                " Bsuccess=${ledger.successfulReturns(FRAME_B)}" +
                " Csuccess=${ledger.successfulReturns(FRAME_C)}" +
                " " + ledger.describe(stage),
        )
    }

    /** Una invocación registrada; su estado se lee SIEMPRE bajo el candado del libro. */
    private class HandoffEntry(
        val id: Int,
        val frameId: Long,
        val gate: CompletableDeferred<AngleV2SavedImage>,
        val job: Job?,
    ) {
        var cancelled = false
        var returned = false
        var finished = false
        var duplicateOfLive = false
        var returnedAfterCancel = false

        /**
         * ÚNICA noción de «sigue activa»: un `Job` ya cancelado (aunque todavía no haya ejecutado
         * su `finally`) NO está activo, y una invocación devuelta o terminada tampoco.
         */
        fun isLive(): Boolean =
            !returned && !cancelled && !finished && (job == null || job.isActive)
    }

    /**
     * Libro de registro LOCAL del caso controlado de transferencia: UNA entrada por cada invocación
     * REAL del proveedor, con identidad, fotograma, `Job` de la corrutina que la hizo, puerta
     * INDEPENDIENTE y estado (viva / cancelada / devuelta / terminada).
     *
     * No es un contador de llamadas: acredita QUÉ invocaciones siguen vivas. Una invocación a la que
     * Compose pidió cancelación queda CANCELADA y sin imagen devuelta, así que la siguiente puede ser
     * la única viva. Una invocación simultánea cuyo `Job` sigue activo es un DUPLICADO CONCURRENTE y
     * se engancha de forma PERMANENTE: el caso falla con el libro volcado en vez de quedarse con la
     * última de varias vivas. Todos los accesos van bajo candado: las invocaciones pueden venir de
     * cualquier hilo, no sólo del hilo principal.
     */
    private class HandoffLedger(
        private val frozenFrameB: Long,
        private val frozenFrameC: Long,
    ) {

        private val lock = Any()
        private val entries = mutableListOf<HandoffEntry>()
        private var nextId = 0
        private var latched = 0

        /** Registra una invocación; si ya había una VIVA del mismo fotograma, la engancha. */
        fun start(frameId: Long, job: Job?): HandoffEntry = synchronized(lock) {
            val entry = HandoffEntry(
                id = nextId++,
                frameId = frameId,
                gate = CompletableDeferred(),
                job = job,
            )
            if (entries.any { it.frameId == frameId && it.isLive() }) {
                entry.duplicateOfLive = true
                latched++
            }
            entries += entry
            entry
        }

        fun returned(entry: HandoffEntry) {
            synchronized(lock) {
                if (entry.cancelled) entry.returnedAfterCancel = true
                entry.returned = true
            }
        }

        fun cancelled(entry: HandoffEntry) {
            synchronized(lock) { entry.cancelled = true }
        }

        fun finished(entry: HandoffEntry) {
            synchronized(lock) { entry.finished = true }
        }

        fun starts(frameId: Long): Int = synchronized(lock) { entries.count { it.frameId == frameId } }

        fun liveCount(frameId: Long): Int =
            synchronized(lock) { entries.count { it.frameId == frameId && it.isLive() } }

        fun successfulReturns(frameId: Long): Int = synchronized(lock) {
            entries.count { it.frameId == frameId && it.returned && !it.cancelled }
        }

        /** Única invocación VIVA del fotograma; si no hay exactamente una, falla con el libro. */
        fun requireSingleLive(frameId: Long, stage: String): HandoffEntry = synchronized(lock) {
            val live = entries.filter { it.frameId == frameId && it.isLive() }
            if (live.size != 1) {
                fail(
                    describe(
                        "en '$stage' se esperaba UNA invocación viva de $frameId y hay ${live.size}",
                    ),
                )
            }
            live.single()
        }

        /**
         * Los arranques de B y C siguen EXACTAMENTE en la línea base congelada antes de liberar
         * nada, y no se ha enganchado ningún duplicado concurrente.
         */
        fun assertFrozenStarts(stage: String, frozenB: Int, frozenC: Int) {
            synchronized(lock) {
                val nowB = entries.count { it.frameId == frozenFrameB }
                val nowC = entries.count { it.frameId == frozenFrameC }
                if (nowB != frozenB || nowC != frozenC || latched != 0) {
                    fail(
                        describe(
                            "en '$stage' cambió la línea base congelada: " +
                                "B $frozenB→$nowB, C $frozenC→$nowC",
                        ),
                    )
                }
            }
        }

        fun assertNoDuplicatesLatched(stage: String) {
            synchronized(lock) {
                if (latched != 0 || entries.any { it.duplicateOfLive }) {
                    fail(describe("en '$stage' se enganchó un duplicado concurrente"))
                }
            }
        }

        /**
         * Ninguna invocación previa del fotograma puede seguir viva ni haber devuelto imagen: si
         * Compose relanzó esa celda, sus intentos anteriores tienen que estar CANCELADOS.
         */
        fun assertPreviousAttemptsCancelled(frameId: Long, expected: HandoffEntry, stage: String) {
            synchronized(lock) {
                val survivors = entries.filter {
                    it.frameId == frameId && it.id != expected.id && it.isLive()
                }
                if (survivors.isNotEmpty()) {
                    fail(describe("en '$stage' quedan invocaciones vivas de $frameId además de la suya"))
                }
                val returnedOthers = entries.filter {
                    it.frameId == frameId && it.id != expected.id && it.returned
                }
                if (returnedOthers.isNotEmpty()) {
                    fail(describe("en '$stage' una lectura previa de $frameId devolvió imagen"))
                }
                val notCancelled = entries.filter {
                    it.frameId == frameId && it.id != expected.id && !it.cancelled
                }
                if (notCancelled.isNotEmpty()) {
                    fail(describe("en '$stage' hay intentos previos de $frameId que no se cancelaron"))
                }
            }
        }

        /** Volcado COMPACTO del libro: qué invocaciones existen y en qué estado. */
        fun describe(reason: String): String = synchronized(lock) {
            val detail = entries.joinToString(separator = " ") { entry ->
                val state = when {
                    entry.returned -> "RETURNED"
                    entry.cancelled -> "CANCELLED"
                    entry.isLive() -> "ACTIVE"
                    else -> "FINISHED"
                }
                val dup = if (entry.duplicateOfLive) ",DUPLICATE" else ""
                val after = if (entry.returnedAfterCancel) ",RETURNED-AFTER-CANCEL" else ""
                "#${entry.id}(frame=${entry.frameId},job=$state$dup$after)"
            }
            "ledger[$reason] latched=$latched entries=$detail"
        }
    }

    private fun contentOf(
        rootId: Long,
        frames: List<AngleV2SavedDetailFrame>,
    ): AngleV2SavedDetailContent = AngleV2SavedDetailContent(
        rootId = rootId,
        createdAt = 0L,
        athleteId = null,
        frames = frames,
    )

    private fun frame(
        frameId: Long,
        assignedFrameIndex: Int,
        sha: String = SHA_A,
        color: Int = COLOR_A,
    ): AngleV2SavedDetailFrame {
        images[frameId] = images[frameId] ?: solidBitmap(color)
        return AngleV2SavedDetailFrame(
            ordinal = assignedFrameIndex,
            frameId = frameId,
            assignedFrameIndex = assignedFrameIndex,
            reportedFrameIndex = assignedFrameIndex,
            presentationWidth = BITMAP_WIDTH,
            presentationHeight = BITMAP_HEIGHT,
            rotationDegrees = 0,
            imageKey = "angle-images/frame-$frameId.png",
            imageWidth = BITMAP_WIDTH,
            imageHeight = BITMAP_HEIGHT,
            imageByteCount = 2048L,
            imageSha256 = sha,
            imageMimeType = "image/png",
            marks = listOf(markVisible, markHidden),
        )
    }

    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    /** Bitmap REAL con el MISMO aspecto declarado por el fotograma, sin decorar la fixture. */
    private fun aspectBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /**
     * Comprueba la caja REAL de la imagen del fotograma en Análisis: ancho real, alto acotado entre
     * el mínimo legible (48 dp) y el máximo de la familia (480 dp), y el número REAL de fotograma
     * asociado a ESA imagen. Nada de muestrear un color central con antialias: el aspecto extremo
     * deja la imagen en un píxel de ancho o de alto.
     */
    private fun assertBoundedCardImage(frame: AngleV2SavedDetailFrame) {
        rule.waitForIdle()
        val node = rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG)
            .assertExists()
            .fetchSemanticsNode()
        val heightDp = node.boundsInRoot.height / densityScale
        assertTrue(
            "la imagen debe ocupar ancho real: ${node.boundsInRoot}",
            node.boundsInRoot.width > 0f,
        )
        assertTrue("alto $heightDp dp por debajo del mínimo de 48 dp", heightDp >= 47f)
        assertTrue("alto $heightDp dp por encima del máximo de 480 dp", heightDp <= 481f)
        rule.onNodeWithContentDescription(
            strings(R.string.angle_multi_frame_frame_label, frame.displayFrameNumber),
        ).assertExists()
        rule.onAllNodesWithText(strings(R.string.angle_v2_saved_image_unavailable))
            .assertCountEquals(0)
    }

    /**
     * Escribe una captura PNG REAL del host durante la EJECUCIÓN de la prueba.
     *
     * La instrumentación corre con el uid de la APP bajo prueba, así que el directorio se toma del
     * `targetContext` (nunca del contexto de pruebas privado) y de su caché EXTERNA, que la app
     * puede escribir sin permisos. Es una salida de QA fuera de todos los hashes internos; no se
     * toca ninguna carpeta de datos de usuario. La ruta se imprime para que se puedan extraer los
     * PNG con `adb` después de la ejecución.
     */
    private fun captureAnglePolishEvidence(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        assertEquals(
            "la evidencia visual se escribe sólo para la app bajo prueba",
            TARGET_PACKAGE,
            targetContext.packageName,
        )
        val externalCacheDir = requireNotNull(targetContext.externalCacheDir) {
            "la app bajo prueba debe exponer una caché externa para la evidencia visual"
        }
        val dir = File(externalCacheDir, ANGLE_POLISH_UI_EVIDENCE_PREFIX + evidenceRunId)
        if (!dir.isDirectory) {
            assertTrue("no se pudo crear el directorio de evidencia $dir", dir.mkdirs())
        }
        println("ANGLE_POLISH_UI_EVIDENCE_DIR=" + dir.absolutePath)
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { stream ->
            assertTrue(
                "la captura $name no se pudo comprimir en PNG",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream),
            )
        }
        assertTrue("la captura $name debe existir y no estar vacía", file.length() > 0L)
    }

    private fun assertImagePending() {
        rule.onNodeWithText(pendingText()).assertIsDisplayed()
        rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG).assertDoesNotExist()
    }

    /**
     * Píxel REAL pintado por el nodo de imagen (no una leyenda): el centro necesita cubrirse con el
     * color sólido del bitmap, con la tolerancia de la familia.
     */
    private fun assertRendersColor(expected: Int) {
        rule.waitForIdle()
        val node = rule.onNodeWithTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG)
        node.assertIsDisplayed()
        val pixels = node.captureToImage().toPixelMap()
        val actual = pixels[pixels.width / 2, pixels.height / 2].toArgb()
        assertTrue(
            "color pintado ${Integer.toHexString(actual)} no coincide con " +
                Integer.toHexString(expected),
            abs((actual shr 16 and 0xFF) - (expected shr 16 and 0xFF)) <= COLOR_TOLERANCE &&
                abs((actual shr 8 and 0xFF) - (expected shr 8 and 0xFF)) <= COLOR_TOLERANCE &&
                abs((actual and 0xFF) - (expected and 0xFF)) <= COLOR_TOLERANCE,
        )
    }

    /**
     * Espera acotada a que la celda de la galería tenga la imagen REAL disponible: la descripción
     * accesible única del fotograma sólo existe cuando la celda pinta [AngleV2SavedImage.Available],
     * nunca con una leyenda de espera. Sin sleeps ni comprobaciones por texto de leyenda.
     */
    private fun awaitGalleryCellImage(displayFrameNumber: Int) {
        rule.waitUntil(timeoutMillis = 5_000L) {
            rule.onAllNodesWithContentDescription(frameImageLabel(displayFrameNumber))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * Imagen REAL de una celda de la galería: caja con tamaño dentro de la ventana y píxel central
     * del color esperado. La descripción accesible del fotograma identifica la imagen disponible
     * (las variantes de espera o error son texto, no imagen), así que esto no acredita una leyenda.
     */
    private fun assertGalleryCellRendersColor(displayFrameNumber: Int, expected: Int) {
        rule.waitForIdle()
        val label = frameImageLabel(displayFrameNumber)
        val node = rule.onNodeWithContentDescription(label).assertExists().fetchSemanticsNode()
        assertTrue(
            "la celda $displayFrameNumber debe tener una caja real: ${node.boundsInRoot}",
            node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f,
        )
        assertTrue(
            "la celda $displayFrameNumber debe caber en la ventana: ${node.boundsInRoot}",
            node.boundsInRoot.bottom <= rule.activity.resources.displayMetrics.heightPixels + 1f,
        )
        val pixels = rule.onNodeWithContentDescription(label).captureToImage().toPixelMap()
        val actual = pixels[pixels.width / 2, pixels.height / 2].toArgb()
        assertTrue(
            "color pintado en la celda $displayFrameNumber ${Integer.toHexString(actual)} no " +
                "coincide con ${Integer.toHexString(expected)}",
            abs((actual shr 16 and 0xFF) - (expected shr 16 and 0xFF)) <= COLOR_TOLERANCE &&
                abs((actual shr 8 and 0xFF) - (expected shr 8 and 0xFF)) <= COLOR_TOLERANCE &&
                abs((actual and 0xFF) - (expected and 0xFF)) <= COLOR_TOLERANCE,
        )
    }

    /**
     * Píxel REAL de la ÚLTIMA celda de la galería (la que termina de cargar DESPUÉS de B).
     *
     * Su descripción accesible única sólo existe cuando la celda pinta [AngleV2SavedImage.Available],
     * así que no se acredita una leyenda. El píxel se muestrea sobre la captura de la RAÍZ en las
     * coordenadas del nodo: esta celda es el último elemento de la lista y su borde inferior puede
     * quedar bajo el pliegue, y lo que se comprueba aquí es que pinta SU bitmap con el color
     * esperado, no que la última celda de la lista quepa entera en la ventana. La comprobación de
     * caja dentro de la ventana sigue vigente, sin cambios, para la celda que se pulsa.
     */
    private fun assertLastGalleryCellRendersColor(displayFrameNumber: Int, expected: Int) {
        rule.waitForIdle()
        val label = frameImageLabel(displayFrameNumber)
        val node = rule.onNodeWithContentDescription(label).assertExists().fetchSemanticsNode()
        assertTrue(
            "la celda $displayFrameNumber debe tener una caja real: ${node.boundsInRoot}",
            node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f,
        )
        val window = rule.onRoot().captureToImage().toPixelMap()
        val x = node.boundsInRoot.center.x.toInt().coerceIn(0, window.width - 1)
        val y = node.boundsInRoot.center.y.toInt().coerceIn(0, window.height - 1)
        val actual = window[x, y].toArgb()
        assertTrue(
            "color pintado en la celda $displayFrameNumber ${Integer.toHexString(actual)} no " +
                "coincide con ${Integer.toHexString(expected)}",
            abs((actual shr 16 and 0xFF) - (expected shr 16 and 0xFF)) <= COLOR_TOLERANCE &&
                abs((actual shr 8 and 0xFF) - (expected shr 8 and 0xFF)) <= COLOR_TOLERANCE &&
                abs((actual and 0xFF) - (expected and 0xFF)) <= COLOR_TOLERANCE,
        )
    }

    /**
     * Las pestañas pedidas ocupan TODO el ancho visible repartidas a partes iguales.
     *
     * Comprueba el ancho de cada una contra `ventana / slots`, que la suma cubre el ancho (sin
     * hueco muerto ni desplazamiento), que TODAS exponen el rol nativo de pestaña con su selección,
     * y que la etiqueta real de cada una está centrada dentro de su pestaña y no se sale de ella
     * (fuente 1.5). No toca la navegación: eso lo cubren los casos existentes.
     */
    private fun assertEqualWidthTabs(tabs: List<String>, viewportWidth: Float, slots: Int) {
        val expectedWidth = viewportWidth / slots
        val bounds = tabs.associateWith { tab ->
            rule.onNodeWithTag(tabTag(tab)).assertExists().fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach { (tab, tabBounds) ->
            assertTrue(
                "la pestaña $tab debe medir ~1/$slots del ancho visible: $tabBounds vs $expectedWidth",
                abs(tabBounds.width - expectedWidth) <= densityScale + 1f,
            )
            assertEquals(
                "la pestaña $tab debe exponer el rol nativo de pestaña",
                Role.Tab,
                rule.onNodeWithTag(tabTag(tab)).fetchSemanticsNode().config[SemanticsProperties.Role],
            )
        }
        val totalWidth = bounds.values.sumOf { it.width.toDouble() }.toFloat()
        assertTrue(
            "las pestañas deben cubrir el ancho visible sin hueco muerto: $totalWidth vs $viewportWidth",
            abs(totalWidth - viewportWidth) <= 3f * densityScale,
        )
        val labels = mapOf(
            "analysis" to R.string.angle_polish_tab_analysis,
            "frames" to R.string.angle_polish_tab_frames,
            "info" to R.string.angle_polish_tab_info,
        )
        tabs.forEach { tab ->
            val tabBounds = bounds.getValue(tab)
            val labelBounds = rule.onNodeWithText(strings(labels.getValue(tab)))
                .assertExists()
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue(
                "la etiqueta de $tab no puede salirse de su pestaña: $labelBounds vs $tabBounds",
                labelBounds.left >= tabBounds.left - 1f && labelBounds.right <= tabBounds.right + 1f,
            )
            assertTrue(
                "la etiqueta de $tab debe quedar centrada: $labelBounds vs $tabBounds",
                abs(labelBounds.center.x - tabBounds.center.x) <= 2f * densityScale,
            )
        }
        rule.onNodeWithTag(tabTag(tabs.first())).assertIsSelected()
        tabs.drop(1).forEach { tab -> rule.onNodeWithTag(tabTag(tab)).assertIsNotSelected() }
    }

    /** Píxeles REALES del avatar del dueño, capturados del propio nodo que se está pintando. */
    private fun avatarPixels(): IntArray {
        val bitmap = rule.onNodeWithTag("angle-detail-owner-avatar")
            .captureToImage()
            .asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun tabTag(tab: String): String = "angle-tab-$tab"

    private val locale: Locale
        get() = rule.activity.resources.configuration.locales[0]

    private fun strings(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private fun framesCountText(count: Int): String =
        rule.activity.resources.getQuantityString(
            R.plurals.angle_polish_frames_count,
            count,
            count,
        )

    /**
     * Texto EXACTO de la línea compacta de conteos de la cabecera: los dos plurales localizados
     * unidos por el mismo punto medio neutro que usa producción (puntuación, no texto de producto).
     */
    private fun headerCountsText(frames: Int, marks: Int): String =
        framesCountText(frames) + " \u00b7 " + rule.activity.resources.getQuantityString(
            R.plurals.angle_polish_marks_count,
            marks,
            marks,
        )

    /** Fecha REAL de la cabecera: la misma función de formateo y el mismo locale de la app. */
    private fun dateText(createdAt: Long): String = Formatting.dateTimeToText(createdAt, locale)

    /** Densidad REAL de la ventana de la prueba, para leer cotas en dp sin depender del tema. */
    private val densityScale: Float
        get() = rule.activity.resources.displayMetrics.density

    private fun frameLabelAt(displayFrameNumber: Int): String =
        strings(R.string.angle_saved_frame, displayFrameNumber)

    /** Descripción accesible de la imagen pintada: única por fotograma (la leyenda puede repetirse). */
    private fun frameImageLabel(displayFrameNumber: Int): String =
        strings(R.string.angle_multi_frame_frame_label, displayFrameNumber)

    private fun openInAnalysisLabel(displayFrameNumber: Int): String =
        strings(R.string.angle_polish_open_in_analysis, displayFrameNumber)

    private fun shareDescription(displayFrameNumber: Int): String =
        strings(R.string.angle_v2_saved_share_frame_image, displayFrameNumber)

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

    private companion object {
        const val ROOT_A = 7L
        const val ROOT_B = 9L
        const val ROOT_C = 11L
        const val FRAME_A = 101L
        const val FRAME_B = 102L
        const val FRAME_C = 103L
        const val FRAME_D = 104L
        const val FRAME_E = 105L
        const val FRAME_F = 106L
        const val FRAME_G = 107L
        const val FRAME_VISUAL_A = 201L
        const val FRAME_VISUAL_B = 202L
        const val FRAME_VISUAL_C = 203L
        const val FRAME_TALL = 301L
        const val FRAME_WIDE = 302L
        const val FRAME_HEADER_A = 401L
        const val FRAME_HEADER_B = 402L
        const val HEADER_ATHLETE_NAME = "Atleta1"
        const val HEADER_AVATAR_KEY = "avatar_frog_jump"
        const val LONG_ATHLETE_NAME =
            "Atleta1 de la Peña Fernández de Córdoba y Ortiz de Zárate"
        const val BITMAP_WIDTH = 64
        const val BITMAP_HEIGHT = 48
        const val COLOR_TOLERANCE = 8
        const val HANDOFF_SETTLE_PASSES = 4
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val TARGET_PACKAGE = "com.openjump.app"
        const val ANGLE_POLISH_UI_EVIDENCE_PREFIX = "angle-polish-ui-evidence-"
        const val VISUAL_CREATED_AT = 1_700_000_000_000L
        const val VISUAL_ATHLETE_ID = 5L
        const val VISUAL_ATHLETE_NAME = "Lucía"
        val COLOR_A = 0xFF1E88E5.toInt()
        val COLOR_B = 0xFF43A047.toInt()
        val COLOR_C = 0xFFFDD835.toInt()
        val COLOR_LATE = 0xFFE53935.toInt()
    }
}
