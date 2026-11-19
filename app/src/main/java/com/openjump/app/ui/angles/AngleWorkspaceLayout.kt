package com.openjump.app.ui.angles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openjump.app.ui.theme.Spacing

/**
 * Contenedor responsive **real** del workspace (S4a).
 *
 * Recibe tres ranuras ya construidas (media, tira y controles) y decide el sitio según el plan puro
 * de constraints, sin asumir que la orientación pedida haya surtido efecto:
 *
 * - fullscreen: la media ocupa todo el espacio; la salida/acciones esenciales se superponen en
 *   [overlay] (chrome superpuesto, no footer que reduzca la imagen);
 * - apaisado: media a la izquierda y controles en un panel lateral compacto y desplazable, sin
 *   apilar cuatro filas debajo del vídeo;
 * - portrait: media dominante con su mínimo real, tira (solo si hay fotogramas) y controles
 *   debajo, acotados y desplazables para que el texto grande no se coma la imagen.
 *
 * No pinta contenido placebo: si una ranura está vacía no reserva altura.
 */
@Composable
internal fun AngleWorkspaceResponsiveLayout(
    plan: AngleWorkspaceLayoutPlan,
    modifier: Modifier = Modifier,
    media: @Composable (Modifier) -> Unit,
    filmstrip: @Composable () -> Unit,
    controls: @Composable (Modifier) -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    when {
        plan.fullscreen -> Box(modifier = modifier.fillMaxSize()) {
            media(Modifier.fillMaxSize())
            overlay()
        }

        plan.controlsBesideMedia -> Row(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                media(Modifier.fillMaxWidth().weight(1f))
                // La tira sigue a la media a lo ancho del área principal; los controles pesados
                // no le roban altura al vídeo.
                filmstrip()
            }
            Column(
                modifier = Modifier
                    .widthIn(max = plan.lateralPanelMaxWidthDp.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            ) {
                controls(Modifier.fillMaxWidth())
            }
        }

        else -> Column(modifier = modifier.fillMaxSize()) {
            media(Modifier.fillMaxWidth().weight(1f))
            filmstrip()
            // Los controles NUNCA pueden consumir la etapa: su altura está acotada por el plan
            // (`controlsMaxHeightDp` deja fuera una etapa positiva) y el contenido que no cabe se
            // desplaza dentro. Con fontScale 2 o un motivo de error largo todo sigue alcanzable.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = plan.controlsMaxHeightDp.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}
