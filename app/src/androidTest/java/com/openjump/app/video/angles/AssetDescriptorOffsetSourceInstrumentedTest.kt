package com.openjump.app.video.angles

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba instrumentada del contrato de `AssetFileDescriptor` de la fuente (hallazgo B2 #1, revisado
 * tras la auditoría del padre).
 *
 * El scout no demostró que `AssetFileDescriptor.createInputStream()` respete `startOffset`/`length`,
 * así que la producción ya no depende de esa semántica: [FrozenFrameSources.sealFromAssetDescriptor]
 * abre el descriptor del proveedor y aplica el offset de forma explícita, con una búsqueda absoluta
 * **también cuando el offset declarado es `0`** (un descriptor seekable puede entregarse ya posicionado
 * a mitad del fichero y el rango del AFD es relativo al comienzo). La prueba lo acredita con un
 * `ContentProvider` **real de test** que entrega un AFD sobre un fichero contenedor con:
 *
 * - bytes centinela **antes** del rango (prefijo declarado como `startOffset`),
 * - el fixture de vídeo dentro del rango,
 * - bytes centinela **después** del rango (`length` exacta).
 *
 * ## Qué acredita (y qué no)
 *
 * - **Bytes exactos, no solo metadatos:** el índice (número de frames y PTS exactos), las dimensiones
 *   y la longitud podrían coincidir aunque el snapshot contuviera un prefijo o un sufijo —un MP4 con
 *   `moov` al principio puede parsear desde otro punto—, así que cada prueba compara el contenido
 *   sellado **byte a byte** con el fixture, leído por el descriptor interno de la sesión (el mismo que
 *   usa el productor).
 * - **Offset 0 no significa «descriptor ya al inicio»:** una prueba abre un fichero seekable, desplaza
 *   su descriptor a mitad y declara `startOffset = 0`; el snapshot debe contener el fichero íntegro
 *   desde el comienzo, no el sufijo desde la posición entregada.
 * - **Longitud desconocida en pipe:** el rango se copia hasta EOF con tope y el snapshot es el fixture
 *   byte a byte. Los métodos que usan un pipe están marcados
 *   `@SdkSuppress(minSdkVersion = 30)` porque en API 26–29 la producción **rechaza** el origen no
 *   seekable (`NON_SEEKABLE_UNSUPPORTED`) en vez de emitir una lectura bloqueante sin cancelación
 *   demostrable; ese rechazo lo cubre `NonSeekableSourceApiGateInstrumentedTest`. No es un fallo
 *   oculto: es la política de versión aprobada, y los ficheros seekables de esta suite siguen
 *   ejecutándose en API 26–29.
 * - **Rechazos tipados:** una longitud declarada mayor que todos los bytes disponibles y un offset más
 *   allá del final fallan sin sellar ni retener datos. Declarar exactamente el rango que queda hasta
 *   EOF no es un truncamiento: el rango declarado es el contenido y se sella tal cual (con sus
 *   centinelas de sufijo, si están dentro de él), sin recortarlo ni inventar corrupción.
 * - **Cierre del descriptor:** en API 35 `ParcelFileDescriptor.getFd()` sobre un descriptor cerrado no
 *   devuelve `-1`: lanza `IllegalStateException("Already closed")`. Las pruebas exigen esa excepción
 *   concreta para acreditar el cierre, en lugar de comparar un fd centinela que ya no se obtiene.
 *
 * ## Hueco residual: no hay ida por `ContentResolver`/Binder
 *
 * Registrar un `ContentProvider` de test para que lo consuma el proceso de la app exigiría
 * `android:exported="true"` (uid distinto) con un permiso que este slice no puede añadir sin tocar el
 * manifest principal, o dejar el fixture legible por cualquier app. Por eso el proveedor **no se
 * registra** en `AndroidManifest.xml` ni se exporta: la prueba lo instancia en proceso y llama a su
 * `openAssetFile` como método normal. El AFD, el offset, la longitud, el pipe y el cierre son objetos y
 * código de plataforma reales, y el código ejercitado es el de producción
 * ([FrozenFrameSources.sealFromAssetDescriptor]), pero **no** se ejercita la resolución de la URI, el
 * paso por Binder ni la comprobación de grants del `ContentResolver`: eso queda como hueco residual.
 *
 * ## Ejecución (requiere AVD; este slice no ejecuta dispositivo)
 *
 * ```
 * adb shell am instrument -w -r \
 *   -e class com.openjump.app.video.angles.AssetDescriptorOffsetSourceInstrumentedTest \
 *   com.openjump.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
/** URI opaca del proveedor de test: no se registra en ningún manifest (ver KDoc de la prueba). */
private const val TEST_PROVIDER_URI = "content://com.openjump.app.test.angles-offset/source"

/**
 * `ContentProvider` **real de test** que entrega un [AssetFileDescriptor] con `startOffset` y
 * `length` declarados, sobre un fichero contenedor o sobre el extremo de lectura de un pipe.
 *
 * No se registra en `AndroidManifest.xml` ni se exporta: la prueba lo instancia en proceso y llama a
 * [openAssetFile] directamente. Así el AFD, el offset, la longitud y el cierre son código de plataforma
 * auténtico; lo que no se ejercita es la resolución por `ContentResolver` ni el Binder hacia otro
 * proceso, que no intervienen en el contrato del offset (hueco residual documentado en la prueba).
 */
class TestOffsetAssetProvider : ContentProvider() {

    /** Apertura del descriptor subyacente (fichero regular o extremo de lectura de un pipe). */
    var openDescriptor: (() -> ParcelFileDescriptor)? = null

    var declaredStartOffset: Long = 0L
    var declaredLength: Long = AssetFileDescriptor.UNKNOWN_LENGTH

    /** Nº de AFD entregados: la prueba comprueba que se usó el proveedor, no un atajo. */
    var openedAssetDescriptors: Int = 0
        private set

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        require(mode == "r") { "Este proveedor de test solo sirve lectura." }
        val descriptor = openDescriptor?.invoke() ?: return null
        openedAssetDescriptors += 1
        return AssetFileDescriptor(descriptor, declaredStartOffset, declaredLength)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = "video/mp4"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

@RunWith(AndroidJUnit4::class)
class AssetDescriptorOffsetSourceInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext: Context = instrumentation.context
    private val targetContext: Context = instrumentation.targetContext
    private val fixtureName = "angles/ordinal_cfr_bframes.mp4"

    /** Ficheros propios creados por la prueba; se borran en [cleanUp]. */
    private val ownFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        ownFiles.forEach { it.delete() }
        ownFiles.clear()
    }

    private fun fixtureBytes(): ByteArray = testContext.assets.open(fixtureName).use { it.readBytes() }

    private fun ownFile(prefix: String, bytes: ByteArray): File {
        val directory = File(targetContext.cacheDir, "angles_test_source").apply { mkdirs() }
        val file = File.createTempFile(prefix, ".bin", directory)
        FileOutputStream(file).use { it.write(bytes) }
        ownFiles.add(file)
        return file
    }

    /**
     * Fichero contenedor: [sentinelPrefixBytes] de `0x5A`, el fixture completo y [sentinelSuffixBytes]
     * de `0xA5`. Devuelve el fichero, el offset declarado y la longitud exacta del fixture.
     */
    private fun wrapperFile(sentinelPrefixBytes: Int, sentinelSuffixBytes: Int): Triple<File, Long, Long> {
        val fixture = fixtureBytes()
        val wrapper = ByteArray(sentinelPrefixBytes + fixture.size + sentinelSuffixBytes)
        for (index in 0 until sentinelPrefixBytes) wrapper[index] = 0x5A
        fixture.copyInto(wrapper, sentinelPrefixBytes)
        for (index in 0 until sentinelSuffixBytes) wrapper[sentinelPrefixBytes + fixture.size + index] = 0xA5.toByte()
        val file = ownFile("angles_wrapped_", wrapper)
        return Triple(file, sentinelPrefixBytes.toLong(), fixture.size.toLong())
    }

    private fun assetDescriptorFromProvider(
        provider: TestOffsetAssetProvider,
        startOffset: Long,
        length: Long,
    ): AssetFileDescriptor {
        provider.declaredStartOffset = startOffset
        provider.declaredLength = length
        return provider.openAssetFile(Uri.parse(TEST_PROVIDER_URI), "r")
            ?: throw AssertionError("El ContentProvider de test no entregó descriptor")
    }

    private fun fileProvider(file: File): TestOffsetAssetProvider = TestOffsetAssetProvider().apply {
        openDescriptor = { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    }

    private fun openedSession(open: FrozenFrameSourceOpen): FrozenFrameSourceSession =
        (open as? FrozenFrameSourceOpen.Opened)?.session
            ?: throw AssertionError("Se esperaba una sesión sellada y fue $open")

    /** Índice de referencia: el fixture puro abierto como fichero, sin contenedor ni offset. */
    private fun referenceIndex(): VideoFrameIndexSnapshot {
        val copy = ownFile("angles_reference_", fixtureBytes())
        val session = openedSession(FrozenFrameSources.openFromFile(targetContext, copy))
        return try {
            session.index
        } finally {
            session.close()
        }
    }

    private fun assertSameIndex(expected: VideoFrameIndexSnapshot, actual: VideoFrameIndexSnapshot) {
        assertEquals("Número de frames distinto: el snapshot no contiene el rango declarado", expected.frameCount, actual.frameCount)
        assertArrayEquals("Los PTS del índice no son los del fixture", expected.frameTimesUs, actual.frameTimesUs)
        assertEquals(expected.durationUs, actual.durationUs)
        assertEquals(expected.encodedWidth, actual.encodedWidth)
        assertEquals(expected.encodedHeight, actual.encodedHeight)
        assertEquals(expected.declaredRotationDegrees, actual.declaredRotationDegrees)
    }

    private fun snapshotFileCount(): Int =
        File(targetContext.cacheDir, FrozenFrameSources.SNAPSHOT_DIRECTORY).listFiles()?.size ?: 0

    /**
     * Bytes del snapshot privado que la sesión acaba de sellar, leídos por el descriptor interno de la
     * sesión (el mismo que abrirá el productor), no por la ruta. Es lo que permite comparar contenido
     * exacto en lugar de confiar en que índice y longitud coincidan.
     */
    private fun sealedSnapshotBytes(session: FrozenFrameSourceSession): ByteArray {
        val opened = session.openSnapshotDescriptor()
        val descriptor = (opened as? SnapshotDescriptorOpen.Opened)?.descriptor
            ?: throw AssertionError("No se pudo abrir el descriptor del snapshot sellado: $opened")
        return descriptor.use { parcel ->
            FileInputStream(parcel.fileDescriptor).use { it.readBytes() }
        }
    }

    /**
     * Acredita que [descriptor] quedó cerrado. En API 35 un `ParcelFileDescriptor` cerrado no devuelve
     * `-1`: `getFd()` lanza `IllegalStateException("Already closed")`
     * (`ParcelFileDescriptor.java:794`), así que la prueba exige esa excepción concreta. Un descriptor
     * todavía abierto devuelve un fd válido y hace fallar la aserción.
     */
    private fun assertDescriptorClosed(message: String, descriptor: ParcelFileDescriptor) {
        assertThrows(message, IllegalStateException::class.java) { descriptor.fd }
    }

    /**
     * Exige un fallo tipado con [expectedFailure]. Si el sellado se hubiera abierto por error, cierra
     * la sesión inesperada antes de fallar: un test rojo no debe dejar un snapshot privado retenido en
     * la caché del proceso instrumentado.
     */
    private fun assertTypedFailure(
        open: FrozenFrameSourceOpen,
        expectedFailure: SourceOpenFailure,
    ): FrozenFrameSourceOpen.Unavailable {
        val unavailable = open as? FrozenFrameSourceOpen.Unavailable
        if (unavailable == null) {
            (open as? FrozenFrameSourceOpen.Opened)?.session?.close()
            throw AssertionError("Se esperaba fallo tipado ($expectedFailure) y fue $open")
        }
        assertEquals(expectedFailure, unavailable.failure)
        assertTrue(unavailable.detail.isNotBlank())
        return unavailable
    }

    // ------------------------------------------------------------------ offset exacto

    @Test
    fun exactStartOffsetAndLengthDeliverTheFixtureWithoutSentinels() {
        val fixture = fixtureBytes()
        val (wrapper, startOffset, length) = wrapperFile(sentinelPrefixBytes = 32, sentinelSuffixBytes = 48)
        val provider = fileProvider(wrapper)
        val descriptor = assetDescriptorFromProvider(provider, startOffset, length)
        val reference = referenceIndex()

        val session = openedSession(
            FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor),
        )
        try {
            assertEquals("Debe sellarse exactamente el rango declarado", length, session.snapshotLengthBytes)
            // Igualdad byte a byte: índice, dimensiones y longitud podrían coincidir aunque el snapshot
            // contuviera el prefijo centinela (un MP4 con `moov` al inicio puede parsear desde otro
            // punto), así que la prueba compara el contenido completo con el fixture.
            assertArrayEquals(
                "El snapshot sellado debe ser el fixture byte a byte, sin centinelas de prefijo ni sufijo",
                fixture,
                sealedSnapshotBytes(session),
            )
            assertSameIndex(reference, session.index)
            assertTrue("La sesión debe quedar abierta tras sellar", session.isOpen)
            assertDescriptorClosed(
                "El descriptor del proveedor debe cerrarse al terminar (aunque la sesión siga abierta)",
                descriptor.parcelFileDescriptor,
            )
            assertEquals("La prueba debe pasar por el ContentProvider, no por un atajo", 1, provider.openedAssetDescriptors)
        } finally {
            session.close()
        }
        // Cierre idempotente: no lanza ni deja la sesión abierta.
        session.close()
        assertTrue(!session.isOpen)
    }

    // ------------------------------------------------------------------ descriptor ya posicionado

    @Test
    fun aSeekableDescriptorDeliveredMidFileWithZeroOffsetIsRepositionedAtTheStart() {
        val fixture = fixtureBytes()
        // Rango declarado [0, length) sobre un fichero que solo contiene el fixture (sin centinelas):
        // el proveedor entrega el descriptor ya desplazado a mitad. Leer desde la posición actual
        // sellaría un sufijo de mitad de fichero y fallaría la longitud declarada, en lugar de entregar
        // el vídeo; el offset 0 del AFD es relativo al comienzo del fichero, no a esa posición.
        val plain = ownFile("angles_midposition_", fixture)
        val positioned = ParcelFileDescriptor.open(plain, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val mid = fixture.size / 2L
            assertEquals(
                "El descriptor de partida debe quedar a mitad del fichero",
                mid,
                Os.lseek(positioned.fileDescriptor, mid, OsConstants.SEEK_SET),
            )
            val provider = TestOffsetAssetProvider().apply { openDescriptor = { positioned } }
            val descriptor = assetDescriptorFromProvider(provider, startOffset = 0L, length = fixture.size.toLong())
            val reference = referenceIndex()

            val session = openedSession(FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor))
            try {
                assertEquals("Debe sellarse el fichero completo", fixture.size.toLong(), session.snapshotLengthBytes)
                assertArrayEquals(
                    "El snapshot debe ser el fixture íntegro byte a byte; la posición heredada del descriptor no es el rango del AFD",
                    fixture,
                    sealedSnapshotBytes(session),
                )
                assertSameIndex(reference, session.index)
                assertEquals("La prueba debe pasar por el ContentProvider, no por un atajo", 1, provider.openedAssetDescriptors)
            } finally {
                session.close()
            }
        } finally {
            runCatching { positioned.close() }
        }
    }

    // ------------------------------------------------------------------ longitud desconocida

    /**
     * Pipe sin longitud: sondeo no bloqueante. En API 26–29 la producción rechaza el origen no
     * seekable ([SourceNonSeekableUnsupported], `NON_SEEKABLE_UNSUPPORTED`) antes de tocar el
     * descriptor, así que este comportamiento solo se ejerce en API 30+ (ver
     * `NonSeekableSourceApiGateInstrumentedTest` para la rama rechazada).
     */
    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun anUnknownLengthPipeIsCopiedUntilEofWithTheRealLength() {
        val fixture = fixtureBytes()
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = Thread {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(fixture) }
            } finally {
                runCatching { pipe[1].close() }
            }
        }.apply { isDaemon = true }
        writer.start()
        val provider = TestOffsetAssetProvider().apply { openDescriptor = { pipe[0] } }
        val descriptor = assetDescriptorFromProvider(
            provider,
            startOffset = 0L,
            length = AssetFileDescriptor.UNKNOWN_LENGTH,
        )
        val reference = referenceIndex()

        val session = openedSession(FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor))
        try {
            writer.join(10_000L)
            assertEquals("Un pipe sin longitud se copia hasta EOF", fixture.size.toLong(), session.snapshotLengthBytes)
            assertArrayEquals(
                "El snapshot de un pipe sin longitud debe ser el fixture byte a byte",
                fixture,
                sealedSnapshotBytes(session),
            )
            assertSameIndex(reference, session.index)
        } finally {
            session.close()
        }
    }

    // ------------------------------------------------------------------ rechazos tipados

    @Test
    fun aDeclaredLengthLongerThanTheContentFailsTypedWithoutRetainingData() {
        val fixture = fixtureBytes()
        // El contenedor tiene 16 centinelas de prefijo + fixture + 16 de sufijo, así que desde el
        // offset declarado solo quedan `fixture + 16` bytes. Declarar MÁS que todos los bytes
        // disponibles hace que el flujo llegue a EOF sin reunir la longitud declarada: es un
        // truncamiento y no se puede sellar. (Declarar exactamente `fixture + 16` no es este caso:
        // el rango declarado cubriría todos los bytes restantes; ver la prueba siguiente.)
        val (wrapper, startOffset, length) = wrapperFile(sentinelPrefixBytes = 16, sentinelSuffixBytes = 16)
        val availableAfterOffset = wrapper.length() - startOffset
        assertEquals(
            "El contenedor debe ofrecer exactamente el fixture más el sufijo dentro del rango",
            length + 16L,
            availableAfterOffset,
        )
        val declaredLength = availableAfterOffset + 64L
        val before = snapshotFileCount()
        val beforeBytes = wrapper.readBytes()
        val descriptor = assetDescriptorFromProvider(fileProvider(wrapper), startOffset, declaredLength)
        val open = FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor)
        // Si el rango se sellara por error, el helper cierra la sesión inesperada antes de fallar (la
        // evidencia preexistente del AVD no se toca).
        assertTypedFailure(open, SourceOpenFailure.COPY_FAILED)
        assertArrayEquals("La fuente no se modifica al fallar", beforeBytes, wrapper.readBytes())
        assertEquals("Un fallo no deja snapshots ni temporales propios", before, snapshotFileCount())
        assertEquals("El fixture debe seguir intacto", fixture.size, fixtureBytes().size)
        assertDescriptorClosed(
            "El descriptor del proveedor también se cierra cuando el sellado falla",
            descriptor.parcelFileDescriptor,
        )
    }

    @Test
    fun aDeclaredRangeThatReachesEofSealsExactlyTheDeclaredBytes() {
        val fixture = fixtureBytes()
        // Caso complementario del anterior: si el proveedor declara el rango completo que queda hasta
        // EOF (fixture + los 16 centinelas de sufijo), no hay truncamiento que castigar. El rango
        // declarado es el contenido: el snapshot debe ser exactamente esos bytes, sufijo incluido, sin
        // recortarlo ni inventar corrupción.
        val (wrapper, startOffset, length) = wrapperFile(sentinelPrefixBytes = 16, sentinelSuffixBytes = 16)
        val declaredLength = length + 16L
        val expected = wrapper.readBytes().copyOfRange(
            startOffset.toInt(),
            (startOffset + declaredLength).toInt(),
        )
        val provider = fileProvider(wrapper)
        val descriptor = assetDescriptorFromProvider(provider, startOffset, declaredLength)

        val session = openedSession(FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor))
        try {
            assertEquals("Debe sellarse exactamente la longitud declarada", declaredLength, session.snapshotLengthBytes)
            assertArrayEquals(
                "El snapshot debe contener exactamente los bytes declarados, sufijo dentro del rango incluido",
                expected,
                sealedSnapshotBytes(session),
            )
            assertEquals("La prueba debe pasar por el ContentProvider, no por un atajo", 1, provider.openedAssetDescriptors)
            assertDescriptorClosed(
                "El descriptor del proveedor debe cerrarse al terminar (aunque la sesión siga abierta)",
                descriptor.parcelFileDescriptor,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun aStartOffsetBeyondTheEndFailsTypedAndDoesNotInventContent() {
        val (wrapper, _, length) = wrapperFile(sentinelPrefixBytes = 8, sentinelSuffixBytes = 8)
        val beyondEof = wrapper.length() + 64L
        val open = FrozenFrameSources.sealFromAssetDescriptor(
            targetContext,
            assetDescriptorFromProvider(fileProvider(wrapper), beyondEof, length),
        )
        assertTypedFailure(open, SourceOpenFailure.COPY_FAILED)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun aPipeWithDeclaredOffsetDiscardsThePrefixAndDeliversTheRangeBytes() {
        // Descriptor sin búsqueda: el prefijo declarado no se puede saltar, se descarta leyendo y nunca
        // llega al snapshot. El AFD de un pipe con offset exige longitud declarada (UNKNOWN_LENGTH solo
        // admite offset 0), así que aquí se declara la longitud exacta del fixture.
        val fixture = fixtureBytes()
        val prefix = 32
        val payload = ByteArray(prefix + fixture.size)
        for (index in 0 until prefix) payload[index] = 0x5A
        fixture.copyInto(payload, prefix)
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = Thread {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(payload) }
            } finally {
                runCatching { pipe[1].close() }
            }
        }.apply { isDaemon = true }
        writer.start()
        val provider = TestOffsetAssetProvider().apply { openDescriptor = { pipe[0] } }
        val descriptor = assetDescriptorFromProvider(
            provider,
            startOffset = prefix.toLong(),
            length = fixture.size.toLong(),
        )
        val reference = referenceIndex()

        val session = openedSession(FrozenFrameSources.sealFromAssetDescriptor(targetContext, descriptor))
        try {
            writer.join(10_000L)
            assertEquals(fixture.size.toLong(), session.snapshotLengthBytes)
            assertArrayEquals(
                "El prefijo descartado del pipe no puede acabar en el snapshot",
                fixture,
                sealedSnapshotBytes(session),
            )
            assertSameIndex(reference, session.index)
        } finally {
            session.close()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun aCancellationDuringTheOffsetDiscardIsTypedAsCancelled() {
        val pipe = ParcelFileDescriptor.createPipe()
        // Extremo de escritura cerrado: si la cancelación no se comprobara antes de leer, el descarte
        // del offset vería EOF. La comprobación de cancelación va primero, así que no se bloquea.
        runCatching { pipe[1].close() }
        val provider = TestOffsetAssetProvider().apply { openDescriptor = { pipe[0] } }
        val descriptor = assetDescriptorFromProvider(provider, startOffset = 32L, length = 1_024L)

        val open = FrozenFrameSources.sealFromAssetDescriptor(
            targetContext,
            descriptor,
            cancellationCheck = { true },
        )
        assertTypedFailure(open, SourceOpenFailure.CANCELLED)
        assertDescriptorClosed(
            "El descriptor del pipe debe quedar cerrado tras la cancelación",
            descriptor.parcelFileDescriptor,
        )
    }
}
