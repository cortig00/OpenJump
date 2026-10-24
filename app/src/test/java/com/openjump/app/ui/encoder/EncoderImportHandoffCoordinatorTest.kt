package com.openjump.app.ui.encoder

import com.openjump.app.video.PersistedVideoGrantStore
import com.openjump.app.video.VideoUriGrantReconciler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderImportHandoffCoordinatorTest {
    @Test
    fun detachedObserverCanBeReplacedAndNewObserverConsumesSameRequestExactlyOnce() = runBlocking {
        val uri = "content://provider/encoder-video"
        val entered = CountDownLatch(1)
        val continueAcquire = CountDownLatch(1)
        val store = FakeGrantStore(setOf(uri)) { entered.countDown(); check(continueAcquire.await(5, TimeUnit.SECONDS)) }
        val reconciler = reconciler(store)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = EncoderImportHandoffCoordinator(reconciler, ownerScope, Dispatchers.IO)
        val request = request(uri)

        assertTrue(coordinator.submit(request))
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        val oldObserver = launch(Dispatchers.Unconfined) { coordinator.state.collect { } }
        oldObserver.cancel()
        continueAcquire.countDown()

        val replacementReady = withTimeout(5_000) {
            coordinator.state.first { it is EncoderImportHandoffState.Ready }
        } as EncoderImportHandoffState.Ready
        val delivered = mutableListOf<EncoderImportRequest>()
        delivered += replacementReady.request
        assertEquals(request, replacementReady.request)
        assertEquals(listOf(request), delivered)
        assertTrue(coordinator.acknowledge(request.requestId))
        assertFalse(coordinator.acknowledge(request.requestId))
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        ownerScope.cancel()
    }

    @Test
    fun completedAcquisitionKeepsLeaseWhileReadyWaitsForUiThenAcknowledgementReleasesIt() = runBlocking {
        val uri = "content://provider/ready-waits"
        val store = FakeGrantStore(setOf(uri))
        val reconciler = reconciler(store)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = EncoderImportHandoffCoordinator(reconciler, ownerScope, Dispatchers.Unconfined)

        assertTrue(coordinator.submit(request(uri)))
        val ready = coordinator.state.value as EncoderImportHandoffState.Ready // producer completed; no UI ack yet
        reconciler.reconcile()
        assertTrue(store.released.isEmpty())
        assertTrue(coordinator.acknowledge(ready.request.requestId))
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        ownerScope.cancel()
    }

    @Test
    fun cancellationBeforeCoroutineStartClosesLeaseAndBlockedIoAbandonSuppressesReadyUntilCleanup() = runBlocking {
        val beforeUri = "content://provider/cancel-before-start"
        val beforeStore = FakeGrantStore(setOf(beforeUri))
        val beforeReconciler = reconciler(beforeStore)
        val queuedDispatcher = QueuedDispatcher()
        val beforeScope = CoroutineScope(SupervisorJob() + queuedDispatcher)
        val beforeCoordinator = EncoderImportHandoffCoordinator(beforeReconciler, beforeScope, queuedDispatcher)

        assertTrue(beforeCoordinator.submit(request(beforeUri)))
        beforeCoordinator.abandon() // Intentional clear after registration but before coroutine dispatch.
        queuedDispatcher.runQueued()
        beforeReconciler.reconcile()
        assertEquals(0, beforeStore.takeCount)
        assertEquals(listOf(beforeUri), beforeStore.released)
        assertEquals(EncoderImportHandoffState.Idle, beforeCoordinator.state.value)
        beforeScope.cancel()

        val cancelledUri = "content://provider/cancelled-parent"
        val cancelledStore = FakeGrantStore(setOf(cancelledUri))
        val cancelledReconciler = reconciler(cancelledStore)
        val cancelledScope = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.Unconfined)
        val cancelledCoordinator = EncoderImportHandoffCoordinator(cancelledReconciler, cancelledScope, Dispatchers.Unconfined)
        assertFalse(cancelledCoordinator.submit(request(cancelledUri)))
        cancelledReconciler.reconcile()
        assertEquals(listOf(cancelledUri), cancelledStore.released)

        val blockedUri = "content://provider/cancel-during-io"
        val entered = CountDownLatch(1)
        val continueAcquire = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val blockedStore = FakeGrantStore(setOf(blockedUri)) {
            entered.countDown()
            check(continueAcquire.await(5, TimeUnit.SECONDS))
            exited.countDown()
        }
        val blockedReconciler = reconciler(blockedStore)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val leaseReleases = AtomicInteger()
        val coordinator = EncoderImportHandoffCoordinator(
            blockedReconciler, ownerScope, Dispatchers.IO, onLeaseReleased = { leaseReleases.incrementAndGet() },
        )
        assertTrue(coordinator.submit(request(blockedUri, id = 2L)))
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }

        coordinator.abandon() // Same path used by explicit navigation and ViewModel.onCleared().
        assertEquals(EncoderImportHandoffState.Cancelling(2L), coordinator.state.value)
        assertFalse(coordinator.submit(request("content://provider/not-yet", id = 3L)))
        assertEquals("lease must remain while the blocking provider call is active", 0, leaseReleases.get())
        assertTrue(blockedStore.released.isEmpty())
        continueAcquire.countDown()
        withContext(Dispatchers.IO) { assertTrue(exited.await(5, TimeUnit.SECONDS)) }
        withTimeout(5_000) {
            while (blockedStore.released.isEmpty()) {
                blockedReconciler.reconcile()
                delay(10)
            }
        }
        assertEquals(listOf(blockedUri), blockedStore.released)
        assertEquals(1, leaseReleases.get())
        assertEquals(EncoderImportHandoffState.Idle, coordinator.state.value)

        val nextUri = "content://provider/after-cancel"
        assertTrue(coordinator.submit(request(nextUri, id = 3L)))
        val nextReady = withTimeout(5_000) {
            coordinator.state.first { it is EncoderImportHandoffState.Ready }
        } as EncoderImportHandoffState.Ready
        assertEquals(nextUri, nextReady.request.uri)
        assertTrue(coordinator.acknowledge(3L))
        assertEquals(EncoderImportHandoffState.Idle, coordinator.state.value)
        blockedReconciler.reconcile()
        assertEquals(listOf(blockedUri, nextUri), blockedStore.released)
        ownerScope.cancel()
    }

    @Test
    fun pickerCallbackDecisionIgnoresDuplicatesAndDoesNotStrandRejectedSubmissions() {
        var submits = 0
        val request = request("content://provider/picker-decision", id = 8L)
        val duplicate = resolveEncoderPickerCallback(
            pendingRequestId = null,
            uri = request.uri,
            request = request,
            submit = { submits++; true },
        )
        assertEquals(EncoderPickerCallbackDisposition.Ignore, duplicate)
        assertEquals(null, duplicate.startDispatchedAfterCallback)
        assertEquals(0, submits)

        val rejected = resolveEncoderPickerCallback(
            pendingRequestId = request.requestId,
            uri = request.uri,
            request = request,
            submit = { submits++; false },
        )
        assertEquals(EncoderPickerCallbackDisposition.Rejected, rejected)
        assertEquals(false, rejected.startDispatchedAfterCallback)
        assertEquals(1, submits)

        val cancelled = resolveEncoderPickerCallback(
            pendingRequestId = request.requestId,
            uri = null,
            request = null,
            submit = { submits++; true },
        )
        assertEquals(EncoderPickerCallbackDisposition.Cancel, cancelled)
        assertEquals(false, cancelled.startDispatchedAfterCallback)
        assertEquals(1, submits)
    }

    @Test
    fun explicitPickerCancelAbandonsOnlyItsCapturedRequest() = runBlocking {
        val uri = "content://provider/captured-cancel"
        val store = FakeGrantStore(setOf(uri))
        val reconciler = reconciler(store)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = EncoderImportHandoffCoordinator(reconciler, ownerScope, Dispatchers.Unconfined)
        val accepted = request(uri, id = 9L)
        assertTrue(coordinator.submit(accepted))
        assertTrue(coordinator.state.value is EncoderImportHandoffState.Ready)

        val staleCancel = resolveEncoderPickerCallback(
            pendingRequestId = 10L,
            uri = null,
            request = null,
            submit = coordinator::submit,
        )
        assertEquals(EncoderPickerCallbackDisposition.Cancel, staleCancel)
        coordinator.abandon(10L)
        assertEquals(EncoderImportHandoffState.Ready(accepted), coordinator.state.value)

        val matchingCancel = resolveEncoderPickerCallback(
            pendingRequestId = accepted.requestId,
            uri = null,
            request = null,
            submit = coordinator::submit,
        )
        assertEquals(EncoderPickerCallbackDisposition.Cancel, matchingCancel)
        coordinator.abandon(accepted.requestId)
        assertEquals(EncoderImportHandoffState.Idle, coordinator.state.value)
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        ownerScope.cancel()
    }

    @Test
    fun duplicateRequestIdIsIgnoredBeforeAndAfterHandoff() = runBlocking {
        val uri = "content://provider/duplicate"
        val store = FakeGrantStore(setOf(uri))
        val reconciler = reconciler(store)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = EncoderImportHandoffCoordinator(reconciler, ownerScope, Dispatchers.Unconfined)
        val request = request(uri)

        assertTrue(coordinator.submit(request))
        assertFalse(coordinator.submit(request))
        assertTrue(coordinator.state.value is EncoderImportHandoffState.Ready)
        val ready = coordinator.state.value as EncoderImportHandoffState.Ready
        assertTrue(coordinator.acknowledge(ready.request.requestId))
        assertFalse(coordinator.submit(request))
        assertEquals(1, store.takeCount)
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        ownerScope.cancel()
    }

    private fun request(uri: String, id: Long = 1L) = EncoderImportRequest(
        requestId = id,
        uri = uri,
        exerciseName = "BENCH_PRESS",
        loadKg = 62.5,
        athleteId = 17L,
    )

    private fun reconciler(store: FakeGrantStore) = VideoUriGrantReconciler(
        grants = store,
        savedReferences = { emptySet() },
        liveReferences = { emptySet() },
        diagnostic = {},
    )

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = java.util.ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queued) { queued.addLast(block) }
        }

        fun runQueued() {
            while (true) {
                val next = synchronized(queued) { if (queued.isEmpty()) null else queued.removeFirst() } ?: return
                next.run()
            }
        }
    }

    private class FakeGrantStore(
        initial: Set<String>,
        private val onTake: (() -> Unit)? = null,
    ) : PersistedVideoGrantStore {
        private val lock = Any()
        private val persisted = initial.toMutableSet()
        val released = mutableListOf<String>()
        var takeCount = 0
            private set

        override fun persistedReadUris(): Set<String> = synchronized(lock) { persisted.toSet() }

        override fun takeReadGrant(uri: String) {
            synchronized(lock) { takeCount += 1 }
            onTake?.invoke()
            synchronized(lock) { persisted += uri }
        }

        override fun releaseReadGrant(uri: String) {
            synchronized(lock) {
                persisted -= uri
                released += uri
            }
        }
    }
}
