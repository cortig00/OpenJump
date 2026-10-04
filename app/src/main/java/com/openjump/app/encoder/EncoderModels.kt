package com.openjump.app.encoder

import com.openjump.app.measurement.MetricCalibration
import java.text.Normalizer
import java.util.Locale
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingStatus

enum class EncoderExercise(
    val displayName: String,
    val eccentricFirst: Boolean,
    private val localizedAliases: Set<String>,
) {
    BENCH_PRESS("Press banca", true, setOf("Bench press", "Press banca", "Développé couché", "Bankdrücken")),
    SQUAT("Sentadilla", true, setOf("Squat", "Sentadilla", "Kniebeugen")),
    DEADLIFT("Peso muerto", false, setOf("Deadlift", "Peso muerto", "Soulevé de terre", "Kreuzheben")),
    PULL_UP("Dominada", false, setOf("Pull-up", "Pull up", "Dominada", "Traction", "Klimmzug"));

    /**
     * Converts a visible exercise name back to the enum stored by Room. The history DAO only
     * knows canonical enum values, so this resolution belongs at its UI/domain boundary.
     */
    companion object {
        private fun normalizeAlias(value: String): String = Normalizer
            .normalize(value.trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)

        fun canonicalSearchValue(query: String): String? {
            val normalized = normalizeAlias(query)
            return entries.firstOrNull { exercise ->
                normalized == normalizeAlias(exercise.name) ||
                    (normalized.length >= 3 && exercise.localizedAliases.any {
                        normalizeAlias(it).contains(normalized)
                    })
            }?.name
        }
    }
}

data class EncoderSetup(
    val exercise: EncoderExercise,
    val loadKg: Double,
) {
    init {
        require(loadKg.isFinite() && loadKg >= 0.0) { "La carga debe ser un número mayor o igual que cero." }
    }
}

enum class VideoTimingMode { REAL_TIME, SLOW_MOTION, UNKNOWN }

data class VideoTimingDecision(
    val mode: VideoTimingMode,
    val slowMotionFactor: Double = 1.0,
) {
    init {
        require(slowMotionFactor.isFinite() && slowMotionFactor > 0.0)
        when (mode) {
            VideoTimingMode.REAL_TIME -> require(slowMotionFactor == 1.0)
            VideoTimingMode.SLOW_MOTION -> require(slowMotionFactor > 1.0)
            VideoTimingMode.UNKNOWN -> Unit
        }
    }

    val isReliable: Boolean get() = mode != VideoTimingMode.UNKNOWN
    val timeScale: Double? get() = if (isReliable) 1.0 / slowMotionFactor else null
}

data class EncoderDraft(
    val sessionKey: String,
    /** Authoritative owner snapshot. New sessions are created with a real id. */
    val athleteId: Long = 0L,
    /** Nullable for ordinary sessions; when present it is the immutable testing context. */
    val testingSessionId: Long? = null,
    val setup: EncoderSetup,
    val sessionPlateDiameterCm: Double,
    val source: VideoSource? = null,
    val videoUri: String? = null,
    val timingDecision: VideoTimingDecision? = null,
    val calibrationPointA: ImagePoint? = null,
    val calibrationPointB: ImagePoint? = null,
    val calibrationFrameIndex: Int? = null,
    val calibration: MetricCalibration? = null,
    val automaticPlateCalibration: Boolean = false,
    val plateDetection: PlateDetection? = null,
    val tracking: List<TrackingFrameResult> = emptyList(),
    /** Optional root note; persistence normalizes it at the repository boundary. */
    val notes: String? = null,
) {
    init {
        require(sessionPlateDiameterCm.isFinite() && sessionPlateDiameterCm > 0.0)
    }
}

data class MetricPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite())
    }
}

enum class ObservationKind { MEASURED, MISSING }

data class EncoderSample(
    val ordinal: Int,
    val frameIndex: Int,
    val sourcePtsUs: Long,
    val physicalTimeUs: Long?,
    val rawPixelPoint: ImagePoint?,
    val rawMetricPoint: MetricPoint?,
    val smoothedMetricPoint: MetricPoint?,
    val velocityXMps: Double?,
    val velocityYMps: Double?,
    val confidence: Double,
    val trackingStatus: TrackingStatus,
    val observationKind: ObservationKind,
    val fitResidualM: Double?,
    val gapBeforeUs: Long?,
) {
    val verticalPositionM: Double? get() = smoothedMetricPoint?.y
    val verticalVelocityMps: Double? get() = velocityYMps
}

enum class MovementDirection { UP, DOWN }

data class MovementPhase(
    val direction: MovementDirection,
    val startSample: Int,
    val endSample: Int,
) {
    init {
        require(startSample >= 0 && endSample >= startSample)
    }
}

enum class RepetitionQuality { VALID, UNCERTAIN, INVALID }

enum class QualityReason {
    UNRELIABLE_TIME,
    INCOMPLETE_PHASE,
    TRACKING_LOST,
    UNCERTAIN_TRACKING,
    LOW_CONFIDENCE,
    PTS_GAP,
    TURNAROUND_GAP,
    INSUFFICIENT_SAMPLING_RATE,
    DISCONTINUITY,
    ROM_OUTLIER,
}

enum class EncoderMetricKey {
    MCV,
    PEAK_VELOCITY,
    ROM,
    CONCENTRIC_TIME,
    ECCENTRIC_TIME,
    MEAN_ECCENTRIC_VELOCITY,
    PAUSE_TIME,
    VELOCITY_LOSS,
}

enum class EncoderMetricUnit { METER_PER_SECOND, METER, SECOND, PERCENT }
enum class MetricValidity { VALID, LOW_CONFIDENCE, INVALID }

data class EncoderMetric(
    val key: EncoderMetricKey,
    val value: Double?,
    val unit: EncoderMetricUnit,
    val validity: MetricValidity,
    val reason: QualityReason? = null,
) {
    init {
        require(value == null || value.isFinite())
        require(validity == MetricValidity.INVALID || value != null)
    }
}

data class EncoderRepetition(
    val ordinal: Int,
    val startSample: Int,
    val endSample: Int,
    val eccentricPhase: MovementPhase,
    val concentricPhase: MovementPhase,
    val quality: RepetitionQuality,
    val reasons: Set<QualityReason>,
    val metrics: List<EncoderMetric>,
) {
    fun metric(key: EncoderMetricKey): EncoderMetric? = metrics.firstOrNull { it.key == key }
}

data class EncoderAnalysisConfig(
    val version: Int = 1,
    val minimumVelocityMps: Double = 0.04,
    val noiseMultiplier: Double = 4.0,
    val exitRatio: Double = 0.5,
    val minimumDirectionHoldUs: Long = 50_000L,
    val stationaryDurationUs: Long = 300_000L,
    val pauseDurationUs: Long = 150_000L,
    val minimumPhaseDurationUs: Long = 150_000L,
    val minimumPhaseDisplacementM: Double = 0.02,
    val romOutlierFraction: Double = 0.20,
    val minimumMedianConfidence: Double = 0.50,
    val maximumUncertainFraction: Double = 0.15,
) {
    init {
        require(version > 0)
        require(minimumVelocityMps > 0.0 && noiseMultiplier > 0.0)
        require(exitRatio in 0.0..1.0)
        require(minimumDirectionHoldUs > 0L && stationaryDurationUs > 0L && pauseDurationUs > 0L)
        require(minimumPhaseDurationUs > 0L && minimumPhaseDisplacementM > 0.0)
        require(romOutlierFraction in 0.0..1.0)
        require(minimumMedianConfidence in 0.0..1.0)
        require(maximumUncertainFraction in 0.0..1.0)
    }
}

data class StoredEncoderSession(
    val id: Long,
    val dateTime: Long,
    val athleteId: Long? = null,
    val athleteName: String? = null,
    val source: VideoSource,
    val videoUri: String?,
    val analysis: EncoderAnalysis,
    val notes: String? = null,
)

/** Stable, derivable session warnings. They intentionally remain display-neutral Spanish keys
 * for compatibility with the existing localized result presenter. */
object EncoderAnalysisWarnings {
    const val UNRELIABLE_TIMELINE = "La cronología física no está confirmada; no se calculan velocidades ni repeticiones."
    const val TRACKING_LOST = "El tracking se perdió antes de terminar el vídeo."
    const val NO_REPETITIONS = "No se detectaron repeticiones completas."
    const val NO_VALID_REPETITIONS = "No hay repeticiones válidas para el resumen de la serie."

    fun derive(timingDecision: VideoTimingDecision, samples: List<EncoderSample>, repetitions: List<EncoderRepetition>): List<String> = buildList {
        if (!timingDecision.isReliable) add(UNRELIABLE_TIMELINE)
        if (samples.any { it.trackingStatus == TrackingStatus.LOST }) add(TRACKING_LOST)
        if (timingDecision.isReliable && repetitions.isEmpty()) add(NO_REPETITIONS)
        if (timingDecision.isReliable && repetitions.isNotEmpty() && repetitions.none { it.quality == RepetitionQuality.VALID }) {
            add(NO_VALID_REPETITIONS)
        }
    }
}

data class EncoderAnalysis(
    val setup: EncoderSetup,
    val timingDecision: VideoTimingDecision,
    val calibration: MetricCalibration,
    val samples: List<EncoderSample>,
    val repetitions: List<EncoderRepetition>,
    val effectiveFps: Double?,
    val smoothingWindowSamples: Int,
    val velocityEnterMps: Double?,
    val velocityExitMps: Double?,
    val warnings: List<String>,
    val config: EncoderAnalysisConfig = EncoderAnalysisConfig(),
) {
    val validRepetitions: List<EncoderRepetition>
        get() = repetitions.filter { it.quality == RepetitionQuality.VALID }

    val bestMcv: Double?
        get() = validRepetitions.mapNotNull { it.metric(EncoderMetricKey.MCV)?.value }.maxOrNull()

    val setVelocityLoss: Double?
        get() = if (validRepetitions.size >= 2) {
            validRepetitions.last().metric(EncoderMetricKey.VELOCITY_LOSS)?.value
        } else {
            null
        }
}
