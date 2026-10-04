package com.openjump.app.math

import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult

object ProtocolCalculator {

    class InvalidMeasurement(message: String) : Exception(message)

    fun compute(draft: MeasurementDraft): ProtocolResult {
        draft.validationError()?.let { throw InvalidMeasurement(it) }
        val result = try {
            when (draft.protocolId) {
                ProtocolId.CMJ,
                ProtocolId.ABALAKOV,
                ProtocolId.UNILATERAL,
                -> computeCountermovement(draft)

                ProtocolId.SJ -> computeSquatJump(draft)
                ProtocolId.DROP_JUMP -> computeDropJump(draft)
                ProtocolId.HORIZONTAL -> computeHorizontalJump(draft)
                ProtocolId.REPEATED_10_5,
                ProtocolId.ASYMMETRY,
                -> throw InvalidMeasurement("Este protocolo todavía no está disponible.")
            }
        } catch (error: JumpMath.InvalidTimes) {
            throw InvalidMeasurement(error.message ?: "Los eventos no son válidos.")
        }
        return JumpEstimates.enrich(result, draft.athleteAnthropometrics)
    }

    private fun computeCountermovement(draft: MeasurementDraft): ProtocolResult {
        val result = JumpMath.compute(
            movementStartUs = draft.pts(EventType.MOVEMENT_START),
            takeoffUs = draft.pts(EventType.TAKEOFF),
            landingUs = draft.pts(EventType.LANDING),
        )
        return ProtocolResult(
            protocolId = draft.protocolId,
            primaryMetric = MetricValue(MetricKey.HEIGHT_CM, result.heightCm, MetricUnit.CENTIMETER),
            secondaryMetrics = listOf(
                MetricValue(MetricKey.FLIGHT_TIME_MS, result.flightTimeMs, MetricUnit.MILLISECOND),
                MetricValue(
                    MetricKey.TAKEOFF_VELOCITY_MPS,
                    result.takeoffVelocityMS,
                    MetricUnit.METER_PER_SECOND,
                ),
                MetricValue(
                    MetricKey.TIME_TO_TAKEOFF_MS,
                    result.timeToTakeoffMs,
                    MetricUnit.MILLISECOND,
                ),
                MetricValue(MetricKey.RSI_MOD, result.rsiMod, MetricUnit.METER_PER_SECOND),
            ),
        )
    }

    private fun computeSquatJump(draft: MeasurementDraft): ProtocolResult {
        val result = JumpMath.computeFlight(
            takeoffUs = draft.pts(EventType.TAKEOFF),
            landingUs = draft.pts(EventType.LANDING),
        )
        return ProtocolResult(
            protocolId = draft.protocolId,
            primaryMetric = MetricValue(MetricKey.HEIGHT_CM, result.heightCm, MetricUnit.CENTIMETER),
            secondaryMetrics = listOf(
                MetricValue(MetricKey.FLIGHT_TIME_MS, result.flightTimeMs, MetricUnit.MILLISECOND),
                MetricValue(
                    MetricKey.TAKEOFF_VELOCITY_MPS,
                    result.takeoffVelocityMS,
                    MetricUnit.METER_PER_SECOND,
                ),
            ),
        )
    }

    private fun computeDropJump(draft: MeasurementDraft): ProtocolResult {
        val contactUs = draft.pts(EventType.INITIAL_CONTACT)
        val takeoffUs = draft.pts(EventType.TAKEOFF)
        val flight = JumpMath.computeFlight(takeoffUs, draft.pts(EventType.LANDING))
        val contactS = JumpMath.contactTimeSeconds(contactUs, takeoffUs)
        val rsi = flight.heightM / contactS
        return ProtocolResult(
            protocolId = draft.protocolId,
            primaryMetric = MetricValue(MetricKey.RSI, rsi, MetricUnit.METER_PER_SECOND),
            secondaryMetrics = listOf(
                MetricValue(MetricKey.CONTACT_TIME_MS, contactS * 1_000.0, MetricUnit.MILLISECOND),
                MetricValue(MetricKey.HEIGHT_CM, flight.heightCm, MetricUnit.CENTIMETER),
                MetricValue(MetricKey.FLIGHT_TIME_MS, flight.flightTimeMs, MetricUnit.MILLISECOND),
            ),
        )
    }

    private fun computeHorizontalJump(draft: MeasurementDraft): ProtocolResult {
        val distanceM = checkNotNull(draft.horizontalJump).distanceMeters()
        return ProtocolResult(
            protocolId = ProtocolId.HORIZONTAL,
            primaryMetric = MetricValue(MetricKey.DISTANCE_M, distanceM, MetricUnit.METER),
            secondaryMetrics = emptyList(),
            method = com.openjump.app.protocol.MeasurementMethod.MANUAL_CALIBRATED_DISTANCE,
        )
    }

    private fun MeasurementDraft.pts(type: EventType): Long? = events[EventKey(type)]?.ptsUs
}
