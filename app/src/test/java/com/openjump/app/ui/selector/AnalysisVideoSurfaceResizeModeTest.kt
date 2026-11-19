package com.openjump.app.ui.selector

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guarda el mapeo FIT/CROP -> `resizeMode` de Media3 que consume la superficie
 * extraída en `AnalysisVideoSurface`. CROP debe seguir siendo ZOOM para que el
 * `exo_content_frame` reciba la misma escala/traslación del mapper; ningún
 * branch puede degradarse a FIT al compartir la superficie con otros visores.
 */
class AnalysisVideoSurfaceResizeModeTest {

    @Test
    fun `FIT usa el resize mode FIT de Media3`() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            analysisVideoResizeMode(VideoScaleMode.FIT),
        )
    }

    @Test
    fun `CROP usa el resize mode ZOOM de Media3 y no el de FIT`() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            analysisVideoResizeMode(VideoScaleMode.CROP),
        )
        assertEquals(
            false,
            analysisVideoResizeMode(VideoScaleMode.CROP) == AspectRatioFrameLayout.RESIZE_MODE_FIT,
        )
    }
}
