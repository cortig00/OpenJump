package com.openjump.app.ui.fvp

import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato del codec de recuperacion F-V-P (slice R1, plan 13 D6). Casos LITERALES: cada asercion
 * nombra un campo con su valor exacto, de modo que perder un campo (el decode devuelve `null` o
 * falta el dato), inventar un `0.0` donde habia `null` o aceptar un payload incoherente rompe la
 * prueba. Nada aqui afirma recuperacion de proceso: el codec es puro y su resultado NO esta
 * revalidado (eso es R2/R3).
 */
class FvpDraftStateCodecTest {

    // --- round trip completo -------------------------------------------------------------------

    @Test
    fun fullRoundTripPreservesEveryField() {
        val original = fullDraft()
        val payload = encoded(FvpRecoveryState(original, FvpFlowStep.CONDITIONS))

        assertTrue(
            "el payload debe declarar la version del formato",
            payload.contains("\"version\":${FvpDraftStateCodec.VERSION}"),
        )
        assertFalse(
            "las claves de memoria NO viajan: condicion",
            payload.contains(original.conditions.first().draftKey),
        )
        assertFalse(
            "las claves de memoria NO viajan: intento",
            payload.contains(original.conditions.first().attempts.first().draftKey),
        )

        val recovered = decoded(payload)
        val draft = recovered.draft

        assertEquals("el paso del flujo viaja", FvpFlowStep.CONDITIONS, recovered.step)
        assertEquals("session-full-1", draft.sessionKey)
        assertEquals(7L, draft.athleteId)
        assertEquals(FvpProtocol.CMJ, draft.protocol)
        assertBitExact(78.39999999999999, draft.bodyMassKg, "bodyMassKg [kg]")
        assertTrue("la procedencia de la masa viaja", draft.bodyMassFromProfile)
        assertEquals("PROFILE", draft.bodyMassSource)
        assertEquals(FvpPushOffUiMode.PER_CONDITION, draft.pushOffMode)
        assertNull("COMMON no aplica en PER_CONDITION y no se inventa un 0.0", draft.commonPushOffM)
        assertEquals("espina iliaca anterosuperior", draft.pushOffLandmark)
        assertBitExact(96.29999999999998, draft.pushOffLandmarkHeightCm, "hito [cm]")
        assertEquals("LANDMARK_EXTENDED_MINUS_CROUCH", draft.pushOffProtocol)
        assertEquals(1_727_000_000_000L, draft.pushOffMeasuredAt)
        assertBitExact(41.50000000000001, draft.pushOffCrouchCm, "crouch [cm]")
        assertBitExact(137.79999999999998, draft.pushOffExtendedCm, "extension [cm]")
        assertEquals("nota de la serie", draft.notes)
        // Derivados: se RECONSTRUYEN identicos desde los campos que viajan (no van en el payload).
        assertEquals("MEASURED", draft.pushOffSource)
        assertBitExact(0.3999992, draft.referencePushOffM, "h_po de referencia [m]")

        assertEquals(2, draft.conditions.size)
        val loaded = draft.conditions[0]
        assertEquals("20kg", loaded.conditionKey)
        assertBitExact(20.049999999999997, loaded.addedLoadKg, "carga añadida [kg]")
        assertBitExact(0.3999992, loaded.pushOffM, "h_po por condicion [m]")
        assertEquals(3, loaded.attempts.size)

        val first = loaded.attempts[0]
        assertBitExact(0.41370000000000005, first.heightM, "altura [m]")
        assertEquals(FvpMeasureMethod.FLIGHT_TIME_VIDEO, first.method)
        assertBitExact(0.5810000000000001, first.flightTimeS, "tiempo de vuelo [s]")
        assertBitExact(29.97002997002997, first.sourceFps, "fps de origen")
        assertEquals(42L, first.sourceAssessmentId)
        assertEquals(1, first.sourceAttemptOrdinal)
        assertEquals("protocolo-origen-9", first.sourceProtocolId)
        assertTrue(first.valid)
        assertNull(first.exclusionReason)
        assertEquals("primer intento", first.notes)

        val discarded = loaded.attempts[1]
        assertBitExact(0.3895, discarded.heightM, "altura del descartado [m]")
        assertEquals(FvpMeasureMethod.MANUAL, discarded.method)
        assertNull("un opcional null sigue null", discarded.flightTimeS)
        assertNull("un opcional null sigue null", discarded.sourceFps)
        assertNull(discarded.sourceAssessmentId)
        assertNull(discarded.sourceAttemptOrdinal)
        assertNull(discarded.sourceProtocolId)
        assertFalse("valid=false viaja tal cual", discarded.valid)
        assertEquals("el motivo de exclusion viaja con el descarte", "PIES_NO_SIMULTANEOS", discarded.exclusionReason)
        assertNull(discarded.notes)

        val partial = loaded.attempts[2]
        assertNull("un intento parcial sin altura recupera null, nunca 0.0", partial.heightM)
        assertEquals(FvpMeasureMethod.IMPORTED, partial.method)
        assertTrue(partial.valid)
        assertEquals(43L, partial.sourceAssessmentId)
        assertEquals(2, partial.sourceAttemptOrdinal)
        assertEquals("protocolo-origen-9", partial.sourceProtocolId)

        val zeroLoad = draft.conditions[1]
        assertEquals("0kg", zeroLoad.conditionKey)
        assertEquals("una carga 0.0 real no puede volverse null", 0.0, zeroLoad.addedLoadKg)
        assertNull("un h_po por condicion ausente sigue ausente", zeroLoad.pushOffM)
        assertEquals(1, zeroLoad.attempts.size)
        assertBitExact(0.4, zeroLoad.attempts[0].heightM, "altura [m]")
        assertEquals(FvpMeasureMethod.MANUAL, zeroLoad.attempts[0].method)
        assertTrue(zeroLoad.attempts[0].valid)

        assertNotEquals(
            "las claves de memoria se regeneran (no son datos)",
            original.conditions[0].draftKey,
            loaded.draftKey,
        )
        assertNotEquals(
            "las claves de memoria se regeneran (no son datos)",
            original.conditions[0].attempts[0].draftKey,
            first.draftKey,
        )
    }

    // --- estados parciales / vacios ------------------------------------------------------------

    @Test
    fun nullsStayNullAndNothingIsInvented() {
        val original = FvpDraft(
            sessionKey = "session-nulls",
            athleteId = 0L,
            protocol = FvpProtocol.SJ,
            conditions = listOf(
                FvpConditionDraft(
                    conditionKey = "0kg",
                    addedLoadKg = null,
                    pushOffM = null,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = null,
                            method = FvpMeasureMethod.MANUAL,
                            valid = false,
                            exclusionReason = "SIN_MEDICION",
                        ),
                    ),
                ),
            ),
        )
        val draft = decoded(encoded(FvpRecoveryState(original, FvpFlowStep.SETUP))).draft

        assertNull("sin masa declarada no se inventa una masa", draft.bodyMassKg)
        assertFalse("sin masa no puede declararse PROFILE", draft.bodyMassFromProfile)
        assertEquals("MANUAL", draft.bodyMassSource)
        assertNull(draft.commonPushOffM)
        assertEquals("un texto vacio sigue vacio (no se inventa un hito)", "", draft.pushOffLandmark)
        assertNull(draft.pushOffLandmarkHeightCm)
        assertEquals("", draft.pushOffProtocol)
        assertNull(draft.pushOffMeasuredAt)
        assertNull(draft.pushOffCrouchCm)
        assertNull(draft.pushOffExtendedCm)
        assertNull(draft.notes)
        assertEquals("sin hito ni fecha la procedencia declarada es MANUAL", "MANUAL", draft.pushOffSource)
        assertEquals(0L, draft.athleteId)

        val condition = draft.conditions.single()
        assertEquals("0kg", condition.conditionKey)
        assertNull("una carga no declarada no se convierte en 0.0", condition.addedLoadKg)
        assertNull(condition.pushOffM)
        val attempt = condition.attempts.single()
        assertNull("una fila de intento vacia recupera null", attempt.heightM)
        assertEquals(FvpMeasureMethod.MANUAL, attempt.method)
        assertNull(attempt.flightTimeS)
        assertNull(attempt.sourceFps)
        assertNull(attempt.sourceAssessmentId)
        assertNull(attempt.sourceAttemptOrdinal)
        assertNull(attempt.sourceProtocolId)
        assertFalse(attempt.valid)
        assertEquals("SIN_MEDICION", attempt.exclusionReason)
        assertNull(attempt.notes)
    }

    @Test
    fun emptyDraftRoundTripsWithoutInventingConditions() {
        val original = FvpDraft(sessionKey = "session-empty", athleteId = 11L)
        val recovered = decoded(encoded(FvpRecoveryState(original, FvpFlowStep.RESULT)))

        assertEquals(FvpFlowStep.RESULT, recovered.step)
        assertEquals("session-empty", recovered.draft.sessionKey)
        assertEquals(11L, recovered.draft.athleteId)
        assertEquals(FvpProtocol.SJ, recovered.draft.protocol)
        assertEquals(FvpPushOffUiMode.COMMON, recovered.draft.pushOffMode)
        assertNull(recovered.draft.bodyMassKg)
        assertNull(recovered.draft.notes)
        assertTrue(
            "un borrador sin condiciones recupera CERO condiciones, no una inventada",
            recovered.draft.conditions.isEmpty(),
        )
    }

    @Test
    fun everyFlowStepRoundTrips() {
        FvpFlowStep.entries.forEach { step ->
            val draft = FvpDraft(sessionKey = "session-step", athleteId = 1L)
            assertEquals(
                "el paso $step debe volver igual",
                step,
                decoded(encoded(FvpRecoveryState(draft, step))).step,
            )
        }
    }

    // --- unidades SI ---------------------------------------------------------------------------

    @Test
    fun siUnitsSurviveBitExactAndAreNotRounded() {
        val original = FvpDraft(
            sessionKey = "session-si",
            athleteId = 3L,
            bodyMassKg = 78.39999999999999,
            pushOffMode = FvpPushOffUiMode.COMMON,
            commonPushOffM = 0.3999992,
            pushOffLandmark = "hito",
            pushOffLandmarkHeightCm = 96.29999999999998,
            pushOffProtocol = "procedimiento",
            pushOffMeasuredAt = 1L,
            conditions = listOf(
                FvpConditionDraft(
                    conditionKey = "0kg",
                    addedLoadKg = 0.30000000000000004,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = 0.41370000000000005,
                            method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                            flightTimeS = 0.5810000000000001,
                            sourceFps = 29.97002997002997,
                        ),
                    ),
                ),
            ),
        )
        val draft = decoded(encoded(FvpRecoveryState(original, FvpFlowStep.SETUP))).draft

        assertBitExact(0.3999992, draft.commonPushOffM, "h_po [m]")
        assertFalse("0.3999992 no puede redondearse a 0.4", draft.commonPushOffM == 0.4)
        assertBitExact(78.39999999999999, draft.bodyMassKg, "masa [kg]")
        assertBitExact(0.30000000000000004, draft.conditions[0].addedLoadKg, "carga [kg]")
        assertBitExact(0.41370000000000005, draft.conditions[0].attempts[0].heightM, "altura [m]")
        assertBitExact(0.5810000000000001, draft.conditions[0].attempts[0].flightTimeS, "tiempo de vuelo [s]")
        assertBitExact(29.97002997002997, draft.conditions[0].attempts[0].sourceFps, "fps")
        assertBitExact(96.29999999999998, draft.pushOffLandmarkHeightCm, "componente del hito [cm]")
    }

    // --- closed fail ---------------------------------------------------------------------------

    @Test
    fun unknownVersionIsRejected() {
        val payload = encoded(referenceState())
        assertNotNull("control: el payload de referencia es valido", FvpDraftStateCodec.decode(payload))

        listOf(0, 3, 99).forEach { version ->
            val tampered = tampered(payload, "\"version\":${FvpDraftStateCodec.VERSION}", "\"version\":$version")
            assertNull("la version $version no se interpreta", FvpDraftStateCodec.decode(tampered))
        }
    }

    @Test
    fun unknownEnumKeyIsRejected() {
        val payload = encoded(referenceState())
        assertNotNull("control: el payload de referencia es valido", FvpDraftStateCodec.decode(payload))

        listOf(
            "\"protocol\":\"CMJ\"" to "\"protocol\":\"JUMP\"",
            "\"method\":\"MANUAL\"" to "\"method\":\"GPS\"",
            "\"pushOffMode\":\"COMMON\"" to "\"pushOffMode\":\"SOMETIMES\"",
            "\"step\":\"SETUP\"" to "\"step\":\"STEP_4\"",
        ).forEach { (from, to) ->
            val tampered = tampered(payload, from, to)
            assertNull("una clave de enum desconocida ($to) no se interpreta", FvpDraftStateCodec.decode(tampered))
        }
    }

    @Test
    fun truncatedCorruptOrUnknownShapePayloadIsRejected() {
        val payload = encoded(referenceState())
        assertNotNull("control: el payload de referencia es valido", FvpDraftStateCodec.decode(payload))

        assertNull("payload vacio", FvpDraftStateCodec.decode(""))
        assertNull("texto que no es JSON", FvpDraftStateCodec.decode("esto no es json"))
        assertNull("JSON valido pero con otra forma", FvpDraftStateCodec.decode("{\"a\":1}"))
        assertNull("JSON nulo", FvpDraftStateCodec.decode("null"))
        assertNotEquals("el truncado debe ser real", payload, payload.take(payload.length / 2))
        assertNull("payload truncado por la mitad", FvpDraftStateCodec.decode(payload.take(payload.length / 2)))
        assertNull("payload sin el ultimo caracter", FvpDraftStateCodec.decode(payload.dropLast(1)))
        assertNull(
            "payload con la llave inicial cambiada",
            FvpDraftStateCodec.decode(tampered(payload, "{\"version\"", "[\"version\"")),
        )
        assertNull(
            "la version 1 y la 2 se aceptan; el resto no: una version desconocida con clave ajena tampoco",
            FvpDraftStateCodec.decode(tampered(payload, "\"version\":2", "\"version\":2,\"futuro\":true")),
        )
    }

    @Test
    fun missingRequiredFieldIsRejected() {
        val payload = encoded(FvpRecoveryState(fullDraft(), FvpFlowStep.RESULT))
        assertNotNull("control: el payload completo es valido", FvpDraftStateCodec.decode(payload))

        listOf("version", "step", "sessionKey", "athleteId", "protocol", "conditions", "notes").forEach { key ->
            assertNull(
                "un payload al que le falta '$key' no se interpreta",
                FvpDraftStateCodec.decode(jsonWithoutTopLevelKey(payload, key)),
            )
        }
        listOf("heightM", "method", "flightTimeS", "valid", "exclusionReason", "notes").forEach { key ->
            assertNull(
                "un intento al que le falta '$key' no se interpreta",
                FvpDraftStateCodec.decode(jsonWithoutFirstAttemptKey(payload, key)),
            )
        }
    }

    @Test
    fun incoherentStateIsRejectedByEncodeAndDecode() {
        // `PROFILE` sin valor no puede salir del flujo en vivo (tabla de verdad de procedencia de
        // `fvpBodyMassProvenanceIsFromProfile`), asi que tampoco se emite un payload que decode
        // tendria que rechazar: el contrato es simetrico.
        val impossible = FvpDraft(
            sessionKey = "session-profile",
            athleteId = 3L,
            bodyMassKg = null,
            bodyMassFromProfile = true,
        )
        assertNull(
            "encode no emite un payload incoherente",
            FvpDraftStateCodec.encode(FvpRecoveryState(impossible, FvpFlowStep.SETUP)),
        )

        val base = encoded(FvpRecoveryState(FvpDraft(sessionKey = "session-ok", athleteId = 3L, bodyMassKg = 80.0), FvpFlowStep.SETUP))
        assertNotNull("control: el payload base es valido", FvpDraftStateCodec.decode(base))

        val profileWithoutValue = tampered(
            tampered(base, "\"bodyMassKg\":80.0", "\"bodyMassKg\":null"),
            "\"bodyMassFromProfile\":false",
            "\"bodyMassFromProfile\":true",
        )
        assertNull("masa PROFILE sin valor", FvpDraftStateCodec.decode(profileWithoutValue))
        assertNull(
            "sessionKey vacio (la identidad persistida es obligatoria)",
            FvpDraftStateCodec.decode(tampered(base, "\"sessionKey\":\"session-ok\"", "\"sessionKey\":\"\"")),
        )
        assertNull(
            "numero no finito (1e400 no es representable)",
            FvpDraftStateCodec.decode(tampered(base, "\"bodyMassKg\":80.0", "\"bodyMassKg\":1e400")),
        )
        assertNull(
            "boolean escrito como numero: no se coerciona",
            FvpDraftStateCodec.decode(tampered(base, "\"bodyMassFromProfile\":false", "\"bodyMassFromProfile\":1")),
        )
        assertNull(
            "campo obligatorio escrito como null: no se coerciona",
            FvpDraftStateCodec.decode(tampered(base, "\"athleteId\":3", "\"athleteId\":null")),
        )
    }

    // --- presupuesto ---------------------------------------------------------------------------

    // --- motivo del rechazo (R2: R1 review P2-1) -----------------------------------------------

    @Test
    fun encodeDetailedReportsTheRealRefusalReasonWithoutChangingEncodeSemantics() {
        val reference = referenceState()
        val encoded = FvpDraftStateCodec.encodeDetailed(reference)
        assertTrue(
            "un estado valido se codifica completo (mismo payload que encode)",
            encoded is FvpEncodeResult.Encoded,
        )
        assertEquals(
            "encodeDetailed no puede divergir de encode en el caso Encoded",
            FvpDraftStateCodec.encode(reference),
            (encoded as FvpEncodeResult.Encoded).payload,
        )

        // Incoherencia estructural: el motivo es NO recuperable, nunca "demasiado grande".
        val profileWithoutValue = FvpDraft(
            sessionKey = "session-profile",
            athleteId = 3L,
            bodyMassKg = null,
            bodyMassFromProfile = true,
        )
        assertEquals(
            "una incoherencia estructural NO se reporta como problema de tamano",
            FvpEncodeResult.NotRecoverable,
            FvpDraftStateCodec.encodeDetailed(FvpRecoveryState(profileWithoutValue, FvpFlowStep.SETUP)),
        )
        assertNull(
            "encode sigue devolviendo null para el mismo estado (una sola semantica)",
            FvpDraftStateCodec.encode(FvpRecoveryState(profileWithoutValue, FvpFlowStep.SETUP)),
        )

        // Numero no finito: tampoco representable -> NO recuperable (no es un problema de tamano).
        val nonFinite = FvpDraft(sessionKey = "session-nan", athleteId = 4L, bodyMassKg = Double.NaN)
        assertEquals(
            "un numero no finito es NO recuperable",
            FvpEncodeResult.NotRecoverable,
            FvpDraftStateCodec.encodeDetailed(FvpRecoveryState(nonFinite, FvpFlowStep.CONDITIONS)),
        )

        // Presupuesto: el motivo es el tamano real en bytes UTF-8, jamas un payload recortado.
        val overNote = "x".repeat(70_000)
        val overDraft = FvpDraft(sessionKey = "session-note", athleteId = 4L, notes = overNote)
        val overDetailed = FvpDraftStateCodec.encodeDetailed(FvpRecoveryState(overDraft, FvpFlowStep.SETUP))
        assertEquals(
            "un estado por encima del presupuesto se reporta como TooLarge",
            FvpEncodeResult.TooLarge,
            overDetailed,
        )
        assertNull(
            "y encode sigue devolviendo null para el mismo estado",
            FvpDraftStateCodec.encode(FvpRecoveryState(overDraft, FvpFlowStep.SETUP)),
        )

        val multibyte = "é".repeat(40_000)
        assertTrue("control: menos caracteres que el presupuesto", multibyte.length < FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES)
        val multibyteDraft = FvpDraft(sessionKey = "session-multibyte", athleteId = 6L, notes = multibyte)
        assertEquals(
            "el motivo TooLarge mide bytes UTF-8, no caracteres",
            FvpEncodeResult.TooLarge,
            FvpDraftStateCodec.encodeDetailed(FvpRecoveryState(multibyteDraft, FvpFlowStep.SETUP)),
        )
    }

    @Test
    fun overBudgetNoteIsRefusedByEncodeWithoutTruncation() {
        val belowNote = "x".repeat(60_000)
        val belowDraft = FvpDraft(sessionKey = "session-note", athleteId = 4L, notes = belowNote)
        val belowPayload = FvpDraftStateCodec.encode(FvpRecoveryState(belowDraft, FvpFlowStep.SETUP))
        assertNotNull("un estado por debajo del presupuesto SI se recupera", belowPayload)
        assertTrue(
            "el payload aceptado debe caber en el presupuesto",
            belowPayload!!.toByteArray(Charsets.UTF_8).size <= FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )

        val overNote = "x".repeat(70_000)
        val overDraft = FvpDraft(sessionKey = "session-note", athleteId = 4L, notes = overNote)
        assertNull(
            "un estado por encima del presupuesto se RECHAZA (null), nunca se recorta",
            FvpDraftStateCodec.encode(FvpRecoveryState(overDraft, FvpFlowStep.SETUP)),
        )

        // Y un payload recibido por encima del presupuesto tampoco se acepta a medias.
        val oversizedIncoming = tampered(
            belowPayload,
            "\"notes\":\"$belowNote\"",
            "\"notes\":\"$overNote\"",
        )
        assertNull(
            "un payload entrante por encima del presupuesto se rechaza entero",
            FvpDraftStateCodec.decode(oversizedIncoming),
        )
    }

    @Test
    fun overBudgetManyConditionsIsRefusedByEncode() {
        val huge = FvpDraft(
            sessionKey = "session-huge",
            athleteId = 5L,
            bodyMassKg = 80.0,
            conditions = (0 until 500).map { index ->
                FvpConditionDraft(
                    conditionKey = "carga-$index",
                    addedLoadKg = index.toDouble(),
                    pushOffM = 0.4,
                    attempts = (0 until 4).map { attempt ->
                        FvpAttemptDraft(
                            heightM = 0.4,
                            method = FvpMeasureMethod.MANUAL,
                            notes = "intento $index-$attempt",
                        )
                    },
                )
            },
        )
        assertNull(
            "2000 intentos no caben en el presupuesto: el estado se queda SOLO en vivo",
            FvpDraftStateCodec.encode(FvpRecoveryState(huge, FvpFlowStep.CONDITIONS)),
        )
    }

    @Test
    fun budgetIsMeasuredInUtf8BytesNotCharacters() {
        val multibyteNote = "é".repeat(40_000)
        assertTrue(
            "menos caracteres que el presupuesto",
            multibyteNote.length < FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        assertTrue(
            "pero mas bytes UTF-8 que el presupuesto",
            multibyteNote.toByteArray(Charsets.UTF_8).size > FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        val draft = FvpDraft(sessionKey = "session-multibyte", athleteId = 6L, notes = multibyteNote)
        assertNull(
            "el presupuesto cuenta bytes UTF-8, no caracteres",
            FvpDraftStateCodec.encode(FvpRecoveryState(draft, FvpFlowStep.SETUP)),
        )
    }

    // --- B1b-2b: las 20 columnas de procedencia viajan en el payload v2 -------------------------

    @Test
    fun v2RoundTripCarriesAllTwentyProvenanceColumnsExactly() {
        assertEquals("el formato de ESCRITURA es v2 (B1b-2b)", 2, FvpDraftStateCodec.VERSION)
        val original = provenanceDraft()
        val payload = encoded(FvpRecoveryState(original, FvpFlowStep.CONDITIONS))
        assertTrue(
            "el payload debe declarar la version del formato v2",
            payload.contains("\"version\":${FvpDraftStateCodec.VERSION}"),
        )

        // Presencia literal de las 20 claves nuevas: 5 en la raiz, 2 por condicion, 13 por intento.
        val root = Json.parseToJsonElement(payload).jsonObject
        rootProvenanceKeys().forEach { key ->
            assertTrue("la raiz v2 debe llevar '$key'", root.containsKey(key))
        }
        val condition = root.getValue("conditions").jsonArray.first().jsonObject
        conditionProvenanceKeys().forEach { key ->
            assertTrue("cada condicion v2 debe llevar '$key'", condition.containsKey(key))
        }
        val attempt = condition.getValue("attempts").jsonArray.first().jsonObject
        attemptProvenanceKeys().forEach { key ->
            assertTrue("cada intento v2 debe llevar '$key'", attempt.containsKey(key))
        }
        assertEquals(
            "el contrato B0 son 20 columnas de procedencia",
            20,
            rootProvenanceKeys().size + conditionProvenanceKeys().size + attemptProvenanceKeys().size,
        )

        val draft = decoded(payload).draft
        // RAIZ (5): adquisicion y pesaje de masa.
        assertEquals(1_727_000_000_001L, draft.acquiredAt)
        assertEquals("SAME_ACQUISITION", draft.acquisitionContextKind)
        assertEquals("ctx-serie-1", draft.acquisitionContextKey)
        assertEquals("WEIGHED", draft.bodyMassMeasurement)
        assertEquals(1_727_000_000_005L, draft.bodyMassMeasuredAt)
        // CONDICION (2): carga anadida, con valores DISTINTOS por condicion.
        assertEquals(2, draft.conditions.size)
        val loaded = draft.conditions[0]
        assertEquals("WEIGHED", loaded.addedLoadSource)
        assertEquals(1_727_000_000_006L, loaded.addedLoadMeasuredAt)
        val emptyLoad = draft.conditions[1]
        assertEquals("un UNKNOWN no se promueve al viajar", "UNKNOWN", emptyLoad.addedLoadSource)
        assertNull("una fecha de medida ausente sigue ausente", emptyLoad.addedLoadMeasuredAt)
        // INTENTO (13): origen, reutilizacion, PTS y mapeo temporal, con valores DISTINTOS por intento.
        assertEquals(2, loaded.attempts.size)
        val reused = loaded.attempts[0]
        assertEquals(1_726_000_000_003L, reused.sourceRecordedAt)
        assertEquals(9L, reused.sourceAthleteId)
        assertEquals(1_727_000_000_002L, reused.acquiredAt)
        assertEquals(1_727_500_000_004L, reused.reuseConfirmedAt)
        assertEquals("fvp-reuse-1", reused.reuseConfirmationVersion)
        assertEquals(1_500_000L, reused.takeoffPtsUs)
        assertEquals(1_499_999L, reused.takeoffPreviousPtsUs)
        assertEquals(1_500_001L, reused.takeoffNextPtsUs)
        assertEquals(1_700_000L, reused.landingPtsUs)
        assertEquals(1_699_999L, reused.landingPreviousPtsUs)
        assertEquals(1_700_001L, reused.landingNextPtsUs)
        assertEquals("IDENTITY_VERIFIED", reused.timeMapping)
        assertEquals("evidencia-pts-1", reused.timeMappingEvidenceKey)
        val imported = loaded.attempts[1]
        assertEquals(1_726_000_000_007L, imported.sourceRecordedAt)
        assertEquals(11L, imported.sourceAthleteId)
        assertEquals(1_727_000_000_008L, imported.acquiredAt)
        assertNull("sin confirmacion durable no se inventa una fecha", imported.reuseConfirmedAt)
        assertNull("sin confirmacion durable no se inventa una version", imported.reuseConfirmationVersion)
        assertEquals(2_000_000L, imported.takeoffPtsUs)
        assertEquals(1_999_000L, imported.takeoffPreviousPtsUs)
        assertNull(imported.takeoffNextPtsUs)
        assertNull(imported.landingPtsUs)
        assertNull(imported.landingPreviousPtsUs)
        assertNull(imported.landingNextPtsUs)
        assertEquals("UNKNOWN", imported.timeMapping)
        assertNull(imported.timeMappingEvidenceKey)
        // El intento sin ninguna columna de procedencia (v1) sigue con las 13 en desconocido.
        val bare = draft.conditions[1].attempts.single()
        assertNull(bare.sourceRecordedAt)
        assertNull(bare.sourceAthleteId)
        assertNull(bare.acquiredAt)
        assertNull(bare.reuseConfirmedAt)
        assertNull(bare.reuseConfirmationVersion)
        assertNull(bare.takeoffPtsUs)
        assertNull(bare.takeoffPreviousPtsUs)
        assertNull(bare.takeoffNextPtsUs)
        assertNull(bare.landingPtsUs)
        assertNull(bare.landingPreviousPtsUs)
        assertNull(bare.landingNextPtsUs)
        assertEquals("UNKNOWN", bare.timeMapping)
        assertNull(bare.timeMappingEvidenceKey)

        assertEquals(
            "decode(encode(x)) debe ser espejo EXACTO de TODOS los campos que viajan",
            withoutMemoryKeys(original),
            withoutMemoryKeys(draft),
        )
    }

    @Test
    fun literalV1PayloadWithoutProvenanceKeysDecodesWithUnknownDefaults() {
        val recovered = decoded(v1PayloadLiteral)
        assertEquals("el paso del flujo viaja igual en v1", FvpFlowStep.SETUP, recovered.step)
        val draft = recovered.draft
        assertEquals("session-v1", draft.sessionKey)
        assertEquals(9L, draft.athleteId)
        assertEquals(FvpProtocol.SJ, draft.protocol)
        assertBitExact(80.0, draft.bodyMassKg, "masa [kg]")
        assertEquals(FvpPushOffUiMode.COMMON, draft.pushOffMode)
        assertBitExact(0.4, draft.commonPushOffM, "h_po [m]")
        assertEquals("hito", draft.pushOffLandmark)
        assertBitExact(96.3, draft.pushOffLandmarkHeightCm, "hito [cm]")
        assertEquals("procedimiento", draft.pushOffProtocol)
        assertEquals(1_727_000_000_000L, draft.pushOffMeasuredAt)
        assertBitExact(41.5, draft.pushOffCrouchCm, "crouch [cm]")
        assertBitExact(137.8, draft.pushOffExtendedCm, "extension [cm]")
        assertEquals("nota v1", draft.notes)
        val condition = draft.conditions.single()
        assertEquals("0kg", condition.conditionKey)
        assertEquals(0.0, condition.addedLoadKg)
        assertBitExact(0.4, condition.pushOffM, "h_po de condicion [m]")
        val attempt = condition.attempts.single()
        assertBitExact(0.4, attempt.heightM, "altura [m]")
        assertEquals(FvpMeasureMethod.MANUAL, attempt.method)
        assertEquals(42L, attempt.sourceAssessmentId)
        assertEquals(1, attempt.sourceAttemptOrdinal)
        assertEquals("protocolo-origen-9", attempt.sourceProtocolId)
        assertTrue(attempt.valid)
        assertEquals("intento v1", attempt.notes)

        // Las 20 columnas de procedencia: UNKNOWN/NULL (el formato v1 no las llevaba), jamas
        // inferidas de los datos que si viajaban (fps, fechas de la serie o del intento).
        assertNull(draft.acquiredAt)
        assertEquals("UNKNOWN", draft.acquisitionContextKind)
        assertNull(draft.acquisitionContextKey)
        assertEquals("UNKNOWN", draft.bodyMassMeasurement)
        assertNull(draft.bodyMassMeasuredAt)
        assertEquals("UNKNOWN", condition.addedLoadSource)
        assertNull(condition.addedLoadMeasuredAt)
        assertEquals("UNKNOWN", attempt.timeMapping)
        assertNull(attempt.timeMappingEvidenceKey)
        listOf(
            attempt.sourceRecordedAt,
            attempt.sourceAthleteId,
            attempt.acquiredAt,
            attempt.reuseConfirmedAt,
            attempt.reuseConfirmationVersion,
            attempt.takeoffPtsUs,
            attempt.takeoffPreviousPtsUs,
            attempt.takeoffNextPtsUs,
            attempt.landingPtsUs,
            attempt.landingPreviousPtsUs,
            attempt.landingNextPtsUs,
        ).forEach { assertNull("un v1 no rellena ninguna columna de origen/PTS", it) }

        // Un v1 recuperado y vuelto a guardar se escribe como v2: sigue sin inventar procedencia y
        // no degrada ningun otro campo (lo que el codec debe garantizar frente a la muerte del
        // proceso es justo esto: nada de la parte v1 se pierde).
        val rewritten = encoded(FvpRecoveryState(draft, recovered.step))
        assertTrue("la reescritura es v2", rewritten.contains("\"version\":2"))
        val rewrittenDraft = decoded(rewritten).draft
        assertEquals("UNKNOWN", rewrittenDraft.acquisitionContextKind)
        assertNull(rewrittenDraft.acquiredAt)
        assertEquals(
            "UNKNOWN sigue siendo UNKNOWN tras la reescritura",
            "UNKNOWN",
            rewrittenDraft.conditions.single().attempts.single().timeMapping,
        )
        assertEquals(
            "la reescritura no degrada ningun campo que viajaba en v1",
            withoutMemoryKeys(draft),
            withoutMemoryKeys(rewrittenDraft),
        )
    }

    @Test
    fun v2PayloadMissingAnyProvenanceKeyIsRejected() {
        val payload = encoded(FvpRecoveryState(provenanceDraft(), FvpFlowStep.CONDITIONS))
        assertNotNull("control: el payload v2 completo es valido", FvpDraftStateCodec.decode(payload))

        rootProvenanceKeys().forEach { key ->
            assertNull(
                "un v2 al que le falta la clave de raiz '$key' no se interpreta (los defaults solo son para v1)",
                FvpDraftStateCodec.decode(jsonWithoutTopLevelKey(payload, key)),
            )
        }
        conditionProvenanceKeys().forEach { key ->
            assertNull(
                "un v2 al que le falta la clave de condicion '$key' no se interpreta",
                FvpDraftStateCodec.decode(jsonWithoutFirstConditionKey(payload, key)),
            )
        }
        attemptProvenanceKeys().forEach { key ->
            assertNull(
                "un v2 al que le falta la clave de intento '$key' no se interpreta",
                FvpDraftStateCodec.decode(jsonWithoutFirstAttemptKey(payload, key)),
            )
        }

        // Control de que la presencia importa y no el valor: una clave presente con `null` SI se
        // acepta (es un desconocido declarado), y el mismo payload sin esa clave se rechaza.
        val withNullKey = tampered(payload, "\"acquiredAt\":1727000000001", "\"acquiredAt\":null")
        assertNotNull("una columna presente con null es un desconocido legitimo", FvpDraftStateCodec.decode(withNullKey))
        assertNull(
            "la misma columna AUSENTE se rechaza",
            FvpDraftStateCodec.decode(jsonWithoutTopLevelKey(withNullKey, "acquiredAt")),
        )

        // No basta con mirar el PRIMER elemento: la ultima condicion y el ultimo intento tambien
        // deben llevar la clave, o el payload entero se rechaza. Se usa un fixture con 2
        // condiciones x 2 intentos para que "ultimo" sea distinto de "primero".
        val multi = encoded(FvpRecoveryState(worstCaseDraft(conditionCount = 2, attemptsPerCondition = 2), FvpFlowStep.CONDITIONS))
        assertNotNull("control: el fixture multiple es valido", FvpDraftStateCodec.decode(multi))
        conditionProvenanceKeys().take(1).forEach { key ->
            assertNull(
                "un v2 al que le falta '$key' en la ULTIMA condicion no se interpreta",
                FvpDraftStateCodec.decode(jsonWithoutLastConditionKey(multi, key)),
            )
        }
        attemptProvenanceKeys().take(1).forEach { key ->
            assertNull(
                "un v2 al que le falta '$key' en el ULTIMO intento no se interpreta",
                FvpDraftStateCodec.decode(jsonWithoutLastAttemptKey(multi, key)),
            )
        }
    }

    @Test
    fun incoherentProvenanceIsRejectedByDecodeAndRefusedByEncode() {
        val payload = encoded(FvpRecoveryState(provenanceDraft(), FvpFlowStep.CONDITIONS))
        assertNotNull("control: el payload v2 de referencia es valido", FvpDraftStateCodec.decode(payload))

        // Direccion DECODE: el payload llega con el dato incoherente escrito (proceso viejo, edicion
        // manual del estado parcelado o payload manipulado) y tiene que fallar cerrado.
        val contextKey129 = "c".repeat(129)
        val evidenceKey129 = "e".repeat(129)
        listOf(
            "enum de contexto desconocido" to
                ("\"acquisitionContextKind\":\"SAME_ACQUISITION\"" to "\"acquisitionContextKind\":\"PARTIAL\""),
            "enum de pesaje desconocido" to
                ("\"bodyMassMeasurement\":\"WEIGHED\"" to "\"bodyMassMeasurement\":\"GUESSED\""),
            "enum de carga desconocido" to
                ("\"addedLoadSource\":\"WEIGHED\"" to "\"addedLoadSource\":\"ESTIMATED\""),
            "enum de mapeo temporal desconocido" to
                ("\"timeMapping\":\"IDENTITY_VERIFIED\"" to "\"timeMapping\":\"FRAME_INDEX\""),
            "timestamp de adquisicion negativo" to
                ("\"acquiredAt\":1727000000001" to "\"acquiredAt\":-1"),
            "timestamp de pesaje negativo" to
                ("\"bodyMassMeasuredAt\":1727000000005" to "\"bodyMassMeasuredAt\":-5"),
            "timestamp de medida de carga negativo" to
                ("\"addedLoadMeasuredAt\":1727000000006" to "\"addedLoadMeasuredAt\":-6"),
            "timestamp de registro del origen negativo" to
                ("\"sourceRecordedAt\":1726000000003" to "\"sourceRecordedAt\":-7"),
            "timestamp de confirmacion negativo" to
                ("\"reuseConfirmedAt\":1727500000004" to "\"reuseConfirmedAt\":-8"),
            "sourceAthleteId = 0" to ("\"sourceAthleteId\":9" to "\"sourceAthleteId\":0"),
            "PTS previo no anterior al despegue" to
                ("\"takeoffPreviousPtsUs\":1499999" to "\"takeoffPreviousPtsUs\":1500001"),
            "PTS de aterrizaje no posterior al despegue" to
                ("\"landingPtsUs\":1700000" to "\"landingPtsUs\":1400000"),
            "version de confirmacion desconocida" to
                ("\"reuseConfirmationVersion\":\"fvp-reuse-1\"" to "\"reuseConfirmationVersion\":\"fvp-reuse-2\""),
            "fecha de confirmacion sin version" to
                ("\"reuseConfirmedAt\":1727500000004" to "\"reuseConfirmedAt\":null"),
            "version de confirmacion sin fecha" to
                ("\"reuseConfirmationVersion\":\"fvp-reuse-1\"" to "\"reuseConfirmationVersion\":null"),
            "SAME_ACQUISITION sin clave" to
                ("\"acquisitionContextKey\":\"ctx-serie-1\"" to "\"acquisitionContextKey\":null"),
            "IDENTITY_VERIFIED sin evidencia" to
                ("\"timeMappingEvidenceKey\":\"evidencia-pts-1\"" to "\"timeMappingEvidenceKey\":null"),
            "clave de evidencia vacia" to
                ("\"timeMappingEvidenceKey\":\"evidencia-pts-1\"" to "\"timeMappingEvidenceKey\":\"   \""),
            "clave de contexto de 129 caracteres" to
                ("\"acquisitionContextKey\":\"ctx-serie-1\"" to "\"acquisitionContextKey\":\"$contextKey129\""),
            "clave de evidencia de 129 caracteres" to
                ("\"timeMappingEvidenceKey\":\"evidencia-pts-1\"" to "\"timeMappingEvidenceKey\":\"$evidenceKey129\""),
            "PTS vecino posterior sin PTS de despegue" to
                ("\"takeoffPtsUs\":1500000" to "\"takeoffPtsUs\":null"),
            // Las cuatro columnas de enum TEXT son NOT NULL en Room: null explicito no es un
            // desconocido valido (solo las 12 nullable pueden ser null).
            "enum de contexto null" to ("\"acquisitionContextKind\":\"SAME_ACQUISITION\"" to "\"acquisitionContextKind\":null"),
            "enum de pesaje null" to ("\"bodyMassMeasurement\":\"WEIGHED\"" to "\"bodyMassMeasurement\":null"),
            "enum de carga null" to ("\"addedLoadSource\":\"WEIGHED\"" to "\"addedLoadSource\":null"),
            "enum de mapeo temporal null" to ("\"timeMapping\":\"IDENTITY_VERIFIED\"" to "\"timeMapping\":null"),
        ).forEach { (label, tamper) ->
            assertNull(
                "un payload v2 con $label no se interpreta",
                FvpDraftStateCodec.decode(tampered(payload, tamper.first, tamper.second)),
            )
        }

        // Direccion ENCODE: un estado asi no puede salir del flujo en vivo, asi que el codec tampoco
        // emite un payload que su propio decode tendria que rechazar (simetria del contrato).
        listOf(
            "enum de contexto desconocido" to provenanceDraftWith { copy(acquisitionContextKind = "PARTIAL") },
            "enum de pesaje desconocido" to provenanceDraftWith { copy(bodyMassMeasurement = "GUESSED") },
            "timestamp de adquisicion negativo" to provenanceDraftWith { copy(acquiredAt = -1L) },
            "timestamp de pesaje negativo" to provenanceDraftWith { copy(bodyMassMeasuredAt = -1L) },
            "SAME_ACQUISITION sin clave" to provenanceDraftWith { copy(acquisitionContextKey = null) },
            "clave de contexto vacia" to provenanceDraftWith { copy(acquisitionContextKey = "   ") },
            "clave de contexto de 129 caracteres" to provenanceDraftWith { copy(acquisitionContextKey = "c".repeat(129)) },
            "enum de carga desconocido" to withFirstConditionDraft { copy(addedLoadSource = "ESTIMATED") },
            "timestamp de medida de carga negativo" to withFirstConditionDraft { copy(addedLoadMeasuredAt = -1L) },
            "enum de mapeo temporal desconocido" to withFirstAttemptDraft { copy(timeMapping = "FRAME_INDEX") },
            "IDENTITY_VERIFIED sin evidencia" to withFirstAttemptDraft { copy(timeMappingEvidenceKey = null) },
            "clave de evidencia vacia" to withFirstAttemptDraft { copy(timeMappingEvidenceKey = "   ") },
            "clave de evidencia de 129 caracteres" to withFirstAttemptDraft { copy(timeMappingEvidenceKey = "e".repeat(129)) },
            "sourceAthleteId = 0" to withFirstAttemptDraft { copy(sourceAthleteId = 0L) },
            "timestamp de registro del origen negativo" to withFirstAttemptDraft { copy(sourceRecordedAt = -1L) },
            "timestamp de adquisicion del intento negativo" to withFirstAttemptDraft { copy(acquiredAt = -1L) },
            "timestamp de confirmacion negativo" to withFirstAttemptDraft { copy(reuseConfirmedAt = -1L) },
            "version de confirmacion desconocida" to withFirstAttemptDraft { copy(reuseConfirmationVersion = "fvp-reuse-2") },
            "version de confirmacion sin fecha" to withFirstAttemptDraft { copy(reuseConfirmedAt = null) },
            "fecha de confirmacion sin version" to withFirstAttemptDraft { copy(reuseConfirmationVersion = null) },
            "PTS previo no anterior al despegue" to withFirstAttemptDraft { copy(takeoffPreviousPtsUs = takeoffPtsUs) },
            "PTS posterior sin PTS de despegue" to withFirstAttemptDraft { copy(takeoffPtsUs = null) },
            "PTS de aterrizaje no posterior al despegue" to withFirstAttemptDraft { copy(landingPtsUs = takeoffPtsUs) },
        ).forEach { (label, draft) ->
            val state = FvpRecoveryState(draft, FvpFlowStep.CONDITIONS)
            assertEquals(
                "encodeDetailed debe reportar NO recuperable con $label",
                FvpEncodeResult.NotRecoverable,
                FvpDraftStateCodec.encodeDetailed(state),
            )
            assertNull("encode tambien debe devolver null con $label", FvpDraftStateCodec.encode(state))
        }
    }

    @Test
    fun unsupportedVersionsUnknownKeysAndLegacyKeysFailClosedAsSpecified() {
        val payload = encoded(FvpRecoveryState(provenanceDraft(), FvpFlowStep.CONDITIONS))
        assertNotNull("control: el payload v2 de referencia es valido", FvpDraftStateCodec.decode(payload))

        listOf(0, 3, 99, -1).forEach { version ->
            assertNull(
                "la version $version no se interpreta (solo 1 y 2)",
                FvpDraftStateCodec.decode(tampered(payload, "\"version\":2", "\"version\":$version")),
            )
        }
        assertNull("sin 'version' no hay formato", FvpDraftStateCodec.decode(jsonWithoutTopLevelKey(payload, "version")))
        // NOTA (observado, fuera del alcance de este slice): la capa JSON del proyecto acepta un
        // numero ESCRITO COMO TEXTO (`"version":"2"`) como el entero 2, igual que acepta
        // `"athleteId":"9"`; el codec no coerciona booleanos ni `null` (ver
        // `incoherentStateIsRejectedByEncodeAndDecode`), pero la cuarentena de primitivas entre
        // comillas es una decision de `kotlinx.serialization`, no de este contrato.
        assertNull(
            "clave desconocida en la raiz v2",
            FvpDraftStateCodec.decode(tampered(payload, "\"version\":2", "\"version\":2,\"futuro\":true")),
        )
        assertNull(
            "clave desconocida en una condicion v2",
            FvpDraftStateCodec.decode(tampered(payload, "\"conditionKey\":\"20kg\"", "\"conditionKey\":\"20kg\",\"futuro\":1")),
        )
        assertNull(
            "clave desconocida en un intento v2",
            FvpDraftStateCodec.decode(tampered(payload, "\"valid\":true", "\"valid\":true,\"futuro\":1")),
        )
        assertNull(
            "clave desconocida en un payload v1: tampoco se ignora lo que no se entiende",
            FvpDraftStateCodec.decode(tampered(v1PayloadLiteral, "\"version\":1", "\"version\":1,\"futuro\":true")),
        )

        // Un v1 con las claves nuevas PRESENTES es un hibrido incoherente: el formato v1 no las
        // tenia, y aceptarlo aplicaria solo las claves presentes sin exigir la forma completa. La
        // version debe describir la forma, asi que se rechaza entero (control: el v1 puro sin
        // ninguna clave nueva se acepta en `legacyV1PayloadDecodes...`).
        assertNull(
            "un payload declarado v1 con claves de procedencia es un hibrido y se rechaza",
            FvpDraftStateCodec.decode(tampered(payload, "\"version\":2", "\"version\":1")),
        )
        assertNull(
            "un v1 puro con UNA sola clave de procedencia tambien se rechaza",
            FvpDraftStateCodec.decode(
                tampered(
                    FvpDraftStateCodec.encode(FvpRecoveryState(provenanceDraft(), FvpFlowStep.CONDITIONS))!!,
                    "\"version\":2",
                    "\"version\":1",
                ).replaceFirst("\"acquiredAt\":1727000000001", "\"acquiredAt\":null"),
            ),
        )
    }

    @Test
    fun v2WorstCaseBudgetIsMeasuredAndRefusedWithoutTruncation() {
        // Peor caso razonable del contrato: 4 condiciones x 8 intentos con las 20 columnas de
        // procedencia, TODOS los textos opcionales a la longitud declarada (128 caracteres) y PTS
        // completos (evento + 2 vecinos por evento).
        val worst4x8 = worstCaseDraft(conditionCount = 4, attemptsPerCondition = 8)
        val state4x8 = FvpRecoveryState(worst4x8, FvpFlowStep.CONDITIONS)
        val bytes4x8 = measuredUtf8Bytes(state4x8)
        println(
            "[presupuesto B1b-2b] 4x8 (32 intentos): $bytes4x8 bytes de " +
                "${FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES}",
        )
        assertEquals("tamano documentado del peor caso 4x8", expectedWorst4x8Bytes, bytes4x8)
        assertTrue(
            "el peor caso 4x8 debe caber en el presupuesto (holgura ${FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES - bytes4x8} bytes)",
            bytes4x8 <= FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        val recovered4x8 = decoded(requireNotNull(FvpDraftStateCodec.encode(state4x8)))
        assertEquals("ninguna condicion se pierde", 4, recovered4x8.draft.conditions.size)
        assertTrue(
            "ningun intento se pierde",
            recovered4x8.draft.conditions.all { it.attempts.size == 8 },
        )
        assertEquals(
            "el peor caso recuperable es espejo exacto",
            withoutMemoryKeys(worst4x8),
            withoutMemoryKeys(recovered4x8.draft),
        )

        // Suelo del formato: el mismo 4x8 con los textos opcionales vacios (la procedencia sigue
        // llevando sus enums, timestamps y PTS). Es el coste de las 20 COLUMNAS, no de los textos.
        val typical4x8 = FvpRecoveryState(worstCaseDraftWithoutOptionalTexts(), FvpFlowStep.CONDITIONS)
        val typicalBytes4x8 = measuredUtf8Bytes(typical4x8)
        println(
            "[presupuesto B1b-2b] 4x8 sin textos opcionales: $typicalBytes4x8 bytes de " +
                "${FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES}",
        )
        assertEquals("tamano documentado del 4x8 sin textos opcionales", expectedTypical4x8Bytes, typicalBytes4x8)
        assertTrue(
            "un 4x8 sin textos opcionales tiene que caber de sobra",
            typicalBytes4x8 <= FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES / 2,
        )

        // Peor caso extremo pedido por el contrato: 12 condiciones x 12 intentos (144 intentos).
        val worst12x12 = worstCaseDraft(conditionCount = 12, attemptsPerCondition = 12)
        val state12x12 = FvpRecoveryState(worst12x12, FvpFlowStep.CONDITIONS)
        assertEquals(
            "12x12 NO cabe: el motivo reportado es TooLarge",
            FvpEncodeResult.TooLarge,
            FvpDraftStateCodec.encodeDetailed(state12x12),
        )
        assertNull("encode no emite un payload parcial para 12x12", FvpDraftStateCodec.encode(state12x12))
        assertEquals(
            "TooLarge no transporta payload: no hay recorte que se pueda usar",
            null,
            (FvpDraftStateCodec.encodeDetailed(state12x12) as? FvpEncodeResult.Encoded)?.payload,
        )

        // Prueba de que NO se recorta en silencio: se busca el mayor numero de condiciones con 12
        // intentos que SI cabe y se comprueba que se recupera COMPLETO (todas las condiciones y
        // todos los intentos). El rechazo es del payload entero, nunca de una parte.
        var fittingConditionCount = 12
        while (fittingConditionCount > 1 &&
            FvpDraftStateCodec.encode(FvpRecoveryState(worstCaseDraft(fittingConditionCount, 12), FvpFlowStep.CONDITIONS)) == null
        ) {
            fittingConditionCount--
        }
        val fittingPayload = requireNotNull(
            FvpDraftStateCodec.encode(FvpRecoveryState(worstCaseDraft(fittingConditionCount, 12), FvpFlowStep.CONDITIONS)),
        ) { "debe existir una serie de 12 intentos por condicion que quepa" }
        println(
            "[presupuesto B1b-2b] 12 intentos/condicion: caben hasta $fittingConditionCount condiciones " +
                "(${fittingPayload.toByteArray(Charsets.UTF_8).size} bytes); 12x12 no cabe",
        )
        assertEquals("umbral medido con 12 intentos por condicion", expectedFittingConditionCount, fittingConditionCount)
        assertEquals(
            "tamano documentado del mayor payload recuperable con 12 intentos/condicion",
            expectedFitting12Bytes,
            fittingPayload.toByteArray(Charsets.UTF_8).size,
        )
        // Estimacion del peor caso 12x12 a partir de la pendiente MEDIDA por condicion de 12
        // intentos (el JSON es aditivo por condicion). Se rotula como estimacion: `encode` rechaza el
        // payload entero, asi que su tamano exacto no es observable desde fuera. Es varias veces el
        // presupuesto, de modo que el rechazo es correcto y NO se recorta nada.
        val oneByTwelve = measuredUtf8Bytes(FvpRecoveryState(worstCaseDraft(1, 12), FvpFlowStep.CONDITIONS))
        val perConditionBytes = (fittingPayload.toByteArray(Charsets.UTF_8).size - oneByTwelve) / (fittingConditionCount - 1)
        println(
            "[presupuesto B1b-2b] 12x12 (144 intentos) estimado ~" +
                "${fittingPayload.toByteArray(Charsets.UTF_8).size + perConditionBytes * (12 - fittingConditionCount)} bytes " +
                "($perConditionBytes bytes por condicion de 12 intentos)",
        )
        val recoveredFitting = requireNotNull(FvpDraftStateCodec.decode(fittingPayload))
        assertEquals("no se recorta ninguna condicion", fittingConditionCount, recoveredFitting.draft.conditions.size)
        assertTrue(
            "no se recorta ningun intento",
            recoveredFitting.draft.conditions.all { it.attempts.size == 12 },
        )
        assertEquals(
            "y el payload aceptado es espejo exacto del estado completo",
            withoutMemoryKeys(worstCaseDraft(fittingConditionCount, 12)),
            withoutMemoryKeys(recoveredFitting.draft),
        )
    }

    // --- fixtures y ayudas ---------------------------------------------------------------------

    /** Borrador con TODOS los campos poblados, incluidos un descarte y un intento parcial. */
    private fun fullDraft(): FvpDraft = FvpDraft(
        sessionKey = "session-full-1",
        athleteId = 7L,
        protocol = FvpProtocol.CMJ,
        bodyMassKg = 78.39999999999999,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.PER_CONDITION,
        commonPushOffM = null,
        pushOffLandmark = "espina iliaca anterosuperior",
        pushOffLandmarkHeightCm = 96.29999999999998,
        pushOffProtocol = "LANDMARK_EXTENDED_MINUS_CROUCH",
        pushOffMeasuredAt = 1_727_000_000_000L,
        pushOffCrouchCm = 41.50000000000001,
        pushOffExtendedCm = 137.79999999999998,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.049999999999997,
                pushOffM = 0.3999992,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.41370000000000005,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        flightTimeS = 0.5810000000000001,
                        sourceFps = 29.97002997002997,
                        sourceAssessmentId = 42L,
                        sourceAttemptOrdinal = 1,
                        sourceProtocolId = "protocolo-origen-9",
                        valid = true,
                        exclusionReason = null,
                        notes = "primer intento",
                    ),
                    FvpAttemptDraft(
                        heightM = 0.3895,
                        method = FvpMeasureMethod.MANUAL,
                        flightTimeS = null,
                        sourceFps = null,
                        sourceAssessmentId = null,
                        sourceAttemptOrdinal = null,
                        sourceProtocolId = null,
                        valid = false,
                        exclusionReason = "PIES_NO_SIMULTANEOS",
                        notes = null,
                    ),
                    FvpAttemptDraft(
                        heightM = null,
                        method = FvpMeasureMethod.IMPORTED,
                        valid = true,
                        sourceAssessmentId = 43L,
                        sourceAttemptOrdinal = 2,
                        sourceProtocolId = "protocolo-origen-9",
                    ),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                pushOffM = null,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.4, method = FvpMeasureMethod.MANUAL),
                ),
            ),
        ),
        notes = "nota de la serie",
    )

    /** Estado minimo valido con una clave de cada enum, para los casos de closed fail. */
    private fun referenceState(): FvpRecoveryState = FvpRecoveryState(
        draft = FvpDraft(
            sessionKey = "session-enums",
            athleteId = 5L,
            protocol = FvpProtocol.CMJ,
            bodyMassKg = 80.0,
            pushOffMode = FvpPushOffUiMode.COMMON,
            commonPushOffM = 0.4,
            conditions = listOf(
                FvpConditionDraft(
                    conditionKey = "0kg",
                    addedLoadKg = 0.0,
                    attempts = listOf(
                        FvpAttemptDraft(heightM = 0.4, method = FvpMeasureMethod.MANUAL),
                    ),
                ),
            ),
        ),
        step = FvpFlowStep.SETUP,
    )

    private fun encoded(state: FvpRecoveryState): String =
        requireNotNull(FvpDraftStateCodec.encode(state)) { "el estado de referencia debe codificarse" }

    private fun decoded(payload: String): FvpRecoveryState =
        requireNotNull(FvpDraftStateCodec.decode(payload)) { "el payload de referencia debe decodificarse" }

    /** Asercion de igualdad EXACTA (sin tolerancia) y bit a bit (IEEE-754). */
    private fun assertBitExact(expected: Double, actual: Double?, label: String) {
        assertNotNull("$label no debe ser null", actual)
        assertEquals(
            "$label debe viajar bit a bit identico (IEEE-754)",
            expected.toRawBits(),
            actual!!.toRawBits(),
        )
    }

    /** Aplica una alteracion literal y comprueba que de verdad cambio el payload. */
    private fun tampered(payload: String, from: String, to: String): String {
        val result = payload.replace(from, to)
        assertNotEquals("la alteracion '$from' -> '$to' debe aplicarse", payload, result)
        return result
    }

    /**
     * Fixture JSON **v1 LITERAL** (formato anterior a B1b-2b): NO lleva ninguna de las 20 claves de
     * procedencia. Es la forma exacta que habria dejado en el `SavedStateHandle` un proceso con el
     * codec v1, de modo que servira mientras existan payloads v1 en la calle.
     */
    private val v1PayloadLiteral: String =
        "{\"version\":1,\"step\":\"SETUP\",\"sessionKey\":\"session-v1\",\"athleteId\":9," +
            "\"protocol\":\"SJ\",\"bodyMassKg\":80.0,\"bodyMassFromProfile\":false," +
            "\"pushOffMode\":\"COMMON\",\"commonPushOffM\":0.4,\"pushOffLandmark\":\"hito\"," +
            "\"pushOffLandmarkHeightCm\":96.3,\"pushOffProtocol\":\"procedimiento\"," +
            "\"pushOffMeasuredAt\":1727000000000,\"pushOffCrouchCm\":41.5,\"pushOffExtendedCm\":137.8," +
            "\"notes\":\"nota v1\",\"conditions\":[{\"conditionKey\":\"0kg\",\"addedLoadKg\":0.0," +
            "\"pushOffM\":0.4,\"attempts\":[{\"heightM\":0.4,\"method\":\"MANUAL\"," +
            "\"flightTimeS\":null,\"sourceFps\":null,\"sourceAssessmentId\":42," +
            "\"sourceAttemptOrdinal\":1,\"sourceProtocolId\":\"protocolo-origen-9\",\"valid\":true," +
            "\"exclusionReason\":null,\"notes\":\"intento v1\"}]}]}"

    /**
     * Borrador con las 20 columnas de procedencia pobladas y valores DISTINTOS por condicion y por
     * intento: el intento reutilizado lleva las 13, el importado lleva un subconjunto (vecino sin
     * posterior, sin reutilizacion) y el ultimo intento no lleva ninguna (forma v1).
     */
    private fun provenanceDraft(): FvpDraft = FvpDraft(
        sessionKey = "session-provenance",
        athleteId = 9L,
        protocol = FvpProtocol.CMJ,
        bodyMassKg = 80.0,
        pushOffMode = FvpPushOffUiMode.COMMON,
        commonPushOffM = 0.4,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                pushOffM = 0.4,
                addedLoadSource = "WEIGHED",
                addedLoadMeasuredAt = 1_727_000_000_006L,
                attempts = listOf(
                    FvpAttemptDraft(
                        heightM = 0.41,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        sourceFps = 29.97,
                        sourceRecordedAt = 1_726_000_000_003L,
                        sourceAthleteId = 9L,
                        acquiredAt = 1_727_000_000_002L,
                        reuseConfirmedAt = 1_727_500_000_004L,
                        reuseConfirmationVersion = "fvp-reuse-1",
                        takeoffPtsUs = 1_500_000L,
                        takeoffPreviousPtsUs = 1_499_999L,
                        takeoffNextPtsUs = 1_500_001L,
                        landingPtsUs = 1_700_000L,
                        landingPreviousPtsUs = 1_699_999L,
                        landingNextPtsUs = 1_700_001L,
                        timeMapping = "IDENTITY_VERIFIED",
                        timeMappingEvidenceKey = "evidencia-pts-1",
                    ),
                    FvpAttemptDraft(
                        heightM = 0.39,
                        method = FvpMeasureMethod.IMPORTED,
                        sourceRecordedAt = 1_726_000_000_007L,
                        sourceAthleteId = 11L,
                        acquiredAt = 1_727_000_000_008L,
                        takeoffPtsUs = 2_000_000L,
                        takeoffPreviousPtsUs = 1_999_000L,
                        timeMapping = "UNKNOWN",
                    ),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                pushOffM = null,
                addedLoadSource = "UNKNOWN",
                addedLoadMeasuredAt = null,
                attempts = listOf(FvpAttemptDraft(heightM = 0.4, method = FvpMeasureMethod.MANUAL)),
            ),
        ),
        notes = "serie con procedencia",
        acquiredAt = 1_727_000_000_001L,
        acquisitionContextKind = "SAME_ACQUISITION",
        acquisitionContextKey = "ctx-serie-1",
        bodyMassMeasurement = "WEIGHED",
        bodyMassMeasuredAt = 1_727_000_000_005L,
    )

    /** El borrador de procedencia con UN cambio de raiz (para probar el rechazo simetrico). */
    private fun provenanceDraftWith(change: FvpDraft.() -> FvpDraft): FvpDraft = provenanceDraft().change()

    /** El borrador de procedencia con la PRIMERA condicion cambiada. */
    private fun withFirstConditionDraft(change: FvpConditionDraft.() -> FvpConditionDraft): FvpDraft {
        val draft = provenanceDraft()
        val first = draft.conditions.first()
        return draft.copy(conditions = listOf(first.change()) + draft.conditions.drop(1))
    }

    /** El borrador de procedencia con el PRIMER intento de la primera condicion cambiado. */
    private fun withFirstAttemptDraft(change: FvpAttemptDraft.() -> FvpAttemptDraft): FvpDraft {
        val draft = provenanceDraft()
        val firstCondition = draft.conditions.first()
        val firstAttempt = firstCondition.attempts.first()
        return draft.copy(
            conditions = listOf(
                firstCondition.copy(
                    attempts = listOf(firstAttempt.change()) + firstCondition.attempts.drop(1),
                ),
            ) + draft.conditions.drop(1),
        )
    }

    /** Las 5 columnas de procedencia de la RAIZ (contrato 18 §2). */
    private fun rootProvenanceKeys(): List<String> = listOf(
        "acquiredAt",
        "acquisitionContextKind",
        "acquisitionContextKey",
        "bodyMassMeasurement",
        "bodyMassMeasuredAt",
    )

    /** Las 2 columnas de procedencia de una CONDICION (carga anadida). */
    private fun conditionProvenanceKeys(): List<String> = listOf("addedLoadSource", "addedLoadMeasuredAt")

    /** Las 13 columnas de procedencia de un INTENTO (origen, reutilizacion, PTS y mapeo temporal). */
    private fun attemptProvenanceKeys(): List<String> = listOf(
        "sourceRecordedAt",
        "sourceAthleteId",
        "acquiredAt",
        "reuseConfirmedAt",
        "reuseConfirmationVersion",
        "takeoffPtsUs",
        "takeoffPreviousPtsUs",
        "takeoffNextPtsUs",
        "landingPtsUs",
        "landingPreviousPtsUs",
        "landingNextPtsUs",
        "timeMapping",
        "timeMappingEvidenceKey",
    )

    /**
     * Borrador con las 20 columnas de procedencia y todo lo opcional en su LONGITUD MAXIMA del
     * contrato (128 caracteres), PTS completos y confirmacion durable: el peor caso legitimo del
     * presupuesto, sin `null` que ahorre bytes.
     */
    private fun worstCaseDraft(conditionCount: Int, attemptsPerCondition: Int): FvpDraft = FvpDraft(
        sessionKey = "session-peor-caso",
        athleteId = 12L,
        protocol = FvpProtocol.CMJ,
        bodyMassKg = 82.5,
        bodyMassFromProfile = true,
        pushOffMode = FvpPushOffUiMode.PER_CONDITION,
        pushOffLandmark = "h".repeat(128),
        pushOffLandmarkHeightCm = 96.3,
        pushOffProtocol = "p".repeat(128),
        pushOffMeasuredAt = 1_727_000_000_000L,
        pushOffCrouchCm = 41.5,
        pushOffExtendedCm = 137.8,
        notes = "n".repeat(128),
        conditions = (0 until conditionCount).map { conditionIndex ->
            FvpConditionDraft(
                conditionKey = "carga-$conditionIndex",
                addedLoadKg = conditionIndex * 10.0,
                pushOffM = 0.4,
                addedLoadSource = "WEIGHED",
                addedLoadMeasuredAt = 1_727_000_000_000L,
                attempts = (0 until attemptsPerCondition).map { attemptIndex ->
                    FvpAttemptDraft(
                        heightM = 0.4,
                        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                        flightTimeS = 0.581,
                        sourceFps = 29.97,
                        sourceAssessmentId = 42L,
                        sourceAttemptOrdinal = attemptIndex,
                        sourceProtocolId = "q".repeat(128),
                        valid = true,
                        exclusionReason = "e".repeat(128),
                        notes = "n".repeat(128),
                        sourceRecordedAt = 1_726_000_000_000L,
                        sourceAthleteId = 12L,
                        acquiredAt = 1_727_000_000_000L,
                        reuseConfirmedAt = 1_727_500_000_000L,
                        reuseConfirmationVersion = "fvp-reuse-1",
                        takeoffPtsUs = 1_500_000L,
                        takeoffPreviousPtsUs = 1_499_999L,
                        takeoffNextPtsUs = 1_500_001L,
                        landingPtsUs = 1_700_000L,
                        landingPreviousPtsUs = 1_699_999L,
                        landingNextPtsUs = 1_700_001L,
                        timeMapping = "IDENTITY_VERIFIED",
                        timeMappingEvidenceKey = "k".repeat(128),
                    )
                },
            )
        },
        acquiredAt = 1_727_000_000_000L,
        acquisitionContextKind = "HISTORICAL_COMPOSITE",
        acquisitionContextKey = "x".repeat(128),
        bodyMassMeasurement = "WEIGHED",
        bodyMassMeasuredAt = 1_727_000_000_000L,
    )

    /** Tamanos MEDIDOS en este entorno (JVM, `kotlinx.serialization` del proyecto) para el peor caso
     *  del presupuesto. Son evidencia DOCUMENTADA: si cambian hay que revisarlos a proposito (que es
     *  justo lo que se quiere notar si el formato crece), no relajarlos.
     */
    private val expectedWorst4x8Bytes: Int = 36_489

    /** 4x8 con los textos opcionales vacios: coste de las 20 columnas, no de los textos. */
    private val expectedTypical4x8Bytes: Int = 19_533

    /** Numero de condiciones de 12 intentos que caben en el presupuesto (medido). */
    private val expectedFittingConditionCount: Int = 4

    /** Tamano del mayor payload con 12 intentos/condicion que el codec SI acepta (medido). */
    private val expectedFitting12Bytes: Int = 53_937

    /** El peor caso `4x8` con TODOS los textos opcionales vacios (mismo resto de columnas). */
    private fun worstCaseDraftWithoutOptionalTexts(): FvpDraft {
        val draft = worstCaseDraft(conditionCount = 4, attemptsPerCondition = 8)
        return draft.copy(
            pushOffLandmark = "",
            pushOffProtocol = "",
            notes = null,
            acquisitionContextKey = null,
            conditions = draft.conditions.map { condition ->
                condition.copy(
                    attempts = condition.attempts.map { attempt ->
                        attempt.copy(
                            sourceProtocolId = null,
                            exclusionReason = null,
                            notes = null,
                            // IDENTITY_VERIFIED exige evidencia: sin evidencia el mapeo es UNKNOWN.
                            timeMapping = "UNKNOWN",
                            timeMappingEvidenceKey = null,
                        )
                    },
                )
            },
        )
    }

    /** Tamanos UTF-8 REALES del payload de un estado que el codec SI codifica. */
    private fun measuredUtf8Bytes(state: FvpRecoveryState): Int =
        requireNotNull(FvpDraftStateCodec.encode(state)) { "el estado debe codificarse para medirse" }
            .toByteArray(Charsets.UTF_8)
            .size

    /** Borrador con las claves SOLO de memoria borradas, para comparar espejos exactos. */
    private fun withoutMemoryKeys(draft: FvpDraft): FvpDraft = draft.copy(
        conditions = draft.conditions.map { condition ->
            condition.copy(
                draftKey = "",
                attempts = condition.attempts.map { it.copy(draftKey = "") },
            )
        },
    )

    private fun jsonWithoutTopLevelKey(payload: String, key: String): String {
        val root = Json.parseToJsonElement(payload).jsonObject
        assertTrue("el payload de referencia debe contener la clave '$key'", root.containsKey(key))
        return JsonObject(root.toMutableMap().apply { remove(key) }).toString()
    }

    private fun jsonWithoutLastConditionKey(payload: String, key: String): String {
        val root = Json.parseToJsonElement(payload).jsonObject
        val conditions = root.getValue("conditions").jsonArray
        assertTrue("el fixture debe tener al menos 2 condiciones", conditions.size > 1)
        val lastCondition = conditions.last().jsonObject
        assertTrue("la ultima condicion debe contener la clave '$key'", lastCondition.containsKey(key))
        val patchedCondition = JsonObject(lastCondition.toMutableMap().apply { remove(key) })
        return JsonObject(
            root + ("conditions" to JsonArray(conditions.dropLast(1) + listOf<JsonElement>(patchedCondition))),
        ).toString()
    }

    private fun jsonWithoutLastAttemptKey(payload: String, key: String): String {
        val root = Json.parseToJsonElement(payload).jsonObject
        val conditions = root.getValue("conditions").jsonArray
        val lastCondition = conditions.last().jsonObject
        val attempts = lastCondition.getValue("attempts").jsonArray
        assertTrue("el fixture debe tener al menos 2 intentos", attempts.size > 1)
        val lastAttempt = attempts.last().jsonObject
        assertTrue("el ultimo intento debe contener la clave '$key'", lastAttempt.containsKey(key))
        val patchedAttempt = JsonObject(lastAttempt.toMutableMap().apply { remove(key) })
        val patchedCondition = JsonObject(
            lastCondition + ("attempts" to JsonArray(attempts.dropLast(1) + listOf<JsonElement>(patchedAttempt))),
        )
        return JsonObject(
            root + ("conditions" to JsonArray(conditions.dropLast(1) + listOf<JsonElement>(patchedCondition))),
        ).toString()
    }

    private fun jsonWithoutFirstConditionKey(payload: String, key: String): String {
        val root = Json.parseToJsonElement(payload).jsonObject
        val conditions = root.getValue("conditions").jsonArray
        val firstCondition = conditions.first().jsonObject
        assertTrue("la primera condicion debe contener la clave '$key'", firstCondition.containsKey(key))
        val patchedCondition = JsonObject(firstCondition.toMutableMap().apply { remove(key) })
        return JsonObject(
            root + ("conditions" to JsonArray(listOf<JsonElement>(patchedCondition) + conditions.drop(1))),
        ).toString()
    }

    private fun jsonWithoutFirstAttemptKey(payload: String, key: String): String {
        val root = Json.parseToJsonElement(payload).jsonObject
        val conditions = root.getValue("conditions").jsonArray
        val firstCondition = conditions.first().jsonObject
        val attempts = firstCondition.getValue("attempts").jsonArray
        val firstAttempt = attempts.first().jsonObject
        assertTrue("el primer intento debe contener la clave '$key'", firstAttempt.containsKey(key))
        val patchedAttempt = JsonObject(firstAttempt.toMutableMap().apply { remove(key) })
        val patchedCondition = JsonObject(
            firstCondition + ("attempts" to JsonArray(listOf<JsonElement>(patchedAttempt) + attempts.drop(1))),
        )
        return JsonObject(
            root + ("conditions" to JsonArray(listOf<JsonElement>(patchedCondition) + conditions.drop(1))),
        ).toString()
    }
}
