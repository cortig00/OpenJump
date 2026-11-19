package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleMarkers

/**
 * Contenido semántico **comparable** de una edición de ángulos.
 *
 * Es la proyección que decide si cerrar el editor perdería algo: nombre, color, visibilidad y las
 * coordenadas (A, B, C) de cada ángulo, más el ancla y el número de puntos del borrador. Se excluye
 * a propósito todo lo que cambia sin editar contenido:
 *
 * - los ids locales de [com.openjump.app.angles.Angle] (un ángulo recién creado y uno rehidratado
 *   con la misma geometría son el mismo contenido);
 * - [AngleEditorState.selectedAngleId] (seleccionar no es editar);
 * - [AngleEditorState.externallyPersistedAngleIds] (marcar lo ya persistido no cambia el contenido);
 * - [AngleEditorState.generation] y el frame actual del reproductor (navegar no borra nada).
 */
internal data class AngleContent(
    val name: String,
    val colorArgb: Int,
    val visible: Boolean,
    val markers: AngleMarkers,
)

/**
 * Proyección de una edición completa a [AngleContent].
 *
 * [draftPoints] cuenta los puntos parciales A/B. Un borrador nunca se persiste como marca
 * incompleta, así que **siempre** es contenido sin guardar: una edición con borrador no puede
 * considerarse limpia frente a ninguna línea base.
 */
internal data class AngleEditorContent(
    val anchor: AngleFrameAnchor? = null,
    val angles: List<AngleContent> = emptyList(),
    val draftPoints: Int = 0,
) {

    /** `true` si el editor conserva algo que no está (necesariamente) persistido. */
    val hasAnyChange: Boolean
        get() = anchor != null || angles.isNotEmpty() || draftPoints > 0
}

/**
 * Forma canónica del contenido semántico: un ancla sin ángulos ni puntos parciales **no** es
 * contenido.
 *
 * El ancla solo indica a qué frame pertenece este análisis; no se persiste por sí misma. Sin
 * ángulos ni borrador no hay nada que perder al cerrar, y ese estado se produce de verdad: tras
 * colocar A y B, [AngleEditorState.cancelDraft] vacía el borrador pero conserva el ancla a
 * propósito. Normalizar el ancla evita confundirlo con una edición sin guardar, sin dejar de
 * detectar bajas: una línea base con ángulos sigue difiriendo de un contenido que ya no los tiene
 * (la lista de ángulos pesa en la igualdad, no el ancla).
 */
internal fun AngleEditorContent.canonical(): AngleEditorContent =
    if (angles.isEmpty() && draftPoints <= 0) copy(anchor = null) else this

/** Proyección del estado vivo; `null` (sin editor) es una edición vacía. */
internal fun angleEditorContent(editor: AngleEditorState?): AngleEditorContent {
    if (editor == null) return AngleEditorContent()
    return AngleEditorContent(
        anchor = editor.anchor,
        angles = editor.angles.map { angle ->
            AngleContent(
                name = angle.name,
                colorArgb = angle.colorArgb,
                visible = angle.visible,
                markers = angle.markers,
            )
        },
        draftPoints = editor.draft.points.size,
    ).canonical()
}

/**
 * Línea base de contenido **verificado** a partir de un estado cuya persistencia ya se comprobó
 * (reapertura verificada o instantánea enviada y releída igual).
 *
 * El borrador se descarta de la línea base aunque el estado enviado lo tuviera: no se escribió, así
 * que no forma parte de lo persistido y seguirá exigiendo confirmación al cerrar.
 */
internal fun angleEditorBaselineContent(editor: AngleEditorState): AngleEditorContent =
    angleEditorContent(editor).copy(draftPoints = 0).canonical()

/** Qué se perdería al cerrar, para que el diálogo no cuente solo fotogramas. */
internal data class AngleEditorDiscardContent(
    val liveUnsavedChanges: Boolean,
    val frozenFrameCount: Int,
) {

    val hasFrozenFrames: Boolean
        get() = frozenFrameCount > 0
}

/** Decisión de cierre del editor; no navega ni escribe por sí misma. */
internal sealed interface AngleEditorCloseDecision {

    /**
     * Hay una escritura en vuelo (mono o multi-fotograma): la salida se consume sin diálogo ni
     * navegación. Cerrar a mitad de escritura dejaría el resultado sin superficie visible.
     */
    data object BlockedBySave : AngleEditorCloseDecision

    /** No se pierde nada: se puede salir sin confirmación. */
    data object Exit : AngleEditorCloseDecision

    /** Se perderían datos: hace falta confirmación explícita. */
    data class Confirm(val content: AngleEditorDiscardContent) : AngleEditorCloseDecision
}

/**
 * Política pura de cierre del editor: protege **todo** el contenido en memoria, no solo los
 * fotogramas congelados.
 *
 * - Con una escritura en vuelo no hay salida ([AngleEditorCloseDecision.BlockedBySave]).
 * - Sin línea base conocida ([baseline] `null`) cualquier contenido exige confirmar: no se puede
 *   afirmar que esté persistido.
 * - Con línea base conocida, la salida es libre solo si el contenido vivo es **idéntico** a la
 *   línea base (incluido no tener borrador). Editar, borrar o mover cualquier marca exige
 *   confirmar; seleccionar, navegar o marcar ids persistidos, no.
 * - Un ancla sin ángulos ni borrador no es contenido (se normaliza con `canonical()`): no se pierde
 *   nada al cerrar, ni siquiera sin línea base. Sí se pierde si la línea base tenía ángulos y
 *   ahora no queda ninguno.
 * - Los fotogramas congelados en memoria siempre cuentan como pérdida: no tienen escritor de
 *   persistencia propio en esta edición.
 *
 * Un guardado fallido, no verificado o en conflicto no actualiza la línea base (lo decide el
 * ViewModel), así que la protección se conserva sin que esta función tenga que saber el motivo.
 */
internal fun angleEditorCloseDecision(
    saveInFlight: Boolean,
    baseline: AngleEditorContent?,
    current: AngleEditorContent,
    frozenFrameCount: Int,
): AngleEditorCloseDecision {
    if (saveInFlight) return AngleEditorCloseDecision.BlockedBySave
    // La comparación se hace siempre en forma canónica: la política no depende de que el llamante
    // proyecte el estado con [angleEditorContent].
    val currentContent = current.canonical()
    val baselineContent = baseline?.canonical()
    val liveUnsaved = if (baselineContent == null) {
        currentContent.hasAnyChange
    } else {
        currentContent != baselineContent
    }
    val frozen = frozenFrameCount.coerceAtLeast(0)
    return if (liveUnsaved || frozen > 0) {
        AngleEditorCloseDecision.Confirm(AngleEditorDiscardContent(liveUnsaved, frozen))
    } else {
        AngleEditorCloseDecision.Exit
    }
}
