package com.openjump.app.video.angles

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM del permiso de proceso ([NonSeekablePollingLease]) y de la secuencia de apertura
 * ([NonBlockingSourceStreamOpener]) que protege `O_NONBLOCK`.
 *
 * ## Qué demuestran
 *
 * `O_NONBLOCK` pertenece a la *open file description*, no a un descriptor: dos `dup` del mismo extremo
 * de lectura la comparten. Con dos adaptadores en vuelo, el primero en cerrar desactivaba la bandera
 * mientras el otro seguía leyendo (lectura bloqueante y cancelación no demostrable) y el último en
 * cerrar la dejaba activada en los descriptores del proveedor. Estas pruebas fijan el contrato que
 * elimina ese escenario sin esperar hilos:
 *
 * - el permiso se toma **antes** de `F_GETFL`, así que las banderas originales guardadas no pueden
 *   incluir una activada por otro adaptador;
 * - el segundo flujo se rechaza con [SourceDescriptorBusy] **sin invocar ningún puerto**: no lee, no
 *   escribe banderas y no cierra el descriptor del segundo intento;
 * - el permiso se mantiene hasta **después** de restaurar las banderas y cerrar el descriptor, y se
 *   libera en todos los caminos: apertura fallida (`F_GETFL`/`F_SETFL`), cancelación y excepción de
 *   lectura, y cierre normal;
 * - no hay hilos en espera: con intentos concurrentes gana exactamente uno y el resto recibe el rechazo
 *   inmediato (un `Future.get` con plazo lo comprueba);
 * - el cierre de un titular tardío no retira el permiso de un flujo abierto después (liberación por
 *   testigo).
 *
 * Los puertos son dobles deterministas: aquí no se toca Android ni descriptores reales. La prueba
 * instrumentada `NonSeekableStreamFlagSharingInstrumentedTest` confirma lo mismo con `dup` de un pipe
 * real y `F_GETFL`/`F_SETFL` de plataforma.
 */
class NonSeekableStreamLeaseTest {

    /**
     * Doble de los puertos de banderas y cierre. Registra el **orden** de las llamadas porque el
     * contrato exige restaurar antes de cerrar y liberar el permiso después de ambas.
     */
    private class FakeDescriptorPorts(
        var flags: Int = INITIAL_FLAGS,
        private val readFailure: IOException? = null,
        private val successfulWritesBeforeFailure: Int = Int.MAX_VALUE,
    ) {
        val events = mutableListOf<String>()
        var descriptorCloses = 0
            private set
        private var writeAttempts = 0

        fun readFlags(): Int {
            events += "F_GETFL"
            readFailure?.let { throw it }
            return flags
        }

        fun writeFlags(value: Int) {
            events += "F_SETFL:$value"
            writeAttempts += 1
            if (writeAttempts > successfulWritesBeforeFailure) {
                throw IOException("F_SETFL simulado falló")
            }
            flags = value
        }

        fun closeDescriptor() {
            events += "close"
            descriptorCloses += 1
        }
    }

    private fun open(
        ports: FakeDescriptorPorts,
        cancellationCheck: () -> Boolean = { false },
        readRaw: (buffer: ByteArray, offset: Int, length: Int) -> Int = { _, _, _ -> 1 },
        awaitReadable: (timeoutMillis: Int) -> Boolean = { true },
    ): PollingSourceStream = NonBlockingSourceStreamOpener.open(
        // El permiso y las banderas solo se ejercitan en versiones con `fcntlInt` público; la
        // política de versión tiene su propio guard en NonSeekablePlatformGateTest.
        platformApiLevel = SUPPORTED_API_LEVEL,
        nonBlockingFlag = NON_BLOCKING_FLAG,
        readFlags = ports::readFlags,
        writeFlags = ports::writeFlags,
        closeDescriptor = ports::closeDescriptor,
        awaitReadable = awaitReadable,
        readRaw = readRaw,
        cancellationCheck = cancellationCheck,
    )

    @Test
    fun `el segundo flujo no seekable se rechaza sin leer ni escribir banderas`() {
        val first = FakeDescriptorPorts()
        val second = FakeDescriptorPorts()
        val streamA = open(first)
        try {
            assertEquals(
                "El primer flujo activa O_NONBLOCK sobre el descriptor",
                listOf("F_GETFL", "F_SETFL:${INITIAL_FLAGS or NON_BLOCKING_FLAG}"),
                first.events,
            )
            assertTrue("El permiso debe quedar tomado mientras A vive", NonSeekablePollingLease.isHeld)

            val error = runCatching { open(second) }.exceptionOrNull()
            assertTrue(
                "La apertura concurrente debe ser un rechazo tipado y no un fallo ambiguo: $error",
                error is SourceDescriptorBusy,
            )
            assertEquals(
                "El segundo intento no puede tocar banderas ni cerrar su descriptor",
                emptyList<String>(),
                second.events,
            )
            assertEquals("El descriptor del segundo intento no se cierra desde aquí", 0, second.descriptorCloses)
            assertTrue("El permiso sigue siendo de A", NonSeekablePollingLease.isHeld)
        } finally {
            streamA.close()
        }
        assertFalse("Cerrar A libera el permiso", NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `cerrar restaura banderas y cierra el descriptor antes de liberar el permiso`() {
        val ports = FakeDescriptorPorts()
        val stream = open(ports)
        assertEquals(
            "El estado inicial se guarda tras tomar el permiso y antes de modificarlo",
            listOf("F_GETFL", "F_SETFL:${INITIAL_FLAGS or NON_BLOCKING_FLAG}"),
            ports.events,
        )
        stream.close()
        assertEquals(
            "Restaurar → cerrar; el último paso (liberar el permiso) no toca el descriptor",
            listOf(
                "F_GETFL",
                "F_SETFL:${INITIAL_FLAGS or NON_BLOCKING_FLAG}",
                "F_SETFL:$INITIAL_FLAGS",
                "close",
            ),
            ports.events,
        )
        assertEquals("Las banderas vuelven exactamente al estado inicial", INITIAL_FLAGS, ports.flags)
        assertEquals("El descriptor se cierra una sola vez", 1, ports.descriptorCloses)
        assertFalse(NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `si F_GETFL falla no se escribe ninguna bandera y el permiso se libera`() {
        val ports = FakeDescriptorPorts(readFailure = IOException("F_GETFL simulado falló"))
        val error = runCatching { open(ports) }.exceptionOrNull()
        assertTrue("El fallo de lectura de banderas se propaga como E/S: $error", error is IOException)
        assertFalse("No puede degradarse en rechazo de concurrencia", error is SourceDescriptorBusy)
        assertEquals("Sin F_GETFL no se escribe ninguna bandera, pero el descriptor se cierra", listOf("F_GETFL", "close"), ports.events)
        assertEquals("El descriptor se cierra (posesión del adaptador)", 1, ports.descriptorCloses)
        assertEquals("Las banderas siguen en el estado inicial", INITIAL_FLAGS, ports.flags)
        assertFalse("El permiso no puede quedar tomado por una apertura fallida", NonSeekablePollingLease.isHeld)
        // Recuperable: una apertura nueva funciona.
        val retryPorts = FakeDescriptorPorts()
        val retry = open(retryPorts)
        try {
            assertTrue(NonSeekablePollingLease.isHeld)
        } finally {
            retry.close()
        }
        assertFalse(NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `si F_SETFL falla se intenta restaurar, se cierra el descriptor y el permiso se libera`() {
        val ports = FakeDescriptorPorts(successfulWritesBeforeFailure = 0)
        val error = runCatching { open(ports) }.exceptionOrNull()
        assertTrue("El fallo de F_SETFL se propaga como E/S: $error", error is IOException)
        assertEquals(
            "Se intenta activar y luego restaurar el estado inicial antes de cerrar",
            listOf(
                "F_GETFL",
                "F_SETFL:${INITIAL_FLAGS or NON_BLOCKING_FLAG}",
                "F_SETFL:$INITIAL_FLAGS",
                "close",
            ),
            ports.events,
        )
        assertEquals("El descriptor se cierra", 1, ports.descriptorCloses)
        assertEquals("Las banderas quedan en el estado inicial", INITIAL_FLAGS, ports.flags)
        assertFalse("El permiso se libera aunque la activación falle", NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `la cancelacion durante la lectura no retiene el permiso y restaura al cerrar`() {
        val ports = FakeDescriptorPorts()
        val stream = open(ports, cancellationCheck = { true })
        try {
            val error = runCatching { stream.read(ByteArray(8), 0, 8) }.exceptionOrNull()
            assertTrue("La cancelación debe ser tipada: $error", error is SourceReadCancelled)
            assertTrue("El flujo sigue abierto hasta que el llamador lo cierre", NonSeekablePollingLease.isHeld)
        } finally {
            // `use` cierra el flujo también cuando la copia se cancela.
            stream.close()
        }
        assertEquals(
            "Al cerrar tras la cancelación se restauran banderas antes de cerrar",
            "F_SETFL:$INITIAL_FLAGS",
            ports.events[2],
        )
        assertEquals("close", ports.events[3])
        assertEquals("El descriptor se cierra", 1, ports.descriptorCloses)
        assertFalse("La cancelación no puede dejar el permiso tomado", NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `la cancelacion activada durante el sondeo no lee y libera el permiso al cerrar`() {
        val ports = FakeDescriptorPorts()
        var cancelled = false
        var reads = 0
        val stream = open(
            ports,
            cancellationCheck = { cancelled },
            readRaw = { _, _, _ ->
                reads += 1
                0
            },
            // El sondeo devuelve EOF en el mismo instante en que se cancela: sin revalidar, la
            // lectura seguiría y el cierre correspondería a una apertura ya cancelada.
            awaitReadable = {
                cancelled = true
                true
            },
        )
        try {
            val error = runCatching { stream.read(ByteArray(8), 0, 8) }.exceptionOrNull()
            assertTrue("La cancelación observada en el sondeo debe ser tipada: $error", error is SourceReadCancelled)
            assertEquals("No se puede leer tras observar la cancelación en el sondeo", 0, reads)
            assertTrue("El flujo sigue abierto hasta que el llamador lo cierre", NonSeekablePollingLease.isHeld)
        } finally {
            stream.close()
        }
        assertEquals(
            "Al cerrar se restauran banderas antes de cerrar el descriptor",
            listOf(
                "F_GETFL",
                "F_SETFL:${INITIAL_FLAGS or NON_BLOCKING_FLAG}",
                "F_SETFL:$INITIAL_FLAGS",
                "close",
            ),
            ports.events,
        )
        assertEquals("Las banderas vuelven al estado inicial", INITIAL_FLAGS, ports.flags)
        assertEquals("El descriptor se cierra una sola vez", 1, ports.descriptorCloses)
        assertFalse("La cancelación en el sondeo no puede dejar el permiso tomado", NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `una excepcion de lectura no retiene el permiso y restaura al cerrar`() {
        val ports = FakeDescriptorPorts()
        val stream = open(ports, readRaw = { _, _, _ -> throw IOException("fallo de E/S simulado") })
        try {
            val error = runCatching { stream.read(ByteArray(8), 0, 8) }.exceptionOrNull()
            assertTrue("El fallo de lectura se propaga como E/S: $error", error is IOException)
        } finally {
            stream.close()
        }
        assertEquals("Las banderas vuelven al estado inicial", INITIAL_FLAGS, ports.flags)
        assertEquals(1, ports.descriptorCloses)
        assertFalse("Una excepción de lectura no puede dejar el permiso tomado", NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `con intentos concurrentes gana exactamente uno y ninguno espera`() {
        val attempts = 16
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(attempts)
        try {
            val futures = (0 until attempts).map {
                executor.submit<Result<PollingSourceStream>> {
                    ready.countDown()
                    start.await()
                    runCatching { open(FakeDescriptorPorts()) }
                }
            }
            assertTrue("Los intentos deben estar listos", ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
            val winners = outcomes.mapNotNull { it.getOrNull() }
            val rejections = outcomes.mapNotNull { it.exceptionOrNull() }
            assertEquals("Sólo un flujo no seekable puede quedar en vuelo", 1, winners.size)
            assertEquals("El resto recibe rechazo inmediato, no una espera", attempts - 1, rejections.size)
            assertTrue(
                "Todos los rechazos deben ser del tipo tipado: ${rejections.map { it.javaClass.simpleName }}",
                rejections.all { it is SourceDescriptorBusy },
            )
            assertNotNull("El ganador conserva el permiso hasta que se cierre", winners.single())
            winners.single().close()
        } finally {
            executor.shutdownNow()
            assertFalse("Ningún permiso puede quedar tomado al final", NonSeekablePollingLease.isHeld)
        }
    }

    @Test
    fun `una liberacion tardia no retira el permiso de un flujo abierto despues`() {
        val first = NonSeekablePollingLease.tryAcquire()
        assertNotNull("El primer testigo debe obtener el permiso", first)
        NonSeekablePollingLease.release(first!!)
        assertFalse(NonSeekablePollingLease.isHeld)

        val second = NonSeekablePollingLease.tryAcquire()
        assertNotNull(second)
        // Un cierre duplicado del titular anterior no puede robar el permiso del nuevo.
        NonSeekablePollingLease.release(first)
        assertTrue("El testigo viejo no libera el permiso del titular actual", NonSeekablePollingLease.isHeld)
        NonSeekablePollingLease.release(second!!)
        assertFalse(NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `el permiso se puede reintentar tras un cierre`() {
        val ports = FakeDescriptorPorts()
        open(ports).close()
        assertFalse("Tras cerrar, el permiso queda libre", NonSeekablePollingLease.isHeld)
        val retryPorts = FakeDescriptorPorts()
        val retry = open(retryPorts)
        try {
            assertTrue(NonSeekablePollingLease.isHeld)
        } finally {
            retry.close()
        }
        assertEquals("El reintento vuelve a dejar el estado inicial", INITIAL_FLAGS, retryPorts.flags)
        assertFalse(NonSeekablePollingLease.isHeld)
    }

    private companion object {
        /** API 30: primera versión con `fcntlInt` en la superficie pública del SDK. */
        const val SUPPORTED_API_LEVEL = 30

        /** `O_NONBLOCK` en Linux/Android; en JVM se inyecta para no depender de `OsConstants`. */
        const val NON_BLOCKING_FLAG = 0x800

        /**
         * Bits arbitrarios distintos de `O_NONBLOCK` (`O_LARGEFILE`-like) para comprobar que la
         * restauración conserva la palabra completa de banderas y no sólo la bandera de bloqueo.
         */
        const val INITIAL_FLAGS = 0x8000
    }
}
