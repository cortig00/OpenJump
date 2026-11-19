package com.openjump.app.ui.angles

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Pestañas del detalle V2 de un análisis de ángulos guardado.
 *
 * El orden de declaración es el de presentación (Análisis primero, como en el resultado del encoder)
 * y NO es identidad persistida: [AngleV2SavedDetailUiState] guarda la pestaña por valor de enumerado
 * y su `ordinal` sólo se usa para el `ScrollableTabRow`, nunca para identificar un fotograma.
 *
 * [FRAMES] es condicional: con un único fotograma no aporta nada (no hay nada que elegir) y no se
 * ofrece; [reconcile] devuelve a [ANALYSIS] si deja de haber más de uno.
 */
internal enum class AngleV2SavedDetailTab {
    ANALYSIS,
    FRAMES,
    INFO,
}

/**
 * Estado de presentación (efímero) del detalle V2: pestaña visible y fotograma seleccionado.
 *
 * Es estado de UI puro: no contiene modelos persistidos, ni bitmaps, ni rutas, ni peticiones de
 * imagen, y no escribe nada. Vive por encima de las tres vistas para que la selección se conserve al
 * cambiar de pestaña (Análisis ↔ Fotogramas ↔ Información) y sobreviva a la rotación mediante
 * [StateSaver].
 *
 * La selección se identifica por **`frameId`** (identidad estable de la fila dentro de la raíz), no
 * por índice de lista ni por ordinal visible: reconciliar contra contenido nuevo no puede
 * seleccionar por accidente el fotograma de otra raíz o de otra posición.
 */
internal data class AngleV2SavedDetailUiState(
    val tab: AngleV2SavedDetailTab = AngleV2SavedDetailTab.ANALYSIS,
    val selectedFrameId: Long? = null,
) {

    /** Cambia de pestaña respetando las pestañas que existen para este contenido. */
    fun show(tab: AngleV2SavedDetailTab, frames: List<AngleV2SavedDetailFrame>): AngleV2SavedDetailUiState {
        if (tab == AngleV2SavedDetailTab.FRAMES && !hasFramesTab(frames)) return copy(tab = AngleV2SavedDetailTab.ANALYSIS)
        return copy(tab = tab)
    }

    /**
     * Selecciona un fotograma por su identidad estable: la acción de la galería entra en Análisis.
     *
     * Un `frameId` que no pertenece al contenido actual no cambia nada (no se inventa una selección
     * ni se acepta el fotograma de otra raíz).
     */
    fun select(frames: List<AngleV2SavedDetailFrame>, frameId: Long): AngleV2SavedDetailUiState =
        if (frames.any { it.frameId == frameId }) {
            copy(tab = AngleV2SavedDetailTab.ANALYSIS, selectedFrameId = frameId)
        } else {
            this
        }

    /**
     * Ajusta el estado al contenido vigente: raíz recargada, fotograma desaparecido, galería que
     * deja de existir o lista vacía.
     *
     * - Sin fotogramas no hay selección. La pestaña visible se CONSERVA: Información sigue siendo
     *   legible (no depende de los fotogramas) y sólo «Fotogramas» cae a Análisis, porque esa
     *   pestaña no existe sin galería. Forzar Análisis aquí dejaba una pestaña visible muerta.
     * - Si el seleccionado sigue existiendo, se conserva tal cual (misma pestaña incluida).
     * - Si desapareció, se selecciona el primero del contenido vigente (nunca un homónimo de otra
     *   raíz: el estado se reconcilia sólo contra el contenido que se está pintando).
     * - Con un único fotograma la galería no se ofrece y se vuelve a Análisis.
     */
    fun reconcile(frames: List<AngleV2SavedDetailFrame>): AngleV2SavedDetailUiState {
        if (frames.isEmpty()) {
            return AngleV2SavedDetailUiState(
                tab = if (tab == AngleV2SavedDetailTab.FRAMES) {
                    AngleV2SavedDetailTab.ANALYSIS
                } else {
                    tab
                },
                selectedFrameId = null,
            )
        }
        val stillPresent = selectedFrameId != null && frames.any { it.frameId == selectedFrameId }
        val resolvedId = if (stillPresent) selectedFrameId else frames.first().frameId
        val resolvedTab = if (tab == AngleV2SavedDetailTab.FRAMES && !hasFramesTab(frames)) {
            AngleV2SavedDetailTab.ANALYSIS
        } else {
            tab
        }
        return AngleV2SavedDetailUiState(tab = resolvedTab, selectedFrameId = resolvedId)
    }

    /** Fotograma de la selección ya reconciliada; `null` sólo con contenido vacío. */
    fun selectedFrame(frames: List<AngleV2SavedDetailFrame>): AngleV2SavedDetailFrame? {
        if (frames.isEmpty()) return null
        return frames.firstOrNull { it.frameId == selectedFrameId } ?: frames.first()
    }

    /**
     * Pestañas ofrecidas para [frames], en orden de presentación y por valor de enumerado.
     *
     * Con un único fotograma no hay galería: «Fotogramas» desaparece en vez de mostrar una lista de
     * un elemento. La lista es determinista y no depende de posiciones de una lista filtrada.
     */
    fun tabs(frames: List<AngleV2SavedDetailFrame>): List<AngleV2SavedDetailTab> =
        if (hasFramesTab(frames)) {
            AngleV2SavedDetailTab.entries.toList()
        } else {
            AngleV2SavedDetailTab.entries.filterNot { it == AngleV2SavedDetailTab.FRAMES }
        }

    companion object {

        /** La galería sólo existe con más de un fotograma guardado. */
        fun hasFramesTab(frames: List<AngleV2SavedDetailFrame>): Boolean = frames.size > 1

        /**
         * «Compartir todas las imágenes» sólo se ofrece con más de un fotograma: con uno solo sería
         * una segunda acción que comparte EXACTAMENTE la misma imagen que «Compartir imagen».
         */
        fun shareAllAvailable(frames: List<AngleV2SavedDetailFrame>): Boolean = frames.size > 1

        /** Estado inicial: Análisis con el primer fotograma seleccionado, si lo hay. */
        fun initial(frames: List<AngleV2SavedDetailFrame>): AngleV2SavedDetailUiState =
            AngleV2SavedDetailUiState(
                tab = AngleV2SavedDetailTab.ANALYSIS,
                selectedFrameId = frames.firstOrNull()?.frameId,
            )

        /**
         * Guarda la pestaña por valor de enumerado (no por ordinal de una lista filtrada) y la
         * selección por `frameId`, nunca por índice.
         */
        val StateSaver: Saver<AngleV2SavedDetailUiState, Any> =
            listSaver<AngleV2SavedDetailUiState, Long>(
                save = { state ->
                    listOf(
                        state.tab.ordinal.toLong(),
                        state.selectedFrameId ?: NO_SELECTION,
                    )
                },
                restore = { saved ->
                    AngleV2SavedDetailUiState(
                        tab = AngleV2SavedDetailTab.entries.getOrElse(saved.getOrElse(0) { 0L }.toInt()) {
                            AngleV2SavedDetailTab.ANALYSIS
                        },
                        selectedFrameId = saved.getOrElse(1) { NO_SELECTION }.takeIf { it != NO_SELECTION },
                    )
                },
            )

        /** Centinela de «sin selección» en el `Saver` (los `frameId` de Room son positivos). */
        private const val NO_SELECTION: Long = -1L
    }
}
