package com.openjump.app.protocol

/** Stable reasons for visible validation states; UI maps these to localized resources. */
enum class MeasurementValidationIssue {
    SETUP,
    MISSING_EVENTS,
    EVENT_ORDER,
    HORIZONTAL_NOT_STARTED,
    HORIZONTAL_CALIBRATION,
    HORIZONTAL_START,
    HORIZONTAL_LANDING,
    HORIZONTAL_EVENT_ORDER,
    HORIZONTAL_GEOMETRY,
}

/** In-memory analysis state. Final values are persisted only after explicit save. */
data class MeasurementDraft(
    val sessionKey: String,
    val protocolId: ProtocolId,
    /** Authoritative owner snapshot. 0 is retained only for old in-memory tests. */
    val athleteId: Long = 0L,
    /** Nullable for ordinary measurements; when present it is the immutable testing context. */
    val testingSessionId: Long? = null,
    /** Snapshot prevents later profile edits from changing a saved estimate. */
    val athleteAnthropometrics: AthleteAnthropometrics = AthleteAnthropometrics(),
    val setup: ProtocolSetup = ProtocolSetup(),
    val source: VideoSource? = null,
    val videoUri: String? = null,
    val events: Map<EventKey, EventMark> = emptyMap(),
    val horizontalJump: HorizontalJumpDraft? = null,
    /** Optional root note; persistence normalizes it at the repository boundary. */
    val notes: String? = null,
) {
    val definition: ProtocolDefinition
        get() = ProtocolCatalog.find(protocolId)

    fun nextRequiredEvent(): EventType? =
        definition.requiredEvents.firstOrNull { EventKey(it) !in events }

    fun isComplete(): Boolean = if (protocolId == ProtocolId.HORIZONTAL) {
        horizontalJump?.stage == HorizontalJumpStage.COMPLETE && horizontalJump.validationError() == null
    } else {
        definition.requiredEvents.all { EventKey(it) in events }
    }

    fun orderedMarks(): List<EventMark> = definition.requiredEvents.mapNotNull { events[EventKey(it)] }

    fun validationIssue(): MeasurementValidationIssue? {
        if (ProtocolCatalog.validateSetup(definition, setup) != null) {
            return MeasurementValidationIssue.SETUP
        }
        if (protocolId == ProtocolId.HORIZONTAL) {
            val horizontal = horizontalJump ?: return MeasurementValidationIssue.HORIZONTAL_NOT_STARTED
            return horizontal.validationIssue()
        }
        if (!isComplete()) return MeasurementValidationIssue.MISSING_EVENTS
        val pts = orderedMarks().map { it.ptsUs }
        if (pts.zipWithNext().any { (first, second) -> second <= first }) {
            return MeasurementValidationIssue.EVENT_ORDER
        }
        return null
    }

    fun validationError(): String? = when (validationIssue()) {
        null -> null
        MeasurementValidationIssue.SETUP -> ProtocolCatalog.validateSetup(definition, setup)
        MeasurementValidationIssue.MISSING_EVENTS -> "Faltan eventos por marcar."
        MeasurementValidationIssue.EVENT_ORDER -> "Los eventos deben estar en orden temporal."
        MeasurementValidationIssue.HORIZONTAL_NOT_STARTED -> "Falta iniciar la medición horizontal."
        MeasurementValidationIssue.HORIZONTAL_CALIBRATION,
        MeasurementValidationIssue.HORIZONTAL_START,
        MeasurementValidationIssue.HORIZONTAL_LANDING,
        MeasurementValidationIssue.HORIZONTAL_EVENT_ORDER,
        MeasurementValidationIssue.HORIZONTAL_GEOMETRY -> horizontalJump?.validationError()
    }
}
