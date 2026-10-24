package com.openjump.app.fvp

import com.openjump.fvp.FvpConditionInput
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.MAX_JUMP_HEIGHT_M
import com.openjump.fvp.MAX_PUSH_OFF_M
import com.openjump.fvp.MIN_CONDITIONS
import com.openjump.fvp.MIN_PUSH_OFF_M
import com.openjump.fvp.RECOMMENDED_MIN_CONDITIONS

/**
 * Capa de elegibilidad/validacion cientifica de una serie F-V-P, PREVIA a la llamada al motor
 * numerico (`:fvp`, envuelto por [FvpEngine]).
 *
 * Este archivo NO calcula nada: no hay formulas F-V-P, ni OLS, ni derivados. Solo (a) valida las
 * condiciones cientificas de la serie, (b) selecciona el intento valido de mayor altura por
 * condicion y (c) decide si se puede llamar al motor ([FvpEligibility.canCompute]) y si la serie
 * sirve como perfil normal ([FvpEligibility.profileEligible]). Toda la matematica vive en
 * `:fvp`; aqui se reutilizan sus constantes de dominio ([MIN_CONDITIONS], [MIN_PUSH_OFF_M],
 * [MAX_PUSH_OFF_M], [MAX_JUMP_HEIGHT_M]) para no duplicar limites.
 *
 * Reglas vinculantes aplicadas (docs/research/fvp/09-INTEGRATION_GATE_ADDENDUM.md,
 * docs/research/fvp/10-SCIENTIFIC_GATE_REVIEW.md B3/B4/B6, 03-MATHEMATICAL_SPEC.md):
 * - Seleccion del intento tecnicamente VALIDO de mayor altura (nunca la mayor altura sin
 *   revisar); una carga = un punto (sin cargas duplicadas).
 * - >=4 condiciones para perfil normal; 2 = solo diagnostico; 3 = condicional.
 * - Calidad temporal agregada por el PEOR intento seleccionado, con `desconocido` peor que
 *   `bloqueado` (un fps ausente no es elegible automaticamente; gate B6).
 * - h_po medido: dominio (0.05, 0.8] del motor y aviso fuera de la ventana recomendada 0.20-0.60.
 *
 * Caracteristicas de diseno:
 * - Kotlin puro, determinista y SIN estado: mismas entradas -> mismas salidas.
 * - Sin dependencias Android/Room/Media3/coroutines/logging.
 * - Sin excepciones de negocio: los problemas se devuelven como datos
 *   ([FvpEligibility.blocking] / [FvpEligibility.flags]).
 * - SJ y CMJ NO pueden mezclarse por tipo: [FvpSeriesInput.protocol] es un unico protocolo para
 *   toda la serie y NO existe protocolo por condicion (gate B2: prohibida la mezcla SJ+CMJ).
 * - [selectedValidAttempt] / [selectedValidAttemptIndex] son la **REGLA CANONICA** de seleccion de
 *   intento: "el intento VALIDO de mayor altura, gana el PRIMERO en orden de entrada ante empate".
 *   Cualquier otra capa (presentacion, persistencia, importacion) DEBE reflejar exactamente esta
 *   regla en vez de reimplementarla; las copias locales que aun quedan son deuda a migrar
 *   (Wave 1b), no una segunda fuente de verdad.
 * - La validacion de dominio es la MISMA que exige la persistencia para TODOS los intentos,
 *   validos o no (altura finita en `(0, 1.2]`), de modo que una serie que pasa [checkSeries] nunca
 *   se rechaza al persistir. Si hay duda se falla en cerrado: se bloquea la serie, no se relaja el
 *   contrato de datos.
 *
 * Alcance de esta capa (declarado, no una omision accidental): NO valida la consistencia de
 * fechas/contexto de sesion y NO calcula R^2 (eso exige el motor). La mezcla de metodos de medida
 * ([FvpAttemptDraft.method]) SI se vigila, pero SOLO entre los intentos SELECCIONADOS (la regla
 * canonica de una condicion = un punto): mezclar metodos entre condiciones distintas hace la
 * comparacion no homogenea y se reporta como [FvpEligibilityFlag.MIXED_SELECTED_METHODS]; un
 * intento DESCARTADO con otro metodo no contamina la serie.
 */

/** Minimo de FPS efectivos para considerar la captura elegible sin condiciones. Gate B6. */
private const val TIMING_ALLOW_FPS = 120.0

/** Umbral inferior de FPS efectivos: por debajo la captura queda bloqueada. Gate B6. */
private const val TIMING_CONDITIONAL_FPS = 60.0

/**
 * Limite inferior de la ventana RECOMENDADA de h_po [m]. Addendum 09, condicion 1.
 *
 * `internal` (no `private`) a proposito: es la UNICA fuente de esta ventana. La UI
 * (`pushOffRecommendedRangeText`) la usa para el texto del aviso y esta capa para decidir el flag
 * [FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE]; una copia local en la UI podria
 * divergir en silencio y mostrar un limite distinto del que se evalua.
 */
internal const val RECOMMENDED_MIN_PUSH_OFF_M = 0.20

/**
 * Limite superior de la ventana RECOMENDADA de h_po [m]. Addendum 09, condicion 1.
 *
 * Misma decision que [RECOMMENDED_MIN_PUSH_OFF_M]: declaracion UNICA compartida con la UI.
 */
internal const val RECOMMENDED_MAX_PUSH_OFF_M = 0.60

/** Flags temporales: cualquiera de ellos impide publicar un perfil normal. */
private val TIMING_FLAGS = setOf(
    FvpEligibilityFlag.TIMING_CONDITIONAL,
    FvpEligibilityFlag.TIMING_BLOCKED,
    FvpEligibilityFlag.TIMING_UNKNOWN_NOT_ELIGIBLE,
)

/**
 * Calidad temporal de UN intento a partir de sus FPS efectivos de origen (PTS fisicos, nunca
 * fps nominal ni frame/ordinal; gate B6).
 *
 * Umbrales: `>= 120` [ALLOW]; `60..<120` [CONDITIONAL]; `> 0` y `< 60` [BLOCKED]; `null`
 * [UNKNOWN]; `<= 0`, NaN o infinito [BLOCKED] (no es una cadencia fisica creible).
 * `UNKNOWN` no es elegible automaticamente: exige evidencia verificable o queda como borrador.
 *
 * Funcion pura y determinista; no lanza excepciones.
 */
fun fvpTimingQuality(sourceFps: Double?): FvpTimingQuality = when {
    sourceFps == null -> FvpTimingQuality.UNKNOWN
    !sourceFps.isFinite() -> FvpTimingQuality.BLOCKED
    sourceFps >= TIMING_ALLOW_FPS -> FvpTimingQuality.ALLOW
    sourceFps >= TIMING_CONDITIONAL_FPS -> FvpTimingQuality.CONDITIONAL
    else -> FvpTimingQuality.BLOCKED
}

/** Severidad creciente de la calidad temporal para agregar por el peor intento seleccionado. */
private val FvpTimingQuality.severity: Int
    get() = when (this) {
        FvpTimingQuality.ALLOW -> 0
        FvpTimingQuality.CONDITIONAL -> 1
        FvpTimingQuality.BLOCKED -> 2
        // Desconocido es PEOR que bloqueado: sin evidencia verificable no hay elegibilidad
        // automatica (gate B6: no ignorar los fps desconocidos al tomar el minimo de los conocidos).
        FvpTimingQuality.UNKNOWN -> 3
    }

/** Calidad temporal de un intento de la serie F-V. */
enum class FvpTimingQuality {
    /** >= 120 fps efectivos: elegible por defecto. */
    ALLOW,

    /** 60..<120 fps efectivos: condicional; hay que mostrar la sensibilidad temporal. */
    CONDITIONAL,

    /** < 60 fps efectivos (o cadencia invalida <= 0): se bloquea el perfilado. */
    BLOCKED,

    /** FPS de origen desconocidos: nunca elegible automaticamente. */
    UNKNOWN,
}

/**
 * Modo de agregacion de la distancia de impulso h_po de la serie.
 *
 * La decision (comun o por condicion) se registra a nivel de SERIE; el motor sigue recibiendo un
 * unico h_po por condicion. En [COMMON] los `pushOffM` por condicion se ignoran; en
 * [PER_CONDITION] se ignora `commonPushOffM`.
 */
enum class FvpPushOffMode {
    /** Un unico h_po medido compartido por todas las condiciones de la serie. */
    COMMON,

    /** Un h_po medido por condicion (p. ej. profundidad distinta por carga). */
    PER_CONDITION,
}

/**
 * Problema BLOQUEANTE de la serie: si hay alguno, `canCompute == false` y el motor NO debe
 * llamarse. Orden de la lista devuelta por [checkSeries]: orden de declaracion de este enum.
 */
enum class FvpEligibilityProblem {
    /** `bodyMassKg` no finita o <= 0 (la masa del sistema seria invalida). */
    INVALID_BODY_MASS,

    /** Menos de [MIN_CONDITIONS] condiciones: no hay recta F-V que ajustar. */
    TOO_FEW_CONDITIONS,

    /** `conditionKey` vacia o solo espacios: sin identificador estable no hay trazabilidad. */
    INVALID_CONDITION_KEY,

    /** Dos condiciones con la misma `conditionKey` (comparacion exacta, sin trim). */
    DUPLICATE_CONDITION_KEY,

    /** Dos condiciones con la misma carga adicional: una carga = un punto F-V. */
    DUPLICATE_LOAD,

    /** `addedLoadKg` no finita o < 0. */
    INVALID_ADDED_LOAD,

    /** h_po ausente para el modo declarado ([FvpPushOffMode.COMMON]: `commonPushOffM` null). */
    MISSING_PUSH_OFF,

    /** h_po declarado fuera del dominio del motor (0.05, 0.8] o no finito. */
    INVALID_PUSH_OFF,

    /** Condicion sin intentos o sin ningun intento con `valid == true`. */
    CONDITION_WITHOUT_VALID_ATTEMPT,

    /**
     * Altura de CUALQUIER intento (valido o NO) no finita, `<= 0` o `> 1.2 m`; el dominio es el
     * mismo que exige la persistencia (`FvpDao.save`), que valida tambien los intentos invalidos.
     */
    INVALID_HEIGHT,

    /**
     * TODAS las alturas seleccionadas son EXACTAMENTE iguales (con al menos [MIN_CONDITIONS]
     * selecciones): en el modelo la velocidad depende SOLO de la altura
     * (`v = sqrt(g * h / 2)`), asi que todas las velocidades salen identicas y el motor rechaza la
     * serie porque la pendiente F-V queda indefinida (`all measured velocities are identical`).
     *
     * Se bloquea aqui para que la UI muestre un motivo ACCIONABLE (pedir alturas distintas) en vez
     * de un boton Calcular deshabilitado con el detalle vacio. La regla es "no repetir la MISMA
     * altura en todas las condiciones": repetir una altura en dos condiciones es valido mientras
     * el conjunto NO sea todo igual (p. ej. 0.35 / 0.35 / 0.32 / 0.28 no se bloquea).
     *
     * El motor sigue siendo la AUTORIDAD: esta comprobacion cubre el rechazo tipico (velocidades
     * identicas) y no promete que cualquier otro conjunto de alturas distintas vaya a ajustar.
     */
    IDENTICAL_SELECTED_HEIGHTS,

    /**
     * Algun intento marcado como NO valido (`valid == false`) no declara motivo de exclusion
     * (`exclusionReason` nulo, vacio o solo espacios). Gate B3: un descarte tecnico sin motivo no es
     * auditable, de modo que la serie queda bloqueada en el DOMINIO.
     *
     * Esta regla vivia SOLO en la UI (`FvpConditionsScreen` deshabilitaba el boton Calcular), por lo
     * que cualquier otro llamante (repositorio, importacion, test, futura pantalla) podia persistir
     * una serie con un descarte sin justificar. Se anade AL FINAL del enum para no alterar los
     * ordinales ya existentes (el orden de la lista devuelta refleja la declaracion).
     */
    EXCLUSION_REASON_MISSING,
}

/**
 * Aviso NO bloqueante: permite calcular pero condiciona la INTERPRETACION del perfil.
 * Orden de la lista devuelta por [checkSeries]: orden de declaracion de este enum.
 */
enum class FvpEligibilityFlag {
    /** Exactamente 2 condiciones: recta solo diagnostica, nunca un perfil normal publicable. */
    DIAGNOSTIC_ONLY_TWO_CONDITIONS,

    /** 3 condiciones: ruta condicional (exige rango amplio y R^2 >= 0.95 en capas superiores). */
    FEW_CONDITIONS,

    /** Algun h_po efectivo fuera de la ventana recomendada 0.20-0.60 m (aviso, no bloqueo). */
    PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE,

    /** El peor intento seleccionado esta en 60..<120 fps: condicional con sensibilidad temporal. */
    TIMING_CONDITIONAL,

    /** El peor intento seleccionado esta por debajo de 60 fps: perfilado bloqueado en producto. */
    TIMING_BLOCKED,

    /** Algun intento seleccionado tiene fps desconocidos: no elegible automaticamente. */
    TIMING_UNKNOWN_NOT_ELIGIBLE,

    /**
     * Entre los intentos SELECCIONADOS (uno por condicion, la eleccion canonica) hay mas de un
     * [FvpMeasureMethod] distinto: la serie mezcla metodos de medida.
     *
     * Es un aviso NO bloqueante (la serie se puede calcular) pero condiciona la INTERPRETACION: la
     * comparacion entre series ya exige el mismo conjunto de metodos, de modo que una serie mixta no
     * es comparable en terminos homogeneos. Solo cuentan los intentos SELECCIONADOS: un intento
     * descartado o no seleccionado con otro metodo NO activa este aviso (no aporta ningun punto
     * F-V-P). Se anade AL FINAL del enum para no alterar los ordinales ya existentes.
     */
    MIXED_SELECTED_METHODS,
}

/**
 * Un intento de salto tal como lo entrega la capa de captura/importacion, ANTES de seleccion.
 *
 * @param heightM altura de salto del CdM [m]; se exige finita y en (0, 1.2] para poder publicarse.
 * @param method metodo de medida de la altura. Lo que se valida es la MEZCLA de metodos entre los
 *   intentos SELECCIONADOS de la serie ([FvpEligibilityFlag.MIXED_SELECTED_METHODS], aviso no
 *   bloqueante): un intento descartado con otro metodo no se tiene en cuenta.
 * @param sourceFps FPS efectivos del video origen (PTS fisicos), `null` si se desconocen.
 * @param valid decision de validez tecnica; solo los intentos validos pueden seleccionarse.
 * @param exclusionReason motivo tecnico de exclusion, obligatorio en la practica cuando
 *   `valid == false` (se conserva para la trazabilidad de la serie).
 */
data class FvpAttemptDraft(
    val heightM: Double,
    val method: FvpMeasureMethod,
    val sourceFps: Double?,
    val valid: Boolean = true,
    val exclusionReason: String? = null,
)

/**
 * Una condicion de carga de la serie con TODOS sus intentos (validos e invalidos).
 *
 * @param conditionKey identificador estable de la condicion (no vacio; comparacion exacta).
 * @param addedLoadKg carga adicional de la condicion [kg], finita y >= 0; unica en la serie.
 * @param pushOffM h_po medido de la condicion [m] (solo se usa en [FvpPushOffMode.PER_CONDITION]).
 * @param attempts intentos de la condicion; los invalidos se conservan.
 */
data class FvpConditionDraft(
    val conditionKey: String,
    val addedLoadKg: Double,
    val pushOffM: Double? = null,
    val attempts: List<FvpAttemptDraft> = emptyList(),
)

/**
 * Entrada completa de una serie F-V-P lista para validar (todavia NO calculada).
 *
 * @param protocol protocolo UNICO de la serie; mezclar SJ y CMJ es imposible por tipo.
 * @param bodyMassKg masa corporal en vacio usada como referencia [kg], finita y > 0. Debe ser el
 *   snapshot de la serie (no el peso vivo editable del atleta).
 * @param pushOffMode modo de agregacion de h_po declarado por el operador.
 * @param commonPushOffM h_po compartido [m]; obligatorio en [FvpPushOffMode.COMMON].
 * @param conditions condiciones de carga; en la practica >= 4 para un perfil normal.
 */
data class FvpSeriesInput(
    val protocol: FvpProtocol,
    val bodyMassKg: Double,
    val pushOffMode: FvpPushOffMode,
    val commonPushOffM: Double? = null,
    val conditions: List<FvpConditionDraft> = emptyList(),
)

/**
 * Veredicto de elegibilidad de una serie. Datos puros, sin estado ni efectos.
 *
 * @param conditionCount numero de condiciones declaradas (cuenta TODAS, validas o no).
 * @param blocking problemas bloqueantes, ordenados por orden de declaracion del enum.
 * @param flags avisos no bloqueantes, ordenados por orden de declaracion del enum.
 * @param timingQuality calidad temporal agregada por el peor intento seleccionado
 *   ([FvpTimingQuality.ALLOW] si no hay ningun intento seleccionable: minimo vacio, y en ese caso
 *   la serie ya esta bloqueada por [FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT]).
 */
data class FvpEligibility(
    val conditionCount: Int,
    val blocking: List<FvpEligibilityProblem>,
    val flags: List<FvpEligibilityFlag>,
    val timingQuality: FvpTimingQuality,
) {
    /** `true` solo si no hay ningun problema bloqueante: es lo que autoriza llamar al motor. */
    val canCompute: Boolean
        get() = blocking.isEmpty()

    /**
     * `true` solo para la ruta de perfil NORMAL: se puede calcular, hay >= 4 condiciones, todos
     * los intentos seleccionados tienen fps >= 120 y no hay ningun flag temporal.
     *
     * Decision de contrato de este slice: los demas avisos (p. ej.
     * [FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE]) NO descalifican el perfil; el
     * consumidor DEBE mostrarlos junto al resultado.
     */
    val profileEligible: Boolean
        get() = canCompute &&
            conditionCount >= RECOMMENDED_MIN_CONDITIONS &&
            timingQuality == FvpTimingQuality.ALLOW &&
            flags.none { it in TIMING_FLAGS }
}

/**
 * Valida una serie completa y devuelve su veredicto. Funcion pura, determinista y sin excepciones.
 *
 * Comprueba, en este orden conceptual (la lista de salida va siempre ordenada por declaracion de
 * enum, no por orden de comprobacion):
 * 1. `bodyMassKg` finita y > 0, si no [FvpEligibilityProblem.INVALID_BODY_MASS].
 * 2. `conditions.size < 2` bloquea con [FvpEligibilityProblem.TOO_FEW_CONDITIONS]; 2 condiciones
 *    marcaran [FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS]; 3,
 *    [FvpEligibilityFlag.FEW_CONDITIONS]; 4 o mas, ningun flag de recuento.
 * 3. `conditionKey` vacia -> [FvpEligibilityProblem.INVALID_CONDITION_KEY]; claves repetidas
 *    (comparacion EXACTA, sin trim) -> [FvpEligibilityProblem.DUPLICATE_CONDITION_KEY].
 * 4. `addedLoadKg` no finita o < 0 -> [FvpEligibilityProblem.INVALID_ADDED_LOAD]; dos condiciones
 *    con la MISMA carga -> [FvpEligibilityProblem.DUPLICATE_LOAD] (una carga = un punto).
 * 5. h_po segun [FvpSeriesInput.pushOffMode] y [FvpSeriesInput.effectivePushOffM]: ausente ->
 *    [FvpEligibilityProblem.MISSING_PUSH_OFF]; fuera de (0.05, 0.8] o no finito ->
 *    [FvpEligibilityProblem.INVALID_PUSH_OFF]; fuera de 0.20-0.60 m ->
 *    [FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE].
 * 6. Condicion sin intentos o sin ningun intento valido ->
 *    [FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT].
 * 7. Intento NO valido (`valid == false`) con `exclusionReason` nulo, vacio o solo espacios ->
 *    [FvpEligibilityProblem.EXCLUSION_REASON_MISSING]. Un intento valido no necesita motivo. Regla
 *    de dominio unica: la UI ya la exigia, pero la persistencia (que re-ejecuta esta funcion) y
 *    cualquier otro llamante no la veian.
 * 8. Altura de CUALQUIER intento (valido o invalido) no finita, `<= 0` o `> 1.2 m` ->
 *    [FvpEligibilityProblem.INVALID_HEIGHT]. La carga adicional de cada condicion tambien exige
 *    valor finito y `>= 0` para todos sus intentos ([FvpEligibilityProblem.INVALID_ADDED_LOAD]).
 *    Esta es la paridad con `FvpDao.save`: si la serie pasa aqui, la persistencia la acepta con
 *    los MISMOS valores (antes, un intento invalido sin altura pasaba elegibilidad y el DAO lo
 *    rechazaba con `IllegalArgumentException`).
 * 9. Calidad temporal = peor calidad de los intentos SELECCIONADOS
 *    (desconocido > bloqueado > condicional > elegible), con el flag correspondiente.
 * 10. Si TODAS las alturas seleccionadas (al menos [MIN_CONDITIONS]) son exactamente iguales ->
 *    [FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS]: la velocidad del modelo depende solo de
 *    la altura, de modo que el motor rechazaria la serie por velocidades identicas. Con menos de
 *    [MIN_CONDITIONS] selecciones ya bloquean [FvpEligibilityProblem.TOO_FEW_CONDITIONS] o
 *    [FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT] y no se anade ruido a la lista.
 * 11. Metodos de medida de los intentos SELECCIONADOS: si hay mas de uno distinto ->
 *    [FvpEligibilityFlag.MIXED_SELECTED_METHODS] (aviso, no bloqueo). Nunca se miran los intentos
 *    descartados ni los no seleccionados.
 *
 * El intento seleccionado por condicion lo define la regla canonica [selectedValidAttempt]: el
 * intento valido con mayor `heightM`, y ante empates gana el PRIMERO en orden de entrada. Los
 * intentos con altura no utilizable (no finita o <= 0) no participan en la seleccion: ya se
 * reportan como [FvpEligibilityProblem.INVALID_HEIGHT].
 */
fun checkSeries(input: FvpSeriesInput): FvpEligibility {
    val blocking = linkedSetOf<FvpEligibilityProblem>()
    val flags = linkedSetOf<FvpEligibilityFlag>()

    if (!input.bodyMassKg.isFinite() || input.bodyMassKg <= 0.0) {
        blocking += FvpEligibilityProblem.INVALID_BODY_MASS
    }

    val conditions = input.conditions
    when {
        conditions.size < MIN_CONDITIONS -> blocking += FvpEligibilityProblem.TOO_FEW_CONDITIONS
        conditions.size == MIN_CONDITIONS ->
            flags += FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS
        conditions.size < RECOMMENDED_MIN_CONDITIONS -> flags += FvpEligibilityFlag.FEW_CONDITIONS
    }

    val keys = conditions.map { it.conditionKey }
    if (keys.any { it.isBlank() }) blocking += FvpEligibilityProblem.INVALID_CONDITION_KEY
    if (keys.toSet().size != keys.size) blocking += FvpEligibilityProblem.DUPLICATE_CONDITION_KEY

    // Valores finitos y no negativos; NaN/Infinito son INVALID_ADDED_LOAD. Solo los finitos
    // participan en DUPLICATE_LOAD: dos NaN no son "la misma carga" (no son comparables).
    // La comparacion de duplicados es exacta (sin tolerancia): 0.0 == 0.0 es la misma carga.
    // La carga de una condicion vale para TODOS sus intentos (validos o no): es el mismo dominio
    // que exige `FvpDao.save` (`requireFinite` + `>= 0`), asi que no hay divergencia posible aqui.
    val loads = conditions.map { it.addedLoadKg }
    if (loads.any { !it.isFinite() || it < 0.0 }) blocking += FvpEligibilityProblem.INVALID_ADDED_LOAD
    val finiteLoads = loads.filter { it.isFinite() }
    if (finiteLoads.toSet().size != finiteLoads.size) blocking += FvpEligibilityProblem.DUPLICATE_LOAD

    conditions.forEach { condition ->
        val effective = input.effectivePushOffM(condition)
        if (effective == null) {
            val declared = when (input.pushOffMode) {
                FvpPushOffMode.COMMON -> input.commonPushOffM
                FvpPushOffMode.PER_CONDITION -> condition.pushOffM
            }
            blocking += if (declared == null) {
                FvpEligibilityProblem.MISSING_PUSH_OFF
            } else {
                FvpEligibilityProblem.INVALID_PUSH_OFF
            }
        } else if (effective < RECOMMENDED_MIN_PUSH_OFF_M || effective > RECOMMENDED_MAX_PUSH_OFF_M) {
            flags += FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE
        }
    }

    val selectedQualities = mutableListOf<FvpTimingQuality>()
    val selectedHeights = mutableListOf<Double>()
    val selectedMethods = mutableListOf<FvpMeasureMethod>()
    conditions.forEach { condition ->
        // Paridad con la persistencia (FvpDao.save valida TODOS los intentos, incluidos los
        // invalidos): altura finita y en (0, 1.2] para cada intento de la condicion. Antes solo se
        // comprobaban los intentos VALIDOS, de modo que una serie con un intento invalido sin
        // altura (NaN) pasaba elegibilidad y luego el DAO la rechazaba con IllegalArgumentException.
        // Se falla en CERRADO: se bloquea la serie, nunca se relaja el contrato de datos.
        val unusableHeight = condition.attempts.any {
            !it.heightM.isFinite() || it.heightM <= 0.0 || it.heightM > MAX_JUMP_HEIGHT_M
        }
        if (unusableHeight) blocking += FvpEligibilityProblem.INVALID_HEIGHT
        if (condition.attempts.none { it.valid }) {
            blocking += FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT
            return@forEach
        }
        // Un intento invalido mas alto nunca gana la seleccion (regla canonica).
        val selected = input.selectedValidAttempt(condition) ?: return@forEach
        selectedQualities += fvpTimingQuality(selected.sourceFps)
        selectedHeights += selected.heightM
        // Solo el intento SELECCIONADO aporta metodo: un descarte mas alto (o con otro metodo) no
        // entra ni en la comparabilidad ni en el aviso de mezcla.
        selectedMethods += selected.method
    }

    // Gate B3 en el DOMINIO: un intento NO valido sin motivo de exclusion bloquea la serie. La UI
    // ya lo impedia, pero el repositorio (que re-ejecuta esta funcion), un test o cualquier futuro
    // llamante podian persistir el descarte sin justificar. Se comprueba sobre TODOS los intentos
    // (validos o no): es el contrato de datos que exige la trazabilidad del descarte.
    val missingExclusionReason = conditions.any { condition ->
        condition.attempts.any { !it.valid && it.exclusionReason.isNullOrBlank() }
    }
    if (missingExclusionReason) blocking += FvpEligibilityProblem.EXCLUSION_REASON_MISSING

    // La comparacion cientifica exige el MISMO conjunto de metodos: una serie con metodos mixtos
    // entre sus puntos no es homogenea. Aviso, no bloqueo: el perfil se puede calcular, pero deja de
    // ser un perfil NORMAL publicable sin la salvedad (ver FvpQualityState.CONDITIONAL).
    if (selectedMethods.distinct().size > 1) flags += FvpEligibilityFlag.MIXED_SELECTED_METHODS

    // La velocidad del modelo depende SOLO de la altura (`v = sqrt(g * h / 2)`): con todas las
    // alturas seleccionadas iguales, todas las velocidades son iguales y el motor rechaza la serie
    // (pendiente indefinida). Se bloquea ANTES de llamarlo para que el motivo llegue a la UI; el
    // motor sigue siendo la autoridad. Se exige el minimo de selecciones para no duplicar el ruido
    // de una serie ya bloqueada por falta de condiciones/intentos validos.
    if (selectedHeights.size >= MIN_CONDITIONS && selectedHeights.all { it == selectedHeights.first() }) {
        blocking += FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS
    }

    val timingQuality = selectedQualities.maxByOrNull { it.severity } ?: FvpTimingQuality.ALLOW
    when (timingQuality) {
        FvpTimingQuality.ALLOW -> Unit
        FvpTimingQuality.CONDITIONAL -> flags += FvpEligibilityFlag.TIMING_CONDITIONAL
        FvpTimingQuality.BLOCKED -> flags += FvpEligibilityFlag.TIMING_BLOCKED
        FvpTimingQuality.UNKNOWN -> flags += FvpEligibilityFlag.TIMING_UNKNOWN_NOT_ELIGIBLE
    }

    return FvpEligibility(
        conditionCount = conditions.size,
        blocking = blocking.sortedBy { it.ordinal },
        flags = flags.sortedBy { it.ordinal },
        timingQuality = timingQuality,
    )
}

/**
 * h_po efectivo de una condicion segun el modo declarado por la serie, o `null` si no es utilizable.
 *
 * - [FvpPushOffMode.COMMON] -> [FvpSeriesInput.commonPushOffM] para todas las condiciones.
 * - [FvpPushOffMode.PER_CONDITION] -> [FvpConditionDraft.pushOffM] de esa condicion.
 *
 * Devuelve `null` si el valor declarado es `null`, no finito, <= 0.05 m o > 0.8 m (dominio del
 * motor). No aplica la ventana recomendada 0.20-0.60 m: eso es un aviso, no un rechazo.
 */
fun FvpSeriesInput.effectivePushOffM(condition: FvpConditionDraft): Double? {
    val declared: Double? = when (pushOffMode) {
        FvpPushOffMode.COMMON -> commonPushOffM
        FvpPushOffMode.PER_CONDITION -> condition.pushOffM
    }
    if (declared == null || !declared.isFinite()) return null
    return if (declared > MIN_PUSH_OFF_M && declared <= MAX_PUSH_OFF_M) declared else null
}

/**
 * Entradas por condicion para el motor (`:fvp`), ordenadas por `conditionKey`, o `null` si la
 * serie no se puede calcular ([FvpEligibility.canCompute] `== false`).
 *
 * - `conditionId` = `conditionKey`
 * - `totalMassKg` = `bodyMassKg + addedLoadKg`
 * - `heightM` = altura del intento valido seleccionado (mayor altura valida)
 * - `pushOffM` = h_po efectivo ([effectivePushOffM])
 *
 * NUNCA llama al motor: solo prepara la entrada. La ordenacion por `conditionKey` es la comparacion
 * natural de `String` (exacta, sin trim) y hace determinista la salida aunque cambie el orden de
 * entrada. Devuelve `null` tambien si la seleccion deja de ser coherente con `canCompute`
 * (invariante defensiva; no deberia ocurrir).
 */
fun FvpSeriesInput.selectedConditionInputs(): List<FvpConditionInput>? {
    if (!checkSeries(this).canCompute) return null
    return conditions.sortedBy { it.conditionKey }.map { condition ->
        val selected = selectedValidAttempt(condition) ?: return null
        val pushOffM = effectivePushOffM(condition) ?: return null
        FvpConditionInput(
            conditionId = condition.conditionKey,
            totalMassKg = bodyMassKg + condition.addedLoadKg,
            heightM = selected.heightM,
            pushOffM = pushOffM,
        )
    }
}

/**
 * ¿Hace falta un TERCER intento en esa carga? Gate B3: se recomiendan dos intentos validos por
 * carga y se pide un tercero cuando los dos primeros se separan mas del `thresholdPercent` (%).
 *
 * Referencia conservadora: la MENOR de las dos alturas, de modo que el resultado es independiente
 * del orden de los argumentos y la decision es la mas exigente (la diferencia relativa mayor).
 * La comparacion es estricta (`> thresholdPercent`), de forma que 5.0 % exacto NO pide tercero.
 *
 * Devuelve `false` (no hay decision utilizable) si alguna altura es no finita o <= 0, o si el
 * umbral no es finito o es negativo: esas alturas inutilizables no llegan al motor, se reportan
 * como [FvpEligibilityProblem.INVALID_HEIGHT].
 */
fun needsThirdAttempt(firstHeightM: Double, secondHeightM: Double, thresholdPercent: Double = 5.0): Boolean {
    if (!firstHeightM.isFinite() || !secondHeightM.isFinite()) return false
    if (firstHeightM <= 0.0 || secondHeightM <= 0.0) return false
    if (!thresholdPercent.isFinite() || thresholdPercent < 0.0) return false
    val low = minOf(firstHeightM, secondHeightM)
    val high = maxOf(firstHeightM, secondHeightM)
    return (high - low) / low * 100.0 > thresholdPercent
}

// --- Seleccion CANONICA de intento (una sola definicion para toda la app) -------------------

/**
 * Indice (dentro de `condition.attempts`, es decir el ordinal persistido) del intento VALIDO
 * SELECCIONADO de una condicion, o `null` si no hay ninguno.
 *
 * REGLA CANONICA, identica a la que deben reflejar las capas de presentacion y persistencia:
 * - solo participan intentos con `valid == true` y altura UTILIZABLE (finita y > 0);
 * - gana la MAYOR altura entre los candidatos;
 * - ante empate gana el PRIMERO en orden de entrada (determinista y estable);
 * - el resultado de una condicion no depende del orden de las DEMAS condiciones de la serie.
 *
 * Un intento INVALIDO, por alto que sea, nunca se selecciona. La funcion no lanza y no depende del
 * resto de la serie: el receptor [FvpSeriesInput] solo documenta de que serie proviene la condicion
 * (el llamante ya tiene el contexto de serie a mano), y la regla se aplica condicion a condicion.
 */
fun FvpSeriesInput.selectedValidAttemptIndex(condition: FvpConditionDraft): Int? {
    var bestIndex: Int? = null
    var bestHeight = 0.0
    condition.attempts.forEachIndexed { index, attempt ->
        if (!attempt.valid || !attempt.heightM.isFinite() || attempt.heightM <= 0.0) return@forEachIndexed
        if (bestIndex == null || attempt.heightM > bestHeight) {
            bestIndex = index
            bestHeight = attempt.heightM
        }
    }
    return bestIndex
}

/**
 * Intento VALIDO SELECCIONADO de una condicion segun la regla canonica
 * ([selectedValidAttemptIndex]); `null` si no hay ningun candidato.
 */
fun FvpSeriesInput.selectedValidAttempt(condition: FvpConditionDraft): FvpAttemptDraft? =
    selectedValidAttemptIndex(condition)?.let { condition.attempts[it] }
