package com.openjump.app.video.angles

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas del tercer hallazgo: una copia sobre un pipe **no puede quedar bloqueada**
 * cuando el escritor del proveedor sigue abierto y no entrega datos.
 *
 * ## Qué se demuestra
 *
 * - **Cancelación real con pipe real:** se abre `ParcelFileDescriptor.createPipe()`, se entrega el
 *   extremo de lectura en un [AssetFileDescriptor] y se inicia la copia en un hilo con el extremo de
 *   escritura **abierto y sin datos**. La prueba espera a que la copia esté esperando, pide
 *   cancelación y exige que el hilo termine en plazo con [SourceOpenFailure.CANCELLED]. Sin sondeo no
 *   bloqueante este escenario no terminaría: es el caso que el hallazgo describe.
 * - **Sin recursos huérfanos:** al terminar, el descriptor está cerrado, no hay snapshot/temporal en
 *   caché y el registro de reservas vuelve al valor inicial. El hilo de copia ya no existe (no hay
 *   lectura bloqueada huérfana).
 * - **Banderas compartidas:** se comprueba con `F_GETFL` que el extremo de **escritura** del pipe
 *   conserva `O_NONBLOCK` desactivado mientras la lectura no bloqueante está activa. El cambio de
 *   bandera se aplica al extremo de lectura y se restaura antes de cerrar.
 * - **Sin regresión de pipes válidos:** un pipe con longitud desconocida y otro con offset/longitud
 *   declarados sellan exactamente el fixture (comparado byte a byte con el descriptor interno de la
 *   sesión), sin prefijo descartado ni sufijo de más. Un pipe cuyo escritor cierra sin datos falla
 *   tipado y no deja ficheros.
 *
 * ## Límites
 *
 * - Requiere dispositivo/AVD: `Os.poll`, `Os.read` y los pipes son código de plataforma real. Este
 *   slice no arranca AVD; la prueba se ejecuta con
 *   `adb shell am instrument -w -r -e class com.openjump.app.video.angles.PollingPipeSourceInstrumentedTest com.openjump.app.test/androidx.test.runner.AndroidJUnitRunner`.
 * - No se ejercita la resolución por `ContentResolver`/Binder (el proveedor de test se instancia en
 *   proceso), igual que el resto de pruebas de esta frontera de fuente.
 * - No se toca Room ni fixtures compartidos: el fixture se lee del APK de test y los ficheros de
 *   trabajo viven en la caché del proceso instrumentado, con nombres propios.
 * - **API 30+:** `Os.fcntlInt` solo está en la superficie pública del SDK desde API 30 y en API 26–29
 *   la producción rechaza el origen no seekable ([SourceNonSeekableUnsupported] /
 *   `NON_SEEKABLE_UNSUPPORTED`) antes de tocar el descriptor. Por eso el sondeo real de esta suite se
 *   ejecuta solo en API 30+ con [SdkSuppress]; el rechazo en 26–29 lo cubre
 *   `NonSeekableSourceApiGateInstrumentedTest`.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PollingPipeSourceInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext: Context = instrumentation.context
    private val targetContext: Context = instrumentation.targetContext
    private val fixtureName = "angles/ordinal_cfr_bframes.mp4"

    private fun fixtureBytes(): ByteArray = testContext.assets.open(fixtureName).use { it.readBytes() }

    private fun snapshotFileCount(): Int =
        File(targetContext.cacheDir, FrozenFrameSources.SNAPSHOT_DIRECTORY).listFiles()?.size ?: 0

    private fun pipeDescriptor(
        startOffset: Long,
        length: Long,
        readEnd: ParcelFileDescriptor,
    ): AssetFileDescriptor = TestOffsetAssetProvider().apply {
        openDescriptor = { readEnd }
        declaredStartOffset = startOffset
        declaredLength = length
    }.openAssetFile(Uri.parse("content://com.openjump.app.test.angles-pipe/source"), "r")
        ?: throw AssertionError("El proveedor de test no entregó descriptor")

    private fun assertDescriptorClosed(descriptor: ParcelFileDescriptor) {
        // En API 35 getFd() sobre un descriptor cerrado lanza IllegalStateException (no devuelve -1).
        assertThrows(IllegalStateException::class.java) { descriptor.fd }
    }

    private fun assertTypedFailure(
        result: FrozenFrameSourceOpen?,
        expected: SourceOpenFailure,
    ) {
        val unavailable = result as? FrozenFrameSourceOpen.Unavailable
            ?: throw AssertionError("Se esperaba fallo tipado ($expected) y fue $result")
        assertEquals(expected, unavailable.failure)
        assertTrue(unavailable.detail.isNotBlank())
    }

    private fun sealedSnapshotBytes(session: FrozenFrameSourceSession): ByteArray {
        val opened = session.openSnapshotDescriptor() as? SnapshotDescriptorOpen.Opened
            ?: throw AssertionError("No se pudo abrir el descriptor del snapshot")
        return opened.descriptor.use { parcel ->
            FileInputStream(parcel.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun assertWriteEndStillBlocking(writeEnd: ParcelFileDescriptor) {
        val flags = Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_GETFL, 0)
        assertEquals(
            "El extremo de escritura del pipe no puede heredar O_NONBLOCK del extremo de lectura",
            0,
            flags and OsConstants.O_NONBLOCK,
        )
    }

    @Test
    fun pipeWithOpenWriterAndNoDataCancelsWithinDeadlineAndCleansUp() {
        val filesBefore = snapshotFileCount()
        val pinnedBefore = SnapshotLeases.pinnedCount()
        val pipe = ParcelFileDescriptor.createPipe()
        val descriptor = pipeDescriptor(0L, AssetFileDescriptor.UNKNOWN_LENGTH, pipe[0])
        val cancelled = AtomicBoolean(false)
        val result = AtomicReference<FrozenFrameSourceOpen?>()
        val worker = Thread {
            result.set(
                FrozenFrameSources.sealFromAssetDescriptor(
                    targetContext,
                    descriptor,
                    cancellationCheck = { cancelled.get() },
                ),
            )
        }.apply { isDaemon = true }
        try {
            worker.start()
            Thread.sleep(300L)
            assertTrue(
                "La copia debe estar esperando datos con el escritor abierto, no haber terminado",
                worker.isAlive,
            )
            assertWriteEndStillBlocking(pipe[1])

            cancelled.set(true)
            worker.join(5_000L)
            assertFalse(
                "La cancelación debe terminar la copia aunque el escritor siga abierto y sin datos",
                worker.isAlive,
            )
            assertTypedFailure(result.get(), SourceOpenFailure.CANCELLED)
            assertDescriptorClosed(descriptor.parcelFileDescriptor)
            assertEquals("No puede quedar snapshot ni temporal propio", filesBefore, snapshotFileCount())
            assertEquals("No puede quedar una reserva colgada", pinnedBefore, SnapshotLeases.pinnedCount())
        } finally {
            cancelled.set(true)
            runCatching { worker.join(1_000L) }
            runCatching { pipe[1].close() }
            runCatching { pipe[0].close() }
            runCatching { descriptor.close() }
        }
    }

    @Test
    fun pipeWithOpenWriterAndNoDataCancelsDuringOffsetDiscard() {
        val filesBefore = snapshotFileCount()
        val pinnedBefore = SnapshotLeases.pinnedCount()
        val pipe = ParcelFileDescriptor.createPipe()
        val descriptor = pipeDescriptor(32L, 1_024L, pipe[0])
        val cancelled = AtomicBoolean(false)
        val result = AtomicReference<FrozenFrameSourceOpen?>()
        val worker = Thread {
            result.set(
                FrozenFrameSources.sealFromAssetDescriptor(
                    targetContext,
                    descriptor,
                    cancellationCheck = { cancelled.get() },
                ),
            )
        }.apply { isDaemon = true }
        try {
            worker.start()
            Thread.sleep(300L)
            assertTrue("El descarte del offset también debe estar esperando datos", worker.isAlive)
            cancelled.set(true)
            worker.join(5_000L)
            assertFalse("La cancelación debe terminar el descarte del offset", worker.isAlive)
            assertTypedFailure(result.get(), SourceOpenFailure.CANCELLED)
            assertDescriptorClosed(descriptor.parcelFileDescriptor)
            assertEquals(filesBefore, snapshotFileCount())
            assertEquals(pinnedBefore, SnapshotLeases.pinnedCount())
        } finally {
            cancelled.set(true)
            runCatching { worker.join(1_000L) }
            runCatching { pipe[1].close() }
            runCatching { pipe[0].close() }
            runCatching { descriptor.close() }
        }
    }

    @Test
    fun pipeWithUnknownLengthStillSealsTheFixture() {
        val fixture = fixtureBytes()
        val filesBefore = snapshotFileCount()
        val pipe = ParcelFileDescriptor.createPipe()
        val descriptor = pipeDescriptor(0L, AssetFileDescriptor.UNKNOWN_LENGTH, pipe[0])
        val writer = Thread {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(fixture) }
            } finally {
                runCatching { pipe[1].close() }
            }
        }.apply { isDaemon = true }
        writer.start()
        val session = try {
            val opened = FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor)
            (opened as? FrozenFrameSourceOpen.Opened)?.session
                ?: throw AssertionError("Se esperaba una sesión sellada y fue $opened")
        } finally {
            writer.join(10_000L)
        }
        try {
            assertEquals(fixture.size.toLong(), session.snapshotLengthBytes)
            assertArrayEquals(
                "Un pipe no bloqueante debe sellar el fixture byte a byte",
                fixture,
                sealedSnapshotBytes(session),
            )
            assertTrue("El fixture indexado debe tener frames", session.index.frameCount > 0)
        } finally {
            session.close()
        }
        assertEquals("Al cerrar no puede quedar snapshot", filesBefore, snapshotFileCount())
    }

    @Test
    fun pipeWithDeclaredOffsetAndLengthDoesNotOverreadTheSuffix() {
        val fixture = fixtureBytes()
        val prefix = ByteArray(32) { 0x5A }
        val suffix = ByteArray(48) { 0xA5.toByte() }
        val payload = prefix + fixture + suffix
        val pipe = ParcelFileDescriptor.createPipe()
        val descriptor = pipeDescriptor(prefix.size.toLong(), fixture.size.toLong(), pipe[0])
        val writer = Thread {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(payload) }
            } finally {
                runCatching { pipe[1].close() }
            }
        }.apply { isDaemon = true }
        writer.start()
        val session = try {
            val opened = FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor)
            (opened as? FrozenFrameSourceOpen.Opened)?.session
                ?: throw AssertionError("Se esperaba una sesión sellada y fue $opened")
        } finally {
            writer.join(10_000L)
        }
        try {
            assertEquals(fixture.size.toLong(), session.snapshotLengthBytes)
            assertArrayEquals(
                "Ni el prefijo descartado ni el sufijo fuera del rango pueden llegar al snapshot",
                fixture,
                sealedSnapshotBytes(session),
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun pipeWhoseWriterClosesWithoutDataFailsTypedAndLeavesNothing() {
        val filesBefore = snapshotFileCount()
        val pinnedBefore = SnapshotLeases.pinnedCount()
        val pipe = ParcelFileDescriptor.createPipe()
        runCatching { pipe[1].close() }
        val descriptor = pipeDescriptor(0L, AssetFileDescriptor.UNKNOWN_LENGTH, pipe[0])
        try {
            val result = FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor)
            assertTypedFailure(result, SourceOpenFailure.COPY_FAILED)
            assertDescriptorClosed(descriptor.parcelFileDescriptor)
            assertEquals(filesBefore, snapshotFileCount())
            assertEquals(pinnedBefore, SnapshotLeases.pinnedCount())
        } finally {
            runCatching { pipe[0].close() }
            runCatching { descriptor.close() }
        }
    }
}
