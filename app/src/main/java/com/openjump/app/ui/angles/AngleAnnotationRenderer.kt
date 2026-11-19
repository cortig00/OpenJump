package com.openjump.app.ui.angles

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.ContentRect
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.selector.ViewportSize
import com.openjump.app.video.angles.FrameFreezeBudget
import com.openjump.app.video.angles.FrozenFrameImage
import kotlin.math.max

/**
 * Anotación **fuera de pantalla** de una imagen congelada: compone una copia de
 * [FrozenFrameImage.bitmap] con la geometría de los ángulos visibles de un [AngleEditorState], a
 * resolución completa y sin controles de edición.
 *
 * ## Prototipo aislado (slice B)
 *
 * Este componente **no está conectado** al editor, a Room, al historial ni al guardado, y no
 * pretende estarlo todavía: su contrato se valida con pruebas JVM e instrumentadas antes de
 * integrarse. No lee vídeo, no decodifica fotogramas, no hace capturas de pantalla y no persiste
 * nada. La única entrada de píxeles es el bitmap de la imagen congelada que el llamante ya tiene.
 *
 * ## Qué garantiza
 *
 * - **La imagen base no se toca.** El bitmap de [FrozenFrameImage] se usa solo como origen de una
 *   copia (`Bitmap.copy`) y nunca se modifica ni se recicla. La salida es un bitmap nuevo, del mismo
 *   tamaño y del mismo espacio de color, propiedad del llamante.
 * - **Exactamente la misma imagen que se mostró.** La anotación parte de [FrozenFrameImage], cuya
 *   procedencia acredita la identidad de contenido de la imagen congelada; no hay una segunda
 *   decodificación ni una captura de la superficie.
 * - **El mismo dibujo que el editor.** Los segmentos, el arco interior, las marcas y la etiqueta se
 *   dibujan con [drawAngleEntry], la función que usa `AngleOverlay`; no hay una segunda
 *   implementación de líneas/arcos/etiquetas que pueda divergir.
 * - **Vínculo declarado con la imagen.** El estado debe pertenecer a la **misma** fuente, índice de
 *   fotograma y PTS declarado que [FrozenFrameImage.provenance]; se comprueba antes de asignar
 *   ninguna copia y con motivo tipado, también cuando todos los ángulos están ocultos. Dimensiones
 *   iguales no acreditan identidad: dos frames distintos del mismo tamaño se rechazan. La
 *   procedencia solo declara ese vínculo (el PTS es el asignado en la solicitud, no una observación
 *   del decodificador), así que esta validación no se presenta como prueba física de los píxeles.
 * - **Nada de otra imagen, nada a medias.** Un estado sin ancla, un estado realmente vacío y un
 *   borrador A/B pendiente se rechazan con motivo tipado ([AngleAnnotationRejectionReason]); no hay
 *   una imagen final que omita en silencio puntos ya colocados ni una "preview" implícita. Una
 *   preview en vivo, si alguna vez se necesita, debe exponerse mediante una API explícita separada
 *   (hoy no existe) y no reutilizando el render final.
 * - **Controles excluidos.** No se dibujan afordancias de asas (`editingAffordances = false`) ni el
 *   borrador (los puntos parciales no forman parte de un resultado guardado).
 * - **Proyección determinista.** La geometría se proyecta con el mismo [VideoCoordinateMapper] del
 *   editor usando `FIT` y un viewport igual al tamaño de la imagen, de modo que el píxel de la
 *   imagen y el del lienzo comparten sistema de coordenadas. **No se afirma identidad visual** con
 *   lo que el usuario ve con zoom/FIT en pantalla: el grosor de trazos y el tamaño del texto los fija
 *   [AngleAnnotationMetrics], no la densidad del dispositivo, y el mapeo normaliza `width - 1` sobre
 *   `width` (diferencia de menos de un píxel en el borde lejano).
 *
 * ## Lo que decide el llamante (no este componente)
 *
 * - La **selección** resaltada es explícita ([AngleAnnotationRequest.selectedAngleId]); `null` no
 *   resalta ningún ángulo. Este componente no lee `state.selectedAngleId` por su cuenta: si el
 *   llamante quiere conservar el resaltado del editor en la imagen anotada debe pasarlo, y con él se
 *   dibujan también el trazo más grueso, el anillo doble, el borde de placa y los glifos A/B/C.
 * - La **densidad** (`dp` → píxeles) y el escalado de fuente son explícitos y deterministas
 *   ([AngleAnnotationMetrics]). No hay regla de producto que adapte el tamaño de las marcas al
 *   tamaño de la imagen.
 * - El **formato** de la etiqueta ([AngleAnnotationRenderer.render] recibe `formatLabel`): este
 *   componente no contiene textos localizados ni redondeos.
 * - El **presupuesto de memoria** de la copia anotada ([AngleAnnotationRequest.budgetBytes]). Se
 *   comprueba **antes** de asignar; si no cabe se rechaza con motivo tipado en lugar de arriesgar un
 *   `OutOfMemoryError` no controlado.
 *
 * ## Ciclo de vida
 *
 * 1. [planAngleAnnotation] (puro, sin Android) valida y proyecta; no asigna memoria.
 * 2. [AngleAnnotationRenderer.render] copia el bitmap base, dibuja sobre la copia y devuelve
 *    [AngleAnnotationImage].
 * 3. El **llamante** es dueño del resultado y debe cerrarlo ([AngleAnnotationImage.close]) cuando ya
 *    no lo necesite; el componente nunca cierra ni recicla la imagen base. Si el dibujo falla, la
 *    copia parcial se recicla y no se devuelve ninguna imagen.
 *
 * El rasterizado de un frame completo es trabajo de CPU: el llamante no debe invocarlo en el hilo
 * principal de la UI.
 */

/** Motivos tipados de rechazo de la anotación; no existe un estado "imagen aproximada". */
internal enum class AngleAnnotationRejectionReason {
    /** El bitmap de la imagen congelada ya se liberó o está reciclado. */
    IMAGE_UNAVAILABLE,

    /** El bitmap no coincide con las dimensiones que declara su procedencia, o son inválidas. */
    DIMENSIONS_INCONSISTENT,

    /**
     * El estado del editor pertenece a otra fuente que la imagen congelada.
     *
     * Que las dimensiones coincidan no demuestra nada: dos vídeos distintos pueden medir lo mismo.
     */
    SOURCE_IDENTITY_MISMATCH,

    /** El estado está anclado a otro fotograma del índice que el que declara la imagen congelada. */
    FRAME_INDEX_MISMATCH,

    /** El estado está anclado a otro PTS declarado que el que declara la imagen congelada. */
    FRAME_PTS_MISMATCH,

    /**
     * El estado tiene geometría (ángulos o borrador) pero ninguna ancla: no se puede probar a qué
     * imagen pertenecen esos puntos, así que no se anotan.
     */
    ANCHOR_MISSING,

    /**
     * El estado tiene un borrador A/B pendiente. Una imagen final no puede descartar en silencio
     * puntos que el usuario ya colocó; se rechaza con motivo tipado en lugar de omitirlos.
     */
    DRAFT_PENDING,

    /**
     * El estado no tiene ancla, ni ángulos, ni borrador: no hay nada que anotar. Se rechaza para no
     * devolver como "anotada" una copia idéntica a la imagen base.
     */
    STATE_EMPTY,

    /** Algún ángulo visible tiene marcas fuera de la imagen de la que se congelaron los píxeles. */
    GEOMETRY_INCONSISTENT,

    /** La selección pedida no es un ángulo visible del estado: no se puede resaltar lo que no se dibuja. */
    SELECTION_INCONSISTENT,

    /** La copia anotada no cabe en la envolvente o en el presupuesto explícito del llamante. */
    MEMORY_BUDGET_EXCEEDED,
}

/**
 * Resultado de la anotación: o hay una imagen nueva con la geometría compuesta, o hay un rechazo
 * tipado. Nunca hay una imagen parcialmente anotada.
 */
internal sealed interface AngleAnnotationResult {

    /** Copia anotada a resolución completa; el llamante es su dueño y debe cerrarla. */
    data class Rendered(val image: AngleAnnotationImage) : AngleAnnotationResult

    /** No se produjo ninguna imagen; [detail] es diagnóstico, no un texto de UI. */
    data class Rejected(
        val reason: AngleAnnotationRejectionReason,
        val detail: String,
    ) : AngleAnnotationResult
}

/**
 * Copia anotada de una imagen congelada. Tras [close] el acceso a [bitmap] falla en lugar de
 * devolver píxeles liberados; [width] y [height] se conservan para diagnóstico.
 */
internal class AngleAnnotationImage internal constructor(private val annotatedBitmap: Bitmap) : AutoCloseable {

    init {
        require(annotatedBitmap.width >= 1 && annotatedBitmap.height >= 1) {
            "La imagen anotada no puede estar vacía."
        }
    }

    /** Ancho en píxeles de la copia (siempre el de la imagen base). */
    val width: Int = annotatedBitmap.width

    /** Alto en píxeles de la copia (siempre el de la imagen base). */
    val height: Int = annotatedBitmap.height

    val bitmap: Bitmap
        get() {
            check(!annotatedBitmap.isRecycled) { "La imagen anotada ya se liberó." }
            return annotatedBitmap
        }

    private var released = false

    override fun close() {
        if (!released) {
            released = true
            annotatedBitmap.recycle()
        }
    }
}

/**
 * Densidad (`dp` → píxeles) y escalado de fuente que se aplican al dibujo de la anotación.
 *
 * Son explícitos a propósito: el dibujo fuera de pantalla no debe depender de la densidad del
 * dispositivo ni del zoom con el que el usuario está trabajando. Valores no finitos o no positivos
 * son un error de programación del llamante y lanzan [IllegalArgumentException] (como
 * `ViewportSize`/`AngleOverlayTouchMetrics`), no un rechazo tipado.
 */
internal data class AngleAnnotationMetrics(
    /** Píxeles por `dp` con los que se dimensionan trazos, halos, marcas y etiquetas. */
    val densityScale: Float,
    /** Escalado de fuente para el texto de etiqueta y glifos; por defecto 1 (sin accesibilidad). */
    val fontScale: Float = 1f,
) {
    init {
        require(densityScale.isFinite() && densityScale > 0f) {
            "La densidad de la anotación debe ser finita y positiva."
        }
        require(fontScale.isFinite() && fontScale > 0f) {
            "El escalado de fuente de la anotación debe ser finito y positivo."
        }
    }
}

/**
 * Identidad **declarada** de la imagen que se va a anotar, extraída de
 * [FrozenFrameImage.provenance]: fuente, índice de fotograma y PTS asignado por el índice.
 *
 * No es una prueba física de los píxeles: acredita únicamente el vínculo que la procedencia declara.
 * El PTS es el **asignado en la solicitud** ([com.openjump.app.video.angles.FrameCaptureProvenance])
 * y no una observación del decodificador. Aun así, es la evidencia disponible para impedir que un
 * estado anclado a otro fotograma se dibuje sobre esta imagen, y se exige igualdad exacta.
 */
internal data class AngleAnnotationFrameIdentity(
    val sourceIdentity: String,
    val frameIndex: Int,
    val sourcePtsUs: Long,
) {
    init {
        require(sourceIdentity.isNotBlank()) { "La identidad de fuente de la anotación no puede estar vacía." }
        require(frameIndex >= 0) { "El índice de fotograma de la anotación debe ser >= 0, era $frameIndex." }
        require(sourcePtsUs >= 0L) { "El PTS declarado de la anotación debe ser >= 0, era $sourcePtsUs." }
    }

    companion object {
        /** Identidad declarada por la procedencia de [image]. */
        fun of(image: FrozenFrameImage): AngleAnnotationFrameIdentity = AngleAnnotationFrameIdentity(
            sourceIdentity = image.provenance.sourceIdentity.value,
            frameIndex = image.provenance.frameIndex,
            sourcePtsUs = image.provenance.assignedRequestPtsUs,
        )
    }
}

/**
 * Entrada tipada de la anotación fuera de pantalla.
 *
 * @property image imagen congelada ya mostrada al usuario; **solo se lee**, nunca se modifica,
 *   redecodifica ni se recicla.
 * @property state estado del editor en el instante de la anotación. Debe pertenecer a la **misma**
 *   fuente, fotograma y PTS declarado que [image] (se comparan antes de asignar memoria); solo se
 *   dibujan los ángulos completos visibles. Un borrador A/B pendiente se rechaza con
 *   [AngleAnnotationRejectionReason.DRAFT_PENDING] y un estado sin ancla con
 *   [AngleAnnotationRejectionReason.STATE_EMPTY] o [AngleAnnotationRejectionReason.ANCHOR_MISSING]:
 *   no existe una "preview" que acepte datos de otra imagen.
 * @property selectedAngleId selección explícita que se resalta en la imagen, o `null` para no
 *   resaltar ninguna. No se deduce de [state].
 * @property metrics densidad y escalado de fuente del dibujo.
 * @property budgetBytes presupuesto máximo en bytes de la copia anotada; debe cubrir la copia
 *   ARGB_8888 más una fila de trabajo. Se comprueba antes de asignar.
 */
internal data class AngleAnnotationRequest(
    val image: FrozenFrameImage,
    val state: AngleEditorState,
    val selectedAngleId: AngleId?,
    val metrics: AngleAnnotationMetrics,
    val budgetBytes: Long,
)

/** Plan de dibujo sin Android: qué se dibuja, en qué orden y con qué tamaño/presupuesto. */
internal data class AngleAnnotationPlan(
    val width: Int,
    val height: Int,
    val metrics: AngleAnnotationMetrics,
    /** Bytes que necesita la copia anotada (ARGB_8888 de la imagen completa + una fila). */
    val requiredBytes: Long,
    /** Rectángulo de contenido: con viewport igual a la imagen es el lienzo completo. */
    val contentRect: ContentRect,
    /** Ángulos visibles ya proyectados, **en orden de dibujo** (el resaltado al final). */
    val entries: List<AngleOverlayEntry>,
)

/** Resultado del plan puro: listo para dibujar o rechazado antes de asignar memoria. */
internal sealed interface AngleAnnotationPlanResult {

    data class Ready(val plan: AngleAnnotationPlan) : AngleAnnotationPlanResult

    data class Rejected(
        val reason: AngleAnnotationRejectionReason,
        val detail: String,
    ) : AngleAnnotationPlanResult
}

/**
 * Proyección de la anotación: el mismo [VideoCoordinateMapper] del editor con `FIT` y un viewport
 * del tamaño de la imagen. Así el sistema de coordenadas del lienzo y el de la imagen congelada
 * coinciden (salvo la normalización `width - 1` → `width` del propio mapper, menor que un píxel).
 */
private fun annotationMapper(imageWidth: Int, imageHeight: Int): VideoCoordinateMapper =
    VideoCoordinateMapper(
        video = VideoPresentationGeometry(width = imageWidth, height = imageHeight),
        viewport = ViewportSize(width = imageWidth.toDouble(), height = imageHeight.toDouble()),
        scaleMode = VideoScaleMode.FIT,
    )

private fun AnglePoint.isInsideImage(imageWidth: Int, imageHeight: Int): Boolean =
    isFinite &&
        x >= 0.0 && x <= (imageWidth - 1).toDouble() &&
        y >= 0.0 && y <= (imageHeight - 1).toDouble()

/**
 * Valida la entrada y proyecta los ángulos visibles a píxeles de la imagen completa. Es puro: no
 * toca Android, no asigna bitmaps y se puede probar en JVM.
 *
 * Orden de las comprobaciones (todas se resuelven antes de asignar memoria):
 * 1. **Vínculo declarado estado ↔ imagen**: identidad de fuente, índice de fotograma y PTS. Se
 *    valida antes que cualquier otra cosa y cubre también los ángulos ocultos, porque el ancla es
 *    del estado completo. Las dimensiones iguales no sustituyen a esta comprobación.
 * 2. Contenido anotable: el estado debe estar anclado; un estado realmente vacío no se anota y un
 *    borrador pendiente se rechaza en lugar de desaparecer de la imagen final.
 * 3. Dimensiones de la imagen válidas y dentro de la envolvente presupuestable.
 * 4. El presupuesto explícito cubre la copia anotada.
 * 5. La selección pedida (si hay) es un ángulo **visible** del estado.
 * 6. Todas las marcas de los ángulos visibles caen dentro de la imagen congelada.
 *
 * Rechaza con [AngleAnnotationRejectionReason] en lugar de lanzar: una imagen con geometría
 * incongruente no se anota parcialmente. Los ángulos ocultos no se dibujan y por eso no se validan
 * sus coordenadas, pero sí caen bajo el vínculo del paso 1: ocultarlos no permite saltarse la
 * comprobación de que el estado pertenece a esta imagen.
 */
internal fun planAngleAnnotation(
    imageWidth: Int,
    imageHeight: Int,
    frameIdentity: AngleAnnotationFrameIdentity,
    state: AngleEditorState,
    selectedAngleId: AngleId?,
    metrics: AngleAnnotationMetrics,
    budgetBytes: Long,
): AngleAnnotationPlanResult {
    // (1) Vínculo declarado con la imagen. Va primero y no depende de la visibilidad ni de las
    // dimensiones: dos frames del mismo tamaño pueden ser de otra fuente, otro índice u otro PTS.
    val anchor = state.anchor
    if (state.sourceIdentity != frameIdentity.sourceIdentity) {
        return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.SOURCE_IDENTITY_MISMATCH,
            "El estado pertenece a la fuente '${state.sourceIdentity}' y la imagen declara " +
                "'${frameIdentity.sourceIdentity}'.",
        )
    }
    if (anchor == null) {
        // Sin ancla no hay nada que pueda probarse contra esta imagen. Se distingue el estado
        // realmente vacío (nada que anotar) del que tiene geometría sin ancla (no se puede probar).
        return if (state.angles.isEmpty() && state.draft.points.isEmpty()) {
            AngleAnnotationPlanResult.Rejected(
                AngleAnnotationRejectionReason.STATE_EMPTY,
                "El estado no tiene ancla, ángulos ni borrador: no hay nada que anotar.",
            )
        } else {
            AngleAnnotationPlanResult.Rejected(
                AngleAnnotationRejectionReason.ANCHOR_MISSING,
                "El estado tiene ${state.angles.size} ángulo(s) y ${state.draft.points.size} punto(s) " +
                    "de borrador sin ancla: no se puede probar a qué imagen pertenecen.",
            )
        }
    }
    if (anchor.sourceIdentity != state.sourceIdentity) {
        return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.SOURCE_IDENTITY_MISMATCH,
            "La ancla declara la fuente '${anchor.sourceIdentity}' y el estado " +
                "'${state.sourceIdentity}'.",
        )
    }
    if (anchor.frameIndex != frameIdentity.frameIndex) {
        return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.FRAME_INDEX_MISMATCH,
            "El estado está anclado al fotograma ${anchor.frameIndex} y la imagen declara " +
                "${frameIdentity.frameIndex}.",
        )
    }
    if (anchor.sourcePtsUs != frameIdentity.sourcePtsUs) {
        return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.FRAME_PTS_MISMATCH,
            "El estado está anclado al PTS ${anchor.sourcePtsUs} µs y la imagen declara " +
                "${frameIdentity.sourcePtsUs} µs.",
        )
    }

    // (2) Contenido anotable. El borrador A/B no se omite en silencio de una imagen final; integrar
    // una preview en vivo exigiría una API explícita separada, que este componente no expone.
    if (state.draft.points.isNotEmpty()) {
        return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.DRAFT_PENDING,
            "El borrador tiene ${state.draft.points.size} punto(s) A/B pendientes: una imagen final " +
                "no los descarta en silencio.",
        )
    }

    // (3) Dimensiones: la imagen debe poder presupuestarse.
    if (imageWidth < 1 || imageHeight < 1) {
        return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.DIMENSIONS_INCONSISTENT,
            "La imagen debe medir al menos 1×1 píxeles; llegó ${imageWidth}×$imageHeight.",
        )
    }
    val copyBytes = FrameFreezeBudget.argbArrayBytes(imageWidth, imageHeight)
        ?: return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.MEMORY_BUDGET_EXCEEDED,
            "Las dimensiones ${imageWidth}×$imageHeight no son presupuestables.",
        )
    // Una fila de trabajo además de la copia: mismo criterio que FrameFreezeBudget.requiredBytes.
    val requiredBytes = copyBytes + max(imageWidth, imageHeight).toLong() * 4L
    if (budgetBytes < requiredBytes) {
        return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.MEMORY_BUDGET_EXCEEDED,
            "La copia anotada necesita $requiredBytes bytes y el presupuesto es $budgetBytes.",
        )
    }
    if (selectedAngleId != null && state.angles.none { it.id == selectedAngleId && it.visible }) {
        return AngleAnnotationPlanResult.Rejected(
            AngleAnnotationRejectionReason.SELECTION_INCONSISTENT,
            "El ángulo resaltado ${selectedAngleId.value} no es un ángulo visible del estado.",
        )
    }
    state.angles.asSequence().filter { it.visible }.forEach { angle ->
        val outside = listOf(angle.markers.a, angle.markers.b, angle.markers.c).firstOrNull {
            !it.isInsideImage(imageWidth, imageHeight)
        }
        if (outside != null) {
            return AngleAnnotationPlanResult.Rejected(
                AngleAnnotationRejectionReason.GEOMETRY_INCONSISTENT,
                "El ángulo ${angle.id.value} tiene una marca (${outside.x}, ${outside.y}) fuera de " +
                    "la imagen ${imageWidth}×$imageHeight.",
            )
        }
    }

    val mapper = annotationMapper(imageWidth, imageHeight)
    // Misma proyección que la capa del editor (solo ángulos visibles), con la selección explícita
    // pedida y no la que el estado tuviera en la UI.
    val entries = angleOverlayLayout(mapper, state).entries.map { entry ->
        entry.copy(selected = entry.angleId == selectedAngleId)
    }
    return AngleAnnotationPlanResult.Ready(
        AngleAnnotationPlan(
            width = imageWidth,
            height = imageHeight,
            metrics = metrics,
            requiredBytes = requiredBytes,
            contentRect = mapper.contentRect,
            entries = angleOverlayDrawOrder(entries),
        ),
    )
}

/**
 * Rasteriza el plan sobre una copia del bitmap congelado. Es la única parte que necesita Android
 * (Canvas, fuente y medición de texto) y comparte [drawAngleEntry] con la capa del editor.
 */
internal object AngleAnnotationRenderer {

    /**
     * Compone la imagen anotada a partir de [request].
     *
     * @param context solo se usa para resolver la familia tipográfica; no se guarda ni se consulta
     *   ningún recurso de la app.
     * @param formatLabel texto de la etiqueta de grados; mismo contrato que en `AngleOverlay`
     *   (grados sin redondear, idioma y formato a cargo del llamante).
     * @param markerLabel glifo de las marcas A/B/C; por defecto su letra.
     *
     * No lee vídeo, no persiste y no retiene la imagen base más allá de la llamada. Devuelve
     * [AngleAnnotationResult.Rejected] con motivo tipado cuando la entrada o el presupuesto no son
     * válidos —incluido un estado de otra fuente, de otro fotograma u otro PTS, un estado vacío o un
     * borrador pendiente, comprobados antes de copiar el bitmap—; un fallo inesperado del dibujo
     * propaga la excepción después de reciclar la copia parcial (nunca se devuelve una imagen a
     * medias).
     */
    fun render(
        context: Context,
        request: AngleAnnotationRequest,
        formatLabel: (Angle, Double) -> String,
        markerLabel: (AngleMarker) -> String = { it.name },
    ): AngleAnnotationResult {
        val base = try {
            request.image.bitmap
        } catch (_: IllegalStateException) {
            return AngleAnnotationResult.Rejected(
                AngleAnnotationRejectionReason.IMAGE_UNAVAILABLE,
                "La imagen congelada ya se liberó.",
            )
        }
        if (base.isRecycled) {
            return AngleAnnotationResult.Rejected(
                AngleAnnotationRejectionReason.IMAGE_UNAVAILABLE,
                "El bitmap de la imagen congelada está reciclado.",
            )
        }
        if (base.width != request.image.width || base.height != request.image.height) {
            return AngleAnnotationResult.Rejected(
                AngleAnnotationRejectionReason.DIMENSIONS_INCONSISTENT,
                "El bitmap mide ${base.width}×${base.height} y su procedencia declara " +
                    "${request.image.width}×${request.image.height}.",
            )
        }
        val plan = when (
            val planned = planAngleAnnotation(
                imageWidth = base.width,
                imageHeight = base.height,
                frameIdentity = AngleAnnotationFrameIdentity.of(request.image),
                state = request.state,
                selectedAngleId = request.selectedAngleId,
                metrics = request.metrics,
                budgetBytes = request.budgetBytes,
            )
        ) {
            is AngleAnnotationPlanResult.Rejected ->
                return AngleAnnotationResult.Rejected(planned.reason, planned.detail)
            is AngleAnnotationPlanResult.Ready -> planned.plan
        }

        // Copia independiente: el bitmap base se queda como está y nunca se recicla aquí.
        val annotated = try {
            base.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return AngleAnnotationResult.Rejected(
            AngleAnnotationRejectionReason.MEMORY_BUDGET_EXCEEDED,
            "No se pudo asignar la copia anotada de ${plan.width}×${plan.height}.",
        )
        if (annotated.isRecycled || annotated.width != plan.width || annotated.height != plan.height) {
            annotated.recycle()
            return AngleAnnotationResult.Rejected(
                AngleAnnotationRejectionReason.DIMENSIONS_INCONSISTENT,
                "La copia anotada no conserva el tamaño de la imagen original.",
            )
        }

        try {
            drawPlan(context, annotated, plan, formatLabel, markerLabel)
        } catch (_: OutOfMemoryError) {
            annotated.recycle()
            return AngleAnnotationResult.Rejected(
                AngleAnnotationRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "No se pudo completar la copia anotada (falta de memoria durante el dibujo).",
            )
        } catch (error: Throwable) {
            annotated.recycle()
            throw error
        }
        return AngleAnnotationResult.Rendered(AngleAnnotationImage(annotated))
    }

    private fun drawPlan(
        context: Context,
        annotated: Bitmap,
        plan: AngleAnnotationPlan,
        formatLabel: (Angle, Double) -> String,
        markerLabel: (AngleMarker) -> String,
    ) {
        val density = Density(plan.metrics.densityScale, plan.metrics.fontScale)
        val textMeasurer = TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = density,
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(annotated.asImageBitmap()),
            size = Size(plan.width.toFloat(), plan.height.toFloat()),
        ) {
            val haloWidth = ANGLE_OVERLAY_HALO_WIDTH_DP.dp.toPx()
            plan.entries.forEach { entry ->
                drawAngleEntry(
                    entry = entry,
                    textMeasurer = textMeasurer,
                    labelStyle = AngleOverlayLabelTextStyle,
                    markerStyle = AngleOverlayMarkerTextStyle,
                    formatLabel = formatLabel,
                    markerLabel = markerLabel,
                    contentRect = plan.contentRect,
                    // Sin controles: la anotación de un resultado no es una pantalla de edición.
                    editingAffordances = false,
                    alpha = 1f,
                    haloWidth = haloWidth,
                )
            }
        }
    }
}
