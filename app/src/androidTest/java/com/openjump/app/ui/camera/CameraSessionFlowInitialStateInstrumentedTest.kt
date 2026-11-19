package com.openjump.app.ui.camera

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.settings.RecordingUse
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First-composition state of the real [CameraScreen] session helpers.
 *
 * Each case captures the values read **inside** the composition as a plain val and only
 * *records* that val from a [SideEffect]: reading the [androidx.compose.runtime.State]
 * later in the side effect could observe a subsequent emission and hide a wrong first
 * snapshot. No CameraScreen, camera controller, database, AppSession or permission is
 * involved; only the production helpers are composed.
 */
@RunWith(AndroidJUnit4::class)
class CameraSessionFlowInitialStateInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun protocolFlowFirstSnapshotKeepsSeededSessionState() {
        // Seeded session state exactly like a live encoder session: the legacy helpers
        // exposed the current StateFlow value immediately, with no placeholder frame.
        val encoderDraft = MutableStateFlow<String?>("encoder-7")
        val seriesTargets = MutableStateFlow(7)
        val roster = MutableStateFlow(setOf(17L, 23L))
        val firstSnapshot = AtomicReference<List<Any?>?>(null)
        compose.setContent {
            val encoder by appSessionFlowOrNull(true, encoderDraft)
            val series by appSessionFlowOr(true, seriesTargets, 1)
            val participants by appSessionFlowOr(true, roster, emptySet<Long>())
            val captured = listOf<Any?>(encoder, series, participants)
            SideEffect { if (firstSnapshot.get() == null) firstSnapshot.set(captured) }
        }
        compose.waitForIdle()

        assertEquals(listOf<Any?>("encoder-7", 7, setOf(17L, 23L)), firstSnapshot.get())
        assertEquals(
            "a seeded encoder draft must derive ENCODER on the first frame",
            RecordingUse.ENCODER,
            CameraCaptureContextPolicy.recordingUse(
                CameraCaptureContext.ProtocolFlow,
                hasEncoderDraft = firstSnapshot.get()?.get(0) != null,
            ),
        )
    }

    @Test
    fun standaloneFirstSnapshotKeepsNeutralPlaceholders() {
        // The same stale global state exists, but standalone must never observe it.
        val encoderDraft = MutableStateFlow<String?>("stale-encoder")
        val seriesTargets = MutableStateFlow(7)
        val roster = MutableStateFlow(setOf(17L, 23L))
        val firstSnapshot = AtomicReference<List<Any?>?>(null)
        compose.setContent {
            val encoder by appSessionFlowOrNull(false, encoderDraft)
            val series by appSessionFlowOr(false, seriesTargets, 1)
            val participants by appSessionFlowOr(false, roster, emptySet<Long>())
            val captured = listOf<Any?>(encoder, series, participants)
            SideEffect { if (firstSnapshot.get() == null) firstSnapshot.set(captured) }
        }
        compose.waitForIdle()

        assertEquals(listOf<Any?>(null, 1, emptySet<Long>()), firstSnapshot.get())
        assertEquals(
            RecordingUse.JUMPS,
            CameraCaptureContextPolicy.recordingUse(
                CameraCaptureContext.Standalone,
                hasEncoderDraft = firstSnapshot.get()?.get(0) != null,
            ),
        )
    }

    @Test
    fun standaloneNeverCollectsAPlainFlow() {
        val collections = AtomicInteger(0)
        val athletes = flow {
            collections.incrementAndGet()
            emit(listOf("athlete-1"))
        }
        val recorded = AtomicReference<List<String>?>(null)
        compose.setContent {
            val activeAthletes by appSessionFlowOr(false, athletes, emptyList<String>())
            val captured = activeAthletes
            SideEffect { recorded.set(captured) }
        }
        compose.waitForIdle()

        assertEquals("standalone must not subscribe to the repository flow", 0, collections.get())
        assertEquals(emptyList<String>(), recorded.get())
    }
}
