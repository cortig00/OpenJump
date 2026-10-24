package com.openjump.app.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.video.VideoFileSizeReader
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoSourceInfo
import com.openjump.app.video.VideoSourceInfoFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface SizeLoad {
    data object Loading : SizeLoad
    data class Ready(val sizeBytes: Long?) : SizeLoad
}

/**
 * Compact secondary source row shared by imported and recorded videos.
 *
 * Sits below the viewport/readout, never over the video and never as cards per
 * value. Summary shows the encoded resolution, the effective PTS frame rate and
 * the real file size; the info button opens a scrollable [ModalBottomSheet]
 * with the summary first and the declared details below. Null or still-indexing
 * sources hide the row; an in-flight size read shows a discrete "…" placeholder,
 * never an error and never a stale size from a previous URI.
 */
@Composable
fun VideoSourceInfoRow(
    videoUri: String?,
    index: VideoFrameIndex?,
    modifier: Modifier = Modifier,
) {
    if (videoUri == null || index == null) return
    // Composition identity per source: a new URI or a reindex resets the size
    // load to Loading and drops the sheet state, so URI A's size can never
    // leak into URI B's row while B's IO is still in flight.
    key(videoUri, index) {
    val context = LocalContext.current
    // Keyed on the URI string only: a new URI restarts at Loading so the
    // previous size can never leak into the new row. Cancellation propagates.
    val sizeLoad by produceState<SizeLoad>(SizeLoad.Loading, videoUri) {
        if (videoUri.isBlank()) {
            value = SizeLoad.Ready(null)
            return@produceState
        }
        val parsed = runCatching { Uri.parse(videoUri) }.getOrNull()
        value = try {
            SizeLoad.Ready(
                if (parsed == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        VideoFileSizeReader.sizeBytes(context.contentResolver, parsed)
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SizeLoad.Ready(null)
        }
    }
    // Snapshot once per index/URI/size: VideoFrameIndex.temporalContinuity sorts
    // on every access, so this must not recompute on unrelated recompositions.
    // Byte reading never reindexes and never touches the player.
    val info = remember(index, videoUri, sizeLoad) {
        VideoSourceInfoFactory.fromIndex(
            videoUri = videoUri,
            index = index,
            sizeBytes = (sizeLoad as? SizeLoad.Ready)?.sizeBytes,
        )
    }
    VideoSourceInfoRowContent(
        info = info,
        isSizeLoading = sizeLoad is SizeLoad.Loading,
        modifier = modifier,
    )
    }
}

/**
 * Pure render of a [VideoSourceInfo] snapshot. Split for tests and previews:
 * instrumented tests pass in-memory snapshots without file I/O or fake metadata.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSourceInfoRowContent(
    info: VideoSourceInfo,
    isSizeLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val locale = currentAppLocale()
    val resolution = remember(info.encodedWidth, info.encodedHeight) {
        VideoSourceInfoFormat.resolutionLabel(info.encodedWidth, info.encodedHeight)
    }
    val effectiveNumber = remember(locale, info.ptsFramesPerSecond) {
        VideoSourceInfoFormat.effectiveFps(locale, info.ptsFramesPerSecond)
    }
    val sizeNumber = remember(locale, info.sizeMegabytesDecimal, isSizeLoading) {
        VideoSourceInfoFormat.sizeMegabytes(locale, info.sizeMegabytesDecimal, isSizeLoading)
    }
    val fpsPart = if (effectiveNumber == VideoSourceInfoFormat.UNKNOWN || effectiveNumber == VideoSourceInfoFormat.LOADING) {
        effectiveNumber
    } else {
        stringResource(R.string.video_source_fps, effectiveNumber)
    }
    val sizePart = if (sizeNumber == VideoSourceInfoFormat.UNKNOWN || sizeNumber == VideoSourceInfoFormat.LOADING) {
        sizeNumber
    } else {
        stringResource(R.string.video_source_size_value, sizeNumber)
    }
    val summary = stringResource(R.string.video_source_summary, resolution, fpsPart, sizePart)
    // TalkBack names the effective PTS rate explicitly: the compact visual
    // summary stays terse while assistive tech hears "FPS efectivos (PTS)".
    val summaryDescription = "${stringResource(R.string.video_source_resolution)}: $resolution, " +
        "${stringResource(R.string.video_source_effective_fps)}: $fpsPart, " +
        "${stringResource(R.string.video_source_size)}: $sizePart"
    var showSheet by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = summary,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = summaryDescription }
                .testTag("video-source-summary"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = true,
        )
        IconButton(
            onClick = { showSheet = true },
            modifier = Modifier
                .size(48.dp)
                .testTag("video-source-details-button"),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.video_source_details),
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(
                        start = Spacing.screenHorizontal,
                        end = Spacing.screenHorizontal,
                        bottom = Spacing.xl,
                    )
                    .testTag("video-source-sheet"),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.video_source_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_resolution),
                    value = remember(info.encodedWidth, info.encodedHeight) {
                        VideoSourceInfoFormat.resolutionGeometry(info.encodedWidth, info.encodedHeight)
                    },
                )
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_effective_fps),
                    value = fpsPart,
                )
                val nominalNumber = remember(locale, info.nominalFrameRate) {
                    VideoSourceInfoFormat.nominalFps(locale, info.nominalFrameRate)
                }
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_nominal_fps),
                    value = if (nominalNumber == VideoSourceInfoFormat.UNKNOWN) {
                        nominalNumber
                    } else {
                        stringResource(R.string.video_source_fps, nominalNumber)
                    },
                )
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_size),
                    value = sizePart,
                )
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_codec),
                    value = remember(info.codecMimeType) {
                        VideoSourceInfoFormat.codecLabel(info.codecMimeType)
                    },
                )
                val bitrateNumber = remember(locale, info.declaredAverageBitrateBps) {
                    VideoSourceInfoFormat.bitrateMbps(locale, info.declaredAverageBitrateBps)
                }
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_bitrate),
                    value = if (bitrateNumber == VideoSourceInfoFormat.UNKNOWN) {
                        bitrateNumber
                    } else {
                        stringResource(R.string.video_source_bitrate_value, bitrateNumber)
                    },
                )
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_frames),
                    value = remember(locale, info.frameCount) {
                        VideoSourceInfoFormat.frameCount(locale, info.frameCount)
                    },
                )
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_duration),
                    value = remember(info.playbackDurationUs) {
                        Formatting.usToClockMs(info.playbackDurationUs)
                    },
                )
                VideoSourceDetailRow(
                    label = stringResource(R.string.video_source_gaps),
                    value = if (VideoSourceInfoFormat.hasGaps(info.gapCount)) {
                        stringResource(
                            R.string.video_source_gaps_value,
                            info.gapCount,
                            VideoSourceInfoFormat.gapLargestMs(locale, info.largestGapUs),
                        )
                    } else {
                        stringResource(R.string.video_source_no_gaps)
                    },
                )
                TextButton(
                    onClick = { showSheet = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.video_source_close))
                }
            }
        }
    }
}

@Composable
private fun VideoSourceDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = true,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = true,
        )
    }
}
