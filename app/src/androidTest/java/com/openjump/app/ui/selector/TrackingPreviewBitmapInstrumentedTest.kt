package com.openjump.app.ui.selector

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.VideoTrackingFrameSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackingPreviewBitmapInstrumentedTest {
    @Test fun identityTransformStillOwnsColourPixelsAfterDecoderRecycles() {
        val borrowed = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        borrowed.eraseColor(Color.RED)
        borrowed.setPixel(0, 0, Color.BLUE)
        val owned = copyTrackingPreview(borrowed, 0)
        assertNotSame(borrowed, owned)
        borrowed.recycle()
        assertEquals(Color.BLUE, owned.getPixel(0, 0))
        assertEquals(Color.RED, owned.getPixel(1, 2))
        owned.recycle()
    }

    @Test fun presentationRotationAndSizeCapMatchTheDisplayedCoordinateSpace() {
        val borrowed = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        borrowed.eraseColor(Color.GREEN)
        val owned = copyTrackingPreview(borrowed, 90)
        assertEquals(216, owned.width)
        assertEquals(384, owned.height)
        assertEquals(Color.GREEN, owned.getPixel(100, 200))
        assertTrue(maxOf(owned.width, owned.height) <= MAX_TRACKING_PREVIEW_SIDE)
        borrowed.recycle(); owned.recycle()
    }

    @Test fun decoderObserverCopiesChangingVideoAtExactlyTheCanonicalFramePts() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "preview-source-translation.mp4")
        instrumentation.context.assets.open("tracking_translation.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        var latest: Bitmap? = null
        var first: Bitmap? = null
        var observedIndex = -1
        var observedPts = -1L
        val index = VideoFrameIndexer.indexFromPath(file.absolutePath)
        val source = VideoTrackingFrameSource(context, Uri.fromFile(file), index,
            previewObserver = { frame, pts, borrowed, rotation ->
                latest?.takeIf { it !== first }?.recycle()
                latest = copyTrackingPreview(borrowed, rotation)
                if (first == null) first = latest
                observedIndex = frame; observedPts = pts
            })
        try {
            var count = 0
            source.forEachFrame(0) { frame ->
                assertEquals(frame.frameIndex, observedIndex)
                assertEquals(index.timeOf(frame.frameIndex), observedPts)
                assertEquals(frame.timestampUs, observedPts)
                assertFalse(requireNotNull(latest).isRecycled)
                count++
                true
            }
            assertEquals(index.frameCount, count)
            assertFalse("Dynamic fixture pixels must change", requireNotNull(first).sameAs(requireNotNull(latest)))
            // The source already recycled its borrowed bitmap; our owned copy is still readable.
            requireNotNull(latest).getPixel(0, 0)
        } finally {
            source.close(); first?.recycle(); latest?.takeIf { it !== first }?.recycle(); file.delete()
        }
    }
}
