package com.openjump.app.angles

/**
 * Borrador de dominio, puro (sin Android, sin Room, sin reproductor, sin ficheros y sin UI), de un
 * análisis de ángulos compuesto por **un único vídeo fuente y N fotogramas congelados**.
 *
 * ## Contrato
 *
 * - **Una fuente por análisis.** [sourceIdentity] y [generation] se fijan al crear el estado y no
 *   cambian nunca: no existe ninguna operación para añadir fotogramas de otro vídeo ni para
 *   reutilizar este agregado con otra sesión de render. Una fuente distinta exige un estado nuevo.
 * - **N fotogramas, cada uno con su editor.** Cada [AngleFrameDraft] reutiliza un
 *   [AngleEditorState] independiente (su propio ancla, borrador A/B/C, ángulos, selección y
 *   revisiones locales). Esta capa no replica ni debilita las reglas del editor: solo las compone y
 *   añade las reglas de sesión.
 * - **Identidad de fotograma estable e independiente de la posición.** [AngleFrameId] lo aporta el
 *   llamante y es opaco para esta capa: [frames] es la lista **ordenada** de presentación, pero
 *   reordenar ([moveFrame]) o eliminar otro fotograma no cambia el id, el editor, la imagen ni las
 *   selecciones de los demás. Un id eliminado queda retirado ([AngleAnalysisDraftRejection.RetiredFrameId]):
 *   no se reutiliza para otro contenido, de modo que una respuesta tardía no resucita el fotograma.
 * - **Imagen congelada = referencia opaca, nunca píxeles.** [AngleFrozenImage] solo declara un token
 *   opaco y el ancla a la que el adaptador de captura dice pertenecer. **Esta capa no lee píxeles, no
 *   autentica el PTS, no verifica rotación, dimensiones ni la existencia del archivo**; comparar el
 *   ancla declarada y exigir unicidad del token no acredita que la imagen sea correcta. Esa prueba
 *   corresponde al adaptador de captura (experimento B), que debe autenticar la imagen antes de
 *   construirla. Aquí nunca se guarda un `Bitmap` ni ningún otro contenido binario: solo metadatos.
 * - **La imagen se adopta antes de marcar.** El ancla de un fotograma puede registrarse antes de
 *   tener imagen (p. ej. al abrir el diálogo de captura), pero aplicar geometría
 *   ([applyEditorOutcome]) a un fotograma sin imagen adoptada se rechaza con
 *   [AngleAnalysisDraftRejection.ImageNotAdopted]: es la traducción pura de «congelar y mostrar la
 *   imagen antes de marcar». Adoptar no comprueba que el archivo exista.
 * - **Acciones destructivas con confirmación explícita.** [removeFrame], [discardFrameDraft] (cuando
 *   hay puntos A/B) y [replaceFrozenImage] exigen [AngleDiscardConfirmation.CONFIRMED]; con datos
 *   presentes se rechazan con [AngleAnalysisDraftRejection.DiscardConfirmationRequired] y el estado
 *   queda intacto. Retirar un fotograma totalmente vacío (sin imagen, sin ángulos y sin borrador) no
 *   exige confirmación porque no hay nada que perder. Nunca se descarta nada en silencio.
 * - **Cambiar de imagen nunca mueve marcas.** Los puntos de un ángulo son píxeles de *esta* imagen
 *   congelada, no del índice ni del PTS: que dos capturas declaren el mismo ancla no prueba que sus
 *   píxeles sean iguales. Por eso [replaceFrozenImage] solo sustituye la imagen de un fotograma
 *   **sin geometría**; si hay ángulos completos o puntos A/B pendientes se rechaza con
 *   [AngleAnalysisDraftRejection.ImageReplacementBlockedByGeometry] y el fotograma queda intacto
 *   (imagen, editor y revisión), aunque llegue confirmación. Cambiar de imagen con geometría exige
 *   una acción intencional del llamante: eliminar el fotograma ([removeFrame], con su confirmación)
 *   y crear otro con id nuevo, volviendo a marcar sobre la imagen nueva. Al reemplazar de verdad,
 *   el token anterior queda retirado ([AngleAnalysisDraftRejection.FrozenImageRetired]) para que no
 *   se reutilice en otro fotograma ni se re-adopte una respuesta de captura obsoleta.
 * - **Operaciones tardías acotadas a la revisión esperada.** Las operaciones que dependen de un
 *   fotograma concreto reciben un [AngleDraftExpectation] con fuente, generación, id y revisión.
 *   Se valida en orden determinista fuente → generación → fotograma → revisión. Una respuesta de una
 *   sesión, un fotograma o una revisión anteriores se rechaza sin tocar el estado: no se aplica un
 *   resultado asíncrono a un estado distinto del que lo produjo.
 * - **Finalización solo como consulta.** [finalization] devuelve un plan tipado y **no guarda,
 *   persiste ni limpia nada**: no hay ninguna operación de guardado en esta capa. Un análisis solo es
 *   [AngleAnalysisFinalization.Ready] si hay al menos un fotograma y **cada** fotograma tiene imagen
 *   adoptada, al menos un ángulo completo y ningún borrador A/B pendiente.
 *
 * ## Fuera de alcance
 *
 * Persistencia (Room, archivos, backup), captura real, autenticación de píxeles/PTS, UI,
 * navegación, textos localizados, dimensiones/rotación, zoom, límites de frame y cualquier forma de
 * recuperación tras muerte de proceso. Las revisiones de esta capa son locales a la sesión y no son
 * ids de persistencia.
 */
class AngleAnalysisDraftState private constructor(
    /** Identidad lógica del único vídeo del análisis; acota todos los fotogramas. */
    val sourceIdentity: String,
    /** Generación opaca de la sesión de render; debe coincidir para aplicar resultados. */
    val generation: Long,
    /** Fotogramas en orden de presentación; el orden no es identidad. */
    val frames: List<AngleFrameDraft>,
    /** Fotograma activo (el visible), o `null` si no hay ninguno seleccionado. */
    val activeFrameId: AngleFrameId?,
    private val retiredFrameIds: Set<AngleFrameId>,
    private val retiredImageTokens: Set<String>,
    private val defaults: AngleEditorDefaults,
) {

    init {
        require(sourceIdentity.isNotBlank()) {
            "La identidad de la fuente del análisis no puede estar vacía."
        }
        require(frames.map { it.id }.toSet().size == frames.size) {
            "Los ids de fotograma deben ser únicos: ${frames.map { it.id.value }}."
        }
        require(frames.map { it.anchor }.toSet().size == frames.size) {
            "Dos fotogramas del análisis no pueden compartir ancla."
        }
        require(frames.all { it.anchor.sourceIdentity == sourceIdentity }) {
            "Todos los fotogramas deben pertenecer a la fuente $sourceIdentity."
        }
        require(frames.all { it.editor.sourceIdentity == sourceIdentity }) {
            "El editor de un fotograma no puede ser de otra fuente."
        }
        require(frames.all { it.editor.generation == generation }) {
            "El editor de un fotograma no puede ser de otra generación."
        }
        require(frames.all { it.id !in retiredFrameIds }) {
            "Un fotograma vivo no puede tener un id retirado."
        }
        val liveImageTokens = frames.mapNotNull { it.image?.token }
        require(liveImageTokens.size == liveImageTokens.toSet().size) {
            "Dos fotogramas no pueden compartir el token de una imagen congelada."
        }
        require(liveImageTokens.none { it in retiredImageTokens }) {
            "Un fotograma vivo no puede reutilizar el token de una imagen retirada."
        }
        require(activeFrameId == null || frames.any { it.id == activeFrameId }) {
            "El fotograma activo $activeFrameId no existe en el análisis."
        }
    }

    /** Número de fotogramas del análisis. */
    val frameCount: Int
        get() = frames.size

    /** `true` si algún fotograma conserva información que se perdería si se descarta la sesión. */
    val hasUnsavedData: Boolean
        get() = frames.any { it.hasData }

    /** Fotograma con ese id, o `null` si no existe en el análisis. */
    fun frame(frameId: AngleFrameId): AngleFrameDraft? = frames.firstOrNull { it.id == frameId }

    /** Fotograma activo, o `null` si no hay selección. */
    fun activeFrame(): AngleFrameDraft? = activeFrameId?.let(::frame)

    /**
     * `true` si eliminar [frameId] descartaría datos (imagen congelada, ángulos o borrador A/B).
     *
     * La UI lo usa para decidir si debe pedir confirmación antes de llamar a [removeFrame]. Un id
     * desconocido devuelve `false`: no hay nada que confirmar.
     */
    fun requiresDiscardConfirmation(frameId: AngleFrameId): Boolean =
        frame(frameId)?.hasData == true

    /**
     * Expectativa vigente de un fotograma para las operaciones asíncronas, o `null` si no existe.
     * Es la instantánea que el llamante debe conservar y reenviar con la respuesta.
     */
    fun expectationFor(frameId: AngleFrameId): AngleDraftExpectation? {
        val current = frame(frameId) ?: return null
        return AngleDraftExpectation(
            sourceIdentity = sourceIdentity,
            generation = generation,
            frameId = frameId,
            revision = current.revision,
        )
    }

    /**
     * Registra un fotograma del vídeo del análisis, todavía sin imagen congelada adoptada.
     *
     * El [frameId] lo aporta el llamante (identidad estable independiente del índice visible) y el
     * [anchor] fija el frame/índice y PTS que el llamante declara. Se rechaza si la fuente del ancla
     * no es la del análisis, si el id ya existe, si el id se retiró al eliminar su fotograma o si ya
     * hay otro fotograma con exactamente el mismo ancla. Añadir no cambia el fotograma activo ni el
     * de los demás.
     */
    fun addFrame(frameId: AngleFrameId, anchor: AngleFrameAnchor): AngleAnalysisDraftOutcome {
        if (anchor.sourceIdentity != sourceIdentity) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.ForeignSource(
                    expectedSourceIdentity = sourceIdentity,
                    receivedSourceIdentity = anchor.sourceIdentity,
                ),
            )
        }
        if (frameId in retiredFrameIds) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.RetiredFrameId(frameId),
            )
        }
        if (frames.any { it.id == frameId }) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.DuplicateFrameId(frameId),
            )
        }
        val sameAnchor = frames.firstOrNull { it.anchor == anchor }
        if (sameAnchor != null) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.DuplicateFrameAnchor(
                    existingFrameId = sameAnchor.id,
                    anchor = anchor,
                ),
            )
        }
        val frame = AngleFrameDraft(
            id = frameId,
            anchor = anchor,
            image = null,
            editor = AngleEditorState.initial(sourceIdentity, generation, defaults),
            revision = 0L,
        )
        return AngleAnalysisDraftOutcome.Applied(copy(frames = frames + frame))
    }

    /**
     * Adopta la imagen congelada de un fotograma que todavía no tenía ninguna.
     *
     * No toca píxeles ni el sistema de archivos: solo registra la referencia opaca que el adaptador
     * de captura declara. Se rechaza si el fotograma ya tiene imagen
     * ([AngleAnalysisDraftRejection.ImageAlreadyAdopted], para cambiarla hace falta
     * [replaceFrozenImage] con confirmación), si la imagen declara otra ancla, si su token ya está en
     * uso por otro fotograma o si pertenece a un fotograma eliminado.
     */
    fun adoptFrozenImage(
        expectation: AngleDraftExpectation,
        image: AngleFrozenImage,
    ): AngleAnalysisDraftOutcome {
        staleRejection(expectation)?.let { return it }
        val current = frame(expectation.frameId) ?: return unknownFrame(expectation)
        if (current.image != null) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.ImageAlreadyAdopted(current.id),
            )
        }
        if (image.anchor != current.anchor) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.ImageBoundToOtherFrame(
                    expected = current.anchor,
                    received = image.anchor,
                ),
            )
        }
        imageRejection(image)?.let { return it }
        return AngleAnalysisDraftOutcome.Applied(
            replaceFrame(current, current.copy(image = image, revision = current.revision + 1L)),
        )
    }

    /**
     * Sustituye la imagen congelada de un fotograma **con confirmación explícita** y sin geometría.
     *
     * Siempre exige [AngleDiscardConfirmation.CONFIRMED]: la imagen anterior se pierde aunque todavía
     * no haya marcas. El nuevo token no puede ser el de otro fotograma vivo ni el de un fotograma
     * eliminado, y su ancla declarada debe ser la del fotograma.
     *
     * **La sustitución no traslada geometría.** Los puntos A/B/C son píxeles de la imagen congelada
     * vigente; dos capturas con el mismo índice y PTS declarados no garantizan los mismos píxeles.
     * Si el fotograma conserva ángulos completos o puntos A/B pendientes, la operación se rechaza
     * con [AngleAnalysisDraftRejection.ImageReplacementBlockedByGeometry] y el fotograma queda
     * intacto (imagen, editor, selección y revisión). Ninguna confirmación habilita ese traslado:
     * para cambiar de imagen hay que eliminar el fotograma ([removeFrame], con su confirmación) y
     * crear otro con un id nuevo, marcando de forma intencional sobre la imagen nueva.
     *
     * Orden de comprobaciones (documentado para no cambiar el motivo que ya recibía un llamante):
     * revisión → ancla → imagen existente → confirmación → no-op de misma imagen → geometría →
     * token. La falta de confirmación se informa antes que el bloqueo por geometría, y el no-op de
     * misma imagen no se ve afectado por el bloqueo porque no cambia nada (ni imagen ni marcas).
     *
     * Al reemplazar de verdad, el token anterior se **retira**: no se puede adoptar en otro
     * fotograma ni volver a este ([AngleAnalysisDraftRejection.FrozenImageRetired]), de modo que
     * una respuesta tardía de la captura sustituida no puede resucitarla. Reemplazar por la misma
     * imagen es un no-op explícito (sigue exigiendo confirmación, no retira el token y no toca la
     * geometría).
     */
    fun replaceFrozenImage(
        expectation: AngleDraftExpectation,
        image: AngleFrozenImage,
        confirmation: AngleDiscardConfirmation,
    ): AngleAnalysisDraftOutcome {
        staleRejection(expectation)?.let { return it }
        val current = frame(expectation.frameId) ?: return unknownFrame(expectation)
        if (image.anchor != current.anchor) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.ImageBoundToOtherFrame(
                    expected = current.anchor,
                    received = image.anchor,
                ),
            )
        }
        val existing = current.image
            ?: return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.NoImageToReplace(current.id),
            )
        if (confirmation != AngleDiscardConfirmation.CONFIRMED) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                    AngleDiscardReason.IMAGE_REPLACEMENT,
                ),
            )
        }
        if (existing == image) return AngleAnalysisDraftOutcome.Applied(this)
        val completeAngles = current.editor.angles.size
        val draftPoints = current.editor.draft.points.size
        if (completeAngles > 0 || draftPoints > 0) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.ImageReplacementBlockedByGeometry(
                    frameId = current.id,
                    completeAngles = completeAngles,
                    draftPoints = draftPoints,
                ),
            )
        }
        imageRejection(image)?.let { return it }
        return AngleAnalysisDraftOutcome.Applied(
            replaceFrame(
                previous = current,
                updated = current.copy(image = image, revision = current.revision + 1L),
                retiredImageTokens = retiredImageTokens + existing.token,
            ),
        )
    }

    /**
     * Elimina un fotograma del análisis.
     *
     * Si conserva datos (imagen, ángulos o borrador A/B) exige
     * [AngleDiscardConfirmation.CONFIRMED]; si está totalmente vacío se puede retirar sin
     * confirmación. El id queda retirado y su token de imagen (si lo tenía) también, así que una
     * respuesta tardía no puede resucitar ni el fotograma ni su imagen. Si era el activo, la
     * selección queda vacía; el resto de fotogramas no cambia.
     */
    fun removeFrame(
        expectation: AngleDraftExpectation,
        confirmation: AngleDiscardConfirmation,
    ): AngleAnalysisDraftOutcome {
        staleRejection(expectation)?.let { return it }
        val current = frame(expectation.frameId) ?: return unknownFrame(expectation)
        if (current.hasData && confirmation != AngleDiscardConfirmation.CONFIRMED) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                    AngleDiscardReason.FRAME_REMOVAL,
                ),
            )
        }
        val imageToken = current.image?.token
        return AngleAnalysisDraftOutcome.Applied(
            copy(
                frames = frames.filterNot { it.id == current.id },
                activeFrameId = activeFrameId?.takeUnless { it == current.id },
                retiredFrameIds = retiredFrameIds + current.id,
                retiredImageTokens = imageToken?.let { retiredImageTokens + it }
                    ?: retiredImageTokens,
            ),
        )
    }

    /**
     * Descarta los puntos A/B del borrador de un fotograma, conservando ángulos, ancla y selección.
     *
     * Con puntos colocados exige [AngleDiscardConfirmation.CONFIRMED] para no perder trabajo en
     * silencio. Un borrador ya vacío devuelve el mismo estado sin exigir confirmación (no es
     * destructivo).
     */
    fun discardFrameDraft(
        expectation: AngleDraftExpectation,
        confirmation: AngleDiscardConfirmation,
    ): AngleAnalysisDraftOutcome {
        staleRejection(expectation)?.let { return it }
        val current = frame(expectation.frameId) ?: return unknownFrame(expectation)
        if (current.editor.draft.points.isEmpty()) {
            return AngleAnalysisDraftOutcome.Applied(this)
        }
        if (confirmation != AngleDiscardConfirmation.CONFIRMED) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                    AngleDiscardReason.DRAFT_WITH_POINTS,
                ),
            )
        }
        val editor = when (val outcome = current.editor.cancelDraft()) {
            is AngleEditorOutcome.Applied -> outcome.state
            is AngleEditorOutcome.Rejected ->
                return AngleAnalysisDraftOutcome.Rejected(
                    AngleAnalysisDraftRejection.EditorRejected(outcome.reason),
                )
        }
        if (editor === current.editor) return AngleAnalysisDraftOutcome.Applied(this)
        return AngleAnalysisDraftOutcome.Applied(
            replaceFrame(current, current.copy(editor = editor, revision = current.revision + 1L)),
        )
    }

    /**
     * Selecciona el fotograma visible. `null` limpia la selección.
     *
     * La selección es presentación: no valida ni descarta borradores, no mueve geometría y no cambia
     * la revisión de ningún fotograma. Un id desconocido se rechaza con
     * [AngleAnalysisDraftRejection.UnknownFrame].
     */
    fun selectFrame(frameId: AngleFrameId?): AngleAnalysisDraftOutcome {
        if (frameId == activeFrameId) return AngleAnalysisDraftOutcome.Applied(this)
        if (frameId == null) {
            return AngleAnalysisDraftOutcome.Applied(copy(activeFrameId = null))
        }
        if (frames.none { it.id == frameId }) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.UnknownFrame(frameId),
            )
        }
        return AngleAnalysisDraftOutcome.Applied(copy(activeFrameId = frameId))
    }

    /**
     * Mueve un fotograma a otra posición de presentación.
     *
     * Solo cambia el orden: ids, anclas, imágenes, editores, borradores y selección se conservan, y
     * ninguna revisión de fotograma cambia (una respuesta asíncrona pendiente sigue siendo válida).
     * Una posición fuera de `0 until frameCount` se rechaza.
     */
    fun moveFrame(frameId: AngleFrameId, toIndex: Int): AngleAnalysisDraftOutcome {
        val fromIndex = frames.indexOfFirst { it.id == frameId }
        if (fromIndex < 0) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.UnknownFrame(frameId),
            )
        }
        if (toIndex !in frames.indices) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.InvalidFramePosition(
                    requested = toIndex,
                    frameCount = frames.size,
                ),
            )
        }
        if (fromIndex == toIndex) return AngleAnalysisDraftOutcome.Applied(this)
        // En Kotlin MutableList, add(index, element) sobre una lista de la que se ha quitado el
        // elemento interpreta el índice en la lista ya sin él; por eso se reordena quitando y
        // volviendo a insertar, sin índices desplazados a mano.
        val reordered = frames.toMutableList().also { list ->
            val moved = list.removeAt(fromIndex)
            list.add(toIndex, moved)
        }
        return AngleAnalysisDraftOutcome.Applied(copy(frames = reordered))
    }

    /**
     * Aplica el resultado de una acción hecha sobre el editor de un fotograma.
     *
     * La [expectation] acota la respuesta a la fuente, generación, fotograma y revisión esperados.
     * Además del rechazo propio de [AngleEditorOutcome.Rejected] (que se devuelve tipado sin tocar
     * nada), se rechaza el resultado si su estado pertenece a otra fuente o generación, si está
     * anclado a otro frame, o si intenta anclar geometría en un fotograma cuya imagen congelada aún
     * no se ha adoptado. Un resultado idéntico al editor vigente es un no-op y no avanza la revisión.
     */
    fun applyEditorOutcome(
        expectation: AngleDraftExpectation,
        outcome: AngleEditorOutcome,
    ): AngleAnalysisDraftOutcome {
        staleRejection(expectation)?.let { return it }
        val current = frame(expectation.frameId) ?: return unknownFrame(expectation)
        val editor = when (outcome) {
            is AngleEditorOutcome.Rejected -> return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.EditorRejected(outcome.reason),
            )

            is AngleEditorOutcome.Applied -> outcome.state
        }
        if (editor.sourceIdentity != sourceIdentity) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.ForeignSource(
                    expectedSourceIdentity = sourceIdentity,
                    receivedSourceIdentity = editor.sourceIdentity,
                ),
            )
        }
        if (editor.generation != generation) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.StaleGeneration(
                    expectedGeneration = generation,
                    receivedGeneration = editor.generation,
                ),
            )
        }
        val editorAnchor = editor.anchor
        if (editorAnchor != null && editorAnchor != current.anchor) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.FrameAnchorMismatch(
                    frameId = current.id,
                    expected = current.anchor,
                    received = editorAnchor,
                ),
            )
        }
        if (current.image == null && editorAnchor != null) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.ImageNotAdopted(current.id),
            )
        }
        if (editor === current.editor) return AngleAnalysisDraftOutcome.Applied(this)
        return AngleAnalysisDraftOutcome.Applied(
            replaceFrame(current, current.copy(editor = editor, revision = current.revision + 1L)),
        )
    }

    /**
     * Plan de finalización **puro**: qué se podría guardar y qué lo impide.
     *
     * No guarda, no persiste, no crea archivos y no limpia nada. Devuelve
     * [AngleAnalysisFinalization.Blocked] con todos los motivos, en orden de fotograma, si el
     * análisis está vacío o si algún fotograma no tiene imagen adoptada, no tiene ningún ángulo
     * completo o conserva un borrador A/B pendiente. Solo con todos los fotogramas completos
     * devuelve [AngleAnalysisFinalization.Ready] con una instantánea de lo finalizable.
     */
    fun finalization(): AngleAnalysisFinalization {
        if (frames.isEmpty()) {
            return AngleAnalysisFinalization.Blocked(
                blockers = listOf(AngleFinalizationBlocker.NoFrames),
            )
        }
        val blockers = mutableListOf<AngleFinalizationBlocker>()
        frames.forEach { frame ->
            if (frame.image == null) {
                blockers += AngleFinalizationBlocker.MissingImage(frame.id)
            }
            val pendingPoints = frame.editor.draft.points.size
            if (pendingPoints > 0) {
                blockers += AngleFinalizationBlocker.PendingDraft(
                    frameId = frame.id,
                    placedPoints = pendingPoints,
                )
            }
            if (frame.editor.angles.isEmpty()) {
                blockers += AngleFinalizationBlocker.NoCompleteAngle(frame.id)
            }
        }
        if (blockers.isNotEmpty()) {
            return AngleAnalysisFinalization.Blocked(blockers = blockers)
        }
        return AngleAnalysisFinalization.Ready(
            sourceIdentity = sourceIdentity,
            generation = generation,
            frames = frames.map { frame ->
                AngleFrameReady(
                    id = frame.id,
                    anchor = frame.anchor,
                    image = requireNotNull(frame.image),
                    angles = frame.editor.angles,
                )
            },
        )
    }

    /** Crea un estado nuevo conservando lo que no se indica; nunca muta el actual. */
    private fun copy(
        frames: List<AngleFrameDraft> = this.frames,
        activeFrameId: AngleFrameId? = this.activeFrameId,
        retiredFrameIds: Set<AngleFrameId> = this.retiredFrameIds,
        retiredImageTokens: Set<String> = this.retiredImageTokens,
    ): AngleAnalysisDraftState = AngleAnalysisDraftState(
        sourceIdentity = sourceIdentity,
        generation = generation,
        frames = frames,
        activeFrameId = activeFrameId,
        retiredFrameIds = retiredFrameIds,
        retiredImageTokens = retiredImageTokens,
        defaults = defaults,
    )

    private fun replaceFrame(
        previous: AngleFrameDraft,
        updated: AngleFrameDraft,
        retiredImageTokens: Set<String> = this.retiredImageTokens,
    ): AngleAnalysisDraftState =
        copy(
            frames = frames.map { if (it.id == previous.id) updated else it },
            retiredImageTokens = retiredImageTokens,
        )

    /** Validación común y determinista: fuente → generación → fotograma → revisión. */
    private fun staleRejection(
        expectation: AngleDraftExpectation,
    ): AngleAnalysisDraftOutcome.Rejected? {
        if (expectation.sourceIdentity != sourceIdentity) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.ForeignSource(
                    expectedSourceIdentity = sourceIdentity,
                    receivedSourceIdentity = expectation.sourceIdentity,
                ),
            )
        }
        if (expectation.generation != generation) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.StaleGeneration(
                    expectedGeneration = generation,
                    receivedGeneration = expectation.generation,
                ),
            )
        }
        val current = frame(expectation.frameId)
        if (current == null) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.UnknownFrame(expectation.frameId),
            )
        }
        if (current.revision != expectation.revision) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.StaleFrameRevision(
                    frameId = current.id,
                    expectedRevision = expectation.revision,
                    actualRevision = current.revision,
                ),
            )
        }
        return null
    }

    private fun unknownFrame(expectation: AngleDraftExpectation): AngleAnalysisDraftOutcome =
        AngleAnalysisDraftOutcome.Rejected(
            AngleAnalysisDraftRejection.UnknownFrame(expectation.frameId),
        )

    /** Un token de imagen no puede pertenecer a otro fotograma vivo ni a uno eliminado. */
    private fun imageRejection(image: AngleFrozenImage): AngleAnalysisDraftOutcome.Rejected? {
        val owner = frames.firstOrNull { it.image?.token == image.token }
        if (owner != null) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.FrozenImageAlreadyUsed(
                    token = image.token,
                    frameId = owner.id,
                ),
            )
        }
        if (image.token in retiredImageTokens) {
            return AngleAnalysisDraftOutcome.Rejected(
                AngleAnalysisDraftRejection.FrozenImageRetired(image.token),
            )
        }
        return null
    }

    companion object {
        /**
         * Análisis vacío: sin fotogramas, sin selección y sin datos que perder.
         *
         * La fuente y la generación quedan fijadas para toda la vida del estado. Esta capa no tiene
         * ninguna operación para cambiar de fuente: abrir otro vídeo exige un estado nuevo.
         */
        fun initial(
            sourceIdentity: String,
            generation: Long,
            defaults: AngleEditorDefaults,
        ): AngleAnalysisDraftState {
            require(sourceIdentity.isNotBlank()) {
                "La identidad de la fuente del análisis no puede estar vacía."
            }
            return AngleAnalysisDraftState(
                sourceIdentity = sourceIdentity,
                generation = generation,
                frames = emptyList(),
                activeFrameId = null,
                retiredFrameIds = emptySet(),
                retiredImageTokens = emptySet(),
                defaults = defaults,
            )
        }
    }
}

/**
 * Un fotograma congelado del análisis: su identidad estable, el ancla que declara, su imagen (si ya
 * se ha adoptado) y su [AngleEditorState] independiente.
 *
 * [revision] es local a la sesión y avanza con cada cambio de contenido (imagen, editor o descarte
 * del borrador); reordenar o seleccionar no la cambian. El constructor valida la coherencia
 * interna, pero un fotograma solo entra en [AngleAnalysisDraftState] por sus operaciones, que
 * aplican las reglas de sesión (unicidad de ids/anclas, fuente, generación y confirmaciones).
 * Nunca contiene píxeles.
 */
data class AngleFrameDraft(
    val id: AngleFrameId,
    val anchor: AngleFrameAnchor,
    val image: AngleFrozenImage?,
    val editor: AngleEditorState,
    val revision: Long,
) {
    init {
        require(anchor.sourceIdentity == editor.sourceIdentity) {
            "El ancla del fotograma y su editor deben ser de la misma fuente."
        }
        require(editor.anchor == null || editor.anchor == anchor) {
            "El editor del fotograma solo puede anclarse al frame del propio fotograma."
        }
        require(image == null || image.anchor == anchor) {
            "La imagen del fotograma debe declarar el ancla del propio fotograma."
        }
        require(revision >= 0L) { "La revisión del fotograma debe ser >= 0, era $revision." }
    }

    /** `true` si borrar este fotograma perdería imagen, ángulos o puntos A/B. */
    val hasData: Boolean
        get() = image != null || editor.angles.isNotEmpty() || editor.draft.points.isNotEmpty()
}

/**
 * Identidad local y estable de un fotograma dentro de un [AngleAnalysisDraftState].
 *
 * La aporta el llamante y es independiente del índice visible (`frameIndex`) y de la posición en
 * [AngleAnalysisDraftState.frames]: reordenar o quitar otros fotogramas no la cambia. No es un id de
 * Room ni de persistencia.
 */
@JvmInline
value class AngleFrameId(val value: Long) {
    init {
        require(value > 0L) { "AngleFrameId debe ser > 0, era $value." }
    }
}

/**
 * Referencia **opaca** a la imagen congelada de un fotograma y el ancla que el adaptador de captura
 * declara para ella.
 *
 * Esta capa no interpreta [token], no lo abre, no comprueba que el archivo exista y **no autentica
 * píxeles, orientación ni PTS**: solo exige que la ancla declarada coincida con la del fotograma y
 * que el token no se reutilice. Autenticar la imagen es responsabilidad del adaptador de captura
 * (experimento B), que debe construir esta referencia únicamente después de verificar su contenido.
 * No contiene ni admite bitmaps.
 */
data class AngleFrozenImage(
    val token: String,
    val anchor: AngleFrameAnchor,
) {
    init {
        require(token.isNotBlank()) { "El token de la imagen congelada no puede estar vacío." }
    }
}

/**
 * Instantánea de la fuente, generación, fotograma y revisión a la que se acota una respuesta
 * asíncrona.
 *
 * El llamante la obtiene con [AngleAnalysisDraftState.expectationFor] antes de iniciar el trabajo y
 * la reenvía al aplicarlo. Si la sesión, el fotograma o su revisión cambiaron mientras tanto, la
 * operación se rechaza y no se aplica a un estado distinto del que la produjo.
 */
data class AngleDraftExpectation(
    val sourceIdentity: String,
    val generation: Long,
    val frameId: AngleFrameId,
    val revision: Long,
) {
    init {
        require(sourceIdentity.isNotBlank()) {
            "La identidad de la fuente de la expectativa no puede estar vacía."
        }
        require(revision >= 0L) { "La revisión esperada debe ser >= 0, era $revision." }
    }
}

/** Confirmación explícita del usuario para una acción destructiva; nunca se asume. */
enum class AngleDiscardConfirmation {
    /** Todavía no hay confirmación: con datos presentes la acción se rechaza. */
    NOT_CONFIRMED,

    /** El usuario ha confirmado explícitamente que se puede descartar. */
    CONFIRMED,
}

/** Qué acción destructiva exige confirmación, para que la UI pueda pedirla con el texto correcto. */
enum class AngleDiscardReason {
    /** Eliminar un fotograma que conserva imagen, ángulos o puntos A/B. */
    FRAME_REMOVAL,

    /** Descartar los puntos A/B todavía sin completar. */
    DRAFT_WITH_POINTS,

    /** Sustituir una imagen congelada ya adoptada (no se puede recuperar desde esta capa). */
    IMAGE_REPLACEMENT,
}

/** Resultado de una operación de la sesión: estado nuevo inmutable o rechazo tipado sin cambios. */
sealed interface AngleAnalysisDraftOutcome {

    /** Operación aplicada; [state] puede ser el mismo objeto si fue un no-op. */
    data class Applied(val state: AngleAnalysisDraftState) : AngleAnalysisDraftOutcome

    /** Operación rechazada; el estado del que se partió no se modificó. */
    data class Rejected(val reason: AngleAnalysisDraftRejection) : AngleAnalysisDraftOutcome
}

/** Motivos tipados por los que la sesión rechaza una operación. */
sealed interface AngleAnalysisDraftRejection {

    /** La operación es de otra fuente; no se mezclan análisis entre vídeos. */
    data class ForeignSource(
        val expectedSourceIdentity: String,
        val receivedSourceIdentity: String,
    ) : AngleAnalysisDraftRejection

    /** La operación pertenece a una generación de render anterior o distinta. */
    data class StaleGeneration(
        val expectedGeneration: Long,
        val receivedGeneration: Long,
    ) : AngleAnalysisDraftRejection

    /** No existe ningún fotograma con ese id en el análisis. */
    data class UnknownFrame(val frameId: AngleFrameId) : AngleAnalysisDraftRejection

    /** Ya hay un fotograma vivo con ese id (respuesta duplicada o id reutilizado). */
    data class DuplicateFrameId(val frameId: AngleFrameId) : AngleAnalysisDraftRejection

    /** El id pertenecía a un fotograma eliminado; no se reutiliza para otro contenido. */
    data class RetiredFrameId(val frameId: AngleFrameId) : AngleAnalysisDraftRejection

    /** Ya hay un fotograma con exactamente el mismo ancla (fuente, índice y PTS). */
    data class DuplicateFrameAnchor(
        val existingFrameId: AngleFrameId,
        val anchor: AngleFrameAnchor,
    ) : AngleAnalysisDraftRejection

    /** La respuesta se calculó sobre una revisión anterior del fotograma. */
    data class StaleFrameRevision(
        val frameId: AngleFrameId,
        val expectedRevision: Long,
        val actualRevision: Long,
    ) : AngleAnalysisDraftRejection

    /** El estado del editor está anclado a otro frame, no al del fotograma destino. */
    data class FrameAnchorMismatch(
        val frameId: AngleFrameId,
        val expected: AngleFrameAnchor,
        val received: AngleFrameAnchor,
    ) : AngleAnalysisDraftRejection

    /** La imagen declara el ancla de otro fotograma (u otra fuente). */
    data class ImageBoundToOtherFrame(
        val expected: AngleFrameAnchor,
        val received: AngleFrameAnchor,
    ) : AngleAnalysisDraftRejection

    /**
     * El fotograma ya tiene imagen adoptada; cambiarla exige
     * [AngleAnalysisDraftState.replaceFrozenImage].
     */
    data class ImageAlreadyAdopted(val frameId: AngleFrameId) : AngleAnalysisDraftRejection

    /** Se pidió reemplazar la imagen de un fotograma que todavía no tiene ninguna. */
    data class NoImageToReplace(val frameId: AngleFrameId) : AngleAnalysisDraftRejection

    /**
     * El fotograma conserva geometría, así que sustituir su imagen movería marcas medidas sobre
     * otra imagen; la operación se rechaza aunque llegue confirmación.
     *
     * [completeAngles] y [draftPoints] son el recuento que motivó el rechazo (al menos uno es > 0).
     * Para cambiar de imagen hay que eliminar el fotograma y crear otro con un id nuevo.
     */
    data class ImageReplacementBlockedByGeometry(
        val frameId: AngleFrameId,
        val completeAngles: Int,
        val draftPoints: Int,
    ) : AngleAnalysisDraftRejection

    /** Otro fotograma vivo ya usa ese token de imagen congelada. */
    data class FrozenImageAlreadyUsed(
        val token: String,
        val frameId: AngleFrameId,
    ) : AngleAnalysisDraftRejection

    /** El token de imagen pertenecía a un fotograma eliminado; no se reutiliza. */
    data class FrozenImageRetired(val token: String) : AngleAnalysisDraftRejection

    /** No se puede anclar geometría en un fotograma sin imagen congelada adoptada. */
    data class ImageNotAdopted(val frameId: AngleFrameId) : AngleAnalysisDraftRejection

    /** La acción descartaría datos y no llegó la confirmación explícita del usuario. */
    data class DiscardConfirmationRequired(
        val reason: AngleDiscardReason,
    ) : AngleAnalysisDraftRejection

    /** El editor rechazó la acción; se propaga el motivo tipado sin tocar la sesión. */
    data class EditorRejected(
        val editorRejection: AngleEditorRejection,
    ) : AngleAnalysisDraftRejection

    /** La posición de destino no existe en el orden actual del análisis. */
    data class InvalidFramePosition(
        val requested: Int,
        val frameCount: Int,
    ) : AngleAnalysisDraftRejection
}

/**
 * Resultado de la consulta de finalización: instantánea finalizable o lista completa de bloqueos.
 *
 * No es una orden de guardado ni implica que exista persistencia: esta capa solo decide si los
 * datos puros de la sesión cumplen el contrato de guardado.
 */
sealed interface AngleAnalysisFinalization {

    /** Todos los fotogramas están completos; no se ha guardado nada todavía. */
    data class Ready(
        val sourceIdentity: String,
        val generation: Long,
        val frames: List<AngleFrameReady>,
    ) : AngleAnalysisFinalization

    /** El análisis no se puede guardar; [blockers] está en orden de fotograma. */
    data class Blocked(
        val blockers: List<AngleFinalizationBlocker>,
    ) : AngleAnalysisFinalization
}

/** Datos de un fotograma que cumple el contrato de finalización; sin píxeles ni rutas. */
data class AngleFrameReady(
    val id: AngleFrameId,
    val anchor: AngleFrameAnchor,
    val image: AngleFrozenImage,
    val angles: List<Angle>,
)

/** Motivo tipado por el que el análisis no se puede dar por completo. */
sealed interface AngleFinalizationBlocker {

    /** El análisis no tiene ningún fotograma. */
    data object NoFrames : AngleFinalizationBlocker

    /** El fotograma no tiene imagen congelada adoptada. */
    data class MissingImage(val frameId: AngleFrameId) : AngleFinalizationBlocker

    /** El fotograma no tiene ningún ángulo completo. */
    data class NoCompleteAngle(val frameId: AngleFrameId) : AngleFinalizationBlocker

    /** El fotograma conserva puntos A/B sin completar; [placedPoints] es 1 o 2. */
    data class PendingDraft(
        val frameId: AngleFrameId,
        val placedPoints: Int,
    ) : AngleFinalizationBlocker
}
