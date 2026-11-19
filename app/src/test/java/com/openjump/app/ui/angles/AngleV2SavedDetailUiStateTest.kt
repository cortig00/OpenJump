package com.openjump.app.ui.angles

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM del estado de presentación del detalle V2 (`AngleV2SavedDetailUiState`).
 *
 * Son funciones puras: no abren base, no leen imágenes, no componen nada y no dependen del reloj ni
 * del idioma. Cubren lo que la pantalla no puede permitirse: identidad estable por `frameId` (nunca
 * por posición), reconciliación con contenido nuevo, pestaña de Fotogramas condicional y la
 * selección compartida entre vistas.
 */
class AngleV2SavedDetailUiStateTest {

    @Test
    fun `initial state selects the first frame id and analysis tab`() {
        val frames = listOf(frame(frameId = 41, ordinal = 0), frame(frameId = 42, ordinal = 1))

        val state = AngleV2SavedDetailUiState.initial(frames)

        assertEquals(AngleV2SavedDetailTab.ANALYSIS, state.tab)
        assertEquals(41L, state.selectedFrameId)
        assertEquals(41L, state.selectedFrame(frames)?.frameId)
    }

    @Test
    fun `selection follows the frame id and not the list position`() {
        val first = frame(frameId = 7, ordinal = 0)
        val second = frame(frameId = 9, ordinal = 1)
        val state = AngleV2SavedDetailUiState.initial(listOf(first, second)).select(
            listOf(first, second),
            frameId = 9,
        )

        // Contenido recargado: mismos frameId en OTRO orden de lista y con otro ordinal.
        val reloaded = listOf(
            second.copy(ordinal = 0, assignedFrameIndex = 1),
            first.copy(ordinal = 1, assignedFrameIndex = 2),
        )
        val reconciled = state.reconcile(reloaded)

        assertEquals(9L, reconciled.selectedFrameId)
        assertEquals(9L, reconciled.selectedFrame(reloaded)?.frameId)
        // La selección no se quedó pegada al índice 1 ni al ordinal.
        assertEquals(second.frameId, reconciled.selectedFrame(reloaded)?.frameId)
    }

    @Test
    fun `selection survives a full tab round trip`() {
        val frames = listOf(frame(frameId = 1), frame(frameId = 2), frame(frameId = 3))
        val selected = AngleV2SavedDetailUiState.initial(frames).select(frames, frameId = 2)

        val inFrames = selected.show(AngleV2SavedDetailTab.FRAMES, frames)
        val inInfo = inFrames.show(AngleV2SavedDetailTab.INFO, frames)
        val back = inInfo.show(AngleV2SavedDetailTab.ANALYSIS, frames)

        assertEquals(AngleV2SavedDetailTab.FRAMES, inFrames.tab)
        assertEquals(2L, inFrames.selectedFrameId)
        assertEquals(AngleV2SavedDetailTab.INFO, inInfo.tab)
        assertEquals(2L, inInfo.selectedFrameId)
        assertEquals(AngleV2SavedDetailTab.ANALYSIS, back.tab)
        assertEquals(2L, back.selectedFrameId)
    }

    @Test
    fun `frames tab disappears with a single frame and falls back to analysis`() {
        val single = listOf(frame(frameId = 5))

        val tabs = AngleV2SavedDetailUiState.initial(single).tabs(single)
        val requested = AngleV2SavedDetailUiState.initial(single)
            .show(AngleV2SavedDetailTab.FRAMES, single)
        val reconciled = AngleV2SavedDetailUiState(
            tab = AngleV2SavedDetailTab.FRAMES,
            selectedFrameId = 5L,
        ).reconcile(single)

        assertEquals(
            listOf(AngleV2SavedDetailTab.ANALYSIS, AngleV2SavedDetailTab.INFO),
            tabs,
        )
        assertEquals(AngleV2SavedDetailTab.ANALYSIS, requested.tab)
        assertEquals(AngleV2SavedDetailTab.ANALYSIS, reconciled.tab)
        assertEquals(5L, reconciled.selectedFrameId)
    }

    @Test
    fun `frames tab exists with more than one frame and share all is only then available`() {
        val frames = listOf(frame(frameId = 1), frame(frameId = 2))

        assertEquals(AngleV2SavedDetailTab.entries, AngleV2SavedDetailUiState.initial(frames).tabs(frames))
        assertTrue(AngleV2SavedDetailUiState.shareAllAvailable(frames))
        assertFalse(AngleV2SavedDetailUiState.shareAllAvailable(listOf(frame(frameId = 1))))
        assertFalse(AngleV2SavedDetailUiState.hasFramesTab(emptyList()))
    }

    @Test
    fun `empty content clears the selection and keeps analysis`() {
        val state = AngleV2SavedDetailUiState(
            tab = AngleV2SavedDetailTab.FRAMES,
            selectedFrameId = 3L,
        ).reconcile(emptyList())

        assertEquals(AngleV2SavedDetailTab.ANALYSIS, state.tab)
        assertNull(state.selectedFrameId)
        assertNull(state.selectedFrame(emptyList()))
        assertEquals(emptyList<AngleV2SavedDetailTab>(), state.tabs(emptyList()).filter {
            it == AngleV2SavedDetailTab.FRAMES
        })
        // Seleccionar sobre contenido vacío no inventa selección ni rompe la pestaña.
        assertEquals(state, state.select(emptyList(), 3L))
    }

    @Test
    fun `empty content keeps the visible info tab and only clears the selection`() {
        val state = AngleV2SavedDetailUiState(
            tab = AngleV2SavedDetailTab.INFO,
            selectedFrameId = 5L,
        ).reconcile(emptyList())

        // Información no depende de los fotogramas: con contenido vacío sigue siendo legible y no
        // puede quedar una pestaña visible muerta ni saltar a Análisis por su cuenta.
        assertEquals(AngleV2SavedDetailTab.INFO, state.tab)
        assertNull(state.selectedFrameId)
        assertNull(state.selectedFrame(emptyList()))
        assertTrue(state.tabs(emptyList()).contains(AngleV2SavedDetailTab.INFO))
        assertFalse(state.tabs(emptyList()).contains(AngleV2SavedDetailTab.FRAMES))
    }

    @Test
    fun `a missing selected frame reconciles to the first available frame`() {
        val frames = listOf(frame(frameId = 11), frame(frameId = 12))
        val state = AngleV2SavedDetailUiState(
            tab = AngleV2SavedDetailTab.ANALYSIS,
            selectedFrameId = 999L,
        ).reconcile(frames)

        assertEquals(11L, state.selectedFrameId)
        assertEquals(11L, state.selectedFrame(frames)?.frameId)
    }

    @Test
    fun `selecting a frame that belongs to another content is ignored`() {
        val frames = listOf(frame(frameId = 21))
        val state = AngleV2SavedDetailUiState.initial(frames)

        assertEquals(state, state.select(frames, frameId = 22))
    }

    @Test
    fun `saver restores the tab by value and the selection by frame id`() {
        val frames = listOf(frame(frameId = 31), frame(frameId = 32))
        val state = AngleV2SavedDetailUiState.initial(frames)
            .select(frames, frameId = 32)
            .show(AngleV2SavedDetailTab.INFO, frames)
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }

        val saved = with(AngleV2SavedDetailUiState.StateSaver) { scope.save(state) }
        val restored = AngleV2SavedDetailUiState.StateSaver.restore(saved!!)

        assertEquals(AngleV2SavedDetailTab.INFO, restored?.tab)
        assertEquals(32L, restored?.selectedFrameId)
        assertEquals(restored, restored?.reconcile(frames))
    }

    private fun frame(
        frameId: Long,
        ordinal: Int = 0,
        assignedFrameIndex: Int = 1,
    ): AngleV2SavedDetailFrame = AngleV2SavedDetailFrame(
        ordinal = ordinal,
        frameId = frameId,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = assignedFrameIndex,
        presentationWidth = 1080,
        presentationHeight = 1920,
        rotationDegrees = 0,
        imageKey = "frames/$frameId.png",
        imageWidth = 1080,
        imageHeight = 1920,
        imageByteCount = 24_576L,
        imageSha256 = "a".repeat(64),
        imageMimeType = "image/png",
        marks = emptyList(),
    )
}
