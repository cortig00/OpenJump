package com.openjump.app.benchmark

import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.TrackingFrameSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Test-only bounded prefetch wrapper around an MMR [TrackingFrameSource].
 *
 * Canary slice only: the production decoder stays MMR with identical grouping/BitmapParams,
 * rotation, grayscale and PTS. The queue holds at most [QUEUE_CAPACITY] grayscale frames; no
 * Bitmap ever enters the queue and gray arrays are never copied or altered.
 *
 * Resource envelope (conservative, NOT an RSS/native bound):
 * queue(2) + producer-blocked-send(1) + consumer-current(1) = at most 4 owned gray images,
 * each <= 720 x 720 = 518400 bytes, so <= 2073600 bytes (< 2 MiB) beyond the existing 64 MiB MMR
 * envelope; combined conservative bounded image envelope <= 66 MiB. Tracking dimension stays
 * <= 720 via runtime enforcement (fail fast, no silent mixed paths).
 *
 * Threading: the delegate MUST be constructed with [ownerDispatcher] as its decoder dispatcher
 * so retriever create/dataSource/metadata/decode/release (seed via frameAt, batch via
 * forEachFrame, close) all run on the single owned physical thread (physical thread id, never
 * thread name). The producer coroutine runs on [ownerDispatcher]; the single consumer ([onFrame],
 * KLT) runs on the caller thread and never on the producer. No preview is ever issued ahead of
 * the consumer.
 *
 * Lifecycle contract (minimal approved option):
 * - [frameAt]/[forEachFrame] claim the single operation slot under [lifecycleLock]; concurrent
 *   operations are rejected with [IllegalStateException].
 * - [close] is only valid when idle. A synchronous [close] racing an active [forEachFrame] is
 *   explicitly rejected with [IllegalStateException] WITHOUT setting the closed bit, releasing
 *   the retriever, closing the dispatcher or shutting down the executor. The caller must cancel
 *   its own [forEachFrame] (which cancels the producer, joins it, drains the queue and clears
 *   the slot) and then call [close] again when idle. No async auto-close races, no forced
 *   native/decode interrupt; cancellation relies on the existing source cooperative checks.
 * - Idle [close] from the owner thread calls through directly and must NOT self-await executor
 *   termination; termination is observed externally via `awaitTermination` after return. Idle
 *   [close] from any other thread dispatches `delegate.close()` to the owner (bounded 5s) then
 *   shuts down and awaits termination bounded 5s. `ownerShutdown` vs `ownerTerminated` are
 *   reported as separate fields; the legacy OR field is retained only for compatibility.
 */
@Suppress("DEPRECATION")
class MmrPrefetchFrameSource(
    private val delegate: TrackingFrameSource,
    private val ownerDispatcher: ExecutorCoroutineDispatcher,
    private val ownerExecutor: ExecutorService,
    private val queueCapacity: Int = QUEUE_CAPACITY,
) : TrackingFrameSource {

    private val lifecycleLock = Any()

    override val frameCount: Int get() = delegate.frameCount

    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val activeJob = AtomicReference<Job?>(null)
    private val ownerThreadId = AtomicLong(UNKNOWN_THREAD)
    private val produced = AtomicInteger(0)
    private val consumed = AtomicInteger(0)
    private val highWaterMark = AtomicInteger(0)
    private val outstandingBytes = AtomicLong(0L)
    private val maxOwnedBytes = AtomicLong(0L)
    private val producerThreadIds = ConcurrentHashMap.newKeySet<Long>()
    private val consumerThreadId = AtomicLong(UNKNOWN_THREAD)
    private val cancelled = AtomicBoolean(false)
    private val joined = AtomicBoolean(false)
    private val producerFailure = AtomicReference<Throwable?>(null)
    private val ownerShutdown = AtomicBoolean(false)
    private val ownerTerminated = AtomicBoolean(false)

    init {
        require(queueCapacity == QUEUE_CAPACITY) { "queue capacity is fixed at $QUEUE_CAPACITY for this canary" }
        // Eagerly bind the physical owner thread id. The constructor never runs on the owner
        // thread (caller thread != single owner thread), so a blocking get here is safe and makes
        // the owner-guard valid before any operation (close-before-any-op must not self-deadlock).
        try {
            val bound = ownerExecutor.submit {
                val id = Thread.currentThread().id
                ownerThreadId.compareAndSet(UNKNOWN_THREAD, id)
                producerThreadIds.add(id)
            }
            bound.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw IllegalStateException("prefetch owner bind failed", error)
        }
    }

    override suspend fun frameAt(index: Int): DecodedTrackingFrame {
        synchronized(lifecycleLock) {
            if (closed.get()) throw IllegalStateException("MmrPrefetchFrameSource is closed")
            if (active.get()) throw IllegalStateException("concurrent frameAt/forEachFrame rejected")
            active.set(true)
        }
        try {
            // Run the seed on the owner so the seed thread is recorded even for fakes. Same
            // dispatcher object re-entry is the fast path and does not deadlock.
            val frame = withContext(ownerDispatcher) {
                val id = Thread.currentThread().id
                ownerThreadId.compareAndSet(UNKNOWN_THREAD, id)
                producerThreadIds.add(id)
                delegate.frameAt(index)
            }
            require(frame.image.width <= MAX_TRACKING_DIMENSION && frame.image.height <= MAX_TRACKING_DIMENSION) {
                "over-wide seed frame ${frame.image.width}x${frame.image.height}"
            }
            require(frame.image.pixels.size <= MAX_GRAY_BYTES) {
                "over-wide seed frame bytes ${frame.image.pixels.size}"
            }
            return frame
        } finally {
            synchronized(lifecycleLock) { active.set(false) }
        }
    }

    override suspend fun forEachFrame(
        startIndex: Int,
        onFrame: suspend (DecodedTrackingFrame) -> Boolean,
    ) {
        synchronized(lifecycleLock) {
            if (closed.get()) throw IllegalStateException("MmrPrefetchFrameSource is closed")
            if (active.get()) throw IllegalStateException("concurrent frameAt/forEachFrame rejected")
            active.set(true)
        }
        consumerThreadId.set(Thread.currentThread().id)
        // A send may transfer ownership and still throw on prompt cancellation. Idempotent
        // release keeps handoff, cancellation and drain from decrementing the same frame twice.
        val channel = Channel<OwnedFrame>(queueCapacity, onUndeliveredElement = { it.release() })
        val job = Job()
        val scope = CoroutineScope(ownerDispatcher + job)
        activeJob.set(scope.coroutineContext[Job])
        // Reset per-call counters; diagnostics reflect the last forEachFrame call.
        produced.set(0)
        consumed.set(0)
        highWaterMark.set(0)
        outstandingBytes.set(0L)
        maxOwnedBytes.set(0L)
        cancelled.set(false)
        joined.set(false)
        producerFailure.set(null)
        val producerJob = scope.launch {
            try {
                val ownerId = Thread.currentThread().id
                producerThreadIds.add(ownerId)
                ownerThreadId.compareAndSet(UNKNOWN_THREAD, ownerId)
                // Preserve the delegate's indexed-batch grouping: forward its batched forEachFrame
                // and only suspend on bounded backpressure. Never copy or alter gray arrays.
                delegate.forEachFrame(startIndex) { frame ->
                    require(frame.image.width <= MAX_TRACKING_DIMENSION && frame.image.height <= MAX_TRACKING_DIMENSION) {
                        "over-wide prefetched frame ${frame.image.width}x${frame.image.height}"
                    }
                    require(frame.image.pixels.size <= MAX_GRAY_BYTES) {
                        "over-wide prefetched frame bytes ${frame.image.pixels.size}"
                    }
                    produced.incrementAndGet()
                    val nowBytes = outstandingBytes.addAndGet(frame.image.pixels.size.toLong())
                    maxOwnedBytes.getAndUpdate { current ->
                        maxOf(current, nowBytes)
                    }
                    val outstanding = produced.get() - consumed.get()
                    highWaterMark.getAndUpdate { current -> maxOf(current, outstanding) }
                    check(outstanding in 1..MAX_OUTSTANDING_FRAMES) {
                        "owned frame bound exceeded outstanding=$outstanding"
                    }
                    check(nowBytes in 1..MAX_ADDITIONAL_BYTES) {
                        "owned byte bound exceeded bytes=$nowBytes"
                    }
                    val owned = OwnedFrame(frame) {
                        outstandingBytes.addAndGet(-frame.image.pixels.size.toLong())
                    }
                    try {
                        channel.send(owned)
                    } catch (sendFailure: Throwable) {
                        owned.release()
                        throw sendFailure
                    }
                    true
                }
                channel.close()
            } catch (cancel: CancellationException) {
                cancelled.set(true)
                channel.close(cancel)
                // Cancellation is the single error path via channel close; do not rethrow beyond
                // the scope (CancellationException from a child is benign) but preserve the flag.
                // Rethrowing cancel here is correct and does not crash (unlike failure below).
                throw cancel
            } catch (failure: Throwable) {
                producerFailure.set(failure)
                channel.close(failure)
                // Single error path is channel.close(failure); do NOT throw failure here or the
                // standalone Job routes it to the default uncaught handler and crashes the process.
                // The consumer rethrows from the channel receive path.
            }
        }
        var primary: Throwable? = null
        var keepGoing = true
        try {
            for (owned in channel) {
                val frame = owned.frame
                // Consumer-current is still owned during onFrame: record high-water before the
                // post-delivery decrement so the max includes queue + blocked-send + current.
                highWaterMark.getAndUpdate { current -> maxOf(current, produced.get() - consumed.get()) }
                try {
                    val deliver = try {
                        onFrame(frame)
                    } catch (consumerError: Throwable) {
                        primary = consumerError
                        throw consumerError
                    }
                    if (!deliver) {
                        keepGoing = false
                        break
                    }
                } finally {
                    // Decrement AFTER onFrame so maxOwnedBytes/outstanding cover the whole consumer
                    // processing window. Consumed counts deliveries (including the early-false and
                    // throwing frames) once processing finished.
                    consumed.incrementAndGet()
                    owned.release()
                }
            }
        } catch (failure: Throwable) {
            if (primary == null) primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                if (producerJob.isActive) {
                    cancelled.set(true)
                    producerJob.cancel(CancellationException("prefetch consumer finished keepGoing=$keepGoing"))
                }
                try {
                    producerJob.join()
                } finally {
                    joined.set(true)
                }
                val producerError: Throwable? = producerFailure.get()
                val primaryError: Throwable? = primary
                if (primaryError != null && producerError != null && producerError !== primaryError) {
                    primaryError.addSuppressed(producerError)
                }
                // Drain any queued gray refs so no owned array is retained past the call. Each
                // drained array decrements bytes; drained (undelivered) frames do NOT increment
                // consumed so produced > consumed stays visible as decoded-but-undelivered.
                var drained = 0
                while (drained < MAX_DRAIN_ITERATIONS) {
                    val queued = channel.tryReceive().getOrNull() ?: break
                    queued.release()
                    drained++
                }
                channel.cancel()
                try {
                    job.cancel(CancellationException("prefetch scope finished"))
                } finally {
                    activeJob.set(null)
                    synchronized(lifecycleLock) { active.set(false) }
                }
                if (outstandingBytes.get() != 0L) {
                    val leak = IllegalStateException("owned bytes leak after cleanup bytes=${outstandingBytes.get()}")
                    if (primary != null) primary.addSuppressed(leak) else throw leak
                }
            }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed.get()) return
            if (active.get()) {
                throw IllegalStateException(
                    "close while operation active rejected; cancel the caller, join via forEachFrame, then idle close",
                )
            }
            closed.set(true)
        }
        val isOwner = ownerThreadId.get() != UNKNOWN_THREAD && Thread.currentThread().id == ownerThreadId.get()
        try {
            // All source operations including close run on the owner. A synchronous close from the
            // owner thread itself must not re-dispatch (single-thread deadlock), so call directly.
            if (isOwner) {
                delegate.close()
            } else {
                runOwnerBlocking { delegate.close() }
            }
        } finally {
            ownerShutdown.set(true)
            try {
                ownerDispatcher.close()
            } catch (_: Throwable) {
                // Shutdown must not mask the delegate close outcome.
            }
            ownerExecutor.shutdown()
            if (!isOwner) {
                // Normal caller close: bounded await so termination is actually observed. Outer
                // close as a whole stays bounded (~5s dispatch + 5s termination).
                try {
                    val terminated = ownerExecutor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    ownerTerminated.set(terminated)
                } catch (_: Throwable) {
                    // Leave false; diagnostics expose live isTerminated separately.
                }
            } else {
                // Owner-thread idle close must not self-await termination (single-thread
                // self-deadlock). Termination is asserted externally after return.
                ownerTerminated.set(false)
            }
        }
    }

    fun diagnostics(): MmrPrefetchDiagnostics = MmrPrefetchDiagnostics(
        queueCapacity = queueCapacity,
        highWaterMark = highWaterMark.get(),
        maxOwnedBytes = maxOwnedBytes.get(),
        produced = produced.get(),
        consumed = consumed.get(),
        producerThreadIds = producerThreadIds.toSet(),
        consumerThreadId = consumerThreadId.get().takeIf { it != UNKNOWN_THREAD },
        ownerThreadId = ownerThreadId.get().takeIf { it != UNKNOWN_THREAD },
        cancelled = cancelled.get(),
        joined = joined.get(),
        closed = closed.get(),
        ownerShutdown = ownerShutdown.get() || ownerExecutor.isShutdown,
        ownerTerminated = ownerTerminated.get() || ownerExecutor.isTerminated,
        ownerTerminatedOrShutdown = ownerExecutor.isShutdown || ownerExecutor.isTerminated,
    )

    private fun runOwnerBlocking(block: () -> Unit) {
        var failure: Throwable? = null
        val future = ownerExecutor.submit {
            try {
                ownerThreadId.compareAndSet(UNKNOWN_THREAD, Thread.currentThread().id)
                producerThreadIds.add(Thread.currentThread().id)
                block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        try {
            future.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            if (error is java.util.concurrent.TimeoutException) {
                future.cancel(false)
            }
            val cause = failure ?: error.cause ?: error
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw IllegalStateException("prefetch close failed", cause)
        }
        failure?.let {
            if (it is RuntimeException) throw it
            if (it is Error) throw it
            throw IllegalStateException("prefetch close failed", it)
        }
    }

    private class OwnedFrame(val frame: DecodedTrackingFrame, private val onRelease: () -> Unit) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) onRelease()
        }
    }

    companion object {
        const val QUEUE_CAPACITY = 2
        const val MAX_TRACKING_DIMENSION = 720
        const val MAX_GRAY_BYTES = 720 * 720
        const val MAX_OUTSTANDING_FRAMES = 4
        const val MAX_ADDITIONAL_BYTES = 4 * 720 * 720
        const val EXISTING_MMR_ENVELOPE_BYTES = 64L * 1024L * 1024L
        const val COMBINED_IMAGE_ENVELOPE_BYTES = 66L * 1024L * 1024L
        const val ENVELOPE_NOTE = "conservative bounded image envelope, NOT an RSS/native-heap bound"
        const val CLOSE_TIMEOUT_SECONDS = 5L
        private const val UNKNOWN_THREAD = -1L
        private const val MAX_DRAIN_ITERATIONS = 8

        fun newOwnedDispatcher(name: String): Pair<ExecutorService, ExecutorCoroutineDispatcher> {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, name).also { it.isDaemon = true }
            }
            return executor to executor.asCoroutineDispatcher()
        }
    }
}

data class MmrPrefetchDiagnostics(
    val queueCapacity: Int,
    val highWaterMark: Int,
    val maxOwnedBytes: Long,
    val produced: Int,
    val consumed: Int,
    val producerThreadIds: Set<Long>,
    val consumerThreadId: Long?,
    val ownerThreadId: Long?,
    val cancelled: Boolean,
    val joined: Boolean,
    val closed: Boolean,
    val ownerShutdown: Boolean,
    val ownerTerminated: Boolean,
    val ownerTerminatedOrShutdown: Boolean,
)
