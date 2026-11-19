package com.openjump.app.ui.angles

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AnglePoint
import com.openjump.app.video.angles.FrameCaptureProvenance
import com.openjump.app.video.angles.FrameOrientationCertainty
import com.openjump.app.video.angles.FrameSourceIdentity
import com.openjump.app.video.angles.FrameTemporalClaim
import com.openjump.app.video.angles.FrozenFrameImage
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas del rasterizado de la anotación fuera de pantalla.
 *
 * Verifican el contrato que los tests JVM no alcanzan: el bitmap base de [FrozenFrameImage] no se
 * modifica ni se recicla, la salida conserva el tamaño original, las zonas sin marcas conservan los
 * píxeles de la imagen base, los ángulos ocultos no se dibujan, la selección y la densidad son
 * explícitas y estables, y los rechazos son tipados.
 *
 * La base es un bitmap **sintético** de patrón conocido: no se decodifica vídeo, no se captura la
 * pantalla y la prueba no reutiliza el renderer para calcular la expectativa. Que el patrón de
 * píxeles cambie solo donde se dibuja es una comparación directa contra la copia previa del bitmap.
 */
@RunWith(AndroidJUnit4::class)
class AngleAnnotationInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val source = "video:content://openjump/angle-annotation-instrumented"
    private val generation = 9L
    private val anchor = AngleFrameAnchor(source, frameIndex = 2, sourcePtsUs = 80_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFF35D0FF.toInt())

    private val width = 480
    private val height = 360

    // Ángulo recto en la esquina superior izquierda: todas sus marcas, arco y etiqueta quedan dentro
    // de una caja conservadora (x < 200, y < 160) con densidad 1.
    private val angleA = AnglePoint(40.0, 80.0)
    private val angleB = AnglePoint(120.0, 80.0)
    private val angleC = AnglePoint(120.0, 20.0)

    // Segundo ángulo en la esquina inferior derecha, para comprobar múltiples y ocultos.
    private val secondA = AnglePoint(360.0, 280.0)
    private val secondB = AnglePoint(440.0, 280.0)
    private val secondC = AnglePoint(440.0, 200.0)

    /** Caja conservadora alrededor de las marcas del ángulo superior izquierdo con densidad 1. */
    private val firstBoxX = 0 until 200
    private val firstBoxY = 0 until 160

    /** Región sin geometría del ángulo superior izquierdo. */
    private val untouchedX = 320 until width
    private val untouchedY = 240 until height

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun editor(): AngleEditorState =
        AngleEditorState.initial(source, generation, defaults)

    private fun AngleEditorState.place(point: AnglePoint): AngleEditorState =
        applied(placePoint(point, confirmation, selectOnComplete = false))

    private fun editorWithFirstAngle(): AngleEditorState =
        editor().place(angleA).place(angleB).place(angleC)

    private fun editorWithTwoAngles(): AngleEditorState =
        editorWithFirstAngle().place(secondA).place(secondB).place(secondC)

    /**
     * Bitmap sintético opaco con un patrón determinista. Se devuelve una copia **inmutable**, que es
     * el contrato que [FrozenFrameImage] exige de sus píxeles.
     */
    private fun baseBitmap(w: Int = width, h: Int = height): Bitmap {
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            val y = index / w
            (0xFF shl 24) or (((x * 5) % 256) shl 16) or (((y * 7) % 256) shl 8) or ((x + y) % 256)
        }
        val mutable = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        try {
            return requireNotNull(mutable.copy(Bitmap.Config.ARGB_8888, false)) {
                "No se pudo crear la copia inmutable de la base."
            }
        } finally {
            mutable.recycle()
        }
    }

    /**
     * Procedencia de la imagen congelada. Por defecto **coincide con el ancla del estado** (fuente,
     * índice y PTS asignado), que es el único vínculo admitido; los tests que necesitan un rechazo
     * tipado la desvían explícitamente.
     */
    private fun provenance(
        frozenWidth: Int = width,
        frozenHeight: Int = height,
        sourceIdentityValue: String = source,
        frameIndex: Int = anchor.frameIndex,
        assignedRequestPtsUs: Long = anchor.sourcePtsUs,
    ) =
        FrameCaptureProvenance(
            schemaVersion = FrameCaptureProvenance.CURRENT_SCHEMA_VERSION,
            sourceIdentity = FrameSourceIdentity(sourceIdentityValue),
            frameIndex = frameIndex,
            assignedRequestPtsUs = assignedRequestPtsUs,
            decoderReportedOutputPtsUs = assignedRequestPtsUs,
            temporalClaim = FrameTemporalClaim.DECODER_OUTPUT_BUFFER_CORRELATED,
            encodedWidth = frozenWidth,
            encodedHeight = frozenHeight,
            declaredRotationDegrees = 0,
            decodedWidth = frozenWidth,
            decodedHeight = frozenHeight,
            rotationAppliedDegrees = 0,
            frozenWidth = frozenWidth,
            frozenHeight = frozenHeight,
            orientationCertainty = FrameOrientationCertainty.DECLARED_NO_ROTATION,
            contentDigest = 0L,
        )

    private fun budget(w: Int = width, h: Int = height): Long =
        w.toLong() * h.toLong() * 4L + maxOf(w, h).toLong() * 4L

    private fun render(
        image: FrozenFrameImage,
        state: AngleEditorState,
        selectedAngleId: AngleId? = null,
        metrics: AngleAnnotationMetrics = AngleAnnotationMetrics(densityScale = 1f),
        budgetBytes: Long = budget(image.width, image.height),
    ): AngleAnnotationResult = AngleAnnotationRenderer.render(
        context = context,
        request = AngleAnnotationRequest(
            image = image,
            state = state,
            selectedAngleId = selectedAngleId,
            metrics = metrics,
            budgetBytes = budgetBytes,
        ),
        formatLabel = { _, degrees -> String.format(Locale.US, "%.1f°", degrees) },
    )

    private fun rendered(result: AngleAnnotationResult): AngleAnnotationImage =
        (result as? AngleAnnotationResult.Rendered)?.image
            ?: throw AssertionError("Esperaba Rendered, llegó $result")

    private fun rejected(result: AngleAnnotationResult): AngleAnnotationResult.Rejected =
        result as? AngleAnnotationResult.Rejected
            ?: throw AssertionError("Esperaba Rejected, llegó $result")

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { pixels ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun changedIndices(before: IntArray, after: IntArray): List<Int> {
        assertEquals("Las imágenes comparadas deben tener el mismo número de píxeles.", before.size, after.size)
        return before.indices.filter { before[it] != after[it] }
    }

    private fun index(x: Int, y: Int): Int = y * width + x

    private fun assertRegionUntouched(
        before: IntArray,
        after: IntArray,
        xs: IntRange,
        ys: IntRange,
        label: String,
    ) {
        for (y in ys) {
            for (x in xs) {
                assertEquals(
                    "$label: píxel ($x, $y) cambió fuera de la geometría anotada.",
                    before[index(x, y)],
                    after[index(x, y)],
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Inmutabilidad de la base y tamaño de la salida
    // -----------------------------------------------------------------------------------------

    @Test
    fun la_anotacion_no_modifica_ni_recicla_la_base_y_conserva_el_tamano_original() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance())
        try {
            val result = render(image, editorWithFirstAngle())
            val annotated = rendered(result)
            try {
                assertFalse("La imagen base no puede quedar reciclada por la anotación.", base.isRecycled)
                assertFalse("El contrato de FrozenFrameImage exige pixeles inmutables.", base.isMutable)
                assertNotSame("La salida debe ser una copia, no el bitmap base.", base, annotated.bitmap)
                assertEquals("La salida debe conservar el ancho original.", base.width, annotated.width)
                assertEquals("La salida debe conservar el alto original.", base.height, annotated.height)
                assertEquals(base.width, annotated.bitmap.width)
                assertEquals(base.height, annotated.bitmap.height)
                assertArrayEquals(
                    "La anotación no puede escribir en el bitmap base.",
                    before,
                    pixelsOf(base),
                )
            } finally {
                annotated.close()
            }
        } finally {
            image.close()
        }
    }

    @Test
    fun las_zonas_sin_marcas_conservan_los_pixeles_de_la_imagen_base() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance())
        try {
            val annotated = rendered(render(image, editorWithFirstAngle()))
            try {
                val after = pixelsOf(annotated.bitmap)
                val changed = changedIndices(before, after)
                assertTrue("La anotación debe dibujar al menos una marca.", changed.isNotEmpty())
                val outside = changed.filter { position ->
                    val x = position % width
                    val y = position / width
                    x !in firstBoxX || y !in firstBoxY
                }
                assertTrue(
                    "Hay ${outside.size} píxeles modificados fuera de la caja de la geometría " +
                        "(primeros: ${outside.take(5).map { "${it % width},${it / width}" }}).",
                    outside.isEmpty(),
                )
                assertRegionUntouched(before, after, untouchedX, untouchedY, "Zona sin marcas")
            } finally {
                annotated.close()
            }
        } finally {
            image.close()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Múltiples ángulos y ocultos
    // -----------------------------------------------------------------------------------------

    @Test
    fun los_angulos_multiples_se_dibujan_y_los_ocultos_no() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance())
        val state = editorWithTwoAngles()
        try {
            val both = rendered(render(image, state))
            val bothPixels: IntArray
            try {
                bothPixels = pixelsOf(both.bitmap)
            } finally {
                both.close()
            }
            val changedBoth = changedIndices(before, bothPixels)
            assertTrue(
                "El segundo ángulo visible debe modificar píxeles en su zona.",
                changedBoth.any { position ->
                    val x = position % width
                    val y = position / width
                    x >= 320 && y >= 180
                },
            )

            val hiddenState = applied(state.setVisible(AngleId(2L), false))
            val hidden = rendered(render(image, hiddenState))
            val hiddenPixels: IntArray
            try {
                hiddenPixels = pixelsOf(hidden.bitmap)
            } finally {
                hidden.close()
            }
            val changedHidden = changedIndices(before, hiddenPixels)
            assertTrue("El primer ángulo sigue siendo visible.", changedHidden.isNotEmpty())
            assertTrue(
                "Un ángulo oculto no puede dibujar nada en su zona.",
                changedHidden.none { position ->
                    val x = position % width
                    val y = position / width
                    x >= 320 && y >= 180
                },
            )
            assertFalse(
                "Ocultar un ángulo debe cambiar la imagen anotada.",
                bothPixels.contentEquals(hiddenPixels),
            )
        } finally {
            image.close()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Selección explícita y estabilidad
    // -----------------------------------------------------------------------------------------

    @Test
    fun la_seleccion_resaltada_es_explicita_y_el_resultado_es_estable() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance())
        val state = editorWithFirstAngle()
        try {
            val plainFirst = rendered(render(image, state, selectedAngleId = null))
            val plainPixels: IntArray
            try {
                plainPixels = pixelsOf(plainFirst.bitmap)
            } finally {
                plainFirst.close()
            }
            val plainSecond = rendered(render(image, state, selectedAngleId = null))
            val plainPixelsAgain: IntArray
            try {
                plainPixelsAgain = pixelsOf(plainSecond.bitmap)
            } finally {
                plainSecond.close()
            }
            assertArrayEquals(
                "La misma entrada debe producir exactamente la misma imagen.",
                plainPixels,
                plainPixelsAgain,
            )

            val selected = rendered(render(image, state, selectedAngleId = AngleId(1L)))
            val selectedPixels: IntArray
            try {
                selectedPixels = pixelsOf(selected.bitmap)
            } finally {
                selected.close()
            }
            val selectedAgain = rendered(render(image, state, selectedAngleId = AngleId(1L)))
            val selectedAgainPixels: IntArray
            try {
                selectedAgainPixels = pixelsOf(selectedAgain.bitmap)
            } finally {
                selectedAgain.close()
            }
            assertArrayEquals(
                "La selección explícita también debe ser estable.",
                selectedPixels,
                selectedAgainPixels,
            )
            assertFalse(
                "Resaltar un ángulo debe cambiar la imagen (anillo, trazo y glifos).",
                selectedPixels.contentEquals(plainPixels),
            )
            assertTrue(
                "El resaltado añade dibujo, no lo quita.",
                changedIndices(before, selectedPixels).size > changedIndices(before, plainPixels).size,
            )
        } finally {
            image.close()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Densidad y errores tipados
    // -----------------------------------------------------------------------------------------

    @Test
    fun la_densidad_explicita_escala_el_dibujo_sin_cambiar_el_tamano() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance())
        val state = editorWithFirstAngle()
        try {
            val one = rendered(render(image, state, metrics = AngleAnnotationMetrics(densityScale = 1f)))
            val onePixels: IntArray
            try {
                onePixels = pixelsOf(one.bitmap)
            } finally {
                one.close()
            }
            val two = rendered(render(image, state, metrics = AngleAnnotationMetrics(densityScale = 2f)))
            val twoPixels: IntArray
            try {
                assertEquals("El tamaño de salida no depende de la densidad.", width, two.width)
                assertEquals("El tamaño de salida no depende de la densidad.", height, two.height)
                twoPixels = pixelsOf(two.bitmap)
            } finally {
                two.close()
            }
            assertFalse(
                "La densidad explícita debe cambiar el tamaño de las marcas.",
                onePixels.contentEquals(twoPixels),
            )
            assertTrue(
                "Con densidad 2 el dibujo cubre más píxeles que con densidad 1.",
                changedIndices(before, twoPixels).size > changedIndices(before, onePixels).size,
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun rechaza_una_imagen_ya_liberada() {
        val image = FrozenFrameImage(baseBitmap(), provenance())
        image.close()

        val result = render(image, editorWithFirstAngle())

        assertEquals(AngleAnnotationRejectionReason.IMAGE_UNAVAILABLE, rejected(result).reason)
    }

    @Test
    fun rechaza_dimensiones_incoherentes_con_la_procedencia() {
        val base = baseBitmap()
        val image = FrozenFrameImage(base, provenance(frozenWidth = width - 1))
        val before = pixelsOf(base)
        try {
            val result = render(image, editorWithFirstAngle())

            assertEquals(AngleAnnotationRejectionReason.DIMENSIONS_INCONSISTENT, rejected(result).reason)
            assertArrayEquals("Un rechazo no puede tocar los píxeles base.", before, pixelsOf(base))
        } finally {
            image.close()
        }
    }

    @Test
    fun rechaza_un_presupuesto_insuficiente_sin_tocar_la_base() {
        val base = baseBitmap()
        val image = FrozenFrameImage(base, provenance())
        val before = pixelsOf(base)
        try {
            val result = render(image, editorWithFirstAngle(), budgetBytes = 1L)

            assertEquals(AngleAnnotationRejectionReason.MEMORY_BUDGET_EXCEEDED, rejected(result).reason)
            assertFalse(base.isRecycled)
            assertArrayEquals("El rechazo por presupuesto no puede tocar los píxeles base.", before, pixelsOf(base))
        } finally {
            image.close()
        }
    }

    @Test
    fun rechaza_una_seleccion_que_no_es_un_angulo_visible() {
        val base = baseBitmap()
        val image = FrozenFrameImage(base, provenance())
        try {
            val state = applied(editorWithFirstAngle().setVisible(AngleId(1L), false))

            val result = render(image, state, selectedAngleId = AngleId(1L))

            assertEquals(AngleAnnotationRejectionReason.SELECTION_INCONSISTENT, rejected(result).reason)
        } finally {
            image.close()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Vínculo declarado estado ↔ imagen (antes de copiar el bitmap)
    // -----------------------------------------------------------------------------------------

    @Test
    fun rechaza_un_estado_de_otra_fuente_con_las_mismas_dimensiones() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance(sourceIdentityValue = "androidtest:otra-fuente"))
        try {
            val result = render(image, editorWithFirstAngle())

            assertEquals(AngleAnnotationRejectionReason.SOURCE_IDENTITY_MISMATCH, rejected(result).reason)
            assertFalse("Un rechazo no puede reciclar la base.", base.isRecycled)
            assertArrayEquals("Un rechazo no puede tocar los píxeles base.", before, pixelsOf(base))
        } finally {
            image.close()
        }
    }

    @Test
    fun rechaza_un_estado_anclado_a_otro_indice_con_las_mismas_dimensiones() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance(frameIndex = anchor.frameIndex + 1))
        try {
            val result = render(image, editorWithFirstAngle())

            assertEquals(AngleAnnotationRejectionReason.FRAME_INDEX_MISMATCH, rejected(result).reason)
            assertArrayEquals("Un rechazo no puede tocar los píxeles base.", before, pixelsOf(base))
        } finally {
            image.close()
        }
    }

    @Test
    fun rechaza_un_estado_anclado_a_otro_pts_declarado() {
        val base = baseBitmap()
        val image = FrozenFrameImage(base, provenance(assignedRequestPtsUs = anchor.sourcePtsUs + 1L))
        try {
            val result = render(image, editorWithFirstAngle())

            assertEquals(AngleAnnotationRejectionReason.FRAME_PTS_MISMATCH, rejected(result).reason)
        } finally {
            image.close()
        }
    }

    @Test
    fun rechaza_un_borrador_pendiente_sin_tocar_la_base() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance())
        try {
            val state = editor().place(angleA).place(angleB)

            val result = render(image, state)

            assertEquals(AngleAnnotationRejectionReason.DRAFT_PENDING, rejected(result).reason)
            assertArrayEquals("Un rechazo no puede tocar los píxeles base.", before, pixelsOf(base))
        } finally {
            image.close()
        }
    }

    @Test
    fun rechaza_un_estado_vacio_sin_tocar_la_base() {
        val base = baseBitmap()
        val before = pixelsOf(base)
        val image = FrozenFrameImage(base, provenance())
        try {
            val result = render(image, editor())

            assertEquals(AngleAnnotationRejectionReason.STATE_EMPTY, rejected(result).reason)
            assertArrayEquals("Un rechazo no puede tocar los píxeles base.", before, pixelsOf(base))
        } finally {
            image.close()
        }
    }
}
