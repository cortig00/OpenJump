package com.openjump.app.testsupport

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Espera de CONDICIÓN POSITIVA sobre un [StateFlow] público, compartida por las pruebas JVM
 * (`app/src/test`, `app/src/jumpValidationTest`) y las instrumentadas (`app/src/androidTest`).
 *
 * Antes de este helper cada arnés sondeaba con `Thread.sleep` y comparaba `value` en un bucle: eso
 * gastaba CPU, alargaba el tiempo mínimo y, sobre todo, repetía la misma lógica (cota, último valor,
 * mensaje) en cada sitio. Aquí la espera es cooperativa: `first` observa las emisiones reales del
 * flujo y `withTimeoutOrNull` pone la cota. No se simulan eventos ni se escribe el flujo: se espera
 * su valor publicado.
 *
 * Contrato:
 *
 * - **estado inicial**: si el valor publicado ya cumple [predicate] se devuelve sin esperar emisión.
 * - **`null` es un valor válido**: `T` admite null; una coincidencia `null` se devuelve como `null`,
 *   nunca se confunde con un timeout.
 * - **cota**: [timeoutMillis] es el máximo de espera; al agotarse se lanza `AssertionError` con el
 *   último valor observado (con contexto del [what] esperado).
 * - **excepciones reales**: sólo se traduce el timeout propio; una excepción lanzada por
 *   [predicate] o cualquier otra cancelación se propaga tal cual, sin convertirse en éxito.
 *
 * No crea hilos, dispatchers ni ámbitos: usa el contexto de quien llama.
 */
suspend fun <T> StateFlow<T>.awaitValue(
    what: String,
    timeoutMillis: Long,
    describe: (T) -> String = { it.toString() },
    predicate: (T) -> Boolean,
): T {
    var latest: T = value
    // `withTimeoutOrNull` sólo devuelve `null` por SU timeout (comprueba la propiedad de la
    // cancelación); un timeout de un ámbito superior se propaga como cancelación real. El resultado
    // va envuelto para que un `null` legítimo de `T` no se confunda con la cota agotada.
    val matched = withTimeoutOrNull(timeoutMillis) {
        AwaitResult(
            first { candidate ->
                latest = candidate
                predicate(candidate)
            },
        )
    }
    if (matched == null) {
        throw AssertionError("Timeout esperando «$what»; último estado: ${describe(latest)}")
    }
    return matched.value
}

/** Envoltorio no nulo: distingue una coincidencia `null` de la cota agotada. */
private class AwaitResult<T>(val value: T)

/**
 * Adaptador bloqueante de [awaitValue] para llamantes que no son `suspend` (métodos JUnit y
 * arneses que corren en el hilo de pruebas/instrumentación y no pueden volverse `suspend`).
 *
 * Bloquea el hilo que llama; está pensado para el hilo de pruebas/instrumentación, nunca para el
 * hilo principal. No cambia dispatchers ni ejecuta el flujo en otro hilo. La cota y las excepciones
 * son las de [awaitValue].
 */
fun <T> StateFlow<T>.awaitValueBlocking(
    what: String,
    timeoutMillis: Long,
    describe: (T) -> String = { it.toString() },
    predicate: (T) -> Boolean,
): T = runBlocking { awaitValue(what, timeoutMillis, describe, predicate) }
