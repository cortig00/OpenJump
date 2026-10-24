package com.openjump.app.ui.fvp

import com.openjump.app.data.FvpAttemptEntity
import com.openjump.app.data.FvpConditionEntity
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.fvp.FvpAttemptDraft as FvpEligibilityAttemptDraft
import com.openjump.app.fvp.FvpConditionDraft as FvpEligibilityConditionDraft
import com.openjump.app.fvp.FvpEligibility
import com.openjump.app.fvp.FvpEligibilityFlag
import com.openjump.app.fvp.FvpEngine
import com.openjump.app.fvp.FvpQualityState
import com.openjump.app.fvp.FvpSensitivityAvailability
import com.openjump.app.fvp.FvpSeriesInput
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.fvp.fvpQualityState
import com.openjump.app.fvp.fvpSensitivityAvailability
import com.openjump.app.fvp.selectedValidAttemptIndex
import com.openjump.app.fvp.toQualityInput
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.FvpResult
import com.openjump.fvp.FvpWarning

/**
 * Avisos mostrados en la UI F-V-P, en orden determinista de declaracion.
 *
 * El nombre coincide con la clave de recurso `fvp_warning_<name>` (minusculas); la traduccion la
 * resuelve la capa Compose, no este archivo. Reglas vinculantes:
 * - `NON_PHYSICAL` -> V0/Pmax se muestran como valor indefinido, NUNCA 0.0.
 * - `R2_UNDEFINED` SUSTITUYE a `LOW_R2` cuando R^2 no es finito (no se puede juzgar el ajuste).
 * - `SNAPSHOT_RECALCULATED` solo con una evaluacion guardada cuyo snapshot no coincide CON un
 *   recalculo que existe de verdad (`MISMATCH`, `STALE`, `STALE_METHOD`).
 * - `SNAPSHOT_UNAVAILABLE` SOLO cuando no hubo recalculo (`RECALCULATION_UNAVAILABLE`): nunca se
 *   declara un recalculo que no ocurrio y los valores que se ven son los persistidos.
 */
enum class FvpUiWarning {
    FEW_POINTS,
    DIAGNOSTIC_ONLY,
    NARROW_VELOCITY_RANGE,
    LOW_R2,
    NON_PHYSICAL,
    HIGH_EXTRAPOLATION,
    FPS_BLOCKED,
    TIMING_CONDITIONAL,
    TIMING_UNKNOWN,
    PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE,
    SNAPSHOT_RECALCULATED,

    /**
     * La fila guardada no se puede recalcular con la version actual: se muestran los valores
     * persistidos y NUNCA se afirma que exista un recalculo con el que compararlos.
     */
    SNAPSHOT_UNAVAILABLE,

    R2_UNDEFINED,

    /**
     * h_po declarado SIN hito/procedimiento/fecha verificables: la serie se registra con origen
     * `MANUAL` (estimacion) y NUNCA debe presentarse como medicion (gate B1).
     */
    PUSH_OFF_MANUAL_SOURCE,

    /**
     * La serie guarda un aviso (`warningsCsv`) que esta version NO clasifica: se declara como
     * limitacion GENERICA visible (Q2) en vez de eliminarlo en silencio. La politica de calidad lo
     * trata como DIAGNOSTIC y la serie no produce deltas.
     */
    UNCLASSIFIED_WARNING,

    /**
     * B4b: masa corporal DECLARADA (no pesada). `bodyMassMeasurement = DECLARED` es INDEPENDIENTE
     * de `bodyMassSource` (`PROFILE`/`MANUAL`): declara que el valor NO procede de una pesada
     * verificada en el flujo, de modo que la serie no puede presentarla como medida.
     */
    BODY_MASS_DECLARED,

    /**
     * B4b: masa corporal SIN procedencia declarada (`UNKNOWN`): no consta si se peso o solo se
     * declaro. `UNKNOWN` domina a `DECLARED` (peor caso) y jamas se promociona.
     */
    BODY_MASS_UNKNOWN,

    /**
     * B4b: carga anadida NOMINAL (no pesada) en ALGUNA condicion de la serie: el valor es el
     * esperado por la condicion, no una pesada verificada. Se declara el PEOR caso: con alguna
     * condicion `UNKNOWN` manda `ADDED_LOAD_UNKNOWN` y este aviso no se anade.
     */
    ADDED_LOAD_NOMINAL,

    /**
     * B4b: carga anadida SIN procedencia declarada (`UNKNOWN`) en ALGUNA condicion: no consta si
     * se peso, se declaro o es nominal. Peor caso de la carga, que domina a `NOMINAL`.
     */
    ADDED_LOAD_UNKNOWN,

    /**
     * B4b: algun intento SELECCIONADO no declara `timeMapping = IDENTITY_VERIFIED` (incluye
     * `UNKNOWN` y las condiciones sin intento seleccionado registrado): la serie NO puede afirmar
     * tiempo fisico verificado. El peor intento seleccionado decide.
     */
    TIME_MAPPING_UNVERIFIED,

    /**
     * B4b: contexto de adquisicion SIN declarar (`UNKNOWN`, o una clave que esta version no
     * reconoce): NO equivale a "misma adquisicion" y no se infiere por cercania de fechas.
     */
    ACQUISITION_CONTEXT_UNKNOWN,

    /**
     * B4b: contexto de adquisicion `HISTORICAL_COMPOSITE`: la fila declara un conjunto compuesto/
     * retrospectivo, no una unica adquisicion.
     */
    ACQUISITION_CONTEXT_COMPOSITE,
}

/** Clave de almacenamiento de la masa corporal pesada (misma que `fvp_assessments.bodyMassMeasurement`). */
private const val FVP_BODY_MASS_MEASUREMENT_WEIGHED = "WEIGHED"

/** Clave de almacenamiento de la masa corporal declarada, NO pesada. */
private const val FVP_BODY_MASS_MEASUREMENT_DECLARED = "DECLARED"

/** Clave de almacenamiento de la carga anadida PESADA (misma que `fvp_conditions.addedLoadSource`). */
private const val FVP_ADDED_LOAD_SOURCE_WEIGHED = "WEIGHED"

/** Clave de almacenamiento de la carga anadida NOMINAL (valor esperado, no pesado). */
private const val FVP_ADDED_LOAD_SOURCE_NOMINAL = "NOMINAL"

/** Mapeo temporal verificado (misma clave que `fvp_attempts.timeMapping`). */
private const val FVP_TIME_MAPPING_IDENTITY_VERIFIED = "IDENTITY_VERIFIED"

/** Contexto de adquisicion declarado como UNICA adquisicion (ver el contrato B0). */
private const val FVP_ACQUISITION_CONTEXT_SAME = "SAME_ACQUISITION"

/** Contexto de adquisicion declarado como compuesto/retrospectivo (ver el contrato B0). */
private const val FVP_ACQUISITION_CONTEXT_COMPOSITE = "HISTORICAL_COMPOSITE"

/** Clave de almacenamiento de h_po medido (hito + procedimiento + fecha; gate B1). */
internal const val FVP_PUSH_OFF_SOURCE_MEASURED = "MEASURED"

/** Clave de almacenamiento de h_po declarado como estimacion manual (sin metadatos B1). */
internal const val FVP_PUSH_OFF_SOURCE_MANUAL = "MANUAL"

/**
 * Procedencia B1 EFECTIVA de h_po, regla unica del flujo: `MEASURED` exige hito anatomico y
 * procedimiento NO vacios + fecha de medicion, y que la serie no se declare ya `MANUAL`; cualquier
 * otro caso es `MANUAL` (estimacion declarada, nunca silenciosa).
 *
 * Se aplica igual a un borrador vivo (declaredSource = `null`) y a una fila persistida, de modo que
 * una serie antigua que se declare `MEASURED` sin metadatos verificables se presenta como `MANUAL`
 * con el aviso dedicado, jamas como medicion.
 */
internal fun fvpEffectivePushOffSource(
    declaredSource: String?,
    landmark: String?,
    procedure: String?,
    measuredAt: Long?,
): String = if (declaredSource != FVP_PUSH_OFF_SOURCE_MANUAL &&
    !landmark.isNullOrBlank() && !procedure.isNullOrBlank() && measuredAt != null
) {
    FVP_PUSH_OFF_SOURCE_MEASURED
} else {
    FVP_PUSH_OFF_SOURCE_MANUAL
}

/**
 * Punto por condicion listo para tabla/grafica. [measured] indica que el punto cae dentro del rango
 * de velocidad medido `[Vmin,Vmax]` (los puntos de condicion son datos medidos por definicion).
 * [method]/[sourceFps]/[exclusionReason] son trazabilidad del intento seleccionado.
 */
data class FvpPointUi(
    val label: String,
    val heightM: Double,
    val forceN: Double,
    val velocityMps: Double,
    val powerW: Double,
    val valid: Boolean,
    val measured: Boolean,
    val method: FvpMeasureMethod? = null,
    val sourceFps: Double? = null,
    val exclusionReason: String? = null,
)

/** Intento seleccionado de una condicion, tal como se muestra en la tabla de puntos. */
data class FvpAttemptUi(
    val heightM: Double?,
    val method: FvpMeasureMethod,
    val sourceFps: Double?,
    val valid: Boolean,
    val exclusionReason: String? = null,
)

/**
 * Snapshot persistido de una evaluacion guardada (auditoria): la UNICA fuente de la vista
 * `ORIGINAL`. El recalculo con el motor actual vive en su propia vista (`RECALCULATION`) y solo
 * alimenta ademas la nota de discrepancia ([FvpUiWarning.SNAPSHOT_RECALCULATED]).
 */
data class FvpStoredSnapshot(
    val f0N: Double,
    val slopeFv: Double,
    val sfvNsPerM: Double,
    val v0Mps: Double?,
    val pmaxW: Double?,
    val r2: Double?,
    val conditionCount: Int,
    val vMinMps: Double,
    val vMaxMps: Double,
    val fMinN: Double,
    val fMaxN: Double,
    val f0RelNPerKg: Double?,
    val pmaxRelWPerKg: Double?,
)

/**
 * Insumos de la sensibilidad temporal de una serie: el vuelo total [s] y la fps declarada [Hz] del
 * intento SELECCIONADO de cada condicion, en el MISMO orden que `selectedConditionInputs()`
 * (`conditionKey` ascendente). `null` en la posicion de una condicion cuyo intento seleccionado no
 * declara ese dato, o que no tiene intento seleccionado: NUNCA un valor por defecto.
 *
 * No es un resultado ni una medida: es la lista de entradas que el gate G-SCIENCE mantiene sin
 * publicar (los rangos no se muestran). Solo decide la DISPONIBILIDAD.
 */
internal data class FvpSensitivityInputs(
    val flightTimesS: List<Double?>,
    val fpsPerCondition: List<Double?>,
)

/**
 * Insumos de sensibilidad de un BORRADOR vivo: los mismos intentos que `selectedAttemptsByCondition()`
 * (regla canonica: intento valido de mayor altura, primero ante empate), proyectados a vuelo + fps y
 * ordenados por `conditionKey`, el orden que usa [com.openjump.app.fvp.selectedConditionInputs].
 *
 * El intento DESCARTADO (invalido o no seleccionado) no aporta nada: si el seleccionado no declara
 * vuelo (p. ej. se midio de forma manual) o fps, la posicion queda `null` y la disponibilidad lo
 * declara; jamas se hereda el dato de otro intento.
 */
internal fun FvpDraft.selectedSensitivityInputs(): FvpSensitivityInputs {
    // La seleccion se hace sobre la serie de elegibilidad equivalente (mismo orden, 1:1): asi el
    // vuelo y la fps salen del MISMO intento del que sale la altura, que es la precondicion del
    // envelope ("el vuelo del que se derivo esa altura").
    val seriesInput = toSeriesInput()
    val selectedByKey = conditions.mapIndexed { index, condition ->
        val selectedIndex = seriesInput.selectedValidAttemptIndex(seriesInput.conditions[index])
        condition.conditionKey to selectedIndex?.let { condition.attempts[it] }
    }.sortedBy { it.first }
    return FvpSensitivityInputs(
        flightTimesS = selectedByKey.map { it.second?.flightTimeS },
        fpsPerCondition = selectedByKey.map { it.second?.sourceFps },
    )
}

/**
 * Insumos de sensibilidad de una fila GUARDADA, proyectados desde la VISTA que se esta mostrando
 * ([view], OBLIGATORIO y sin valor por defecto): la pareja vuelo/fps de cada condicion sale del
 * MISMO intento del que sale la altura que esa vista pinta, de modo que la proyeccion no puede
 * emparejar la altura de una toma con el vuelo de OTRA. Orden `conditionKey` ascendente, el del
 * motor (`selectedConditionInputs`).
 *
 * - `ORIGINAL`: el intento que la fila REGISTRA como seleccionado (`selectedAttemptOrdinal`, la misma
 *   proyeccion que la tabla de puntos). Esa vista muestra la ejecucion persistida, de modo que sus
 *   insumos son los del intento registrado, se haya reseleccionado o no al recalcular.
 * - `RECALCULATION`: la RESELECCION CANONICA sobre los intentos GUARDADOS (la misma regla
 *   [com.openjump.app.fvp.selectedValidAttemptIndex] y el mismo orden con el que el repositorio
 *   recalcula la serie: condiciones por `conditionKey`, intentos por `ordinal` y, ante empate, `id`),
 *   porque es el intento del que salen los numeros y la altura de ESA vista.
 *
 * Sin intento en la vista correspondiente (sin ordinal registrado, sin ordinal presente, o sin
 * candidato valido en la reseleccion) la posicion queda `null`: una fila antigua o importada puede no
 * traer el vuelo o la fps de ese intento, y eso es un motivo que la UI declara (MISSING_*) en vez de
 * rellenar con el valor de otro intento ni con un valor por defecto.
 *
 * La proyeccion del BORRADOR ([FvpDraft.selectedSensitivityInputs]) no necesita este parametro: un
 * borrador vivo tiene UNA sola ejecucion y su seleccion ya es la canonica.
 */
internal fun StoredFvpAssessment.selectedSensitivityInputs(view: FvpResultView): FvpSensitivityInputs {
    // Orden del MOTOR: condiciones por `conditionKey` ascendente e intentos por `ordinal` y, ante
    // empate, `id` (el mismo orden con el que el repositorio recalcula la serie). Las dos listas se
    // construyen juntas, de modo que `orderedAttempts[i]` es la lista de intentos que alimenta a
    // `series.conditions[i]`: la reseleccion no puede emparejar dos condiciones distintas.
    val orderedConditions = conditions.sortedBy { it.conditionKey }
    val orderedAttempts = orderedConditions.map { attemptsOf(it) }
    // Reseleccion canonica de la vista RECALCULATION (`null` = clave persistida no mapeable: la fila
    // no es recalculable, asi que NINGUNA posicion hereda el dato de otro intento).
    val canonicalSeries = if (view == FvpResultView.RECALCULATION) {
        canonicalSeriesInput(orderedConditions, orderedAttempts)
    } else {
        null
    }
    val positions = orderedConditions.mapIndexed { index, condition ->
        when (view) {
            // Ejecucion PERSISTIDA: el intento que la fila registra como seleccionado.
            FvpResultView.ORIGINAL -> persistedSelectedAttempt(condition)
                ?.let { it.flightTimeS to it.sourceFps }
                ?: (null to null)

            // Ejecucion RECALCULADA: el intento que elige la reselccion canonica entre los intentos
            // GUARDADOS de ESA condicion, con el orden en el que el motor los recibe.
            FvpResultView.RECALCULATION -> canonicalSeries
                ?.let { series -> series.selectedValidAttemptIndex(series.conditions[index]) }
                ?.let { selectedIndex -> orderedAttempts[index].getOrNull(selectedIndex) }
                ?.let { it.flightTimeS to it.sourceFps }
                ?: (null to null)
        }
    }
    return FvpSensitivityInputs(
        flightTimesS = positions.map { it.first },
        fpsPerCondition = positions.map { it.second },
    )
}

/** Intentos de una condicion guardada en el orden del repositorio (`ordinal` y, ante empate, `id`). */
private fun StoredFvpAssessment.attemptsOf(condition: FvpConditionEntity): List<FvpAttemptEntity> =
    attempts.filter { it.conditionId == condition.id }.sortedWith(compareBy({ it.ordinal }, { it.id }))

/**
 * Intento que la fila GUARDADA registra como seleccionado para una condicion
 * (`selectedAttemptOrdinal`), o `null` si no declara ordinal o no hay intento con ese ordinal.
 * Es la ejecucion PERSISTIDA (vista `ORIGINAL`), no una reseleccion.
 */
private fun StoredFvpAssessment.persistedSelectedAttempt(condition: FvpConditionEntity): FvpAttemptEntity? =
    condition.selectedAttemptOrdinal?.let { ordinal ->
        attempts.firstOrNull { it.conditionId == condition.id && it.ordinal == ordinal }
    }

/**
 * Serie de elegibilidad EQUIVALENTE a las filas GUARDADAS, o `null` si una clave persistida no se
 * puede mapear (protocolo, modo de h_po o metodo de medida): con esa fila el repositorio tampoco
 * recalcula, de modo que la vista de recalculo se queda sin insumos en vez de emparejarlos a ciegas.
 *
 * Se construye aqui (la funcion equivalente del repositorio es privada y queda fuera del alcance de
 * este slice) y solo aporta PROVENANCIA a la regla canonica
 * ([com.openjump.app.fvp.selectedValidAttemptIndex]), que unicamente lee `valid` y `heightM` de cada
 * intento (proyectados VERBATIM desde las filas) y el ORDEN de intentos de cada condicion: no se
 * reimplementa la regla de seleccion ni se inventan alturas, vuelos ni fps.
 */
private fun StoredFvpAssessment.canonicalSeriesInput(
    orderedConditions: List<FvpConditionEntity>,
    orderedAttempts: List<List<FvpAttemptEntity>>,
): FvpSeriesInput? {
    val protocol = FvpEngine.parseProtocol(assessment.protocolId) ?: return null
    val pushOffMode = FvpPushOffUiMode.fromStorageKey(assessment.pushOffMode) ?: return null
    return FvpSeriesInput(
        protocol = protocol,
        bodyMassKg = assessment.bodyMassKg,
        pushOffMode = pushOffMode.toEligibilityMode(),
        commonPushOffM = assessment.pushOffM,
        conditions = orderedConditions.mapIndexed { index, condition ->
            FvpEligibilityConditionDraft(
                conditionKey = condition.conditionKey,
                addedLoadKg = condition.addedLoadKg,
                pushOffM = condition.pushOffM,
                attempts = orderedAttempts[index].map { attempt ->
                    val method = FvpEngine.parseMethod(attempt.method) ?: return null
                    FvpEligibilityAttemptDraft(
                        heightM = attempt.heightM,
                        method = method,
                        sourceFps = attempt.sourceFps,
                        valid = attempt.valid,
                        exclusionReason = attempt.exclusionReason,
                    )
                },
            )
        },
    )
}

/**
 * Ejecucion que alimenta la pantalla de resultado. Una vista = UNA ejecucion: nunca se mezclan
 * tarjetas de una con puntos o etiquetas de la otra.
 *
 * - `ORIGINAL`: la ejecucion REGISTRADA de la serie. En una fila guardada son los valores
 *   persistidos y NINGUN punto (el snapshot no guarda puntos por condicion, de modo que la lista
 *   queda vacia y no se fabrica ninguno). En un borrador vivo no existe snapshot: su unica
 *   ejecucion es la actual, y el comportamiento es el de siempre.
 * - `RECALCULATION`: SOLO el perfil recalculado por el motor de esta version (escalares Y puntos).
 *   Sin recalculo no hay nada que mostrar: NUNCA cae al snapshot.
 */
enum class FvpResultView { ORIGINAL, RECALCULATION }

/**
 * Conmutador explicito ORIGINAL <-> RECALCULACION: solo cuando el estado declara que el recalculo
 * EXISTE y DIFIERE del snapshot (por numeros o por version).
 *
 * - `MISMATCH` / `STALE` / `STALE_METHOD` -> `true` (hay dos ejecuciones distintas que mostrar).
 * - `MATCHES` -> `false`: el recalculo es identico, de modo que una sola vista con puntos es
 *   honesta y no hay nada que conmutar.
 * - `RECALCULATION_UNAVAILABLE` -> `false`: no hubo recalculo.
 * - estado desconocido (`null`) -> `false`: no se declara una segunda ejecucion que no consta.
 */
internal fun fvpShowsViewToggle(status: FvpSnapshotStatus?): Boolean = when (status) {
    FvpSnapshotStatus.MISMATCH,
    FvpSnapshotStatus.STALE,
    FvpSnapshotStatus.STALE_METHOD,
    -> true

    FvpSnapshotStatus.MATCHES,
    FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
    null,
    -> false
}

/**
 * Vista UNICA (sin conmutador) de una fila guardada. `MATCHES` con recalculo conserva la vista
 * calculada: el repositorio certifica que sus numeros son los del snapshot, de modo que sus puntos
 * son honestos. En cualquier otro caso (sin recalculo o sin veredicto de coincidencia) manda el
 * original persistido.
 */
internal fun fvpDefaultStoredView(status: FvpSnapshotStatus?, hasRecalculation: Boolean): FvpResultView =
    if (status == FvpSnapshotStatus.MATCHES && hasRecalculation) {
        FvpResultView.RECALCULATION
    } else {
        FvpResultView.ORIGINAL
    }

/**
 * Valores de UNA ejecucion, listos para el modelo de presentacion. Los construye SOLO
 * [fvpViewSource]; no existe un tercer camino ni un campo que se rellene desde la otra fuente.
 */
internal data class FvpViewValues(
    val f0N: Double,
    val slopeFv: Double,
    val sfvNsPerM: Double,
    val v0Mps: Double?,
    val pmaxW: Double?,
    val r2: Double?,
    val conditionCount: Int,
    val vMinMps: Double,
    val vMaxMps: Double,
    val fMinN: Double,
    val fMaxN: Double,
    val f0RelNPerKg: Double?,
    val pmaxRelWPerKg: Double?,
)

/**
 * Fuente UNICA del modelo: la ejecucion elegida por la vista, sin mezcla posible.
 *
 * @param values numeros de esa ejecucion (snapshot persistido o perfil recalculado).
 * @param algorithmVersion version del algoritmo de ESA ejecucion (la persistida en el original, la
 *   del motor actual en el recalculo): el rotulo no puede atribuir una version a la otra ejecucion.
 * @param hasExecution hay una ejecucion detras de [values]: sin ella no se juzga el ajuste
 *   (`R2_UNDEFINED` no se declara sobre numeros que no existen).
 * @param fromStoredSnapshot los valores (y por tanto los VEREDICTOS que se muestran) son los de la
 *   ejecucion persistida; en otro caso son los del motor actual.
 */
internal data class FvpViewSource(
    val values: FvpViewValues,
    val algorithmVersion: String,
    val hasExecution: Boolean,
    val fromStoredSnapshot: Boolean,
) {
    /**
     * Puntos por condicion de la ejecucion CALCULADA; la vista `ORIGINAL` de un guardado no los
     * tiene (el snapshot no los guarda) y sin ejecucion no hay nada que dibujar.
     */
    val includesPoints: Boolean get() = hasExecution && !fromStoredSnapshot

    /**
     * La ejecucion mostrada ES un snapshot persistido y esa fuente NO guarda puntos por condicion
     * (el ORIGINAL de una fila guardada, se pueda recalcular o no). La lista vacia significa
     * "puntos que no viven en esta fuente", NO "serie sin mediciones": los puntos, si existen, son
     * los del recalculo. Una sola decision derivada de [fromStoredSnapshot]/[includesPoints].
     */
    val pointsUnavailableFromSource: Boolean get() = fromStoredSnapshot && !includesPoints
}

/**
 * Selecciona la UNICA ejecucion que alimenta la vista (D3 del plan 13):
 *
 * - `ORIGINAL` de una fila guardada (`snapshot != null`) -> SOLO el snapshot persistido, sin
 *   puntos y con la version persistida.
 * - Cualquier otra combinacion (`RECALCULATION`, o `ORIGINAL` de un borrador vivo sin snapshot)
 *   -> SOLO el perfil recalculado, con sus puntos y su version. Si hoy no hay perfil (fila no
 *   recalculable) los numeros quedan indefinidos: NUNCA se toma un dato del snapshot.
 *
 * Es una funcion pura sin ramas de conveniencia: cada fuente se decide por la vista y por la
 * existencia del snapshot, no por el valor de un campo.
 */
internal fun fvpViewSource(
    view: FvpResultView,
    snapshot: FvpStoredSnapshot?,
    result: FvpResult?,
    storedAlgorithmVersion: String,
    conditionCount: Int,
): FvpViewSource {
    if (view == FvpResultView.ORIGINAL && snapshot != null) {
        return FvpViewSource(
            values = FvpViewValues(
                f0N = snapshot.f0N,
                slopeFv = snapshot.slopeFv,
                sfvNsPerM = snapshot.sfvNsPerM,
                v0Mps = snapshot.v0Mps.finiteOrNull(),
                pmaxW = snapshot.pmaxW.finiteOrNull(),
                r2 = snapshot.r2.finiteOrNull(),
                conditionCount = snapshot.conditionCount,
                vMinMps = snapshot.vMinMps,
                vMaxMps = snapshot.vMaxMps,
                fMinN = snapshot.fMinN,
                fMaxN = snapshot.fMaxN,
                f0RelNPerKg = snapshot.f0RelNPerKg.finiteOrNull(),
                pmaxRelWPerKg = snapshot.pmaxRelWPerKg.finiteOrNull(),
            ),
            algorithmVersion = storedAlgorithmVersion,
            fromStoredSnapshot = true,
            hasExecution = true,
        )
    }
    // Sin perfil recalculado (fila no computable hoy) no hay numeros: la vista de recalculo los
    // deja indefinidos en vez de rellenarlos con el snapshot.
    val profile = result?.profile
    return FvpViewSource(
        values = FvpViewValues(
            f0N = profile?.f0N ?: Double.NaN,
            slopeFv = profile?.slopeFv ?: Double.NaN,
            sfvNsPerM = profile?.sfvNsPerM ?: Double.NaN,
            v0Mps = profile?.v0Mps.finiteOrNull(),
            pmaxW = profile?.pmaxW.finiteOrNull(),
            r2 = profile?.r2.finiteOrNull(),
            conditionCount = profile?.n ?: conditionCount,
            vMinMps = profile?.vMinMps ?: Double.NaN,
            vMaxMps = profile?.vMaxMps ?: Double.NaN,
            fMinN = profile?.fMinN ?: Double.NaN,
            fMaxN = profile?.fMaxN ?: Double.NaN,
            f0RelNPerKg = profile?.f0RelNPerKg.finiteOrNull(),
            pmaxRelWPerKg = profile?.pmaxRelWPerKg.finiteOrNull(),
        ),
        algorithmVersion = result?.algorithmVersion.orEmpty(),
        fromStoredSnapshot = false,
        hasExecution = profile != null,
    )
}

/**
 * Modelo de presentacion del resultado F-V-P. Kotlin puro (sin `android`/`androidx`): los numeros
 * se formatean en la capa Compose.
 *
 * Los campos nullable valen `null` cuando el valor del motor es NaN (nunca un 0.0 sustituto).
 */
data class FvpResultUiModel(
    /** `null` si la clave de protocolo persistida no la reconoce esta version (ni se inventa SJ/CMJ). */
    val protocolLabelKey: FvpProtocol?,
    val bodyMassKg: Double,
    val conditionCount: Int,
    /**
     * `true` con 2 o 3 condiciones en la ejecucion MOSTRADA: el recuento sale de [conditionCount]
     * (el persistido del snapshot en la vista `ORIGINAL`, el del perfil del motor en
     * `RECALCULATION`), NO de la elegibilidad.
     *
     * Puede DISCREPAR de [FvpQualityState.DIAGNOSTIC] en [quality], que juzga el recuento de la
     * ELEGIBILIDAD (derivado de las condiciones/procedimiento GUARDADOS): en una fila
     * legada/extranjera cuyo `conditionCount` persistido no cuadra con sus condiciones almacenadas
     * (el backup ACTUAL lo rechaza; una fila anterior o importada puede traerlo) este campo rotula el
     * recuento PERSISTIDO y `quality` la serie REAL. La divergencia es DELIBERADA (cada campo declara
     * su fuente, D3) y esta fijada por `FvpPresenterTest`: no se unifica ni se elige un ganador en
     * este slice.
     */
    val diagnosticOnly: Boolean,
    val profileEligible: Boolean,
    val timingQuality: FvpTimingQuality,
    /**
     * Estado de calidad DERIVADO ([com.openjump.app.fvp.fvpQualityState]) de la ejecucion MOSTRADA
     * (D3): la UNICA sintesis de elegibilidad + veredictos, para que ninguna pantalla tenga que
     * combinar por su cuenta `profileEligible` con los avisos. `PROFILE` es un subconjunto de
     * [profileEligible] y los avisos siguen en [warnings]; este campo es la lectura conjunta, no un
     * sustituto. La copia de usuario de cada estado es de la capa Compose (Q2), no de este archivo.
     *
     * Recuento de condiciones: el de la ELEGIBILIDAD ([FvpEligibility.conditionCount], derivada de las
     * condiciones/procedimiento GUARDADOS), no el de la ejecucion mostrada; por eso puede DISCREPAR de
     * [diagnosticOnly], que rotula el recuento de esa ejecucion (ver su KDoc).
     *
     * STALENESS (senal que un consumidor DEBE consultar): en la vista `ORIGINAL` los veredictos del
     * ajuste salen del snapshot PERSISTIDO y pueden haberse producido con umbrales/reglas de una
     * version anterior, de modo que este estado NO certifica su vigencia. Cualquier consumidor tiene
     * que mostrar junto a la afirmacion de calidad el estado del snapshot de la FILA
     * ([com.openjump.app.data.FvpSnapshotStatus] via `StoredFvpAssessment.snapshotStatus`): con
     * `STALE` o `STALE_METHOD` lo que se puede afirmar es "esto decia la version que lo guardo",
     * nunca "esto dice la politica actual".
     */
    val quality: FvpQualityState,
    val f0N: Double,
    val sfvNsPerM: Double,
    val slopeFv: Double,
    val v0Mps: Double?,
    val pmaxW: Double?,
    val r2: Double?,
    val vMinMps: Double,
    val vMaxMps: Double,
    val fMinN: Double,
    val fMaxN: Double,
    val f0RelNPerKg: Double?,
    val pmaxRelWPerKg: Double?,
    /**
     * Disponibilidad (NO los rangos) de la envolvente de sensibilidad temporal ante el error de
     * marcado del video. Se deriva de los insumos declarados por el llamante (`flightTimesS` /
     * `fpsPerCondition`, el intento seleccionado de cada condición) y no de un campo persistido: un
     * guardado antiguo puede no traer vuelo o fps y el modelo lo declara en vez de inventar una
     * entrada. `AVAILABLE_GATED` NO autoriza a pintar ningun rango, ancho ni numero: los rangos siguen
     * bloqueados por G-SCIENCE (plan 13, lineas 443-446).
     */
    val sensitivityAvailability: FvpSensitivityAvailability,
    val warnings: List<FvpUiWarning>,
    val points: List<FvpPointUi>,
    /**
     * `true` cuando `points` esta vacia porque la FUENTE mostrada no guarda puntos (el ORIGINAL de
     * una fila guardada: el snapshot persistido no los incluye), no porque la serie no tenga
     * mediciones. La pantalla debe explicar el motivo y como verlos, nunca decir "sin puntos
     * medidos". Falso en un borrador vivo y en la vista `RECALCULATION` (ahi los puntos SI existen).
     */
    val pointsUnavailableFromSource: Boolean,
    val algorithmVersion: String,
    val storedSnapshotMatches: Boolean?,
    /** Ejecucion de la que salen TODOS los valores de este modelo (nunca una mezcla). */
    val view: FvpResultView,
) {
    /** V0/Pmax interpretables: solo con un perfil fisico (nunca se sustituyen por 0.0). */
    val hasPhysicalIntercepts: Boolean get() = v0Mps != null && pmaxW != null

    /** Puntos proyectables con la curva P(V) (excluye los que no son finitos). */
    val plottablePoints: List<FvpPointUi>
        get() = points.filter { it.velocityMps.isFinite() && it.forceN.isFinite() && it.powerW.isFinite() }
}

/**
 * Presentador puro del resultado F-V-P. Reglas del contrato (cubiertas por `FvpPresenterTest`):
 * - NaN -> `null` en los campos nullable; ningun 0.0 sustituto.
 * - avisos deterministas: orden de declaracion del enum, sin duplicados.
 * - `R2_UNDEFINED` sustituye a `LOW_R2` cuando `r2 == null`.
 * - sin recálculo (`result == null` con snapshot persistido), mandan los números persistidos y
 *   avisa `SNAPSHOT_UNAVAILABLE` (nunca `SNAPSHOT_RECALCULATED`): no se inventa un resultado, ni
 *   un punto, ni un recálculo que no existió.
 * - `diagnosticOnly` con 2 o 3 condiciones; `profileEligible` exige elegibilidad y timing `ALLOW`.
 * - `quality` ([FvpQualityState]) es la lectura CONJUNTA de la ejecucion mostrada: elegibilidad
 *   (serie/procedimiento) + R^2 y avisos de esa MISMA ejecucion (persistidos para el original, del
 *   motor para el recalculo/borrador). Ver `fvpQualityState` para la tabla de precedencia. En este
 *   slice el estado NO lleva texto de usuario: la copia es de la capa Compose (Q2).
 * - puntos ordenados por V ascendente y sin puntos NaN.
 * - UNA sola ejecucion por vista (D3 del plan 13): todos los valores derivados (escalares, rangos,
 *   relativos, version y avisos que dependen de los numeros) salen de `view`; la vista `ORIGINAL`
 *   de un guardado NUNCA lee el perfil recalculado y la vista `RECALCULATION` nunca lee el snapshot.
 *   La vista `ORIGINAL` de un guardado no tiene puntos (el snapshot no los guarda) y el modelo lo
 *   DECLARA (`pointsUnavailableFromSource`): la pantalla explica que no viven en esa fuente, en vez
 *   de afirmar que la serie no tiene mediciones.
 * - avisos de la EJECUCION mostrada, no de la otra: los veredictos del ajuste (`NON_PHYSICAL`,
 *   `HIGH_EXTRAPOLATION`, `LOW_R2`/`R2_UNDEFINED`, `FEW_POINTS`, ...) acompanan a los numeros que
 *   los produjeron. Los persistidos solo se declaran en la vista original y los del motor actual
 *   solo en la calculada. Los de SERIE/procedimiento (elegibilidad, tiempo, B1) y el estado del
 *   snapshot son de la FILA y valen en las dos vistas.
 */
object FvpPresenter {

    fun present(
        result: FvpResult?,
        eligibility: FvpEligibility?,
        protocol: FvpProtocol?,
        bodyMassKg: Double?,
        conditionCount: Int,
        timingQuality: FvpTimingQuality,
        attemptByCondition: Map<String, FvpAttemptUi> = emptyMap(),
        storedWarnings: List<String> = emptyList(),
        storedSnapshot: FvpStoredSnapshot? = null,
        storedSnapshotStatus: FvpSnapshotStatus? = null,
        algorithmVersion: String = "",
        pushOffSource: String? = null,
        /**
         * Vuelo total [s] del intento SELECCIONADO de cada condicion EN LA VISTA mostrada, en el
         * orden de `selectedConditionInputs()` (`conditionKey` ascendente); `null` donde no se
         * declara. Alimenta SOLO la disponibilidad de la sensibilidad temporal y lo proyecta el
         * llamante desde la vista que pinta (`ORIGINAL`: intento registrado; `RECALCULATION`:
         * reseleccion canonica), de modo que la pareja vuelo/fps es siempre del intento que esa
         * vista muestra.
         */
        flightTimesS: List<Double?> = emptyList(),
        /** Fps declarada [Hz] de ese mismo intento seleccionado, por condicion y en el mismo orden. */
        fpsPerCondition: List<Double?> = emptyList(),
        /**
         * Existe una ejecucion con el motor actual de la que partiria la envolvente: el recalculo de
         * una fila guardada (`saved.result != null`) o el calculo vivo de un borrador
         * (`presentation.result != null`). Sin ella, la disponibilidad es `NO_RECALCULATION`.
         */
        hasRecalculation: Boolean = false,
        /**
         * Avisos de PROCEDENCIA B4b de la fila guardada ([FvpUiWarning.BODY_MASS_*],
         * [FvpUiWarning.ADDED_LOAD_*], [FvpUiWarning.TIME_MAPPING_UNVERIFIED],
         * [FvpUiWarning.ACQUISITION_CONTEXT_*]), ya derivados por el llamante con
         * [provenanceWarningsForUi] o [fvpStoredProvenanceWarnings]. Son de la FILA (valen en las
         * dos vistas) y el presentador solo los declara: no los interpreta, no los relaja y no
         * promociona ningun `UNKNOWN`/`NULL`. Vacio por defecto: un borrador vivo o un llamante
         * que no dispone de la entidad no declara procedencia en este camino.
         */
        provenanceWarnings: List<FvpUiWarning> = emptyList(),
        /**
         * Ejecucion que se pinta. Por defecto `ORIGINAL`: un borrador vivo no tiene snapshot, de
         * modo que su unica ejecucion es la actual (el comportamiento de siempre). La ruta
         * guardada elige la vista con [fvpDefaultStoredView] / [fvpShowsViewToggle].
         */
        view: FvpResultView = FvpResultView.ORIGINAL,
    ): FvpResultUiModel {
        val snapshot = storedSnapshot
        // Fuente UNICA: ni un campo se toma de la otra ejecucion (antes cada escalar hacia
        // `snapshot?.x ?: profile?.x`, de modo que una pantalla podia mezclar dos ejecuciones).
        val source = fvpViewSource(
            view = view,
            snapshot = snapshot,
            result = result,
            storedAlgorithmVersion = algorithmVersion,
            conditionCount = conditionCount,
        )
        val values = source.values

        val warnings = warningsFor(
            result = result,
            eligibility = eligibility,
            timingQuality = timingQuality,
            storedWarnings = storedWarnings,
            storedSnapshot = storedSnapshot,
            storedSnapshotStatus = storedSnapshotStatus,
            hasExecution = source.hasExecution,
            fromStoredSnapshot = source.fromStoredSnapshot,
            r2 = values.r2,
            pushOffSource = pushOffSource,
            provenanceWarnings = provenanceWarnings,
        )

        // Procedencia del estado de calidad (D3), declarada para que Q2 pueda etiquetarlo sin
        // mentir: los campos de SERIE/procedimiento vienen de la elegibilidad actual y el R^2 y los
        // avisos de la ejecucion MOSTRADA, en la MISMA fuente que los numeros del modelo:
        // - `ORIGINAL` de una fila guardada: R^2 del SNAPSHOT persistido y nombres de aviso
        //   PERSISTIDOS (`warningsCsv`), nunca los del recalculo.
        // - `RECALCULATION` y borrador vivo (sin snapshot): R^2 y avisos del MOTOR actual.
        // Si la fuente mostrada no tiene ejecucion (`values.r2 == null`), `r2Finite` es falso y el
        // estado cae en DIAGNOSTIC (no hay ajuste utilizable que juzgar en esa fuente).
        val shownWarningNames: Set<String> = if (source.fromStoredSnapshot) {
            storedWarnings.toSet()
        } else {
            result?.profile?.warnings?.map { it.name }?.toSet().orEmpty()
        }
        val quality = eligibility
            ?.toQualityInput(r2Finite = values.r2 != null, warningNames = shownWarningNames)
            ?.let(::fvpQualityState)
            // Sin elegibilidad no hay serie validada: no se declara nada mejor que INCOMPLETE.
            ?: FvpQualityState.INCOMPLETE

        return FvpResultUiModel(
            protocolLabelKey = protocol,
            bodyMassKg = bodyMassKg ?: Double.NaN,
            conditionCount = values.conditionCount,
            diagnosticOnly = values.conditionCount == 2 || values.conditionCount == 3,
            profileEligible = eligibility?.profileEligible == true && timingQuality == FvpTimingQuality.ALLOW,
            timingQuality = timingQuality,
            quality = quality,
            f0N = values.f0N,
            sfvNsPerM = values.sfvNsPerM,
            slopeFv = values.slopeFv,
            v0Mps = values.v0Mps,
            pmaxW = values.pmaxW,
            r2 = values.r2,
            vMinMps = values.vMinMps,
            vMaxMps = values.vMaxMps,
            fMinN = values.fMinN,
            fMaxN = values.fMaxN,
            f0RelNPerKg = values.f0RelNPerKg,
            pmaxRelWPerKg = values.pmaxRelWPerKg,
            // G-SCIENCE (plan 13): la envolvente de sensibilidad temporal se calcula en `:fvp`, pero
            // aqui solo se publica si esta DISPONIBLE, nunca sus rangos.
            sensitivityAvailability = fvpSensitivityAvailability(
                flightTimesS = flightTimesS,
                fpsPerCondition = fpsPerCondition,
                hasRecalculation = hasRecalculation,
            ),
            warnings = warnings,
            // Los puntos por condicion solo existen en la ejecucion CALCULADA; la vista ORIGINAL de
            // un guardado queda sin puntos (no se fabrican puntos historicos que nadie midio).
            points = if (source.includesPoints) {
                pointsFor(result, values.vMinMps, values.vMaxMps, attemptByCondition)
            } else {
                emptyList()
            },
            pointsUnavailableFromSource = source.pointsUnavailableFromSource,
            algorithmVersion = source.algorithmVersion,
            storedSnapshotMatches = snapshot?.let { storedSnapshotMatchesFor(storedSnapshotStatus) },
            view = view,
        )
    }

    /** Puntos del motor: NaN fuera, orden ascendente por V, con la trazabilidad del intento. */
    private fun pointsFor(
        result: FvpResult?,
        vMinMps: Double,
        vMaxMps: Double,
        attemptByCondition: Map<String, FvpAttemptUi>,
    ): List<FvpPointUi> = result?.points.orEmpty()
        .filter { it.forceN.isFinite() && it.velocityMps.isFinite() && it.powerW.isFinite() }
        .sortedBy { it.velocityMps }
        .map { point ->
            val attempt = attemptByCondition[point.conditionId]
            FvpPointUi(
                label = point.conditionId,
                heightM = point.heightM,
                forceN = point.forceN,
                velocityMps = point.velocityMps,
                powerW = point.powerW,
                valid = attempt?.valid ?: true,
                measured = vMinMps.isFinite() && vMaxMps.isFinite() &&
                    point.velocityMps >= vMinMps && point.velocityMps <= vMaxMps,
                method = attempt?.method,
                sourceFps = attempt?.sourceFps,
                exclusionReason = attempt?.exclusionReason,
            )
        }

    private fun warningsFor(
        result: FvpResult?,
        eligibility: FvpEligibility?,
        timingQuality: FvpTimingQuality,
        storedWarnings: List<String>,
        storedSnapshot: FvpStoredSnapshot?,
        storedSnapshotStatus: FvpSnapshotStatus?,
        hasExecution: Boolean,
        fromStoredSnapshot: Boolean,
        r2: Double?,
        pushOffSource: String?,
        provenanceWarnings: List<FvpUiWarning>,
    ): List<FvpUiWarning> {
        val warnings = LinkedHashSet<FvpUiWarning>()
        // B4b: procedencia de la FILA (masa, carga, tiempo y contexto de adquisicion). Se declara
        // tal cual la deriva el llamante de la entidad: el presentador no promociona un UNKNOWN ni
        // la convierte en un veredicto de calidad.
        warnings += provenanceWarnings
        // B1 (h_po declarado sin metadatos verificables) y los avisos de elegibilidad/tiempo son
        // propiedades de la SERIE y del procedimiento, las MISMAS en las dos ejecuciones: valen en
        // las dos vistas. Por eso el mapeo de la lista persistida se hace sin `pushOffSource` (que
        // solo anadiria ese aviso) y B1 se declara aparte, una sola vez.
        if (pushOffSource != null && pushOffSource != FVP_PUSH_OFF_SOURCE_MEASURED) {
            warnings += FvpUiWarning.PUSH_OFF_MANUAL_SOURCE
        }
        // Veredictos de la EJECUCION mostrada, nunca los de la otra: los persistidos acompanan a los
        // numeros del snapshot y los del motor actual a los del recalculo. Asi un recalculo no
        // fisico no puede etiquetar de no fisico a un original que si lo era (ni al reves).
        if (fromStoredSnapshot) {
            warnings += storedWarningsForUi(storedWarnings, pushOffSource = null)
        } else {
            result?.profile?.warnings?.forEach { warnings += it.toUiWarning() }
        }
        // Los flags sin aviso de usuario (mezcla de metodos) no se anuncian aqui: el flag sigue en
        // la elegibilidad y en el estado de calidad, sin inventar una etiqueta que diga otra cosa.
        eligibility?.flags?.forEach { flag -> flag.toUiWarning()?.let(warnings::add) }
        timingQuality.toUiWarning()?.let(warnings::add)
        if (storedSnapshot != null) snapshotWarningFor(storedSnapshotStatus)?.let(warnings::add)
        if (hasExecution && r2 == null) {
            warnings -= FvpUiWarning.LOW_R2
            warnings += FvpUiWarning.R2_UNDEFINED
        }
        return warnings.sortedBy { it.ordinal }
    }
}

/**
 * `snapshotMatches` de presentacion: la MISMA tabla que `StoredFvpAssessment.snapshotMatches`
 * (`MATCHES` -> true, `MISMATCH` -> false, cualquier otro estado o estado DESCONOCIDO -> null).
 */
internal fun storedSnapshotMatchesFor(status: FvpSnapshotStatus?): Boolean? = when (status) {
    FvpSnapshotStatus.MATCHES -> true
    FvpSnapshotStatus.MISMATCH -> false
    FvpSnapshotStatus.STALE,
    FvpSnapshotStatus.STALE_METHOD,
    FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
    // Sin estado no se declara nada: nunca se inventa un veredicto de coincidencia.
    null,
    -> null
}

/**
 * Aviso de UI del snapshot persistido, derivado SOLO del estado del repositorio:
 * - `MATCHES` o estado desconocido -> ninguno (no hay nada que declarar).
 * - `MISMATCH` / `STALE` / `STALE_METHOD` -> `SNAPSHOT_RECALCULATED` (el recalculo EXISTE y no
 *   coincide con el snapshot guardado, sea por numeros o por version).
 * - `RECALCULATION_UNAVAILABLE` -> `SNAPSHOT_UNAVAILABLE` (no hubo recalculo: afirmar que se
 *   "recalculo" seria falso).
 */
internal fun snapshotWarningFor(status: FvpSnapshotStatus?): FvpUiWarning? = when (status) {
    FvpSnapshotStatus.MATCHES, null -> null
    FvpSnapshotStatus.MISMATCH,
    FvpSnapshotStatus.STALE,
    FvpSnapshotStatus.STALE_METHOD,
    -> FvpUiWarning.SNAPSHOT_RECALCULATED
    FvpSnapshotStatus.RECALCULATION_UNAVAILABLE -> FvpUiWarning.SNAPSHOT_UNAVAILABLE
}

private fun FvpWarning.toUiWarning(): FvpUiWarning = when (this) {
    FvpWarning.FEW_POINTS -> FvpUiWarning.FEW_POINTS
    FvpWarning.NARROW_VELOCITY_RANGE -> FvpUiWarning.NARROW_VELOCITY_RANGE
    FvpWarning.LOW_R2 -> FvpUiWarning.LOW_R2
    FvpWarning.NON_PHYSICAL -> FvpUiWarning.NON_PHYSICAL
    FvpWarning.HIGH_EXTRAPOLATION -> FvpUiWarning.HIGH_EXTRAPOLATION
    FvpWarning.FPS_30_BLOCKED -> FvpUiWarning.FPS_BLOCKED
}

private fun FvpEligibilityFlag.toUiWarning(): FvpUiWarning? = when (this) {
    FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS -> FvpUiWarning.DIAGNOSTIC_ONLY
    FvpEligibilityFlag.FEW_CONDITIONS -> FvpUiWarning.FEW_POINTS
    FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE -> FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE
    FvpEligibilityFlag.TIMING_CONDITIONAL -> FvpUiWarning.TIMING_CONDITIONAL
    FvpEligibilityFlag.TIMING_BLOCKED -> FvpUiWarning.FPS_BLOCKED
    FvpEligibilityFlag.TIMING_UNKNOWN_NOT_ELIGIBLE -> FvpUiWarning.TIMING_UNKNOWN
    // La mezcla de metodos de medida NO se anuncia como aviso existente: ninguna etiqueta de
    // `FvpUiWarning` la describe sin mentir (`NARROW_VELOCITY_RANGE`/`LOW_R2` dirian otra cosa) y
    // crear una nueva exige copia propia (Q2) mas una rama en `FvpUiWarning.messageRes()`. El flag
    // sigue existiendo en el dominio y degrada la calidad de la serie a
    // [FvpQualityState.DIAGNOSTIC] (Q2: sin compatibilidad revisada), que es lo que la capa Compose
    // debe etiquetar. Se declara RECONOCIDO para que nunca se confunda con un nombre desconocido.
    FvpEligibilityFlag.MIXED_SELECTED_METHODS -> null
}

private fun FvpTimingQuality.toUiWarning(): FvpUiWarning? = when (this) {
    FvpTimingQuality.ALLOW -> null
    FvpTimingQuality.CONDITIONAL -> FvpUiWarning.TIMING_CONDITIONAL
    FvpTimingQuality.BLOCKED -> FvpUiWarning.FPS_BLOCKED
    FvpTimingQuality.UNKNOWN -> FvpUiWarning.TIMING_UNKNOWN
}

/**
 * Etiquetas de UI de los nombres PERSISTIDOS que la app ACTUAL declara, uno a uno: avisos del motor
 * ([FvpWarning]) y flags de elegibilidad ([FvpEligibilityFlag]), las dos familias que escribe
 * `warningsCsv`.
 *
 * La tabla es EXPLICITA y NO se deriva de los enums a proposito: un nombre anadido al dominio en el
 * futuro no hereda etiqueta ni queda "reconocido" solo por existir en el enum. Ese nombre no mapea
 * aqui y, como tampoco esta en [UNLABELLED_STORED_WARNING_NAMES], cae en
 * [FvpUiWarning.UNCLASSIFIED_WARNING]: la limitacion se VE (Q2) en vez de desaparecer en silencio.
 */
private val STORED_WARNING_UI: Map<String, FvpUiWarning> = mapOf(
    FvpWarning.FEW_POINTS.name to FvpUiWarning.FEW_POINTS,
    FvpEligibilityFlag.FEW_CONDITIONS.name to FvpUiWarning.FEW_POINTS,
    FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS.name to FvpUiWarning.DIAGNOSTIC_ONLY,
    FvpWarning.NARROW_VELOCITY_RANGE.name to FvpUiWarning.NARROW_VELOCITY_RANGE,
    FvpWarning.LOW_R2.name to FvpUiWarning.LOW_R2,
    FvpWarning.NON_PHYSICAL.name to FvpUiWarning.NON_PHYSICAL,
    FvpWarning.HIGH_EXTRAPOLATION.name to FvpUiWarning.HIGH_EXTRAPOLATION,
    FvpWarning.FPS_30_BLOCKED.name to FvpUiWarning.FPS_BLOCKED,
    FvpEligibilityFlag.TIMING_BLOCKED.name to FvpUiWarning.FPS_BLOCKED,
    FvpEligibilityFlag.TIMING_CONDITIONAL.name to FvpUiWarning.TIMING_CONDITIONAL,
    FvpEligibilityFlag.TIMING_UNKNOWN_NOT_ELIGIBLE.name to FvpUiWarning.TIMING_UNKNOWN,
    FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE.name to
        FvpUiWarning.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE,
    FvpUiWarning.SNAPSHOT_RECALCULATED.name to FvpUiWarning.SNAPSHOT_RECALCULATED,
)

/**
 * Exencion EXPLICITA (hoy un unico nombre): la mezcla de metodos de medida NO se anuncia como aviso
 * existente porque ninguna etiqueta de [FvpUiWarning] la describe sin mentir
 * (`NARROW_VELOCITY_RANGE`/`LOW_R2` dirian otra cosa) y crear una nueva exige copia propia (Q2) mas
 * una rama en `FvpUiWarning.messageRes()`. El flag sigue existiendo en el dominio y degrada la
 * calidad de la serie a [FvpQualityState.DIAGNOSTIC] (Q2: sin compatibilidad revisada), que es lo
 * que la capa Compose debe etiquetar. Se declara RECONOCIDO para que nunca se confunda con un
 * nombre desconocido.
 */
private val UNLABELLED_STORED_WARNING_NAMES: Set<String> =
    setOf(FvpEligibilityFlag.MIXED_SELECTED_METHODS.name)

/**
 * Nombres PERSISTIDOS que la app ACTUAL reconoce: los que llevan etiqueta en [STORED_WARNING_UI]
 * mas la exencion explicita [UNLABELLED_STORED_WARNING_NAMES]. Un nombre fuera de esta lista se
 * declara como limitacion generica, nunca se elimina en silencio.
 */
private val RECOGNISED_STORED_WARNING_NAMES: Set<String> =
    STORED_WARNING_UI.keys + UNLABELLED_STORED_WARNING_NAMES

/**
 * Traduce un nombre de la lista persistida `warningsCsv` (nombres de `FvpWarning` y
 * `FvpEligibilityFlag`) al aviso de UI; `null` si el nombre esta RECONOCIDO pero no tiene etiqueta
 * propia (ver [UNLABELLED_STORED_WARNING_NAMES]), o si es DESCONOCIDO: el llamante
 * ([storedWarningsForUi]) distingue los dos casos con [RECOGNISED_STORED_WARNING_NAMES] y declara
 * los desconocidos como limitacion generica, nunca los elimina en silencio.
 */
internal fun storedWarningToUi(name: String): FvpUiWarning? = STORED_WARNING_UI[name]

/**
 * Avisos de UI de una serie GUARDADA: nombres persistidos (`warningsCsv`) mas el requisito B1 de
 * h_po medido, que no se persiste como warning porque ya vive en la columna `pushOffSource`.
 * Determinista: orden de declaracion del enum, sin duplicados. Q2: un nombre que la app NO reconoce
 * no se elimina en silencio: se declara [FvpUiWarning.UNCLASSIFIED_WARNING].
 */
internal fun storedWarningsForUi(
    storedWarningNames: List<String>,
    pushOffSource: String?,
): List<FvpUiWarning> = buildList {
    storedWarningNames.forEach { name ->
        when (val warning = storedWarningToUi(name)) {
            null -> if (name !in RECOGNISED_STORED_WARNING_NAMES) {
                add(FvpUiWarning.UNCLASSIFIED_WARNING)
            }

            else -> add(warning)
        }
    }
    if (pushOffSource != null && pushOffSource != FVP_PUSH_OFF_SOURCE_MEASURED) {
        add(FvpUiWarning.PUSH_OFF_MANUAL_SOURCE)
    }
}.distinct().sortedBy { it.ordinal }

/**
 * Avisos de procedencia B4b de una fila GUARDADA, derivados SIN promocionar nada:
 *
 * - masa corporal: `WEIGHED` = pesada declarada verificada en el flujo (ningun aviso); `DECLARED`
 *   = valor declarado, NO pesado ([FvpUiWarning.BODY_MASS_DECLARED]); cualquier otra clave
 *   (`UNKNOWN` o una que esta version no reconoce) = sin declarar
 *   ([FvpUiWarning.BODY_MASS_UNKNOWN], el peor caso, que domina a `DECLARED`). Es INDEPENDIENTE de
 *   `bodyMassSource` (`PROFILE`/`MANUAL`), que NO entra aqui.
 * - carga anadida: si CUALQUIER condicion no declara `WEIGHED` se declara el PEOR caso
 *   ([FvpUiWarning.ADDED_LOAD_UNKNOWN] si alguna no es `WEIGHED` ni `NOMINAL`; en otro caso
 *   [FvpUiWarning.ADDED_LOAD_NOMINAL]). Una lista vacia (fila sin condiciones) no afirma nada.
 * - tiempo: si CUALQUIER intento SELECCIONADO no declara `IDENTITY_VERIFIED`
 *   ([FvpUiWarning.TIME_MAPPING_UNVERIFIED]). Un `null` (condicion sin intento seleccionado
 *   registrado, o sin intento con ese ordinal) NO es un mapeo verificado: tampoco promociona.
 * - contexto: `SAME_ACQUISITION` no anade aviso; `HISTORICAL_COMPOSITE` se declara compuesto/
 *   retrospectivo ([FvpUiWarning.ACQUISITION_CONTEXT_COMPOSITE]); `UNKNOWN` o una clave no
 *   reconocida NUNCA equivalen a "misma adquisicion"
 *   ([FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN]).
 *
 * Determinista: orden de declaracion del enum, sin duplicados. No toca numeros, calidad ni
 * snapshots: solo declara lo que la fila NO puede afirmar.
 */
internal fun fvpStoredProvenanceWarnings(
    bodyMassMeasurement: String,
    acquisitionContextKind: String,
    acquisitionContextKey: String?,
    addedLoadSourceByCondition: List<String>,
    selectedTimeMappingByCondition: List<String?>,
): List<FvpUiWarning> = buildList {
    when (bodyMassMeasurement) {
        FVP_BODY_MASS_MEASUREMENT_WEIGHED -> Unit
        FVP_BODY_MASS_MEASUREMENT_DECLARED -> add(FvpUiWarning.BODY_MASS_DECLARED)
        else -> add(FvpUiWarning.BODY_MASS_UNKNOWN)
    }
    when {
        addedLoadSourceByCondition.any {
            it != FVP_ADDED_LOAD_SOURCE_WEIGHED && it != FVP_ADDED_LOAD_SOURCE_NOMINAL
        } -> add(FvpUiWarning.ADDED_LOAD_UNKNOWN)

        addedLoadSourceByCondition.any { it != FVP_ADDED_LOAD_SOURCE_WEIGHED } ->
            add(FvpUiWarning.ADDED_LOAD_NOMINAL)
    }
    if (selectedTimeMappingByCondition.any { it != FVP_TIME_MAPPING_IDENTITY_VERIFIED }) {
        add(FvpUiWarning.TIME_MAPPING_UNVERIFIED)
    }
    when {
        // SAME_ACQUISITION sin clave declarada no prueba "misma adquisicion": defensa en
        // profundidad (ningun escritor validado puede producir esa combinacion).
        acquisitionContextKind == FVP_ACQUISITION_CONTEXT_SAME && acquisitionContextKey == null ->
            add(FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN)

        acquisitionContextKind == FVP_ACQUISITION_CONTEXT_SAME -> Unit
        acquisitionContextKind == FVP_ACQUISITION_CONTEXT_COMPOSITE ->
            add(FvpUiWarning.ACQUISITION_CONTEXT_COMPOSITE)

        else -> add(FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN)
    }
}.distinct().sortedBy { it.ordinal }

/**
 * Proyeccion de la procedencia de una fila GUARDADA a los avisos de [fvpStoredProvenanceWarnings]:
 * las claves se leen VERBATIM de la entidad (nunca se derivan de fps, fechas, perfiles ni de la
 * existencia de PTS) y el intento seleccionado es el que la fila REGISTRA
 * (`selectedAttemptOrdinal`), la misma proyeccion que la tabla de puntos de la vista `ORIGINAL`.
 */
internal fun StoredFvpAssessment.provenanceWarningsForUi(): List<FvpUiWarning> =
    fvpStoredProvenanceWarnings(
        bodyMassMeasurement = assessment.bodyMassMeasurement,
        acquisitionContextKind = assessment.acquisitionContextKind,
        acquisitionContextKey = assessment.acquisitionContextKey,
        addedLoadSourceByCondition = conditions.map { it.addedLoadSource },
        selectedTimeMappingByCondition = conditions.map { condition ->
            persistedSelectedAttempt(condition)?.timeMapping
        },
    )

private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }
