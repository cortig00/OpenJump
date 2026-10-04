package com.openjump.app.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Availability is kept in the hoisted result state, independently from chart availability. */
enum class EncoderPlaybackAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE }

data class EncoderPendingSeek(val id: Long, val sourcePtsUs: Long)

data class EncoderPlaybackCoordinatorState(
    val availability: EncoderPlaybackAvailability = EncoderPlaybackAvailability.UNKNOWN,
    val selectedSourcePtsUs: Long? = null,
    val renderedSourcePtsUs: Long? = null,
    /** The current unacknowledged request; it remains until matching metadata arrives. */
    val pendingSeek: EncoderPendingSeek? = null,
    val inFlightSeek: EncoderPendingSeek? = null,
    val bindingGeneration: Long = 0L,
    val firstSourcePtsUs: Long? = null,
)

/**
 * Single-direction bridge between result charts and the one ExoPlayer. State is observable by
 * Compose, while seek requests remain durable until a matching rendered-frame acknowledgement.
 */
class EncoderPlaybackCoordinator {
    private val _state = MutableStateFlow(EncoderPlaybackCoordinatorState())
    val stateFlow: StateFlow<EncoderPlaybackCoordinatorState> = _state.asStateFlow()

    /** Compatibility snapshot for non-Compose callers and pure tests. */
    val state: EncoderPlaybackCoordinatorState get() = _state.value

    private var nextRequestId = 0L
    private var pendingRequests = mutableListOf<EncoderPendingSeek>()
    private var emittedGeneration: Long? = null

    fun bind(
        availability: EncoderPlaybackAvailability,
        firstSourcePtsUs: Long? = state.firstSourcePtsUs,
    ): Long {
        if (availability == EncoderPlaybackAvailability.UNAVAILABLE) {
            pendingRequests.clear()
            emittedGeneration = null
        }
        val generation = state.bindingGeneration + 1L
        _state.value = state.copy(
            availability = availability,
            bindingGeneration = generation,
            firstSourcePtsUs = firstSourcePtsUs,
            // Keep requests across an available player leaving composition. They will be emitted
            // once for the new generation, then still wait for rendered metadata.
            pendingSeek = if (availability != EncoderPlaybackAvailability.UNAVAILABLE) {
                pendingRequests.firstOrNull() ?: state.inFlightSeek
            } else null,
            inFlightSeek = if (availability != EncoderPlaybackAvailability.UNAVAILABLE) state.inFlightSeek else null,
            renderedSourcePtsUs = null,
        )
        emittedGeneration = null
        return generation
    }

    /** Leaves composition without discarding a request made while the player was unavailable. */
    fun unbind(bindingGeneration: Long): Boolean {
        if (bindingGeneration != state.bindingGeneration) return false
        emittedGeneration = null
        _state.value = state.copy(
            availability = EncoderPlaybackAvailability.UNKNOWN,
            bindingGeneration = state.bindingGeneration + 1L,
            renderedSourcePtsUs = null,
            pendingSeek = pendingRequests.firstOrNull() ?: state.inFlightSeek,
        )
        return true
    }

    fun setAvailability(availability: EncoderPlaybackAvailability) {
        if (state.availability == availability) return
        if (availability == EncoderPlaybackAvailability.UNAVAILABLE) {
            pendingRequests.clear()
            emittedGeneration = null
            _state.value = state.copy(
                availability = availability,
                bindingGeneration = state.bindingGeneration + 1L,
                renderedSourcePtsUs = null,
                pendingSeek = null,
                inFlightSeek = null,
            )
        } else if (availability == EncoderPlaybackAvailability.UNKNOWN) {
            emittedGeneration = null
            _state.value = state.copy(
                availability = availability,
                bindingGeneration = state.bindingGeneration + 1L,
                renderedSourcePtsUs = null,
                pendingSeek = pendingRequests.firstOrNull() ?: state.inFlightSeek,
            )
        } else {
            _state.value = state.copy(availability = availability)
        }
    }

    fun sourcePtsFromPresentation(presentationPtsUs: Long): Long =
        presentationPtsUs + (state.firstSourcePtsUs ?: 0L)

    fun presentationPtsFromSource(sourcePtsUs: Long): Long =
        (sourcePtsUs - (state.firstSourcePtsUs ?: 0L)).coerceAtLeast(0L)

    /** A gesture always updates local selection, but only an available player gets a request. */
    fun requestSeek(sourcePtsUs: Long): EncoderPendingSeek {
        val request = EncoderPendingSeek(++nextRequestId, sourcePtsUs)
        _state.value = state.copy(selectedSourcePtsUs = sourcePtsUs)
        if (state.availability == EncoderPlaybackAvailability.UNAVAILABLE) return request
        pendingRequests += request
        _state.value = state.copy(pendingSeek = pendingRequests.firstOrNull() ?: state.inFlightSeek)
        return request
    }

    /** Emit at most once per binding. The in-flight request is not acknowledged here. */
    fun consumePendingSeek(): EncoderPendingSeek? {
        if (state.availability != EncoderPlaybackAvailability.AVAILABLE) return null
        val inFlight = state.inFlightSeek
        if (inFlight != null) {
            if (emittedGeneration == state.bindingGeneration) return null
            emittedGeneration = state.bindingGeneration
            return inFlight
        }
        val request = pendingRequests.firstOrNull() ?: return null
        _state.value = state.copy(inFlightSeek = request, pendingSeek = request)
        emittedGeneration = state.bindingGeneration
        return request
    }

    /** A stale callback is ignored. A non-matching current frame advances cursor but never seeks. */
    fun onFrameRendered(bindingGeneration: Long, sourcePtsUs: Long): Boolean {
        if (bindingGeneration != state.bindingGeneration) return false
        val inFlight = state.inFlightSeek
        if (inFlight != null && inFlight.sourcePtsUs == sourcePtsUs) {
            pendingRequests.removeAll { it.id == inFlight.id }
            val next = pendingRequests.firstOrNull()
            _state.value = state.copy(
                renderedSourcePtsUs = sourcePtsUs,
                pendingSeek = next,
                inFlightSeek = next,
            )
            emittedGeneration = null
        } else {
            _state.value = state.copy(renderedSourcePtsUs = sourcePtsUs)
        }
        return true
    }
}
