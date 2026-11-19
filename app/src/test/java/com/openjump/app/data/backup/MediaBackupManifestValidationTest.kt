package com.openjump.app.data.backup

import com.openjump.app.angles.AngleMarkNameContract
import com.openjump.app.data.backup.MediaBackupManifestCodecTest.Companion.MEDIA_KEY_A
import com.openjump.app.data.backup.MediaBackupManifestCodecTest.Companion.MEDIA_KEY_B
import com.openjump.app.data.backup.MediaBackupManifestCodecTest.Companion.MEDIA_KEY_C
import com.openjump.app.data.backup.MediaBackupManifestCodecTest.Companion.frame
import com.openjump.app.data.backup.MediaBackupManifestCodecTest.Companion.image
import com.openjump.app.data.backup.MediaBackupManifestCodecTest.Companion.mark
import com.openjump.app.data.backup.MediaBackupManifestCodecTest.Companion.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validacion previa del manifiesto: lo que nunca debe llegar a escribirse ni aceptarse. */
class MediaBackupManifestValidationTest {

    @Test
    fun validateAcceptsTheSampleAndEmptyV2IsAllowed() {
        sample().validate()
        val emptyMedia = sample(roots = emptyList(), frames = emptyList(), marks = emptyList(), images = emptyList())
        emptyMedia.validate()
        assertEquals(0, emptyMedia.archivePlan().imageCount)
    }

    @Test
    fun legacyDocumentMustStayMediaFree() {
        val manifest = sample()
        val media = manifest.legacy.copy(mediaIncluded = true)
        val error = assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(legacy = media).validate()
        }
        assertTrue(error.message.orEmpty().contains("Media or preferences"))
        // El codec tambien lo rechaza antes de escribir bytes.
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.encode(manifest.copy(legacy = media))
        }
    }

    @Test
    fun legacyDocumentKeepsItsOwnContract() {
        val manifest = sample()
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(legacy = manifest.legacy.copy(formatVersion = 2)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(legacy = manifest.legacy.copy(sourceRoomSchemaVersion = 8)).validate()
        }
    }

    @Test
    fun rootFormatAndGeometryVersionsAreRecognisedOnly() {
        val manifest = sample()
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = manifest.angleV2.copy(roots = manifest.angleV2.roots.map { it.copy(analysisFormat = "ANGLE_MULTI_FRAME_V2") })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(
                angleV2 = manifest.angleV2.copy(frames = manifest.angleV2.frames.map { it.copy(geometryVersion = 0) }),
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(
                angleV2 = manifest.angleV2.copy(frames = manifest.angleV2.frames.map { it.copy(rotationDegrees = 45) }),
            ).validate()
        }
    }

    @Test
    fun idsAndOpaqueKeysAreUniqueAndPositive() {
        val manifest = sample()
        val v2 = manifest.angleV2
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(roots = listOf(v2.roots[0], v2.roots[1].copy(id = 1L)))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(frames = v2.frames.map { it.copy(id = 0L) })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(marks = v2.marks.map { it.copy(id = 0L) })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(roots = v2.roots.map { it.copy(sessionKey = "same") })).validate()
        }
        // Dos fotogramas no pueden compartir imagen...
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(frames = v2.frames.map { it.copy(imageKey = MEDIA_KEY_A) })).validate()
        }
        // ...ni declararse dos veces la misma entrada.
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(images = v2.images + image(MEDIA_KEY_A))).validate()
        }
        // Clave opaca obligatoria: ni rutas, ni mayusculas, ni texto libre.
        listOf("/sdcard/pic.png", "images/$MEDIA_KEY_A.png", MEDIA_KEY_A.uppercase(), "0123456789abcdef").forEach { bad ->
            assertThrows("imageKey '$bad' must be rejected", IllegalArgumentException::class.java) {
                manifest.copy(
                    angleV2 = v2.copy(
                        frames = v2.frames.map { it.copy(imageKey = bad) },
                        images = v2.images.map { it.copy(imageKey = bad) },
                    ),
                ).validate()
            }
        }
    }

    @Test
    fun relationsAreNeverOrphan() {
        val manifest = sample()
        val v2 = manifest.angleV2
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(frames = v2.frames.map { it.copy(rootId = 99L) })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(marks = v2.marks.map { it.copy(frameId = 99L) })).validate()
        }
        // Imagen declarada sin fotograma que la use.
        val extraImage = manifest.copy(angleV2 = v2.copy(images = v2.images.filter { it.imageKey != MEDIA_KEY_C }))
        assertThrows(IllegalArgumentException::class.java) { extraImage.validate() }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(images = emptyList())).validate()
        }
    }

    @Test
    fun ownerMustExistInLegacyAthletesAndNullIsPreserved() {
        val manifest = sample()
        val v2 = manifest.angleV2
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(roots = v2.roots.map { it.copy(athleteId = 999L) })).validate()
        }
        // `null` es un estado real: se conserva en vez de inventar un atleta.
        val ownerless = manifest.copy(angleV2 = v2.copy(roots = v2.roots.map { it.copy(athleteId = null) }))
        ownerless.validate()
        assertEquals(listOf(null, null), MediaBackupManifestCodec.decode(MediaBackupManifestCodec.encode(ownerless)).angleV2.roots.map { it.athleteId })
    }

    @Test
    fun framesAndAnglesAreMandatoryWithConsecutiveOrdinals() {
        val manifest = sample()
        val v2 = manifest.angleV2
        assertThrows(IllegalArgumentException::class.java) {
            // Raiz 1 sin fotogramas.
            manifest.copy(angleV2 = v2.copy(frames = v2.frames.filter { it.rootId == 2L }, marks = v2.marks.filter { it.frameId != 11L }, images = v2.images.filter { it.imageKey != MEDIA_KEY_A })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            // Hueco de ordinales dentro de la raiz 2 (0 y 2, sin 1).
            manifest.copy(angleV2 = v2.copy(frames = v2.frames.map { if (it.id == 13L) it.copy(ordinal = 2) else it })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            // Fotograma sin marcas.
            manifest.copy(angleV2 = v2.copy(marks = v2.marks.filter { it.frameId != 13L })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            // Hueco de ordinales de marcas en el fotograma 11 (0 y 2).
            manifest.copy(angleV2 = v2.copy(marks = v2.marks.map { if (it.id == 102L) it.copy(ordinal = 2) else it })).validate()
        }
    }

    @Test
    fun markNamesAreBoundedByTheSharedContract() {
        val manifest = sample()
        val v2 = manifest.angleV2
        val longest = manifest.copy(angleV2 = v2.copy(marks = v2.marks.map { it.copy(name = "n".repeat(AngleMarkNameContract.MAX_CODE_UNITS)) }))
        longest.validate()
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(marks = v2.marks.map { it.copy(name = "n".repeat(AngleMarkNameContract.MAX_CODE_UNITS + 1)) })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(marks = v2.marks.map { it.copy(name = " ") })).validate()
        }
    }

    @Test
    fun markPointsMustBeFiniteAndInsideThePresentationFrame() {
        val manifest = sample()
        val v2 = manifest.angleV2
        fun withPoint(markId: Long, update: MediaBackupMark.() -> MediaBackupMark) = manifest.copy(
            angleV2 = v2.copy(marks = v2.marks.map { if (it.id == markId) it.update() else it }),
        )
        assertThrows(IllegalArgumentException::class.java) { withPoint(101L) { copy(pointAX = Double.NaN) }.validate() }
        assertThrows(IllegalArgumentException::class.java) { withPoint(101L) { copy(pointAY = Double.POSITIVE_INFINITY) }.validate() }
        assertThrows(IllegalArgumentException::class.java) { withPoint(101L) { copy(pointCX = -0.5) }.validate() }
        assertThrows(IllegalArgumentException::class.java) { withPoint(101L) { copy(pointCY = 480.0) }.validate() }
        // El ultimo pixel es width-1/height-1 y si es valido.
        withPoint(101L) { copy(pointCX = 639.0, pointCY = 479.0) }.validate()
        // A == B no tiene lado; A == C es un 0 grados legitimo.
        assertThrows(IllegalArgumentException::class.java) {
            withPoint(101L) { copy(pointBX = pointAX, pointBY = pointAY) }.validate()
        }
        withPoint(101L) { copy(pointCX = pointAX, pointCY = pointAY) }.validate()
    }

    @Test
    fun frameDimensionsMustBePositiveAndMatchTheRotation() {
        val manifest = sample()
        val v2 = manifest.angleV2
        fun withFrame(frameId: Long, update: MediaBackupFrame.() -> MediaBackupFrame) = manifest.copy(
            angleV2 = v2.copy(frames = v2.frames.map { if (it.id == frameId) it.update() else it }),
        )
        assertThrows(IllegalArgumentException::class.java) { withFrame(11L) { copy(presentationWidth = 0) }.validate() }
        assertThrows(IllegalArgumentException::class.java) { withFrame(11L) { copy(encodedHeight = 0) }.validate() }
        assertThrows(IllegalArgumentException::class.java) {
            withFrame(11L) { copy(rotationDegrees = 90) }.validate()
        }
        // 90 grados exige ejes intercambiados; con ellos es valido.
        withFrame(11L) {
            copy(rotationDegrees = 90, presentationWidth = encodedHeight, presentationHeight = encodedWidth)
        }.validate()
    }

    @Test
    fun assignedAndReportedTimesStaySeparate() {
        val manifest = sample()
        val v2 = manifest.angleV2
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(frames = v2.frames.map { it.copy(assignedPtsUs = -1L) })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(frames = v2.frames.map { it.copy(reportedPtsUs = -1L) })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(frames = v2.frames.map { it.copy(reportedFrameIndex = -1) })).validate()
        }
        val reported = sample(
            frames = listOf(
                frame(11L, rootId = 1L, ordinal = 0, sha256 = SHA_A_REPORTED, reportedFrameIndex = 7, reportedPtsUs = 700_000L),
                frame(12L, rootId = 2L, ordinal = 0, imageKey = MEDIA_KEY_B, sha256 = SHA_B_REPORTED),
            ),
            marks = listOf(
                mark(101L, frameId = 11L, ordinal = 0),
                mark(103L, frameId = 12L, ordinal = 0),
            ),
            images = listOf(
                image(MEDIA_KEY_A, 1_024L, SHA_A_REPORTED),
                image(MEDIA_KEY_B, 1_024L, SHA_B_REPORTED),
            ),
        )
        val decoded = MediaBackupManifestCodec.decode(MediaBackupManifestCodec.encode(reported))
        assertEquals(7, decoded.angleV2.frames.single { it.id == 11L }.reportedFrameIndex)
        assertEquals(null, decoded.angleV2.frames.single { it.id == 12L }.reportedPtsUs)
    }

    @Test
    fun imageMetadataMustMatchTheFrameDeclaration() {
        val manifest = sample()
        val v2 = manifest.angleV2
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(images = v2.images.map { if (it.imageKey == MEDIA_KEY_A) it.copy(byteCount = 2_048L) else it })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(images = v2.images.map { if (it.imageKey == MEDIA_KEY_A) it.copy(sha256 = "f".repeat(64)) else it })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(images = v2.images.map { if (it.imageKey == MEDIA_KEY_A) it.copy(sha256 = "A".repeat(64)) else it })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(images = v2.images.map { if (it.imageKey == MEDIA_KEY_A) it.copy(sha256 = "0".repeat(63)) else it })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(angleV2 = v2.copy(images = v2.images.map { if (it.imageKey == MEDIA_KEY_A) it.copy(byteCount = 0L) else it })).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(
                angleV2 = v2.copy(frames = v2.frames.map { if (it.id == 11L) it.copy(imageMimeType = "image/jpeg") else it }),
            ).validate()
        }
    }

    @Test
    fun imageBudgetsRejectCapsAndOverflow() {
        val manifest = sample()
        val v2 = manifest.angleV2
        val tooLarge = MEDIA_BACKUP_MAX_IMAGE_BYTES + 1
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(
                angleV2 = v2.copy(
                    frames = v2.frames.map { if (it.id == 11L) it.copy(imageByteCount = tooLarge) else it },
                    images = v2.images.map { if (it.imageKey == MEDIA_KEY_A) it.copy(byteCount = tooLarge) else it },
                ),
            ).validate()
        }
        // Caps y overflow se comprueban con el presupuesto del preflight, que es donde se aplican.
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestPreflight.checkManifest(manifest, MediaBackupPreflightLimits(maxImages = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestPreflight.checkManifest(manifest, MediaBackupPreflightLimits(maxImageBytes = 1_023L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestPreflight.checkManifest(manifest, MediaBackupPreflightLimits(maxTotalImageBytes = 3_071L))
        }
        MediaBackupManifestPreflight.checkManifest(manifest, MediaBackupPreflightLimits(maxTotalImageBytes = 3_072L))
        // Suma de bytes sin overflow: dos entradas enormes desbordan Long y se rechazan.
        val overflow = manifest.copy(
            angleV2 = v2.copy(
                frames = v2.frames.map { it.copy(imageByteCount = Long.MAX_VALUE) },
                images = v2.images.map { it.copy(byteCount = Long.MAX_VALUE) },
            ),
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestPreflight.checkManifest(
                overflow,
                MediaBackupPreflightLimits(maxImageBytes = Long.MAX_VALUE, maxTotalImageBytes = Long.MAX_VALUE),
            )
        }
        assertTrue(error.message.orEmpty().contains("overflow"))
    }

    @Test
    fun preflightRejectsOversizedOrMalformedJsonBeforeDecoding() {
        MediaBackupManifestPreflight.check("""{"a":[]}""")
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestPreflight.check("[[0]]", MediaBackupPreflightLimits(maxDepth = 1)) }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestPreflight.check("[0,0,0]", MediaBackupPreflightLimits(maxArrayItems = 2)) }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestPreflight.check("\"bad\u0000text\"") }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestPreflight.check("\"bad\\q\"") }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestPreflight.check("123456789", MediaBackupPreflightLimits(maxNumberChars = 8)) }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestPreflight.check("""{"a":1,"a":1}""") }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestPreflight.check("""{"a":"${"x".repeat(9)}"}""", MediaBackupPreflightLimits(maxStringChars = 8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestPreflight.check("""{"a":1}""", MediaBackupPreflightLimits(maxBytes = 4))
        }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestPreflight.check("""{"a":}""") }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestPreflight.check("""{"a":1} {}""") }
    }

    @Test
    fun preflightBudgetCountsMediaRowsAndStrings() {
        val manifest = sample()
        // 2 raices + 3 fotogramas + 6 marcas + 3 imagenes = 14 filas.
        MediaBackupManifestPreflight.checkManifest(manifest, MediaBackupPreflightLimits(maxArrayItems = 14))
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestPreflight.checkManifest(manifest, MediaBackupPreflightLimits(maxArrayItems = 13))
        }
        // El SHA-256 canonico mide 64 unidades: por debajo no cabe y se rechaza.
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestPreflight.checkManifest(manifest, MediaBackupPreflightLimits(maxStringChars = 63))
        }
        MediaBackupManifestPreflight.checkManifest(manifest, MediaBackupPreflightLimits(maxStringChars = 64))
    }

    private companion object {
        const val SHA_A_REPORTED = "3333333333333333333333333333333333333333333333333333333333333333"
        const val SHA_B_REPORTED = "4444444444444444444444444444444444444444444444444444444444444444"
    }
}
