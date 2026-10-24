package com.openjump.app.ui.fvp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTheme
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contrato tipografico de las COLUMNAS DE NUMEROS de los ejes Y de F-V y P-V, con las graficas
 * reales ([FvpForceVelocityChart] / [FvpPowerVelocityChart]) y un banco de datos sintetico: no toca
 * Room, ajustes ni datos persistidos.
 *
 * Regresion reportada: a 150% de fontscale en tema oscuro la etiqueta superior de P-V `2162.21`
 * (7 cifras) se partia en `2162.2` + `1` porque la columna de numeros estaba fijada a 62 dp. Estas
 * pruebas exigen el numero COMPLETO, UNA sola linea, sin desbordamiento visual y sin elipsis, con la
 * grafica conservando el ancho restante; el ancho de la etiqueta debe superar la columna historica de
 * 62 dp para demostrar que el caso reproduce el corte (nada de trucos con `maxLines`).
 */
@RunWith(AndroidJUnit4::class)
class FvpChartTypographyInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class ChartRender(
        val fontScale: Float,
        val density: Float,
        val dark: Boolean,
        val generation: Int,
    )

    private val rendered = mutableStateOf<ChartRender?>(null)

    /** Locale REAL de composicion (el mismo que usa el formateo `%.2f` de las etiquetas). */
    private val compositionLocale = mutableStateOf(Locale.US)
    private var contentInstalled = false
    private var generation = 0

    /**
     * Monta las DOS graficas con escala de fuente, densidad y tema explicitos. Cada `render` crea una
     * composicion nueva (`key(generation)`) para que no queden estados `remember` del caso anterior.
     */
    private fun render(fontScale: Float, density: Float, dark: Boolean) {
        val spec = ChartRender(fontScale, density, dark, ++generation)
        compose.runOnIdle { rendered.value = spec }
        if (!contentInstalled) {
            compose.setContent {
                rendered.value?.let { current ->
                    key(current.generation) {
                        OpenJumpTheme(darkTheme = current.dark) {
                            CompositionLocalProvider(
                                LocalDensity provides Density(current.density, current.fontScale),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .testTag(FIXTURE_TAG),
                                ) {
                                    // Locale REAL de composicion (el mismo que usa el formateo "%.2f").
                                    val chartLocale = currentAppLocale()
                                    SideEffect { compositionLocale.value = chartLocale }
                                    FvpForceVelocityChart(
                                        points = axisPoints(),
                                        f0N = F0_N,
                                        sfvNsPerM = SFV_NS_PER_M,
                                        vMinMps = V_MIN_MPS,
                                        vMaxMps = V_MAX_MPS,
                                        v0Mps = V0_MPS,
                                        protocolLabel = PROTOCOL,
                                    )
                                    FvpPowerVelocityChart(
                                        points = axisPoints(),
                                        f0N = F0_N,
                                        sfvNsPerM = SFV_NS_PER_M,
                                        pmaxW = PMAX_W,
                                        v0Mps = V0_MPS,
                                        vMinMps = V_MIN_MPS,
                                        vMaxMps = V_MAX_MPS,
                                        protocolLabel = PROTOCOL,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            contentInstalled = true
        }
        compose.waitForIdle()
    }

    /**
     * Caso reportado exacto (P-V, oscuro, 150%): `2162.21` debe verse entero en una linea y la
     * grafica debe conservar su ancho.
     */
    @Test
    fun powerVelocityAxisMaxLabelStaysSingleLineAtFontScale150InDarkTheme() {
        render(fontScale = 1.5f, density = DEVICE_LIKE_DENSITY, dark = true)
        val where = "P-V fontScale=1.5 density=$DEVICE_LIKE_DENSITY dark=true"
        assertAxisNumberFits(PV_MAX_LABEL_TAG, formattedAxisValue(PMAX_W), 1.5f, DEVICE_LIKE_DENSITY, where)
        assertPlotKeepsRoom(PV_CANVAS_TAG, where)
    }

    /**
     * Mismo caso para F-V con el otro valor de 7 cifras reportado (`3137.38`): el arreglo cubre las
     * DOS columnas de numeros, no solo la de P-V.
     */
    @Test
    fun forceVelocityAxisMaxLabelStaysSingleLineAtFontScale150InDarkTheme() {
        render(fontScale = 1.5f, density = DEVICE_LIKE_DENSITY, dark = true)
        val where = "F-V fontScale=1.5 density=$DEVICE_LIKE_DENSITY dark=true"
        assertAxisNumberFits(FV_MAX_LABEL_TAG, formattedAxisValue(F0_N), 1.5f, DEVICE_LIKE_DENSITY, where)
        assertPlotKeepsRoom(FV_CANVAS_TAG, where)
    }

    /** Barrido 100/150% de fontscale x densidad 2.0/3.0 x tema claro/oscuro sobre las dos graficas. */
    @Test
    fun axisNumberColumnsStaySingleLineAcrossFontScaleDensityAndTheme() {
        for (density in DENSITIES) {
            for (fontScale in FONT_SCALES) {
                for (dark in THEMES) {
                    render(fontScale = fontScale, density = density, dark = dark)
                    val where = "fontScale=$fontScale density=$density dark=$dark"
                    assertAxisNumberFits(FV_MAX_LABEL_TAG, formattedAxisValue(F0_N), fontScale, density, where)
                    assertPlotKeepsRoom(FV_CANVAS_TAG, where)
                    assertAxisNumberFits(PV_MAX_LABEL_TAG, formattedAxisValue(PMAX_W), fontScale, density, where)
                    assertPlotKeepsRoom(PV_CANVAS_TAG, where)
                }
            }
        }
    }

    /**
     * El numero de eje se muestra COMPLETO, en UNA linea, sin recorte visual ni elipsis, y la columna
     * le da el ancho que el texto necesita. A 150% el ancho de una sola linea debe superar la columna
     * historica fija de 62 dp: si no, el caso de regresion ya no reproduce el corte reportado.
     */
    private fun assertAxisNumberFits(
        tag: String,
        expected: String,
        fontScale: Float,
        density: Float,
        where: String,
    ) {
        compose.onNodeWithTag(tag).assertTextEquals(expected)
        val layout = textLayout(tag)
        assertTrue(
            "el numero de eje debe ocupar UNA linea ($where, tag=$tag): ${layout.lineCount} lineas",
            layout.lineCount == 1,
        )
        val diagnostic = "size=${layout.size} constraints=${layout.layoutInput.constraints} " +
            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
            "overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight} " +
            "line=${layout.getLineLeft(0)}..${layout.getLineRight(0)} " +
            "top=${layout.getLineTop(0)} bottom=${layout.getLineBottom(0)} " +
            "density=${layout.layoutInput.density}"
        android.util.Log.i("FvpChartTypography", "$where tag=$tag $diagnostic")
        assertFalse("sin desbordamiento visual ($where, tag=$tag): $diagnostic", layout.hasVisualOverflow)
        assertFalse("sin elipsis ($where, tag=$tag)", layout.isLineEllipsized(0))
        val nodeWidthPx = compose.onNodeWithTag(tag).fetchSemanticsNode().size.width
        assertTrue(
            "la columna debe dar al numero su ancho completo ($where, tag=$tag): " +
                "texto=${layout.size.width}px columna=${nodeWidthPx}px",
            nodeWidthPx >= layout.size.width,
        )
        assertTrue(
            "la etiqueta ocupa la columna con suelo historico de 62 dp ($where, tag=$tag)",
            nodeWidthPx >= HISTORICAL_AXIS_COLUMN.value * density,
        )
        if (fontScale > 1.0f) {
            val historicalColumnPx = HISTORICAL_AXIS_COLUMN.value * density
            assertTrue(
                "el caso a ${fontScale}x ya no excede la columna historica fija de 62 dp " +
                    "($where, tag=$tag): ${layout.size.width}px <= ${historicalColumnPx}px",
                layout.size.width > historicalColumnPx,
            )
        }
    }

    /** La columna de numeros no puede quedarse con la grafica: el lienzo conserva ancho util. */
    private fun assertPlotKeepsRoom(canvasTag: String, where: String) {
        val canvas = compose.onNodeWithTag(canvasTag).fetchSemanticsNode().size
        val fixture = compose.onNodeWithTag(FIXTURE_TAG).fetchSemanticsNode().size
        assertTrue("el lienzo debe tener ancho positivo ($where, tag=$canvasTag)", canvas.width > 0)
        assertTrue(
            "la columna de numeros no puede comerse la grafica ($where, tag=$canvasTag): " +
                "lienzo=${canvas.width}px de ${fixture.width}px",
            canvas.width * 2 >= fixture.width,
        )
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("la etiqueta $tag debe exponer su TextLayoutResult", results.isNotEmpty())
        return results.first()
    }

    private fun formattedAxisValue(value: Double): String =
        String.format(compositionLocale.value, "%.2f", value)

    private companion object {
        const val FIXTURE_TAG = "fvp-chart-typography-fixture"
        const val FV_MAX_LABEL_TAG = "fvp-chart-axis-max-fv"
        const val PV_MAX_LABEL_TAG = "fvp-chart-axis-max-pv"
        const val FV_CANVAS_TAG = "fvp-chart-canvas-fv"
        const val PV_CANVAS_TAG = "fvp-chart-canvas-pv"
        const val PROTOCOL = "SJ"

        /** Valores reportados: 7 cifras + separador decimal en las etiquetas maximas de cada eje. */
        const val F0_N = 3137.38
        const val PMAX_W = 2162.21
        const val SFV_NS_PER_M = 1138.0
        const val V0_MPS = 2.757
        const val V_MIN_MPS = 0.60
        const val V_MAX_MPS = 0.85

        /** Densidad de referencia 3.0x (ventana de 1080 px -> 360 dp), la escala del caso reportado. */
        const val DEVICE_LIKE_DENSITY = 3.0f
        val DENSITIES = listOf(2.0f, 3.0f)
        val FONT_SCALES = listOf(1.0f, 1.5f)
        val THEMES = listOf(false, true)

        /**
         * Columna fija historica (62 dp) que provocaba el corte en dos lineas. NO se lee de
         * produccion a proposito: es el hecho que la regresion debe reproducir.
         */
        val HISTORICAL_AXIS_COLUMN = 62.dp
    }
}

/** Puntos sinteticos con P = F·V y por debajo de F0/Pmax: el banco no toca datos reales. */
private fun axisPoints(): List<FvpPointUi> = listOf(
    FvpPointUi(
        label = "SJ 20",
        heightM = 0.40,
        forceN = 2980.0,
        velocityMps = 0.62,
        powerW = 1847.6,
        valid = true,
        measured = true,
    ),
    FvpPointUi(
        label = "SJ 40",
        heightM = 0.60,
        forceN = 2760.0,
        velocityMps = 0.72,
        powerW = 1987.2,
        valid = true,
        measured = true,
    ),
    FvpPointUi(
        label = "SJ 60",
        heightM = 0.80,
        forceN = 2540.0,
        velocityMps = 0.80,
        powerW = 2032.0,
        valid = true,
        measured = true,
    ),
)
