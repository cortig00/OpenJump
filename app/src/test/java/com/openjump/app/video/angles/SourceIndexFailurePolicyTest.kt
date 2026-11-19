package com.openjump.app.video.angles

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la política que decide si un fallo del indexador se atribuye al contenido o no.
 *
 * Regla bajo prueba (contrato verificable, ver [SourceIndexFailurePolicy]):
 *
 * - `INVALID_VIDEO` sólo con un veredicto estructural del extractor (`IllegalArgumentException` que
 *   declara la ausencia de track de vídeo), nunca por una `IOException` ni por una cabecera.
 * - Una `IOException` del extractor es **ambigua**: Android no permite distinguir contenido no
 *   soportado o inválido de un fallo del extractor o de E/S (`initMediaExtractor: failed to create
 *   MediaExtractor` es el caso observado en el AVD el 2026-11-10). Se queda en `SOURCE_UNAVAILABLE`.
 * - Los límites del índice y las discrepancias de longitud sellada conservan su tipado propio.
 *
 * Estas pruebas reemplazan a las que fijaban el diagnóstico anterior ("cabecera no reconocida ⇒
 * vídeo inválido"): ese diagnóstico no era demostrable porque la lista de firmas era incompleta y el
 * fallo del extractor no habla de los bytes. La cobertura útil se conserva: un fallo ambiguo nunca se
 * presenta como contenido inválido ni como tamaño de fuente.
 */
class SourceIndexFailurePolicyTest {

    @Test
    fun `una ioexception del extractor es ambigua y no acusa al contenido`() {
        // Caso observado en el AVD: `initMediaExtractor: failed to create MediaExtractor` con 4096
        // bytes 0x5A. La cabecera (reconocida o no) ya no participa en el veredicto.
        val error = IOException("Failed to instantiate extractor")
        val verdict = SourceIndexFailurePolicy.verdict(error)
        assertEquals(
            "Un IOException del extractor no prueba nada sobre el formato: ${verdict.detail}",
            SourceOpenFailure.SOURCE_UNAVAILABLE,
            verdict.failure,
        )
        assertNotEquals(
            "Un fallo ambiguo no puede presentarse como vídeo inválido: ${verdict.detail}",
            SourceOpenFailure.INVALID_VIDEO,
            verdict.failure,
        )
        assertTrue(
            "El detalle debe citar el error real: ${verdict.detail}",
            verdict.detail.contains("IOException") && verdict.detail.contains("Failed to instantiate extractor"),
        )
        assertTrue(
            "El detalle debe declarar la ambigüedad: ${verdict.detail}",
            verdict.detail.contains("no permite distinguir"),
        )
        assertTrue(
            "El detalle debe declarar que no se atribuye al contenido: ${verdict.detail}",
            verdict.detail.contains("no se atribuye al contenido"),
        )
        assertFalse(
            "El detalle no puede culpar al formato: ${verdict.detail}",
            verdict.detail.contains("se clasifica como vídeo inválido"),
        )
    }

    @Test
    fun `una io de lectura del extractor tampoco se convierte en contenido invalido`() {
        // Mismo tipo con otra causa (stream truncado). Aunque la cabecera de los bytes fuese
        // reconocible o desconocida, la política no recibe cabecera: no hay prueba local de formato.
        val verdict = SourceIndexFailurePolicy.verdict(EOFException("end of stream"))
        assertEquals(SourceOpenFailure.SOURCE_UNAVAILABLE, verdict.failure)
        assertTrue("Debe citar EOFException: ${verdict.detail}", verdict.detail.contains("EOFException"))
        assertTrue(
            "Debe declarar la ambigüedad: ${verdict.detail}",
            verdict.detail.contains("no permite distinguir"),
        )
    }

    @Test
    fun `un veredicto estructural del extractor se atribuye al contenido`() {
        val noVideoTrack = SourceIndexFailurePolicy.verdict(
            IllegalArgumentException("El archivo no contiene un track de vídeo."),
        )
        assertEquals(SourceOpenFailure.INVALID_VIDEO, noVideoTrack.failure)
        assertTrue(
            "Debe conservar el mensaje del extractor: ${noVideoTrack.detail}",
            noVideoTrack.detail.contains("no contiene un track de vídeo"),
        )
        // 0 tracks (ftyp truncado) usa el mismo mensaje estructural: no es un tamaño de fuente.
        val zeroTracks = SourceIndexFailurePolicy.verdict(
            IllegalArgumentException("El vídeo supera el límite de tracks procesables."),
        )
        assertEquals(
            "Un límite de tracks no es un tamaño de fuente",
            SourceOpenFailure.INVALID_VIDEO,
            zeroTracks.failure,
        )
        assertTrue(
            "El detalle debe hablar de track de vídeo utilizable: ${zeroTracks.detail}",
            zeroTracks.detail.contains("track de vídeo utilizable"),
        )
    }

    @Test
    fun `un rechazo por limites del indice sigue siendo source too large`() {
        val verdict = SourceIndexFailurePolicy.verdict(
            IllegalArgumentException("El vídeo supera el límite de frames procesables."),
        )
        assertEquals(SourceOpenFailure.SOURCE_TOO_LARGE, verdict.failure)
    }

    @Test
    fun `un fallo sin mensaje estructural ni limite es ambiguo`() {
        val runtime = SourceIndexFailurePolicy.verdict(IllegalStateException("estado raro"))
        assertEquals(
            "Un RuntimeException genérico no demuestra que el contenido sea inválido",
            SourceOpenFailure.SOURCE_UNAVAILABLE,
            runtime.failure,
        )
        assertTrue("Debe citar la clase: ${runtime.detail}", runtime.detail.contains("IllegalStateException"))

        // Un IllegalArgumentException sin mensaje de track/límite tampoco es un veredicto de formato.
        val bareArgument = SourceIndexFailurePolicy.verdict(IllegalArgumentException("argumento no válido"))
        assertEquals(SourceOpenFailure.SOURCE_UNAVAILABLE, bareArgument.failure)

        val uncategorized = SourceIndexFailurePolicy.verdict(AssertionError("no clasificable"))
        assertEquals(
            "Un fallo que no es estructural no puede atribuirse al contenido",
            SourceOpenFailure.SOURCE_UNAVAILABLE,
            uncategorized.failure,
        )
        assertTrue(
            "Debe declarar que no se atribuye: ${uncategorized.detail}",
            uncategorized.detail.contains("no se atribuye al contenido"),
        )
    }

    @Test
    fun `la integridad del snapshot distingue almacenamiento de contenido`() {
        assertNull("Misma longitud: sin incidencia", SourceIndexFailurePolicy.integrityIssue(2474, 2474))
        val issue = SourceIndexFailurePolicy.integrityIssue(expectedLengthBytes = 2474, actualLengthBytes = 100)
        assertNotNull("Una longitud distinta debe reportarse", issue)
        assertTrue("El detalle debe citar ambas longitudes: $issue", issue!!.contains("2474") && issue.contains("100"))
        assertTrue("Y declarar que es almacenamiento: $issue", issue.contains("almacenamiento"))
    }
}
