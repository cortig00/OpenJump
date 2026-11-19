package com.openjump.app.ui.selector

import android.net.Uri
import com.openjump.app.video.VideoFrameIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contrato JVM del puerto de sesión que consume [ViewerTransport].
 *
 * No se prueba el adaptador de [com.openjump.app.video.AppSession] ni `setVideo` (requieren
 * `android.net.Uri` real, fuera del alcance de un test JVM sin Robolectric; quedan cubiertos por
 * los flujos instrumentados). Sí se fija que el puerto expone lecturas vivas del frame actual y
 * que el transporte no necesita conocer la sesión concreta.
 */
class ViewerSessionPortTest {

    private class FakeSessionPort : ViewerSessionPort {

        private val frameRequests = MutableStateFlow(0)
        private var video: Uri? = null
        private var index: VideoFrameIndex? = null

        override val videoUri: Uri? get() = video
        override val frameIndex: VideoFrameIndex? get() = index
        override val currentFrameIndex: Int get() = frameRequests.value
        override val currentFrameIndexFlow: StateFlow<Int> get() = frameRequests

        override fun setVideo(uri: Uri, index: VideoFrameIndex) {
            video = uri
            this.index = index
        }

        override fun setFrameIndex(index: Int) {
            frameRequests.value = index
        }
    }

    @Test
    fun `el frame actual es una lectura viva de la sesion`() {
        val port = FakeSessionPort()

        assertEquals(0, port.currentFrameIndex)
        port.setFrameIndex(7)
        assertEquals(7, port.currentFrameIndex)
        assertEquals(7, port.currentFrameIndexFlow.value)

        // El flujo es el mismo objeto que observa el transporte, no una copia.
        port.setFrameIndex(3)
        assertEquals(3, port.currentFrameIndexFlow.value)
        assertEquals(3, port.currentFrameIndex)
    }

    @Test
    fun `sin video indexado el puerto no inventa estado`() {
        val port = FakeSessionPort()

        assertNull(port.videoUri)
        assertNull(port.frameIndex)
    }
}
