package com.openjump.app.data.backup

import com.openjump.app.angles.AngleMarkNameContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM del nombre de ángulo en el backup manual (encode + preflight + decode).
 *
 * Es el otro extremo del contrato compartido: un nombre que el editor y el repositorio aceptan (en
 * el límite exacto, con letras no ASCII y emoji) atraviesa encode/decode íntegro, y uno por encima
 * del límite sigue siendo rechazado por el preflight del backup (no se debilita la validación de
 * backup ni se trunca el nombre en ningún punto).
 */
class AngleMarkNameBackupTest {

    private val max = AngleMarkNameContract.MAX_CODE_UNITS

    private fun tables(marks: List<BackupAngleMark>) = ManualBackupTables(
        athletes = emptyList(),
        athleteGroups = emptyList(),
        athleteGroupCrossRef = emptyList(),
        testingSessions = emptyList(),
        testingParticipants = emptyList(),
        assessments = emptyList(),
        attempts = emptyList(),
        attemptEvents = emptyList(),
        attemptMetrics = emptyList(),
        attemptCalibrations = emptyList(),
        attemptSpatialMarks = emptyList(),
        encoderSessions = emptyList(),
        encoderSamples = emptyList(),
        encoderRepetitions = emptyList(),
        encoderRepMetrics = emptyList(),
        angleAnalyses = listOf(
            BackupAngleAnalysis(
                id = 9_000L,
                sessionKey = "angle-key",
                athleteId = null,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_050_000L,
                revision = 2,
                encodedWidth = 1920,
                encodedHeight = 1080,
                presentationWidth = 1920,
                presentationHeight = 1080,
                rotationDegrees = 0,
                frameIndex = 42,
                sourcePtsUs = 1_400_000L,
                geometryVersion = 1,
            ),
        ),
        angleMarks = marks,
    )

    private fun mark(name: String) = BackupAngleMark(
        id = 9_100L,
        analysisId = 9_000L,
        ordinal = 0,
        name = name,
        colorArgb = 0xFF3366CC.toInt(),
        visible = true,
        pointAX = 10.0,
        pointAY = 10.0,
        pointBX = 20.0,
        pointBY = 20.0,
        pointCX = 30.0,
        pointCY = 10.0,
    )

    private fun documentWithName(name: String) = ManualBackupDocument(
        contract = MANUAL_BACKUP_CONTRACT,
        formatVersion = MANUAL_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MANUAL_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = 1_700_000_100_000L,
        mediaIncluded = false,
        preferencesIncluded = false,
        tables = tables(listOf(mark(name))),
    )

    @Test
    fun `un nombre aceptado en el limite exacto sobrevive al roundtrip sin truncarse`() {
        val atLimit = "ñ".repeat(max - 2) + "😀"
        assertEquals(max, AngleMarkNameContract.codeUnits(atLimit))
        assertTrue(AngleMarkNameContract.fits(atLimit))

        val document = documentWithName(atLimit)
        // El preflight del backup (unidad de código UTF-16) acepta exactamente el mismo nombre.
        ManualBackupPreflight.checkDocument(document)

        val decoded = ManualBackupCodec.decode(ManualBackupCodec.encode(document))

        assertEquals(atLimit, decoded.tables.angleMarks.single().name)
        assertEquals(max, decoded.tables.angleMarks.single().name.length)
    }

    @Test
    fun `un nombre por encima del limite sigue rechazandose en el preflight del backup`() {
        val overLimit = "ñ".repeat(max + 1)
        val document = documentWithName(overLimit)

        val error = assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.checkDocument(document)
        }
        assertTrue(
            "el preflight debe seguir aplicando el presupuesto por cadena: ${error.message}",
            error.message.orEmpty().contains("string limit"),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(document) }
    }

    @Test
    fun `un json con un nombre por encima del limite se rechaza al decodificar`() {
        val validJson = String(
            ManualBackupCodec.encode(documentWithName("MARCA")),
            Charsets.UTF_8,
        )
        assertTrue(validJson.contains("\"MARCA\""))

        val tampered = validJson.replace("\"MARCA\"", "\"" + "x".repeat(max + 1) + "\"")

        val error = assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.decode(tampered)
        }
        assertTrue(
            "el decode debe seguir rechazando cadenas fuera de presupuesto: ${error.message}",
            error.message.orEmpty().contains("string limit"),
        )
    }
}
