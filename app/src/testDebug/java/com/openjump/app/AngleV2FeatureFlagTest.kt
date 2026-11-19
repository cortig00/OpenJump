package com.openjump.app

import com.openjump.app.ui.angles.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `debug` es una superficie con forma de producto: el guardado multi-fotograma de ángulos (V2)
 * sigue apagado aunque el build sea debug y el flujo de prueba viva en `anglesV2Test`.
 */
class AngleV2FeatureFlagTest {

    @Test
    fun debugBuildKeepsAngleMultiFrameSaveOff() {
        // El campo generado, la bandera de producto y el gate real que lee la UI, los tres OFF.
        assertFalse(BuildConfig.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED)
        assertFalse(FeatureFlags.angleMultiFrameSaveEnabled)
        assertFalse(ANGLE_MULTI_FRAME_SAVE_UI_ENABLED)
    }

    @Test
    fun debugBuildKeepsProductionIdentity() {
        assertEquals("com.openjump.app", BuildConfig.APPLICATION_ID)
        assertEquals("debug", BuildConfig.BUILD_TYPE)
    }
}
