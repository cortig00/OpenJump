package com.openjump.app.video

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUriGrantReconcilerTest {
    @Test
    fun retainsSharedAttemptAndEncoderSavedReferencesThenReleasesAfterLastDelete() = runBlocking {
        val uri = "content://provider/shared"
        val provider = FakeGrantStore(setOf(uri))
        var saved = setOf(uri) // Union of Attempt.videoUri and EncoderSession.videoUri.
        val reconciler = reconciler(provider, { saved })

        reconciler.reconcile()
        assertTrue(provider.released.isEmpty())
        saved = emptySet()
        reconciler.reconcile()
        assertEquals(listOf(uri), provider.released)
    }

    @Test
    fun queriesSavedUnionExactlyOnceForMultiplePersistedUris() = runBlocking {
        val uris = (1..3).map { "content://provider/$it" }.toSet()
        val provider = FakeGrantStore(uris)
        var savedQueries = 0
        val reconciler = reconciler(provider, {
            savedQueries += 1
            emptySet()
        })

        reconciler.reconcile()

        assertEquals(1, savedQueries)
        assertEquals(uris, provider.released.toSet())
    }

    @Test
    fun retainsHiddenAndPendingLiveOwnersAndIgnoresNonReadOrNonContentGrants() = runBlocking {
        val uri = "content://provider/shared"
        val fileUri = "file:///private/video.mp4"
        val provider = FakeGrantStore(setOf(uri, fileUri))
        var hiddenBilateralDraftAndSnapshot = setOf(uri)
        val reconciler = reconciler(provider, live = { hiddenBilateralDraftAndSnapshot })
        val pendingImport = reconciler.registerOwner(setOf(uri))

        reconciler.reconcile()
        assertTrue(provider.released.isEmpty())
        hiddenBilateralDraftAndSnapshot = emptySet()
        reconciler.reconcile()
        assertTrue(provider.released.isEmpty())
        pendingImport.close()
        reconciler.reconcile()
        assertEquals(listOf(uri), provider.released)
        assertFalse(fileUri in provider.released)
    }

    @Test
    fun pendingSaveLeaseProtectsResetBeforeRoomCommitAndCommitOwnsAfterward() = runBlocking {
        val uri = "content://provider/saving"
        val provider = FakeGrantStore(setOf(uri))
        val saved = AtomicReference(emptySet<String>())
        val reconciler = reconciler(provider, { saved.get() })
        val pendingSave = reconciler.registerOwner(setOf(uri)) // before async save/IO handoff
        val commitReached = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val save = async(Dispatchers.Default) {
            commitReached.complete(Unit)
            allowCommit.await()
            saved.set(setOf(uri))
        }
        commitReached.await()
        reconciler.reconcile() // AppSession reset already cleared the live draft.
        assertTrue(provider.released.isEmpty())
        allowCommit.complete(Unit)
        save.await()
        pendingSave.close()
        reconciler.reconcile()
        assertTrue(provider.released.isEmpty())
    }

    @Test
    fun successfulRestoreThenSessionResetRemovesFormerSavedAndLiveOwners() = runBlocking {
        val uri = "content://provider/restored-away"
        val provider = FakeGrantStore(setOf(uri))
        var savedBeforeRestore = setOf(uri)
        var activeSession = setOf(uri)
        val reconciler = reconciler(provider, { savedBeforeRestore }, { activeSession })

        reconciler.reconcile()
        assertTrue(provider.released.isEmpty())
        // ManualBackupViewModel resets AppSession only after replaceAll succeeds.
        savedBeforeRestore = emptySet()
        activeSession = emptySet()
        reconciler.reconcile()
        assertEquals(listOf(uri), provider.released)
    }

    @Test
    fun registrationAndCloseStayPromptWhileProviderReleaseIsBlocked() = runBlocking {
        val uri = "content://provider/releasing"
        val releaseEntered = CountDownLatch(1)
        val continueRelease = CountDownLatch(1)
        val provider = FakeGrantStore(setOf(uri), onRelease = { _ ->
            releaseEntered.countDown()
            check(continueRelease.await(5, TimeUnit.SECONDS))
        })
        val reconciler = reconciler(provider)
        val sweep = async(Dispatchers.Default) { reconciler.reconcile() }
        withContext(Dispatchers.IO) { check(releaseEntered.await(5, TimeUnit.SECONDS)) }

        val ownerLease = withTimeout(1_000) {
            async(Dispatchers.IO) { reconciler.registerOwner(setOf("content://provider/other")) }.await()
        }
        withTimeout(1_000) { async(Dispatchers.IO) { ownerLease.close() }.await() }

        continueRelease.countDown()
        sweep.await()
    }

    @Test
    fun ownerRegisteredDuringReleaseIsRestoredBeforeSerializedAcquireCompletes() = runBlocking {
        val uri = "content://provider/pending-acquire"
        val releaseEntered = CountDownLatch(1)
        val continueRelease = CountDownLatch(1)
        val provider = FakeGrantStore(setOf(uri), onRelease = { _ ->
            releaseEntered.countDown()
            check(continueRelease.await(5, TimeUnit.SECONDS))
        })
        val reconciler = reconciler(provider)
        val sweep = async(Dispatchers.Default) { reconciler.reconcile() }
        withContext(Dispatchers.IO) { check(releaseEntered.await(5, TimeUnit.SECONDS)) }

        val pending = reconciler.registerOwner(setOf(uri))
        val acquire = async(Dispatchers.Default) { reconciler.takeReadGrant(uri) }
        continueRelease.countDown()
        sweep.await()
        acquire.await()

        assertTrue(uri in provider.granted)
        assertEquals(2, provider.takeCount)
        pending.close()
    }

    @Test
    fun saveThatCommitsAndClosesDuringReleaseIsRestoredDespiteEmptyCachedSavedSet() = runBlocking {
        val uri = "content://provider/save-during-release"
        val releaseEntered = CountDownLatch(1)
        val continueRelease = CountDownLatch(1)
        val provider = FakeGrantStore(setOf(uri), onRelease = { _ ->
            releaseEntered.countDown()
            check(continueRelease.await(5, TimeUnit.SECONDS))
        })
        val saved = AtomicReference(emptySet<String>())
        val reconciler = reconciler(provider, { saved.get() })
        val sweep = async(Dispatchers.Default) { reconciler.reconcile() }
        withContext(Dispatchers.IO) { check(releaseEntered.await(5, TimeUnit.SECONDS)) }

        val commitAndClose = async(Dispatchers.IO) {
            val lease = reconciler.registerOwner(setOf(uri))
            saved.set(setOf(uri))
            lease.close()
        }
        withTimeout(1_000) { commitAndClose.await() }
        continueRelease.countDown()
        sweep.await()

        assertTrue(uri in provider.granted)
        assertEquals(1, provider.takeCount)
    }

    @Test
    fun mutationForLaterCandidateInvalidatesCachedSavedSnapshotAfterEarlierRelease() = runBlocking {
        val firstUri = "content://provider/a-first"
        val savedByConcurrentSave = "content://provider/b-saved"
        val releaseEntered = CountDownLatch(1)
        val continueRelease = CountDownLatch(1)
        val firstRelease = java.util.concurrent.atomic.AtomicBoolean(true)
        val provider = FakeGrantStore(linkedSetOf(firstUri, savedByConcurrentSave), onRelease = { uri ->
            if (uri == firstUri && firstRelease.compareAndSet(true, false)) {
                releaseEntered.countDown()
                check(continueRelease.await(5, TimeUnit.SECONDS))
            }
        })
        val saved = AtomicReference(emptySet<String>())
        var savedQueries = 0
        val reconciler = reconciler(provider, {
            savedQueries += 1
            saved.get()
        })
        val pendingSave = reconciler.registerOwner(setOf(savedByConcurrentSave))
        val sweep = async(Dispatchers.Default) { reconciler.reconcile() }
        withContext(Dispatchers.IO) { check(releaseEntered.await(5, TimeUnit.SECONDS)) }

        val commitAndClose = async(Dispatchers.IO) {
            saved.set(setOf(savedByConcurrentSave))
            pendingSave.close()
        }
        withTimeout(1_000) { commitAndClose.await() }
        continueRelease.countDown()
        sweep.await()

        // The generation change restores A and aborts before stale saved=empty can release B.
        assertTrue(firstUri in provider.granted)
        assertTrue(savedByConcurrentSave in provider.granted)
        assertEquals(1, savedQueries)
        reconciler.reconcile()
        assertFalse(firstUri in provider.granted)
        assertTrue(savedByConcurrentSave in provider.granted)
        assertEquals(2, savedQueries)
    }

    @Test
    fun cancelledTransientOwnerDuringReleaseIsRestoredThenCleanableOnNextSweep() = runBlocking {
        val uri = "content://provider/cancelled-owner"
        val releaseEntered = CountDownLatch(1)
        val continueRelease = CountDownLatch(1)
        val provider = FakeGrantStore(setOf(uri), onRelease = { _ ->
            releaseEntered.countDown()
            check(continueRelease.await(5, TimeUnit.SECONDS))
        })
        val reconciler = reconciler(provider)
        val sweep = async(Dispatchers.Default) { reconciler.reconcile() }
        withContext(Dispatchers.IO) { check(releaseEntered.await(5, TimeUnit.SECONDS)) }

        val cancelledOwner = reconciler.registerOwner(setOf(uri))
        cancelledOwner.close()
        continueRelease.countDown()
        sweep.await()
        assertTrue(uri in provider.granted)

        reconciler.reconcile()
        assertFalse(uri in provider.granted)
    }

    @Test
    fun cancelledBeforeStartLaunchStillClosesOwnedLease() = runBlocking {
        val uri = "content://provider/cancelled-launch"
        val provider = FakeGrantStore(setOf(uri))
        val reconciler = reconciler(provider)
        val parent = Job().apply { cancel() }
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        var bodyStarted = false
        val lease = reconciler.registerOwner(setOf(uri))

        val job = scope.launchWithLease(lease) { bodyStarted = true }
        job.join()
        reconciler.reconcile()

        assertFalse(bodyStarted)
        assertEquals(listOf(uri), provider.released)
    }

    @Test
    fun savedQueryFailureAndProviderDenialFailClosedAndCancellationPropagates() = runBlocking {
        val uri = "content://provider/retained"
        val provider = FakeGrantStore(setOf(uri))
        val queryFailure = reconciler(provider, savedReferences = { error("database unavailable") })
        queryFailure.reconcile()
        assertTrue(provider.released.isEmpty())

        provider.denyList = true
        reconciler(provider).reconcile()
        assertTrue(provider.released.isEmpty())

        provider.denyList = false
        provider.denyRelease = true
        reconciler(provider).reconcile()
        assertTrue(provider.released.isEmpty())

        provider.denyRelease = false
        var cancelled = false
        try {
            reconciler(provider, savedReferences = { throw CancellationException("cancel") }).reconcile()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(provider.released.isEmpty())
    }

    @Test
    fun providerTakeDenialDoesNotPreventImportOwnerRegistration() = runBlocking {
        val uri = "content://provider/denied"
        val provider = FakeGrantStore()
        provider.denyTake = true
        val reconciler = reconciler(provider)
        val pending = reconciler.registerOwner(setOf(uri))
        reconciler.takeReadGrant(uri)
        reconciler.reconcile()
        assertTrue(provider.released.isEmpty())
        pending.close()
    }

    private fun reconciler(
        provider: FakeGrantStore,
        savedReferences: suspend () -> Set<String> = { emptySet() },
        live: () -> Set<String> = { emptySet() },
    ) = VideoUriGrantReconciler(provider, savedReferences, live, diagnostic = {})

    private class FakeGrantStore(
        initial: Set<String> = emptySet(),
        private val onList: () -> Unit = {},
        private val onRelease: (String) -> Unit = {},
    ) : PersistedVideoGrantStore {
        val granted = Collections.synchronizedSet(initial.toMutableSet())
        val released = Collections.synchronizedList(mutableListOf<String>())
        var denyList = false
        var denyTake = false
        var denyRelease = false
        var takeCount = 0

        override fun persistedReadUris(): Set<String> {
            onList()
            if (denyList) throw SecurityException()
            return synchronized(granted) { granted.toSet() }
        }

        override fun takeReadGrant(uri: String) {
            if (denyTake) throw SecurityException()
            synchronized(granted) { granted += uri }
            takeCount += 1
        }

        override fun releaseReadGrant(uri: String) {
            onRelease(uri)
            if (denyRelease) throw SecurityException()
            synchronized(granted) { granted -= uri }
            released += uri
        }
    }
}
