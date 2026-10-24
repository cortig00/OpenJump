package com.openjump.app.ui.fvp

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Doble en MEMORIA de la preferencia de UI de la introduccion guiada.
 *
 * Es SOLO una preferencia de presentacion (no es evidencia cientifica, no es un dato del atleta y
 * no forma parte del borrador): los tests de pantallas que no prueban la introduccion la montan ya
 * vista para no depender del fichero privado del dispositivo ni del orden de ejecucion.
 *
 * Los tests de la introduccion en si (`FvpGuidedEntryInstrumentedTest`) siguen inyectando su propio
 * doble con el valor que cada caso necesita; este helper no relaja ninguna asercion de esos casos.
 */
class FvpTestIntroductionStore(seen: Boolean = true) : FvpIntroductionStore {
    private var seen = seen

    override fun hasSeenIntroduction(): Boolean = seen

    override fun markIntroductionSeen() {
        seen = true
    }
}

/**
 * Marca la introduccion como vista en el fichero PRIVADO real de la app.
 *
 * Necesario solo para los montajes que NO pueden inyectar [FvpIntroductionStore] porque arrancan el
 * `NavHost` real (`OpenJumpNavHost`): ese host usa la implementacion persistente de produccion. No
 * toca el borrador, ni Room, ni los ajustes de la app.
 */
fun markFvpIntroductionSeen() {
    FvpIntroductionPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        .markIntroductionSeen()
}
