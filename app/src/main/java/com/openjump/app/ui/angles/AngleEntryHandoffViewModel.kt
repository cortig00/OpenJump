package com.openjump.app.ui.angles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AthleteRepository
import com.openjump.app.video.VideoUriGrantReconciler
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** External acquisition the athlete picked in the intro; the editor itself is shared. */
internal enum class AngleEntryKind { IMPORT, RECORD }

/**
 * Frozen owner of one capture/pick attempt. It is created before the picker or camera is launched,
 * so every later callback can be matched against the athlete that was actually shown to the user.
 * [originToken] is unique per attempt: a request id may repeat across navigation entries, the token
 * never matches a closed entry.
 */
internal data class AngleEntryTicket(
    val requestId: Long,
    val originToken: String,
    val kind: AngleEntryKind,
    val athleteId: Long,
)

/** Primitive-only URI transfer; no Activity, NavController, Uri or composition callback retained. */
internal data class AngleEntryTransfer(
    val requestId: Long,
    val originToken: String,
    val kind: AngleEntryKind,
    val athleteId: Long,
    val uri: String,
)

/** Recoverable outcomes surfaced on the intro. There is deliberately no fallback to the global athlete. */
internal enum class AngleEntryError {
    /** The frozen athlete was archived or deleted while the picker/camera was open. */
    ATHLETE_UNAVAILABLE,

    /** The fresh exact-athlete query failed; the transfer is dropped instead of guessing. */
    ATHLETE_VALIDATION_FAILED,
}

internal sealed interface AngleEntryHandoffState {
    data object Idle : AngleEntryHandoffState

    /** Owner frozen, picker/camera launched, no URI yet. Never cancelled by a return to the intro. */
    data class AwaitingCapture(val ticket: AngleEntryTicket) : AngleEntryHandoffState

    /** URI owned synchronously; grant take + fresh athlete query still in flight. */
    data class Acquiring(val transfer: AngleEntryTransfer) : AngleEntryHandoffState

    data class Ready(val transfer: AngleEntryTransfer) : AngleEntryHandoffState

    /** Claimed by the current intro entry; the intake lease outlives the navigation on purpose. */
    data class HandedOff(val transfer: AngleEntryTransfer) : AngleEntryHandoffState

    data class Releasing(val requestId: Long) : AngleEntryHandoffState

    data class Rejected(val requestId: Long, val error: AngleEntryError) : AngleEntryHandoffState
}

internal enum class AngleEntryResultDisposition {
    /** The URI started an asynchronous transfer; navigation happens on [AngleEntryHandoffState.Ready]. */
    Accepted,

    /** Cancelled picker/camera: no capture, no ownership, selection preserved. */
    Cancelled,

    /** Stale, duplicated, closed or already-superseded callback. */
    Ignored,
}

/** Revalidates the frozen athlete against the store right before publishing Ready. */
internal fun interface AngleEntryAthleteValidator {
    suspend fun isActiveAthlete(athleteId: Long): Boolean
}

internal class RepositoryAngleEntryAthleteValidator(
    private val repository: AthleteRepository,
) : AngleEntryAthleteValidator {
    override suspend fun isActiveAthlete(athleteId: Long): Boolean =
        repository.activeById(athleteId) != null
}

/**
 * Entry-scoped owner of the Angles introduction. State and lease live per navigation entry; the
 * screen takes the ViewModel from `NavBackStackEntry`, so configuration recreation only replaces an
 * observer of [state] and [selectedAthleteId].
 *
 * Ordering guarantees:
 *  - [begin] freezes kind + athlete + origin token before the picker/camera is launched.
 *  - [completeResult] registers URI ownership synchronously, before any dispatcher or provider call.
 *  - The intake lease is retained through [AngleEntryHandoffState.HandedOff] until the caller
 *    reports a real return to the intro entry ([returnToIntro]) or the entry is cleared ([close]).
 *    `AngleEditorViewModel.open` registers its own owner only after it is launched, so releasing at
 *    navigation time would open a revocation window the encoder hand-off does not have.
 *  - Cancellation keeps the state non-Idle and the pending owner registered while a blocking
 *    `takePersistableUriPermission` is still running, so no replacement picker can race cleanup.
 */
internal class AngleEntryHandoffCoordinator(
    private val grants: VideoUriGrantReconciler,
    private val athleteValidator: AngleEntryAthleteValidator,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialSelectedAthleteId: Long? = null,
    private val onSelectionPersisted: (Long?) -> Unit = {},
    private val onLeaseReleased: () -> Unit = {},
) {
    private val lock = Any()
    private val _state = MutableStateFlow<AngleEntryHandoffState>(AngleEntryHandoffState.Idle)
    val state: StateFlow<AngleEntryHandoffState> = _state.asStateFlow()

    private val _selectedAthleteId = MutableStateFlow(initialSelectedAthleteId?.takeIf { it > 0L })
    val selectedAthleteId: StateFlow<Long?> = _selectedAthleteId.asStateFlow()

    private var active: Pending? = null
    private var closed = false

    /**
     * True from the first selection this intro ever owns: explicit, restored from saved state or
     * adopted from the global preselection. Dropping an id because its athlete was archived keeps it
     * set, so asking for another athlete is the only way to select again: a later global resolution
     * is never a substitute for a choice the user has not made.
     */
    private var selectionResolved = initialSelectedAthleteId?.let { it > 0L } == true
    private var lastRequestId = 0L

    /** Local, entry-scoped choice. The global SelectedAthleteStore is intentionally not written. */
    fun selectAthlete(athleteId: Long): Boolean {
        if (athleteId <= 0L) return false
        synchronized(lock) {
            if (closed) return false
            selectionResolved = true
            persistSelectionLocked(athleteId)
        }
        return true
    }

    /**
     * Adopts the first valid global preselection while this intro has never resolved a selection. A
     * null/loading global is never frozen, and once a selection existed later global changes can no
     * longer retarget the intro.
     */
    fun onGlobalAthleteResolved(athleteId: Long?) {
        if (athleteId == null || athleteId <= 0L) return
        synchronized(lock) {
            if (closed || selectionResolved || _selectedAthleteId.value != null) return
            selectionResolved = true
            persistSelectionLocked(athleteId)
        }
    }

    /** Drops a selection that is no longer active so the intro can ask for another athlete. */
    fun onActiveAthletesResolved(activeIds: Set<Long>) {
        synchronized(lock) {
            if (closed) return
            val current = _selectedAthleteId.value ?: return
            if (current in activeIds) return
            // The id is dropped so the UI asks for another athlete; [selectionResolved] stays set, so
            // the next global resolution cannot silently adopt someone else.
            persistSelectionLocked(null)
        }
    }

    fun canBegin(): Boolean = synchronized(lock) {
        !closed && active == null && (_selectedAthleteId.value ?: 0L) > 0L
    }

    /** Single flight: returns null while closed, busy, or without a positive frozen athlete. */
    fun begin(kind: AngleEntryKind): AngleEntryTicket? {
        synchronized(lock) {
            if (closed || active != null) return null
            val athleteId = _selectedAthleteId.value ?: return null
            if (athleteId <= 0L) return null
            lastRequestId += 1L
            val ticket = AngleEntryTicket(
                requestId = lastRequestId,
                originToken = UUID.randomUUID().toString(),
                kind = kind,
                athleteId = athleteId,
            )
            active = Pending(ticket)
            _state.value = AngleEntryHandoffState.AwaitingCapture(ticket)
            return ticket
        }
    }

    fun completeResult(originToken: String, uri: String?): AngleEntryResultDisposition {
        val pending: Pending
        lateinit var job: Job
        synchronized(lock) {
            val candidate = active ?: return AngleEntryResultDisposition.Ignored
            if (closed || candidate.cleanupStarted || candidate.ticket.originToken != originToken) {
                return AngleEntryResultDisposition.Ignored
            }
            // A second URI for the same request is a duplicate delivery, never a retarget.
            if (candidate.transfer != null) return AngleEntryResultDisposition.Ignored
            if (uri.isNullOrBlank()) {
                candidate.abandoned = true
                candidate.cleanupStarted = true
                active = null
                _state.value = AngleEntryHandoffState.Idle
                return AngleEntryResultDisposition.Cancelled
            }
            val transfer = AngleEntryTransfer(
                requestId = candidate.ticket.requestId,
                originToken = candidate.ticket.originToken,
                kind = candidate.ticket.kind,
                athleteId = candidate.ticket.athleteId,
                uri = uri,
            )
            // Synchronous ownership before the dispatcher/take so reconciliation cannot revoke a
            // grant in the window between the provider result and the persisted permission.
            candidate.lease = grants.registerOwner(setOf(transfer.uri))
            candidate.transfer = transfer
            _state.value = AngleEntryHandoffState.Acquiring(transfer)
            job = scope.launch(start = CoroutineStart.LAZY) { acquireThenPublish(candidate, transfer) }
            candidate.job = job
            // Also closes the lease if the owner scope was already cancelled before the start.
            job.invokeOnCompletion { finish(candidate) }
            pending = candidate
        }
        job.start()
        return if (synchronized(lock) { active === pending }) {
            AngleEntryResultDisposition.Accepted
        } else {
            AngleEntryResultDisposition.Ignored
        }
    }

    private suspend fun acquireThenPublish(pending: Pending, transfer: AngleEntryTransfer) {
        try {
            try {
                withContext(ioDispatcher) { grants.takeReadGrant(transfer.uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best-effort, like the encoder import path: the provider may refuse persistence and
                // the existing editor still reports a truthful unreadable-source error.
            }
            val activeAthlete = try {
                withContext(ioDispatcher) { athleteValidator.isActiveAthlete(transfer.athleteId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reject(pending, AngleEntryError.ATHLETE_VALIDATION_FAILED)
                return
            }
            if (!activeAthlete) {
                reject(pending, AngleEntryError.ATHLETE_UNAVAILABLE)
                return
            }
            synchronized(lock) {
                if (active === pending && !pending.abandoned && !pending.cleanupStarted) {
                    pending.ready = true
                    _state.value = AngleEntryHandoffState.Ready(transfer)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun reject(pending: Pending, error: AngleEntryError) {
        synchronized(lock) {
            if (active !== pending || pending.cleanupStarted) return
            // The rejected attempt keeps its slot and a non-Idle state until the lease is closed, so
            // no replacement picker can begin while this cleanup (take/close/onLeaseReleased) runs.
            pending.cleanupStarted = true
            pending.terminalError = error
            _state.value = AngleEntryHandoffState.Releasing(pending.ticket.requestId)
        }
        finishCleanup(pending)
    }

    /** Atomic Ready -> HandedOff. Only the callback bound to the original token can claim it. */
    fun claimReady(originToken: String): AngleEntryTransfer? {
        synchronized(lock) {
            val pending = active ?: return null
            if (closed || pending.cleanupStarted || pending.abandoned) return null
            val ready = _state.value as? AngleEntryHandoffState.Ready ?: return null
            if (ready.transfer.originToken != originToken) return null
            _state.value = AngleEntryHandoffState.HandedOff(ready.transfer)
            return ready.transfer
        }
    }

    /**
     * Called from a stable lifecycle event when the intro entry is RESUMED again and the editor/detail
     * is no longer on top. It only finalizes [AngleEntryHandoffState.HandedOff]: a waiting picker or
     * camera must survive `ON_RESUME` because its ActivityResult can be delivered afterwards.
     */
    fun returnToIntro(originToken: String? = null) {
        val pending: Pending
        synchronized(lock) {
            val candidate = active ?: return
            if (candidate.cleanupStarted) return
            if (originToken != null && candidate.ticket.originToken != originToken) return
            if (_state.value !is AngleEntryHandoffState.HandedOff) return
            candidate.cleanupStarted = true
            _state.value = AngleEntryHandoffState.Releasing(candidate.ticket.requestId)
            pending = candidate
        }
        finishCleanup(pending)
    }

    /** Camera back / explicit cancel. A late URI for this token is ignored; the selection stays. */
    fun cancel(originToken: String? = null) {
        abandon(originToken)
    }

    /** Pops/clears of the entry. Idempotent; a blocking provider call keeps its lease until it exits. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        abandon(null)
    }

    private fun abandon(originToken: String?) {
        val pending: Pending
        val cancelJob: Job?
        val releaseNow: Boolean
        synchronized(lock) {
            val candidate = active ?: return
            if (originToken != null && candidate.ticket.originToken != originToken) return
            if (candidate.cleanupStarted) return
            candidate.abandoned = true
            if (candidate.lease == null) {
                // Picker/camera still open: there is no URI ownership to release.
                candidate.cleanupStarted = true
                active = null
                _state.value = AngleEntryHandoffState.Idle
                return
            }
            _state.value = AngleEntryHandoffState.Releasing(candidate.ticket.requestId)
            cancelJob = candidate.job
            releaseNow = candidate.ready || cancelJob?.isCompleted == true
            if (releaseNow) candidate.cleanupStarted = true
            pending = candidate
        }
        if (releaseNow) finishCleanup(pending) else cancelJob?.cancel()
    }

    private fun finish(pending: Pending) {
        val releaseNow = synchronized(lock) {
            when {
                active !== pending || pending.cleanupStarted -> false
                // Ready owns the lease while the UI is detached, paused, or about to claim it.
                pending.ready && !pending.abandoned -> false
                else -> {
                    pending.cleanupStarted = true
                    _state.value = AngleEntryHandoffState.Releasing(pending.ticket.requestId)
                    true
                }
            }
        }
        if (releaseNow) finishCleanup(pending)
    }

    private fun finishCleanup(pending: Pending) {
        try {
            // Provider/lease cleanup stays outside the lock. The entry keeps a non-Idle state until it
            // finishes, so a second launch cannot race the previous grant cleanup.
            pending.lease?.close()
            runCatching(onLeaseReleased)
        } finally {
            synchronized(lock) {
                if (active === pending && pending.cleanupStarted) {
                    active = null
                    val terminalError = pending.terminalError
                    // A rejected attempt publishes its error only now, so the intro keeps showing the
                    // truthful failure for a retry while the cleanup was still in progress.
                    _state.value = if (terminalError == null) {
                        AngleEntryHandoffState.Idle
                    } else {
                        AngleEntryHandoffState.Rejected(pending.ticket.requestId, terminalError)
                    }
                }
            }
        }
    }

    private fun persistSelectionLocked(athleteId: Long?) {
        _selectedAthleteId.value = athleteId
        runCatching { onSelectionPersisted(athleteId) }
    }

    private class Pending(val ticket: AngleEntryTicket) {
        var transfer: AngleEntryTransfer? = null
        var lease: VideoUriGrantReconciler.Lease? = null
        var job: Job? = null
        var ready: Boolean = false
        var abandoned: Boolean = false
        var cleanupStarted: Boolean = false
        var terminalError: AngleEntryError? = null
    }
}

/**
 * Thin entry-owned ViewModel. Only the selected athlete id is serialized (a primitive), never a URI,
 * capture or draft: after process death an orphaned external result is ignored and the user starts again.
 */
internal class AngleEntryHandoffViewModel(
    grants: VideoUriGrantReconciler,
    athleteValidator: AngleEntryAthleteValidator,
    savedStateHandle: SavedStateHandle,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    requestReconciliation: () -> Unit = {},
) : ViewModel() {
    private val coordinator = AngleEntryHandoffCoordinator(
        grants = grants,
        athleteValidator = athleteValidator,
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        initialSelectedAthleteId = savedStateHandle.get<Long>(KEY_SELECTED_ATHLETE_ID),
        onSelectionPersisted = { athleteId ->
            if (athleteId == null) {
                savedStateHandle.remove<Long>(KEY_SELECTED_ATHLETE_ID)
            } else {
                savedStateHandle[KEY_SELECTED_ATHLETE_ID] = athleteId
            }
        },
        onLeaseReleased = requestReconciliation,
    )

    val state: StateFlow<AngleEntryHandoffState> = coordinator.state
    val selectedAthleteId: StateFlow<Long?> = coordinator.selectedAthleteId

    fun selectAthlete(athleteId: Long): Boolean = coordinator.selectAthlete(athleteId)

    fun onGlobalAthleteResolved(athleteId: Long?) = coordinator.onGlobalAthleteResolved(athleteId)

    fun onActiveAthletesResolved(activeIds: Set<Long>) = coordinator.onActiveAthletesResolved(activeIds)

    fun canBegin(): Boolean = coordinator.canBegin()

    fun begin(kind: AngleEntryKind): AngleEntryTicket? = coordinator.begin(kind)

    fun completeResult(originToken: String, uri: String?): AngleEntryResultDisposition =
        coordinator.completeResult(originToken, uri)

    fun claimReady(originToken: String): AngleEntryTransfer? = coordinator.claimReady(originToken)

    fun returnToIntro(originToken: String? = null) = coordinator.returnToIntro(originToken)

    fun cancel(originToken: String? = null) = coordinator.cancel(originToken)

    override fun onCleared() {
        // A popped entry is an intentional abandon; a configuration change retains the ViewModel.
        coordinator.close()
        super.onCleared()
    }

    companion object {
        internal const val KEY_SELECTED_ATHLETE_ID = "angles_intro_selected_athlete_id"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                AngleEntryHandoffViewModel(
                    grants = app.videoUriGrantReconciler,
                    athleteValidator = RepositoryAngleEntryAthleteValidator(app.athleteRepository),
                    savedStateHandle = createSavedStateHandle(),
                    requestReconciliation = app::requestVideoUriReconciliation,
                )
            }
        }
    }
}
