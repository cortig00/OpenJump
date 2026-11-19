package com.openjump.app

import com.openjump.app.ui.angles.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `release` es la superficie de producción del candidato autorizado 1.3: el guardado
 * multi-fotograma de ángulos se ofrece. Encender el gate **no** acredita los gates de release
 * (backup/restore manual con imágenes, unicidad de escritor y verificación de PNG), que siguen
 * pendientes antes de distribuir.
 */
class AngleV2FeatureFlagTest {

    @Test
    fun releaseBuildOffersAngleMultiFrameSave() {
        // El campo generado, la bandera de producto y el gate real que lee la UI, los tres ON.
        assertTrue(BuildConfig.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED)
        assertTrue(FeatureFlags.angleMultiFrameSaveEnabled)
        assertTrue(ANGLE_MULTI_FRAME_SAVE_UI_ENABLED)
    }

    @Test
    fun releaseBuildKeepsProductionIdentity() {
        assertEquals("com.openjump.app", BuildConfig.APPLICATION_ID)
        assertEquals("release", BuildConfig.BUILD_TYPE)
    }
}
