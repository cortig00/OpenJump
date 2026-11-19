package com.openjump.app.angles

/**
 * Estado y transiciones puras (sin Android, sin Room, sin reproductor y sin UI) del editor manual
 * de ángulos 2D.
 *
 * ## Contrato
 *
 * - **Un único frame ancla por editor.** El ancla es la terna ([AngleFrameAnchor.sourceIdentity],
 *   [AngleFrameAnchor.frameIndex], [AngleFrameAnchor.sourcePtsUs]) y se fija con el **primer punto**
 *   colocado. Mientras no haya primer punto el editor no está anclado y el llamante puede colocar
 *   sobre cualquier frame confirmado; desde ese momento el ancla es inmutable. No existe ninguna
 *   acción para mover el ancla, para reutilizar marcas en otro frame ni para trasladarlas a otra
 *   fuente o generación: un análisis nuevo exige un estado nuevo.
 * - **N ángulos independientes, sin límite funcional.** Cada [Angle] tiene id estable, nombre, color,
 *   visibilidad y tres marcas A, B (vértice) y C. El estado no impone un máximo de ángulos (el
 *   guardado decidirá qué es persistible; esta capa no replica las reglas de Room).
 * - **Borrador A → B → C.** [placePoint] guarda como máximo dos puntos parciales en [AngleDraft]. Al
 *   colocar el tercer punto se mide la terna con [AngleGeometry]; si es [AngleMeasurement.Measured]
 *   el ángulo se crea **inmediatamente**, el borrador se vacía y, por defecto, el nuevo ángulo queda
 *   seleccionado para minimizar pasos (editar sus asas o renombrarlo sin un toque extra). El
 *   llamante en **modo colocación** pasa `selectOnComplete = false` para encadenar ángulos sin
 *   cambiar la selección. Si la terna es degenerada o no finita, la colocación se rechaza con un
 *   motivo tipado y el estado anterior queda intacto.
 * - **Solo se guardan ángulos completos y válidos.** [Angle] exige tres marcas medibles y un nombre
 *   no vacío en su inicializador. Un borrador parcial nunca se expone como ángulo.
 * - **Editar coordenadas exige una confirmación vigente.** [placePoint], [moveMarker] y
 *   [moveDraftMarker] reciben una [AngleFrameConfirmation] construida por el propietario a partir de
 *   un frame realmente renderizado y en pausa. Se rechaza, con motivos tipados, si la identidad de
 *   la fuente no es la del editor, si la generación no es la vigente, si el player no está en pausa
 *   o si el frame confirmado no es el ancla. Esto bloquea crear o arrastrar puntos en otro frame.
 * - **Las acciones de presentación no tocan coordenadas.** [rename], [setColor], [setVisible],
 *   [select] y [deleteAngle] no exigen confirmación de frame: no dependen del píxel y deben poder
 *   usarse desde una lista mientras se navega. La navegación es externa a este estado: el llamante
 *   decide qué geometría dibujar con [showsGeometryAt]; navegar no muta el ancla ni las marcas, así
 *   que volver al ancla y confirmarla de nuevo permite seguir editando.
 * - **Un ángulo oculto no puede quedar seleccionado.** [setVisible] con `visible = false` limpia la
 *   selección si era el seleccionado y [select] rechaza un id oculto con
 *   [HiddenAngle]: la selección dirige las asas y la edición de la capa de
 *   presentación, y un ángulo oculto no es interactivo, así que esa selección sería inalcanzable.
 *   La lista puede seleccionar cualquier ángulo **visible** por id, sin depender del *hit test* de la
 *   superposición (que solo alcanza al que está encima).
 * - **Rehidratación pura.** [AngleEditorState.rehydrate] reconstruye un estado desde un ancla y una
 *   lista de [Angle] ya materializados (el adaptador de persistencia es quien mapea sus ids externos
 *   a [AngleId] local). Valida unicidad de ids, coherencia ancla/fuente, borrador sin ancla y
 *   agotamiento del espacio de ids; avanza [AngleId] y el ordinal de nombre por defecto para que un
 *   ángulo nuevo no colisione con los rehidratados. No se añade ninguna dependencia de Room ni de
 *   Android: la persistencia sigue fuera de esta capa.
 * - **Acciones inmutables y explícitas.** Cada operación devuelve un [AngleEditorOutcome]; no hay un
 *   reductor genérico ni una función "global" que pueda inventar estado y no hay setters públicos.
 *   Los rechazos son atómicos: no existe escritura parcial.
 * - **Identidad de render opaca.** [generation] la elige el propietario al crear el estado y este no
 *   la modifica. Si la sesión de render o la fuente cambia, el propietario debe crear un estado
 *   nuevo (y rehidratar desde persistencia si corresponde en otra capa); no hay API para arrastrar
 *   marcas entre generaciones.
 *
 * ## Fuera de alcance
 *
 * - **Seguridad, zoom, rotación, recorte, aspecto y límites de frame.** Los puntos son píxeles de
 *   presentación (post-rotación) y esta capa acepta cualquier par finito, incluso fuera de la imagen:
 *   el mapeo de pantalla a imagen y la validación de límites pertenecen a la capa de presentación
 *   (las reglas de persistencia ya viven en `AngleAggregateRules`).
 * - **Persistencia.** Los ids son locales al estado (monótonos y estables durante la sesión), no ids
 *   de Room; el adaptador que guarde deberá mapearlos y aplicar sus propias reglas.
 *
 * ## Visibilidad
 *
 * [Angle.visible] es una propiedad de presentación de cada ángulo y no altera sus coordenadas ni su
 * medición. La geometría de un estado anclado se dibuja cuando el frame renderizado coincide
 * exactamente con el ancla ([showsGeometryAt]); cualquier otro frame la oculta sin borrar nada.
 * El estado no conoce el modo del player para decidir visibilidad: el llamante solo debe preguntar
 * por frames realmente renderizados.
 *
 * ## Integración con la capa de presentación
 *
 * El llamante compone `AngleOverlay` con `showsGeometry = state.showsGeometryAt(frameConfirmado)`.
 * Ese parámetro es la **única** vía admitida para ocultar la geometría en un frame que no es el
 * ancla: no se debe reutilizar `enabled = false`, porque deshabilitar es un estado distinto (capa
 * atenuada y sin gestos por otro motivo) y mezclarlos haría ambigua la causa de que no se dibuje.
 * El estado no se entera de la navegación: `showsGeometryAt` es una consulta, no una mutación.
 */
class AngleEditorState private constructor(
    /** Identidad lógica de la fuente del vídeo; acota todo el análisis. */
    val sourceIdentity: String,
    /** Generación opaca del render con la que se creó el editor; debe coincidir en cada confirmación. */
    val generation: Long,
    /** Frame del análisis; `null` hasta que se coloca el primer punto. */
    val anchor: AngleFrameAnchor?,
    /** Ángulos completos y válidos, en orden de creación. */
    val angles: List<Angle>,
    /** Puntos parciales del ángulo en construcción (0, 1 o 2); A es el primero. */
    val draft: AngleDraft,
    /** Ángulo seleccionado en la UI, o `null` si no hay selección. */
    val selectedAngleId: AngleId?,
    /**
     * Ids locales que el adaptador ya ha persistido externamente.
     *
     * Esta capa no guarda ids de Room ni decide cuándo guardar: solo recuerda qué ángulos ya tienen
     * contrapartida persistida para que el adaptador elija actualizar en vez de insertar. Un ángulo
     * recién colocado no está marcado; [markExternallyPersisted] lo marca y borrar el ángulo lo
     * elimina del conjunto.
     */
    val externallyPersistedAngleIds: Set<AngleId>,
    private val nextAngleId: Long,
    private val nextAngleOrdinal: Int,
    private val defaults: AngleEditorDefaults,
) {

    /**
     * `true` si la geometría de este editor debe dibujarse sobre [frame].
     *
     * La navegación es un asunto del llamante: este estado no guarda un "frame actual". Devolver
     * `false` no borra ni invalida nada; al volver al ancla y confirmar de nuevo se puede editar.
     * Antes del primer punto no hay ancla y la geometría nunca se dibuja.
     */
    fun showsGeometryAt(frame: AngleFrameAnchor): Boolean = anchor == frame

    /**
     * Coloca el siguiente punto del borrador: A, B o C.
     *
     * El primer punto fija el ancla ([anchor]) con el frame de [confirmation]. Con el tercer punto,
     * si la terna es medible, se crea un [Angle] de inmediato y el borrador se vacía. Si la terna es
     * inválida no se crea nada y el borrador conserva sus dos puntos para que el usuario pueda
     * corregirlos.
     */
    fun placePoint(
        point: AnglePoint,
        confirmation: AngleFrameConfirmation,
        selectOnComplete: Boolean = true,
    ): AngleEditorOutcome {
        rejectionFor(confirmation)?.let { return AngleEditorOutcome.Rejected(it) }
        if (!point.isFinite) return AngleEditorOutcome.Rejected(AngleEditorRejection.NonFinitePoint)

        val anchored = anchor ?: confirmation.frame
        val points = draft.points + point
        if (points.size < MARKERS_PER_ANGLE) {
            return AngleEditorOutcome.Applied(
                copy(anchor = anchored, draft = AngleDraft(points)),
            )
        }

        val markers = AngleMarkers(points[0], points[1], points[2])
        return when (val measurement = AngleGeometry.measure(markers)) {
            is AngleMeasurement.Invalid -> AngleEditorOutcome.Rejected(
                AngleEditorRejection.InvalidGeometry(measurement.reason),
            )

            is AngleMeasurement.Measured -> {
                require(nextAngleId < Long.MAX_VALUE) {
                    "Espacio de ids locales agotado: no se puede crear otro ángulo."
                }
                val angle = Angle(
                    id = AngleId(nextAngleId),
                    name = "${defaults.namePrefix} $nextAngleOrdinal",
                    colorArgb = defaults.colorArgb,
                    visible = true,
                    markers = markers,
                )
                AngleEditorOutcome.Applied(
                    copy(
                        anchor = anchored,
                        angles = angles + angle,
                        draft = AngleDraft(),
                        // Sin modo colocación, el ángulo recién creado queda seleccionado para
                        // poder ajustarlo o renombrarlo sin un toque extra.
                        selectedAngleId = if (selectOnComplete) angle.id else selectedAngleId,
                        nextAngleId = nextAngleId + 1L,
                        nextAngleOrdinal = nextAngleOrdinal.saturatedIncrement(),
                    ),
                )
            }
        }
    }

    /**
     * Arrastra una de las tres marcas de un ángulo existente.
     *
     * El id, el nombre, el color y la visibilidad del ángulo se conservan. El movimiento solo se
     * aplica si la terna resultante sigue siendo medible: si el vértice coincide con un extremo (o
     * la terna desborda), se rechaza con [AngleEditorRejection.InvalidGeometry] y las marcas
     * anteriores quedan intactas.
     */
    fun moveMarker(
        angleId: AngleId,
        marker: AngleMarker,
        point: AnglePoint,
        confirmation: AngleFrameConfirmation,
    ): AngleEditorOutcome {
        rejectionFor(confirmation)?.let { return AngleEditorOutcome.Rejected(it) }
        val index = angles.indexOfFirst { it.id == angleId }
        if (index < 0) return AngleEditorOutcome.Rejected(AngleEditorRejection.UnknownAngle(angleId))
        if (!point.isFinite) return AngleEditorOutcome.Rejected(AngleEditorRejection.NonFinitePoint)

        val angle = angles[index]
        val markers = angle.markers.replacing(marker, point)
        return when (val measurement = AngleGeometry.measure(markers)) {
            is AngleMeasurement.Invalid -> AngleEditorOutcome.Rejected(
                AngleEditorRejection.InvalidGeometry(measurement.reason),
            )

            is AngleMeasurement.Measured -> AngleEditorOutcome.Applied(
                copy(
                    angles = angles.toMutableList().also {
                        it[index] = angle.copy(markers = markers)
                    },
                ),
            )
        }
    }

    /**
     * Arrastra una marca ya colocada del borrador (solo A o B).
     *
     * No valida la terna completa porque todavía no existe: solo exige que el punto sea finito. La
     * validación geométrica ocurre al colocar C. Si la marca pedida todavía no se ha colocado se
     * rechaza con [AngleEditorRejection.DraftMarkerNotPlaced].
     */
    fun moveDraftMarker(
        marker: AngleMarker,
        point: AnglePoint,
        confirmation: AngleFrameConfirmation,
    ): AngleEditorOutcome {
        rejectionFor(confirmation)?.let { return AngleEditorOutcome.Rejected(it) }
        val index = marker.draftIndex
        if (index >= draft.points.size) {
            return AngleEditorOutcome.Rejected(AngleEditorRejection.DraftMarkerNotPlaced(marker))
        }
        if (!point.isFinite) return AngleEditorOutcome.Rejected(AngleEditorRejection.NonFinitePoint)

        val points = draft.points.toMutableList().also { it[index] = point }
        return AngleEditorOutcome.Applied(copy(draft = AngleDraft(points)))
    }

    /**
     * Descarta los puntos parciales del borrador sin tocar ángulos, selección ni ancla.
     *
     * No exige confirmación porque no crea geometría. Es idempotente: cancelar un borrador vacío
     * devuelve el mismo estado como [AngleEditorOutcome.Applied]. El ancla **no** se libera: si no
     * quedan ángulos, este análisis sigue perteneciendo a ese frame y para empezar en otro hace falta
     * un estado nuevo.
     */
    fun cancelDraft(): AngleEditorOutcome =
        if (draft.points.isEmpty()) {
            AngleEditorOutcome.Applied(this)
        } else {
            AngleEditorOutcome.Applied(copy(draft = AngleDraft()))
        }

    /**
     * Renombra un ángulo.
     *
     * Un nombre vacío o solo con espacios se rechaza con [AngleEditorRejection.BlankName]. Un nombre
     * que supera [AngleMarkNameContract.MAX_CODE_UNITS] unidades UTF-16 (el límite compartido con la
     * persistencia y con el backup manual) se rechaza con [AngleEditorRejection.NameTooLong]: el
     * estado no se modifica y el nombre NUNCA se trunca.
     */
    fun rename(angleId: AngleId, name: String): AngleEditorOutcome {
        if (name.isBlank()) return AngleEditorOutcome.Rejected(AngleEditorRejection.BlankName)
        if (!AngleMarkNameContract.fits(name)) {
            return AngleEditorOutcome.Rejected(
                AngleEditorRejection.NameTooLong(
                    maxCodeUnits = AngleMarkNameContract.MAX_CODE_UNITS,
                    actualCodeUnits = AngleMarkNameContract.codeUnits(name),
                ),
            )
        }
        return updateAngle(angleId) { it.copy(name = name) }
    }

    /** Cambia el color ARGB de presentación de un ángulo; no afecta a sus marcas. */
    fun setColor(angleId: AngleId, colorArgb: Int): AngleEditorOutcome =
        updateAngle(angleId) { it.copy(colorArgb = colorArgb) }

    /**
     * Oculta o muestra un ángulo; no afecta a sus marcas ni a su medición.
     *
     * Ocultar el ángulo seleccionado **limpia la selección**: un ángulo oculto no se dibuja ni es
     * interactivo, de modo que mantenerlo seleccionado dejaría una selección inalcanzable (y
     * [select] volvería a rechazarla).
     */
    fun setVisible(angleId: AngleId, visible: Boolean): AngleEditorOutcome {
        val index = angles.indexOfFirst { it.id == angleId }
        if (index < 0) return AngleEditorOutcome.Rejected(AngleEditorRejection.UnknownAngle(angleId))
        val updated = angles.toMutableList().also { it[index] = it[index].copy(visible = visible) }
        return AngleEditorOutcome.Applied(
            copy(
                angles = updated,
                selectedAngleId = if (!visible && selectedAngleId == angleId) null else selectedAngleId,
            ),
        )
    }

    /**
     * Selecciona un ángulo visible. `null` limpia la selección.
     *
     * Un id desconocido se rechaza con [AngleEditorRejection.UnknownAngle] y uno oculto con
     * [HiddenAngle]. Cualquier ángulo visible se puede seleccionar por id, sin
     * depender del *hit test* de la superposición: así una lista puede seleccionar también los que
     * quedan debajo de otro.
     */
    fun select(angleId: AngleId?): AngleEditorOutcome {
        if (angleId == null) return AngleEditorOutcome.Applied(copy(selectedAngleId = null))
        val angle = angles.firstOrNull { it.id == angleId }
            ?: return AngleEditorOutcome.Rejected(AngleEditorRejection.UnknownAngle(angleId))
        if (!angle.visible) return AngleEditorOutcome.Rejected(HiddenAngle(angleId))
        return AngleEditorOutcome.Applied(copy(selectedAngleId = angleId))
    }

    /**
     * Marca un ángulo como ya persistido externamente; idempotente.
     *
     * No crea ni modifica geometría: es la señal que el adaptador usa para decidir entre insertar y
     * actualizar. Un id desconocido se rechaza con [AngleEditorRejection.UnknownAngle].
     */
    fun markExternallyPersisted(angleId: AngleId): AngleEditorOutcome {
        if (angles.none { it.id == angleId }) {
            return AngleEditorOutcome.Rejected(AngleEditorRejection.UnknownAngle(angleId))
        }
        if (angleId in externallyPersistedAngleIds) return AngleEditorOutcome.Applied(this)
        return AngleEditorOutcome.Applied(
            copy(externallyPersistedAngleIds = externallyPersistedAngleIds + angleId),
        )
    }

    /**
     * Elimina un ángulo completo. Si estaba seleccionado, la selección se limpia.
     *
     * No hay límite mínimo: borrar todos los ángulos es válido en esta capa (la decisión de guardar
     * un análisis vacío pertenece a la persistencia).
     */
    fun deleteAngle(angleId: AngleId): AngleEditorOutcome {
        if (angles.none { it.id == angleId }) {
            return AngleEditorOutcome.Rejected(AngleEditorRejection.UnknownAngle(angleId))
        }
        return AngleEditorOutcome.Applied(
            copy(
                angles = angles.filterNot { it.id == angleId },
                selectedAngleId = selectedAngleId?.takeUnless { it == angleId },
                externallyPersistedAngleIds = externallyPersistedAngleIds - angleId,
            ),
        )
    }

    private fun updateAngle(
        angleId: AngleId,
        transform: (Angle) -> Angle,
    ): AngleEditorOutcome {
        val index = angles.indexOfFirst { it.id == angleId }
        if (index < 0) return AngleEditorOutcome.Rejected(AngleEditorRejection.UnknownAngle(angleId))
        return AngleEditorOutcome.Applied(
            copy(
                angles = angles.toMutableList().also { it[index] = transform(it[index]) },
            ),
        )
    }

    /**
     * Comprueba la confirmación de frame en un orden determinista y documentado:
     * fuente → generación → pausa → ancla.
     */
    private fun rejectionFor(confirmation: AngleFrameConfirmation): AngleEditorRejection? {
        val frame = confirmation.frame
        if (frame.sourceIdentity != sourceIdentity) {
            return AngleEditorRejection.ForeignSource(
                expectedSourceIdentity = sourceIdentity,
                receivedSourceIdentity = frame.sourceIdentity,
            )
        }
        if (confirmation.generation != generation) {
            return AngleEditorRejection.StaleGeneration(
                expectedGeneration = generation,
                receivedGeneration = confirmation.generation,
            )
        }
        if (!confirmation.paused) return AngleEditorRejection.FrameNotPaused
        val currentAnchor = anchor
        if (currentAnchor != null && frame != currentAnchor) {
            return AngleEditorRejection.OtherFrame(
                anchor = currentAnchor,
                rendered = frame,
            )
        }
        return null
    }

    private fun copy(
        anchor: AngleFrameAnchor? = this.anchor,
        angles: List<Angle> = this.angles,
        draft: AngleDraft = this.draft,
        selectedAngleId: AngleId? = this.selectedAngleId,
        externallyPersistedAngleIds: Set<AngleId> = this.externallyPersistedAngleIds,
        nextAngleId: Long = this.nextAngleId,
        nextAngleOrdinal: Int = this.nextAngleOrdinal,
    ): AngleEditorState = AngleEditorState(
        sourceIdentity = sourceIdentity,
        generation = generation,
        anchor = anchor,
        angles = angles,
        draft = draft,
        selectedAngleId = selectedAngleId,
        externallyPersistedAngleIds = externallyPersistedAngleIds,
        nextAngleId = nextAngleId,
        nextAngleOrdinal = nextAngleOrdinal,
        defaults = defaults,
    )

    companion object {
        private const val MARKERS_PER_ANGLE = 3
        private const val FIRST_ANGLE_ID = 1L
        private const val FIRST_ANGLE_ORDINAL = 1

        /**
         * Estado inicial, sin ancla ni ángulos. La generación y la identidad de la fuente quedan
         * fijadas para toda la vida del estado.
         *
         * @param sourceIdentity identidad lógica de la fuente; no vacía.
         * @param generation token opaco de la sesión de render del propietario.
         * @param defaults nombre y color iniciales de los ángulos que se vayan creando.
         */
        fun initial(
            sourceIdentity: String,
            generation: Long,
            defaults: AngleEditorDefaults,
        ): AngleEditorState {
            require(sourceIdentity.isNotBlank()) {
                "La identidad de la fuente del editor de ángulos no puede estar vacía."
            }
            return AngleEditorState(
                sourceIdentity = sourceIdentity,
                generation = generation,
                anchor = null,
                angles = emptyList(),
                draft = AngleDraft(),
                selectedAngleId = null,
                externallyPersistedAngleIds = emptySet(),
                nextAngleId = FIRST_ANGLE_ID,
                nextAngleOrdinal = FIRST_ANGLE_ORDINAL,
                defaults = defaults,
            )
        }

        /**
         * Reconstruye un estado ya existente desde persistencia, sin tocar Android ni Room.
         *
         * El adaptador es el propietario del mapeo id persistido → [AngleId]: esta fábrica recibe
         * los [Angle] ya materializados con sus ids locales estables y conserva el orden recibido
         * (que es el orden de dibujo de los no seleccionados). El estado avanza su siguiente id y su
         * siguiente ordinal de nombre por defecto para que un ángulo nuevo **no colisione** con los
         * rehidratados, aunque sus ids o nombres no sean contiguos.
         *
         * Reglas de validación (todas lanzan [IllegalArgumentException]; no hay escritura parcial):
         *
         * - `sourceIdentity` no vacía y, si hay [anchor], de la misma fuente.
         * - Sin ancla no puede haber ángulos ni borrador: la geometría vive en un frame concreto.
         * - Los [AngleId] de [angles] deben ser únicos y dejar al menos un id libre
         *   (`Long.MAX_VALUE` agotado se rechaza).
         * - [selectedAngleId] debe existir; si apunta a un ángulo oculto se normaliza a `null`
         *   (misma regla que [select]).
         * - [externallyPersistedAngleIds] debe ser subconjunto de los ids rehidratados.
         *
         * Estado vacío es válido (`anchor = null`, `angles = emptyList()`): equivale a [initial]
         * pero con la generación y la fuente que aporte el llamante.
         */
        fun rehydrate(
            sourceIdentity: String,
            generation: Long,
            anchor: AngleFrameAnchor?,
            angles: List<Angle>,
            defaults: AngleEditorDefaults,
            draft: AngleDraft = AngleDraft(),
            selectedAngleId: AngleId? = null,
            externallyPersistedAngleIds: Set<AngleId> = emptySet(),
        ): AngleEditorState {
            require(sourceIdentity.isNotBlank()) {
                "La identidad de la fuente del editor de ángulos no puede estar vacía."
            }
            if (anchor == null) {
                require(angles.isEmpty()) {
                    "No se pueden rehidratar ángulos sin ancla: su geometría pertenece a un frame."
                }
                require(draft.points.isEmpty()) {
                    "No se puede rehidratar un borrador sin ancla."
                }
            } else {
                require(anchor.sourceIdentity == sourceIdentity) {
                    "El ancla rehidratada es de ${anchor.sourceIdentity}, no de $sourceIdentity."
                }
            }

            val ids = angles.map { it.id }
            require(ids.size == ids.toSet().size) {
                "Los ids locales rehidratados deben ser únicos; llegaron ${ids.map { it.value }}."
            }
            val maxId = ids.maxOfOrNull { it.value }
            if (maxId != null) {
                require(maxId < Long.MAX_VALUE) {
                    "No quedan ids locales disponibles: el id máximo rehidratado es $maxId."
                }
            }
            val nextId = maxId?.let { it + 1L } ?: FIRST_ANGLE_ID

            val selected = selectedAngleId?.let { id ->
                val angle = angles.firstOrNull { it.id == id }
                require(angle != null) { "El ángulo seleccionado rehidratado ($id) no existe en la lista." }
                id.takeIf { angle.visible }
            }
            require(externallyPersistedAngleIds.all { it in ids }) {
                "Se marcaron como persistidos ids que no están en la lista: " +
                    "${externallyPersistedAngleIds.filterNot { it in ids }.map { it.value }}."
            }

            // El ordinal por defecto es el menor que no colisione con un nombre ya existente, así
            // que dos ángulos vivos nunca comparten nombre por defecto tras rehidratar.
            var ordinal = FIRST_ANGLE_ORDINAL
            while (angles.any { it.name == "${defaults.namePrefix} $ordinal" }) {
                require(ordinal < Int.MAX_VALUE) {
                    "No quedan ordinales de nombre disponibles para el prefijo '${defaults.namePrefix}'."
                }
                ordinal += 1
            }

            return AngleEditorState(
                sourceIdentity = sourceIdentity,
                generation = generation,
                anchor = anchor,
                angles = angles.toList(),
                draft = draft,
                selectedAngleId = selected,
                externallyPersistedAngleIds = externallyPersistedAngleIds.toSet(),
                nextAngleId = nextId,
                nextAngleOrdinal = ordinal,
                defaults = defaults,
            )
        }
    }
}

/**
 * Identidad lógica de una fuente de vídeo para el editor de ángulos.
 *
 * Puede ser la URI canónica, un hash de contenido o una clave de sesión: lo importante es que dos
 * fuentes distintas no compartan identidad. El editor nunca compara dimensiones ni PTS de otra
 * fuente para "adivinar" equivalencias.
 */
data class AngleFrameAnchor(
    val sourceIdentity: String,
    val frameIndex: Int,
    val sourcePtsUs: Long,
) {
    init {
        require(sourceIdentity.isNotBlank()) { "sourceIdentity no puede estar vacía." }
        require(frameIndex >= 0) { "frameIndex debe ser >= 0, era $frameIndex." }
        require(sourcePtsUs >= 0L) { "sourcePtsUs debe ser >= 0, era $sourcePtsUs." }
    }
}

/**
 * Confirmación externa de que un frame concreto está renderizado y en pausa.
 *
 * La construye el propietario (por ejemplo, al confirmar el player que el frame visible y su PTS
 * canónico coinciden y que la reproducción está parada). El estado no puede verificar el render por
 * sí mismo: confía en esta confirmación, pero exige que su fuente y generación sean las vigentes.
 * [paused] se comprueba explícitamente porque dibujar o guardar sobre un frame en movimiento no es
 * seguro aunque el índice coincida.
 */
data class AngleFrameConfirmation(
    val frame: AngleFrameAnchor,
    val generation: Long,
    val paused: Boolean,
)

/**
 * Valores por defecto de los ángulos que crea el editor.
 *
 * El nombre de un ángulo nuevo es `"$namePrefix $ordinal"`; el ordinal es monótono y no se reutiliza
 * al borrar, de modo que dos ángulos vivos nunca comparten nombre por defecto. El prefijo lo aporta
 * el llamante (normalmente desde recursos de la UI); esta capa pura no contiene textos localizados.
 */
data class AngleEditorDefaults(
    val namePrefix: String,
    val colorArgb: Int,
) {
    init {
        require(namePrefix.isNotBlank()) { "namePrefix no puede estar vacío." }
    }
}

/**
 * Selección de un ángulo oculto: no es interactivo y la selección dirige las asas y la edición de
 * la capa de presentación, así que dejarlo seleccionado sería una selección inalcanzable.
 *
 * Se declara en este archivo (misma *package* que [AngleEditorRejection], que es un `sealed
 * interface`) porque describe un invariante de selección del estado, no un motivo del modelo
 * geométrico.
 */
data class HiddenAngle(val angleId: AngleId) : AngleEditorRejection

/** Marca del ángulo; B es el vértice. */
enum class AngleMarker {
    A,
    B,
    C,
    ;

    /** Posición de la marca dentro del borrador (A=0, B=1, C=2). */
    internal val draftIndex: Int
        get() = when (this) {
            A -> 0
            B -> 1
            C -> 2
        }
}

/** Identidad local y estable de un ángulo dentro de un [AngleEditorState]; no es un id de Room. */
@JvmInline
value class AngleId(val value: Long) {
    init {
        require(value > 0L) { "AngleId debe ser > 0, era $value." }
    }
}

/**
 * Ángulo completo del editor: tres marcas medibles y su presentación.
 *
 * El inicializador rechaza nombres vacíos y ternas no medibles, de modo que un [Angle] nunca
 * representa un estado incompleto o degenerado. La medición no se guarda: se deriva de las marcas
 * con [AngleGeometry] para no persistir resultados que puedan quedar obsoletos.
 *
 * La longitud del nombre NO se exige aquí: el límite ([AngleMarkNameContract]) se aplica al
 * renombrar y al persistir, para que un análisis guardado antes de este contrato siga pudiendo
 * cargarse sin romper el editor; al guardarlo de nuevo se pedirá acortar el nombre.
 */
data class Angle(
    val id: AngleId,
    val name: String,
    val colorArgb: Int,
    val visible: Boolean,
    val markers: AngleMarkers,
) {
    init {
        require(name.isNotBlank()) { "El nombre de un ángulo no puede estar vacío." }
        require(AngleGeometry.measure(markers) is AngleMeasurement.Measured) {
            "Un ángulo solo puede existir con una terna medible."
        }
    }

    /** Medición actual, sin redondear, recalculada desde las marcas. */
    val measurement: AngleMeasurement
        get() = AngleGeometry.measure(markers)
}

/**
 * Puntos parciales del ángulo en construcción, en orden A → B → C.
 *
 * Un borrador nunca tiene más de dos puntos y nunca contiene coordenadas no finitas; al completarse
 * la terna se convierte en [Angle] y el borrador vuelve a quedar vacío.
 */
data class AngleDraft(val points: List<AnglePoint> = emptyList()) {
    init {
        require(points.size <= 2) {
            "Un borrador solo puede tener 0, 1 o 2 puntos; tenía ${points.size}."
        }
        require(points.all { it.isFinite }) {
            "Un borrador no puede contener puntos no finitos."
        }
    }
}

/** Resultado de una acción del editor: estado nuevo inmutable o rechazo tipado sin cambios. */
sealed interface AngleEditorOutcome {

    /** Acción aplicada; [state] es el estado resultante (puede ser el mismo objeto si fue un no-op). */
    data class Applied(val state: AngleEditorState) : AngleEditorOutcome

    /** Acción rechazada; el estado del que se partió no se modificó. */
    data class Rejected(val reason: AngleEditorRejection) : AngleEditorOutcome
}

/** Motivos tipados por los que el editor rechaza una acción. */
sealed interface AngleEditorRejection {

    /** La confirmación es de otra fuente; no se mezclan análisis entre vídeos. */
    data class ForeignSource(
        val expectedSourceIdentity: String,
        val receivedSourceIdentity: String,
    ) : AngleEditorRejection

    /** La confirmación pertenece a una generación de render anterior o distinta. */
    data class StaleGeneration(
        val expectedGeneration: Long,
        val receivedGeneration: Long,
    ) : AngleEditorRejection

    /** El frame está renderizado pero el player no está en pausa. */
    data object FrameNotPaused : AngleEditorRejection

    /** El frame confirmado no es el ancla del análisis (distinto índice, PTS o fuente). */
    data class OtherFrame(
        val anchor: AngleFrameAnchor,
        val rendered: AngleFrameAnchor,
    ) : AngleEditorRejection

    /** Alguna coordenada del punto es NaN o infinita. */
    data object NonFinitePoint : AngleEditorRejection

    /** La terna resultante no es medible; [reason] detalla el caso. */
    data class InvalidGeometry(val reason: AngleInvalidReason) : AngleEditorRejection

    /** No existe ningún ángulo con ese id en el estado. */
    data class UnknownAngle(val angleId: AngleId) : AngleEditorRejection

    /** El nombre propuesto está vacío o solo tiene espacios. */
    data object BlankName : AngleEditorRejection

    /**
     * El nombre propuesto supera [AngleMarkNameContract.MAX_CODE_UNITS] unidades de código UTF-16,
     * el límite compartido con la persistencia y el backup manual. Se rechaza en vez de truncarlo.
     */
    data class NameTooLong(
        val maxCodeUnits: Int,
        val actualCodeUnits: Int,
    ) : AngleEditorRejection

    /** Se intentó arrastrar una marca del borrador que todavía no se ha colocado. */
    data class DraftMarkerNotPlaced(val marker: AngleMarker) : AngleEditorRejection
}

/** Incremento del ordinal de nombre sin desbordar en el límite teórico del `Int`. */
private fun Int.saturatedIncrement(): Int = if (this == Int.MAX_VALUE) Int.MAX_VALUE else this + 1

private fun AngleMarkers.replacing(marker: AngleMarker, point: AnglePoint): AngleMarkers =
    when (marker) {
        AngleMarker.A -> copy(a = point)
        AngleMarker.B -> copy(b = point)
        AngleMarker.C -> copy(c = point)
    }
