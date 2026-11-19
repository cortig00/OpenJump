package com.openjump.app.ui.history

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM de la navegación de las filas de Ángulos en HISTORY.
 *
 * La pantalla y el `NavHost` son Compose, así que este slice no los instancia. Lo que sí se puede
 * verificar —y es justo lo que cambió— es el contrato de las fuentes:
 *
 * 1. la fila de un análisis navega SIEMPRE: no queda condicionada por la validez del vídeo
 *    (`canReopen` dejó de existir y de bloquear el click);
 * 2. el destino es el detalle guardado, que consulta la raíz persistida sin abrir vídeo;
 * 3. ese detalle es INMUTABLE: no edita, no reabre el vídeo y por tanto no navega al editor (la
 *    ruta de recuperación sigue existiendo en el grafo, pero el detalle no la usa).
 *
 * No valida composición ni aspecto: eso requiere instrumentación y no se ejecuta aquí.
 */
class HistoryAngleNavigationTest {

    private val historyScreen: String by lazy { read("app/src/main/java/com/openjump/app/ui/history/HistoryScreen.kt") }
    private val measurementHistory: String by lazy { read("app/src/main/java/com/openjump/app/ui/history/MeasurementHistory.kt") }
    private val appNav: String by lazy { read("app/src/main/java/com/openjump/app/ui/AppNav.kt") }

    @Test
    fun `every angle row navigates regardless of video availability`() {
        assertTrue(
            "La fila de ángulos debe entregar la entrada al callback sin condición de vídeo",
            historyScreen.contains("onAngleAnalysisSelected(item.entry)"),
        )
        assertFalse(
            "El click ya no puede depender de canReopen",
            historyScreen.contains("canReopen"),
        )
        assertFalse(
            "La entrada de historial no conserva un predicado de reapertura que bloquee el listado",
            measurementHistory.contains("canReopen"),
        )
    }

    @Test
    fun `history opens the saved detail instead of the editor`() {
        assertTrue(
            "La ruta del detalle guardado debe existir en el grafo",
            appNav.contains("composable(ANGLES_SAVED_DETAIL_ROUTE)"),
        )
        assertTrue(
            "La fila de ángulos navega al detalle guardado",
            appNav.contains("anglesSavedDetailRoute(entry.id)"),
        )
        assertTrue(
            "El detalle compone la pantalla de solo lectura con el ViewModel existente",
            appNav.contains("AngleSavedDetailScreen("),
        )
        assertTrue(
            "El detalle se abre con su ViewModel de producción",
            appNav.contains("AngleSavedDetailViewModel.factory()"),
        )
    }

    @Test
    fun `the saved detail is read only and never reopens the editor`() {
        assertTrue(
            "El detalle V2 debe existir para cerrar el bloque del detalle legacy",
            appNav.contains("composable(ANGLES_V2_SAVED_DETAIL_ROUTE) {"),
        )
        // Los bloques se extraen con marcadores obligatorios: si un marcador desaparece, el guard
        // falla en vez de degradar a "todo el fuente" y dar por bueno un detalle que ya no es el suyo.
        val legacyDetail = routeBlock(
            start = "composable(ANGLES_SAVED_DETAIL_ROUTE) {",
            end = "composable(ANGLES_V2_SAVED_DETAIL_ROUTE) {",
        )

        assertTrue(
            "El detalle compone la pantalla de solo lectura",
            legacyDetail.contains("AngleSavedDetailScreen("),
        )
        assertTrue(
            "El detalle se abre con su ViewModel de producción",
            legacyDetail.contains("AngleSavedDetailViewModel.factory()"),
        )
        // Un análisis guardado se consulta: editar exigiría reabrir el vídeo original, que es
        // justo lo que la decisión de detalle inmutable descarta.
        assertFalse(
            "El detalle inmutable no navega a la ruta de recuperación del editor",
            legacyDetail.contains("anglesSavedAnalysisRoute("),
        )
        assertFalse(
            "El detalle inmutable no navega al editor",
            legacyDetail.contains("anglesEditorRoute("),
        )
        assertFalse(
            "El detalle inmutable no compone el editor",
            legacyDetail.contains("AngleEditorScreen("),
        )
        assertFalse(
            "El detalle inmutable no abre el vídeo original con el editor",
            legacyDetail.contains("AngleEditorRequest("),
        )
        assertFalse(
            "El detalle inmutable no construye el ViewModel del editor",
            legacyDetail.contains("AngleEditorViewModel.factory("),
        )

        val v2Detail = routeBlock(
            start = "composable(ANGLES_V2_SAVED_DETAIL_ROUTE) {",
            end = "composable(HISTORY_ROUTE) {",
        )

        assertTrue(
            "El detalle V2 compone la pantalla de solo consulta",
            v2Detail.contains("AngleV2SavedDetailScreen("),
        )
        assertTrue(
            "El detalle V2 se abre con su ViewModel de producción",
            v2Detail.contains("AngleV2SavedDetailViewModel.factory()"),
        )
        assertFalse(
            "El detalle V2 no reabre el editor por la ruta de recuperación",
            v2Detail.contains("anglesSavedAnalysisRoute("),
        )
        assertFalse(
            "El detalle V2 no navega al editor",
            v2Detail.contains("anglesEditorRoute("),
        )
        assertFalse(
            "El detalle V2 no compone el editor",
            v2Detail.contains("AngleEditorScreen("),
        )
        assertFalse(
            "El detalle V2 no abre el vídeo original con el editor",
            v2Detail.contains("AngleEditorRequest("),
        )
    }

    /**
     * Bloque de [appNav] entre dos marcadores EXIGIDOS: si falta el de inicio o el de fin, el test
     * falla. `substringAfter`/`substringBefore` solos devuelven la cadena completa cuando el marcador
     * no existe y convertirían una regresión en un falso positivo.
     */
    private fun routeBlock(start: String, end: String): String {
        val from = appNav.indexOf(start)
        assertTrue("Falta el marcador de inicio '$start' en AppNav.kt", from >= 0)
        val to = appNav.indexOf(end, from + start.length)
        assertTrue("Falta el marcador de fin '$end' después de '$start' en AppNav.kt", to > from)
        return appNav.substring(from, to)
    }

    private fun read(path: String): String {
        val relative = path.removePrefix("app/")
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, path), File(directory, relative))) {
                if (candidate.isFile) return candidate.readText()
            }
            directory = directory.parentFile
        }
        throw AssertionError("No se encuentra '$path'. Directorio: ${File(".").absolutePath}")
    }
}
