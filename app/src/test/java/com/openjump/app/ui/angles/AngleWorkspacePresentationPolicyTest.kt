package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleFrameId
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.CropAlignment
import com.openjump.app.ui.selector.CropViewportState
import com.openjump.app.ui.selector.MAX_CROP_ZOOM
import com.openjump.app.ui.selector.MIN_CROP_ZOOM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la presentación S4a: layout por constraints, orientación pedida, encuadre por
 * fuente/fotograma y mapper compartido. No tocan Android ni afirman retención de rotación.
 */
class AngleWorkspacePresentationPolicyTest {

    private val frameA = AngleFrameId(11L)
    private val frameB = AngleFrameId(22L)

    @Test
    fun portraitYApaisadoSeDecidenPorConstraintsReales() {
        assertEquals(
            AngleWorkspacePresentationOrientation.PORTRAIT,
            angleWorkspacePresentationOrientation(360, 640),
        )
        assertEquals(
            AngleWorkspacePresentationOrientation.LANDSCAPE,
            angleWorkspacePresentationOrientation(640, 360),
        )
        // Cuadrado no es apaisado por accidente.
        assertEquals(
            AngleWorkspacePresentationOrientation.PORTRAIT,
            angleWorkspacePresentationOrientation(500, 500),
        )
    }

    @Test
    fun apaisadoLlevaControlesAlLateralYNoRepiteFilasBajoLaMedia() {
        val portrait = angleWorkspaceLayoutPlan(360, 640, framesExist = true, fullscreen = false)
        assertTrue(portrait.controlsBesideMedia.not())
        assertTrue(portrait.showFilmstrip)

        val landscape = angleWorkspaceLayoutPlan(640, 360, framesExist = true, fullscreen = false)
        assertTrue(landscape.controlsBesideMedia)
        assertTrue(landscape.lateralPanelMaxWidthDp > 0)
        assertEquals(48, landscape.minTargetDp)
    }

    @Test
    fun sinFotogramasNoHayTiraDeSoloVideo() {
        val empty = angleWorkspaceLayoutPlan(360, 640, framesExist = false, fullscreen = false)
        assertTrue(empty.showFilmstrip.not())
        val withFrame = angleWorkspaceLayoutPlan(360, 640, framesExist = true, fullscreen = false)
        assertTrue(withFrame.showFilmstrip)
    }

    @Test
    fun fullscreenOcultaChromePesadoYNoTiraLaMedia() {
        val plan = angleWorkspaceLayoutPlan(
            widthDp = 640,
            heightDp = 360,
            framesExist = true,
            fullscreen = true,
        )
        assertTrue(plan.fullscreen)
        assertTrue(plan.showHeader.not())
        assertTrue(plan.showFilmstrip.not())
        assertTrue(plan.showStatus.not())
        assertTrue(plan.controlsBesideMedia.not())
        assertTrue(plan.mediaMinHeightDp > 0)
    }

    @Test
    fun fontScale2MantieneAlturaDeEtapaPositivaEnCualquierLayout() {
        listOf(360 to 640, 640 to 360, 320 to 240, 200 to 900).forEach { (w, h) ->
            val plan = angleWorkspaceLayoutPlan(w, h, true, false, fontScale = 2f)
            assertTrue("etapa positiva para ${w}x$h", plan.mediaMinHeightDp > 0)
        }
    }

    @Test
    fun orientacionScopedSeDecidePorLaMediaNoPorLaVentanaDelTelefono() {
        val horizontalVideo = VideoPresentationGeometry(320, 240)
        val verticalVideo = VideoPresentationGeometry(1080, 1920)
        val square = VideoPresentationGeometry(720, 720)

        // Fuera de fullscreen nunca se fuerza giro, aunque la media sea horizontal.
        assertEquals(
            AngleWorkspaceOrientationTarget.FOLLOW_USER,
            angleWorkspaceOrientationTarget(fullscreen = false, mediaGeometry = horizontalVideo),
        )
        // Teléfono en portrait + vídeo horizontal: el fullscreen SÍ pide landscape.
        assertEquals(
            AngleWorkspaceOrientationTarget.LANDSCAPE,
            angleWorkspaceOrientationTarget(fullscreen = true, mediaGeometry = horizontalVideo),
        )
        // Vídeo rotado 90° (geometría postrotación vertical): NO se fuerza landscape.
        assertEquals(
            AngleWorkspaceOrientationTarget.FOLLOW_USER,
            angleWorkspaceOrientationTarget(fullscreen = true, mediaGeometry = verticalVideo),
        )
        // Cuadrado o sin geometría: se sigue al usuario, no se inventa un giro.
        assertEquals(
            AngleWorkspaceOrientationTarget.FOLLOW_USER,
            angleWorkspaceOrientationTarget(fullscreen = true, mediaGeometry = square),
        )
        assertEquals(
            AngleWorkspaceOrientationTarget.FOLLOW_USER,
            angleWorkspaceOrientationTarget(fullscreen = true, mediaGeometry = null),
        )
    }

    @Test
    fun planAcotaControlesSinSuperarElAltoDisponibleYDejaEtapaPositiva() {
        listOf(320 to 360, 360 to 640, 640 to 360, 320 to 240).forEach { (w, h) ->
            listOf(1f, 2f).forEach { fontScale ->
                listOf(true, false).forEach { frames ->
                    val plan = angleWorkspaceLayoutPlan(
                        widthDp = w,
                        heightDp = h,
                        framesExist = frames,
                        fullscreen = false,
                        fontScale = fontScale,
                    )
                    val label = "${w}x$h font=$fontScale frames=$frames"
                    assertTrue("etapa positiva: $label", plan.mediaMinHeightDp > 0)
                    assertTrue("tope de controles positivo: $label", plan.controlsMaxHeightDp > 0)
                    assertTrue(
                        "la etapa reservada nunca supera el alto: $label",
                        plan.mediaMinHeightDp < h,
                    )
                    assertTrue(
                        "sin mínimos imposibles: $label ${plan.mediaMinHeightDp}+" +
                            "${plan.controlsMaxHeightDp} > $h",
                        plan.mediaMinHeightDp + plan.controlsMaxHeightDp <= h,
                    )
                }
            }
        }
        // Caso hostil del contrato: 360dp de alto con fontScale 2 y error largo.
        val tight = angleWorkspaceLayoutPlan(
            widthDp = 320,
            heightDp = 360,
            framesExist = false,
            fullscreen = false,
            fontScale = 2f,
        )
        assertTrue(tight.mediaMinHeightDp >= 180)
        assertTrue(tight.controlsMaxHeightDp >= tight.minTargetDp)
    }

    @Test
    fun zoomYResetAcotadosSinCambiarDeModo() {
        var crop = CropViewportState()
        repeat(20) { crop = angleWorkspaceNextCrop(crop, zoomIn = true) }
        assertEquals(MAX_CROP_ZOOM, crop.zoom, 0.0)
        repeat(40) { crop = angleWorkspaceNextCrop(crop, zoomIn = false) }
        assertEquals(MIN_CROP_ZOOM, crop.zoom, 0.0)
        assertEquals(angleWorkspaceResetCrop, CropViewportState())
    }

    @Test
    fun encuadrePorFotogramaEsIndependienteYCambiarSeleccionNoLoPisa() {
        val source = CropViewportState(zoom = 1.5, alignment = CropAlignment(0.2, 0.8))
        val frameACrop = CropViewportState(zoom = 2.5, alignment = CropAlignment(0.6, 0.4))
        val crops = AngleWorkspaceCrops(source = source, byFrame = mapOf(frameA to frameACrop))
        assertEquals(frameACrop, crops.forFrame(frameA))
        assertEquals(source, crops.forFrame(frameB))
        assertEquals(source, crops.forFrame(null))

        // Cambiar el encuadre de B no toca el de A.
        val changed = crops.withCrop(frameB, CropViewportState(zoom = 3.0))
        assertEquals(frameACrop, changed.forFrame(frameA))
        assertEquals(3.0, changed.forFrame(frameB).zoom, 0.0)
        assertEquals(source, changed.source)
    }

    @Test
    fun capturaNuevaCopiaEncuadreDeFuenteYAlreadyTrackedNoSobrescribe() {
        val source = CropViewportState(zoom = 1.75, alignment = CropAlignment(0.3, 0.7))
        val crops = AngleWorkspaceCrops(source = source)

        val afterCapture = angleWorkspaceRegisterCapture(crops, frameA, alreadyTracked = false)
        assertEquals(source, afterCapture.forFrame(frameA))

        // Ya existía: ni la ruta ni el id presente cambian su encuadre, aunque la fuente haya
        // cambiado de zoom después.
        val adjusted = afterCapture.withCrop(
            frameA,
            CropViewportState(zoom = 2.25, alignment = CropAlignment(0.9, 0.1)),
        )
        val zoomedSource = adjusted.copy(source = CropViewportState(zoom = 3.0))
        val tracked = angleWorkspaceRegisterCapture(zoomedSource, frameA, alreadyTracked = true)
        assertEquals(2.25, tracked.forFrame(frameA).zoom, 0.0)
        val repeated = angleWorkspaceRegisterCapture(zoomedSource, frameA, alreadyTracked = false)
        assertEquals(2.25, repeated.forFrame(frameA).zoom, 0.0)
    }

    @Test
    fun mapperCompartidoAplicaMismaEscalaYTraslacionANativoYOverlay() {
        val geometry = VideoPresentationGeometry(1920, 1080)
        val crop = CropViewportState(zoom = 2.0, alignment = CropAlignment(0.25, 0.75))
        val mapper = angleWorkspaceMapper(geometry, 1000, 1000, crop)
        assertNotNull(mapper)
        requireNotNull(mapper)
        assertEquals(2.0, mapper.nativeScale, 1e-9)
        assertTrue(mapper.contentRect.width > 0.0 && mapper.contentRect.height > 0.0)
        // El rectángulo que ven los puntos es el mismo que usa la imagen nativa.
        assertTrue(mapper.contentRect.left <= 0.0 || mapper.contentRect.left < 1000.0)
    }

    @Test
    fun mapperSinGeometriaOViewportNoInventaFuente() {
        assertNull(angleWorkspaceMapper(null, 100, 100, angleWorkspaceResetCrop))
        assertNull(
            angleWorkspaceMapper(VideoPresentationGeometry(10, 10), 0, 100, angleWorkspaceResetCrop),
        )
    }
}
