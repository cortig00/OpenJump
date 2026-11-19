package com.openjump.app.ui.history

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openjump.app.R

/**
 * Diálogos del borrado V2 confirmado (familia [HistoryDeleteFamily.ANGLE_V2]) desde HISTORY.
 *
 * Se separan del diálogo legacy porque el contenido confirmado NO es «una raíz y sus marcas»: se
 * borran el análisis multi-fotograma **y las imágenes privadas anotadas de sus fotogramas**, mientras
 * el vídeo original no se toca. El copy reutiliza el título, la acción de borrado y el error ya
 * traducidos (`result_delete_*`); sólo el cuerpo y los avisos son propios
 * (`history_v2_delete_*`) porque la cuenta de fotogramas viene de la prueba preparada, no de la fila
 * antigua.
 *
 * Ninguno de estos diálogos ejecuta por sí mismo: [onConfirm] entrega la confirmación al controlador
 * (que posee la prueba exacta), [onCancel] sólo la descarta, y los avisos son informativos o piden un
 * reintento explícito con la misma prueba. `common_confirm` se usa como acuse de recibo en los avisos
 * (no hay una clave `common_close` traducida y no se añade una para no ampliar copy innecesario).
 */
@Composable
internal fun HistoryV2DeletionDialogs(
    confirmation: HistoryV2DeleteConfirmation?,
    notice: HistoryV2DeleteNotice?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissNotice: () -> Unit,
    onRetry: () -> Unit,
) {
    if (confirmation != null) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.result_delete_title)) },
            text = {
                Text(stringResource(R.string.history_v2_delete_body, confirmation.frameCount))
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        stringResource(R.string.result_delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    when (val current = notice) {
        null -> Unit

        // El borrado YA se aplicó: la fila desaparece y esto sólo dice qué quedó en disco. Sin
        // reintento de borrado ni de limpieza y sin reabrir la confirmación.
        is HistoryV2DeleteNotice.CleanupIncomplete -> AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text(stringResource(R.string.result_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.history_v2_delete_cleanup,
                        current.retained,
                        current.unconfirmed,
                        current.directories,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissNotice) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )

        // La prueba dejó de valer o la raíz ya no es la misma: hay que releer y volver a pedir el
        // borrado. Nunca se reapunta a la raíz que ocupe ese número.
        HistoryV2DeleteNotice.Reconfirm -> AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text(stringResource(R.string.result_delete_title)) },
            text = { Text(stringResource(R.string.history_v2_delete_reconfirm)) },
            confirmButton = {
                TextButton(onClick = onDismissNotice) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )

        // Fallo ANTES de confirmar: la fila sigue ahí y la misma prueba puede reintentarse.
        HistoryV2DeleteNotice.Retryable -> AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text(stringResource(R.string.result_delete_title)) },
            text = { Text(stringResource(R.string.result_delete_error)) },
            confirmButton = {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.common_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissNotice) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
