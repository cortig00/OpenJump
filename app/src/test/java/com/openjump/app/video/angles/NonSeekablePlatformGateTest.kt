package com.openjump.app.video.angles

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM de la política de versión de Android para descriptores **no seekables**.
 *
 * `Os.fcntlInt` (`F_GETFL`/`F_SETFL`) entró en la superficie pública del SDK en API 30. Con
 * `minSdk` 26 y sin reflexión ni API oculta, en API 26–29 un pipe/socket no se puede poner en modo no
 * bloqueante desde API pública: la decisión aprobada es **rechazarlo** con un fallo tipado (y ofrecer
 * una copia local del vídeo), no emitir una lectura bloqueante sin cancelación demostrable ni usar API
 * oculta.
 *
 * Estas pruebas fijan, sin Android ni descriptores reales:
 *
 * 1. la **política** ([NonSeekablePollingSupport]): límite exacto 26–29 → rechazo, 30+ → admitido;
 * 2. el **orden y los cero efectos** del rechazo: el abridor comprueba la versión **antes** de tomar
 *    el permiso de proceso, de leer/escribir banderas y de cerrar el descriptor, de modo que el
 *    llamador conserva su descriptor y no queda ningún permiso tomado;
 * 3. la **traducción** de la excepción a [SourceOpenFailure]: el rechazo de plataforma tiene su propio
 *    código (nunca la E/S ambigua genérica) y los demás fallos conservan el suyo.
 *
 * El límite se documenta explícitamente: un AVD API 35 **no** demuestra el comportamiento en API
 * 26–29; la prueba instrumentada `NonSeekableSourceApiGateInstrumentedTest` cubre esa rama en un
 * dispositivo real cuando se ejecute en esas versiones, y aquí se fija la política pura.
 */
class NonSeekablePlatformGateTest {

    /** Puertos falsos que registran cualquier efecto: el rechazo por versión no debe invocar ninguno. */
    private class FakeDescriptorPorts {
        val events = mutableListOf<String>()
        var descriptorCloses = 0
            private set
        var flags = INITIAL_FLAGS

        fun readFlags(): Int {
            events += "F_GETFL"
            return flags
        }

        fun writeFlags(value: Int) {
            events += "F_SETFL:$value"
            flags = value
        }

        fun closeDescriptor() {
            events += "close"
            descriptorCloses += 1
        }
    }

    private fun open(
        ports: FakeDescriptorPorts,
        platformApiLevel: Int,
    ): PollingSourceStream = NonBlockingSourceStreamOpener.open(
        platformApiLevel = platformApiLevel,
        nonBlockingFlag = NON_BLOCKING_FLAG,
        readFlags = ports::readFlags,
        writeFlags = ports::writeFlags,
        closeDescriptor = ports::closeDescriptor,
        awaitReadable = { true },
        readRaw = { _, _, _ -> 1 },
        cancellationCheck = { false },
    )

    @Test
    fun `la politica rechaza api 26 a 29 y admite api 30 y posteriores`() {
        assertEquals(
            "La superficie pública de fcntlInt entra en API 30 (Build.VERSION_CODES.R)",
            30,
            NonSeekablePollingSupport.MIN_SUPPORTED_API,
        )
        for (level in listOf(26, 27, 28, 29)) {
            assertFalse(
                "API $level no expone fcntlInt en el SDK público: no puede habilitar la ruta no bloqueante",
                NonSeekablePollingSupport.isSupported(level),
            )
        }
        for (level in listOf(30, 31, 33, 35, 36)) {
            assertTrue("API $level debe conservar el sondeo no bloqueante", NonSeekablePollingSupport.isSupported(level))
        }
    }

    @Test
    fun `en api 26 a 29 se rechaza antes del permiso y sin tocar banderas ni descriptor`() {
        assertFalse("La prueba exige el permiso libre al empezar", NonSeekablePollingLease.isHeld)
        val ports = FakeDescriptorPorts()
        val error = runCatching { open(ports, platformApiLevel = 29) }.exceptionOrNull()

        assertTrue("El rechazo debe ser una E/S tipada y no un fallo ambiguo: $error", error is SourceNonSeekableUnsupported)
        assertTrue("El rechazo debe ser una IOException de la capa de E/S", error is IOException)
        assertFalse("El rechazo de plataforma no es concurrencia", error is SourceDescriptorBusy)
        assertFalse("El rechazo de plataforma no es una cancelación", error is SourceReadCancelled)
        assertTrue(
            "El mensaje debe indicar la alternativa de copia local: ${error?.message}",
            error?.message?.contains("copia local") == true,
        )
        assertEquals("Antes del rechazo no se puede leer ni escribir ninguna bandera", emptyList<String>(), ports.events)
        assertEquals("El descriptor no se cierra: sigue siendo propiedad del llamador", 0, ports.descriptorCloses)
        assertEquals("Las banderas quedan intactas", INITIAL_FLAGS, ports.flags)
        assertFalse("El rechazo no puede dejar tomado el permiso de proceso", NonSeekablePollingLease.isHeld)

        // La política no consume el permiso: una plataforma admitida abre inmediatamente después.
        val supported = FakeDescriptorPorts()
        val stream = open(supported, platformApiLevel = 30)
        try {
            assertTrue("API 30+ conserva la apertura con permiso", NonSeekablePollingLease.isHeld)
            assertEquals(
                "La apertura admitida sigue el orden permiso → F_GETFL → F_SETFL",
                listOf("F_GETFL", "F_SETFL:${INITIAL_FLAGS or NON_BLOCKING_FLAG}"),
                supported.events,
            )
        } finally {
            stream.close()
        }
        assertEquals("El cierre restaura el estado inicial", INITIAL_FLAGS, supported.flags)
        assertFalse("El permiso se libera al cerrar", NonSeekablePollingLease.isHeld)
    }

    @Test
    fun `el rechazo de plataforma tiene su propio codigo de fallo de fuente`() {
        val rejection = sourceStreamRejection(
            SourceNonSeekableUnsupported("La lectura no bloqueante requiere API 30 o superior."),
        )
        assertEquals(SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED, rejection.failure)
        assertTrue("El detalle no puede quedar vacío", rejection.detail.isNotBlank())
    }

    @Test
    fun `los demas fallos de apertura conservan su codigo y no filtran el mensaje tecnico`() {
        assertEquals(SourceOpenFailure.CANCELLED, sourceStreamRejection(SourceReadCancelled()).failure)
        assertEquals(SourceOpenFailure.SOURCE_BUSY, sourceStreamRejection(SourceDescriptorBusy()).failure)

        val ambiguous = sourceStreamRejection(IOException("errno 5 crudo del proveedor"))
        assertEquals(
            "Una E/S no tipada sigue siendo ambigua y no se atribuye al contenido ni al formato",
            SourceOpenFailure.SOURCE_UNAVAILABLE,
            ambiguous.failure,
        )
        assertFalse(
            "El detalle genérico no debe reenviar el mensaje crudo de la excepción: ${ambiguous.detail}",
            ambiguous.detail.contains("errno 5"),
        )
    }

    private companion object {
        /** `O_NONBLOCK` en Linux/Android; en JVM se inyecta para no depender de `OsConstants`. */
        const val NON_BLOCKING_FLAG = 0x800

        /** Bits arbitrarios distintos de `O_NONBLOCK` para comprobar que no se escriben banderas. */
        const val INITIAL_FLAGS = 0x8000
    }
}
