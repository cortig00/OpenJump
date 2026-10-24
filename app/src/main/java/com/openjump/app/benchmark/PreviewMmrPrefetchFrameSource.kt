package com.openjump.app.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.TrackingFrameSource
import com.openjump.app.tracking.TrackingPerformanceProfiler
import com.openjump.app.ui.selector.copyTrackingPreview
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoTrackingFrameSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AndroidTest-only UI/preview prefetch wrapper (approved canary slice).
 *
 * Wraps the frozen core [MmrPrefetchFrameSource] (which owns real MMR on a single owned
 * dispatcher thread). The producer hook copies the native bitmap with the existing
 * [copyTrackingPreview] (<=384 side) on producer cadence `(frame - seed) % 5 == 0` or the last
 * frame, pairs frameIndex/PTS, and never calls the VM observer on the producer nor retains the
 * native bitmap.
 *
 * On the single consumer, before KLT, the exact frame/PTS packet is delivered to the original
 * VM preview observer as a borrowed tiny copy with rotation 0 (the VM owns its display copy);
 * recycled here is only the private borrowed copy. The displayed copy is NEVER recycled here.
 *
 * Envelope (conservative, NOT an RSS/native bound): existing gray queue 4 x 518400B (<=2MiB)
 * plus at most 4 tiny preview copies x 589824B (<=2.25MiB) beyond the existing 64MiB MMR
 * envelope gives a combined conservative bounded image envelope <= 70MiB.
 *
 * Fail-fast (no silent drops): over-capacity recycles the new tiny copy and throws; PTS
 * mismatch throws; a missing expected-cadence packet on the consumer throws. Close is idle-only:
 * the delegate is closed FIRST and the wrapper closed-bit/drain happen only on success, so a
 * rejected close leaves closed=false with no drain.
 */
@Suppress("DEPRECATION")
class PreviewMmrPrefetchFrameSource(
    context: Context,
    uri: Uri,
    index: VideoFrameIndex,
    profiler: TrackingPerformanceProfiler,
    private val seedFrameIndex: Int,
    private val originalPreviewObserver: ((frameIndex: Int, ptsUs: Long, borrowedBitmap: Bitmap, rotationDegrees: Int) -> Unit)?,
) : TrackingFrameSource {

    private val appContext: Context = context.applicationContext ?: context
    private val totalFrames: Int = index.frameCount
    private val timeOf: (Int) -> Long = { position -> index.timeOf(position) }

    private val store = PreviewPacketStore(seedFrameIndex = seedFrameIndex, frameCount = totalFrames)
    private val closed = AtomicBoolean(false)
    private val closeLock = Any()

    private val owned = MmrPrefetchFrameSource.newOwnedDispatcher("preview-mmr-prefetch-owner")
    private val ownerExecutor = owned.first
    private val ownerDispatcher = owned.second

    private val producerHook: (Int, Long, Bitmap, Int) -> Unit = { frame, pts, borrowed, rotation ->
        store.offerProducerCopy(frame, pts, borrowed, rotation)
    }

    private val inner: VideoTrackingFrameSource
    private val prefetch: MmrPrefetchFrameSource

    init {
        // Constructor failure must not leak the owned dispatcher/executor (main source file is
        // frozen, so ownership cleanup lives here).
        var constructed = false
        var tmpInner: VideoTrackingFrameSource? = null
        var tmpPrefetch: MmrPrefetchFrameSource? = null
        try {
            tmpInner = VideoTrackingFrameSource(
                context = appContext,
                uri = uri,
                index = index,
                performanceProfiler = profiler,
                previewObserver = producerHook,
                decoderDispatcher = ownerDispatcher,
            )
            tmpPrefetch = MmrPrefetchFrameSource(
                delegate = tmpInner,
                ownerDispatcher = ownerDispatcher,
                ownerExecutor = ownerExecutor,
            )
            constructed = true
        } finally {
            if (!constructed) {
                try {
                    ownerDispatcher.close()
                } catch (_: Throwable) {
                }
                ownerExecutor.shutdown()
            }
        }
        inner = requireNotNull(tmpInner) { "preview wrapper delegate construction failed" }
        prefetch = requireNotNull(tmpPrefetch) { "preview wrapper prefetch construction failed" }
    }

    override val frameCount: Int get() = prefetch.frameCount

    override suspend fun frameAt(index: Int): DecodedTrackingFrame = prefetch.frameAt(index)

    override suspend fun forEachFrame(
        startIndex: Int,
        onFrame: suspend (DecodedTrackingFrame) -> Boolean,
    ) {
        var primary: Throwable? = null
        store.beginRange(startIndex)
        try {
            prefetch.forEachFrame(startIndex) { frame ->
                store.deliverForConsumer(frame.frameIndex, frame.timestampUs, originalPreviewObserver)
                onFrame(frame)
            }
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            // Delegate already joined its producer; drain any undelivered private copies now.
            // Drain never throws (recycles defensively), so a consumer primary is preserved.
            try {
                store.drainAndRecycle()
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    override fun close() {
        // Idle-only: delegate FIRST. A rejected close (active operation) leaves closed=false
        // with no drain, so the caller can cancel/join and retry.
        synchronized(closeLock) {
            if (closed.get()) return
            // Throws (IllegalStateException) when an operation is active; closed stays false.
            prefetch.close()
            closed.set(true)
        }
        // Delegate joined/closed successfully; now drain private copies only.
        store.drainAndRecycle()
    }

    fun previewDiagnostics(): PreviewMmrDiagnostics = store.snapshot(
        prefetchDiagnostics = prefetch.diagnostics(),
        closed = closed.get(),
    )

    companion object {
        const val PREVIEW_CADENCE = 5
        const val MAX_PREVIEW_COPIES = 4
        const val TINY_SIDE = 384
        const val TINY_BYTES = 384 * 384 * 4
        const val MAX_PREVIEW_BYTES = 4 * 384 * 384 * 4
        const val EXISTING_MMR_ENVELOPE_BYTES = 64L * 1024L * 1024L
        const val COMBINED_IMAGE_ENVELOPE_BYTES = 70L * 1024L * 1024L
        const val ENVELOPE_NOTE = "conservative bounded image envelope, NOT an RSS/native-heap bound"
        const val SOURCE_ROUTE = "PREVIEW_MMR_PREFETCH_OWNED_SINGLE_THREAD"

        /**
         * Test-visible helper that delegates to the production [copyTrackingPreview] so the
         * scale/rotate/copy algorithm is never duplicated.
         */
        internal fun tinyPreviewCopy(borrowed: Bitmap, rotationDegrees: Int): Bitmap =
            copyTrackingPreview(borrowed, rotationDegrees)

        fun isPreviewCadence(frameIndex: Int, seedFrameIndex: Int, frameCount: Int): Boolean =
            (frameIndex - seedFrameIndex) % PREVIEW_CADENCE == 0 || frameIndex == frameCount - 1

        fun expectedPreviewPackets(seedFrameIndex: Int, frameCount: Int, startIndex: Int = 0): Int =
            (startIndex until frameCount).count { isPreviewCadence(it, seedFrameIndex, frameCount) }
    }

    private class PreviewPacket(
        val frameIndex: Int,
        val ptsUs: Long,
        val bitmap: Bitmap,
    )

    private class PreviewPacketStore(
        private val seedFrameIndex: Int,
        private val frameCount: Int,
    ) {
        private val lock = Any()
        private val pending = LinkedHashMap<Int, PreviewPacket>()
        private val produced = AtomicInteger(0)
        private val delivered = AtomicInteger(0)
        private val droppedOldest = AtomicInteger(0)
        private val maxPending = AtomicInteger(0)
        private val ptsMatches = AtomicInteger(0)
        private val ptsMismatches = AtomicInteger(0)
        private val producerThreadIds = mutableSetOf<Long>()
        private val consumerThreadId = AtomicLong(-1L)
        private var borrowedOut = 0
        private var borrowedBytes = 0L
        private var maxLiveCopies = 0
        private var maxLiveBytes = 0L
        private var firstRequestedFrame = 0

        fun beginRange(startIndex: Int) = synchronized(lock) {
            firstRequestedFrame = startIndex
        }

        fun offerProducerCopy(frameIndex: Int, ptsUs: Long, borrowed: Bitmap, rotation: Int) {
            if (!isPreviewCadence(frameIndex, seedFrameIndex, frameCount)) return
            // Never retain the native bitmap; copy synchronously on the producer thread via the
            // single production helper.
            val tiny = tinyPreviewCopy(borrowed, rotation)
            try {
                require(tiny.width <= TINY_SIDE && tiny.height <= TINY_SIDE) {
                    "over-wide private preview ${tiny.width}x${tiny.height}"
                }
                require(tiny.allocationByteCount <= TINY_BYTES) {
                    "over-wide private preview bytes ${tiny.allocationByteCount}"
                }
                synchronized(lock) {
                    producerThreadIds += Thread.currentThread().id
                    val liveBefore = pending.size + borrowedOut
                    if (liveBefore >= MAX_PREVIEW_COPIES || pending.size >= MAX_PREVIEW_COPIES) {
                        // Fail fast: refuse capacity, recycle the NEW tiny copy, never evict oldest.
                        throw IllegalStateException(
                            "preview bound exceeded live=$liveBefore pending=${pending.size}",
                        )
                    }
                    // Replace same-frame stale entry without leaking.
                    pending.remove(frameIndex)?.bitmap?.recycle()
                    pending[frameIndex] = PreviewPacket(frameIndex, ptsUs, tiny)
                    produced.incrementAndGet()
                    if (pending.size > maxPending.get()) maxPending.set(pending.size)
                    val liveNow = pending.size + borrowedOut
                    if (liveNow > maxLiveCopies) maxLiveCopies = liveNow
                    val liveBytesNow = pendingBytesLocked() + borrowedBytesLocked()
                    if (liveBytesNow > maxLiveBytes) maxLiveBytes = liveBytesNow
                    check(pending.size <= MAX_PREVIEW_COPIES) { "preview bound exceeded size=${pending.size}" }
                    check(liveNow <= MAX_PREVIEW_COPIES) { "preview live bound exceeded live=$liveNow" }
                }
            } catch (error: Throwable) {
                // Reserved slot is released by recycling the new tiny copy on any failure.
                try {
                    tiny.recycle()
                } catch (_: Throwable) {
                }
                throw error
            }
        }

        fun deliverForConsumer(
            frameIndex: Int,
            framePtsUs: Long,
            originalObserver: ((Int, Long, Bitmap, Int) -> Unit)?,
        ) {
            val packet: PreviewPacket = synchronized(lock) {
                consumerThreadId.compareAndSet(-1L, Thread.currentThread().id)
                pending.remove(frameIndex)?.also { owned ->
                    // Map-to-consumer ownership transfer is atomic with respect to producer bounds.
                    borrowedOut++
                    borrowedBytes += owned.bitmap.allocationByteCount.toLong()
                }
            } ?: run {
                if (isPreviewCadence(frameIndex, seedFrameIndex, frameCount)) {
                    throw IllegalStateException("missing expected preview cadence packet frame=$frameIndex")
                }
                return
            }
            try {
                if (packet.ptsUs != framePtsUs) {
                    ptsMismatches.incrementAndGet()
                    throw IllegalStateException(
                        "preview PTS mismatch frame=$frameIndex packetPts=${packet.ptsUs} framePts=$framePtsUs",
                    )
                }
                ptsMatches.incrementAndGet()
                // Borrowed tiny copy with rotation 0; the VM observer makes its own owned UI copy.
                originalObserver?.invoke(packet.frameIndex, packet.ptsUs, packet.bitmap, 0)
                delivered.incrementAndGet()
            } finally {
                synchronized(lock) {
                    if (borrowedOut > 0) borrowedOut--
                    borrowedBytes = (borrowedBytes - packet.bitmap.allocationByteCount.toLong()).coerceAtLeast(0L)
                }
                // Recycle only the private borrowed copy; the displayed copy owned by the VM is
                // never recycled here.
                try {
                    packet.bitmap.recycle()
                } catch (_: Throwable) {
                }
            }
        }

        fun drainAndRecycle() {
            val toRecycle: List<PreviewPacket> = synchronized(lock) {
                val drained = pending.values.toList()
                pending.clear()
                drained
            }
            toRecycle.forEach { packet ->
                try {
                    packet.bitmap.recycle()
                } catch (_: Throwable) {
                }
            }
        }

        private fun pendingBytesLocked(): Long {
            var total = 0L
            pending.values.forEach { total += it.bitmap.allocationByteCount.toLong() }
            return total
        }

        private fun borrowedBytesLocked(): Long = borrowedBytes

        fun snapshot(prefetchDiagnostics: MmrPrefetchDiagnostics, closed: Boolean): PreviewMmrDiagnostics {
            val pendingSize: Int
            val producerThreads: Set<Long>
            val liveCopies: Int
            val liveMaxCopies: Int
            val liveMaxBytes: Long
            synchronized(lock) {
                pendingSize = pending.size
                producerThreads = producerThreadIds.toSet()
                liveCopies = pendingSize + borrowedOut
                liveMaxCopies = maxLiveCopies
                liveMaxBytes = maxLiveBytes
            }
            return PreviewMmrDiagnostics(
                producedPreviewPackets = produced.get(),
                deliveredPreviewPackets = delivered.get(),
                droppedOldest = droppedOldest.get(),
                pendingPreviewPackets = pendingSize,
                maxPendingPreviewPackets = maxPending.get(),
                previewPtsMatches = ptsMatches.get(),
                previewPtsMismatches = ptsMismatches.get(),
                producerThreadIds = producerThreads,
                consumerThreadId = consumerThreadId.get().takeIf { it != -1L },
                prefetchDiagnostics = prefetchDiagnostics,
                closed = closed,
                maxPreviewCopies = MAX_PREVIEW_COPIES,
                maxPreviewBytes = MAX_PREVIEW_BYTES.toLong(),
                combinedImageEnvelopeBytes = COMBINED_IMAGE_ENVELOPE_BYTES,
                envelopeNote = ENVELOPE_NOTE,
                sourceRoute = SOURCE_ROUTE,
                maxLivePreviewCopies = liveMaxCopies,
                maxLivePreviewBytes = liveMaxBytes,
                livePreviewCopies = liveCopies,
                previewCadence = PREVIEW_CADENCE,
                expectedPreviewPackets = expectedPreviewPackets(seedFrameIndex, frameCount, startIndex = firstRequestedFrame),
            )
        }
    }
}

data class PreviewMmrDiagnostics(
    val producedPreviewPackets: Int,
    val deliveredPreviewPackets: Int,
    val droppedOldest: Int,
    val pendingPreviewPackets: Int,
    val maxPendingPreviewPackets: Int,
    val previewPtsMatches: Int,
    val previewPtsMismatches: Int,
    val producerThreadIds: Set<Long>,
    val consumerThreadId: Long?,
    val prefetchDiagnostics: MmrPrefetchDiagnostics,
    val closed: Boolean,
    val maxPreviewCopies: Int,
    val maxPreviewBytes: Long,
    val combinedImageEnvelopeBytes: Long,
    val envelopeNote: String,
    val sourceRoute: String,
    val maxLivePreviewCopies: Int = 0,
    val maxLivePreviewBytes: Long = 0L,
    val livePreviewCopies: Int = 0,
    val previewCadence: Int = 5,
    val expectedPreviewPackets: Int = 0,
)
