package com.openjump.app.camera

/**
 * Android-free coordination for a bound candidate waiting for a visible preview stream.
 * Generation and candidate tokens make late stream/timeout callbacks harmless.
 */
class PreviewReadinessCoordinator<T> {
    enum class State { IDLE, WAITING_FOR_STREAM, READY, ERROR, CANCELLED, DISPOSED }

    sealed class Decision<out T> {
        data class Retry<T>(val candidate: T, val index: Int) : Decision<T>()
        data object Error : Decision<Nothing>()
        data object Ignored : Decision<Nothing>()
    }

    var state: State = State.IDLE
        private set
    var generation: Long = 0L
        private set

    private var candidates: List<T> = emptyList()
    private var candidateIndex = -1
    private var candidateToken = 0L
    private var idleObserved = true
    private var candidateHadIdleBarrier = false

    fun begin(candidates: List<T>): Long {
        if (state == State.DISPOSED) return generation
        generation++
        this.candidates = candidates.toList()
        // The supplied candidate list defines the fallback boundary. Manual policy
        // supplies only one exact FPS, so retries cannot cross to another FPS.
        candidateIndex = if (candidates.isEmpty()) -1 else 0
        candidateToken++
        idleObserved = true
        candidateHadIdleBarrier = false
        state = State.IDLE
        return generation
    }

    /**
     * Requires a fresh IDLE observation before the next graph is bound. The initial
     * no-graph state is represented by the default true value from [begin].
     */
    fun requireIdle(generation: Long): Boolean {
        if (generation != this.generation || state != State.IDLE) return false
        idleObserved = false
        candidateHadIdleBarrier = false
        candidateToken++
        return true
    }

    /** Records the IDLE edge for the generation currently waiting to bind. */
    fun onIdle(generation: Long): Boolean {
        if (generation != this.generation || state != State.IDLE) return false
        idleObserved = true
        return true
    }

    /** Fails an IDLE barrier without leaving the caller stuck in BINDING. */
    fun onIdleTimeout(generation: Long): Decision<T> {
        if (generation != this.generation || state != State.IDLE || idleObserved) return Decision.Ignored
        candidateToken++
        state = State.ERROR
        return Decision.Error
    }

    /** Skips a candidate rejected before binding (for example, an unsupported graph). */
    fun skipCandidate(generation: Long, index: Int): Boolean {
        if (
            generation != this.generation ||
            state != State.IDLE ||
            index !in candidates.indices ||
            index < candidateIndex
        ) return false
        candidateIndex = index + 1
        candidateToken++
        return true
    }

    /** Marks one specific candidate as bound and starts its readiness window. */
    fun bindCandidate(generation: Long, index: Int): Long? {
        if (
            generation != this.generation ||
            state != State.IDLE ||
            !idleObserved ||
            index != candidateIndex
        ) return null
        if (index !in candidates.indices) return null
        candidateToken++
        candidateHadIdleBarrier = true
        idleObserved = false
        state = State.WAITING_FOR_STREAM
        return candidateToken
    }

    /** Only the current candidate's STREAMING event can make the graph ready. */
    fun onStreaming(generation: Long, candidateToken: Long): Boolean {
        if (
            generation != this.generation ||
            candidateToken != this.candidateToken ||
            state != State.WAITING_FOR_STREAM ||
            !candidateHadIdleBarrier
        ) return false
        state = State.READY
        return true
    }

    fun onTimeout(generation: Long, candidateToken: Long): Decision<T> =
        decideFailure(generation, candidateToken)

    fun onBindFailure(generation: Long, candidateToken: Long): Decision<T> =
        decideFailure(generation, candidateToken)

    fun cancel() {
        if (state == State.DISPOSED) return
        generation++
        candidateToken++
        state = State.CANCELLED
    }

    fun dispose() {
        if (state == State.DISPOSED) return
        generation++
        candidateToken++
        state = State.DISPOSED
    }

    private fun decideFailure(generation: Long, candidateToken: Long): Decision<T> {
        if (
            generation != this.generation ||
            candidateToken != this.candidateToken ||
            state != State.WAITING_FOR_STREAM
        ) return Decision.Ignored

        this.candidateToken++
        candidateHadIdleBarrier = false
        idleObserved = false
        val nextIndex = candidateIndex + 1
        if (nextIndex !in candidates.indices) {
            state = State.ERROR
            return Decision.Error
        }
        candidateIndex = nextIndex
        state = State.IDLE
        return Decision.Retry(candidates[nextIndex], nextIndex)
    }
}
