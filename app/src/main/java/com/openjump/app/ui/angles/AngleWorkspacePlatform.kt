package com.openjump.app.ui.angles

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openjump.app.tracking.VideoPresentationGeometry

/**
 * Estado previo de las barras del sistema, guardado SOLO por el workspace mientras las oculta.
 *
 * Se guardan visibilidad de status y navegación por separado (no una suposición de que ambas
 * estaban visibles) y el comportamiento de las barras, para restaurar exactamente lo que había y
 * no afectar a otras rutas.
 */
internal data class AngleWorkspaceBarsSnapshot(
    val statusBarsVisible: Boolean,
    val navigationBarsVisible: Boolean,
    val systemBarsBehavior: Int,
)

/** Acción a aplicar sobre una familia de barras al restaurar: mostrar u ocultar. */
internal enum class AngleWorkspaceBarsAction { SHOW, HIDE }

/**
 * Plan de restauración derivado de la instantánea: **cada** familia de barras recibe su acción
 * (mostrar si estaba visible, ocultar si no) y el comportamiento previo.
 *
 * Es puro para poder probarlo sin stubs de Android: restaurar no es «volver a mostrar», es devolver
 * exactamente la visibilidad anterior. Si el usuario había expuesto las barras transitorias, la
 * instantánea dice `false` y la restauración debe **ocultarlas** otra vez.
 */
internal data class AngleWorkspaceBarsRestorePlan(
    val statusBars: AngleWorkspaceBarsAction,
    val navigationBars: AngleWorkspaceBarsAction,
    val systemBarsBehavior: Int,
)

internal fun angleWorkspaceBarsRestorePlan(
    snapshot: AngleWorkspaceBarsSnapshot,
): AngleWorkspaceBarsRestorePlan = AngleWorkspaceBarsRestorePlan(
    statusBars = if (snapshot.statusBarsVisible) {
        AngleWorkspaceBarsAction.SHOW
    } else {
        AngleWorkspaceBarsAction.HIDE
    },
    navigationBars = if (snapshot.navigationBarsVisible) {
        AngleWorkspaceBarsAction.SHOW
    } else {
        AngleWorkspaceBarsAction.HIDE
    },
    systemBarsBehavior = snapshot.systemBarsBehavior,
)

/**
 * Puerto de ventana del workspace. La implementación real es la única que toca `Activity`; los
 * tests de layout inyectan un fake explícito (ComponentActivity no tiene `configChanges`, así que
 * nunca se finge retención de rotación).
 */
internal interface AngleWorkspaceWindowHost {
    fun requestedOrientation(): Int
    fun requestOrientation(orientation: Int)
    fun hideSystemBars(): AngleWorkspaceBarsSnapshot
    fun restoreSystemBars(snapshot: AngleWorkspaceBarsSnapshot)
}

/** Host real por defecto en AppNav/APK: actividad de la ventana, sin fallback silencioso. */
internal fun angleWorkspaceWindowHost(context: Context): AngleWorkspaceWindowHost? {
    val activity = context.findActivity() ?: return null
    return AndroidAngleWorkspaceWindowHost(activity)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class AndroidAngleWorkspaceWindowHost(
    private val activity: Activity,
) : AngleWorkspaceWindowHost {

    private val controller: WindowInsetsControllerCompat?
        get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    override fun requestedOrientation(): Int = activity.requestedOrientation

    override fun requestOrientation(orientation: Int) {
        if (activity.requestedOrientation != orientation) {
            activity.requestedOrientation = orientation
        }
    }

    override fun hideSystemBars(): AngleWorkspaceBarsSnapshot {
        val decor = activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
        val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val previousBehavior = controller?.systemBarsBehavior
            ?: WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars() or
            WindowInsetsCompat.Type.navigationBars())
        return AngleWorkspaceBarsSnapshot(statusVisible, navigationVisible, previousBehavior)
    }

    override fun restoreSystemBars(snapshot: AngleWorkspaceBarsSnapshot) {
        val controller = controller ?: return
        val plan = angleWorkspaceBarsRestorePlan(snapshot)
        val statusType = WindowInsetsCompat.Type.statusBars()
        val navigationType = WindowInsetsCompat.Type.navigationBars()
        // Ambas direcciones: lo que estaba visible se muestra y lo que estaba oculto se oculta.
        when (plan.statusBars) {
            AngleWorkspaceBarsAction.SHOW -> controller.show(statusType)
            AngleWorkspaceBarsAction.HIDE -> controller.hide(statusType)
        }
        when (plan.navigationBars) {
            AngleWorkspaceBarsAction.SHOW -> controller.show(navigationType)
            AngleWorkspaceBarsAction.HIDE -> controller.hide(navigationType)
        }
        controller.systemBarsBehavior = plan.systemBarsBehavior
    }
}

/**
 * Lease de presentación del workspace: orientación scoped + barras locales.
 *
 * Reglas de propiedad (probadas con un host fake):
 * - al entrar en RESUMED guarda la orientación **previa** y pide `FULL_USER` (respeta el bloqueo
 *   del usuario); nunca `FULL_SENSOR`;
 * - fullscreen con media HORIZONTAL pide `USER_LANDSCAPE` explícito aunque la ventana siga en
 *   portrait; fullscreen con media vertical/cuadrada (o sin geometría) no fuerza giro;
 * - al volver de fullscreen pide otra vez `FULL_USER` sin restaurar aún la orientación previa;
 * - un `update` no pisa la orientación de una ruta entrante: si el host ya no tiene el valor que
 *   pedimos nosotros, se suelta la propiedad (se restauran solo nuestras barras) sin reescribir
 *   nada, y un `ON_RESUME` posterior vuelve a adquirirla;
 * - al pausar/dispose restaura la previa **solo si el último `requestedOrientation` sigue siendo
 *   el nuestro**: si otra ruta (entrante) ya cambió la ventana, no se pisa su valor;
 * - las barras se ocultan al entrar en fullscreen y se restauran al salir/pausar con la instantánea
 *   exacta (mostrar **u ocultar** según lo que había).
 */
internal class AngleWorkspacePresentationLease(
    private val host: AngleWorkspaceWindowHost,
    private val followUserOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
    private val landscapeOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
) {
    private var previousOrientation: Int? = null
    private var lastRequested: Int? = null
    private var bars: AngleWorkspaceBarsSnapshot? = null
    private var active = false

    val ownsWindow: Boolean get() = active

    fun onResumed(
        fullscreen: Boolean,
        mediaGeometry: VideoPresentationGeometry?,
    ) {
        if (!active) {
            previousOrientation = host.requestedOrientation()
            active = true
        }
        apply(fullscreen, mediaGeometry)
    }

    fun update(
        fullscreen: Boolean,
        mediaGeometry: VideoPresentationGeometry?,
    ) {
        if (!active) return
        val ours = lastRequested
        if (ours != null && host.requestedOrientation() != ours) {
            // Otra ruta cambió la orientación antes de nuestro ON_PAUSE: ese valor es el vigente.
            // Soltamos la propiedad sin reescribir la orientación entrante (un ON_RESUME posterior
            // la vuelve a adquirir con la previa correcta) y restauramos solo las barras propias.
            releaseOwnership()
            return
        }
        apply(fullscreen, mediaGeometry)
    }

    private fun apply(
        fullscreen: Boolean,
        mediaGeometry: VideoPresentationGeometry?,
    ) {
        val target = angleWorkspaceOrientationTarget(fullscreen, mediaGeometry)
        val requested = when (target) {
            AngleWorkspaceOrientationTarget.FOLLOW_USER -> followUserOrientation
            AngleWorkspaceOrientationTarget.LANDSCAPE -> landscapeOrientation
        }
        if (host.requestedOrientation() != requested) {
            host.requestOrientation(requested)
        }
        lastRequested = requested
        if (fullscreen) {
            if (bars == null) bars = host.hideSystemBars()
        } else {
            bars?.let {
                host.restoreSystemBars(it)
                bars = null
            }
        }
    }

    /** ON_PAUSE: deja de poseer la ventana y restaura lo propio sin clobber de otra ruta. */
    fun onPaused() {
        if (!active) return
        restoreOrientation()
        bars?.let {
            host.restoreSystemBars(it)
            bars = null
        }
        active = false
    }

    /**
     * Suelta la propiedad sin tocar la orientación vigente: restaura las barras propias y olvida la
     * previa, para que la próxima adquisición guarde la orientación que realmente haya entonces.
     */
    private fun releaseOwnership() {
        bars?.let {
            host.restoreSystemBars(it)
            bars = null
        }
        previousOrientation = null
        lastRequested = null
        active = false
    }

    /** Dispose/exit: idempotente con [onPaused]. */
    fun dispose() = onPaused()

    private fun restoreOrientation() {
        val previous = previousOrientation ?: return
        val requested = lastRequested
        // Solo restauramos si el valor actual sigue siendo el que pedimos nosotros. Si otra ruta ya
        // pidió otra orientación, su valor es el vigente y no se pisa.
        if (requested == null || host.requestedOrientation() == requested) {
            if (host.requestedOrientation() != previous) host.requestOrientation(previous)
        }
        previousOrientation = null
        lastRequested = null
    }
}
