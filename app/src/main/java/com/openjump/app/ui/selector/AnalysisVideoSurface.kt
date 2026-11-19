package com.openjump.app.ui.selector

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3UiR

/**
 * Superficie de vídeo del análisis (PlayerView + SurfaceView) extraída de
 * `FrameSelectorScreen` para que otros visores (p. ej. Ángulos) reutilicen la
 * misma implementación sin duplicarla.
 *
 * Contrato preservado literalmente respecto al original:
 * - Creación de `PlayerView` con `useController = false`, FIT inicial, fondo
 *   negro y `setKeepContentOnPlayerReset(true)`; el player se asigna ya en la
 *   factory, antes de registrar el callback de superficie.
 * - `SurfaceHolder.surfaceCreated` pide refresco con `post { onRefreshFrame() }`,
 *   es decir, después del callback interno de `PlayerView`, cuando ExoPlayer
 *   ya posee la nueva superficie.
 * - `scaleMode` se aplica en `update` (FIT/ZOOM) y el `exo_content_frame` del
 *   propio PlayerView recibe escala/traslación desde el mapper.
 * - Si el player cambia (o la vista se recreó), `update` lo reasigna y pide
 *   otro refresco con `post { onRefreshFrame() }`.
 * - No hay `onRelease`: el player no se libera aquí, igual que antes.
 *
 * El tamaño de la vista y el mapper siguen siendo propiedad del llamador:
 * `onSizeChanged`/`videoViewport` permanecen en `FrameSelectorScreen`, que
 * entrega el `coordinateMapper` ya calculado.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun AnalysisVideoSurface(
    player: Player?,
    coordinateMapper: VideoCoordinateMapper?,
    scaleMode: VideoScaleMode,
    onRefreshFrame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { androidContext ->
            PlayerView(androidContext).apply {
                val playerView = this
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                this.player = player
                (videoSurfaceView as? SurfaceView)?.holder?.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Ejecutar tras el callback interno de PlayerView,
                            // cuando ExoPlayer ya posee la nueva superficie.
                            playerView.post { onRefreshFrame() }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    },
                )
            }
        },
        update = { playerView ->
            playerView.resizeMode = analysisVideoResizeMode(scaleMode)
            val contentFrame = playerView.findViewById<View>(Media3UiR.id.exo_content_frame)
            val translation = coordinateMapper?.contentTranslation
            contentFrame?.apply {
                scaleX = coordinateMapper?.nativeScale?.toFloat() ?: 1f
                scaleY = coordinateMapper?.nativeScale?.toFloat() ?: 1f
                translationX = translation?.x?.toFloat() ?: 0f
                translationY = translation?.y?.toFloat() ?: 0f
            }
            if (playerView.player !== player) {
                playerView.player = player
                playerView.post { onRefreshFrame() }
            }
        },
        modifier = modifier,
    )
}

/**
 * Media3 resize mode for a viewport [scaleMode]. Kept as a pure mapping so
 * FIT/CROP cannot drift while the surface is shared with other viewers.
 */
@OptIn(UnstableApi::class)
internal fun analysisVideoResizeMode(scaleMode: VideoScaleMode): Int = when (scaleMode) {
    VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}
