package com.openjump.app.ui

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.ui.angles.ANGLES_CAMERA_ROUTE
import com.openjump.app.ui.angles.anglesCameraRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regresiones deterministas del movimiento de navegación con un `NavHost` **real** y los mismos
 * callbacks de producción ([navEnterTransition]/[navExitTransition]).
 *
 * El reloj de Compose es virtual y estas pruebas **asumen** el `MotionDurationScale` nominal 1x del
 * dispositivo (el valor por defecto; no se fuerza por ninguna API soportada), así que las
 * aserciones son de tiempo de composición, no de rendimiento real: no miden jank ni la liberación
 * del hardware de cámara. Si el dispositivo altera la escala de animación del sistema, los
 * umbrales dejan de ser válidos y la prueba falla de forma visible (la ventana temprana deja de
 * ver el destino entrante o el saliente). Los destinos son `Text` ligeros; no se monta
 * `MainActivity`, Room, CameraX ni ningún hook de producción.
 *
 * **Sincronización del commit.** Con el reloj virtual pausado, el `NavHost` real no compone el
 * destino entrante en el primer frame: la navegación publica el estado y la composición entrante
 * llega en un frame posterior. Por eso ninguna aserción asume un número de frame concreto:
 * [awaitIncomingComposition] avanza frames acotados y consulta la semántica real hasta observar el
 * destino (así distingue el commit de composición de la mera existencia de la ruta en el
 * `NavController`). Las ventanas se cuentan **desde la orden de navegación**, no desde el frame en
 * que el destino aparece, y por eso se descuentan los frames que consumió el commit.
 */
@RunWith(AndroidJUnit4::class)
class NavMotionInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun startHost() {
        compose.setContent {
            val controller = rememberNavController()
            SideEffect { nav = controller }
            NavHost(
                navController = controller,
                startDestination = HOME_ROUTE,
                enterTransition = { navEnterTransition() },
                exitTransition = { navExitTransition() },
                popEnterTransition = { navEnterTransition() },
                popExitTransition = { navExitTransition() },
            ) {
                composable(HOME_ROUTE) { ProbeDestination("home") }
                composable(OTHER_ROUTE) { ProbeDestination("other") }
                composable(CAMERA_ROUTE) { ProbeDestination("camera") }
                composable(ANGLES_CAMERA_ROUTE) { ProbeDestination("angles-camera") }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun ordinaryForwardAndBackFadeThenDisposeOutgoingBeforeTheLibraryDefault() {
        startHost()
        compose.mainClock.autoAdvance = false

        // Forward: la entrada se compone mientras la saliente sigue presente (fundido recíproco
        // real, no un corte), aunque el NavHost necesite varios frames virtuales para commitearla.
        val forwardInput = compose.mainClock.currentTime
        compose.runOnUiThread { nav.navigate(OTHER_ROUTE) }
        val forwardCommitMillis =
            awaitIncomingComposition(incoming = "other", outgoing = "home", inputTime = forwardInput)
        compose.onNodeWithText("other").assertExists()
        compose.onNodeWithText("home").assertExists()
        compose.mainClock.advanceTimeBy(OVERLAP_CHECK_MILLIS)
        compose.onNodeWithText("other").assertExists()
        compose.onNodeWithText("home").assertExists()
        // ...y la saliente ya no está dentro de la cota fija contada desde la orden de navegación
        // (descontando los frames del commit); el fundido por defecto de 700 ms fallaría aquí.
        val forwardRemaining =
            ORDINARY_TEST_BOUND_MILLIS - (forwardCommitMillis + OVERLAP_CHECK_MILLIS)
        assertTrue(
            "La cota fija de la prueba ($ORDINARY_TEST_BOUND_MILLIS ms virtuales) debe cubrir los " +
                "frames de commit observados ($forwardCommitMillis ms) y la comprobación de " +
                "solape ($OVERLAP_CHECK_MILLIS ms)",
            forwardRemaining > 0,
        )
        compose.mainClock.advanceTimeBy(forwardRemaining)
        val forwardElapsed = compose.mainClock.currentTime - forwardInput
        assertTrue(
            "El fundido ordinario debe resolverse en $ORDINARY_TEST_BOUND_MILLIS ms virtuales " +
                "desde la entrada: elapsedMillis=$forwardElapsed",
            forwardElapsed <= ORDINARY_TEST_BOUND_MILLIS,
        )
        compose.onNodeWithText("other").assertIsDisplayed()
        compose.onNodeWithText("home").assertDoesNotExist()

        // Back: pop usa los mismos callbacks de producción, fundido recíproco incluido.
        val backInput = compose.mainClock.currentTime
        compose.runOnUiThread { nav.popBackStack() }
        val backCommitMillis =
            awaitIncomingComposition(incoming = "home", outgoing = "other", inputTime = backInput)
        compose.onNodeWithText("home").assertExists()
        compose.onNodeWithText("other").assertExists()
        compose.mainClock.advanceTimeBy(OVERLAP_CHECK_MILLIS)
        compose.onNodeWithText("home").assertExists()
        compose.onNodeWithText("other").assertExists()

        val backRemaining = ORDINARY_TEST_BOUND_MILLIS - (backCommitMillis + OVERLAP_CHECK_MILLIS)
        assertTrue(
            "La cota fija de la prueba ($ORDINARY_TEST_BOUND_MILLIS ms virtuales) debe cubrir los " +
                "frames de commit observados ($backCommitMillis ms) y la comprobación de solape " +
                "($OVERLAP_CHECK_MILLIS ms)",
            backRemaining > 0,
        )
        compose.mainClock.advanceTimeBy(backRemaining)
        val backElapsed = compose.mainClock.currentTime - backInput
        assertTrue(
            "El fundido de vuelta debe resolverse en $ORDINARY_TEST_BOUND_MILLIS ms virtuales " +
                "desde la entrada: elapsedMillis=$backElapsed",
            backElapsed <= ORDINARY_TEST_BOUND_MILLIS,
        )
        compose.onNodeWithText("home").assertIsDisplayed()
        compose.onNodeWithText("other").assertDoesNotExist()
    }

    @Test
    fun cameraBoundariesSkipTheFadeInBothDirectionsIncludingPop() {
        startHost()
        compose.mainClock.autoAdvance = false

        // Hacia la cámara de Saltos/Encoder y de vuelta: la frontera no hereda el fundido.
        assertCameraBoundaryCut(incoming = "camera", outgoing = "home") {
            nav.navigate(CAMERA_ROUTE)
        }
        assertCameraBoundaryCut(incoming = "home", outgoing = "camera") { nav.popBackStack() }

        // La cámara de Ángulos es su propia ruta y recibe el mismo trato simétrico.
        assertCameraBoundaryCut(incoming = "angles-camera", outgoing = "home") {
            nav.navigate(anglesCameraRoute(token = "motion-test", athleteId = 1L, requestId = 2L))
        }
        assertCameraBoundaryCut(incoming = "home", outgoing = "angles-camera") {
            nav.popBackStack()
        }
    }

    /**
     * Avanza el reloj virtual frame a frame (como máximo [MAX_COMMIT_FRAMES]) hasta observar en la
     * semántica real el destino [incoming], y devuelve los milisegundos virtuales transcurridos
     * desde [inputTime] (la orden de navegación).
     *
     * No usa espera de reloj de pared, ni `autoAdvance`, ni un "avanzar y asentar": consulta el
     * árbol de semántica en cada frame. Si el destino no aparece dentro del tope, falla de forma
     * explícita con los frames consumidos y la presencia observada.
     */
    private fun awaitIncomingComposition(
        incoming: String,
        outgoing: String,
        inputTime: Long,
    ): Long {
        var lastOutgoingPresent = false
        repeat(MAX_COMMIT_FRAMES) { index ->
            compose.mainClock.advanceTimeByFrame()
            val frames = index + 1
            val elapsed = compose.mainClock.currentTime - inputTime
            val incomingPresent = nodeExists(incoming)
            lastOutgoingPresent = nodeExists(outgoing)
            Log.i(
                NAV_MOTION_LOG_TAG,
                "incoming='$incoming' frame=$frames elapsedMillis=$elapsed " +
                    "incomingPresent=$incomingPresent outgoingPresent=$lastOutgoingPresent",
            )
            if (incomingPresent) {
                return elapsed
            }
        }
        val elapsed = compose.mainClock.currentTime - inputTime
        throw AssertionError(
            "El destino entrante '$incoming' no apareció en la composición tras " +
                "$MAX_COMMIT_FRAMES frames virtuales ($elapsed ms desde la entrada); " +
                "'$outgoing' presente=$lastOutgoingPresent",
        )
    }

    private fun nodeExists(label: String): Boolean =
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    /**
     * Ejecuta [navigate] en el hilo de UI, espera a que [incoming] esté compuesto y comprueba en una
     * ventana corta y fija, contada desde la orden de navegación, que [outgoing] ya no está: la
     * frontera de cámara no hereda el fundido ordinario, en ninguno de los dos sentidos.
     */
    private fun assertCameraBoundaryCut(
        incoming: String,
        outgoing: String,
        navigate: () -> Unit,
    ) {
        val inputTime = compose.mainClock.currentTime
        compose.runOnUiThread(navigate)
        awaitIncomingComposition(incoming = incoming, outgoing = outgoing, inputTime = inputTime)
        compose.mainClock.advanceTimeBy(OVERLAP_CHECK_MILLIS)
        val elapsed = compose.mainClock.currentTime - inputTime
        assertTrue(
            "El corte de cámara debe resolverse dentro de los $CAMERA_BOUNDARY_TEST_BOUND_MILLIS " +
                "ms virtuales de la prueba desde la entrada: elapsedMillis=$elapsed",
            elapsed <= CAMERA_BOUNDARY_TEST_BOUND_MILLIS,
        )
        compose.onNodeWithText(incoming).assertIsDisplayed()
        compose.onNodeWithText(outgoing).assertDoesNotExist()
    }

    @Test
    fun motionLeavesTheBackStackUnchangedAndSingleTopIsNotDuplicated() {
        startHost()

        compose.runOnUiThread {
            nav.navigate(OTHER_ROUTE)
            nav.navigate(CAMERA_ROUTE)
        }
        compose.waitForIdle()
        assertEquals(CAMERA_ROUTE, nav.currentBackStackEntry?.destination?.route)
        assertEquals(OTHER_ROUTE, nav.previousBackStackEntry?.destination?.route)

        compose.runOnUiThread {
            nav.popBackStack()
            nav.popBackStack()
        }
        compose.waitForIdle()
        assertEquals(HOME_ROUTE, nav.currentBackStackEntry?.destination?.route)
        assertNull(nav.previousBackStackEntry)

        // launchSingleTop keeps a single other entry above home.
        compose.runOnUiThread {
            nav.navigate(OTHER_ROUTE) { launchSingleTop = true }
            nav.navigate(OTHER_ROUTE) { launchSingleTop = true }
        }
        compose.waitForIdle()
        assertEquals(OTHER_ROUTE, nav.currentBackStackEntry?.destination?.route)
        assertEquals(HOME_ROUTE, nav.previousBackStackEntry?.destination?.route)
    }
}

private const val OTHER_ROUTE = "motion/other"

/**
 * Cota **de la prueba** para el fundido ordinario, en milisegundos virtuales contados **desde la
 * orden de navegación**.
 *
 * Es deliberadamente independiente de `NAV_FADE_DURATION_MILLIS`: si producción volviera al fundido
 * de 700 ms por defecto de Navigation-Compose, el destino saliente seguiría compuesto al final de
 * esta ventana y la prueba fallaría en vez de adaptarse al valor nuevo. El total cubre los frames
 * que el `NavHost` real consume hasta componer la entrada, la ventana de solape y el resto del
 * fundido, y se queda por debajo de 300 ms virtuales.
 */
private const val ORDINARY_TEST_BOUND_MILLIS = 288L

/** Ventana en la que el fundido ordinario debe mantener compuestos el destino de entrada y el de salida. */
private const val OVERLAP_CHECK_MILLIS = 48L

/** Frames virtuales máximos que se esperan al `NavHost` real hasta componer el destino entrante. */
private const val MAX_COMMIT_FRAMES = 4

/** Duración del frame del reloj de prueba de Compose, en milisegundos virtuales. */
private const val TEST_FRAME_MILLIS = 16L

/**
 * Ventana **de la prueba** para el corte de cámara desde la orden de navegación: los frames de
 * commit acotados más la comprobación fija de ausencia. Queda muy por debajo del fundido
 * ordinario, así que una frontera que lo heredara seguiría mostrando el destino saliente aquí.
 */
private const val CAMERA_BOUNDARY_TEST_BOUND_MILLIS =
    MAX_COMMIT_FRAMES * TEST_FRAME_MILLIS + OVERLAP_CHECK_MILLIS

/** Etiqueta de logcat solo de prueba para la sincronización observada; no hay log de producción. */
private const val NAV_MOTION_LOG_TAG = "OJNavMotion"

@Composable
private fun ProbeDestination(label: String) {
    Text(text = label)
}
