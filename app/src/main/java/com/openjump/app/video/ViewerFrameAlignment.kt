package com.openjump.app.video

/**
 * Traduce el PTS que reporta el player al frame canónico del [VideoFrameIndex] medido
 * con los timestamps reales del MediaExtractor.
 *
 * ExoPlayer normaliza algunos contenedores para empezar en cero. La primera salida
 * corresponde al seek inicial exacto y permite alinear su timeline con los PTS
 * originales del MediaExtractor; ese desfase se calcula una sola vez (latch) y se
 * reutiliza hasta el siguiente [rebase].
 *
 * Límites explícitos, para no atribuirle más garantías de las que tiene:
 * - No prueba la correlación física entre la imagen decodificada y el frame indexado:
 *   solo traduce un PTS del player al índice PTS canónico más cercano.
 * - El latch no demuestra precisión en runtime. Si la primera salida no correspondiera
 *   al seek inicial exacto, el desfase quedaría desplazado hasta el siguiente [rebase]
 *   (por ejemplo al reanudar el player tras una exportación).
 *
 * No añade sincronización: conserva el mismo modelo de acceso que los campos privados
 * del ViewModel que sustituye.
 */
class ViewerFrameAlignment {

    /** Frame resuelto para un PTS del player, con su PTS canónico exacto del índice. */
    data class AlignedFrame(val renderedIndex: Int, val canonicalPtsUs: Long)

    private var ptsOffsetUs: Long? = null
    private var anchorFrameIndex: Int = 0

    /**
     * Reancla la alineación: descarta el desfase anterior y fija el frame que se asumirá
     * como primera salida del player. [anchorFrameIndex] debe ser un índice válido del
     * índice de frames (los llamadores ya lo acotan con `coerceIn`).
     */
    fun rebase(anchorFrameIndex: Int) {
        this.anchorFrameIndex = anchorFrameIndex
        ptsOffsetUs = null
    }

    /**
     * Alinea [playerPtsUs] con el índice y devuelve el frame canónico.
     *
     * El desfase se calcula en la primera llamada tras un [rebase] y no se recalcula
     * después: el resto de salidas se desplazan con ese mismo valor.
     */
    fun align(index: VideoFrameIndex, playerPtsUs: Long): AlignedFrame {
        val offset = ptsOffsetUs ?: (index.timeOf(anchorFrameIndex) - playerPtsUs).also {
            ptsOffsetUs = it
        }
        val sourcePtsUs = playerPtsUs + offset
        val renderedIndex = index.indexOfNearest(sourcePtsUs)
        return AlignedFrame(renderedIndex = renderedIndex, canonicalPtsUs = index.timeOf(renderedIndex))
    }
}
