package com.openjump.app.ui.selector

/**
 * Estado que separa el frame solicitado del PTS confirmado por Media3 para render.
 * Un marcador solo puede guardarse en pausa y cuando ambos índices coinciden.
 */
enum class ViewerTransportMode { PAUSED, PLAYING, SEEKING, SCRUBBING }

data class FrameDisplayState(
    val requestedIndex: Int = 0,
    val renderedIndex: Int? = null,
    val renderedPtsUs: Long? = null,
    val mode: ViewerTransportMode = ViewerTransportMode.SEEKING,
) {
    val isPlaying: Boolean get() = mode == ViewerTransportMode.PLAYING
    val isSeeking: Boolean
        get() = mode == ViewerTransportMode.SEEKING || mode == ViewerTransportMode.SCRUBBING ||
            renderedIndex != requestedIndex
    val canMark: Boolean
        get() = mode == ViewerTransportMode.PAUSED &&
            renderedIndex != null && renderedPtsUs != null && renderedIndex == requestedIndex

    fun request(
        index: Int,
        mode: ViewerTransportMode = ViewerTransportMode.SEEKING,
    ): FrameDisplayState = copy(requestedIndex = index, mode = mode)

    fun rendered(index: Int, ptsUs: Long): FrameDisplayState {
        val settledMode = when {
            mode == ViewerTransportMode.PLAYING -> ViewerTransportMode.PLAYING
            mode == ViewerTransportMode.SCRUBBING -> ViewerTransportMode.SCRUBBING
            index == requestedIndex -> ViewerTransportMode.PAUSED
            else -> mode
        }
        return copy(
            requestedIndex = if (mode == ViewerTransportMode.PLAYING) index else requestedIndex,
            renderedIndex = index,
            renderedPtsUs = ptsUs,
            mode = settledMode,
        )
    }

    fun playing(): FrameDisplayState = copy(mode = ViewerTransportMode.PLAYING)

    fun pauseAtRendered(): FrameDisplayState {
        val visible = renderedIndex ?: return copy(mode = ViewerTransportMode.SEEKING)
        return copy(requestedIndex = visible, mode = ViewerTransportMode.PAUSED)
    }
}
