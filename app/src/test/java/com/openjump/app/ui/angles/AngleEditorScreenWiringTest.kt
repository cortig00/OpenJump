package com.openjump.app.ui.angles

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM del cableado de [AngleEditorScreen] con [AngleEditorViewModel].
 *
 * La pantalla es un composable y el ViewModel tiene frontera Android (ExoPlayer, Context), así que
 * este slice no puede instanciarlos en una prueba JVM. Lo que sí se puede verificar —y es justo lo
 * que cambió— es el contrato de la fuente:
 *
 * 1. la geometría de presentación se consume de [AngleEditorViewModel.videoGeometry] y ya no se
 *    aproxima leyendo `Tracks`/`VideoSize` del player (esa derivación se eliminó de la pantalla);
 * 2. el transporte prev/play/next se conecta por defecto a `stepFrames(-1)`, `stepFrames(1)` y
 *    `togglePlayback()` del ViewModel, es decir al player existente, sin player paralelo;
 * 3. el timeline por PTS no se simula: no hay un `SportsTimeline`/`Slider` inventado en la pantalla
 *    mientras el ViewModel no exponga la lista de PTS;
 * 4. la acción semántica que abre el panel accesible pide la posición de la lista sin forzar un
 *    pase de layout síncrono (ver el comentario del test correspondiente).
 *
 * No valida composición ni aspecto: eso requiere instrumentación y no se ejecuta aquí.
 */
class AngleEditorScreenWiringTest {

    private val source: String by lazy { readSource("AngleEditorScreen.kt") }

    @Test
    fun `la geometria se consume del viewmodel y no se aproxima desde el player`() {
        assertTrue(
            "AngleEditorScreen debe recoger viewModel.videoGeometry",
            source.contains("viewModel.videoGeometry.collectAsStateWithLifecycle()"),
        )
        assertFalse(
            "AngleEditorScreen no debe volver a leer tracks del player",
            source.contains("currentTracks"),
        )
        assertFalse(
            "AngleEditorScreen no debe volver a filtrar tracks de vídeo de Media3",
            source.contains("TRACK_TYPE_VIDEO"),
        )
        assertFalse(
            "AngleEditorScreen no debe inferir dimensiones con VideoSize",
            source.contains("VideoSize"),
        )
        assertFalse(
            "La derivación de geometría desde Tracks debe seguir eliminada",
            source.contains("rememberPlayerPresentationGeometry"),
        )
    }

    @Test
    fun `el transporte por defecto delega en el viewmodel existente`() {
        assertTrue(
            "El paso anterior debe delegar en el ViewModel",
            source.contains("viewModel.stepFrames(-1)"),
        )
        assertTrue(
            "El paso siguiente debe delegar en el ViewModel",
            source.contains("viewModel.stepFrames(1)"),
        )
        assertTrue(
            "Play/pausa debe delegar en el ViewModel",
            source.contains("viewModel.togglePlayback()"),
        )
        assertTrue(
            "Las acciones del VM deben ser el transporte por defecto de la pantalla",
            source.contains("transport ?: transportFromViewModel"),
        )
    }

    @Test
    fun `el timeline del workspace usa el mapper PTS real del viewmodel y no crea player`() {
        val workspace = readSource("AngleEditorWorkspace.kt")
        assertTrue(
            "El workspace debe consumir el mapper PTS real del ViewModel",
            workspace.contains("viewModel.timelineMapper.collectAsStateWithLifecycle()"),
        )
        assertTrue(
            "El timeline debe ser el componente existente sobre ese mapper",
            workspace.contains("SportsTimeline(") && workspace.contains("mapper = timeline"),
        )
        assertTrue(
            "El scrub debe delegar en el ViewModel existente, no en un reproductor paralelo",
            workspace.contains("viewModel.beginScrubbing()") &&
                workspace.contains("viewModel.scrubToFrame(index)") &&
                workspace.contains("viewModel.endScrubbing()"),
        )
        assertFalse(
            "El workspace no debe inventar un slider de timeline",
            workspace.contains("Slider("),
        )
        assertFalse(
            "El workspace no debe crear otro ExoPlayer",
            workspace.contains("ExoPlayer.Builder"),
        )
        assertFalse(
            "Sin capa de anotación sobre vídeo vivo: las marcas solo van en la foto congelada",
            workspace.contains("AngleOverlay("),
        )
        assertFalse(
            "El editor legacy no debe montar un timeline propio mientras no cambia su composición",
            source.contains("SportsTimeline("),
        )
    }

    @Test
    fun `el dialogo de renombrar valida con el contrato compartido y no trunca`() {
        assertTrue(
            "El diálogo debe validar el texto con el mismo contrato que la persistencia",
            source.contains("validateAngleRename(name)"),
        )
        assertTrue(
            "El diálogo debe mostrar el error localizado de nombre demasiado largo",
            source.contains("R.string.angle_rename_too_long_error"),
        )
        assertFalse(
            "El nombre no debe recortarse en silencio (take/drop): se rechaza con error visible",
            source.contains("name.take("),
        )
    }

    @Test
    fun `la accion accesible pide la posicion del panel sin forzar un pase de layout`() {
        // La acción personalizada de la capa la ejecuta quien la invoca: en la prueba instrumentada
        // `performCustomAccessibilityActionWithLabel` la corre en el hilo de test y el despachador
        // de efectos de la composición es *unconfined*, así que un
        // `scope.launch { listState.scrollToItem(...) }` dentro de la acción termina llamando a
        // `LayoutNode.forceRemeasure` -> `Owner.measureAndLayout` fuera del hilo principal mientras
        // el propio `AndroidComposeView` se está midiendo/dibujando, y el frame revienta con
        // `IllegalArgumentException: performMeasureAndLayout called during measure layout`.
        // `requestScrollToItem` deja la petición y solo escribe estado: el siguiente pase de medida
        // (el que dispara la propia expansión) coloca el panel arriba.
        assertFalse(
            "La acción accesible no debe desplazar con scrollToItem: fuerza measureAndLayout",
            Regex("""(?<![A-Za-z0-9_])scrollToItem\(""").containsMatchIn(source),
        )
        assertTrue(
            "La acción accesible debe pedir la posición del panel sin forzar remeasure",
            source.contains("listState.requestScrollToItem(0)"),
        )
        assertFalse(
            "La acción semántica no debe lanzar corrutinas: se ejecuta en el despachador del que la invoca",
            source.contains("scope.launch"),
        )
    }

    @Test
    fun `la confirmacion no se memoriza solo por la salida del visor`() {
        // La reapertura puede terminar conservando el frame ya confirmado sin emitir otra salida del
        // visor: si la pantalla memoriza la confirmación solo por `displayState`, se queda con el
        // `null` de la reapertura y no hay capa, ni panel accesible, ni forma de colocar C.
        assertTrue(
            "La pantalla debe observar la puerta de gestos del ViewModel",
            source.contains("viewModel.gestureGate.collectAsStateWithLifecycle()"),
        )
        assertTrue(
            "La confirmación debe pasar por la caché de entradas observables",
            source.contains("AngleEditorConfirmationCache()"),
        )
        assertTrue(
            "La caché debe recibir salida del visor, puerta y estado del editor",
            source.contains("confirmationCache.confirmation(displayState, gestureGate, editor)"),
        )
    }

    @Test
    fun `anadir nunca descarta un borrador pendiente`() {
        val addBlock = source.substringAfter("onAddAngle = {").substringBefore("},")
        assertTrue(
            "«Añadir» debe entrar en colocación",
            addBlock.contains("AngleOverlayMode.PLACEMENT"),
        )
        assertFalse(
            "«Añadir» no puede descartar A/B: recrear la Activity conserva el borrador y resetea el modo",
            addBlock.contains("onCancelDraft"),
        )
        assertTrue(
            "El descarte explícito del borrador debe seguir disponible",
            source.contains("onCancelDraft = actions.onCancelDraft"),
        )
        assertTrue(
            "El modo inicial se deriva del borrador vivo del ViewModel",
            source.contains("initialOverlayMode(editor?.draft?.points?.size ?: 0)"),
        )
    }

    @Test
    fun `el cierre pasa por la politica pura y no se fia de fotogramas ni de Saved`() {
        assertTrue(
            "La decisión de cierre debe salir de la política pura",
            source.contains("angleEditorCloseDecision("),
        )
        assertTrue(
            "La línea base verificada la publica el ViewModel",
            source.contains("viewModel.closeBaseline.value"),
        )
        assertTrue(
            "La guarda de escritura se relee del ViewModel en el instante del toque",
            source.contains("viewModel.isSaveInFlight()"),
        )
        assertFalse(
            "El cierre no puede decidirse solo por fotogramas en memoria ni por la lista de ángulos",
            source.contains("closeConfirm = true"),
        )
        assertTrue(
            "El diálogo nuevo no debe contar solo fotogramas",
            source.contains("AngleEditorCloseDiscardDialog("),
        )
        assertTrue(
            "El atrás también debe bloquearse con el guardado mono-fotograma en vuelo",
            source.contains("saving = multiFrameSaving || monoFrameSaving"),
        )
    }

    private fun readSource(fileName: String): String {
        val relative = "app/src/main/java/com/openjump/app/ui/angles/$fileName"
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
