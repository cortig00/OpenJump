package com.openjump.app.video

import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.VideoSource
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.tracking.ImagePoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSessionTest {

    private fun <T> setFlowValue(fieldName: String, value: T) {
        val field = AppSession::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(AppSession) as kotlinx.coroutines.flow.MutableStateFlow<T?>).value = value
    }

    private fun installFrameIndex(index: VideoFrameIndex) {
        val field = AppSession::class.java.getDeclaredField("_frameIndex")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(AppSession) as kotlinx.coroutines.flow.MutableStateFlow<VideoFrameIndex?>).value = index
    }

    private fun installBilateralFrameIndex() {
        installFrameIndex(
            VideoFrameIndex(
                frameTimesUs = LongArray(32) { it * 1_000L },
                durationUs = 31_000L,
                width = 100,
                height = 100,
                rotationDegrees = 0,
                mimeType = "video/avc",
            ),
        )
    }

    private fun bilateralResult(heightCm: Double) = ProtocolResult(
        protocolId = ProtocolId.UNILATERAL,
        primaryMetric = MetricValue(MetricKey.HEIGHT_CM, heightCm, MetricUnit.CENTIMETER),
        secondaryMetrics = emptyList(),
    )

    private fun markRemainingUnilateralEvents(firstFrameIndex: Int) {
        val draft = requireNotNull(AppSession.draft.value)
        draft.definition.requiredEvents.forEachIndexed { offset, event ->
            if (com.openjump.app.protocol.EventKey(event) !in draft.events) {
                AppSession.markAt(event, firstFrameIndex + offset)
            }
        }
    }

    @After
    fun clear() = AppSession.reset()

    @Test
    fun `target reselection clears tracking but preserves calibration while A B reset clears both`() {
        AppSession.beginEncoder(EncoderSetup(EncoderExercise.BENCH_PRESS, 60.0), athleteId = 1L)
        installFrameIndex(VideoFrameIndex(longArrayOf(0L, 33_000L), 33_000L, 200, 100, 0, "video/avc"))
        AppSession.markCalibrationPoint(ImagePoint(20.0, 20.0), 0)
        AppSession.markCalibrationPoint(ImagePoint(20.0, 80.0), 0)
        AppSession.confirmCalibration(0.6)
        val calibration = AppSession.encoderDraft.value!!.calibration
        assertTrue(calibration != null)

        AppSession.reselectEncoderTarget()
        assertEquals(calibration, AppSession.encoderDraft.value!!.calibration)
        assertTrue(AppSession.encoderDraft.value!!.tracking.isEmpty())

        AppSession.clearEncoderCalibration()
        assertNull(AppSession.encoderDraft.value!!.calibration)
        assertNull(AppSession.encoderDraft.value!!.calibrationPointA)
        assertTrue(AppSession.encoderDraft.value!!.tracking.isEmpty())
    }

    @Test
    fun `video ownership includes bilateral active pending and completed snapshot drafts`() {
        AppSession.beginBilateral(targetAttempts = 1, athleteId = 1L)
        val uri = "content://provider/bilateral"
        val left = AppSession.draft.value!!.copy(videoUri = uri)
        val right = left.copy(setup = left.setup.copy(side = MeasurementSide.RIGHT))
        setFlowValue("_draft", left)
        val currentState = AppSession.bilateral.value!!
        val snapshot = BilateralAttemptSnapshot(
            side = MeasurementSide.LEFT,
            sideOrdinal = 1,
            draft = left,
            result = bilateralResult(10.0),
            detectedFps = 120,
        )
        setFlowValue(
            "_bilateral",
            currentState.copy(attempts = listOf(snapshot), pendingDrafts = mapOf(MeasurementSide.RIGHT to right)),
        )

        assertEquals(setOf(uri), AppSession.videoUriReferences())
        AppSession.reset()
        assertTrue(AppSession.videoUriReferences().isEmpty())
    }

    @Test
    fun `begin keeps protocol setup and creates a fresh session`() {
        AppSession.begin(
            ProtocolId.UNILATERAL,
            ProtocolSetup(side = MeasurementSide.LEFT),
            athleteId = 1L,
            athleteAnthropometrics = AthleteAnthropometrics(weightKg = 72.5, heightCm = 181.0),
        )
        val first = AppSession.draft.value!!
        assertEquals(ProtocolId.UNILATERAL, first.protocolId)
        assertEquals(MeasurementSide.LEFT, first.setup.side)
        assertEquals(72.5, first.athleteAnthropometrics.weightKg!!, 0.0)
        assertEquals(181.0, first.athleteAnthropometrics.heightCm!!, 0.0)

        AppSession.begin(ProtocolId.SJ, athleteId = 1L)
        assertNotEquals(first.sessionKey, AppSession.draft.value!!.sessionKey)
        assertNull(AppSession.draft.value!!.setup.side)
    }

    @Test
    fun `series attempt count accepts one through five and rejects outside bounds`() {
        (1..5).forEach { target ->
            AppSession.begin(ProtocolId.SJ, athleteId = 1L, targetAttempts = target)
            assertEquals(target, AppSession.seriesTargetAttempts.value)
            assertEquals(1, AppSession.seriesAttempt.value)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppSession.begin(ProtocolId.SJ, athleteId = 1L, targetAttempts = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppSession.begin(ProtocolId.SJ, athleteId = 1L, targetAttempts = 6)
        }
    }

    @Test
    fun `multi jump series emits one successful-save event for next draft only`() {
        for (protocol in listOf(ProtocolId.SJ, ProtocolId.HORIZONTAL)) {
            AppSession.begin(protocol, athleteId = 1L, targetAttempts = 3)
            val firstKey = AppSession.draft.value!!.sessionKey
            assertNull(AppSession.takeSeriesFeedback(firstKey))
            assertTrue(AppSession.prepareNextSeriesAttempt())
            val secondKey = AppSession.draft.value!!.sessionKey
            assertNull(AppSession.takeSeriesFeedback(firstKey))
            assertEquals(SeriesFeedback(secondKey, 1, 3), AppSession.takeSeriesFeedback(secondKey))
            assertNull(AppSession.takeSeriesFeedback(secondKey))
            assertNull(AppSession.completedSeriesFeedback())

            assertTrue(AppSession.prepareNextSeriesAttempt())
            val thirdKey = AppSession.draft.value!!.sessionKey
            assertEquals(SeriesFeedback(thirdKey, 2, 3), AppSession.takeSeriesFeedback(thirdKey))
            assertFalse(AppSession.prepareNextSeriesAttempt())
            assertNull(AppSession.takeSeriesFeedback(thirdKey))
            assertEquals(SeriesFeedback(thirdKey, 3, 3, completed = true), AppSession.completedSeriesFeedback())
            AppSession.reset()
        }
    }

    @Test
    fun `single jump and cancelled series do not leak feedback into another protocol`() {
        AppSession.begin(ProtocolId.SJ, athleteId = 1L)
        assertFalse(AppSession.prepareNextSeriesAttempt())
        assertNull(AppSession.completedSeriesFeedback())
        AppSession.begin(ProtocolId.CMJ, athleteId = 1L, targetAttempts = 5)
        assertTrue(AppSession.prepareNextSeriesAttempt())
        val oldKey = AppSession.draft.value!!.sessionKey
        AppSession.begin(ProtocolId.DROP_JUMP, ProtocolSetup(dropHeightCm = 30.0), athleteId = 1L, targetAttempts = 2)
        assertNull(AppSession.takeSeriesFeedback(oldKey))
        assertNull(AppSession.takeSeriesFeedback(AppSession.draft.value!!.sessionKey))
    }

    @Test
    fun `next series attempt gets a new UUID and preserves setup and context`() {
        val setup = ProtocolSetup(side = MeasurementSide.LEFT)
        AppSession.begin(
            ProtocolId.UNILATERAL,
            setup = setup,
            athleteId = 7L,
            testingSessionId = 41L,
            testingParticipantIds = setOf(7L),
            athleteAnthropometrics = AthleteAnthropometrics(weightKg = 72.5, heightCm = 181.0),
            targetAttempts = 2,
        )
        val first = AppSession.draft.value!!

        assertTrue(AppSession.prepareNextSeriesAttempt())

        val second = AppSession.draft.value!!
        assertNotEquals(first.sessionKey, second.sessionKey)
        assertEquals(first.protocolId, second.protocolId)
        assertEquals(first.athleteId, second.athleteId)
        assertEquals(first.testingSessionId, second.testingSessionId)
        assertEquals(first.athleteAnthropometrics, second.athleteAnthropometrics)
        assertEquals(first.setup, second.setup)
        assertEquals(first.source, second.source)
        assertEquals(first.videoUri, second.videoUri)
        assertTrue(second.events.isEmpty())
        assertEquals(0, AppSession.currentFrameIndex.value)
        assertEquals(2, AppSession.seriesAttempt.value)
        assertEquals(2, AppSession.seriesTargetAttempts.value)
    }

    @Test
    fun `series resumes at landing instead of the current cursor`() {
        val landingKey = EventKey(EventType.LANDING)
        val temporalDraft = com.openjump.app.protocol.MeasurementDraft(
            sessionKey = "session",
            protocolId = ProtocolId.SJ,
            athleteId = 1L,
            events = mapOf(landingKey to EventMark(landingKey, ptsUs = 500_000L, frameIndex = 500)),
        )
        val horizontalDraft = com.openjump.app.protocol.MeasurementDraft(
            sessionKey = "horizontal-session",
            protocolId = ProtocolId.HORIZONTAL,
            athleteId = 1L,
            horizontalJump = com.openjump.app.protocol.HorizontalJumpDraft(
                landingHeel = com.openjump.app.protocol.SpatialMark(
                    type = com.openjump.app.protocol.SpatialMarkType.LANDING_HEEL,
                    point = com.openjump.app.tracking.ImagePoint(10.0, 20.0),
                    frameIndex = 500,
                    ptsUs = 500_000L,
                ),
            ),
        )

        // The cursor moved to 123 after landing 500 was marked; the transition
        // must resume on the landing frame, never on the next frame.
        assertEquals(500, seriesResumeFrameIndex(temporalDraft, cursorFrameIndex = 123))
        assertEquals(500, seriesResumeFrameIndex(horizontalDraft, cursorFrameIndex = 123))
        assertEquals(123, seriesResumeFrameIndex(temporalDraft.copy(events = emptyMap()), cursorFrameIndex = 123))
    }

    @Test
    fun `series ends after target and ephemeral state resets for encoder`() {
        AppSession.begin(ProtocolId.SJ, athleteId = 1L, targetAttempts = 2)
        assertTrue(AppSession.prepareNextSeriesAttempt())
        assertFalse(AppSession.prepareNextSeriesAttempt())
        assertEquals(2, AppSession.seriesAttempt.value)

        AppSession.beginEncoder(EncoderSetup(EncoderExercise.SQUAT, 100.0), athleteId = 1L)
        assertEquals(1, AppSession.seriesAttempt.value)
        assertEquals(1, AppSession.seriesTargetAttempts.value)

        AppSession.reset()
        assertEquals(1, AppSession.seriesAttempt.value)
        assertEquals(1, AppSession.seriesTargetAttempts.value)
    }

    @Test
    fun `bilateral starts as an ephemeral unilateral left draft`() {
        AppSession.beginBilateral(targetAttempts = 2, athleteId = 1L)
        assertEquals(MeasurementSide.LEFT, AppSession.bilateral.value!!.currentSide)
        assertEquals(1, AppSession.bilateral.value!!.currentSideAttempt)
        assertEquals(ProtocolId.UNILATERAL, AppSession.draft.value!!.protocolId)
        assertEquals(MeasurementSide.LEFT, AppSession.draft.value!!.setup.side)
        AppSession.reset()
        assertNull(AppSession.bilateral.value)
    }

    @Test
    fun `bilateral three attempts per side report progress and complete automatically`() {
        AppSession.beginBilateral(targetAttempts = 3, athleteId = 1L)
        installBilateralFrameIndex()
        val rootKey = requireNotNull(AppSession.bilateral.value).sessionKey

        for (attempt in 1..3) {
            val sideBefore = requireNotNull(AppSession.bilateral.value).currentSide
            assertEquals(MeasurementSide.LEFT, sideBefore)
            val activeKey = requireNotNull(AppSession.draft.value).sessionKey
            markRemainingUnilateralEvents((attempt - 1) * 3)

            assertEquals(
                BilateralAdvance.CONTINUE,
                AppSession.acceptBilateralAttempt(activeKey, bilateralResult(20.0 + attempt), detectedFps = 30),
            )

            val state = requireNotNull(AppSession.bilateral.value)
            assertEquals(attempt, state.completedCount(MeasurementSide.LEFT))
            assertEquals(0, state.completedCount(MeasurementSide.RIGHT))
            assertEquals(if (attempt == 3) MeasurementSide.RIGHT else MeasurementSide.LEFT, state.currentSide)
            assertEquals(if (attempt == 3) 1 else attempt + 1, state.currentSideAttempt)
            // Repeated StateFlow reads do not consume or recreate feedback.
            assertEquals(state, AppSession.bilateral.value)
            assertNull(AppSession.takeBilateralFeedback("not-$rootKey"))
            val feedback = requireNotNull(AppSession.takeBilateralFeedback(rootKey))
            assertEquals(attempt, feedback.ordinal)
            assertEquals(MeasurementSide.LEFT, feedback.side)
            assertEquals(attempt, feedback.completedCount)
            assertEquals(3, feedback.targetAttempts)
            assertEquals(
                if (attempt == 3) BilateralFeedbackKind.SIDE_COMPLETE else BilateralFeedbackKind.ATTEMPT,
                feedback.kind,
            )
            assertEquals(if (attempt == 3) MeasurementSide.RIGHT else null, feedback.nextSide)
            assertNull(AppSession.takeBilateralFeedback(rootKey))
        }

        for (attempt in 1..3) {
            val activeKey = requireNotNull(AppSession.draft.value).sessionKey
            markRemainingUnilateralEvents(9 + (attempt - 1) * 3)
            val expectedAdvance = if (attempt == 3) BilateralAdvance.COMPLETED else BilateralAdvance.CONTINUE
            assertEquals(expectedAdvance, AppSession.acceptBilateralAttempt(activeKey, bilateralResult(30.0 + attempt), 30))

            val state = requireNotNull(AppSession.bilateral.value)
            assertEquals(3, state.completedCount(MeasurementSide.LEFT))
            assertEquals(attempt, state.completedCount(MeasurementSide.RIGHT))
            val feedback = requireNotNull(AppSession.takeBilateralFeedback(rootKey))
            assertEquals(3 + attempt, feedback.ordinal)
            assertEquals(MeasurementSide.RIGHT, feedback.side)
            assertEquals(attempt, feedback.completedCount)
            assertEquals(if (attempt == 3) BilateralFeedbackKind.COMPLETE else BilateralFeedbackKind.ATTEMPT, feedback.kind)
            assertNull(AppSession.takeBilateralFeedback(rootKey))
        }

        val comparison = requireNotNull(AppSession.bilateralComparison())
        assertTrue(requireNotNull(AppSession.bilateral.value).completed)
        assertEquals(6, comparison.attempts.size)
        assertEquals(List(3) { MeasurementSide.LEFT } + List(3) { MeasurementSide.RIGHT }, comparison.attempts.map { it.side })
        assertEquals(listOf(1, 2, 3, 1, 2, 3), comparison.attempts.map { it.sideOrdinal })
    }

    @Test
    fun `bilateral may start right first but accepts only one attempt per side`() {
        AppSession.beginBilateral(targetAttempts = 1, athleteId = 1L)
        installBilateralFrameIndex()
        val rootKey = requireNotNull(AppSession.bilateral.value).sessionKey

        assertTrue(AppSession.selectBilateralSide(MeasurementSide.RIGHT))
        assertEquals(MeasurementSide.RIGHT, AppSession.bilateral.value!!.currentSide)
        assertEquals(1, AppSession.bilateral.value!!.currentSideAttempt)
        assertEquals(0, AppSession.bilateral.value!!.completedCount(MeasurementSide.LEFT))
        assertEquals(0, AppSession.bilateral.value!!.completedCount(MeasurementSide.RIGHT))
        assertNull(AppSession.takeBilateralFeedback(rootKey)) // Manual selection is not an acceptance event.

        val rightKey = requireNotNull(AppSession.draft.value).sessionKey
        markRemainingUnilateralEvents(0)
        assertEquals(BilateralAdvance.CONTINUE, AppSession.acceptBilateralAttempt(rightKey, bilateralResult(28.0), 30))
        val sideComplete = requireNotNull(AppSession.takeBilateralFeedback(rootKey))
        assertEquals(BilateralFeedbackKind.SIDE_COMPLETE, sideComplete.kind)
        assertEquals(MeasurementSide.RIGHT, sideComplete.side)
        assertEquals(MeasurementSide.LEFT, sideComplete.nextSide)
        assertEquals(1, AppSession.bilateral.value!!.completedCount(MeasurementSide.RIGHT))
        assertEquals(0, AppSession.bilateral.value!!.completedCount(MeasurementSide.LEFT))
        assertNull(AppSession.takeBilateralFeedback(rootKey))

        val leftKey = requireNotNull(AppSession.draft.value).sessionKey
        // Re-submitting the consumed result is an idempotent no-op, not a second attempt/event.
        assertEquals(BilateralAdvance.CONTINUE, AppSession.acceptBilateralAttempt(rightKey, bilateralResult(28.0), 30))
        assertEquals(1, AppSession.bilateral.value!!.attempts.size)
        assertEquals(MeasurementSide.LEFT, AppSession.bilateral.value!!.currentSide)
        assertEquals(leftKey, AppSession.draft.value!!.sessionKey)
        assertNull(AppSession.takeBilateralFeedback(rootKey))
        assertFalse(AppSession.selectBilateralSide(MeasurementSide.RIGHT)) // The completed side cannot be selected again.

        markRemainingUnilateralEvents(3)
        assertEquals(BilateralAdvance.COMPLETED, AppSession.acceptBilateralAttempt(leftKey, bilateralResult(25.0), 30))
        assertEquals(BilateralFeedbackKind.COMPLETE, AppSession.takeBilateralFeedback(rootKey)!!.kind)
        assertNull(AppSession.takeBilateralFeedback(rootKey))
        val comparison = requireNotNull(AppSession.bilateralComparison())
        assertEquals(listOf(MeasurementSide.LEFT, MeasurementSide.RIGHT), comparison.attempts.map { it.side })
        assertEquals(listOf(25.0, 28.0), comparison.attempts.map { it.result.primaryMetric.value })
    }

    @Test
    fun `interleaved bilateral switching preserves partial drafts and comparison sorts canonically`() {
        AppSession.beginBilateral(targetAttempts = 2, athleteId = 1L)
        installBilateralFrameIndex()
        val rootKey = requireNotNull(AppSession.bilateral.value).sessionKey

        AppSession.setFrameIndex(2)
        AppSession.markAt(com.openjump.app.protocol.EventType.MOVEMENT_START, 2)
        val leftOneKey = requireNotNull(AppSession.draft.value).sessionKey
        assertTrue(AppSession.selectBilateralSide(MeasurementSide.RIGHT))
        AppSession.setFrameIndex(5)
        AppSession.markAt(com.openjump.app.protocol.EventType.MOVEMENT_START, 5)
        assertTrue(AppSession.selectBilateralSide(MeasurementSide.LEFT))
        assertEquals(leftOneKey, AppSession.draft.value!!.sessionKey)
        assertEquals(2, AppSession.currentFrameIndex.value)
        assertEquals(2, AppSession.draft.value!!.events.values.single().frameIndex)
        markRemainingUnilateralEvents(2)
        assertEquals(BilateralAdvance.CONTINUE, AppSession.acceptBilateralAttempt(leftOneKey, bilateralResult(20.0), 30))

        // Partially mark LEFT 2/2, then resume RIGHT 1/2 with its own partial mark.
        AppSession.setFrameIndex(10)
        AppSession.markAt(com.openjump.app.protocol.EventType.MOVEMENT_START, 10)
        assertTrue(AppSession.selectBilateralSide(MeasurementSide.RIGHT))
        assertEquals(MeasurementSide.RIGHT, AppSession.draft.value!!.setup.side)
        assertEquals(5, AppSession.currentFrameIndex.value)
        assertEquals(5, AppSession.draft.value!!.events.values.single().frameIndex)
        markRemainingUnilateralEvents(5)
        val rightOneKey = AppSession.draft.value!!.sessionKey
        assertEquals(BilateralAdvance.CONTINUE, AppSession.acceptBilateralAttempt(rightOneKey, bilateralResult(30.0), 30))

        assertTrue(AppSession.selectBilateralSide(MeasurementSide.LEFT))
        assertEquals(10, AppSession.currentFrameIndex.value)
        assertEquals(10, AppSession.draft.value!!.events.values.single().frameIndex)
        val leftTwoKey = AppSession.draft.value!!.sessionKey
        markRemainingUnilateralEvents(10)
        assertEquals(BilateralAdvance.CONTINUE, AppSession.acceptBilateralAttempt(leftTwoKey, bilateralResult(22.0), 30))

        val rightTwoKey = AppSession.draft.value!!.sessionKey
        markRemainingUnilateralEvents(13)
        assertEquals(BilateralAdvance.COMPLETED, AppSession.acceptBilateralAttempt(rightTwoKey, bilateralResult(32.0), 30))
        assertEquals(BilateralFeedbackKind.COMPLETE, AppSession.takeBilateralFeedback(rootKey)!!.kind)

        val comparison = requireNotNull(AppSession.bilateralComparison())
        assertEquals(
            listOf(MeasurementSide.LEFT, MeasurementSide.LEFT, MeasurementSide.RIGHT, MeasurementSide.RIGHT),
            comparison.attempts.map { it.side },
        )
        assertEquals(listOf(1, 2, 1, 2), comparison.attempts.map { it.sideOrdinal })
        assertEquals(listOf(20.0, 22.0, 30.0, 32.0), comparison.attempts.map { it.result.primaryMetric.value })
    }

    @Test
    fun `notes stay on the active root and do not copy to next or bilateral attempts`() {
        AppSession.begin(ProtocolId.SJ, athleteId = 1L, targetAttempts = 2)
        AppSession.updateMeasurementNote("first")
        assertEquals("first", AppSession.draft.value!!.notes)
        assertTrue(AppSession.prepareNextSeriesAttempt())
        assertNull(AppSession.draft.value!!.notes)

        AppSession.beginBilateral(targetAttempts = 1, athleteId = 1L)
        assertNull(AppSession.draft.value!!.notes)
        AppSession.updateBilateralNote("root note")
        assertEquals("root note", AppSession.bilateral.value!!.notes)
        assertNull(AppSession.draft.value!!.notes)
    }

    @Test
    fun `basic series and bilateral attempts complete with no movement start`() {
        val index = VideoFrameIndex(
            longArrayOf(0L, 300_000L, 900_000L, 1_500_000L, 2_100_000L),
            2_100_000L, 100, 100, 0, "video/avc",
        )
        fun markFlight(takeoffFrame: Int, landingFrame: Int): ProtocolResult {
            AppSession.markAt(EventType.TAKEOFF, takeoffFrame)
            AppSession.markAt(EventType.LANDING, landingFrame)
            val draft = requireNotNull(AppSession.draft.value)
            assertFalse(draft.markMovementStart)
            assertEquals(setOf(EventKey(EventType.TAKEOFF), EventKey(EventType.LANDING)), draft.events.keys)
            assertTrue(draft.isComplete())
            assertNull(draft.nextRequiredEvent())
            assertNull(draft.validationIssue())
            return com.openjump.app.math.ProtocolCalculator.compute(draft).also { result ->
                assertFalse(result.allMetrics.any { it.key == MetricKey.TIME_TO_TAKEOFF_MS || it.key == MetricKey.RSI_MOD })
            }
        }

        AppSession.begin(ProtocolId.CMJ, athleteId = 1L, targetAttempts = 2)
        installFrameIndex(index)
        markFlight(1, 2)
        assertTrue(AppSession.prepareNextSeriesAttempt())
        assertEquals(EventType.TAKEOFF, AppSession.draft.value!!.nextRequiredEvent())
        markFlight(3, 4)
        assertFalse(AppSession.prepareNextSeriesAttempt())

        AppSession.beginBilateral(targetAttempts = 1, athleteId = 1L)
        installFrameIndex(index)
        val left = markFlight(1, 2)
        assertEquals(BilateralAdvance.CONTINUE, AppSession.acceptBilateralAttempt(left, 30))
        assertEquals(MeasurementSide.RIGHT, AppSession.draft.value!!.setup.side)
        val right = markFlight(3, 4)
        assertEquals(BilateralAdvance.COMPLETED, AppSession.acceptBilateralAttempt(right, 30))
        val comparison = requireNotNull(AppSession.bilateralComparison())
        assertEquals(2, comparison.attempts.size)
        assertTrue(comparison.attempts.all { it.draft.events.size == 2 && !it.draft.markMovementStart })
    }

    @Test
    fun `marking mode snapshot covers series legs and bilateral drafts`() {
        AppSession.begin(ProtocolId.CMJ, athleteId = 1L)
        assertFalse(AppSession.draft.value!!.markMovementStart)
        assertEquals(EventType.TAKEOFF, AppSession.draft.value!!.nextRequiredEvent())

        AppSession.begin(ProtocolId.CMJ, athleteId = 1L, targetAttempts = 2, markMovementStart = true)
        assertTrue(AppSession.draft.value!!.markMovementStart)
        assertEquals(EventType.MOVEMENT_START, AppSession.draft.value!!.nextRequiredEvent())
        assertTrue(AppSession.prepareNextSeriesAttempt())
        assertTrue(AppSession.draft.value!!.markMovementStart)
        assertEquals(EventType.MOVEMENT_START, AppSession.draft.value!!.nextRequiredEvent())
        assertTrue(AppSession.draft.value!!.events.isEmpty())

        AppSession.beginBilateral(targetAttempts = 1, athleteId = 1L)
        assertFalse(requireNotNull(AppSession.draft.value).markMovementStart)

        AppSession.beginBilateral(targetAttempts = 1, athleteId = 1L, markMovementStart = true)
        installBilateralFrameIndex()
        assertTrue(requireNotNull(AppSession.draft.value).markMovementStart)
        assertTrue(AppSession.selectBilateralSide(MeasurementSide.RIGHT))
        assertTrue(AppSession.draft.value!!.markMovementStart)
        assertTrue(AppSession.selectBilateralSide(MeasurementSide.LEFT))
        assertTrue(AppSession.draft.value!!.markMovementStart)

        val leftKey = requireNotNull(AppSession.draft.value).sessionKey
        markRemainingUnilateralEvents(0)
        assertEquals(
            BilateralAdvance.CONTINUE,
            AppSession.acceptBilateralAttempt(leftKey, bilateralResult(28.0), detectedFps = 30),
        )
        // The next leg draft is created from the session snapshot, never from a current default.
        assertEquals(MeasurementSide.RIGHT, AppSession.draft.value!!.setup.side)
        assertTrue(AppSession.draft.value!!.markMovementStart)
        assertEquals(EventType.MOVEMENT_START, AppSession.draft.value!!.nextRequiredEvent())
    }

    @Test
    fun `new session starts at first required event`() {
        AppSession.begin(ProtocolId.SJ, athleteId = 1L)
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(100L, 4_200L, 8_700L),
            durationUs = 8_700L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
        )
        val draft = AppSession.draft.value!!
        assertEquals(EventType.TAKEOFF, draft.nextRequiredEvent())
        assertFalse(draft.isComplete())
        assertEquals(3, index.frameCount)
    }

    @Test
    fun `measurement draft validates ordered protocol events`() {
        AppSession.begin(ProtocolId.DROP_JUMP, ProtocolSetup(dropHeightCm = 40.0), athleteId = 1L)
        val required = AppSession.draft.value!!.definition.requiredEvents
        assertEquals(
            listOf(EventType.INITIAL_CONTACT, EventType.TAKEOFF, EventType.LANDING),
            required,
        )
        assertTrue(EventKey(EventType.INITIAL_CONTACT) !in AppSession.draft.value!!.events)
    }

    @Test
    fun `encoder and protocol drafts are mutually exclusive`() {
        AppSession.beginEncoder(EncoderSetup(EncoderExercise.DEADLIFT, 120.0), athleteId = 1L)
        assertNull(AppSession.draft.value)
        assertEquals(EncoderExercise.DEADLIFT, AppSession.encoderDraft.value!!.setup.exercise)

        AppSession.setEncoderTiming(VideoTimingDecision(VideoTimingMode.REAL_TIME))
        assertEquals(VideoTimingMode.REAL_TIME, AppSession.encoderDraft.value!!.timingDecision!!.mode)

        AppSession.begin(ProtocolId.SJ, athleteId = 1L)
        assertNull(AppSession.encoderDraft.value)
        assertEquals(ProtocolId.SJ, AppSession.draft.value!!.protocolId)
    }

    @Test
    fun `timing seam resolves recorded source to real time`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 33_333L, 66_666L),
            durationUs = 66_666L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            nominalFrameRate = 240.0,
        )

        assertEquals(
            VideoTimingMode.REAL_TIME,
            resolveEncoderTiming(null, VideoSource.RECORDED, index).mode,
        )
    }

    @Test
    fun `timing seam infers imported slow motion from capture metadata`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 16_667L, 33_334L),
            durationUs = 33_334L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            metadataCaptureFrameRate = 240.0,
        )

        val decision = resolveEncoderTiming(null, VideoSource.IMPORTED, index)
        assertEquals(VideoTimingMode.SLOW_MOTION, decision.mode)
        assertEquals(4.0, decision.slowMotionFactor, 0.0)
    }

    @Test
    fun `timing seam preserves a manual decision`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 16_667L, 33_334L),
            durationUs = 33_334L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            metadataCaptureFrameRate = 240.0,
        )
        val manual = VideoTimingDecision(VideoTimingMode.UNKNOWN)

        assertEquals(manual, resolveEncoderTiming(manual, VideoSource.IMPORTED, index))
    }

    @Test
    fun `encoder copies global plate diameter and session override does not leak`() {
        AppSession.beginEncoder(EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0), defaultPlateDiameterCm = 45.0, athleteId = 1L)
        assertEquals(45.0, AppSession.encoderDraft.value!!.sessionPlateDiameterCm, 0.0)

        AppSession.setSessionPlateDiameterCm(40.0)
        assertEquals(40.0, AppSession.encoderDraft.value!!.sessionPlateDiameterCm, 0.0)

        AppSession.beginEncoder(EncoderSetup(EncoderExercise.SQUAT, 100.0), defaultPlateDiameterCm = 45.0, athleteId = 1L)
        assertEquals(45.0, AppSession.encoderDraft.value!!.sessionPlateDiameterCm, 0.0)
    }

    @Test
    fun `manual circle stores exact geometry and physical diameter recalculates scale`() {
        AppSession.beginEncoder(EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0), athleteId = 1L)
        installFrameIndex(
            VideoFrameIndex(
                frameTimesUs = longArrayOf(0L, 10_000L),
                durationUs = 10_000L,
                width = 1000,
                height = 1000,
                rotationDegrees = 0,
                mimeType = "video/avc",
            ),
        )

        AppSession.applyManualPlateCircle(
            center = ImagePoint(500.0, 500.0),
            diameterPx = 400.0,
            angleRadians = 0.0,
            visibleFrameIndex = 0,
        )
        val first = requireNotNull(AppSession.encoderDraft.value)
        assertFalse(first.automaticPlateCalibration)
        assertEquals(0.45 / 400.0, first.calibration!!.metersPerPixel, 1e-12)
        assertEquals(500.0 - 200.0, first.calibrationPointA!!.x, 0.0)

        AppSession.setSessionPlateDiameterCm(40.0)
        val updated = requireNotNull(AppSession.encoderDraft.value)
        assertFalse(updated.automaticPlateCalibration)
        assertEquals(0.40 / 400.0, updated.calibration!!.metersPerPixel, 1e-12)
        assertTrue(updated.tracking.isEmpty())
    }

    @Test
    fun `manual circle outside presentation frame is rejected`() {
        AppSession.beginEncoder(EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0), athleteId = 1L)
        installFrameIndex(VideoFrameIndex(longArrayOf(0L), 0L, 100, 100, 0, "video/avc"))
        assertThrows(IllegalArgumentException::class.java) {
            AppSession.applyManualPlateCircle(ImagePoint(2.0, 50.0), 20.0, 0.0, 0)
        }
    }

    @Test
    fun `encoder rejects absurd plate diameter overrides`() {
        AppSession.beginEncoder(EncoderSetup(EncoderExercise.DEADLIFT, 120.0), athleteId = 1L)

        assertThrows(IllegalArgumentException::class.java) { AppSession.setSessionPlateDiameterCm(0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            AppSession.setSessionPlateDiameterCm(EncoderSettings.MAXIMUM_PLATE_DIAMETER_CM + 1.0)
        }
    }

    @Test
    fun `horizontal protocol starts with an isolated spatial draft`() {
        AppSession.begin(ProtocolId.HORIZONTAL, athleteId = 1L)

        assertEquals(ProtocolId.HORIZONTAL, AppSession.draft.value!!.protocolId)
        assertEquals(com.openjump.app.protocol.HorizontalJumpStage.CALIBRATION, AppSession.draft.value!!.horizontalJump!!.stage)
        assertTrue(AppSession.draft.value!!.events.isEmpty())
    }

    @Test
    fun `explicit reassignment changes ownership and its anthropometric snapshot`() {
        AppSession.begin(ProtocolId.SJ, athleteId = 7L)
        val before = AppSession.draft.value!!
        AppSession.reassignActiveAthlete(
            11L,
            AthleteAnthropometrics(weightKg = 80.0, heightCm = 190.0),
        )
        val after = AppSession.draft.value!!
        assertEquals(11L, after.athleteId)
        assertEquals(before.sessionKey, after.sessionKey)
        assertEquals(before.setup, after.setup)
        assertEquals(before.events, after.events)
        assertEquals(80.0, after.athleteAnthropometrics.weightKg!!, 0.0)
        assertEquals(190.0, after.athleteAnthropometrics.heightCm!!, 0.0)
    }

    @Test
    fun `testing reassignment preserves context and rejects athletes outside frozen roster`() {
        val setup = ProtocolSetup(side = MeasurementSide.LEFT)
        AppSession.begin(
            ProtocolId.UNILATERAL,
            setup = setup,
            athleteId = 7L,
            testingSessionId = 41L,
            testingParticipantIds = setOf(7L, 11L),
        )
        val before = AppSession.draft.value!!

        AppSession.reassignActiveAthlete(11L)
        val after = AppSession.draft.value!!

        assertEquals(before.sessionKey, after.sessionKey)
        assertEquals(before.protocolId, after.protocolId)
        assertEquals(before.setup, after.setup)
        assertEquals(41L, after.testingSessionId)
        assertEquals(11L, after.athleteId)
        assertThrows(IllegalArgumentException::class.java) {
            AppSession.reassignActiveAthlete(99L)
        }
        assertEquals(11L, AppSession.draft.value!!.athleteId)
    }

    @Test
    fun `encoder testing reassignment preserves setup plate and testing context`() {
        val setup = EncoderSetup(EncoderExercise.SQUAT, 90.0)
        AppSession.beginEncoder(
            setup = setup,
            defaultPlateDiameterCm = 44.0,
            athleteId = 3L,
            testingSessionId = 52L,
            testingParticipantIds = setOf(3L, 4L),
        )
        val before = AppSession.encoderDraft.value!!

        AppSession.reassignActiveAthlete(4L)
        val after = AppSession.encoderDraft.value!!

        assertEquals(before.sessionKey, after.sessionKey)
        assertEquals(setup, after.setup)
        assertEquals(44.0, after.sessionPlateDiameterCm, 0.0)
        assertEquals(52L, after.testingSessionId)
        assertEquals(4L, after.athleteId)
    }

    @Test
    fun `begin rejects invalid athlete ownership`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppSession.begin(ProtocolId.SJ, athleteId = 0L)
        }
    }

    @Test
    fun `coming soon protocol cannot begin`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppSession.begin(ProtocolId.REPEATED_10_5, athleteId = 1L)
        }
    }
}
