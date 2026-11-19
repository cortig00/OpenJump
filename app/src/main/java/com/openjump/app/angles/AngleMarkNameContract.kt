package com.openjump.app.angles

/**
 * Contrato compartido del nombre de un ángulo manual: el MISMO límite y la MISMA unidad en el
 * editor, en la frontera de persistencia ([com.openjump.app.data.AngleAggregateRules]) y en el
 * backup manual.
 *
 * El nombre de una marca viaja tal cual a `angle_marks.name` y de ahí al payload del backup, donde
 * [com.openjump.app.data.backup.ManualBackupPreflight] aplica un presupuesto por cadena. Ese
 * presupuesto se mide en **unidades de código UTF-16** (`String.length` de Kotlin), así que ese es
 * el límite público: un nombre aceptado por el editor y persistido por el repositorio nunca puede
 * romper un backup completo.
 *
 * Unidad exacta (no es "caracteres visibles" ni bytes):
 *  - una letra ASCII cuenta 1;
 *  - una letra no ASCII precompuesta o de un plano básico (`á`, `ñ`, `Ω`, `文`) cuenta 1;
 *  - un carácter fuera del BMP (un emoji, por ejemplo) es un par sustituto y cuenta 2;
 *  - los caracteres de control también cuentan 1 (el codec los escribe escapados, y el preflight
 *    cuenta el carácter decodificado, no los bytes del escape).
 *
 * Nunca se trunca: quien rechaza un nombre largo lo hace con un error explícito para no guardar en
 * silencio un nombre distinto del que vio la persona.
 */
object AngleMarkNameContract {

    /** Máximo de unidades de código UTF-16 admitidas en el nombre de un ángulo. */
    const val MAX_CODE_UNITS: Int = 4_096

    /** Unidades de código UTF-16 del nombre: la misma medida que aplica el preflight del backup. */
    fun codeUnits(name: String): Int = name.length

    /** `true` si el nombre cabe en el contrato; no decide si está vacío (eso es otra regla). */
    fun fits(name: String): Boolean = name.length <= MAX_CODE_UNITS
}
