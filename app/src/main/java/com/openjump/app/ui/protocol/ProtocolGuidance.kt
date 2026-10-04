package com.openjump.app.ui.protocol

import androidx.annotation.StringRes
import com.openjump.app.R
import com.openjump.app.protocol.ProtocolAvailability
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId

/** Localized, UI-only execution contract. Exactly three cues keep setup scannable. */
data class ProtocolGuidance(
    @StringRes val executionCues: List<Int>,
    @StringRes val cameraCue: Int,
    @StringRes val markingSummary: Int,
    @StringRes val limits: Int,
    @StringRes val illustrationDescription: Int,
)

object ProtocolGuidanceCatalog {
    fun forProtocol(protocolId: ProtocolId): ProtocolGuidance? = guidance[protocolId]

    fun available(): Map<ProtocolId, ProtocolGuidance> = guidance.filterKeys { id ->
        ProtocolCatalog.find(id).availability == ProtocolAvailability.AVAILABLE
    }

    private val guidance = mapOf(
        ProtocolId.CMJ to guidance(
            R.string.guidance_cmj_1, R.string.guidance_cmj_2, R.string.guidance_cmj_3,
            R.string.camera_capture_vertical, R.string.guidance_mark_events_vertical,
            R.string.guidance_limits_manual, R.string.guidance_illustration_cmj,
        ),
        ProtocolId.SJ to guidance(
            R.string.guidance_sj_1, R.string.guidance_sj_2, R.string.guidance_sj_3,
            R.string.camera_capture_vertical, R.string.guidance_mark_events_sj,
            R.string.guidance_limits_manual, R.string.guidance_illustration_sj,
        ),
        ProtocolId.ABALAKOV to guidance(
            R.string.guidance_abalakov_1, R.string.guidance_abalakov_2, R.string.guidance_abalakov_3,
            R.string.camera_capture_vertical, R.string.guidance_mark_events_vertical,
            R.string.guidance_limits_manual, R.string.guidance_illustration_abalakov,
        ),
        ProtocolId.UNILATERAL to guidance(
            R.string.guidance_unilateral_1, R.string.guidance_unilateral_2, R.string.guidance_unilateral_3,
            R.string.camera_capture_vertical, R.string.guidance_mark_events_vertical,
            R.string.guidance_limits_manual, R.string.guidance_illustration_unilateral,
        ),
        ProtocolId.ASYMMETRY to guidance(
            R.string.guidance_bilateral_1, R.string.guidance_bilateral_2, R.string.guidance_bilateral_3,
            R.string.camera_capture_vertical, R.string.guidance_mark_events_bilateral,
            R.string.guidance_limits_bilateral, R.string.guidance_illustration_bilateral,
        ),
        ProtocolId.DROP_JUMP to guidance(
            R.string.guidance_drop_jump_1, R.string.guidance_drop_jump_2, R.string.guidance_drop_jump_3,
            R.string.camera_capture_drop_jump, R.string.guidance_mark_events_drop_jump,
            R.string.guidance_limits_manual, R.string.guidance_illustration_drop_jump,
        ),
        ProtocolId.HORIZONTAL to guidance(
            R.string.guidance_horizontal_1, R.string.guidance_horizontal_2, R.string.guidance_horizontal_3,
            R.string.camera_capture_horizontal, R.string.guidance_mark_events_horizontal,
            R.string.guidance_limits_horizontal, R.string.guidance_illustration_horizontal,
        ),
    )

    private fun guidance(
        first: Int, second: Int, third: Int, camera: Int, marking: Int, limits: Int, illustration: Int,
    ) = ProtocolGuidance(listOf(first, second, third), camera, marking, limits, illustration)
}
