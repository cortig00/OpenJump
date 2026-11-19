package com.openjump.app.ui

import com.openjump.app.ui.angles.ANGLES_CAMERA_ROUTE
import com.openjump.app.ui.angles.AngleEntryHandoffState
import com.openjump.app.ui.angles.AngleEntryKind
import com.openjump.app.ui.angles.AngleEntryTicket
import com.openjump.app.ui.angles.AngleEntryTransfer
import com.openjump.app.ui.angles.AnglesCameraRequest
import com.openjump.app.ui.angles.anglesCameraRoute
import com.openjump.app.ui.angles.anglesCameraRouteOwnsIntake
import com.openjump.app.ui.angles.canStartNewAngleCapture
import com.openjump.app.ui.angles.parseAnglesCameraRequest
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guard JVM de la entrada «Ángulos» de Home y de la ruta del editor manual de ángulos.
 *
 * El `NavHost` y el editor tienen frontera Android (Activity Result del selector de vídeo,
 * ExoPlayer, Room), así que este slice no puede componerlos en JVM. Lo que sí se verifica aquí —y es
 * justo lo que cambió— es:
 *
 * 1. el constructor puro de la ruta: percent-encoding del argumento, forma del destino y rechazo de
 *    valores inválidos (sin `android.net.Uri`, que no existe en JVM sin Robolectric);
 * 2. el cableado en el código fuente: Home abre la PREPARACIÓN de Ángulos (sin lanzar el
 *    selector ni la cámara al entrar), el picker por intento vive en la introducción con
 *    `VideoOnly`, cancelar no navega, el atleta congelado viaja en la ruta de cámara propia y el
 *    editor se abre con el ViewModel real por su `factory` (nunca con `AppSession` ni con una
 *    instancia construida a mano);
 * 3. la clave de sesión por análisis es un UUID conservado con `rememberSaveable` y el guardado
 *    verificado devuelve la navegación al origen;
 * 4. las tres claves nuevas existen, una sola vez y no vacías, en los 13 catálogos de idioma;
 * 5. los constructores puros de la ruta de cámara de Ángulos (encoding del token, validación de
 *    atleta/petición) y las compuertas de propiedad del intento y de arranque de una captura nueva.
 *
 * No valida composición, aspecto ni ejecución real del vídeo: eso requiere instrumentación y no se
 * ejecuta aquí.
 */
class AnglesHomeNavigationTest {

    private val appNavSource: String by lazy { read("app/src/main/java/com/openjump/app/ui/AppNav.kt") }
    private val angleEntryNavSource: String by lazy {
        read("app/src/main/java/com/openjump/app/ui/angles/AngleEntryNavigation.kt")
    }
    private val homeScreenSource: String by lazy {
        read("app/src/main/java/com/openjump/app/ui/home/HomeScreen.kt")
    }

    // --- constructor puro de la ruta ---------------------------------------------------------------

    @Test
    fun `la ruta del editor codifica la uri del video y conserva el atleta`() {
        val route = anglesEditorRoute("content://media/picker/0/video/42", 7L)
        assertEquals(
            "angles/editor?videoUri=content%3A%2F%2Fmedia%2Fpicker%2F0%2Fvideo%2F42&athleteId=7&analysisId=0",
            route,
        )
        assertTrue(route, route.startsWith("angles/editor?"))
        assertTrue(route, route.contains("&athleteId=7&"))
    }

    @Test
    fun `la ruta de recuperacion transporta el analisis guardado sin perder el atleta`() {
        // Un análisis guardado viaja con su id: 0 es «análisis nuevo» y cualquier id > 0 pide
        // reabrirlo. El argumento va SIEMPRE presente para que el patrón case en Navigation.
        val nuevo = anglesEditorRoute("content://media/picker/0/video/42", 7L)
        val guardado = anglesEditorRoute("content://media/picker/0/video/42", 7L, analysisId = 12L)

        assertEquals("0", nuevo.substringAfter("&analysisId="))
        assertEquals("12", guardado.substringAfter("&analysisId="))
        assertTrue(guardado, guardado.startsWith(nuevo.substringBefore("&analysisId=")))
        assertEquals(
            "angles/editor?videoUri=content%3A%2F%2Fv%2F1&athleteId=7&analysisId=12",
            anglesEditorRoute("content://v/1", 7L, analysisId = 12L),
        )
        // Un id de análisis no puede ser negativo y no se puede recuperar sin análisis.
        assertThrows(IllegalArgumentException::class.java) {
            anglesEditorRoute("content://v/1", 7L, analysisId = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            anglesSavedAnalysisRoute(0L, "content://v/1", 7L)
        }
    }

    @Test
    fun `todo argumento declarado en el patron viaja en la ruta construida`() {
        // Navigation no casa un destino si el patrón declara un argumento y la URI lo omite
        // (NavDeepLink.getMatchingArguments -> missingRequiredArguments). Por eso se comparan los
        // placeholders del patrón con las claves que emite el constructor, no sólo su texto.
        val declarados = Regex("""\{(\w+)\}""").findAll(ANGLES_EDITOR_ROUTE)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(setOf("videoUri", "athleteId", "analysisId"), declarados)

        for (analysisId in listOf(0L, 12L)) {
            val route = anglesEditorRoute("content://media/picker/0/video/42", 7L, analysisId)
            val emitidos = route.substringAfter('?')
                .split('&')
                .map { it.substringBefore('=') }
                .toSet()
            assertEquals("analysisId=$analysisId", declarados, emitidos)
        }
    }

    @Test
    fun `los caracteres reservados no pueden inyectar parametros ni segmentos`() {
        // '&', '=' y '?' dentro del valor romperían el patrón si no se escaparan.
        val hostile = "content://x/a&b=c?d#e f/h"
        val route = anglesEditorRoute(hostile, 1L)
        val query = route.substringAfter('?')
        assertEquals(
            "El valor debe quedar escapado en un único parámetro",
            3,
            query.split('&').size,
        )
        val encodedValue = query.substringAfter("videoUri=").substringBefore("&athleteId=")
        assertFalse(encodedValue, encodedValue.contains('&'))
        assertFalse(encodedValue, encodedValue.contains('?'))
        assertFalse(encodedValue, encodedValue.contains('#'))
        assertFalse(encodedValue, encodedValue.contains(' '))
        assertEquals(hostile, percentDecode(encodedValue))
    }

    @Test
    fun `los caracteres no reservados y el utf8 se codifican de forma reversible`() {
        assertEquals("abcXYZ019-._~", encodeRouteArgument("abcXYZ019-._~"))
        assertEquals("v%C3%ADdeo%20a%C3%B1o", encodeRouteArgument("vídeo año"))
        assertEquals("%25", encodeRouteArgument("%"))
        assertEquals("", encodeRouteArgument(""))
        val samples = listOf(
            "content://media/picker/0/com.android.providers.media.photopicker/media/1000000039",
            "content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2Fa.mp4",
            "content://x/a+b&c=d?e#f g/h",
            "vídeo con acentos y 日本語",
        )
        for (sample in samples) {
            assertEquals(sample, percentDecode(encodeRouteArgument(sample)))
        }
    }

    @Test
    fun `el patron de ruta declara los dos argumentos y queda fuera de la barra inferior`() {
        assertTrue(ANGLES_EDITOR_ROUTE, ANGLES_EDITOR_ROUTE.contains("{videoUri}"))
        assertTrue(ANGLES_EDITOR_ROUTE, ANGLES_EDITOR_ROUTE.contains("{athleteId}"))
        assertTrue(ANGLES_EDITOR_ROUTE, ANGLES_EDITOR_ROUTE.startsWith("angles/editor?"))
        assertEquals("videoUri", ANGLES_VIDEO_URI_ARG)
        assertEquals("athleteId", ANGLES_ATHLETE_ID_ARG)
        // Flujo a pantalla completa: ni barra inferior ni entrada de nivel superior.
        assertFalse(isBottomBarVisible(ANGLES_EDITOR_ROUTE))
        assertFalse(isBottomBarVisible("angles/editor"))
        assertFalse(topLevelRouteOrder().contains("angles/editor"))
    }

    @Test
    fun `una uri vacia o un atleta invalido no producen destino`() {
        assertThrows(IllegalArgumentException::class.java) { anglesEditorRoute("   ", 1L) }
        assertThrows(IllegalArgumentException::class.java) { anglesEditorRoute("content://x", 0L) }
        assertThrows(IllegalArgumentException::class.java) { anglesEditorRoute("content://x", -3L) }
    }

    // --- entrada de Home --------------------------------------------------------------------------

    @Test
    fun `home ofrece la entrada de angulos junto a saltos con el selector existente`() {
        val home = block(homeScreenSource, "fun HomeScreen(")
        assertTrue(
            "HomeScreen debe exponer la intención de ángulos",
            home.contains("onAnglesSelected: () -> Unit"),
        )
        val body = homeScreenSource
        val jumpsEntry = body.indexOf("JumpEntry(onJumpsSelected)")
        val anglesEntry = body.indexOf("AnglesEntry(onAnglesSelected)")
        val encoderEntry = body.indexOf("EncoderEntry(onEncoderSelected)")
        assertTrue("La entrada de ángulos debe existir", anglesEntry > 0)
        assertTrue(
            "Ángulos va justo después de Saltos",
            anglesEntry in (jumpsEntry + 1) until encoderEntry,
        )
        assertTrue(
            "La fila debe usar los textos nuevos",
            body.contains("R.string.home_angles_title") &&
                body.contains("R.string.home_angles_description"),
        )
    }

    // --- ruta del editor --------------------------------------------------------------------------

    @Test
    fun homeAbreLaPreparacionDeAngulosYNoLanzaElSelectorAlEntrar() {
        val home = block(appNavSource, "composable(HOME_ROUTE) {")
        assertTrue(
            "Home debe abrir la introducción de Ángulos, no el selector ni la cámara",
            home.contains("onAnglesSelected = { navController.navigate(ANGLES_INTRO_ROUTE) }"),
        )
        assertFalse(
            "Entrar en Ángulos no puede lanzar ninguna actividad externa",
            home.contains("rememberLauncherForActivityResult") || home.contains("anglesVideoPicker"),
        )
        assertFalse("Home no abre directamente el editor", home.contains("anglesEditorRoute("))
        // El picker vive en la introducción: uno por INTENTO y VideoOnly, nunca al entrar.
        assertTrue(
            "La introducción es la única que abre el picker existente",
            angleEntryNavSource.contains("rememberLauncherForActivityResult") &&
                angleEntryNavSource.contains("ActivityResultContracts.PickVisualMedia()") &&
                angleEntryNavSource.contains(
                    "PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)",
                ),
        )
        assertTrue(
            "El lanzamiento se marca antes de abrir la actividad externa",
            angleEntryNavSource.contains("launched.value = true"),
        )
        val introDestination = block(angleEntryNavSource, "fun AnglesIntroDestination(")
        assertTrue(
            "Grabar congela el ticket antes de navegar a la cámara propia",
            introDestination.contains("val ticket = handoff.begin(AngleEntryKind.RECORD)") &&
                introDestination.contains(
                    "anglesCameraRoute(ticket.originToken, ticket.athleteId, ticket.requestId)",
                ),
        )
        assertTrue(
            "Elegir atleta lee el estado FRESCO del actor y no exige canBegin",
            introDestination.contains("val fresh = handoff.state.value") &&
                introDestination.contains("fresh is Idle || fresh is Rejected") &&
                introDestination.contains("handoff.canBegin()").not(),
        )
        assertTrue(
            "La escritura global sólo ocurre tras aceptar el actor (elección explícita)",
            introDestination.contains("handoff.selectAthlete(athleteId)") &&
                introDestination.contains("app.selectedAthleteStore.select(app.athleteRepository, athleteId)"),
        )
        assertTrue(
            "La ayuda tiene su propio destino y comparte el modelo de la entrada",
            introDestination.contains("navController.navigate(ANGLES_HELP_ROUTE)"),
        )
        assertTrue(
            "La importación espera a Ready y reclama una sola vez antes de abrir el editor",
            introDestination.contains("val ready = state as? Ready ?: return@LaunchedEffect") &&
                introDestination.contains("handoff.claimReady(ready.transfer.originToken)") &&
                introDestination.contains(
                    "navController.navigate(anglesEditorRoute(transfer.uri, transfer.athleteId))",
                ) &&
                // `introResumed` despierta el efecto; la autoridad fresca se consulta en el efecto.
                introDestination.contains("LaunchedEffect(state, introResumed)") &&
                introDestination.contains("if (!introOwnsForeground()) return@LaunchedEffect"),
        )
        assertFalse(
            "Un booleano recordado no puede ser la única autoridad de la reclamación",
            introDestination.contains("if (!introResumed) return@LaunchedEffect"),
        )
        assertTrue(
            "El retorno del editor/ayuda se detecta con un observador estable ON_RESUME",
            introDestination.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)") &&
                introDestination.contains("handoff.returnToIntro(handedOff.transfer.originToken)"),
        )
        assertTrue(
            "Volver de la intro no inicia nada y abandona el intento pendiente antes del pop",
            introDestination.contains("handoff.cancel()") &&
                introDestination.contains("navController.popBackStack()") &&
                introDestination.indexOf("handoff.cancel()") <
                introDestination.indexOf("navController.popBackStack()") &&
                introDestination.contains("onImport = {") &&
                introDestination.contains("handoff.begin(AngleEntryKind.IMPORT)"),
        )
    }

    @Test
    fun laCamaraDeAngulosEsSuRutaPropiaConContextoStandalone() {
        val camera = block(angleEntryNavSource, "fun AnglesCameraDestination(")
        assertTrue("La cámara reutilizada declara el contexto standalone", camera.contains("captureContext = CameraCaptureContext.Standalone"))
        assertTrue(
            "El arranque de una captura nueva se lee FRESCO del actor",
            camera.contains("state = handoff.state.value") &&
                camera.contains("ownEntryResumed = entry.lifecycle.currentState == Lifecycle.State.RESUMED"),
        )
        assertTrue(
            "La URI entregada completa el MISMO ticket RECORD, nunca un begin(IMPORT)",
            camera.contains("handoff.completeResult(request.token, uri.toString())") &&
                camera.contains("handoff.begin(").not(),
        )
        assertTrue(
            "La navegación al editor es por la transferencia reclamada, con cámara fuera de la pila",
            camera.contains("val transfer = handoff.claimReady(request.token) ?: return@LaunchedEffect") &&
                camera.contains("anglesEditorRoute(transfer.uri, transfer.athleteId)") &&
                camera.contains("popUpTo(ANGLES_CAMERA_ROUTE) { inclusive = true }"),
        )
        assertTrue(
            "Back/cancel preserva la selección y no libera el lease de inmediato",
            camera.contains("handoff.cancel(request.token)") &&
                camera.contains("handoff.returnToIntro(").not(),
        )
        assertFalse(
            "La entrada de Ángulos no usa AppSession ni la ruta camera existente",
            angleEntryNavSource.contains("AppSession.") ||
                angleEntryNavSource.contains("navController.navigate(\"camera\")") ||
                angleEntryNavSource.contains("navController.navigate(CAMERA_ROUTE)") ||
                angleEntryNavSource.contains("\"selector\""),
        )
        // El literal se centralizó en CAMERA_ROUTE: el valor compilado debe seguir siendo el mismo.
        assertEquals("camera", CAMERA_ROUTE)
        // El Scaffold sólo cede los insets al patrón exacto: ambas rutas de cámara y cero insets.
        assertTrue(
            "Los insets a cero cubren la ruta propia de cámara de Ángulos",
            Regex(
                """contentWindowInsets\s*=\s*if\s*\(\s*currentRoute\s*==\s*CAMERA_ROUTE\s*\|\|\s*currentRoute\s*==\s*ANGLES_CAMERA_ROUTE\s*\)\s*\{\s*WindowInsets\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)""",
            ).containsMatchIn(appNavSource),
        )
    }

    // --- ruta de cámara de Ángulos -----------------------------------------------------------------

    private val cameraRequest = AnglesCameraRequest(token = "token-1", athleteId = 7L, requestId = 3L)

    @Test
    fun laRutaDeCamaraDeAngulosCodificaElTokenYExigePeticionValida() {
        val route = anglesCameraRoute("token/uno & dos", 7L, 3L)
        assertEquals(
            "angles/camera?token=token%2Funo%20%26%20dos&athleteId=7&requestId=3",
            route,
        )
        // Es una ruta PROPIA: ni la ruta `camera` existente ni la del editor.
        assertNotEquals("camera", ANGLES_CAMERA_ROUTE.substringBefore('?'))
        assertFalse(isBottomBarVisible(ANGLES_CAMERA_ROUTE))
        assertFalse(topLevelRouteOrder().contains(ANGLES_CAMERA_ROUTE.substringBefore('?')))
        // El patrón declara los tres argumentos y la URI construida los emite todos.
        val declared = Regex("""\{(\w+)\}""").findAll(ANGLES_CAMERA_ROUTE).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("token", "athleteId", "requestId"), declared)
        val emitted = route.substringAfter('?').split('&').map { it.substringBefore('=') }.toSet()
        assertEquals(declared, emitted)
        // Ni un token vacío ni un atleta/petición no positivos producen destino.
        assertThrows(IllegalArgumentException::class.java) { anglesCameraRoute("  ", 7L, 3L) }
        assertThrows(IllegalArgumentException::class.java) { anglesCameraRoute("token-1", 0L, 3L) }
        assertThrows(IllegalArgumentException::class.java) { anglesCameraRoute("token-1", -2L, 3L) }
        assertThrows(IllegalArgumentException::class.java) { anglesCameraRoute("token-1", 7L, 0L) }
    }

    @Test
    fun laRutaDeCamaraRechazaArgumentosAusentesOVacios() {
        assertEquals(
            cameraRequest,
            parseAnglesCameraRequest("token-1", "7", "3"),
        )
        for (invalid in listOf(
            parseAnglesCameraRequest(null, "7", "3"),
            parseAnglesCameraRequest(" ", "7", "3"),
            parseAnglesCameraRequest("token-1", null, "3"),
            parseAnglesCameraRequest("token-1", "0", "3"),
            parseAnglesCameraRequest("token-1", "siete", "3"),
            parseAnglesCameraRequest("token-1", "7", null),
            parseAnglesCameraRequest("token-1", "7", "-1"),
        )) {
            assertEquals(null, invalid)
        }
    }

    @Test
    fun laRutaSoloEsDueniaDelIntentoMientrasElTicketCongeladoCoincide() {
        val ticket = AngleEntryTicket(
            requestId = cameraRequest.requestId,
            originToken = cameraRequest.token,
            kind = AngleEntryKind.RECORD,
            athleteId = cameraRequest.athleteId,
        )
        val transfer = AngleEntryTransfer(
            requestId = ticket.requestId,
            originToken = ticket.originToken,
            kind = ticket.kind,
            athleteId = ticket.athleteId,
            uri = "content://media/1",
        )
        val foreign = cameraRequest.copy(token = "otro-token")
        assertTrue(anglesCameraRouteOwnsIntake(AngleEntryHandoffState.Idle, cameraRequest).not())
        assertTrue(
            anglesCameraRouteOwnsIntake(AngleEntryHandoffState.AwaitingCapture(ticket), cameraRequest),
        )
        assertFalse(
            anglesCameraRouteOwnsIntake(AngleEntryHandoffState.AwaitingCapture(ticket), foreign),
        )
        // La cámara sigue viva durante la transferencia (no se recrea ni se poppea al aceptar URI).
        assertTrue(anglesCameraRouteOwnsIntake(AngleEntryHandoffState.Acquiring(transfer), cameraRequest))
        assertTrue(anglesCameraRouteOwnsIntake(AngleEntryHandoffState.Ready(transfer), cameraRequest))
        assertTrue(anglesCameraRouteOwnsIntake(AngleEntryHandoffState.HandedOff(transfer), cameraRequest))
        assertFalse(anglesCameraRouteOwnsIntake(AngleEntryHandoffState.Ready(transfer), foreign))
        // El cierre transitorio del rechazo se tolera sin pop prematuro; el rechazo propio vuelve a la intro.
        assertTrue(
            anglesCameraRouteOwnsIntake(
                AngleEntryHandoffState.Releasing(cameraRequest.requestId),
                cameraRequest,
            ),
        )
        assertTrue(
            anglesCameraRouteOwnsIntake(
                AngleEntryHandoffState.Rejected(cameraRequest.requestId, com.openjump.app.ui.angles.AngleEntryError.ATHLETE_UNAVAILABLE),
                cameraRequest,
            ),
        )
        assertFalse(
            anglesCameraRouteOwnsIntake(
                AngleEntryHandoffState.Rejected(cameraRequest.requestId + 1, com.openjump.app.ui.angles.AngleEntryError.ATHLETE_UNAVAILABLE),
                cameraRequest,
            ),
        )
    }

    @Test
    fun soloSePuedeGrabarUnaVezConElMismoIntentoYLaEntradaEnResumed() {
        val ticket = AngleEntryTicket(
            requestId = cameraRequest.requestId,
            originToken = cameraRequest.token,
            kind = AngleEntryKind.RECORD,
            athleteId = cameraRequest.athleteId,
        )
        val transfer = AngleEntryTransfer(
            requestId = ticket.requestId,
            originToken = ticket.originToken,
            kind = ticket.kind,
            athleteId = ticket.athleteId,
            uri = "content://media/1",
        )
        assertFalse(
            "Sin RESUMED no se arranca otra grabación",
            canStartNewAngleCapture(AngleEntryHandoffState.AwaitingCapture(ticket), cameraRequest, ownEntryResumed = false),
        )
        assertTrue(
            canStartNewAngleCapture(AngleEntryHandoffState.AwaitingCapture(ticket), cameraRequest, ownEntryResumed = true),
        )
        assertFalse(
            "Un token de otro intento no puede arrancar",
            canStartNewAngleCapture(
                AngleEntryHandoffState.AwaitingCapture(ticket),
                cameraRequest.copy(token = "otro"),
                ownEntryResumed = true,
            ),
        )
        // Tras aceptar la primera URI no hay segunda grabación, aunque la UI no se haya recompuesto.
        for (state in listOf(
            AngleEntryHandoffState.Acquiring(transfer),
            AngleEntryHandoffState.Ready(transfer),
            AngleEntryHandoffState.HandedOff(transfer),
            AngleEntryHandoffState.Releasing(cameraRequest.requestId),
            AngleEntryHandoffState.Rejected(cameraRequest.requestId, com.openjump.app.ui.angles.AngleEntryError.ATHLETE_VALIDATION_FAILED),
            AngleEntryHandoffState.Idle,
        )) {
            assertFalse(state.toString(), canStartNewAngleCapture(state, cameraRequest, ownEntryResumed = true))
        }
    }

    // --- política de consumo: la cámara sólo se autentica con un intento RECORD propio ----------

    private fun recordTicket(
        token: String = cameraRequest.token,
        athleteId: Long = cameraRequest.athleteId,
        requestId: Long = cameraRequest.requestId,
    ) = AngleEntryTicket(requestId, token, AngleEntryKind.RECORD, athleteId)

    private fun importTicket(
        token: String = cameraRequest.token,
        athleteId: Long = cameraRequest.athleteId,
        requestId: Long = cameraRequest.requestId,
    ) = AngleEntryTicket(requestId, token, AngleEntryKind.IMPORT, athleteId)

    private fun transfer(ticket: AngleEntryTicket) = AngleEntryTransfer(
        requestId = ticket.requestId,
        originToken = ticket.originToken,
        kind = ticket.kind,
        athleteId = ticket.athleteId,
        uri = "content://media/picker/0/video/1",
    )

    /** Los cuatro estados vivos que llevan ticket/transferencia con `kind` y token. */
    private fun liveStates(ticket: AngleEntryTicket): List<AngleEntryHandoffState> {
        val transfer = transfer(ticket)
        return listOf(
            AngleEntryHandoffState.AwaitingCapture(ticket),
            AngleEntryHandoffState.Acquiring(transfer),
            AngleEntryHandoffState.Ready(transfer),
            AngleEntryHandoffState.HandedOff(transfer),
        )
    }

    @Test
    fun `un intento IMPORT nunca es duenio ni arranca la camara de angulos`() {
        // El intento de importación puede coincidir en token, atleta Y requestId con la ruta: aun
        // así el consumo es RECORD, así que la cámara propia no puede abrirse ni grabar por él.
        val import = importTicket()
        assertEquals(cameraRequest.token, import.originToken)
        assertEquals(cameraRequest.athleteId, import.athleteId)
        assertEquals(cameraRequest.requestId, import.requestId)
        for (state in liveStates(import)) {
            assertFalse(
                "$state: un ticket IMPORT no autentica la cámara de Ángulos",
                anglesCameraRouteOwnsIntake(state, cameraRequest),
            )
            assertFalse(
                "$state: el picker de importación no puede arrancar una grabación",
                canStartNewAngleCapture(state, cameraRequest, ownEntryResumed = true),
            )
        }
    }

    @Test
    fun `solo un RECORD con token y atleta exactos es duenio de la camara`() {
        val record = recordTicket()
        for (state in liveStates(record)) {
            assertTrue("$state: un RECORD propio sí es dueño", anglesCameraRouteOwnsIntake(state, cameraRequest))
        }
        // Nonce (token) ajeno, atleta ajeno o petición ajena: nunca dueño, en ningún estado.
        val foreignRequests = listOf(
            cameraRequest.copy(token = "otro-token"),
            cameraRequest.copy(athleteId = cameraRequest.athleteId + 1L),
            cameraRequest.copy(requestId = cameraRequest.requestId + 1L),
        )
        for (foreign in foreignRequests) {
            for (state in liveStates(record)) {
                assertFalse("$state / $foreign no es dueño", anglesCameraRouteOwnsIntake(state, foreign))
            }
            assertFalse(
                "$foreign no puede arrancar una captura",
                canStartNewAngleCapture(liveStates(record).first(), foreign, ownEntryResumed = true),
            )
        }
        // Un RECORD exacto pero en PAUSA no arranca una captura nueva.
        assertTrue(canStartNewAngleCapture(liveStates(record).first(), cameraRequest, ownEntryResumed = true))
        assertFalse(canStartNewAngleCapture(liveStates(record).first(), cameraRequest, ownEntryResumed = false))
    }

    // --- liveness: autoridad FRESCA de entrada (guards STATIC, no prueba runtime) -----------------
    //
    // Estos guards leen la FORMA declarada del fuente: no demuestran el comportamiento en ejecución
    // (eso exige el test de navegación instrumentado de C2). Sirven para que un booleano recordado
    // no vuelva a ser la única autoridad de reclamar, navegar, popear o escribir estado global.

    @Test
    fun `la liveness de la intro se reevalua con identidad fresca de la entrada`() {
        val intro = section(
            angleEntryNavSource,
            "fun AnglesIntroDestination(",
            "private fun AngleImportLauncher(",
        )
        // Autoridad fresca: entrada ACTUAL y RESUMED, consultada en el instante de la llamada.
        assertTrue(
            "La intro debe evaluar `currentBackStackEntry === entry` + RESUMED",
            angleEntryNavSource.contains(
                "private fun NavHostController.isForegroundEntry(entry: NavBackStackEntry): Boolean",
            ) &&
                angleEntryNavSource.contains("currentBackStackEntry === entry") &&
                angleEntryNavSource.contains("entry.lifecycle.currentState == Lifecycle.State.RESUMED"),
        )
        assertTrue(intro.contains("navController.isForegroundEntry(entry)"))
        assertTrue(intro.contains("if (!introOwnsForeground()) return@LaunchedEffect"))
        // `introResumed` sigue despertando la composición, pero ya no decide por sí solo.
        assertTrue(intro.contains("LaunchedEffect(state, introResumed)"))
        assertFalse(intro.contains("if (!introResumed) return@LaunchedEffect"))
        // El observador estable compara IDENTIDAD de entrada, no el string de la ruta.
        assertFalse(intro.contains("destination?.route != ANGLES_INTRO_ROUTE"))
        assertTrue(intro.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)"))
        assertTrue(
            "El observador estable debe exigir la entrada fresca y en primer plano",
            intro.contains("if (!navController.isForegroundEntry(entry)) return@LifecycleEventEffect"),
        )
        // Efecto + los cinco callbacks (record/import/back/help/selección) + la escritura global.
        assertTrue(
            "Cada efecto/callback debe consultar la autoridad fresca",
            Regex("""introOwnsForeground\(\)""").findAll(intro).count() >= 7,
        )
    }

    @Test
    fun `la camara solo compone escena autenticada y reevalua su identidad`() {
        val camera = section(
            angleEntryNavSource,
            "fun AnglesCameraDestination(",
            "private fun rememberEntryResumed(",
        )
        // `Releasing`/`Rejected` no llevan kind/token: nunca autentican una escena nueva por requestId.
        assertTrue(camera.contains("val sceneAuthenticated = anglesCameraSceneAuthenticated(state, request)"))
        assertTrue(camera.contains("if (sceneAuthenticated) authenticatedScene = true"))
        assertTrue(
            "Sólo una escena ya autenticada sobrevive al cierre transitorio",
            camera.contains("val showCamera = sceneAuthenticated || (state is Releasing && authenticatedScene)"),
        )
        assertTrue(camera.contains("if (showCamera) {"))
        assertFalse(
            "No se puede componer la cámara (y pedir permiso) desde un requestId sin autenticar",
            camera.contains("if (ownsIntake) {") || camera.contains("if (ownsIntake) CameraScreen("),
        )
        // Identidad fresca antes de popear/reclamar; RESUMED fresco antes de navegar al editor.
        assertTrue(camera.contains("if (!navController.isCurrentEntry(entry)) return@LaunchedEffect"))
        assertTrue(camera.contains("if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return@LaunchedEffect"))
        // Los listeners de URI aceptan una entrada viva (STARTED/CREATED) y no cancelan un HandedOff.
        val uriListeners = camera.substring(
            camera.indexOf("onSaved = {"),
            camera.indexOf("onBack = {"),
        )
        assertTrue(
            "La URI sólo se acepta con esta entrada de cámara viva y actual",
            uriListeners.contains("if (navController.isCurrentEntry(entry)) {"),
        )
        assertTrue(
            "Una URI/entrega tardía no puede cancelar el lease ya entregado",
            !uriListeners.contains("handoff.cancel("),
        )
        assertTrue(camera.contains("handoff.completeResult(request.token, uri.toString())"))
        // Back de cámara: cancel explícito del MISMO token, sólo en primer plano.
        assertTrue(camera.contains("if (navController.isForegroundEntry(entry)) {"))
    }

    @Test
    fun `el picker vive hasta el resultado y solo abre en primer plano`() {
        val intro = section(
            angleEntryNavSource,
            "fun AnglesIntroDestination(",
            "private fun AngleImportLauncher(",
        )
        val launcher = section(
            angleEntryNavSource,
            "private fun AngleImportLauncher(",
            "fun AnglesHelpDestination(",
        )
        // El REGISTRO se compone por INTENTO (key por token), sin condición de ciclo de vida: el
        // picker externo pausa la intro y retirar la composición perdería el resultado.
        assertTrue(
            "El registro del picker debe componerse por intento, no por estado de ciclo de vida",
            Regex(
                """if \(awaitingImport != null\) \{\s*key\(awaitingImport\.ticket\.originToken\) \{\s*AngleImportLauncher\(""",
            ).containsMatchIn(intro),
        )
        assertTrue(launcher.contains("val introResumed = rememberEntryResumed(entry)"))
        assertTrue(launcher.contains("LaunchedEffect(ticket.originToken, introResumed)"))
        assertTrue(
            "Sólo se abre el picker desde la entrada actual y RESUMED",
            launcher.contains("if (!navController.isForegroundEntry(entry)) return@LaunchedEffect"),
        )
        // La marca guardada de lanzamiento va ANTES de abrir la actividad externa, y sólo se marca
        // cuando de verdad se lanza (el guard de primer plano va antes de la marca).
        val guard = launcher.indexOf("isForegroundEntry")
        val mark = launcher.indexOf("launched.value = true")
        val launch = launcher.indexOf("launcher.launch(")
        assertTrue("El guard de primer plano debe ir antes de marcar", guard in 0 until mark)
        assertTrue("La marca va antes de abrir la actividad externa", mark in 0 until launch)
        assertTrue("Un lanzamiento fallido no deja la marca puesta", launcher.contains("launched.value = false"))
    }

    @Test
    fun `la uri del picker solo se acepta con la intro current y no cancela un intento entregado`() {
        val intro = section(
            angleEntryNavSource,
            "fun AnglesIntroDestination(",
            "private fun AngleImportLauncher(",
        )
        val callback = intro.substring(
            intro.indexOf("onResult = {"),
            intro.indexOf("onLaunchFailed = {"),
        )
        assertTrue("El callback del picker debe existir: $callback", callback.isNotBlank())
        // Gap conocido tras S2C1F: el resultado del picker se completaba INCONDICIONALMENTE, así que
        // una introducción inactiva (p. ej. Ayuda delante) podía adquirir grant y publicar Ready.
        assertTrue(
            "El resultado del picker debe decidirse por identidad FRESCA de la entrada: $callback",
            callback.contains("if (navController.isCurrentEntry(entry)) {"),
        )
        val guard = callback.indexOf("isCurrentEntry")
        val complete = callback.indexOf("handoff.completeResult(token, uri)")
        assertTrue(
            "La comprobación de entrada va ANTES de completar el intento: $callback",
            guard in 0 until complete,
        )
        // Si otra entrada está delante sólo se descarta el intento PENDIENTE de ese token: una URI ya
        // aceptada (Acquiring/Ready/HandedOff) conserva su lease y nunca se cancela desde aquí.
        assertTrue(
            "Sólo se cancela el pending AwaitingCapture del mismo token: $callback",
            callback.contains("if (fresh is AwaitingCapture && fresh.ticket.originToken == token) {"),
        )
        assertTrue(callback.contains("handoff.cancel(token)"))
        assertFalse(
            "El callback no puede completar ni cancelar sin comprobar la entrada: $callback",
            callback.contains("onResult = { token, uri -> handoff.completeResult(token, uri) }"),
        )
    }

    @Test
    fun `el fallo de lanzamiento del picker es un error local recuperable`() {
        val intro = section(
            angleEntryNavSource,
            "fun AnglesIntroDestination(",
            "private fun AngleImportLauncher(",
        )
        assertTrue(intro.contains("var startBlocked by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(
            "El fallo se muestra con el texto localizado existente",
            intro.contains("stringResource(R.string.angle_intro_start_blocked)"),
        )
        val onLaunchFailed = intro.substringAfter("onLaunchFailed = { token ->").substringBefore("},")
        assertTrue(
            "El fallo cancela el intento y enciende el error local",
            onLaunchFailed.contains("startBlocked = true"),
        )
        assertFalse("No se registra la URI ni se inventa un resultado", onLaunchFailed.contains("completeResult"))
        // Se limpia al reintentar (record/import) y al aceptar una selección válida.
        assertEquals(3, Regex("""startBlocked = false""").findAll(intro).count())
    }

    // --- ruta del editor --------------------------------------------------------------------------
    @Test
    fun `la ruta abre el viewmodel real y no usa AppSession`() {
        val route = block(appNavSource, "composable(ANGLES_EDITOR_ROUTE) {")
        assertTrue(
            "El editor debe abrirse con el ViewModel real de producción",
            route.contains("AngleEditorViewModel.factory(angleDefaults)"),
        )
        assertFalse(
            "No se debe construir el ViewModel a mano (evita saltarse la puerta transaccional)",
            route.contains("AngleEditorViewModel("),
        )
        assertTrue(
            "La apertura usa la petición existente del editor",
            route.contains("AngleEditorRequest("),
        )
        assertTrue(
            "La URI y el atleta vienen de la ruta, no de un borrador compartido",
            route.contains("uri = videoUri") && route.contains("athleteId = athleteId"),
        )
        assertFalse(
            "El editor de ángulos no usa AppSession",
            route.contains("AppSession."),
        )
        assertTrue(
            "La pantalla existente se reutiliza sin rutas paralelas",
            route.contains("AngleEditorScreen("),
        )
        assertTrue(
            "La salida vuelve al origen",
            route.contains("onCancel = { navController.popBackStack() }"),
        )
    }

    @Test
    fun `la clave de sesion es un uuid por analisis conservado por rememberSaveable`() {
        val route = block(appNavSource, "composable(ANGLES_EDITOR_ROUTE) {")
        assertTrue(
            "Un UUID por análisis, conservado ante recreación/muerte de proceso",
            route.contains("rememberSaveable { UUID.randomUUID().toString() }"),
        )
        assertTrue(
            "La petición usa esa clave",
            route.contains("sessionKey = analysisSessionKey"),
        )
        // Un solo `rememberSaveable` de clave y una sola apertura: no se reabre el vídeo por recomposición.
        assertEquals(1, Regex("""rememberSaveable\s*\{""").findAll(route).count())
        assertEquals(1, Regex("""\.open\(""").findAll(route).count())
    }

    @Test
    fun `solo un guardado verificado devuelve la navegacion al origen`() {
        val route = block(appNavSource, "composable(ANGLES_EDITOR_ROUTE) {")
        assertTrue(
            "El guardado verificado se observa desde el VM real",
            route.contains("angleEditorViewModel.saveState.collectAsState()"),
        )
        assertTrue(
            "Solo `Saved` navega",
            route.contains("if (angleSaveState is AngleSaveState.Saved) navController.popBackStack()"),
        )
        // Un conflicto/borrado/fallo no navega: no se puede perder la edición sin mensaje.
        assertFalse(route, route.contains("AngleSaveState.Failed"))
        assertFalse(route, route.contains("AngleSaveState.Conflict"))
        assertFalse(route, route.contains("AngleSaveState.Deleted"))
    }

    @Test
    fun `el editor reabre el analisis guardado una sola vez y solo con el video listo`() {
        val route = block(appNavSource, "composable(ANGLES_EDITOR_ROUTE) {")
        // Una sola llamada de reapertura: la reapertura sustituye ancla, revisión y marcas, así que
        // repetirla por recomposición perdería el estado que el usuario acaba de abrir.
        assertEquals(1, Regex("""\.reopen\(""").findAll(route).count())
        assertTrue(
            "El guard de una sola reapertura debe ser estado de composición, no rememberSaveable",
            route.contains("var angleReopenRequested by remember { mutableStateOf(false) }"),
        )
        assertTrue(route.contains("analysisId > 0L"))
        assertTrue(route.contains("angleVideoState is AngleVideoState.Ready"))
        val guardIndex = route.indexOf("angleReopenRequested = true")
        val reopenIndex = route.indexOf(".reopen(")
        assertTrue("El guard debe activarse antes de reabrir", guardIndex >= 0 && guardIndex < reopenIndex)
    }

    @Test
    fun `history abre el detalle guardado de la fila de angulos`() {
        val history = block(appNavSource, "composable(HISTORY_ROUTE) {")
        assertTrue(
            "HISTORY debe recibir la intención de consulta",
            history.contains("onAngleAnalysisSelected = { entry ->"),
        )
        assertTrue(
            "La fila de ángulos navega al detalle guardado, con vídeo o sin él",
            history.contains("anglesSavedDetailRoute(entry.id)"),
        )
        assertFalse(
            "El click no puede volver a depender de la validez del vídeo",
            history.contains("videoUri.isNullOrBlank()"),
        )
    }

    // --- catálogos de idioma ----------------------------------------------------------------------

    @Test
    fun `las claves nuevas existen una sola vez y no vacias en los trece catalogos`() {
        assertEquals("Se esperaban 13 catálogos", 13, LOCALES.size)
        val keys = listOf("home_angles_title", "home_angles_description", "angle_default_name_prefix")
        for ((locale, path) in LOCALES) {
            val strings = parseStrings(path)
            for (key in keys) {
                val values = strings[key]
                assertTrue("[$locale] falta '$key'", values != null)
                assertEquals("[$locale] '$key' debe aparecer una vez", 1, values!!.size)
                assertTrue("[$locale] '$key' no puede estar vacío", values.single().isNotBlank())
            }
        }
        // El nombre por defecto de un ángulo nuevo no puede ser el título plural de la pantalla.
        val base = parseStrings(LOCALES.getValue("values"))
        assertNotEquals(
            base.getValue("angle_list_title").single(),
            base.getValue("angle_default_name_prefix").single(),
        )
    }

    // --- utilidades -------------------------------------------------------------------------------

    /**
     * Devuelve el bloque de código que empieza en [marker] hasta el siguiente `composable(` de la
     * misma indentación (o el final del fichero). Evita depender de números de línea.
     */
    private fun block(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("No se encuentra '$marker' en el fuente", start >= 0)
        val next = source.indexOf("\n            composable(", start + marker.length)
        return if (next > start) source.substring(start, next) else source.substring(start)
    }

    /**
     * Devuelve el texto entre [startMarker] y [endMarker] (marcador final excluido). Sirve para
     * aislar una función concreta del fuente sin depender de números de línea ni de la indentación
     * del `NavHost` (que no existe en este fichero).
     */
    private fun section(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("No se encuentra '$startMarker' en el fuente", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("No se encuentra '$endMarker' después de '$startMarker'", end > start)
        return source.substring(start, end)
    }

    private fun percentDecode(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                assertTrue("Escape incompleto en '$value'", index + 2 < value.length)
                bytes.add(value.substring(index + 1, index + 3).toInt(16).toByte())
                index += 3
            } else {
                bytes.add(char.code.toByte())
                index += 1
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun parseStrings(path: String): Map<String, List<String>> {
        val file = resolve(path)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // Parser sin soporte para esta feature: el parseo normal sigue siendo válido aquí.
        }
        val document = try {
            factory.newDocumentBuilder().parse(file)
        } catch (failure: Exception) {
            throw AssertionError("No se pudo parsear $path (${file.absolutePath})", failure)
        }
        val byName = LinkedHashMap<String, MutableList<String>>()
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            byName.getOrPut(element.getAttribute("name")) { mutableListOf() }
                .add(element.textContent ?: "")
        }
        return byName
    }

    private fun read(path: String): String = resolve(path).readText()

    private fun resolve(path: String): File {
        val relative = path.removePrefix("app/")
        val tried = mutableListOf<File>()
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, path), File(directory, relative))) {
                tried += candidate
                if (candidate.isFile) return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError(
            "No se encuentra '$path'. Directorio de trabajo: ${File(".").absolutePath}. " +
                "Rutas probadas: ${tried.joinToString { it.absolutePath }}",
        )
    }

    private companion object {
        val LOCALES = linkedMapOf(
            "values" to "app/src/main/res/values/strings.xml",
            "values-es" to "app/src/main/res/values-es/strings.xml",
            "values-en" to "app/src/main/res/values-en/strings.xml",
            "values-b+es+419" to "app/src/main/res/values-b+es+419/strings.xml",
            "values-de" to "app/src/main/res/values-de/strings.xml",
            "values-fr" to "app/src/main/res/values-fr/strings.xml",
            "values-it" to "app/src/main/res/values-it/strings.xml",
            "values-ja" to "app/src/main/res/values-ja/strings.xml",
            "values-ko" to "app/src/main/res/values-ko/strings.xml",
            "values-pl" to "app/src/main/res/values-pl/strings.xml",
            "values-pt-rBR" to "app/src/main/res/values-pt-rBR/strings.xml",
            "values-pt-rPT" to "app/src/main/res/values-pt-rPT/strings.xml",
            "values-tr" to "app/src/main/res/values-tr/strings.xml",
        )
    }
}
