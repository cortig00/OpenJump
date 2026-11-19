package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameId

/**
 * Política **pura** de elegibilidad del workspace de ángulos vídeo-primero.
 *
 * Decide si la entrada del editor debe presentarse como workspace nuevo a partir de dos datos que
 * manda la autoridad de la **ruta**: el gate V2 y la identidad de análisis que transporta la
 * navegación. No es un estado ni un framework: no mira el `ViewModel`, ni los ids que la instancia
 * abrió o guardó, ni el número de marcas. Por eso se puede probar sola y el cableado posterior no
 * necesita introducir ninguna regla nueva.
 *
 * - [gateEnabled]: `true` solo con el gate de análisis V2 activo (en los APK de prueba,
 *   `.anglesv2test`). Con el gate apagado el editor se comporta exactamente como el flujo legacy,
 *   sea cual sea la ruta.
 * - [routeAnalysisId]: identidad de análisis de la ruta. `0` es el valor que la navegación del
 *   editor (`anglesEditorRoute`) usa para un análisis **nuevo**; un id positivo es una reapertura
 *   legacy (nunca workspace) y un id negativo es inválido, así que tampoco es elegible.
 *
 * En este slice solo se publica y se prueba: la UI lo consume en el siguiente, junto con el
 * workspace funcional y la guarda de cierre, para no dejar una rama habilitada a medias.
 */
internal fun angleWorkspaceEligible(gateEnabled: Boolean, routeAnalysisId: Long): Boolean =
    gateEnabled && routeAnalysisId == 0L

/**
 * Fase de interacción del lienzo para el editor **actual** y la intención explícita de añadir.
 *
 * Función pura y única autoridad de la fase: la UI la vuelve a evaluar con el editor que el
 * `ViewModel` tiene en ese instante dentro del callback del toque, no con la copia que se compuso.
 * Así el cuarto toque de una terna, que llega antes de recomponer, ve el editor ya completo y no
 * coloca otro A.
 *
 * Reglas:
 * - sin editor (fotograma aún sin imagen o sin selección) se coloca: el primer A es posible;
 * - con A/B pendientes en el borrador se sigue colocando y el borrador **no** se descarta;
 * - con ángulos completos y sin intención explícita se edita (seleccionar/arrastrar, nunca colocar);
 * - «Otro ángulo» es una intención explícita y vuelve a colocar sin cancelar el borrador pendiente.
 */
internal fun angleWorkspaceInteractionMode(
    editor: AngleEditorState?,
    addingAngle: Boolean,
): AngleOverlayMode = when {
    addingAngle -> AngleOverlayMode.PLACEMENT
    editor == null -> AngleOverlayMode.PLACEMENT
    editor.draft.points.isNotEmpty() -> AngleOverlayMode.PLACEMENT
    editor.angles.isEmpty() -> AngleOverlayMode.PLACEMENT
    else -> AngleOverlayMode.EDIT
}

/** `true` solo si un toque que llega AHORA con [editor] debe colocar el siguiente punto A/B. */
internal fun angleWorkspaceAcceptsPlacement(
    editor: AngleEditorState?,
    addingAngle: Boolean,
): Boolean = angleWorkspaceInteractionMode(editor, addingAngle) == AngleOverlayMode.PLACEMENT

/**
 * Identidad del fotograma concreto sobre el que se está dibujando: su id **y** la sesión de render
 * que lo contiene ([sourceIdentity] + [generation]).
 *
 * Dos sesiones distintas pueden reutilizar el mismo id de fotograma (otra copia del vídeo, otro
 * render), así que el id solo no basta para decidir si una acción sigue apuntando al mismo sitio.
 */
internal data class AngleFrameBinding(
    val frameId: AngleFrameId,
    val sourceIdentity: String,
    val generation: Long,
)

/**
 * Binding del fotograma que la composición está **pintando** ahora mismo, o `null` si no hay una
 * imagen prestada y abierta de ese mismo fotograma.
 *
 * Es una función pura: no mira el `ViewModel` ni adquiere nada. El lease dibujado presta su
 * [leaseFrameId] y su [leaseOpen]; si el lease no es del fotograma activo o ya está cerrado no hay
 * binding y ninguna acción de geometría podrá colarse por ahí.
 */
internal fun angleRenderedFrameBinding(
    activeFrameId: AngleFrameId?,
    sourceIdentity: String?,
    generation: Long?,
    leaseFrameId: AngleFrameId?,
    leaseOpen: Boolean,
): AngleFrameBinding? =
    if (activeFrameId != null && sourceIdentity != null && generation != null && leaseOpen &&
        leaseFrameId == activeFrameId
    ) {
        AngleFrameBinding(activeFrameId, sourceIdentity, generation)
    } else {
        null
    }

/**
 * Binding **fresco** del mismo fotograma en el estado que la sesión tiene ahora: `null` si ese
 * fotograma ya no existe, no es el activo, su imagen no se puede editar (captura en vuelo, fallida)
 * o la sesión está cerrada.
 *
 * El fotograma activo es el único editable: si la sesión cambió de fotograma, una acción calculada
 * con la geometría del anterior no puede seguir siendo válida aunque su id siga presente.
 */
internal fun angleFreshFrameBinding(
    state: AngleMultiFrameSessionState?,
    frameId: AngleFrameId,
): AngleFrameBinding? {
    if (state == null || state.isClosed) return null
    if (state.activeFrameId != frameId) return null
    val item = state.frame(frameId) ?: return null
    if (!item.canEdit) return null
    return AngleFrameBinding(frameId, state.sourceIdentity, state.generation)
}

/**
 * Instantánea **fresca** de la sesión para decidir operaciones: la publicada por el `ViewModel` o,
 * dentro de la **misma** fuente y generación, la que devolvió la última acción aplicada si es más
 * nueva.
 *
 * Reglas (todas puras, sin `ViewModel`):
 * - si lo publicado no está [AngleMultiFrameState.Ready] (sin sesión, abriendo, cerrada o no
 *   disponible) no hay sesión fresca: nunca se resucita una copia local;
 * - una copia local **cerrada** no se devuelve jamás;
 * - una copia local con otra fuente u otra generación es de otra época: se ignora y manda lo
 *   publicado;
 * - dentro de la misma época gana la versión mayor, para que un toque que llega antes de la
 *   réplica del colector no se evalúe contra el estado anterior.
 */
internal fun freshestMultiFrameSession(
    published: AngleMultiFrameState?,
    applied: AngleMultiFrameSessionState?,
): AngleMultiFrameSessionState? {
    val current = (published as? AngleMultiFrameState.Ready)?.state ?: return null
    if (current.isClosed) return null
    if (applied == null || applied.isClosed) return current
    if (applied.sourceIdentity != current.sourceIdentity) return current
    if (applied.generation != current.generation) return current
    return if (applied.version > current.version) applied else current
}

/**
 * Fotograma sobre el que se debe aplicar una acción de geometría, o `null` para **ignorarla**.
 *
 * Nunca retarget: si el binding dibujado no es exactamente el fresco (otro fotograma, otra fuente u
 * otra generación) la acción se descarta. Es lo que impide que un toque calculado con el mapper de A
 * se escriba en B al cambiar de miniatura antes de recomponer.
 */
internal fun angleFrameActionTarget(
    rendered: AngleFrameBinding?,
    fresh: AngleFrameBinding?,
): AngleFrameId? = if (rendered != null && fresh != null && rendered == fresh) fresh.frameId else null
