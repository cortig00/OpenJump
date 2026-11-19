package com.openjump.app.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.ui.theme.OpenJumpTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regresion REAL del titulo del [OpenJumpTopAppBar] SIN contenido leading (la rama que usan las
 * pantallas sin marca/icono, p. ej. Saved Detail), montando el componente de produccion tal cual y
 * midiendo su layout de texto de verdad (`GetTextLayoutResult`), no solo la cadena de semantica.
 *
 * Caso reportado en runtime a fontScale 150%: el recurso hi `angle_list_title` (`कोण`) se pintaba
 * como `को…` con bounds de texto de ~140 px pese a sobrar ancho en la barra (el texto de semantica
 * seguia completo, por eso aqui se comprueba la elipsis/desbordamiento del motor de texto y el ancho
 * asignado). Controles: zh-Hans `角度` y es `Ángulos` ya se pintaban completos y deben seguir en una
 * sola linea. Sin Room, sin ajustes persistidos, sin Activity principal y sin esqueleto de widget
 * duplicado: solo el widget real montado en el host de test.
 */
@RunWith(AndroidJUnit4::class)
class OpenJumpTopAppBarInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private data class BarSpec(val title: String, val dark: Boolean)

    private val clicks = AtomicInteger()
    private val spec = mutableStateOf(BarSpec(HINDI_TITLE, dark = false))
    private var contentInstalled = false

    /**
     * Monta UNA sola vez el componente real con `LocalDensity` a [FONT_SCALE] (densidad real del
     * host) y cambia titulo/tema por estado: nada de `setContent` repetido entre casos.
     */
    private fun render(next: BarSpec) {
        if (!contentInstalled) {
            spec.value = next
            compose.setContent {
                val current = spec.value
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, FONT_SCALE),
                ) {
                    OpenJumpTheme(darkTheme = current.dark) {
                        Surface {
                            Column(modifier = Modifier.fillMaxSize()) {
                                OpenJumpTopAppBar(
                                    title = current.title,
                                    onNavigationClick = { clicks.incrementAndGet() },
                                    navigationContentDescription = BACK_DESCRIPTION,
                                )
                            }
                        }
                    }
                }
            }
            contentInstalled = true
        } else {
            compose.runOnIdle { spec.value = next }
        }
        compose.waitForIdle()
    }

    /**
     * Layout real del titulo: una linea, sin elipsis y sin desbordamiento visual, con el texto
     * completo llegando al motor, y con un ancho tomado del espacio disponible de la barra (mas de
     * la mitad del viewport) en lugar del ancho intrinseco minusculo del bug (~140 px).
     */
    private fun assertTitleGetsAvailableWidth(title: String) {
        val node = compose.onNodeWithText(title).assertExists()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("expected a single text layout result for '$title'", 1, results.size)
        val layout = results.single()

        assertEquals("full title must reach the layout engine", title, layout.layoutInput.text.text)
        assertEquals("'$title' must stay on a single line", 1, layout.lineCount)
        assertFalse("'$title' line 0 must not be ellipsized", layout.isLineEllipsized(0))
        assertFalse("'$title' must not visually overflow its bounds", layout.hasVisualOverflow)

        val rootWidthPx = compose.onRoot().fetchSemanticsNode().size.width
        val boundsWidthPx = node.fetchSemanticsNode().size.width
        val layoutWidthPx = layout.size.width
        assertTrue(
            "'$title' text layout width ${layoutWidthPx}px must use the available bar width (more than half of ${rootWidthPx}px)",
            layoutWidthPx * 2 > rootWidthPx,
        )
        assertTrue(
            "'$title' title bounds ${boundsWidthPx}px must use the available bar width (more than half of ${rootWidthPx}px)",
            boundsWidthPx * 2 > rootWidthPx,
        )
        assertTrue(
            "'$title' title bounds ${boundsWidthPx}px must stay inside the bounded root viewport (${rootWidthPx}px)",
            boundsWidthPx <= rootWidthPx,
        )
    }

    /**
     * La navegacion real sigue disponible con el objetivo TACTIL que exige la ergonomia del proyecto
     * (48 dp de area efectiva, no 48 dp de contenedor visual). Un [IconButton] de Material3 sin
     * modificador de tamano mide 40 dp de contenedor visual y expande su area tactil a 48 dp: medir
     * `boundsInRoot` como si fuera el objetivo es un error de medida del test (fue el fallo real de
     * I3B), no un defecto de produccion. Aqui se comprueba el area tactil REAL
     * (`touchBoundsInRoot`) y se inyecta un puntero REAL en la banda de padding, dentro del area
     * tactil efectiva pero fuera del contenedor visual, ademas de conservar la activacion por accion
     * semantica (accesibilidad). Cada activacion debe disparar el callback de produccion exactamente
     * una vez. Si en otro host el contenedor visual ya midiera el objetivo completo, el punto valido
     * pasa a ser el centro y el objetivo de 48 dp se sigue exigiendo igual.
     */
    private fun assertBackButtonWorks() {
        val back = compose.onNodeWithContentDescription(BACK_DESCRIPTION)
        back.assertIsDisplayed()

        val root = compose.onRoot().fetchSemanticsNode()
        val node = back.fetchSemanticsNode()
        val visual = node.boundsInRoot
        val touch = node.touchBoundsInRoot
        val minTouchPx = with(compose.density) { MIN_TOUCH_DP.toPx() }

        // Objetivo tactil efectivo >= 48 dp: solo se tolera el redondeo del layout a px (<= 1 px).
        // No se admite un margen por dp ni un waiver a los 40 dp del contenedor visual.
        assertTrue(
            "back button effective touch width ${touch.width}px must be at least ${MIN_TOUCH_DP}dp (${minTouchPx}px, 1px rounding); visual container is ${visual.width}px wide",
            touch.width >= minTouchPx - PX_ROUNDING_TOLERANCE,
        )
        assertTrue(
            "back button effective touch height ${touch.height}px must be at least ${MIN_TOUCH_DP}dp (${minTouchPx}px, 1px rounding); visual container is ${visual.height}px tall",
            touch.height >= minTouchPx - PX_ROUNDING_TOLERANCE,
        )
        assertTrue(
            "effective touch rect $touch must stay inside the root viewport (${root.size.width}x${root.size.height})",
            touch.left >= -PX_ROUNDING_TOLERANCE && touch.top >= -PX_ROUNDING_TOLERANCE &&
                touch.right <= root.size.width + PX_ROUNDING_TOLERANCE &&
                touch.bottom <= root.size.height + PX_ROUNDING_TOLERANCE,
        )

        clicks.set(0)
        back.performClick()
        compose.waitForIdle()
        assertEquals("semantic back activation must fire the callback exactly once", 1, clicks.get())

        // Puntero REAL: un toque en la banda de padding tactil no puede depender de la accion
        // semantica; debe llegar al area tactil efectiva del boton de produccion.
        val paddingPoint = touchPaddingPoint(visual, touch)
        val point = paddingPoint ?: visual.center
        assertTrue("pointer point $point must be inside the effective touch rect $touch", touch.contains(point))
        Log.i(
            TOUCH_LOG_TAG,
            "title='${spec.value.title}' visual=$visual touch=$touch point=$point padded=${paddingPoint != null}",
        )
        if (paddingPoint != null) {
            assertFalse(
                "padding-band pointer point $paddingPoint must stay OUTSIDE the visual container $visual",
                visual.contains(paddingPoint),
            )
        } else {
            Log.i(TOUCH_LOG_TAG, "non-expanded case: visual rect $visual already covers the effective touch rect $touch")
        }

        val rootOrigin = root.boundsInRoot.topLeft
        clicks.set(0)
        compose.onRoot().performTouchInput { click(point - rootOrigin) }
        compose.waitForIdle()
        assertEquals(
            "real pointer tap inside the effective touch target (outside the visual container when it is expanded) must fire the back callback exactly once",
            1,
            clicks.get(),
        )
    }

    /**
     * Punto de la banda de padding tactil: mitad entre el borde del area tactil efectiva y el borde
     * del contenedor visual, sobre el eje con hueco. Devuelve `null` si no hay hueco (el contenedor
     * visual ya cubre el objetivo tactil), caso valido en el que el toque va al centro.
     */
    private fun touchPaddingPoint(visual: Rect, touch: Rect): Offset? {
        val center = visual.center
        val candidates = listOf(
            Offset((touch.left + visual.left) / 2f, center.y),
            Offset((touch.right + visual.right) / 2f, center.y),
            Offset(center.x, (touch.top + visual.top) / 2f),
            Offset(center.x, (touch.bottom + visual.bottom) / 2f),
        )
        return candidates.firstOrNull { touch.contains(it) && !visual.contains(it) }
    }

    @Test
    fun hindiTitleUsesAvailableBarWidthWithoutEllipsisAtLargeFont() {
        render(BarSpec(HINDI_TITLE, dark = false))
        assertTitleGetsAvailableWidth(HINDI_TITLE)
        assertBackButtonWorks()

        render(BarSpec(HINDI_TITLE, dark = true))
        assertTitleGetsAvailableWidth(HINDI_TITLE)
        assertBackButtonWorks()
    }

    @Test
    fun chineseAndSpanishTitlesStayOnOneLineAtLargeFont() {
        render(BarSpec(CHINESE_TITLE, dark = false))
        assertTitleGetsAvailableWidth(CHINESE_TITLE)
        assertBackButtonWorks()

        render(BarSpec(CHINESE_TITLE, dark = true))
        assertTitleGetsAvailableWidth(CHINESE_TITLE)

        render(BarSpec(SPANISH_TITLE, dark = false))
        assertTitleGetsAvailableWidth(SPANISH_TITLE)
        assertBackButtonWorks()

        render(BarSpec(SPANISH_TITLE, dark = true))
        assertTitleGetsAvailableWidth(SPANISH_TITLE)
    }

    private companion object {
        /** Escala de fuente del caso reportado (runtime real: 150%). */
        const val FONT_SCALE = 1.5f

        /** Objetivo TACTIL efectivo minimo exigido por la ergonomia del proyecto (no el visual). */
        val MIN_TOUCH_DP = 48.dp

        /** Unico margen admitido al comparar dp con px: redondeo del layout a pixel entero. */
        const val PX_ROUNDING_TOLERANCE = 1f

        /** Log de test (nunca de produccion) con las medidas reales para el operador. */
        const val TOUCH_LOG_TAG = "OJToolbarTouch"

        const val BACK_DESCRIPTION = "Back"

        /** Textos REALES ya publicados en los recursos (`values-hi`, `values-b+zh+Hans`, `values-es`). */
        const val HINDI_TITLE = "कोण"
        const val CHINESE_TITLE = "角度"
        const val SPANISH_TITLE = "Ángulos"
    }
}
