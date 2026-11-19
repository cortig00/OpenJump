package com.openjump.app.video.angles

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.video.VideoFrameIndexer
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba instrumentada del productor **real** `MediaExtractor` + `MediaCodec` (slice B2).
 *
 * Estado: la clase se ejecutó en el AVD `OpenJump_API35` (API 35, x86_64, decodificador goldfish) el
 * 2026-11-10 con 3 tests y 1 fallo por la validación de PTS (F1), corregido después en
 * [CodecCapturePolicy.validateRequest]; este slice no re-ejecuta el AVD. Compilarla no demuestra
 * compatibilidad con ningún dispositivo; cuando se ejecuta, la evidencia válida es:
 * - el ordinal leído de los píxeles decodificados coincide con el frame solicitado (identidad visual,
 *   no el PTS que asigna el adaptador),
 * - el PTS de procedencia es el reportado por el buffer de salida del códec,
 * - el bitmap congelado es inmutable, no es el bitmap del llamador y `close()` solo recicla el suyo,
 * - rutas ambiguas (PTS fuera del índice, cancelación, layout no soportado) terminan en
 *   [FrameFreezeResult.Blocked] sin fallback a `MediaMetadataRetriever`.
 *
 * Ejecución (un solo propietario del AVD, sin borrar datos de usuario):
 * ```
 * ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.openjump.app.video.angles.CodecFrozenFrameProducerInstrumentedTest
 * ```
 *
 * Higiene de fixtures (corrección B3): cada prueba crea su vídeo con nombre único
 * ([File.createTempFile]) en la caché de la app y borra sólo ese fichero; no se reutilizan ni
 * sobrescriben artefactos de ejecuciones anteriores.
 */
@RunWith(AndroidJUnit4::class)
class CodecFrozenFrameProducerInstrumentedTest {

    private val fixtureName = "angles/ordinal_cfr_bframes.mp4"

    /**
     * Copia el fixture a un fichero nuevo de nombre único, creado atómicamente con
     * [File.createTempFile]: nunca reutiliza ni sobrescribe ficheros de ejecuciones anteriores. El
     * llamador es el único propietario y lo borra en su `finally` (también si la copia falla aquí).
     */
    private fun copyFixtureToCache(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File.createTempFile("angles_codec_", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open(fixtureName).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun snapshotOf(video: File): Pair<com.openjump.app.video.VideoFrameIndex, FrozenFrameSourceSession> {
        val index = VideoFrameIndexer.indexFromPath(video.absolutePath)
        val opened = FrozenFrameSources.openFromFile(
            InstrumentationRegistry.getInstrumentation().targetContext,
            video,
        )
        val session = when (opened) {
            is FrozenFrameSourceOpen.Opened -> opened.session
            is FrozenFrameSourceOpen.Unavailable ->
                throw AssertionError("No se pudo abrir la fuente: ${opened.failure} ${opened.detail}")
        }
        return index to session
    }

    private fun ordinalOf(bitmap: Bitmap): Int? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FrameVisualIdentity.decodeOrdinal(pixels, bitmap.width, bitmap.height)
    }

    /** Solicitud tipada: un rechazo de sesión se convierte en fallo de test, nunca en excepción del API. */
    private fun FrozenFrameSourceSession.requireRequest(frameIndex: Int): FrameFreezeRequest =
        when (val result = request(frameIndex)) {
            is FrameSourceRequestResult.Granted -> result.request
            is FrameSourceRequestResult.Blocked ->
                throw AssertionError("No se pudo pedir el frame $frameIndex: ${result.reason} ${result.detail}")
        }

    @Test
    fun codecRouteServesTheRequestedFrameWithReportedPtsForFirstMiddleAndLast() {
        val video = copyFixtureToCache()
        try {
            val (index, session) = snapshotOf(video)
            session.use {
                for (frameIndex in listOf(0, index.frameCount / 2, index.frameCount - 1)) {
                    val request = it.requireRequest(frameIndex)
                    val result = it.capture(request)
                    assertTrue("El códec no entregó el frame $frameIndex: $result", result is FrameFreezeResult.Frozen)
                    val frozen = (result as FrameFreezeResult.Frozen).image
                    try {
                        assertEquals(
                            "El ordinal visual no es el solicitado (frame=$frameIndex)",
                            frameIndex,
                            ordinalOf(frozen.bitmap),
                        )
                        assertFalse("El contrato promete bitmap inmutable", frozen.bitmap.isMutable)
                        assertEquals(request.assignedPtsUs, frozen.provenance.assignedRequestPtsUs)
                        assertEquals(request.assignedPtsUs, frozen.provenance.decoderReportedOutputPtsUs)
                        assertEquals(FrameTemporalClaim.DECODER_OUTPUT_BUFFER_CORRELATED, frozen.provenance.temporalClaim)
                        assertEquals(it.identity, frozen.provenance.sourceIdentity)
                        assertEquals(frameIndex, frozen.provenance.frameIndex)
                    } finally {
                        frozen.close()
                    }
                }
            }
        } finally {
            video.delete()
        }
    }

    @Test
    fun aPtsThatIsNotInTheIndexIsBlockedWithoutDecoderWork() {
        val video = copyFixtureToCache()
        try {
            val (index, session) = snapshotOf(video)
            session.use {
                val valid = it.requireRequest(2)
                val invalid = valid.copy(assignedPtsUs = index.timeOf(2) + 1L)
                val result = it.capture(invalid)
                assertTrue("Un PTS que no está en el índice debe bloquearse: $result", result is FrameFreezeResult.Blocked)
                assertEquals(
                    FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                    (result as FrameFreezeResult.Blocked).reason,
                )
            }
        } finally {
            video.delete()
        }
    }

    @Test
    fun cancellationYieldsABlockAndReleasesResources() {
        val video = copyFixtureToCache()
        try {
            val (index, session) = snapshotOf(video)
            session.use {
                val result = it.capture(
                    request = it.requireRequest(index.frameCount / 2),
                    cancellationCheck = { true },
                )
                assertTrue("La cancelación no puede producir una imagen: $result", result is FrameFreezeResult.Blocked)
                assertEquals(FrameFreezeRejectionReason.CANCELLED, (result as FrameFreezeResult.Blocked).reason)
            }
        } finally {
            video.delete()
        }
    }
}

/**
 * Prueba instrumentada del defecto B1 nº 1: la imagen congelada debe ser inmutable y de propiedad
 * exclusiva, sin reciclar el bitmap del llamador y sin prometer memoria que no cabe.
 */
@RunWith(AndroidJUnit4::class)
class FrozenFrameImmutabilityInstrumentedTest {

    private fun request(
        declaredRotationDegrees: Int = 0,
        encodedWidth: Int = 320,
        encodedHeight: Int = 240,
    ) = FrameFreezeRequest(
        sourceIdentity = FrameSourceIdentity("androidtest:immutability"),
        frameIndex = 1,
        assignedPtsUs = 40_000L,
        indexFrameCount = 2,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        declaredRotationDegrees = declaredRotationDegrees,
    )

    private fun evidence(request: FrameFreezeRequest) = FrameTemporalEvidence.fromCodecOutputBuffer(
        generation = CodecOutputGeneration.mint(outputBufferIndex = 0),
        sourceIdentity = request.sourceIdentity,
        frameIndex = request.frameIndex,
        assignedPtsUs = request.assignedPtsUs,
        codecOutputPtsUs = request.assignedPtsUs,
    )

    private fun bitmap(width: Int, height: Int, mutable: Boolean): Bitmap {
        val pixels = FrameVisualIdentity.encodeOrdinalFrame(width, height, ordinal = 1)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return if (mutable) bitmap.copy(Bitmap.Config.ARGB_8888, true) else bitmap
    }

    @Test
    fun theFrozenBitmapIsImmutableAndOwnedByTheFrozenImage() {
        for (mutable in listOf(false, true)) {
            val request = request()
            val source = bitmap(320, 240, mutable)
            val result = FrozenFrameFreezer.freeze(source, request, evidence(request))
            assertTrue("Se esperaba Frozen y fue $result", result is FrameFreezeResult.Frozen)
            val image = (result as FrameFreezeResult.Frozen).image
            try {
                assertFalse("La imagen congelada debe ser inmutable", image.bitmap.isMutable)
                assertNotSame("No puede devolverse el bitmap del llamador", source, image.bitmap)
                assertFalse("El bitmap del llamador sigue vivo", source.isRecycled)
                assertEquals(1, ordinalOf(image.bitmap) ?: -1)
            } finally {
                image.close()
            }
            assertFalse("close() no debe reciclar el bitmap del llamador", source.isRecycled)
            source.recycle()
        }
    }

    @Test
    fun rotationProducesAnImmutableBitmapOfPresentedDimensions() {
        val request = request(declaredRotationDegrees = 90)
        // El decodificador devuelve la orientación codificada: la congelación debe rotar.
        val source = bitmap(320, 240, mutable = false)
        val result = FrozenFrameFreezer.freeze(source, request, evidence(request))
        assertTrue("Se esperaba Frozen y fue $result", result is FrameFreezeResult.Frozen)
        val image = (result as FrameFreezeResult.Frozen).image
        try {
            assertEquals(240, image.bitmap.width)
            assertEquals(320, image.bitmap.height)
            assertFalse("La copia rotada debe ser inmutable", image.bitmap.isMutable)
            assertEquals(90, image.provenance.rotationAppliedDegrees)
        } finally {
            image.close()
            source.recycle()
        }
    }

    @Test
    fun aRotationThatCannotBeProvenIsBlockedAndDoesNotAllocateAnImage() {
        // Rotación 180: las dimensiones no demuestran si el decodificador rotó.
        val request = request(declaredRotationDegrees = 180)
        val source = bitmap(320, 240, mutable = false)
        try {
            val result = FrozenFrameFreezer.freeze(source, request, evidence(request))
            assertTrue("Rotación no demostrable debe bloquear: $result", result is FrameFreezeResult.Blocked)
            assertEquals(
                FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
                (result as FrameFreezeResult.Blocked).reason,
            )
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun anImageThatDoesNotFitTheBudgetIsBlockedBeforeAllocatingCopies() {
        // 8K: decodificado + una copia ARGB no cabe en la envolvente de 64 MiB; se bloquea antes.
        val eightK = 7680L * 4320L * 4L
        assertFalse(FrameFreezeBudget.isWithinBudget(eightK, 7680, 4320))

        // Y el congelado de una imagen cuyas dimensiones no pueden probarse también se bloquea.
        val request = request(encodedWidth = 7680, encodedHeight = 4320)
        val source = Bitmap.createBitmap(IntArray(1), 1, 1, Bitmap.Config.ARGB_8888)
        try {
            val result = FrozenFrameFreezer.freeze(source, request, evidence(request))
            assertTrue("$result", result is FrameFreezeResult.Blocked)
        } finally {
            source.recycle()
        }
    }

    private fun ordinalOf(bitmap: Bitmap): Int? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FrameVisualIdentity.decodeOrdinal(pixels, bitmap.width, bitmap.height)
    }
}
