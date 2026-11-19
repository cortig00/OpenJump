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
import java.io.FileDescriptor
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas del hallazgo de banderas compartidas entre **alias del mismo extremo de
 * lectura**.
 *
 * ## Escenario que se demuestra
 *
 * `O_NONBLOCK` es una bandera de la *open file description*: dos `ParcelFileDescriptor.dup` del mismo
 * extremo de lectura de un pipe la comparten. Antes de la corrección, con dos adaptadores en vuelo el
 * primero en cerrar desactivaba la bandera mientras el segundo seguía leyendo (lectura bloqueante,
 * cancelación no demostrable) y el último en cerrar dejaba `O_NONBLOCK` activado en los descriptores
 * del proveedor. La prueba usa **dos `dup` + un tercer `dup` observador** de un pipe real y comprueba:
 *
 * 1. el pipe nace bloqueante y el primer adaptador deja el extremo en modo no bloqueante;
 * 2. una segunda apertura concurrente sobre otro `dup` se rechaza con [SourceDescriptorBusy] **sin
 *    cambiar ninguna bandera** (la palabra completa de `F_GETFL` del observador no varía) y el primer
 *    flujo sigue leyendo datos reales en modo no bloqueante;
 * 3. al cerrar el primero, el extremo vuelve **exactamente** al estado inicial (no bloqueante
 *    desactivado);
 * 4. la apertura se puede **reintentar** y el cierre del segundo deja otra vez el estado inicial;
 * 5. a través de la costura pública, la captura concurrente devuelve `SOURCE_BUSY` recuperable, no
 *    deja snapshot ni reserva, no bloquea la captura de un fichero **seekable** y, tras liberar el
 *    permiso, un pipe normal sella el fixture byte a byte.
 *
 * ## Límites
 *
 * - Requiere dispositivo/AVD (pipes, `Os.poll`, `fcntl` reales). Este slice no arranca AVD: la prueba
 *   se compila y queda lista para el siguiente slice.
 * - No se ejerce la resolución por `ContentResolver`/Binder (el proveedor de test se instancia en
 *   proceso), igual que el resto de pruebas de esta frontera.
 * - Un `dup` hecho por otro proceso, o un descriptor retenido por el proveedor fuera de estos
 *   adaptadores, comparte igualmente la *open file description*: es el límite documentado y no se
 *   afirma resuelto.
 * - **API 30+:** `Os.fcntlInt` (`F_GETFL`/`F_SETFL`) solo está en la superficie pública del SDK desde
 *   API 30. En API 26–29 la producción **rechaza** el origen no seekable con
 *   [SourceNonSeekableUnsupported] antes de tocar el descriptor (comportamiento cubierto por
 *   `NonSeekableSourceApiGateInstrumentedTest`), así que esta prueba de banderas reales se ejecuta
 *   solo en API 30+ con [SdkSuppress]; no es un fallo oculto de producción.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class NonSeekableStreamFlagSharingInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext: Context = instrumentation.context
    private val targetContext: Context = instrumentation.targetContext

    private fun fixtureBytes(): ByteArray =
        testContext.assets.open("angles/ordinal_cfr_bframes.mp4").use { it.readBytes() }

    private fun snapshotFileCount(): Int =
        File(targetContext.cacheDir, FrozenFrameSources.SNAPSHOT_DIRECTORY).listFiles()?.size ?: 0

    private fun flagsOf(fd: FileDescriptor): Int = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)

    private fun isNonBlocking(fd: FileDescriptor): Boolean = flagsOf(fd) and OsConstants.O_NONBLOCK != 0

    /** Toda la palabra de banderas salvo `O_NONBLOCK`: el resto no puede cambiar en ningún momento. */
    private fun flagsWithoutNonBlock(fd: FileDescriptor): Int = flagsOf(fd) and OsConstants.O_NONBLOCK.inv()

    private fun dupOf(descriptor: ParcelFileDescriptor): ParcelFileDescriptor =
        ParcelFileDescriptor.dup(descriptor.fileDescriptor)

    private fun assetDescriptor(readEnd: ParcelFileDescriptor, startOffset: Long = 0L): AssetFileDescriptor =
        TestOffsetAssetProvider().apply {
            openDescriptor = { readEnd }
            declaredStartOffset = startOffset
            declaredLength = AssetFileDescriptor.UNKNOWN_LENGTH
        }.openAssetFile(Uri.parse("content://com.openjump.app.test.angles-flag-sharing/source"), "r")
            ?: throw AssertionError("El proveedor de test no entregó descriptor")

    @Test
    fun secondAdapterOnADupOfTheSameReadEndIsRejectedWithoutTouchingFlags() {
        val pipe = ParcelFileDescriptor.createPipe()
        val writeEnd = pipe[1]
        val base = pipe[0]
        val first = dupOf(base)
        val second = dupOf(base)
        val observer = dupOf(base)
        try {
            val initialFlags = flagsOf(observer.fileDescriptor)
            assertFalse(
                "El extremo de lectura de un pipe nace bloqueante",
                initialFlags and OsConstants.O_NONBLOCK != 0,
            )

            val streamA = AndroidPollingSourceStreams.open(first, { false })
            try {
                assertTrue(
                    "Un adaptador activo debe dejar el extremo de lectura no bloqueante",
                    isNonBlocking(observer.fileDescriptor),
                )
                assertEquals(
                    "El adaptador sólo puede cambiar O_NONBLOCK",
                    initialFlags,
                    flagsWithoutNonBlock(observer.fileDescriptor),
                )
                val flagsWhileA = flagsOf(observer.fileDescriptor)

                val error = runCatching { AndroidPollingSourceStreams.open(second, { false }) }.exceptionOrNull()
                assertTrue(
                    "La segunda apertura sobre un dup del mismo extremo debe rechazarse de forma tipada: $error",
                    error is SourceDescriptorBusy,
                )
                assertEquals(
                    "La segunda apertura no puede haber modificado ninguna bandera compartida",
                    flagsWhileA,
                    flagsOf(observer.fileDescriptor),
                )
                assertTrue("El primer flujo conserva el permiso", NonSeekablePollingLease.isHeld)

                // El primer flujo sigue operativo: lee datos reales sin bloquearse.
                val payload = byteArrayOf(0x4F, 0x4A, 0x0A, 0x01, 0x02, 0x03)
                FileOutputStream(writeEnd.fileDescriptor).use { it.write(payload) }
                val buffer = ByteArray(payload.size)
                assertEquals("El flujo no bloqueante debe recibir los bytes del pipe", payload.size, streamA.read(buffer, 0, buffer.size))
                assertArrayEquals(payload, buffer)
            } finally {
                streamA.close()
            }
            assertEquals(
                "Al cerrar A se restaura el estado inicial exacto",
                initialFlags,
                flagsOf(observer.fileDescriptor),
            )

            val streamB = AndroidPollingSourceStreams.open(second, { false })
            try {
                assertTrue("La apertura es recuperable tras liberar el permiso", isNonBlocking(observer.fileDescriptor))
            } finally {
                streamB.close()
            }
            assertEquals(
                "El cierre de B vuelve a dejar el estado inicial",
                initialFlags,
                flagsOf(observer.fileDescriptor),
            )
        } finally {
            runCatching { observer.close() }
            runCatching { second.close() }
            runCatching { first.close() }
            runCatching { base.close() }
            runCatching { writeEnd.close() }
        }
    }

    @Test
    fun concurrentNonSeekableCaptureIsRejectedRecoverablyWithoutBlockingSeekableSources() {
        val filesBefore = snapshotFileCount()
        val pinnedBefore = SnapshotLeases.pinnedCount()
        val pipe = ParcelFileDescriptor.createPipe()
        val base = pipe[0]
        val held = dupOf(base)
        val contender = dupOf(base)
        val streamA = AndroidPollingSourceStreams.open(held, { false })
        val seekableFile = File(targetContext.cacheDir, "angles_flag_sharing_seekable.bin")
        try {
            // 1) Captura concurrente no seekable: rechazo tipado y recuperable, sin residuos.
            val busy = FrozenFrameSources.sealFromAssetDescriptor(targetContext, assetDescriptor(contender))
            val unavailable = busy as? FrozenFrameSourceOpen.Unavailable
                ?: throw AssertionError("Se esperaba rechazo tipado y fue $busy")
            assertEquals(SourceOpenFailure.SOURCE_BUSY, unavailable.failure)
            assertTrue("El rechazo debe explicar que es transitorio", unavailable.detail.isNotBlank())
            assertEquals("No puede quedar snapshot de una apertura rechazada", filesBefore, snapshotFileCount())
            assertEquals("No puede quedar una reserva colgada", pinnedBefore, SnapshotLeases.pinnedCount())

            // 2) Un fichero seekable no pasa por el permiso: la captura sigue funcionando.
            val fixture = fixtureBytes()
            seekableFile.writeBytes(fixture)
            val fileDescriptor = TestOffsetAssetProvider().apply {
                openDescriptor = { ParcelFileDescriptor.open(seekableFile, ParcelFileDescriptor.MODE_READ_ONLY) }
                declaredLength = fixture.size.toLong()
            }.openAssetFile(Uri.parse("content://com.openjump.app.test.angles-offset/seekable"), "r")
                ?: throw AssertionError("El proveedor de test no entregó descriptor de fichero")
            val seekableOpen = FrozenFrameSources.sealFromAssetDescriptor(targetContext, fileDescriptor)
            val session = (seekableOpen as? FrozenFrameSourceOpen.Opened)?.session
                ?: throw AssertionError("Un fichero seekable no puede quedar bloqueado por el permiso: $seekableOpen")
            try {
                assertEquals(fixture.size.toLong(), session.snapshotLengthBytes)
            } finally {
                session.close()
            }
            assertEquals("La captura seekable no deja snapshot tras cerrar", filesBefore, snapshotFileCount())
        } finally {
            streamA.close()
            runCatching { seekableFile.delete() }
            runCatching { contender.close() }
            runCatching { held.close() }
            runCatching { base.close() }
            runCatching { pipe[1].close() }
        }
        assertFalse("Al cerrar el flujo titular el permiso queda libre", NonSeekablePollingLease.isHeld)

        // 3) Recuperable: un pipe normal sella el fixture completo tras liberar el permiso.
        val retryPipe = ParcelFileDescriptor.createPipe()
        val retryRead = dupOf(retryPipe[0])
        val fixture = fixtureBytes()
        val writer = Thread {
            try {
                FileOutputStream(retryPipe[1].fileDescriptor).use { it.write(fixture) }
            } finally {
                runCatching { retryPipe[1].close() }
            }
        }.apply { isDaemon = true }
        writer.start()
        val retried = try {
            FrozenFrameSources.sealFromAssetDescriptor(targetContext, assetDescriptor(retryRead))
        } finally {
            writer.join(10_000L)
        }
        val retriedSession = (retried as? FrozenFrameSourceOpen.Opened)?.session
            ?: throw AssertionError("Tras liberar el permiso, un pipe normal debe sellarse: $retried")
        try {
            assertEquals(fixture.size.toLong(), retriedSession.snapshotLengthBytes)
        } finally {
            retriedSession.close()
            runCatching { retryRead.close() }
            runCatching { retryPipe[0].close() }
        }
    }
}
