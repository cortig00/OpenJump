package com.openjump.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "encoder_sessions",
    foreignKeys = [
        ForeignKey(
            entity = AthleteEntity::class,
            parentColumns = ["id"],
            childColumns = ["athleteId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["sessionKey"], unique = true),
        Index(value = ["athleteId", "dateTime", "id"]),
        Index(value = ["testingSessionId", "dateTime", "id"]),
        Index(value = ["dateTime", "id"]),
    ],
)
data class EncoderSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    /** Nullable only for legacy v4 rows. */
    val athleteId: Long? = null,
    /** Reserved for the future Testing migration; intentionally has no FK yet. */
    val testingSessionId: Long? = null,
    val exercise: String,
    val loadKg: Double,
    val dateTime: Long,
    val source: String,
    val videoUri: String?,
    val observedFps: Double?,
    val nominalFps: Double?,
    val captureFps: Double?,
    val timingMode: String,
    val slowMotionFactor: Double,
    val calibrationFrameIndex: Int,
    val calibrationAX: Double,
    val calibrationAY: Double,
    val calibrationBX: Double,
    val calibrationBY: Double,
    val referenceLengthM: Double,
    val metersPerPixel: Double,
    val analysisVersion: Int,
    val minimumVelocityMps: Double,
    val noiseMultiplier: Double,
    val exitRatio: Double,
    val minimumDirectionHoldUs: Long,
    val stationaryDurationUs: Long,
    val pauseDurationUs: Long,
    val minimumPhaseDurationUs: Long,
    val minimumPhaseDisplacementM: Double,
    val romOutlierFraction: Double,
    val minimumMedianConfidence: Double,
    val maximumUncertainFraction: Double,
    val smoothingWindowSamples: Int,
    val velocityEnterMps: Double?,
    val velocityExitMps: Double?,
    val totalRepetitions: Int,
    val validRepetitions: Int,
    val bestMcv: Double?,
    val setVelocityLoss: Double?,
    val notes: String? = null,
)

@Entity(
    tableName = "encoder_samples",
    primaryKeys = ["sessionId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = EncoderSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class EncoderSampleEntity(
    val sessionId: Long,
    val ordinal: Int,
    val frameIndex: Int,
    val sourcePtsUs: Long,
    val physicalTimeUs: Long?,
    val rawPixelX: Double?,
    val rawPixelY: Double?,
    val rawMetricX: Double?,
    val rawMetricY: Double?,
    val smoothedMetricX: Double?,
    val smoothedMetricY: Double?,
    val velocityXMps: Double?,
    val velocityYMps: Double?,
    val confidence: Double,
    val trackingStatus: String,
    val observationKind: String,
    val fitResidualM: Double?,
    val gapBeforeUs: Long?,
)

@Entity(
    tableName = "encoder_repetitions",
    foreignKeys = [
        ForeignKey(
            entity = EncoderSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "ordinal"], unique = true),
    ],
)
data class EncoderRepetitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ordinal: Int,
    val startSample: Int,
    val endSample: Int,
    val eccentricStartSample: Int,
    val eccentricEndSample: Int,
    val concentricStartSample: Int,
    val concentricEndSample: Int,
    val quality: String,
    val reasons: String,
)

@Entity(
    tableName = "encoder_rep_metrics",
    primaryKeys = ["repetitionId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = EncoderRepetitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["repetitionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("repetitionId")],
)
data class EncoderRepMetricEntity(
    val repetitionId: Long,
    val key: String,
    val value: Double?,
    val unit: String,
    val validity: String,
    val reason: String?,
)

data class EncoderRepetitionWithMetrics(
    @Embedded val repetition: EncoderRepetitionEntity,
    @Relation(parentColumn = "id", entityColumn = "repetitionId")
    val metrics: List<EncoderRepMetricEntity>,
)

data class EncoderSessionWithData(
    @Embedded val session: EncoderSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val samples: List<EncoderSampleEntity>,
    @Relation(
        entity = EncoderRepetitionEntity::class,
        parentColumn = "id",
        entityColumn = "sessionId",
    )
    val repetitions: List<EncoderRepetitionWithMetrics>,
)

data class PersistableEncoderSession(
    val session: EncoderSessionEntity,
    val samples: List<EncoderSampleEntity>,
    val repetitions: List<PersistableEncoderRepetition>,
    /** Setup snapshot used for Testing validation; distinct from manual calibration length. */
    val sessionPlateDiameterCm: Double? = null,
)

data class PersistableEncoderRepetition(
    val repetition: EncoderRepetitionEntity,
    val metrics: List<EncoderRepMetricEntity>,
)

data class RecentEncoderSession(
    val id: Long,
    val dateTime: Long,
    val athleteId: Long? = null,
    val athleteName: String? = null,
    val exercise: String,
    val loadKg: Double,
    val validRepetitions: Int,
    val bestMcv: Double?,
    val hasNotes: Boolean = false,
)
