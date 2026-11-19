package com.openjump.app

import com.openjump.app.ui.angles.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Variante aislada de prueba manual `anglesV2Test`: mismo FQN que la prueba de `debug`, pero aquí
 * el guardado multi-fotograma de ángulos (V2) sí está ofrecido, la aplicación es otra y el perfil
 * F-V-P está apagado (es la app que recibe quien prueba la candidata).
 */
class AngleV2FeatureFlagTest {

    @Test
    fun testVariantEnablesAngleMultiFrameSave() {
        // Campo generado, bandera de producto y gate real que lee la UI, los tres ON en esta variante.
        assertTrue(BuildConfig.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED)
        assertTrue(FeatureFlags.angleMultiFrameSaveEnabled)
        assertTrue(ANGLE_MULTI_FRAME_SAVE_UI_ENABLED)
    }

    @Test
    fun testVariantHidesFvpProfile() {
        // Esta variante parte de `debug`, donde F-V-P sigue encendido: la sobreescritura de la
        // variante debe dejar el campo generado Y la bandera efectiva que lee la UI en `false`.
        // Ocultar la entrada no elimina perfiles, sesiones, datos ni traducciones F-V-P.
        assertFalse(BuildConfig.FVP_ENABLED)
        assertFalse(FeatureFlags.fvpEnabled)
    }

    @Test
    fun testVariantIsAnIsolatedDebuggableBuildOnDebugSigning() {
        // ApplicationId propio: datos privados separados de la instalación normal de OpenJump.
        assertEquals("com.openjump.app.anglesv2test", BuildConfig.APPLICATION_ID)
        assertEquals("anglesV2Test", BuildConfig.BUILD_TYPE)
        assertTrue(BuildConfig.DEBUG)
    }
}
