package com.openjump.app.ui.selector

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.openjump.app.ui.theme.Spacing

/**
 * Responsive layout ownership for video analysis/marking (F034, scoped).
 *
 * Portrait ordering/scroll stays exactly as production today (single column:
 * video on top, controls below). Landscape is the only new branch: a
 * side-by-side row with the video pane on the left and a scrollable controls
 * pane on the right. FIT/CROP mapping, overlay math, PTS handling and the
 * tracking engine are untouched; the existing `videoViewport` from
 * `onSizeChanged` keeps feeding the same `VideoCoordinateMapper`.
 *
 * Mode is chosen from actual `BoxWithConstraints` dimensions via
 * [AnalysisLayout.forDimensions] (wired in `FrameSelectorScreen`), not from
 * `LocalConfiguration.orientation`, so foldables/tablets/multi-window follow
 * the real window. Scoped activity orientation (`AnalysisOrientationPolicy`)
 * is untouched.
 *
 * `movableContentOf` was considered to preserve the player surface across
 * reparenting. It is deliberately NOT used: the remembered movable lambda
 * would capture changing selector state (player, mapper, marks, calibration)
 * and go stale. Instead both branches call the same single production video
 * slot, so the AndroidView + overlays + callbacks have one implementation;
 * the retained ViewModel plus `surfaceCreated -> refreshCurrentFrame()`
 * rebinds the frame without losing marks, calibration or bilateral drafts.
 */
enum class AnalysisLayoutMode {
    PORTRAIT,
    LANDSCAPE,
}

object AnalysisLayout {
    /** Left video weight in landscape; right controls take the remainder. */
    const val VIDEO_WEIGHT = 0.58f

    /** Right controls weight in landscape. */
    const val CONTROLS_WEIGHT = 0.42f

    const val VIDEO_PANE_TAG = "analysis-video-pane"
    const val CONTROLS_PANE_TAG = "analysis-controls-pane"

    fun forDimensions(widthDp: Float, heightDp: Float): AnalysisLayoutMode =
        if (widthDp > heightDp) AnalysisLayoutMode.LANDSCAPE else AnalysisLayoutMode.PORTRAIT
}

/**
 * Real production composition for analysis/marking (F034).
 *
 * Contains the exact portrait/landscape branches invoked by
 * `FrameSelectorScreen` with the fresh existing header/video/controls slots
 * AND by instrumented tests with fixture slots — no duplicated layout.
 *
 * The outer `BoxWithConstraints` (Scaffold `contentPadding` + `fillMaxSize`)
 * lives in the caller; this composable only adds the inner screen padding, so
 * total padding is unchanged.
 *
 * Landscape fit-within: the video child is a plain
 * `Modifier.aspectRatio(ratio)` inside the bounded centered pane. No
 * `fillMaxHeight()` forcing, so the bounded-bounded aspect-ratio path fits
 * BOTH width and height (tall, square and wider-than-pane ratios letterbox
 * instead of overflowing onto the controls pane). Portrait stays width-driven
 * (`fillMaxWidth` + `aspectRatio`) inside the scrollable column (unbounded
 * height), ordering unchanged.
 */
@Composable
internal fun AnalysisPaneLayout(
    mode: AnalysisLayoutMode,
    aspectRatio: Float,
    portraitScrollState: ScrollState,
    landscapeControlsScrollState: ScrollState,
    controlsScrollEnabled: Boolean,
    header: @Composable () -> Unit,
    video: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
) {
    if (mode == AnalysisLayoutMode.LANDSCAPE) {
        // Landscape: side-by-side video (left, fit-within) + scrollable
        // controls (right). Same slots, no copy/paste video implementation.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .weight(AnalysisLayout.VIDEO_WEIGHT)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                video(
                    Modifier.aspectRatio(aspectRatio),
                )
            }
            Column(
                modifier = Modifier
                    .weight(AnalysisLayout.CONTROLS_WEIGHT)
                    .fillMaxHeight()
                    .verticalScroll(landscapeControlsScrollState, enabled = controlsScrollEnabled)
                    .padding(start = Spacing.md)
                    .testTag(AnalysisLayout.CONTROLS_PANE_TAG),
            ) {
                header()
                controls()
            }
        }
    } else {
        // Portrait: single scrollable column, ordering unchanged.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(portraitScrollState, enabled = controlsScrollEnabled)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            header()
            video(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
            )
            controls()
        }
    }
}
