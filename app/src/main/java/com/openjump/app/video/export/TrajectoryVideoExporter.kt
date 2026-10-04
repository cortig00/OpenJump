package com.openjump.app.video.export

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.video.VideoFrameIndex
import java.io.File

/** Main-looper wrapper around one compatible, cancellable Media3 Transformer export. */
@OptIn(UnstableApi::class)
class TrajectoryVideoExporter(context: Context) {
    interface Listener {
        fun onProgress(percent: Int?)
        fun onCompleted(file: File, result: ExportResult)
        fun onCancelled()
        fun onError(message: String, cause: Throwable? = null)
    }

    private data class ActiveExport(
        val transformer: Transformer,
        val output: File,
        val listener: Listener,
        var finished: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val debugLoggingEnabled = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val handler = Handler(Looper.getMainLooper())
    private val exportDirectory = File(appContext.cacheDir, EXPORT_DIRECTORY)
    private var active: ActiveExport? = null

    val isExporting: Boolean get() = active != null

    @MainThread
    fun start(
        sourceUri: Uri,
        sourceIndex: VideoFrameIndex,
        trackingResults: List<TrackingFrameResult>,
        listener: Listener,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "La exportación debe iniciarse en main." }
        check(active == null) { "Ya hay una exportación en curso." }

        val timeline = TrajectoryTimeline(sourceIndex, trackingResults)
        exportDirectory.mkdirs()
        require(exportDirectory.isDirectory) { "No se pudo preparar la caché de exportación." }
        pruneOldExports()
        val output = File(exportDirectory, "openjump_trayectoria_${System.currentTimeMillis()}.mp4")

        val profile = CompatibleExportProfile.from(sourceIndex)
        val overlayEffect = OverlayEffect(
            listOf(
                TrajectoryLineOverlay(timeline),
                CurrentTrajectoryPointOverlay(timeline),
            ),
        )
        val videoEffects = mutableListOf<Effect>(overlayEffect)
        if (profile.shouldDownscale) {
            // Scale after drawing so tracking coordinates stay in the source presentation space.
            videoEffects += Presentation.createForShortSide(CompatibleExportProfile.MAX_SHORT_SIDE_PX)
        }
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
        val sequence = if (sourceIndex.hasAudio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedMediaItem))
        } else {
            EditedMediaItemSequence.withVideoFrom(listOf(editedMediaItem))
        }
        val composition = Composition.Builder(sequence)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val encoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(profile.targetVideoBitrate)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(encoderSettings)
            .setEnableFallback(true)
            .build()

        lateinit var created: ActiveExport
        val transformer = Transformer.Builder(appContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .setMuxerFactory(DefaultMuxer.Factory().setVideoDurationUs(sourceIndex.durationUs))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (!finish(created)) return
                    if (!output.isFile || output.length() == 0L) {
                        output.delete()
                        listener.onError("Transformer terminó sin producir un MP4 válido.")
                    } else {
                        listener.onCompleted(output, exportResult)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (!finish(created)) return
                    output.delete()
                    Log.e(TAG, SafeVideoLog.exportFailure(exportException.errorCode))
                    listener.onError("No se pudo generar un MP4 compatible en este dispositivo.")
                }

                override fun onFallbackApplied(
                    composition: Composition,
                    originalTransformationRequest: TransformationRequest,
                    fallbackTransformationRequest: TransformationRequest,
                ) {
                    if (debugLoggingEnabled) {
                        Log.w(
                            TAG,
                            SafeVideoLog.exportFallback(
                                originalTransformationRequest.outputHeight,
                                fallbackTransformationRequest.outputHeight,
                                originalTransformationRequest.hdrMode,
                                fallbackTransformationRequest.hdrMode,
                            ),
                        )
                    }
                }
            })
            .build()
        created = ActiveExport(transformer, output, listener)
        active = created

        try {
            transformer.start(composition, output.absolutePath)
            pollProgress(created)
        } catch (error: Exception) {
            if (finish(created)) {
                output.delete()
                listener.onError("No se pudo iniciar la exportación.")
            }
        }
    }

    @MainThread
    fun cancel(notifyListener: Boolean = true) {
        val current = active ?: return
        if (!finish(current)) return
        current.transformer.cancel()
        current.output.delete()
        if (notifyListener) current.listener.onCancelled()
    }

    private fun pollProgress(export: ActiveExport) {
        if (active !== export || export.finished) return
        val holder = ProgressHolder()
        val state = export.transformer.getProgress(holder)
        export.listener.onProgress(
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress.coerceIn(0, 99) else null,
        )
        handler.postDelayed({ pollProgress(export) }, PROGRESS_INTERVAL_MS)
    }

    private fun finish(export: ActiveExport): Boolean {
        if (export.finished) return false
        export.finished = true
        if (active === export) active = null
        handler.removeCallbacksAndMessages(null)
        return true
    }

    private fun pruneOldExports() {
        exportDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("openjump_trayectoria_") }
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_EXPORTS - 1)
            .forEach(File::delete)
    }

    private companion object {
        const val EXPORT_DIRECTORY = "trajectory_exports"
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_RETAINED_EXPORTS = 3
        const val TAG = "OpenJumpExport"
    }
}
