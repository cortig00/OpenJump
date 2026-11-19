package com.openjump.app

/**
 * Switches that decide whether an already implemented feature is offered in this build.
 *
 * They are compile-time constants generated per variant; there is no preference, deep link or
 * remote value that can turn a production-only feature on accidentally.
 */
internal object FeatureFlags {
    /** F-V-P profile: implemented and retained, but not offered in production yet. */
    val fvpEnabled: Boolean = BuildConfig.FVP_ENABLED

    /**
     * Guardado multi-fotograma de ángulos (flujo V2) ofrecido en la UI.
     *
     * Matriz por variante: `false` en `debug` y `demo`; `true` en `release`, solo para el candidato
     * autorizado 1.3 (la distribución de producción sigue condicionada a los gates de release que
     * documenta la puerta, que aquí no se dan por superados). La variante aislada de prueba manual
     * `anglesV2Test` (otra aplicación) lo compila como `true` para que el usuario pueda probar el
     * flujo sin abrir el gate en debug/demo. Que esa variante lo ofrezca **no** acredita ninguno de
     * los gates de release que la puerta documenta.
     */
    val angleMultiFrameSaveEnabled: Boolean = BuildConfig.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED
}
