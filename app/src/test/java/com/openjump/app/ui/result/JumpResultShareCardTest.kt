package com.openjump.app.ui.result

import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetric
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderMetricUnit
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.MetricCalibrator
import com.openjump.app.encoder.MetricValidity
import com.openjump.app.encoder.MovementDirection
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.StoredEvent
import com.openjump.app.settings.UnitProfile
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpResultShareCardTest {

    private val baseCard = JumpResultShareCardData(
        protocolTitle = "Countermovement Jump",
        methodLabel = "Flight time",
        primaryLabel = "Jump height",
        primaryValue = "42.3 cm",
        secondaryMetrics = listOf(
            JumpResultShareMetric("Flight time", "587 ms"),
            JumpResultShareMetric("Takeoff velocity", "2.88 m/s"),
        ),
        dateLabel = "2026-11-24",
        athleteName = "Alex Rivera",
        footerLabel = "Measured offline with OpenJump · Open Source",
    )

    @Test
    fun `withPrivacy strips athlete name when disabled`() {
        val anonymous = baseCard.withPrivacy(includeAthleteName = false)
        assertNull(anonymous.athleteName)
        assertEquals(baseCard.primaryValue, anonymous.primaryValue)
        assertEquals(baseCard.secondaryMetrics, anonymous.secondaryMetrics)
    }

    @Test
    fun `withPrivacy preserves athlete name when enabled`() {
        val named = baseCard.withPrivacy(includeAthleteName = true)
        assertEquals("Alex Rivera", named.athleteName)
    }

    @Test
    fun `applyOptions respects athlete name, repetition chart, and secondary metrics toggles`() {
        val cardWithChart = baseCard.copy(
            repetitionChartTitle = "Velocity per rep (m/s)",
            repetitionBars = listOf(
                ShareCardRepetitionBar(1, 0.90, "0.90"),
                ShareCardRepetitionBar(2, 0.78, "0.78"),
            ),
        )
        val minimal = cardWithChart.applyOptions(
            ShareCardOptions(
                aspect = ShareCardAspect.STORY_9_16,
                lightTheme = true,
                includePhotos = false,
                includeRepetitionChart = false,
                includeSecondaryMetrics = false,
                includeAthleteName = false,
                includeBranding = false,
            ),
        )
        assertNull(minimal.athleteName)
        assertTrue(minimal.secondaryMetrics.isEmpty())
        assertTrue(minimal.repetitionBars.isEmpty())
        assertEquals("42.3 cm", minimal.primaryValue)

        val full = cardWithChart.applyOptions(
            ShareCardOptions(
                lightTheme = true,
                includeRepetitionChart = true,
                includeSecondaryMetrics = true,
                includeAthleteName = true,
            ),
        )
        assertEquals("Alex Rivera", full.athleteName)
        assertEquals(2, full.secondaryMetrics.size)
        assertEquals(2, full.repetitionBars.size)
    }

    @Test
    fun `extractJumpEstimatedPowerMetrics selects only Sayers peak and relative power and applyOptions gates them`() {
        val estimates = listOf(
            MetricValue(MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W, 4620.0, MetricUnit.WATT),
            MetricValue(MetricKey.ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG, 61.6, MetricUnit.WATT_PER_KILOGRAM),
            MetricValue(MetricKey.ESTIMATED_POTENTIAL_ENERGY_J, 310.0, MetricUnit.JOULE),
            MetricValue(MetricKey.ESTIMATED_TAKEOFF_KINETIC_ENERGY_J, 310.0, MetricUnit.JOULE),
            MetricValue(MetricKey.ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS, 215.0, MetricUnit.KILOGRAM_METER_PER_SECOND),
        )
        val extracted = extractJumpEstimatedPowerMetrics(
            estimatedMetrics = estimates,
            peakPowerLabel = "Est. peak power",
            relativePowerLabel = "Est. rel. power",
            locale = Locale.US,
            unitSystem = UnitProfile.METRIC,
        )
        assertEquals(2, extracted.size)
        assertEquals("Est. peak power", extracted[0].label)
        assertEquals("Est. rel. power", extracted[1].label)

        val cardWithPower = baseCard.copy(
            estimatedPowerMetrics = extracted,
            estimatedPowerNote = "Est. Sayers (1999)",
        )
        val hidden = cardWithPower.applyOptions(ShareCardOptions(includeEstimatedPower = false))
        assertTrue(hidden.estimatedPowerMetrics.isEmpty())
        assertNull(hidden.estimatedPowerNote)

        val shown = cardWithPower.applyOptions(ShareCardOptions(includeEstimatedPower = true))
        assertEquals(2, shown.estimatedPowerMetrics.size)
        assertEquals("Est. Sayers (1999)", shown.estimatedPowerNote)
    }

    @Test
    fun `selectJumpShareFrameRequests picks first and last distinct marks`() {
        val marks = listOf(
            EventMark(EventKey(EventType.MOVEMENT_START), ptsUs = 100_000L, frameIndex = 6),
            EventMark(EventKey(EventType.TAKEOFF), ptsUs = 400_000L, frameIndex = 24),
            EventMark(EventKey(EventType.LANDING), ptsUs = 980_000L, frameIndex = 58),
        )
        val requests = selectJumpShareFrameRequests(marks) { it.key.type.name }
        assertEquals(
            listOf(
                ShareCardFrameRequest("MOVEMENT_START", 100_000L),
                ShareCardFrameRequest("LANDING", 980_000L),
            ),
            requests,
        )
    }

    @Test
    fun `selectStoredJumpShareFrameRequests sorts events by timestamp`() {
        val events = listOf(
            StoredEvent(EventType.LANDING, 0, ptsUs = 950_000L),
            StoredEvent(EventType.TAKEOFF, 0, ptsUs = 350_000L),
        )
        val requests = selectStoredJumpShareFrameRequests(events) { it.type.name }
        assertEquals(
            listOf(
                ShareCardFrameRequest("TAKEOFF", 350_000L),
                ShareCardFrameRequest("LANDING", 950_000L),
            ),
            requests,
        )
    }

    @Test
    fun `selectEncoderShareFrameRequests picks best valid repetition concentric bounds`() {
        val samples = (0..10).map { i ->
            EncoderSample(
                ordinal = i,
                frameIndex = i,
                sourcePtsUs = i * 100_000L,
                physicalTimeUs = i * 100_000L,
                rawPixelPoint = null,
                rawMetricPoint = null,
                smoothedMetricPoint = null,
                velocityXMps = 0.0,
                velocityYMps = 0.0,
                confidence = 1.0,
                trackingStatus = TrackingStatus.TRACKING,
                observationKind = ObservationKind.MEASURED,
                fitResidualM = null,
                gapBeforeUs = null,
            )
        }
        val rep1 = EncoderRepetition(
            ordinal = 0,
            startSample = 1,
            endSample = 4,
            eccentricPhase = MovementPhase(MovementDirection.DOWN, 1, 2),
            concentricPhase = MovementPhase(MovementDirection.UP, 2, 4),
            quality = RepetitionQuality.VALID,
            reasons = emptySet(),
            metrics = listOf(EncoderMetric(EncoderMetricKey.MCV, 0.72, EncoderMetricUnit.METER_PER_SECOND, MetricValidity.VALID)),
        )
        val rep2 = EncoderRepetition(
            ordinal = 1,
            startSample = 5,
            endSample = 9,
            eccentricPhase = MovementPhase(MovementDirection.DOWN, 5, 6),
            concentricPhase = MovementPhase(MovementDirection.UP, 6, 9),
            quality = RepetitionQuality.VALID,
            reasons = emptySet(),
            metrics = listOf(EncoderMetric(EncoderMetricKey.MCV, 0.88, EncoderMetricUnit.METER_PER_SECOND, MetricValidity.VALID)),
        )
        val analysis = EncoderAnalysis(
            setup = EncoderSetup(EncoderExercise.SQUAT, 80.0),
            timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration = MetricCalibrator.create(0, ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 0.45),
            samples = samples,
            repetitions = listOf(rep1, rep2),
            effectiveFps = 60.0,
            smoothingWindowSamples = 5,
            velocityEnterMps = 0.08,
            velocityExitMps = 0.04,
            warnings = emptyList(),
        )

        val requests = selectEncoderShareFrameRequests(analysis, "Start", "Lockout")
        assertEquals(
            listOf(
                ShareCardFrameRequest("Start", 600_000L),
                ShareCardFrameRequest("Lockout", 900_000L),
            ),
            requests,
        )
    }

    @Test
    fun `aspect presets define expected social media dimensions`() {
        assertEquals(1080, ShareCardAspect.PORTRAIT_4_5.widthPx)
        assertEquals(1350, ShareCardAspect.PORTRAIT_4_5.heightPx)
        assertEquals(1080, ShareCardAspect.STORY_9_16.widthPx)
        assertEquals(1920, ShareCardAspect.STORY_9_16.heightPx)
    }

    @Test
    fun `splitValueAndUnit separates numeric value from short unit cleanly`() {
        assertEquals("42,3" to "cm", JumpResultShareCardRenderer.splitValueAndUnit("42,3 cm"))
        assertEquals("0.78" to "m/s", JumpResultShareCardRenderer.splitValueAndUnit("0.78 m/s"))
        assertEquals("0,54" to null, JumpResultShareCardRenderer.splitValueAndUnit("0,54"))
        assertEquals("Rep 2" to null, JumpResultShareCardRenderer.splitValueAndUnit("Rep 2"))
    }
}
