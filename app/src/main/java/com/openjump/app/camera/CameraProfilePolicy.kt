package com.openjump.app.camera

/** Product-level recording session. A profile never combines values from different cameras. */
enum class CameraSessionKind { NORMAL, HIGH_SPEED }

data class CameraProductProfile(
    val cameraId: String,
    val quality: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val sessionKind: CameraSessionKind,
) {
    init {
        require(cameraId.isNotBlank())
        require(quality.isNotBlank())
        require(width > 0 && height > 0 && fps > 0)
    }

    val isDegraded: Boolean get() = fps == 30

    /** Stable fallback label for non-Compose callers; Compose uses localized resources. */
    fun label(): String = "${height}p · $fps fps"
}

/** Pure product policy shared by CameraX discovery, settings and tests. */
object CameraProfilePolicy {
    val visibleFps: List<Int> = listOf(60, 120, 240)
    private val autoOrder = listOf(120, 60, 240, 30)

    fun chooseAuto(profiles: Collection<CameraProductProfile>): CameraProductProfile? =
        autoOrder.asSequence().mapNotNull { fps ->
            profiles.filter { it.fps == fps }.bestAtSameFps()
        }.firstOrNull()

    fun choose(
        profiles: Collection<CameraProductProfile>,
        preferredFps: Int,
    ): CameraProductProfile? = candidates(profiles, preferredFps).firstOrNull()

    /** Manual requests bind only the exact FPS; quality fallback remains within that FPS. */
    fun candidates(
        profiles: Collection<CameraProductProfile>,
        preferredFps: Int,
    ): List<CameraProductProfile> = if (preferredFps <= 0) {
        autoOrder.flatMap { fps -> profiles.filter { it.fps == fps }.sortedByQuality() }
    } else {
        profiles.filter { it.fps == preferredFps }.sortedByQuality()
    }

    fun visible(profiles: Collection<CameraProductProfile>): List<CameraProductProfile> =
        profiles.filter { it.fps in visibleFps }.distinct().sortedWith(
            compareBy<CameraProductProfile> { visibleFps.indexOf(it.fps) }
                .thenBy { it.qualityRank() }
                .thenBy { it.cameraId },
        )

    private fun Collection<CameraProductProfile>.bestAtSameFps(): CameraProductProfile? =
        minWithOrNull(compareBy<CameraProductProfile> { it.qualityRank() }
            .thenBy { it.cameraId })

    private fun Collection<CameraProductProfile>.sortedByQuality(): List<CameraProductProfile> =
        sortedWith(compareBy<CameraProductProfile> { it.qualityRank() }.thenBy { it.cameraId })

    private fun CameraProductProfile.qualityRank(): Int = when {
        width == 1280 && height == 720 -> 0
        width == 1920 && height == 1080 -> 1
        else -> 2
    }
}

enum class JumpCameraState { BLOCKED, BINDING, READY, STARTING, RECORDING, FINALIZING, ERROR }

/** Tiny Android-free state policy. It makes stale terminal callbacks harmless. */
class JumpCameraStateMachine {
    var state: JumpCameraState = JumpCameraState.BLOCKED
        private set
    var generation: Long = 0
        private set
    var disposed: Boolean = false
        private set
    private var boundGeneration: Long? = null

    fun beginBinding(): Long {
        if (disposed) return generation
        generation++
        state = JumpCameraState.BINDING
        boundGeneration = null
        return generation
    }

    /** Binding only arms readiness; a successful bind is not enough to expose REC. */
    fun bound(token: Long): Boolean = if (isCurrent(token) && state == JumpCameraState.BINDING) {
        boundGeneration = token
        true
    } else false

    fun streaming(token: Long): Boolean = if (
        isCurrent(token) && state == JumpCameraState.BINDING && boundGeneration == token
    ) {
        boundGeneration = null
        state = JumpCameraState.READY
        true
    } else false

    fun beginRecording(token: Long): Boolean = transition(token, JumpCameraState.READY, JumpCameraState.STARTING)
    fun started(token: Long): Boolean = transition(token, JumpCameraState.STARTING, JumpCameraState.RECORDING)
    fun beginFinalize(token: Long): Boolean = transitionAny(
        token,
        setOf(JumpCameraState.STARTING, JumpCameraState.RECORDING),
        JumpCameraState.FINALIZING,
    )
    fun terminal(token: Long, success: Boolean): Boolean = when {
        !isCurrent(token) || state == JumpCameraState.ERROR || state == JumpCameraState.BLOCKED -> false
        success && state != JumpCameraState.FINALIZING -> false
        !success && state == JumpCameraState.READY -> false
        else -> {
            state = if (success) JumpCameraState.READY else JumpCameraState.ERROR
            true
        }
    }

    /** Explicitly terminalizes a current non-terminal error path, including READY setup failures. */
    fun error(token: Long): Boolean = if (
        isCurrent(token) && state != JumpCameraState.ERROR && state != JumpCameraState.BLOCKED
    ) {
        state = JumpCameraState.ERROR
        true
    } else false

    fun cancel() {
        if (!disposed) {
            generation++
            boundGeneration = null
            state = JumpCameraState.BLOCKED
        }
    }

    fun dispose() {
        disposed = true
        generation++
        boundGeneration = null
        state = JumpCameraState.BLOCKED
    }

    fun isCurrent(token: Long): Boolean = !disposed && token == generation

    private fun transition(token: Long, expected: JumpCameraState, next: JumpCameraState): Boolean =
        if (isCurrent(token) && state == expected) {
            state = next
            true
        } else false

    private fun transitionAny(
        token: Long,
        expected: Set<JumpCameraState>,
        next: JumpCameraState,
    ): Boolean = if (isCurrent(token) && state in expected) {
        state = next
        true
    } else false
}
