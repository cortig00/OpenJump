package com.openjump.app.fvp

import com.openjump.fvp.FvpAttempt
import com.openjump.fvp.FvpConditionInput
import com.openjump.fvp.FvpConditionPoint
import com.openjump.fvp.FvpEvaluation
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpPointResult
import com.openjump.fvp.FvpProfile
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.FvpResult
import com.openjump.fvp.FvpSensitivityBand
import com.openjump.fvp.evaluate
import com.openjump.fvp.fit
import com.openjump.fvp.jumpPoint
import com.openjump.fvp.selectBestPerCondition
import com.openjump.fvp.sensitivityEnvelope

/**
 * Adaptador de la app hacia el motor F-V-P del modulo `:fvp` (JVM puro).
 *
 * Es el UNICO punto de entrada app-side al motor: UI, persistencia y ViewModels deben usar
 * exclusivamente estas funciones y NO importar `com.openjump.fvp` directamente para calcular
 * (el modulo `:fvp` sigue siendo la unica implementacion de la matematica; aqui solo se delega,
 * no se duplica ninguna formula).
 *
 * Advertencias de alcance (heredadas de `docs/research/fvp/03-MATHEMATICAL_SPEC.md`):
 * - Todos los valores son ESTIMACIONES DE MODELO, no mediciones directas.
 * - F0 y V0 son EXTRAPOLACIONES teoricas fuera del rango medido; publicar siempre junto a
 *   `vMinMps`/`vMaxMps` y no presentarlas como medidas.
 * - FUERA DE ALCANCE: FVimb, pendiente optima / perfil optimo y prescripcion de entrenamiento,
 *   1RM, comparacion SJ-CMJ y mezcla de metodos de altura en una misma serie.
 *
 * Sin dependencias Android/Room/Media3, sin estado, sin cache, sin logging: funciones puras y
 * deterministas; la misma entrada produce exactamente la misma salida.
 * Los errores bloqueantes de entrada se propagan como `FvpInputError` (IllegalArgumentException)
 * y las condiciones degradadas como advert `FvpWarning` en `FvpProfile.warnings` (nunca excepcion).
 *
 * GATE G-SCIENCE (plan 13, lineas 443-446): la envolvente de sensibilidad temporal se CALCULA aqui,
 * pero sus RANGOS no se muestran al usuario mientras la revision cientifica independiente de T1 no
 * cierre. La UI solo publica su DISPONIBILIDAD ([fvpSensitivityAvailability]) y NO llama a
 * [sensitivityBand]: un numero, un rango o un ancho de la envolvente en pantalla seria un
 * certificado que el metodo todavia no tiene.
 */
object FvpEngine {

    /**
     * Punto (F, V, P) de una condicion desde (masa total, altura, h_po). Spec 03 (1)-(3).
     *
     * @param totalMassKg masa del sistema = corporal + carga adicional [kg], > 0.
     * @param heightM altura de salto del CdM [m], en (0, 1.2].
     * @param pushOffM distancia vertical de impulsion h_po [m], en (0.05, 0.8].
     * @throws com.openjump.fvp.FvpInputError si algun parametro no es finito o viola rangos.
     */
    fun point(totalMassKg: Double, heightM: Double, pushOffM: Double): FvpPointResult =
        jumpPoint(massKg = totalMassKg, heightM = heightM, pushOffM = pushOffM)

    /**
     * Ajusta la recta F(V) = F0 - Sfv*V por minimos cuadrados ordinarios (OLS) sobre los puntos
     * ya calculados. Spec 03 (6)-(9).
     *
     * @param points puntos por condicion; exige >= 2 con `conditionId` no vacio y sin duplicados.
     * @param bodyMassKg masa corporal de referencia (m_ref) para las formas relativas [kg], > 0.
     * @param sourceFps FPS efectivos del video origen (opcional); solo alimenta `FPS_30_BLOCKED`.
     * @throws com.openjump.fvp.FvpInputError si n < 2, hay ids duplicados/vacios, algun campo no
     *   es finito o viola rangos, `bodyMassKg` no es > 0, o todas las velocidades son identicas.
     */
    fun profile(
        points: List<FvpConditionPoint>,
        bodyMassKg: Double,
        sourceFps: Double?,
    ): FvpProfile = fit(points = points, bodyMassKg = bodyMassKg, sourceFps = sourceFps)

    /**
     * Envolvente de sensibilidad temporal de una serie ya CALCULADA: delega en
     * `com.openjump.fvp.sensitivityEnvelope` (la unica implementacion de la matematica; aqui no se
     * re-escribe ninguna formula).
     *
     * CONTRATO DE ENTRADA (mismo orden en las tres listas, el de `selectedConditionInputs()`):
     * [flightTimesS] y [fpsPerCondition] declaran, por condicion SELECCIONADA, el vuelo total del
     * que se derivo `conditions[i].heightM` (misma toma) y la fps con la que se midio. Una sola
     * carencia (un `null`, un valor no finito o <= 0) o un tamano dispar devuelve `null`: NUNCA hay
     * fps por defecto ni resultados parciales por condicion, de modo que `null` significa siempre
     * "sensibilidad no disponible" y no "sensibilidad cero".
     *
     * [bodyMassKg] es la masa corporal de referencia de la serie (el `bodyMassKg` del guardado o del
     * borrador); NO se deduce de `totalMassKg`, que incluye la carga adicional de cada condicion.
     *
     * @throws com.openjump.fvp.FvpInputError si el vuelo/fps/masa son suficientes pero [conditions]
     *   viola el contrato del motor (altura o h_po fuera de dominio, ids duplicados, ...), igual que
     *   [profile]. Las entradas insuficientes NO lanzan: devuelven `null`.
     *
     * FUERA DE PANTALLA HOY: G-SCIENCE sigue abierto, asi que la UI de este slice NO debe llamar a
     * esta funcion (no pinta ningun rango). Existe como unica puerta app-side para el slice que
     * muestre la envolvente cuando el gate cierre, y para fijar por test que una entrada incompleta
     * no produce una banda a medias.
     *
     * PRECONDICION DURA para ese slice: esta funcion puede devolver `null` INCLUSO cuando la
     * disponibilidad ([fvpSensitivityAvailability]) dice `AVAILABLE_GATED`. La disponibilidad solo
     * certifica que los INSUMOS estan declarados, no que el motor produzca banda: con una fps
     * declarada muy baja el motor puede descartar TODAS las variantes (`variantsSkipped` = total) y
     * no hay envolvente que devolver. Quien renderice un rango DEBE manejar `null` como "banda no
     * disponible" (jamas como banda vacia ni como cero) y decidir su copia con ese caso incluido.
     */
    fun sensitivityBand(
        conditions: List<FvpConditionInput>,
        flightTimesS: List<Double?>,
        fpsPerCondition: List<Double?>,
        bodyMassKg: Double,
    ): FvpSensitivityBand? {
        if (flightTimesS.size != conditions.size || fpsPerCondition.size != conditions.size) return null
        if (flightTimesS.any { !it.isUsableSensitivityInput() }) return null
        if (fpsPerCondition.any { !it.isUsableSensitivityInput() }) return null
        return sensitivityEnvelope(
            conditions = conditions,
            flightTimesS = flightTimesS.filterNotNull(),
            fpsPerCondition = fpsPerCondition.filterNotNull(),
            bodyMassKg = bodyMassKg,
        )
    }

    /**
     * Selecciona, por cada condicion de carga, el intento con mayor altura de salto.
     * Determinista: salida ordenada por `conditionId`; ante empates conserva el primer intento
     * en orden de entrada; la lista vacia devuelve lista vacia (no es error).
     *
     * @throws com.openjump.fvp.FvpInputError si algun intento es invalido.
     */
    fun bestPerCondition(attempts: List<FvpAttempt>): List<FvpConditionInput> =
        selectBestPerCondition(attempts)

    /**
     * Evalua una serie completa: puntos por condicion + perfil ajustado + trazabilidad.
     *
     * @param id identificador de la evaluacion (no vacio).
     * @param protocol protocolo de la serie; SJ y CMJ nunca se mezclan en una misma regresion.
     * @param bodyMassKg masa corporal en vacio (m_ref) [kg], > 0.
     * @param conditions puntos ya calculados de la serie (>= 2, ids no vacios ni duplicados).
     * @param provenance origen libre de la serie (operador, fecha, dispositivo, hito h_po).
     * @param sourceFps FPS efectivos del video origen (opcional); solo alimenta `FPS_30_BLOCKED`.
     * @throws com.openjump.fvp.FvpInputError si el id esta vacio o la serie es invalida.
     */
    fun evaluation(
        id: String,
        protocol: FvpProtocol,
        bodyMassKg: Double,
        conditions: List<FvpConditionPoint>,
        provenance: String,
        sourceFps: Double?,
    ): FvpResult = evaluate(
        evaluation = FvpEvaluation(
            id = id,
            protocol = protocol,
            bodyMassKg = bodyMassKg,
            conditions = conditions,
            provenance = provenance,
        ),
        sourceFps = sourceFps,
    )

    /**
     * Mapea una clave de almacenamiento persistida a [FvpProtocol].
     *
     * Mapeo estable, comparacion EXACTA (sin trim ni normalizacion de mayusculas):
     * `"SJ"` -> `SJ`, `"CMJ"` -> `CMJ`. Cualquier otra clave (incluida `null`, vacia o con
     * espacios) devuelve `null`: una clave desconocida NUNCA debe caer en un valor por defecto
     * silencioso, la capa llamante decide (error visible o migracion explicita).
     */
    fun parseProtocol(storageKey: String?): FvpProtocol? = when (storageKey) {
        "SJ" -> FvpProtocol.SJ
        "CMJ" -> FvpProtocol.CMJ
        else -> null
    }

    /**
     * Mapea una clave de almacenamiento persistida a [FvpMeasureMethod].
     *
     * Mapeo estable, comparacion EXACTA (sin trim ni normalizacion de mayusculas):
     * `"FLIGHT_TIME_VIDEO"`, `"MANUAL"`, `"IMPORTED"`. Cualquier otra clave (incluida `null`,
     * vacia o con espacios) devuelve `null`: una clave desconocida NUNCA debe caer en un valor
     * por defecto silencioso, la capa llamante decide (error visible o migracion explicita).
     */
    fun parseMethod(storageKey: String?): FvpMeasureMethod? = when (storageKey) {
        "FLIGHT_TIME_VIDEO" -> FvpMeasureMethod.FLIGHT_TIME_VIDEO
        "MANUAL" -> FvpMeasureMethod.MANUAL
        "IMPORTED" -> FvpMeasureMethod.IMPORTED
        else -> null
    }
}

/**
 * Estado de disponibilidad de la envolvente de sensibilidad temporal (G-SCIENCE abierto).
 *
 * La copia de usuario es de la capa Compose (un rotulo y una explicacion por estado), no de este
 * archivo: aqui vive solo el VEREDICTO. [AVAILABLE_GATED] NO autoriza a pintar ningun rango, ancho
 * ni numero; declarar la disponibilidad no es mostrar la sensibilidad.
 */
enum class FvpSensitivityAvailability {
    /**
     * Insumos completos y recalculo existente: la envolvente PODRIA calcularse, pero sus rangos no se
     * muestran hasta que cierre la revision cientifica independiente de T1 (G-SCIENCE, plan 13).
     */
    AVAILABLE_GATED,

    /** Al menos una condicion seleccionada no declara un tiempo de vuelo utilizable (o no hay entrada). */
    MISSING_FLIGHT_TIME,

    /** Al menos una condicion seleccionada no declara una fps utilizable (o no hay entrada). */
    MISSING_FPS,

    /** La ejecucion mostrada no tiene calculo con el motor actual: no hay serie de la que partir. */
    NO_RECALCULATION,
}

/**
 * Entrada de sensibilidad temporal UTILIZABLE: finita y > 0. Es la misma exigencia que aplica
 * `sensitivityEnvelope`, de modo que "ausente" y "no utilizable" son el MISMO motivo para el
 * usuario y ninguna capa puede colar un 0.0 o un NaN como si fuera un dato.
 */
private fun Double?.isUsableSensitivityInput(): Boolean =
    this != null && this.isFinite() && this > 0.0

/**
 * Disponibilidad (NO los rangos) de la envolvente de sensibilidad temporal ante el error de marcado
 * del video. Los rangos siguen bloqueados por G-SCIENCE (plan 13, lineas 443-446): esta decision
 * solo dice si la envolvente PODRIA calcularse con los datos de la ejecucion y, si no, que insumo
 * falta, para que la UI pueda explicarlo con precision.
 *
 * - [AVAILABLE_GATED]: todos los insumos estan y hay recalculo; la envolvente PODRIA calcularse con
 *   estos datos, pero NO se muestra hasta que cierre la revision cientifica independiente de T1.
 *   DECLARAR DISPONIBILIDAD NO ES GARANTIZAR BANDA: ver la PRECONDICION DURA de [FvpEngine.sensitivityBand].
 * - [MISSING_FLIGHT_TIME] / [MISSING_FPS]: al menos una condicion SELECCIONADA no declara ese dato
 *   (o lo declara inutilizable: no finito o <= 0). El motivo nombra QUE insumo falta, no la
 *   condicion concreta: la copia de usuario se limita a declarar la carencia sin exponer numeros.
 * - [NO_RECALCULATION]: la ejecucion mostrada no tiene calculo con el motor actual, asi que no hay
 *   serie de la que partir y las carencias de vuelo/fps describirian insumos que nadie consumiria.
 *
 * PRECEDENCIA (cuando falta mas de una cosa gana el primer eslabon de la cadena):
 * [NO_RECALCULATION] > [MISSING_FLIGHT_TIME] > [MISSING_FPS] > [AVAILABLE_GATED]. El vuelo va antes
 * que la fps porque sin vuelo no hay ni altura que perturbar (h = g·t²/8) y la fps solo hace falta
 * para PERTURBAR esa altura. La decision es determinista y no depende del orden de las listas.
 *
 * FALLA EN CERRADO: una posicion sin entrada o un tamano dispar nunca devuelven [AVAILABLE_GATED], y
 * con recalculo pero sin ninguna condicion declarada se responde [MISSING_FLIGHT_TIME]. Lo unico que
 * se declara disponible es una serie con TODAS las entradas completas.
 *
 * @param flightTimesS vuelo total [s] del intento seleccionado de cada condicion, mismo orden que
 *   `selectedConditionInputs()`; `null` en la condicion que no lo declara.
 * @param fpsPerCondition fps declarada [Hz] del intento seleccionado de cada condicion, mismo orden.
 * @param hasRecalculation existe una ejecucion con el motor actual de la que partiria la envolvente:
 *   el recalculo de una fila guardada o el calculo vivo de un borrador. Un ORIGINAL persistido sin
 *   recalculo (o una fila que esta version no sabe recalcular) es [NO_RECALCULATION].
 */
internal fun fvpSensitivityAvailability(
    flightTimesS: List<Double?>,
    fpsPerCondition: List<Double?>,
    hasRecalculation: Boolean,
): FvpSensitivityAvailability {
    if (!hasRecalculation) return FvpSensitivityAvailability.NO_RECALCULATION
    // Una entrada AUSENTE de cualquiera de las dos listas cuenta como insumo que falta: asi un
    // tamano dispar nunca puede producir una banda a medias (el codigo de cada condicion sin
    // entrada es `null`, exactamente igual que si la declarara nula).
    val positions = 0 until maxOf(flightTimesS.size, fpsPerCondition.size)
    // Sin ninguna posicion no hay nada declarado: se falla en cerrado (una ejecucion calculada
    // tiene >= MIN_CONDITIONS condiciones, de modo que listas vacias son un incumplimiento del
    // contrato) en vez de declarar disponible una envolvente sin una sola entrada.
    if (positions.isEmpty()) return FvpSensitivityAvailability.MISSING_FLIGHT_TIME
    if (positions.any { !flightTimesS.getOrNull(it).isUsableSensitivityInput() }) {
        return FvpSensitivityAvailability.MISSING_FLIGHT_TIME
    }
    if (positions.any { !fpsPerCondition.getOrNull(it).isUsableSensitivityInput() }) {
        return FvpSensitivityAvailability.MISSING_FPS
    }
    return FvpSensitivityAvailability.AVAILABLE_GATED
}
