package com.openjump.app.ui.fvp

import com.openjump.app.data.FvpDraft as PersistDraft
import java.lang.reflect.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2 (plan 13, lane A): contrato de la orquestacion PURA de guardado/borrado F-V-P
 * ([fvpPersistWithState] / [fvpDeleteWithState]), ejercitada con `runBlocking` y fakes locales
 * (sin `coroutines-test`, sin Android):
 *
 * 1. exito: `[Saving, Saved]`, `onSaved` una sola vez y la MISMA `sessionKey` (idempotencia);
 * 2. fallo: `[Saving, Error]`, sin `onSaved`, sin que el mensaje tecnico sea alcanzable;
 * 3. cancelacion: `CancellationException` re-lanzada, nunca un `Error` visible;
 * 4. reintento tras fallo: misma clave, `onSaved` exactamente una vez;
 * 5. borrador no persistible: `Error` sin llamar al saver;
 * 6. espejo de borrado: `true` -> `Deleted` + `onDeleted` una vez; `false`/excepcion -> `Error`;
 *    cancelacion -> re-lanzada sin `Error`.
 */
class FvpSaveOutcomeTest {

    @Test
    fun successfulSaveEmitsSavingThenSavedAndKeepsTheSessionKey() {
        val draft = persistDraft("session-ok")
        val states = mutableListOf<FvpSaveState>()
        val savedIds = mutableListOf<Long>()
        val saverKeys = mutableListOf<String>()
        var saveCalls = 0

        val outcome = runBlocking {
            fvpPersistWithState(
                persistable = draft,
                save = { persistable ->
                    saveCalls += 1
                    saverKeys += persistable.sessionKey
                    42L
                },
                onState = { states += it },
                onSaved = { savedIds += it },
            )
        }

        assertEquals(FvpSaveOutcome.Saved, outcome)
        assertEquals(listOf<FvpSaveState>(FvpSaveState.Saving, FvpSaveState.Saved), states)
        assertEquals("onSaved exactamente una vez, con el id devuelto", listOf(42L), savedIds)
        assertEquals(1, saveCalls)
        // Idempotencia: el helper entrega al saver EXACTAMENTE la clave del borrador recibido, no la
        // reescribe (la asercion de abajo falla si el helper regenerase la clave o mutase el draft).
        assertEquals(listOf(draft.sessionKey), saverKeys)
        assertEquals(1, saverKeys.distinct().size)
    }

    /**
     * El ORDEN importa: `Saving` tiene que emitirse ANTES de tocar el repositorio (la guarda
     * anti-doble-invocacion y el boton deshabilitado dependen de ello). Se registra todo en UNA
     * lista de eventos: una implementacion que llamase al saver antes de marcar `Saving` fallaria
     * aqui aunque el multiconjunto de estados fuese el mismo.
     */
    @Test
    fun savingIsEmittedBeforeTheRepositoryCall() {
        val draft = persistDraft("session-order")
        val events = mutableListOf<String>()

        runBlocking {
            fvpPersistWithState(
                persistable = draft,
                save = { events += "save"; 7L },
                onState = { events += it.toString() },
                onSaved = { events += "onSaved" },
            )
        }

        assertEquals(
            listOf("Saving", "save", "Saved", "onSaved"),
            events,
        )
    }

    @Test
    fun failedSaveEmitsErrorWithoutExposingTheTechnicalMessage() {
        val draft = persistDraft("session-fail")
        val states = mutableListOf<FvpSaveState>()
        val savedIds = mutableListOf<Long>()

        // Si la excepcion escapara del helper, runBlocking la re-lanzaria y el test fallaria aqui.
        val outcome = runBlocking {
            fvpPersistWithState(
                persistable = draft,
                save = { throw RuntimeException(TECHNICAL_MESSAGE) },
                onState = { states += it },
                onSaved = { savedIds += it },
            )
        }

        assertEquals(FvpSaveOutcome.Failed, outcome)
        assertEquals(listOf<FvpSaveState>(FvpSaveState.Saving, FvpSaveState.Error), states)
        assertEquals("un fallo no puede disparar onSaved", emptyList<Long>(), savedIds)

        val error = states.last()
        val errorClass = FvpSaveState.Error::class.java
        assertSame("Error debe ser el singleton sin payload", FvpSaveState.Error, error)
        assertTrue(
            "Error no puede declarar campos de instancia: ${errorClass.declaredFields.map { it.name }}",
            errorClass.declaredFields.none { !Modifier.isStatic(it.modifiers) },
        )
        assertFalse(
            "el mensaje tecnico no puede ser alcanzable desde el estado: $error",
            error.toString().contains("boom"),
        )
    }

    @Test
    fun saveCancellationIsRethrownAndNeverBecomesAnErrorState() {
        val draft = persistDraft("session-cancel")
        val states = mutableListOf<FvpSaveState>()
        val savedIds = mutableListOf<Long>()
        val cancelled = CancellationException("cancelado: el ViewModel se destruyo")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                fvpPersistWithState(
                    persistable = draft,
                    save = { throw cancelled },
                    onState = { states += it },
                    onSaved = { savedIds += it },
                )
            }
        }

        assertEquals("la cancelacion original debe propagarse sin envolver", cancelled.message, thrown.message)
        assertFalse("cancelar no es fallar: nunca Error", states.contains(FvpSaveState.Error))
        assertEquals(listOf<FvpSaveState>(FvpSaveState.Saving), states)
        assertEquals(emptyList<Long>(), savedIds)
    }

    @Test
    fun retryAfterFailureReusesTheSameSessionKeyAndCallsOnSavedOnce() {
        val draft = persistDraft("session-retry")
        val states = mutableListOf<FvpSaveState>()
        val savedIds = mutableListOf<Long>()
        val saverKeys = mutableListOf<String>()
        var attempts = 0
        val saver: suspend (PersistDraft) -> Long = { persistable ->
            saverKeys += persistable.sessionKey
            attempts += 1
            if (attempts == 1) throw RuntimeException(TECHNICAL_MESSAGE) else 7L
        }

        val first = runBlocking {
            fvpPersistWithState(draft, saver, { states += it }, { savedIds += it })
        }
        val second = runBlocking {
            fvpPersistWithState(draft, saver, { states += it }, { savedIds += it })
        }

        assertEquals(FvpSaveOutcome.Failed, first)
        assertEquals(FvpSaveOutcome.Saved, second)
        assertEquals(
            listOf(FvpSaveState.Saving, FvpSaveState.Error, FvpSaveState.Saving, FvpSaveState.Saved),
            states,
        )
        assertEquals(2, attempts)
        assertEquals(
            "el reintento reusa la MISMA clave (el repositorio es idempotente por sessionKey)",
            listOf("session-retry", "session-retry"),
            saverKeys,
        )
        assertEquals("onSaved exactamente una vez, solo con el exito", listOf(7L), savedIds)
        assertEquals(FvpSaveState.Saved, states.last())
    }

    @Test
    fun missingPersistableFailsWithoutCallingTheSaver() {
        val states = mutableListOf<FvpSaveState>()
        val savedIds = mutableListOf<Long>()
        var saveCalls = 0

        val outcome = runBlocking {
            fvpPersistWithState(
                persistable = null,
                save = {
                    saveCalls += 1
                    1L
                },
                onState = { states += it },
                onSaved = { savedIds += it },
            )
        }

        assertEquals(FvpSaveOutcome.Failed, outcome)
        assertEquals(listOf<FvpSaveState>(FvpSaveState.Error), states)
        assertEquals("sin masa/h_po no se llama al repositorio", 0, saveCalls)
        assertEquals(emptyList<Long>(), savedIds)
    }

    @Test
    fun successfulDeleteEmitsDeletingThenDeletedAndCallsOnDeletedOnce() {
        val states = mutableListOf<FvpDeleteState>()
        var deleteCalls = 0
        var onDeletedCalls = 0

        val deleted = runBlocking {
            fvpDeleteWithState(
                delete = {
                    deleteCalls += 1
                    true
                },
                onState = { states += it },
                onDeleted = { onDeletedCalls += 1 },
            )
        }

        assertTrue("el borrado ocurrio: el helper devuelve true", deleted)
        assertEquals(listOf<FvpDeleteState>(FvpDeleteState.Deleting, FvpDeleteState.Deleted), states)
        assertEquals(1, deleteCalls)
        assertEquals("onDeleted exactamente una vez", 1, onDeletedCalls)
    }

    @Test
    fun missingRowKeepsErrorAndNeverCallsOnDeleted() {
        val states = mutableListOf<FvpDeleteState>()
        var onDeletedCalls = 0

        val deleted = runBlocking {
            fvpDeleteWithState(
                delete = { false },
                onState = { states += it },
                onDeleted = { onDeletedCalls += 1 },
            )
        }

        assertFalse("la fila no existia: no se navega", deleted)
        assertEquals(listOf<FvpDeleteState>(FvpDeleteState.Deleting, FvpDeleteState.Error), states)
        assertEquals(0, onDeletedCalls)
    }

    @Test
    fun deleteExceptionBecomesErrorAndNeverCallsOnDeleted() {
        val states = mutableListOf<FvpDeleteState>()
        var onDeletedCalls = 0

        // Si la excepcion escapara del helper, runBlocking la re-lanzaria y el test fallaria aqui.
        val deleted = runBlocking {
            fvpDeleteWithState(
                delete = { throw RuntimeException(TECHNICAL_MESSAGE) },
                onState = { states += it },
                onDeleted = { onDeletedCalls += 1 },
            )
        }

        assertFalse(deleted)
        assertEquals(listOf<FvpDeleteState>(FvpDeleteState.Deleting, FvpDeleteState.Error), states)
        assertEquals("un fallo no puede navegar como si hubiera borrado", 0, onDeletedCalls)
    }

    @Test
    fun deleteCancellationIsRethrownAndNeverBecomesAnErrorState() {
        val states = mutableListOf<FvpDeleteState>()
        var onDeletedCalls = 0
        val cancelled = CancellationException("cancelado: el ViewModel se destruyo")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                fvpDeleteWithState(
                    delete = { throw cancelled },
                    onState = { states += it },
                    onDeleted = { onDeletedCalls += 1 },
                )
            }
        }

        assertEquals("la cancelacion original debe propagarse sin envolver", cancelled.message, thrown.message)
        assertFalse("cancelar no es fallar: nunca Error", states.contains(FvpDeleteState.Error))
        assertEquals(listOf<FvpDeleteState>(FvpDeleteState.Deleting), states)
        assertEquals(0, onDeletedCalls)
    }

    private fun persistDraft(sessionKey: String): PersistDraft = PersistDraft(
        sessionKey = sessionKey,
        athleteId = 1L,
        protocol = "SJ",
        bodyMassKg = 80.0,
        bodyMassSource = "MANUAL",
        pushOffMode = "COMMON",
        pushOffM = 0.4,
        pushOffSource = "MANUAL",
    )

    private companion object {
        const val TECHNICAL_MESSAGE = "boom: FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT)"
    }
}
