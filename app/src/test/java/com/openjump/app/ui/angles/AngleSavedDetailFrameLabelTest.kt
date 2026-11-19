package com.openjump.app.ui.angles

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM de la etiqueta de fotograma del detalle de un análisis guardado.
 *
 * Evidencia funcional que motiva el slice: con `frameIndex = 10` persistido, el detalle mostraba
 * "Frame 10" (índice interno de base cero) mientras el editor mostraba "Analysis frame: 11" (base
 * uno) para el mismo análisis. La convención de la UI es base uno (editor, detalle de resultados,
 * selector de fotogramas), así que la conversión pertenece a la presentación.
 *
 * El detalle es un composable con ViewModel con frontera Android (Room), por lo que aquí no se
 * compone: se verifica la conversión de presentación y que la pantalla la consuma en vez de imprimir
 * el índice interno.
 */
class AngleSavedDetailFrameLabelTest {

    @Test
    fun `el detalle numera el fotograma en base uno como el resto de la interfaz`() {
        assertEquals(1, angleSavedFrameDisplayNumber(0))
        assertEquals(11, angleSavedFrameDisplayNumber(10))
        assertEquals(6, angleSavedFrameDisplayNumber(5))
    }

    @Test
    fun `la pantalla usa la conversion y no el indice interno del analisis`() {
        val source = readScreen()
        assertTrue(
            "El detalle debe etiquetar con angleSavedFrameDisplayNumber(content.frameIndex)",
            source.contains("angleSavedFrameDisplayNumber(content.frameIndex)"),
        )
        assertFalse(
            "El detalle no debe imprimir el frameIndex persistido sin convertir",
            source.contains("R.string.angle_saved_frame, content.frameIndex)"),
        )
    }

    private fun readScreen(): String {
        val relative = "app/src/main/java/com/openjump/app/ui/angles/AngleSavedDetailScreen.kt"
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
}
