package com.openjump.app.ui.fvp

import com.openjump.app.data.FVP_ACQUISITION_CONTEXT_KINDS
import com.openjump.app.data.FVP_ADDED_LOAD_SOURCES
import com.openjump.app.data.FVP_BODY_MASS_MEASUREMENTS
import com.openjump.app.data.FVP_REUSE_CONFIRMATION_VERSION
import com.openjump.app.data.FVP_TIME_MAPPINGS
import com.openjump.app.fvp.FvpEngine
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Estado RECUPERABLE del flujo F-V-P: el borrador en memoria [FvpDraft] mas el paso del flujo
 * ([FvpFlowStep]) al que pertenece. Es lo unico que viaja en el payload versionado.
 *
 * NO es un estado validado: [FvpDraftStateCodec.decode] lo reconstruye tal cual (incluido su
 * caracter incompleto) y el dueño del flujo debe revalidarlo por la MISMA ruta de
 * elegibilidad/persistencia antes de usarlo.
 */
data class FvpRecoveryState(
    val draft: FvpDraft,
    val step: FvpFlowStep,
)

/**
 * Codec PURO (sin `android`, Compose, Room ni I/O) del estado recuperable del flujo F-V-P
 * (plan 13, D6 y slice R1). Traduce [FvpRecoveryState] a un payload JSON versionado y viceversa.
 *
 * CONTRATO CERRADO (closed fail): [decode] no adivina. Devuelve `null` ante version desconocida,
 * clave de enum no reconocida, campo obligatorio ausente, JSON malformado, clave desconocida,
 * tipo incorrecto, numero no finito o cualquier incoherencia interna (masa `PROFILE` sin valor,
 * `sessionKey` vacio, payload por encima del presupuesto). Nunca devuelve un objeto a medias ni
 * sustituye un `null` por un valor por defecto. Un estado recuperado CONSERVA su caracter
 * INCOMPLETO: los `null` siguen siendo `null` y los estados vacios (sin condiciones) siguen
 * vacios; ninguna capa convierte eso en una evaluacion valida.
 *
 * UNIDADES: los numeros viajan en las unidades CANONICAS de la capa de datos -metros (`heightM`,
 * `pushOffM`), kilogramos (`bodyMassKg`, `addedLoadKg`), segundos (`flightTimeS`) y fps
 * (`sourceFps`)- jamas una unidad de visualizacion formateada o redondeada por el locale.
 * Excepcion DOCUMENTADA: `pushOffLandmarkHeightCm`, `pushOffCrouchCm` y `pushOffExtendedCm` viajan
 * en cm porque ESA es la unidad canonica de sus columnas (`fvp_assessments.pushOff*Cm`); convertir
 * a metros y volver a cm introduciria error de redondeo binario y divergiria de lo persistido.
 *
 * COMPATIBILIDAD (B1b-2b): [VERSION] 2 transporta, ademas del borrador original, las 20 columnas de
 * PROCEDENCIA del contrato 18 §2 (5 en la raiz -`acquiredAt`, `acquisitionContextKind`,
 * `acquisitionContextKey`, `bodyMassMeasurement`, `bodyMassMeasuredAt`-, 2 por condicion
 * -`addedLoadSource`, `addedLoadMeasuredAt`- y 13 por intento -`sourceRecordedAt`, `sourceAthleteId`,
 * `acquiredAt`, `reuseConfirmedAt`, `reuseConfirmationVersion`, `takeoffPtsUs`,
 * `takeoffPreviousPtsUs`, `takeoffNextPtsUs`, `landingPtsUs`, `landingPreviousPtsUs`,
 * `landingNextPtsUs`, `timeMapping`, `timeMappingEvidenceKey`-) de modo que recuperar y volver a
 * guardar NO degrade la procedencia a `UNKNOWN`/NULL. Un payload 1 (formato anterior a B1b-2b) NO
 * llevaba esas claves: se decodifica con las 20 en `UNKNOWN`/`null` (desconocidas, jamas rellenadas
 * por inferencia) y con el resto IDENTICO; su ausencia es un hecho del formato, no un dato perdido.
 * La presencia de las 20 claves en un payload 2 se comprueba ESTRUCTURALMENTE sobre el JSON crudo
 * ([decode]): los defaults de [FvpDraftPayload] existen SOLO para poder leer v1 y nunca pueden tapar
 * un v2 incompleto. Un payload declarado 1 que contenga CUALQUIERA de esas claves es un hibrido
 * incoherente y se rechaza: la version debe describir la forma.
 *
 * COHERENCIA DE PROCEDENCIA: [isStructurallySound] aplica las MISMAS reglas que la capa de datos
 * (`FvpRepository.save`, B1b-1) sobre las 20 columnas: enums en sus conjuntos de almacenamiento,
 * timestamps `>= 0`, `sourceAthleteId > 0`, claves opacas no vacias y de `<= 128` caracteres,
 * confirmacion de reutilizacion bicondicional con su fecha, orden PTS (`previous < evento < next`,
 * `landing > takeoff`, vecino sin evento imposible), `SAME_ACQUISITION` con clave declarada e
 * `IDENTITY_VERIFIED` con evidencia declarada. Un v1 (todo `UNKNOWN`/NULL) sigue siendo valido:
 * `UNKNOWN` nunca se promueve.
 *
 * CLAVES DE ENUM: se usan EXACTAMENTE las claves de almacenamiento de la capa de datos
 * (`"SJ"`/`"CMJ"`, `"COMMON"`/`"PER_CONDITION"`, `"FLIGHT_TIME_VIDEO"`/`"MANUAL"`/`"IMPORTED"`),
 * parseadas con [FvpEngine.parseProtocol], [FvpEngine.parseMethod] y
 * [FvpPushOffUiMode.fromStorageKey] (mismo mapeo que datos y UI). [FvpFlowStep] no se persiste en
 * ninguna columna y no tiene clave de almacenamiento: viaja con el nombre estable del enum.
 *
 * PRESUPUESTO: [MAX_PAYLOAD_UTF8_BYTES] (64 KiB de JSON UTF-8). Es una cota de RECUPERACION
 * acordada en D6, no un limite cientifico de cargas ni de intentos. [encode] mide el tamano REAL
 * en bytes UTF-8 (no en caracteres) y devuelve `null` si se supera: NUNCA trunca ni descarta campos
 * en silencio. Un `null` de [encode] significa "este estado se queda SOLO en vivo"; comunicar ese
 * aviso al usuario es responsabilidad del dueño del flujo (R2), no de este codec. [decode]
 * tambien rechaza un payload entrante por encima del presupuesto: `encode` no puede producirlo.
 *
 * MOTIVO DEL RECHAZO (R1 review P2-1): un `null` de [encode] conflaciona tres causas distintas y
 * un aviso por causa seria FALSO si se adivinase. [encodeDetailed] devuelve el motivo real
 * ([FvpEncodeResult]) y [encode] sigue siendo exactamente su caso `Encoded`, de modo que no hay
 * dos semantica de codificacion: una sola implementacion, dos proyecciones.
 *
 * REVALIDACION OBLIGATORIA: un estado decodificado NO es de fiar. El llamante debe pasarlo por la
 * MISMA ruta de elegibilidad/persistencia ([com.openjump.app.fvp.FvpEligibility] /
 * `FvpRepository`) antes de usarlo: el atleta de la serie puede haberse borrado o archivado y este
 * codec no conoce ese hecho. La verdad persistida MANDA sobre el estado recuperado.
 *
 * IDENTIDAD: `sessionKey` (la identidad persistida: `FvpRepository.save` es idempotente por ella)
 * VIAJA en el payload. Las claves [FvpConditionDraft.draftKey] y [FvpAttemptDraft.draftKey] NO
 * viajan: son identidades SOLO de memoria (claves de Compose y estado de texto), no datos.
 * [decode] las regenera con valores nuevos y eso es seguro porque ninguna capa las persiste,
 * exporta, respalda ni usa para decidir elegibilidad. Tampoco viajan los campos DERIVADOS
 * ([FvpDraft.bodyMassSource], [FvpDraft.referencePushOffM], [FvpDraft.pushOffSource]): se
 * recalculan identicos desde los campos que si viajan, de modo que no existe una segunda fuente de
 * verdad que pueda divergir.
 */
object FvpDraftStateCodec {

    /**
     * Version del formato del payload. [encode] escribe SIEMPRE esta version. [decode] acepta
     * [VERSION] 2 (espejo COMPLETO, incluidas las 20 columnas de procedencia) y [LEGACY_VERSION] 1
     * (formato anterior a B1b-2b: esas columnas se recuperan `UNKNOWN`/`null`). Rechaza cualquier
     * otro valor: un payload de una version futura NO se interpreta "en lo que se pueda".
     */
    const val VERSION: Int = 2

    /**
     * Version LEGACY aceptada en LECTURA (formato anterior a B1b-2b). Solo lectura: [encode] nunca
     * la escribe. Un payload 1 no tenia sitio para las 20 columnas de procedencia, asi que su
     * ausencia no se interpreta como un dato perdido ni como un estado a completar.
     */
    private const val LEGACY_VERSION: Int = 1

    /**
     * Presupuesto de recuperacion: 64 KiB = 65 536 bytes de JSON UTF-8. Cota de INGENIERIA
     * propuesta por D6 para el payload de recuperacion de FVP (no un limite cientifico de cargas ni
     * de intentos). Se mide sobre bytes UTF-8 reales porque el limite del sistema que se intenta
     * respetar (el estado parcelado de Android) cuenta bytes, no caracteres.
     *
     * MEDICION (B1b-2b, evidencia en `FvpDraftStateCodecTest`): con las 20 columnas de procedencia y
     * TODOS los textos opcionales en su maximo (128 caracteres) y PTS completos, un peor caso de 4
     * condiciones x 8 intentos mide 36 489 bytes (CABE, holgura 29 047); el mismo 4x8 sin textos
     * opcionales mide 19 533 (coste del formato); con 12 intentos por condicion caben 4 condiciones
     * (53 937 bytes) y un 12x12 (144 intentos) ronda los 160 KB, de modo que [encodeDetailed]
     * devuelve [FvpEncodeResult.TooLarge] y el estado se queda SOLO en vivo, sin recorte. Estas
     * cifras son la evidencia del presupuesto: ajustarlo es una decision de producto que exige
     * aprobacion explicita y NO se ha tomado en este slice.
     */
    const val MAX_PAYLOAD_UTF8_BYTES: Int = 65_536

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Serializa el estado recuperable, o devuelve `null` si NO es recuperable: supera
     * [MAX_PAYLOAD_UTF8_BYTES], contiene un numero no finito o es estructuralmente incoherente
     * (ver [isStructurallySound]). No hay una tercera salida: o payload completo, o `null`; jamas
     * un payload recortado.
     *
     * Proyeccion EXACTA de [encodeDetailed]: `null` si y solo si el resultado no es
     * [FvpEncodeResult.Encoded]. Para conocer el motivo (y poder avisar por causa) usar
     * [encodeDetailed].
     */
    fun encode(state: FvpRecoveryState): String? =
        (encodeDetailed(state) as? FvpEncodeResult.Encoded)?.payload

    /**
     * Igual que [encode] pero CON MOTIVO: distingue "supera el presupuesto" de "este estado no es
     * recuperable" (estructuralmente incoherente o no representable en el JSON del contrato).
     * Ninguna de las dos salidas emite un payload parcial: el presupuesto se mide sobre bytes
     * UTF-8 reales y el resultado es UN solo payload completo o un rechazo con su causa.
     *
     * El motivo es informacion de DATOS (que causa), nunca un mensaje de usuario: la capa de UI es
     * la que elige el texto localizado (R2).
     */
    fun encodeDetailed(state: FvpRecoveryState): FvpEncodeResult {
        if (!isStructurallySound(state.draft)) return FvpEncodeResult.NotRecoverable
        val payload = state.draft.toPayload(version = VERSION, step = state.step)
        val text = try {
            json.encodeToString(FvpDraftPayload.serializer(), payload)
        } catch (error: SerializationException) {
            // Un valor que el contrato JSON no puede representar (p. ej. un Double no finito con
            // `allowSpecialFloatingPointValues = false`) no es recuperable. No se sustituye.
            return FvpEncodeResult.NotRecoverable
        }
        return if (text.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_UTF8_BYTES) {
            FvpEncodeResult.Encoded(text)
        } else {
            FvpEncodeResult.TooLarge
        }
    }

    /**
     * Reconstruye el estado recuperable, o `null` si el payload no cumple EXACTAMENTE el contrato
     * (closed fail). El resultado es un espejo del borrador original salvo las claves de memoria
     * regeneradas (ver KDoc del objeto) y NO esta revalidado: el llamante debe revalidarlo.
     *
     * FORMATOS: se aceptan [VERSION] 2 y [LEGACY_VERSION] 1; cualquier otra version (futura, `0` o
     * negativa) devuelve `null`. En un payload 2 se exige, ANTES del decodificado tipado, que las 20
     * claves de procedencia existan en la raiz, en CADA condicion y en CADA intento: una clave
     * PRESENTE y nullable con valor `null` es un desconocido legitimo, pero una clave AUSENTE es un
     * payload 2 incompleto y se rechaza entero (los defaults de [FvpDraftPayload] existen solo para
     * leer v1 y no pueden convertir un v2 incompleto en `UNKNOWN`). Un payload que se declara
     * [LEGACY_VERSION] 1 NO puede contener NINGUNA de las 20 claves: el formato v1 no las tenia, y un
     * hibrido declarado v1 con claves parciales se rechaza entero en vez de aplicar solo las presentes
     * (asi los defaults no pueden tapar una version mentida). Las cuatro columnas de enum TEXT no son
     * nullable en Room: un `null` explicito en ellas tambien se rechaza.
     */
    fun decode(payload: String): FvpRecoveryState? {
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_UTF8_BYTES) return null
        // Presencia ESTRUCTURAL (B1b-2b): el JSON crudo decide el formato antes del decodificado
        // tipado, de modo que "clave ausente" y "clave con valor desconocido" no se conflacionan.
        val root = payloadAsJsonObject(payload) ?: return null
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return null
        if (version != VERSION && version != LEGACY_VERSION) return null
        if (version == VERSION && !provenanceShapeIsComplete(root)) return null
        if (version == LEGACY_VERSION && !provenanceKeysAreAbsent(root)) return null
        val decoded = try {
            json.decodeFromString(FvpDraftPayload.serializer(), payload)
        } catch (error: SerializationException) {
            return null
        } catch (error: IllegalArgumentException) {
            return null
        }
        if (decoded.version != version) return null
        val step = FvpFlowStep.entries.firstOrNull { it.name == decoded.step } ?: return null
        val protocol = FvpEngine.parseProtocol(decoded.protocol) ?: return null
        val pushOffMode = FvpPushOffUiMode.fromStorageKey(decoded.pushOffMode) ?: return null
        val conditions = decoded.conditions.map { condition ->
            FvpConditionDraft(
                conditionKey = condition.conditionKey,
                addedLoadKg = condition.addedLoadKg,
                pushOffM = condition.pushOffM,
                attempts = condition.attempts.map { attempt ->
                    FvpAttemptDraft(
                        heightM = attempt.heightM,
                        method = FvpEngine.parseMethod(attempt.method) ?: return null,
                        flightTimeS = attempt.flightTimeS,
                        sourceFps = attempt.sourceFps,
                        sourceAssessmentId = attempt.sourceAssessmentId,
                        sourceAttemptOrdinal = attempt.sourceAttemptOrdinal,
                        sourceProtocolId = attempt.sourceProtocolId,
                        valid = attempt.valid,
                        exclusionReason = attempt.exclusionReason,
                        notes = attempt.notes,
                        // Procedencia 1:1 (B1b-2b). En un payload v1 estos campos llegan con su
                        // default (`null`/`UNKNOWN`) porque el formato no los llevaba.
                        sourceRecordedAt = attempt.sourceRecordedAt,
                        sourceAthleteId = attempt.sourceAthleteId,
                        acquiredAt = attempt.acquiredAt,
                        reuseConfirmedAt = attempt.reuseConfirmedAt,
                        reuseConfirmationVersion = attempt.reuseConfirmationVersion,
                        takeoffPtsUs = attempt.takeoffPtsUs,
                        takeoffPreviousPtsUs = attempt.takeoffPreviousPtsUs,
                        takeoffNextPtsUs = attempt.takeoffNextPtsUs,
                        landingPtsUs = attempt.landingPtsUs,
                        landingPreviousPtsUs = attempt.landingPreviousPtsUs,
                        landingNextPtsUs = attempt.landingNextPtsUs,
                        timeMapping = attempt.timeMapping,
                        timeMappingEvidenceKey = attempt.timeMappingEvidenceKey,
                    )
                },
                addedLoadSource = condition.addedLoadSource,
                addedLoadMeasuredAt = condition.addedLoadMeasuredAt,
            )
        }
        val draft = FvpDraft(
            sessionKey = decoded.sessionKey,
            athleteId = decoded.athleteId,
            protocol = protocol,
            bodyMassKg = decoded.bodyMassKg,
            bodyMassFromProfile = decoded.bodyMassFromProfile,
            pushOffMode = pushOffMode,
            commonPushOffM = decoded.commonPushOffM,
            pushOffLandmark = decoded.pushOffLandmark,
            pushOffLandmarkHeightCm = decoded.pushOffLandmarkHeightCm,
            pushOffProtocol = decoded.pushOffProtocol,
            pushOffMeasuredAt = decoded.pushOffMeasuredAt,
            pushOffCrouchCm = decoded.pushOffCrouchCm,
            pushOffExtendedCm = decoded.pushOffExtendedCm,
            conditions = conditions,
            notes = decoded.notes,
            // Procedencia de adquisicion y pesaje 1:1 (B1b-2b); en v1, `UNKNOWN`/`null`.
            acquiredAt = decoded.acquiredAt,
            acquisitionContextKind = decoded.acquisitionContextKind,
            acquisitionContextKey = decoded.acquisitionContextKey,
            bodyMassMeasurement = decoded.bodyMassMeasurement,
            bodyMassMeasuredAt = decoded.bodyMassMeasuredAt,
        )
        if (!isStructurallySound(draft)) return null
        return FvpRecoveryState(draft = draft, step = step)
    }

    /**
     * JSON crudo como objeto, o `null` si no es un objeto JSON (malformado, `null`, array, escalar).
     * Mismo fail cerrado que el decodificado tipado: aqui no se interpreta nada, solo se mira la
     * FORMA antes de exigir presencia de claves.
     */
    private fun payloadAsJsonObject(payload: String): JsonObject? = try {
        json.parseToJsonElement(payload) as? JsonObject
    } catch (error: SerializationException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }

    /**
     * `true` si las 20 claves de procedencia existen -con CUALQUIER valor, incluido `null`- en la
     * raiz, en cada condicion y en cada intento de un payload [VERSION] 2. Recorre la estructura
     * cruda y falla cerrado si la forma no es la esperada (un `conditions` que no sea array o un
     * elemento que no sea objeto devuelve `false`: el payload no se interpreta).
     */
    private fun provenanceShapeIsComplete(root: JsonObject): Boolean {
        if (!root.containsAll(ROOT_PROVENANCE_KEYS)) return false
        val conditions = root["conditions"] as? JsonArray ?: return false
        for (conditionElement in conditions) {
            val condition = conditionElement as? JsonObject ?: return false
            if (!condition.containsAll(CONDITION_PROVENANCE_KEYS)) return false
            val attempts = condition["attempts"] as? JsonArray ?: return false
            for (attemptElement in attempts) {
                val attempt = attemptElement as? JsonObject ?: return false
                if (!attempt.containsAll(ATTEMPT_PROVENANCE_KEYS)) return false
            }
        }
        return true
    }

    private fun JsonObject.containsAll(keys: List<String>): Boolean = keys.all { containsKey(it) }

    /**
     * `true` si NINGUNA de las 20 claves de procedencia aparece en la raiz, en ninguna condicion ni
     * en ningun intento. Es la condicion del formato [LEGACY_VERSION] 1: si aparece alguna, el payload
     * es un hibrido que se declara v1 pero trae campos v2, y se rechaza entero en vez de aplicar solo
     * las claves presentes (los defaults no pueden tapar una version mentida).
     */
    private fun provenanceKeysAreAbsent(root: JsonObject): Boolean {
        if (root.keys.any { it in ROOT_PROVENANCE_KEYS }) return false
        val conditions = root["conditions"] as? JsonArray ?: return false
        for (conditionElement in conditions) {
            val condition = conditionElement as? JsonObject ?: return false
            if (condition.keys.any { it in CONDITION_PROVENANCE_KEYS }) return false
            val attempts = condition["attempts"] as? JsonArray ?: return false
            for (attemptElement in attempts) {
                val attempt = attemptElement as? JsonObject ?: return false
                if (attempt.keys.any { it in ATTEMPT_PROVENANCE_KEYS }) return false
            }
        }
        return true
    }

    /**
     * Incoherencia ESTRUCTURAL del borrador: no es un problema de elegibilidad (que el llamante
     * revalida despues), sino un estado IMPOSIBLE que no puede salir del flujo en vivo. Mismos
     * criterios en [encode] y [decode], de modo que `encode(x) != null` implica
     * `decode(encode(x)) == x`:
     *
     * - `sessionKey` vacio: la identidad persistida es obligatoria
     *   (`FvpDao.save` rechaza un `sessionKey` en blanco) y sin ella guardar duplicaria filas.
     * - `bodyMassFromProfile == true` sin masa: la tabla de verdad de procedencia de
     *   [fvpBodyMassProvenanceIsFromProfile] prohibe `PROFILE` sin valor ("no | si | - | - | false").
     * - cualquier numero no finito: NaN/Infinity no pueden viajar en JSON estandar ni persistirse.
     * - PROCEDENCIA (B1b-2b), las MISMAS reglas que `FvpRepository.save`/`validateAttemptProvenance`
     *   sobre las 20 columnas: enums en los conjuntos de almacenamiento
     *   ([FVP_ACQUISITION_CONTEXT_KINDS], [FVP_BODY_MASS_MEASUREMENTS], [FVP_ADDED_LOAD_SOURCES],
     *   [FVP_TIME_MAPPINGS]); timestamps `>= 0` cuando se informan; `sourceAthleteId > 0`;
     *   `acquisitionContextKey`/`timeMappingEvidenceKey` no vacias y de `<= 128` caracteres;
     *   `reuseConfirmationVersion` NULL o [FVP_REUSE_CONFIRMATION_VERSION], bicondicional con
     *   `reuseConfirmedAt`; `SAME_ACQUISITION` exige `acquisitionContextKey` e `IDENTITY_VERIFIED`
     *   exige `timeMappingEvidenceKey` (jamas inferidas); PTS no negativos con `previous < evento <
     *   next`, vecino sin evento imposible y `landing > takeoff`. Un payload v1 (las 20 columnas en
     *   `UNKNOWN`/`null`) cumple TODAS estas reglas: `UNKNOWN` no se promueve ni se rellena.
     *
     * NO son incoherencias (se conservan tal cual, son estados parciales legitimos que la
     * elegibilidad/persistencia juzgan): `conditionKey` vacia o duplicada, cargas repetidas,
     * intentos sin altura, intentos `valid == false` con o sin motivo, listas vacias, `""` en
     * campos de texto opcionales, `athleteId == 0` (serie guardada sin atleta; lo revalida R3).
     */
    private fun isStructurallySound(draft: FvpDraft): Boolean {
        if (draft.sessionKey.isBlank()) return false
        if (draft.bodyMassFromProfile && draft.bodyMassKg == null) return false
        if (!acquisitionProvenanceIsSound(draft)) return false
        draft.conditions.forEach { condition ->
            if (condition.addedLoadSource !in FVP_ADDED_LOAD_SOURCES) return false
            if (!isUnknownOrValidTimestamp(condition.addedLoadMeasuredAt)) return false
            condition.attempts.forEach { attempt ->
                if (!attemptProvenanceIsSound(attempt)) return false
            }
        }
        val numbers = buildList {
            add(draft.bodyMassKg)
            add(draft.commonPushOffM)
            add(draft.pushOffLandmarkHeightCm)
            add(draft.pushOffCrouchCm)
            add(draft.pushOffExtendedCm)
            draft.conditions.forEach { condition ->
                add(condition.addedLoadKg)
                add(condition.pushOffM)
                condition.attempts.forEach { attempt ->
                    add(attempt.heightM)
                    add(attempt.flightTimeS)
                    add(attempt.sourceFps)
                }
            }
        }
        return numbers.none { it != null && !it.isFinite() }
    }

    /**
     * Procedencia de la RAIZ: enums de almacenamiento, timestamps `>= 0` cuando se informan, clave
     * del contexto declarado acotada y `SAME_ACQUISITION` con clave declarada. No rellena ninguna
     * columna: `UNKNOWN`/`null` sigue siendo desconocido.
     */
    private fun acquisitionProvenanceIsSound(draft: FvpDraft): Boolean {
        if (draft.acquisitionContextKind !in FVP_ACQUISITION_CONTEXT_KINDS) return false
        if (draft.bodyMassMeasurement !in FVP_BODY_MASS_MEASUREMENTS) return false
        if (!isUnknownOrValidTimestamp(draft.acquiredAt)) return false
        if (!isUnknownOrValidTimestamp(draft.bodyMassMeasuredAt)) return false
        if (!isUnknownOrValidProvenanceKey(draft.acquisitionContextKey)) return false
        return draft.acquisitionContextKind != SAME_ACQUISITION_KIND || draft.acquisitionContextKey != null
    }

    /**
     * Procedencia de un INTENTO: mapeo temporal conocido y con evidencia declarada, snapshots de
     * origen/reutilizacion coherentes y PTS ordenados. Las reglas de orden solo se exigen cuando los
     * valores EXISTEN; el codec no deriva PTS, fechas ni versiones de confirmacion.
     */
    private fun attemptProvenanceIsSound(attempt: FvpAttemptDraft): Boolean {
        if (attempt.timeMapping !in FVP_TIME_MAPPINGS) return false
        if (!isUnknownOrValidProvenanceKey(attempt.timeMappingEvidenceKey)) return false
        if (attempt.timeMapping == IDENTITY_VERIFIED_MAPPING && attempt.timeMappingEvidenceKey == null) {
            return false
        }
        if (!isUnknownOrValidTimestamp(attempt.sourceRecordedAt)) return false
        if (!isUnknownOrValidTimestamp(attempt.acquiredAt)) return false
        if (!isUnknownOrValidTimestamp(attempt.reuseConfirmedAt)) return false
        if (attempt.sourceAthleteId != null && attempt.sourceAthleteId <= 0L) return false
        val reuseVersion = attempt.reuseConfirmationVersion
        if (reuseVersion != null && reuseVersion != FVP_REUSE_CONFIRMATION_VERSION) return false
        if ((reuseVersion == null) != (attempt.reuseConfirmedAt == null)) return false
        if (!ptsOrderIsSound(attempt.takeoffPtsUs, attempt.takeoffPreviousPtsUs, attempt.takeoffNextPtsUs)) {
            return false
        }
        if (!ptsOrderIsSound(attempt.landingPtsUs, attempt.landingPreviousPtsUs, attempt.landingNextPtsUs)) {
            return false
        }
        return attempt.takeoffPtsUs == null || attempt.landingPtsUs == null ||
            attempt.landingPtsUs > attempt.takeoffPtsUs
    }

    private fun FvpDraft.toPayload(version: Int, step: FvpFlowStep): FvpDraftPayload = FvpDraftPayload(
        version = version,
        step = step.name,
        sessionKey = sessionKey,
        athleteId = athleteId,
        protocol = protocol.storageKey(),
        bodyMassKg = bodyMassKg,
        bodyMassFromProfile = bodyMassFromProfile,
        pushOffMode = pushOffMode.storageKey,
        commonPushOffM = commonPushOffM,
        pushOffLandmark = pushOffLandmark,
        pushOffLandmarkHeightCm = pushOffLandmarkHeightCm,
        pushOffProtocol = pushOffProtocol,
        pushOffMeasuredAt = pushOffMeasuredAt,
        pushOffCrouchCm = pushOffCrouchCm,
        pushOffExtendedCm = pushOffExtendedCm,
        notes = notes,
        // Procedencia de adquisicion (B1b-2b): viaja 1:1, sin derivar ni rellenar.
        acquiredAt = acquiredAt,
        acquisitionContextKind = acquisitionContextKind,
        acquisitionContextKey = acquisitionContextKey,
        bodyMassMeasurement = bodyMassMeasurement,
        bodyMassMeasuredAt = bodyMassMeasuredAt,
        conditions = conditions.map { condition ->
            FvpDraftPayloadCondition(
                conditionKey = condition.conditionKey,
                addedLoadKg = condition.addedLoadKg,
                pushOffM = condition.pushOffM,
                // Procedencia de la carga anadida (B1b-2b): 1:1.
                addedLoadSource = condition.addedLoadSource,
                addedLoadMeasuredAt = condition.addedLoadMeasuredAt,
                attempts = condition.attempts.map { attempt ->
                    FvpDraftPayloadAttempt(
                        heightM = attempt.heightM,
                        method = attempt.method.storageKey(),
                        flightTimeS = attempt.flightTimeS,
                        sourceFps = attempt.sourceFps,
                        sourceAssessmentId = attempt.sourceAssessmentId,
                        sourceAttemptOrdinal = attempt.sourceAttemptOrdinal,
                        sourceProtocolId = attempt.sourceProtocolId,
                        valid = attempt.valid,
                        exclusionReason = attempt.exclusionReason,
                        notes = attempt.notes,
                        // Origen, reutilizacion y snapshots temporales (B1b-2b): 1:1, sin inferir.
                        sourceRecordedAt = attempt.sourceRecordedAt,
                        sourceAthleteId = attempt.sourceAthleteId,
                        acquiredAt = attempt.acquiredAt,
                        reuseConfirmedAt = attempt.reuseConfirmedAt,
                        reuseConfirmationVersion = attempt.reuseConfirmationVersion,
                        takeoffPtsUs = attempt.takeoffPtsUs,
                        takeoffPreviousPtsUs = attempt.takeoffPreviousPtsUs,
                        takeoffNextPtsUs = attempt.takeoffNextPtsUs,
                        landingPtsUs = attempt.landingPtsUs,
                        landingPreviousPtsUs = attempt.landingPreviousPtsUs,
                        landingNextPtsUs = attempt.landingNextPtsUs,
                        timeMapping = attempt.timeMapping,
                        timeMappingEvidenceKey = attempt.timeMappingEvidenceKey,
                    )
                },
            )
        },
    )

    /** Clave de almacenamiento de `fvp_assessments.protocolId` (nunca el nombre del enum por azar). */
    private fun FvpProtocol.storageKey(): String = when (this) {
        FvpProtocol.SJ -> "SJ"
        FvpProtocol.CMJ -> "CMJ"
    }

    /** Clave de almacenamiento de `fvp_attempts.method` (misma que `FvpEngine.parseMethod`). */
    private fun FvpMeasureMethod.storageKey(): String = when (this) {
        FvpMeasureMethod.FLIGHT_TIME_VIDEO -> "FLIGHT_TIME_VIDEO"
        FvpMeasureMethod.MANUAL -> "MANUAL"
        FvpMeasureMethod.IMPORTED -> "IMPORTED"
    }

    /**
     * Claves de las 20 columnas de procedencia (B1b-2b) por nivel del payload. Se usan para exigir
     * PRESENCIA en un payload v2 sobre el JSON crudo: la clave puede valer `null`, pero no puede
     * faltar (los defaults de los data class existen solo para poder leer v1).
     */
    private val ROOT_PROVENANCE_KEYS = listOf(
        "acquiredAt",
        "acquisitionContextKind",
        "acquisitionContextKey",
        "bodyMassMeasurement",
        "bodyMassMeasuredAt",
    )

    /** Las 2 columnas de procedencia de una condicion (carga anadida). */
    private val CONDITION_PROVENANCE_KEYS = listOf("addedLoadSource", "addedLoadMeasuredAt")

    /** Las 13 columnas de procedencia de un intento (origen, reutilizacion y snapshots PTS). */
    private val ATTEMPT_PROVENANCE_KEYS = listOf(
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

    /** Clave de `fvp_assessments.acquisitionContextKind` que exige una clave de contexto. */
    private const val SAME_ACQUISITION_KIND = "SAME_ACQUISITION"

    /** Clave de `fvp_attempts.timeMapping` que exige una evidencia declarada. */
    private const val IDENTITY_VERIFIED_MAPPING = "IDENTITY_VERIFIED"

    /**
     * Longitud maxima de una clave textual de procedencia (misma regla que el contrato 18 §2 y que
     * `FvpRepository`). Duplicada aqui a proposito porque la del repositorio es privada de su archivo
     * y el codec no puede depender de un detalle de implementacion suyo.
     */
    private const val FVP_PROVENANCE_KEY_MAX_LENGTH = 128

    /** `true` si el timestamp de procedencia es `null` (desconocido) o `>= 0`. No rellena nada. */
    private fun isUnknownOrValidTimestamp(value: Long?): Boolean = value == null || value >= 0L

    /** `true` si la clave opaca es `null` (desconocida) o no vacia y de `<= 128` caracteres. */
    private fun isUnknownOrValidProvenanceKey(value: String?): Boolean =
        value == null || (value.isNotBlank() && value.length <= FVP_PROVENANCE_KEY_MAX_LENGTH)

    /**
     * `true` si el PTS de un evento y sus vecinos opcionales son coherentes: no negativos, cada
     * vecino exige el evento y el orden es estricto (`previous < event < next`). No aproxima PTS.
     */
    private fun ptsOrderIsSound(event: Long?, previous: Long?, next: Long?): Boolean {
        if (!isUnknownOrValidTimestamp(event)) return false
        if (!isUnknownOrValidTimestamp(previous)) return false
        if (!isUnknownOrValidTimestamp(next)) return false
        if (event == null && (previous != null || next != null)) return false
        if (event != null && previous != null && previous >= event) return false
        if (event != null && next != null && event >= next) return false
        return true
    }
}

/**
 * Resultado de [FvpDraftStateCodec.encodeDetailed] CON MOTIVO (R1 review P2-1).
 *
 * Existe porque un unico `null` conflacionaba superar el presupuesto, la incoherencia estructural
 * y la no representabilidad en JSON: un aviso por causa elegido desde ese `null` podia ser FALSO.
 * No anade ninguna salida nueva al codec: [FvpEncodeResult.Encoded] es el payload que [encode]
 * devolvia y los otros dos casos son el antiguo `null` desglosado por causa real.
 */
sealed interface FvpEncodeResult {
    /** Estado serializado COMPLETO dentro del presupuesto: es el payload recuperable. */
    data class Encoded(val payload: String) : FvpEncodeResult

    /** El payload superaria [FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES]: nunca se trunca. */
    data object TooLarge : FvpEncodeResult

    /** El estado es estructuralmente incoherente o el contrato JSON no puede representarlo. */
    data object NotRecoverable : FvpEncodeResult
}

/**
 * Payload versionado. Los campos del formato v1 son OBLIGATORIOS (sin valores por defecto): un
 * payload al que le falte cualquiera de ellos -incluidos los opcionales escritos como `null`- no se
 * interpreta. Los opcionales se declaran nullable y se emiten SIEMPRE (`explicitNulls`), de modo que
 * "ausente" y "vacio" no pueden confundirse.
 *
 * Las 20 columnas de procedencia de B1b-2b (ver [FvpDraftStateCodec] y el contrato 18 §2) SI llevan
 * DEFAULT `null`/`UNKNOWN`: es lo que permite decodificar un payload v1, cuyo formato no las tenia.
 * Ese default NO es la via de aceptacion de un v2 incompleto: [FvpDraftStateCodec.decode] exige la
 * PRESENCIA de las 20 claves sobre el JSON crudo antes de decodificar tipado.
 */
@Serializable
internal data class FvpDraftPayload(
    val version: Int,
    val step: String,
    val sessionKey: String,
    val athleteId: Long,
    val protocol: String,
    val bodyMassKg: Double?,
    val bodyMassFromProfile: Boolean,
    val pushOffMode: String,
    val commonPushOffM: Double?,
    val pushOffLandmark: String,
    val pushOffLandmarkHeightCm: Double?,
    val pushOffProtocol: String,
    val pushOffMeasuredAt: Long?,
    val pushOffCrouchCm: Double?,
    val pushOffExtendedCm: Double?,
    val notes: String?,
    // --- procedencia de adquisicion/pesaje (B1b-2b, default = desconocido de un payload v1) -------
    val acquiredAt: Long? = null,
    val acquisitionContextKind: String = "UNKNOWN",
    val acquisitionContextKey: String? = null,
    val bodyMassMeasurement: String = "UNKNOWN",
    val bodyMassMeasuredAt: Long? = null,
    val conditions: List<FvpDraftPayloadCondition>,
)

/** Condicion de carga del payload; `draftKey` no viaja (clave de memoria regenerada). */
@Serializable
internal data class FvpDraftPayloadCondition(
    val conditionKey: String,
    val addedLoadKg: Double?,
    val pushOffM: Double?,
    // Procedencia de la carga anadida (B1b-2b, default = desconocido de un payload v1).
    val addedLoadSource: String = "UNKNOWN",
    val addedLoadMeasuredAt: Long? = null,
    val attempts: List<FvpDraftPayloadAttempt>,
)

/** Intento del payload; `draftKey` no viaja (clave de memoria regenerada). */
@Serializable
internal data class FvpDraftPayloadAttempt(
    val heightM: Double?,
    val method: String,
    val flightTimeS: Double?,
    val sourceFps: Double?,
    val sourceAssessmentId: Long?,
    val sourceAttemptOrdinal: Int?,
    val sourceProtocolId: String?,
    val valid: Boolean,
    val exclusionReason: String?,
    val notes: String?,
    // Origen, reutilizacion y snapshots temporales (B1b-2b, default = desconocido de un payload v1).
    val sourceRecordedAt: Long? = null,
    val sourceAthleteId: Long? = null,
    val acquiredAt: Long? = null,
    val reuseConfirmedAt: Long? = null,
    val reuseConfirmationVersion: String? = null,
    val takeoffPtsUs: Long? = null,
    val takeoffPreviousPtsUs: Long? = null,
    val takeoffNextPtsUs: Long? = null,
    val landingPtsUs: Long? = null,
    val landingPreviousPtsUs: Long? = null,
    val landingNextPtsUs: Long? = null,
    val timeMapping: String = "UNKNOWN",
    val timeMappingEvidenceKey: String? = null,
)
