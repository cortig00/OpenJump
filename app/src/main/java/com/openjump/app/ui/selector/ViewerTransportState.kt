package com.openjump.app.ui.selector

/**
 * Lectura agregada del transporte: el estado de frame que ya conoce la UI, la carga y el
 * error de presentación. Se calcula bajo demanda a partir de los flujos del transporte; no
 * introduce un segundo estado paralelo.
 */
data class ViewerTransportState(
    val display: FrameDisplayState = FrameDisplayState(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    /**
     * Único frame que un marcador puede usar: el confirmado por
     * [FrameDisplayState.canMark]. No afirma que ese frame esté físicamente presentado
     * en la superficie, solo que Media3 confirmó su PTS y coincide con lo solicitado en pausa.
     */
    val confirmedFrameIndex: Int?
        get() = if (display.canMark) display.renderedIndex else null
}

/**
 * Modo de una petición disparada por la sesión: durante un scrub activo el frame externo
 * se sigue tratando como scrub, salvo que ya se haya pedido el cierre del scrub.
 */
internal fun viewerRequestMode(
    scrubbingActive: Boolean,
    scrubEndRequested: Boolean,
): ViewerTransportMode = if (scrubbingActive && !scrubEndRequested) {
    ViewerTransportMode.SCRUBBING
} else {
    ViewerTransportMode.SEEKING
}

/** La sesión no vuelve a pedir el frame que ya es el objetivo pendiente del transporte. */
internal fun viewerSessionRequestIsRedundant(requestedIndex: Int, current: FrameDisplayState): Boolean =
    requestedIndex == current.requestedIndex

/**
 * Base de un salto relativo: en reproducción se parte del frame ya renderizado (o, si aún
 * no hay ninguno, del frame de sesión); en pausa manda siempre el frame de sesión.
 */
internal fun viewerStepBaseFrame(display: FrameDisplayState, sessionFrameIndex: Int): Int =
    if (display.isPlaying) display.renderedIndex ?: sessionFrameIndex else sessionFrameIndex

/** La UI sigue cargando mientras ningún frame se haya confirmado todavía. */
internal fun viewerLoadingAfterRequest(display: FrameDisplayState): Boolean = display.renderedIndex == null

/**
 * El player ya publicó una salida **y** es el frame que el visor pidió (el frame de apertura).
 *
 * Lo usa la reapertura del editor de ángulos: [com.openjump.app.video.ViewerFrameAlignment] fija el
 * desfase PTS→frame con la PRIMERA salida, así que darla por buena sin comprobar que es el frame
 * pedido confirmaría un ancla desalineada (todos los PTS quedarían corridos y `canMark` no se
 * cumpliría nunca). Un `renderedIndex` cualquiera no basta. No exige `mode == PAUSED`:
 * `FrameDisplayState.rendered` ya asienta en pausa cuando el índice coincide.
 */
internal fun viewerOpeningFrameConfirmed(display: FrameDisplayState): Boolean =
    display.renderedIndex != null && display.renderedIndex == display.requestedIndex

/**
 * Una confirmación de pausa sirve tal cual para reposicionar el visor en una reapertura solo si el
 * frame ya confirmado es exactamente el pedido y el player sigue utilizable sobre la misma fuente.
 *
 * En cualquier otro caso hay que provocar una salida real: `seekTo` a la posición actual es un
 * no-op para ExoPlayer y nunca emitiría un callback que confirme el frame (el caso del frame 0 y de
 * cualquier reapertura sobre el frame que ya está en pantalla), de modo que el visor se quedaría en
 * `SEEKING` con los gestos deshabilitados para siempre. Conservar la confirmación solo es lícito si
 * no hay nada que volver a presentar: mismo frame, pausa confirmada y player vivo.
 */
internal fun viewerReopenKeepsConfirmation(
    display: FrameDisplayState,
    requestedIndex: Int,
    playerUsable: Boolean,
): Boolean = playerUsable && display.canMark && display.requestedIndex == requestedIndex

/** El cierre de scrub queda resuelto cuando el frame renderizado ya es el target pedido. */
internal fun viewerScrubEndSettled(renderedIndex: Int?, targetIndex: Int): Boolean =
    renderedIndex == targetIndex

/** Modo final del scrub: pausa solo si el frame visible es el target; si no, sigue buscando. */
internal fun viewerScrubSettledMode(renderedIndex: Int?, requestedIndex: Int): ViewerTransportMode =
    if (renderedIndex == requestedIndex) ViewerTransportMode.PAUSED else ViewerTransportMode.SEEKING
