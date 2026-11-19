package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleInvalidReason
import com.openjump.app.data.AngleAnalysisEntity
import com.openjump.app.data.AngleAnalysisSnapshot
import com.openjump.app.data.AngleMarkEntity
import com.openjump.app.ui.ANGLES_SAVED_DETAIL_ROUTE
import com.openjump.app.ui.anglesSavedAnalysisRoute
import com.openjump.app.ui.anglesSavedDetailRoute
import com.openjump.app.ui.history.HistoryDeleteFamily
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM del detalle guardado de un análisis de ángulos.
 *
 * Verifica la política que permite consultar un resultado SIN vídeo:
 *
 * 1. la proyección de la foto de Room a la pantalla conserva timestamp, fotograma y marcas, y
 *    distingue "no existe" ([AngleSavedDetailState.Missing]) de un análisis válido;
 * 2. el valor derivado se recalcula desde los puntos guardados (nunca se lee persistido) y un
 *    ángulo no medible no se presenta como 0°;
 * 3. editar exige URI y dueño válidos, igual que `AngleEditorRequest`, sin que eso afecte a la
 *    consulta;
 * 4. el borrado reutiliza la familia ANGLE de HISTORY (nunca un id suelto) y la ruta del detalle no
 *    colisiona con la del editor.
 *
 * No compone UI ni toca Room: la instrumentación de la pantalla queda fuera de este slice.
 */
class AngleSavedDetailTest {

    // --- proyección de la raíz --------------------------------------------------------------------

    @Test
    fun `a missing snapshot is reported as missing and never as empty content`() {
        assertEquals(AngleSavedDetailState.Missing, angleSavedDetailState(null))
    }

    @Test
    fun `a saved analysis projects timestamp frame and ordered marks`() {
        val state = angleSavedDetailState(
            snapshot(
                marks = listOf(
                    mark(id = 22L, ordinal = 2, name = "Rodilla"),
                    mark(id = 11L, ordinal = 1, name = "Cadera"),
                ),
            ),
        )
        val content = (state as AngleSavedDetailState.Loaded).content
        assertEquals(7L, content.analysisId)
        assertEquals(1_700_000_000_000L, content.createdAt)
        assertEquals(42, content.frameIndex)
        assertEquals(listOf("Cadera", "Rodilla"), content.marks.map { it.name })
        assertEquals(listOf(1, 2), content.marks.map { it.ordinal })
    }

    @Test
    fun `derived values come from the saved points and keep hidden marks listed`() {
        val state = angleSavedDetailState(
            snapshot(
                marks = listOf(
                    // A(0,0) B(0,0) C(1,1): vértice coincidente -> no medible, sin 0° falso.
                    mark(
                        id = 1L,
                        ordinal = 1,
                        name = "Cadera",
                        visible = false,
                        a = 0.0 to 0.0,
                    ),
                    // 90° exactos en el vértice B.
                    mark(id = 2L, ordinal = 2, name = "Rodilla"),
                ),
            ),
        )
        val content = (state as AngleSavedDetailState.Loaded).content
        val invalid = content.marks.first { it.name == "Cadera" }
        val right = content.marks.first { it.name == "Rodilla" }

        assertNull(invalid.degrees)
        assertEquals(AngleInvalidReason.COINCIDENT_VERTEX, invalid.invalidReason)
        assertFalse(invalid.visible)
        assertEquals(90.0, right.degrees!!, 1e-9)
        assertNull(right.invalidReason)
        assertTrue(right.visible)
        // Una marca oculta sigue siendo un dato guardado consultable.
        assertEquals(1, content.visibleMarkCount)
        assertEquals(2, content.marks.size)
    }

    // --- política de edición y borrado -------------------------------------------------------------

    @Test
    fun `editing requires both a video uri and a valid athlete`() {
        val base = content(videoUri = "content://video/1", athleteId = 3L)
        assertTrue(base.canEditWithVideo)
        assertFalse(base.copy(videoUri = null).canEditWithVideo)
        assertFalse(base.copy(videoUri = "   ").canEditWithVideo)
        assertFalse(base.copy(athleteId = null).canEditWithVideo)
        assertFalse(base.copy(athleteId = 0L).canEditWithVideo)
    }

    @Test
    fun `a missing video keeps the saved results consultable`() {
        // Consultar no depende de canEditWithVideo: el contenido sigue proyectándose completo.
        val noVideo = angleSavedDetailState(snapshot(videoUri = null, athleteId = null))
        val content = (noVideo as AngleSavedDetailState.Loaded).content
        assertFalse(content.canEditWithVideo)
        assertEquals(1, content.marks.size)
        assertEquals(90.0, content.marks.single().degrees!!, 1e-9)
    }

    @Test
    fun `deletion targets the angle family and never a bare id`() {
        val target = angleSavedDeleteTarget(7L)
        assertEquals(HistoryDeleteFamily.ANGLE, target.family)
        assertEquals(7L, target.id)
        // La clave serializable sobrevive a la recreación sin ambigüedad entre familias.
        assertEquals(target, com.openjump.app.ui.history.HistoryDeleteTarget.fromKey(target.key))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unsaved analysis cannot be a deletion target`() {
        angleSavedDeleteTarget(0L)
    }

    // --- rutas y formato ----------------------------------------------------------------------------

    @Test
    fun `the saved detail route is stable and distinct from the editor route`() {
        assertEquals("angles/saved/7", anglesSavedDetailRoute(7L))
        assertEquals("angles/saved/{analysisId}", ANGLES_SAVED_DETAIL_ROUTE)
        assertTrue(
            "El detalle no debe reutilizar la ruta del editor",
            anglesSavedDetailRoute(7L) != anglesSavedAnalysisRoute(7L, "content://video/1", 3L),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the saved detail route rejects unsaved analyses`() {
        anglesSavedDetailRoute(0L)
    }

    @Test
    fun `degrees and points are formatted with the app locale`() {
        assertEquals("90.0", formatAngleSavedDegrees(90.0, Locale.US))
        assertEquals("90,0", formatAngleSavedDegrees(90.0, Locale.GERMANY))
        assertEquals("12", formatAngleSavedPoint(12.4, Locale.US))
        assertEquals("6", formatAngleSavedPoint(5.6, Locale.US))
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun snapshot(
        videoUri: String? = "content://video/1",
        athleteId: Long? = 3L,
        marks: List<AngleMarkEntity> = listOf(mark(id = 1L, ordinal = 1, name = "Cadera")),
    ) = AngleAnalysisSnapshot(
        analysis = AngleAnalysisEntity(
            id = 7L,
            sessionKey = "session-7",
            athleteId = athleteId,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_100_000L,
            videoUri = videoUri,
            encodedWidth = 1920,
            encodedHeight = 1080,
            presentationWidth = 1080,
            presentationHeight = 1920,
            rotationDegrees = 90,
            frameIndex = 42,
            sourcePtsUs = 1_400_000L,
            geometryVersion = 1,
        ),
        marks = marks,
    )

    private fun content(
        videoUri: String?,
        athleteId: Long?,
    ) = (angleSavedDetailState(snapshot(videoUri = videoUri, athleteId = athleteId))
        as AngleSavedDetailState.Loaded).content

    /**
     * Marca con A=(0,10), B=(0,0) y C=(10,0) por defecto: 90° en el vértice B. Los tests ajustan
     * los puntos que necesitan para provocar un caso no medible.
     */
    private fun mark(
        id: Long,
        ordinal: Int,
        name: String,
        visible: Boolean = true,
        a: Pair<Double, Double> = 0.0 to 10.0,
        b: Pair<Double, Double> = 0.0 to 0.0,
        c: Pair<Double, Double> = 10.0 to 0.0,
    ) = AngleMarkEntity(
        id = id,
        analysisId = 7L,
        ordinal = ordinal,
        name = name,
        colorArgb = 0xFF00FF00.toInt(),
        visible = visible,
        pointAX = a.first,
        pointAY = a.second,
        pointBX = b.first,
        pointBY = b.second,
        pointCX = c.first,
        pointCY = c.second,
    )
}
