package com.openjump.app.data.backup

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato **puro** del probe de PNG (G3c) en JVM: presupuesto, dimensiones y orden de rechazo
 * antes de decodificar.
 *
 * Lo que se demuestra aqui, sin `android.graphics`:
 *  - el presupuesto por defecto es exactamente el de [com.openjump.app.video.VideoFrameSafety] y
 *    solo se puede acotar mas;
 *  - [MediaBackupPngSafety.checkDimensions] rechaza 0, negativos, `Int.MAX_VALUE`, 32 bits sin
 *    signo, area y coste por encima del limite, y acepta el limite exacto **sin multiplicar valores
 *    sin acotar** (no hay overflow ni excepcion);
 *  - [MediaBackupPngProbe.probe] valida MIME, tamano declarado, dimensiones esperadas y cancelacion
 *    **antes** de abrir el primer flujo o pedir un solo decode.
 *
 * Nada de esto toca ficheros, Room, SAF ni un dispositivo: los flujos son contadores y el
 * decodificador es un fake que falla si se le llama.
 */
class MediaBackupPngProbeSafetyTest {

    // --- Presupuesto --------------------------------------------------------------------------

    @Test
    fun defaultBudgetIsExactlyTheVideoFrameSafetyEnvelope() {
        val budget = MediaBackupPngBudget()
        assertEquals(16_384, budget.maxDimension)
        assertEquals(12_000_000L, budget.maxPixelArea)
        assertEquals(64L * 1024L * 1024L, budget.maxDecodedBytes)
        assertEquals(12L * 1024L * 1024L, budget.maxEncodedBytes)
    }

    @Test
    fun budgetCanOnlyNarrowTheVideoFrameSafetyEnvelope() {
        // Un presupuesto mas permisivo que VideoFrameSafety no se puede ni construir.
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupPngBudget(maxDimension = 16_385)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupPngBudget(maxPixelArea = 12_000_001L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupPngBudget(maxDecodedBytes = 64L * 1024L * 1024L + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupPngBudget(maxEncodedBytes = 12L * 1024L * 1024L + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupPngBudget(maxDimension = 0) }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupPngBudget(maxPixelArea = 0L) }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupPngBudget(maxDecodedBytes = 0L) }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupPngBudget(maxEncodedBytes = 0L) }
    }

    // --- Dimensiones declaradas (helper puro) --------------------------------------------------

    @Test
    fun exactDimensionAreaAndCostLimitsAreAccepted() {
        val budget = MediaBackupPngBudget()
        val maxDimension = check(16_384L, 1L, budget)
        assertEquals(16_384, maxDimension.width)
        assertEquals(1, maxDimension.height)
        assertEquals(16_384L, maxDimension.area)
        assertEquals(65_536L, maxDimension.decodedBytes)

        val exactArea = check(4_000L, 3_000L, budget)
        assertEquals(12_000_000L, exactArea.area)
        assertEquals(48_000_000L, exactArea.decodedBytes)

        val narrowCost = check(1_000L, 100L, MediaBackupPngBudget(maxDecodedBytes = 400_000L))
        assertEquals(400_000L, narrowCost.decodedBytes)
    }

    @Test
    fun zeroAndNegativeDimensionsAreRejectedWithoutMultiplying() {
        val budget = MediaBackupPngBudget()
        reject(0L, 1L, budget, MediaBackupPngDimensionRejection.NON_POSITIVE)
        reject(1L, 0L, budget, MediaBackupPngDimensionRejection.NON_POSITIVE)
        reject(0L, 0L, budget, MediaBackupPngDimensionRejection.NON_POSITIVE)
        reject(-1L, 1L, budget, MediaBackupPngDimensionRejection.NON_POSITIVE)
        reject(1L, -9_999L, budget, MediaBackupPngDimensionRejection.NON_POSITIVE)
        reject(-1L, -1L, budget, MediaBackupPngDimensionRejection.NON_POSITIVE)
    }

    @Test
    fun intMaxAndUnsigned32BitDimensionsAreRejectedBeforeAnyMultiplication() {
        val budget = MediaBackupPngBudget()
        reject(Int.MAX_VALUE.toLong(), 1L, budget, MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE)
        reject(1L, Int.MAX_VALUE.toLong(), budget, MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE)
        // Dos valores que desbordarian Long si se multiplicasen sin acotar antes (2^32-1 al cuadrado).
        reject(0xFFFF_FFFFL, 0xFFFF_FFFFL, budget, MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE)
        reject(0x8000_0000L, 1L, budget, MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE)
        reject(16_385L, 1L, budget, MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE)
        reject(1L, 16_385L, budget, MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE)
    }

    @Test
    fun areaOneAboveTheLimitIsRejected() {
        val budget = MediaBackupPngBudget()
        reject(3_999L, 3_001L, budget, MediaBackupPngDimensionRejection.AREA_TOO_LARGE) // 12_000_999
        reject(1L, 12_000_001L, budget, MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE)
        reject(12_000_001L, 1L, budget, MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE)
    }

    @Test
    fun decodedCostOneAboveANarrowBudgetIsRejected() {
        val budget = MediaBackupPngBudget(maxDecodedBytes = 400_000L)
        reject(1_001L, 100L, budget, MediaBackupPngDimensionRejection.DECODED_BYTES_TOO_LARGE) // 400_400
        reject(4_000L, 3_000L, MediaBackupPngBudget(maxDecodedBytes = 47_999_999L),
            MediaBackupPngDimensionRejection.DECODED_BYTES_TOO_LARGE)
    }

    // --- Expectativa --------------------------------------------------------------------------

    @Test
    fun expectationRequiresAWellFormedSeal() {
        assertThrows(IllegalArgumentException::class.java) { expectation(sha256 = "abc") }
        assertThrows(IllegalArgumentException::class.java) { expectation(sha256 = "z".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { expectation(byteCount = 0L) }
        assertThrows(IllegalArgumentException::class.java) { expectation(imageKey = "") }
        // Mayusculas son un hexadecimal valido: el probe compara sin distinguir caja.
        assertEquals(SHA256_A.uppercase(), expectation(sha256 = SHA256_A.uppercase()).sha256)
    }

    @Test
    fun frameExpectationUsesTheManifestDeclaredGeometryAndSeal() {
        val frame = frame(imageKey = KEY, imageWidth = 24, imageHeight = 16)
        val mapped = frame.pngExpectation()
        assertEquals(KEY, mapped.imageKey)
        assertEquals(24, mapped.width)
        assertEquals(16, mapped.height)
        assertEquals(frame.imageByteCount, mapped.byteCount)
        assertEquals(frame.imageSha256, mapped.sha256)
        assertEquals(MEDIA_BACKUP_IMAGE_MIME_TYPE, mapped.mimeType)
    }

    // --- Orden de rechazo del probe (nada de IO ni decode) -------------------------------------

    @Test
    fun nonPngDeclaredMimeIsRejectedBeforeOpeningTheStagedFile() {
        val fake = RecordingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), fake)
        var opens = 0
        val failure = assertThrows(MediaBackupPngProbeException.MimeRejected::class.java) {
            probe.probe(expectation(mimeType = "image/jpeg"), { opens++; fakePngStream() })
        }
        assertTrue(failure.message.orEmpty().contains("image/jpeg"))
        assertEquals(0, opens)
        assertEquals(0, fake.boundsCalls)
        assertEquals(0, fake.pixelCalls)
    }

    @Test
    fun declaredSizeAboveTheEncodedBudgetIsRejectedBeforeOpeningTheStagedFile() {
        val fake = RecordingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(maxEncodedBytes = 10L), fake)
        var opens = 0
        assertThrows(MediaBackupPngProbeException.BudgetExceeded::class.java) {
            probe.probe(expectation(byteCount = 11L), { opens++; fakePngStream() })
        }
        assertEquals(0, opens)
        assertEquals(0, fake.boundsCalls)
        assertEquals(0, fake.pixelCalls)
    }

    @Test
    fun impossibleExpectedDimensionsAreRejectedBeforeOpeningTheStagedFile() {
        val fake = RecordingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), fake)
        var opens = 0
        val zero = assertThrows(MediaBackupPngProbeException.DimensionsRejected::class.java) {
            probe.probe(expectation(width = 0), { opens++; fakePngStream() })
        }
        assertEquals(MediaBackupPngDimensionRejection.NON_POSITIVE, zero.reason)
        val huge = assertThrows(MediaBackupPngProbeException.DimensionsRejected::class.java) {
            probe.probe(expectation(width = Int.MAX_VALUE), { opens++; fakePngStream() })
        }
        assertEquals(MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE, huge.reason)
        assertEquals(0, opens)
        assertEquals(0, fake.boundsCalls)
        assertEquals(0, fake.pixelCalls)
    }

    @Test
    fun expectedDimensionsOutsideANarrowCostBudgetAreRejectedBeforeOpeningTheStagedFile() {
        val fake = RecordingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(maxDecodedBytes = 400_000L), fake)
        var opens = 0
        val failure = assertThrows(MediaBackupPngProbeException.DimensionsRejected::class.java) {
            probe.probe(expectation(width = 1_001, height = 100), { opens++; fakePngStream() })
        }
        assertEquals(MediaBackupPngDimensionRejection.DECODED_BYTES_TOO_LARGE, failure.reason)
        assertEquals(0, opens)
        assertEquals(0, fake.boundsCalls)
        assertEquals(0, fake.pixelCalls)
    }

    @Test
    fun cancellationIsReportedBeforeOpeningTheStagedFile() {
        val fake = RecordingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), fake)
        var opens = 0
        assertThrows(MediaBackupPngProbeException.Canceled::class.java) {
            probe.probe(
                expectation(),
                { opens++; fakePngStream() },
                MediaBackupArchiveCancellation { true },
            )
        }
        assertEquals(0, opens)
        assertEquals(0, fake.boundsCalls)
        assertEquals(0, fake.pixelCalls)
    }

    // --- Utilidades ---------------------------------------------------------------------------

    private fun check(width: Long, height: Long, budget: MediaBackupPngBudget): MediaBackupPngDimensions {
        val result = MediaBackupPngSafety.checkDimensions(width, height, budget)
        assertTrue("expected Accepted for ${width}x$height but got $result", result is MediaBackupPngDimensionCheck.Accepted)
        return (result as MediaBackupPngDimensionCheck.Accepted).dimensions
    }

    private fun reject(
        width: Long,
        height: Long,
        budget: MediaBackupPngBudget,
        reason: MediaBackupPngDimensionRejection,
    ) {
        val result = MediaBackupPngSafety.checkDimensions(width, height, budget)
        assertTrue("expected Rejected for ${width}x$height but got $result", result is MediaBackupPngDimensionCheck.Rejected)
        val rejected = result as MediaBackupPngDimensionCheck.Rejected
        assertEquals("unexpected reason for ${width}x$height", reason, rejected.reason)
        assertEquals(width, rejected.width)
        assertEquals(height, rejected.height)
    }

    private fun expectation(
        width: Int = 24,
        height: Int = 16,
        byteCount: Long = 128L,
        sha256: String = SHA256_A,
        mimeType: String = MEDIA_BACKUP_IMAGE_MIME_TYPE,
        imageKey: String = KEY,
    ) = MediaBackupPngExpectation(
        imageKey = imageKey,
        width = width,
        height = height,
        byteCount = byteCount,
        sha256 = sha256,
        mimeType = mimeType,
    )

    private fun frame(
        imageKey: String,
        imageWidth: Int,
        imageHeight: Int,
    ) = MediaBackupFrame(
        id = 101L,
        rootId = 11L,
        ordinal = 0,
        assignedFrameIndex = 10,
        assignedPtsUs = 333_333L,
        encodedWidth = 1080,
        encodedHeight = 1920,
        presentationWidth = 1920,
        presentationHeight = 1080,
        rotationDegrees = 90,
        geometryVersion = 1,
        imageKey = imageKey,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        imageByteCount = 128L,
        imageSha256 = SHA256_A,
        imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
    )

    /** El probe solo rechaza antes de decodificar en estas pruebas: el fake jamas debe ejecutarse. */
    private class RecordingDecoder : MediaBackupPngDecoder {
        var boundsCalls = 0
        var pixelCalls = 0

        override fun decodeBounds(stream: InputStream): MediaBackupPngBounds? {
            boundsCalls++
            throw AssertionError("decodeBounds must not be called for a pre-decode rejection")
        }

        override fun decodePixels(stream: InputStream): android.graphics.Bitmap? {
            pixelCalls++
            throw AssertionError("decodePixels must not be called for a pre-decode rejection")
        }
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef"
        const val SHA256_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun fakePngStream(): InputStream = object : InputStream() {
            override fun read(): Int = -1
        }
    }
}
