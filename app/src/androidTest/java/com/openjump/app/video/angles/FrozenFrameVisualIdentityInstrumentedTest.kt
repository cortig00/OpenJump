package com.openjump.app.video.angles

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Experimento instrumentado (requiere AVD) de identidad **visual** por fotograma.
 *
 * El fixture `assets/angles/ordinal_cfr_bframes.mp4` contiene 24 frames CFR a 25 fps (PTS
 * 0, 40 000, …, 920 000 µs), H.264 High con B-frames (`has_b_frames=2`), cada uno con un patrón
 * sintético que codifica su ordinal de presentación. La verificación compara el ordinal leído de los
 * píxeles con el índice solicitado: **no** compara el PTS que el adaptador asigna, de modo que una
 * ruta que devuelva otro fotograma se detecta por contenido.
 *
 * Se comprueban dos rutas de MediaMetadataRetriever:
 * - `getFrameAtTime(OPTION_CLOSEST)`, disponible también en API 26–27.
 * - `getFrameAtIndex`, solo API ≥ 28 y cuando el frame count coincide.
 *
 * Ninguna de las dos expone el PTS de la muestra devuelta, así que la política las bloquea como
 * [FrameTemporalCorrelation.RETRIEVER_CLOSEST_UNVERIFIED] incluso si el ordinal coincide. El test fija
 * ese bloqueo: un bitmap visualmente correcto por una ruta no verificable **no** se etiqueta como
 * captura exacta.
 *
 * Instrucciones de ejecución (un solo propietario del AVD, sin borrar datos):
 * ```
 * ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.openjump.app.video.angles.FrozenFrameVisualIdentityInstrumentedTest
 * ```
 * Si falla el ordinal de algún frame, el mensaje incluye frame, tamaño y ordinal observado: es
 * evidencia de que esa ruta no puede sostener el contrato y debe usarse la ruta MediaCodec de B2.
 *
 * Higiene de fixtures (corrección B3): cada prueba crea su vídeo con nombre único
 * ([File.createTempFile]) en la caché de la app y borra sólo ese fichero; no se reutilizan ni
 * sobrescriben artefactos de ejecuciones anteriores.
 */
@RunWith(AndroidJUnit4::class)
class FrozenFrameVisualIdentityInstrumentedTest {

    private val fixtureName = "angles/ordinal_cfr_bframes.mp4"

    /**
     * Copia el fixture a un fichero nuevo de nombre único, creado atómicamente con
     * [File.createTempFile]: nunca reutiliza ni sobrescribe ficheros de ejecuciones anteriores. Cada
     * prueba es la única propietaria y lo borra en su `finally`.
     */
    private fun copyFixtureToCache(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File.createTempFile("angles_ordinal_", ".mp4", instrumentation.targetContext.cacheDir)
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

    private fun indexOf(video: File): VideoFrameIndex = VideoFrameIndexer.indexFromPath(video.absolutePath)

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { pixels ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun assertOrdinal(bitmap: Bitmap, requestedIndex: Int, route: String) {
        val pixels = pixelsOf(bitmap)
        val ordinal = FrameVisualIdentity.decodeOrdinal(pixels, bitmap.width, bitmap.height)
        assertEquals(
            "$route devolvió un fotograma cuyo ordinal visual no es el solicitado " +
                "(frame=$requestedIndex, tamaño=${bitmap.width}x${bitmap.height}, ordinal=$ordinal)",
            requestedIndex,
            ordinal,
        )
    }

    private fun frameRequest(index: VideoFrameIndex, frameIndex: Int) = FrameFreezeRequest(
        sourceIdentity = FrameSourceIdentity("androidtest:$fixtureName"),
        frameIndex = frameIndex,
        assignedPtsUs = index.timeOf(frameIndex),
        indexFrameCount = index.frameCount,
        encodedWidth = index.width,
        encodedHeight = index.height,
        declaredRotationDegrees = index.rotationDegrees,
    )

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun advertisedFrameCount(retriever: MediaMetadataRetriever): Int? =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun frameAtIndex(retriever: MediaMetadataRetriever, frameIndex: Int): Bitmap? =
        retriever.getFrameAtIndex(frameIndex)

    @Test
    fun fixtureIndexMatchesSyntheticPresentationOrder() {
        val video = copyFixtureToCache()
        try {
            val index = indexOf(video)
            assertEquals(24, index.frameCount)
            assertEquals(320, index.width)
            assertEquals(240, index.height)
            assertEquals(0, index.rotationDegrees)
            assertEquals(25, index.detectedFps)
            assertEquals(0L, index.frameTimesUs.first())
            assertEquals(920_000L, index.frameTimesUs.last())
            assertTrue(
                index.frameTimesUs.asSequence().zipWithNext().all { (a, b) -> b - a == 40_000L },
            )
        } finally {
            video.delete()
        }
    }

    @Test
    fun retrieverClosestVisualIdentityMatchesRequestedFrameForEveryFrame() {
        val video = copyFixtureToCache()
        val retriever = MediaMetadataRetriever()
        try {
            val index = indexOf(video)
            retriever.setDataSource(video.absolutePath)
            for (frameIndex in 0 until index.frameCount) {
                // PTS asignado desde el índice: se usa para pedir el frame, no como prueba.
                val assignedPtsUs = index.timeOf(frameIndex)
                val bitmap = requireNotNull(retriever.getFrameAtTime(assignedPtsUs, MediaMetadataRetriever.OPTION_CLOSEST)) {
                    "Sin bitmap para el frame $frameIndex (${assignedPtsUs}µs)."
                }
                try {
                    assertEquals(320, bitmap.width)
                    assertEquals(240, bitmap.height)
                    assertOrdinal(bitmap, frameIndex, "OPTION_CLOSEST")
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            retriever.release()
            video.delete()
        }
    }

    @Test
    fun frameAtIndexVisualIdentityMatchesRequestedFrameForEveryFrame() {
        assumeTrue("getFrameAtIndex requiere API ≥ 28", Build.VERSION.SDK_INT >= 28)
        val video = copyFixtureToCache()
        val retriever = MediaMetadataRetriever()
        try {
            val index = indexOf(video)
            retriever.setDataSource(video.absolutePath)
            val advertisedFrames = advertisedFrameCount(retriever)
            assumeTrue(
                "El retriever no anuncia el mismo frame count que el índice ($advertisedFrames vs ${index.frameCount}).",
                advertisedFrames == index.frameCount,
            )
            for (frameIndex in 0 until index.frameCount) {
                val bitmap = requireNotNull(frameAtIndex(retriever, frameIndex)) {
                    "Sin bitmap indexado para el frame $frameIndex."
                }
                try {
                    assertOrdinal(bitmap, frameIndex, "getFrameAtIndex")
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            retriever.release()
            video.delete()
        }
    }

    @Test
    fun anUnverifiableRetrieverRouteIsBlockedEvenWhenVisualIdentityMatches() {
        val video = copyFixtureToCache()
        val retriever = MediaMetadataRetriever()
        try {
            val index = indexOf(video)
            retriever.setDataSource(video.absolutePath)
            for (frameIndex in listOf(0, index.frameCount / 2, index.frameCount - 1)) {
                val request = frameRequest(index, frameIndex)
                val bitmap = requireNotNull(
                    retriever.getFrameAtTime(request.assignedPtsUs, MediaMetadataRetriever.OPTION_CLOSEST),
                )
                try {
                    // La identidad visual coincide, pero la ruta no aporta PTS de la muestra devuelta.
                    assertOrdinal(bitmap, frameIndex, "OPTION_CLOSEST")
                    val evidence = FrameTemporalEvidence.unverifiedRetriever(
                        assignedPtsUs = request.assignedPtsUs,
                        sourceIdentity = request.sourceIdentity,
                        frameIndex = request.frameIndex,
                    )
                    val result = FrozenFrameFreezer.freeze(bitmap, request, evidence)
                    assertTrue(
                        "Una ruta no verificable no puede producir un frame congelado: $result",
                        result is FrameFreezeResult.Blocked,
                    )
                    assertEquals(
                        FrameFreezeRejectionReason.UNVERIFIED_TEMPORAL_CORRELATION,
                        (result as FrameFreezeResult.Blocked).reason,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            retriever.release()
            video.delete()
        }
    }
}
