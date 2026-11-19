package com.openjump.app.testsupport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Regresión del helper compartido [awaitValue]: espera POSITIVA sobre un `StateFlow` real, con
 * estado inicial, `null` válido, cota con contexto del último valor y excepciones reales intactas.
 *
 * No usa `kotlinx-coroutines-test` (no es dependencia del módulo): observa flujos reales y una cota
 * corta, igual que los arneses que lo consumen.
 */
class AwaitStateFlowTest {

    @Test
    fun `el estado inicial que ya cumple se devuelve sin esperar emision`() {
        runBlocking {
            val flow = MutableStateFlow("listo")

            assertEquals("listo", flow.awaitValue("el valor listo", 5_000L) { it == "listo" })
        }
    }

    @Test
    fun `espera la emision positiva e ignora los valores que no cumplen`() {
        runBlocking {
            val flow = MutableStateFlow(1)
            launch(Dispatchers.Default) {
                delay(20L)
                flow.value = 2
                delay(20L)
                flow.value = 3
            }

            assertEquals(3, flow.awaitValue("el valor 3", 5_000L) { it == 3 })
        }
    }

    @Test
    fun `un null emitido es una coincidencia valida y no un timeout`() {
        runBlocking {
            val flow = MutableStateFlow<String?>("pendiente")
            launch(Dispatchers.Default) {
                delay(20L)
                flow.value = null
            }

            assertNull(flow.awaitValue("el valor nulo", 5_000L) { it == null })
        }
    }

    @Test
    fun `un null inicial tambien se devuelve sin esperar emision`() {
        runBlocking {
            val flow = MutableStateFlow<String?>(null)

            assertNull(flow.awaitValue("el valor nulo", 5_000L) { it == null })
        }
    }

    @Test
    fun `el timeout falla con el ultimo valor observado y no como exito`() {
        runBlocking {
            val flow = MutableStateFlow("pendiente")
            launch(Dispatchers.Default) {
                delay(10L)
                flow.value = "pendiente-2"
            }

            val failure = runCatching {
                flow.awaitValue("el valor listo", timeoutMillis = 500L) { it == "listo" }
            }.exceptionOrNull()

            assertTrue("un timeout no puede devolver un valor: $failure", failure is AssertionError)
            assertFalse(
                "la cancelación de la corrutina no puede escapar como resultado",
                failure is TimeoutCancellationException,
            )
            val message = requireNotNull(failure?.message)
            assertTrue(message, message.contains("el valor listo"))
            assertTrue(message, message.contains("último estado: pendiente-2"))
        }
    }

    @Test
    fun `un fallo real del predicado se propaga tal cual`() {
        runBlocking {
            val flow = MutableStateFlow("pendiente")

            val failure = runCatching {
                flow.awaitValue("el valor listo", timeoutMillis = 5_000L) { throw PredicateBoom() }
            }.exceptionOrNull()

            assertTrue(
                "la excepción real no se convierte en espera ni en éxito: $failure",
                failure is PredicateBoom,
            )
        }
    }

    @Test
    fun `el adaptador bloqueante espera la emision y devuelve el valor`() {
        val flow = MutableStateFlow("pendiente")
        val producer = thread(name = "await-state-flow-producer") {
            Thread.sleep(20L)
            flow.value = "listo"
        }
        try {
            assertEquals("listo", flow.awaitValueBlocking("el valor listo", 5_000L) { it == "listo" })
        } finally {
            producer.join()
        }
    }

    private class PredicateBoom : RuntimeException("fallo real del predicado")
}
