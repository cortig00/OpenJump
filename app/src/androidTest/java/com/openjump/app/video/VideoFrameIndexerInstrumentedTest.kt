package com.openjump.app.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoFrameIndexerInstrumentedTest {

    @Test
    fun syntheticPortraitVideoKeepsRealPtsAndMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "pat_p.mp4")
        instrumentation.context.assets.open("pat_p.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            val index = VideoFrameIndexer.indexFromPath(video.absolutePath)

            assertEquals(30, index.frameCount)
            assertEquals(30, index.detectedFps)
            assertEquals(1080, index.width)
            assertEquals(1920, index.height)
            assertEquals(0, index.rotationDegrees)
            assertEquals(0L, index.frameTimesUs.first())
            assertTrue(index.frameTimesUs.asSequence().zipWithNext().all { (a, b) -> a < b })
        } finally {
            video.delete()
        }
    }
}
