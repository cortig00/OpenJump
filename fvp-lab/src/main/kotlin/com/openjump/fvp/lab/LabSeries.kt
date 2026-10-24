package com.openjump.fvp.lab

import com.openjump.fvp.FvpProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Condicion de laboratorio: una carga sintetica emparejada con un vuelo REAL.
 *
 * @param trialKey clave estable `testId__trialId` del catalogo dorado (vuelo REAL).
 * @param totalMassKg masa del sistema = corporal + carga [kg] (SINTETICA, declarada por serie).
 * @param pushOffM distancia de impulsion h_po [m] (SINTETICA, declarada por serie).
 * @param sourceFps FPS efectivos asumidos para gate/bandas (SINTETICO si se declara; el
 *   catalogo dorado no trae fps por trial). Nulo = desconocido (gate UNKNOWN, bandas con
 *   fps por defecto documentado en el pipeline).
 */
data class LabCondition(
    val trialKey: String,
    val totalMassKg: Double,
    val pushOffM: Double,
    val sourceFps: Double? = null,
)

/**
 * Procedencia declarada: que es REAL (medido) y que es SINTETICO (asumido).
 * Ambas cadenas son obligatorias para que el informe pueda auditar la serie.
 */
data class LabProvenance(
    val real: String,
    val synthetic: String,
    val notes: String = "",
)

/**
 * Definicion versionada de una serie de laboratorio.
 *
 * Schema `com.openjump.fvp-lab-series` v1. Protocolo SJ|CMJ (nunca mezcla). Version de
 * algoritmo fijada a `fvp-lab-1.0` (pipeline + `:fvp fvp-poc-1.0` subyacente).
 */
data class LabSeriesDef(
    val id: String,
    val protocol: FvpProtocol,
    val bodyMassKg: Double,
    val conditions: List<LabCondition>,
    val algorithmVersion: String = LabSeriesParser.EXPECTED_ALGORITHM_VERSION,
    val provenance: LabProvenance,
)

/**
 * Parser READ-ONLY de series `com.openjump.fvp-lab-series` v1 con
 * `Json.parseToJsonElement` SIN plugin. Valida schema, protocolo, masas, h_po,
 * unicidad de trialKeys y presencia de provenance REAL/SINTETICO.
 */
object LabSeriesParser {
    const val EXPECTED_SCHEMA = "com.openjump.fvp-lab-series"
    const val EXPECTED_SCHEMA_VERSION = 1
    const val EXPECTED_ALGORITHM_VERSION = "fvp-lab-1.0"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(path: String): LabSeriesDef = parse(File(path).readText(Charsets.UTF_8))

    fun parse(jsonText: String): LabSeriesDef {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val schema = root["schema"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("series: missing 'schema'")
        if (schema != EXPECTED_SCHEMA) {
            throw IllegalArgumentException("series: unexpected schema '$schema'")
        }
        val v = root["schema_version"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("series: missing/invalid 'schema_version'")
        if (v != EXPECTED_SCHEMA_VERSION.toLong()) {
            throw IllegalArgumentException("series: unexpected schema_version $v")
        }
        val id = root["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("series: missing/blank 'id'")
        val protocolRaw = root["protocol"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("series $id: missing 'protocol'")
        val protocol = try {
            FvpProtocol.valueOf(protocolRaw)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("series $id: invalid protocol '$protocolRaw' (SJ|CMJ only)")
        }
        if (protocol != FvpProtocol.SJ && protocol != FvpProtocol.CMJ) {
            throw IllegalArgumentException("series $id: protocol must be SJ|CMJ, was $protocolRaw")
        }
        val bodyMass = root["bodyMassKg"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("series $id: missing/invalid 'bodyMassKg'")
        if (!bodyMass.isFinite() || bodyMass <= 0.0) {
            throw IllegalArgumentException("series $id: invalid bodyMassKg $bodyMass")
        }
        val algo = root["algorithmVersion"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("series $id: missing 'algorithmVersion'")
        if (algo != EXPECTED_ALGORITHM_VERSION) {
            throw IllegalArgumentException(
                "series $id: unexpected algorithmVersion '$algo', expected '$EXPECTED_ALGORITHM_VERSION'",
            )
        }
        val provObj = root["provenance"]?.jsonObject
            ?: throw IllegalArgumentException("series $id: missing 'provenance'")
        val real = provObj["real"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("series $id: provenance.real missing/blank")
        val synthetic = provObj["synthetic"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("series $id: provenance.synthetic missing/blank")
        val notes = provObj["notes"]?.jsonPrimitive?.content ?: ""
        val condArr = root["conditions"]?.jsonArray
            ?: throw IllegalArgumentException("series $id: missing 'conditions'")
        if (condArr.size < 2) {
            throw IllegalArgumentException("series $id: need >= 2 conditions, was ${condArr.size}")
        }
        val conditions = condArr.map { parseCondition(id, it.jsonObject) }
        val keys = conditions.map { it.trialKey }
        if (keys.toSet().size != keys.size) {
            throw IllegalArgumentException("series $id: duplicate trialKeys $keys")
        }
        return LabSeriesDef(
            id = id,
            protocol = protocol,
            bodyMassKg = bodyMass,
            conditions = conditions,
            algorithmVersion = algo,
            provenance = LabProvenance(real = real, synthetic = synthetic, notes = notes),
        )
    }

    private fun parseCondition(seriesId: String, o: kotlinx.serialization.json.JsonObject): LabCondition {
        val key = o["trialKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("series $seriesId: condition missing/blank trialKey")
        val mass = o["totalMassKg"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("series $seriesId condition $key: missing/invalid totalMassKg")
        if (!mass.isFinite() || mass <= 0.0) {
            throw IllegalArgumentException("series $seriesId condition $key: invalid totalMassKg $mass")
        }
        val hpo = o["pushOffM"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("series $seriesId condition $key: missing/invalid pushOffM")
        // Mismo rango que :fvp (0.05, 0.8].
        if (!hpo.isFinite() || hpo <= 0.05 || hpo > 0.8) {
            throw IllegalArgumentException("series $seriesId condition $key: pushOffM $hpo not in (0.05, 0.8]")
        }
        val fpsEl = o["sourceFps"]
        val fps: Double? = when {
            fpsEl == null || fpsEl.toString() == "null" -> null
            else -> try {
                fpsEl.jsonPrimitive.doubleOrNull
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("series $seriesId condition $key: invalid sourceFps")
            }
        }
        if (fps != null && (!fps.isFinite() || fps <= 0.0)) {
            throw IllegalArgumentException("series $seriesId condition $key: invalid sourceFps $fps")
        }
        return LabCondition(trialKey = key, totalMassKg = mass, pushOffM = hpo, sourceFps = fps)
    }
}
