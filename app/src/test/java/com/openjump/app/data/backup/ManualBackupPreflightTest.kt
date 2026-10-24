package com.openjump.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualBackupPreflightTest {
    @Test
    fun rejectsArrayItemsBeforeDeserialization() {
        val limits = ManualBackupPreflightLimits(maxArrayItems = 2)
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.check("[0,0,0]", limits)
        }
    }

    @Test
    fun appliesInjectedBudgetsToInMemoryDocumentsBeforeValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.checkDocument(sampleBackup(), ManualBackupPreflightLimits(maxArrayItems = 5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.checkDocument(sampleBackup(), ManualBackupPreflightLimits(maxJsonValues = 10))
        }
    }

    @Test
    fun rejectsDepthAndOversizedNumberTokens() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.check("[[[0]]]", ManualBackupPreflightLimits(maxDepth = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.check("123456789", ManualBackupPreflightLimits(maxNumberChars = 8))
        }
    }

    @Test
    fun rejectsRawControlCharactersAndInvalidEscapes() {
        assertThrows(IllegalArgumentException::class.java) { ManualBackupPreflight.check("\"bad\u0000text\"") }
        assertThrows(IllegalArgumentException::class.java) { ManualBackupPreflight.check("\"bad\\q\"") }
    }

    @Test
    fun rejectsEscapedScalarAndAggregateTextBudgets() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.check("\"${"\\u0061".repeat(5)}\"", ManualBackupPreflightLimits(maxStringChars = 4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.check("[\"abc\",\"def\"]", ManualBackupPreflightLimits(maxTotalStringChars = 5))
        }
    }

    @Test
    fun directDocumentValidationChecksRowBudgetBeforeIteratingRows() {
        val fixture = sampleBackup()
        val oversizedRows = object : AbstractList<BackupAthlete>() {
            override val size: Int = MANUAL_BACKUP_MAX_ARRAY_ITEMS + 1
            override fun get(index: Int): BackupAthlete = error("row iteration must not happen")
        }
        val document = fixture.copy(tables = fixture.tables.copy(athletes = oversizedRows))

        val error = assertThrows(IllegalArgumentException::class.java) { document.validate() }
        org.junit.Assert.assertTrue(error.message.orEmpty().contains("too many rows"))
    }

    @Test
    fun rejectsOversizedInMemoryStringBeforeDocumentValidation() {
        val document = sampleBackup().copy(
            tables = sampleBackup().tables.copy(
                athletes = sampleBackup().tables.athletes.map { it.copy(displayName = "x".repeat(MANUAL_BACKUP_MAX_STRING_CHARS + 1)) },
            ),
        )
        val error = assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(document) }
        org.junit.Assert.assertTrue(error.message.orEmpty().contains("string limit"))
    }

    @Test
    fun fvpRowsAndWarningsCountTowardTheSameBudgets() {
        val document = fvpDocument()
        // Sin las filas F-V-P el fixture tiene 6 filas (1 atleta + 1 assessment + 1 intento + 3 eventos).
        ManualBackupPreflight.checkDocument(document.copy(tables = document.tables.copy(fvpAssessments = emptyList(), fvpConditions = emptyList(), fvpAttempts = emptyList())), ManualBackupPreflightLimits(maxArrayItems = 6))
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.checkDocument(document, ManualBackupPreflightLimits(maxArrayItems = 6))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.checkDocument(document, ManualBackupPreflightLimits(maxJsonValues = 10))
        }
        // El texto F-V-P entra en el presupuesto de strings: la cadena más larga del fixture base
        // son los 22 caracteres del contrato, así que un `warningsCsv` de 24 lo desborda.
        val withoutFvp = document.copy(tables = document.tables.copy(fvpAssessments = emptyList(), fvpConditions = emptyList(), fvpAttempts = emptyList()))
        ManualBackupPreflight.checkDocument(withoutFvp, ManualBackupPreflightLimits(maxStringChars = 23))
        ManualBackupPreflight.checkDocument(document, ManualBackupPreflightLimits(maxStringChars = 23))
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.checkDocument(
                document.copy(tables = document.tables.copy(fvpAssessments = document.tables.fvpAssessments.map { it.copy(warningsCsv = "w".repeat(24)) })),
                ManualBackupPreflightLimits(maxStringChars = 23),
            )
        }
    }

    /**
     * B2: el presupuesto de estructura usa `elementsCount`, asi que las columnas de procedencia
     * entran solas. El delta medido contra el mismo documento sin filas F-V-P es exactamente el
     * coste de los tres espejos con sus 20 columnas nuevas (41 + 12 + 27 = 80).
     */
    @Test
    fun fvpMirrorsAccountForTheRoom12ProvenanceColumnsInTheStructureBudget() {
        val document = fvpDocument()
        val withoutFvp = document.copy(
            tables = document.tables.copy(fvpAssessments = emptyList(), fvpConditions = emptyList(), fvpAttempts = emptyList()),
        )
        assertEquals(80, minimalJsonValues(document) - minimalJsonValues(withoutFvp))
        assertEquals(40, BackupFvpAssessment.serializer().descriptor.elementsCount)
        assertEquals(11, BackupFvpCondition.serializer().descriptor.elementsCount)
        assertEquals(26, BackupFvpAttempt.serializer().descriptor.elementsCount)
    }

    /** B2: las claves opacas de 128 caracteres caben justo en el limite por string del preflight. */
    @Test
    fun provenanceKeysAtTheDomainLimitFitTheStringBudget() {
        val document = fvpDocumentWithProvenanceKeys("k".repeat(128), "e".repeat(128))
        ManualBackupCodec.encode(document)
        ManualBackupPreflight.checkDocument(document, ManualBackupPreflightLimits(maxStringChars = 128))
        val error = assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.checkDocument(document, ManualBackupPreflightLimits(maxStringChars = 127))
        }
        org.junit.Assert.assertTrue(error.message.orEmpty().contains("string limit"))
    }

    /**
     * B2: los campos textuales nuevos cuentan en el presupuesto agregado (antes no se walkeaban).
     * El texto adicional es exactamente el de las dos claves informadas (128 + 128) y superarlo por
     * un caracter ya es "text budget".
     */
    @Test
    fun provenanceTextCountsTowardTheAggregateTextBudget() {
        val withKeys = fvpDocumentWithProvenanceKeys("k".repeat(128), "e".repeat(128))
        val withoutKeys = fvpDocumentWithProvenanceKeys(null, null)
        val totalWithKeys = minimalTotalStringChars(withKeys)
        val totalWithoutKeys = minimalTotalStringChars(withoutKeys)
        assertEquals(256L, totalWithKeys - totalWithoutKeys)
        val error = assertThrows(IllegalArgumentException::class.java) {
            ManualBackupPreflight.checkDocument(withKeys, ManualBackupPreflightLimits(maxTotalStringChars = totalWithKeys - 1))
        }
        org.junit.Assert.assertTrue(error.message.orEmpty().contains("text budget"))
        ManualBackupPreflight.checkDocument(
            withKeys,
            ManualBackupPreflightLimits(maxStringChars = 128, maxTotalStringChars = totalWithKeys),
        )
    }

    /** Presupuesto minimo de `jsonValues` con el que el documento pasa `checkDocument`. */
    private fun minimalJsonValues(document: ManualBackupDocument): Int {
        var budget = 0
        while (budget <= 10_000) {
            val accepted = runCatching {
                ManualBackupPreflight.checkDocument(document, ManualBackupPreflightLimits(maxJsonValues = budget))
            }.isSuccess
            if (accepted) return budget
            budget++
        }
        error("el documento nunca cupo en el presupuesto de estructura")
    }

    /** Presupuesto minimo agregado de texto con el que el documento pasa `checkDocument`. */
    private fun minimalTotalStringChars(document: ManualBackupDocument): Long {
        var budget = 0L
        while (budget <= 100_000L) {
            val accepted = runCatching {
                ManualBackupPreflight.checkDocument(document, ManualBackupPreflightLimits(maxTotalStringChars = budget))
            }.isSuccess
            if (accepted) return budget
            budget++
        }
        error("el documento nunca cupo en el presupuesto de texto")
    }

    /**
     * Fixture F-V-P con las claves de procedencia en el limite del contrato (o ausentes). El resto
     * del texto se mantiene identico para que el unico delta del presupuesto sean las claves.
     */
    private fun fvpDocumentWithProvenanceKeys(acquisitionContextKey: String?, timeMappingEvidenceKey: String?): ManualBackupDocument {
        val base = fvpDocument()
        return base.copy(
            tables = base.tables.copy(
                fvpAssessments = base.tables.fvpAssessments.map {
                    it.copy(acquisitionContextKind = "SAME_ACQUISITION", acquisitionContextKey = acquisitionContextKey)
                },
                fvpAttempts = base.tables.fvpAttempts.map {
                    it.copy(timeMapping = "IDENTITY_VERIFIED", timeMappingEvidenceKey = timeMappingEvidenceKey)
                },
            ),
        )
    }

    private fun fvpDocument(): ManualBackupDocument {
        val base = sampleBackup()
        return base.copy(
            sourceRoomSchemaVersion = MANUAL_BACKUP_ROOM_VERSION,
            tables = base.tables.copy(
                fvpAssessments = listOf(
                    BackupFvpAssessment(
                        id = 7L, sessionKey = "fvp", athleteId = 1L, testingSessionId = null, protocolId = "CMJ",
                        dateTime = 5L, bodyMassKg = 80.0, bodyMassSource = "PROFILE", pushOffMode = "COMMON",
                        pushOffM = 0.4, pushOffSource = "MEASURED", pushOffLandmark = null,
                        pushOffLandmarkHeightCm = null, pushOffProtocol = null, pushOffMeasuredAt = null,
                        pushOffCrouchCm = null, pushOffExtendedCm = null, sourceFps = 240.0,
                        algorithmVersion = "fvp-poc-1.0", methodVersion = "fvp-app-1.0", notes = null, f0N = 1.0,
                        slopeFv = -1.0, sfvNsPerM = 1.0, v0Mps = 1.0, pmaxW = null, r2 = null,
                        conditionCount = 1, vMinMps = 0.0, vMaxMps = 1.0, fMinN = 0.0, fMaxN = 1.0,
                        f0RelNPerKg = null, pmaxRelWPerKg = null, warningsCsv = "warnings",
                    ),
                ),
                fvpConditions = listOf(BackupFvpCondition(8L, 7L, 0, "0kg", 0.0, 80.0, 0.4, 0.42, null)),
                fvpAttempts = listOf(BackupFvpAttempt(9L, 8L, 0, 0.42, "MANUAL", null, null, null, null, null, true, null, null)),
            ),
        )
    }

    private fun sampleBackup() = ManualBackupCodec.decode(
        """{"contract":"${MANUAL_BACKUP_CONTRACT}","formatVersion":1,"sourceRoomSchemaVersion":10,"createdAtEpochMillis":1,"mediaIncluded":false,"preferencesIncluded":false,"tables":{"athletes":[{"id":1,"displayName":"athlete","birthDate":null,"sex":null,"notes":null,"createdAt":1,"updatedAt":1,"archivedAt":null,"avatarKey":null,"weightKg":null,"heightCm":null}],"athleteGroups":[],"athleteGroupCrossRef":[],"testingSessions":[],"testingParticipants":[],"assessments":[{"id":2,"sessionKey":"s","athleteId":1,"testingSessionId":null,"protocolId":"CMJ","dateTime":1,"primaryMetricKey":"HEIGHT_CM","primaryMetricValue":1.0,"primaryMetricUnit":"CENTIMETER","notes":null}],"attempts":[{"id":3,"assessmentId":2,"ordinal":0,"side":null,"source":"UNKNOWN","detectedFps":0,"dropHeightCm":null,"distanceCm":null}],"attemptEvents":[{"attemptId":3,"type":"MOVEMENT_START","ordinal":0,"ptsUs":1},{"attemptId":3,"type":"TAKEOFF","ordinal":0,"ptsUs":2},{"attemptId":3,"type":"LANDING","ordinal":0,"ptsUs":3}],"attemptMetrics":[],"attemptCalibrations":[],"attemptSpatialMarks":[],"encoderSessions":[],"encoderSamples":[],"encoderRepetitions":[],"encoderRepMetrics":[]}}""",
    )
}
