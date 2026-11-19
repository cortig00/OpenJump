package com.openjump.app.ui.selector

import android.net.Uri
import com.openjump.app.video.AppSession
import com.openjump.app.video.VideoFrameIndex
import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que el transporte del visor necesita de la sesión: vídeo, índice de frames y frame
 * solicitado. El selector lo implementa con [AppSession]; otros visores (por ejemplo el
 * editor de ángulos) pueden inyectar su propia sesión sin arrastrar la política de placas.
 *
 * El puerto no expone escrituras de dominio: solo fija el vídeo indexado y el frame actual,
 * exactamente los dos efectos que el transporte ya realizaba sobre la sesión.
 */
interface ViewerSessionPort {

    val videoUri: Uri?

    val frameIndex: VideoFrameIndex?

    /** Frame solicitado actualmente; se lee al pedir, pausar, refrescar o reanudar el visor. */
    val currentFrameIndex: Int

    /** Flujo observado por el transporte para reflejar cambios externos de frame. */
    val currentFrameIndexFlow: StateFlow<Int>

    fun setVideo(uri: Uri, index: VideoFrameIndex)

    fun setFrameIndex(index: Int)
}

/** Adaptador del selector: el mismo estado de sesión que usaba el ViewModel directamente. */
object AppSessionViewerSessionPort : ViewerSessionPort {

    override val videoUri: Uri?
        get() = AppSession.videoUri.value

    override val frameIndex: VideoFrameIndex?
        get() = AppSession.frameIndex.value

    override val currentFrameIndex: Int
        get() = AppSession.currentFrameIndex.value

    override val currentFrameIndexFlow: StateFlow<Int>
        get() = AppSession.currentFrameIndex

    override fun setVideo(uri: Uri, index: VideoFrameIndex) {
        AppSession.setVideo(uri, index)
    }

    override fun setFrameIndex(index: Int) {
        AppSession.setFrameIndex(index)
    }
}
