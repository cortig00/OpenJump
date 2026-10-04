package com.openjump.app.ui.result

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openjump.app.OpenJumpApp
import com.openjump.app.protocol.ProtocolId

/** Selects the bilateral root renderer without changing the legacy result route. */
@Composable
fun SavedMeasurementDispatch(
    assessmentId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onAttemptSelected: ((Int) -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as OpenJumpApp
    var protocol by remember(assessmentId) { mutableStateOf<ProtocolId?>(null) }
    var loaded by remember(assessmentId) { mutableStateOf(false) }
    LaunchedEffect(assessmentId) {
        protocol = runCatching { app.repository.measurementById(assessmentId)?.protocolId }.getOrNull()
        loaded = true
    }
    when {
        protocol == ProtocolId.ASYMMETRY -> SavedBilateralResultRoute(
            assessmentId = assessmentId,
            onBack = onBack,
            onDeleted = onDeleted,
            onAttemptSelected = onAttemptSelected,
        )
        !loaded -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator() }
        else -> SavedResultScreen(assessmentId, onBack, onDeleted)
    }
}
