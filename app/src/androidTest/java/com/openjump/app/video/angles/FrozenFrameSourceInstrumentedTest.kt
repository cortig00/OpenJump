package com.openjump.app.video.angles

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de la frontera de fuente (slice de corrección B2/P1, revisión B3).
 *
 * Cubren lo que una prueba JVM no puede: un `content://` real resuelto por [FileProvider] con
 * descriptor y longitud, una fuente no disponible, el reemplazo de la fuente después de abrir la
 * sesión, la cancelación durante la copia y dos fuentes con la misma metadata/PTS que **no** deben
 * atribuirse entre sí.
 *
 * ## Contrato de fallos tipados (revisado dos veces)
 *
 * La ejecución en AVD del 2026-11-10 (`/srv/projects/openjump-evidence/20261110-164557-b2-validation`)
 * dejó una prueba en rojo porque, tras sustituir la fuente por 4096 bytes `0x5A`, el resultado no era
 * [SourceOpenFailure.INVALID_VIDEO]. El `logcat` muestra
 * `E NuMediaExtractor: initMediaExtractor: failed to create MediaExtractor`: `setDataSource(fd)`
 * lanzó `IOException`.
 *
 * La primera corrección añadió una sonda de cabecera: "cabecera no reconocida + `IOException` ⇒
 * vídeo inválido". Ese diagnóstico **no era demostrable** y se retiró: la lista de firmas es
 * incompleta (un formato compatible no listado produce la misma evidencia), `initMediaExtractor`
 * puede fallar por el propio servicio de extractores y una cabecera no reconocida tampoco descarta
 * una E/S. Contrato vigente, sólo con evidencia local:
 *
 * - Error de E/S en abrir/copiar el snapshot o longitud sellada distinta: fallo tipado de E/S
 *   ([SourceOpenFailure.SOURCE_UNAVAILABLE], [SourceOpenFailure.COPY_FAILED],
 *   [SourceOpenFailure.STORAGE_UNAVAILABLE], [SourceOpenFailure.SOURCE_TOO_LARGE]); nunca se
 *   atribuye al contenido.
 * - `IOException` del extractor (bytes reconocibles o no): **ambigua**.
 *   [SourceOpenFailure.SOURCE_UNAVAILABLE] con un detalle que declara que Android no permite
 *   distinguir contenido no soportado/inválido de un fallo del extractor o de E/S.
 * - Veredicto estructural del extractor ("no contiene un track de vídeo", límite de tracks):
 *   [SourceOpenFailure.INVALID_VIDEO] con el mensaje real. Límites de frames:
 *   [SourceOpenFailure.SOURCE_TOO_LARGE].
 *
 * Nada de esto entrega píxeles: todas las rutas de fallo son cerradas y la sesión sellada de la
 * fuente A sigue sirviendo su snapshot con independencia del fichero original.
 *
 * ## Higiene de fixtures (corrección B3)
 *
 * Cada prueba crea su propio fixture con nombre único y creación atómica (`File.createTempFile`) en
 * la caché de la app bajo prueba, y borra sólo el fichero que ella misma creó, en `finally` y también
 * si la copia falla a medias. Ninguna prueba reutiliza ni sobrescribe ficheros de ejecuciones
 * anteriores (antes se reescribía `cache/trajectory_exports/angles_source_uri_fixture.mp4` de un run
 * previo). La URI `content://` se publica por [FileProvider] sobre ese temporal propio y se retira al
 * terminar. No se borra ni se toca ningún otro fichero del dispositivo.
 *
 * Ejecución en un AVD propiedad de un único escritor:
 * ```
 * adb shell am instrument -w -r -e class com.openjump.app.video.angles.FrozenFrameSourceInstrumentedTest \
 *   com.openjump.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FrozenFrameSourceInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val fixtureName = "angles/ordinal_cfr_bframes.mp4"

    /**
     * Copia el fixture a un fichero nuevo, con nombre único acuñado atómicamente por
     * [File.createTempFile]. El llamador es el único propietario y debe borrarlo.
     */
    private fun copyFixtureToOwnTempFile(directory: File): File {
        directory.mkdirs()
        val target = File.createTempFile("angles_", ".mp4", directory)
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

    private fun fixtureBytes(): ByteArray =
        instrumentation.context.assets.open(fixtureName).use { it.readBytes() }

    /**
     * Publica por `FileProvider` una copia temporal **propia** (nombre único) del fixture y devuelve
     * su URI junto al fichero, que el llamador debe borrar al terminar. No reutiliza ni sobrescribe
     * copias de ejecuciones anteriores.
     */
    private fun contentUriForOwnTempCopy(): Pair<Uri, File> {
        val directory = File(context.cacheDir, "trajectory_exports").apply { mkdirs() }
        val file = copyFixtureToOwnTempFile(directory)
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) to file
        } catch (error: Throwable) {
            // Si publicar la URI falla, el temporal tampoco se deja atrás.
            file.delete()
            throw error
        }
    }

    private fun openUri(uri: Uri, cancellationCheck: () -> Boolean = { false }): FrozenFrameSourceOpen =
        FrozenFrameSources.openFromUri(context, uri, cancellationCheck = cancellationCheck)

    private fun openedSession(open: FrozenFrameSourceOpen): FrozenFrameSourceSession = when (open) {
        is FrozenFrameSourceOpen.Opened -> open.session
        is FrozenFrameSourceOpen.Unavailable ->
            throw AssertionError("Se esperaba una sesión abierta y fue ${open.failure}: ${open.detail}")
    }

    /** Fallo tipado con mensaje explícito: el valor real y el detalle siempre aparecen en el error. */
    private fun unavailableOf(open: FrozenFrameSourceOpen, expectation: String): FrozenFrameSourceOpen.Unavailable {
        assertTrue("$expectation. Resultado real: $open", open is FrozenFrameSourceOpen.Unavailable)
        return open as FrozenFrameSourceOpen.Unavailable
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

    private fun snapshotFileCount(): Int =
        File(context.cacheDir, FrozenFrameSources.SNAPSHOT_DIRECTORY).listFiles()?.size ?: 0

    @Test
    fun aRealContentUriIsSealedAndCapturedFromThePrivateSnapshot() {
        val (uri, fixture) = contentUriForOwnTempCopy()
        try {
            val session = openedSession(openUri(uri))
            session.use {
                assertEquals(24, it.index.frameCount)
                assertEquals(320, it.index.encodedWidth)
                assertEquals(240, it.index.encodedHeight)
                val frameIndex = 3
                val result = it.capture(it.requireRequest(frameIndex))
                assertTrue("La captura por URI real falló: $result", result is FrameFreezeResult.Frozen)
                val image = (result as FrameFreezeResult.Frozen).image
                try {
                    assertEquals("Ordinal inesperado en el frame $frameIndex", frameIndex, ordinalOf(image.bitmap))
                    assertEquals(it.identity, image.provenance.sourceIdentity)
                } finally {
                    image.close()
                }
            }
            assertFalse("close() debe borrar el snapshot", session.isOpen)
        } finally {
            // Sólo se borra el temporal que esta prueba creó.
            fixture.delete()
        }
    }

    @Test
    fun aMissingContentSourceFailsWithATypedFailureInsteadOfThrowing() {
        // Nombre aleatorio: nunca apunta a un fichero de una ejecución anterior (ni lo crea).
        val missingName = "angles_missing_${UUID.randomUUID()}.mp4"
        val missing = Uri.parse("content://${context.packageName}.fileprovider/trajectory_exports/$missingName")
        val result = openUri(missing)
        val failure = unavailableOf(result, "Una fuente content:// inexistente debe fallar de forma tipada")
        assertEquals(
            "Un error real del proveedor es E/S de apertura, no contenido inválido. Detalle: ${failure.detail}",
            SourceOpenFailure.SOURCE_UNAVAILABLE,
            failure.failure,
        )
    }

    @Test
    fun aCancelledOpenDoesNotLeaveASnapshotBehind() {
        val before = snapshotFileCount()
        val source = copyFixtureToOwnTempFile(context.cacheDir)
        try {
            val result = FrozenFrameSources.openFromFile(
                context,
                source,
                cancellationCheck = { true },
            )
            val failure = unavailableOf(result, "Una apertura cancelada debe devolver Unavailable")
            assertEquals(
                "La cancelación debe tiparse como CANCELLED. Detalle: ${failure.detail}",
                SourceOpenFailure.CANCELLED,
                failure.failure,
            )
            val after = snapshotFileCount()
            assertEquals("Una apertura cancelada no debe sellar snapshots ($before → $after)", before, after)
        } finally {
            source.delete()
        }
    }

    @Test
    fun replacingTheSourceAfterOpeningDoesNotChangeTheSessionContent() {
        val source = copyFixtureToOwnTempFile(context.cacheDir)
        try {
            val session = openedSession(FrozenFrameSources.openFromFile(context, source))
            session.use {
                // Otro editor reemplaza el fichero por un contenido distinto (longitud y bytes).
                source.writeBytes(ByteArray(4096) { 0x5A })
                val result = it.capture(it.requireRequest(5))
                assertTrue(
                    "El snapshot sellado debe seguir sirviendo el contenido original: $result",
                    result is FrameFreezeResult.Frozen,
                )
                val image = (result as FrameFreezeResult.Frozen).image
                try {
                    assertEquals("La captura sellada no debe cambiar de frame", 5, ordinalOf(image.bitmap))
                } finally {
                    image.close()
                }
                // Una sesión nueva ve el reemplazo y no abre sesión: nada que capturar, ningún bitmap
                // y ninguna identidad (la identidad sólo la acuña una sesión abierta).
                val failure = unavailableOf(
                    FrozenFrameSources.openFromFile(context, source),
                    "El reemplazo ilegible debe fallar de forma tipada",
                )
                assertTrue(
                    "La reapertura del reemplazo no puede acusar al formato: ${failure.detail}",
                    failure.detail.contains("no permite distinguir"),
                )
            }
        } finally {
            source.delete()
        }
    }

    /**
     * 4096 bytes `0x5A` no son un vídeo indexable, pero eso **no** demuestra que el formato sea
     * inválido: el extractor de este AVD falla al instanciarse (`initMediaExtractor: failed to create
     * MediaExtractor`) y lanza `IOException`. El fallo debe ser el ambiguo
     * [SourceOpenFailure.SOURCE_UNAVAILABLE], nunca una acusación de vídeo inválido; además no puede
     * quedar sesión abierta (no hay bitmap posible).
     */
    @Test
    fun unrecognizedBytesAreRejectedAsAmbiguousInsteadOfAccusingTheFormat() {
        val source = copyFixtureToOwnTempFile(context.cacheDir)
        try {
            source.writeBytes(ByteArray(4096) { 0x5A })
            val result = FrozenFrameSources.openFromFile(context, source)
            assertTrue(
                "El contenido no indexable debe fallar de forma tipada: $result",
                result is FrozenFrameSourceOpen.Unavailable,
            )
            val failure = result as FrozenFrameSourceOpen.Unavailable
            assertEquals(
                "Una IOException del extractor sobre bytes no reconocidos es ambigua (también podría " +
                    "ser un formato compatible no soportado o un extractor no disponible); no se acusa " +
                    "al formato. Detalle: ${failure.detail}",
                SourceOpenFailure.SOURCE_UNAVAILABLE,
                failure.failure,
            )
            assertTrue(
                "El detalle debe declarar la ambigüedad y citar el error: ${failure.detail}",
                failure.detail.contains("no permite distinguir") && failure.detail.contains("IOException"),
            )
        } finally {
            source.delete()
        }
    }

    /**
     * Contrato verificable cuando Android decide por el estado del contenedor: un `ftyp` truncado
     * (MP4 reconocible sin `moov`) puede rechazarse con `IOException` (ambiguo) o con un veredicto
     * estructural del extractor (sin track de vídeo). Nunca por una lista de firmas ni por una
     * `IOException` presentada como contenido inválido.
     */
    @Test
    fun aRecognizableContainerRejectedByTheExtractorIsNotAccusedOfCorruption() {
        val source = copyFixtureToOwnTempFile(context.cacheDir)
        try {
            val bytes = fixtureBytes()
            // 0x20 = 32 bytes: la caja `ftyp` completa del fixture, sin `moov`/`mdat`.
            assertTrue("El fixture debe tener al menos la caja ftyp", bytes.size > 32)
            source.writeBytes(bytes.copyOf(32))
            val result = FrozenFrameSources.openFromFile(context, source)
            assertTrue(
                "Un ftyp truncado no puede sellarse como sesión: $result",
                result is FrozenFrameSourceOpen.Unavailable,
            )
            val failure = result as FrozenFrameSourceOpen.Unavailable
            when (failure.failure) {
                SourceOpenFailure.SOURCE_UNAVAILABLE -> assertTrue(
                    "Un fallo ambiguo del extractor sobre un contenedor reconocible debe declarar que no " +
                        "se atribuye al contenido: ${failure.detail}",
                    failure.detail.contains("no permite distinguir"),
                )

                SourceOpenFailure.INVALID_VIDEO -> assertTrue(
                    "Un contenedor reconocible sólo puede acusarse con un veredicto estructural del " +
                        "extractor (sin track de vídeo utilizable), nunca por cabecera: ${failure.detail}",
                    failure.detail.contains("track de vídeo utilizable"),
                )

                else -> throw AssertionError(
                    "Un contenedor truncado debe fallar como E/S ambigua o como vídeo no utilizable, y fue " +
                        "${failure.failure}: ${failure.detail}",
                )
            }
        } finally {
            source.delete()
        }
    }

    /** Error de apertura real: la ruta desaparece entre la apertura y la reapertura. A sigue viva. */
    @Test
    fun aSourceThatDisappearsAfterOpeningIsNotBlamedOnContentAndTheSessionSurvives() {
        val source = copyFixtureToOwnTempFile(context.cacheDir)
        try {
            val session = openedSession(FrozenFrameSources.openFromFile(context, source))
            session.use {
                assertTrue("El fichero de prueba debe existir antes de borrarlo", source.delete())
                val reopened = FrozenFrameSources.openFromFile(context, source)
                val failure = unavailableOf(reopened, "Una ruta borrada debe fallar de forma tipada")
                assertEquals(
                    "Una ruta que ya no existe es E/S de apertura, nunca contenido inválido. Detalle: ${failure.detail}",
                    SourceOpenFailure.SOURCE_UNAVAILABLE,
                    failure.failure,
                )
                // La sesión sellada no depende del fichero borrado.
                val captured = it.capture(it.requireRequest(2))
                assertTrue("La sesión A debe seguir sirviendo su snapshot: $captured", captured is FrameFreezeResult.Frozen)
                val image = (captured as FrameFreezeResult.Frozen).image
                try {
                    assertEquals("El snapshot de A no debe cambiar", 2, ordinalOf(image.bitmap))
                } finally {
                    image.close()
                }
            }
        } finally {
            source.delete()
        }
    }

    /** Rechazo por límite de copia: tipado, sin snapshot sellado residual y sin captura. */
    @Test
    fun aSnapshotLimitRejectionIsTypedAndLeavesNoSnapshotBehind() {
        val before = snapshotFileCount()
        val source = copyFixtureToOwnTempFile(context.cacheDir)
        try {
            val result = FrozenFrameSources.openFromFile(
                context,
                source,
                limits = FrozenFrameSourceLimits(maxSnapshotBytes = 1024),
            )
            val failure = unavailableOf(result, "Un fichero mayor que el tope debe fallar de forma tipada")
            assertEquals(
                "El tope de snapshot es un límite de copia, no contenido inválido. Detalle: ${failure.detail}",
                SourceOpenFailure.SOURCE_TOO_LARGE,
                failure.failure,
            )
            assertEquals("Un rechazo por tope no debe sellar snapshots", before, snapshotFileCount())
        } finally {
            source.delete()
        }
    }

    @Test
    fun twoSourcesWithIdenticalMetadataAndPtsAreNotInterchangeable() {
        val first = copyFixtureToOwnTempFile(context.cacheDir)
        // Fuente B: mismos metadatos/PTS (misma cabecera y tablas) pero bytes distintos en el cuerpo.
        val second = try {
            copyFixtureToOwnTempFile(context.cacheDir)
        } catch (error: Throwable) {
            first.delete()
            throw error
        }
        try {
            val bytes = second.readBytes()
            val perturbAt = (bytes.size * 0.8).toInt().coerceIn(0, bytes.size - 1)
            bytes[perturbAt] = (bytes[perturbAt].toInt() xor 0x5A).toByte()
            second.writeBytes(bytes)

            val firstOpen = FrozenFrameSources.openFromFile(context, first)
            val secondOpen = FrozenFrameSources.openFromFile(context, second)
            assertTrue(
                "La fuente B (mismo contenedor, cuerpo alterado) debe seguir abriendo: $secondOpen",
                secondOpen is FrozenFrameSourceOpen.Opened,
            )
            val sessionA = openedSession(firstOpen)
            sessionA.use { a ->
                val sessionB = openedSession(secondOpen)
                sessionB.use { b ->
                    // Misma metadata y mismos PTS indexados...
                    assertEquals(a.index.frameCount, b.index.frameCount)
                    assertEquals(a.index.encodedWidth, b.index.encodedWidth)
                    assertEquals(a.index.encodedHeight, b.index.encodedHeight)
                    assertEquals(a.index.frameTimesUs.toList(), b.index.frameTimesUs.toList())
                    assertNotNull(a.identity)
                    assertNotEquals("Dos contenidos distintos no pueden compartir identidad", a.identity, b.identity)

                    // ...pero la solicitud de A no se atribuye a B: se rechaza antes de decodificar.
                    val foreign = b.capture(a.requireRequest(4))
                    assertTrue("Se esperaba bloqueo y fue $foreign", foreign is FrameFreezeResult.Blocked)
                    assertEquals(
                        "La solicitud de A en la sesión B debe rechazarse por sesión inválida",
                        FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                        (foreign as FrameFreezeResult.Blocked).reason,
                    )
                    // La sesión de A sigue sirviendo su propio contenido.
                    val own = a.capture(a.requireRequest(4))
                    assertTrue("La captura propia de A falló: $own", own is FrameFreezeResult.Frozen)
                    val image = (own as FrameFreezeResult.Frozen).image
                    try {
                        assertEquals("La captura de A no debe servir píxeles de B", 4, ordinalOf(image.bitmap))
                    } finally {
                        image.close()
                    }
                }
            }
        } finally {
            first.delete()
            second.delete()
        }
    }
}
