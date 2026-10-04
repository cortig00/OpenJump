package com.openjump.app.protocol

import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.measurement.MetricScale
import com.openjump.app.tracking.ImagePoint

enum class SpatialMarkType(val storageKey: String) {
    START_POINT("START_POINT"),
    LANDING_HEEL("LANDING_HEEL");

    companion object {
        fun fromStorageKey(value: String?): SpatialMarkType? = entries.firstOrNull { it.storageKey == value }
    }
}

data class SpatialMark(
    val type: SpatialMarkType,
    val point: ImagePoint,
    val frameIndex: Int,
    val ptsUs: Long,
) {
    init {
        require(frameIndex >= 0)
    }
}

enum class HorizontalJumpStage { CALIBRATION, START_POINT, LANDING_HEEL, COMPLETE }

data class HorizontalJumpDraft(
    val calibrationPointA: ImagePoint? = null,
    val calibrationPointB: ImagePoint? = null,
    val calibrationFrameIndex: Int? = null,
    val calibration: MetricCalibration? = null,
    val startPoint: SpatialMark? = null,
    val landingHeel: SpatialMark? = null,
) {
    val stage: HorizontalJumpStage
        get() = when {
            calibration == null -> HorizontalJumpStage.CALIBRATION
            startPoint == null -> HorizontalJumpStage.START_POINT
            landingHeel == null -> HorizontalJumpStage.LANDING_HEEL
            else -> HorizontalJumpStage.COMPLETE
        }

    fun markCalibrationPoint(point: ImagePoint, frameIndex: Int): HorizontalJumpDraft {
        require(frameIndex >= 0)
        return when {
            calibrationPointA == null || calibrationFrameIndex != frameIndex -> copy(
                calibrationPointA = point,
                calibrationPointB = null,
                calibrationFrameIndex = frameIndex,
                calibration = null,
                startPoint = null,
                landingHeel = null,
            )
            calibrationPointB == null -> copy(
                calibrationPointB = point,
                calibration = null,
                startPoint = null,
                landingHeel = null,
            )
            else -> copy(
                calibrationPointA = point,
                calibrationPointB = null,
                calibrationFrameIndex = frameIndex,
                calibration = null,
                startPoint = null,
                landingHeel = null,
            )
        }
    }

    fun confirmCalibration(referenceLengthM: Double): HorizontalJumpDraft {
        val pointA = checkNotNull(calibrationPointA) { "Marca el punto A." }
        val pointB = checkNotNull(calibrationPointB) { "Marca el punto B." }
        val frameIndex = checkNotNull(calibrationFrameIndex)
        return copy(
            calibration = MetricScale.create(frameIndex, pointA, pointB, referenceLengthM),
            startPoint = null,
            landingHeel = null,
        )
    }

    fun markNextPoint(point: ImagePoint, frameIndex: Int, ptsUs: Long): HorizontalJumpDraft {
        checkNotNull(calibration) { "Completa primero la calibración." }
        val mark = when (stage) {
            HorizontalJumpStage.START_POINT -> SpatialMark(SpatialMarkType.START_POINT, point, frameIndex, ptsUs)
            HorizontalJumpStage.LANDING_HEEL -> SpatialMark(SpatialMarkType.LANDING_HEEL, point, frameIndex, ptsUs)
            HorizontalJumpStage.CALIBRATION -> error("Completa primero la calibración.")
            HorizontalJumpStage.COMPLETE -> error("La medición ya está completa.")
        }
        return when (mark.type) {
            SpatialMarkType.START_POINT -> copy(startPoint = mark, landingHeel = null)
            SpatialMarkType.LANDING_HEEL -> copy(landingHeel = mark)
        }
    }

    fun clearCalibration(): HorizontalJumpDraft = HorizontalJumpDraft()

    fun clearStartPoint(): HorizontalJumpDraft = copy(startPoint = null, landingHeel = null)

    fun clearLandingHeel(): HorizontalJumpDraft = copy(landingHeel = null)

    fun validationIssue(): MeasurementValidationIssue? {
        val calibration = calibration ?: return MeasurementValidationIssue.HORIZONTAL_CALIBRATION
        val start = startPoint ?: return MeasurementValidationIssue.HORIZONTAL_START
        val landing = landingHeel ?: return MeasurementValidationIssue.HORIZONTAL_LANDING
        if (landing.ptsUs <= start.ptsUs) return MeasurementValidationIssue.HORIZONTAL_EVENT_ORDER
        return runCatching { MetricScale.projectedDistanceMeters(start.point, landing.point, calibration) }
            .exceptionOrNull()?.let { MeasurementValidationIssue.HORIZONTAL_GEOMETRY }
    }

    fun validationError(): String? = when (validationIssue()) {
        null -> null
        MeasurementValidationIssue.HORIZONTAL_CALIBRATION -> "Falta confirmar la calibración."
        MeasurementValidationIssue.HORIZONTAL_START -> "Falta marcar el punto de salida."
        MeasurementValidationIssue.HORIZONTAL_LANDING -> "Falta marcar el talón del aterrizaje."
        MeasurementValidationIssue.HORIZONTAL_EVENT_ORDER -> "El aterrizaje debe estar después de la salida."
        MeasurementValidationIssue.HORIZONTAL_GEOMETRY -> runCatching {
            MetricScale.projectedDistanceMeters(
                checkNotNull(startPoint).point,
                checkNotNull(landingHeel).point,
                checkNotNull(calibration),
            )
        }.exceptionOrNull()?.message
        else -> null
    }

    fun distanceMeters(): Double {
        validationError()?.let { error(it) }
        return MetricScale.projectedDistanceMeters(
            checkNotNull(startPoint).point,
            checkNotNull(landingHeel).point,
            checkNotNull(calibration),
        )
    }
}
