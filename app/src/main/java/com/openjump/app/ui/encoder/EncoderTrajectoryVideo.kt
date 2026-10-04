package com.openjump.app.ui.encoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.selector.PlayPauseIcon
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.ViewportSize
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.EncoderPlaybackPositionMapper
import com.openjump.app.video.EncoderPlaybackCoordinator
import com.openjump.app.video.EncoderPlaybackAvailability
import com.openjump.app.video.EncoderPlaybackTimeline
import com.openjump.app.video.EncoderRepetitionPlaybackSegment
import com.openjump.app.video.activeEncoderRepetitionOrdinal
import com.openjump.app.video.buildEncoderRepetitionPlaybackSegments
import com.openjump.app.video.encoderRepetitionRailGeometry
import com.openjump.app.video.hitTestEncoderRepetitionRail
import com.openjump.app.video.shouldShowEncoderRepetitionLabel
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.checkEncoderPlaybackCompatibility
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class EncoderVideoUnavailableReason {
    MISSING_URI,
    INVALID_URI,
    INCOMPATIBLE,
    TRANSIENT,
}

sealed interface EncoderVideoPlaybackState {
    data object Loading : EncoderVideoPlaybackState
    data class Playable(
        val uri: Uri,
        val index: VideoFrameIndex,
        val timeline: EncoderPlaybackTimeline,
        val repetitionSegments: List<EncoderRepetitionPlaybackSegment>,
    ) : EncoderVideoPlaybackState
    data class Unavailable(
        val reason: EncoderVideoUnavailableReason,
        val detail: String? = null,
    ) : EncoderVideoPlaybackState
}

@Composable
fun EncoderTrajectoryVideo(
    analysis: EncoderAnalysis,
    videoUri: String?,
    knownIndex: VideoFrameIndex? = null,
    coordinator: EncoderPlaybackCoordinator? = null,
    selectedOrdinal: Int? = null,
    onRepetitionSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    videoHeightCap: Dp = Dp.Infinity,
    onPlaybackPlayerLifecycleChanged: ((Boolean) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember(context.applicationContext) { context.applicationContext as? OpenJumpApp }
    val playbackCoordinator = coordinator ?: remember { EncoderPlaybackCoordinator() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(app, videoUri) {
        val compositionLease = videoUri?.let { app?.videoUriGrantReconciler?.registerOwner(setOf(it)) }
        onDispose {
            if (compositionLease != null) {
                compositionLease.close()
                app?.requestVideoUriReconciliation()
            }
        }
    }
    var retry by remember(videoUri, knownIndex, analysis) { mutableIntStateOf(0) }
    var state by remember(videoUri, knownIndex, analysis, playbackCoordinator) {
        mutableStateOf<EncoderVideoPlaybackState>(EncoderVideoPlaybackState.Loading)
    }
    val aspectRatio = remember(knownIndex) {
        knownIndex?.let {
            val geometry = com.openjump.app.tracking.VideoPresentationGeometry.fromEncoded(
                it.width,
                it.height,
                it.rotationDegrees,
            )
            (geometry.width.toFloat() / geometry.height.toFloat()).takeIf { ratio -> ratio.isFinite() && ratio > 0f }
        } ?: (16f / 9f)
    }
    LaunchedEffect(videoUri, knownIndex, analysis, retry, playbackCoordinator) {
        state = EncoderVideoPlaybackState.Loading
        val uriText = videoUri?.trim()
        // Keep an independent owner until indexing has returned and closed any native descriptors,
        // even if this LaunchedEffect is cancelled because the result screen leaves composition.
        val indexingLease = uriText?.let { app?.videoUriGrantReconciler?.registerOwner(setOf(it)) }
        try {
            // New coordinators start UNKNOWN; disposing the old player unbinds it. Do not
            // invalidate a just-created binding if a cached index completes before a draw.
            state = withContext(Dispatchers.IO) {
                if (uriText.isNullOrEmpty()) {
                    EncoderVideoPlaybackState.Unavailable(EncoderVideoUnavailableReason.MISSING_URI)
                } else {
                    runCatching {
                        Uri.parse(uriText).also { uri ->
                            check(!uri.scheme.isNullOrBlank()) { "invalid video URI" }
                        }
                    }.fold(
                        onSuccess = { uri ->
                            runCatching {
                                val index = knownIndex ?: VideoFrameIndexer.indexFromUri(context, uri)
                                val compatibility = checkEncoderPlaybackCompatibility(index, analysis.samples, analysis.calibration)
                                if (!compatibility.compatible) {
                                    EncoderVideoPlaybackState.Unavailable(
                                        EncoderVideoUnavailableReason.INCOMPATIBLE,
                                        compatibility.reason,
                                    )
                                } else {
                                    EncoderVideoPlaybackState.Playable(
                                        uri = uri,
                                        index = index,
                                        timeline = EncoderPlaybackTimeline(index, analysis.samples, analysis.calibration),
                                        repetitionSegments = buildEncoderRepetitionPlaybackSegments(
                                            analysis.repetitions,
                                            analysis.samples,
                                        ),
                                    )
                                }
                            }.getOrElse { error ->
                                if (error is CancellationException) throw error
                                EncoderVideoPlaybackState.Unavailable(
                                    EncoderVideoUnavailableReason.TRANSIENT,
                                    error.message,
                                )
                            }
                        },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            EncoderVideoPlaybackState.Unavailable(
                                EncoderVideoUnavailableReason.INVALID_URI,
                                error.message,
                            )
                        },
                    )
                }
            }
        } finally {
            indexingLease?.close()
            if (indexingLease != null) app?.requestVideoUriReconciliation()
        }
    }
    when (val current = state) {
        EncoderVideoPlaybackState.Loading -> {
            PlaybackPlaceholder(
                aspectRatio = aspectRatio,
                description = stringResource(R.string.selector_video_loading),
                showProgress = true,
                videoHeightCap = videoHeightCap,
            )
        }
        is EncoderVideoPlaybackState.Unavailable -> {
            Column(
                modifier = modifier.fillMaxWidth().testTag("encoder-result-video"),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                LaunchedEffect(current) { playbackCoordinator.setAvailability(EncoderPlaybackAvailability.UNAVAILABLE) }
                PlaybackPlaceholder(
                    aspectRatio = aspectRatio,
                    description = stringResource(R.string.selector_video_unavailable),
                    showProgress = false,
                    videoHeightCap = videoHeightCap,
                    testTag = "encoder-video-placeholder",
                )
                if (current.reason == EncoderVideoUnavailableReason.TRANSIENT) {
                    OutlinedButton(onClick = { retry++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.selector_video_retry))
                    }
                }
            }
        }
        is EncoderVideoPlaybackState.Playable ->
            EncoderTrajectoryPlayer(
                playable = current,
                lifecycleOwner = lifecycleOwner,
                coordinator = playbackCoordinator,
                app = app,
                selectedOrdinal = selectedOrdinal,
                onRepetitionSelected = onRepetitionSelected,
                modifier = modifier,
                videoHeightCap = videoHeightCap,
                onPlaybackPlayerLifecycleChanged = onPlaybackPlayerLifecycleChanged,
                onPlaybackError = { state = EncoderVideoPlaybackState.Unavailable(EncoderVideoUnavailableReason.TRANSIENT) },
            )
    }
}

/** Explicit height avoids aspectRatio overriding a max-height constraint in a lazy item. */
@Composable
private fun EncoderVideoViewport(
    aspectRatio: Float,
    heightCap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            modifier.fillMaxWidth().height(minOf(maxWidth / aspectRatio, heightCap)),
            content = content,
        )
    }
}

@Composable
private fun PlaybackPlaceholder(
    aspectRatio: Float,
    description: String,
    showProgress: Boolean,
    videoHeightCap: Dp,
    testTag: String = "encoder-result-video",
) {
    EncoderVideoViewport(
        aspectRatio = aspectRatio,
        heightCap = videoHeightCap,
        modifier = Modifier.testTag(testTag)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            if (showProgress) CircularProgressIndicator()
            Text(description)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun EncoderTrajectoryPlayer(
    playable: EncoderVideoPlaybackState.Playable,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    coordinator: EncoderPlaybackCoordinator,
    app: OpenJumpApp?,
    selectedOrdinal: Int?,
    onRepetitionSelected: (Int) -> Unit,
    modifier: Modifier,
    videoHeightCap: Dp,
    onPlaybackPlayerLifecycleChanged: ((Boolean) -> Unit)?,
    onPlaybackError: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialSourceFrameIndex = 0
    val initialSourcePtsUs = playable.index.timeOf(initialSourceFrameIndex)
    val player = remember(playable.uri, playable.index, coordinator) {
        ExoPlayer.Builder(context).setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT).build().apply {
            volume = 0f
            playWhenReady = false
        }
    }
    val lifecycleChanged = rememberUpdatedState(onPlaybackPlayerLifecycleChanged)
    val bindingGeneration = remember(player, coordinator) {
        coordinator.bind(
            EncoderPlaybackAvailability.AVAILABLE,
            playable.index.frameTimesUs.first(),
        )
    }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var renderedSourcePtsUs by remember(player) { mutableStateOf<Long?>(null) }
    var isDragging by remember(player) { mutableStateOf(false) }
    var dragFraction by remember(player) { mutableFloatStateOf(0f) }
    val pathColor = OpenJumpTheme.colors.positive.toArgb()
    val pointColor = MaterialTheme.colorScheme.error.toArgb()
    val overlay = remember(playable, pathColor, pointColor) {
        EncoderPlaybackOverlayView(
            context = context,
            index = playable.index,
            timeline = playable.timeline,
            pathColor = pathColor,
            pointColor = pointColor,
        )
    }
    DisposableEffect(player, lifecycleOwner, coordinator, bindingGeneration, app) {
        // This player owns the source independently of the parent composition lease. Release only
        // after ExoPlayer has dropped its source during dispose.
        val playerLease = app?.videoUriGrantReconciler?.registerOwner(setOf(playable.uri.toString()))
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = VideoFrameMetadataListener { pts, _, _, _ ->
            mainHandler.post {
                val sourcePtsUs = coordinator.sourcePtsFromPresentation(pts)
                if (coordinator.onFrameRendered(bindingGeneration, sourcePtsUs)) {
                    renderedSourcePtsUs = sourcePtsUs
                    overlay.setPlayerPts(pts)
                }
            }
        }
        val playbackListener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) isPlaying = false
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isPlaying = false
                onPlaybackError()
            }
        }
        player.addListener(playbackListener)
        player.setVideoFrameMetadataListener(listener)
        // Install metadata observation before preparing a paused player. A very short
        // cached clip can otherwise produce its only initial frame before registration.
        player.setMediaItem(MediaItem.fromUri(playable.uri))
        player.prepare()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                player.pause()
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        lifecycleChanged.value?.invoke(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.clearVideoFrameMetadataListener(listener)
            mainHandler.removeCallbacksAndMessages(null)
            player.removeListener(playbackListener)
            player.pause()
            coordinator.unbind(bindingGeneration)
            player.release()
            playerLease?.close()
            if (playerLease != null) app?.requestVideoUriReconciliation()
            lifecycleChanged.value?.invoke(false)
        }
    }
    LaunchedEffect(player, coordinator, bindingGeneration) {
        while (isActive) {
            // A bound coordinator is not proof that the AndroidView has a video output
            // yet. Wait for a real initial frame before consuming the first paused seek.
            val confirmedPts = renderedSourcePtsUs
            if (player.playbackState == Player.STATE_READY && confirmedPts != null) {
                coordinator.consumePendingSeek()?.let { request ->
                    player.pause()
                    if (confirmedPts == request.sourcePtsUs) {
                        // Reuse actual metadata from this binding for a no-op seek.
                        coordinator.onFrameRendered(bindingGeneration, confirmedPts)
                    } else {
                        val presentationUs = coordinator.presentationPtsFromSource(request.sourcePtsUs)
                        player.seekTo(presentationUs / 1_000L)
                    }
                    isPlaying = false
                }
            }
            delay(if (player.isPlaying) 100L else 250L)
        }
    }
    Column(
        modifier.fillMaxWidth().testTag("encoder-result-video"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val geometry = VideoPresentationGeometry.fromEncoded(
            playable.index.width,
            playable.index.height,
            playable.index.rotationDegrees,
        )
        val validTrackedSamples = playable.timeline.samples.count { it.point != null }
        EncoderVideoViewport(
            aspectRatio = (geometry.width.toFloat() / geometry.height.toFloat()).coerceIn(0.75f, 1.78f),
            heightCap = videoHeightCap,
            modifier = Modifier.semantics {
                    contentDescription = context.getString(
                        R.string.encoder_result_video_description,
                        validTrackedSamples,
                    )
                }
                .testTag("encoder-video-player-surface"),
        ) {
            AndroidView(
                factory = { context -> PlayerView(context).also { view ->
                    view.useController = false
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    view.player = player
                } },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            AndroidView(
                factory = { overlay },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val playbackDurationUs = EncoderPlaybackPositionMapper.playbackDurationUs(playable.index)
        val displayedPositionUs = if (isDragging) {
            EncoderPlaybackPositionMapper.positionUs(dragFraction, playbackDurationUs)
        } else {
            renderedSourcePtsUs?.let {
                EncoderPlaybackPositionMapper.presentationPtsUs(
                    it,
                    EncoderPlaybackPositionMapper.firstSourcePtsUs(playable.index),
                )
            } ?: 0L
        }
        val elapsedLabel = Formatting.usToClockMs(displayedPositionUs)
        val totalLabel = playbackDurationUs.takeIf { it > 0L }
            ?.let(Formatting::usToClockMs)
            ?: "—"
        val positionDescription = stringResource(
            R.string.encoder_video_position,
            elapsedLabel,
            totalLabel,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val playPauseDescription = stringResource(
                if (isPlaying) R.string.selector_video_pause else R.string.selector_video_play,
            )
            FilledIconButton(
                onClick = {
                    if (isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) {
                            coordinator.requestSeek(initialSourcePtsUs)
                        } else {
                            player.play()
                        }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = playPauseDescription },
            ) {
                PlayPauseIcon(isPlaying)
            }
            Slider(
                value = if (isDragging) {
                    dragFraction
                } else {
                    renderedSourcePtsUs?.let {
                        EncoderPlaybackPositionMapper.fraction(it, playable.index)
                    } ?: 0f
                },
                onValueChange = { fraction ->
                    isDragging = true
                    val snappedSourcePtsUs = EncoderPlaybackPositionMapper.sourcePtsForFraction(fraction, playable.index)
                    dragFraction = EncoderPlaybackPositionMapper.fraction(snappedSourcePtsUs, playable.index)
                },
                onValueChangeFinished = {
                    EncoderPlaybackPositionMapper.sourcePtsForFraction(dragFraction, playable.index)
                        .let(coordinator::requestSeek)
                    isDragging = false
                },
                enabled = playbackDurationUs > 0L,
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = context.getString(R.string.encoder_video_timeline_description)
                        stateDescription = positionDescription
                    },
            )
        }
        EncoderRepetitionRail(
            segments = playable.repetitionSegments,
            index = playable.index,
            selectedOrdinal = selectedOrdinal,
            activeOrdinal = activeEncoderRepetitionOrdinal(
                playable.repetitionSegments,
                renderedSourcePtsUs,
            ),
            onSelected = { ordinal ->
                onRepetitionSelected(ordinal)
            },
        )
        Text(
            positionDescription,
            modifier = Modifier.align(Alignment.End).testTag("encoder-video-position"),
            style = OpenJumpTypes.Timestamp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EncoderRepetitionRail(
    segments: List<EncoderRepetitionPlaybackSegment>,
    index: VideoFrameIndex,
    selectedOrdinal: Int?,
    activeOrdinal: Int?,
    onSelected: (Int) -> Unit,
) {
    val playbackDurationUs = EncoderPlaybackPositionMapper.playbackDurationUs(index)
    if (segments.isEmpty() || playbackDurationUs <= 0L) return
    val railDescription = stringResource(R.string.encoder_video_repetition_timeline_description)
    val activeColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val selectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val idleColor = MaterialTheme.colorScheme.surfaceContainerLow
    val selectedBorderColor = MaterialTheme.colorScheme.primary
    val activeLabelColor = MaterialTheme.colorScheme.onSurface
    val selectedLabelColor = MaterialTheme.colorScheme.primary
    val idleLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val railTapTolerancePx = with(LocalDensity.current) { 24.dp.toPx() }
    val inlineLabelStyle = MaterialTheme.typography.labelSmall
    val inlineLabels = segments.associate { segment ->
        segment.ordinal to stringResource(R.string.encoder_video_repetition_label, segment.ordinal + 1)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(railDescription, style = MaterialTheme.typography.labelMedium)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(segments, index, railTapTolerancePx) {
                    detectTapGestures { offset ->
                        hitTestEncoderRepetitionRail(
                            geometries = encoderRepetitionRailGeometry(segments, index, size.width.toFloat()),
                            tapX = offset.x,
                            railWidthPx = size.width.toFloat(),
                            tolerancePx = railTapTolerancePx,
                        )?.let(onSelected)
                    }
                }
                .semantics { contentDescription = railDescription },
        ) {
            val geometries = encoderRepetitionRailGeometry(segments, index, size.width)
            geometries.forEach { geometry ->
                val segment = segments.first { it.ordinal == geometry.ordinal }
                val start = geometry.startPx.toDouble()
                val end = geometry.endPx.toDouble()
                drawRoundRect(
                    color = when {
                        segment.ordinal == activeOrdinal -> activeColor
                        segment.ordinal == selectedOrdinal -> selectedColor
                        else -> idleColor
                    },
                    topLeft = androidx.compose.ui.geometry.Offset(start.toFloat(), size.height * 0.25f),
                    size = androidx.compose.ui.geometry.Size(
                        (end - start).toFloat().coerceAtLeast(size.height * 0.04f),
                        size.height * 0.5f,
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.125f, size.height * 0.125f),
                )
                val measuredLabel = textMeasurer.measure(
                    inlineLabels.getValue(segment.ordinal),
                    style = inlineLabelStyle,
                )
                if (shouldShowEncoderRepetitionLabel(
                        segmentWidth = (end - start).toFloat(),
                        measuredTextWidth = measuredLabel.size.width.toFloat(),
                        horizontalPadding = 16.dp.toPx(),
                    )
                ) {
                    val labelColor = when {
                        segment.ordinal == activeOrdinal -> activeLabelColor
                        segment.ordinal == selectedOrdinal -> selectedLabelColor
                        else -> idleLabelColor
                    }
                    drawText(
                        layoutResult = measuredLabel,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            start.toFloat() + ((end - start).toFloat() - measuredLabel.size.width.toFloat()) / 2f,
                            (size.height - measuredLabel.size.height.toFloat()) / 2f,
                        ),
                        color = labelColor,
                    )
                }
                if (segment.ordinal == activeOrdinal) {
                    drawLine(
                        color = activeLabelColor,
                        start = androidx.compose.ui.geometry.Offset(start.toFloat(), size.height * 0.875f),
                        end = androidx.compose.ui.geometry.Offset(end.toFloat(), size.height * 0.875f),
                        strokeWidth = size.height * 0.08f,
                    )
                }
                if (segment.ordinal == selectedOrdinal) {
                    drawRoundRect(
                        color = selectedBorderColor,
                        topLeft = androidx.compose.ui.geometry.Offset(start.toFloat(), size.height * 0.25f),
                        size = androidx.compose.ui.geometry.Size(
                            (end - start).toFloat().coerceAtLeast(size.height * 0.04f),
                            size.height * 0.5f,
                        ),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.height * 0.04f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.125f, size.height * 0.125f),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            segments.forEach { segment ->
                val selected = segment.ordinal == selectedOrdinal
                val active = segment.ordinal == activeOrdinal
                val label = stringResource(R.string.encoder_video_repetition_label, segment.ordinal + 1)
                val accessibleLabel = stringResource(R.string.encoder_video_repetition_accessibility, segment.ordinal + 1)
                val stateLabel = listOfNotNull(
                    stringResource(R.string.encoder_video_repetition_selected).takeIf { selected },
                    stringResource(R.string.encoder_video_repetition_active).takeIf { active },
                ).joinToString(", ")
                FilterChip(
                    selected = selected,
                    onClick = { onSelected(segment.ordinal) },
                    label = {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            textDecoration = if (active) TextDecoration.Underline else TextDecoration.None)
                    },
                    colors = encoderSelectionColors(),
                    border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant) else null,
                    leadingIcon = if (selected) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                        .testTag("encoder-video-repetition-${segment.ordinal + 1}")
                        .semantics {
                            role = Role.Tab
                            contentDescription = accessibleLabel
                            stateDescription = stateLabel
                        },
                )
            }
        }
    }
}

private fun DrawScope.drawText(
    layoutResult: TextLayoutResult,
    topLeft: androidx.compose.ui.geometry.Offset,
    color: Color,
) {
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.translate(topLeft.x, topLeft.y)
        layoutResult.multiParagraph.paint(canvas, color)
        canvas.restore()
    }
}

private class EncoderPlaybackOverlayView(
    context: Context,
    private val index: VideoFrameIndex,
    private val timeline: EncoderPlaybackTimeline,
    pathColor: Int,
    pointColor: Int,
) : View(context) {
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = pathColor }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = pointColor }
    private var playerPtsUs: Long = Long.MIN_VALUE

    /** Media3 presentation PTS is already normalized against the index origin. */
    fun setPlayerPts(presentationPtsUs: Long) {
        playerPtsUs = presentationPtsUs
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (playerPtsUs == Long.MIN_VALUE || width <= 0 || height <= 0) return
        val mapper = VideoCoordinateMapper(
            VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees),
            ViewportSize(width.toDouble(), height.toDouble()),
        )
        timeline.pathSegmentsAtOrBefore(playerPtsUs).forEach { points ->
            points.zipWithNext().forEach { (from, to) ->
                val a = mapper.videoToView(from); val b = mapper.videoToView(to)
                canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), pathPaint)
            }
        }
        timeline.sampleAtOrBefore(playerPtsUs)?.takeIf { it.hasUsablePoint }?.point?.let {
            val point = mapper.videoToView(it)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 10f, pointPaint)
        }
    }
}
