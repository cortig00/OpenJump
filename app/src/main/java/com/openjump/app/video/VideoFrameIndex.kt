package com.openjump.app.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.FileDescriptor
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * Índice de frames de un vídeo basado en los **presentation timestamps reales** de cada muestra.
 * No depende del FPS nominal: la medición se hará siempre sobre estos timestamps.
 */
data class VideoFrameIndex(
    /** Timestamps de presentación (µs), ordenados ascendentemente. */
    val frameTimesUs: LongArray,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val mimeType: String?,
    /** FPS declarado por el track. Solo informa/sugiere; nunca sustituye a los PTS. */
    val nominalFrameRate: Double? = null,
    /** FPS de captura original expuesto por Android cuando el contenedor lo conserva. */
    val metadataCaptureFrameRate: Double? = null,
    /** Bitrate medio declarado por el track; solo informa, no obliga al encoder de exportación. */
    val averageBitrate: Int? = null,
    val colorStandard: Int? = null,
    val colorTransfer: Int? = null,
    val colorRange: Int? = null,
    val hasAudio: Boolean = false,
) {

    init {
        require(frameTimesUs.isNotEmpty()) { "El vídeo no contiene frames analizables." }
        require(frameTimesUs.size <= VideoFrameSafety.MAX_FRAME_COUNT) {
            "El vídeo supera el límite de frames procesables."
        }
        VideoFrameSafety.checkedArea(width, height)
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Rotación de vídeo no compatible." }
        require(frameTimesUs.asSequence().zipWithNext().all { (a, b) -> a < b }) {
            "Los PTS deben estar ordenados y sin duplicados."
        }
        require(nominalFrameRate == null || nominalFrameRate.isFinite() && nominalFrameRate > 0.0)
        require(metadataCaptureFrameRate == null || metadataCaptureFrameRate.isFinite() && metadataCaptureFrameRate > 0.0)
        require(averageBitrate == null || averageBitrate > 0)
    }

    val frameCount: Int get() = frameTimesUs.size

    /** Playback duration after removing a non-zero media timeline origin, when present. */
    val playbackDurationUs: Long
        get() {
            val firstPtsUs = frameTimesUs.first()
            return if (firstPtsUs > 0L && durationUs >= frameTimesUs.last()) {
                (durationUs - firstPtsUs).coerceAtLeast(0L)
            } else {
                durationUs
            }
        }

    /**
     * Continuidad observada exclusivamente en PTS. Un hueco supera dos veces la
     * mediana del delta: no invalida los cálculos (que siguen usando PTS reales),
     * pero advierte de que una grabación interna no entregó cadencia continua.
     */
    val temporalContinuity: TemporalContinuity
        get() {
            if (frameTimesUs.size < 2) return TemporalContinuity(0L, 0L, 0, 0L)
            val deltas = deltasUs()
            val median = medianOfSorted(deltas)
            val threshold = median * 2L
            val gaps = deltas.count { it > threshold }
            return TemporalContinuity(
                medianDeltaUs = median,
                gapThresholdUs = threshold,
                gapCount = gaps,
                largestGapUs = deltas.last(),
            )
        }

    /** FPS detectado desde la mediana PTS; información, nunca fuente de la medida. */
    val detectedFps: Int
        get() {
            val median = temporalContinuity.medianDeltaUs
            if (median <= 0) return 0
            return (1_000_000.0 / median).roundToInt()
        }

    private fun deltasUs(): LongArray = LongArray(frameTimesUs.size - 1) {
        frameTimesUs[it + 1] - frameTimesUs[it]
    }.also { it.sort() }

    private fun medianOfSorted(values: LongArray): Long = if (values.size % 2 == 1) {
        values[values.size / 2]
    } else {
        (values[values.size / 2 - 1] + values[values.size / 2]) / 2
    }

    fun timeOf(index: Int): Long = frameTimesUs[index]

    /** Returns the adjacent real PTS values for an exact indexed frame. */
    fun previousPtsUs(frameIndex: Int, ptsUs: Long): Long? {
        require(frameIndex in frameTimesUs.indices && frameTimesUs[frameIndex] == ptsUs) {
            "El evento no coincide con un frame del índice PTS."
        }
        return frameTimesUs.getOrNull(frameIndex - 1)
    }

    fun nextPtsUs(frameIndex: Int, ptsUs: Long): Long? {
        require(frameIndex in frameTimesUs.indices && frameTimesUs[frameIndex] == ptsUs) {
            "El evento no coincide con un frame del índice PTS."
        }
        return frameTimesUs.getOrNull(frameIndex + 1)
    }

    /** Índice del frame cuyo timestamp está más cerca de [timeUs]. */
    fun indexOfNearest(timeUs: Long): Int {
        var lo = 0
        var hi = frameTimesUs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (frameTimesUs[mid] < timeUs) lo = mid + 1 else hi = mid
        }
        // lo es el primer índice con tiempo >= timeUs; compara con el anterior
        return when {
            lo == 0 -> 0
            timeUs - frameTimesUs[lo - 1] <= frameTimesUs[lo] - timeUs -> lo - 1
            else -> lo
        }
    }
}

/**
 * Converts extractor iteration order (which may be decode order for B-frames) into
 * presentation order. Duplicate PTS are invalid and are rejected rather than hidden.
 */
object PresentationTimestampNormalizer {
    fun normalize(rawTimestampsUs: Iterable<Long>): LongArray {
        (rawTimestampsUs as? Collection<Long>)?.let {
            require(it.size <= VideoFrameSafety.MAX_FRAME_COUNT) { "El vídeo supera el límite de frames procesables." }
        }
        val values = BoundedTimestampCollector(VideoFrameSafety.MAX_FRAME_COUNT)
        rawTimestampsUs.forEach(values::add)
        return values.sortedUnique()
    }
}

private class BoundedTimestampCollector(private val maximumCount: Int) {
    private var values = LongArray(minOf(1024, maximumCount))
    private var size = 0

    fun add(value: Long) {
        require(size < maximumCount) { "El vídeo supera el límite de frames procesables." }
        if (size == values.size) {
            val nextSize = minOf(maximumCount, maxOf(1, values.size * 2))
            values = values.copyOf(nextSize)
        }
        values[size++] = value
    }

    fun sortedUnique(): LongArray {
        require(size > 0) { "El track de vídeo no contiene frames." }
        val sorted = values.copyOf(size)
        sorted.sort()
        for (index in 1 until sorted.size) {
            require(sorted[index - 1] < sorted[index]) { "Los PTS duplicados no son válidos." }
        }
        return sorted
    }
}

data class TemporalContinuity(
    val medianDeltaUs: Long,
    val gapThresholdUs: Long,
    val gapCount: Int,
    val largestGapUs: Long,
) {
    val hasGaps: Boolean get() = gapCount > 0
}

/**
 * Construye un [VideoFrameIndex] leyendo los timestamps reales del track de vídeo con [MediaExtractor].
 * Es una pasada de metadatos (sin decode), rápida para vídeos cortos de salto.
 */
object VideoFrameIndexer {

    fun indexFromUri(
        context: Context,
        uri: Uri,
        cancellationCheck: () -> Unit = {},
    ): VideoFrameIndex {
        val captureRate = readCaptureRate(cancellationCheck) { it.setDataSource(context, uri) }
        cancellationCheck()
        return context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            requireNotNull(pfd) { "No se pudo abrir el vídeo (URI inaccesible)." }
            indexFromFd(pfd.fileDescriptor, captureRate, cancellationCheck)
        }
    }

    fun indexFromPath(path: String, cancellationCheck: () -> Unit = {}): VideoFrameIndex {
        val captureRate = readCaptureRate(cancellationCheck) { it.setDataSource(path) }
        val extractor = MediaExtractor()
        try {
            cancellationCheck()
            extractor.setDataSource(path)
            return build(extractor, captureRate, cancellationCheck)
        } finally {
            extractor.release()
        }
    }

    fun indexFromFd(fd: FileDescriptor, cancellationCheck: () -> Unit = {}): VideoFrameIndex {
        val captureRate = readCaptureRate(cancellationCheck) { it.setDataSource(fd) }
        return indexFromFd(fd, captureRate, cancellationCheck)
    }

    private fun indexFromFd(
        fd: FileDescriptor,
        captureRate: Double?,
        cancellationCheck: () -> Unit,
    ): VideoFrameIndex {
        val extractor = MediaExtractor()
        try {
            cancellationCheck()
            extractor.setDataSource(fd)
            return build(extractor, captureRate, cancellationCheck)
        } finally {
            extractor.release()
        }
    }

    private fun build(
        extractor: MediaExtractor,
        captureRate: Double?,
        cancellationCheck: () -> Unit,
    ): VideoFrameIndex {
        require(extractor.trackCount in 1..VideoFrameSafety.MAX_TRACK_COUNT) {
            "El vídeo supera el límite de tracks procesables."
        }
        var videoTrackIndex = -1
        var hasAudio = false
        for (trackIndex in 0 until extractor.trackCount) {
            cancellationCheck()
            val mime = extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME).orEmpty()
            if (videoTrackIndex < 0 && mime.startsWith("video/")) videoTrackIndex = trackIndex
            if (mime.startsWith("audio/")) hasAudio = true
        }
        require(videoTrackIndex >= 0) { "El archivo no contiene un track de vídeo." }

        val format = extractor.getTrackFormat(videoTrackIndex)
        val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
        val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        VideoFrameSafety.checkedArea(width, height)
        val mime = format.getString(MediaFormat.KEY_MIME)
        val colorStandard = format.intValueOrNull(MediaFormat.KEY_COLOR_STANDARD)
        val colorTransfer = format.intValueOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        val colorRange = format.intValueOrNull(MediaFormat.KEY_COLOR_RANGE)
        val averageBitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()?.takeIf { it > 0 }
        } else {
            null
        }
        val nominalFrameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble()
            } else {
                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() }
            }
            value?.takeIf { it.isFinite() && it > 0.0 }
        } else {
            null
        }

        extractor.selectTrack(videoTrackIndex)

        // MediaExtractor can expose decode order (for example with B-frames). Keep every
        // presentation timestamp, sort into presentation order, and reject duplicates.
        val raw = BoundedTimestampCollector(VideoFrameSafety.MAX_FRAME_COUNT)
        while (true) {
            cancellationCheck()
            val timestampUs = extractor.sampleTime
            if (timestampUs < 0L) break
            raw.add(timestampUs)
            if (!extractor.advance()) break
        }
        cancellationCheck()
        val frameTimes = raw.sortedUnique()
        cancellationCheck()

        val duration =
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                frameTimes.last()
            }

        return VideoFrameIndex(
            frameTimesUs = frameTimes,
            durationUs = duration,
            width = width,
            height = height,
            rotationDegrees = rotation,
            mimeType = mime,
            nominalFrameRate = nominalFrameRate,
            metadataCaptureFrameRate = captureRate?.takeIf { it.isFinite() && it > 0.0 },
            averageBitrate = averageBitrate,
            colorStandard = colorStandard,
            colorTransfer = colorTransfer,
            colorRange = colorRange,
            hasAudio = hasAudio,
        )
    }

    private fun readCaptureRate(
        cancellationCheck: () -> Unit,
        setDataSource: (MediaMetadataRetriever) -> Unit,
    ): Double? = try {
        cancellationCheck()
        MediaMetadataRetriever().useCompat { retriever ->
            setDataSource(retriever)
            cancellationCheck()
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun MediaFormat.intValueOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private inline fun <T> MediaMetadataRetriever.useCompat(block: (MediaMetadataRetriever) -> T): T =
        try {
            block(this)
        } finally {
            release()
        }
}
