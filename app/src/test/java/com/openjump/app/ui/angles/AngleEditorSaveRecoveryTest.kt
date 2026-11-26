package com.openjump.app.ui.angles

import androidx.media3.exoplayer.ExoPlayer
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
import com.openjump.app.data.AngleAnalysisDraft
import com.openjump.app.data.AngleAnalysisEntity
import com.openjump.app.data.AngleAnalysisSnapshot
import com.openjump.app.data.AngleMarkEntity
import com.openjump.app.data.AngleUpdateDraft
import com.openjump.app.data.AngleUpdateResult
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerTransportMode
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoUriGrantReconciler
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM del camino de **recuperación** de una edición que ya no se puede guardar sobre su
 * análisis (raíz borrada en otra pantalla o escritura efectuada pero sin verificar).
 *
 * Cubre las tres piezas que el ciclo de revisión dejó abiertas:
 *
 * 1. la acción «guardar como análisis nuevo» es explícita y **solo** se ofrece mientras
 *    [AngleSaveRecovery] está pendiente, cableada al ViewModel y separada del guardado normal (que
 *    nunca crea copias por sí solo);
 * 2. el estado de recuperación es observable y **sobrevive a un fallo** del guardado de la copia,
 *    de modo que el usuario puede reintentar sin perder la edición ni el borrador;
 * 3. la recuperación real funciona para `Deleted` y para `Unverified`, es idempotente por intento
 *    (misma clave derivada, nunca un segundo análisis) y no reutiliza ni recrea la raíz original.
 *
 * La composición real se valida en instrumentación; aquí se fija el contrato de la fuente que la
 * pantalla consume, siguiendo el patrón ya existente de [AngleEditorScreenWiringTest].
 */
class AngleEditorSaveRecoveryTest {

    private val request = AngleEditorRequest(
        uri = VIDEO_URI,
        athleteId = ATHLETE_ID,
        sessionKey = SESSION_KEY,
        initialFrameIndex = 2,
    )

    // --- acción explícita en la pantalla ---------------------------------------------------------

    @Test
    fun `la accion de guardar como nuevo solo se ofrece durante una recuperacion`() {
        assertFalse(
            "sin recuperación pendiente el guardado normal es seguro: una copia sería un duplicado",
            angleSaveAsNewOffered(AngleSaveRecovery.None),
        )
        assertTrue(
            "una raíz borrada solo se puede conservar como análisis nuevo",
            angleSaveAsNewOffered(AngleSaveRecovery.Deleted(analysisId = 7L)),
        )
        assertTrue(
            "una escritura sin verificar no se pisa: se ofrece la copia explícita",
            angleSaveAsNewOffered(AngleSaveRecovery.Unverified(analysisId = 7L, revision = 9)),
        )
    }

    @Test
    fun `la pantalla cablea la accion al viewmodel y mantiene el guardado normal separado`() {
        val source = readScreen()

        assertTrue(
            "La pantalla debe observar el estado de recuperación del ViewModel",
            source.contains("viewModel.saveRecovery.collectAsStateWithLifecycle()"),
        )
        assertTrue(
            "La visibilidad del botón debe salir de la función que la prueba fija",
            source.contains("angleSaveAsNewOffered(saveRecovery)"),
        )
        assertTrue(
            "La acción explícita debe delegar en el único camino que conserva la edición",
            source.contains("onSaveAsNew = viewModel::saveAsNew"),
        )
        assertTrue(
            "El guardado normal debe seguir siendo `save`, no una copia silenciosa",
            source.contains("onSave = viewModel::save"),
        )
        assertTrue(
            "El botón normal no puede enviar la intención de copia",
            Regex("""onClick = actions\.onSave,""").containsMatchIn(source),
        )
        assertTrue(
            "El botón de recuperación debe estar localizado",
            source.contains("R.string.angle_save_as_new"),
        )
        assertTrue(
            "El botón debe explicarse a TalkBack sin depender del texto visible",
            source.contains("R.string.angle_save_as_new_description"),
        )
        // Objetivo táctil de 48dp: el botón de recuperación usa el mismo token que el resto de la
        // barra inferior (Spacing.xxxl == 48.dp) y ocupa el ancho completo.
        val recoveryBlock = source.substringAfter("ANGLE_SAVE_AS_NEW_TAG")
        assertTrue(
            "El botón de recuperación debe respetar el mínimo táctil de 48dp",
            recoveryBlock.contains("heightIn(min = Spacing.xxxl)"),
        )
        assertTrue(
            "El botón de recuperación debe ocupar el ancho completo de la barra",
            recoveryBlock.contains("fillMaxWidth()"),
        )
    }

    // --- recuperación real sobre el ViewModel ----------------------------------------------------

    @Test
    fun `un analisis borrado se recupera como copia explicita sin recrear la raiz original`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(revision = 0, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)

        // La raíz se borra en otra pantalla y el guardado normal no puede persistir la edición.
        harness.store.updateToReturn = AngleUpdateResult.NotFound
        assertEquals(AngleSaveState.Deleted, harness.viewModel.saveInternal())
        assertEquals(
            "el estado observable debe identificar la raíz borrada",
            AngleSaveRecovery.Deleted(analysisId = 7L),
            harness.viewModel.saveRecovery.value,
        )
        assertTrue(
            "con la raíz borrada la pantalla ofrece la acción explícita",
            angleSaveAsNewOffered(harness.viewModel.saveRecovery.value),
        )

        // La copia se crea con una clave derivada y se adopta como nueva referencia durable.
        harness.store.analysisIdToReturn = 42L
        harness.store.snapshotToReturn = snapshot(
            id = 42L,
            revision = 0,
            marks = listOf(markEntity(id = 71L, ordinal = 0)),
            sessionKey = recoverySessionKey(SESSION_KEY, 0),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 42L, revision = 0), harness.viewModel.saveAsNewInternal())
        assertEquals(42L, harness.viewModel.savedAnalysisId)
        assertEquals(
            "la recuperación resuelta deja de ofrecer la copia",
            AngleSaveRecovery.None,
            harness.viewModel.saveRecovery.value,
        )
        val copies = harness.store.saveNewDrafts.drop(1)
        assertEquals(listOf(recoverySessionKey(SESSION_KEY, 0)), copies.map { it.sessionKey })
        assertNotEquals(
            "la clave de la raíz borrada no se reutiliza ni se recrea",
            SESSION_KEY,
            copies.single().sessionKey,
        )

        // Guardar después de recuperar actualiza la COPIA, nunca el análisis borrado.
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(id = 42L, revision = 1, marks = listOf(markEntity(id = 71L, ordinal = 0))),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 42L, revision = 1), harness.viewModel.saveInternal())
        assertEquals(42L, harness.store.updateDrafts.last().analysisId)
        assertFalse(
            "ninguna escritura posterior puede apuntar a la raíz borrada",
            harness.store.updateDrafts.drop(1).any { it.analysisId == 7L },
        )
    }

    @Test
    fun `un fallo al guardar como nuevo conserva la edicion y el reintento es idempotente`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(revision = 0, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())
        harness.store.updateToReturn = AngleUpdateResult.NotFound
        assertEquals(AngleSaveState.Deleted, harness.viewModel.saveInternal())

        // Borrador A/B en curso: no es persistible, pero no puede perderse en el fallo.
        harness.placeDraftPoints()
        val anglesBefore = requireNotNull(harness.viewModel.editor.value).angles.map { it.name }

        harness.store.saveNewFailure = IllegalStateException("disco lleno")
        val failed = harness.viewModel.saveAsNewInternal()
        assertTrue("el fallo de la copia no se oculta: $failed", failed is AngleSaveState.Failed)
        assertEquals(
            "la recuperación sigue pendiente para poder reintentar",
            AngleSaveRecovery.Deleted(analysisId = 7L),
            harness.viewModel.saveRecovery.value,
        )
        assertTrue(angleSaveAsNewOffered(harness.viewModel.saveRecovery.value))
        val afterFailure = requireNotNull(harness.viewModel.editor.value)
        assertEquals(anglesBefore, afterFailure.angles.map { it.name })
        assertEquals("el borrador A/B se conserva", 2, afterFailure.draft.points.size)

        // Reintento: mismo intento, misma clave derivada (idempotente), sin segundo análisis.
        harness.store.saveNewFailure = null
        harness.store.analysisIdToReturn = 42L
        harness.store.snapshotToReturn = snapshot(
            id = 42L,
            revision = 0,
            marks = listOf(markEntity(id = 71L, ordinal = 0)),
            sessionKey = recoverySessionKey(SESSION_KEY, 0),
        )
        // El reintento reutiliza la clave del intento (idempotente) y crea la copia. El borrador A/B
        // sigue pendiente: la copia se adopta pero NO se informa `Saved`, porque la pantalla cerraría
        // el editor y perdería esos puntos.
        val copied = harness.viewModel.saveAsNewInternal()
        assertTrue("la copia con borrador pendiente no navega: $copied", copied is AngleSaveState.Failed)
        assertEquals(42L, harness.viewModel.savedAnalysisId)
        val keys = harness.store.saveNewDrafts.map { it.sessionKey }
        assertEquals(
            "el reintento reutiliza la clave del intento, no crea otra copia",
            listOf(SESSION_KEY, recoverySessionKey(SESSION_KEY, 0), recoverySessionKey(SESSION_KEY, 0)),
            keys,
        )
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)
        val afterRecovery = requireNotNull(harness.viewModel.editor.value)
        assertEquals(anglesBefore, afterRecovery.angles.map { it.name })
        assertEquals("la copia no descarta el borrador pendiente", 2, afterRecovery.draft.points.size)
        assertEquals(
            "el borrador no se envía como marca a medias",
            1,
            harness.store.saveNewDrafts.last().marks.size,
        )
    }

    // --- distinguir intentos de contenido (P1/P2) ------------------------------------------------

    @Test
    fun `una foto ilegible tras insertar no deja el reintento en conflicto sin salida`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()

        // La inserción se efectuó, pero su foto no se pudo releer: nada se adopta ni se verifica.
        harness.store.snapshotToReturn = null
        val unreadable = harness.viewModel.saveInternal()
        assertTrue("la foto ilegible no se da por guardada: $unreadable", unreadable is AngleSaveState.Failed)
        assertTrue((unreadable as AngleSaveState.Failed).message.orEmpty().contains("no se pudo releer"))
        assertNull(harness.viewModel.savedAnalysisId)

        // El usuario sigue editando: la edición viva ya no es el contenido de la fila insertada.
        harness.placeAngle()
        assertEquals(2, harness.viewModel.editor.value!!.angles.size)
        // Reintento: el repositorio devuelve la MISMA fila (idempotencia por clave) con el contenido
        // viejo, así que no puede coincidir con lo enviado.
        harness.store.snapshotToReturn = snapshot(
            revision = 3,
            marks = listOf(markEntity(id = 11L, ordinal = 0)),
        )

        val retry = harness.viewModel.saveInternal()

        assertTrue(
            "la clave ocupada con otro contenido no puede ser un conflicto terminal: $retry",
            retry is AngleSaveState.Failed,
        )
        assertNull("no se adopta una fila con otro contenido", harness.viewModel.savedAnalysisId)
        assertEquals(
            "la recuperación explícita queda disponible",
            AngleSaveRecovery.Unverified(analysisId = 7L, revision = 3),
            harness.viewModel.saveRecovery.value,
        )
        assertTrue(angleSaveAsNewOffered(harness.viewModel.saveRecovery.value))
        assertEquals(
            "los dos ángulos siguen en el editor",
            listOf("Angle 1", "Angle 2"),
            harness.viewModel.editor.value!!.angles.map { it.name },
        )
        assertEquals(
            "el reintento no inventa una clave nueva por el camino normal",
            listOf(SESSION_KEY, SESSION_KEY),
            harness.store.saveNewDrafts.map { it.sessionKey },
        )

        // El guardado normal queda bloqueado: reintentar contra la misma clave no puede funcionar.
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Failed)
        assertEquals(2, harness.store.saveNewDrafts.size)
        assertTrue(harness.store.updateDrafts.isEmpty())

        // Salida explícita: la edición posterior se conserva como copia, sin pisar la fila previa.
        harness.store.analysisIdToReturn = 42L
        harness.store.snapshotToReturn = snapshot(
            id = 42L,
            revision = 0,
            marks = listOf(
                markEntity(id = 71L, ordinal = 0),
                markEntity(id = 72L, ordinal = 1, name = "Angle 2"),
            ),
            sessionKey = recoverySessionKey(SESSION_KEY, 0),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 42L, revision = 0), harness.viewModel.saveAsNewInternal())
        assertEquals(42L, harness.viewModel.savedAnalysisId)
        assertEquals(
            "la copia usa una clave derivada, nunca la clave ocupada",
            recoverySessionKey(SESSION_KEY, 0),
            harness.store.saveNewDrafts.last().sessionKey,
        )
        assertEquals(
            "la fila previa no se reescribe",
            2,
            harness.store.saveNewDrafts.count { it.sessionKey == SESSION_KEY },
        )
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)

        // Ya adoptada la copia, el guardado normal escribe sobre ELLA (CAS), no sobre la fila vieja.
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(
                id = 42L,
                revision = 1,
                marks = listOf(
                    markEntity(id = 71L, ordinal = 0),
                    markEntity(id = 72L, ordinal = 1, name = "Angle 2"),
                ),
                sessionKey = recoverySessionKey(SESSION_KEY, 0),
            ),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 42L, revision = 1), harness.viewModel.saveInternal())
        assertEquals(42L, harness.store.updateDrafts.single().analysisId)
    }

    @Test
    fun `el reintento del mismo payload no crea otra copia ni deriva otra clave`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()

        harness.store.snapshotToReturn = null
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Failed)

        // Mismo payload, foto ya legible: la idempotencia devuelve la fila insertada y coincide.
        harness.store.snapshotToReturn = snapshot(revision = 0, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())

        assertEquals(7L, harness.viewModel.savedAnalysisId)
        assertEquals(
            "el reintento del mismo contenido reutiliza la clave original",
            listOf(SESSION_KEY, SESSION_KEY),
            harness.store.saveNewDrafts.map { it.sessionKey },
        )
        assertFalse(
            "no se crea ninguna copia para el mismo contenido",
            harness.store.saveNewDrafts.any { it.sessionKey != SESSION_KEY },
        )
        assertTrue(harness.store.updateDrafts.isEmpty())
    }

    @Test
    fun `un segundo guardar como nuevo tras adoptar la copia se rechaza sin duplicar`() = runBlocking {
        val harness = recoveredHarness()
        assertEquals(AngleSaveState.Saved(analysisId = 42L, revision = 0), harness.viewModel.saveAsNewInternal())
        assertEquals(2, harness.store.saveNewDrafts.size)

        // Segunda pulsación (doble toque o intención obsoleta): la copia ya se adoptó, así que no hay
        // nada que recuperar y NO se crea `#copy-2`.
        val obsolete = harness.viewModel.saveAsNewInternal()

        assertTrue("una intención obsoleta no se informa como guardada: $obsolete", obsolete is AngleSaveState.Failed)
        assertEquals(
            "no se escribe ninguna copia adicional",
            listOf(SESSION_KEY, recoverySessionKey(SESSION_KEY, 0)),
            harness.store.saveNewDrafts.map { it.sessionKey },
        )
        assertEquals(42L, harness.viewModel.savedAnalysisId)
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)
        assertEquals(
            "la edición no se pierde al rechazar la intención obsoleta",
            listOf("Angle 1"),
            harness.viewModel.editor.value!!.angles.map { it.name },
        )
    }

    @Test
    fun `un borrador A pendiente sobre la copia mantiene el editor abierto y no pierde datos`() = runBlocking {
        val harness = recoveredHarness()
        harness.placeDraftPoint()

        assertPendingDraftKeepsEditor(harness, harness.viewModel.saveAsNewInternal(), draftPoints = 1)

        // El borrador no se descarta: se completa y el siguiente guardado va por CAS sobre la copia.
        harness.completeDraft()
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(
                id = 42L,
                revision = 1,
                marks = listOf(
                    markEntity(id = 71L, ordinal = 0),
                    markEntity(id = 72L, ordinal = 1, name = "Angle 2"),
                ),
                sessionKey = recoverySessionKey(SESSION_KEY, 0),
            ),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 42L, revision = 1), harness.viewModel.saveInternal())
        assertEquals(42L, harness.store.updateDrafts.last().analysisId)
        assertEquals("el ángulo completado se persiste entero", 2, harness.store.updateDrafts.last().marks.size)
        assertEquals(0, requireNotNull(harness.viewModel.editor.value).draft.points.size)
    }

    @Test
    fun `un borrador AB pendiente sobre la copia mantiene el editor abierto y no pierde datos`() = runBlocking {
        val harness = recoveredHarness()
        harness.placeDraftPoints()

        assertPendingDraftKeepsEditor(harness, harness.viewModel.saveAsNewInternal(), draftPoints = 2)

        // Descartarlo es una decisión explícita del usuario y no borra el análisis guardado.
        assertEquals(AngleEditorActionResult.Applied, harness.viewModel.cancelDraft())
        assertEquals(0, requireNotNull(harness.viewModel.editor.value).draft.points.size)
        assertEquals(1, requireNotNull(harness.viewModel.editor.value).angles.size)
        assertEquals(42L, harness.viewModel.savedAnalysisId)
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)
    }

    @Test
    fun `la navegacion solo sale con un guardado informado como Saved`() {
        // Guard estructural: la pantalla del editor solo hace popBackStack con `Saved`. Cualquier
        // otro estado (incluido el `Failed` que conserva un borrador sin completar) deja el editor
        // abierto; la composición real se valida en instrumentación.
        val navSource = readSource("app/src/main/java/com/openjump/app/ui/AppNav.kt")
        val effect = navSource.substringAfter("LaunchedEffect(angleSaveState)").take(200)

        assertTrue(
            "el único pop por estado de guardado debe estar condicionado a Saved: $effect",
            effect.contains("if (angleSaveState is AngleSaveState.Saved) navController.popBackStack()"),
        )
        assertFalse(
            "ningún estado que conserva edición pendiente puede cerrar el editor: $effect",
            effect.contains("AngleSaveState.Failed") ||
                effect.contains("AngleSaveState.Conflict") ||
                effect.contains("AngleSaveState.Deleted"),
        )
    }

    @Test
    fun `una escritura sin verificar publica recuperacion y se conserva como copia`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(revision = 0, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())

        // La escritura se efectuó, pero la foto persistida no es lo enviado.
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(revision = 9, marks = listOf(markEntity(id = 11L, ordinal = 0, name = "Otro"))),
        )
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Failed)
        assertEquals(
            "la revisión persistida se expone sin adivinar",
            AngleSaveRecovery.Unverified(analysisId = 7L, revision = 9),
            harness.viewModel.saveRecovery.value,
        )
        assertTrue(angleSaveAsNewOffered(harness.viewModel.saveRecovery.value))

        // El guardado normal sigue bloqueado: no se pisa la raíz no verificada.
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Failed)
        assertEquals(1, harness.store.updateDrafts.size)

        harness.store.analysisIdToReturn = 42L
        harness.store.snapshotToReturn = snapshot(
            id = 42L,
            revision = 0,
            marks = listOf(markEntity(id = 72L, ordinal = 0)),
            sessionKey = recoverySessionKey(SESSION_KEY, 0),
        )
        assertEquals(AngleSaveState.Saved(analysisId = 42L, revision = 0), harness.viewModel.saveAsNewInternal())
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)
        assertEquals(
            "la copia de una escritura no verificada no reutiliza la raíz",
            recoverySessionKey(SESSION_KEY, 0),
            harness.store.saveNewDrafts.last().sessionKey,
        )
        assertEquals(
            "la recuperación desbloquea el guardado normal sobre la copia",
            AngleSaveState.Saved(analysisId = 42L, revision = 1),
            run {
                harness.store.updateToReturn = AngleUpdateResult.Updated(
                    snapshot(id = 42L, revision = 1, marks = listOf(markEntity(id = 72L, ordinal = 0))),
                )
                harness.viewModel.saveInternal()
            },
        )
    }

    @Test
    fun `reabrir otro analisis limpia la recuperacion pendiente y no ofrece copia`() = runBlocking {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(revision = 0, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())
        harness.store.updateToReturn = AngleUpdateResult.Updated(
            snapshot(revision = 9, marks = listOf(markEntity(id = 11L, ordinal = 0, name = "Otro"))),
        )
        assertTrue(harness.viewModel.saveInternal() is AngleSaveState.Failed)
        assertTrue(angleSaveAsNewOffered(harness.viewModel.saveRecovery.value))

        // Reabrir otro análisis sustituye la edición viva: la recuperación de la raíz anterior deja
        // de aplicar y el editor no puede mostrar una acción que ya no corresponde a su contenido.
        harness.store.snapshotToReturn = snapshot(
            id = 8L,
            revision = 3,
            marks = listOf(markEntity(id = 81L, ordinal = 0, name = "Cadera")),
            sessionKey = "otra-sesion",
        )
        assertEquals(AngleReopenResult.Reopened, harness.viewModel.reopenInternal(8L))

        assertEquals(8L, harness.viewModel.savedAnalysisId)
        assertEquals(AngleSaveRecovery.None, harness.viewModel.saveRecovery.value)
        assertFalse(angleSaveAsNewOffered(harness.viewModel.saveRecovery.value))
        assertEquals(listOf("Cadera"), harness.viewModel.editor.value!!.angles.map { it.name })
    }

    // --- utilidades -----------------------------------------------------------------------------

    /**
     * Harness con la raíz ya borrada en otra pantalla (recuperación [AngleSaveRecovery.Deleted]
     * pendiente) y la copia derivada preparada: una sola marca, la del ángulo completo.
     */
    private suspend fun recoveredHarness(): Harness {
        val harness = Harness()
        harness.viewModel.openInternal(request)
        harness.pauseAt(frameIndex = 2)
        harness.placeAngle()
        harness.store.snapshotToReturn = snapshot(revision = 0, marks = listOf(markEntity(id = 11L, ordinal = 0)))
        assertEquals(AngleSaveState.Saved(analysisId = 7L, revision = 0), harness.viewModel.saveInternal())
        harness.store.updateToReturn = AngleUpdateResult.NotFound
        assertEquals(AngleSaveState.Deleted, harness.viewModel.saveInternal())
        assertEquals(
            AngleSaveRecovery.Deleted(analysisId = 7L),
            harness.viewModel.saveRecovery.value,
        )
        harness.store.analysisIdToReturn = 42L
        harness.store.snapshotToReturn = snapshot(
            id = 42L,
            revision = 0,
            marks = listOf(markEntity(id = 71L, ordinal = 0)),
            sessionKey = recoverySessionKey(SESSION_KEY, 0),
        )
        return harness
    }

    /**
     * Contrato del borrador sin completar: la copia queda adoptada, pero no se informa `Saved` (la
     * pantalla cerraría el editor), no se escriben puntos a medias y la edición sigue intacta.
     */
    private fun assertPendingDraftKeepsEditor(
        harness: Harness,
        state: AngleSaveState,
        draftPoints: Int,
    ) {
        assertTrue(
            "un borrador sin completar no puede informarse Saved: la pantalla cerraría el editor: $state",
            state is AngleSaveState.Failed,
        )
        assertTrue(
            "el estado debe explicar que el borrador sigue sin guardar",
            (state as AngleSaveState.Failed).message.orEmpty().contains("borrador"),
        )
        assertEquals("la copia ya está adoptada como referencia durable", 42L, harness.viewModel.savedAnalysisId)
        assertEquals(
            "la recuperación queda resuelta: la edición tiene destino",
            AngleSaveRecovery.None,
            harness.viewModel.saveRecovery.value,
        )
        val editor = requireNotNull(harness.viewModel.editor.value)
        assertEquals("el borrador no se descarta", draftPoints, editor.draft.points.size)
        assertEquals("los ángulos completos siguen en el editor", 1, editor.angles.size)
        assertEquals(
            "los puntos incompletos no se escriben como marca",
            1,
            harness.store.saveNewDrafts.last().marks.size,
        )
        assertTrue(
            "la copia no se persiste con un update: ninguna escritura CAS apunta a ella",
            harness.store.updateDrafts.none { it.analysisId == 42L },
        )
    }

    private fun snapshot(
        revision: Int,
        marks: List<AngleMarkEntity>,
        sessionKey: String = SESSION_KEY,
        id: Long = 7L,
    ): AngleAnalysisSnapshot = AngleAnalysisSnapshot(
        analysis = AngleAnalysisEntity(
            id = id,
            sessionKey = sessionKey,
            athleteId = ATHLETE_ID,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            revision = revision,
            videoUri = VIDEO_URI,
            sourceIdentity = VIDEO_URI,
            sourceHash = null,
            encodedWidth = 640,
            encodedHeight = 360,
            presentationWidth = 640,
            presentationHeight = 360,
            rotationDegrees = 0,
            frameIndex = 2,
            sourcePtsUs = 80_000L,
            geometryVersion = ANGLE_GEOMETRY_VERSION,
        ),
        marks = marks,
    )

    private fun markEntity(id: Long, ordinal: Int, name: String = "Angle 1") = AngleMarkEntity(
        id = id,
        analysisId = 7L,
        ordinal = ordinal,
        name = name,
        colorArgb = COLOR_ARGB,
        visible = true,
        pointAX = 10.0,
        pointAY = 10.0,
        pointBX = 10.0,
        pointBY = 50.0,
        pointCX = 50.0,
        pointCY = 10.0,
    )

    private class Harness {
        val store = FakeStore()
        val viewer = FakeViewer()
        val grants = FakeGrants()
        val viewModel = AngleEditorViewModel(
            store = store,
            grants = grants,
            requestReconcile = {},
            indexer = FakeIndexer(),
            defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = COLOR_ARGB),
            createViewer = { _, _ -> viewer },
        )

        /** Presenta el frame en pausa con su PTS canónico: es la confirmación que exigen los gestos. */
        fun pauseAt(frameIndex: Int) {
            viewer.display.value = FrameDisplayState(
                requestedIndex = frameIndex,
                renderedIndex = frameIndex,
                renderedPtsUs = VIDEO_INDEX.timeOf(frameIndex),
                mode = ViewerTransportMode.PAUSED,
            )
        }

        fun placeAngle() {
            val confirmation = requireNotNull(viewModel.currentConfirmation())
            listOf(
                AnglePoint(10.0, 10.0),
                AnglePoint(10.0, 50.0),
                AnglePoint(50.0, 10.0),
            ).forEach { point ->
                assertEquals(AngleEditorActionResult.Applied, viewModel.placePoint(point, confirmation))
            }
        }

        /** Deja un borrador A/B sin completar (dos de los tres puntos), como un toque interrumpido. */
        fun placeDraftPoints() {
            val confirmation = requireNotNull(viewModel.currentConfirmation())
            listOf(AnglePoint(20.0, 20.0), AnglePoint(20.0, 40.0)).forEach { point ->
                assertEquals(AngleEditorActionResult.Applied, viewModel.placePoint(point, confirmation))
            }
            assertEquals(2, requireNotNull(viewModel.editor.value).draft.points.size)
        }

        /** Deja solo el punto A del borrador, como un toque único antes de salir de pantalla. */
        fun placeDraftPoint() {
            val confirmation = requireNotNull(viewModel.currentConfirmation())
            assertEquals(
                AngleEditorActionResult.Applied,
                viewModel.placePoint(AnglePoint(10.0, 10.0), confirmation),
            )
            assertEquals(1, requireNotNull(viewModel.editor.value).draft.points.size)
        }

        /** Completa el borrador pendiente en el frame confirmado, sin descartar ningún punto. */
        fun completeDraft() {
            val confirmation = requireNotNull(viewModel.currentConfirmation())
            val remaining = listOf(AnglePoint(10.0, 50.0), AnglePoint(50.0, 10.0))
            var placed = 0
            while (requireNotNull(viewModel.editor.value).draft.points.isNotEmpty()) {
                check(placed < remaining.size) { "el borrador no se pudo completar en tres puntos" }
                assertEquals(
                    AngleEditorActionResult.Applied,
                    viewModel.placePoint(remaining[placed], confirmation),
                )
                placed += 1
            }
            assertEquals(
                "el borrador completado es un ángulo más, no un descarte",
                2,
                requireNotNull(viewModel.editor.value).angles.size,
            )
        }
    }

    private class FakeStore : AngleAnalysisStore {

        val saveNewDrafts = mutableListOf<AngleAnalysisDraft>()
        val updateDrafts = mutableListOf<AngleUpdateDraft>()
        var analysisIdToReturn = 7L
        var snapshotToReturn: AngleAnalysisSnapshot? = null
        var updateToReturn: AngleUpdateResult = AngleUpdateResult.NotFound
        var saveNewFailure: Exception? = null

        override suspend fun saveNew(draft: AngleAnalysisDraft): Long {
            saveNewDrafts += draft
            saveNewFailure?.let { throw it }
            return analysisIdToReturn
        }

        override suspend fun update(draft: AngleUpdateDraft): AngleUpdateResult {
            updateDrafts += draft
            return updateToReturn
        }

        override suspend fun snapshot(analysisId: Long): AngleAnalysisSnapshot? = snapshotToReturn
    }

    private class FakeGrants : AngleVideoGrants {
        override fun registerOwner(uris: Set<String>): VideoUriGrantReconciler.Lease =
            VideoUriGrantReconciler.Lease {}

        override suspend fun takeReadGrant(uri: String) = Unit
    }

    private class FakeIndexer : AngleVideoIndexer {
        override suspend fun index(uri: String): VideoFrameIndex = VIDEO_INDEX
    }

    private class FakeViewer : AngleViewerViewer {

        val display = MutableStateFlow(FrameDisplayState())
        override val player: StateFlow<ExoPlayer?> = MutableStateFlow<ExoPlayer?>(null)
        override val displayState: StateFlow<FrameDisplayState> = display
        override val loading: StateFlow<Boolean> = MutableStateFlow(false)
        override val error: StateFlow<String?> = MutableStateFlow<String?>(null)

        override fun start() = Unit

        override fun startSessionCollection() = Unit

        override fun seekToFrame(frameIndex: Int) {
            display.value = display.value.request(frameIndex, ViewerTransportMode.SEEKING)
        }

        override fun reopenFrame(frameIndex: Int) {
            val current = display.value
            if (current.canMark && current.requestedIndex == frameIndex) return
            display.value = current.request(frameIndex, ViewerTransportMode.SEEKING)
        }

        override fun refreshCurrentFrame() = Unit

        override fun stepFrames(delta: Int) = Unit

        override fun togglePlayback() = Unit

        override fun pausePlayback() = Unit

        override fun beginScrubbing() = Unit

        override fun scrubToFrame(frameIndex: Int) = Unit

        override fun endScrubbing() = Unit

        override fun cancelPendingScrubRelease() = Unit

        override fun release() = Unit
    }

    private companion object {
        const val VIDEO_URI = "content://openjump/angle-video"
        const val SESSION_KEY = "angle-session-1"
        const val ATHLETE_ID = 3L
        const val COLOR_ARGB = 0xFF35D0FF.toInt()

        val VIDEO_INDEX = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 40_000L, 80_000L, 120_000L),
            durationUs = 160_000L,
            width = 640,
            height = 360,
            rotationDegrees = 0,
            mimeType = "video/mp4",
        )

        fun readScreen(): String = readSource("app/src/main/java/com/openjump/app/ui/angles/AngleEditorScreen.kt")

        fun readSource(relative: String): String {
            val direct = relative.removePrefix("app/")
            val tried = mutableListOf<File>()
            var directory: File? = File(".").absoluteFile
            while (directory != null) {
                for (candidate in listOf(File(directory, relative), File(directory, direct))) {
                    tried += candidate
                    if (candidate.isFile) return candidate.readText()
                }
                directory = directory.parentFile
            }
            throw AssertionError(
                "No se encuentra '$relative'. Directorio de trabajo: ${File(".").absolutePath}. " +
                    "Rutas probadas: ${tried.joinToString { it.absolutePath }}",
            )
        }
    }
}
