package com.openjump.app.fvp

import com.openjump.fvp.FvpWarning
import com.openjump.fvp.RECOMMENDED_MIN_CONDITIONS

/**
 * Estado de CALIDAD DERIVADO de una serie F-V-P: la UNICA sintesis que combina la elegibilidad de
 * dominio ([FvpEligibility]) con los veredictos de la ejecucion mostrada (R^2 y avisos del motor).
 *
 * Existe porque hoy `profileEligible` (elegibilidad) y los avisos del motor viven en sitios
 * distintos y cada pantalla/decision los interpretaba por su cuenta. Este estado NO sustituye a
 * ninguno de los dos: es la lectura conjunta, y quien necesite el detalle sigue teniendo la
 * elegibilidad y los avisos.
 *
 * Los estados son EXCLUYENTES y se resuelven por PRECEDENCIA (el primero que encaja gana):
 * `INCOMPLETE` > `DIAGNOSTIC` > `CONDITIONAL` > `PROFILE`.
 */
enum class FvpQualityState {
    /** No hay una ejecucion calculable: la serie esta bloqueada o no existe un resultado que juzgar. */
    INCOMPLETE,

    /** Hay ejecucion, pero NO sirve para un perfil: 2-3 condiciones, ajuste no revisable, timing
     *  no `ALLOW`, metodos de medida mezclados sin compatibilidad revisada o un aviso persistido
     *  sin clasificar. */
    DIAGNOSTIC,

    /** El perfil existe, pero con salvedades: hoy solo extrapolacion alta ([FvpWarning.HIGH_EXTRAPOLATION]). */
    CONDITIONAL,

    /** Perfil NORMAL: 4+ condiciones, ajuste utilizable, fps `ALLOW`, sin mezcla de metodos. */
    PROFILE,
}

/**
 * Entradas del estado de calidad. Son los DOS ORIGENES ya existentes, sin recalcular nada:
 * la elegibilidad de dominio y los veredictos de la ejecucion mostrada.
 *
 * @param canCompute tal como lo declara [FvpEligibility.canCompute]: sin esto no hay estado.
 * @param conditionCount condiciones de la serie (manda [RECOMMENDED_MIN_CONDITIONS]).
 * @param profileEligible ruta de perfil normal segun la elegibilidad (4+ condiciones, fps `ALLOW`).
 * @param timingAllows calidad temporal agregada `ALLOW` (los flags temporales son su representacion
 *   equivalente; se acepta una sola de las dos, la que ya trae la elegibilidad).
 * @param r2Finite hay R^2 finito en la ejecucion mostrada (un ajuste sin R^2 no es juzgable).
 * @param mixedSelectedMethods la serie mezcla metodos de medida entre sus intentos SELECCIONADOS.
 * @param warningNames nombres de los avisos del MOTOR ([FvpWarning]) de la ejecucion mostrada
 *   (nombres persistidos para un original, del motor para un recalculo/borrador). Se comparan por
 *   nombre para no duplicar la lista de avisos ni sus umbrales: los umbrales viven en `:fvp`.
 */
internal data class FvpQualityInput(
    val canCompute: Boolean,
    val conditionCount: Int,
    val profileEligible: Boolean,
    val timingAllows: Boolean,
    val r2Finite: Boolean,
    val mixedSelectedMethods: Boolean,
    val warningNames: Set<String>,
)

/**
 * Avisos RECONOCIDOS del motor y de la elegibilidad que implican que NO hay un perfil revisable:
 * `NON_PHYSICAL` (pendiente/interceptos imposibles), `LOW_R2` y `NARROW_VELOCITY_RANGE` (el ajuste
 * no describe los puntos o la pendiente es inestable), `FPS_30_BLOCKED`/`TIMING_*` (ruta temporal no
 * `ALLOW`) y `MIXED_SELECTED_METHODS` (mezcla de metodos de medida sin compatibilidad revisada).
 *
 * Ademas del nombre del motor, se clasifican los NOMBRES DE FLAG de elegibilidad que viajan en la
 * MISMA lista persistida `warningsCsv` (`FvpRepository.warningsCsv`): un flag valido NUNCA se trata
 * como nombre desconocido.
 */
private val UNREVIEWABLE_WARNINGS: Set<String> = setOf(
    FvpWarning.NON_PHYSICAL.name,
    FvpWarning.LOW_R2.name,
    FvpWarning.NARROW_VELOCITY_RANGE.name,
    FvpWarning.FPS_30_BLOCKED.name,
    FvpWarning.FEW_POINTS.name,
    FvpEligibilityFlag.FEW_CONDITIONS.name,
    FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS.name,
    FvpEligibilityFlag.TIMING_CONDITIONAL.name,
    FvpEligibilityFlag.TIMING_BLOCKED.name,
    FvpEligibilityFlag.TIMING_UNKNOWN_NOT_ELIGIBLE.name,
    FvpEligibilityFlag.MIXED_SELECTED_METHODS.name,
)

/**
 * Avisos RECONOCIDOS que dejan el perfil en CONDITIONAL: el ajuste existe pero con salvedades. Hoy
 * el UNICO es [FvpWarning.HIGH_EXTRAPOLATION] (Q2/D4: parte del resultado queda lejos del rango
 * medido, sin invalidar el ajuste).
 */
private val CONDITIONAL_WARNINGS: Set<String> = setOf(FvpWarning.HIGH_EXTRAPOLATION.name)

/**
 * Avisos RECONOCIDOS que NO degradan el estado: el unico ADVISORY es la ventana recomendada de h_po
 * ([FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE]), que se muestra junto al resultado.
 */
private val ADVISORY_WARNINGS: Set<String> = setOf(
    FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE.name,
)

/**
 * Nombres persistibles que la politica RECONOCE (avisos del motor + flags de elegibilidad
 * actuales). La tabla es CERRADA: cualquier nombre que no este aqui degrada a DIAGNOSTIC (fail
 * closed) y la UI lo declara como limitacion generica, de modo que un aviso de una version futura
 * no puede pasar por "sin problemas".
 */
private val RECOGNISED_WARNINGS: Set<String> =
    UNREVIEWABLE_WARNINGS + CONDITIONAL_WARNINGS + ADVISORY_WARNINGS

/**
 * Estado de calidad de una serie. Funcion PURA, determinista y sin excepciones: solo mira
 * [FvpQualityInput] (no llama al motor ni a la UI y no reimplementa ningun umbral numerico).
 *
 * Tabla EXACTA, por precedencia (Q2: fail closed, nada sin revisar establece comparabilidad):
 * 1. `!canCompute` -> [FvpQualityState.INCOMPLETE].
 * 2. `conditionCount < `[RECOMMENDED_MIN_CONDITIONS] (2 o 3 condiciones) ->
 *    [FvpQualityState.DIAGNOSTIC].
 * 3. `warningNames` contiene un nombre que NO esta en [RECOGNISED_WARNINGS] ->
 *    [FvpQualityState.DIAGNOSTIC] (un aviso persistido sin clasificar NUNCA se interpreta como
 *    "sin problemas").
 * 4. `warningNames` contiene algun aviso de [UNREVIEWABLE_WARNINGS] o `!r2Finite` ->
 *    [FvpQualityState.DIAGNOSTIC] (hay ejecucion, pero no un ajuste utilizable que interpretar).
 * 5. `!timingAllows` (fps UNKNOWN/BLOCKED o 60-<120 sin sensibilidad revisada) o
 *    `mixedSelectedMethods` -> [FvpQualityState.DIAGNOSTIC]: no hay ruta temporal condicional
 *    habilitada ni compatibilidad revisada entre metodos de medida.
 * 6. `warningNames` contiene [FvpWarning.HIGH_EXTRAPOLATION] -> [FvpQualityState.CONDITIONAL].
 * 7. `profileEligible` -> [FvpQualityState.PROFILE].
 * 8. En cualquier otro caso -> [FvpQualityState.CONDITIONAL] (perfil calculable que no cumple la
 *    ruta normal: nunca se degrada a DIAGNOSTIC ni se promociona a PROFILE por defecto).
 *
 * Contratos de este estado:
 * - [FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE] NO aparece como degradante: es un aviso
 *   ADVISORY de la ventana recomendada de h_po y NO descalifica; con todo lo demas en orden la serie
 *   sigue siendo [FvpQualityState.PROFILE] (se muestra junto al resultado).
 * - [FvpQualityState.PROFILE] es un SUBCONJUNTO de [FvpQualityInput.profileEligible]: solo se
 *   alcanza a traves del paso 7, de modo que un PROFILE nunca contradice a la elegibilidad.
 * - La clasificacion por NOMBRE es completa para los avisos del motor y los flags de elegibilidad
 *   actuales (lo vigila `FvpQualityPolicyTest` con el estado esperado de cada uno); un nombre NUEVO
 *   no puede quedar en PROFILE en silencio porque el paso 3 lo degrada.
 */
internal fun fvpQualityState(input: FvpQualityInput): FvpQualityState = when {
    !input.canCompute -> FvpQualityState.INCOMPLETE
    input.conditionCount < RECOMMENDED_MIN_CONDITIONS -> FvpQualityState.DIAGNOSTIC
    input.warningNames.any { it !in RECOGNISED_WARNINGS } -> FvpQualityState.DIAGNOSTIC
    input.warningNames.any { it in UNREVIEWABLE_WARNINGS } || !input.r2Finite -> FvpQualityState.DIAGNOSTIC
    !input.timingAllows || input.mixedSelectedMethods -> FvpQualityState.DIAGNOSTIC
    input.warningNames.any { it in CONDITIONAL_WARNINGS } -> FvpQualityState.CONDITIONAL
    input.profileEligible -> FvpQualityState.PROFILE
    else -> FvpQualityState.CONDITIONAL
}

/**
 * Adaptador de la elegibilidad de dominio a las entradas del estado de calidad. Los campos de SERIE
 * y procedimiento salen de [FvpEligibility] (fuente unica de `canCompute`, recuento,
 * `profileEligible`, timing y mezcla de metodos); la ejecucion mostrada solo aporta `r2Finite` y sus
 * avisos ([FvpQualityInput.warningNames]).
 *
 * Representacion UNICA del timing: se toma de [FvpEligibility.timingQuality] (`ALLOW`) y NO se
 * re-deduce de los flags temporales, para no tener dos implementaciones de la misma regla.
 */
internal fun FvpEligibility.toQualityInput(
    r2Finite: Boolean,
    warningNames: Set<String>,
): FvpQualityInput = FvpQualityInput(
    canCompute = canCompute,
    conditionCount = conditionCount,
    profileEligible = profileEligible,
    timingAllows = timingQuality == FvpTimingQuality.ALLOW,
    r2Finite = r2Finite,
    mixedSelectedMethods = flags.contains(FvpEligibilityFlag.MIXED_SELECTED_METHODS),
    warningNames = warningNames,
)
