package com.openjump.app.data.backup

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrato del manifiesto con imagenes: codec estricto, orden canonico y nombres de entrada. */
class MediaBackupManifestCodecTest {

    @Test
    fun roundTripPreservesLegacyDocumentAndMultiFrameMedia() {
        val manifest = sampleManifest()
        val bytes = MediaBackupManifestCodec.encode(manifest)
        val decoded = MediaBackupManifestCodec.decode(bytes)
        assertEquals(manifest, decoded)
        assertEquals(manifest.legacy, decoded.legacy)
        assertFalse(decoded.legacy.mediaIncluded)
        assertFalse(decoded.legacy.preferencesIncluded)
        assertEquals(2, decoded.angleV2.roots.size)
        assertEquals(3, decoded.angleV2.frames.size)
        assertEquals(6, decoded.angleV2.marks.size)
        assertEquals(3, decoded.angleV2.images.size)
        // Asignado y reportado siguen siendo campos distintos tras el viaje.
        val reported = decoded.angleV2.frames.single { it.id == 11L }
        assertEquals(5, reported.assignedFrameIndex)
        assertEquals(7, reported.reportedFrameIndex)
        assertEquals(700_000L, reported.reportedPtsUs)
        val unreported = decoded.angleV2.frames.single { it.id == 13L }
        assertNull(unreported.reportedFrameIndex)
        assertNull(unreported.reportedPtsUs)
    }

    @Test
    fun encodingIsDeterministicRegardlessOfInputOrder() {
        val manifest = sampleManifest()
        val shuffled = manifest.copy(
            angleV2 = manifest.angleV2.copy(
                roots = manifest.angleV2.roots.reversed(),
                frames = manifest.angleV2.frames.reversed(),
                marks = manifest.angleV2.marks.reversed(),
                images = manifest.angleV2.images.reversed(),
            ),
        )
        assertEquals(
            String(MediaBackupManifestCodec.encode(manifest), Charsets.UTF_8),
            String(MediaBackupManifestCodec.encode(shuffled), Charsets.UTF_8),
        )
    }

    @Test
    fun encodedManifestCarriesNoPathsUrisOrVideoIdentity() {
        val text = String(MediaBackupManifestCodec.encode(sampleManifest()), Charsets.UTF_8)
        listOf("content://", "file://", "videoUri", "sourceIdentity", "sourceHash", ".mp4", "/storage", "..").forEach {
            assertFalse("manifest must not leak '$it'", text.contains(it))
        }
        // Las entradas del contenedor no viajan en el JSON: el nombre se deriva de la clave opaca.
        assertFalse(text.contains("images/"))
        assertTrue(text.contains("\"imageKey\":\"${MEDIA_KEY_A}\""))
        assertTrue(text.contains("\"imageMimeType\":\"image/png\""))
    }

    @Test
    fun archivePlanDerivesCanonicalImageEntryNames() {
        val plan = sampleManifest().archivePlan()
        assertEquals("manifest.json", plan.manifestEntryName)
        assertEquals(3, plan.imageCount)
        assertEquals(
            listOf(MEDIA_KEY_A, MEDIA_KEY_B, MEDIA_KEY_C).sorted(),
            plan.imageEntries.map { it.imageKey },
        )
        assertEquals(
            listOf(MEDIA_KEY_A, MEDIA_KEY_B, MEDIA_KEY_C).sorted().map { "images/$it.png" },
            plan.imageEntries.map { it.entryName },
        )
        assertEquals(3L * 1_024L, plan.totalImageByteCount)
    }

    @Test
    fun archiveNamingOnlyAcceptsTheCanonicalForm() {
        assertEquals("images/$MEDIA_KEY_A.png", MediaBackupArchive.imageEntryName(MEDIA_KEY_A))
        assertEquals(MEDIA_KEY_A, MediaBackupArchive.imageKeyOfEntry("images/$MEDIA_KEY_A.png"))
        assertTrue(MediaBackupArchive.isReservedEntryName("manifest.json"))
        assertTrue(MediaBackupArchive.isReservedEntryName("images/$MEDIA_KEY_A.png"))
        listOf(
            "../images/$MEDIA_KEY_A.png",
            "/images/$MEDIA_KEY_A.png",
            "images/$MEDIA_KEY_A.PNG",
            "images/${MEDIA_KEY_A.uppercase()}.png",
            "images/$MEDIA_KEY_A..png",
            "images/sub/$MEDIA_KEY_A.png",
            "images/$MEDIA_KEY_A",
            "manifest.json/../$MEDIA_KEY_A.png",
            "",
        ).forEach { name ->
            assertNull("entry '$name' must not resolve to an image key", MediaBackupArchive.imageKeyOfEntry(name))
            assertFalse(MediaBackupArchive.isReservedEntryName(name))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupArchive.imageEntryName("../$MEDIA_KEY_A")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupArchive.imageEntryName(MEDIA_KEY_A.uppercase())
        }
    }

    @Test
    fun strictDecodeRejectsUnknownMissingAndDuplicateKeys() {
        val text = String(MediaBackupManifestCodec.encode(sampleManifest()), Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.decode(text.replaceFirst("{", "{\"unexpected\":1,"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.decode(text.replaceFirst("\"rotationDegrees\":0,", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.decode(text.replaceFirst("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"))
        }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestCodec.decode("$text}") }
        assertThrows(IllegalArgumentException::class.java) { MediaBackupManifestCodec.decode("[]") }
    }

    @Test
    fun decodeIsBoundedByTheDeclaredAndObservedLength() {
        val bytes = MediaBackupManifestCodec.encode(sampleManifest())
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.decode(ByteArrayInputStream(bytes), MEDIA_BACKUP_MAX_MANIFEST_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.decode(ByteArrayInputStream(bytes), -1L)
        }
        assertEquals(sampleManifest(), MediaBackupManifestCodec.decode(ByteArrayInputStream(bytes), bytes.size.toLong()))
        // UTF-8 invalido se rechaza antes de intentar decodificar.
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '{'.code.toByte()))
        }
    }

    @Test
    fun contractAndVersionAreNotTheLegacyOnes() {
        assertNotEquals(MANUAL_BACKUP_CONTRACT, MEDIA_BACKUP_CONTRACT)
        val manifest = sampleManifest()
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.encode(manifest.copy(contract = MANUAL_BACKUP_CONTRACT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.encode(manifest.copy(formatVersion = MEDIA_BACKUP_FORMAT_VERSION + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.encode(manifest.copy(sourceRoomSchemaVersion = 13))
        }
    }

    @Test
    fun emissionOverTheBudgetIsBoundedAndWritesNothingExternally() {
        val manifest = sampleManifest()
        val exact = MediaBackupManifestCodec.encode(manifest).size.toLong()
        // Un byte menos que la serializacion real: el presupuesto se aplica emitiendo, no despues.
        val tight = MediaBackupCodecLimits(maxManifestBytes = exact - 1)
        val error = assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.encode(manifest, tight)
        }
        assertEquals("Media backup manifest is too large", error.message)
        // encodeTo no deja bytes parciales: la excepcion llega antes de escribir en el destino.
        val sink = RecordingOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.encodeTo(manifest, sink, tight)
        }
        assertEquals(0, sink.size)
        // Borde numerico exacto: el limite igual al tamano se acepta byte a byte.
        val atLimit = MediaBackupManifestCodec.encode(
            manifest,
            MediaBackupCodecLimits(maxManifestBytes = exact),
        )
        assertEquals(exact.toInt(), atLimit.size)
        assertEquals(
            String(MediaBackupManifestCodec.encode(manifest), Charsets.UTF_8),
            String(atLimit, Charsets.UTF_8),
        )
        // La lectura sin longitud declarada tambien esta acotada mientras se consume el flujo.
        val bytes = MediaBackupManifestCodec.encode(manifest)
        assertEquals(
            "Media backup manifest is too large",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(
                    ByteArrayInputStream(bytes),
                    null,
                    MediaBackupCodecLimits(maxManifestBytes = bytes.size.toLong() - 1L),
                )
            }.message,
        )
    }

    @Test
    fun legacyDocumentOverItsByteBudgetIsRejectedOnEncodeAndDecode() {
        val manifest = sample().copy(legacy = paddedLegacy(notesChars = 4_000, athletes = 30))
        val legacyBytes = ManualBackupCodec.encode(manifest.legacy).size.toLong()
        // El documento es grande de verdad (cientos de KiB) y estructuralmente valido: el codec v1
        // lo acepta; lo que se comprueba aqui es el presupuesto de bytes del legacy.
        assertTrue("padded legacy must be large", legacyBytes > 100L * 1_024L)
        val generous = MediaBackupCodecLimits(maxLegacyBytes = legacyBytes)
        // Borde numerico: exactamente el tamano del JSON legacy se acepta y el roundtrip es fiel.
        val bytes = MediaBackupManifestCodec.encode(manifest, generous)
        assertEquals(manifest, MediaBackupManifestCodec.decode(bytes, generous))
        val text = String(bytes, Charsets.UTF_8)
        assertEquals(manifest, MediaBackupManifestCodec.decode(text, generous))
        val tight = MediaBackupCodecLimits(maxLegacyBytes = legacyBytes - 1)
        assertEquals(
            "Media backup legacy document is too large",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.encode(manifest, tight)
            }.message,
        )
        assertEquals(
            "Media backup legacy document is too large",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(bytes, tight)
            }.message,
        )
        assertEquals(
            "Media backup legacy document is too large",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(text, tight)
            }.message,
        )
        assertEquals(
            "Media backup legacy document is too large",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(ByteArrayInputStream(bytes), bytes.size.toLong(), tight)
            }.message,
        )
    }

    @Test
    fun legacyWithinTheBudgetIsAcceptedAndDefaultsAreTheProductionLimits() {
        // Los limites por defecto son los de contrato y no se pueden cambiar desde la API publica:
        // la unica inyeccion es la sobrecarga `internal` que usan estas pruebas.
        assertEquals(16L * 1_024L * 1_024L, MEDIA_BACKUP_MAX_MANIFEST_BYTES)
        assertEquals(8L * 1_024L * 1_024L, MANUAL_BACKUP_MAX_BYTES)
        assertEquals(
            MediaBackupCodecLimits(
                maxManifestBytes = MEDIA_BACKUP_MAX_MANIFEST_BYTES,
                maxLegacyBytes = MANUAL_BACKUP_MAX_BYTES,
            ),
            MediaBackupCodecLimits(),
        )
        assertEquals(MediaBackupPreflightLimits(), MediaBackupCodecLimits().preflightLimits)
        // Un legacy por debajo del presupuesto se acepta en ambos sentidos, tambien con el tope
        // inyectado justo en su tamano (no se exige el de produccion para aceptar).
        val manifest = sampleManifest()
        val legacyBytes = ManualBackupCodec.encode(manifest.legacy).size.toLong()
        val limits = MediaBackupCodecLimits(maxLegacyBytes = legacyBytes)
        val bytes = MediaBackupManifestCodec.encode(manifest, limits)
        assertEquals(manifest, MediaBackupManifestCodec.decode(bytes, limits))
        assertEquals(
            "Media backup legacy document is too large",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.encode(manifest, MediaBackupCodecLimits(maxLegacyBytes = legacyBytes - 1))
            }.message,
        )
    }

    @Test
    fun declaredLengthIsExactNotAnUpperBound() {
        val bytes = MediaBackupManifestCodec.encode(sampleManifest())
        val exact = bytes.size.toLong()
        // El flujo entrega menos bytes que los declarados: entrada truncada, no JSON invalido.
        assertEquals(
            "Media backup manifest length does not match the declared length",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), exact)
            }.message,
        )
        // El flujo entrega mas bytes que los declarados.
        assertEquals(
            "Media backup manifest length does not match the declared length",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(
                    ByteArrayInputStream(bytes + '}'.code.toByte()),
                    exact,
                )
            }.message,
        )
        assertEquals(
            "Media backup manifest length does not match the declared length",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(ByteArrayInputStream(bytes), exact + 1)
            }.message,
        )
        // La longitud exacta del contenedor se acepta.
        assertEquals(sampleManifest(), MediaBackupManifestCodec.decode(ByteArrayInputStream(bytes), exact))
        // El prechequeo de presupuesto ocurre antes de leer: el flujo ni se toca.
        val untouched = ThrowingInputStream()
        assertEquals(
            "Media backup manifest is too large",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(untouched, MEDIA_BACKUP_MAX_MANIFEST_BYTES + 1)
            }.message,
        )
        assertEquals(0, untouched.reads)
    }

    @Test
    fun injectedLimitsCannotRelaxContractValidation() {
        val manifest = sampleManifest()
        val generous = MediaBackupCodecLimits(
            maxManifestBytes = Long.MAX_VALUE,
            maxLegacyBytes = Long.MAX_VALUE,
        )
        // Duplicados, NaN y claves desconocidas siguen rechazandose con topes enormes inyectados.
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.encode(
                manifest.copy(
                    angleV2 = manifest.angleV2.copy(
                        marks = manifest.angleV2.marks.map { it.copy(pointAX = Double.NaN) },
                    ),
                ),
                generous,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.encode(
                manifest.copy(legacy = manifest.legacy.copy(mediaIncluded = true)),
                generous,
            )
        }
        val text = String(MediaBackupManifestCodec.encode(manifest), Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.decode(text.replaceFirst("{", "{\"unexpected\":1,"), generous)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaBackupManifestCodec.decode(
                text.replaceFirst("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
                generous,
            )
        }
        // El presupuesto de bytes inyectado si acota la lectura de texto.
        assertEquals(
            "Media backup manifest is too large",
            assertThrows(IllegalArgumentException::class.java) {
                MediaBackupManifestCodec.decode(text, MediaBackupCodecLimits(maxManifestBytes = 32L))
            }.message,
        )
    }

    companion object {
        const val MEDIA_KEY_A = "0123456789abcdef0123456789abcdef"
        const val MEDIA_KEY_B = "00112233445566778899aabbccddeeff"
        const val MEDIA_KEY_C = "fedcba9876543210fedcba9876543210"
        private const val SHA_A = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val SHA_B = "1111111111111111111111111111111111111111111111111111111111111111"
        private const val SHA_C = "2222222222222222222222222222222222222222222222222222222222222222"

        /** Documento legacy v1 real (Room 10) decodificado por el codec existente. */
        fun legacyDocument(): ManualBackupDocument = ManualBackupCodec.decode(LEGACY_JSON)

        /**
         * Legacy estructuralmente valido y grande: anade atletas con notas largas (<= 4096 por
         * cadena, <= 1 M de texto) para rebasar presupuestos de bytes pequenos sin tocar los
         * limites de produccion ni construir 8 MiB.
         */
        fun paddedLegacy(notesChars: Int, athletes: Int): ManualBackupDocument {
            val document = legacyDocument()
            val template = document.tables.athletes.first()
            val padding = (1..athletes).map { offset ->
                template.copy(
                    id = template.id + offset,
                    displayName = "athlete-${offset + 1}",
                    notes = "x".repeat(notesChars),
                )
            }
            return document.copy(tables = document.tables.copy(athletes = document.tables.athletes + padding))
        }

        fun frame(
            id: Long,
            rootId: Long = 1L,
            ordinal: Int = 0,
            imageKey: String = MEDIA_KEY_A,
            byteCount: Long = 1_024L,
            sha256: String = SHA_A,
            rotationDegrees: Int = 0,
            presentationWidth: Int = 640,
            presentationHeight: Int = 480,
            reportedFrameIndex: Int? = null,
            reportedPtsUs: Long? = null,
        ) = MediaBackupFrame(
            id = id,
            rootId = rootId,
            ordinal = ordinal,
            assignedFrameIndex = 5,
            reportedFrameIndex = reportedFrameIndex,
            assignedPtsUs = 500_000L,
            reportedPtsUs = reportedPtsUs,
            encodedWidth = 640,
            encodedHeight = 480,
            presentationWidth = presentationWidth,
            presentationHeight = presentationHeight,
            rotationDegrees = rotationDegrees,
            geometryVersion = 1,
            imageKey = imageKey,
            imageWidth = presentationWidth,
            imageHeight = presentationHeight,
            imageByteCount = byteCount,
            imageSha256 = sha256,
            imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
        )

        fun mark(
            id: Long,
            frameId: Long = 11L,
            ordinal: Int = 0,
            name: String = "rodilla",
            pointAX: Double = 10.0,
            pointAY: Double = 10.0,
            pointBX: Double = 100.0,
            pointBY: Double = 100.0,
            pointCX: Double = 200.0,
            pointCY: Double = 10.0,
        ) = MediaBackupMark(
            id = id,
            frameId = frameId,
            ordinal = ordinal,
            name = name,
            colorArgb = -0x10000,
            visible = true,
            pointAX = pointAX,
            pointAY = pointAY,
            pointBX = pointBX,
            pointBY = pointBY,
            pointCX = pointCX,
            pointCY = pointCY,
        )

        fun image(
            imageKey: String,
            byteCount: Long = 1_024L,
            sha256: String = SHA_A,
        ) = MediaBackupImage(imageKey = imageKey, byteCount = byteCount, sha256 = sha256)

        fun sample(
            roots: List<MediaBackupRoot> = defaultRoots(),
            frames: List<MediaBackupFrame> = defaultFrames(),
            marks: List<MediaBackupMark> = defaultMarks(),
            images: List<MediaBackupImage> = defaultImages(),
        ) = MediaBackupManifest(
            contract = MEDIA_BACKUP_CONTRACT,
            formatVersion = MEDIA_BACKUP_FORMAT_VERSION,
            sourceRoomSchemaVersion = MEDIA_BACKUP_ROOM_VERSION,
            createdAtEpochMillis = 1_700_000_000_000L,
            legacy = legacyDocument(),
            angleV2 = MediaBackupAngleV2(roots = roots, frames = frames, marks = marks, images = images),
        )

        fun sampleManifest(): MediaBackupManifest = sample()

        fun defaultRoots() = listOf(
            MediaBackupRoot(id = 1L, analysisFormat = "ANGLE_MULTI_FRAME_V1", sessionKey = "session-1", athleteId = 1L, createdAt = 1L, updatedAt = 2L, revision = 0),
            MediaBackupRoot(id = 2L, analysisFormat = "ANGLE_MULTI_FRAME_V1", sessionKey = "session-2", athleteId = null, createdAt = 3L, updatedAt = 4L, revision = 1),
        )

        fun defaultFrames() = listOf(
            frame(11L, rootId = 1L, ordinal = 0, imageKey = MEDIA_KEY_A, sha256 = SHA_A, reportedFrameIndex = 7, reportedPtsUs = 700_000L),
            frame(12L, rootId = 2L, ordinal = 0, imageKey = MEDIA_KEY_B, sha256 = SHA_B),
            frame(13L, rootId = 2L, ordinal = 1, imageKey = MEDIA_KEY_C, sha256 = SHA_C, byteCount = 1_024L),
        )

        fun defaultMarks() = listOf(
            mark(101L, frameId = 11L, ordinal = 0, name = "rodilla"),
            mark(102L, frameId = 11L, ordinal = 1, name = "cadera"),
            mark(103L, frameId = 12L, ordinal = 0, name = "tobillo"),
            mark(104L, frameId = 12L, ordinal = 1, name = "rodilla"),
            mark(105L, frameId = 13L, ordinal = 0, name = "hombro"),
            mark(106L, frameId = 13L, ordinal = 1, name = "codo"),
        )

        /** En el orden canonico que fija el codec (por clave), para que el roundtrip sea estable. */
        fun defaultImages() = listOf(
            image(MEDIA_KEY_B, byteCount = 1_024L, sha256 = SHA_B),
            image(MEDIA_KEY_A, byteCount = 1_024L, sha256 = SHA_A),
            image(MEDIA_KEY_C, byteCount = 1_024L, sha256 = SHA_C),
        )

        private const val LEGACY_JSON =
            """{"contract":"$MANUAL_BACKUP_CONTRACT","formatVersion":1,"sourceRoomSchemaVersion":10,"createdAtEpochMillis":1,"mediaIncluded":false,"preferencesIncluded":false,"tables":{"athletes":[{"id":1,"displayName":"athlete","birthDate":null,"sex":null,"notes":null,"createdAt":1,"updatedAt":1,"archivedAt":null,"avatarKey":null,"weightKg":null,"heightCm":null}],"athleteGroups":[],"athleteGroupCrossRef":[],"testingSessions":[],"testingParticipants":[],"assessments":[{"id":2,"sessionKey":"s","athleteId":1,"testingSessionId":null,"protocolId":"CMJ","dateTime":1,"primaryMetricKey":"HEIGHT_CM","primaryMetricValue":1.0,"primaryMetricUnit":"CENTIMETER","notes":null}],"attempts":[{"id":3,"assessmentId":2,"ordinal":0,"side":null,"source":"UNKNOWN","detectedFps":0,"dropHeightCm":null,"distanceCm":null}],"attemptEvents":[{"attemptId":3,"type":"MOVEMENT_START","ordinal":0,"ptsUs":1},{"attemptId":3,"type":"TAKEOFF","ordinal":0,"ptsUs":2},{"attemptId":3,"type":"LANDING","ordinal":0,"ptsUs":3}],"attemptMetrics":[],"attemptCalibrations":[],"attemptSpatialMarks":[],"encoderSessions":[],"encoderSamples":[],"encoderRepetitions":[],"encoderRepMetrics":[]}}"""
    }
}

/** Destino de escritura que solo cuenta bytes: prueba que un fallo no escribe nada fuera. */
private class RecordingOutputStream : java.io.OutputStream() {
    private var written = 0

    val size: Int get() = written

    override fun write(value: Int) {
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        written += length
    }
}

/** Flujo que falla si alguien lo lee: prueba que el prechequeo ocurre antes de consumirlo. */
private class ThrowingInputStream : java.io.InputStream() {
    var reads: Int = 0
        private set

    override fun read(): Int {
        reads++
        throw AssertionError("the stream must not be read")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        reads++
        throw AssertionError("the stream must not be read")
    }
}
