package com.openjump.app.protocol

import com.openjump.app.measurement.MetricCalibration

/**
 * Persisted result reconstructed from Room for presentation. It deliberately
 * contains domain values instead of Room entities or localized display text.
 */
data class StoredMeasurement(
    val id: Long,
    val dateTime: Long,
    val athleteId: Long? = null,
    val athleteName: String? = null,
    val protocolId: ProtocolId,
    val primaryMetric: MetricValue,
    val attempts: List<StoredAttempt>,
    val notes: String? = null,
)

data class StoredAttempt(
    val ordinal: Int,
    val side: MeasurementSide?,
    val source: VideoSource,
    val videoUri: String?,
    val detectedFps: Int,
    val setup: ProtocolSetup,
    val events: List<StoredEvent>,
    val metrics: List<MetricValue>,
    val calibration: MetricCalibration? = null,
    val spatialMarks: List<SpatialMark> = emptyList(),
)

data class StoredEvent(
    val type: EventType,
    val ordinal: Int,
    val ptsUs: Long,
    val frameIndex: Int? = null,
    val previousPtsUs: Long? = null,
    val nextPtsUs: Long? = null,
)
