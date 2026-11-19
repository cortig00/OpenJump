package com.openjump.app.ui.angles

import android.content.pm.ActivityInfo
import com.openjump.app.tracking.VideoPresentationGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership del lease de ventana S4a SIN Android: el host fake registra peticiones y barras. No se
 * afirma que una rotación haya ocurrido ni que la Activity retenga estado (eso no lo prueba un host
 * de test).
 */
class AngleWorkspacePlatformLeaseTest {

    private val horizontalVideo = VideoPresentationGeometry(320, 240)
    private val verticalVideo = VideoPresentationGeometry(240, 320)

    private class FakeHost(
        var requested: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
    ) : AngleWorkspaceWindowHost {
        val orientationRequests = mutableListOf<Int>()
        var hidden: AngleWorkspaceBarsSnapshot? = null
        var restored: AngleWorkspaceBarsSnapshot? = null
        var hideCalls = 0
        var restoreCalls = 0

        override fun requestedOrientation(): Int = requested

        override fun requestOrientation(orientation: Int) {
            requested = orientation
            orientationRequests += orientation
        }

        override fun hideSystemBars(): AngleWorkspaceBarsSnapshot {
            hideCalls += 1
            return AngleWorkspaceBarsSnapshot(
                statusBarsVisible = true,
                navigationBarsVisible = false,
                systemBarsBehavior = 7,
            ).also { hidden = it }
        }

        override fun restoreSystemBars(snapshot: AngleWorkspaceBarsSnapshot) {
            restoreCalls += 1
            restored = snapshot
        }
    }

    @Test
    fun alResumirGuardaPrevioYPideFullUserSinTocarBarras() {
        val host = FakeHost()
        val lease = AngleWorkspacePresentationLease(host)
        lease.onResumed(fullscreen = false, mediaGeometry = null)

        assertEquals(
            listOf(ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
            host.orientationRequests,
        )
        assertEquals(0, host.hideCalls)
        assertTrue(lease.ownsWindow)
    }

    @Test
    fun fullscreenApaisadoPideUserLandscapeYVerticalNoFuerzaGiro() {
        val host = FakeHost()
        val lease = AngleWorkspacePresentationLease(host)
        lease.onResumed(fullscreen = true, mediaGeometry = horizontalVideo)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, host.requested)
        assertEquals(1, host.hideCalls)
        val snapshot = host.hidden

        // Cambiar a fullscreen vertical: vuelve a FULL_USER, sin forzar landscape.
        lease.update(fullscreen = true, mediaGeometry = null)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, host.requested)
        // Las barras ya ocultas se conservan (no se guarda dos veces la instantánea).
        assertEquals(1, host.hideCalls)
        assertEquals(snapshot, host.hidden)

        // Salir de fullscreen restaura la instantánea exacta de barras.
        lease.update(fullscreen = false, mediaGeometry = null)
        assertEquals(1, host.restoreCalls)
        assertEquals(snapshot, host.restored)
    }

    @Test
    fun pausaRestauraOrientacionPreviaSoloSiSeguimosSiendoPropietarios() {
        val host = FakeHost()
        val lease = AngleWorkspacePresentationLease(host)
        lease.onResumed(fullscreen = false, mediaGeometry = null)
        lease.onPaused()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, host.requested)
        assertFalse(lease.ownsWindow)
    }

    @Test
    fun rutaEntranteNoEsPisadaAlPausar() {
        val host = FakeHost()
        val lease = AngleWorkspacePresentationLease(host)
        lease.onResumed(fullscreen = false, mediaGeometry = null)
        // Otra ruta cambia la orientación mientras el workspace sigue compuesto: no es nuestra.
        host.requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        lease.onPaused()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, host.requested)
    }

    @Test
    fun disposeEsIdempotenteYNoDuplicaRestauraciones() {
        val host = FakeHost()
        val lease = AngleWorkspacePresentationLease(host)
        lease.onResumed(fullscreen = true, mediaGeometry = horizontalVideo)
        lease.dispose()
        lease.dispose()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, host.requested)
        assertEquals(1, host.restoreCalls)
    }

    @Test
    fun fullscreenHorizontalPideLandscapeAunqueLaVentanaSigaEnPortrait() {
        val host = FakeHost(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val lease = AngleWorkspacePresentationLease(host)
        // Ventana vertical (portrait) y vídeo horizontal: la petición sale igualmente.
        lease.onResumed(fullscreen = true, mediaGeometry = horizontalVideo)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, host.requested)
        assertEquals(
            listOf(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE),
            host.orientationRequests,
        )
        // Con la misma media, un update no repite la petición.
        lease.update(fullscreen = true, mediaGeometry = horizontalVideo)
        assertEquals(1, host.orientationRequests.size)
        // Media vertical: vuelve a seguir al usuario sin forzar giro.
        lease.update(fullscreen = true, mediaGeometry = verticalVideo)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, host.requested)
    }

    @Test
    fun updateNoPisaLaOrientacionEntranteNiReafirmaLaNuestra() {
        val host = FakeHost()
        val lease = AngleWorkspacePresentationLease(host)
        lease.onResumed(fullscreen = true, mediaGeometry = horizontalVideo)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, host.requested)
        val hidden = host.hidden
        // Otra ruta pide su orientación ANTES de nuestro ON_PAUSE.
        host.requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val requestsBefore = host.orientationRequests.size

        // Un update del workspace viejo no reescribe la orientación entrante.
        lease.update(fullscreen = true, mediaGeometry = horizontalVideo)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, host.requested)
        assertEquals(requestsBefore, host.orientationRequests.size)
        // Se suelta la propiedad: nuestras barras vuelven y no nos creemos dueños.
        assertFalse(lease.ownsWindow)
        assertEquals(1, host.restoreCalls)
        assertEquals(hidden, host.restored)

        // Un ON_RESUME posterior readquiere y vuelve a pedir lo nuestro.
        lease.onResumed(fullscreen = true, mediaGeometry = horizontalVideo)
        assertTrue(lease.ownsWindow)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, host.requested)
        // Al pausar restaura la orientación que encontró al readquirir, no una anterior inventada.
        lease.onPaused()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, host.requested)
    }

    @Test
    fun restaurarBarrasDevuelveCadaVisibilidadEnAmbasDirecciones() {
        // Instantánea mixta real: status oculta, navegación visible.
        val mixed = AngleWorkspaceBarsSnapshot(
            statusBarsVisible = false,
            navigationBarsVisible = true,
            systemBarsBehavior = 7,
        )
        val plan = angleWorkspaceBarsRestorePlan(mixed)
        assertEquals(AngleWorkspaceBarsAction.HIDE, plan.statusBars)
        assertEquals(AngleWorkspaceBarsAction.SHOW, plan.navigationBars)
        assertEquals(7, plan.systemBarsBehavior)
        // Idempotente: restaurar dos veces produce exactamente el mismo plan.
        assertEquals(plan, angleWorkspaceBarsRestorePlan(mixed))

        val bothVisible = mixed.copy(statusBarsVisible = true)
        assertEquals(AngleWorkspaceBarsAction.SHOW, angleWorkspaceBarsRestorePlan(bothVisible).statusBars)
        assertEquals(
            AngleWorkspaceBarsAction.SHOW,
            angleWorkspaceBarsRestorePlan(bothVisible).navigationBars,
        )
        val bothHidden = mixed.copy(navigationBarsVisible = false)
        assertEquals(AngleWorkspaceBarsAction.HIDE, angleWorkspaceBarsRestorePlan(bothHidden).statusBars)
        assertEquals(
            AngleWorkspaceBarsAction.HIDE,
            angleWorkspaceBarsRestorePlan(bothHidden).navigationBars,
        )
    }
}
