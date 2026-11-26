package com.openjump.app.ui.angles

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openjump.app.R
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.NumberFormat
import java.util.Locale

/**
 * Visor de SOLO LECTURA de un fotograma guardado: imagen protagonista, ampliación y datos.
 *
 * Este fichero es deliberadamente independiente del ViewModel y de la fuente de datos: recibe el
 * fotograma ya cargado, un `suspend` que entrega su imagen verificada y la acción de compartir. Así
 * la identidad de la imagen, la ampliación y los datos técnicos se pueden fijar después con pruebas
 * de Compose controladas (cargador retrasado) sin abrir base, almacén ni vídeo.
 *
 * Reglas de identidad, todas obligatorias:
 * - Todo el estado de la sección (imagen, diálogo de ampliación, plegable técnico) está ligado a la
 *   MISMA petición ([AngleV2SavedDetailFrame.imageRequest] de esta raíz). Al cambiar de raíz, de
 *   fotograma o de integridad declarada, el estado anterior se descarta en el mismo paso de
 *   composición: no se pinta un bitmap viejo con una leyenda nueva, ni un diálogo sobre otra imagen.
 * - El diálogo de ampliación usa la MISMA referencia de bitmap que la tarjeta: no hay segunda
 *   decodificación, ni copia, ni caché, ni reciclado. Tampoco se navega dentro de él.
 * - La ampliación es de sólo lectura: zoom y desplazamiento son transformación de vista
 *   ([AngleDetailViewport], funciones puras verificables en JVM) y nunca tocan puntos, medidas ni
 *   presupuestos de imagen.
 */

/** Zoom mínimo del visor: 1× es el ajuste completo, sin recorte. */
internal const val ANGLE_DETAIL_MIN_ZOOM: Float = 1f

/** Zoom máximo del visor: acotado para que la imagen siga siendo reconocible. */
internal const val ANGLE_DETAIL_MAX_ZOOM: Float = 4f

/** Paso de los botones de zoom (mismo orden de magnitud que el visor del editor). */
internal const val ANGLE_DETAIL_ZOOM_STEP: Float = 0.5f

/** Fracción del tamaño del área visible que avanza un toque de desplazamiento. */
internal const val ANGLE_DETAIL_PAN_STEP_FRACTION: Float = 0.15f

/**
 * Alto mínimo de la caja visible de la imagen en la lista, en dp (objetivo táctil existente).
 *
 * El mínimo ya lo imponía el contenedor; se nombra para que el cálculo acotado del alto y sus
 * pruebas hablen del mismo intervalo que se aplica en pantalla.
 */
internal const val ANGLE_V2_SAVED_VIEWPORT_MIN_HEIGHT_DP: Float = 48f

/**
 * Alto MÁXIMO de la caja visible de la imagen en la lista, en dp.
 *
 * Es un límite de presentación, no un presupuesto de decodificación: la imagen sigue siendo la
 * misma y con `Fit` se ve completa. Existe porque el alto no puede derivarse sin límite del aspecto
 * declarado dentro de una lista de alto ilimitado (LazyColumn): una imagen válida de 1×1280 a
 * ~300 px de ancho pediría 384.000 px de alto y desbordaría el límite de codificación de
 * restricciones de Compose al medir la tarjeta.
 */
internal const val ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP: Float = 480f

/**
 * Etiquetas estables de la sección, para poder distinguir la imagen de la tarjeta de la del diálogo
 * y observar el pie del visor sin depender de textos de usuario.
 *
 * No son datos ni identidad persistida: sólo permiten que las pruebas de Compose apunten a la pieza
 * real que se está viendo (misma referencia de bitmap, mismos botones, mismo pie).
 */
internal const val ANGLE_V2_SAVED_CARD_IMAGE_TAG = "angle-v2-saved-card-image"

/** Tag de la imagen de una celda de la galería (distinto de la imagen protagonista). */
internal const val ANGLE_V2_SAVED_GALLERY_IMAGE_TAG = "angle-v2-saved-gallery-image"
internal const val ANGLE_V2_SAVED_DIALOG_ROOT_TAG = "angle-v2-saved-dialog-root"
internal const val ANGLE_V2_SAVED_DIALOG_IMAGE_TAG = "angle-v2-saved-dialog-image"
internal const val ANGLE_V2_SAVED_DIALOG_FOOTER_TAG = "angle-v2-saved-dialog-footer"

/** Barra superior propia del diálogo: contiene el único control de cierre (48 dp). */
internal const val ANGLE_V2_SAVED_DIALOG_TOPBAR_TAG = "angle-v2-saved-dialog-topbar"

/** Panel secundario de desplazamiento por flechas, revelado por el conmutador «Mover». */
internal const val ANGLE_V2_SAVED_DIALOG_PAN_PANEL_TAG = "angle-v2-saved-dialog-pan-panel"

internal const val ANGLE_V2_SAVED_TECHNICAL_TOGGLE_TAG = "angle-v2-saved-technical-toggle"

/**
 * Estado de vista del visor ampliado: zoom y desplazamiento en píxeles del área visible.
 *
 * Es sólo presentación: no se persiste, no entra en la fila guardada y no influye en las mediciones.
 */
data class AngleDetailViewport(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

/**
 * Estado de vista APLICADO a la imagen del diálogo, publicado en su nodo semántico.
 *
 * Permite observar la transformación real que lleva el bitmap (zoom y desplazamiento ya acotados),
 * no una copia calculada aparte por la prueba. Es información de presentación: no se persiste.
 */
internal val AngleDetailViewportSemanticsKey =
    SemanticsPropertyKey<AngleDetailViewport>(name = "AngleDetailViewport")

/** Estado de vista del diálogo, legible desde pruebas de accesibilidad/Compose. */
internal var SemanticsPropertyReceiver.angleDetailViewport by AngleDetailViewportSemanticsKey

/** Vista restablecida: 1× y centrada. */
internal fun angleDetailResetViewport(): AngleDetailViewport = AngleDetailViewport()

/**
 * Zoom acotado a [[ANGLE_DETAIL_MIN_ZOOM], [ANGLE_DETAIL_MAX_ZOOM]].
 *
 * Cualquier entrada no finita (NaN, infinito) no amplía nada: se normaliza al ajuste completo en vez
 * de propagar un valor que rompería la transformación de vista.
 */
internal fun angleDetailClampZoom(zoom: Float): Float =
    if (!zoom.isFinite()) ANGLE_DETAIL_MIN_ZOOM else zoom.coerceIn(ANGLE_DETAIL_MIN_ZOOM, ANGLE_DETAIL_MAX_ZOOM)

/** Aspecto (ancho/alto) del PNG anotado; el de reserva si la fila no declara dimensiones válidas. */
internal fun angleDetailImageAspect(imageWidth: Int, imageHeight: Int): Float {
    if (imageWidth <= 0 || imageHeight <= 0) return ANGLE_VIEWPORT_FALLBACK_ASPECT
    val aspect = imageWidth.toFloat() / imageHeight.toFloat()
    return if (aspect.isFinite() && aspect > 0f) aspect else ANGLE_VIEWPORT_FALLBACK_ASPECT
}

/**
 * Alto de la caja visible de la imagen en la lista, en dp, a partir del ancho disponible y del
 * aspecto declarado.
 *
 * El alto se deriva del aspecto para conservar el tamaño responsivo en los aspectos normales (4:3,
 * 9:16…), pero SIEMPRE queda dentro de
 * [[ANGLE_V2_SAVED_VIEWPORT_MIN_HEIGHT_DP], [ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP]]. Así una imagen
 * válida con aspecto extremo (por ejemplo 1×1280) no puede pedir un alto fuera de lo representable:
 * la caja se queda en el máximo y `Fit` muestra la imagen completa, sin recorte.
 *
 * Entradas no finitas o no positivas no producen NaN ni infinito: un aspecto inválido cae al de
 * reserva y, sin ancho medible, se usa el alto máximo acotado.
 */
internal fun angleDetailViewportHeightDp(widthDp: Float, aspect: Float): Float {
    val safeAspect = aspect.takeIf { it.isFinite() && it > 0f } ?: ANGLE_VIEWPORT_FALLBACK_ASPECT
    val width = widthDp.takeIf { it.isFinite() && it > 0f }
        ?: return ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP
    val height = width / safeAspect
    if (!height.isFinite()) return ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP
    return height.coerceIn(
        ANGLE_V2_SAVED_VIEWPORT_MIN_HEIGHT_DP,
        ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP,
    )
}

/**
 * Ancho visible de la imagen con ajuste completo (Fit) dentro del área dada, en píxeles.
 *
 * Es la geometría que usa el clamp del desplazamiento: con Fit la imagen nunca recorta, así que a 1×
 * el desplazamiento máximo es cero en ambos ejes.
 */
internal fun angleDetailFittedWidth(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): Float {
    val width = positiveOrNull(containerWidth) ?: return 0f
    val height = positiveOrNull(containerHeight) ?: return 0f
    val aspect = angleDetailImageAspect(imageWidth, imageHeight)
    val heightIfWidthBound = width / aspect
    return if (heightIfWidthBound <= height) width else height * aspect
}

/** Alto visible de la imagen con ajuste completo (Fit) dentro del área dada, en píxeles. */
internal fun angleDetailFittedHeight(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): Float {
    val width = positiveOrNull(containerWidth) ?: return 0f
    val height = positiveOrNull(containerHeight) ?: return 0f
    val aspect = angleDetailImageAspect(imageWidth, imageHeight)
    val heightIfWidthBound = width / aspect
    return if (heightIfWidthBound <= height) heightIfWidthBound else height
}

/**
 * Desplazamiento máximo (en píxeles, por eje) sin dejar hueco dentro de la imagen ampliada.
 *
 * Se calcula sobre la extensión REALMENTE dibujada (`fitted × zoom`) contra la ventana: el borde de
 * la imagen puede llegar al borde de la ventana, nunca más allá. A 1× el resultado es cero.
 */
internal fun angleDetailMaxPan(zoom: Float, containerExtent: Float, fittedExtent: Float): Float {
    val container = positiveOrNull(containerExtent) ?: return 0f
    val fitted = positiveOrNull(fittedExtent) ?: return 0f
    val drawn = fitted * angleDetailClampZoom(zoom)
    if (!drawn.isFinite()) return 0f
    return ((drawn - container) / 2f).coerceAtLeast(0f)
}

/** Vista con zoom y desplazamiento dentro de sus límites para esta área y esta imagen. */
internal fun angleDetailClampViewport(
    viewport: AngleDetailViewport,
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): AngleDetailViewport {
    val zoom = angleDetailClampZoom(viewport.zoom)
    val maxPanX = angleDetailMaxPan(
        zoom = zoom,
        containerExtent = containerWidth,
        fittedExtent = angleDetailFittedWidth(containerWidth, containerHeight, imageWidth, imageHeight),
    )
    val maxPanY = angleDetailMaxPan(
        zoom = zoom,
        containerExtent = containerHeight,
        fittedExtent = angleDetailFittedHeight(containerWidth, containerHeight, imageWidth, imageHeight),
    )
    val panX = if (viewport.panX.isFinite()) viewport.panX.coerceIn(-maxPanX, maxPanX) else 0f
    val panY = if (viewport.panY.isFinite()) viewport.panY.coerceIn(-maxPanY, maxPanY) else 0f
    return AngleDetailViewport(zoom = zoom, panX = panX, panY = panY)
}

/** Vista con el zoom pedido, acotado y con el desplazamiento reajustado a los nuevos límites. */
internal fun angleDetailZoomTo(
    viewport: AngleDetailViewport,
    zoom: Float,
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): AngleDetailViewport = angleDetailClampViewport(
    viewport.copy(zoom = zoom),
    containerWidth,
    containerHeight,
    imageWidth,
    imageHeight,
)

/** Vista tras un paso de zoom ([delta] puede ser negativo para alejar). */
internal fun angleDetailZoomBy(
    viewport: AngleDetailViewport,
    delta: Float,
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): AngleDetailViewport {
    val step = if (delta.isFinite()) delta else 0f
    return angleDetailZoomTo(
        viewport = viewport,
        zoom = angleDetailClampZoom(viewport.zoom) + step,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
    )
}

/** Vista tras desplazarse [dx]/[dy] píxeles, siempre dentro de los límites de la imagen ampliada. */
internal fun angleDetailPanBy(
    viewport: AngleDetailViewport,
    dx: Float,
    dy: Float,
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): AngleDetailViewport {
    val stepX = if (dx.isFinite()) dx else 0f
    val stepY = if (dy.isFinite()) dy else 0f
    return angleDetailClampViewport(
        viewport = viewport.copy(panX = viewport.panX + stepX, panY = viewport.panY + stepY),
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
    )
}

/** Avance de un toque de desplazamiento sobre un eje de [containerExtent] píxeles. */
internal fun angleDetailPanStep(containerExtent: Float): Float {
    val extent = positiveOrNull(containerExtent) ?: return 0f
    val step = extent * ANGLE_DETAIL_PAN_STEP_FRACTION
    return if (step.isFinite() && step > 0f) step else 0f
}

/**
 * Vista resultante de un gesto de dos dedos (pinza + arrastre), acotada a los límites existentes.
 *
 * [zoomChange] es el factor RELATIVO que entrega el gesto (1 = sin cambio) y se aplica sobre el zoom
 * YA acotado del visor; el factor EFECTIVO es `zoomFinal / zoomPrevio`, así que el anclaje usa la
 * transformación que realmente se aplica (un gesto que pide 8× sobre 3× ancla con 4/3, no con 8).
 *
 * El anclaje es respecto al CENTRO `C` del área visible: el punto bajo el centroide [centroidX]/
 * [centroidY] no debe moverse, de modo que el desplazamiento previo se corrige en ese espacio:
 * `nuevoPan = (viejoPan - (cen - C)) * ratio + (cen - C) + pan`. Después se aplica
 * [angleDetailClampViewport], la MISMA cota que los botones y la semántica, así que el resultado es
 * siempre una vista legal (zoom en [ANGLE_DETAIL_MIN_ZOOM]..[ANGLE_DETAIL_MAX_ZOOM] y sin huecos).
 *
 * Entradas no finitas (factor, centroide o arrastre) cuentan como «sin cambio» en su eje: un `NaN`
 * nunca llega a `graphicsLayer` ni a la semántica.
 */
internal fun angleDetailTransformGesture(
    viewport: AngleDetailViewport,
    zoomChange: Float,
    panX: Float,
    panY: Float,
    centroidX: Float,
    centroidY: Float,
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): AngleDetailViewport {
    val current = angleDetailClampViewport(
        viewport = viewport,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
    )
    val requestedZoom = if (zoomChange.isFinite()) current.zoom * zoomChange else current.zoom
    val zoom = angleDetailClampZoom(requestedZoom)
    val ratio = if (current.zoom > 0f) zoom / current.zoom else 1f
    val centroidFinite = centroidX.isFinite() && centroidY.isFinite()
    val offsetX = if (centroidFinite) centroidX - containerWidth / 2f else 0f
    val offsetY = if (centroidFinite) centroidY - containerHeight / 2f else 0f
    val dragX = if (panX.isFinite()) panX else 0f
    val dragY = if (panY.isFinite()) panY else 0f
    return angleDetailClampViewport(
        viewport = AngleDetailViewport(
            zoom = zoom,
            panX = (current.panX - offsetX) * ratio + offsetX + dragX,
            panY = (current.panY - offsetY) * ratio + offsetY + dragY,
        ),
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
    )
}

private fun positiveOrNull(value: Float): Float? = value.takeIf { it.isFinite() && it > 0f }

/**
 * Sección visible de un fotograma guardado: imagen, ampliación, sus marcas y sus datos técnicos.
 *
 * @param rootId raíz V2 de la que procede [frame]; forma parte de la identidad de la petición.
 * @param frame fotograma ya proyectado desde los datos persistidos.
 * @param imageLoader lectura verificada de la imagen de ESTE fotograma (E/S fuera del hilo de UI).
 * @param shareEnabled `false` mientras otra compartición prepara trabajo.
 * @param onShare comparte la copia verificada de ESTE fotograma (mismo comportamiento existente).
 */
@Composable
fun AngleV2SavedFrameSection(
    rootId: Long,
    frame: AngleV2SavedDetailFrame,
    imageLoader: suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage,
    shareEnabled: Boolean,
    onShare: (AngleV2SavedDetailFrame) -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = remember(rootId, frame) { frame.imageRequest(rootId) }
    // Imagen y diálogo comparten la MISMA clave: un cambio de raíz, de fotograma o de integridad
    // descarta el bitmap y el diálogo anteriores antes de pintar nada nuevo.
    var image by remember(request) { mutableStateOf<AngleV2SavedImage>(AngleV2SavedImage.Loading) }
    var expanded by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request) {
        image = AngleV2SavedImage.Loading
        image = imageLoader(frame)
    }
    val available = image as? AngleV2SavedImage.Available
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FrameImageViewport(
                image = image,
                frame = frame,
                onOpen = { if (available != null) expanded = true },
            )
            Text(
                stringResource(R.string.angle_saved_frame, frame.displayFrameNumber),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FrameActions(
                frame = frame,
                expandable = available != null,
                shareEnabled = shareEnabled,
                onExpand = { expanded = true },
                onShare = { onShare(frame) },
            )
            ExtractionNotice(frame)
            MarksSection(frame)
            // El plegable usa la MISMA petición que la imagen: al cambiar de raíz, fotograma o
            // integridad declarada vuelve a cerrarse, aunque el composable se reutilice.
            TechnicalSection(frame = frame, request = request)
        }
    }
    if (expanded && available != null) {
        // Misma referencia de bitmap que la tarjeta: ni decodificación nueva, ni copia, ni reciclado.
        AngleDetailImageDialog(
            frame = frame,
            request = request,
            bitmap = available.bitmap,
            onClose = { expanded = false },
        )
    }
}

/**
 * Área de la imagen del fotograma.
 *
 * El aspecto declarado describe SÓLO la imagen verificada, que se ajusta completa (Fit) dentro de
 * una caja de alto acotado ([angleDetailViewportHeightDp]) y un mínimo táctil de 48 dp. Los estados
 * de espera y de fallo NO usan proporción fija ni recorte: el aviso crece con su texto completo,
 * porque con una relación de aspecto extrema pero válida (por ejemplo 1280×1) una caja proporcional
 * lo dejaría en una franja ilegible. Las marcas y los datos técnicos viven fuera de esta caja y no
 * se tocan.
 *
 * El ancho de la tarjeta se mide ANTES de fijar el alto: el alto sale de una cuenta acotada, nunca
 * de un `aspectRatio` sin límite dentro de una lista de alto ilimitado.
 */
@Composable
private fun FrameImageViewport(
    image: AngleV2SavedImage,
    frame: AngleV2SavedDetailFrame,
    onOpen: () -> Unit,
) {
    val available = image as? AngleV2SavedImage.Available
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val boxModifier = if (available != null) {
            val viewportHeight = angleDetailViewportHeightDp(
                widthDp = maxWidth.value,
                aspect = angleDetailImageAspect(frame.imageWidth, frame.imageHeight),
            )
            Modifier
                .fillMaxWidth()
                .height(viewportHeight.dp)
                .clipToBounds()
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        }
        Box(
            modifier = boxModifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when (image) {
                AngleV2SavedImage.Loading -> Text(
                    stringResource(R.string.angle_multi_frame_image_pending),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.sm),
                )

                AngleV2SavedImage.Absent -> Text(
                    stringResource(R.string.angle_multi_frame_frame_not_captured),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.sm),
                )

                AngleV2SavedImage.Corrupted,
                AngleV2SavedImage.Unreadable,
                -> Text(
                    stringResource(R.string.angle_v2_saved_image_unavailable),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(Spacing.sm),
                )

                is AngleV2SavedImage.Available -> Image(
                    bitmap = image.bitmap.asImageBitmap(),
                    contentDescription = stringResource(
                        R.string.angle_multi_frame_frame_label,
                        frame.displayFrameNumber,
                    ),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ANGLE_V2_SAVED_CARD_IMAGE_TAG)
                        // La imagen también abre la ampliación, pero el botón explícito de al lado es la
                        // vía garantizada: el gesto nunca es la única forma de ampliar.
                        .clickable(
                            onClickLabel = stringResource(
                                R.string.angle_detail_expand_frame,
                                frame.displayFrameNumber,
                            ),
                            onClick = onOpen,
                        ),
                )
            }
        }
    }
}

/** Acciones del fotograma: ampliar (sólo con imagen verificada) y compartir la copia verificada. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrameActions(
    frame: AngleV2SavedDetailFrame,
    expandable: Boolean,
    shareEnabled: Boolean,
    onExpand: () -> Unit,
    onShare: () -> Unit,
) {
    val shareDescription = stringResource(
        R.string.angle_v2_saved_share_frame_image,
        frame.displayFrameNumber,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (expandable) {
            TextButton(onClick = onExpand, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.angle_detail_expand_frame, frame.displayFrameNumber))
            }
        }
        TextButton(
            onClick = onShare,
            enabled = shareEnabled,
            // Igual que antes: la acción se ofrece siempre y un fotograma sin imagen produce un aviso
            // legible al verificar, no un control deshabilitado sin explicación.
            modifier = Modifier
                .heightIn(min = 56.dp)
                .semantics { contentDescription = shareDescription },
        ) {
            Text(stringResource(R.string.angle_v2_saved_share_image))
        }
    }
}

/**
 * Aviso de extracción del fotograma, FUERA del plegable técnico.
 *
 * MATCH no avisa (no hay nada que explicar); UNVERIFIED y MISMATCH sí, porque afectan a la relación
 * entre la imagen y los datos. No se inventa nada sobre tiempos: la comparación es de índices.
 */
@Composable
private fun ExtractionNotice(frame: AngleV2SavedDetailFrame) {
    when (frame.extractionObservation) {
        AngleV2FrameExtractionObservation.MATCH -> Unit
        AngleV2FrameExtractionObservation.UNVERIFIED -> Text(
            stringResource(R.string.angle_detail_extraction_unverified, frame.displayFrameNumber),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AngleV2FrameExtractionObservation.MISMATCH -> Text(
            stringResource(
                R.string.angle_detail_extraction_mismatch,
                frame.reportedFrameIndex?.let(::angleSavedFrameDisplayNumber) ?: frame.displayFrameNumber,
                frame.displayFrameNumber,
            ),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Marcas del fotograma con sus grados reales; las ocultas siguen identificadas y consultables. */
@Composable
private fun MarksSection(frame: AngleV2SavedDetailFrame) {
    val locale = currentAppLocale()
    val degreesFormat = stringResource(R.string.angle_degrees_value)
    Text(
        stringResource(
            R.string.angle_saved_marks_title,
            frame.marks.count { it.visible },
            frame.marks.size,
        ),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (frame.marks.isEmpty()) {
        Text(
            stringResource(R.string.angle_saved_marks_empty),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        frame.marks.forEach { mark ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // Sin una sola línea: a 1,5× el nombre se parte en varias y no se recorta.
                Text(mark.name, style = MaterialTheme.typography.titleMedium)
                MarkDegrees(mark = mark, locale = locale, degreesFormat = degreesFormat)
                if (!mark.visible) {
                    Text(
                        stringResource(R.string.angle_hidden),
                        style = OpenJumpTypes.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Grados reales de la marca, o su motivo de no medible: nunca un 0° falso. */
@Composable
private fun MarkDegrees(mark: AngleSavedDetailMark, locale: Locale, degreesFormat: String) {
    val degrees = mark.degrees
    Text(
        if (degrees != null) {
            degreesFormat.format(formatAngleSavedDegrees(degrees, locale))
        } else {
            stringResource(R.string.angle_saved_value_invalid)
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * Datos técnicos del fotograma, PLEGADOS por defecto.
 *
 * Contiene lo que no debe competir con la imagen: identidad de la imagen declarada por la fila
 * (dimensiones, bytes, MIME, clave y SHA-256), geometría de presentación, índices asignado/reportado,
 * las coordenadas de cada marca y la nota de sólo lectura. Los avisos de imagen e integridad NO
 * viven aquí: se ven siempre.
 */
@Composable
private fun TechnicalSection(
    frame: AngleV2SavedDetailFrame,
    request: AngleV2SavedImageRequest,
) {
    // La petición incluye raíz, fotograma e integridad declarada: el plegable se cierra cuando
    // cualquiera de ellas cambia, igual que la imagen y el diálogo.
    var open by remember(request) { mutableStateOf(false) }
    val expandedLabel = stringResource(R.string.angle_viewport_state_expanded)
    val collapsedLabel = stringResource(R.string.angle_viewport_state_normal)
    TextButton(
        onClick = { open = !open },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(ANGLE_V2_SAVED_TECHNICAL_TOGGLE_TAG)
            .semantics { stateDescription = if (open) expandedLabel else collapsedLabel },
    ) {
        Text(
            stringResource(
                if (open) R.string.angle_detail_technical_hide else R.string.angle_detail_technical_show,
            ),
        )
    }
    if (!open) return
    val locale = currentAppLocale()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(
                R.string.angle_detail_meta_image,
                frame.imageWidth,
                frame.imageHeight,
                frame.imageByteCount,
                frame.imageMimeType,
            ),
            style = OpenJumpTypes.Secondary,
        )
        Text(
            stringResource(
                R.string.angle_detail_meta_presentation,
                frame.presentationWidth,
                frame.presentationHeight,
                frame.rotationDegrees,
            ),
            style = OpenJumpTypes.Secondary,
        )
        Text(
            stringResource(
                R.string.angle_detail_meta_extraction,
                frame.displayFrameNumber,
                frame.reportedFrameIndex?.let(::angleSavedFrameDisplayNumber)
                    ?: stringResource(R.string.angle_detail_meta_reported_unknown),
            ),
            style = OpenJumpTypes.Secondary,
        )
        Text(
            stringResource(R.string.angle_detail_meta_storage, frame.imageKey, frame.imageSha256),
            style = OpenJumpTypes.Secondary,
        )
        frame.marks.forEach { mark ->
            // Cada bloque de coordenadas lleva SU nombre justo encima: con varias marcas (algunas
            // ocultas) unas coordenadas sin nombre no se pueden atribuir a ninguna marca.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    mark.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        R.string.angle_saved_points,
                        formatAngleSavedPoint(mark.pointA.x, locale),
                        formatAngleSavedPoint(mark.pointA.y, locale),
                        formatAngleSavedPoint(mark.pointB.x, locale),
                        formatAngleSavedPoint(mark.pointB.y, locale),
                        formatAngleSavedPoint(mark.pointC.x, locale),
                        formatAngleSavedPoint(mark.pointC.y, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(R.string.angle_v2_saved_read_only_note),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Imagen protagonista de la pestaña Análisis.
 *
 * Recibe el estado de la imagen YA cargado por el contenedor de la pantalla: no hay carga propia,
 * así que cambiar de pestaña y volver no vuelve a decodificar y la imagen del diálogo es la MISMA
 * referencia de bitmap que se está viendo. La caja reutiliza la geometría acotada de la sección
 * ([angleDetailViewportHeightDp]): el alto sale del aspecto declarado dentro de
 * [ANGLE_V2_SAVED_VIEWPORT_MIN_HEIGHT_DP]..[ANGLE_V2_SAVED_VIEWPORT_MAX_HEIGHT_DP], de modo que una
 * imagen válida extrema (1×1280) se ajusta completa y los estados de espera/fallo crecen con su
 * texto en vez de recortarse.
 */
@Composable
internal fun AngleV2AnalysisImagePanel(
    frame: AngleV2SavedDetailFrame,
    request: AngleV2SavedImageRequest,
    image: AngleV2SavedImage,
    shareEnabled: Boolean,
    onShare: () -> Unit,
    /**
     * Si la acción de compartir ESTE fotograma se muestra en el panel. Por defecto `true`, que es lo
     * que ya veían los llamantes existentes; el detalle la oculta con un único fotograma porque la
     * cabecera comparte exactamente ese mismo PNG y duplicarla serían dos botones para lo mismo. Con
     * varios fotogramas sigue visible para compartir el seleccionado, que no es el lote completo.
     */
    showShareAction: Boolean = true,
) {
    val available = image as? AngleV2SavedImage.Available
    // El diálogo pertenece a la MISMA petición que la imagen: al cambiar de raíz, de fotograma o de
    // integridad declarada se cierra antes de poder pintarse sobre otra imagen.
    var expanded by remember(request) { mutableStateOf(false) }
    val shareDescription = stringResource(
        R.string.angle_v2_saved_share_frame_image,
        frame.displayFrameNumber,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FrameImageViewport(
            image = image,
            frame = frame,
            onOpen = { if (available != null) expanded = true },
        )
        // Fallos de verificación y diferencias de extracción SIEMPRE visibles junto a la imagen: son
        // lo que cambia cómo se lee el resultado y no se esconden en Información.
        ExtractionNotice(frame)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (available != null) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.angle_detail_expand_frame, frame.displayFrameNumber))
                }
            }
            if (showShareAction) {
                TextButton(
                    onClick = onShare,
                    enabled = shareEnabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = shareDescription },
                ) {
                    Text(stringResource(R.string.angle_v2_saved_share_image))
                }
            }
        }
    }
    if (expanded && available != null) {
        AngleDetailImageDialog(
            frame = frame,
            request = request,
            bitmap = available.bitmap,
            onClose = { expanded = false },
        )
    }
}

/**
 * Marcas del fotograma seleccionado, alineadas: nombre a la izquierda y grados a la derecha.
 *
 * Sin una tarjeta por ángulo ni bloques apilados: cada fila se separa con una línea y comparte el
 * mismo eje, así los nombres y sus valores se leen juntos. Las marcas ocultas se listan igual y
 * llevan su etiqueta, para que sigan siendo consultables; grados no medibles se dicen como tales y
 * nunca como un 0° falso.
 */
@Composable
internal fun AngleV2AlignedMarkRows(frame: AngleV2SavedDetailFrame) {
    val locale = currentAppLocale()
    val degreesFormat = stringResource(R.string.angle_degrees_value)
    Text(
        stringResource(
            R.string.angle_saved_marks_title,
            frame.marks.count { it.visible },
            frame.marks.size,
        ),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (frame.marks.isEmpty()) {
        Text(
            stringResource(R.string.angle_saved_marks_empty),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    frame.marks.forEachIndexed { index, mark ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(mark.name, style = MaterialTheme.typography.bodyLarge)
                if (!mark.visible) {
                    Text(
                        stringResource(R.string.angle_hidden),
                        style = OpenJumpTypes.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val degrees = mark.degrees
            Text(
                text = if (degrees != null) {
                    degreesFormat.format(formatAngleSavedDegrees(degrees, locale))
                } else {
                    stringResource(R.string.angle_saved_value_invalid)
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * Disclosures de la pestaña Información, con el mismo lenguaje que el resultado del encoder.
 *
 * Sólo enseña metadatos REALES ya persistidos (contexto de la raíz, forma declarada de la imagen y
 * datos técnicos del fotograma). Los avisos que cambian la lectura siguen visibles en Análisis: aquí
 * no se esconden. La clave del almacén y el SHA-256 quedan dentro del disclosure técnico cerrado,
 * nunca impresos por defecto, y no se ofrece ninguna acción de edición, borrado ni navegación.
 */
@Composable
internal fun AngleV2FrameInfo(
    content: AngleV2SavedDetailContent,
    frame: AngleV2SavedDetailFrame?,
    request: AngleV2SavedImageRequest?,
) {
    var contextOpen by remember(content.rootId, frame?.frameId) { mutableStateOf(false) }
    var imageOpen by remember(content.rootId, frame?.frameId) { mutableStateOf(false) }
    var technicalOpen by remember(request) { mutableStateOf(false) }
    val locale = currentAppLocale()
    InfoDisclosure(
        title = stringResource(R.string.angle_polish_info_context),
        open = contextOpen,
        onToggle = { contextOpen = !contextOpen },
    ) {
        Text(Formatting.dateTimeToText(content.createdAt), style = OpenJumpTypes.Secondary)
        Text(
            when {
                content.athleteName != null ->
                    stringResource(R.string.angle_detail_owner_name, content.athleteName)

                content.athleteId != null -> stringResource(R.string.angle_detail_owner_unavailable)
                else -> stringResource(R.string.athlete_unassigned)
            },
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            pluralStringResource(
                R.plurals.angle_polish_frames_count,
                content.frames.size,
                content.frames.size,
            ),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (frame == null) return
    InfoDisclosure(
        title = stringResource(R.string.angle_polish_info_image),
        open = imageOpen,
        onToggle = { imageOpen = !imageOpen },
    ) {
        Text(
            stringResource(
                R.string.angle_detail_meta_image,
                frame.imageWidth,
                frame.imageHeight,
                frame.imageByteCount,
                frame.imageMimeType,
            ),
            style = OpenJumpTypes.Secondary,
        )
    }
    InfoDisclosure(
        title = stringResource(R.string.angle_polish_info_technical),
        open = technicalOpen,
        onToggle = { technicalOpen = !technicalOpen },
    ) {
        Text(
            stringResource(
                R.string.angle_detail_meta_presentation,
                frame.presentationWidth,
                frame.presentationHeight,
                frame.rotationDegrees,
            ),
            style = OpenJumpTypes.Secondary,
        )
        Text(
            stringResource(
                R.string.angle_detail_meta_extraction,
                frame.displayFrameNumber,
                frame.reportedFrameIndex?.let(::angleSavedFrameDisplayNumber)
                    ?: stringResource(R.string.angle_detail_meta_reported_unknown),
            ),
            style = OpenJumpTypes.Secondary,
        )
        Text(
            stringResource(R.string.angle_detail_meta_storage, frame.imageKey, frame.imageSha256),
            style = OpenJumpTypes.Secondary,
        )
        frame.marks.forEach { mark ->
            // Cada bloque de coordenadas lleva SU nombre (y su estado de oculta) justo al lado: con
            // varias marcas, unas coordenadas sin nombre no se pueden atribuir a ninguna. No se
            // recalcula nada ni se quita ninguna coordenada.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    mark.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!mark.visible) {
                    Text(
                        stringResource(R.string.angle_hidden),
                        style = OpenJumpTypes.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(
                        R.string.angle_saved_points,
                        formatAngleSavedPoint(mark.pointA.x, locale),
                        formatAngleSavedPoint(mark.pointA.y, locale),
                        formatAngleSavedPoint(mark.pointB.x, locale),
                        formatAngleSavedPoint(mark.pointB.y, locale),
                        formatAngleSavedPoint(mark.pointC.x, locale),
                        formatAngleSavedPoint(mark.pointC.y, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(R.string.angle_v2_saved_read_only_note),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Cabecera plegable de Información con el patrón NEUTRO del resultado del encoder.
 *
 * Botón de texto a todo el ancho y 48 dp mínimos, título `titleMedium` a la izquierda y un chevron
 * que gira al abrirse; el estado se anuncia con las cadenas ya existentes del encoder («Mostrar
 * detalles» / «Ocultar detalles»). No hay etiquetas verdes ni el texto «Normal»: el plegable es
 * información, no un resultado.
 */
@Composable
private fun InfoDisclosure(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val stateText = stringResource(
        if (open) {
            R.string.encoder_result_disclosure_collapse
        } else {
            R.string.encoder_result_disclosure_expand
        },
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(
            onClick = onToggle,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { stateDescription = stateText },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (open) 90f else 0f),
                )
            }
        }
        if (open) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                content()
            }
        }
    }
}

/**
 * Imagen de una celda de la galería de Fotogramas.
 *
 * La celda NO comparte la caja del Análisis: aquí la imagen es una vista compacta de alto fijo que
 * se ajusta completa, y su carga la gobierna la celda (sólo cuando está realmente visible). El
 * tag es propio para poder distinguirla de la imagen protagonista sin depender de textos.
 */
@Composable
internal fun AngleV2GalleryImageBox(
    image: AngleV2SavedImage,
    frame: AngleV2SavedDetailFrame,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val openLabel = stringResource(R.string.angle_polish_open_in_analysis, frame.displayFrameNumber)
    val selectedLabel = stringResource(R.string.angle_polish_selected_frame)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ANGLE_V2_SAVED_GALLERY_IMAGE_HEIGHT_DP.dp)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when (image) {
            AngleV2SavedImage.Loading -> Text(
                stringResource(R.string.angle_multi_frame_image_pending),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.sm),
            )

            AngleV2SavedImage.Absent -> Text(
                stringResource(R.string.angle_multi_frame_frame_not_captured),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.sm),
            )

            AngleV2SavedImage.Corrupted,
            AngleV2SavedImage.Unreadable,
            -> Text(
                stringResource(R.string.angle_v2_saved_image_unavailable),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(Spacing.sm),
            )

            is AngleV2SavedImage.Available -> Image(
                bitmap = image.bitmap.asImageBitmap(),
                contentDescription = stringResource(
                    R.string.angle_multi_frame_frame_label,
                    frame.displayFrameNumber,
                ),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ANGLE_V2_SAVED_GALLERY_IMAGE_TAG)
                    .clickable(onClickLabel = openLabel, onClick = onSelect),
            )
        }
    }
    if (selected) {
        // La selección es del estado de la pantalla, no de la celda: se marca para que se vea de
        // dónde salió la imagen que está en Análisis.
        Text(
            selectedLabel,
            style = OpenJumpTypes.Label,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { stateDescription = selectedLabel },
        )
    }
}

/** Alto fijo de la imagen de una celda de galería, en dp (vista compacta, no la protagonista). */
internal const val ANGLE_V2_SAVED_GALLERY_IMAGE_HEIGHT_DP: Float = 160f

/**
 * Celda de la galería de Fotogramas.
 *
 * La celda que NO es la seleccionada carga su imagen con un estado local y desechable, y sólo lo
 * hace cuando el elemento está REALMENTE visible en la lista (no por estar compuesto en el
 * prefetch). La visibilidad se resuelve con la CLAVE ESTABLE del elemento (`rootId:frameId`) en cada
 * consulta del snapshot, nunca con el índice capturado: reordenar o recargar no deja la comprobación
 * apuntando a la posición de otro fotograma. Al dejar de estar visible (o al pasar a seleccionada) la
 * referencia local se suelta y una respuesta tardía no se publica. No hay caché, ni mapa, ni copia de
 * bitmaps: al salir de composición la lectura local se cancela y su resultado se descarta. La celda
 * seleccionada usa la MISMA referencia de bitmap que Análisis, así que alternar de pestaña no
 * decodifica dos veces.
 *
 * [onSelect] recibe la petición COMPLETA de ESTA celda junto con la imagen que esta celda tiene ya
 * pintable, y se llama SÓLO al pulsarla: la transferencia al contenedor seleccionado ocurre en ese
 * instante, con lo que esta celda tiene, y no cuando una carga ajena termina. [onDisposed] suelta el
 * candidato si la celda sale de composición. Ambos son opcionales en el sentido de que la celda
 * también funciona sola (sin transferencia).
 */
@Composable
internal fun AngleV2GalleryFrameCell(
    index: Int,
    frame: AngleV2SavedDetailFrame,
    request: AngleV2SavedImageRequest,
    selected: Boolean,
    selectedImage: AngleV2SavedImage?,
    listState: LazyListState,
    imageLoader: suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage,
    onSelect: (AngleV2SavedImageRequest, AngleV2SavedImage) -> Unit,
    onDisposed: (AngleV2SavedImageRequest) -> Unit = {},
) {
    // Clave estable del elemento (la misma con la que la lista se compone), no el índice recibido.
    val itemKey = "${request.rootId}:${frame.frameId}"
    val actuallyVisible by remember(listState, itemKey) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.key == itemKey && it.size > 0 }
        }
    }
    // Estado local (no compartido): se descarta al salir de composición y al dejar de estar visible.
    var localImage by remember(request) { mutableStateOf<AngleV2SavedImage?>(null) }
    LaunchedEffect(request, actuallyVisible, selected) {
        if (selected || !actuallyVisible) {
            // Nada retenido fuera de pantalla ni mientras la pinta el contenedor seleccionado.
            localImage = null
            return@LaunchedEffect
        }
        if (localImage == null) {
            val loaded = imageLoader(frame)
            // Un cargador que no coopere con la cancelación no puede publicar una imagen que ya no
            // corresponde a una celda visible.
            currentCoroutineContext().ensureActive()
            localImage = loaded
        }
    }
    DisposableEffect(request) {
        onDispose { onDisposed(request) }
    }
    val image: AngleV2SavedImage = if (selected) {
        selectedImage ?: AngleV2SavedImage.Loading
    } else {
        localImage ?: AngleV2SavedImage.Loading
    }
    // ÚNICO punto de selección de la celda: el título y la imagen comparten ESTE envoltorio, así que
    // la transferencia lleva siempre la imagen que se está viendo en ESTA celda y su petición
    // completa, justo antes de cambiar la selección. Nada se ofrece al terminar una carga.
    val selectCurrent = { onSelect(request, image) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.angle_polish_open_in_analysis, frame.displayFrameNumber)) {
                selectCurrent()
            },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        AngleV2GalleryImageBox(
            image = image,
            frame = frame,
            selected = selected,
            onSelect = selectCurrent,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.angle_saved_frame, frame.displayFrameNumber),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                pluralStringResource(
                    R.plurals.angle_polish_marks_count,
                    frame.marks.size,
                    frame.marks.size,
                ),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Ampliación a pantalla completa de SÓLO LECTURA: misma imagen, zoom y desplazamiento por botones.
 *
 * `usePlatformDefaultWidth = false` da toda la pantalla, pero NO basta para que el contenido quepa:
 * el diálogo de Compose ignora el `measureSpec` de la ventana y mide `fillMaxSize` con la altura de
 * la CONFIGURACIÓN (p. ej. 2399 px), no con el hueco realmente concedido a esta ventana (2209 px
 * con barras de 128/63). Esa petición desbordada se recorta por abajo y se lleva el pie. Por eso la
 * raíz se acota ANTES de `fillMaxSize` al viewport nativo de ESTA ventana menos sus insets
 * ([angleDialogSafeViewport]): el diálogo pide entonces un tamaño que el sistema sí puede conceder.
 *
 * El viewport acotado ya EXCLUYE las barras del sistema, así que es la ÚNICA fuente de insets: no se
 * suma además [systemBarsPadding], porque se restarían dos veces en equipos con insets propios. Ese
 * relleno queda sólo como respaldo heredado cuando no hay proyección nativa disponible.
 *
 * El fondo es la superficie semántica del tema. La imagen se ajusta completa (Fit) y ocupa el
 * espacio que deja el pie; Back/«Cerrar» cierran sólo este diálogo. Ningún botón decodifica, copia,
 * recicla ni navega.
 */
@Composable
private fun AngleDetailImageDialog(
    frame: AngleV2SavedDetailFrame,
    request: AngleV2SavedImageRequest,
    bitmap: Bitmap,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Cota síncrona en la PRIMERA composición: no se pinta una imagen a 2399 para saltar luego.
        val safeViewport = rememberAngleDialogSafeViewport()
        val density = LocalDensity.current
        val viewportCap = if (safeViewport == null) {
            Modifier
        } else {
            with(density) {
                Modifier.sizeIn(
                    maxWidth = safeViewport.width.toDp(),
                    maxHeight = safeViewport.height.toDp(),
                )
            }
        }
        Surface(
            viewportCap
                .fillMaxSize()
                .testTag(ANGLE_V2_SAVED_DIALOG_ROOT_TAG),
            color = MaterialTheme.colorScheme.surface,
        ) {
            // Los dos estados se guardan como OBJETOS y se leen con `.value` en cada evento. El gesto
            // es una corrutina de larga vida: `rememberUpdatedState` sólo refresca su copia al
            // recomponer, así que una ráfaga de eventos sin recomposición intermedia acumulaba sobre
            // el valor viejo y perdía los deltas. Leyendo el MISMO `MutableState` al que se escribe,
            // cada evento parte del valor YA aplicado, también el tamaño medido.
            val containerState = remember(frame.frameId) { mutableStateOf(IntSize.Zero) }
            // La transformación se reinicia con la PETICIÓN COMPLETA (raíz, fotograma, clave e
            // integridad declarada) y NUNCA con el tamaño del contenedor: abrir/cerrar el panel de
            // desplazamiento o un cambio de alto disponible no vuelve a 1×.
            val viewportState = remember(request) { mutableStateOf(AngleDetailViewport()) }
            var viewport by viewportState
            var container by containerState
            // Al cambiar el área disponible se REAJUSTA, no se reinicia: se conserva el zoom y sólo
            // se vuelve a acotar el desplazamiento a los límites nuevos.
            LaunchedEffect(container, frame.imageWidth, frame.imageHeight) {
                if (container.width <= 0 || container.height <= 0) return@LaunchedEffect
                val reclamped = angleDetailClampViewport(
                    viewport = viewport,
                    containerWidth = container.width.toFloat(),
                    containerHeight = container.height.toFloat(),
                    imageWidth = frame.imageWidth,
                    imageHeight = frame.imageHeight,
                )
                if (reclamped != viewport) viewport = reclamped
            }
            val widthPx = container.width.toFloat()
            val heightPx = container.height.toFloat()
            // Botones del pie: leen el tamaño y la vista VIGENTES en el momento del toque, no una
            // copia capturada por la composición que creó la lambda.
            fun updateFromControls(
                transform: (AngleDetailViewport, Float, Float) -> AngleDetailViewport,
            ) {
                val size = containerState.value
                if (size.width <= 0 || size.height <= 0) return
                val current = viewportState.value
                val next = transform(current, size.width.toFloat(), size.height.toFloat())
                if (next != current) viewportState.value = next
            }
            // Una sola fuente de insets: con cota nativa ya excluida no se vuelve a rellenar.
            val legacyInsetPadding =
                if (safeViewport == null) Modifier.systemBarsPadding() else Modifier
            Column(Modifier.fillMaxSize().then(legacyInsetPadding)) {
                // Cierre propio en la barra superior: no compite con los controles del pie ni queda
                // dentro de él. Todo el visor es de sólo lectura; esto únicamente cierra el diálogo.
                AngleDetailTopBar(onClose = onClose)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { measured ->
                            // Reacota SÍNCRONAMENTE en la fase de medida: este mismo fotograma se
                            // dibuja ya con la vista legal. Antes el tamaño sólo se publicaba aquí y
                            // el clamp llegaba en un `LaunchedEffect` posterior, así que el primer
                            // fotograma tras un cambio de alto podía pintar una franja vacía.
                            containerState.value = measured
                            if (measured.width > 0 && measured.height > 0) {
                                val clamped = angleDetailClampViewport(
                                    viewport = viewportState.value,
                                    containerWidth = measured.width.toFloat(),
                                    containerHeight = measured.height.toFloat(),
                                    imageWidth = frame.imageWidth,
                                    imageHeight = frame.imageHeight,
                                )
                                if (clamped != viewportState.value) viewportState.value = clamped
                            }
                        }
                        .clipToBounds()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        // El handler se recrea con la PETICIÓN COMPLETA (no con el id de fotograma):
                        // cambia de raíz, de clave o de integridad declarada y no arrastra estado.
                        .pointerInput(request) {
                            detectTransformGestures(panZoomLock = true) { centroid, pan, zoomChange, _ ->
                                val size = containerState.value
                                if (size.width <= 0 || size.height <= 0) return@detectTransformGestures
                                val current = viewportState.value
                                val next = angleDetailTransformGesture(
                                    viewport = current,
                                    zoomChange = zoomChange,
                                    panX = pan.x,
                                    panY = pan.y,
                                    centroidX = centroid.x,
                                    centroidY = centroid.y,
                                    containerWidth = size.width.toFloat(),
                                    containerHeight = size.height.toFloat(),
                                    imageWidth = frame.imageWidth,
                                    imageHeight = frame.imageHeight,
                                )
                                if (next != current) viewportState.value = next
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(
                            R.string.angle_multi_frame_frame_label,
                            frame.displayFrameNumber,
                        ),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(ANGLE_V2_SAVED_DIALOG_IMAGE_TAG)
                            // Estado de vista realmente aplicado al bitmap (zoom/pan acotados).
                            .semantics { angleDetailViewport = viewport }
                            .graphicsLayer {
                                scaleX = viewport.zoom
                                scaleY = viewport.zoom
                                translationX = viewport.panX
                                translationY = viewport.panY
                            },
                    )
                }
                ViewportControls(
                    zoom = viewport.zoom,
                    onZoomIn = {
                        updateFromControls { current, w, h ->
                            angleDetailZoomBy(
                                viewport = current,
                                delta = ANGLE_DETAIL_ZOOM_STEP,
                                containerWidth = w,
                                containerHeight = h,
                                imageWidth = frame.imageWidth,
                                imageHeight = frame.imageHeight,
                            )
                        }
                    },
                    onZoomOut = {
                        updateFromControls { current, w, h ->
                            angleDetailZoomBy(
                                viewport = current,
                                delta = -ANGLE_DETAIL_ZOOM_STEP,
                                containerWidth = w,
                                containerHeight = h,
                                imageWidth = frame.imageWidth,
                                imageHeight = frame.imageHeight,
                            )
                        }
                    },
                    onReset = { viewportState.value = angleDetailResetViewport() },
                    onPan = { dx, dy ->
                        updateFromControls { current, w, h ->
                            angleDetailPanBy(
                                viewport = current,
                                dx = dx,
                                dy = dy,
                                containerWidth = w,
                                containerHeight = h,
                                imageWidth = frame.imageWidth,
                                imageHeight = frame.imageHeight,
                            )
                        }
                    },
                    panStepX = angleDetailPanStep(widthPx),
                    panStepY = angleDetailPanStep(heightPx),
                )
            }
        }
    }
}

/**
 * Barra superior del visor: un único control de cierre, con área táctil de 48 dp.
 *
 * El cierre vive AQUÍ y no en el pie: es la acción de salida de la ventana, no una acción de
 * imagen, y así el pie compacto se reserva para zoom, ajuste y desplazamiento.
 */
@Composable
private fun AngleDetailTopBar(onClose: () -> Unit) {
    val barDescription = stringResource(R.string.angle_polish_control_viewer_bar)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = Spacing.xs)
            .testTag(ANGLE_V2_SAVED_DIALOG_TOPBAR_TAG)
            .semantics { contentDescription = barDescription },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerIconButton(
            iconRes = R.drawable.ic_close,
            label = stringResource(R.string.angle_polish_control_close),
            onClick = onClose,
        )
    }
}

/**
 * Pie compacto y neutro del visor: zoom −, valor aplicado, zoom +, ajustar y «Mover».
 *
 * Es una sola fila de iconos (cada uno con etiqueta localizada, mínima de 48 dp) más el panel
 * secundario de cuatro flechas, que se revela con el conmutador «Mover» y que también usa iconos
 * con etiqueta. Ningún gesto es la única vía: todo lo que hace un dedo se puede hacer con un toque
 * explícito. El pie conserva su altura mínima de 56 dp y su tag estable; la imagen se queda con el
 * resto del alto.
 */
@Composable
private fun ViewportControls(
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    onPan: (Float, Float) -> Unit,
    panStepX: Float,
    panStepY: Float,
) {
    val controlsDescription = stringResource(R.string.angle_viewport_controls_description)
    // El panel de flechas se revela a demanda: cerrado, el pie es una sola fila y la imagen gana
    // alto; abierto, añade la vía alternativa al arrastre. Su estado se anuncia como expandido.
    var panPanelOpen by remember { mutableStateOf(false) }
    val moveState = stringResource(
        if (panPanelOpen) {
            R.string.angle_polish_control_move_on
        } else {
            R.string.angle_polish_control_move_off
        },
    )
    Column(
        // El tag y la descripción accesible van ANTES del relleno: el nodo del pie mide sus 56 dp
        // mínimos completos y las pruebas pueden medirlo como contenedor; el relleno interior de
        // 4 dp por lado (contenido 48 dp) se conserva intacto, igual que la geometría de los
        // controles y su separación.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(ANGLE_V2_SAVED_DIALOG_FOOTER_TAG)
            .semantics { contentDescription = controlsDescription }
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerIconButton(
                iconRes = R.drawable.ic_angle_zoom_out,
                label = stringResource(R.string.angle_polish_control_zoom_out),
                onClick = onZoomOut,
            )
            AngleDetailZoomLabel(zoom, Modifier.weight(1f))
            ViewerIconButton(
                iconRes = R.drawable.ic_angle_zoom_in,
                label = stringResource(R.string.angle_polish_control_zoom_in),
                onClick = onZoomIn,
            )
            ViewerIconButton(
                iconRes = R.drawable.ic_angle_collapse,
                label = stringResource(R.string.angle_polish_control_fit),
                onClick = onReset,
            )
            ViewerIconButton(
                iconRes = R.drawable.ic_angle_pan,
                label = stringResource(R.string.angle_polish_control_move),
                stateDescription = moveState,
                selected = panPanelOpen,
                onClick = { panPanelOpen = !panPanelOpen },
            )
        }
        if (panPanelOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ANGLE_V2_SAVED_DIALOG_PAN_PANEL_TAG),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerIconButton(
                    iconRes = R.drawable.ic_arrow_back,
                    label = stringResource(R.string.angle_polish_control_pan_left),
                    onClick = { onPan(-panStepX, 0f) },
                )
                ViewerIconButton(
                    iconRes = R.drawable.ic_arrow_back,
                    label = stringResource(R.string.angle_polish_control_pan_up),
                    rotation = 90f,
                    onClick = { onPan(0f, -panStepY) },
                )
                ViewerIconButton(
                    iconRes = R.drawable.ic_arrow_back,
                    label = stringResource(R.string.angle_polish_control_pan_right),
                    rotation = 180f,
                    onClick = { onPan(panStepX, 0f) },
                )
                ViewerIconButton(
                    iconRes = R.drawable.ic_arrow_back,
                    label = stringResource(R.string.angle_polish_control_pan_down),
                    rotation = 270f,
                    onClick = { onPan(0f, panStepY) },
                )
            }
        }
    }
}

/**
 * Zoom realmente aplicado, con la etiqueta localizada existente ([R.string.angle_zoom_value]).
 *
 * Es sólo lectura: no es un campo de entrada ni cambia la transformación por sí mismo. Se muestra
 * con un decimal para que el paso de zoom ([ANGLE_DETAIL_ZOOM_STEP]) sea legible, y lleva su propia
 * descripción para que el valor no se lea como un número suelto.
 */
@Composable
private fun AngleDetailZoomLabel(zoom: Float, modifier: Modifier = Modifier) {
    val locale = currentAppLocale()
    val formatted = remember(zoom, locale) {
        NumberFormat.getNumberInstance(locale).run {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            format(zoom.toDouble())
        }
    }
    val appliedDescription = stringResource(R.string.angle_polish_control_zoom_value, formatted)
    Text(
        stringResource(R.string.angle_zoom_value, formatted),
        modifier = modifier.semantics { contentDescription = appliedDescription },
        style = OpenJumpTypes.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * Botón de icono del visor: etiqueta localizada, tinte del tema y área táctil de al menos 48×48 dp.
 *
 * La etiqueta va como `contentDescription` (el icono no aporta texto propio), de modo que las
 * pruebas y el lector de pantalla apuntan al control REAL por su nombre localizado y nunca a un
 * texto pintado que ya no existe. [rotation] sólo gira el glifo de la flecha existente.
 */
@Composable
private fun ViewerIconButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    stateDescription: String? = null,
    selected: Boolean = false,
    rotation: Float = 0f,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = label
                if (stateDescription != null) this.stateDescription = stateDescription
            },
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = tint,
            modifier = if (rotation == 0f) Modifier else Modifier.rotate(rotation),
        )
    }
}

/**
 * Cota pura del contenido del diálogo en píxeles: ancho y alto REALES de la ventana nativa menos los
 * insets de las barras, el recorte de pantalla y el título de ventana.
 *
 * Devuelve `null` cuando el marco completo no es representable (`<= 0`): sin marco no se inventa una
 * cota y el llamador conserva el comportamiento heredado. Con marco válido, los insets negativos
 * cuentan como 0 y los desbordados se recortan: el resultado es SIEMPRE positivo y NUNCA mayor que
 * el marco disponible. No hay constantes de barra, de pie ni de pantalla: la única fuente son los
 * números nativos de esta ventana.
 */
internal fun angleDialogSafeViewport(
    fullWidth: Int,
    fullHeight: Int,
    insetLeft: Int,
    insetTop: Int,
    insetRight: Int,
    insetBottom: Int,
): IntSize? {
    if (fullWidth <= 0 || fullHeight <= 0) return null
    return IntSize(
        angleDialogSafeExtent(fullWidth, insetLeft, insetRight),
        angleDialogSafeExtent(fullHeight, insetTop, insetBottom),
    )
}

private fun angleDialogSafeExtent(full: Int, firstInset: Int, secondInset: Int): Int {
    val first = firstInset.coerceIn(0, full)
    val second = secondInset.coerceIn(0, full)
    // En `Long`: la suma de dos insets acotados a `full` no puede desbordar el cálculo.
    val remaining = full.toLong() - first.toLong() - second.toLong()
    return remaining.coerceIn(1L, full.toLong()).toInt()
}

/**
 * Viewport visible de la ventana PROPIA de este diálogo, leído sin mutar nada.
 *
 * API 30+: `WindowManager.currentWindowMetrics` de ESTE contexto, no la configuración de pantalla ni
 * `LocalConfiguration.screenHeightDp` (que es de dónde salía la altura equivocada de 2399). API 26–29:
 * `getWindowVisibleDisplayFrame`, que ya excluye las barras de la ventana; sólo cuando el host está
 * adjunto. Sin proyección disponible se devuelve `null` y se conserva el relleno heredado.
 *
 * La lectura es SÍNCRONA en la primera composición, se repite al cambiar la configuración y de forma
 * PASIVA cuando la ventana se vuelve a medir/colocar (observador propio, sin tocar los listeners de
 * insets del framework ni el `window.setLayout` del diálogo). Se compara antes de publicar para no
 * realimentar el layout con la propia cota.
 */
@Composable
private fun rememberAngleDialogSafeViewport(): IntSize? {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val safeViewport = remember(view, configuration) {
        mutableStateOf(angleDialogNativeSafeViewport(view))
    }
    DisposableEffect(view, configuration) {
        fun refresh() {
            val projection = angleDialogNativeSafeViewport(view)
            if (projection != null && projection != safeViewport.value) {
                safeViewport.value = projection
            }
        }
        refresh()
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                attached.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                refresh()
            }

            override fun onViewDetachedFromWindow(detached: View) {
                detached.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        view.addOnAttachStateChangeListener(attachListener)
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            view.removeOnAttachStateChangeListener(attachListener)
        }
    }
    return safeViewport.value
}

private fun angleDialogNativeSafeViewport(view: View): IntSize? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        angleDialogSafeViewportFromWindowMetrics(view)
    } else {
        angleDialogSafeViewportFromVisibleFrame(view)
    }

@RequiresApi(Build.VERSION_CODES.R)
private fun angleDialogSafeViewportFromWindowMetrics(view: View): IntSize? {
    val windowManager = view.context.getSystemService(WindowManager::class.java) ?: return null
    val metrics = windowManager.currentWindowMetrics
    // `getInsetsIgnoringVisibility`: la cota no puede cambiar porque una barra esté oculta ahora.
    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
        android.view.WindowInsets.Type.systemBars() or
            android.view.WindowInsets.Type.displayCutout() or
            android.view.WindowInsets.Type.captionBar(),
    )
    return angleDialogSafeViewport(
        fullWidth = metrics.bounds.width(),
        fullHeight = metrics.bounds.height(),
        insetLeft = insets.left,
        insetTop = insets.top,
        insetRight = insets.right,
        insetBottom = insets.bottom,
    )
}

private fun angleDialogSafeViewportFromVisibleFrame(view: View): IntSize? {
    if (!view.isAttachedToWindow) return null
    val frame = android.graphics.Rect()
    view.getWindowVisibleDisplayFrame(frame)
    // El marco visible ya excluye las barras de la ventana: sus insets efectivos son cero.
    return angleDialogSafeViewport(frame.width(), frame.height(), 0, 0, 0, 0)
}
