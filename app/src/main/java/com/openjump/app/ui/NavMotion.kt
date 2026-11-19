package com.openjump.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry
import com.openjump.app.ui.angles.ANGLES_CAMERA_ROUTE

/**
 * Movimiento de navegación propio de la app.
 *
 * Navigation-Compose anima los pares de destinos con fundidos de 700 ms por defecto. Aquí se
 * sustituyen por un fundido de opacidad recíproco y corto ([NAV_FADE_DURATION_MILLIS]), sin
 * retardo, desplazamiento ni escala. Es un valor de movimiento de la app, no una medición: no se
 * afirma que el fundido sea más rápido en tiempo real que la duración por defecto de la librería ni
 * que elimine trabajo de renderizado; eso se mide en dispositivo, fuera de este archivo.
 *
 * **Frontera de cámara.** Si cualquiera de los dos lados de la transición es [CAMERA_ROUTE] o
 * [ANGLES_CAMERA_ROUTE], la entrada Y la salida son [EnterTransition.None]/[ExitTransition.None]:
 * no hay fundido explícito en el borde de la cámara, en ninguno de los dos sentidos, tampoco con
 * `popBackStack` ni con Back por gesto (Navigation-Compose aplica los mismos callbacks de pop). No
 * se afirma que el hardware de cámara se libere de forma síncrona al navegar; la pantalla y sus
 * efectos de ciclo de vida siguen siendo los responsables, y solo una prueba en dispositivo lo
 * verifica.
 *
 * Se mantiene `internal` para reutilizar **los mismos** callbacks en las regresiones instrumentadas,
 * que montan un `NavHost` real con destinos ligeros.
 */
internal const val NAV_FADE_DURATION_MILLIS = 160

/** Ruta de cámara de la entrada de Ángulos; el patrón coincide con [ANGLES_CAMERA_ROUTE]. */
private fun String?.isCameraRoute(): Boolean = this == CAMERA_ROUTE || this == ANGLES_CAMERA_ROUTE

private fun AnimatedContentTransitionScope<NavBackStackEntry>.touchesCameraBoundary(): Boolean =
    initialState.destination.route.isCameraRoute() || targetState.destination.route.isCameraRoute()

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnterTransition(): EnterTransition =
    if (touchesCameraBoundary()) {
        EnterTransition.None
    } else {
        fadeIn(animationSpec = tween(durationMillis = NAV_FADE_DURATION_MILLIS))
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navExitTransition(): ExitTransition =
    if (touchesCameraBoundary()) {
        ExitTransition.None
    } else {
        fadeOut(animationSpec = tween(durationMillis = NAV_FADE_DURATION_MILLIS))
    }
