package com.openjump.app.demo

import com.openjump.app.data.FvpAttemptDraft
import com.openjump.app.data.FvpConditionDraft
import com.openjump.app.data.FvpDraft
import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.VideoSource
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Fixtures for visual exploration ONLY. No participant, video or instrument was measured. */
internal object DemoSamples {
    const val ATHLETE_NAME = "DEMO · Alex (datos ficticios)"
    const val NOTICE = "DEMO / SYNTHETIC — Datos ficticios para explorar la interfaz. " +
        "No son mediciones reales ni evidencia de rendimiento."
    const val BODY_MASS_KG = 80.0
    private const val PUSH_OFF_M = 0.40

    fun profiles(athleteId: Long, now: Long): List<FvpDraft> = listOf(
        profile(athleteId, now, "sj-a", "SJ", 28, listOf(.40, .30, .22, .14)),
        profile(athleteId, now, "sj-b", "SJ", 14, listOf(.42, .32, .24, .16)),
        profile(athleteId, now, "sj-c", "SJ", 0, listOf(.43, .33, .25, .17)),
        profile(athleteId, now, "narrow", "SJ", 7, listOf(.34, .33, .32, .31)),
        profile(athleteId, now, "cmj", "CMJ", 3, listOf(.48, .36, .26, .18)),
        profile(athleteId, now, "diagnostic", "SJ", 1, listOf(.40, .28)),
    )

    private fun profile(
        athleteId: Long,
        now: Long,
        key: String,
        protocol: String,
        daysAgo: Long,
        heights: List<Double>,
    ): FvpDraft {
        val date = now - TimeUnit.DAYS.toMillis(daysAgo)
        return FvpDraft(
            sessionKey = "demo-v1-fvp-$key",
            athleteId = athleteId,
            protocol = protocol,
            dateTime = date,
            bodyMassKg = BODY_MASS_KG,
            bodyMassSource = "MANUAL",
            pushOffMode = "COMMON",
            pushOffM = PUSH_OFF_M,
            pushOffSource = "MANUAL",
            pushOffLandmark = "DEMO: hito ficticio / synthetic landmark",
            pushOffProtocol = "DEMO_ONLY_SYNTHETIC",
            // Date is illustrative, not evidence that h_po was measured.
            pushOffMeasuredAt = date,
            notes = "$NOTICE Caso: $key.",
            conditions = heights.mapIndexed { index, height ->
                FvpConditionDraft(
                    conditionKey = "DEMO ${index * 20} kg",
                    addedLoadKg = index * 20.0,
                    attempts = listOf(
                        FvpAttemptDraft(heightM = height - .012, method = "MANUAL", notes = NOTICE),
                        FvpAttemptDraft(heightM = height - .006, method = "MANUAL", notes = NOTICE),
                        FvpAttemptDraft(heightM = height, method = "MANUAL", notes = NOTICE),
                        FvpAttemptDraft(
                            heightM = height + .04,
                            method = "MANUAL",
                            valid = false,
                            exclusionReason = "DEMO: ejemplo de intento excluido por tecnica",
                            notes = NOTICE,
                        ),
                    ),
                )
            },
        )
    }

    /** Ordinary jump history is populated as well as the dedicated FVP history. No video URI. */
    fun jumps(athleteId: Long, now: Long): List<Pair<MeasurementDraft, Long>> =
        (0 until 10).map { index ->
            val protocol = if (index % 2 == 0) ProtocolId.SJ else ProtocolId.CMJ
            val targetHeightM = .30 + index * .009
            val flightUs = (sqrt(8.0 * targetHeightM / 9.80665) * 1_000_000).roundToLong()
            val marks = buildList {
                if (protocol == ProtocolId.CMJ) {
                    add(EventMark(EventKey(EventType.MOVEMENT_START), 500_000L, 0))
                }
                add(EventMark(EventKey(EventType.TAKEOFF), 1_000_000L, 0))
                add(EventMark(EventKey(EventType.LANDING), 1_000_000L + flightUs, 1))
            }
            val draft = MeasurementDraft(
                sessionKey = "demo-v1-jump-$index",
                protocolId = protocol,
                athleteId = athleteId,
                athleteAnthropometrics = AthleteAnthropometrics(weightKg = BODY_MASS_KG),
                source = VideoSource.UNKNOWN,
                events = marks.associateBy { it.key },
                notes = NOTICE,
            )
            // Fail immediately if a future protocol change makes this fixture invalid.
            ProtocolCalculator.compute(draft)
            draft to (now - TimeUnit.DAYS.toMillis((10 - index).toLong()))
        }
}
