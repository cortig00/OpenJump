package com.openjump.app.video.angles

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de la **política de versión** para orígenes no seekables (pipe/socket).
 *
 * `Os.fcntlInt` (`F_GETFL`/`F_SETFL`) solo está en la superficie pública del SDK desde API 30. Con
 * `minSdk` 26 y sin reflexión ni API oculta, la decisión aprobada es:
 *
 * - **API 26–29:** rechazar el origen no seekable con [SourceNonSeekableUnsupported] /
 *   [SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED] **antes** de leer el descriptor, pedir banderas,
 *   sondear o tomar el permiso de proceso. El descriptor sigue siendo propiedad del llamador (no se
 *   cierra ahí; el `finally` de [FrozenFrameSources.sealFromAssetDescriptor] cierra el
 *   `AssetFileDescriptor`), no se crea snapshot ni reserva, y la UI ofrece elegir una copia local.
 * - **API 30+:** el sondeo no bloqueante sigue funcionando exactamente como antes; el rechazo de
 *   plataforma no aparece.
 *
 * Los ficheros **seekables** no dependen de la versión: se comprueba que en API 26–29 un
 * `AssetFileDescriptor` sobre un fichero regular sigue sellando e indexando.
 *
 * ## Límites y honestidad de la evidencia
 *
 * - Requiere dispositivo/AVD. La rama API 26–29 solo se ejecuta en un dispositivo con esa versión
 *   (`assumeTrue` la marca como *skipped* en API 30+); **un AVD API 35 no demuestra** el
 *   comportamiento en API 26–29. La rama API 30+ se ejecuta en API 30+ y no demuestra la anterior.
 * - La política pura (límite 26–29/30+, orden y cero efectos del rechazo, mapeo de la excepción) se
 *   fija además en JVM con `NonSeekablePlatformGateTest`, que no necesita dispositivo.
 * - No se ejercita la resolución por `ContentResolver`/Binder: el proveedor de test se instancia en
 *   proceso, igual que el resto de pruebas de esta frontera.
 */
@RunWith(AndroidJUnit4::class)
class NonSeekableSourceApiGateInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext: Context = instrumentation.context
    private val targetContext: Context = instrumentation.targetContext

    private fun fixtureBytes(): ByteArray =
        testContext.assets.open("angles/ordinal_cfr_bframes.mp4").use { it.readBytes() }

    private fun snapshotFileCount(): Int =
        File(targetContext.cacheDir, FrozenFrameSources.SNAPSHOT_DIRECTORY).listFiles()?.size ?: 0

    private fun assetDescriptor(
        descriptor: ParcelFileDescriptor,
        startOffset: Long = 0L,
        length: Long = AssetFileDescriptor.UNKNOWN_LENGTH,
    ): AssetFileDescriptor = TestOffsetAssetProvider().apply {
        openDescriptor = { descriptor }
        declaredStartOffset = startOffset
        declaredLength = length
    }.openAssetFile(Uri.parse("content://com.openjump.app.test.angles-api-gate/source"), "r")
        ?: throw AssertionError("El proveedor de test no entregó descriptor")

    private fun typedFailure(result: FrozenFrameSourceOpen?): FrozenFrameSourceOpen.Unavailable =
        result as? FrozenFrameSourceOpen.Unavailable
            ?: throw AssertionError("Se esperaba un fallo tipado y fue $result")

    @Test
    fun api26to29RejectsNonSeekableSourceBeforeTouchingTheCallerDescriptor() {
        assumeTrue(
            "Solo aplica a API 26–29 (dispositivo en API ${Build.VERSION.SDK_INT})",
            !NonSeekablePollingSupport.isSupported(Build.VERSION.SDK_INT),
        )

        // 1) Adaptador directo: rechazo tipado sin leer, cerrar ni tomar el permiso.
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val direct = runCatching { AndroidPollingSourceStreams.open(pipe[0], { false }) }.exceptionOrNull()
            assertTrue(
                "En API 26–29 el adaptador debe rechazar con la excepción tipada: $direct",
                direct is SourceNonSeekableUnsupported,
            )
            assertTrue(
                "El descriptor sigue siendo del llamador y no se cierra en el rechazo",
                pipe[0].fd >= 0,
            )
            assertFalse(
                "El rechazo no puede dejar tomado el permiso de proceso",
                NonSeekablePollingLease.isHeld,
            )
            // El llamador conserva la propiedad: puede cerrarlo él mismo sin error.
            runCatching { pipe[0].close() }.getOrThrow()
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }

        // 2) Costura pública: fallo tipado propio, sin snapshot, sin reserva y con la alternativa.
        val filesBefore = snapshotFileCount()
        val pinnedBefore = SnapshotLeases.pinnedCount()
        val pipe2 = ParcelFileDescriptor.createPipe()
        try {
            val descriptor = assetDescriptor(pipe2[0])
            val unavailable = typedFailure(FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor))
            assertEquals(SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED, unavailable.failure)
            assertTrue("El detalle no puede quedar vacío", unavailable.detail.isNotBlank())
            assertTrue(
                "El aviso debe ofrecer una copia local del vídeo: ${unavailable.detail}",
                unavailable.detail.contains("copia local"),
            )
            assertEquals("Un rechazo no puede sellar ni dejar snapshots", filesBefore, snapshotFileCount())
            assertEquals("Un rechazo no puede dejar reservas de snapshot", pinnedBefore, SnapshotLeases.pinnedCount())
            assertFalse("Un rechazo no puede retener el permiso no seekable", NonSeekablePollingLease.isHeld)
        } finally {
            runCatching { pipe2[0].close() }
            runCatching { pipe2[1].close() }
        }
    }

    @Test
    fun api26to29StillSealsSeekableAssetDescriptors() {
        assumeTrue(
            "Solo aplica a API 26–29 (dispositivo en API ${Build.VERSION.SDK_INT})",
            !NonSeekablePollingSupport.isSupported(Build.VERSION.SDK_INT),
        )
        val source = File(targetContext.cacheDir, "angles_api_gate_seekable_${System.nanoTime()}.mp4")
        try {
            FileOutputStream(source).use { it.write(fixtureBytes()) }
            val descriptor = assetDescriptor(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY))
            val open = FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor)
            val session = (open as? FrozenFrameSourceOpen.Opened)?.session
                ?: throw AssertionError("Un fichero regular debe seguir sellándose en API 26–29: $open")
            try {
                assertTrue("El índice del fixture debe tener fotogramas", session.index.frameCount >= 1)
                assertEquals("El snapshot debe medir lo que el fixture", fixtureBytes().size.toLong(), session.snapshotLengthBytes)
            } finally {
                session.close()
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun api30plusKeepsTheNonBlockingPollingPath() {
        assumeTrue(
            "La ruta no bloqueante solo existe en API 30+ (dispositivo en API ${Build.VERSION.SDK_INT})",
            NonSeekablePollingSupport.isSupported(Build.VERSION.SDK_INT),
        )
        val pipe = ParcelFileDescriptor.createPipe()
        val stream = AndroidPollingSourceStreams.open(pipe[0], { false })
        try {
            assertTrue("La apertura admitida toma el permiso de proceso", NonSeekablePollingLease.isHeld)
            FileOutputStream(pipe[1].fileDescriptor).use { it.write(PAYLOAD) }
            runCatching { pipe[1].close() }
            val read = ByteArray(PAYLOAD.size)
            var offset = 0
            while (offset < read.size) {
                val count = stream.read(read, offset, read.size - offset)
                if (count < 0) break
                offset += count
            }
            assertEquals("El flujo no bloqueante debe entregar los bytes del pipe", PAYLOAD.size, offset)
            assertTrue("Los bytes deben coincidir", read.contentEquals(PAYLOAD))
        } finally {
            stream.close()
            runCatching { pipe[1].close() }
        }
        assertFalse("Cerrar el flujo libera el permiso", NonSeekablePollingLease.isHeld)
    }

    private companion object {
        val PAYLOAD = byteArrayOf(0x4F, 0x4A, 0x2D, 0x41, 0x4E, 0x47, 0x4C, 0x45)
    }
}
