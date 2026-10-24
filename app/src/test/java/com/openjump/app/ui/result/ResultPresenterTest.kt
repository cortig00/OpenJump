package com.openjump.app.ui.result

import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.HorizontalJumpDraft
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementMethod
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.StoredAttempt
import com.openjump.app.protocol.StoredEvent
import com.openjump.app.protocol.StoredMeasurement
import com.openjump.app.protocol.VideoSource
import com.openjump.app.settings.UnitSystem
import com.openjump.app.tracking.ImagePoint
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultPresenterTest {

    @Test
    fun `cmj timeline has preparation and flight from pts only`() {
        val ui = ResultPresenter.presentLive(
            draft = draft(
                ProtocolId.CMJ,
                ProtocolSetup(),
                EventType.MOVEMENT_START to 100_000L,
                EventType.TAKEOFF to 700_000L,
                EventType.LANDING to 1_300_000L,
            ),
            result = cmjResult(),
            detectedFps = 240,
        )

        assertEquals(
            listOf(TimelineSegmentKind.PREPARATION, TimelineSegmentKind.FLIGHT),
            ui.timeline!!.segments.map { it.kind },
        )
        assertEquals(listOf(600_000L, 600_000L), ui.timeline.segments.map { it.durationUs })
        assertEquals(1_200_000L, ui.timeline.totalDurationUs)
        assertEquals(240, ui.attempt.detectedFps)
        assertNull(ui.warning)
    }

    @Test
    fun `sj timeline contains only flight and does not invent tto`() {
        val ui = ResultPresenter.presentLive(
            draft = draft(
                ProtocolId.SJ,
                ProtocolSetup(),
                EventType.TAKEOFF to 700_000L,
                EventType.LANDING to 1_300_000L,
            ),
            result = ProtocolResult(
                ProtocolId.SJ,
                MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
                listOf(
                    MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND),
                    MetricValue(MetricKey.TAKEOFF_VELOCITY_MPS, 2.94, MetricUnit.METER_PER_SECOND),
                ),
            ),
            detectedFps = 0,
        )

        assertEquals(listOf(TimelineSegmentKind.FLIGHT), ui.timeline!!.segments.map { it.kind })
        assertEquals(600_000L, ui.timeline.totalDurationUs)
        assertEquals(0, ui.attempt.detectedFps)
        assertFalse(ui.secondaryMetrics.any { it.key == MetricKey.TIME_TO_TAKEOFF_MS })
        assertFalse(ui.secondaryMetrics.any { it.key == MetricKey.RSI_MOD })
    }

    @Test
    fun `drop jump timeline has contact then flight`() {
        val ui = ResultPresenter.presentLive(
            draft = draft(
                ProtocolId.DROP_JUMP,
                ProtocolSetup(dropHeightCm = 40.0),
                EventType.INITIAL_CONTACT to 100_000L,
                EventType.TAKEOFF to 300_000L,
                EventType.LANDING to 900_000L,
            ),
            result = ProtocolResult(
                ProtocolId.DROP_JUMP,
                MetricValue(MetricKey.RSI, 2.206, MetricUnit.METER_PER_SECOND),
                listOf(
                    MetricValue(MetricKey.CONTACT_TIME_MS, 200.0, MetricUnit.MILLISECOND),
                    MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
                    MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND),
                ),
            ),
            detectedFps = 240,
        )

        assertEquals(
            listOf(TimelineSegmentKind.CONTACT, TimelineSegmentKind.FLIGHT),
            ui.timeline!!.segments.map { it.kind },
        )
        assertEquals(listOf(200_000L, 600_000L), ui.timeline.segments.map { it.durationUs })
        assertEquals(40.0, ui.attempt.dropHeightCm)
    }

    @Test
    fun `stored and live equivalent results have same content`() {
        val live = ResultPresenter.presentLive(
            draft(
                ProtocolId.UNILATERAL,
                ProtocolSetup(side = MeasurementSide.LEFT),
                EventType.MOVEMENT_START to 100_000L,
                EventType.TAKEOFF to 700_000L,
                EventType.LANDING to 1_300_000L,
            ),
            cmjResult(ProtocolId.UNILATERAL),
            detectedFps = 240,
        )
        val stored = ResultPresenter.presentStored(
            StoredMeasurement(
                id = 5L,
                dateTime = 100L,
                protocolId = ProtocolId.UNILATERAL,
                primaryMetric = cmjResult(ProtocolId.UNILATERAL).primaryMetric,
                attempts = listOf(
                    StoredAttempt(
                        ordinal = 0,
                        side = MeasurementSide.LEFT,
                        source = VideoSource.IMPORTED,
                        videoUri = "content://video/5",
                        detectedFps = 240,
                        setup = ProtocolSetup(side = MeasurementSide.LEFT),
                        events = listOf(
                            StoredEvent(EventType.MOVEMENT_START, 0, 100_000L),
                            StoredEvent(EventType.TAKEOFF, 0, 700_000L),
                            StoredEvent(EventType.LANDING, 0, 1_300_000L),
                        ),
                        metrics = cmjResult(ProtocolId.UNILATERAL).allMetrics,
                    ),
                ),
            ),
        )

        assertEquals(live.primaryMetric, stored.primaryMetric)
        assertEquals(live.secondaryMetrics, stored.secondaryMetrics)
        assertEquals(live.timeline, stored.timeline)
        assertEquals(MeasurementSide.LEFT, stored.attempt.side)
        assertEquals(100L, stored.dateTime)
    }

    @Test
    fun `live and stored presenters expose the root note`() {
        val liveDraft = draft(
            ProtocolId.SJ,
            ProtocolSetup(),
            EventType.TAKEOFF to 100_000L,
            EventType.LANDING to 700_000L,
        ).copy(notes = "  live note  ")
        val result = ProtocolResult(
            ProtocolId.SJ,
            MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
            listOf(MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND)),
        )
        assertEquals("  live note  ", ResultPresenter.presentLive(liveDraft, result, 30).note)
        assertEquals(
            "stored note",
            ResultPresenter.presentStored(
                StoredMeasurement(
                    id = 1L,
                    dateTime = 2L,
                    protocolId = ProtocolId.SJ,
                    primaryMetric = result.primaryMetric,
                    attempts = listOf(
                        StoredAttempt(
                            ordinal = 0,
                            side = null,
                            source = VideoSource.IMPORTED,
                            videoUri = null,
                            detectedFps = 30,
                            setup = ProtocolSetup(),
                            events = listOf(
                                StoredEvent(EventType.TAKEOFF, 0, 100_000L),
                                StoredEvent(EventType.LANDING, 0, 700_000L),
                            ),
                            metrics = result.allMetrics,
                        ),
                    ),
                    notes = "stored note",
                ),
            ).note,
        )
    }

    @Test
    fun `horizontal live and stored results retain spatial context and calibrated method`() {
        val horizontal = HorizontalJumpDraft()
            .markCalibrationPoint(ImagePoint(0.0, 0.0), 2)
            .markCalibrationPoint(ImagePoint(200.0, 0.0), 2)
            .confirmCalibration(1.0)
            .markNextPoint(ImagePoint(10.0, 20.0), 3, 100_000L)
            .markNextPoint(ImagePoint(496.0, 90.0), 30, 700_000L)
        val measurementDraft = MeasurementDraft(
            sessionKey = "horizontal-result",
            protocolId = ProtocolId.HORIZONTAL,
            horizontalJump = horizontal,
        )
        val result = ProtocolCalculator.compute(measurementDraft)
        val live = ResultPresenter.presentLive(measurementDraft, result, detectedFps = 120)
        val stored = ResultPresenter.presentStored(
            StoredMeasurement(
                id = 10L,
                dateTime = 20L,
                protocolId = ProtocolId.HORIZONTAL,
                primaryMetric = result.primaryMetric,
                attempts = listOf(
                    StoredAttempt(
                        ordinal = 0,
                        side = null,
                        source = VideoSource.IMPORTED,
                        videoUri = "content://horizontal/10",
                        detectedFps = 120,
                        setup = ProtocolSetup(),
                        events = emptyList(),
                        metrics = result.allMetrics,
                        calibration = horizontal.calibration,
                        spatialMarks = listOf(horizontal.startPoint!!, horizontal.landingHeel!!),
                    ),
                ),
            ),
        )

        assertEquals(MeasurementMethod.MANUAL_CALIBRATED_DISTANCE, live.method)
        assertEquals(live.primaryMetric, stored.primaryMetric)
        assertEquals(live.horizontal, stored.horizontal)
        assertNull(live.timeline)
        assertNull(live.warning)
        assertEquals("2.43 m", ResultText.formatMetric(result.primaryMetric, Locale.US))
        assertEquals("2,43 m", ResultText.formatMetric(result.primaryMetric, Locale("es", "ES")))
        assertEquals(
            "7.97 ft",
            ResultText.formatMetric(result.primaryMetric, Locale.US, UnitSystem.US_CUSTOMARY),
        )
    }

    @Test
    fun `jump metrics always show two decimal places`() {
        assertEquals(
            "44.13 cm",
            ResultText.formatMetric(
                MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
                Locale.US,
            ),
        )
        assertEquals(
            "600.00 ms",
            ResultText.formatMetric(
                MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND),
                Locale.US,
            ),
        )
        assertEquals(
            "44,13 cm",
            ResultText.formatMetric(
                MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
                Locale("es", "ES"),
            ),
        )
    }

    @Test
    fun `anthropometric estimates are separated from directly derived protocol metrics`() {
        val measurement = draft(
            ProtocolId.SJ,
            ProtocolSetup(),
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        ).copy(
            athleteAnthropometrics = AthleteAnthropometrics(weightKg = 80.0, heightCm = 180.0),
        )
        val result = ProtocolCalculator.compute(measurement)

        val ui = ResultPresenter.presentLive(measurement, result, detectedFps = 240)

        assertTrue(ui.estimatedMetrics.isNotEmpty())
        assertTrue(ui.estimatedMetrics.all { it.key.isEstimate })
        assertFalse(ui.secondaryMetrics.any { it.key.isEstimate })
        assertEquals(
            MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W,
            ui.estimatedMetrics.first().key,
        )
    }

    @Test
    fun `two-event countermovement timeline has only flight and no warning`() {
        val draft = draft(
            ProtocolId.CMJ,
            ProtocolSetup(),
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        )
        val ui = ResultPresenter.presentLive(
            draft = draft,
            result = ProtocolCalculator.compute(draft),
            detectedFps = 240,
        )

        assertEquals(listOf(TimelineSegmentKind.FLIGHT), ui.timeline!!.segments.map { it.kind })
        assertEquals(600_000L, ui.timeline.totalDurationUs)
        assertNull(ui.warning)
        assertEquals(
            listOf(EventType.TAKEOFF, EventType.LANDING),
            ui.events.map { it.type },
        )
        assertFalse(ui.secondaryMetrics.any { it.key == MetricKey.TIME_TO_TAKEOFF_MS })
        assertFalse(ui.secondaryMetrics.any { it.key == MetricKey.RSI_MOD })
    }

    @Test
    fun `stored two-event countermovement presents flight without warning or invented sensitivity`() {
        val metrics = listOf(
            MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
            MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND),
            MetricValue(MetricKey.TAKEOFF_VELOCITY_MPS, 2.94, MetricUnit.METER_PER_SECOND),
        )
        val ui = ResultPresenter.presentStored(
            StoredMeasurement(
                id = 6L,
                dateTime = 200L,
                protocolId = ProtocolId.CMJ,
                primaryMetric = metrics.first(),
                attempts = listOf(
                    StoredAttempt(
                        ordinal = 0,
                        side = null,
                        source = VideoSource.IMPORTED,
                        videoUri = "content://video/6",
                        detectedFps = 240,
                        setup = ProtocolSetup(),
                        events = listOf(
                            StoredEvent(EventType.TAKEOFF, 0, 700_000L),
                            StoredEvent(EventType.LANDING, 0, 1_300_000L),
                        ),
                        metrics = metrics,
                    ),
                ),
            ),
        )

        assertEquals(listOf(TimelineSegmentKind.FLIGHT), ui.timeline!!.segments.map { it.kind })
        assertEquals(600_000L, ui.timeline.totalDurationUs)
        assertNull(ui.warning)
        assertNull(ui.temporalSensitivity)
    }

    @Test
    fun `invalid pts yield recoverable warning rather than false timeline`() {
        val ui = ResultPresenter.presentLive(
            draft(
                ProtocolId.SJ,
                ProtocolSetup(),
                EventType.TAKEOFF to 700_000L,
                EventType.LANDING to 700_000L,
            ),
            ProtocolResult(
                ProtocolId.SJ,
                MetricValue(MetricKey.HEIGHT_CM, 10.0, MetricUnit.CENTIMETER),
                emptyList(),
            ),
            detectedFps = 30,
        )

        assertNull(ui.timeline)
        assertEquals(TimelineWarning.INVALID_PTS, ui.warning)
    }

    @Test
    fun `stored result without attempt is rejected`() {
        assertThrows(ResultPresenter.InvalidResultData::class.java) {
            ResultPresenter.presentStored(
                StoredMeasurement(
                    id = 1L,
                    dateTime = 1L,
                    protocolId = ProtocolId.CMJ,
                    primaryMetric = cmjResult().primaryMetric,
                    attempts = emptyList(),
                ),
            )
        }
    }

    private fun cmjResult(protocol: ProtocolId = ProtocolId.CMJ) = ProtocolResult(
        protocol,
        MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
        listOf(
            MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND),
            MetricValue(MetricKey.TAKEOFF_VELOCITY_MPS, 2.94, MetricUnit.METER_PER_SECOND),
            MetricValue(MetricKey.TIME_TO_TAKEOFF_MS, 600.0, MetricUnit.MILLISECOND),
            MetricValue(MetricKey.RSI_MOD, 0.74, MetricUnit.METER_PER_SECOND),
        ),
    )

    private fun draft(
        protocol: ProtocolId,
        setup: ProtocolSetup,
        vararg events: Pair<EventType, Long>,
    ): MeasurementDraft = MeasurementDraft(
        sessionKey = "result-test",
        protocolId = protocol,
        setup = setup,
        events = events.mapIndexed { index, (type, pts) ->
            EventKey(type) to EventMark(EventKey(type), pts, index)
        }.toMap(),
    )
}
