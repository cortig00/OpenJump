package com.openjump.app.video

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reader contract over app-private fixtures only (cache dir + FileProvider):
 * exact sizes match, unknown stays `null` and stale URIs never crash.
 */
@RunWith(AndroidJUnit4::class)
class VideoFileSizeReaderInstrumentedTest {

    @Test
    fun fileReportsExactBytesWithoutReadingThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "size-reader-${System.nanoTime()}.bin")
        try {
            file.writeBytes(ByteArray(12_345) { (it % 251).toByte() })
            assertEquals(12_345L, VideoFileSizeReader.sizeBytes(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun realEmptyFileReportsZeroWhileMissingAndDirectoriesStayUnknown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val empty = File(context.cacheDir, "size-reader-empty-${System.nanoTime()}.bin")
        try {
            check(empty.createNewFile())
            assertEquals(0L, VideoFileSizeReader.sizeBytes(empty))
        } finally {
            empty.delete()
        }
        assertNull(VideoFileSizeReader.sizeBytes(File(context.cacheDir, "size-reader-missing.mp4")))
        assertNull(VideoFileSizeReader.sizeBytes(context.cacheDir))
    }

    @Test
    fun privateContentUriReportsTheSameBytesAsTheBackingFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val exports = File(context.cacheDir, "trajectory_exports").apply { mkdirs() }
        val file = File(exports, "size-reader-${System.nanoTime()}.mp4")
        try {
            instrumentation.context.assets.open("pat_p.mp4").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            assertEquals(file.length(), VideoFileSizeReader.sizeBytes(context, uri))
            assertEquals(file.length(), VideoFileSizeReader.sizeBytes(context.contentResolver, uri))
        } finally {
            file.delete()
        }
    }

    @Test
    fun staleContentUriIsUnknownInsteadOfCrashing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val exports = File(context.cacheDir, "trajectory_exports").apply { mkdirs() }
        val file = File(exports, "size-reader-stale-${System.nanoTime()}.mp4")
        file.writeBytes(ByteArray(16))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        check(file.delete())
        assertNull(VideoFileSizeReader.sizeBytes(context, uri))
    }
}
