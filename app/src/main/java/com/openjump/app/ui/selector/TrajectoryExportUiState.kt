package com.openjump.app.ui.selector

import android.net.Uri
import java.io.File

enum class TrajectoryExportPhase { IDLE, EXPORTING, VALIDATING, READY, SAVING, SAVED, ERROR }

data class TrajectoryExportUiState(
    val phase: TrajectoryExportPhase = TrajectoryExportPhase.IDLE,
    val progressPercent: Int? = null,
    val file: File? = null,
    val savedUri: Uri? = null,
    val message: String? = null,
) {
    val blocksViewer: Boolean
        get() = phase == TrajectoryExportPhase.EXPORTING || phase == TrajectoryExportPhase.VALIDATING

    val canExport: Boolean
        get() = phase in setOf(
            TrajectoryExportPhase.IDLE,
            TrajectoryExportPhase.READY,
            TrajectoryExportPhase.SAVED,
            TrajectoryExportPhase.ERROR,
        )

    val canUseFile: Boolean
        get() = file?.isFile == true && phase in setOf(
            TrajectoryExportPhase.READY,
            TrajectoryExportPhase.SAVING,
            TrajectoryExportPhase.SAVED,
        )
}
