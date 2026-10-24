package com.openjump.app.ui.fvp

import com.openjump.app.data.FVP_METHOD_VERSION
import com.openjump.app.data.FvpSnapshotStatus
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.fvp.FvpQualityState
import com.openjump.app.fvp.fvpQualityState
import com.openjump.app.fvp.toQualityInput
import com.openjump.fvp.ALGORITHM_VERSION

/**
 * Motivo por el que dos series guardadas NO se pueden comparar con deltas (gate B4 / decision D4,
 * segunda mitad de D5: C2).
 *
 * C2 separa dos cosas que C1 trataba igual: lo que impide comparar (esta lista) y lo que solo
 * limita la interpretacion y hay que ENSENAR (masa corporal y valor de h_po en modo `COMMON`, que
 * pasan a CONTEXTO en [FvpComparisonContext]). Cada valor de este enum es un motivo BLOQUEANTE;
 * [fvpComparisonPolicy] los recoge en el orden de declaracion.
 *
 * EXCEPCION DELIBERADA (P2-1, no es un olvido): con distinta COBERTURA de cargas
 * ([DIFFERENT_LOAD_COVERAGE]) NO se anaden los motivos de asociacion ([DIFFERENT_MEASURE_METHODS]
 * y [DIFFERENT_PER_LOAD_PUSH_OFF]), porque describirian mal la causa real: dos series con los
 * MISMOS metodos y los MISMOS h_po por carga pero otra lista de cargas no "usan otros metodos" ni
 * "otros h_po por carga". El motivo que de verdad difiere es la cobertura. NO "arreglar" este
 * comportamiento anadiendo los motivos suprimidos: la UI imprimiria razones falsas.
 */
enum class FvpComparisonBlock {
    /** Falta el atleta en alguna de las dos series o no es el mismo en ambas. */
    DIFFERENT_ATHLETE,

    /**
     * Alguna version del algoritmo o del METODO no es la ACTUAL revisada (desconocida, futura o de
     * otra version): la compatibilidad exige la version EXACTA de las dos series. Un prefijo comun,
     * dos cadenas desconocidas iguales o una version futura NO establecen compatibilidad (D5; Q2).
     */
    UNKNOWN_OR_DIFFERENT_ALGORITHM,

    /** Distinto protocolo o distinto modo de agregacion de h_po. */
    DIFFERENT_PROTOCOL,

    /**
     * Distinto PROCEDIMIENTO declarado de h_po (texto normalizado sin espacios alrededor), o un
     * procedimiento que no esta en la lista de PROCEDIMIENTOS CONOCIDOS/REVISADOS ([KNOWN_PUSH_OFF_PROCEDURES]).
     * Un procedimiento en blanco, ausente o no reconocido NUNCA es compatible, ni siquiera consigo
     * mismo (D5: procedimiento desconocido -> bloqueo). El VALOR de h_po ya NO bloquea por si mismo
     * en modo `COMMON`: es contexto.
     */
    DIFFERENT_PUSH_OFF_PROCEDURE,

    /** Alguna serie es DIAGNOSTIC o INCOMPLETE: no hay perfil que comparar con deltas. */
    NOT_A_PROFILE,

    /**
     * Distinta ASOCIACION carga -> metodo de medida de los intentos SELECCIONADOS (no solo un
     * conjunto distinto de metodos: el mismo conjunto repartido entre otras cargas bloquea).
     * SUPRIMIDO a proposito cuando aplica [DIFFERENT_LOAD_COVERAGE]: con otra lista de cargas no se
     * puede afirmar que la asociacion carga-metodo difiere sin enganar (ver KDoc del enum).
     */
    DIFFERENT_MEASURE_METHODS,

    /**
     * Distinta LISTA ordenada de cargas CON multiplicidad (nunca un `Set`): `[0,20,20,40]` no es
     * `[0,20,40]`. Sin cobertura de cargas comparable no hay deltas que publicar y SUPRIME los
     * motivos de asociacion ([DIFFERENT_MEASURE_METHODS] y [DIFFERENT_PER_LOAD_PUSH_OFF]), que con
     * otra cobertura no describen de verdad lo que difiere.
     */
    DIFFERENT_LOAD_COVERAGE,

    /**
     * En `PER_CONDITION`: distintos pares ordenados `(carga, h_po)` (bisagra obligatoria). La
     * asociacion carga-h_po es el P1 que corrigio W4a: dos series con las mismas cargas y los h_po
     * permutados NO son el mismo experimento. En `COMMON` este motivo NUNCA aplica. SUPRIMIDO a
     * proposito cuando aplica [DIFFERENT_LOAD_COVERAGE] (ver KDoc del enum).
     */
    DIFFERENT_PER_LOAD_PUSH_OFF,

    /**
     * B4b: el CONTEXTO DE ADQUISICION declarado no coincide entre las dos series, o ALGUNA de las
     * dos no lo declara (`UNKNOWN`, o una clave que esta version no reconoce). `UNKNOWN` NO
     * equivale a "misma adquisicion" y no se infiere por cercania de fechas: sin contexto
     * declarado e identico no se publican deltas. Dos series `HISTORICAL_COMPOSITE` identicas NO
     * disparan este motivo (el contrato solo bloquea contexto distinto o sin declarar).
     */
    DIFFERENT_ACQUISITION_CONTEXT,

    /**
     * B4b: las dos series declaran `SAME_ACQUISITION` pero la CLAVE opaca del contexto no es la
     * MISMA (o falta en alguna de las dos): sin clave, o con claves distintas, no se puede
     * comprobar que sea la misma adquisicion y ninguna asociacion contexto-intentos se sostiene.
     */
    DIFFERENT_ACQUISITION_CONTEXT_KEY,

    /**
     * B4b: algun intento SELECCIONADO de alguna de las dos series no declara
     * `timeMapping = IDENTITY_VERIFIED` (incluye `UNKNOWN` y las condiciones sin intento
     * seleccionado registrado): la serie no puede afirmar tiempo fisico, de modo que tampoco se
     * publican deltas. El peor intento seleccionado decide.
     */
    TIME_MAPPING_NOT_VERIFIED,
}

/** Rejilla de entrada de h_po documentada en B1 (0,5 cm): unica canonicalizacion al comparar. */
internal const val FVP_PUSH_OFF_ENTRY_GRID_M = 0.005

/** Umbral del aviso de rango estrecho de B4 (ΔV < 0,15 m/s), como CONTEXTO de la comparacion. */
internal const val FVP_NARROW_VELOCITY_RANGE_MPS = 0.15

/**
 * Clave persistida del aviso de rango estrecho (misma que [com.openjump.app.fvp.FvpWarning]).
 * Se comprueba ademas del ΔV medido: una fila puede traer el aviso persistido.
 */
private const val NARROW_VELOCITY_RANGE_WARNING = "NARROW_VELOCITY_RANGE"

/** Valor de las dos series y su diferencia (`segunda - primera`); `null` == no medible. */
data class FvpComparisonValue(
    val first: Double?,
    val second: Double?,
    val delta: Double?,
)

/** Delta concreto que la pantalla puede pintar, con su papel en la lectura. */
enum class FvpComparisonDeltaKind {
    /** ΔF0 relativo (N/kg), campo ya persistido `f0RelNPerKg`. */
    F0_RELATIVE,

    /** ΔPmax relativo (W/kg), campo ya persistido `pmaxRelWPerKg`. */
    PMAX_RELATIVE,

    /** ΔF0 absoluto (N). */
    F0_ABSOLUTE,

    /** ΔPmax absoluto (W). */
    PMAX_ABSOLUTE,

    /** ΔV0 (m/s): velocidad, NO afectada por el escalado de la masa. */
    V0_ABSOLUTE,
}

/**
 * Linea de delta con su papel: [primary] marca las lineas que resumen la diferencia y
 * [massScaled] los deltas ABSOLUTOS de fuerza/potencia, que incluyen el escalado por masa. Las dos
 * afirmaciones no se presentan como la misma (contrato C2, seccion 2.2).
 */
data class FvpComparisonDeltaLine(
    val kind: FvpComparisonDeltaKind,
    val value: Double,
    val primary: Boolean,
    val massScaled: Boolean,
)

/** Procedencia B1 declarada de h_po en una serie (se muestra; no se compara). */
data class FvpPushOffProvenance(
    val source: String?,
    val procedure: String?,
    val landmark: String?,
    val measuredAt: Long?,
)

/** Cobertura de velocidad medida de una serie, con el aviso de rango estrecho de B4. */
data class FvpSeriesVelocityCoverage(
    val vMinMps: Double?,
    val vMaxMps: Double?,
    val narrowRange: Boolean,
) {
    /** ΔV medido de la serie (`vMax - vMin`), o `null` si no es medible. */
    val spanMps: Double? get() = span(vMinMps, vMaxMps)
}

/** Par `(carga, h_po)` de UNA condicion, con el h_po CANONICALIZADO a la rejilla de entrada de
 * 0,5 cm ([FVP_PUSH_OFF_ENTRY_GRID_M]): es el valor exacto que compara la regla 8, no el ruido
 * persistido. Dato de DIAGNOSTICO visible (P2-6): la UI lo ensena, nunca recompone una regla. */
data class FvpPerLoadPushOffEntry(
    val loadKg: Double,
    val pushOffM: Double,
)

/**
 * Datos de CONTEXTO que la pantalla de historial debe ensenar SIEMPRE que haya dos series
 * seleccionadas, tambien cuando algo bloquea los deltas. Es un tipo puro (sin tipos de UI, sin
 * recursos): la politica lo calcula y el test de politica lo comprueba sin Compose.
 *
 * @param bodyMassKg masa corporal de cada serie y su diferencia (segunda - primera).
 * @param massDiffers `true` si la masa NO coincide: los deltas PRIMARIOS son los relativos.
 * @param f0RelNPerKg ΔF0 relativo (N/kg) ya persistido en cada fila.
 * @param pmaxRelWPerKg ΔPmax relativo (W/kg) ya persistido en cada fila.
 * @param deltaLines deltas a pintar, en orden de lectura; VACIO si algo bloquea (nunca una linea
 *   de delta en la rama bloqueada). El papel `primary`/`massScaled` de cada linea se DERIVA de
 *   [relativeDeltasArePrimary] (P2-8): una sola decision manda en la lectura y en las etiquetas.
 * @param pushOffM valor de h_po de cada serie tal y como se persistio (solo `COMMON`; `null` en
 *   `PER_CONDITION`, donde la asociacion carga-h_po vive en [FvpComparisonBlock]).
 * @param canonicalPushOffM los MISMOS valores de h_po canonicalizados a la rejilla de entrada
 *   ([FVP_PUSH_OFF_ENTRY_GRID_M]) y su diferencia (P2-7): es la unica diferencia con signo fiable,
 *   porque el ruido de ida y vuelta metrico/imperial se colapsa a la misma celda (diferencia
 *   exactamente 0 dentro de la rejilla) y una celda distinta da un signo real. `null` salvo en
 *   `COMMON` en las dos series, igual que [pushOffM].
 * @param perLoadPushOffFirst/perLoadPushOffSecond pares `(carga, h_po canonicalizado)` de cada
 *   condicion, ordenados por carga y h_po (P2-6). Solo cuando ALGUNA serie es `PER_CONDITION`
 *   (el dato que explica [FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF] o la diferencia de
 *   modos); `null` en `COMMON`/`COMMON`, donde la fila de h_po ya ensena el valor unico.
 * @param pushOffProvenanceFirst/pushOffProvenanceSecond procedencia B1 declarada de cada serie.
 * @param velocityFirst/velocitySecond cobertura medida de cada serie.
 * @param narrowRangeWarningApplies aviso de rango estrecho de B4 visible como contexto.
 * @param conditionCountFirst/conditionCountSecond recuento de condiciones de cada serie.
 * @param qualityFirst/qualitySecond estado de calidad DERIVADO (Q1) de cada serie.
 * @param algorithmVersionFirst/.../methodVersionSecond versiones exactas de cada serie.
 * @param sameExactAlgorithmVersion/sameExactMethodVersion las dos cadenas son identicas.
 */
data class FvpComparisonContext(
    val bodyMassKg: FvpComparisonValue,
    val massDiffers: Boolean,
    val f0RelNPerKg: FvpComparisonValue,
    val pmaxRelWPerKg: FvpComparisonValue,
    val deltaLines: List<FvpComparisonDeltaLine>,
    val pushOffM: FvpComparisonValue?,
    val canonicalPushOffM: FvpComparisonValue?,
    val perLoadPushOffFirst: List<FvpPerLoadPushOffEntry>?,
    val perLoadPushOffSecond: List<FvpPerLoadPushOffEntry>?,
    val pushOffProvenanceFirst: FvpPushOffProvenance,
    val pushOffProvenanceSecond: FvpPushOffProvenance,
    val velocityFirst: FvpSeriesVelocityCoverage,
    val velocitySecond: FvpSeriesVelocityCoverage,
    val narrowRangeWarningApplies: Boolean,
    val conditionCountFirst: Int,
    val conditionCountSecond: Int,
    val qualityFirst: FvpQualityState,
    val qualitySecond: FvpQualityState,
    val algorithmVersionFirst: String,
    val algorithmVersionSecond: String,
    val methodVersionFirst: String,
    val methodVersionSecond: String,
    val sameExactAlgorithmVersion: Boolean,
    val sameExactMethodVersion: Boolean,
) {
    /**
     * Nota de version OBLIGATORIA si difiere CUALQUIERA de las dos versiones: una diferencia solo
     * de metodo tambien la produce (el repositorio ya la clasifica como `STALE_METHOD`).
     */
    val versionNoteRequired: Boolean
        get() = !sameExactAlgorithmVersion || !sameExactMethodVersion

    /**
     * Deltas PRIMARIOS (la lectura principal) cuando la masa difiere; los absolutos en caso
     * contrario. Es la UNICA fuente de la decision: los papeles `primary`/`massScaled` de
     * [deltaLines] se derivan de este mismo valor (P2-8), de modo que la UI no puede pintar una
     * lectura y etiquetar otra.
     */
    val relativeDeltasArePrimary: Boolean get() = massDiffers

    /**
     * Hay alguna linea de delta RELATIVO (N/kg, W/kg) que ensenar. La nota de C2 que declara los
     * relativos como lectura primaria solo puede afirmarse cuando existe (P2-2): en la rama
     * bloqueada (o sin relativos medibles) seria una afirmacion falsa.
     */
    val relativeDeltasShown: Boolean
        get() = deltaLines.any {
            it.kind == FvpComparisonDeltaKind.F0_RELATIVE ||
                it.kind == FvpComparisonDeltaKind.PMAX_RELATIVE
        }

    /**
     * La nota neutral de comparacion es obligatoria SIEMPRE que se pinte cualquier delta; con la
     * rama bloqueada no hay deltas y la nota no aplica.
     */
    val neutralCaptionRequired: Boolean get() = deltaLines.isNotEmpty()

    /**
     * P2-9 (defensivo): la masa difiere y la fila NO trae medible NINGUN delta relativo, de modo
     * que no queda ninguna linea primaria relativa y el grupo escalado por masa no puede presentarse
     * como toda la lectura. La UI lo DECLARA (no lo calla).
     */
    val relativeDeltasUnavailable: Boolean
        get() = massDiffers && deltaLines.isNotEmpty() && !relativeDeltasShown
}

/**
 * Resultado de comparar dos series guardadas.
 *
 * @param blocks motivos BLOQUEANTES que aplican, en el orden de declaracion de
 *   [FvpComparisonBlock] (determinista). Vacio == se pueden mostrar deltas.
 * @param context datos de contexto que la UI ensena en las DOS ramas (tambien con bloqueos).
 */
data class FvpComparisonPolicy(
    val blocks: List<FvpComparisonBlock>,
    val context: FvpComparisonContext,
) {
    /**
     * `true` solo si NO hay ningun bloqueo: contexto de calidad y numeros (deltas) de las dos
     * series. Con un unico bloqueo ya NO se muestran deltas.
     */
    val canCompareDeltas: Boolean get() = blocks.isEmpty()

    /** Las dos cadenas `algorithmVersion` son IDENTICAS (si difieren, nota de version). */
    val sameExactAlgorithmVersion: Boolean get() = context.sameExactAlgorithmVersion

    /** Las dos cadenas `methodVersion` son IDENTICAS (si difieren, nota de version). */
    val sameExactMethodVersion: Boolean get() = context.sameExactMethodVersion
}

/**
 * Politica PURA de comparacion de dos series guardadas: recoge los motivos aplicables en el orden
 * del enum y ninguno de ellos se "arregla" borrando otro. La UNICA supresion es deliberada y esta
 * documentada en la regla 7 (cobertura de cargas): con cobertura distinta no se anaden los motivos
 * de asociacion 6 y 8, porque describirian mal la causa real. No toca la UI, el motor ni la base
 * de datos, de modo que la regla se puede testear con filas literales.
 *
 * Reglas EXACTAS (una regla = un motivo; se anaden en este orden = orden del enum):
 * 1. [FvpComparisonBlock.DIFFERENT_ATHLETE]: `athleteId` igual y NO nulo en las dos series. Un
 *    `athleteId` nulo (serie sin atleta) nunca es comparable, ni siquiera con otra fila nula.
 * 2. [FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM]: `algorithmVersion` y `methodVersion`
 *    EXACTAMENTE las versiones ACTUALES revisadas ([ALGORITHM_VERSION] y [FVP_METHOD_VERSION]) en
 *    las DOS series. Una version en blanco, futura, desconocida o distinta no se compara NUNCA; la
 *    familia/prefijo comun NO establece compatibilidad (Q2).
 * 3. [FvpComparisonBlock.DIFFERENT_PROTOCOL]: mismo `protocolId` y mismo `pushOffMode`.
 * 4. [FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE]: mismo PROCEDIMIENTO declarado (comparado
 *    sin espacios alrededor) y ADEMAS un procedimiento CONOCIDO/REVISADO ([KNOWN_PUSH_OFF_PROCEDURES]):
 *    un procedimiento ausente, en blanco o no reconocido bloquea. En C2 el VALOR de h_po deja de
 *    bloquear: en `COMMON` pasa a contexto (diferencia visible + frase de acoplamiento) y en
 *    `PER_CONDITION` lo cubre la regla 8.
 * 5. [FvpComparisonBlock.NOT_A_PROFILE]: el estado de calidad DERIVADO de cualquiera de las dos
 *    series ([fvpQualityState] sobre la elegibilidad + el ajuste PERSISTIDO: R^2 finito y avisos
 *    del snapshot, solo con `snapshotStatus == MATCHES`) es [FvpQualityState.DIAGNOSTIC] o
 *    [FvpQualityState.INCOMPLETE]; tambien cuando la fila no se puede recalcular (sin elegibilidad
 *    -> INCOMPLETE) o su snapshot no se puede verificar frente al recalculo actual (MISMATCH,
 *    STALE, STALE_METHOD, RECALCULATION_UNAVAILABLE -> DIAGNOSTIC, la fila sigue legible).
 * 6. [FvpComparisonBlock.DIFFERENT_MEASURE_METHODS]: la ASOCIACION carga -> metodo (metodo del
 *    intento SELECCIONADO de cada carga) es la misma lista ordenada de pares. C2 cierra el hueco de
 *    C1: el mismo conjunto de metodos repartido entre otras cargas bloquea.
 * 7. [FvpComparisonBlock.DIFFERENT_LOAD_COVERAGE]: misma LISTA ORDENADA de cargas CON
 *    multiplicidad (`[0,20,20,40]` != `[0,20,40]`). Nunca se interpola ni se reasigna una condicion.
 *    SIN cobertura igual no se anaden los motivos de asociacion (6 y 8): la cobertura es el motivo
 *    que de verdad difiere, y los otros dos describirian mal una serie que usa los mismos metodos.
 *    Esta supresion es DELIBERADA (P2-1): no "corregir" el codigo para que los anada.
 * 8. [FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF]: si ALGUNA de las dos series es
 *    `PER_CONDITION`, los pares ordenados `(carga, h_po)` tienen que coincidir. Los h_po se
 *    comparan canonicalizados a la rejilla de entrada de 0,5 cm ([FVP_PUSH_OFF_ENTRY_GRID_M]) para
 *    que el ruido de ida y vuelta metrico/imperial (0,40 re-parseado como 0,3999992) no sea una
 *    diferencia. NO se admite ninguna tolerancia mayor (D5 prohibe inventar equivalencias).
 * 9. [FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT]: los dos `acquisitionContextKind`
 *    declarados son el MISMO y NINGUNO es `UNKNOWN` (o una clave que esta version no reconoce).
 *    `UNKNOWN` no es "misma adquisicion" ni se infiere: bloquea aunque las dos series lo declaren.
 *    `HISTORICAL_COMPOSITE` identico en las dos NO bloquea por si mismo (el contrato solo bloquea
 *    contexto distinto o sin declarar): la comparacion sigue siendo DESCRIPTIVA y son los avisos
 *    de procedencia los que declaran el caracter compuesto/retrospectivo.
 * 10. [FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT_KEY]: con `SAME_ACQUISITION` en las dos
 *    series, la clave opaca tiene que estar PRESENTE y ser la MISMA en las dos. Sin clave en
 *    alguna (o con claves distintas) no se puede comprobar la misma adquisicion y bloquea.
 * 11. [FvpComparisonBlock.TIME_MAPPING_NOT_VERIFIED]: NINGUN intento SELECCIONADO de ninguna de
 *    las dos series puede carecer de `timeMapping = IDENTITY_VERIFIED` (una condicion sin intento
 *    seleccionado registrado tampoco es un mapeo verificado). El tiempo fisico no se afirma: sin
 *    mapeo verificado no hay deltas.
 *
 * La masa corporal NO bloquea: pasa a [FvpComparisonContext] (con los deltas relativos como
 * primarios cuando difiere). La nota de version ([FvpComparisonContext.versionNoteRequired]) se
 * calcula para el contexto de la rama bloqueada: cuando se muestran deltas las dos versiones son la
 * exacta revisada, de modo que la nota no aplica.
 */
internal fun fvpComparisonPolicy(
    first: StoredFvpAssessment,
    second: StoredFvpAssessment,
): FvpComparisonPolicy {
    val firstAssessment = first.assessment
    val secondAssessment = second.assessment
    val blocks = mutableListOf<FvpComparisonBlock>()

    // 1. Mismo atleta, presente en las DOS filas.
    if (firstAssessment.athleteId == null ||
        secondAssessment.athleteId == null ||
        firstAssessment.athleteId != secondAssessment.athleteId
    ) {
        blocks += FvpComparisonBlock.DIFFERENT_ATHLETE
    }
    // 2. Version EXACTA revisada del algoritmo Y del metodo en las DOS series: un prefijo comun,
    //    una version futura/desconocida o dos cadenas desconocidas iguales no son compatibilidad.
    val versionsReviewed = firstAssessment.algorithmVersion == ALGORITHM_VERSION &&
        secondAssessment.algorithmVersion == ALGORITHM_VERSION &&
        firstAssessment.methodVersion == FVP_METHOD_VERSION &&
        secondAssessment.methodVersion == FVP_METHOD_VERSION
    if (!versionsReviewed) {
        blocks += FvpComparisonBlock.UNKNOWN_OR_DIFFERENT_ALGORITHM
    }
    // 3. Mismo protocolo y mismo modo de h_po.
    if (firstAssessment.protocolId != secondAssessment.protocolId ||
        firstAssessment.pushOffMode != secondAssessment.pushOffMode
    ) {
        blocks += FvpComparisonBlock.DIFFERENT_PROTOCOL
    }
    // 4. Mismo procedimiento declarado CONOCIDO/REVISADO; el VALOR de h_po ya no bloquea aqui.
    if (!sameKnownProcedure(firstAssessment.pushOffProtocol, secondAssessment.pushOffProtocol)) {
        blocks += FvpComparisonBlock.DIFFERENT_PUSH_OFF_PROCEDURE
    }
    // 5. Perfil en las DOS series: DIAGNOSTIC/INCOMPLETE (o fila no recalculable) bloquean.
    val qualityFirst = first.qualityState()
    val qualitySecond = second.qualityState()
    if (qualityFirst.blocksProfileComparison() || qualitySecond.blocksProfileComparison()) {
        blocks += FvpComparisonBlock.NOT_A_PROFILE
    }
    // 6/7/8. Cobertura de cargas primero: sin la MISMA lista de cargas (con multiplicidad) la
    // asociacion carga -> metodo y carga -> h_po no son comparables, y callar la cobertura para
    // decir "metodos distintos" (con los mismos metodos) seria enganoso. La cobertura no relaja el
    // bloqueo: lo explica con el motivo que de verdad difiere.
    val sameLoadCoverage = first.loadCoverageKg() == second.loadCoverageKg()
    if (sameLoadCoverage && first.perLoadMethods() != second.perLoadMethods()) {
        blocks += FvpComparisonBlock.DIFFERENT_MEASURE_METHODS
    }
    if (!sameLoadCoverage) {
        blocks += FvpComparisonBlock.DIFFERENT_LOAD_COVERAGE
    }
    val perLoadPushOffApplies =
        firstAssessment.pushOffMode == PER_CONDITION_MODE ||
            secondAssessment.pushOffMode == PER_CONDITION_MODE
    if (sameLoadCoverage && perLoadPushOffApplies &&
        first.perLoadPushOffPairs() != second.perLoadPushOffPairs()
    ) {
        blocks += FvpComparisonBlock.DIFFERENT_PER_LOAD_PUSH_OFF
    }
    // 9/10/11. B4b: no se publican deltas sin procedencia de adquisicion declarada, comprobable e
    // identica, ni con tiempos sin mapeo verificado. `UNKNOWN`/`NULL` nunca promociona: bloquea.
    val firstContextKind = firstAssessment.acquisitionContextKind
    val secondContextKind = secondAssessment.acquisitionContextKind
    if (firstContextKind != secondContextKind || firstContextKind !in DECLARED_ACQUISITION_CONTEXTS) {
        blocks += FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT
    } else if (firstContextKind == SAME_ACQUISITION_MODE) {
        val firstKey = firstAssessment.acquisitionContextKey
        val secondKey = secondAssessment.acquisitionContextKey
        if (firstKey == null || secondKey == null || firstKey != secondKey) {
            blocks += FvpComparisonBlock.DIFFERENT_ACQUISITION_CONTEXT_KEY
        }
    }
    if (first.hasUnverifiedSelectedTimeMapping() || second.hasUnverifiedSelectedTimeMapping()) {
        blocks += FvpComparisonBlock.TIME_MAPPING_NOT_VERIFIED
    }

    // P2-8: una SOLA decision manda en la lectura primaria y en el papel de cada linea. El contexto
    // expone exactamente este valor (`relativeDeltasArePrimary` == `massDiffers`).
    val relativeDeltasArePrimary = firstAssessment.bodyMassKg != secondAssessment.bodyMassKg
    return FvpComparisonPolicy(
        blocks = blocks.toList(),
        context = fvpComparisonContext(
            first = first,
            second = second,
            qualityFirst = qualityFirst,
            qualitySecond = qualitySecond,
            relativeDeltasArePrimary = relativeDeltasArePrimary,
            perLoadPushOffApplies = perLoadPushOffApplies,
            deltaLines = if (blocks.isEmpty()) {
                deltaLines(first, second, relativeDeltasArePrimary = relativeDeltasArePrimary)
            } else {
                emptyList()
            },
        ),
    )
}

/**
 * Contexto puro de la comparacion. Se calcula SIEMPRE (tambien con bloqueos) porque el contrato
 * exige ensenar el contexto aunque no se comparen numeros; las lineas de delta llegan ya resueltas
 * (vacias en la rama bloqueada).
 *
 * [relativeDeltasArePrimary] y [perLoadPushOffApplies] llegan ya decididos por la politica: el
 * contexto NO recalcula ninguna decision de comparacion ni de lectura (una sola fuente).
 */
private fun fvpComparisonContext(
    first: StoredFvpAssessment,
    second: StoredFvpAssessment,
    qualityFirst: FvpQualityState,
    qualitySecond: FvpQualityState,
    relativeDeltasArePrimary: Boolean,
    perLoadPushOffApplies: Boolean,
    deltaLines: List<FvpComparisonDeltaLine>,
): FvpComparisonContext {
    val firstAssessment = first.assessment
    val secondAssessment = second.assessment
    val velocityFirst = first.velocityCoverage()
    val velocitySecond = second.velocityCoverage()
    // En PER_CONDITION no hay un h_po unico: la asociacion carga-h_po es la que bloquea.
    val bothCommon = firstAssessment.pushOffMode == COMMON_MODE &&
        secondAssessment.pushOffMode == COMMON_MODE
    return FvpComparisonContext(
        bodyMassKg = comparisonValue(firstAssessment.bodyMassKg, secondAssessment.bodyMassKg),
        massDiffers = relativeDeltasArePrimary,
        f0RelNPerKg = comparisonValue(firstAssessment.f0RelNPerKg, secondAssessment.f0RelNPerKg),
        pmaxRelWPerKg = comparisonValue(firstAssessment.pmaxRelWPerKg, secondAssessment.pmaxRelWPerKg),
        deltaLines = deltaLines,
        pushOffM = if (bothCommon) {
            comparisonValue(firstAssessment.pushOffM, secondAssessment.pushOffM)
        } else {
            null
        },
        // P2-7: la diferencia fiable de h_po es la de los valores canonicalizados a la rejilla de
        // entrada (los mismos que compara la regla 8).
        canonicalPushOffM = if (bothCommon) {
            comparisonValue(
                firstAssessment.pushOffM.takeIf { it.isFinite() }?.let(::canonicalPushOffM),
                secondAssessment.pushOffM.takeIf { it.isFinite() }?.let(::canonicalPushOffM),
            )
        } else {
            null
        },
        // P2-6: el h_po por condicion solo es el dato relevante con PER_CONDITION en alguna serie;
        // en COMMON/COMMON la fila de h_po ya ensena el valor unico y repetirlo seria ruido.
        perLoadPushOffFirst = if (perLoadPushOffApplies) first.perLoadPushOffEntries() else null,
        perLoadPushOffSecond = if (perLoadPushOffApplies) second.perLoadPushOffEntries() else null,
        pushOffProvenanceFirst = first.pushOffProvenance(),
        pushOffProvenanceSecond = second.pushOffProvenance(),
        velocityFirst = velocityFirst,
        velocitySecond = velocitySecond,
        narrowRangeWarningApplies = velocityFirst.narrowRange || velocitySecond.narrowRange,
        conditionCountFirst = firstAssessment.conditionCount,
        conditionCountSecond = secondAssessment.conditionCount,
        qualityFirst = qualityFirst,
        qualitySecond = qualitySecond,
        algorithmVersionFirst = firstAssessment.algorithmVersion,
        algorithmVersionSecond = secondAssessment.algorithmVersion,
        methodVersionFirst = firstAssessment.methodVersion,
        methodVersionSecond = secondAssessment.methodVersion,
        sameExactAlgorithmVersion =
        firstAssessment.algorithmVersion == secondAssessment.algorithmVersion,
        sameExactMethodVersion = firstAssessment.methodVersion == secondAssessment.methodVersion,
    )
}

/**
 * Lineas de delta, en orden de lectura. La UNICA decision de lectura es [relativeDeltasArePrimary]
 * (P2-8): los papeles `primary`/`massScaled` de cada linea se derivan de ella con
 * [comparisonDeltaLine], de modo que no pueden contradecir a
 * [FvpComparisonContext.relativeDeltasArePrimary]. Cuando la masa difiere, los primarios son los
 * relativos (N/kg, W/kg) y los absolutos de fuerza/potencia van marcados como escalados por masa;
 * ΔV0 no depende del escalado de la masa y se lee como delta normal en los dos casos.
 */
private fun deltaLines(
    first: StoredFvpAssessment,
    second: StoredFvpAssessment,
    relativeDeltasArePrimary: Boolean,
): List<FvpComparisonDeltaLine> = buildList {
    val firstAssessment = first.assessment
    val secondAssessment = second.assessment
    if (relativeDeltasArePrimary) {
        finiteDelta(firstAssessment.f0RelNPerKg, secondAssessment.f0RelNPerKg)?.let { delta ->
            add(comparisonDeltaLine(FvpComparisonDeltaKind.F0_RELATIVE, delta, relativeDeltasArePrimary))
        }
        finiteDelta(firstAssessment.pmaxRelWPerKg, secondAssessment.pmaxRelWPerKg)?.let { delta ->
            add(comparisonDeltaLine(FvpComparisonDeltaKind.PMAX_RELATIVE, delta, relativeDeltasArePrimary))
        }
    }
    finiteDelta(firstAssessment.f0N, secondAssessment.f0N)?.let { delta ->
        add(comparisonDeltaLine(FvpComparisonDeltaKind.F0_ABSOLUTE, delta, relativeDeltasArePrimary))
    }
    finiteDelta(firstAssessment.pmaxW, secondAssessment.pmaxW)?.let { delta ->
        add(comparisonDeltaLine(FvpComparisonDeltaKind.PMAX_ABSOLUTE, delta, relativeDeltasArePrimary))
    }
    finiteDelta(firstAssessment.v0Mps, secondAssessment.v0Mps)?.let { delta ->
        add(comparisonDeltaLine(FvpComparisonDeltaKind.V0_ABSOLUTE, delta, relativeDeltasArePrimary))
    }
}

/**
 * Construye la linea con su papel DERIVADO de la unica decision de lectura (`relativeDeltasArePrimary`,
 * la misma que expone [FvpComparisonContext.relativeDeltasArePrimary]):
 * - los deltas ABSOLUTOS de fuerza/potencia son las lineas escaladas por masa cuando los relativos
 *   mandan, y las primarias cuando no;
 * - los relativos y ΔV0 (velocidad) nunca son escalados por masa y siempre son la lectura directa.
 */
private fun comparisonDeltaLine(
    kind: FvpComparisonDeltaKind,
    value: Double,
    relativeDeltasArePrimary: Boolean,
): FvpComparisonDeltaLine {
    val massSensitive = kind == FvpComparisonDeltaKind.F0_ABSOLUTE ||
        kind == FvpComparisonDeltaKind.PMAX_ABSOLUTE
    return FvpComparisonDeltaLine(
        kind = kind,
        value = value,
        primary = if (massSensitive) !relativeDeltasArePrimary else true,
        massScaled = massSensitive && relativeDeltasArePrimary,
    )
}

/**
 * Procedimientos de h_po CONOCIDOS y REVISADOS por la app. La comparacion exige el mismo
 * procedimiento y que este en esta lista: un texto libre no reconocido (aunque coincida en las dos
 * series) no establece compatibilidad (D5). Hoy el unico procedimiento canonico declarado por el
 * flujo es [LANDMARK_EXTENDED_MINUS_CROUCH]; no se admite ningun otro por prefijo ni por
 * coincidencia de cadenas desconocidas.
 */
private val KNOWN_PUSH_OFF_PROCEDURES: Set<String> = setOf("LANDMARK_EXTENDED_MINUS_CROUCH")

/**
 * `true` solo si los dos PROCEDIMIENTOS declarados son el MISMO y estan en [KNOWN_PUSH_OFF_PROCEDURES]
 * (comparados sin espacios alrededor). Ausente, en blanco o no reconocido NUNCA es compatible.
 */
private fun sameKnownProcedure(first: String?, second: String?): Boolean {
    val normalisedFirst = normalisedProcedure(first)
    val normalisedSecond = normalisedProcedure(second)
    return normalisedFirst.isNotEmpty() &&
        normalisedFirst == normalisedSecond &&
        normalisedFirst in KNOWN_PUSH_OFF_PROCEDURES
}

/**
 * Distancia h_po canonicalizada a la rejilla de entrada de 0,5 cm documentada en B1: es la UNICA
 * tolerancia admitida (ruido de ida y vuelta metrico/imperial), nunca una equivalencia inventada.
 */
internal fun canonicalPushOffM(value: Double): Double =
    if (value.isFinite()) {
        Math.round(value / FVP_PUSH_OFF_ENTRY_GRID_M) * FVP_PUSH_OFF_ENTRY_GRID_M
    } else {
        value
    }

/** Procedimiento declarado normalizado (sin espacios alrededor); `null` == no declarado. */
private fun normalisedProcedure(procedure: String?): String = procedure.orEmpty().trim()

/**
 * Estado de calidad DERIVADO de la fila guardada con la MISMA tabla que la UI del resultado
 * (`fvpQualityState`): elegibilidad + ajuste PERSISTIDO (R^2 finito de la fila y avisos del
 * snapshot). El snapshot debe estar VERIFICADO (`MATCHES`): con `MISMATCH`, `STALE`, `STALE_METHOD`
 * o `RECALCULATION_UNAVAILABLE` la fila sigue legible pero no puede establecer deltas -> DIAGNOSTIC
 * (D5). Sin elegibilidad (fila no recalculable) no hay nada mejor que [FvpQualityState.INCOMPLETE]:
 * nunca se inventa una calidad.
 */
private fun StoredFvpAssessment.qualityState(): FvpQualityState {
    val eligibility = eligibility ?: return FvpQualityState.INCOMPLETE
    if (snapshotStatus != FvpSnapshotStatus.MATCHES) return FvpQualityState.DIAGNOSTIC
    return fvpQualityState(
        eligibility.toQualityInput(
            // Q1: un ajuste sin R^2 FINITO no es juzgable (mismo criterio que la vista original).
            r2Finite = assessment.r2?.isFinite() == true,
            warningNames = warnings.toSet(),
        ),
    )
}

/** `true` si la serie NO es un perfil comparable: diagnostica o incompleta. */
private fun FvpQualityState.blocksProfileComparison(): Boolean = when (this) {
    FvpQualityState.DIAGNOSTIC,
    FvpQualityState.INCOMPLETE,
    -> true

    FvpQualityState.CONDITIONAL,
    FvpQualityState.PROFILE,
    -> false
}

/**
 * Asociacion ORDENADA carga -> metodo del intento SELECCIONADO de cada condicion (los que producen
 * los puntos). `null` = la condicion no tiene intento seleccionado, y eso TAMBIEN es una asociacion
 * distinta (nunca se ignora la carga).
 */
private fun StoredFvpAssessment.perLoadMethods(): List<Pair<Double, String?>> =
    conditions
        .map { condition ->
            val ordinal = condition.selectedAttemptOrdinal
            val method = if (ordinal == null) {
                null
            } else {
                attempts.firstOrNull { it.conditionId == condition.id && it.ordinal == ordinal }?.method
            }
            condition.addedLoadKg to method
        }
        .sortedWith(compareBy<Pair<Double, String?>> { it.first }.thenBy { it.second ?: "" })

/** Lista ORDENADA de cargas adicionales CON multiplicidad (`[0,20,20,40]` != `[0,20,40]`). */
private fun StoredFvpAssessment.loadCoverageKg(): List<Double> =
    conditions.map { it.addedLoadKg }.sorted()

/**
 * Pares ORDENADOS `(carga, h_po canonicalizado a la rejilla de 0,5 cm)` por condicion. La
 * ordenacion por carga y despues por h_po hace la comparacion independiente del orden de las
 * condiciones, pero CONSERVA que h_po pertenece a que carga: intercambiar los h_po entre cargas
 * produce pares distintos (los puntos F-V cambian). Se DERIVAN de [perLoadPushOffEntries] para que
 * la regla y el dato que la UI ensena (P2-6) no puedan divergir.
 */
private fun StoredFvpAssessment.perLoadPushOffPairs(): List<Pair<Double, Double>> =
    perLoadPushOffEntries().map { it.loadKg to it.pushOffM }

/**
 * Los mismos pares por condicion, ya como dato de DIAGNOSTICO que la UI puede ensenar (P2-6): h_po
 * CANONICALIZADO (el valor que compara la regla 8), ordenado por carga y despues por h_po.
 */
private fun StoredFvpAssessment.perLoadPushOffEntries(): List<FvpPerLoadPushOffEntry> =
    conditions.map { FvpPerLoadPushOffEntry(it.addedLoadKg, canonicalPushOffM(it.pushOffM)) }
        .sortedWith(compareBy({ it.loadKg }, { it.pushOffM }))

/** Contexto de velocidad medido de la serie + aviso de rango estrecho (ΔV < 0,15 m/s) de B4. */
private fun StoredFvpAssessment.velocityCoverage(): FvpSeriesVelocityCoverage {
    val measuredSpan = span(assessment.vMinMps, assessment.vMaxMps)
    // El aviso puede venir PERSISTIDO en la fila o deducirse del rango medido: los dos cuentan.
    val storedNarrowRange = NARROW_VELOCITY_RANGE_WARNING in warnings
    return FvpSeriesVelocityCoverage(
        vMinMps = assessment.vMinMps,
        vMaxMps = assessment.vMaxMps,
        narrowRange = storedNarrowRange ||
            (measuredSpan != null && measuredSpan < FVP_NARROW_VELOCITY_RANGE_MPS),
    )
}

/** Procedencia B1 declarada de h_po en la fila (origen, hito y fecha de medicion). */
private fun StoredFvpAssessment.pushOffProvenance(): FvpPushOffProvenance = FvpPushOffProvenance(
    source = assessment.pushOffSource,
    procedure = assessment.pushOffProtocol,
    landmark = assessment.pushOffLandmark,
    measuredAt = assessment.pushOffMeasuredAt,
)

/** `vMax - vMin` solo si las dos cotas son finitas; nunca una diferencia inventada. */
private fun span(min: Double?, max: Double?): Double? =
    if (min != null && max != null && min.isFinite() && max.isFinite()) max - min else null

/** Valor de un campo en las dos series y su diferencia (`segunda - primera`) si es medible. */
private fun comparisonValue(first: Double?, second: Double?): FvpComparisonValue = FvpComparisonValue(
    first = first?.takeIf { it.isFinite() },
    second = second?.takeIf { it.isFinite() },
    delta = finiteDelta(first, second),
)

/** `segunda - primera` solo con los dos valores finitos; `null` == no medible (nunca 0.0 sustituto). */
private fun finiteDelta(first: Double?, second: Double?): Double? = if (first != null &&
    second != null &&
    first.isFinite() &&
    second.isFinite()
) {
    second - first
} else {
    null
}

/** Clave persistida del modo de h_po unico para toda la serie (misma que la entidad). */
private const val COMMON_MODE = "COMMON"

/** Clave persistida del modo de h_po distinto por condicion (misma que la entidad). */
private const val PER_CONDITION_MODE = "PER_CONDITION"

/** Contextos de adquisicion DECLARADOS y reconocidos por esta version (nunca `UNKNOWN`). */
private val DECLARED_ACQUISITION_CONTEXTS = setOf("SAME_ACQUISITION", "HISTORICAL_COMPOSITE")

/** Clave persistida de la adquisicion UNICA declarada (misma que la entidad). */
private const val SAME_ACQUISITION_MODE = "SAME_ACQUISITION"

/** Mapeo temporal verificado (misma clave que `fvp_attempts.timeMapping`). */
private const val IDENTITY_VERIFIED_MAPPING = "IDENTITY_VERIFIED"

/**
 * `true` si ALGUN intento SELECCIONADO de la serie no declara `IDENTITY_VERIFIED` (B4b). La
 * seleccion es la que la fila REGISTRA (`selectedAttemptOrdinal`, la de la vista `ORIGINAL`): una
 * condicion sin ordinal, o cuyo ordinal no existe entre sus intentos, NO tiene un mapeo verificado
 * y cuenta como no verificado (el peor caso incluye `UNKNOWN`). Una serie sin condiciones no
 * afirma ninguna seleccion y no dispara el motivo.
 */
private fun StoredFvpAssessment.hasUnverifiedSelectedTimeMapping(): Boolean =
    conditions.any { condition ->
        val ordinal = condition.selectedAttemptOrdinal ?: return@any true
        val mapping = attempts
            .firstOrNull { it.conditionId == condition.id && it.ordinal == ordinal }
            ?.timeMapping
        mapping != IDENTITY_VERIFIED_MAPPING
    }
