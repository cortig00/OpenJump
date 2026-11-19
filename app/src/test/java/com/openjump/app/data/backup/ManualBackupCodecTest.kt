package com.openjump.app.data.backup

import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.openjump.app.angles.AngleGeometry
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.FVP_REUSE_CONFIRMATION_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualBackupCodecTest {
    @Test fun roundTripIsUtf8DeterministicAndOmitsUris() {
        val document = sample()
        val first = ManualBackupCodec.encode(document)
        val second = ManualBackupCodec.encode(document.copy(createdAtEpochMillis = document.createdAtEpochMillis))
        assertEquals(String(first, Charsets.UTF_8), String(second, Charsets.UTF_8))
        val decoded = ManualBackupCodec.decode(first)
        assertEquals(9_000_000_000L, decoded.tables.assessments.single().id)
        assertTrue(decoded.tables.attemptEvents.any { it.ptsUs == 123_456_789_012_345L })
        assertFalse(String(first, Charsets.UTF_8).contains("videoUri"))
        assertEquals(ManualBackupSummary.from(document), ManualBackupSummary.from(decoded))
    }

    @Test
    fun roundTripPreservesRoom10NotesAndEventNeighborhood() {
        val document = sample().copy(
            tables = sample().tables.copy(
                assessments = sample().tables.assessments.map { it.copy(notes = " jump note ".trim()) },
                encoderSessions = sample().tables.encoderSessions.map { it.copy(notes = "encoder note") },
                attemptEvents = sample().tables.attemptEvents.mapIndexed { index, event ->
                    event.copy(frameIndex = index + 1, previousPtsUs = if (index == 0) null else event.ptsUs - 1, nextPtsUs = event.ptsUs + 1)
                },
            ),
        )
        val decoded = ManualBackupCodec.decode(ManualBackupCodec.encode(document))
        assertEquals("jump note", decoded.tables.assessments.single().notes)
        assertEquals("encoder note", decoded.tables.encoderSessions.single().notes)
        assertEquals(1, decoded.tables.attemptEvents.first { it.type == "MOVEMENT_START" }.frameIndex)
        val takeoff = decoded.tables.attemptEvents.single { it.type == "TAKEOFF" }
        assertEquals(takeoff.ptsUs - 1, takeoff.previousPtsUs)
    }

    @Test
    fun rejectsPartialPtsNeighborhoodsAndRoom9V10Fields() {
        val base = sample()
        val events = base.tables.attemptEvents
        val takeoff = events.single { it.type == "TAKEOFF" }
        fun withTakeoff(updated: BackupAttemptEvent) = base.copy(
            tables = base.tables.copy(attemptEvents = events.map { if (it.type == "TAKEOFF") updated else it }),
        )
        val previousWithoutFrame = withTakeoff(
            takeoff.copy(frameIndex = null, previousPtsUs = takeoff.ptsUs - 1, nextPtsUs = null),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(previousWithoutFrame) }
        val nextWithoutFrame = withTakeoff(
            takeoff.copy(frameIndex = null, previousPtsUs = null, nextPtsUs = takeoff.ptsUs + 1),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(nextWithoutFrame) }
        val validEdge = withTakeoff(
            takeoff.copy(frameIndex = 1, previousPtsUs = null, nextPtsUs = takeoff.ptsUs + 1),
        )
        val roundTrippedEdge = ManualBackupCodec.decode(ManualBackupCodec.encode(validEdge))
        val restoredTakeoff = roundTrippedEdge.tables.attemptEvents.single { it.type == "TAKEOFF" }
        assertEquals(1, restoredTakeoff.frameIndex)
        assertEquals(null, restoredTakeoff.previousPtsUs)
        assertEquals(takeoff.ptsUs + 1, restoredTakeoff.nextPtsUs)
        val room9WithV10 = sample().copy(
            formatVersion = 1,
            sourceRoomSchemaVersion = 9,
            tables = sample().tables.copy(
                assessments = sample().tables.assessments.map { it.copy(notes = "new") },
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(room9WithV10) }
    }

    @Test
    fun legacyRoom9JsonWithoutNewKeysDecodesWithNulls() {
        val encoded = String(ManualBackupCodec.encode(sample().copy(formatVersion = 1, sourceRoomSchemaVersion = 10)), Charsets.UTF_8)
            .replace("\"sourceRoomSchemaVersion\":10,", "\"sourceRoomSchemaVersion\":9,")
            .replace(",\"primaryMetricUnit\":\"CENTIMETER\",\"notes\":null", ",\"primaryMetricUnit\":\"CENTIMETER\"")
            .replace(",\"setVelocityLoss\":0.1,\"notes\":null", ",\"setVelocityLoss\":0.1")
            .replace(Regex("(\"ptsUs\":[0-9]+),\"frameIndex\":null,\"previousPtsUs\":null,\"nextPtsUs\":null"), "$1")
        val decoded = ManualBackupCodec.decode(encoded)
        assertEquals(9, decoded.sourceRoomSchemaVersion)
        assertEquals(null, decoded.tables.assessments.single().notes)
        assertEquals(null, decoded.tables.encoderSessions.single().notes)
        assertTrue(decoded.tables.attemptEvents.all { it.frameIndex == null && it.previousPtsUs == null && it.nextPtsUs == null })
    }

    @Test
    fun twoEventCountermovementAndBilateralBackupsRoundTrip() {
        val base = sample()
        val twoEvent = base.copy(
            tables = base.tables.copy(
                attemptEvents = base.tables.attemptEvents.filterNot { it.type == "MOVEMENT_START" },
            ),
        )
        val decoded = ManualBackupCodec.decode(ManualBackupCodec.encode(twoEvent))
        assertEquals(2, decoded.tables.attemptEvents.size)
        assertTrue(decoded.tables.attemptEvents.none { it.type == "MOVEMENT_START" })

        val firstAttempt = twoEvent.tables.attempts.single().copy(side = "LEFT")
        val secondAttempt = firstAttempt.copy(id = firstAttempt.id + 1, ordinal = 1, side = "RIGHT")
        val events = twoEvent.tables.attemptEvents.flatMap { event ->
            listOf(event.copy(attemptId = firstAttempt.id), event.copy(attemptId = secondAttempt.id))
        }
        val metrics = twoEvent.tables.attemptMetrics +
            twoEvent.tables.attemptMetrics.map { it.copy(attemptId = secondAttempt.id) }
        val bilateral = twoEvent.copy(
            tables = twoEvent.tables.copy(
                assessments = twoEvent.tables.assessments.map {
                    it.copy(
                        protocolId = "ASYMMETRY",
                        testingSessionId = null,
                        primaryMetricKey = "ASYMMETRY_PERCENT",
                        primaryMetricUnit = "PERCENT",
                    )
                },
                attempts = listOf(firstAttempt, secondAttempt),
                attemptEvents = events,
                attemptMetrics = metrics,
                attemptCalibrations = twoEvent.tables.attemptCalibrations.filter { it.attemptId == firstAttempt.id },
                attemptSpatialMarks = emptyList(),
            ),
        )
        val decodedBilateral = ManualBackupCodec.decode(ManualBackupCodec.encode(bilateral))
        assertEquals(4, decodedBilateral.tables.attemptEvents.size)
        assertTrue(decodedBilateral.tables.attemptEvents.none { it.type == "MOVEMENT_START" })
    }

    @Test
    fun rejectsMissingMandatoryEventsAndAStartAfterTakeoff() {
        val base = sample()
        val takeoffPts = base.tables.attemptEvents.single { it.type == "TAKEOFF" }.ptsUs
        fun withEvents(update: (List<BackupAttemptEvent>) -> List<BackupAttemptEvent>) =
            base.copy(tables = base.tables.copy(attemptEvents = update(base.tables.attemptEvents)))

        // Takeoff and landing stay mandatory even when the optional start is absent.
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(withEvents { events -> events.filterNot { it.type == "TAKEOFF" } })
        }
        // A start that exists must precede takeoff.
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(
                withEvents { events ->
                    events.map { if (it.type == "MOVEMENT_START") it.copy(ptsUs = takeoffPts + 1) else it }
                },
            )
        }

        val twoEvent = withEvents { events -> events.filterNot { it.type == "MOVEMENT_START" } }
        val landingAtTakeoff = twoEvent.copy(
            tables = twoEvent.tables.copy(
                attemptEvents = twoEvent.tables.attemptEvents.map {
                    if (it.type == "LANDING") it.copy(ptsUs = takeoffPts) else it
                },
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(landingAtTakeoff) }

        // The bilateral branch follows the same optional-start rule.
        val firstAttempt = base.tables.attempts.single().copy(side = "LEFT")
        val secondAttempt = firstAttempt.copy(id = firstAttempt.id + 1, ordinal = 1, side = "RIGHT")
        fun bilateralWith(events: List<BackupAttemptEvent>) = base.copy(
            tables = base.tables.copy(
                assessments = base.tables.assessments.map {
                    it.copy(
                        protocolId = "ASYMMETRY",
                        testingSessionId = null,
                        primaryMetricKey = "ASYMMETRY_PERCENT",
                        primaryMetricUnit = "PERCENT",
                    )
                },
                attempts = listOf(firstAttempt, secondAttempt),
                attemptEvents = events,
                attemptMetrics = base.tables.attemptMetrics +
                    base.tables.attemptMetrics.map { it.copy(attemptId = secondAttempt.id) },
                attemptCalibrations = base.tables.attemptCalibrations.filter { it.attemptId == firstAttempt.id },
                attemptSpatialMarks = emptyList(),
            ),
        )
        val validEvents = base.tables.attemptEvents.flatMap { event ->
            listOf(event.copy(attemptId = firstAttempt.id), event.copy(attemptId = secondAttempt.id))
        }
        ManualBackupCodec.encode(bilateralWith(validEvents.filterNot { it.type == "MOVEMENT_START" }))
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(
                bilateralWith(
                    validEvents.map {
                        if (it.type == "MOVEMENT_START") it.copy(ptsUs = takeoffPts + 1) else it
                    },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(bilateralWith(validEvents.filterNot { it.type == "TAKEOFF" }))
        }
    }

    @Test
    fun bilateralBackupUsesExplicitUnilateralEventSequence() {
        val base = sample()
        val firstAttempt = base.tables.attempts.single().copy(side = "LEFT")
        val secondAttempt = firstAttempt.copy(id = firstAttempt.id + 1, ordinal = 1, side = "RIGHT")
        val events = base.tables.attemptEvents.flatMap { event ->
            listOf(event.copy(attemptId = firstAttempt.id), event.copy(attemptId = secondAttempt.id))
        }
        val metrics = base.tables.attemptMetrics + base.tables.attemptMetrics.map { it.copy(attemptId = secondAttempt.id) }
        val bilateral = base.copy(
            tables = base.tables.copy(
                assessments = base.tables.assessments.map { it.copy(protocolId = "ASYMMETRY", testingSessionId = null, primaryMetricKey = "ASYMMETRY_PERCENT", primaryMetricUnit = "PERCENT") },
                attempts = listOf(firstAttempt, secondAttempt),
                attemptEvents = events,
                attemptMetrics = metrics,
                attemptCalibrations = base.tables.attemptCalibrations.filter { it.attemptId == firstAttempt.id },
                attemptSpatialMarks = emptyList(),
            ),
        )
        ManualBackupCodec.decode(ManualBackupCodec.encode(bilateral))
    }

    @Test fun rejectsAnalyticalContractAndFutureVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.decode("{\"contract\":\"openjump-graph-export\"}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(sample().copy(formatVersion = 4))
        }
    }

    @Test fun rejectsTruncationUnknownKeysAndNonFiniteValues() {
        val encoded = ManualBackupCodec.encode(sample())
        assertThrows(Exception::class.java) { ManualBackupCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows(Exception::class.java) { ManualBackupCodec.decode(String(encoded).replace("{", "{\"unknown\":1,")) }
        assertThrows(Exception::class.java) { ManualBackupCodec.decode(String(encoded).replace("1.0", "NaN")) }
    }

    @Test fun requiresEveryHeaderAndTableCollectionOnDecode() {
        val json = String(ManualBackupCodec.encode(sample()), Charsets.UTF_8)
        val missingHeader = json.replaceFirst(Regex("\\\"contract\\\":\\\"[^\\\"]*\\\",?"), "")
        val missingCollection = json.replaceFirst(Regex("\"athletes\":\\[[^\\]]*\\],?"), "")
        assertThrows(Exception::class.java) { ManualBackupCodec.decode(missingHeader) }
        assertThrows(Exception::class.java) { ManualBackupCodec.decode(missingCollection) }
    }

    @Test fun rejectsMalformedUtf8AndNonPositiveGeneratedIds() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.decode(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28))
        }
        val document = sample()
        val invalidDocuments = listOf(
            document.copy(tables = document.tables.copy(athletes = listOf(document.tables.athletes.single().copy(id = 0L)))),
            document.copy(tables = document.tables.copy(athleteGroups = listOf(document.tables.athleteGroups.single().copy(id = -1L)))),
            document.copy(tables = document.tables.copy(testingSessions = listOf(document.tables.testingSessions.single().copy(id = 0L)))),
            document.copy(tables = document.tables.copy(assessments = listOf(document.tables.assessments.single().copy(id = -1L)))),
            document.copy(tables = document.tables.copy(attempts = listOf(document.tables.attempts.single().copy(id = 0L)))),
            document.copy(tables = document.tables.copy(encoderSessions = listOf(document.tables.encoderSessions.single().copy(id = -1L)))),
            document.copy(tables = document.tables.copy(encoderRepetitions = listOf(document.tables.encoderRepetitions.single().copy(id = 0L)))),
        )
        invalidDocuments.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(invalid) }
        }
    }

    @Test fun rejectsNonIncreasingEncoderPtsAndEmptyDocuments() {
        val document = sample()
        val rows = document.tables.encoderSamples
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(document.copy(tables = document.tables.copy(encoderSamples = rows.map { it.copy(sourcePtsUs = 1L) })))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(document.copy(tables = emptyTables()))
        }
    }

    @Test fun validatesActiveTestingCursorWhileAllowingRosterGapsFromAthleteDeletion() {
        val document = sample()
        val gappedRoster = document.copy(
            tables = document.tables.copy(
                testingSessions = document.tables.testingSessions.map { it.copy(status = "ACTIVE", currentOrdinal = 7) },
                testingParticipants = document.tables.testingParticipants.map { it.copy(ordinal = 7) },
            ),
        )
        ManualBackupCodec.encode(gappedRoster)
        val invalidCursor = gappedRoster.copy(
            tables = gappedRoster.tables.copy(
                testingSessions = gappedRoster.tables.testingSessions.map { it.copy(currentOrdinal = 20) },
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(invalidCursor) }
    }

    @Test fun rejectsEncoderPhasesOutsideRepetitionOrInWrongExerciseOrder() {
        val document = sample()
        val repetition = document.tables.encoderRepetitions.single()
        val outsideBounds = document.copy(
            tables = document.tables.copy(
                encoderRepetitions = listOf(repetition.copy(startSample = 1)),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(outsideBounds) }

        val wrongBenchPressOrder = document.copy(
            tables = document.tables.copy(
                encoderRepetitions = listOf(
                    repetition.copy(
                        eccentricStartSample = 1,
                        eccentricEndSample = 1,
                        concentricStartSample = 0,
                        concentricEndSample = 0,
                    ),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(wrongBenchPressOrder) }
    }

    @Test fun validatesProtocolChronologyAndEncoderPhysicalTimeline() {
        val document = sample()
        val missingLanding = document.copy(
            tables = document.tables.copy(
                attemptEvents = document.tables.attemptEvents.filter { it.type != "LANDING" },
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(missingLanding) }

        val horizontal = document.copy(
            tables = document.tables.copy(
                testingSessions = document.tables.testingSessions.map { it.copy(protocolId = "HORIZONTAL") },
                assessments = document.tables.assessments.map { it.copy(protocolId = "HORIZONTAL") },
                attemptEvents = emptyList(),
                attemptSpatialMarks = listOf(
                    BackupAttemptSpatialMark(10_000_000_000L, "START_POINT", 2, 100L, 1.0, 2.0),
                    BackupAttemptSpatialMark(10_000_000_000L, "LANDING_HEEL", 3, 200L, 2.0, 2.0),
                ),
            ),
        )
        ManualBackupCodec.encode(horizontal)
        val badLanding = horizontal.copy(
            tables = horizontal.tables.copy(
                attemptSpatialMarks = horizontal.tables.attemptSpatialMarks.map { it.copy(ptsUs = 100L) },
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(badLanding) }

        val badPhysicalTime = document.copy(
            tables = document.tables.copy(
                encoderSamples = document.tables.encoderSamples.mapIndexed { index, row ->
                    if (index == 1) row.copy(physicalTimeUs = 999L) else row
                },
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(badPhysicalTime) }
    }

    @Test fun rejectsDuplicateKeysOrOrphanReferences() {
        val document = sample()
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(document.copy(tables = document.tables.copy(athleteGroups = document.tables.athleteGroups + document.tables.athleteGroups)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(document.copy(tables = document.tables.copy(attempts = listOf(document.tables.attempts.single().copy(assessmentId = 404L)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.decode(ByteArray(MANUAL_BACKUP_MAX_BYTES.toInt() + 1))
        }
    }

    @Test fun declaredLengthAboveMaxReportsTooLargeWhileNegativeStaysInvalid() {
        val tooLarge = assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.decode(ByteArrayInputStream(ByteArray(0)), MANUAL_BACKUP_MAX_BYTES + 1)
        }
        assertTrue(tooLarge.message?.contains("too large", ignoreCase = true) == true)
        val negative = assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.decode(ByteArrayInputStream(ByteArray(0)), -1L)
        }
        assertEquals("Invalid manual backup length", negative.message)
    }

    @Test fun streamWithSmallReadsPreservesMultibyteUtf8AndBudgets() {
        val bytes = ManualBackupCodec.encode(sample())
        val input = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 3))
        }
        val decoded = ManualBackupCodec.decode(input, bytes.size.toLong())
        assertEquals("Átleta Ω", decoded.tables.athletes.single().displayName)
        assertEquals(ManualBackupSummary.from(sample()), ManualBackupSummary.from(decoded))
    }

    @Test fun rejectsOversizedEscapedStringBeforeDeserializing() {
        val valid = String(ManualBackupCodec.encode(sample()), Charsets.UTF_8)
        val malicious = valid.replace("Átleta Ω", "\\u0061".repeat(MANUAL_BACKUP_MAX_STRING_CHARS + 1))
        val error = assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.decode(malicious) }
        assertTrue(error.message?.contains("string limit", ignoreCase = true) == true)
    }

    // --- F-V-P (Room 11) -----------------------------------------------------------------------

    @Test fun fvpTablesRoundTripWithEveryColumnAndSummaryCount() {
        val document = fvpSample()
        val decoded = ManualBackupCodec.decode(ManualBackupCodec.encode(document))
        assertEquals(document.tables.fvpAssessments, decoded.tables.fvpAssessments)
        assertEquals(document.tables.fvpConditions, decoded.tables.fvpConditions)
        assertEquals(document.tables.fvpAttempts, decoded.tables.fvpAttempts)
        assertEquals(14, decoded.sourceRoomSchemaVersion)
        assertEquals(MANUAL_BACKUP_ROOM_VERSION, decoded.sourceRoomSchemaVersion)
        assertEquals(1, ManualBackupSummary.from(decoded).fvpAssessmentCount)
        assertEquals(2, decoded.tables.fvpConditions.size)
        assertEquals(3, decoded.tables.fvpAttempts.size)
        // El perfil coherente sobrevive el round-trip con el par exacto (sfvNsPerM = -slopeFv): el
        // fixture ya no codifica el par imposible -300/-300 (regresion W3.1).
        val root = decoded.tables.fvpAssessments.single()
        assertEquals(-300.0, root.slopeFv, 0.0)
        assertEquals(300.0, root.sfvNsPerM, 0.0)
        assertEquals(-root.slopeFv, root.sfvNsPerM, 0.0)
        // La ficha de auditoría conserva nullables, booleanos y vínculos de origen.
        val attempt = decoded.tables.fvpAttempts.single { it.id == 7_201L }
        assertFalse(attempt.valid)
        assertEquals("SHORT_FLIGHT", attempt.exclusionReason)
        assertEquals(9_000_000_000L, decoded.tables.fvpAttempts.single { it.id == 7_200L }.sourceAssessmentId)
        assertEquals(null, decoded.tables.fvpAttempts.single { it.id == 7_201L }.flightTimeS)
    }

    /**
     * W3.1: la validacion del backup es una frontera de integridad mas (un JSON editado a mano
     * puede traer cualquier numero). La invariante del motor `sfvNsPerM == -slopeFv` es dura, igual
     * que en `FvpDao.save`: sin ella un backup podria inyectar un perfil imposible en la base.
     */
    @Test fun rejectsFvpRowsWhereSfvIsNotThePositiveMagnitudeOfTheSlope() {
        val base = fvpSample()
        val assessment = base.tables.fvpAssessments.single()
        val impossiblePairs = listOf(
            // Ambos negativos: el par que traia el fixture antes de la correccion.
            assessment.copy(slopeFv = -300.0, sfvNsPerM = -300.0),
            // Ambos positivos: la pendiente fisica F-V es siempre negativa.
            assessment.copy(slopeFv = 300.0, sfvNsPerM = 300.0),
            // Misma direccion pero magnitud distinta: tampoco es la misma pendiente.
            assessment.copy(slopeFv = -300.0, sfvNsPerM = 299.9),
        )
        impossiblePairs.forEach { row ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ManualBackupCodec.encode(base.copy(tables = base.tables.copy(fvpAssessments = listOf(row))))
            }
            assertTrue(
                "el rechazo debe explicar la invariante de signo: ${error.message}",
                error.message.orEmpty().contains("sfvNsPerM") && error.message.orEmpty().contains("slopeFv"),
            )
        }
        // El mismo backup con el par coherente se valida y sobrevive el round-trip sin cambios.
        val roundTripped = ManualBackupCodec.decode(ManualBackupCodec.encode(base)).tables.fvpAssessments.single()
        assertEquals(-300.0, roundTripped.slopeFv, 0.0)
        assertEquals(300.0, roundTripped.sfvNsPerM, 0.0)
        assertEquals(-roundTripped.slopeFv, roundTripped.sfvNsPerM, 0.0)
    }

    /**
     * Gate B3 en la frontera de BACKUP: la regla de dominio "un intento NO valido declara motivo de
     * exclusion" la exige `checkSeries` (y con ella `FvpRepository.save`), pero restaurar un backup
     * inserta las filas en crudo. Sin esta comprobacion un JSON editado a mano (o de otra
     * instalacion) restauraria un descarte sin justificar, que ademas degradaria la fila a
     * `RECALCULATION_UNAVAILABLE` en cuanto se recalcule.
     */
    @Test fun rejectsInvalidFvpAttemptsWithoutExclusionReason() {
        val base = fvpSample()
        val excludedAttemptId = 7_201L
        val excluded = base.tables.fvpAttempts.single { it.id == excludedAttemptId }
        assertFalse("el fixture ya trae un descarte tecnico CON motivo", excluded.valid)
        assertEquals("SHORT_FLIGHT", excluded.exclusionReason)

        val withoutReason: List<String?> = listOf(null, "", " ", "   \n")
        withoutReason.forEach { reason ->
            val document = base.copy(
                tables = base.tables.copy(
                    fvpAttempts = base.tables.fvpAttempts.map {
                        if (it.id == excludedAttemptId) it.copy(exclusionReason = reason) else it
                    },
                ),
            )

            val error = assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(document) }
            assertTrue(
                "el rechazo debe nombrar el motivo ausente y no otra validacion (reason=$reason): ${error.message}",
                error.message.orEmpty().contains("exclusionReason"),
            )
        }

        // El mismo documento con motivo sigue validando y sobrevive el round-trip; el intento
        // VALIDO sin motivo no se toca (la regla solo mira los NO validos).
        val decoded = ManualBackupCodec.decode(ManualBackupCodec.encode(base))
        assertEquals("SHORT_FLIGHT", decoded.tables.fvpAttempts.single { it.id == excludedAttemptId }.exclusionReason)
        val valid = decoded.tables.fvpAttempts.single { it.id == 7_200L }
        assertTrue("un intento valido no necesita motivo de exclusion", valid.valid)
        assertEquals(null, valid.exclusionReason)
    }

    @Test fun fvpListsAreSortedDeterministicallyInThePayload() {
        val base = fvpSample()
        val shuffled = base.copy(
            tables = base.tables.copy(
                fvpConditions = base.tables.fvpConditions.reversed(),
                fvpAttempts = base.tables.fvpAttempts.reversed(),
            ),
        )
        assertEquals(String(ManualBackupCodec.encode(base), Charsets.UTF_8), String(ManualBackupCodec.encode(shuffled), Charsets.UTF_8))
        assertEquals(listOf("0kg", "20kg"), ManualBackupCodec.decode(ManualBackupCodec.encode(shuffled)).tables.fvpConditions.map { it.conditionKey })
    }

    @Test fun payloadWithoutFvpKeysStillDecodesWithEmptyLists() {
        val legacyJson = String(ManualBackupCodec.encode(sample()), Charsets.UTF_8)
            .replace(",\"fvpAssessments\":[]", "")
            .replace(",\"fvpConditions\":[]", "")
            .replace(",\"fvpAttempts\":[]", "")
        val decoded = ManualBackupCodec.decode(legacyJson)
        assertTrue(decoded.tables.fvpAssessments.isEmpty())
        assertTrue(decoded.tables.fvpConditions.isEmpty())
        assertTrue(decoded.tables.fvpAttempts.isEmpty())
        assertEquals(ManualBackupSummary.from(sample()), ManualBackupSummary.from(decoded))
    }

    @Test fun rejectsMalformedFvpPayloads() {
        val base = fvpSample()
        val assessment = base.tables.fvpAssessments.single()
        val invalid = listOf(
            base.copy(tables = base.tables.copy(fvpConditions = base.tables.fvpConditions.map { it.copy(assessmentId = 404L) })),
            base.copy(tables = base.tables.copy(fvpAttempts = base.tables.fvpAttempts.map { it.copy(conditionId = 404L) })),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(id = 0L)))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(sessionKey = " ")))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(protocolId = "BANANA")))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(bodyMassSource = " ")))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(pushOffMode = " ")))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(pushOffSource = " ")))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(algorithmVersion = " ")))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(methodVersion = " ")))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(conditionCount = 0)))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(r2 = Double.NaN)))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(warningsCsv = "w".repeat(MANUAL_BACKUP_MAX_WARNINGS_CSV_CHARS + 1))))),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(athleteId = 404L)))),
            base.copy(tables = base.tables.copy(fvpConditions = base.tables.fvpConditions.map { it.copy(conditionKey = " ") })),
            base.copy(tables = base.tables.copy(fvpConditions = base.tables.fvpConditions.map { it.copy(assessmentId = 1L) })),
            base.copy(tables = base.tables.copy(fvpAttempts = base.tables.fvpAttempts.map { it.copy(method = " ") })),
            base.copy(tables = base.tables.copy(fvpAttempts = base.tables.fvpAttempts.map { it.copy(heightM = Double.POSITIVE_INFINITY) })),
            // Coherencia raiz-hijos: sin condiciones, o con conditionCount que no cuadra.
            base.copy(tables = base.tables.copy(fvpConditions = emptyList(), fvpAttempts = emptyList())),
            base.copy(tables = base.tables.copy(fvpAssessments = listOf(assessment.copy(conditionCount = 7)))),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(it) } }
        // Las tablas F-V-P nacen en Room 11: declararse 9/10 con ellas es un payload malformado.
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(base.copy(sourceRoomSchemaVersion = 10)) }
        assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(base.copy(sourceRoomSchemaVersion = 9)) }
        // Un payload Room 10 sin datos F-V-P sigue restaurando.
        val room10 = base.copy(
            sourceRoomSchemaVersion = 10,
            tables = base.tables.copy(fvpAssessments = emptyList(), fvpConditions = emptyList(), fvpAttempts = emptyList()),
        )
        assertTrue(ManualBackupCodec.decode(ManualBackupCodec.encode(room10)).tables.fvpAssessments.isEmpty())
    }

    // --- B2: procedencia Room 12 en el backup ---------------------------------------------------

    /**
     * B2: las 20 columnas de procedencia viajan 1:1 en el documento y en los mapeadores
     * `entity()` que usa el restore; los valores conocidos vuelven exactos y los desconocidos
     * siguen siendo UNKNOWN/NULL (nunca se derivan de fechas, fps ni perfiles).
     */
    @Test fun room12FvpProvenanceRoundTripsThroughDocumentAndEntityMappers() {
        val document = fvpSample()
        val decoded = ManualBackupCodec.decode(ManualBackupCodec.encode(document))
        assertEquals(document.tables.fvpAssessments, decoded.tables.fvpAssessments)
        assertEquals(document.tables.fvpConditions, decoded.tables.fvpConditions)
        assertEquals(document.tables.fvpAttempts, decoded.tables.fvpAttempts)

        val root = decoded.tables.fvpAssessments.single()
        assertEquals(1_700_000_000_500L, root.acquiredAt)
        assertEquals("SAME_ACQUISITION", root.acquisitionContextKind)
        assertEquals("ctx-1", root.acquisitionContextKey)
        assertEquals("WEIGHED", root.bodyMassMeasurement)
        assertEquals(1_700_000_000_400L, root.bodyMassMeasuredAt)

        val weighedLoad = decoded.tables.fvpConditions.single { it.conditionKey == "0kg" }
        val nominalLoad = decoded.tables.fvpConditions.single { it.conditionKey == "20kg" }
        assertEquals("WEIGHED", weighedLoad.addedLoadSource)
        assertEquals(1_700_000_000_600L, weighedLoad.addedLoadMeasuredAt)
        assertEquals("NOMINAL", nominalLoad.addedLoadSource)
        assertEquals(null, nominalLoad.addedLoadMeasuredAt)

        val reused = decoded.tables.fvpAttempts.single { it.id == 7_200L }
        assertEquals(1_690_000_000_000L, reused.sourceRecordedAt)
        assertEquals(4_000_000_000L, reused.sourceAthleteId)
        assertEquals(1_700_000_000_700L, reused.acquiredAt)
        assertEquals(1_700_000_000_800L, reused.reuseConfirmedAt)
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, reused.reuseConfirmationVersion)
        assertEquals(1_000_000L, reused.takeoffPtsUs)
        assertEquals(999_000L, reused.takeoffPreviousPtsUs)
        assertEquals(1_001_000L, reused.takeoffNextPtsUs)
        assertEquals(1_500_000L, reused.landingPtsUs)
        assertEquals(1_499_000L, reused.landingPreviousPtsUs)
        assertEquals(1_501_000L, reused.landingNextPtsUs)
        assertEquals("IDENTITY_VERIFIED", reused.timeMapping)
        assertEquals("evidence-1", reused.timeMappingEvidenceKey)

        val unknownAttempt = decoded.tables.fvpAttempts.single { it.id == 7_201L }
        assertEquals("UNKNOWN", unknownAttempt.timeMapping)
        assertEquals(null, unknownAttempt.timeMappingEvidenceKey)
        assertEquals(null, unknownAttempt.reuseConfirmationVersion)
        assertEquals(null, unknownAttempt.takeoffPtsUs)
        assertEquals(null, unknownAttempt.landingPtsUs)

        // 1:1 hacia las entidades de Room: es lo que inserta el restore.
        val rootEntity = root.entity()
        assertEquals(1_700_000_000_500L, rootEntity.acquiredAt)
        assertEquals("SAME_ACQUISITION", rootEntity.acquisitionContextKind)
        assertEquals("ctx-1", rootEntity.acquisitionContextKey)
        assertEquals("WEIGHED", rootEntity.bodyMassMeasurement)
        assertEquals(1_700_000_000_400L, rootEntity.bodyMassMeasuredAt)
        val weighedEntity = weighedLoad.entity()
        assertEquals("WEIGHED", weighedEntity.addedLoadSource)
        assertEquals(1_700_000_000_600L, weighedEntity.addedLoadMeasuredAt)
        assertEquals("NOMINAL", nominalLoad.entity().addedLoadSource)
        assertEquals(null, nominalLoad.entity().addedLoadMeasuredAt)
        val reusedEntity = reused.entity()
        assertEquals(1_690_000_000_000L, reusedEntity.sourceRecordedAt)
        assertEquals(4_000_000_000L, reusedEntity.sourceAthleteId)
        assertEquals(1_700_000_000_700L, reusedEntity.acquiredAt)
        assertEquals(1_700_000_000_800L, reusedEntity.reuseConfirmedAt)
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, reusedEntity.reuseConfirmationVersion)
        assertEquals(1_000_000L, reusedEntity.takeoffPtsUs)
        assertEquals(999_000L, reusedEntity.takeoffPreviousPtsUs)
        assertEquals(1_001_000L, reusedEntity.takeoffNextPtsUs)
        assertEquals(1_500_000L, reusedEntity.landingPtsUs)
        assertEquals(1_499_000L, reusedEntity.landingPreviousPtsUs)
        assertEquals(1_501_000L, reusedEntity.landingNextPtsUs)
        assertEquals("IDENTITY_VERIFIED", reusedEntity.timeMapping)
        assertEquals("evidence-1", reusedEntity.timeMappingEvidenceKey)
        val unknownEntity = unknownAttempt.entity()
        assertEquals("UNKNOWN", unknownEntity.timeMapping)
        assertEquals(null, unknownEntity.timeMappingEvidenceKey)
        assertEquals(null, unknownEntity.sourceRecordedAt)
        assertEquals(null, unknownEntity.sourceAthleteId)
        assertEquals(null, unknownEntity.acquiredAt)
        assertEquals(null, unknownEntity.reuseConfirmedAt)
        assertEquals(null, unknownEntity.reuseConfirmationVersion)
        assertEquals(null, unknownEntity.takeoffPtsUs)
        assertEquals(null, unknownEntity.landingPtsUs)
        assertEquals(1_680_000_000_000L, decoded.tables.fvpAttempts.single { it.id == 7_202L }.entity().sourceRecordedAt)
    }

    /**
     * F2: F-V-P nace en Room 11, asi que un documento declarado 11 CON filas F-V-P es legitimo
     * aunque el esquema actual sea 12. Ademas, las claves de procedencia ausentes (documento
     * escrito por la app de Room 11) decodifican como UNKNOWN/NULL y no rechazan las filas.
     */
    @Test fun room11DocumentWithFvpRowsAndWithoutProvenanceKeysStillDecodesAndRestores() {
        // Se parte de un documento 12 real, se le quitan las 20 claves y se declara 11: es lo que
        // podia escribir la app Room 11 (que no tenia columnas de procedencia).
        val v12Json = String(ManualBackupCodec.encode(fvpSample().copy(sourceRoomSchemaVersion = 12)), Charsets.UTF_8)
        val json = legacyJsonWithoutProvenance(v12Json)
            .replace("\"sourceRoomSchemaVersion\":12", "\"sourceRoomSchemaVersion\":11")
        assertFalse("el JSON de Room 11 no lleva acquisitionContextKind", json.contains("\"acquisitionContextKind\""))
        assertFalse(json.contains("\"acquisitionContextKey\""))
        assertFalse(json.contains("\"addedLoadSource\""))
        assertFalse(json.contains("\"timeMappingEvidenceKey\""))
        assertFalse(json.contains("\"reuseConfirmationVersion\""))

        val decoded = ManualBackupCodec.decode(json)
        assertEquals(11, decoded.sourceRoomSchemaVersion)
        assertEquals(1, decoded.tables.fvpAssessments.size)
        assertEquals(2, decoded.tables.fvpConditions.size)
        assertEquals(3, decoded.tables.fvpAttempts.size)
        val root = decoded.tables.fvpAssessments.single()
        assertEquals("UNKNOWN", root.acquisitionContextKind)
        assertEquals(null, root.acquisitionContextKey)
        assertEquals("UNKNOWN", root.bodyMassMeasurement)
        assertEquals(null, root.acquiredAt)
        assertEquals(null, root.bodyMassMeasuredAt)
        assertTrue(decoded.tables.fvpConditions.all { it.addedLoadSource == "UNKNOWN" && it.addedLoadMeasuredAt == null })
        assertTrue(
            decoded.tables.fvpAttempts.all {
                it.timeMapping == "UNKNOWN" && it.timeMappingEvidenceKey == null && it.sourceRecordedAt == null &&
                    it.sourceAthleteId == null && it.acquiredAt == null && it.reuseConfirmedAt == null &&
                    it.reuseConfirmationVersion == null && it.takeoffPtsUs == null && it.takeoffPreviousPtsUs == null &&
                    it.takeoffNextPtsUs == null && it.landingPtsUs == null && it.landingPreviousPtsUs == null &&
                    it.landingNextPtsUs == null
            },
        )
        // El restore de un documento 11 escribe UNKNOWN/NULL en las entidades (honesto: el formato
        // no tenia esas claves) y conserva las filas.
        val restoredRoot = root.entity()
        assertEquals("UNKNOWN", restoredRoot.acquisitionContextKind)
        assertEquals(null, restoredRoot.acquisitionContextKey)
        assertEquals("UNKNOWN", restoredRoot.bodyMassMeasurement)
        assertEquals(null, restoredRoot.acquiredAt)
        assertEquals(null, restoredRoot.bodyMassMeasuredAt)
        assertTrue(decoded.tables.fvpAttempts.all { it.entity().timeMapping == "UNKNOWN" && it.entity().takeoffPtsUs == null })
        // Y se puede reescribir sin rechazo (el documento ya es un 11 valido con FVP).
        ManualBackupCodec.decode(ManualBackupCodec.encode(decoded))
    }

    @Test fun legacyRoom9AndRoom10DocumentsWithoutFvpRowsStillValidate() {
        listOf(9, 10).forEach { room ->
            val legacy = sample().copy(sourceRoomSchemaVersion = room)
            assertEquals(room, ManualBackupCodec.decode(ManualBackupCodec.encode(legacy)).sourceRoomSchemaVersion)
        }
        // Las filas F-V-P nacen en Room 11: declararse 9/10 con ellas sigue siendo malformado.
        listOf(9, 10).forEach { room ->
            assertThrows(IllegalArgumentException::class.java) {
                ManualBackupCodec.encode(fvpSample().copy(sourceRoomSchemaVersion = room))
            }
        }
        // 11 con filas F-V-P si es valido (el gate no sigue al esquema actual). Pero la procedencia
        // nace en Room 12: un documento declarado 11 que la traiga es un hibrido y se rechaza.
        val unknownProvenance11 = fvpSample().copy(
            sourceRoomSchemaVersion = 11,
            tables = fvpSample().tables.copy(
                fvpAssessments = fvpSample().tables.fvpAssessments.map {
                    it.copy(acquiredAt = null, acquisitionContextKind = "UNKNOWN", acquisitionContextKey = null, bodyMassMeasurement = "UNKNOWN", bodyMassMeasuredAt = null)
                },
                fvpConditions = fvpSample().tables.fvpConditions.map { it.copy(addedLoadSource = "UNKNOWN", addedLoadMeasuredAt = null) },
                fvpAttempts = fvpSample().tables.fvpAttempts.map {
                    it.copy(
                        sourceRecordedAt = null, sourceAthleteId = null, acquiredAt = null, reuseConfirmedAt = null,
                        reuseConfirmationVersion = null, takeoffPtsUs = null, takeoffPreviousPtsUs = null,
                        takeoffNextPtsUs = null, landingPtsUs = null, landingPreviousPtsUs = null,
                        landingNextPtsUs = null, timeMapping = "UNKNOWN", timeMappingEvidenceKey = null,
                    )
                },
            ),
        )
        ManualBackupCodec.encode(unknownProvenance11)
        val hybrid = assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(fvpSample().copy(sourceRoomSchemaVersion = 11))
        }
        assertTrue(
            "un 11 con procedencia debe rechazarse: ${hybrid.message}",
            hybrid.message.orEmpty().contains("cannot carry Room 12 provenance"),
        )
    }

    @Test fun rejectsUnsupportedFutureRoomSchemaVersions() {
        // 14 es la version actual (la barrera V2 vive en el DAO, no en el formato): solo se rechaza
        // lo que no es ni actual ni un linaje legacy explicito.
        listOf(15, 8, 0, -1).forEach { room ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ManualBackupCodec.encode(sample().copy(sourceRoomSchemaVersion = room))
            }
            assertTrue("Room $room debe rechazarse: ${error.message}", error.message.orEmpty().contains("Room schema version"))
        }
        assertEquals(14, ManualBackupCodec.decode(ManualBackupCodec.encode(sample().copy(sourceRoomSchemaVersion = 14))).sourceRoomSchemaVersion)
    }

    @Test fun rejectsInvalidFvpProvenanceValues() {
        val base = fvpSample()
        val root = base.tables.fvpAssessments.single()
        val weighedLoad = base.tables.fvpConditions.single { it.conditionKey == "0kg" }
        val reused = base.tables.fvpAttempts.single { it.id == 7_200L }
        fun withRoot(row: BackupFvpAssessment) = base.copy(tables = base.tables.copy(fvpAssessments = listOf(row)))
        fun withCondition(row: BackupFvpCondition) =
            base.copy(tables = base.tables.copy(fvpConditions = base.tables.fvpConditions.map { if (it.id == weighedLoad.id) row else it }))
        fun withAttempt(row: BackupFvpAttempt) =
            base.copy(tables = base.tables.copy(fvpAttempts = base.tables.fvpAttempts.map { if (it.id == reused.id) row else it }))

        val cases = listOf(
            withRoot(root.copy(acquisitionContextKind = "MAYBE")) to "acquisitionContextKind",
            withRoot(root.copy(bodyMassMeasurement = "MAYBE")) to "bodyMassMeasurement",
            withRoot(root.copy(acquisitionContextKey = null)) to "acquisitionContextKey",
            withRoot(root.copy(acquisitionContextKey = " ")) to "acquisitionContextKey",
            withRoot(root.copy(acquisitionContextKey = "k".repeat(129))) to "acquisitionContextKey",
            withRoot(root.copy(acquiredAt = -1L)) to "acquiredAt",
            withRoot(root.copy(bodyMassMeasuredAt = -1L)) to "bodyMassMeasuredAt",
            withCondition(weighedLoad.copy(addedLoadSource = "MAYBE")) to "addedLoadSource",
            withCondition(weighedLoad.copy(addedLoadMeasuredAt = -1L)) to "addedLoadMeasuredAt",
            withAttempt(reused.copy(timeMapping = "MAYBE")) to "timeMapping",
            withAttempt(reused.copy(timeMapping = "IDENTITY_VERIFIED", timeMappingEvidenceKey = null)) to "timeMappingEvidenceKey",
            withAttempt(reused.copy(timeMappingEvidenceKey = "")) to "timeMappingEvidenceKey",
            withAttempt(reused.copy(timeMappingEvidenceKey = "k".repeat(129))) to "timeMappingEvidenceKey",
            withAttempt(reused.copy(sourceAthleteId = 0L)) to "sourceAthleteId",
            withAttempt(reused.copy(sourceRecordedAt = -1L)) to "sourceRecordedAt",
            withAttempt(reused.copy(acquiredAt = -1L)) to "acquiredAt",
            withAttempt(reused.copy(reuseConfirmedAt = null)) to "reuseConfirmedAt",
            withAttempt(reused.copy(reuseConfirmationVersion = null)) to "reuseConfirmationVersion",
            withAttempt(reused.copy(reuseConfirmationVersion = "fvp-reuse-2")) to "reuseConfirmationVersion",
            withAttempt(reused.copy(takeoffPtsUs = -1L)) to "takeoffPtsUs",
            withAttempt(reused.copy(takeoffPreviousPtsUs = reused.takeoffPtsUs)) to "takeoffPreviousPtsUs",
            withAttempt(reused.copy(takeoffNextPtsUs = reused.takeoffPtsUs)) to "takeoffNextPtsUs",
            withAttempt(reused.copy(takeoffPtsUs = null, takeoffPreviousPtsUs = 1L, takeoffNextPtsUs = null)) to "takeoffPreviousPtsUs",
            withAttempt(reused.copy(landingPtsUs = reused.takeoffPtsUs)) to "landingPtsUs",
            withAttempt(reused.copy(landingPreviousPtsUs = reused.landingPtsUs)) to "landingPreviousPtsUs",
            withAttempt(reused.copy(landingNextPtsUs = reused.landingPtsUs)) to "landingNextPtsUs",
            withAttempt(reused.copy(landingPtsUs = null, landingPreviousPtsUs = 1L, landingNextPtsUs = null)) to "landingPreviousPtsUs",
        )
        cases.forEach { (document, token) ->
            val error = assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.encode(document) }
            assertTrue("se esperaba un rechazo que nombre '$token', fue: ${error.message}", error.message.orEmpty().contains(token))
        }
        // Nada se infiere: el mismo documento con todas las columnas desconocidas valida y viaja.
        ManualBackupCodec.encode(unknownProvenanceSample())
    }

    @Test fun encodedRoom14DocumentsDeclareTheSchemaAndCarryTheProvenanceKeys() {
        assertEquals(14, MANUAL_BACKUP_ROOM_VERSION)
        assertEquals(11, MANUAL_BACKUP_FVP_INTRODUCED_ROOM_VERSION)
        assertEquals(12, MANUAL_BACKUP_PROVENANCE_INTRODUCED_ROOM_VERSION)
        assertEquals(13, MANUAL_BACKUP_ANGLE_INTRODUCED_ROOM_VERSION)
        // Las tablas V2 nacen en Room 14, pero el formato v1 no las modela: la barrera es del DAO.
        assertEquals(14, MANUAL_BACKUP_V2_INTRODUCED_ROOM_VERSION)
        val json = String(ManualBackupCodec.encode(fvpSample()), Charsets.UTF_8)
        assertTrue(json.contains("\"sourceRoomSchemaVersion\":14"))
        assertTrue(json.contains("\"acquisitionContextKind\":\"SAME_ACQUISITION\""))
        assertTrue(json.contains("\"addedLoadSource\":\"WEIGHED\""))
        assertTrue(json.contains("\"timeMappingEvidenceKey\":\"evidence-1\""))
        // `encodeDefaults=true`: hasta lo desconocido se emite explicitamente en un documento 13.
        val unknown = unknownProvenanceSample()
        val unknownJson = String(ManualBackupCodec.encode(unknown), Charsets.UTF_8)
        assertTrue(unknownJson.contains("\"acquisitionContextKind\":\"UNKNOWN\""))
        assertTrue(unknownJson.contains("\"bodyMassMeasurement\":\"UNKNOWN\""))
        assertTrue(unknownJson.contains("\"addedLoadSource\":\"UNKNOWN\""))
        assertTrue(unknownJson.contains("\"timeMapping\":\"UNKNOWN\""))
        assertTrue(unknownJson.contains("\"timeMappingEvidenceKey\":null"))
        assertTrue(unknownJson.contains("\"acquiredAt\":null"))
        val decoded = ManualBackupCodec.decode(unknownJson)
        assertEquals(unknown.tables.fvpAssessments, decoded.tables.fvpAssessments)
        assertEquals(unknown.tables.fvpConditions, decoded.tables.fvpConditions)
        assertEquals(unknown.tables.fvpAttempts, decoded.tables.fvpAttempts)
        val decodedAttempt = decoded.tables.fvpAttempts.single { it.id == 7_200L }.entity()
        assertEquals("UNKNOWN", decodedAttempt.timeMapping)
        assertEquals(null, decodedAttempt.timeMappingEvidenceKey)
        assertEquals(null, decodedAttempt.acquiredAt)
    }

    // --- Angulos manuales (Room 13, vigentes en Room 14) -----------------------------------------

    /**
     * S3b: un documento solo-angulos viaja completo. Se preserva la geometria (dimensiones
     * codificadas y de presentacion, rotacion, frame, PTS, revision, version de geometria), la
     * identidad (id, sessionKey, atleta) y la presentacion de cada marca (nombre, color, visible,
     * ordinal); y NO se emite identidad privada de la fuente (URI, identidad logica, hash).
     */
    @Test fun angleOnlyDocumentRoundTripsWithGeometryAndMarks() {
        val document = angleSample()
        val json = String(ManualBackupCodec.encode(document), Charsets.UTF_8)
        assertTrue(json.contains("\"sourceRoomSchemaVersion\":14"))
        assertTrue(json.contains("\"angleAnalyses\""))
        assertTrue(json.contains("\"angleMarks\""))
        assertFalse("la URI privada no viaja en el backup", json.contains("videoUri"))
        assertFalse(json.contains("sourceIdentity"))
        assertFalse(json.contains("sourceHash"))

        val decoded = ManualBackupCodec.decode(json)
        assertEquals(document.tables.angleAnalyses, decoded.tables.angleAnalyses)
        assertEquals(document.tables.angleMarks, decoded.tables.angleMarks)
        assertEquals(1, ManualBackupSummary.from(decoded).angleAnalysisCount)
        assertEquals(1, decoded.tables.angleAnalyses.size)
        assertEquals(2, decoded.tables.angleMarks.size)

        // 1:1 hacia las entidades de Room: la geometria se conserva y la identidad privada es NULL.
        val analysis = decoded.tables.angleAnalyses.single()
        val analysisEntity = analysis.entity()
        assertEquals(9_000L, analysisEntity.id)
        assertEquals("angle-key", analysisEntity.sessionKey)
        assertEquals(null, analysisEntity.athleteId)
        assertEquals(2, analysisEntity.revision)
        assertEquals(1920, analysisEntity.encodedWidth)
        assertEquals(1080, analysisEntity.encodedHeight)
        assertEquals(1080, analysisEntity.presentationWidth)
        assertEquals(1920, analysisEntity.presentationHeight)
        assertEquals(90, analysisEntity.rotationDegrees)
        assertEquals(42, analysisEntity.frameIndex)
        assertEquals(1_400_000L, analysisEntity.sourcePtsUs)
        assertEquals(1, analysisEntity.geometryVersion)
        assertEquals(null, analysisEntity.videoUri)
        assertEquals(null, analysisEntity.sourceIdentity)
        assertEquals(null, analysisEntity.sourceHash)

        val mark = decoded.tables.angleMarks.single { it.id == 9_101L }
        assertEquals(9_000L, mark.analysisId)
        assertEquals(1, mark.ordinal)
        assertEquals("Rodilla", mark.name)
        assertEquals(0x803366CC.toInt(), mark.colorArgb)
        assertEquals(false, mark.visible)
        val markEntity = mark.entity()
        assertEquals(1, markEntity.ordinal)
        assertEquals("Rodilla", markEntity.name)
        assertEquals(0x803366CC.toInt(), markEntity.colorArgb)
        assertEquals(false, markEntity.visible)
        assertEquals(100.5, markEntity.pointAX, 0.0)
        assertEquals(200.25, markEntity.pointAY, 0.0)
        assertEquals(300.0, markEntity.pointBX, 0.0)
        assertEquals(100.0, markEntity.pointBY, 0.0)
        assertEquals(250.0, markEntity.pointCX, 0.0)
        assertEquals(300.0, markEntity.pointCY, 0.0)
    }

    /**
     * La subida a Room 14 no puede romper el linaje historico: un documento 12 con las columnas de
     * procedencia F-V-P (B0) sigue decodificando y reescribiendose como 12.
     */
    @Test fun room12DocumentWithFvpProvenanceStillDecodesAfterTheRoom14Bump() {
        val json = String(ManualBackupCodec.encode(fvpSample()), Charsets.UTF_8)
            .replace("\"sourceRoomSchemaVersion\":14", "\"sourceRoomSchemaVersion\":12")
        val decoded = ManualBackupCodec.decode(json)
        assertEquals(12, decoded.sourceRoomSchemaVersion)
        assertEquals("SAME_ACQUISITION", decoded.tables.fvpAssessments.single().acquisitionContextKind)
        assertEquals("IDENTITY_VERIFIED", decoded.tables.fvpAttempts.single { it.id == 7_200L }.timeMapping)
        assertTrue(decoded.tables.angleAnalyses.isEmpty() && decoded.tables.angleMarks.isEmpty())
        val rewritten = ManualBackupCodec.decode(ManualBackupCodec.encode(decoded))
        assertEquals(12, rewritten.sourceRoomSchemaVersion)
        assertEquals(decoded.tables.fvpAttempts, rewritten.tables.fvpAttempts)
    }

    @Test fun legacyRoom9To12DocumentsWithoutAngleRowsStillDecode() {
        listOf(9, 10, 11, 12).forEach { room ->
            val legacy = sample().copy(sourceRoomSchemaVersion = room)
            val json = String(ManualBackupCodec.encode(legacy), Charsets.UTF_8)
            // Sin filas de angulos el payload conserva la forma previa (claves vacias, nada que
            // validar): la nueva puerta de geometria no cambia los documentos legacy.
            assertTrue(json.contains("\"angleAnalyses\":[]") && json.contains("\"angleMarks\":[]"))
            val decoded = ManualBackupCodec.decode(json)
            assertEquals(room, decoded.sourceRoomSchemaVersion)
            assertTrue(decoded.tables.angleAnalyses.isEmpty() && decoded.tables.angleMarks.isEmpty())
            assertEquals(ManualBackupSummary.from(legacy), ManualBackupSummary.from(decoded))
        }
    }

    /**
     * Compatibilidad de linaje al subir el esquema a Room 14: un JSON escrito por la app Room 13
     * (angulos legacy ya presentes, sin claves V2) sigue decodificando, conserva su declaracion 13
     * al reescribirse y no pierde filas. La barrera V2 de [ManualBackupDao] es de base de datos, no
     * de formato, asi que aqui el documento 13 atraviesa encode/decode sin rechazo.
     */
    @Test fun legacyRoom13JsonStillDecodesAndRewritesAsRoom13() {
        // Con filas de angulos legacy (lo que ya escribia Room 13).
        val room13AnglesJson = String(ManualBackupCodec.encode(angleSample()), Charsets.UTF_8)
            .replace("\"sourceRoomSchemaVersion\":14", "\"sourceRoomSchemaVersion\":13")
        val angleDocument = ManualBackupCodec.decode(room13AnglesJson)
        assertEquals(13, angleDocument.sourceRoomSchemaVersion)
        assertEquals(angleSample().tables.angleAnalyses, angleDocument.tables.angleAnalyses)
        assertEquals(angleSample().tables.angleMarks, angleDocument.tables.angleMarks)
        val rewrittenAngles = ManualBackupCodec.decode(ManualBackupCodec.encode(angleDocument))
        assertEquals(13, rewrittenAngles.sourceRoomSchemaVersion)
        assertEquals(angleDocument.tables, rewrittenAngles.tables)
        assertTrue(String(ManualBackupCodec.encode(angleDocument), Charsets.UTF_8).contains("\"sourceRoomSchemaVersion\":13"))

        // Un 13 con filas F-V-P y su procedencia (nacida en 12) tambien es un documento legitimo.
        val room13FvpJson = String(ManualBackupCodec.encode(fvpSample()), Charsets.UTF_8)
            .replace("\"sourceRoomSchemaVersion\":14", "\"sourceRoomSchemaVersion\":13")
        val fvpDocument = ManualBackupCodec.decode(room13FvpJson)
        assertEquals(13, fvpDocument.sourceRoomSchemaVersion)
        assertEquals(fvpSample().tables.fvpAssessments, fvpDocument.tables.fvpAssessments)
        assertEquals(fvpSample().tables.fvpAttempts, fvpDocument.tables.fvpAttempts)
        assertEquals(13, ManualBackupCodec.decode(ManualBackupCodec.encode(fvpDocument)).sourceRoomSchemaVersion)
    }

    /**
     * El formato v1 no puede transportar el agregado V2 (ni media) aunque se declare Room 14: no hay
     * claves para esas tablas y el codec es estricto. Esa es la mitad "de formato" del fail-closed;
     * la otra mitad (base con filas V2) la cubre [ManualBackupDao].
     */
    @Test fun v1JsonCannotCarryAngleV2RowsNorMediaInAnyDeclaredSchema() {
        val currentJson = String(ManualBackupCodec.encode(angleSample()), Charsets.UTF_8)
        assertFalse("el formato v1 no tiene clave para las tablas V2", currentJson.contains("angleAnalysisV2"))
        assertFalse(currentJson.contains("angle_analysis_v2"))
        val elementNames = (0 until ManualBackupTables.serializer().descriptor.elementsCount)
            .map { ManualBackupTables.serializer().descriptor.getElementName(it) }
        assertTrue(elementNames.containsAll(listOf("angleAnalyses", "angleMarks")))
        assertTrue("el formato v1 no declara ninguna tabla V2", elementNames.none { it.startsWith("angleAnalysisV2") })

        listOf(13, 14).forEach { room ->
            val declared = currentJson.replace("\"sourceRoomSchemaVersion\":14", "\"sourceRoomSchemaVersion\":$room")
            // Una raiz V2 colada en el objeto `tables` se rechaza en el decode (clave desconocida).
            val withV2Root = declared.replaceFirst(
                "\"tables\":{",
                "\"tables\":{\"angleAnalysisV2Roots\":[{\"id\":1,\"analysisFormat\":\"ANGLE_MULTI_FRAME_V1\"," +
                    "\"sessionKey\":\"v2\",\"athleteId\":null,\"createdAt\":1,\"updatedAt\":1,\"revision\":0}],",
            )
            val v2Error = assertThrows(IllegalArgumentException::class.java) { ManualBackupCodec.decode(withV2Root) }
            assertTrue("Room $room con V2 debe rechazarse: ${v2Error.message}", v2Error.message.orEmpty().isNotBlank())
            // Y media/ficheros tampoco: ni la bandera ni una tabla de medios tienen cabida.
            assertThrows(IllegalArgumentException::class.java) {
                ManualBackupCodec.decode(declared.replaceFirst("\"mediaIncluded\":false", "\"mediaIncluded\":true"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ManualBackupCodec.decode(declared.replaceFirst("\"tables\":{", "\"tables\":{\"mediaFiles\":[{\"imageKey\":\"media-1\"}],"))
            }
        }
    }

    /**
     * Diagnostico del rechazo transitorio: el tipo es propio (estado de la base, no de formato) y
     * su cargamento nombra las tres tablas, para que un informe de soporte no dependa del texto.
     */
    @Test fun blockedBarrierExceptionReportsPerTableCounts() {
        val blocked = ManualBackupV2BlockedException(AngleAnalysisV2RowCounts(roots = 2, frames = 0, marks = 3))
        // Es un estado de la base (IllegalStateException), no un error de formato/validacion: quien
        // capture IllegalArgumentException para el decode no debe confundirlos.
        assertTrue(IllegalStateException::class.java.isAssignableFrom(blocked::class.java))
        assertFalse(IllegalArgumentException::class.java.isAssignableFrom(blocked::class.java))
        assertEquals(5, blocked.rows.total)
        assertFalse(blocked.rows.isEmpty)
        val message = blocked.message.orEmpty()
        assertTrue(message, message.contains("temporarily blocked"))
        assertTrue(message, message.contains("angle_analysis_v2_roots=2"))
        assertTrue(message, message.contains("angle_analysis_v2_frames=0"))
        assertTrue(message, message.contains("angle_analysis_v2_marks=3"))
        assertTrue(message, message.contains("no row was replaced"))
        assertTrue(AngleAnalysisV2RowCounts(0, 0, 0).isEmpty)
    }

    /**
     * La validacion del backup es otra frontera de integridad: coherencia schema->forma, IDs/FK,
     * ordinales unicos, geometria finita y dentro del frame post-rotacion, angulo medible, rotacion
     * admitida, raices con hijos y frame/versiones razonables. Nada de esto se acepta en silencio.
     */
    @Test fun rejectsMalformedAnglePayloads() {
        val base = angleSample()
        val analysis = base.tables.angleAnalyses.single()
        val hip = base.tables.angleMarks.single { it.id == 9_100L }
        val knee = base.tables.angleMarks.single { it.id == 9_101L }
        // Frame de un solo pixel (rotacion 0 no intercambia ejes): solo (0,0) es un pixel real.
        val unitAnalysis = analysis.copy(
            encodedWidth = 1, encodedHeight = 1, presentationWidth = 1, presentationHeight = 1,
            rotationDegrees = 0,
        )
        fun withRows(
            analyses: List<BackupAngleAnalysis> = base.tables.angleAnalyses,
            marks: List<BackupAngleMark> = base.tables.angleMarks,
        ) = base.copy(tables = base.tables.copy(angleAnalyses = analyses, angleMarks = marks))

        val cases = listOf(
            withRows(marks = emptyList()) to "has no marks",
            withRows(marks = base.tables.angleMarks + hip.copy(id = 9_999L, analysisId = 7_777L)) to "unknown analysis",
            withRows(marks = base.tables.angleMarks + knee.copy(id = 9_998L)) to "ordinal",
            withRows(marks = base.tables.angleMarks + knee.copy(id = 9_100L, ordinal = 2)) to "angle mark id",
            withRows(analyses = listOf(analysis, analysis.copy(id = 9_001L))) to "sessionKey",
            withRows(analyses = listOf(analysis.copy(id = 0L))) to "angle analysis id",
            withRows(analyses = listOf(analysis, analysis.copy(sessionKey = "other"))) to "Duplicate angle analysis id",
            withRows(marks = listOf(hip.copy(name = "  ")) + knee) to "name",
            withRows(marks = listOf(hip.copy(ordinal = -1)) + knee) to "ordinal",
            withRows(marks = listOf(hip.copy(pointAX = Double.NaN)) + knee) to "finite",
            withRows(marks = listOf(hip.copy(pointCY = Double.POSITIVE_INFINITY)) + knee) to "finite",
            withRows(marks = listOf(hip.copy(pointAX = 1080.5)) + knee) to "presentation frame",
            withRows(marks = listOf(hip.copy(pointBY = -0.1)) + knee) to "presentation frame",
            // El ultimo pixel es width-1/height-1: la dimension en si no es una coordenada valida.
            withRows(marks = listOf(hip.copy(pointAX = 1080.0)) + knee) to "presentation frame",
            withRows(marks = listOf(hip.copy(pointAY = 1920.0)) + knee) to "presentation frame",
            // Geometria degenerada: sin lado no hay angulo, y persistirla guardaria un falso 0.
            withRows(marks = listOf(hip.copy(pointAX = hip.pointBX, pointAY = hip.pointBY)) + knee) to "no measurable angle",
            withRows(marks = listOf(hip.copy(pointCX = hip.pointBX, pointCY = hip.pointBY)) + knee) to "COINCIDENT_VERTEX",
            // -0.0 pasa los limites pero el vector nulo lo detecta AngleGeometry (Double.equals
            // distingue -0.0 de 0.0, asi que la coincidencia no la ve el data class).
            withRows(marks = listOf(hip.copy(pointAX = -0.0, pointAY = -0.0, pointBX = 0.0, pointBY = 0.0)) + knee) to "no measurable angle",
            // Dimensiones unitarias: el limite superior es 0 (sin negativos) y todo punto es el
            // vertice, asi que el unico pixel posible es a su vez geometria degenerada.
            withRows(analyses = listOf(unitAnalysis), marks = listOf(hip.copy(pointAX = 1.0))) to "presentation frame",
            withRows(
                analyses = listOf(unitAnalysis),
                marks = listOf(hip.copy(pointAX = 0.0, pointAY = 0.0, pointBX = 0.0, pointBY = 0.0, pointCX = 0.0, pointCY = 0.0)),
            ) to "no measurable angle",
            withRows(analyses = listOf(analysis.copy(rotationDegrees = 45))) to "rotationDegrees",
            withRows(analyses = listOf(analysis.copy(rotationDegrees = 0))) to "cannot swap",
            withRows(analyses = listOf(analysis.copy(rotationDegrees = 90, presentationWidth = 1920, presentationHeight = 1080))) to "must swap",
            withRows(analyses = listOf(analysis.copy(frameIndex = -1))) to "frameIndex",
            withRows(analyses = listOf(analysis.copy(sourcePtsUs = -1L))) to "sourcePtsUs",
            withRows(analyses = listOf(analysis.copy(geometryVersion = 0))) to "geometryVersion",
            withRows(analyses = listOf(analysis.copy(revision = -1))) to "revision",
            withRows(analyses = listOf(analysis.copy(encodedWidth = 0))) to "encoded dimensions",
            withRows(analyses = listOf(analysis.copy(presentationHeight = 0))) to "presentation dimensions",
            withRows(analyses = listOf(analysis.copy(createdAt = -1L))) to "createdAt",
        )
        cases.forEachIndexed { index, (document, token) ->
            val error = assertThrows("caso $index debe rechazarse", IllegalArgumentException::class.java) {
                ManualBackupCodec.encode(document)
            }
            assertTrue("caso $index: se esperaba '$token', fue: ${error.message}", error.message.orEmpty().contains(token))
        }

        // Coherencia schema->forma: las tablas de angulos nacen en Room 13 y un 12 no puede traerlas.
        val room12WithAngles = assertThrows(IllegalArgumentException::class.java) {
            ManualBackupCodec.encode(base.copy(sourceRoomSchemaVersion = 12))
        }
        assertTrue(
            "un 12 con filas de angulos debe rechazarse: ${room12WithAngles.message}",
            room12WithAngles.message.orEmpty().contains("cannot contain angle rows"),
        )
        // Y un 13 solo-angulos (sin atletas) sigue siendo un backup restaurable, no vacio.
        ManualBackupCodec.encode(base)
    }

    /**
     * Solo se rechaza lo que no mide: A == C con B distinto es 0 grados legitimo, la colinealidad
     * con B entre A y C mide 180 y el ultimo pixel del frame (`width-1`, `height-1`) es coordenada
     * valida. Las tres marcas atraviesan encode/decode sin cambios.
     */
    @Test fun angleMarksAcceptCollinearZeroOneEightyAndTheLastPixel() {
        val base = angleSample()
        val zero = BackupAngleMark(9_102L, 9_000L, 2, "Tobillo 0", 0xFF112233.toInt(), true, 10.0, 10.0, 20.0, 20.0, 10.0, 10.0)
        val oneEighty = BackupAngleMark(9_103L, 9_000L, 3, "Tobillo 180", 0xFF223344.toInt(), true, 10.0, 10.0, 20.0, 10.0, 30.0, 10.0)
        // Esquina inferior-derecha como vertice: A y C usan los otros bordes del frame.
        val lastPixel = BackupAngleMark(9_104L, 9_000L, 4, "Esquina", 0xFF334455.toInt(), false, 0.0, 1919.0, 1079.0, 1919.0, 1079.0, 0.0)
        val document = base.copy(
            tables = base.tables.copy(angleMarks = base.tables.angleMarks + listOf(zero, oneEighty, lastPixel)),
        )

        // La validez no viaja en el formato: se recalcula desde las marcas con AngleGeometry.
        fun degreesOf(mark: BackupAngleMark): Double = when (
            val measured = AngleGeometry.measure(
                AnglePoint(mark.pointAX, mark.pointAY),
                AnglePoint(mark.pointBX, mark.pointBY),
                AnglePoint(mark.pointCX, mark.pointCY),
            )
        ) {
            is AngleMeasurement.Measured -> measured.degrees
            is AngleMeasurement.Invalid -> throw AssertionError("la marca ${mark.id} deberia medir: ${measured.reason}")
        }
        assertEquals(0.0, degreesOf(zero), 1e-9)
        assertEquals(180.0, degreesOf(oneEighty), 1e-9)
        assertEquals(90.0, degreesOf(lastPixel), 1e-9)

        val decoded = ManualBackupCodec.decode(ManualBackupCodec.encode(document))
        assertEquals(5, decoded.tables.angleMarks.size)
        assertEquals(zero, decoded.tables.angleMarks.single { it.id == 9_102L })
        assertEquals(oneEighty, decoded.tables.angleMarks.single { it.id == 9_103L })
        assertEquals(lastPixel, decoded.tables.angleMarks.single { it.id == 9_104L })
    }

    @Test fun angleListsAreSortedDeterministicallyInThePayload() {
        val base = angleSample()
        val shuffled = base.copy(
            tables = base.tables.copy(angleMarks = base.tables.angleMarks.reversed()),
        )
        val sorted = base.copy(
            tables = base.tables.copy(angleMarks = base.tables.angleMarks.sortedBy { it.ordinal }),
        )
        assertEquals(
            String(ManualBackupCodec.encode(sorted), Charsets.UTF_8),
            String(ManualBackupCodec.encode(shuffled), Charsets.UTF_8),
        )
        val json = String(ManualBackupCodec.encode(shuffled), Charsets.UTF_8)
        assertTrue(json.indexOf("\"ordinal\":0") < json.indexOf("\"ordinal\":1"))
    }

    /** Documento solo-angulos (sin atletas): las tablas de angulos bastan como dato restaurable. */
    private fun angleSample() = ManualBackupDocument(
        contract = MANUAL_BACKUP_CONTRACT,
        formatVersion = MANUAL_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MANUAL_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = 1_700_000_100_000L,
        mediaIncluded = false,
        preferencesIncluded = false,
        tables = emptyTables().copy(
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
                    presentationWidth = 1080,
                    presentationHeight = 1920,
                    rotationDegrees = 90,
                    frameIndex = 42,
                    sourcePtsUs = 1_400_000L,
                    geometryVersion = 1,
                ),
            ),
            angleMarks = listOf(
                BackupAngleMark(
                    9_100L, 9_000L, 0, "Cadera", 0xFF3366CC.toInt(), true,
                    10.0, 20.0, 30.0, 40.0, 50.0, 60.0,
                ),
                BackupAngleMark(
                    9_101L, 9_000L, 1, "Rodilla", 0x803366CC.toInt(), false,
                    100.5, 200.25, 300.0, 100.0, 250.0, 300.0,
                ),
            ),
        ),
    )

    private fun fvpSample(): ManualBackupDocument {
        val base = sample()
        return base.copy(
            tables = base.tables.copy(
                fvpAssessments = listOf(
                    BackupFvpAssessment(
                        id = 7_000_000_000L, sessionKey = "fvp-key", athleteId = 4_000_000_000L,
                        testingSessionId = null, protocolId = "CMJ", dateTime = 5L, bodyMassKg = 80.0,
                        bodyMassSource = "PROFILE", pushOffMode = "COMMON", pushOffM = 0.4,
                        pushOffSource = "MEASURED", pushOffLandmark = "ASIS", pushOffLandmarkHeightCm = 92.5,
                        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH", pushOffMeasuredAt = 4L,
                        pushOffCrouchCm = 30.0, pushOffExtendedCm = 122.5, sourceFps = 240.0,
                        algorithmVersion = "fvp-poc-1.0", methodVersion = "fvp-app-1.0", notes = "fvp note",
                        f0N = 1_200.0, slopeFv = -300.0, sfvNsPerM = 300.0, v0Mps = 1.4, pmaxW = 900.0,
                        r2 = 0.98, conditionCount = 2, vMinMps = 0.2, vMaxMps = 1.6, fMinN = 400.0,
                        fMaxN = 1_800.0, f0RelNPerKg = 15.0, pmaxRelWPerKg = 11.25, warningsCsv = "LOW_R2",
                        acquiredAt = 1_700_000_000_500L, acquisitionContextKind = "SAME_ACQUISITION",
                        acquisitionContextKey = "ctx-1", bodyMassMeasurement = "WEIGHED",
                        bodyMassMeasuredAt = 1_700_000_000_400L,
                    ),
                ),
                fvpConditions = listOf(
                    BackupFvpCondition(7_100L, 7_000_000_000L, 0, "0kg", 0.0, 80.0, 0.4, 0.42, 0, "WEIGHED", 1_700_000_000_600L),
                    BackupFvpCondition(7_101L, 7_000_000_000L, 1, "20kg", 20.0, 100.0, 0.4, 0.31, 0, "NOMINAL", null),
                ),
                fvpAttempts = listOf(
                    BackupFvpAttempt(
                        7_200L, 7_100L, 0, 0.42, "FLIGHT_TIME_VIDEO", 0.585, 240.0, 9_000_000_000L, 0, "CMJ", true, null, "attempt note",
                        sourceRecordedAt = 1_690_000_000_000L, sourceAthleteId = 4_000_000_000L,
                        acquiredAt = 1_700_000_000_700L, reuseConfirmedAt = 1_700_000_000_800L,
                        reuseConfirmationVersion = FVP_REUSE_CONFIRMATION_VERSION,
                        takeoffPtsUs = 1_000_000L, takeoffPreviousPtsUs = 999_000L, takeoffNextPtsUs = 1_001_000L,
                        landingPtsUs = 1_500_000L, landingPreviousPtsUs = 1_499_000L, landingNextPtsUs = 1_501_000L,
                        timeMapping = "IDENTITY_VERIFIED", timeMappingEvidenceKey = "evidence-1",
                    ),
                    BackupFvpAttempt(7_201L, 7_100L, 1, 0.35, "MANUAL", null, null, null, null, null, false, "SHORT_FLIGHT", null),
                    BackupFvpAttempt(
                        7_202L, 7_101L, 0, 0.31, "IMPORTED", null, 120.0, null, null, null, true, null, null,
                        sourceRecordedAt = 1_680_000_000_000L,
                    ),
                ),
            ),
        )
    }

    /** El mismo fixture con las 20 columnas de procedencia en su estado desconocido. */
    private fun unknownProvenanceSample(): ManualBackupDocument {
        val base = fvpSample()
        return base.copy(
            tables = base.tables.copy(
                fvpAssessments = base.tables.fvpAssessments.map {
                    it.copy(acquiredAt = null, acquisitionContextKind = "UNKNOWN", acquisitionContextKey = null, bodyMassMeasurement = "UNKNOWN", bodyMassMeasuredAt = null)
                },
                fvpConditions = base.tables.fvpConditions.map { it.copy(addedLoadSource = "UNKNOWN", addedLoadMeasuredAt = null) },
                fvpAttempts = base.tables.fvpAttempts.map {
                    it.copy(
                        sourceRecordedAt = null, sourceAthleteId = null, acquiredAt = null, reuseConfirmedAt = null,
                        reuseConfirmationVersion = null, takeoffPtsUs = null, takeoffPreviousPtsUs = null,
                        takeoffNextPtsUs = null, landingPtsUs = null, landingPreviousPtsUs = null,
                        landingNextPtsUs = null, timeMapping = "UNKNOWN", timeMappingEvidenceKey = null,
                    )
                },
            ),
        )
    }

    private fun sample() = ManualBackupDocument(
        contract = MANUAL_BACKUP_CONTRACT,
        formatVersion = MANUAL_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MANUAL_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = 1_700_000_000_000,
        mediaIncluded = false,
        preferencesIncluded = false,
        tables = ManualBackupTables(
            athletes = listOf(BackupAthlete(4_000_000_000L, "Átleta Ω", null, null, "notes", 1L, 2L, null, null, 80.0, 180.0)),
            athleteGroups = listOf(BackupGroup(2L, "Grupo", null, 1L, 2L, null)),
            athleteGroupCrossRef = listOf(BackupMembership(2L, 4_000_000_000L)),
            testingSessions = listOf(BackupTestingSession(12L, 2L, "Grupo", "JUMP", "CMJ", null, null, null, null, null, 1, "COMPLETED", 0, 1L, 2L, 3L)),
            testingParticipants = listOf(BackupTestingParticipant(12L, 4_000_000_000L, 0, null)),
            assessments = listOf(BackupAssessment(9_000_000_000L, "jump-key", 4_000_000_000L, 12L, "CMJ", 3L, "HEIGHT_CM", 42.0, "CENTIMETER")),
            attempts = listOf(BackupAttempt(10_000_000_000L, 9_000_000_000L, 0, null, "UNKNOWN", 0, null, null)),
            attemptEvents = listOf(
                BackupAttemptEvent(10_000_000_000L, "MOVEMENT_START", 0, 123_456_789_012_343L),
                BackupAttemptEvent(10_000_000_000L, "TAKEOFF", 0, 123_456_789_012_345L),
                BackupAttemptEvent(10_000_000_000L, "LANDING", 0, 123_456_789_012_346L),
            ),
            attemptMetrics = listOf(BackupAttemptMetric(10_000_000_000L, "HEIGHT_CM", 0, 42.0, "CENTIMETER")),
            attemptCalibrations = listOf(BackupAttemptCalibration(10_000_000_000L, 2, 1.0, 2.0, 5.0, 2.0, 1.0, .25)),
            attemptSpatialMarks = listOf(BackupAttemptSpatialMark(10_000_000_000L, "START_POINT", 2, 123_456_789_012_345L, 1.0, 2.0)),
            encoderSessions = listOf(BackupEncoderSession(20L, "encoder-key", 4_000_000_000L, null, "BENCH_PRESS", 50.0, 4L, "UNKNOWN", null, null, null, "UNKNOWN", 1.0, 0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1, .04, 4.0, .5, 50_000, 300_000, 150_000, 150_000, .02, .2, .5, .15, 5, null, null, 1, 1, 1.2, .1)),
            encoderSamples = listOf(
                BackupEncoderSample(20L, 0, 2, 100L, null, null, null, null, null, null, null, null, null, .9, "TRACKING", "MEASURED", null, null),
                BackupEncoderSample(20L, 1, 3, 200L, null, null, null, null, null, null, null, null, null, .9, "TRACKING", "MEASURED", null, null),
            ),
            encoderRepetitions = listOf(BackupEncoderRepetition(30L, 20L, 0, 0, 1, 0, 0, 1, 1, "VALID", "")),
            encoderRepMetrics = listOf(BackupEncoderRepMetric(30L, "MCV", 1.2, "METER_PER_SECOND", "VALID", null)),
        ),
    )

    private fun emptyTables() = ManualBackupTables(
        athletes = emptyList(), athleteGroups = emptyList(), athleteGroupCrossRef = emptyList(),
        testingSessions = emptyList(), testingParticipants = emptyList(), assessments = emptyList(),
        attempts = emptyList(), attemptEvents = emptyList(), attemptMetrics = emptyList(),
        attemptCalibrations = emptyList(), attemptSpatialMarks = emptyList(), encoderSessions = emptyList(),
        encoderSamples = emptyList(), encoderRepetitions = emptyList(), encoderRepMetrics = emptyList(),
    )

    /** Quita estructuralmente las 20 claves de procedencia para simular un JSON escrito por Room 11. */
    private fun legacyJsonWithoutProvenance(payload: String): String {
        val root = Json.parseToJsonElement(payload).jsonObject
        val tables = root.getValue("tables").jsonObject
        fun strip(key: String, fields: List<String>): JsonElement {
            val rows = tables.getValue(key).jsonArray
            return JsonArray(
                rows.map { element ->
                    JsonObject(element.jsonObject.toMutableMap().apply { fields.forEach { remove(it) } })
                },
            )
        }
        val patchedTables = JsonObject(
            tables.toMutableMap().apply {
                put(
                    "fvpAssessments",
                    strip("fvpAssessments", listOf("acquiredAt", "acquisitionContextKind", "acquisitionContextKey", "bodyMassMeasurement", "bodyMassMeasuredAt")),
                )
                put("fvpConditions", strip("fvpConditions", listOf("addedLoadSource", "addedLoadMeasuredAt")))
                put(
                    "fvpAttempts",
                    strip(
                        "fvpAttempts",
                        listOf(
                            "sourceRecordedAt", "sourceAthleteId", "acquiredAt", "reuseConfirmedAt",
                            "reuseConfirmationVersion", "takeoffPtsUs", "takeoffPreviousPtsUs",
                            "takeoffNextPtsUs", "landingPtsUs", "landingPreviousPtsUs",
                            "landingNextPtsUs", "timeMapping", "timeMappingEvidenceKey",
                        ),
                    ),
                )
            },
        )
        return JsonObject(root + ("tables" to patchedTables)).toString()
    }

}
