package com.openjump.app.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Borrado de análisis de ángulos en HISTORY: la fila ofrece la acción detrás de un menú secundario
 * `⋯`, la confirmación sigue siendo explícita, cancelar no borra ni quita la fila, y confirmar
 * dispara UN solo borrado (un segundo toque ya no tiene diálogo). Los saltos siguen sin ofrecer
 * borrado: sus datos y su vídeo no se tocan.
 *
 * Cada aserción de fila distingue la fuente persistida (legacy / multi-fotograma) además del id: dos
 * raíces de tablas distintas pueden compartir número y nunca deben intercambiar acciones.
 *
 * No ejecuta Room: el borrado real (raíz + CASCADE de marcas + reconciliación de grants) vive en
 * `AngleRepository.delete` y en el ViewModel, y se valida fuera de este slice.
 */
@RunWith(AndroidJUnit4::class)
class HistoryAngleDeletionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val deleteLabel get() = context.getString(R.string.result_delete_action)
    private val optionsLabel get() = context.getString(R.string.angle_history_options)
    private val confirmTitle get() = context.getString(R.string.result_delete_title)

    private fun angle(
        id: Long = 7L,
        markCount: Int = 2,
        source: AngleHistorySource = AngleHistorySource.LEGACY,
        restoreToken: Long? = null,
    ) = MeasurementHistoryItem.Angle(
        AngleAnalysisHistoryEntry(
            id = id,
            dateTime = 1_690_000_000_000L,
            athleteId = 1L,
            athleteName = "Ana",
            markCount = markCount,
            videoUri = "content://video/$id",
            source = source,
            restoreToken = restoreToken,
        ),
    )

    @Test
    fun angleRowOffersDeleteAndClickingItOpensTheConfirmation() {
        val pending = mutableStateOf<HistoryDeleteTarget?>(null)
        val item = angle()
        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(
                    item = item,
                    onClick = {},
                    onDelete = { pending.value = historyDeleteTarget(item) },
                )
            }
        }

        // Paso 1: el menú `⋯` es la única vía; su descripción accesible es la copia localizada.
        compose.onNodeWithContentDescription(optionsLabel).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(angleHistoryOptionsTag(item.entry)).assertIsEnabled().performClick()
        // Paso 2: la acción del menú sigue siendo la de borrado ya traducida.
        compose.onNodeWithTag(angleHistoryDeleteOptionTag(item.entry))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        // La fila sólo pide confirmación: entrega el objetivo con SU familia y su id.
        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 7L), pending.value)
    }

    @Test
    fun deletingAngleRowDisablesTheMenuAndOffersNoDeleteAction() {
        val item = angle(id = 8L)
        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(
                    item = item,
                    onClick = {},
                    // Segundo toque bloqueado mientras el borrado está en vuelo.
                    onDelete = {},
                    deleting = true,
                )
            }
        }

        compose.onNodeWithTag(angleHistoryOptionsTag(item.entry))
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()

        // Con el menú deshabilitado la acción no existe: no hay forma de encolar otro borrado.
        compose.onNodeWithText(deleteLabel).assertDoesNotExist()
    }

    @Test
    fun jumpRowOffersNoDelete() {
        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(
                    item = MeasurementHistoryItem.Jump(
                        com.openjump.app.data.RecentMeasurement(
                            id = 9L,
                            dateTime = 1_000L,
                            protocolId = "CMJ",
                            primaryMetricKey = "HEIGHT_CM",
                            primaryMetricValue = 42.0,
                            primaryMetricUnit = "CENTIMETER",
                            side = null,
                        ),
                    ),
                    onClick = {},
                    onDelete = null,
                )
            }
        }

        compose.onNodeWithText(deleteLabel).assertDoesNotExist()
    }

    @Test
    fun angleRowWithoutDeleteCallbackOffersNoMenu() {
        val item = angle(id = 12L)
        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(item = item, onClick = {}, onDelete = null)
            }
        }

        // Sin autoridad de borrado no hay menú que ofrecer (ni acción huérfana ni icono vacío).
        compose.onNodeWithTag(angleHistoryOptionsTag(item.entry)).assertDoesNotExist()
        compose.onNodeWithContentDescription(optionsLabel).assertDoesNotExist()
        compose.onNodeWithText(deleteLabel).assertDoesNotExist()
    }

    @Test
    fun openingTheMenuAndItsActionNeverNavigatesTheRow() {
        val navigations = AtomicInteger(0)
        val item = angle(id = 13L)
        compose.setContent {
            OpenJumpTheme {
                HistoryEntryRow(
                    item = item,
                    onClick = { navigations.incrementAndGet() },
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithTag(angleHistoryOptionsTag(item.entry)).performClick()
        compose.onNodeWithTag(angleHistoryDeleteOptionTag(item.entry)).performClick()

        // La fila sólo navega al pulsarla: ni el menú ni su acción abren el detalle.
        assertEquals(0, navigations.get())
    }

    @Test
    fun sameIdLegacyAndMultiFrameRowsKeepTheirOwnTargets() {
        val legacy = angle(id = 21L, source = AngleHistorySource.LEGACY)
        val multiFrame = angle(id = 21L, markCount = 3, source = AngleHistorySource.MULTI_FRAME, restoreToken = 5L)
        val targets = mutableListOf<HistoryDeleteTarget>()
        compose.setContent {
            OpenJumpTheme {
                Column {
                    AngleHistoryEntryRow(
                        entry = legacy.entry,
                        onClick = {},
                        onDelete = { historyDeleteTarget(legacy)?.let(targets::add) },
                    )
                    AngleHistoryEntryRow(
                        entry = multiFrame.entry,
                        onClick = {},
                        onDelete = { historyDeleteTarget(multiFrame)?.let(targets::add) },
                    )
                }
            }
        }

        // Mismo número, tablas distintas: etiquetas y acciones propias, nunca intercambiadas.
        compose.onNodeWithTag(angleHistoryRowTag(legacy.entry)).assertIsDisplayed()
        compose.onNodeWithTag(angleHistoryRowTag(multiFrame.entry)).assertIsDisplayed()
        compose.onNodeWithTag(angleHistoryOptionsTag(legacy.entry)).performClick()
        compose.onNodeWithTag(angleHistoryDeleteOptionTag(legacy.entry)).performClick()
        compose.onNodeWithTag(angleHistoryOptionsTag(multiFrame.entry)).performClick()
        compose.onNodeWithTag(angleHistoryDeleteOptionTag(multiFrame.entry)).performClick()

        assertEquals(
            listOf(
                HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 21L),
                HistoryDeleteTarget(HistoryDeleteFamily.ANGLE_V2, 21L),
            ),
            targets,
        )
    }

    @Test
    fun confirmDeletesTheAngleOnceAndClosesTheConfirmation() {
        val deleted = mutableListOf<HistoryDeleteTarget>()
        val cancelled = AtomicInteger(0)
        val pending = mutableStateOf<HistoryDeleteTarget?>(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 15L))
        compose.setContent {
            OpenJumpTheme {
                HistoryDeleteDialogs(
                    pending = pending.value,
                    failed = null,
                    onConfirm = { target ->
                        deleted += target
                        // Producción limpia la confirmación pendiente antes de borrar: no queda
                        // diálogo al que dar un segundo toque.
                        pending.value = null
                    },
                    onCancelPending = { cancelled.incrementAndGet() },
                    onRetry = {},
                    onDismissFailure = {},
                )
            }
        }

        compose.onNodeWithText(confirmTitle).assertIsDisplayed()
        compose.onNodeWithText(deleteLabel).assertIsDisplayed().performClick()

        assertEquals(listOf(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 15L)), deleted)
        assertEquals(0, cancelled.get())
        compose.onNodeWithText(deleteLabel).assertDoesNotExist()
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
    }

    @Test
    fun cancelDeletesNothingAndKeepsTheAngleRowListed() {
        val deleted = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        val pending = mutableStateOf<HistoryDeleteTarget?>(null)
        val item = angle(id = 31L, markCount = 2)
        val markCountLabel = context.resources.getQuantityString(R.plurals.history_angle_count, 2, 2)
        compose.setContent {
            OpenJumpTheme {
                Column {
                    HistoryEntryRow(
                        item = item,
                        onClick = {},
                        onDelete = { pending.value = historyDeleteTarget(item) },
                    )
                    HistoryDeleteDialogs(
                        pending = pending.value,
                        failed = null,
                        onConfirm = {
                            deleted.incrementAndGet()
                            pending.value = null
                        },
                        onCancelPending = {
                            cancelled.incrementAndGet()
                            pending.value = null
                        },
                        onRetry = {},
                        onDismissFailure = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(angleHistoryOptionsTag(item.entry)).performClick()
        compose.onNodeWithTag(angleHistoryDeleteOptionTag(item.entry)).performClick()
        compose.onNodeWithText(confirmTitle).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_cancel)).performClick()

        assertEquals(1, cancelled.get())
        assertEquals(0, deleted.get())
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        // La fila (con su fecha y su recuento) sigue en la lista: cancelar no borra ni filtra.
        compose.onNodeWithTag(angleHistoryRowTag(item.entry)).assertIsDisplayed()
        compose.onNodeWithText(markCountLabel).assertIsDisplayed()
        // Cancelar cierra el menú: se vuelve a abrir para comprobar que la acción sigue disponible.
        compose.onNodeWithTag(angleHistoryDeleteOptionTag(item.entry)).assertDoesNotExist()
        compose.onNodeWithTag(angleHistoryOptionsTag(item.entry)).performClick()
        compose.onNodeWithTag(angleHistoryDeleteOptionTag(item.entry)).assertIsDisplayed()
    }

    @Test
    fun failureKeepsTheAngleTargetForAnExplicitRetry() {
        val retried = mutableListOf<HistoryDeleteTarget>()
        val failedTarget = HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 41L)
        compose.setContent {
            OpenJumpTheme {
                HistoryDeleteDialogs(
                    pending = null,
                    failed = failedTarget,
                    onConfirm = {},
                    onCancelPending = {},
                    onRetry = { target -> retried += target },
                    onDismissFailure = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.result_delete_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertIsDisplayed().performClick()

        assertEquals(listOf(failedTarget), retried)
    }
}
