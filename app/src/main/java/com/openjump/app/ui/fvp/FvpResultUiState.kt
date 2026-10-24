package com.openjump.app.ui.fvp

import androidx.annotation.StringRes
import com.openjump.app.R

/**
 * Secciones laterales del resultado F–V–P (plan `.pi/plans/fvp-results-sections.md`, slice A).
 *
 * Un enum, y no `FvpResultView`: `FvpResultView` (ORIGINAL/RECALCULACION) es un selector GLOBAL
 * independiente que cambia TODOS los datos; [FvpResultSection] solo decide QUE bloque del mismo
 * modelo construido se esta mirando. Mezclarlos dejaria la seccion como si cambiara la ejecucion.
 *
 * Orden = orden de las pestanas y de las paginas del `HorizontalPager`. El indice NO es la
 * identidad: [name] es estable y no se renombra sin actualizar su consumidor de restauracion
 * ([fvpSectionFromName]); el ordinal solo ordena.
 */
enum class FvpResultSection(
    /** Rotulo visible de la pestana; nunca se recorta para forzar los cuatro en una fila. */
    @StringRes val labelRes: Int,
    /** Sufijo estable de los `testTag` de pestana y panel (no se traduce ni depende del ordinal). */
    val tagSuffix: String,
) {
    SUMMARY(R.string.fvp_section_summary, "summary"),
    CHARTS(R.string.fvp_section_charts, "charts"),
    DATA(R.string.fvp_section_data, "data"),
    QUALITY(R.string.fvp_section_quality, "quality");

    /** `testTag` de la pestana de esta seccion. */
    val tabTag: String get() = "$TAG_PREFIX-tab-$tagSuffix"

    /** `testTag` del panel de esta seccion (uno por pagina; nunca se repite en otra pagina). */
    val panelTag: String get() = "$TAG_PREFIX-panel-$tagSuffix"

    internal companion object {
        const val TAG_PREFIX = "fvp-section"
    }
}

/**
 * Pagina del pager de una seccion. Fuente UNICA de la relacion seccion <-> indice: las pestanas
 * mueven el pager con este valor y el pager pinta la seccion con [fvpSectionAt], de modo que no
 * puede haber dos ordenes que discrepen.
 */
fun fvpPageOf(section: FvpResultSection): Int = section.ordinal

/**
 * Seccion de una pagina del pager; `null` fuera de rango en vez de una seccion inventada (el pager
 * siempre pide indices validos, pero un indice corrupto no puede caer en Resumen por accidente).
 */
fun fvpSectionAt(page: Int): FvpResultSection? = FvpResultSection.entries.getOrNull(page)

/**
 * Seccion a partir de su NOMBRE estable (`SUMMARY`, `CHARTS`, ...): permite restaurar la seccion sin
 * persistir un ordinal fragil (una reordenacion futura del enum no apuntaria a otra seccion) y un
 * nombre desconocido devuelve `null` (el llamante decide, p. ej. abrir Resumen), nunca otra seccion.
 */
fun fvpSectionFromName(name: String?): FvpResultSection? =
    FvpResultSection.entries.firstOrNull { it.name == name }
