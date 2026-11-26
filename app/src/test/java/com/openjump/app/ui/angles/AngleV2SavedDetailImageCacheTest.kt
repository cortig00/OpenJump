package com.openjump.app.ui.angles

import android.graphics.Bitmap
import com.openjump.app.data.AngleAnalysisV2Dao
import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.StoredAngleImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleV2SavedDetailImageCacheTest {

    @Test
    fun `bounded cache evicts least recently used entry when budget is exceeded`() {
        val cache = BoundedAngleImageCache(maxCacheKb = 10)
        val b1 = fakeBitmap()
        val b2 = fakeBitmap()
        val b3 = fakeBitmap()

        cache.put("k1", b1, sizeKb = 4)
        cache.put("k2", b2, sizeKb = 4)
        // Touch k1 so k2 becomes least recently used
        assertSame(b1, cache.get("k1"))
        cache.put("k3", b3, sizeKb = 4)

        assertSame(b1, cache.get("k1"))
        assertNull(cache.get("k2"))
        assertSame(b3, cache.get("k3"))

        cache.clear()
        assertNull(cache.get("k1"))
        assertNull(cache.get("k3"))
    }

    @Test
    fun `viewModel image caches decoded bitmap and avoids repeated disk reads`() = runBlocking {
        var readCount = 0
        val cachedBitmap = fakeBitmap()
        val vm = AngleV2SavedDetailViewModel(
            dao = StubAngleV2Dao(),
            readImageBytes = { key, integrity ->
                readCount++
                AngleImageReadResult.Delivered(
                    image = StoredAngleImage(key, integrity.byteCount, integrity.sha256Hex),
                    value = byteArrayOf(1, 2, 3),
                )
            },
            prepareShare = { _, _ -> AngleV2FrameShareResult.Unavailable },
            decodeImage = { _, _ -> AngleV2SavedImage.Available(cachedBitmap) },
            maxImageCacheKb = 1024,
        )
        val frame = AngleV2SavedDetailFrame(
            ordinal = 0,
            frameId = 1L,
            assignedFrameIndex = 10,
            reportedFrameIndex = 10,
            presentationWidth = 640,
            presentationHeight = 480,
            rotationDegrees = 0,
            imageKey = "0123456789abcdef0123456789abcdef",
            imageWidth = 640,
            imageHeight = 480,
            imageByteCount = 3L,
            imageSha256 = "a".repeat(64),
            imageMimeType = "image/png",
            marks = emptyList(),
        )

        val first = vm.image(frame)
        val second = vm.image(frame)

        assertTrue(first is AngleV2SavedImage.Available)
        assertTrue(second is AngleV2SavedImage.Available)
        assertSame(cachedBitmap, (first as AngleV2SavedImage.Available).bitmap)
        assertSame(cachedBitmap, (second as AngleV2SavedImage.Available).bitmap)
        assertEquals(1, readCount)
    }

    /** Allocates a Bitmap instance on JVM without invoking Android native init. */
    private fun fakeBitmap(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, Bitmap::class.java) as Bitmap
    }

    private class StubAngleV2Dao : AngleAnalysisV2Dao {
        override suspend fun rootById(rootId: Long): AngleAnalysisV2RootEntity? = null
        override suspend fun rootBySessionKey(sessionKey: String): AngleAnalysisV2RootEntity? = null
        override suspend fun framesForRoot(rootId: Long): List<AngleAnalysisV2FrameEntity> = emptyList()
        override suspend fun frameById(rootId: Long, frameId: Long): AngleAnalysisV2FrameEntity? = null
        override suspend fun marksForFrame(frameId: Long): List<AngleAnalysisV2MarkEntity> = emptyList()
        override suspend fun frameCount(rootId: Long): Int = 0
        override suspend fun frameImageReferenceCount(imageKey: String): Int = 0
        override suspend fun insertRoot(root: AngleAnalysisV2RootEntity): Long = 0L
        override suspend fun insertFrame(frame: AngleAnalysisV2FrameEntity): Long = 0L
        override suspend fun insertMarks(marks: List<AngleAnalysisV2MarkEntity>) = Unit
        override suspend fun deleteRootRow(rootId: Long): Int = 0
    }
}
