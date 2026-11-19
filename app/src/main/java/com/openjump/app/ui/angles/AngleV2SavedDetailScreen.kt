package com.openjump.app.ui.angles

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.openjump.app.R
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.components.AthleteAvatar
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Detalle compacto y de SOLO LECTURA de un análisis de ángulos multi-fotograma guardado.
 *
 * Muestra TODOS los fotogramas en su orden estable, cada uno con su imagen anotada (cuando se puede
 * verificar) y sus marcas estructuradas con los grados recalculados. No abre ni pide el vídeo
 * original, no ofrece editar, añadir fotogramas ni borrar: el resultado guardado es inmutable y esta
 * pantalla sólo lo consulta.
 *
 * Lo único que puede salir de la app son **copias PNG ya verificadas**: la de un fotograma cuando el
 * usuario la comparte desde su tarjeta, o el conjunto completo y ordenado del análisis cuando usa
 * «compartir todas las imágenes» de la cabecera. Nunca sale el vídeo, ni el almacén privado, ni un
 * árbol de directorios. Si un solo fotograma no tiene imagen verificable, el envío múltiple no se
 * ofrece: se informa del fallo y no se entrega un conjunto incompleto.
 *
 * La imagen de cada fotograma se verifica (tamaño + SHA-256 contra la fila) y se decodifica con
 * presupuesto acotado ANTES de mostrarse. Si falta, no coincide o no se puede decodificar sin
 * exceder el presupuesto, se informa en su sitio y las mediciones de ese fotograma siguen visibles:
 * nunca se sustituye por otra imagen ni se borra nada.
 *
 * La pantalla es deliberadamente distinta del detalle legacy (`AngleSavedDetailScreen`): los `id` de
 * `angle_analysis_v2_roots` y `angle_analyses` pueden coincidir numéricamente y no son
 * intercambiables.
 */
@Composable
fun AngleV2SavedDetailScreen(
    rootId: Long,
    viewModel: AngleV2SavedDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val shareAllBusy by viewModel.shareAllBusy.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Un solo vuelo compartido entre las dos acciones: mientras una prepara, la otra no arranca.
    val frameShareBusy = remember { mutableStateOf(false) }
    val shareFrame = rememberAngleFrameShare(viewModel, snackbarHostState, frameShareBusy)
    val shareAll = rememberAngleShareAll(viewModel, snackbarHostState, frameShareBusy)
    LaunchedEffect(rootId) { viewModel.load(rootId) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.angle_list_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val current = state) {
            AngleV2SavedDetailState.Loading -> DetailColumn(padding) {
                Text(
                    stringResource(R.string.angle_saved_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AngleV2SavedDetailState.Missing -> DetailColumn(padding) {
                EmptyState(
                    stringResource(R.string.angle_saved_missing_title),
                    stringResource(R.string.angle_saved_missing_body),
                )
                BackButton(onBack)
            }

            AngleV2SavedDetailState.Error -> DetailColumn(padding) {
                EmptyState(
                    stringResource(R.string.angle_saved_error_title),
                    stringResource(R.string.angle_saved_error_body),
                )
                Button(
                    onClick = viewModel::retry,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.common_retry))
                }
                BackButton(onBack)
            }

            // La raíz pedida manda sobre lo publicado: mientras `load(rootId)` todavía no ha
            // sustituido el contenido anterior, no se pinta el de OTRA raíz (ni su imagen ni su
            // cabecera) para no mostrar datos de un análisis bajo el id de otro.
            is AngleV2SavedDetailState.Loaded -> if (current.content.rootId != rootId) {
                DetailColumn(padding) {
                    Text(
                        stringResource(R.string.angle_saved_loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AngleV2SavedDetailContentHost(
                    content = current.content,
                    imageLoader = viewModel::image,
                    shareEnabled = !frameShareBusy.value,
                    shareAllBusy = shareAllBusy,
                    onShare = shareFrame,
                    onShareAll = shareAll,
                    padding = padding,
                )
            }
        }
    }
}

/**
 * Host REAL de contenido ya cargado: cabecera compacta, pestañas y una vista por pestaña.
 *
 * Es el cuerpo que usa [AngleV2SavedDetailScreen] cuando la raíz pedida ya está publicada. Recibe
 * contenido inmutable y la lectura de imágenes como parámetro, sin ViewModel ni base de datos, para
 * que las pruebas de Compose puedan montarlo con tres fotogramas sintéticos y observar la misma
 * composición que producción.
 *
 * Propiedad de la imagen, en un único sitio por encima de las tres ramas:
 * - la petición seleccionada ([AngleV2SavedDetailFrame.imageRequest]) se calcula aquí y una sola
 *   decodificación la publica ([rememberAngleV2SelectedFrameImage]);
 * - Análisis, la celda seleccionada de Fotogramas y el diálogo de ampliación comparten ESAT misma
 *   referencia de bitmap, así que cambiar de pestaña no vuelve a leer ni a decodificar nada;
 * - cualquier cambio de raíz, de fotograma o de integridad declarada crea un contenedor NUEVO en el
 *   mismo paso de composición (vuelve a `Loading` y cierra el diálogo) y una respuesta tardía del
 *   cargador anterior se publica en el contenedor viejo, ya descartado: no puede retargetear la
 *   imagen de otro fotograma ni la leyenda nueva.
 *
 * La selección es estado de UI puro ([AngleV2SavedDetailUiState]) identificado por `frameId`; el
 * estado que se PINTA es siempre el reconciliado contra el contenido vigente, de modo que un
 * fotograma desaparecido, una raíz recargada o una galería que deja de existir no dejan una vista
 * imposible.
 */
@Composable
internal fun AngleV2SavedDetailContentHost(
    content: AngleV2SavedDetailContent,
    imageLoader: suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage,
    shareEnabled: Boolean,
    shareAllBusy: Boolean,
    onShare: (AngleV2SavedDetailFrame) -> Unit,
    onShareAll: () -> Unit,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val frames = content.frames
    var uiState by rememberSaveable(
        content.rootId,
        stateSaver = AngleV2SavedDetailUiState.StateSaver,
    ) {
        mutableStateOf(AngleV2SavedDetailUiState.initial(frames))
    }
    // Lo que se pinta es SIEMPRE el estado reconciliado con el contenido vigente: escribir sólo en
    // las acciones del usuario evita publicar un estado intermedio imposible durante la composición.
    val state = remember(uiState, frames) { uiState.reconcile(frames) }
    val selected = state.selectedFrame(frames)
    val selectedRequest = if (selected == null) {
        null
    } else {
        remember(content.rootId, selected) { selected.imageRequest(content.rootId) }
    }
    // Hueco ÚNICO de transferencia entre la celda PULSADA de la galería y el contenedor seleccionado:
    // al tocar una celda se entrega SU imagen ya pintable y SU petición completa, y la selección
    // reutiliza la MISMA referencia de bitmap en vez de volver a leer. Se recrea con la raíz y no
    // guarda nada más que ese candidato efímero.
    val galleryHandoff = remember(content.rootId) { AngleV2GalleryImageHandoff() }
    val selectedImage = rememberAngleV2SelectedFrameImage(
        request = selectedRequest,
        frame = selected,
        load = imageLoader,
        handoff = galleryHandoff,
    )
    // Un scroll por pestaña, conservado al alternar: volver a Análisis o a Fotogramas no salta al
    // principio ni arrastra la posición de la otra vista.
    val analysisScroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val framesScroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val infoScroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    Column(modifier = modifier.fillMaxSize().padding(padding)) {
        AngleV2DetailHeader(
            content = content,
            shareAllBusy = shareAllBusy,
            shareEnabled = shareEnabled,
            onShare = onShare,
            onShareAll = onShareAll,
        )
        AngleV2DetailTabs(
            state = state,
            frames = frames,
            onSelectTab = { tab -> uiState = state.show(tab, frames) },
        )
        when (state.tab) {
            AngleV2SavedDetailTab.ANALYSIS -> LazyColumn(
                state = analysisScroll,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    top = Spacing.md,
                    bottom = Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (selected == null || selectedRequest == null) {
                    item(key = "analysis-empty") {
                        Text(
                            stringResource(R.string.angle_polish_empty_frames),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    if (AngleV2SavedDetailUiState.hasFramesTab(frames)) {
                        item(key = "analysis-selector") {
                            AngleV2FrameSelector(
                                frames = frames,
                                selected = selected,
                                onSelect = { frameId -> uiState = state.select(frames, frameId) },
                            )
                        }
                    }
                    item(key = "analysis-image") {
                        AngleV2AnalysisImagePanel(
                            frame = selected,
                            request = selectedRequest,
                            image = selectedImage,
                            // La compartición individual y la del lote comparten puerta: mientras el
                            // lote prepara, este botón también se deshabilita (antes un toque era un
                            // no-op silencioso sin ninguna señal en la UI).
                            shareEnabled = shareEnabled && !shareAllBusy,
                            onShare = { onShare(selected) },
                            // Con UN solo fotograma la cabecera comparte ESE mismo PNG: el botón
                            // inferior se oculta para no duplicar la acción. Con varios, sigue
                            // visible porque comparte el seleccionado, no el lote completo.
                            showShareAction = frames.size > 1,
                        )
                    }
                    item(key = "analysis-marks") {
                        AngleV2AlignedMarkRows(selected)
                    }
                }
            }

            AngleV2SavedDetailTab.FRAMES -> LazyColumn(
                state = framesScroll,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    top = Spacing.md,
                    bottom = Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                itemsIndexed(
                    frames,
                    key = { _, frame -> "${content.rootId}:${frame.frameId}" },
                ) { index, frame ->
                    val request = remember(content.rootId, frame) {
                        frame.imageRequest(content.rootId)
                    }
                    val isSelected = frame.frameId == selected?.frameId
                    AngleV2GalleryFrameCell(
                        index = index,
                        frame = frame,
                        request = request,
                        selected = isSelected,
                        selectedImage = if (isSelected) selectedImage else null,
                        listState = framesScroll,
                        imageLoader = imageLoader,
                        // Transferencia SÓLO al pulsar ESTA celda: se publica lo que ESTA celda tiene
                        // ya pintable con su petición completa y, en el mismo toque, cambia la
                        // selección. Otra celda visible que termine de cargar no ofrece nada, así
                        // que no puede desplazar el candidato de la que el usuario tocó.
                        onSelect = { transferRequest, transferImage ->
                            galleryHandoff.offer(transferRequest, transferImage)
                            uiState = state.select(frames, frame.frameId)
                        },
                        onDisposed = galleryHandoff::clear,
                    )
                }
            }

            AngleV2SavedDetailTab.INFO -> LazyColumn(
                state = infoScroll,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    top = Spacing.md,
                    bottom = Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "info") {
                    AngleV2FrameInfo(
                        content = content,
                        frame = selected,
                        request = selectedRequest,
                    )
                }
            }
        }
    }
}

/**
 * Imagen del fotograma seleccionado en un contenedor que se recrea con la petición.
 *
 * Al cambiar la petición (raíz, fotograma o integridad declarada) el contenedor es NUEVO y nace en
 * [AngleV2SavedImage.Loading]: el estado anterior —y su diálogo— no se pinta con la leyenda nueva.
 * Una respuesta que llegue después de cambiar de petición se escribe en el contenedor viejo, que ya
 * nadie observa: se rechaza por petición, nunca por índice de lista.
 *
 * La imagen se lee UNA vez por petición. `AngleV2SavedDetailViewModel.image` no cachea y decodifica
 * en IO, así que no se vuelve a llamar al cambiar de pestaña ni al abrir/cerrar el diálogo.
 */
@Composable
private fun rememberAngleV2SelectedFrameImage(
    request: AngleV2SavedImageRequest?,
    frame: AngleV2SavedDetailFrame?,
    load: suspend (AngleV2SavedDetailFrame) -> AngleV2SavedImage,
    handoff: AngleV2GalleryImageHandoff,
): AngleV2SavedImage {
    // Si la celda pulsada transfirió EXACTAMENTE esta petición, el contenedor nace con esa MISMA
    // referencia: seleccionar una celda visible no vuelve a leer el fichero ni a decodificar. La
    // transferencia se consume una sola vez y sólo con identidad de petición completa (raíz,
    // fotograma, clave e integridad declarada), nunca por índice ni por clave suelta.
    val slot = remember(request) {
        AngleV2SelectedImageSlot(handoff.consume(request) ?: AngleV2SavedImage.Loading)
    }
    LaunchedEffect(request) {
        if (slot.image !is AngleV2SavedImage.Loading) return@LaunchedEffect
        slot.image = AngleV2SavedImage.Loading
        if (request != null && frame != null) {
            val loaded = load(frame)
            // Un cargador que no coopere con la cancelación no puede publicar sobre una petición
            // que ya no es la vigente: el contenedor viejo se descarta y aquí se corta.
            currentCoroutineContext().ensureActive()
            slot.image = loaded
        }
    }
    return slot.image
}

/** Contenedor de la imagen seleccionada; se recrea con cada petición (ver el composable de arriba). */
@Stable
private class AngleV2SelectedImageSlot(initial: AngleV2SavedImage = AngleV2SavedImage.Loading) {
    var image by mutableStateOf(initial)
}

/**
 * Único hueco de transferencia entre una celda de galería y el contenedor seleccionado.
 *
 * No es una caché: guarda como mucho UN candidato ya decodificado, sin copiar el bitmap y sin
 * retener peticiones antiguas. Sólo se publica desde el toque de la celda pulsada ([offer]); una
 * carga que termina no publica nada. [consume] exige la identidad de petición COMPLETA y vacía el
 * hueco al entregarla, de modo que la referencia no se queda viva después de usarse ni puede
 * retargetear otra raíz, otro fotograma u otra integridad declarada.
 */
@Stable
private class AngleV2GalleryImageHandoff {
    private var request: AngleV2SavedImageRequest? = null
    private var image: AngleV2SavedImage? = null

    /**
     * Publica lo que la celda PULSADA tiene ya pintable, en el mismo toque que cambia la selección.
     *
     * Nunca se llama al terminar una carga: dos celdas visibles no compiten por este hueco y la
     * última en cargar no puede robar el candidato de la que el usuario tocó.
     */
    fun offer(request: AngleV2SavedImageRequest, image: AngleV2SavedImage) {
        if (image !is AngleV2SavedImage.Available) return
        this.request = request
        this.image = image
    }

    /** Entrega el candidato SÓLO si coincide la petición completa, y lo olvida en el acto. */
    fun consume(request: AngleV2SavedImageRequest?): AngleV2SavedImage? {
        val candidate = image
        if (request == null || this.request != request || candidate !is AngleV2SavedImage.Available) {
            return null
        }
        this.request = null
        this.image = null
        return candidate
    }

    /** Suelta el candidato cuando la celda que lo publicó deja de estar en pantalla. */
    fun clear(request: AngleV2SavedImageRequest) {
        if (this.request != request) return
        this.request = null
        this.image = null
    }
}

/**
 * Cabecera compacta del detalle: identidad, fecha y conteos, sin acción grande.
 *
 * Primera fila, el avatar REAL del atleta dueño ([AthleteAvatar] con la clave leída de la MISMA
 * fila que el nombre) junto a una columna con el nombre como identidad principal, la fecha real y
 * los dos conteos de la raíz. El nombre envuelve sin truncar: a escala de fuente grande crece hacia
 * abajo y no empuja ni recorta el control de al lado. Una raíz SIN dueño no pinta avatar (no se
 * inventa un perfil ni se usa el atleta global) y conserva los marcadores locales de siempre.
 *
 * A la derecha, en su PROPIO espacio, el control de compartición de la CABECERA: con UN fotograma
 * comparte ese único PNG ([onShare], la misma acción que la de Análisis, que por eso no se duplica) y
 * con VARIOS comparte el lote completo de la raíz ([onShareAll]). Es el mismo icono estándar de 24 dp
 * dentro de un [IconButton] de 48 dp, se deshabilita mientras prepara y la descripción accesible va
 * en el botón (el icono no la duplica). No se añade ninguna autoridad nueva: se reutilizan los
 * callbacks existentes.
 */
@Composable
private fun AngleV2DetailHeader(
    content: AngleV2SavedDetailContent,
    shareAllBusy: Boolean,
    shareEnabled: Boolean,
    onShare: (AngleV2SavedDetailFrame) -> Unit,
    onShareAll: () -> Unit,
) {
    val framesCount = content.frames.size
    val locale = currentAppLocale()
    // Identidad principal: el nombre real del atleta, sin el prefijo de propiedad. Sólo cuando no
    // hay nombre se usa el marcador localizado que ya existía: no se inventa ninguno ni se añaden
    // claves nuevas.
    val name = when {
        content.athleteName != null -> content.athleteName
        content.athleteId != null -> stringResource(R.string.angle_detail_owner_unavailable)
        else -> stringResource(R.string.athlete_unassigned)
    }
    // Los DOS conteos reales de la raíz, cada uno con su plural localizado y unidos por un punto
    // medio neutro (puntuación, no texto de producto): una sola línea compacta bajo la fecha.
    val counts = pluralStringResource(
        R.plurals.angle_polish_frames_count,
        framesCount,
        framesCount,
    ) + " · " + pluralStringResource(
        R.plurals.angle_polish_marks_count,
        content.markCount,
        content.markCount,
    )
    // Compartir desde la cabecera: UN fotograma → esa imagen; VARIOS → el lote completo de la raíz.
    // Sin fotogramas no hay nada que compartir y no se ofrece ninguna acción vacía.
    val singleFrame = content.frames.singleOrNull()
    val shareDescription = if (singleFrame == null) {
        stringResource(R.string.angle_v2_saved_share_all_description, framesCount)
    } else {
        stringResource(R.string.angle_v2_saved_share_frame_image, singleFrame.displayFrameNumber)
    }
    val busyDescription = stringResource(R.string.angle_v2_saved_share_all_busy)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar SÓLO cuando la raíz tiene dueño: es el atleta real de la raíz (nunca el
            // seleccionado global) y, sin clave o con una clave desconocida, el componente ya
            // cae a las iniciales del nombre. Decorativo: el nombre ya se lee al lado.
            if (content.athleteId != null) {
                AthleteAvatar(
                    displayName = name,
                    avatarKey = content.athleteAvatarKey,
                    size = 40.dp,
                    modifier = Modifier.testTag("angle-detail-owner-avatar"),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    Formatting.dateTimeToText(content.createdAt, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    counts,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (content.frames.isNotEmpty()) {
                IconButton(
                    onClick = {
                        if (singleFrame != null) onShare(singleFrame) else onShareAll()
                    },
                    // Mientras se prepara el lote la acción queda deshabilitada y lo dice: el envío es
                    // todo-o-nada y un segundo toque no debe iniciar otra preparación ni otro selector.
                    enabled = shareEnabled && !shareAllBusy,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = shareDescription
                            if (shareAllBusy) stateDescription = busyDescription
                        },
                ) {
                    if (shareAllBusy) {
                        // Mientras prepara, el glifo deja sitio al progreso: el botón está
                        // deshabilitado y el estado ya dice que se está preparando.
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Mismo glifo Material de compartir en las dos comparticiones (la del lote y
                        // la de la imagen), teñido con el color neutro de la familia: nada de un
                        // segundo verde ni de una etiqueta de texto larga.
                        Icon(
                            painter = painterResource(R.drawable.ic_angle_share),
                            // La descripción la lleva el botón; el icono no la repite.
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pestañas de ancho COMPLETO y reparto IGUAL sobre el `TabRow` nativo de Material 3.
 *
 * Con 2 pestañas cada una ocupa la mitad y con 3 un tercio del ancho disponible: no hay desplazamiento
 * horizontal ni hueco muerto a la izquierda, y la pestaña seleccionada se identifica por VALOR de
 * enumerado (el índice sólo sirve al tab row). El contenido propio de cada [Tab] evita los márgenes
 * internos del slot de texto de Material, mantiene la altura mínima de 48 dp y centra la etiqueta,
 * que puede partirse en dos líneas con fuente grande (1.5+) sin recortarse ni desaparecer. La
 * semántica de pestaña (rol, selección) y el `testTag` por nombre se conservan.
 */
@Composable
private fun AngleV2DetailTabs(
    state: AngleV2SavedDetailUiState,
    frames: List<AngleV2SavedDetailFrame>,
    onSelectTab: (AngleV2SavedDetailTab) -> Unit,
) {
    val tabs = state.tabs(frames)
    TabRow(
        selectedTabIndex = tabs.indexOf(state.tab).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
    ) {
        tabs.forEach { tab ->
            Tab(
                modifier = Modifier
                    .testTag("angle-tab-${tab.name.lowercase()}")
                    .heightIn(min = 48.dp),
                selected = state.tab == tab,
                onClick = { onSelectTab(tab) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = Spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabTitle(tab),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        softWrap = true,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Título localizado de cada pestaña. */
@Composable
private fun tabTitle(tab: AngleV2SavedDetailTab): String = stringResource(
    when (tab) {
        AngleV2SavedDetailTab.ANALYSIS -> R.string.angle_polish_tab_analysis
        AngleV2SavedDetailTab.FRAMES -> R.string.angle_polish_tab_frames
        AngleV2SavedDetailTab.INFO -> R.string.angle_polish_tab_info
    },
)

/**
 * Selector compacto del fotograma visible en Análisis (sólo con más de uno).
 *
 * Las etiquetas de las fichas son el NÚMERO REAL de fotograma ([AngleV2SavedDetailFrame.displayFrameNumber],
 * base uno del editor), no el índice de la lista; el contador de posición («i de N») es información
 * distinta y se muestra aparte, para que no se confundan posición y número de fotograma.
 */
@Composable
private fun AngleV2FrameSelector(
    frames: List<AngleV2SavedDetailFrame>,
    selected: AngleV2SavedDetailFrame,
    onSelect: (Long) -> Unit,
) {
    val position = frames.indexOfFirst { it.frameId == selected.frameId } + 1
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.angle_saved_frame, selected.displayFrameNumber),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(
                    R.string.angle_polish_position_counter,
                    position,
                    frames.size,
                ),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            items(frames, key = { it.frameId }) { frame ->
                val openLabel = stringResource(
                    R.string.angle_polish_open_in_analysis,
                    frame.displayFrameNumber,
                )
                TextButton(
                    onClick = { onSelect(frame.frameId) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = openLabel },
                ) {
                    Text(
                        frame.displayFrameNumber.toString(),
                        fontWeight = if (frame.frameId == selected.frameId) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (frame.frameId == selected.frameId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * Acción de compartir un fotograma, estable entre recomposiciones.
 *
 * La copia verificada se prepara en el ViewModel (E/S fuera del hilo principal) y aquí sólo se lanza
 * el selector con **una** URI de sólo lectura. Todo fallo —imagen ausente, que no coincide con sus
 * datos, que no se pudo publicar o falta de app receptora— se anuncia con un mensaje legible y no se
 * entrega ninguna imagen inválida. Mientras cualquiera de las dos comparticiones prepara algo, la
 * acción queda deshabilitada y un toque no inicia una segunda preparación.
 */
@Composable
private fun rememberAngleFrameShare(
    viewModel: AngleV2SavedDetailViewModel,
    snackbarHostState: SnackbarHostState,
    frameShareBusy: MutableState<Boolean>,
): (AngleV2SavedDetailFrame) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.angle_v2_saved_share_image)
    val missing = stringResource(R.string.angle_v2_saved_share_missing)
    val corrupted = stringResource(R.string.angle_v2_saved_share_corrupted)
    val unavailable = stringResource(R.string.angle_v2_saved_share_unavailable)
    val noReceiver = stringResource(R.string.angle_v2_saved_share_no_app)
    return remember(
        context,
        viewModel,
        snackbarHostState,
        frameShareBusy,
        chooserTitle,
        missing,
        corrupted,
        unavailable,
        noReceiver,
    ) {
        { frame ->
            if (!frameShareBusy.value && !viewModel.shareAllBusy.value) {
                frameShareBusy.value = true
                scope.launch {
                    try {
                        val notice = when (val result = viewModel.shareImage(frame)) {
                            is AngleV2FrameShareResult.Ready -> {
                                if (startAngleFrameShare(context, result.uri, chooserTitle)) null else noReceiver
                            }

                            AngleV2FrameShareResult.Missing -> missing
                            AngleV2FrameShareResult.Corrupted -> corrupted
                            AngleV2FrameShareResult.Unavailable -> unavailable
                        }
                        if (notice != null) {
                            snackbarHostState.showSnackbar(notice, duration = SnackbarDuration.Short)
                        }
                    } finally {
                        frameShareBusy.value = false
                    }
                }
            }
        }
    }
}

/**
 * Acción de «compartir todas las imágenes», estable entre recomposiciones.
 *
 * La preparación (verificación, copia y presupuesto de transporte) ocurre en el ViewModel, fuera del
 * hilo principal y de una en una. Aquí se reclama la puerta en el mismo frame, se lanza el selector
 * sólo cuando el lote **completo** está listo, el snapshot sigue vigente y el transporte lo acepta, y
 * cualquier otro desenlace se anuncia con un mensaje localizado sin entregar nada. El indicador de
 * ocupado del ViewModel se limpia también en error o cancelación, y la puerta de la UI se libera en un
 * `finally` en todos los casos.
 */
@Composable
private fun rememberAngleShareAll(
    viewModel: AngleV2SavedDetailViewModel,
    snackbarHostState: SnackbarHostState,
    frameShareBusy: MutableState<Boolean>,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.angle_v2_saved_share_all)
    val messages = mapOf(
        AngleV2ShareAllFailure.NOT_LOADED to stringResource(R.string.angle_v2_saved_share_all_unavailable),
        AngleV2ShareAllFailure.EMPTY to stringResource(R.string.angle_v2_saved_share_all_empty),
        AngleV2ShareAllFailure.MISSING to stringResource(R.string.angle_v2_saved_share_all_incomplete),
        AngleV2ShareAllFailure.CORRUPTED to stringResource(R.string.angle_v2_saved_share_all_corrupted),
        AngleV2ShareAllFailure.UNAVAILABLE to stringResource(R.string.angle_v2_saved_share_all_unavailable),
        AngleV2ShareAllFailure.STALE to stringResource(R.string.angle_v2_saved_share_all_stale),
        AngleV2ShareAllFailure.TOO_LARGE to stringResource(R.string.angle_v2_saved_share_all_too_large),
    )
    val noReceiver = stringResource(R.string.angle_v2_saved_share_no_app)
    return remember(context, viewModel, snackbarHostState, frameShareBusy, chooserTitle, messages, noReceiver) {
        {
            // La puerta se reclama de forma síncrona **antes** de encolar el trabajo (igual que la
            // compartición individual): un segundo toque que llegue antes de la recomposición que
            // deshabilita el botón ya la encuentra cerrada y no arranca otra preparación.
            beginAngleV2ShareAllBatch(
                isFrameShareBusy = { frameShareBusy.value },
                isShareAllBusy = { viewModel.shareAllBusy.value },
                claim = { frameShareBusy.value = true },
                startBatch = {
                    // `UNDISPATCHED` entra en el `try` de inmediato: el `finally` que libera la puerta
                    // existe aunque el ámbito se cancele antes de despachar el trabajo.
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val notice = when (val outcome = viewModel.shareAll()) {
                                AngleV2ShareAllOutcome.Ignored -> null

                                is AngleV2ShareAllOutcome.Ready -> when {
                                    // Última comprobación antes de lanzar: el snapshot no cambió mientras
                                    // se preparaba y el selector no se abre sobre otro contenido.
                                    !viewModel.isShareTokenCurrent(outcome.token) ->
                                        messages.getValue(AngleV2ShareAllFailure.STALE)

                                    else -> when (
                                        AngleV2ShareIntents.launch(context, outcome.uris, chooserTitle)
                                    ) {
                                        AngleV2ShareLaunchResult.Launched -> null
                                        AngleV2ShareLaunchResult.NoReceiver -> noReceiver
                                        // Que no quepa o que el sistema rechace el transporte no es
                                        // «no hay apps»: cada desenlace conserva su mensaje.
                                        AngleV2ShareLaunchResult.TooLarge ->
                                            messages.getValue(AngleV2ShareAllFailure.TOO_LARGE)

                                        AngleV2ShareLaunchResult.Unavailable ->
                                            messages.getValue(AngleV2ShareAllFailure.UNAVAILABLE)
                                    }
                                }

                                is AngleV2ShareAllOutcome.Failed -> messages.getValue(outcome.failure)
                            }
                            if (notice != null) {
                                snackbarHostState.showSnackbar(notice, duration = SnackbarDuration.Short)
                            }
                        } finally {
                            // Se libera también en `Ignored`, error y cancelación.
                            frameShareBusy.value = false
                        }
                    }
                },
            )
        }
    }
}

/**
 * Lanza el selector de apps con un único URI de sólo lectura al PNG verificado del fotograma.
 *
 * El Intent declara `image/png` —el único tipo que esta pantalla comparte—, adjunta el URI en
 * [Intent.EXTRA_STREAM] y en un [ClipData] del mismo tipo, y concede permiso de lectura de **ese**
 * URI: no se usa `FLAG_GRANT_PREFIX_URI_PERMISSION`, no se concede ningún árbol y no se adjunta el
 * vídeo. Devuelve `false` si no hay ninguna app que pueda abrir el selector, para informarlo en vez
 * de cerrar la pantalla.
 */
private fun startAngleFrameShare(context: Context, uri: Uri, chooserTitle: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = AngleV2FrameShare.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData(
            chooserTitle,
            arrayOf(AngleV2FrameShare.MIME_TYPE),
            ClipData.Item(uri),
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
private fun DetailColumn(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        content()
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.common_back))
    }
}
