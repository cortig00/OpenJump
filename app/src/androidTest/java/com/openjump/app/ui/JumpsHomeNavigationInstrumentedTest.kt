package com.openjump.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.angles.AngleNavigationSandbox
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regresión REAL de la salida de Saltos con el tab Inicio, sobre el banco aislado
 * ([AngleNavigationSandbox]): `OpenJumpNavHost` completo con su barra inferior real
 * (`bottom-navigation-item-home`), Room con nombre UUID y preferencias del banco. Ningún dato,
 * base ni preferencia del usuario se abre ni se escribe, y no se simula ninguna pantalla del
 * flujo: el toque en el tab, la navegación y el `Back` son los de la app.
 *
 * Qué demuestra (A0 reprodujo «Home → Saltos → tab Inicio sigue mostrando Saltos» 2/2):
 * 1. Inicio → Saltos (baldosa real de Home) → tab Inicio deja Home EN PANTALLA (texto propio de
 *    Home, no de Saltos) sobre la MISMA entrada pública de Home que ya existía antes de abrir
 *    Saltos (identidad de `NavBackStackEntry` comparada por referencia), sin ninguna entrada
 *    componible justo debajo: ni Home duplicado ni Saltos archivado bajo Inicio.
 * 2. El ciclo completo repetido conserva esa misma entrada, y tocar Inicio estando YA en Home no
 *    crea una entrada nueva ni duplica Home.
 * 3. Inicio → Historial → Atrás vuelve a esa MISMA entrada de Home y no reabre Saltos: el `Back`
 *    de la pila real no tiene una entrada de Saltos debajo (en el host de test el Atrás del
 *    sistema terminaría la actividad, así que se comprueba con la MISMA pila a través del pop
 *    real de Historial).
 * 4. Lo mismo cuando el inicio del grafo es el onboarding (banco sin completar): se sale con el
 *    botón real «Saltar» (que retira la entrada de onboarding) y después el mismo Inicio → Saltos
 *    → Inicio, así que cubre ese camino sin IME ni pasos frágiles.
 *
 * Sólo se consulta API PÚBLICA de `NavController` (`currentBackStackEntry`, `previousBackStackEntry`
 * y `currentDestination`), leída en el hilo de UI; `currentBackStack` está anotada
 * `@RestrictTo(LIBRARY_GROUP)` y este test NO la usa.
 *
 * Límites declarados: no se ejerce el Atrás del sistema sobre Home (finalizaría la actividad del
 * host); la API pública sólo expone las dos entradas superiores de la pila, así que no se afirma
 * nada sobre entradas archivadas más abajo (tercera y siguientes); no se afirma nada sobre la
 * pantalla de Saltos ni sobre sus pruebas; y la rama defensiva sin Home en la pila (inicio en
 * onboarding sin entrada de Home) no es alcanzable desde estos dos flujos reales, así que no se
 * ejercita aquí.
 */
@RunWith(AndroidJUnit4::class)
class JumpsHomeNavigationInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    private lateinit var sandbox: AngleNavigationSandbox
    private lateinit var app: AngleNavigationSandbox.SandboxedApp

    /** App montada en el grafo; `null` desmonta la composición (una sola `setContent`). */
    private val mountedApp = mutableStateOf<AngleNavigationSandbox.SandboxedApp?>(null)

    @Volatile
    private var navController: NavHostController? = null

    @Before
    fun setUp() {
        sandbox = AngleNavigationSandbox(target)
        app = sandbox.newApp()
    }

    @After
    fun tearDown() {
        // Sólo se desmonta el grafo y se cierran las bases del banco: ningún fichero se borra.
        runCatching { rule.runOnUiThread { mountedApp.value = null } }
        runCatching { rule.waitForIdle() }
        runCatching { sandbox.closeDatabases() }
    }

    // --- 1. Inicio del grafo = Home ---------------------------------------------------------------

    @Test
    fun inicioDesdeSaltosVuelveAlHomeExistenteSinDuplicarNiReabrirSaltos() {
        app.onboardingPreferencesStore.completeOnboarding()
        mountGraph()
        val homeEntry = awaitHomeEntryOnTop()
        assertHomeVisible()

        openJumpsFromHome()
        assertJumpsVisible()

        clickHomeTab()
        awaitRoute(HOME_ROUTE)
        assertHomeVisible()
        assertJumpsGoneFromScreen()
        assertBackOnSameHomeEntry(homeEntry)

        // Segundo ciclo completo: repetir el toque conserva la MISMA entrada y no deja Saltos.
        openJumpsFromHome()
        assertJumpsVisible()
        clickHomeTab()
        awaitRoute(HOME_ROUTE)
        assertHomeVisible()
        assertJumpsGoneFromScreen()
        assertBackOnSameHomeEntry(homeEntry)

        // Tocar Inicio estando YA en Home no crea una entrada nueva ni duplica Home.
        clickHomeTab()
        assertHomeVisible()
        assertBackOnSameHomeEntry(homeEntry)

        // Atrás real (pop de la MISMA pila) desde Historial: vuelve a la MISMA entrada de Home.
        rule.onNodeWithTag(BOTTOM_NAV_HISTORY_TAG).performClick()
        awaitRoute(HISTORY_ROUTE)
        assertTrue(
            "El pop real de Historial debía volver a Home",
            rule.runOnIdle { requireNotNull(navController).popBackStack() },
        )
        awaitRoute(HOME_ROUTE)
        assertHomeVisible()
        assertBackOnSameHomeEntry(homeEntry)
    }

    // --- 2. Inicio del grafo = onboarding ----------------------------------------------------------

    @Test
    fun inicioTrasElOnboardingVuelveAlHomeExistenteSinReabrirSaltos() {
        // El banco arranca con el onboarding PENDIENTE, así que el `startRoute` congelado del grafo
        // es el onboarding: la entrada de Home nace de la salida real del asistente.
        mountGraph()
        waitUntilEnabled(ONBOARDING_SKIP_TAG)
        rule.onNodeWithTag(ONBOARDING_SKIP_TAG).performClick()
        // El «Saltar» real retira la entrada de onboarding: Home nace como única entrada componible.
        val homeEntry = awaitHomeEntryOnTop()
        assertHomeVisible()

        openJumpsFromHome()
        assertJumpsVisible()

        clickHomeTab()
        awaitRoute(HOME_ROUTE)
        assertHomeVisible()
        assertJumpsGoneFromScreen()
        assertBackOnSameHomeEntry(homeEntry)

        rule.onNodeWithTag(BOTTOM_NAV_HISTORY_TAG).performClick()
        awaitRoute(HISTORY_ROUTE)
        assertTrue(
            "El pop real de Historial debía volver a Home",
            rule.runOnIdle { requireNotNull(navController).popBackStack() },
        )
        awaitRoute(HOME_ROUTE)
        assertHomeVisible()
        assertBackOnSameHomeEntry(homeEntry)
    }

    // --- montaje del grafo ------------------------------------------------------------------------

    /**
     * Monta UNA sola vez un `setContent` reactivo con la app del banco: contexto de UI aislado y
     * dueño de ViewModels con `APPLICATION_KEY` = app aislada, de modo que toda fábrica real del
     * grafo (Home incluida) use la base UUID y las preferencias del banco.
     */
    private fun mountGraph() {
        rule.setContent {
            val current = mountedApp.value
            if (current != null) {
                val mountedUiContext = remember(current) { current.newUiContext(rule.activity) }
                val owner = remember(current) { current.newDefaultViewModelOwner(rule.activity) }
                CompositionLocalProvider(
                    LocalContext provides mountedUiContext,
                    LocalViewModelStoreOwner provides owner,
                ) {
                    OpenJumpTheme {
                        val controller = rememberNavController()
                        SideEffect { navController = controller }
                        OpenJumpNavHost(navController = controller)
                    }
                }
            }
        }
        rule.runOnUiThread { mountedApp.value = app }
        rule.waitForIdle()
    }

    // --- acciones reales de la UI -----------------------------------------------------------------

    /** Baldosa REAL de Saltos de Home (su texto es el título de la entrada de Home). */
    private fun openJumpsFromHome() {
        val label = target.getString(R.string.home_jumps_title)
        rule.waitUntil(TIMEOUT_MILLIS) {
            rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodes(hasText(label) and hasClickAction()).onFirst().performScrollTo().performClick()
        awaitRoute(JUMPS_ROUTE)
        rule.waitForIdle()
    }

    /** Tab REAL de Inicio de la barra inferior. */
    private fun clickHomeTab() {
        rule.onNodeWithTag(BOTTOM_NAV_HOME_TAG).performClick()
        rule.waitForIdle()
    }

    // --- aserciones de contenido y pila -----------------------------------------------------------

    /** Home REAL en pantalla: el texto que sólo compone Home y que Saltos nunca muestra. */
    private fun assertHomeVisible() {
        waitForText(target.getString(R.string.home_question))
    }

    /** Saltos REAL en pantalla: su subtítulo no existe en Home ni en la barra inferior. */
    private fun assertJumpsVisible() {
        waitForText(target.getString(R.string.jumps_subtitle))
    }

    /** Saltos REAL ya no está en pantalla cuando Home vuelve a estar delante. */
    private fun assertJumpsGoneFromScreen() {
        rule.waitUntil(TIMEOUT_MILLIS) {
            !hasNodeWithText(target.getString(R.string.jumps_subtitle))
        }
        assertFalse(
            "Saltos no puede seguir en pantalla con Home delante",
            hasNodeWithText(target.getString(R.string.jumps_subtitle)),
        )
    }

    /**
     * La cima de la pila observable por API PÚBLICA (`currentBackStackEntry`) es Home y es
     * EXACTAMENTE la MISMA entrada [expected] que ya existía antes de abrir Saltos, sin ninguna
     * entrada componible justo debajo: ni Home duplicado ni Saltos archivado bajo Inicio. Tocar
     * Inicio estando ya en Home también pasa por aquí, así que cubre que no se apila otra entrada.
     */
    private fun assertBackOnSameHomeEntry(expected: NavBackStackEntry) {
        rule.waitForIdle()
        val current = rule.runOnIdle { navController?.currentBackStackEntry }
        val previous = rule.runOnIdle { navController?.previousBackStackEntry }
        assertEquals(
            "La cima de la pila debía ser Home: current=$current previous=$previous",
            HOME_ROUTE,
            current?.destination?.route,
        )
        assertSame(
            "Inicio debía volver a la MISMA entrada de Home ya existente: current=$current",
            expected,
            current,
        )
        assertFalse(
            "No puede haber otra entrada componible bajo Home (Home duplicado o Saltos " +
                "archivado): previous=$previous",
            previous?.destination is ComposeNavigator.Destination,
        )
    }

    // --- esperas acotadas sobre estado real --------------------------------------------------------

    private fun awaitRoute(route: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
        rule.waitUntil(timeoutMillis) { navController?.currentDestination?.route == route }
        rule.waitForIdle()
    }

    /**
     * Espera a que Home esté en la cima y devuelve esa entrada pública leída en el hilo de UI.
     * Se guarda para comparar por REFERENCIA que entrar y salir de Saltos no sustituye la entrada.
     */
    private fun awaitHomeEntryOnTop(): NavBackStackEntry {
        awaitRoute(HOME_ROUTE)
        val entry = rule.runOnIdle { navController?.currentBackStackEntry }
        val current = requireNotNull(entry) { "Home debía estar en la cima de la pila" }
        assertEquals(
            "La cima de la pila debía ser Home: current=$current previous=" +
                "${rule.runOnIdle { navController?.previousBackStackEntry }}",
            HOME_ROUTE,
            current.destination.route,
        )
        return current
    }

    private fun waitForText(text: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
        rule.waitUntil(timeoutMillis) { hasNodeWithText(text) }
    }

    private fun hasNodeWithText(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * El botón real «Saltar» del onboarding arranca deshabilitado hasta que el roster del banco
     * emite; se espera a que esté habilitado en vez de dormir un tiempo fijo.
     */
    private fun waitUntilEnabled(tag: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { rule.onNodeWithTag(tag).assertIsEnabled() }.isSuccess
        }
    }

    private companion object {
        /**
         * Rutas reales del grafo. `jumps` y `history` son privadas en `AppNav.kt` a propósito; el
         * valor se fija aquí como literal independiente para que el test falle si cambian.
         */
        const val JUMPS_ROUTE = "jumps"
        const val HISTORY_ROUTE = "history"

        const val BOTTOM_NAV_HOME_TAG = "bottom-navigation-item-home"
        const val BOTTOM_NAV_HISTORY_TAG = "bottom-navigation-item-history"
        const val ONBOARDING_SKIP_TAG = "onboarding-skip"

        const val TIMEOUT_MILLIS = 30_000L
    }
}
