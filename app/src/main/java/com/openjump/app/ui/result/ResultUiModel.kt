package com.openjump.app.ui.result

import com.openjump.app.math.TemporalSensitivityResult
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementMethod
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.SpatialMark
import com.openjump.app.protocol.VideoSource

/** Presentation-only model shared by live and persisted result screens. */
data class ResultUiModel(
    val protocolId: ProtocolId,
    val protocolTitle: String,
    val protocolShortName: String,
    val dateTime: Long?,
    val primaryMetric: MetricValue,
    val secondaryMetrics: List<MetricValue>,
    val estimatedMetrics: List<MetricValue>,
    val method: MeasurementMethod,
    val attempt: AttemptContextUiModel,
    val timeline: TimelineUiModel?,
    val events: List<ResultEventUiModel>,
    val horizontal: HorizontalResultUiModel? = null,
    val warning: TimelineWarning? = null,
    /** Temporal sensitivity is optional for legacy, horizontal and bilateral results. */
    val temporalSensitivity: TemporalSensitivityResult? = null,
    /** Root note, exposed for both live and stored results. */
    val note: String? = null,
)

data class AttemptContextUiModel(
    val ordinal: Int,
    val side: MeasurementSide?,
    val source: VideoSource?,
    val detectedFps: Int,
    val dropHeightCm: Double?,
    val distanceCm: Double?,
)

data class HorizontalResultUiModel(
    val calibration: MetricCalibration,
    val startPoint: SpatialMark,
    val landingHeel: SpatialMark,
)

data class ResultEventUiModel(
    val type: EventType,
    val ordinal: Int,
    val ptsUs: Long,
)

data class TimelineUiModel(
    val segments: List<TimelineSegmentUiModel>,
    val totalDurationUs: Long,
)

data class TimelineSegmentUiModel(
    val kind: TimelineSegmentKind,
    val durationUs: Long,
)

enum class TimelineSegmentKind { PREPARATION, CONTACT, FLIGHT }

enum class TimelineWarning { UNSUPPORTED_PROTOCOL, MISSING_EVENTS, INVALID_PTS }
