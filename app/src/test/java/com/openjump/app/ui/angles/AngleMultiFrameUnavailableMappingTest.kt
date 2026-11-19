package com.openjump.app.ui.angles

import com.openjump.app.R
import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.video.angles.SourceOpenFailure
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM del mapeo de motivos de indisponibilidad de la pantalla multi-fotograma.
 *
 * El rechazo por versión de plataforma de un origen no seekable
 * ([SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED]) es una limitación del dispositivo, no un detalle del
 * proveedor: la UI debe presentarlo con un aviso propio que ofrezca una copia local del vídeo y
 * **nunca** con el mensaje genérico que imprime `reason.detail` (texto técnico en español: descriptores,
 * pipes, API…). El resto de motivos conserva su mensaje actual.
 *
 * La pantalla es un composable con ViewModel con frontera Android, así que aquí no se compone: se fija
 * la función pura de presentación y que la pantalla la consuma en lugar del detalle técnico.
 */
class AngleMultiFrameUnavailableMappingTest {

    @Test
    fun `el rechazo por version de plataforma usa el recurso especifico`() {
        val reason = AngleMultiFrameUnavailableReason.SourceRejected(
            failure = SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED,
            detail = "detalle técnico que no debe mostrarse",
        )
        assertEquals(
            "El motivo debe presentarse con su propio aviso",
            R.string.angle_multi_frame_unavailable_non_seekable,
            specificUnavailableMessageRes(reason),
        )
    }

    @Test
    fun `los demas motivos conservan su mensaje y no roban el aviso especifico`() {
        val sourceFailures = listOf(
            SourceOpenFailure.BLANK_SOURCE,
            SourceOpenFailure.UNSUPPORTED_SOURCE,
            SourceOpenFailure.PERMISSION_DENIED,
            SourceOpenFailure.SOURCE_UNAVAILABLE,
            SourceOpenFailure.SOURCE_BUSY,
            SourceOpenFailure.SOURCE_TOO_LARGE,
            SourceOpenFailure.STORAGE_UNAVAILABLE,
            SourceOpenFailure.COPY_FAILED,
            SourceOpenFailure.CANCELLED,
            SourceOpenFailure.INVALID_VIDEO,
        )
        for (failure in sourceFailures) {
            assertNull(
                "$failure no puede usar el aviso de versión de plataforma",
                specificUnavailableMessageRes(AngleMultiFrameUnavailableReason.SourceRejected(failure, "detalle")),
            )
        }
        assertNull(specificUnavailableMessageRes(AngleMultiFrameUnavailableReason.NoVideoOpen))
        assertNull(specificUnavailableMessageRes(AngleMultiFrameUnavailableReason.OpenerUnavailable))
        assertNull(specificUnavailableMessageRes(AngleMultiFrameUnavailableReason.OpenFailed("IllegalStateException")))
    }

    @Test
    fun `el aviso especifico ofrece una copia local y no filtra vocabulario tecnico`() {
        val message = catalogs.catalog(BASE_LOCALE).onlyValue("angle_multi_frame_unavailable_non_seekable")
        for (term in listOf("descriptor", "API", "pipe", "socket", "fcntl", "O_NONBLOCK", "seekable", "errno")) {
            assertFalse(
                "El aviso no puede filtrar el término técnico '$term': $message",
                message.contains(term, ignoreCase = true),
            )
        }
        assertTrue(
            "El aviso debe ofrecer la alternativa de copia local: $message",
            message.contains("copia local", ignoreCase = true),
        )
    }

    @Test
    fun `la pantalla consume el mapeo especifico antes que el detalle tecnico`() {
        val source = readEditorSource()
        assertTrue(
            "La pantalla debe resolver el mensaje con specificUnavailableMessageRes(reason)",
            source.contains("specificUnavailableMessageRes(reason)"),
        )
        assertTrue(
            "La pantalla debe usar el recurso específico para el rechazo de versión de plataforma",
            source.contains("R.string.angle_multi_frame_unavailable_non_seekable"),
        )
    }

    private fun readEditorSource(): String {
        val relative = "app/src/main/java/com/openjump/app/ui/angles/AngleMultiFrameEditor.kt"
        val direct = relative.removePrefix("app/")
        val tried = mutableListOf<File>()
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, relative), File(directory, direct))) {
                tried += candidate
                if (candidate.isFile) return candidate.readText()
            }
            directory = directory.parentFile
        }
        throw AssertionError(
            "No se encuentra '$relative'. Directorio de trabajo: ${File(".").absolutePath}. " +
                "Rutas probadas: ${tried.joinToString { it.absolutePath }}",
        )
    }

    private companion object {
        const val BASE_LOCALE = "values"

        val catalogs: ResourceCatalogs by lazy {
            ResourceCatalogs.load(
                mapOf(BASE_LOCALE to "app/src/main/res/values/strings.xml"),
            )
        }
    }
}
