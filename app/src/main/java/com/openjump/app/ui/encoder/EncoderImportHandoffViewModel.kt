package com.openjump.app.ui.encoder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.video.VideoUriGrantReconciler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Primitive, immutable picker result; no Activity, NavController, or composition callback is retained. */
internal data class EncoderImportRequest(
    val requestId: Long,
    val uri: String,
    val exerciseName: String,
    val loadKg: Double,
    val athleteId: Long,
)

internal sealed interface EncoderImportHandoffState {
    data object Idle : EncoderImportHandoffState
    data class Acquiring(val requestId: Long) : EncoderImportHandoffState
    data class Ready(val request: EncoderImportRequest) : EncoderImportHandoffState
    data class Cancelling(val requestId: Long) : EncoderImportHandoffState
    data class Releasing(val requestId: Long) : EncoderImportHandoffState
}

internal sealed interface EncoderPickerCallbackDisposition {
    data object Ignore : EncoderPickerCallbackDisposition
    data object Cancel : EncoderPickerCallbackDisposition
    data object Accepted : EncoderPickerCallbackDisposition
    data object Rejected : EncoderPickerCallbackDisposition

    val startDispatchedAfterCallback: Boolean?
        get() = when (this) {
            Ignore -> null
            Cancel, Rejected -> false
            Accepted -> true
        }
}

/** Resolves only callbacks belonging to an outstanding picker request; stale results are inert. */
internal fun resolveEncoderPickerCallback(
    pendingRequestId: Long?,
    uri: String?,
    request: EncoderImportRequest?,
    submit: (EncoderImportRequest) -> Boolean,
): EncoderPickerCallbackDisposition {
    if (pendingRequestId == null) return EncoderPickerCallbackDisposition.Ignore
    if (uri == null || request == null || request.requestId != pendingRequestId || request.uri != uri) {
        return EncoderPickerCallbackDisposition.Cancel
    }
    return if (submit(request)) EncoderPickerCallbackDisposition.Accepted
    else EncoderPickerCallbackDisposition.Rejected
}

/**
 * State/lease owner for one encoder setup navigation entry. The screen obtains its ViewModel from
 * Navigation's current NavBackStackEntry, so configuration recreation detaches only the observer.
 */
internal class EncoderImportHandoffCoordinator(
    private val grants: VideoUriGrantReconciler,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onLeaseReleased: () -> Unit = {},
) {
    private val lock = Any()
    private val _state = MutableStateFlow<EncoderImportHandoffState>(EncoderImportHandoffState.Idle)
    val state: StateFlow<EncoderImportHandoffState> = _state.asStateFlow()
    private var active: Pending? = null
    private var lastRequestId = 0L

    /** Registers URI ownership synchronously, before any dispatcher or provider handoff. */
    fun submit(request: EncoderImportRequest): Boolean {
        val pending: Pending
        lateinit var job: Job
        synchronized(lock) {
            if (request.requestId <= lastRequestId || active != null) return false
            lastRequestId = request.requestId
            pending = Pending(request, grants.registerOwner(setOf(request.uri)))
            active = pending
            _state.value = EncoderImportHandoffState.Acquiring(request.requestId)

            // LAZY lets us install the completion cleanup before a cancelled-before-start job can
            // finish. This is also what closes the lease if the owner scope was already cancelled.
            job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                acquireThenPublish(pending)
            }
            pending.job = job
            job.invokeOnCompletion { finish(pending) }
        }
        job.start()
        return synchronized(lock) { active === pending }
    }

    private suspend fun acquireThenPublish(pending: Pending) {
        try {
            try {
                withContext(ioDispatcher) { grants.takeReadGrant(pending.request.uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Permission persistence is best-effort. Continue to the existing import path just
                // as for provider denial, which the reconciler already handles internally.
            }
            synchronized(lock) {
                if (active === pending && !pending.abandoned) {
                    pending.ready = true
                    _state.value = EncoderImportHandoffState.Ready(pending.request)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    /** Acknowledgement happens only after the current UI callback synchronously attaches AppSession. */
    fun acknowledge(requestId: Long): Boolean {
        val pending = synchronized(lock) {
            val candidate = active
            if (candidate == null || !candidate.ready || candidate.cleanupStarted ||
                candidate.request.requestId != requestId
            ) {
                null
            } else {
                candidate.cleanupStarted = true
                _state.value = EncoderImportHandoffState.Releasing(requestId)
                candidate
            }
        } ?: return false
        finishCleanup(pending)
        return true
    }

    /** Intentional navigation/cancel; an in-flight blocking provider call keeps its lease until exit. */
    fun abandon(requestId: Long? = null) {
        val pending: Pending
        val cancelJob: Job?
        val releaseNow: Boolean
        synchronized(lock) {
            pending = active ?: return
            if (requestId != null && pending.request.requestId != requestId) return
            if (pending.cleanupStarted) return
            pending.abandoned = true
            _state.value = EncoderImportHandoffState.Cancelling(pending.request.requestId)
            cancelJob = pending.job
            releaseNow = pending.ready || cancelJob?.isCompleted == true
            if (releaseNow) pending.cleanupStarted = true
        }
        if (releaseNow) finishCleanup(pending) else cancelJob?.cancel()
    }

    private fun finish(pending: Pending) {
        val releaseNow = synchronized(lock) {
            if (active !== pending || pending.cleanupStarted) {
                false
            } else if (pending.ready && !pending.abandoned) {
                // Ready owns the lease while UI is detached or before it acknowledges the handoff.
                false
            } else {
                pending.cleanupStarted = true
                _state.value = if (pending.abandoned) {
                    EncoderImportHandoffState.Cancelling(pending.request.requestId)
                } else {
                    EncoderImportHandoffState.Releasing(pending.request.requestId)
                }
                true
            }
        }
        if (releaseNow) finishCleanup(pending)
    }

    private fun finishCleanup(pending: Pending) {
        try {
            // Lease/provider cleanup stays outside the coordinator lock. Keep active + non-Idle state
            // until it has finished so a replacement picker cannot race the old grant cleanup.
            pending.lease.close()
            runCatching(onLeaseReleased)
        } finally {
            synchronized(lock) {
                if (active === pending && pending.cleanupStarted) {
                    active = null
                    _state.value = EncoderImportHandoffState.Idle
                }
            }
        }
    }

    private class Pending(
        val request: EncoderImportRequest,
        val lease: VideoUriGrantReconciler.Lease,
    ) {
        var job: Job? = null
        var ready: Boolean = false
        var abandoned: Boolean = false
        var cleanupStarted: Boolean = false
    }
}

internal class EncoderImportHandoffViewModel(
    grants: VideoUriGrantReconciler,
    requestReconciliation: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val coordinator = EncoderImportHandoffCoordinator(
        grants = grants,
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        onLeaseReleased = requestReconciliation,
    )
    val state: StateFlow<EncoderImportHandoffState> = coordinator.state

    fun acceptPickerResult(request: EncoderImportRequest): Boolean = coordinator.submit(request)

    fun acknowledge(requestId: Long): Boolean = coordinator.acknowledge(requestId)

    fun abandonPendingImport(requestId: Long? = null) = coordinator.abandon(requestId)

    override fun onCleared() {
        // A popped NavBackStackEntry is an intentional abandon; a configuration change retains it.
        coordinator.abandon()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                EncoderImportHandoffViewModel(
                    grants = app.videoUriGrantReconciler,
                    requestReconciliation = app::requestVideoUriReconciliation,
                )
            }
        }
    }
}
