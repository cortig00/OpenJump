package com.openjump.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "assessments",
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
data class AssessmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    /** Nullable only for v4 rows; new drafts always provide an owner. */
    val athleteId: Long? = null,
    /** Reserved for the future Testing migration; intentionally has no FK yet. */
    val testingSessionId: Long? = null,
    val protocolId: String,
    val dateTime: Long,
    val primaryMetricKey: String,
    val primaryMetricValue: Double,
    val primaryMetricUnit: String,
    val notes: String? = null,
)

@Entity(
    tableName = "attempts",
    foreignKeys = [
        ForeignKey(
            entity = AssessmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["assessmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("assessmentId"),
        Index(value = ["assessmentId", "ordinal"], unique = true),
    ],
)
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assessmentId: Long = 0,
    val ordinal: Int = 0,
    val side: String?,
    val source: String,
    val videoUri: String?,
    val detectedFps: Int,
    val dropHeightCm: Double?,
    val distanceCm: Double?,
)

@Entity(
    tableName = "attempt_events",
    primaryKeys = ["attemptId", "type", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("attemptId")],
)
data class AttemptEventEntity(
    val attemptId: Long,
    val type: String,
    val ordinal: Int,
    val ptsUs: Long,
    val frameIndex: Int? = null,
    val previousPtsUs: Long? = null,
    val nextPtsUs: Long? = null,
)

@Entity(
    tableName = "attempt_metrics",
    primaryKeys = ["attemptId", "key", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("attemptId")],
)
data class AttemptMetricEntity(
    val attemptId: Long,
    val key: String,
    val ordinal: Int,
    val value: Double,
    val unit: String,
)

@Entity(
    tableName = "attempt_calibrations",
    foreignKeys = [
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AttemptCalibrationEntity(
    @PrimaryKey val attemptId: Long,
    val frameIndex: Int,
    val pointAX: Double,
    val pointAY: Double,
    val pointBX: Double,
    val pointBY: Double,
    val referenceLengthM: Double,
    val metersPerPixel: Double,
)

@Entity(
    tableName = "attempt_spatial_marks",
    primaryKeys = ["attemptId", "type"],
    foreignKeys = [
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("attemptId")],
)
data class AttemptSpatialMarkEntity(
    val attemptId: Long,
    val type: String,
    val frameIndex: Int,
    val ptsUs: Long,
    val pointX: Double,
    val pointY: Double,
)

/** Nested Room read shape; maps into protocol.StoredMeasurement in the repository. */
data class AttemptWithData(
    @androidx.room.Embedded val attempt: AttemptEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "attemptId")
    val events: List<AttemptEventEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "attemptId")
    val metrics: List<AttemptMetricEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "attemptId")
    val calibrations: List<AttemptCalibrationEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "attemptId")
    val spatialMarks: List<AttemptSpatialMarkEntity>,
)

data class AssessmentWithAttempts(
    @androidx.room.Embedded val assessment: AssessmentEntity,
    @androidx.room.Relation(
        entity = AttemptEntity::class,
        parentColumn = "id",
        entityColumn = "assessmentId",
    )
    val attempts: List<AttemptWithData>,
)

data class PersistableAttempt(
    val attempt: AttemptEntity,
    val events: List<AttemptEventEntity>,
    val metrics: List<AttemptMetricEntity>,
    val calibration: AttemptCalibrationEntity? = null,
    val spatialMarks: List<AttemptSpatialMarkEntity> = emptyList(),
)

/** A root plus one or more children. Legacy callers continue using the singular fields. */
data class PersistableAssessment(
    val assessment: AssessmentEntity,
    val attempt: AttemptEntity,
    val events: List<AttemptEventEntity>,
    val metrics: List<AttemptMetricEntity>,
    val calibration: AttemptCalibrationEntity? = null,
    val spatialMarks: List<AttemptSpatialMarkEntity> = emptyList(),
    val attempts: List<PersistableAttempt> = emptyList(),
) {
    fun children(): List<PersistableAttempt> = attempts.ifEmpty {
        listOf(PersistableAttempt(attempt, events, metrics, calibration, spatialMarks))
    }
}

data class RecentMeasurement(
    val id: Long,
    val dateTime: Long,
    val athleteId: Long? = null,
    val athleteName: String? = null,
    val protocolId: String,
    val primaryMetricKey: String,
    val primaryMetricValue: Double,
    val primaryMetricUnit: String,
    val side: String?,
    val hasNotes: Boolean = false,
)
