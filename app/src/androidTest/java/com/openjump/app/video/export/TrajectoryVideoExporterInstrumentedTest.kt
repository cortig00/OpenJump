package com.openjump.app.video.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrajectoryVideoExporterInstrumentedTest {

    @Test fun exportsTrueCfr30() = exportCfr(30)
    @Test fun exportsTrueCfr60WhenCodecSupportsIt() = exportCfr(60)
    @Test fun exportsTrueCfr120WhenCodecSupportsIt() = exportCfr(120)
    @Test fun exportsTrueCfr240WhenCodecSupportsIt() = exportCfr(240)

    @Test
    fun downscales4kToCompatible1080WithoutLosingFrames() {
        exportAsset("export_4k.mp4", expectedFps = 30, expectCompatibleVideo = true)
    }

    @Test
    fun exportsHlgMain10AsCompatibleH264Sdr() {
        exportAsset("export_hdr_hlg.mp4", expectedFps = 30, expectCompatibleVideo = true)
    }

    @Test
    fun keepsRotationAndAudioTrack() {
        exportAsset("export_rotated_90.mp4", expectedFps = 30)
        exportAsset("export_rotated_180.mp4", expectedFps = 30)
        exportAsset("export_rotated_270.mp4", expectedFps = 30)
        exportAsset("export_audio.mp4", expectedFps = 30, expectAudio = true)
    }

    @Test
    fun preservesVariableFramePts() {
        exportAsset("export_vfr.mp4", expectedFps = null, expectVariablePts = true)
    }

    @Test
    fun normalizesNonZeroInitialPtsWithoutChangingCadence() {
        exportAsset("export_pts_offset.mp4", expectedFps = 30)
    }

    @Test
    fun drawsOnlyAccumulatedPathAndIncludesLastPoint() {
        exportAsset("export_black_path.mp4", expectedFps = 30, verifyPathPixels = true)
    }

    private fun exportCfr(fps: Int) {
        exportAsset("export_cfr_$fps.mp4", expectedFps = fps, requireCodecSupport = true)
    }

    private fun exportAsset(
        assetName: String,
        expectedFps: Int?,
        expectAudio: Boolean = false,
        requireCodecSupport: Boolean = false,
        verifyPathPixels: Boolean = false,
        expectVariablePts: Boolean = false,
        expectCompatibleVideo: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, assetName)
        instrumentation.context.assets.open(assetName).use { input ->
            source.outputStream().use(input::copyTo)
        }
        var output: File? = null
        try {
            val index = VideoFrameIndexer.indexFromPath(source.absolutePath)
            expectedFps?.let { assertEquals(it, index.detectedFps) }
            if (expectVariablePts) {
                assertTrue(index.frameTimesUs.asSequence().zipWithNext { a, b -> b - a }.distinct().count() > 1)
            }
            if (requireCodecSupport) {
                assumeTrue("Encoder exacto no anunciado para $expectedFps fps", supportsEncoding(index))
            }
            val results = trackingResults(index)
            val latch = CountDownLatch(1)
            var failure: String? = null
            val exporter = TrajectoryVideoExporter(context)

            instrumentation.runOnMainSync {
                exporter.start(
                    Uri.fromFile(source),
                    index,
                    results,
                    object : TrajectoryVideoExporter.Listener {
                        override fun onProgress(percent: Int?) = Unit
                        override fun onCompleted(
                            file: File,
                            result: androidx.media3.transformer.ExportResult,
                        ) {
                            output = file
                            latch.countDown()
                        }

                        override fun onCancelled() {
                            failure = "Exportación cancelada"
                            latch.countDown()
                        }

                        override fun onError(message: String, cause: Throwable?) {
                            failure = message
                            latch.countDown()
                        }
                    },
                )
            }

            assertTrue("Timeout exportando $assetName", latch.await(60, TimeUnit.SECONDS))
            assertNull(failure, failure)
            val encoded = requireNotNull(output)
            assertTrue(encoded.length() > 0L)
            val outputIndex = VideoFrameIndexer.indexFromPath(encoded.absolutePath)
            VideoExportValidator.validate(index, outputIndex)
            expectedFps?.let { assertEquals(it, outputIndex.detectedFps) }
            if (expectCompatibleVideo) {
                val profile = CompatibleExportProfile.from(index)
                assertEquals("video/avc", outputIndex.mimeType)
                assertTrue(
                    "La salida sigue siendo HDR",
                    outputIndex.colorTransfer !in setOf(
                        CompatibleExportProfile.COLOR_TRANSFER_ST2084,
                        CompatibleExportProfile.COLOR_TRANSFER_HLG,
                    ),
                )
                outputIndex.averageBitrate?.let { bitrate ->
                    assertTrue(
                        "Bitrate mayor al perfil compatible: $bitrate",
                        bitrate <= (profile.targetVideoBitrate * 1.25).toInt(),
                    )
                }
            }

            val sourceAudio = trackMimes(source).filter { it.startsWith("audio/") }
            val outputAudio = trackMimes(encoded).filter { it.startsWith("audio/") }
            if (expectAudio) {
                assertTrue(sourceAudio.isNotEmpty())
                assertEquals(audioSignature(source), audioSignature(encoded))
            }
            assertEquals(sourceAudio, outputAudio)
            if (verifyPathPixels) verifyAccumulatedPath(encoded, outputIndex)
            if (InstrumentationRegistry.getArguments().getString("preserveExport") == "true") {
                val smokeFile = File(requireNotNull(context.getExternalFilesDir(null)), "smoke_$assetName")
                encoded.copyTo(smokeFile, overwrite = true)
            }
        } finally {
            output?.delete()
            source.delete()
        }
    }

    private fun trackingResults(index: VideoFrameIndex): List<TrackingFrameResult> {
        val geometry = VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees)
        return index.frameTimesUs.indices.map { frame ->
            val progress = frame.toDouble() / index.frameTimesUs.lastIndex.coerceAtLeast(1)
            val point = ImagePoint(
                x = 12.0 + progress * (geometry.width - 24.0),
                y = geometry.height * 0.5,
            )
            TrackingFrameResult(
                frameIndex = frame,
                result = TrackingResult(
                    sample = TrackingSample(index.timeOf(frame), point, 1.0, TrackingStatus.TRACKING),
                    region = null,
                    featurePoints = emptyList(),
                    diagnostics = TrackingDiagnostics.EMPTY,
                ),
            )
        }
    }

    private fun verifyAccumulatedPath(file: File, index: VideoFrameIndex) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val first = requireNotNull(
                retriever.getFrameAtTime(index.timeOf(0), MediaMetadataRetriever.OPTION_CLOSEST),
            )
            val last = requireNotNull(
                retriever.getFrameAtTime(index.timeOf(index.frameCount - 1), MediaMetadataRetriever.OPTION_CLOSEST),
            )
            try {
                assertTrue("Falta el punto inicial", hasGreenNear(first, 12, 120))
                assertTrue("Se dibujó un punto futuro", !hasGreenNear(first, 308, 120))
                assertTrue("Falta el último punto", hasGreenNear(last, 308, 120))
                assertTrue("Falta la trayectoria acumulada", hasGreenNear(last, 160, 120))
            } finally {
                first.recycle()
                last.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    private fun hasGreenNear(bitmap: android.graphics.Bitmap, centerX: Int, centerY: Int): Boolean {
        val left = (centerX - 18).coerceAtLeast(0)
        val right = (centerX + 18).coerceAtMost(bitmap.width - 1)
        val top = (centerY - 18).coerceAtLeast(0)
        val bottom = (centerY + 18).coerceAtMost(bitmap.height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                val color = bitmap.getPixel(x, y)
                val red = android.graphics.Color.red(color)
                val green = android.graphics.Color.green(color)
                val blue = android.graphics.Color.blue(color)
                if (green > 100 && green > red + 35 && green > blue + 20) return true
            }
        }
        return false
    }

    private fun supportsEncoding(index: VideoFrameIndex): Boolean {
        val format = MediaFormat.createVideoFormat(index.mimeType ?: "video/avc", index.width, index.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, index.averageBitrate ?: 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, index.detectedFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
    }

    private data class AudioSignature(
        val mime: String,
        val normalizedPtsUs: List<Long>,
        val payloadSha256: String,
    )

    private fun audioSignature(file: File): AudioSignature {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }
            val mime = requireNotNull(extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(1 shl 20)
            val digest = MessageDigest.getInstance("SHA-256")
            val pts = mutableListOf<Long>()
            var firstPtsUs: Long? = null
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val samplePtsUs = extractor.sampleTime
                if (firstPtsUs == null) firstPtsUs = samplePtsUs
                pts += samplePtsUs - requireNotNull(firstPtsUs)
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes)
                digest.update(bytes)
                extractor.advance()
            }
            return AudioSignature(
                mime = mime,
                normalizedPtsUs = pts,
                payloadSha256 = digest.digest().joinToString("") { "%02x".format(it) },
            )
        } finally {
            extractor.release()
        }
    }

    private fun trackMimes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).mapNotNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
            }
        } finally {
            extractor.release()
        }
    }
}
