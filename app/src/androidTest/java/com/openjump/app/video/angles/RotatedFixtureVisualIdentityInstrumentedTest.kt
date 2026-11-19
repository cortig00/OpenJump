package com.openjump.app.video.angles

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Evidencia instrumentada (requiere AVD) de **identidad visual + transformación de imagen** con
 * rotaciones reales del contenedor (display matrix en `tkhd`) y PTS variables.
 *
 * ## Qué demuestra y qué no
 *
 * Cada fixture codifica en sus píxeles dos cosas independientes del PTS que el adaptador asigne:
 *
 * 1. el **ordinal de presentación** en el patrón de [FrameVisualIdentity] (esquina superior izquierda
 *    de la imagen codificada), y
 * 2. cuatro **esquinas asimétricas** de 16×16: TL rojo, TR verde, BL azul, BR amarillo.
 *
 * La prueba abre la fuente con [FrozenFrameSources.openFromFile] (sesión sellada real, snapshot
 * privado) y captura con la ruta `MediaExtractor` + `MediaCodec` de [FrozenFrameSourceSession]. Para
 * cada frame solicitado comprueba:
 *
 * - el ordinal leído de los píxeles **en la orientación presentada** (aplicando la rotación declarada
 *   a las coordenadas de muestreo) coincide con el índice solicitado, y
 * - la permutación de colores de las cuatro esquinas coincide con la que produce girar la imagen
 *   codificada en sentido horario los grados que el índice declara.
 *
 * Así, una ruta que devuelva otro frame se detecta por el ordinal; una ruta que no aplique la rotación
 * se detecta por las esquinas (y, en 90/270 no cuadrados, también por las dimensiones). El PTS de
 * salida del decodificador se compara con el del índice sellado como comprobación **adicional**, nunca
 * como única evidencia: comparar un PTS consigo mismo no acredita que los píxeles sean del frame.
 *
 * ## Fixtures (`app/src/androidTest/assets/angles/`)
 *
 * Sintéticos, generados localmente con ffmpeg (receta en el README de esa carpeta, sin descargas).
 * Todos tienen 12 frames y `-g 4`, lo que da varios puntos de sincronización para las búsquedas
 * alternas. La matriz de visualización se inyecta en `tkhd` (16.16) y se verifica en host con
 * `ffprobe` antes de versionar el fixture.
 *
 * ## Límites
 *
 * - Este slice **no ejecuta AVD**: la clase está compilada, no ejecutada. El mapeo real
 *   `display matrix → MediaFormat.KEY_ROTATION` se exige aquí con los valores explícitos del manifest
 *   (90/180/270); si el extractor del dispositivo mapeara otra cosa, la prueba fallaría de forma
 *   explícita en vez de aprobar en silencio.
 * - No se afirma fidelidad de color frente a un render de referencia: solo la permutación de esquinas
 *   sintéticas con tolerancia por canal, suficiente para distinguir orientaciones.
 * - Un PTS inicial distinto de 0 **no** se versiona: ffmpeg lo expresa con un `elst` de edición vacía
 *   y sin dispositivo no se puede acreditar que `MediaExtractor` lo conserve, así que queda sin
 *   demostrar en lugar de afirmarse.
 *
 * Ejecución (un solo propietario del AVD, sin borrar datos):
 * ```
 * ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.openjump.app.video.angles.RotatedFixtureVisualIdentityInstrumentedTest
 * ```
 *
 * Higiene de fixtures: cada prueba copia el asset a un fichero temporal propio de nombre único
 * ([File.createTempFile]) en la caché del target y lo borra en `finally`; la sesión sellada usa su
 * propio snapshot privado y lo borra al cerrar. No se reutilizan ni sobrescriben artefactos previos.
 */
@RunWith(AndroidJUnit4::class)
class RotatedFixtureVisualIdentityInstrumentedTest {

    /** Color RGB medio de una región 8×8, en 0..255 por canal. */
    private data class Rgb(val red: Int, val green: Int, val blue: Int)

    /** Esquinas de la imagen **presentada**; el orden es TL, TR, BR, BL. */
    private data class CornerExpectation(
        val topLeft: Rgb,
        val topRight: Rgb,
        val bottomRight: Rgb,
        val bottomLeft: Rgb,
    )

    private data class FixtureManifest(
        val assetName: String,
        val encodedWidth: Int,
        val encodedHeight: Int,
        val frameCount: Int,
        /** Grados que el índice debe declarar y que la congelación debe aplicar en sentido horario. */
        val rotationDegrees: Int,
        /** PTS exactos del índice sellado, en µs. */
        val frameTimesUs: LongArray,
        val corners: CornerExpectation,
    ) {
        val frozenWidth: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) encodedHeight else encodedWidth
        val frozenHeight: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) encodedWidth else encodedHeight
    }

    // Colores codificados en la orientación codificada del fixture.
    private val red = Rgb(255, 0, 0)
    private val green = Rgb(0, 255, 0)
    private val blue = Rgb(0, 0, 255)
    private val yellow = Rgb(255, 255, 0)

    private fun cfrTimes(count: Int, deltaUs: Long): LongArray = LongArray(count) { it * deltaUs }

    /**
     * Manifest explícito: los valores no se derivan del índice leído en el dispositivo, sino del
     * fixture verificado en host con `ffprobe` (dimensiones codificadas, rotación de la matriz,
     * número de frames y PTS de las muestras). La permutación de esquinas es la que produce girar en
     * sentido horario la imagen codificada (TL rojo, TR verde, BR amarillo, BL azul).
     */
    private val fixtures = listOf(
        FixtureManifest(
            assetName = "angles/ordinal_rot90.mp4",
            encodedWidth = 320,
            encodedHeight = 240,
            frameCount = 12,
            rotationDegrees = 90,
            frameTimesUs = cfrTimes(12, 40_000L),
            corners = CornerExpectation(topLeft = blue, topRight = red, bottomRight = green, bottomLeft = yellow),
        ),
        FixtureManifest(
            assetName = "angles/ordinal_rot180.mp4",
            encodedWidth = 320,
            encodedHeight = 240,
            frameCount = 12,
            rotationDegrees = 180,
            frameTimesUs = cfrTimes(12, 40_000L),
            corners = CornerExpectation(topLeft = yellow, topRight = blue, bottomRight = red, bottomLeft = green),
        ),
        FixtureManifest(
            assetName = "angles/ordinal_rot270.mp4",
            encodedWidth = 320,
            encodedHeight = 240,
            frameCount = 12,
            rotationDegrees = 270,
            frameTimesUs = cfrTimes(12, 40_000L),
            corners = CornerExpectation(topLeft = green, topRight = yellow, bottomRight = blue, bottomLeft = red),
        ),
        // Cuadrado: las dimensiones no distinguen 90 de 270 ni prueban que se haya rotado. La única
        // evidencia de la transformación son las esquinas (y el patrón, que deja de ser legible en la
        // orientación codificada).
        FixtureManifest(
            assetName = "angles/ordinal_square_rot90.mp4",
            encodedWidth = 320,
            encodedHeight = 320,
            frameCount = 12,
            rotationDegrees = 90,
            frameTimesUs = cfrTimes(12, 40_000L),
            corners = CornerExpectation(topLeft = blue, topRight = red, bottomRight = green, bottomLeft = yellow),
        ),
        // VFR real: deltas 40,40,40,80,40,40,40,160,40,40,40 ms. Sin rotación.
        FixtureManifest(
            assetName = "angles/ordinal_vfr.mp4",
            encodedWidth = 320,
            encodedHeight = 240,
            frameCount = 12,
            rotationDegrees = 0,
            frameTimesUs = longArrayOf(
                0L, 40_000L, 80_000L, 120_000L, 200_000L, 240_000L,
                280_000L, 320_000L, 480_000L, 520_000L, 560_000L, 600_000L,
            ),
            corners = CornerExpectation(topLeft = red, topRight = green, bottomRight = yellow, bottomLeft = blue),
        ),
    )

    private fun copyAssetToOwnTempFile(assetName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File.createTempFile("angles_rotated_", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open(assetName).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun openSealedSession(assetName: String): Pair<File, FrozenFrameSourceSession> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val copy = copyAssetToOwnTempFile(assetName)
        return when (val opened = FrozenFrameSources.openFromFile(context, copy)) {
            is FrozenFrameSourceOpen.Opened -> copy to opened.session
            is FrozenFrameSourceOpen.Unavailable -> {
                copy.delete()
                fail("No se pudo abrir la fuente sellada $assetName: ${opened.failure} ${opened.detail}")
                error("inalcanzable")
            }
        }
    }

    private fun requireRequest(session: FrozenFrameSourceSession, frameIndex: Int): FrameFreezeRequest =
        when (val result = session.request(frameIndex)) {
            is FrameSourceRequestResult.Granted -> result.request
            is FrameSourceRequestResult.Blocked ->
                throw AssertionError("No se pudo pedir el frame $frameIndex: ${result.reason} ${result.detail}")
        }

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { pixels ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun luma(pixel: Int): Int {
        val red = pixel shr 16 and 0xFF
        val green = pixel shr 8 and 0xFF
        val blue = pixel and 0xFF
        return (77 * red + 150 * green + 29 * blue) shr 8
    }

    /** Media 8×8 de la región con esquina superior izquierda ([x0], [y0]). */
    private fun averagePatch(pixels: IntArray, width: Int, x0: Int, y0: Int, size: Int = 8): Rgb {
        var red = 0
        var green = 0
        var blue = 0
        for (y in y0 until y0 + size) {
            for (x in x0 until x0 + size) {
                val pixel = pixels[y * width + x]
                red += pixel shr 16 and 0xFF
                green += pixel shr 8 and 0xFF
                blue += pixel and 0xFF
            }
        }
        val count = size * size
        return Rgb(red / count, green / count, blue / count)
    }

    private fun assertColorClose(label: String, expected: Rgb, actual: Rgb, tolerance: Int = 56) {
        assertTrue(
            "$label: color medio $actual no se corresponde con el esperado $expected (tolerancia $tolerance por canal).",
            kotlin.math.abs(expected.red - actual.red) <= tolerance &&
                kotlin.math.abs(expected.green - actual.green) <= tolerance &&
                kotlin.math.abs(expected.blue - actual.blue) <= tolerance,
        )
    }

    /**
     * Lee el ordinal del patrón sintético **tras** aplicar la rotación horaria [rotationDegrees]:
     * las coordenadas de cada celda se calculan en la orientación codificada y se transforman a la
     * imagen presentada. Si la imagen no fue rotada, el marcador no aparece donde se espera y la
     * lectura devuelve `null` (nunca se adivina un ordinal).
     */
    private fun decodeOrdinalAfterRotation(
        pixels: IntArray,
        frozenWidth: Int,
        frozenHeight: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        rotationDegrees: Int,
    ): Int? {
        val expectedFrozenWidth = if (rotationDegrees == 90 || rotationDegrees == 270) encodedHeight else encodedWidth
        val expectedFrozenHeight = if (rotationDegrees == 90 || rotationDegrees == 270) encodedWidth else encodedHeight
        if (frozenWidth != expectedFrozenWidth || frozenHeight != expectedFrozenHeight) return null
        if (rotationDegrees !in setOf(0, 90, 180, 270)) return null
        val margin = FrameVisualIdentity.MARGIN
        val cellSize = FrameVisualIdentity.CELL_SIZE
        val centerY = margin + cellSize / 2

        fun lumaAtCell(cell: Int): Boolean {
            val encodedX = margin + cell * cellSize + cellSize / 2
            val encodedY = centerY
            // Transformación de coordenadas codificadas → presentadas (giro horario).
            val frozenX: Int
            val frozenY: Int
            when (rotationDegrees) {
                0 -> {
                    frozenX = encodedX
                    frozenY = encodedY
                }
                90 -> {
                    frozenX = encodedHeight - 1 - encodedY
                    frozenY = encodedX
                }
                180 -> {
                    frozenX = encodedWidth - 1 - encodedX
                    frozenY = encodedHeight - 1 - encodedY
                }
                else -> {
                    frozenX = encodedY
                    frozenY = encodedWidth - 1 - encodedX
                }
            }
            return luma(pixels[frozenY * frozenWidth + frozenX]) > FrameVisualIdentity.LUMA_THRESHOLD
        }

        var cell = 0
        for (expectedLight in listOf(false, true, false)) {
            if (lumaAtCell(cell++) != expectedLight) return null
        }
        var ordinal = 0
        for (bit in 0 until FrameVisualIdentity.DATA_BITS) {
            if (lumaAtCell(cell++)) ordinal = ordinal or (1 shl bit)
        }
        return ordinal
    }

    /**
     * Núcleo de la verificación: manifest, índice sellado y capturas alternas (primero, último,
     * intermedio, segundo y penúltimo) con identidad visual y orientación de esquinas.
     */
    private fun verifyFixture(manifest: FixtureManifest) {
        val (copy, session) = openSealedSession(manifest.assetName)
        try {
            session.use { sealed ->
                val index = sealed.index
                assertEquals("frameCount de ${manifest.assetName}", manifest.frameCount, index.frameCount)
                assertEquals("ancho codificado", manifest.encodedWidth, index.encodedWidth)
                assertEquals("alto codificado", manifest.encodedHeight, index.encodedHeight)
                assertEquals(
                    "rotación declarada por el extractor (${manifest.assetName})",
                    manifest.rotationDegrees,
                    index.declaredRotationDegrees,
                )
                assertArrayEquals(
                    "PTS del índice sellado (${manifest.assetName})",
                    manifest.frameTimesUs,
                    index.frameTimesUs,
                )
                if (manifest.rotationDegrees == 0) {
                    // VFR: el fixture no es CFR; que la prueba lo afirme evita confundir un índice
                    // "redondeado a CFR" con el real.
                    assertTrue(
                        "El fixture VFR debe tener deltas no uniformes: ${index.frameTimesUs.toList()}",
                        index.frameTimesUs.toSet().size == index.frameTimesUs.size &&
                            index.frameTimesUs.asSequence().zipWithNext().map { (a, b) -> b - a }.toSet().size > 1,
                    )
                }

                val captureOrder = listOf(
                    0,
                    manifest.frameCount - 1,
                    manifest.frameCount / 2,
                    1,
                    manifest.frameCount - 2,
                )
                for (frameIndex in captureOrder) {
                    val request = requireRequest(sealed, frameIndex)
                    assertEquals(
                        "PTS asignado desde el índice (frame=$frameIndex)",
                        manifest.frameTimesUs[frameIndex],
                        request.assignedPtsUs,
                    )
                    when (val result = sealed.capture(request)) {
                        is FrameFreezeResult.Frozen -> {
                            val image = result.image
                            try {
                                verifyFrozenImage(manifest, image, frameIndex, captureOrder)
                            } finally {
                                image.close()
                            }
                        }
                        is FrameFreezeResult.Blocked -> fail(
                            "La captura del frame $frameIndex de ${manifest.assetName} se bloqueó: " +
                                "${result.reason} ${result.detail}",
                        )
                    }
                }
            }
        } finally {
            session.close()
            copy.delete()
        }
    }

    private fun verifyFrozenImage(
        manifest: FixtureManifest,
        image: FrozenFrameImage,
        frameIndex: Int,
        captureOrder: List<Int>,
    ) {
        val bitmap = image.bitmap
        val label = "${manifest.assetName} frame=$frameIndex orden=$captureOrder"
        assertEquals("$label: ancho presentado", manifest.frozenWidth, bitmap.width)
        assertEquals("$label: alto presentado", manifest.frozenHeight, bitmap.height)
        assertFalse("$label: el contrato promete bitmap inmutable", bitmap.isMutable)

        val provenance = image.provenance
        assertEquals("$label: rotación aplicada", manifest.rotationDegrees, provenance.rotationAppliedDegrees)
        assertEquals("$label: ancho codificado en procedencia", manifest.encodedWidth, provenance.encodedWidth)
        assertEquals("$label: alto codificado en procedencia", manifest.encodedHeight, provenance.encodedHeight)
        // En modo buffer sin superficie la salida debe seguir en orientación codificada: si el
        // decodificador hubiera rotado, decodedWidth/Height serían las dimensiones presentadas y la
        // congelación habría rotado dos veces. El productor lo bloquea; aquí se afirman los píxeles.
        assertEquals("$label: ancho decodificado", manifest.encodedWidth, provenance.decodedWidth)
        assertEquals("$label: alto decodificado", manifest.encodedHeight, provenance.decodedHeight)
        assertEquals("$label: frame de procedencia", frameIndex, provenance.frameIndex)
        assertEquals(
            "$label: PTS reportado por el buffer de salida == PTS del índice",
            manifest.frameTimesUs[frameIndex],
            provenance.decoderReportedOutputPtsUs,
        )
        assertEquals(
            "$label: PTS de la solicitud == PTS reportado por el decodificador",
            provenance.assignedRequestPtsUs,
            provenance.decoderReportedOutputPtsUs,
        )
        val expectedCertainty = if (manifest.rotationDegrees == 0) {
            FrameOrientationCertainty.DECLARED_NO_ROTATION
        } else {
            FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM
        }
        assertEquals("$label: certeza de orientación", expectedCertainty, provenance.orientationCertainty)

        val pixels = pixelsOf(bitmap)
        // 1) Identidad: ordinal leído de los píxeles en la orientación presentada.
        val ordinal = decodeOrdinalAfterRotation(
            pixels = pixels,
            frozenWidth = bitmap.width,
            frozenHeight = bitmap.height,
            encodedWidth = manifest.encodedWidth,
            encodedHeight = manifest.encodedHeight,
            rotationDegrees = manifest.rotationDegrees,
        )
        assertEquals(
            "$label: ordinal visual en la orientación presentada",
            frameIndex,
            ordinal,
        )
        if (manifest.rotationDegrees != 0) {
            // Control negativo para rotaciones no nulas: el patrón ya no está en la esquina superior
            // izquierda de la imagen presentada. En 180 (mismas dimensiones) y en el cuadrado es la
            // única comprobación que no puede pasar por tamaño; en 90/270 además el tamaño cambia.
            val encodedOrientationOrdinal = FrameVisualIdentity.decodeOrdinal(pixels, bitmap.width, bitmap.height)
            assertTrue(
                "$label: el patrón no debe seguir legible en la orientación codificada (leído=$encodedOrientationOrdinal)",
                encodedOrientationOrdinal == null || encodedOrientationOrdinal != frameIndex,
            )
        }

        // 2) Transformación de imagen: permutación de las cuatro esquinas asimétricas tras el giro.
        val inset = 4
        val patch = 8
        val actualTopLeft = averagePatch(pixels, bitmap.width, inset, inset, patch)
        val actualTopRight = averagePatch(pixels, bitmap.width, bitmap.width - inset - patch, inset, patch)
        val actualBottomRight = averagePatch(
            pixels,
            bitmap.width,
            bitmap.width - inset - patch,
            bitmap.height - inset - patch,
            patch,
        )
        val actualBottomLeft = averagePatch(pixels, bitmap.width, inset, bitmap.height - inset - patch, patch)
        assertColorClose("$label: esquina TL", manifest.corners.topLeft, actualTopLeft)
        assertColorClose("$label: esquina TR", manifest.corners.topRight, actualTopRight)
        assertColorClose("$label: esquina BR", manifest.corners.bottomRight, actualBottomRight)
        assertColorClose("$label: esquina BL", manifest.corners.bottomLeft, actualBottomLeft)
    }

    private fun fixture(name: String): FixtureManifest =
        fixtures.first { it.assetName == name }

    @Test
    fun rot90ServesVisuallyIdenticalFramesWithCornersRotatedClockwise() {
        verifyFixture(fixture("angles/ordinal_rot90.mp4"))
    }

    @Test
    fun rot180ServesVisuallyIdenticalFramesWithCornersRotated180() {
        verifyFixture(fixture("angles/ordinal_rot180.mp4"))
    }

    @Test
    fun rot270ServesVisuallyIdenticalFramesWithCornersRotated270() {
        verifyFixture(fixture("angles/ordinal_rot270.mp4"))
    }

    @Test
    fun squareRot90ServesVisuallyIdenticalFramesUsingOrientationEvidenceNotDimensions() {
        verifyFixture(fixture("angles/ordinal_square_rot90.mp4"))
    }

    @Test
    fun vfrSourceKeepsNonUniformPtsAndVisualIdentity() {
        verifyFixture(fixture("angles/ordinal_vfr.mp4"))
    }

    @Test
    fun rotatedOrdinalReaderControlRejectsUnrotatedPixelsAndAcceptsRotatedOnes() {
        // Control del lector en el cuadrado 320×320, donde las dimensiones no ayudan: sin rotar, el
        // lector girado 90 no encuentra el patrón (devuelve null, no adivina); tras girar los píxeles
        // 180 en sentido horario, devuelve el ordinal codificado. No sustituye a las capturas reales.
        val square = 320
        val pixels = FrameVisualIdentity.encodeOrdinalFrame(square, square, 5)
        assertEquals(5, FrameVisualIdentity.decodeOrdinal(pixels, square, square))
        assertNull(
            "Sin rotar, el lector girado 90 no debe leer el patrón en la orientación presentada.",
            decodeOrdinalAfterRotation(
                pixels = pixels,
                frozenWidth = square,
                frozenHeight = square,
                encodedWidth = square,
                encodedHeight = square,
                rotationDegrees = 90,
            ),
        )
        assertEquals(
            "El lector girado 180 debe devolver el ordinal tras girar el bitmap de prueba.",
            5,
            decodeOrdinalAfterRotation(
                pixels = rotateClockwise(pixels, square, square, 180),
                frozenWidth = square,
                frozenHeight = square,
                encodedWidth = square,
                encodedHeight = square,
                rotationDegrees = 180,
            ),
        )
    }

    /** Giro horario de una matriz ARGB de prueba, solo para el control del lector en JVM/ART. */
    private fun rotateClockwise(pixels: IntArray, width: Int, height: Int, degrees: Int): IntArray {
        if (degrees == 0) return pixels
        if (degrees == 180) {
            val out = IntArray(pixels.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[(height - 1 - y) * width + (width - 1 - x)] = pixels[y * width + x]
                }
            }
            return out
        }
        val outWidth = height
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (nx, ny) = if (degrees == 90) {
                    (height - 1 - y) to x
                } else {
                    y to (width - 1 - x)
                }
                out[ny * outWidth + nx] = pixels[y * width + x]
            }
        }
        return out
    }
}
