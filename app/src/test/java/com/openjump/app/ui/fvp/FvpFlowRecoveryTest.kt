package com.openjump.app.ui.fvp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.fvp.FvpProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Contrato del dueño de la recuperacion F-V-P (slices R2 y R3, plan 13 D6): el PLAN de
 * restauracion, la REGLA de actualizacion del payload y su revalidacion, con LITERALES y sin
 * Compose ni Android.
 *
 * Lo que se prueba aqui es la decision PURA que el dueño ejecuta: que un estado valido se restaure,
 * que un payload corrupto / de version desconocida / por encima del presupuesto se rechace CON su
 * motivo, que un estado decodificado que no pasa la revalidacion NO se adopte, que ante un rechazo
 * el payload ANTERIOR quede intacto, que vaciar no conserve nada y que una ruta ajena al flujo no
 * declare un paso.
 *
 * R3 anade la RECONCILIACION con la verdad PERSISTIDA antes de adoptar: cada rama de
 * [fvpRecoveredStateReconciliation] con su precedencia, la decision del DUEÑO (no adoptar y limpiar
 * el payload cuando la reconciliacion no es [FvpRecoveryReconciliation.ADOPT], fallo cerrado si la
 * verdad no se puede leer) y la regresion de que un estado que SI reconcilia a ADOPT sigue
 * comportandose como en R2, borradores parciales incluidos.
 *
 * A1 anade la MARCA DE VIGENCIA del payload retenido ([FVP_RECOVERY_INVALIDATED_KEY]) probada sobre
 * el DUEÑO real: un rechazo del borrador vivo conserva los bytes anteriores EXACTOS pero inertes
 * (nunca se resucita el contenido viejo ni una identidad ajena al reconstruir), el borrador vivo no
 * se toca, una edicion valida posterior vuelve a habilitar el contenido ACTUAL, la salida terminal
 * descarta bytes y marca, y la marca precede al decode en `init`.
 *
 * A2 anade las MUTACIONES EXTERNAS por el dueño: cada begin/adopt/update/reset supersede de forma
 * SINCRONA la reconciliacion pendiente y la recuperabilidad (incluso con la sesion ya `null`, donde
 * un colector no emite), y la salida terminal de un guardado confirmado queda atada a la `sessionKey`
 * exacta (`resetIfSessionKey`): la serie A no puede descartar ni desviar la serie B.
 *
 * A3 anade la reconciliacion ASINCRONA: el constructor publica PENDIENTE sin bloquear, la verdad
 * persistida llega despues (fuente suspendida y controlada), la cancelacion de ciclo de vida NO se
 * reinterpreta como verdad ausente, y una resolucion capturada con una generacion anterior no aplica
 * NINGUN efecto (adopcion, limpieza de payload, aviso ni bandera de pendiente) aunque la fuente no
 * sea cooperativa.
 *
 * COSTURAS DE CICLO DE VIDA, SESION VIVA Y ORDEN DE PUBLICACION (probadas sobre el DUEÑO real):
 * - un dueño LIMPIADO (`ViewModelStore.clear()`, que NO cambia la generacion) no aplica efecto alguno
 *   aunque la fuente se coma la cancelacion y devuelva su verdad -o el fallo cerrado de su propia
 *   cota- DESPUES de la limpieza;
 * - si el dueño NACE con un borrador VIVO en la sesion (Activity nueva en un proceso que
 *   sobrevivio), la sesion manda: el payload retenido no se decodifica ni se reconcilia (CERO
 *   consultas) y jamas se adopta por encima de lo vivo, con la recuperabilidad derivada del estado
 *   ACTUAL (y sus bytes anteriores inertes si ese estado no es almacenable);
 * - la adopcion ASINCRONA fija el paso restaurado y el "borrador ya escrito" ANTES de publicar el
 *   borrador en la sesion, de modo que un colector que se reanuda EN LINEA dentro del `setter` del
 *   `StateFlow` no re-codifica el payload con el paso viejo.
 *
 * NADA de esta suite JVM afirma recuperacion real de proceso. La mitad Android de R3 se registra
 * por separado en checkpoints19/20 (registry y muerte normal con identidad interna). Aqui solo se
 * reconstruye el estado guardado en un dueño nuevo con la sesion viva descartada.
 */
class FvpFlowRecoveryTest {

    // `FvpDraftSession` es un singleton POR PROCESO compartido por toda la suite: estas pruebas lo
    // usan como sesion viva, asi que lo dejan limpio antes y despues (ningun otro test JVM depende
    // de su contenido).
    @Before
    fun resetSessionBefore() {
        FvpDraftSession.reset()
    }

    /**
     * Limpieza REAL de los dueños del test: `ViewModelStore.clear()` llama a `onCleared()`, que
     * cancela el `viewModelScope` del observador del borrador vivo. Sin esto cada dueño seguiria
     * recolectando el singleton `FvpDraftSession` entre tests (y entre reconstrucciones simuladas),
     * con efectos cruzados sobre el payload de otra prueba.
     */
    @After
    fun clearOwnersThenResetSession() {
        clearOwners()
        FvpDraftSession.reset()
    }

    // --- plan de restauracion ------------------------------------------------------------------

    /**
     * P2 de la revision de R2: la regla de ESCRITURA y la de ADOPCION usan el MISMO predicado. Un
     * estado que la revalidacion rechazaria NO se guarda nunca; se conserva el payload anterior y se
     * publica el motivo, en vez de producir un payload que el propio dueño luego negara a restaurar.
     */
    @Test
    fun storeRuleAndAdoptionRuleUseTheSamePredicate_soAnUnadoptableStateIsNeverStored() {
        val adoptable = FvpDraft(sessionKey = "session-sym", athleteId = 7L, bodyMassKg = 80.0)
        val previousState = FvpRecoveryState(adoptable, FvpFlowStep.SETUP)
        val previous = requireNotNull(FvpDraftStateCodec.encode(previousState))
        // El codec SI sabe serializar un estado sin masa (es un borrador parcial legitimo)...
        val noMass = FvpRecoveryState(
            FvpDraft(sessionKey = "session-sym", athleteId = 7L, bodyMassKg = null),
            FvpFlowStep.SETUP,
        )
        assertNotNull(FvpDraftStateCodec.encode(noMass))

        // ...pero la revalidacion lo rechaza, y por tanto la regla de escritura tampoco lo guarda:
        // conserva el payload anterior EXACTO y explica el motivo.
        assertFalse("un estado sin masa no es adoptable", fvpRecoveredStateIsAdoptable(noMass))
        assertEquals(
            FvpDraftPayloadUpdate.KeepPrevious(FvpRecoveryRefusal.NOT_RECOVERABLE),
            fvpDraftPayloadUpdate(noMass),
        )
        assertEquals(previous, fvpApplyPayloadUpdate(previous, fvpDraftPayloadUpdate(noMass)))

        // Control: un estado adoptable SI se guarda (el predicado no bloquea de mas).
        assertTrue(fvpRecoveredStateIsAdoptable(previousState))
        assertTrue(fvpDraftPayloadUpdate(previousState) is FvpDraftPayloadUpdate.Store)
    }

    @Test
    fun noPayloadIsNothingToRestoreAndNotAFailure() {
        assertEquals(
            "sin payload no hay nada que recuperar (y no es un rechazo)",
            FvpRecoverPlan.Nothing,
            fvpRecoverPlan(null),
        )
    }

    @Test
    fun validPayloadRestoresTheDraftUntouched() {
        val draft = FvpDraft(
            sessionKey = "session-restore-1",
            athleteId = 7L,
            protocol = FvpProtocol.CMJ,
            bodyMassKg = 81.25,
            commonPushOffM = 0.42,
        )
        val payload = requireNotNull(FvpDraftStateCodec.encode(FvpRecoveryState(draft, FvpFlowStep.CONDITIONS)))

        val plan = fvpRecoverPlan(payload)
        assertTrue("un payload valido se restaura", plan is FvpRecoverPlan.Restored)
        val restored = (plan as FvpRecoverPlan.Restored).state
        assertEquals("la identidad persistida viaja", "session-restore-1", restored.draft.sessionKey)
        assertEquals("el borrador se restaura tal cual (masa)", 81.25, restored.draft.bodyMassKg!!, 0.0)
        assertEquals("el paso del flujo viaja", FvpFlowStep.CONDITIONS, restored.step)
    }

    @Test
    fun partialLiveDraftStillRestoresBecauseTheFlowProducesIt() {
        // Un borrador recien confirmado en el paso 1 es PARCIAL por definicion (masa y h_po, sin
        // condiciones): la revalidacion NO puede exigir un perfil completo o rechazaria un estado
        // que el flujo vivo acaba de crear.
        val draft = FvpDraft(
            sessionKey = "session-partial",
            athleteId = 3L,
            protocol = FvpProtocol.SJ,
            bodyMassKg = 78.4,
            commonPushOffM = 0.4,
        )
        val payload = requireNotNull(FvpDraftStateCodec.encode(FvpRecoveryState(draft, FvpFlowStep.SETUP)))

        assertTrue("un borrador parcial legitimo se restaura", fvpRecoverPlan(payload) is FvpRecoverPlan.Restored)
        assertTrue(
            "la revalidacion acepta el borrador parcial (misma admision que el flujo vivo)",
            fvpRecoveredStateIsAdoptable(FvpRecoveryState(draft, FvpFlowStep.SETUP)),
        )
    }

    @Test
    fun corruptPayloadIsRefusedAsNotRecoverable() {
        val plan = fvpRecoverPlan("{no es json")
        assertEquals(
            "un payload corrupto se rechaza con su motivo (nunca se inventa un borrador)",
            FvpRecoverPlan.Refused(FvpRecoveryRefusal.NOT_RECOVERABLE),
            plan,
        )
    }

    @Test
    fun unknownVersionPayloadIsRefusedAsNotRecoverable() {
        val valid = requireNotNull(
            FvpDraftStateCodec.encode(
                FvpRecoveryState(FvpDraft(sessionKey = "session-v", athleteId = 2L, bodyMassKg = 80.0), FvpFlowStep.SETUP),
            ),
        )
        val future = valid.replace("\"version\":${FvpDraftStateCodec.VERSION}", "\"version\":3")
        assertFalse("control: el payload de version futura difiere del valido", future == valid)

        assertEquals(
            "una version desconocida se rechaza entera",
            FvpRecoverPlan.Refused(FvpRecoveryRefusal.NOT_RECOVERABLE),
            fvpRecoverPlan(future),
        )
    }

    @Test
    fun overBudgetPayloadIsRefusedWithItsOwnReason() {
        val valid = requireNotNull(
            FvpDraftStateCodec.encode(
                FvpRecoveryState(FvpDraft(sessionKey = "session-big", athleteId = 2L, bodyMassKg = 80.0), FvpFlowStep.SETUP),
            ),
        )
        val oversized = valid.replace(
            "\"notes\":null",
            "\"notes\":\"${"x".repeat(70_000)}\"",
        )
        assertFalse("control: la alteracion debe aplicarse", oversized == valid)
        assertTrue(
            "el payload inflado supera el presupuesto",
            oversized.toByteArray(Charsets.UTF_8).size > FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        assertEquals(
            "por encima del presupuesto el motivo es el tamano, no otra causa",
            FvpRecoverPlan.Refused(FvpRecoveryRefusal.TOO_LARGE),
            fvpRecoverPlan(oversized),
        )

        // El presupuesto cuenta BYTES UTF-8: un payload con menos CARACTERES que el presupuesto
        // pero mas bytes tambien se rechaza entero (y por el motivo correcto).
        val multibyteNote = "é".repeat(40_000)
        val multibytePayload = valid.replace("\"notes\":null", "\"notes\":\"$multibyteNote\"")
        assertFalse("control: la alteracion multibyte debe aplicarse", multibytePayload == valid)
        assertTrue(
            "el payload multibyte tiene menos caracteres que el presupuesto",
            multibytePayload.length < FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        assertTrue(
            "pero mas bytes UTF-8 que el presupuesto",
            multibytePayload.toByteArray(Charsets.UTF_8).size > FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES,
        )
        assertEquals(
            "el presupuesto de decode se mide en bytes UTF-8, no en caracteres",
            FvpRecoverPlan.Refused(FvpRecoveryRefusal.TOO_LARGE),
            fvpRecoverPlan(multibytePayload),
        )
    }

    @Test
    fun decodedDraftThatFailsRevalidationIsNotAdopted() {
        // Un payload SIN masa corporal sigue siendo decodificable (el codec no inventa una
        // evaluacion valida, pero conserva los estados parciales), y sin embargo el flujo vivo no
        // puede haberlo producido: `commitSetupDraft` exige masa y `FvpRepository.save` exige
        // `bodyMassKg` finito y > 0. La revalidacion lo rechaza en vez de adoptarlo a ciegas.
        val noMass = FvpDraft(sessionKey = "session-no-mass", athleteId = 3L, bodyMassKg = null)
        val noMassPayload = requireNotNull(
            FvpDraftStateCodec.encode(FvpRecoveryState(noMass, FvpFlowStep.SETUP)),
        )
        assertNotNull("control: el codec SI decodifica el estado sin masa", FvpDraftStateCodec.decode(noMassPayload))
        assertFalse("un estado sin masa no pasa la revalidacion", fvpRecoveredStateIsAdoptable(FvpRecoveryState(noMass, FvpFlowStep.SETUP)))
        assertEquals(
            "un estado decodificado que no revalida NO se adopta",
            FvpRecoverPlan.Refused(FvpRecoveryRefusal.NOT_RECOVERABLE),
            fvpRecoverPlan(noMassPayload),
        )

        val negativeMass = FvpDraft(sessionKey = "session-negative", athleteId = 3L, bodyMassKg = -80.0)
        val negativePayload = requireNotNull(
            FvpDraftStateCodec.encode(FvpRecoveryState(negativeMass, FvpFlowStep.SETUP)),
        )
        assertNotNull("control: el codec decodifica una masa negativa", FvpDraftStateCodec.decode(negativePayload))
        assertEquals(
            "una masa no utilizable tampoco se adopta",
            FvpRecoverPlan.Refused(FvpRecoveryRefusal.NOT_RECOVERABLE),
            fvpRecoverPlan(negativePayload),
        )
    }

    // --- regla de actualizacion del payload ----------------------------------------------------

    @Test
    fun storingAValidDraftReplacesThePreviousPayload() {
        val draft = FvpDraft(sessionKey = "session-store", athleteId = 5L, bodyMassKg = 80.0)
        val update = fvpDraftPayloadUpdate(FvpRecoveryState(draft, FvpFlowStep.SETUP))

        assertTrue("un estado recuperable se GUARDA", update is FvpDraftPayloadUpdate.Store)
        val stored = (update as FvpDraftPayloadUpdate.Store).payload
        assertEquals(
            "el payload guardado es exactamente el que el codec produce",
            FvpDraftStateCodec.encode(FvpRecoveryState(draft, FvpFlowStep.SETUP)),
            stored,
        )
        assertEquals(
            "guardar sustituye el payload anterior",
            stored,
            fvpApplyPayloadUpdate("payload-anterior", update),
        )
    }

    @Test
    fun refusalKeepsThePreviousPayloadUntouched() {
        // El borrador sobredimensionado SI es adoptable (masa valida): asi el rechazo que se prueba
        // es el del PRESUPUESTO, no el de la revalidacion (el predicado de adopcion se evalua
        // antes que el tamano y un estado no adoptable daria NOT_RECOVERABLE, no TOO_LARGE).
        val overBudget = FvpDraft(
            sessionKey = "session-big",
            athleteId = 5L,
            bodyMassKg = 80.0,
            notes = "x".repeat(70_000),
        )
        val tooLarge = fvpDraftPayloadUpdate(FvpRecoveryState(overBudget, FvpFlowStep.CONDITIONS))
        assertEquals(
            "por encima del presupuesto el motivo es el tamano",
            FvpDraftPayloadUpdate.KeepPrevious(FvpRecoveryRefusal.TOO_LARGE),
            tooLarge,
        )
        assertEquals(
            "ante un rechazo el payload ANTERIOR queda intacto (no se marca como actualizado)",
            "payload-anterior",
            fvpApplyPayloadUpdate("payload-anterior", tooLarge),
        )

        val incoherent = FvpDraft(
            sessionKey = "session-incoherent",
            athleteId = 5L,
            bodyMassKg = null,
            bodyMassFromProfile = true,
        )
        val notRecoverable = fvpDraftPayloadUpdate(FvpRecoveryState(incoherent, FvpFlowStep.SETUP))
        assertEquals(
            "un estado no representable tiene su propio motivo",
            FvpDraftPayloadUpdate.KeepPrevious(FvpRecoveryRefusal.NOT_RECOVERABLE),
            notRecoverable,
        )
        assertEquals(
            "el rechazo estructural tampoco toca el payload anterior",
            "payload-anterior",
            fvpApplyPayloadUpdate("payload-anterior", notRecoverable),
        )
    }

    @Test
    fun clearingRemovesThePayload() {
        assertEquals(
            "sin borrador vivo no se conserva recuperacion",
            FvpDraftPayloadUpdate.Clear,
            fvpDraftPayloadUpdate(null),
        )
        assertNull(
            "vaciar deja de conservar el payload anterior",
            fvpApplyPayloadUpdate("payload-anterior", FvpDraftPayloadUpdate.Clear),
        )
        assertNull("vaciar sin payload previo sigue sin conservar nada", fvpApplyPayloadUpdate(null, FvpDraftPayloadUpdate.Clear))
    }

    // --- paso del flujo por ruta ---------------------------------------------------------------

    @Test
    fun onlyFlowRoutesDeclareAFlowStep() {
        assertEquals("setup", FvpFlowStep.SETUP, fvpFlowStepForRoute("fvp/setup"))
        assertEquals("condiciones", FvpFlowStep.CONDITIONS, fvpFlowStepForRoute("fvp/conditions"))
        assertEquals("resultado vivo", FvpFlowStep.RESULT, fvpFlowStepForRoute("fvp/result"))
        assertNull("Home no es un paso del flujo", fvpFlowStepForRoute("home"))
        assertNull("el historial no es un paso del flujo", fvpFlowStepForRoute("fvp/history"))
        assertNull("un resultado guardado no es el resultado vivo", fvpFlowStepForRoute("fvp/result/12"))
        assertNull("sin ruta no se declara paso", fvpFlowStepForRoute(null))
    }

    // --- R3: reconciliacion con la verdad persistida (PURA) --------------------------------------

    /**
     * Tabla de verdad LITERAL de [fvpRecoveredStateReconciliation] con la PRECEDENCIA: la fila con
     * ese `sessionKey` manda sobre el estado del atleta (filas 1-2 de la tabla del contrato), y solo
     * cuando no hay fila decide el atleta (filas 3-4).
     */
    @Test
    fun reconciliationTruthTableWithItsPrecedence() {
        val state = FvpRecoveryState(recoveredDraft(athleteId = 7L), FvpFlowStep.CONDITIONS)
        fun decide(
            rowId: Long?,
            athleteId: Long?,
            athleteExists: Boolean = true,
            athleteArchived: Boolean = false,
        ) = fvpRecoveredStateReconciliation(state, rowId, athleteId, athleteExists, athleteArchived)

        // 1. Fila con el MISMO dueño -> ya esta guardada (nada que recuperar).
        assertEquals(FvpRecoveryReconciliation.ALREADY_SAVED, decide(11L, 7L))
        // Precedencia 1 sobre 3: aunque el atleta este archivado o ya no exista, la serie esta
        // guardada y el atleta NO cambia esa verdad.
        assertEquals(FvpRecoveryReconciliation.ALREADY_SAVED, decide(11L, 7L, athleteExists = false))
        assertEquals(FvpRecoveryReconciliation.ALREADY_SAVED, decide(11L, 7L, athleteArchived = true))
        assertEquals(
            "la fila manda incluso con atleta ausente Y archivado a la vez",
            FvpRecoveryReconciliation.ALREADY_SAVED,
            decide(11L, 7L, athleteExists = false, athleteArchived = true),
        )

        // 2. Fila con OTRO dueño -> conflicto de sessionKey (nunca se llama a `save` con la
        // identidad recuperada) y manda sobre el estado del atleta.
        assertEquals(FvpRecoveryReconciliation.MISMATCHED_OWNER, decide(11L, 8L))
        assertEquals(
            FvpRecoveryReconciliation.MISMATCHED_OWNER,
            decide(11L, 8L, athleteExists = false, athleteArchived = true),
        )
        // Dueño persistido DESCONOCIDO (`null`) no es coincidencia.
        assertEquals(FvpRecoveryReconciliation.MISMATCHED_OWNER, decide(11L, null))

        // 3. Sin fila y atleta ausente o archivado -> sesion no recuperable.
        assertEquals(FvpRecoveryReconciliation.ATHLETE_GONE, decide(null, null, athleteExists = false))
        assertEquals(FvpRecoveryReconciliation.ATHLETE_GONE, decide(null, null, athleteArchived = true))
        assertEquals(
            "archivado manda aunque el atleta exista",
            FvpRecoveryReconciliation.ATHLETE_GONE,
            decide(null, null, athleteExists = true, athleteArchived = true),
        )

        // 4. Sin fila y atleta activo -> unica salida que autoriza a adoptar.
        assertEquals(FvpRecoveryReconciliation.ADOPT, decide(null, null))
        assertEquals(
            "un `persistedAthleteId` sin fila no fabrica un conflicto de dueño inexistente",
            FvpRecoveryReconciliation.ADOPT,
            decide(null, 99L),
        )

        // Identidad recuperada INUTILIZABLE (`athleteId <= 0`, que el codec acepta): no es
        // adoptable nunca, ni puede declarar "ya guardada" una fila con dueño 0.
        val noOwner = FvpRecoveryState(recoveredDraft(athleteId = 0L), FvpFlowStep.SETUP)
        assertEquals(
            FvpRecoveryReconciliation.ATHLETE_GONE,
            fvpRecoveredStateReconciliation(noOwner, null, null, athleteExists = true, athleteArchived = false),
        )
        assertEquals(
            FvpRecoveryReconciliation.MISMATCHED_OWNER,
            fvpRecoveredStateReconciliation(noOwner, 11L, 0L, athleteExists = true, athleteArchived = false),
        )
    }

    // --- R3: el dueño consulta la verdad persistida ANTES de adoptar ----------------------------

    /**
     * Regresion de R2: un estado que reconcilia a ADOPT se adopta igual que antes, incluido un
     * borrador PARCIAL, y el resto del contrato de R2 sigue en pie (el payload restaurado no se
     * reescribe, un cambio del borrador vivo lo re-codifica con el paso actual y una salida terminal
     * lo limpia).
     */
    @Test
    fun adoptStillBehavesAsR2IncludingPartialDrafts() {
        // Borrador recien confirmado en el paso 1: masa y h_po, sin condiciones (parcial legitimo).
        val draft = recoveredDraft(sessionKey = "session-adopt", athleteId = 7L, bodyMassKg = 78.4)
        val payload = requireNotNull(FvpDraftStateCodec.encode(FvpRecoveryState(draft, FvpFlowStep.SETUP)))
        var lookups = 0
        val counting = FvpPersistedTruthSource { lookupDraft ->
            lookups++
            assertEquals("la consulta se hace por el borrador recuperado", "session-adopt", lookupDraft.sessionKey)
            FvpPersistedTruth(persistedRowId = null, persistedAthleteId = null, athleteExists = true, athleteArchived = false)
        }

        val owner = ownerWith(payload = payload, truth = counting)

        assertEquals("ADOPT consulta la verdad persistida UNA vez", 1, lookups)
        assertEquals("el borrador se adopta", "session-adopt", FvpDraftSession.draft.value?.sessionKey)
        assertEquals("se adopta TAL CUAL (masa)", 78.4, FvpDraftSession.draft.value?.bodyMassKg!!, 0.0)
        assertEquals("sin aviso: no hay nada que avisar", FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
        assertEquals("el payload del estado recien restaurado no se reescribe", payload, owner.payload)
        assertFalse("y sigue siendo el payload vigente", owner.payloadInvalidated)

        // Cambio del borrador vivo + paso nuevo: el payload se re-codifica con el paso ACTUAL.
        owner.viewModel.onStepVisible(FvpFlowStep.RESULT)
        FvpDraftSession.update { it.copy(notes = "nota viva") }
        val stored = requireNotNull(owner.payload)
        assertNotEquals("un cambio del borrador vivo SI reescribe el payload", payload, stored)
        val storedState = requireNotNull(FvpDraftStateCodec.decode(stored))
        assertEquals("nota viva", storedState.draft.notes)
        assertEquals("el paso viaja en el payload", FvpFlowStep.RESULT, storedState.step)

        // Salida terminal (reset del borrador): el payload deja de conservarse.
        FvpDraftSession.reset()
        assertNull("una salida terminal limpia el payload", owner.payload)
    }

    /**
     * Fila con ESE `sessionKey` y el MISMO dueño: la verdad persistida manda. El borrador NO se
     * adopta y el payload se limpia (conservarlo solo repetiria la misma reconciliacion).
     */
    @Test
    fun alreadySavedRowIsNotAdoptedAndThePayloadIsCleared() {
        val owner = ownerWith(
            payload = advPayload("session-already-saved"),
            truth = truthSource(persistedRowId = 42L, persistedAthleteId = 7L),
        )

        assertNull("la verdad persistida manda: NO se adopta", FvpDraftSession.draft.value)
        assertNull("el payload se limpia", owner.payload)
        assertEquals(
            "sin copy honesto disponible no se publica un aviso falso (nota de copy de FvpRecoveryNotice)",
            FvpRecoveryNotice.None,
            owner.viewModel.recoveryNotice.value,
        )
    }

    /** Atleta ausente o archivado (sin fila): no se adopta, se avisa y el payload se limpia. */
    @Test
    fun athleteGoneOrArchivedIsNotAdoptedAndThePayloadIsCleared() {
        val gone = ownerWith(
            payload = advPayload("session-athlete-gone"),
            truth = truthSource(athleteExists = false),
        )
        assertNull("atleta ausente: NO se adopta", FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, gone.viewModel.recoveryNotice.value)
        assertNull("atleta ausente: el payload se limpia", gone.payload)

        val archived = ownerWith(
            payload = advPayload("session-athlete-archived"),
            truth = truthSource(athleteExists = true, athleteArchived = true),
        )
        assertNull("atleta archivado: NO se adopta", FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, archived.viewModel.recoveryNotice.value)
        assertNull("atleta archivado: el payload se limpia", archived.payload)
    }

    /**
     * Fila con ese `sessionKey` pero OTRO dueño: el `save` con la identidad recuperada lanzaria por
     * conflicto de `sessionKey`, asi que la sesion no se recupera (no se adopta, se avisa, se limpia).
     */
    @Test
    fun mismatchedOwnerIsNotAdoptedAndThePayloadIsCleared() {
        val owner = ownerWith(
            payload = advPayload("session-mismatched-owner"),
            truth = truthSource(persistedRowId = 42L, persistedAthleteId = 8L),
        )

        assertNull("dueño distinto: NO se adopta", FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, owner.viewModel.recoveryNotice.value)
        assertNull("dueño distinto: el payload se limpia", owner.payload)
    }

    /**
     * FALLO CERRADO: si la verdad persistida no se puede LEER, no se adopta y se dice; el payload se
     * CONSERVA porque un fallo de lectura no dictamina nada sobre el borrador.
     */
    @Test
    fun lookupFailureFailsClosedAndKeepsThePayload() {
        val payload = advPayload("session-lookup-fails")
        val failing = FvpPersistedTruthSource { error("base no disponible") }

        val owner = ownerWith(payload = payload, truth = failing)

        assertNull("sin verdad persistida NO se adopta", FvpDraftSession.draft.value)
        assertEquals("se dice que no hay recuperacion", FvpRecoveryNotice.Unrecoverable, owner.viewModel.recoveryNotice.value)
        assertEquals("el payload se conserva intacto", payload, owner.payload)
        assertFalse(
            "el fallo de lectura NO invalida el candidato: sigue siendo un payload vigente",
            owner.payloadInvalidated,
        )

        // Y por eso el MISMO estado guardado SI se recupera cuando la verdad se puede leer: el
        // fallo anterior no muta el candidato (no es un veredicto sobre el borrador).
        val recovered = reconstructAfterDeath(owner, truthSource())
        assertEquals("session-lookup-fails", FvpDraftSession.draft.value?.sessionKey)
        assertEquals(FvpRecoveryNotice.None, recovered.viewModel.recoveryNotice.value)
        assertFalse(recovered.payloadInvalidated)
    }

    /** Fallo cerrado tambien cuando la lectura se queda colgada: la cota agotada no adopta. */
    @Test
    fun lookupTimeoutFailsClosedAndKeepsThePayload() {
        val payload = advPayload("session-lookup-timeout")
        val neverFinishes = CompletableDeferred<FvpPersistedTruth?>()
        val hanging = FvpPersistedTruthSource { neverFinishes.await() }

        val owner = ownerWith(payload = payload, truth = hanging, timeoutMs = 25L)

        assertTrue("A3: la consulta arranca PENDIENTE y sin bloquear el constructor", owner.viewModel.reconciliationPending.value)
        assertNull("mientras esta pendiente no se adopta nada", FvpDraftSession.draft.value)
        // La cota la cumple el temporizador, que puede volver por otro hilo: se espera de forma acotada.
        awaitReconciliationResolved(owner)

        assertNull("la cota agotada NO adopta", FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, owner.viewModel.recoveryNotice.value)
        assertEquals("la cota agotada conserva el payload", payload, owner.payload)
        assertFalse(
            "la cota agotada tampoco invalida el candidato",
            owner.payloadInvalidated,
        )

        val recovered = reconstructAfterDeath(owner, truthSource())
        assertEquals("session-lookup-timeout", FvpDraftSession.draft.value?.sessionKey)
        assertEquals(FvpRecoveryNotice.None, recovered.viewModel.recoveryNotice.value)
    }

    /**
     * Sin payload no hay nada que reconciliar: el dueño NO consulta la base (nada que adoptar y
     * ningun aviso). Regresion del camino [FvpRecoverPlan.Nothing] de R2.
     */
    @Test
    fun withoutPayloadTheTruthIsNotConsultedAndNothingIsAdopted() {
        var lookups = 0
        val counting = FvpPersistedTruthSource { lookups++; FvpPersistedTruth() }

        val owner = ownerWith(payload = null, truth = counting)

        assertEquals("sin payload no se consulta la verdad persistida", 0, lookups)
        assertNull(FvpDraftSession.draft.value)
        assertNull(owner.payload)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
    }

    /**
     * Regresion de R2: un estado que NO revalida se rechaza ANTES de reconciliar (no se consulta la
     * verdad persistida) y el payload se conserva.
     */
    @Test
    fun unadoptableStateIsRefusedBeforeReconcilingAndKeepsThePayload() {
        val noMass = FvpRecoveryState(recoveredDraft(sessionKey = "session-no-mass", athleteId = 7L, bodyMassKg = null), FvpFlowStep.SETUP)
        val payload = requireNotNull(FvpDraftStateCodec.encode(noMass))
        var lookups = 0
        val counting = FvpPersistedTruthSource { lookups++; FvpPersistedTruth(athleteExists = true) }

        val owner = ownerWith(payload = payload, truth = counting)

        assertEquals("un estado que no revalida no llega a la reconciliacion", 0, lookups)
        assertNull(FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, owner.viewModel.recoveryNotice.value)
        assertEquals("el payload anterior se conserva intacto", payload, owner.payload)
    }

    // --- A1: marca de vigencia del payload retenido ----------------------------------------------

    /**
     * A1/D6 (caso del plan "oversized con aviso, NO truncado ni resurreccion del viejo"): un cambio
     * del borrador VIVO de la MISMA identidad que el codec rechaza por presupuesto conserva los bytes
     * anteriores EXACTOS, pero los deja NO vigentes. La reconstruccion posterior no los decodifica ni
     * los adopta: se conservan como material inerte, nunca como estado recuperable.
     */
    @Test
    fun refusedOversizedEditKeepsThePreviousBytesInertAndIsNeverAdoptedAfterReconstruction() {
        val owner = ownerWith(payload = null, truth = truthSource())

        // Serie viva (misma sessionKey) con un payload vigente...
        FvpDraftSession.begin(athleteId = 7L)
        FvpDraftSession.update { it.copy(bodyMassKg = 80.0) }
        val storedPayload = requireNotNull(owner.payload)
        assertFalse("un payload completo y revalidado queda vigente", owner.payloadInvalidated)

        // ...y un cambio del MISMO borrador que el codec rechaza por tamano.
        val refused = requireNotNull(FvpDraftSession.update { it.copy(notes = "x".repeat(70_000)) })
        assertTrue(
            "control: el estado rechazado no es almacenable",
            fvpDraftPayloadUpdate(FvpRecoveryState(refused, FvpFlowStep.SETUP)) is FvpDraftPayloadUpdate.KeepPrevious,
        )
        assertTrue("el payload anterior queda NO vigente", owner.payloadInvalidated)
        assertEquals("pero sus bytes quedan EXACTAMENTE como estaban (D6)", storedPayload, owner.payload)
        assertSame("el rechazo NO toca el borrador vivo", refused, FvpDraftSession.draft.value)
        assertEquals(
            "y se avisa de que este borrador no tiene recuperacion",
            FvpRecoveryNotice.RefusedToStore(FvpRecoveryRefusal.TOO_LARGE),
            owner.viewModel.recoveryNotice.value,
        )

        val restored = reconstructAfterDeath(owner, truthSource())
        assertTrue(
            "control: el JSON retenido SI seria adoptable si estuviera vigente",
            fvpRecoverPlan(storedPayload) is FvpRecoverPlan.Restored,
        )
        assertNull("el payload inerte NO se adopta al reconstruir", FvpDraftSession.draft.value)
        assertEquals("se dice que no hay recuperacion", FvpRecoveryNotice.Unrecoverable, restored.viewModel.recoveryNotice.value)
        assertEquals("los bytes inertes siguen ahi, intactos", storedPayload, restored.payload)
        assertTrue("y siguen marcados como no vigentes", restored.payloadInvalidated)
    }

    /**
     * A1/F2: un rechazo del borrador vivo de una identidad NUEVA no puede dejar recuperable el
     * payload de la serie ANTERIOR (identidad ajena). Los bytes se conservan inertes y la
     * reconstruccion no adopta la serie vieja en lugar de la nueva.
     */
    @Test
    fun refusedNewIdentityEditDoesNotResurrectThePreviousSeriesAfterReconstruction() {
        val owner = ownerWith(payload = null, truth = truthSource())
        FvpDraftSession.begin(athleteId = 7L)
        FvpDraftSession.update { it.copy(bodyMassKg = 80.0) }
        val previousSeriesPayload = requireNotNull(owner.payload)
        val previousSeriesKey = requireNotNull(FvpDraftStateCodec.decode(previousSeriesPayload)).draft.sessionKey
        assertFalse(owner.payloadInvalidated)

        // Serie NUEVA (otra `sessionKey`) todavia sin masa: el codec la rechaza y NO se guarda.
        val newDraft = FvpDraftSession.begin(athleteId = 9L)
        assertNotEquals("la identidad nueva tiene su propia sessionKey", previousSeriesKey, newDraft.sessionKey)
        assertEquals(
            "misma regla de rechazo que la restauracion (simetria)",
            FvpDraftPayloadUpdate.KeepPrevious(FvpRecoveryRefusal.NOT_RECOVERABLE),
            fvpDraftPayloadUpdate(FvpRecoveryState(newDraft, FvpFlowStep.SETUP)),
        )
        assertTrue("el payload de la serie ajena queda NO vigente", owner.payloadInvalidated)
        assertEquals("sus bytes siguen ahi, intactos", previousSeriesPayload, owner.payload)
        assertEquals("el borrador vivo sigue siendo el nuevo", newDraft, FvpDraftSession.draft.value)

        val restored = reconstructAfterDeath(owner, truthSource())
        assertNull("la serie AJENA no se adopta al reconstruir", FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, restored.viewModel.recoveryNotice.value)
        assertEquals("los bytes de la serie ajena quedan inertes", previousSeriesPayload, restored.payload)
        assertTrue(restored.payloadInvalidated)
    }

    /**
     * A1: tras un rechazo, una edicion que SI es almacenable vuelve a habilitar la recuperacion con
     * el contenido COMPLETO y ACTUAL (nunca reactivando los bytes viejos), y esa recuperacion
     * funciona de verdad en un dueño reconstruido.
     */
    @Test
    fun aValidEditAfterARefusalReEnablesRecoveryWithTheCurrentContent() {
        val owner = ownerWith(payload = null, truth = truthSource())
        FvpDraftSession.begin(athleteId = 7L)
        FvpDraftSession.update { it.copy(bodyMassKg = 80.0) }
        val refusedPayload = requireNotNull(owner.payload)
        FvpDraftSession.update { it.copy(notes = "x".repeat(70_000)) }
        assertTrue(owner.payloadInvalidated)

        val fixed = requireNotNull(FvpDraftSession.update { it.copy(notes = "nota corta") })
        assertFalse("un borrador completo vuelve a ser recuperable", owner.payloadInvalidated)
        val payload = requireNotNull(owner.payload)
        assertNotEquals("no se reactivan los bytes viejos", refusedPayload, payload)
        val decoded = requireNotNull(FvpDraftStateCodec.decode(payload))
        assertEquals("el payload vigente es el contenido ACTUAL", fixed.sessionKey, decoded.draft.sessionKey)
        assertEquals("nota corta", decoded.draft.notes)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)

        val restored = reconstructAfterDeath(owner, truthSource())
        assertEquals(fixed.sessionKey, FvpDraftSession.draft.value?.sessionKey)
        assertEquals("nota corta", FvpDraftSession.draft.value?.notes)
        assertEquals(FvpRecoveryNotice.None, restored.viewModel.recoveryNotice.value)
        assertFalse(restored.payloadInvalidated)
    }

    /**
     * A1: la salida TERMINAL (guardado confirmado / descarte / borrado = `reset` del borrador vivo)
     * descarta los bytes anteriores Y la marca. Es la unica salida que tira el payload retenido.
     */
    @Test
    fun terminalResetRemovesBothTheRetainedBytesAndTheInvalidationMarker() {
        val owner = ownerWith(payload = null, truth = truthSource())
        FvpDraftSession.begin(athleteId = 7L)
        FvpDraftSession.update { it.copy(bodyMassKg = 80.0) }
        FvpDraftSession.update { it.copy(notes = "x".repeat(70_000)) }
        assertNotNull(owner.payload)
        assertTrue(owner.payloadInvalidated)

        FvpDraftSession.reset()

        assertNull("los bytes anteriores se descartan en la salida terminal", owner.payload)
        assertFalse("y con ellos desaparece la marca", owner.payloadInvalidated)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
    }

    /**
     * A1: la marca PRECEDE al decode. Con bytes retenidos y marca activa no se decodifica, no se
     * revalida (control: el JSON SI seria adoptable) y no se reconcilia: cero consultas a la base.
     */
    @Test
    fun invalidatedPayloadIsNotDecodedNorReconciledEvenWhenItWouldBeAdoptable() {
        val payload = advPayload("session-inert")
        var lookups = 0
        val counting = FvpPersistedTruthSource {
            lookups++
            FvpPersistedTruth(athleteExists = true)
        }

        val owner = ownerWith(payload = payload, truth = counting, invalidated = true)

        assertTrue(
            "control: el JSON retenido seria adoptable si estuviera vigente",
            fvpRecoverPlan(payload) is FvpRecoverPlan.Restored,
        )
        assertEquals("la marca precede al decode: no se consulta la verdad persistida", 0, lookups)
        assertNull("y no se adopta nada", FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, owner.viewModel.recoveryNotice.value)
        assertEquals("los bytes inertes se conservan", payload, owner.payload)
        assertTrue(owner.payloadInvalidated)
    }

    /**
     * A1: una marca activa SIN bytes no neutraliza nada y no es un fallo: no habia recuperacion que
     * perder, asi que no se publica un aviso falso (el aviso se reserva para cuando hay bytes
     * retenidos que no se pueden usar).
     */
    @Test
    fun invalidationMarkerWithoutRetainedBytesIsNotAFailure() {
        val owner = ownerOn(SavedStateHandle(mapOf(FVP_RECOVERY_INVALIDATED_KEY to true)), truthSource())

        assertNull(owner.payload)
        assertEquals(
            "sin bytes no se inventa un aviso",
            FvpRecoveryNotice.None,
            owner.viewModel.recoveryNotice.value,
        )
        assertTrue("y la marca sin bytes no cambia el resto del contrato", owner.payloadInvalidated)
    }

    /**
     * F5 de la revision de R3 (sustituye a la prueba tautologica del helper identidad): la limpieza
     * al volver a Home se comprueba sobre el DUEÑO real y su `SavedStateHandle`.
     *
     * - Primer Home observado (arranque / restauracion de la pila con Home delante): el candidato
     *   restaurado NO se limpia ni se invalida.
     * - Tras ver un paso REAL del flujo: salir a Home SI limpia bytes y marca, sin tocar el borrador
     *   vivo (resetearlo es responsabilidad de los destinos).
     */
    @Test
    fun initialHomeObservationKeepsTheRestoredCandidateWhileARealExitClearsIt() {
        val payload = advPayload("session-left-flow")
        val owner = ownerWith(payload = payload, truth = truthSource())
        assertEquals("control: el candidato se ha adoptado", "session-left-flow", FvpDraftSession.draft.value?.sessionKey)

        // (a) Home es la PRIMERA ruta observada: no ha habido ningun paso del flujo.
        owner.viewModel.onLeftFlow()
        assertEquals("un primer Home no es una salida del flujo", payload, owner.payload)
        assertFalse("ni invalida el candidato", owner.payloadInvalidated)
        assertEquals(
            "ni toca el borrador restaurado",
            "session-left-flow",
            FvpDraftSession.draft.value?.sessionKey,
        )

        // (b) Un paso REAL del flujo y un cambio del borrador: ahora salir a Home si limpia.
        owner.viewModel.onStepVisible(FvpFlowStep.SETUP)
        FvpDraftSession.update { it.copy(notes = "nota viva") }
        assertNotNull("con un paso visto el payload vuelve a estar vigente", owner.payload)
        assertFalse(owner.payloadInvalidated)

        owner.viewModel.onLeftFlow()
        assertNull("una salida REAL del flujo limpia el payload", owner.payload)
        assertFalse("y retira la marca", owner.payloadInvalidated)
        assertNotNull("sin tocar el borrador vivo (lo resetean los destinos)", FvpDraftSession.draft.value)
    }

    // --- A3: reconciliacion ASINCRONA (pendiente es un estado de primera clase) ------------------

    /**
     * A3: el constructor NO bloquea. Publica PENDIENTE de forma sincrona, no adopta nada hasta que
     * llega la verdad y, al llegar, aplica la adopcion. `onStepVisible` durante la espera no
     * reescribe el candidato (el payload es la unica copia de la serie que se esta restaurando).
     */
    @Test
    fun constructorReturnsPendingAndAdoptsOnlyWhenTheTruthArrives() {
        val payload = advPayload("session-pending")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        val owner = ownerWith(payload = payload, truth = FvpPersistedTruthSource { gate.await() })

        assertTrue(
            "el constructor publica PENDIENTE de forma sincrona (sin bloquearse)",
            owner.viewModel.reconciliationPending.value,
        )
        assertNull("y todavia no adopta nada", FvpDraftSession.draft.value)
        assertEquals("sin aviso mientras la verdad esta en vuelo", FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
        assertEquals("el candidato sigue intacto", payload, owner.payload)
        assertFalse("y sigue vigente", owner.payloadInvalidated)

        owner.viewModel.onStepVisible(FvpFlowStep.CONDITIONS)
        assertEquals("un paso visible NO reescribe el candidato pendiente", payload, owner.payload)
        assertTrue(owner.viewModel.reconciliationPending.value)

        gate.complete(FvpPersistedTruth(athleteExists = true))
        awaitReconciliationResolved(owner)

        assertEquals("al llegar la verdad se adopta el candidato", "session-pending", FvpDraftSession.draft.value?.sessionKey)
        assertEquals("sin aviso: no hay nada que avisar", FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
        assertEquals("y el payload adoptado no se reescribe", payload, owner.payload)
    }

    /**
     * A3: una reconciliacion PENDIENTE que resuelve a un no-ADOPT aplica sus efectos SOLO cuando
     * llega la verdad: hasta entonces el candidato sigue retenido y el borrador no se adopta.
     */
    @Test
    fun pendingResolutionAppliesAlreadySavedOnlyWhenTheTruthArrives() {
        val payload = advPayload("session-pending-saved")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        val owner = ownerWith(payload = payload, truth = FvpPersistedTruthSource { gate.await() })

        assertTrue(owner.viewModel.reconciliationPending.value)
        assertEquals("el candidato sigue retenido mientras la verdad esta en vuelo", payload, owner.payload)
        assertNull(FvpDraftSession.draft.value)

        gate.complete(FvpPersistedTruth(persistedRowId = 42L, persistedAthleteId = 7L))
        awaitReconciliationResolved(owner)

        assertNull("la verdad persistida manda: NO se adopta", FvpDraftSession.draft.value)
        assertNull("y ahora si se limpia el payload", owner.payload)
        assertEquals(
            "sin copy honesto disponible no se publica un aviso falso",
            FvpRecoveryNotice.None,
            owner.viewModel.recoveryNotice.value,
        )
    }

    /**
     * A3: el reset es un descarte EXPLICITO y supersede la reconciliacion de forma SINCRONA incluso
     * cuando la sesion viva ya era `null` (un colector de `StateFlow` no emite en ese caso). Una
     * fuente NO cooperativa que entrega su verdad despues no aplica ningun efecto obsoleto.
     */
    @Test
    fun explicitResetSupersedesAPendingLookupEvenWithNoLiveDraftAndALateSourceHasNoEffect() {
        val payload = advPayload("session-superseded")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        // NO cooperativa: espera dentro de `NonCancellable`, de modo que la cancelacion por si sola
        // no es lo que impide el efecto.
        val nonCooperative = FvpPersistedTruthSource { withContext(NonCancellable) { gate.await() } }
        val owner = ownerWith(payload = payload, truth = nonCooperative)

        assertNull("sin sesion viva", FvpDraftSession.draft.value)
        assertTrue(owner.viewModel.reconciliationPending.value)

        owner.viewModel.reset()

        assertFalse(
            "el reset resuelve el pendiente de forma sincrona (no depende de un colector)",
            owner.viewModel.reconciliationPending.value,
        )
        assertNull("y descarta el candidato", owner.payload)
        assertFalse("sin dejar marca inerte", owner.payloadInvalidated)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)

        gate.complete(FvpPersistedTruth(athleteExists = true))
        awaitQuiet(owner)

        assertNull("la resolucion OBSOLETA no adopta nada", FvpDraftSession.draft.value)
        assertNull("ni resucita el payload", owner.payload)
        assertEquals("ni publica aviso", FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
        assertFalse(owner.viewModel.reconciliationPending.value)
    }

    /**
     * A2/A3: una mutacion externa por el DUEÑO supersede la reconciliacion pendiente y guarda de
     * inmediato el estado COMPLETO actual (o deja los bytes anteriores inertes). `adopt` instala el
     * borrador y su payload vigente; una resolucion tardia no lo toca.
     */
    @Test
    fun adoptWhilePendingSupersedesTheLookupAndStoresTheCompleteCurrentDraft() {
        val payload = advPayload("session-pending-adopt")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        val owner = ownerWith(payload = payload, truth = FvpPersistedTruthSource { gate.await() })
        assertTrue(owner.viewModel.reconciliationPending.value)

        val draft = FvpDraft(sessionKey = "session-new", athleteId = 4L, bodyMassKg = 81.0, commonPushOffM = 0.4)
        owner.viewModel.adopt(draft)

        assertFalse("la mutacion resuelve el pendiente", owner.viewModel.reconciliationPending.value)
        assertSame("el borrador vivo es el adoptado", draft, FvpDraftSession.draft.value)
        val stored = requireNotNull(FvpDraftStateCodec.decode(requireNotNull(owner.payload)))
        assertEquals("el payload vigente es el estado COMPLETO actual", "session-new", stored.draft.sessionKey)
        assertEquals("con su masa", 81.0, stored.draft.bodyMassKg!!, 0.0)
        assertFalse("y queda vigente", owner.payloadInvalidated)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)

        gate.complete(FvpPersistedTruth(athleteExists = true))
        awaitQuiet(owner)

        assertSame("la resolucion obsoleta no cambia el borrador vivo", draft, FvpDraftSession.draft.value)
        assertEquals("ni adopta el candidato viejo", "session-new", owner.payload?.let { requireNotNull(FvpDraftStateCodec.decode(it)).draft.sessionKey })
    }

    /**
     * A2/A3: `begin`/`update` por el DUEÑO. `update` sin sesion viva no aplica nada y deja la
     * recuperacion sin contenido (el estado actual es `null`); `begin`+`update` con contenido
     * valido escriben el payload vigente del borrador actual; una resolucion tardia no lo toca.
     */
    @Test
    fun ownerBeginAndUpdateSupersedeThePendingLookupAndStoreTheCurrentState() {
        val payload = advPayload("session-pending-update")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        val owner = ownerWith(payload = payload, truth = FvpPersistedTruthSource { gate.await() })

        // (a) update SIN borrador vivo: la sesion no cambia y no se conserva recuperacion.
        assertNull("sin sesion viva el update no aplica nada", owner.viewModel.update { it.copy(notes = "x") })
        assertFalse("pero SI supersede el pendiente", owner.viewModel.reconciliationPending.value)
        assertNull("sin borrador vivo no se conserva un payload", owner.payload)
        assertFalse(owner.payloadInvalidated)

        // (b) begin + update por el dueño: el payload vigente es el estado COMPLETO actual.
        owner.viewModel.begin(athleteId = 9L)
        val updated = requireNotNull(owner.viewModel.update { it.copy(bodyMassKg = 79.5) })
        assertSame("el update devuelve el borrador actualizado", updated, FvpDraftSession.draft.value)
        val stored = requireNotNull(FvpDraftStateCodec.decode(requireNotNull(owner.payload)))
        assertEquals(updated.sessionKey, stored.draft.sessionKey)
        assertEquals("con el contenido ACTUAL", 79.5, stored.draft.bodyMassKg!!, 0.0)
        assertFalse(owner.payloadInvalidated)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)

        gate.complete(FvpPersistedTruth(athleteExists = true))
        awaitQuiet(owner)

        assertSame("una resolucion obsoleta no toca el borrador vivo", updated, FvpDraftSession.draft.value)
        assertEquals("ni su payload vigente", updated.sessionKey, requireNotNull(FvpDraftStateCodec.decode(requireNotNull(owner.payload))).draft.sessionKey)
    }

    /**
     * A3: la cancelacion de ciclo de vida (dueño limpiado: `viewModelScope` cancelado) NO se
     * reinterpreta como verdad ausente: no publica aviso, no adopta, no toca ni invalida el
     * candidato. Una fuente que entrega su verdad despues tampoco aplica nada.
     */
    @Test
    fun lifecycleCancellationIsNotReinterpretedAsMissingPersistedTruth() {
        val payload = advPayload("session-cancelled")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        val owner = ownerWith(payload = payload, truth = FvpPersistedTruthSource { gate.await() })
        assertTrue(owner.viewModel.reconciliationPending.value)

        // Muerte del dueño: `ViewModelStore.clear()` cancela el `viewModelScope` (multa la corrutina).
        clearOwners()

        assertEquals(
            "la cancelacion NO publica fallo cerrado",
            FvpRecoveryNotice.None,
            owner.viewModel.recoveryNotice.value,
        )
        assertNull("ni adopta nada", FvpDraftSession.draft.value)
        assertEquals("ni toca el candidato", payload, owner.payload)
        assertFalse("ni lo invalida", owner.payloadInvalidated)

        gate.complete(FvpPersistedTruth(athleteExists = true))
        assertNull("una entrega posterior a la cancelacion tampoco adopta", FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
        assertEquals("y el candidato sigue intacto", payload, owner.payload)
    }

    /**
     * A3: la salida REAL del flujo durante una reconciliacion pendiente supersede la consulta (el
     * candidato ya no se recupera) y una entrega tardia no aplica NINGUN efecto. La PRIMERA
     * observacion de Home no es una salida y conserva el candidato.
     */
    @Test
    fun realExitWhilePendingSupersedesTheLookupAndALateSourceHasNoEffect() {
        val payload = advPayload("session-pending-left")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        val nonCooperative = FvpPersistedTruthSource { withContext(NonCancellable) { gate.await() } }
        val owner = ownerWith(payload = payload, truth = nonCooperative)
        assertTrue(owner.viewModel.reconciliationPending.value)

        owner.viewModel.onLeftFlow()
        assertEquals("un primer Home no es una salida: el candidato sigue ahi", payload, owner.payload)
        assertTrue("ni resuelve la reconciliacion pendiente", owner.viewModel.reconciliationPending.value)

        owner.viewModel.onStepVisible(FvpFlowStep.SETUP)
        owner.viewModel.onLeftFlow()

        assertFalse("la salida REAL supersede la reconciliacion", owner.viewModel.reconciliationPending.value)
        assertNull("y limpia el payload", owner.payload)
        assertFalse(owner.payloadInvalidated)

        gate.complete(FvpPersistedTruth(athleteExists = true))
        awaitQuiet(owner)

        assertNull("la resolucion OBSOLETA no adopta nada", FvpDraftSession.draft.value)
        assertNull("ni resucita el payload", owner.payload)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
    }

    /**
     * A3: la GUARDA de obsolescencia es pura y cubre cualquier cambio de generacion (no solo el
     * avance), que es lo que hace que una resolucion capturada antes de una mutacion no aplique
     * NINGUN efecto aunque la fuente no respete la cancelacion.
     */
    @Test
    fun staleResolutionGuardRejectsAnyGenerationChange() {
        assertFalse("la misma generacion no es obsoleta", fvpReconciliationIsStale(0L, 0L))
        assertFalse(fvpReconciliationIsStale(7L, 7L))
        assertTrue("una mutacion posterior la invalida", fvpReconciliationIsStale(0L, 1L))
        assertTrue(fvpReconciliationIsStale(3L, 4L))
        assertTrue("y cualquier valor distinto tambien", fvpReconciliationIsStale(4L, 3L))
    }

    // --- A3/A2: costuras del plan 13 (ciclo de vida, sesion viva y orden de publicacion) ---------

    /**
     * COSTURA 1 (A3): un dueño LIMPIADO no aplica NINGUN efecto aunque la fuente sea NO cooperativa
     * y entregue su verdad TARDE. `ViewModelStore.clear()` cancela el `viewModelScope` pero NO cambia
     * la GENERACION, de modo que la guarda de obsolescencia no puede decidir aqui: lo que garantiza
     * "cero efectos" es comprobar la VIDA de la corrutina antes de cualquier efecto. La verdad tardia
     * de esta prueba seria ADOPT (adoptaria el candidato si llegase a aplicarse).
     */
    @Test
    fun clearedOwnerAppliesNoEffectWhenANonCooperativeSourceDeliversTheTruthLate() {
        val payload = advPayload("session-cleared-late")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        // NO cooperativa: espera dentro de `NonCancellable`, de modo que la cancelacion por si sola
        // no interrumpe su espera ni su entrega.
        val nonCooperative = FvpPersistedTruthSource { withContext(NonCancellable) { gate.await() } }
        val owner = ownerWith(payload = payload, truth = nonCooperative)
        assertTrue(owner.viewModel.reconciliationPending.value)

        // Muerte del dueño: el `viewModelScope` se cancela, pero la fuente sigue dentro de
        // `NonCancellable` y su verdad (ADOPT) llega DESPUES.
        clearOwners()

        gate.complete(FvpPersistedTruth(athleteExists = true))
        Thread.sleep(100)

        assertNull("un dueño limpiado NO adopta nada con la verdad tardia", FvpDraftSession.draft.value)
        assertEquals("ni publica aviso", FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
        assertEquals("ni toca el candidato", payload, owner.payload)
        assertFalse("ni lo invalida", owner.payloadInvalidated)
    }

    /**
     * COSTURA 1 (A3), variante del FALLO CERRADO: si la fuente se come la cota esperando dentro de
     * `NonCancellable`, la cota no puede cerrar la consulta mientras la fuente siga dentro; y si el
     * dueño muere ANTES de que la fuente devuelva su verdad, la resolucion (que llegaria como "cota
     * agotada" = fallo cerrado) NO puede aplicar el aviso sobre un dueño ya limpiado. La vida de la
     * corrutina se comprueba antes de cualquier efecto.
     */
    @Test
    fun clearedOwnerDoesNotPublishTheSwallowedTimeoutFailureOnceTheOwnerIsGone() {
        val payload = advPayload("session-cleared-timeout")
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        val hangsInsideNonCancellable = FvpPersistedTruthSource { withContext(NonCancellable) { gate.await() } }
        val owner = ownerWith(payload = payload, truth = hangsInsideNonCancellable, timeoutMs = 25L)
        assertTrue(owner.viewModel.reconciliationPending.value)

        // La cota se agota con la fuente todavia dentro de `NonCancellable`: no hay veredicto todavia
        // y el candidato sigue retenido.
        Thread.sleep(80)
        assertEquals(
            "la cota agotada no decide mientras la fuente siga dentro",
            FvpRecoveryNotice.None,
            owner.viewModel.recoveryNotice.value,
        )
        assertEquals("y el candidato sigue retenido", payload, owner.payload)

        // Muere el dueño ANTES de que la fuente devuelva su verdad: lo que se resuelva despues no
        // puede tocar su aviso (aqui seria el fallo cerrado de la cota).
        clearOwners()

        gate.complete(FvpPersistedTruth(athleteExists = true))
        Thread.sleep(100)

        assertEquals(
            "un dueño ya limpiado NO publica el fallo cerrado de la cota",
            FvpRecoveryNotice.None,
            owner.viewModel.recoveryNotice.value,
        )
        assertNull("ni adopta nada", FvpDraftSession.draft.value)
        assertEquals("ni toca el candidato", payload, owner.payload)
        assertFalse("ni lo invalida", owner.payloadInvalidated)
    }

    /**
     * COSTURA 2 (A2/A3): un dueño NUEVO puede nacer con un borrador VIVO en la sesion (Activity
     * nueva en un proceso que SOBREVIVIO). La SESION es la fuente de verdad: el payload retenido del
     * dueño anterior es material VIEJO, no se decodifica ni se reconcilia (cero consultas a la base)
     * y JAMAS se adopta por encima de lo vivo. La recuperabilidad se deriva de forma SINCRONA del
     * borrador ACTUAL COMPLETO, con la misma regla que cualquier cambio vivo.
     */
    @Test
    fun aLiveDraftWinsOverTheRetainedPayloadWithoutConsultingTheTruth() {
        val retainedPayload = advPayload("session-retained-a")
        var lookups = 0
        val counting = FvpPersistedTruthSource {
            lookups++
            FvpPersistedTruth(athleteExists = true)
        }

        // Sesion VIVA: serie B (atleta 9), identidad y contenido distintos del payload retenido (A).
        FvpDraftSession.begin(athleteId = 9L)
        val liveB = requireNotNull(FvpDraftSession.update { it.copy(bodyMassKg = 66.5, notes = "borrador vivo B") })
        assertNotEquals("control: B no es la identidad retenida", "session-retained-a", liveB.sessionKey)

        val owner = ownerWith(payload = retainedPayload, truth = counting)

        assertEquals("no hay candidato que reconciliar: la verdad persistida NO se consulta", 0, lookups)
        assertSame("la sesion viva sigue siendo B (no se adopta A)", liveB, FvpDraftSession.draft.value)
        assertFalse("sin nada pendiente", owner.viewModel.reconciliationPending.value)
        assertEquals("sin aviso", FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
        val stored = requireNotNull(FvpDraftStateCodec.decode(requireNotNull(owner.payload)))
        assertEquals("el payload vigente es el del borrador VIVO", liveB.sessionKey, stored.draft.sessionKey)
        assertEquals("con su contenido", "borrador vivo B", stored.draft.notes)
        assertEquals(66.5, stored.draft.bodyMassKg!!, 0.0)
        assertFalse("y queda vigente", owner.payloadInvalidated)

        // Y si el payload retenido venia marcado como NO vigente (rechazo del dueño anterior), la
        // sesion VIVA tambien manda: el payload se re-deriva de B y la marca se retira.
        val ownerAfterRefusal = ownerWith(payload = retainedPayload, truth = counting, invalidated = true)
        assertEquals("tampoco se consulta con la marca activa", 0, lookups)
        assertSame("la sesion viva sigue siendo B", liveB, FvpDraftSession.draft.value)
        assertEquals(
            "el payload vigente vuelve a ser el del borrador VIVO",
            liveB.sessionKey,
            requireNotNull(FvpDraftStateCodec.decode(requireNotNull(ownerAfterRefusal.payload))).draft.sessionKey,
        )
        assertFalse("y la marca queda retirada: lo vigente es el borrador VIVO", ownerAfterRefusal.payloadInvalidated)
        assertEquals(FvpRecoveryNotice.None, ownerAfterRefusal.viewModel.recoveryNotice.value)
    }

    @Test
    fun aNewOwnerDoesNotRearmAHomeAbandonedLiveDraftUntilTheFlowIsEnteredAgain() {
        val first = ownerWith(null, truthSource())
        val live = recoveredDraft(sessionKey = "session-left-home", athleteId = 7L, bodyMassKg = 80.0)
        first.viewModel.adopt(live)
        first.viewModel.onStepVisible(FvpFlowStep.CONDITIONS)
        assertNotNull(first.payload)
        first.viewModel.onLeftFlow()
        assertNull(first.payload)
        assertSame("Home conserva solo el borrador en memoria", live, FvpDraftSession.draft.value)

        val afterHome = first.handle.keys().associateWith { first.handle.get<Any?>(it) }
        clearOwners() // Activity nueva, proceso superviviente: NO reset del singleton.
        val next = ownerOn(SavedStateHandle(afterHome), truthSource())
        assertSame(live, FvpDraftSession.draft.value)
        assertNull("crear un dueño en Home no rearma el borrador abandonado", next.payload)
        assertFalse(next.payloadInvalidated)

        next.viewModel.onStepVisible(FvpFlowStep.CONDITIONS)
        val reentered = requireNotNull(FvpDraftStateCodec.decode(requireNotNull(next.payload)))
        assertEquals("solo la entrada explicita rearma la misma serie viva", live.sessionKey, reentered.draft.sessionKey)
        assertEquals(FvpFlowStep.CONDITIONS, reentered.step)

        next.viewModel.onLeftFlow()
        val afterSecondExit = next.handle.keys().associateWith { next.handle.get<Any?>(it) }
        clearOwners()
        FvpDraftSession.reset() // Ahora si: muerte de proceso simulada en JVM.
        val afterDeath = ownerOn(SavedStateHandle(afterSecondExit), truthSource())
        assertNull(afterDeath.payload)
        assertNull("sin payload no se resucita la serie abandonada", FvpDraftSession.draft.value)
    }

    /**
     * COSTURA 2 + A1: si el borrador VIVO no es almacenable, el dueño nuevo deriva el rechazo de
     * forma SINCRONA del estado ACTUAL: los bytes retenidos del dueño anterior se conservan EXACTOS
     * (D6) pero quedan NO vigentes (marca de A1), y la reconstruccion posterior no los adopta.
     */
    @Test
    fun aLiveDraftThatCannotBeStoredLeavesTheRetainedBytesInertAndNeverAdoptsThem() {
        val retainedPayload = advPayload("session-retained-inert")
        var lookups = 0
        val counting = FvpPersistedTruthSource {
            lookups++
            FvpPersistedTruth(athleteExists = true)
        }

        // Borrador VIVO sin masa: la revalidacion lo rechaza (misma regla que la restauracion), de
        // modo que el dueño nuevo no puede escribir un payload de ESTE estado.
        val liveNoMass = FvpDraftSession.begin(athleteId = 9L)
        assertNull("control: el borrador vivo no tiene masa", liveNoMass.bodyMassKg)

        val owner = ownerWith(payload = retainedPayload, truth = counting)

        assertEquals("la verdad persistida no se consulta", 0, lookups)
        assertSame("el borrador vivo no se toca", liveNoMass, FvpDraftSession.draft.value)
        assertEquals(
            "el rechazo se deriva del borrador ACTUAL",
            FvpRecoveryNotice.RefusedToStore(FvpRecoveryRefusal.NOT_RECOVERABLE),
            owner.viewModel.recoveryNotice.value,
        )
        assertEquals("los bytes retenidos quedan EXACTOS (D6)", retainedPayload, owner.payload)
        assertTrue("pero NO vigentes (A1)", owner.payloadInvalidated)
        assertTrue(
            "control: el JSON retenido SI seria adoptable si estuviera vigente",
            fvpRecoverPlan(retainedPayload) is FvpRecoverPlan.Restored,
        )

        val restored = reconstructAfterDeath(owner, truthSource())
        assertNull("los bytes inertes NO se adoptan al reconstruir", FvpDraftSession.draft.value)
        assertEquals(FvpRecoveryNotice.Unrecoverable, restored.viewModel.recoveryNotice.value)
        assertEquals("y siguen ahi, intactos", retainedPayload, restored.payload)
        assertTrue(restored.payloadInvalidated)
    }

    /**
     * COSTURA 3 (A3): la adopcion ASINCRONA fija el METADATO restaurado (paso real y borrador ya
     * escrito) ANTES de publicar el borrador en la sesion. El observador del borrador vivo ya esta
     * activo y su reanudacion es EN LINEA (aqui con [InlineDispatchDispatcher], que reproduce el
     * estado real de `Main.immediate` en el hilo principal), de modo que corre dentro del propio
     * `setter` del `StateFlow`: si el metadato se fijase despues, ese colector re-codificaria el
     * payload con el paso VIEJO del dueño (SETUP) y el JSON restaurado dejaria de conservarse BYTE A
     * BYTE.
     */
    @Test
    fun asyncAdoptFixesTheRestoredStepBeforePublishingSoThePayloadSurvivesByteExact() {
        val draft = recoveredDraft(sessionKey = "session-async-adopt", athleteId = 7L, bodyMassKg = 78.4)
        val payload = requireNotNull(FvpDraftStateCodec.encode(FvpRecoveryState(draft, FvpFlowStep.RESULT)))
        val gate = CompletableDeferred<FvpPersistedTruth?>()
        val owner = ownerWith(
            payload = payload,
            truth = FvpPersistedTruthSource { gate.await() },
            dispatcher = inlineDispatch,
        )

        assertTrue(owner.viewModel.reconciliationPending.value)
        assertEquals("el candidato sigue intacto mientras la verdad esta en vuelo", payload, owner.payload)

        // La verdad llega DESPUES del constructor: la adopcion es ASINCRONA y el observador ya lleva
        // tiempo observando la sesion (colector suspendido que se reanuda en linea).
        gate.complete(FvpPersistedTruth(athleteExists = true))
        awaitReconciliationResolved(owner)

        assertEquals("se adopta el borrador restaurado", "session-async-adopt", FvpDraftSession.draft.value?.sessionKey)
        assertEquals("con su masa", 78.4, FvpDraftSession.draft.value?.bodyMassKg!!, 0.0)
        assertEquals(
            "el payload restaurado se conserva BYTE A BYTE (el colector no lo re-codifica)",
            payload,
            owner.payload,
        )
        assertEquals(
            "y conserva el paso ORIGINAL del candidato: nadie llamo a onStepVisible",
            FvpFlowStep.RESULT,
            requireNotNull(FvpDraftStateCodec.decode(requireNotNull(owner.payload))).step,
        )
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
        assertFalse("y sigue vigente", owner.payloadInvalidated)
    }

    // --- A2: guardado confirmado atado a la identidad de la serie ---------------------------------

    /**
     * A2: la confirmacion de un guardado captura la `sessionKey` EXACTA. La salida terminal de la
     * serie A no puede descartar ni desviar la serie B que el usuario empezo mientras el guardado
     * estaba en vuelo; y con la serie viva correcta si descarta (y entonces el llamante navega). El
     * payload se limpia despues del guardado confirmado, nunca antes de la base.
     */
    @Test
    fun saveCompletionOfSeriesACannotDiscardNorNavigateAwayFromANewerSeriesB() {
        val owner = ownerWith(payload = null, truth = truthSource())

        val seriesA = owner.viewModel.begin(athleteId = 7L)
        owner.viewModel.update { it.copy(bodyMassKg = 80.0) }
        assertFalse(owner.payloadInvalidated)

        // B empieza mientras el guardado de A sigue en vuelo.
        owner.viewModel.begin(athleteId = 9L)
        val seriesB = requireNotNull(owner.viewModel.update { it.copy(bodyMassKg = 81.0) })
        val payloadB = requireNotNull(owner.payload)
        assertEquals(seriesB.sessionKey, requireNotNull(FvpDraftStateCodec.decode(payloadB)).draft.sessionKey)

        assertFalse(
            "la confirmacion de A NO descarta el borrador vivo de B",
            owner.viewModel.resetIfSessionKey(seriesA.sessionKey),
        )
        assertSame("B sigue vivo", seriesB, FvpDraftSession.draft.value)
        assertEquals("y su payload vigente no se toca", payloadB, owner.payload)
        assertFalse(owner.payloadInvalidated)

        assertTrue(
            "con la serie viva correcta si se descarta (y solo entonces se navega)",
            owner.viewModel.resetIfSessionKey(seriesB.sessionKey),
        )
        assertNull(FvpDraftSession.draft.value)
        assertNull("el payload se limpia despues del guardado CONFIRMADO", owner.payload)
        assertFalse(owner.payloadInvalidated)
        assertEquals(FvpRecoveryNotice.None, owner.viewModel.recoveryNotice.value)
    }

    /** Fallback de sesion aislada (sin dueño): el mismo guard, sin reconciliacion que superseder. */
    @Test
    fun sessionFallbackResetIfSessionKeyOnlyActsOnTheMatchingDraft() {
        val seriesA = FvpDraftSession.begin(athleteId = 7L)
        val seriesB = FvpDraftSession.begin(athleteId = 9L)

        assertFalse("la serie AJENA no se descarta", FvpDraftSession.resetIfSessionKey(seriesA.sessionKey))
        assertSame(seriesB, FvpDraftSession.draft.value)
        assertTrue(FvpDraftSession.resetIfSessionKey(seriesB.sessionKey))
        assertNull(FvpDraftSession.draft.value)
        assertFalse("sin sesion viva no hay nada que descartar", FvpDraftSession.resetIfSessionKey(seriesB.sessionKey))
    }

    // --- utilidades -----------------------------------------------------------------------------

    /** Dueños creados por el test, cada uno con SU `ViewModelStore` (ver [clearOwners]). */
    private val ownerStores = mutableListOf<ViewModelStore>()

    /** Dueño + su `SavedStateHandle`, para poder comprobar el payload que escribe. */
    private class OwnerUnderTest(
        val handle: SavedStateHandle,
        val viewModel: FvpFlowViewModel,
    ) {
        val payload: String? get() = handle.get(FVP_RECOVERY_PAYLOAD_KEY)

        /** Marca de vigencia: `true` = hay bytes retenidos pero NO vigentes (inertes, A1). */
        val payloadInvalidated: Boolean get() = handle.get<Boolean>(FVP_RECOVERY_INVALIDATED_KEY) == true
    }

    /**
     * Crea el dueño por el camino REAL de un `ViewModelStore` (`ViewModelProvider`), que es lo que
     * permite a [clearOwners] llamar a `onCleared()` y cancelar el `viewModelScope` del observador.
     * `Dispatchers.Unconfined` para que el observador del borrador vivo corra en el hilo del test
     * (mismo patron que `ComparisonStateTest`), sin Main ni Room.
     */
    private fun ownerOn(
        handle: SavedStateHandle,
        truth: FvpPersistedTruthSource,
        timeoutMs: Long = FVP_TRUTH_LOOKUP_TIMEOUT_MS,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): OwnerUnderTest {
        val store = ViewModelStore()
        ownerStores += store
        val factory = viewModelFactory {
            initializer { FvpFlowViewModel(handle, truth, dispatcher, timeoutMs) }
        }
        val viewModel = ViewModelProvider(store, factory)[FvpFlowViewModel::class.java]
        return OwnerUnderTest(handle, viewModel)
    }

    private fun ownerWith(
        payload: String?,
        truth: FvpPersistedTruthSource,
        timeoutMs: Long = FVP_TRUTH_LOOKUP_TIMEOUT_MS,
        invalidated: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): OwnerUnderTest = ownerOn(handleWith(payload, invalidated), truth, timeoutMs, dispatcher)

    /**
     * Despachador que imita a `Dispatchers.Main.immediate` EN EL HILO PRINCIPAL sin el bucle
     * "unconfined" de ese hilo: `isDispatchNeeded = true` (la reanudacion pasa por `dispatch`) pero
     * `dispatch` ejecuta el bloque EN LINEA, sin encolarlo.
     *
     * Es el estado de produccion al publicar la adopcion: la verdad persistida vuelve por un `post`
     * del hilo principal (`DispatchedTask.run`, que no activa el bucle sin despachador), de modo que
     * un colector YA activo (sin despacho pendiente) se reanuda EN LINEA dentro del `setter` del
     * `StateFlow`. Con `Dispatchers.Unconfined` ese mismo caso se ENCOLA en el bucle "unconfined" del
     * hilo y el defecto de orden no seria reproducible: el colector correria despues de la publicacion.
     */
    private class InlineDispatchDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }

    /** Fuente que usa el test de la costura 3 (adopcion asincrona con observacion en linea). */
    private val inlineDispatch = InlineDispatchDispatcher()

    /**
     * Espera (con cota) a que la reconciliacion PENDIENTE se resuelva. La resolucion de una fuente
     * que suspende (o la cota agotada) puede completarse por otro hilo, de modo que las pruebas que
     * dependen de ella esperan de forma acotada en vez de asumir que ya termino.
     */
    private fun awaitReconciliationResolved(owner: OwnerUnderTest, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (owner.viewModel.reconciliationPending.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertFalse(
            "la reconciliacion debe resolverse dentro de la cota de la prueba",
            owner.viewModel.reconciliationPending.value,
        )
    }

    /**
     * Da una ventana acotada a una resolucion OBSOLETA (ya cancelada/supersedida) para que, si
     * llegase a avanzar, pudiese aplicar efectos: la prueba exige despues que no haya aplicado
     * ninguno. Es una espera de COMPROBACION, no una condicion de exito.
     */
    private fun awaitQuiet(owner: OwnerUnderTest, millis: Long = 60L) {
        Thread.sleep(millis)
        assertFalse("una resolucion obsoleta no puede dejarla pendiente", owner.viewModel.reconciliationPending.value)
    }

    /** Estado guardado que Android conservaria: el payload (si lo hay) y su marca de vigencia. */
    private fun handleWith(payload: String?, invalidated: Boolean): SavedStateHandle =
        SavedStateHandle(
            buildMap<String, Any?> {
                if (payload != null) put(FVP_RECOVERY_PAYLOAD_KEY, payload)
                if (invalidated) put(FVP_RECOVERY_INVALIDATED_KEY, true)
            },
        )

    /**
     * Cancela los `viewModelScope` de los dueños creados (via `onCleared()`), igual que los
     * cancelaria la muerte del proceso. NO toca el payload: el estado guardado sobrevive.
     */
    private fun clearOwners() {
        ownerStores.forEach { it.clear() }
        ownerStores.clear()
    }

    /**
     * Reconstruye el dueño desde el estado GUARDADO del anterior (mismos bytes y misma marca), que
     * es lo unico que Android conserva: descarta la sesion viva y cancela los colectores del dueño
     * anterior, como al morir el proceso.
     *
     * NO es prueba de muerte de proceso real: esa es la mitad ANDROID de R3 (V2 [G-DEVICE],
     * bloqueada sin emulador). Aqui solo se prueba la DECISION del dueño con el mismo estado.
     */
    private fun reconstructAfterDeath(previous: OwnerUnderTest, truth: FvpPersistedTruthSource): OwnerUnderTest {
        val snapshot = savedStateSnapshot(previous.handle)
        clearOwners()
        FvpDraftSession.reset()
        return ownerOn(SavedStateHandle(snapshot), truth)
    }

    /** Copia del estado guardado actual (solo String/Boolean: es lo unico que el dueño escribe). */
    private fun savedStateSnapshot(handle: SavedStateHandle): Map<String, Any?> = buildMap<String, Any?> {
        handle.get<String>(FVP_RECOVERY_PAYLOAD_KEY)?.let { put(FVP_RECOVERY_PAYLOAD_KEY, it) }
        if (handle.get<Boolean>(FVP_RECOVERY_INVALIDATED_KEY) == true) {
            put(FVP_RECOVERY_INVALIDATED_KEY, true)
        }
    }

    private fun truthSource(
        persistedRowId: Long? = null,
        persistedAthleteId: Long? = null,
        athleteExists: Boolean = true,
        athleteArchived: Boolean = false,
    ): FvpPersistedTruthSource = FvpPersistedTruthSource {
        FvpPersistedTruth(persistedRowId, persistedAthleteId, athleteExists, athleteArchived)
    }

    /** Payload de un borrador adoptable (masa utilizable) del paso de condiciones, sessionKey dado. */
    private fun advPayload(sessionKey: String): String = requireNotNull(
        FvpDraftStateCodec.encode(FvpRecoveryState(recoveredDraft(sessionKey = sessionKey), FvpFlowStep.CONDITIONS)),
    )

    private fun recoveredDraft(
        sessionKey: String = "session-recovered",
        athleteId: Long = 7L,
        bodyMassKg: Double? = 80.0,
    ): FvpDraft = FvpDraft(
        sessionKey = sessionKey,
        athleteId = athleteId,
        protocol = FvpProtocol.SJ,
        bodyMassKg = bodyMassKg,
        commonPushOffM = 0.40,
    )
}
