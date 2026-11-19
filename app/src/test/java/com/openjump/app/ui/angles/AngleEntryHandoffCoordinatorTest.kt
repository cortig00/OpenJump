package com.openjump.app.ui.angles

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleEntryHandoffCoordinatorTest {

    @Test
    fun frozenAthleteSurvivesLaterGlobalAndSelectionChangesAndStaysAPrimitive() = runBlocking {
        val uri = "content://provider/angles-import"
        val store = FakeGrantStore(setOf(uri))
        val reconciler = reconciler(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val persisted = mutableListOf<Long?>()
        val coordinator = coordinator(reconciler, scope, initial = null, persisted = persisted)

        coordinator.onGlobalAthleteResolved(null) // Loading global must not freeze a null choice.
        assertNull(coordinator.selectedAthleteId.value)
        assertFalse(coordinator.canBegin())
        assertNull(coordinator.begin(AngleEntryKind.IMPORT))

        coordinator.onGlobalAthleteResolved(17L)
        assertEquals(17L, coordinator.selectedAthleteId.value)
        coordinator.onGlobalAthleteResolved(23L) // A later global change cannot retarget the intro.
        assertEquals(17L, coordinator.selectedAthleteId.value)
        assertTrue(coordinator.canBegin())

        val ticket = coordinator.begin(AngleEntryKind.IMPORT)!!
        assertEquals(17L, ticket.athleteId)
        coordinator.selectAthlete(23L) // Choosing B after launching must not retarget request A.
        assertEquals(AngleEntryResultDisposition.Accepted, coordinator.completeResult(ticket.originToken, uri))
        val ready = coordinator.state.first { it is AngleEntryHandoffState.Ready } as AngleEntryHandoffState.Ready
        assertEquals(17L, ready.transfer.athleteId)
        assertEquals(uri, ready.transfer.uri)
        assertEquals(AngleEntryKind.IMPORT, ready.transfer.kind)
        assertEquals(ticket.originToken, ready.transfer.originToken)
        assertEquals(ticket.requestId, ready.transfer.requestId)
        assertEquals(listOf(17L, 23L), persisted)

        assertEquals(ready.transfer, coordinator.claimReady(ticket.originToken))
        coordinator.returnToIntro(ticket.originToken)
        assertEquals(AngleEntryHandoffState.Idle, coordinator.state.value)

        // Record keeps the same frozen-owner rule and the explicit selection B.
        val record = coordinator.begin(AngleEntryKind.RECORD)!!
        assertEquals(23L, record.athleteId)
        assertEquals(AngleEntryKind.RECORD, record.kind)
        assertNotEquals(ticket.originToken, record.originToken)
        assertEquals(AngleEntryResultDisposition.Accepted, coordinator.completeResult(record.originToken, uri))
        val recordReady = coordinator.state.first { it is AngleEntryHandoffState.Ready } as AngleEntryHandoffState.Ready
        assertEquals(23L, recordReady.transfer.athleteId)
        assertEquals(AngleEntryKind.RECORD, recordReady.transfer.kind)
        coordinator.claimReady(record.originToken)
        coordinator.returnToIntro(record.originToken)
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        scope.cancel()
    }

    @Test
    fun doubleTapDuplicateStaleAndClosedRequestsHandOffAtMostOnce() = runBlocking {
        val uri = "content://provider/angles-once"
        val store = FakeGrantStore(setOf(uri))
        val reconciler = reconciler(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(reconciler, scope)

        val ticket = coordinator.begin(AngleEntryKind.IMPORT)!!
        assertNull(coordinator.begin(AngleEntryKind.IMPORT)) // Single flight: second tap emits nothing.
        assertEquals(AngleEntryResultDisposition.Accepted, coordinator.completeResult(ticket.originToken, uri))
        assertEquals(AngleEntryResultDisposition.Ignored, coordinator.completeResult(ticket.originToken, uri))
        assertNotNull(coordinator.claimReady(ticket.originToken))
        assertNull(coordinator.claimReady(ticket.originToken)) // Ready is consumed exactly once.
        assertNull(coordinator.claimReady("someone-else"))
        assertEquals(AngleEntryResultDisposition.Ignored, coordinator.completeResult(ticket.originToken, uri))
        coordinator.returnToIntro(ticket.originToken)

        val second = coordinator.begin(AngleEntryKind.IMPORT)!!
        // A stale result bound to the previous (closed by return) request never registers or navigates.
        assertEquals(
            AngleEntryResultDisposition.Ignored,
            coordinator.completeResult(ticket.originToken, "content://provider/stale"),
        )
        assertTrue(coordinator.state.value is AngleEntryHandoffState.AwaitingCapture)
        assertEquals(1, store.takeCount)

        coordinator.cancel(second.originToken)
        assertEquals(AngleEntryHandoffState.Idle, coordinator.state.value)
        assertEquals(
            AngleEntryResultDisposition.Ignored,
            coordinator.completeResult(second.originToken, "content://provider/after-cancel"),
        )

        coordinator.close()
        assertNull(coordinator.begin(AngleEntryKind.IMPORT))
        assertEquals(
            AngleEntryResultDisposition.Ignored,
            coordinator.completeResult(second.originToken, uri),
        )
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        scope.cancel()
    }

    @Test
    fun nullPickerOrCameraCancelKeepsTheChoiceAndNeverTakesAGrant() = runBlocking {
        val uri = "content://provider/angles-cancel"
        val store = FakeGrantStore(emptySet()) // Nothing persisted yet: a cancel must not take or release.
        val reconciler = reconciler(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(reconciler, scope)

        val pickerTicket = coordinator.begin(AngleEntryKind.IMPORT)!!
        assertEquals(
            AngleEntryResultDisposition.Cancelled,
            coordinator.completeResult(pickerTicket.originToken, null),
        )
        assertEquals(AngleEntryHandoffState.Idle, coordinator.state.value)
        assertEquals(17L, coordinator.selectedAthleteId.value)
        // A late URI for the cancelled picker is inert: no owner, no grant take, no Ready.
        assertEquals(
            AngleEntryResultDisposition.Ignored,
            coordinator.completeResult(pickerTicket.originToken, uri),
        )
        assertEquals(0, store.takeCount)

        val cameraTicket = coordinator.begin(AngleEntryKind.RECORD)!!
        coordinator.cancel(cameraTicket.originToken)
        assertEquals(AngleEntryHandoffState.Idle, coordinator.state.value)
        assertEquals(17L, coordinator.selectedAthleteId.value)
        assertEquals(
            AngleEntryResultDisposition.Ignored,
            coordinator.completeResult(cameraTicket.originToken, uri),
        )
        assertEquals(0, store.takeCount)
        assertTrue(store.released.isEmpty())
        reconciler.reconcile()
        assertTrue(store.released.isEmpty())
        scope.cancel()
    }

    @Test
    fun archivedDeletedOrFailedQueryRejectsWithoutFallingBackToTheGlobalAthlete() = runBlocking {
        val store = FakeGrantStore(setOf())
        val reconciler = reconciler(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var mode = 0 // 0 = active, 1 = archived/deleted, 2 = repository failure
        val queryFailure = IllegalStateException("athlete query failed")
        val checkedIds = mutableListOf<Long>()
        val coordinator = coordinator(
            reconciler,
            scope,
            validator = AngleEntryAthleteValidator { id ->
                checkedIds += id
                when (mode) {
                    1 -> false
                    2 -> throw queryFailure
                    else -> true
                }
            },
        )

        mode = 1
        val archivedUri = "content://provider/angles-archived"
        val first = coordinator.begin(AngleEntryKind.IMPORT)!!
        coordinator.selectAthlete(23L)
        coordinator.completeResult(first.originToken, archivedUri)
        val rejected = coordinator.state.first { it is AngleEntryHandoffState.Rejected } as AngleEntryHandoffState.Rejected
        assertEquals(AngleEntryError.ATHLETE_UNAVAILABLE, rejected.error)
        assertEquals(first.requestId, rejected.requestId)
        assertEquals(listOf(17L), checkedIds) // The frozen athlete is revalidated, never the new selection.
        assertEquals(23L, coordinator.selectedAthleteId.value)
        reconciler.reconcile()
        assertEquals(listOf(archivedUri), store.released) // No Ready, lease released.

        mode = 2
        val failedUri = "content://provider/angles-query-failure"
        val second = coordinator.begin(AngleEntryKind.RECORD)!!
        coordinator.completeResult(second.originToken, failedUri)
        val failed = coordinator.state.first { it is AngleEntryHandoffState.Rejected } as AngleEntryHandoffState.Rejected
        assertEquals(AngleEntryError.ATHLETE_VALIDATION_FAILED, failed.error)
        reconciler.reconcile()
        assertEquals(listOf(archivedUri, failedUri), store.released)

        // An empty/invalid active roster disables start instead of inventing an athlete.
        coordinator.onActiveAthletesResolved(emptySet())
        assertNull(coordinator.selectedAthleteId.value)
        assertFalse(coordinator.canBegin())
        assertNull(coordinator.begin(AngleEntryKind.IMPORT))
        scope.cancel()
    }

    @Test
    fun ownerIsRegisteredBeforeGrantTakeAndHeldThroughReadyHandedOffAndEditorTakeover() = runBlocking {
        val uri = "content://provider/angles-lease"
        val store = FakeGrantStore(setOf(uri))
        var saved: Set<String> = emptySet()
        val live = mutableSetOf<String>()
        val reconciler = VideoUriGrantReconciler(
            grants = store,
            savedReferences = { saved },
            liveReferences = { live.toSet() },
            diagnostic = {},
        )
        val queued = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + queued)
        val coordinator = coordinator(reconciler, scope, queued)

        val ticket = coordinator.begin(AngleEntryKind.IMPORT)!!
        assertEquals(AngleEntryResultDisposition.Accepted, coordinator.completeResult(ticket.originToken, uri))
        assertEquals("owner must exist before the dispatcher runs", 0, store.takeCount)
        reconciler.reconcile()
        assertTrue(store.released.isEmpty()) // Ownership registered synchronously, before take.

        queued.runQueued()
        assertEquals(1, store.takeCount)
        assertTrue(coordinator.state.value is AngleEntryHandoffState.Ready)
        reconciler.reconcile()
        assertTrue("Ready keeps the lease", store.released.isEmpty())

        val transfer = coordinator.claimReady(ticket.originToken)!!
        assertEquals(uri, transfer.uri)
        assertEquals(17L, transfer.athleteId)
        assertTrue(coordinator.state.value is AngleEntryHandoffState.HandedOff)
        reconciler.reconcile()
        assertTrue("HandedOff keeps the lease", store.released.isEmpty())

        // AngleEditorViewModel.open registers its owner only after it has been launched.
        val editorLease = reconciler.registerOwner(setOf(uri))
        coordinator.returnToIntro(ticket.originToken)
        assertEquals(AngleEntryHandoffState.Idle, coordinator.state.value)
        reconciler.reconcile()
        assertTrue("editor owner keeps the grant alive after intake release", store.released.isEmpty())
        assertTrue(uri in store.persistedReadUris())

        // A committed Room reference protects the grant even after the editor lease closes.
        saved = setOf(uri)
        editorLease.close()
        reconciler.reconcile()
        assertTrue(store.released.isEmpty())

        saved = emptySet()
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        scope.cancel()
    }

    @Test
    fun pausedOrReplacedObserverSeesTheSameReadyOnce() = runBlocking {
        val uri = "content://provider/angles-observer"
        val entered = CountDownLatch(1)
        val continueAcquire = CountDownLatch(1)
        val store = FakeGrantStore(setOf(uri)) {
            entered.countDown()
            check(continueAcquire.await(5, TimeUnit.SECONDS))
        }
        val reconciler = reconciler(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(reconciler, scope, Dispatchers.IO)

        val ticket = coordinator.begin(AngleEntryKind.IMPORT)!!
        assertEquals(AngleEntryResultDisposition.Accepted, coordinator.completeResult(ticket.originToken, uri))
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        val oldObserver = launch(Dispatchers.Unconfined) { coordinator.state.collect { } }
        oldObserver.cancel()
        continueAcquire.countDown()

        val ready = withTimeout(5_000) {
            coordinator.state.first { it is AngleEntryHandoffState.Ready }
        } as AngleEntryHandoffState.Ready
        assertEquals(uri, ready.transfer.uri)
        assertEquals(ready.transfer, coordinator.claimReady(ticket.originToken))
        assertNull(coordinator.claimReady(ticket.originToken))
        coordinator.returnToIntro(ticket.originToken)
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        scope.cancel()
    }

    @Test
    fun cancelBeforeStartCancelledScopeAndBlockedProviderIoCloseOnceWithoutReady() = runBlocking {
        // (a) Intentional cancel between registration and the lazy coroutine actually starting.
        val beforeUri = "content://provider/angles-before-start"
        val beforeStore = FakeGrantStore(setOf(beforeUri))
        val beforeReconciler = reconciler(beforeStore)
        val queued = QueuedDispatcher()
        val beforeScope = CoroutineScope(SupervisorJob() + queued)
        val beforeReleases = AtomicInteger()
        val beforeCoordinator = coordinator(beforeReconciler, beforeScope, queued, released = beforeReleases)
        val beforeTicket = beforeCoordinator.begin(AngleEntryKind.IMPORT)!!
        assertEquals(
            AngleEntryResultDisposition.Accepted,
            beforeCoordinator.completeResult(beforeTicket.originToken, beforeUri),
        )
        beforeCoordinator.cancel(beforeTicket.originToken)
        queued.runQueued()
        beforeReconciler.reconcile()
        assertEquals(0, beforeStore.takeCount)
        assertEquals(listOf(beforeUri), beforeStore.released)
        assertEquals(1, beforeReleases.get())
        assertEquals(AngleEntryHandoffState.Idle, beforeCoordinator.state.value)
        beforeScope.cancel()

        // (b) The owner scope was already cancelled when the URI arrives.
        val cancelledUri = "content://provider/angles-cancelled-scope"
        val cancelledStore = FakeGrantStore(setOf(cancelledUri))
        val cancelledReconciler = reconciler(cancelledStore)
        val cancelledScope = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.Unconfined)
        val cancelledCoordinator = coordinator(cancelledReconciler, cancelledScope, Dispatchers.Unconfined)
        val cancelledTicket = cancelledCoordinator.begin(AngleEntryKind.IMPORT)!!
        assertEquals(
            AngleEntryResultDisposition.Ignored,
            cancelledCoordinator.completeResult(cancelledTicket.originToken, cancelledUri),
        )
        cancelledReconciler.reconcile()
        assertEquals(listOf(cancelledUri), cancelledStore.released)
        assertEquals(AngleEntryHandoffState.Idle, cancelledCoordinator.state.value)

        // (c) A blocking provider call keeps the lease, the non-Idle state and the single-flight slot.
        val blockedUri = "content://provider/angles-blocked-io"
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
        val blockedCoordinator = coordinator(blockedReconciler, ownerScope, Dispatchers.IO, released = leaseReleases)
        val blockedTicket = blockedCoordinator.begin(AngleEntryKind.IMPORT)!!
        assertEquals(
            AngleEntryResultDisposition.Accepted,
            blockedCoordinator.completeResult(blockedTicket.originToken, blockedUri),
        )
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }

        blockedCoordinator.cancel(blockedTicket.originToken)
        assertEquals(AngleEntryHandoffState.Releasing(blockedTicket.requestId), blockedCoordinator.state.value)
        assertNull(blockedCoordinator.begin(AngleEntryKind.IMPORT))
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
        assertEquals(AngleEntryHandoffState.Idle, blockedCoordinator.state.value)

        // A replacement request is accepted only after the previous cleanup finished.
        val nextUri = "content://provider/angles-after-cancel"
        val next = blockedCoordinator.begin(AngleEntryKind.RECORD)!!
        assertEquals(AngleEntryResultDisposition.Accepted, blockedCoordinator.completeResult(next.originToken, nextUri))
        val nextReady = withTimeout(5_000) {
            blockedCoordinator.state.first { it is AngleEntryHandoffState.Ready }
        } as AngleEntryHandoffState.Ready
        assertEquals(nextUri, nextReady.transfer.uri)
        ownerScope.cancel()
    }

    @Test
    fun returnToIntroOnlyFinalizesHandedOffAndNeverCancelsAWaitingPicker() = runBlocking {
        val uri = "content://provider/angles-return"
        val store = FakeGrantStore(setOf(uri))
        val reconciler = reconciler(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(reconciler, scope)

        val ticket = coordinator.begin(AngleEntryKind.IMPORT)!!
        // ON_RESUME of the intro happens while the photo picker is still open: nothing may be cancelled.
        coordinator.returnToIntro()
        assertEquals(AngleEntryHandoffState.AwaitingCapture(ticket), coordinator.state.value)
        assertEquals(AngleEntryResultDisposition.Accepted, coordinator.completeResult(ticket.originToken, uri))

        // Ready but unclaimed: a return to the intro must not drop the transfer before the claim.
        coordinator.returnToIntro()
        assertTrue(coordinator.state.value is AngleEntryHandoffState.Ready)
        assertNotNull(coordinator.claimReady(ticket.originToken))

        coordinator.returnToIntro(ticket.originToken) // Real return once the editor is closed.
        assertEquals(AngleEntryHandoffState.Idle, coordinator.state.value)
        coordinator.returnToIntro() // Idempotent.
        coordinator.cancel()
        coordinator.close()
        coordinator.close()
        assertNull(coordinator.begin(AngleEntryKind.IMPORT))
        assertEquals(
            AngleEntryResultDisposition.Ignored,
            coordinator.completeResult(ticket.originToken, uri),
        )
        reconciler.reconcile()
        assertEquals(listOf(uri), store.released)
        scope.cancel()
    }

    @Test
    fun invalidatedAutoSelectionNeverAdoptsAnotherGlobalAthleteSilently() = runBlocking {
        val store = FakeGrantStore(emptySet())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val persisted = mutableListOf<Long?>()
        val coordinator = coordinator(reconciler(store), scope, initial = null, persisted = persisted)

        // The global preselection A is adopted while the roster is still loading.
        coordinator.onGlobalAthleteResolved(17L)
        assertEquals(17L, coordinator.selectedAthleteId.value)
        assertTrue(coordinator.canBegin())

        // A is archived/deleted: the intro must ask for another athlete, never re-pick one.
        coordinator.onActiveAthletesResolved(setOf(23L))
        assertNull(coordinator.selectedAthleteId.value)
        assertEquals(listOf(17L, null), persisted)
        assertFalse(coordinator.canBegin())
        assertNull(coordinator.begin(AngleEntryKind.IMPORT))

        // A repeated global resolution for B is not a user choice and must not retarget the intro.
        coordinator.onGlobalAthleteResolved(23L)
        coordinator.onGlobalAthleteResolved(23L)
        assertNull(coordinator.selectedAthleteId.value)
        assertEquals(listOf(17L, null), persisted)
        assertFalse(coordinator.canBegin())
        assertNull(coordinator.begin(AngleEntryKind.RECORD))

        // Only an explicit choice re-enables start.
        assertTrue(coordinator.selectAthlete(23L))
        assertEquals(23L, coordinator.selectedAthleteId.value)
        assertEquals(listOf(17L, null, 23L), persisted)
        assertEquals(23L, coordinator.begin(AngleEntryKind.IMPORT)!!.athleteId)
        scope.cancel()
    }

    @Test
    fun restoredSavedSelectionInvalidationDoesNotFallBackAndTheInflightRequestKeepsItsOwner() = runBlocking {
        val uri = "content://provider/angles-restored"
        val store = FakeGrantStore(setOf(uri))
        val reconciler = reconciler(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val persisted = mutableListOf<Long?>()
        val checkedIds = mutableListOf<Long>()
        // Saved instance state restored A after process re-creation.
        val coordinator = coordinator(
            reconciler,
            scope,
            validator = AngleEntryAthleteValidator { id ->
                checkedIds += id
                true
            },
            initial = 17L,
            persisted = persisted,
        )

        val ticket = coordinator.begin(AngleEntryKind.IMPORT)!!
        assertEquals(17L, ticket.athleteId)

        // A leaves the active roster while the picker for A is still open.
        coordinator.onActiveAthletesResolved(setOf(23L))
        assertNull(coordinator.selectedAthleteId.value)
        assertEquals(listOf<Long?>(null), persisted)

        // A later global resolution for B is not an explicit choice: the intro stays empty.
        coordinator.onGlobalAthleteResolved(23L)
        assertNull(coordinator.selectedAthleteId.value)
        assertFalse(coordinator.canBegin())

        // The request already in flight keeps its frozen owner A, never the new global B.
        assertEquals(AngleEntryResultDisposition.Accepted, coordinator.completeResult(ticket.originToken, uri))
        val ready = coordinator.state.first { it is AngleEntryHandoffState.Ready } as AngleEntryHandoffState.Ready
        assertEquals(17L, ready.transfer.athleteId)
        assertEquals(listOf(17L), checkedIds)
        assertEquals(ready.transfer, coordinator.claimReady(ticket.originToken))
        coordinator.returnToIntro(ticket.originToken)
        assertEquals(AngleEntryHandoffState.Idle, coordinator.state.value)

        // Only an explicit choice makes a new request possible.
        assertTrue(coordinator.selectAthlete(23L))
        assertEquals(23L, coordinator.begin(AngleEntryKind.RECORD)!!.athleteId)
        scope.cancel()
    }

    @Test
    fun closedOwnerIgnoresALateRosterResolutionAndKeepsThePersistedSelection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val persisted = mutableListOf<Long?>()
        val coordinator = coordinator(
            reconciler(FakeGrantStore(emptySet())),
            scope,
            initial = 17L,
            persisted = persisted,
        )

        coordinator.close()
        // The entry is gone: a stale roster emission must not mutate or re-persist the selection.
        coordinator.onActiveAthletesResolved(emptySet())
        assertEquals(17L, coordinator.selectedAthleteId.value)
        assertEquals(emptyList<Long?>(), persisted)
        assertFalse(coordinator.canBegin())
        scope.cancel()
    }

    @Test
    fun rejectionKeepsTheSingleFlightSlotUntilLeaseCleanupFinishesThenPublishesItsError() = runBlocking {
        // (a) The frozen athlete is archived: reject branch.
        val archivedUri = "content://provider/angles-reject-cleanup"
        val store = FakeGrantStore(setOf(archivedUri))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var coordinator: AngleEntryHandoffCoordinator
        var stateDuringCleanup: AngleEntryHandoffState? = null
        var canBeginDuringCleanup: Boolean? = null
        var beginDuringCleanup: AngleEntryTicket? = null
        val releases = AtomicInteger()
        coordinator = AngleEntryHandoffCoordinator(
            grants = reconciler(store),
            athleteValidator = AngleEntryAthleteValidator { false },
            scope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            initialSelectedAthleteId = 17L,
            onLeaseReleased = {
                stateDuringCleanup = coordinator.state.value
                canBeginDuringCleanup = coordinator.canBegin()
                beginDuringCleanup = coordinator.begin(AngleEntryKind.RECORD)
                releases.incrementAndGet()
            },
        )

        val ticket = coordinator.begin(AngleEntryKind.IMPORT)!!
        coordinator.completeResult(ticket.originToken, archivedUri)

        assertEquals("cleanup must keep the single-flight slot", false, canBeginDuringCleanup)
        assertNull("begin is refused while the rejected lease is cleaning up", beginDuringCleanup)
        assertEquals(AngleEntryHandoffState.Releasing(ticket.requestId), stateDuringCleanup)
        assertEquals(1, releases.get())
        // Once cleanup finishes the error stays published for the intro and the retry is available.
        assertEquals(
            AngleEntryHandoffState.Rejected(ticket.requestId, AngleEntryError.ATHLETE_UNAVAILABLE),
            coordinator.state.value,
        )
        assertTrue(coordinator.canBegin())
        assertNotNull(coordinator.begin(AngleEntryKind.IMPORT))
        scope.cancel()

        // (b) The fresh athlete query fails: same reject branch, same cleanup ordering.
        val failedUri = "content://provider/angles-reject-query-failure"
        val failedStore = FakeGrantStore(setOf(failedUri))
        val failedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var failing: AngleEntryHandoffCoordinator
        var stateWhileFailing: AngleEntryHandoffState? = null
        var canBeginWhileFailing: Boolean? = null
        var beginWhileFailing: AngleEntryTicket? = null
        val failedReleases = AtomicInteger()
        failing = AngleEntryHandoffCoordinator(
            grants = reconciler(failedStore),
            athleteValidator = AngleEntryAthleteValidator {
                throw IllegalStateException("athlete query failed")
            },
            scope = failedScope,
            ioDispatcher = Dispatchers.Unconfined,
            initialSelectedAthleteId = 17L,
            onLeaseReleased = {
                stateWhileFailing = failing.state.value
                canBeginWhileFailing = failing.canBegin()
                beginWhileFailing = failing.begin(AngleEntryKind.IMPORT)
                failedReleases.incrementAndGet()
            },
        )

        val failedTicket = failing.begin(AngleEntryKind.RECORD)!!
        failing.completeResult(failedTicket.originToken, failedUri)

        assertEquals("cleanup must keep the single-flight slot", false, canBeginWhileFailing)
        assertNull("begin is refused while the rejected lease is cleaning up", beginWhileFailing)
        assertEquals(AngleEntryHandoffState.Releasing(failedTicket.requestId), stateWhileFailing)
        assertEquals(1, failedReleases.get())
        assertEquals(
            AngleEntryHandoffState.Rejected(failedTicket.requestId, AngleEntryError.ATHLETE_VALIDATION_FAILED),
            failing.state.value,
        )
        assertTrue(failing.canBegin())
        assertNotNull(failing.begin(AngleEntryKind.RECORD))
        failedScope.cancel()
    }

    private fun coordinator(
        reconciler: VideoUriGrantReconciler,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        validator: AngleEntryAthleteValidator = AngleEntryAthleteValidator { true },
        initial: Long? = 17L,
        persisted: MutableList<Long?>? = null,
        released: AtomicInteger? = null,
    ) = AngleEntryHandoffCoordinator(
        grants = reconciler,
        athleteValidator = validator,
        scope = scope,
        ioDispatcher = dispatcher,
        initialSelectedAthleteId = initial,
        onSelectionPersisted = { athleteId -> persisted?.add(athleteId) },
        onLeaseReleased = { released?.incrementAndGet() },
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
