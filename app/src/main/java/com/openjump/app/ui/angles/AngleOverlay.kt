package com.openjump.app.ui.angles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.selector.ContentRect
import com.openjump.app.ui.selector.VideoCoordinateMapper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Capa de presentación (Canvas Compose) del editor manual de ángulos 2D.
 *
 * ## Qué dibuja
 *
 * - Los segmentos A-B y B-C de **todos** los ángulos visibles de [state], sin límite arbitrario; los
 *   ángulos ocultos no se dibujan ni son interactivos. El orden de creación decide el orden de
 *   dibujo y los desempates; el ángulo seleccionado se pinta al final (encima).
 * - El arco **interior** en el vértice B, calculado con la diferencia angular más corta
 *   ([interiorArcSpec]): su magnitud nunca supera 180°, de modo que un ángulo llano (180°) o casi
 *   llano no dibuja el complemento reflejo de 360°.
 * - Una etiqueta por ángulo visible con el texto que devuelve [formatLabel]. Esta capa no contiene
 *   textos localizados ni redondea: el llamante decide idioma y formato (por ejemplo un decimal) y
 *   recibe los grados crudos de [Angle.measurement].
 * - El marcador B con forma propia (rombo) para distinguirlo de A y C (círculos) incluso en
 *   monocromo o con daltonismo. La selección **no** depende solo del color: añade trazo más grueso,
 *   anillo doble alrededor de las tres marcas y borde de placa en la etiqueta.
 * - Los puntos del borrador A→B con línea discontinua y marcas huecas (también con B en rombo).
 * - Anillos tenues de afordancia en las asas activas (ángulo seleccionado y borrador). El área
 *   táctil real es de 48dp de diámetro aunque el anillo visible sea menor.
 *
 * ## Coordenadas y transformación
 *
 * Reutiliza [VideoCoordinateMapper]: los puntos del estado son píxeles de presentación
 * **post-rotación** y la capa aplica FIT/CROP, zoom y pan igual que el vídeo. Un toque fuera del
 * rectángulo de contenido (barras de letterbox o fuera del viewport) se rechaza con
 * [AngleOverlayTarget.Ignored]: no coloca puntos ni cambia la selección. Un arrastre que sale de la
 * imagen deja de emitir movimientos ([markerDragTarget] devuelve `null`) en vez de escribir
 * coordenadas fuera del frame.
 *
 * ## Interacción y propiedad del gesto
 *
 * El modo es **explícito** ([AngleOverlayMode]); no se deduce de qué callbacks llegan.
 *
 * - [AngleOverlayMode.EDIT]: el arrastre solo afecta a **asas activas**: los tres puntos del ángulo
 *   seleccionado y los puntos del borrador. Las marcas de un ángulo no seleccionado nunca se
 *   arrastran: un toque sobre ellas lo selecciona ([onSelectAngle]) y el siguiente gesto ya puede
 *   arrastrarla. Así un toque no muta geometría por accidente y la selección es siempre explícita.
 *   En este modo un toque **nunca coloca puntos**, aunque el llamante aporte [onPlacePoint].
 * - [AngleOverlayMode.PLACEMENT]: un toque dentro del contenido coloca el siguiente punto del
 *   borrador ([onPlacePoint]) **aunque caiga sobre un asa activa, sobre otra línea o a menos de 24dp
 *   de ambas**: en colocación el dato es el punto y el *hit test* de asas no roba el toque. En este
 *   modo no se arrastran asas ni se cambia la selección; un arrastre no se consume y queda para el
 *   vídeo subyacente (pan/zoom).
 * - [AngleOverlayMode.FRAMING]: no se instala ningún `pointerInput`, de modo que la capa no consume
 *   ni intercepta los gestos de encuadre del vídeo subyacente.
 * - Colisiones en EDIT: gana la asa activa más cercana; en empate exacto el borrador tiene prioridad
 *   sobre el ángulo seleccionado y, dentro del ángulo, A &lt; B &lt; C. Si no hay asa, gana el cuerpo
 *   del ángulo visible más cercano (marcador o segmento); en empate exacto gana el que se dibuja
 *   **encima** (el seleccionado y, si no, el de creación más reciente), para que el *hit test* sea
 *   coherente con lo que el usuario ve en una superposición.
 * - Un toque sin arrastre sobre un asa activa en EDIT no hace nada: evita mover o deseleccionar por
 *   accidente al intentar arrastrar.
 * - Esta capa **no muta** [AngleEditorState]. Todos los callbacks son intenciones; el propietario
 *   decide si las aplica con la [com.openjump.app.angles.AngleFrameConfirmation] vigente.
 * - **Visibilidad de la geometría.** [showsGeometry] es la única vía admitida para no dibujar la
 *   geometría cuando el frame renderizado no es el ancla: el llamante pasa
 *   `state.showsGeometryAt(frameConfirmado)`. Con `false` no se dibuja nada ni se instalan gestos.
 *   No se debe reutilizar [enabled] para ocultar: [enabled] describe otra cosa (capa atenuada y sin
 *   gestos por un motivo ajeno al frame) y confundirlos hace ambigua la causa.
 * - Solo se sigue el primer puntero; un segundo dedo no inicia un arrastre nuevo.
 *
 * ## Accesibilidad (limitaciones de TalkBack)
 *
 * El Canvas expone **un único nodo semántico**. TalkBack no puede enfocar por separado las marcas ni
 * arrastrarlas, y el orden de lectura no distingue ángulos individuales. Por eso el llamante debe:
 *
 * - pasar [accessibilityDescription] ya localizado (nombres, grados, selección, borrador);
 * - ofrecer controles accesibles propios mediante [accessibleControlsLabel] y
 *   [onAccessibleControlsRequested], que se publican como acción personalizada del nodo.
 *
 * Sin esos parámetros la capa no anuncia nada: no inventa textos. La colocación y el ajuste fino de
 * puntos para TalkBack pertenecen a los controles del llamante, no a este Canvas.
 *
 * @param mapper transformación vídeo→pantalla vigente, incluida rotación ya aplicada en
 *   [com.openjump.app.tracking.VideoPresentationGeometry].
 * @param state estado inmutable del editor; se lee, nunca se modifica.
 * @param formatLabel texto visible de la etiqueta de un ángulo; recibe el ángulo y sus grados sin
 *   redondear. Debe estar localizado y decidir los decimales.
 * @param accessibilityDescription descripción localizada del nodo completo; `null` si el llamante
 *   prefiere no publicar semántica.
 * @param markerLabel glifo de cada marca. Por defecto A/B/C del propio enum (identificadores
 *   geométricos, no texto traducible); el llamante puede sustituirlo.
 * @param mode modo explícito de la capa: EDIT (selección y arrastre), PLACEMENT (todo toque coloca el
 *   siguiente punto) o FRAMING (sin gestos, para no consumir el zoom/pan del vídeo).
 * @param showsGeometry si la geometría de [state] corresponde al frame renderizado; el llamante pasa
 *   `state.showsGeometryAt(frameConfirmado)`. Con `false` no se dibuja ni se instalan gestos. No es
 *   un sustituto de [enabled].
 * @param onPlacePoint solicitud de colocar el siguiente punto del borrador; **solo se usa en
 *   [AngleOverlayMode.PLACEMENT]**. En EDIT se ignora aunque no sea `null`.
 * @param onSelectAngle selección explícita de un ángulo visible; `null` limpia la selección. Solo se
 *   usa en [AngleOverlayMode.EDIT].
 * @param onMoveMarker arrastre de una marca A/B/C de un ángulo completo (coordenadas de imagen).
 * @param onMoveDraftMarker arrastre de una marca ya colocada del borrador.
 * @param interactionKey identidad opcional del gesto (por ejemplo fuente+generación+frame). Si cambia,
 *   el detector en curso se cancela y el preview se descarta: dos fotogramas distintos con el mismo
 *   mapper e incluso el mismo [AngleId] nunca comparten un arrastre a medias. `null` conserva el
 *   comportamiento legacy (la clave es solo mapper/métricas/modo).
 * @param deferredGestureCommit opt-in del workspace: el arrastre de un asa no llama al propietario en
 *   cada movimiento; deriva un preview local con [AngleEditorState.moveMarker]/
 *   [AngleEditorState.moveDraftMarker] sobre el estado base y la [previewConfirmation] real, lo dibuja
 *   con la misma proyección y confirma UNA sola vez al levantar el único dedo. Cualquier segundo
 *   dedo, consumo externo, cancelación o cambio de [interactionKey] descarta todo el preview sin
 *   rollback ni callbacks. `false` por defecto: el comportamiento legacy no cambia.
 * @param previewConfirmation confirmación real del frame base (ancla+generación, en pausa) que el
 *   preview usa para validar su derivación; solo se usa con [deferredGestureCommit].
 * @param onPreviewActiveChanged aviso de que hay un preview provisional sin confirmar (`true`) o de
 *   que ya no lo hay (`false`), para que el propietario inhiba OTRAS acciones sin bloquear la capa.
 * @param abortPlacementOnMultitouch opt-in del workspace para la fase de colocación: al ver dos o
 *   más punteros (o un stream ya consumido por otro detector) se abandona la colocación pendiente y
 *   se consume hasta que **todos** los dedos suben, de modo que un pinch nunca deja un punto A/B/C
 *   extra aunque el segundo dedo se levante antes que el primero. `false` por defecto conserva el
 *   comportamiento legacy de un toque/arrastre de un dedo.
 */
@Composable
fun AngleOverlay(
    mapper: VideoCoordinateMapper,
    state: AngleEditorState,
    formatLabel: (Angle, Double) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mode: AngleOverlayMode = AngleOverlayMode.EDIT,
    showsGeometry: Boolean = true,
    accessibilityDescription: String? = null,
    markerLabel: (AngleMarker) -> String = { it.name },
    onSelectAngle: (AngleId?) -> Unit = {},
    onPlacePoint: ((AnglePoint) -> Unit)? = null,
    onMoveMarker: (AngleId, AngleMarker, AnglePoint) -> Unit = { _, _, _ -> },
    onMoveDraftMarker: (AngleMarker, AnglePoint) -> Unit = { _, _ -> },
    accessibleControlsLabel: String? = null,
    onAccessibleControlsRequested: (() -> Unit)? = null,
    interactionKey: Any? = null,
    deferredGestureCommit: Boolean = false,
    previewConfirmation: AngleFrameConfirmation? = null,
    onPreviewActiveChanged: ((Boolean) -> Unit)? = null,
    abortPlacementOnMultitouch: Boolean = false,
) {
    val latestMapper by rememberUpdatedState(mapper)
    val latestState by rememberUpdatedState(state)
    val latestConfirmation by rememberUpdatedState(previewConfirmation)
    val latestOnPreviewActiveChanged by rememberUpdatedState(onPreviewActiveChanged)
    // Preview provisional del workspace: estado derivado con la API PURA existente sobre el estado
    // base del gesto, nunca enviado al ViewModel. Se ata a interactionKey (escena) y al mapper
    // (proyección): un cambio de escena o de proyección lo descarta entero (preview + aviso de
    // ocupación), sin rollback del dominio. La arena padre sigue con clave de escena estable.
    var preview by remember(interactionKey, mapper) { mutableStateOf<AngleOverlayPreview?>(null) }
    val renderedState = preview?.state ?: state
    val layout = remember(mapper, renderedState) { angleOverlayLayout(mapper, renderedState) }
    val latestLayout by rememberUpdatedState(layout)
    DisposableEffect(interactionKey, mapper) {
        onDispose {
            preview = null
            latestOnPreviewActiveChanged?.invoke(false)
        }
    }
    // Si el estado base cambia por fuera (el propietario aplicó otra acción), el preview deja de
    // representar el gesto y se descarta; el commit comprueba además la identidad del base.
    // La proyección también invalida el preview (misma escena con resize): no se confirma geometría
    // calculada antes del relayout.
    LaunchedEffect(state, interactionKey, mapper) {
        if (preview?.base !== state) {
            if (preview != null) latestOnPreviewActiveChanged?.invoke(false)
            preview = null
        }
    }
    val latestOnSelectAngle by rememberUpdatedState(onSelectAngle)
    val latestOnPlacePoint by rememberUpdatedState(onPlacePoint)
    val latestOnMoveMarker by rememberUpdatedState(onMoveMarker)
    val latestOnMoveDraftMarker by rememberUpdatedState(onMoveDraftMarker)
    val latestFormatLabel by rememberUpdatedState(formatLabel)
    val latestMarkerLabel by rememberUpdatedState(markerLabel)

    val density = LocalDensity.current
    val metrics = remember(density) {
        AngleOverlayTouchMetrics(
            handleRadiusPx = with(density) { HANDLE_TOUCH_RADIUS.toPx() }.toDouble(),
            markerRadiusPx = with(density) { HANDLE_TOUCH_RADIUS.toPx() }.toDouble(),
            lineTolerancePx = with(density) { BODY_TOUCH_TOLERANCE.toPx() }.toDouble(),
        )
    }
    val gesturesEnabled = shouldInstallAngleGestures(enabled, mode, showsGeometry)
    val gestureModifier = if (gesturesEnabled) {
        Modifier.pointerInput(
            if (deferredGestureCommit) interactionKey else mapper,
            mapper,
            metrics,
            mode,
            deferredGestureCommit,
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downView = down.position.toImagePoint()
                // Coordenadas canónicas con la proyección VIGENTE (post-pinch): el mapper capturado
                // en la composición puede ser anterior al encuadre; latestMapper es el actual.
                if (!latestMapper.contentRect.contains(downView)) return@awaitEachGesture

                if (mode == AngleOverlayMode.PLACEMENT) {
                    // Colocación explícita: solo el toque actúa. Un arrastre no mueve asas ni se
                    // consume, de modo que el vídeo subyacente conserva el pan/zoom.
                    var travel = 0.0
                    var latchMulti = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (abortPlacementOnMultitouch) {
                            val pressedCount = event.changes.count { it.pressed }
                            val externallyConsumed = event.changes.any { it.isConsumed }
                            if (pressedCount >= 2) latchMulti = true
                            if (latchMulti || externallyConsumed) {
                                // Un pinch (o un stream que otro detector ya consumió) no coloca
                                // ningún punto: se consume hasta que suben TODOS los dedos, incluso
                                // si el segundo se levanta antes que el primero.
                                event.changes.forEach { it.consume() }
                                if (pressedCount == 0) break
                                continue
                            }
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        travel = max(
                            travel,
                            hypot(
                                (change.position.x - down.position.x).toDouble(),
                                (change.position.y - down.position.y).toDouble(),
                            ),
                        )
                        if (!change.pressed) {
                            if (
                                change.changedToUpIgnoreConsumed() &&
                                !change.isConsumed &&
                                travel <= viewConfiguration.touchSlop
                            ) {
                                change.consume()
                                val tap = resolveAngleOverlayTap(
                                    downView = downView,
                                    mapper = latestMapper,
                                    layout = latestLayout,
                                    metrics = metrics,
                                    mode = mode,
                                )
                                if (tap is AngleOverlayTap.PlacePoint) {
                                    latestOnPlacePoint?.invoke(tap.videoPoint)
                                }
                            }
                            break
                        }
                        if (travel > viewConfiguration.touchSlop) break
                    }
                    return@awaitEachGesture
                }

                val target = resolveAngleOverlayTarget(downView, latestMapper, latestLayout, metrics)
                if (target is AngleOverlayTarget.Ignored) return@awaitEachGesture

                val handle = target as? AngleOverlayTarget.Handle
                if (deferredGestureCommit) {
                    // Editor transaccional del workspace: el dominio NO se toca durante el arrastre.
                    // El preview es local, derivado con la API pura existente sobre el estado base
                    // capturado al empezar y la confirmación REAL del frame; se dibuja con la misma
                    // proyección y solo se confirma una vez, al levantar el único dedo.
                    // Proyección del gesto: si cambia a mitad (resize/zoom misma escena) se descarta.
                    val gestureMapper = latestMapper
                    var dragBase: AngleEditorState? = null
                    var pendingPoint: AnglePoint? = null
                    var grabOffset: AnglePoint? = null
                    var dragged = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Misma escena con proyección distinta entre MOVE y UP (resize): el punto
                            // pendiente se calculó antes del relayout y no se confirma (0 commits).
                            // No se depende de que Android entregue ACTION_CANCEL.
                            if (latestMapper !== gestureMapper) {
                                return@awaitEachGesture
                            }
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount > 1) {
                                // Cualquier segundo dedo abandona TODO el preview (incluido el tramo
                                // ya dibujado): sin rollback del ViewModel y sin callbacks. El
                                // encuadre de dos dedos pertenece al detector padre.
                                return@awaitEachGesture
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                val liftedClean = change.changedToUpIgnoreConsumed() &&
                                    !change.isConsumed && pressedCount <= 1
                                val base = dragBase
                                val point = pendingPoint
                                if (liftedClean && point != null && handle != null &&
                                    base != null && base == latestState
                                ) {
                                    change.consume()
                                    if (handle.angleId == null) {
                                        latestOnMoveDraftMarker(handle.marker, point)
                                    } else {
                                        latestOnMoveMarker(handle.angleId, handle.marker, point)
                                    }
                                } else if (liftedClean && !dragged && handle == null) {
                                    // El toque sin arrastre conserva la selección explícita del modo
                                    // EDIT: el cuarto toque S3 y la selección de cuerpos no cambian.
                                    val tap = resolveAngleOverlayTap(
                                        downView = downView,
                                        mapper = latestMapper,
                                        layout = latestLayout,
                                        metrics = metrics,
                                        mode = mode,
                                    )
                                    if (tap is AngleOverlayTap.Select) {
                                        latestOnSelectAngle(tap.angleId)
                                    }
                                }
                                break
                            }
                            val travel = hypot(
                                (change.position.x - down.position.x).toDouble(),
                                (change.position.y - down.position.y).toDouble(),
                            )
                            if (change.isConsumed && (dragged || pendingPoint != null)) {
                                // Otro detector consumió el stream del dedo: se descarta el preview
                                // entero sin publicar geometría imposible.
                                return@awaitEachGesture
                            }
                            if (handle == null) {
                                if (travel > viewConfiguration.touchSlop) {
                                    // Arrastre sobre cuerpo/vacío: no es de esta capa; se cede el
                                    // gesto al vídeo subyacente sin consumir.
                                    return@awaitEachGesture
                                }
                                continue
                            }
                            if (!dragged && travel > viewConfiguration.touchSlop) {
                                val downVideo = latestMapper.viewToVideo(downView) ?: break
                                grabOffset = AnglePoint(
                                    x = handle.videoPoint.x - downVideo.x,
                                    y = handle.videoPoint.y - downVideo.y,
                                )
                                dragBase = latestState
                                dragged = true
                            }
                            val offset = grabOffset
                            if (dragged && offset != null) {
                                change.consume()
                                val targetPoint = markerDragTarget(
                                    latestMapper,
                                    change.position.toImagePoint(),
                                    offset,
                                ) ?: continue
                                val base = dragBase ?: continue
                                val confirmation = latestConfirmation ?: continue
                                val derived = angleOverlayPreviewState(
                                    base = base,
                                    handle = handle,
                                    point = targetPoint,
                                    confirmation = confirmation,
                                ) ?: continue
                                preview = AngleOverlayPreview(
                                    base = base,
                                    state = derived,
                                    point = targetPoint,
                                    handle = handle,
                                )
                                pendingPoint = targetPoint
                                latestOnPreviewActiveChanged?.invoke(true)
                            }
                        }
                    } finally {
                        preview = null
                        latestOnPreviewActiveChanged?.invoke(false)
                    }
                    return@awaitEachGesture
                }
                var grabOffset: AnglePoint? = null
                var dragged = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val travel = hypot(
                        (change.position.x - down.position.x).toDouble(),
                        (change.position.y - down.position.y).toDouble(),
                    )
                    if (!change.pressed) {
                        if (change.changedToUpIgnoreConsumed() && !change.isConsumed) {
                            change.consume()
                            if (!dragged && handle == null) {
                                val tap = resolveAngleOverlayTap(
                                    downView = downView,
                                    mapper = mapper,
                                    layout = latestLayout,
                                    metrics = metrics,
                                    mode = mode,
                                )
                                if (tap is AngleOverlayTap.Select) latestOnSelectAngle(tap.angleId)
                            }
                        }
                        break
                    }
                    if (!dragged && handle != null && travel > viewConfiguration.touchSlop) {
                        val downVideo = mapper.viewToVideo(downView)
                        if (downVideo == null) break
                        grabOffset = AnglePoint(
                            x = handle.videoPoint.x - downVideo.x,
                            y = handle.videoPoint.y - downVideo.y,
                        )
                        dragged = true
                    }
                    val offset = grabOffset
                    if (offset != null) {
                        change.consume()
                        val targetPoint = markerDragTarget(mapper, change.position.toImagePoint(), offset)
                        if (targetPoint != null) {
                            val angleId = handle?.angleId
                            if (angleId == null && handle != null) {
                                latestOnMoveDraftMarker(handle.marker, targetPoint)
                            } else if (angleId != null && handle != null) {
                                latestOnMoveMarker(angleId, handle.marker, targetPoint)
                            }
                        }
                    } else if (handle == null && travel > viewConfiguration.touchSlop) {
                        // Un arrastre sobre el cuerpo o el vacío no es una acción de esta capa:
                        // se abandona sin consumir para no robar el gesto al vídeo subyacente.
                        break
                    }
                }
            }
        }
    } else {
        Modifier
    }

    val semanticModifier = Modifier.semantics {
        accessibilityDescription?.let { contentDescription = it }
        val actionLabel = accessibleControlsLabel
        val action = onAccessibleControlsRequested
        if (!actionLabel.isNullOrBlank() && action != null) {
            customActions = listOf(
                CustomAccessibilityAction(actionLabel) {
                    action()
                    true
                },
            )
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = AngleOverlayLabelTextStyle
    val markerStyle = AngleOverlayMarkerTextStyle
    val editingAffordances = enabled && mode != AngleOverlayMode.FRAMING
    val renderAlpha = if (enabled) 1f else DISABLED_ALPHA

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(gestureModifier)
            .then(semanticModifier),
    ) {
        if (!showsGeometry) return@Canvas
        val contentRect = mapper.contentRect
        val haloWidth = ANGLE_OVERLAY_HALO_WIDTH_DP.dp.toPx()
        for (entry in angleOverlayDrawOrder(layout.entries)) {
            drawAngleEntry(
                entry = entry,
                textMeasurer = textMeasurer,
                labelStyle = labelStyle,
                markerStyle = markerStyle,
                formatLabel = latestFormatLabel,
                markerLabel = latestMarkerLabel,
                contentRect = contentRect,
                editingAffordances = editingAffordances,
                alpha = renderAlpha,
                haloWidth = haloWidth,
            )
        }
        drawDraft(
            draftPoints = layout.draftPoints,
            textMeasurer = textMeasurer,
            markerStyle = markerStyle,
            markerLabel = latestMarkerLabel,
            editingAffordances = editingAffordances,
            alpha = renderAlpha,
            haloWidth = haloWidth,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Constantes de presentación
// ---------------------------------------------------------------------------------------------

/** Radio táctil (24dp → 48dp de diámetro) de asas y marcadores seleccionables. */
private val HANDLE_TOUCH_RADIUS = 24.dp

/** Tolerancia táctil alrededor de los segmentos A-B y B-C para seleccionar un ángulo. */
private val BODY_TOUCH_TOLERANCE = 12.dp

private const val DISABLED_ALPHA = 0.45f
private const val BODY_BASE_STROKE_DP = 1.5f
private const val BODY_SELECTED_STROKE_DP = 3.5f
private const val ARC_BASE_STROKE_DP = 1.5f
private const val ARC_SELECTED_STROKE_DP = 2.5f
private const val MARKER_BASE_RADIUS_DP = 4.5f
private const val MARKER_SELECTED_RADIUS_DP = 6f
private const val DRAFT_MARKER_RADIUS_DP = 7f
private const val SELECTION_RING_RADIUS_DP = 12f
private const val HANDLE_AFFORDANCE_RADIUS_DP = 18f
private const val LABEL_PADDING_HORIZONTAL_DP = 5f
private const val LABEL_PADDING_VERTICAL_DP = 2f
private const val LABEL_CORNER_DP = 5f
private const val ARC_MIN_RADIUS_DP = 14f
private const val ARC_MAX_RADIUS_DP = 72f
private const val ARC_RADIUS_FRACTION = 0.35

private val HaloColor = Color.Black.copy(alpha = 0.82f)
private val OutlineColor = Color.White
private val LabelPlateColor = Color.Black.copy(alpha = 0.68f)
private val DraftColor = Color(0xFFFFD54F)

/**
 * Ancho del halo oscuro que separa el trazo del color del ángulo del contenido de la imagen.
 * Compartido por la capa Compose del editor y la anotación fuera de pantalla ([AngleAnnotationRenderer]).
 */
internal const val ANGLE_OVERLAY_HALO_WIDTH_DP = 2f

/**
 * Estilo del texto de la etiqueta de grados (12sp semibold blanco). Es un valor inmutable compartido
 * para que el editor y la anotación fuera de pantalla midan y dibujen la etiqueta igual.
 */
internal val AngleOverlayLabelTextStyle =
    TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

/** Estilo del glifo A/B/C de la marca seleccionada (11sp bold blanco), compartido como el anterior. */
internal val AngleOverlayMarkerTextStyle =
    TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)

// ---------------------------------------------------------------------------------------------
// Geometría pura, testable sin Android
// ---------------------------------------------------------------------------------------------

/** Punto de un ángulo visible ya proyectado a píxeles de pantalla. */
internal data class AngleOverlayEntry(
    val angle: Angle,
    val a: ImagePoint,
    val b: ImagePoint,
    val c: ImagePoint,
    /** Medición sin redondear; el formato (un decimal, idioma) es del llamante. */
    val degrees: Double,
    val selected: Boolean,
) {
    val angleId: AngleId get() = angle.id
}

/** Punto ya colocado del borrador A→B, con sus coordenadas de imagen y de pantalla. */
internal data class AngleOverlayDraftPoint(
    val marker: AngleMarker,
    val videoPoint: AnglePoint,
    val viewPoint: ImagePoint,
)

/** Proyección de solo lectura del estado del editor para dibujar y resolver gestos. */
internal data class AngleOverlayLayout(
    val entries: List<AngleOverlayEntry>,
    val draftPoints: List<AngleOverlayDraftPoint>,
)

/** Ángulos visibles en orden de creación; los ocultos no se proyectan ni son interactivos. */
internal fun angleOverlayLayout(
    mapper: VideoCoordinateMapper,
    state: AngleEditorState,
): AngleOverlayLayout {
    val entries = state.angles.mapNotNull { angle ->
        if (!angle.visible) return@mapNotNull null
        val measured = angle.measurement as? AngleMeasurement.Measured ?: return@mapNotNull null
        AngleOverlayEntry(
            angle = angle,
            a = mapper.videoToView(angle.markers.a.toImagePoint()),
            b = mapper.videoToView(angle.markers.b.toImagePoint()),
            c = mapper.videoToView(angle.markers.c.toImagePoint()),
            degrees = measured.degrees,
            selected = angle.id == state.selectedAngleId,
        )
    }
    val draftPoints = state.draft.points.mapIndexed { index, point ->
        AngleOverlayDraftPoint(
            marker = AngleMarker.entries[index],
            videoPoint = point,
            viewPoint = mapper.videoToView(point.toImagePoint()),
        )
    }
    return AngleOverlayLayout(entries = entries, draftPoints = draftPoints)
}

/** El seleccionado se dibuja al final para quedar encima del resto; el orden restante se conserva. */
internal fun angleOverlayDrawOrder(entries: List<AngleOverlayEntry>): List<AngleOverlayEntry> =
    entries.filterNot { it.selected } + entries.filter { it.selected }

/** Forma de una marca; B (vértice) usa rombo para distinguirse sin depender del color. */
internal enum class AngleMarkerShape { CIRCLE, DIAMOND }

internal fun markerShapeFor(marker: AngleMarker): AngleMarkerShape =
    if (marker == AngleMarker.B) AngleMarkerShape.DIAMOND else AngleMarkerShape.CIRCLE

/** Radios táctiles en píxeles de pantalla; el llamante los deriva de la densidad (24dp/12dp). */
internal data class AngleOverlayTouchMetrics(
    val handleRadiusPx: Double,
    val markerRadiusPx: Double,
    val lineTolerancePx: Double,
) {
    init {
        require(handleRadiusPx.isFinite() && handleRadiusPx > 0.0)
        require(markerRadiusPx.isFinite() && markerRadiusPx > 0.0)
        require(lineTolerancePx.isFinite() && lineTolerancePx >= 0.0)
    }
}

/** Destino resuelto de un gesto; [Ignored] cubre letterbox y puntos fuera del viewport. */
internal sealed interface AngleOverlayTarget {
    /** Asa activa arrastrable: solo marcas del borrador (`angleId == null`) o del ángulo seleccionado. */
    data class Handle(
        val angleId: AngleId?,
        val marker: AngleMarker,
        val videoPoint: AnglePoint,
    ) : AngleOverlayTarget

    /** Cuerpo de un ángulo visible: el gesto pide seleccionarlo. */
    data class AngleBody(val angleId: AngleId) : AngleOverlayTarget

    /** Zona de vídeo sin geometría: colocación de borrador o limpieza de selección. */
    data object Empty : AngleOverlayTarget

    /** Fuera del contenido visible (barras de letterbox o fuera del viewport): no se consume. */
    data object Ignored : AngleOverlayTarget
}

/**
 * Modo explícito de interacción de la capa.
 *
 * El modo no se deduce de qué callbacks aporta el llamante: con banderas y callbacks opcionales era
 * ambiguo quién poseía el toque. Cada modo tiene un dueño único del gesto.
 */
enum class AngleOverlayMode {
    /** Edición: el arrastre mueve asas activas y el toque selecciona; nunca coloca puntos. */
    EDIT,

    /** Colocación: cualquier toque dentro del contenido coloca el siguiente punto; no arrastra. */
    PLACEMENT,

    /** Encuadre: no se instala `pointerInput`; los gestos de zoom/pan son del vídeo subyacente. */
    FRAMING,
}

/** Decisión pura de un toque (sin arrastre) según [AngleOverlayMode]. */
internal sealed interface AngleOverlayTap {

    /** Colocación solicitada: [videoPoint] ya está en coordenadas de imagen. */
    data class PlacePoint(val videoPoint: AnglePoint) : AngleOverlayTap

    /** Selección solicitada; `null` limpia la selección. */
    data class Select(val angleId: AngleId?) : AngleOverlayTap

    /** El toque no produce ninguna acción en este modo. */
    data object None : AngleOverlayTap
}

/**
 * `true` solo cuando la capa debe instalar `pointerInput`.
 *
 * En ENCUADRAR ([AngleOverlayMode.FRAMING]) no se instala ningún detector: la capa no consume gestos
 * en absoluto. Tampoco se instalan cuando [showsGeometry] es `false` (el frame renderizado no es el
 * ancla): sin geometría no hay nada que arrastrar ni seleccionar y el vídeo debe recibir el gesto.
 */
internal fun shouldInstallAngleGestures(
    enabled: Boolean,
    mode: AngleOverlayMode,
    showsGeometry: Boolean,
): Boolean = enabled && showsGeometry && mode != AngleOverlayMode.FRAMING

/** Preview provisional de un arrastre diferido: estado derivado y su base de gesto exacta. */
internal data class AngleOverlayPreview(
    val base: AngleEditorState,
    val state: AngleEditorState,
    val point: AnglePoint,
    val handle: AngleOverlayTarget.Handle,
)

/**
 * Deriva el estado de preview de un arrastre **sin** tocar el dominio.
 *
 * Usa exclusivamente la API pura existente ([AngleEditorState.moveMarker] o
 * [AngleEditorState.moveDraftMarker]) con la confirmación real del frame base, de modo que el
 * preview respeta exactamente las mismas reglas de geometría (una terna que dejaría de ser medible
 * devuelve `null` y no se publica) y no inventa ángulos, marcas ni aritmética nueva.
 */
internal fun angleOverlayPreviewState(
    base: AngleEditorState,
    handle: AngleOverlayTarget.Handle,
    point: AnglePoint,
    confirmation: AngleFrameConfirmation,
): AngleEditorState? {
    val outcome = if (handle.angleId == null) {
        base.moveDraftMarker(handle.marker, point, confirmation)
    } else {
        base.moveMarker(handle.angleId, handle.marker, point, confirmation)
    }
    return (outcome as? AngleEditorOutcome.Applied)?.state
}

/**
 * Resuelve la acción de un toque sin arrastre. Es la única fuente de verdad del tap, compartida por
 * la capa Compose y los tests JVM.
 *
 * - [AngleOverlayMode.PLACEMENT]: cualquier toque dentro del contenido coloca el siguiente punto
 *   ([AngleOverlayTap.PlacePoint]), **aunque caiga sobre un asa activa, sobre el cuerpo de otro
 *   ángulo o a menos de 24dp de ambos**. En colocación el dato es el punto: el *hit test* de asas no
 *   puede robar el toque. Fuera del contenido no hay acción.
 * - [AngleOverlayMode.EDIT]: el toque no coloca puntos; selecciona el cuerpo tocado
 *   ([AngleOverlayTap.Select]) o limpia la selección en zona vacía. Sobre un asa activa no hace nada
 *   para no mover ni deseleccionar por accidente al intentar arrastrar.
 * - [AngleOverlayMode.FRAMING]: no hay gestos instalados, así que no hay acción.
 *
 * El letterbox (fuera de [VideoCoordinateMapper.contentRect]) nunca produce acción.
 */
internal fun resolveAngleOverlayTap(
    downView: ImagePoint,
    mapper: VideoCoordinateMapper,
    layout: AngleOverlayLayout,
    metrics: AngleOverlayTouchMetrics,
    mode: AngleOverlayMode,
): AngleOverlayTap {
    if (mode == AngleOverlayMode.FRAMING) return AngleOverlayTap.None
    if (!mapper.contentRect.contains(downView)) return AngleOverlayTap.None
    if (mode == AngleOverlayMode.PLACEMENT) {
        val video = mapper.viewToVideo(downView) ?: return AngleOverlayTap.None
        return AngleOverlayTap.PlacePoint(AnglePoint(video.x, video.y))
    }
    return when (val target = resolveAngleOverlayTarget(downView, mapper, layout, metrics)) {
        is AngleOverlayTarget.AngleBody -> AngleOverlayTap.Select(target.angleId)
        AngleOverlayTarget.Empty -> AngleOverlayTap.Select(null)
        is AngleOverlayTarget.Handle, AngleOverlayTarget.Ignored -> AngleOverlayTap.None
    }
}

/**
 * Resuelve qué elemento recibe un toque/arrastre.
 *
 * Orden explícito:
 * 1. fuera del rectángulo de contenido → [AngleOverlayTarget.Ignored];
 * 2. asas activas (borrador + ángulo seleccionado) dentro de [AngleOverlayTouchMetrics.handleRadiusPx]:
 *    gana la más cercana, con desempate por prioridad borrador &lt; A &lt; B &lt; C;
 * 3. cuerpo de un ángulo visible (marcador dentro de `markerRadiusPx` o segmento dentro de
 *    `lineTolerancePx`): gana el más cercano y, en empate exacto, el que se dibuja encima según
 *    [angleOverlayDrawOrder] (el seleccionado y, si no, el de creación más reciente), para que el
 *    *hit test* coincida con lo que se ve en una superposición;
 * 4. si no hay nada, [AngleOverlayTarget.Empty].
 *
 * Un ángulo no seleccionado nunca produce [AngleOverlayTarget.Handle]: primero se selecciona con un
 * toque y solo después se arrastra.
 */
internal fun resolveAngleOverlayTarget(
    downView: ImagePoint,
    mapper: VideoCoordinateMapper,
    layout: AngleOverlayLayout,
    metrics: AngleOverlayTouchMetrics,
): AngleOverlayTarget {
    if (!mapper.contentRect.contains(downView)) return AngleOverlayTarget.Ignored

    val handleCandidates = mutableListOf<HandleCandidate>()
    layout.draftPoints.forEachIndexed { index, draft ->
        val distance = hypot(downView.x - draft.viewPoint.x, downView.y - draft.viewPoint.y)
        if (distance <= metrics.handleRadiusPx) {
            handleCandidates += HandleCandidate(
                distance = distance,
                priority = index,
                target = AngleOverlayTarget.Handle(
                    angleId = null,
                    marker = draft.marker,
                    videoPoint = draft.videoPoint,
                ),
            )
        }
    }
    layout.entries.filter { it.selected }.forEach { entry ->
        entry.markerViews().forEachIndexed { markerIndex, markerView ->
            val distance = hypot(downView.x - markerView.point.x, downView.y - markerView.point.y)
            if (distance <= metrics.handleRadiusPx) {
                handleCandidates += HandleCandidate(
                    distance = distance,
                    priority = SELECTED_MARKER_PRIORITY + markerIndex,
                    target = AngleOverlayTarget.Handle(
                        angleId = entry.angleId,
                        marker = markerView.marker,
                        videoPoint = entry.videoPointFor(markerView.marker),
                    ),
                )
            }
        }
    }
    handleCandidates.minWithOrNull(compareBy({ it.distance }, { it.priority }))?.let {
        return it.target
    }

    val bodyCandidates = mutableListOf<BodyCandidate>()
    val topmostRank = angleOverlayDrawOrder(layout.entries)
        .withIndex()
        .associate { (rank, entry) -> entry.angleId to rank }
    layout.entries.forEach { entry ->
        val markerDistance = entry.markerViews().minOf { markerView ->
            hypot(downView.x - markerView.point.x, downView.y - markerView.point.y)
        }
        val lineDistance = min(
            distanceToSegment(downView, entry.a, entry.b),
            distanceToSegment(downView, entry.b, entry.c),
        )
        val distance = when {
            markerDistance <= metrics.markerRadiusPx -> markerDistance
            lineDistance <= metrics.lineTolerancePx -> lineDistance
            else -> return@forEach
        }
        bodyCandidates += BodyCandidate(
            distance = distance,
            topmostRank = topmostRank.getValue(entry.angleId),
            angleId = entry.angleId,
        )
    }
    return bodyCandidates.minWithOrNull(compareBy({ it.distance }, { -it.topmostRank }))
        ?.let { AngleOverlayTarget.AngleBody(it.angleId) }
        ?: AngleOverlayTarget.Empty
}

/**
 * Coordenadas de imagen del punto que debe ocupar un asa arrastrada, o `null` si el puntero está
 * fuera de la imagen visible (letterbox/fuera del viewport) y el arrastre debe congelarse.
 *
 * [grabOffsetVideo] compensa que el usuario agarre el asa descentrada dentro de su área táctil de
 * 48dp. El resultado se acota al frame para que un gesto nunca produzca coordenadas fuera de la
 * imagen, ni siquiera al arrastrar contra un borde.
 */
internal fun markerDragTarget(
    mapper: VideoCoordinateMapper,
    pointerView: ImagePoint,
    grabOffsetVideo: AnglePoint,
): AnglePoint? {
    if (!mapper.contentRect.contains(pointerView)) return null
    val pointerVideo = mapper.viewToVideo(pointerView) ?: return null
    return AnglePoint(
        x = (pointerVideo.x + grabOffsetVideo.x).coerceIn(0.0, (mapper.video.width - 1).toDouble()),
        y = (pointerVideo.y + grabOffsetVideo.y).coerceIn(0.0, (mapper.video.height - 1).toDouble()),
    )
}

/** Distancia euclídea mínima de [point] al segmento [start]-[end]. */
internal fun distanceToSegment(point: ImagePoint, start: ImagePoint, end: ImagePoint): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0.0) return hypot(point.x - start.x, point.y - start.y)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
}

/** Arco interior del vértice B en coordenadas de pantalla. */
internal data class AngleArcSpec(
    /** Ángulo inicial en grados, normalizado a [0, 360): dirección de BA. */
    val startAngleDegrees: Double,
    /** Barrido con signo en (-180, 180]; su valor absoluto es el ángulo interior. */
    val sweepAngleDegrees: Double,
    /** Dirección (grados normalizados) de la bisectriz interior, para colocar la etiqueta. */
    val labelAngleDegrees: Double,
    /** Radio del arco, acotado por el lado más corto y por los límites de presentación. */
    val radiusPx: Double,
)

/**
 * Calcula el arco interior del ángulo A-B-C en coordenadas de pantalla.
 *
 * Usa la diferencia angular más corta, así que `|sweepAngleDegrees| <= 180` siempre: el arco dibuja
 * el interior y no el complemento reflejo. En el caso llano (180°) el signo es convencional pero la
 * magnitud sigue siendo 180°. Devuelve `null` si algún lado mide cero o si los límites de radio son
 * inválidos (entradas que la capa pura no puede dibujar; [Angle] ya garantiza una terna medible).
 */
internal fun interiorArcSpec(
    sideAView: ImagePoint,
    vertexView: ImagePoint,
    sideCView: ImagePoint,
    minRadiusPx: Double,
    maxRadiusPx: Double,
    radiusFraction: Double = ARC_RADIUS_FRACTION,
): AngleArcSpec? {
    if (!minRadiusPx.isFinite() || !maxRadiusPx.isFinite() || !radiusFraction.isFinite()) return null
    if (minRadiusPx < 0.0 || maxRadiusPx < 0.0 || maxRadiusPx < minRadiusPx || radiusFraction <= 0.0) return null
    val lengthA = hypot(sideAView.x - vertexView.x, sideAView.y - vertexView.y)
    val lengthC = hypot(sideCView.x - vertexView.x, sideCView.y - vertexView.y)
    if (lengthA == 0.0 || lengthC == 0.0) return null

    val shortest = min(lengthA, lengthC)
    val radius = min(shortest * radiusFraction, maxRadiusPx)
        .coerceAtLeast(min(minRadiusPx, shortest))

    val start = normalizeDegrees(Math.toDegrees(atan2(sideAView.y - vertexView.y, sideAView.x - vertexView.x)))
    val end = normalizeDegrees(Math.toDegrees(atan2(sideCView.y - vertexView.y, sideCView.x - vertexView.x)))
    val sweep = normalizeSignedSweep(end - start)
    return AngleArcSpec(
        startAngleDegrees = start,
        sweepAngleDegrees = sweep,
        labelAngleDegrees = normalizeDegrees(start + sweep / 2.0),
        radiusPx = radius,
    )
}

/**
 * Mantiene la etiqueta dentro del rectángulo de contenido para que no se dibuje sobre las barras
 * de letterbox. Si la etiqueta es mayor que el rectángulo, se ancla al borde superior/izquierdo.
 */
internal fun clampLabelTopLeft(
    topLeft: ImagePoint,
    labelWidth: Double,
    labelHeight: Double,
    contentRect: ContentRect,
): ImagePoint {
    val maxX = max(contentRect.left, contentRect.right - labelWidth)
    val maxY = max(contentRect.top, contentRect.bottom - labelHeight)
    return ImagePoint(
        x = topLeft.x.coerceIn(contentRect.left, maxX),
        y = topLeft.y.coerceIn(contentRect.top, maxY),
    )
}

// ---------------------------------------------------------------------------------------------
// Detalle interno de dibujo
// ---------------------------------------------------------------------------------------------

// Prioridad en empates exactos: los puntos del borrador (0..1) van antes que las marcas del
// ángulo seleccionado (2=A, 3=B, 4=C). La distancia euclídea siempre domina este desempate.
private const val SELECTED_MARKER_PRIORITY = 2

private data class HandleCandidate(
    val distance: Double,
    val priority: Int,
    val target: AngleOverlayTarget.Handle,
)

private data class BodyCandidate(
    val distance: Double,
    /** Posición en el orden de dibujo: mayor = más arriba, gana el empate exacto. */
    val topmostRank: Int,
    val angleId: AngleId,
)

private data class MarkerView(val marker: AngleMarker, val point: ImagePoint)

private fun AngleOverlayEntry.markerViews(): List<MarkerView> = listOf(
    MarkerView(AngleMarker.A, a),
    MarkerView(AngleMarker.B, b),
    MarkerView(AngleMarker.C, c),
)

private fun AngleOverlayEntry.videoPointFor(marker: AngleMarker): AnglePoint = when (marker) {
    AngleMarker.A -> angle.markers.a
    AngleMarker.B -> angle.markers.b
    AngleMarker.C -> angle.markers.c
}

private fun AnglePoint.toImagePoint(): ImagePoint = ImagePoint(x, y)

private fun Offset.toImagePoint(): ImagePoint = ImagePoint(x.toDouble(), y.toDouble())

private fun ImagePoint.toOffset(): Offset = Offset(x.toFloat(), y.toFloat())

private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

private fun normalizeSignedSweep(value: Double): Double {
    val wrapped = normalizeDegrees(value)
    return if (wrapped > 180.0) wrapped - 360.0 else wrapped
}

/**
 * Dibuja un ángulo visible completo (segmentos A-B y B-C, arco interior, marcas y etiqueta).
 *
 * Es la **única** implementación del dibujo de un ángulo: la usa la capa Compose del editor y la
 * anotación fuera de pantalla ([AngleAnnotationRenderer]), y no debe duplicarse en otra capa. El
 * llamante aporta la proyección ([AngleOverlayEntry]), el rectángulo de contenido y el formato de
 * la etiqueta; `editingAffordances` decide si se dibujan los anillos de afordancia de las asas de
 * edición (controles), que la anotación guardada excluye.
 */
internal fun DrawScope.drawAngleEntry(
    entry: AngleOverlayEntry,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    markerStyle: TextStyle,
    formatLabel: (Angle, Double) -> String,
    markerLabel: (AngleMarker) -> String,
    contentRect: ContentRect,
    editingAffordances: Boolean,
    alpha: Float,
    haloWidth: Float,
) {
    val a = entry.a.toOffset()
    val b = entry.b.toOffset()
    val c = entry.c.toOffset()
    val color = Color(entry.angle.colorArgb)
    val baseWidth = if (entry.selected) {
        BODY_SELECTED_STROKE_DP.dp.toPx()
    } else {
        BODY_BASE_STROKE_DP.dp.toPx()
    }
    listOf(a to b, b to c).forEach { (start, end) ->
        drawLine(HaloColor, start, end, strokeWidth = baseWidth + haloWidth * 2f, cap = StrokeCap.Round, alpha = alpha)
        drawLine(color, start, end, strokeWidth = baseWidth, cap = StrokeCap.Round, alpha = alpha)
    }

    val arc = interiorArcSpec(
        sideAView = entry.a,
        vertexView = entry.b,
        sideCView = entry.c,
        minRadiusPx = ARC_MIN_RADIUS_DP.dp.toPx().toDouble(),
        maxRadiusPx = ARC_MAX_RADIUS_DP.dp.toPx().toDouble(),
    )
    if (arc != null) {
        val topLeft = Offset(
            (b.x - arc.radiusPx).toFloat(),
            (b.y - arc.radiusPx).toFloat(),
        )
        val arcSize = Size((arc.radiusPx * 2.0).toFloat(), (arc.radiusPx * 2.0).toFloat())
        val arcWidth = if (entry.selected) {
            ARC_SELECTED_STROKE_DP.dp.toPx()
        } else {
            ARC_BASE_STROKE_DP.dp.toPx()
        }
        drawArc(
            color = HaloColor,
            startAngle = arc.startAngleDegrees.toFloat(),
            sweepAngle = arc.sweepAngleDegrees.toFloat(),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = arcWidth + haloWidth * 2f),
            alpha = alpha,
        )
        drawArc(
            color = color,
            startAngle = arc.startAngleDegrees.toFloat(),
            sweepAngle = arc.sweepAngleDegrees.toFloat(),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = arcWidth),
            alpha = alpha,
        )
    }

    entry.markerViews().forEach { markerView ->
        drawMarker(
            center = markerView.point.toOffset(),
            marker = markerView.marker,
            color = color,
            selected = entry.selected,
            editingAffordances = editingAffordances,
            alpha = alpha,
        )
    }
    if (entry.selected) {
        entry.markerViews().forEach { markerView ->
            val text = markerLabel(markerView.marker)
            if (text.isNotBlank()) {
                drawMarkerGlyph(
                    center = markerView.point.toOffset(),
                    text = text,
                    textMeasurer = textMeasurer,
                    style = markerStyle,
                    alpha = alpha,
                )
            }
        }
    }

    val label = formatLabel(entry.angle, entry.degrees)
    if (label.isNotBlank()) {
        val labelCenter = if (arc != null) {
            val direction = Math.toRadians(arc.labelAngleDegrees)
            val distance = arc.radiusPx * LABEL_ARC_DISTANCE_FACTOR
            Offset(
                b.x + (cos(direction) * distance).toFloat(),
                b.y + (sin(direction) * distance).toFloat(),
            )
        } else {
            Offset(b.x, b.y - 28.dp.toPx())
        }
        drawLabel(
            center = labelCenter,
            text = label,
            selected = entry.selected,
            textMeasurer = textMeasurer,
            style = labelStyle,
            contentRect = contentRect,
            alpha = alpha,
        )
    }
}

private fun DrawScope.drawMarker(
    center: Offset,
    marker: AngleMarker,
    color: Color,
    selected: Boolean,
    editingAffordances: Boolean,
    alpha: Float,
) {
    if (selected && editingAffordances) {
        drawCircle(
            color = OutlineColor,
            radius = HANDLE_AFFORDANCE_RADIUS_DP.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
            alpha = alpha * HANDLE_AFFORDANCE_ALPHA,
        )
    }
    val radius = if (selected) MARKER_SELECTED_RADIUS_DP.dp.toPx() else MARKER_BASE_RADIUS_DP.dp.toPx()
    if (markerShapeFor(marker) == AngleMarkerShape.DIAMOND) {
        val half = radius
        rotate(degrees = 45f, pivot = center) {
            drawRect(
                color = HaloColor,
                topLeft = Offset(center.x - half - 1.5.dp.toPx(), center.y - half - 1.5.dp.toPx()),
                size = Size((half + 1.5.dp.toPx()) * 2f, (half + 1.5.dp.toPx()) * 2f),
                alpha = alpha,
            )
            drawRect(
                color = color,
                topLeft = Offset(center.x - half, center.y - half),
                size = Size(half * 2f, half * 2f),
                alpha = alpha,
            )
            drawRect(
                color = OutlineColor,
                topLeft = Offset(center.x - half, center.y - half),
                size = Size(half * 2f, half * 2f),
                style = Stroke(width = 1.dp.toPx()),
                alpha = alpha,
            )
        }
    } else {
        drawCircle(HaloColor, radius + 1.5.dp.toPx(), center, alpha = alpha)
        drawCircle(color, radius, center, alpha = alpha)
        drawCircle(OutlineColor, radius, center, style = Stroke(width = 1.dp.toPx()), alpha = alpha)
    }
    if (selected) {
        drawCircle(
            color = HaloColor,
            radius = SELECTION_RING_RADIUS_DP.dp.toPx(),
            center = center,
            style = Stroke(width = 3.dp.toPx()),
            alpha = alpha,
        )
        drawCircle(
            color = OutlineColor,
            radius = SELECTION_RING_RADIUS_DP.dp.toPx(),
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
            alpha = alpha,
        )
    }
}

private fun DrawScope.drawMarkerGlyph(
    center: Offset,
    text: String,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    alpha: Float,
) {
    val layout = textMeasurer.measure(AnnotatedString(text), style)
    val padding = 3.dp.toPx()
    val width = layout.size.width + padding * 2f
    val height = layout.size.height + padding
    val topLeft = Offset(
        center.x + SELECTION_RING_RADIUS_DP.dp.toPx() + 2.dp.toPx(),
        center.y - height - 2.dp.toPx(),
    )
    drawRoundRect(
        color = LabelPlateColor,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        alpha = alpha,
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(topLeft.x + padding, topLeft.y + padding / 2f),
        alpha = alpha,
    )
}

private fun DrawScope.drawLabel(
    center: Offset,
    text: String,
    selected: Boolean,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    contentRect: ContentRect,
    alpha: Float,
) {
    val layout = textMeasurer.measure(AnnotatedString(text), style)
    val padH = LABEL_PADDING_HORIZONTAL_DP.dp.toPx()
    val padV = LABEL_PADDING_VERTICAL_DP.dp.toPx()
    val width = layout.size.width + padH * 2f
    val height = layout.size.height + padV * 2f
    val clamped = clampLabelTopLeft(
        topLeft = ImagePoint(
            x = (center.x - width / 2f).toDouble(),
            y = (center.y - height / 2f).toDouble(),
        ),
        labelWidth = width.toDouble(),
        labelHeight = height.toDouble(),
        contentRect = contentRect,
    )
    val topLeft = Offset(clamped.x.toFloat(), clamped.y.toFloat())
    drawRoundRect(
        color = LabelPlateColor,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = CornerRadius(LABEL_CORNER_DP.dp.toPx(), LABEL_CORNER_DP.dp.toPx()),
        alpha = alpha,
    )
    drawRoundRect(
        color = if (selected) OutlineColor else Color.White.copy(alpha = 0.35f),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = CornerRadius(LABEL_CORNER_DP.dp.toPx(), LABEL_CORNER_DP.dp.toPx()),
        style = Stroke(width = if (selected) 1.5.dp.toPx() else 0.75.dp.toPx()),
        alpha = alpha,
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(topLeft.x + padH, topLeft.y + padV),
        alpha = alpha,
    )
}

private fun DrawScope.drawDraft(
    draftPoints: List<AngleOverlayDraftPoint>,
    textMeasurer: TextMeasurer,
    markerStyle: TextStyle,
    markerLabel: (AngleMarker) -> String,
    editingAffordances: Boolean,
    alpha: Float,
    haloWidth: Float,
) {
    if (draftPoints.isEmpty()) return
    val dash = PathEffect.dashPathEffect(
        floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
    )
    draftPoints.zipWithNext { start, end ->
        drawLine(
            color = HaloColor,
            start = start.viewPoint.toOffset(),
            end = end.viewPoint.toOffset(),
            strokeWidth = haloWidth + haloWidth * 2f,
            cap = StrokeCap.Round,
            pathEffect = dash,
            alpha = alpha,
        )
        drawLine(
            color = DraftColor,
            start = start.viewPoint.toOffset(),
            end = end.viewPoint.toOffset(),
            strokeWidth = haloWidth,
            cap = StrokeCap.Round,
            pathEffect = dash,
            alpha = alpha,
        )
    }
    draftPoints.forEach { draftPoint ->
        val center = draftPoint.viewPoint.toOffset()
        if (editingAffordances) {
            drawCircle(
                color = OutlineColor,
                radius = HANDLE_AFFORDANCE_RADIUS_DP.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx()),
                alpha = alpha * HANDLE_AFFORDANCE_ALPHA,
            )
        }
        val radius = DRAFT_MARKER_RADIUS_DP.dp.toPx()
        if (markerShapeFor(draftPoint.marker) == AngleMarkerShape.DIAMOND) {
            rotate(degrees = 45f, pivot = center) {
                drawRect(
                    color = HaloColor,
                    topLeft = Offset(center.x - radius - 1.5.dp.toPx(), center.y - radius - 1.5.dp.toPx()),
                    size = Size((radius + 1.5.dp.toPx()) * 2f, (radius + 1.5.dp.toPx()) * 2f),
                    alpha = alpha,
                )
                drawRect(
                    color = DraftColor,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = 2.dp.toPx()),
                    alpha = alpha,
                )
                drawRect(
                    color = OutlineColor,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = 1.dp.toPx()),
                    alpha = alpha,
                )
            }
        } else {
            drawCircle(HaloColor, radius + 1.5.dp.toPx(), center, alpha = alpha)
            drawCircle(DraftColor, radius, center, style = Stroke(width = 2.dp.toPx()), alpha = alpha)
            drawCircle(OutlineColor, radius, center, style = Stroke(width = 1.dp.toPx()), alpha = alpha)
        }
        val text = markerLabel(draftPoint.marker)
        if (text.isNotBlank()) {
            drawMarkerGlyph(
                center = center,
                text = text,
                textMeasurer = textMeasurer,
                style = markerStyle,
                alpha = alpha,
            )
        }
    }
}

private const val HANDLE_AFFORDANCE_ALPHA = 0.55f
private const val LABEL_ARC_DISTANCE_FACTOR = 1.55
