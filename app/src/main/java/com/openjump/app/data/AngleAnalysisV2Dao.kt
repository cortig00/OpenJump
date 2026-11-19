package com.openjump.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.openjump.app.data.angles.AngleImageKey
import com.openjump.app.data.backup.MEDIA_BACKUP_MAX_IMAGES

/**
 * Acceso Room al agregado multi-fotograma
 * ([AngleAnalysisV2RootEntity] → [AngleAnalysisV2FrameEntity] → [AngleAnalysisV2MarkEntity]).
 *
 * **Lectura.** Las consultas son mecánicas (orden y alcance explícitos) para que un lector no dependa
 * de heurísticas: los fotogramas se leen por `(rootId, ordinal, id)` y las marcas por
 * `(frameId, ordinal, id)`. [aggregateBySessionKey] devuelve la instantánea completa de las tres
 * tablas leída dentro de **una sola** transacción, para que una comparación de idempotencia no
 * mezcle filas de dos estados distintos.
 *
 * **Escritura (dos puertas, ambas transaccionales).** No existe ningún `UPDATE`: los resultados
 * guardados son inmutables en cuanto a contenido. Hay exactamente dos caminos de escritura:
 *  - **Alta:** [insertAggregate] es el ÚNICO camino de alta de producto y lo hace en una sola
 *    `@Transaction` de Room: raíz → fotogramas → marcas. No inserta a medias: cualquier fallo
 *    —validación, colisión de índice único o excepción de Room— aborta la transacción completa y
 *    Room la revierte, así que no queda una raíz con menos fotogramas de los declarados. Sigue **sin** existir un llamante de UI: la composición de la petición, la
 *    publicación/verificación de PNG y el ciclo de backup son responsabilidad de
 *    `com.openjump.app.data.angles.AngleV2SaveRepository` (escritor de imágenes + alta), que no está
 *    conectado a ninguna pantalla. `ManualBackupDao` mantiene intacta su barrera v1
 *    ([ManualBackupV2BlockedException]) mientras existan filas V2, y su restore con imágenes
 *    (`replaceAllWithAngleMedia`) sigue siendo la única otra vía de alta V2.
 *  - **Borrado confirmado:** [deleteConfirmedAggregate] exige la **prueba exacta** del agregado
 *    completo (raíz + fotogramas + marcas, con sus ids, claves, columnas y timestamps), relee el
 *    agregado **dentro de la misma transacción** y sólo borra la fila de raíz si la prueba coincide
 *    **exactamente**. La cascada de claves foráneas retira los fotogramas y marcas hijos. No hay
 *    ninguna operación de borrado por id suelto en esta API: sin prueba completa no se borra nada.
 *    El resultado tipado incluye las claves e integridad de las imágenes que poseía el agregado,
 *    para que el almacén privado pueda reclamarlas **fuera** de Room (aquí no se toca ningún
 *    fichero). La composición de la prueba, el mutex de coordinación y la reclamación de imágenes
 *    son del repositorio de borrado, que tampoco existe todavía en UI.
 *
 * **Room y el almacén de imágenes no comparten transacción.** Esta puerta sólo escribe filas: las
 * columnas `image*` se guardan tal y como las declara el llamante. Publicar los PNG, verificarlos
 * contra su tamaño/SHA-256/dimensiones y **no** llamar aquí si esa verificación falla es contrato
 * del llamante; esta puerta no puede detectar un medio ausente o sustituido.
 *
 * **Idempotencia y colisión (huella completa, fail-closed).** El índice único de `sessionKey`
 * garantiza que una sesión de captura produce como máximo una raíz. Si al insertar ya existe una
 * raíz con el mismo `sessionKey` se comparan **todas** las columnas semánticas persistidas
 * ([describeRootDifference] para la raíz y [describeAggregateDifference] para fotogramas y marcas),
 * y sólo se devuelve [AngleAnalysisV2InsertOutcome.AlreadyStored] si coinciden **todas**:
 *  - raíz: `analysisFormat`, `sessionKey`, `athleteId` y `revision`;
 *  - fotogramas: `ordinal`, `assignedFrameIndex`/`reportedFrameIndex`,
 *    `assignedPtsUs`/`reportedPtsUs`, `encodedWidth`/`encodedHeight`,
 *    `presentationWidth`/`presentationHeight`, `rotationDegrees`, `geometryVersion` y la integridad
 *    declarada de la imagen (`imageWidth`, `imageHeight`, `imageByteCount`, `imageSha256`,
 *    `imageMimeType`);
 *  - marcas: `ordinal`, `name`, `colorArgb`, `visible` y las seis coordenadas.
 *
 *  Se excluyen **sólo** las columnas que no son contenido: los `id` autogenerados, `rootId`/
 *  `frameId` (los asigna la base), `imageKey` (cada intento publica una clave nueva con los mismos
 *  bytes) y `createdAt`/`updatedAt` de la raíz (los deriva el intento de guardado con su reloj, no
 *  el usuario; ver [AngleAnalysisV2RootSemantics]).
 *
 *  Cualquier diferencia —o cualquier columna que no se pueda comprobar— ⇒
 *  [AngleAnalysisV2AggregateConflictException] y rollback total: **nunca** se informa de "ya
 *  guardado" con una coincidencia parcial, porque eso perdería en silencio el segundo análisis
 *  (mismos píxeles, otras marcas o instantes). No se "adopta" ni se mezcla la raíz ajena ni se
 *  sobrescribe un resultado inmutable.
 *
 * Las colisiones de los demás índices únicos (`imageKey` repetida, `(rootId, ordinal)` o
 * `(frameId, ordinal)` duplicados) no se tragan: la inserción usa `ABORT` y
 * `SQLiteConstraintException` propaga, con la transacción ya revertida por Room.
 */
@Dao
interface AngleAnalysisV2Dao {

    /**
     * Raíz por identidad de tabla + id. Un `id` igual al de una fila de `angle_analyses` es otra
     * fila distinta: la identidad de tipo la aporta la tabla, no el número.
     */
    @Query("SELECT * FROM angle_analysis_v2_roots WHERE id = :rootId LIMIT 1")
    suspend fun rootById(rootId: Long): AngleAnalysisV2RootEntity?

    @Query("SELECT * FROM angle_analysis_v2_roots WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun rootBySessionKey(sessionKey: String): AngleAnalysisV2RootEntity?

    /** Fotogramas de una raíz en su orden estable; `id` desempata ordinales repetidos imposibles. */
    @Query(
        "SELECT * FROM angle_analysis_v2_frames WHERE rootId = :rootId " +
            "ORDER BY ordinal ASC, id ASC",
    )
    suspend fun framesForRoot(rootId: Long): List<AngleAnalysisV2FrameEntity>

    /** Fotograma concreto acotado a su raíz, para distinguir "no existe" de "es de otra raíz". */
    @Query("SELECT * FROM angle_analysis_v2_frames WHERE id = :frameId AND rootId = :rootId LIMIT 1")
    suspend fun frameById(rootId: Long, frameId: Long): AngleAnalysisV2FrameEntity?

    /** Marcas de un fotograma en su orden estable. */
    @Query(
        "SELECT * FROM angle_analysis_v2_marks WHERE frameId = :frameId " +
            "ORDER BY ordinal ASC, id ASC",
    )
    suspend fun marksForFrame(frameId: Long): List<AngleAnalysisV2MarkEntity>

    /** Número de fotogramas de una raíz, sin cargar filas. */
    @Query("SELECT COUNT(*) FROM angle_analysis_v2_frames WHERE rootId = :rootId")
    suspend fun frameCount(rootId: Long): Int

    /**
     * Referencias **persistidas** a una clave de imagen: cuántos fotogramas V2 la declaran.
     *
     * Es una lectura mecánica para que el repositorio de borrado **vuelva a comprobar**, justo antes
     * de reclamar el fichero, que ya no queda ninguna fila que lo referencie. Sólo mira esta tabla:
     * las imágenes privadas de ángulos no las referencian las tablas legacy ni el vídeo del usuario,
     * y el almacén puede contener ficheros sin fila (huérfanos) que esta consulta no ve. Por sí sola
     * **no** autoriza a borrar un fichero: entre esta lectura y el `unlink` hace falta la exclusión
     * del coordinador de medios V2 (el mutex de proceso), porque Room y el sistema de ficheros no
     * comparten transacción.
     */
    @Query("SELECT COUNT(*) FROM angle_analysis_v2_frames WHERE imageKey = :imageKey")
    suspend fun frameImageReferenceCount(imageKey: String): Int

    /**
     * Instantánea **consistente** del agregado de [sessionKey], o `null` si todavía no existe.
     *
     * Lee raíz, fotogramas y marcas dentro de una única transacción de Room: la comparación de
     * idempotencia del llamante no puede mezclar una raíz ya leída con fotogramas o marcas de otro
     * estado. Es de sólo lectura y mecánica (mismos órdenes que [framesForRoot]/[marksForFrame]).
     *
     * Sustituye a las huellas parciales (SHA de imagen + conteos), que bastaban para descartar
     * "otro contenido" pero no para afirmar "el mismo": dos análisis con los mismos píxeles y
     * marcas distintas habrían coincidido en esas columnas.
     */
    @Transaction
    suspend fun aggregateBySessionKey(sessionKey: String): AngleAnalysisV2StoredAggregate? {
        val root = rootBySessionKey(sessionKey) ?: return null
        return AngleAnalysisV2StoredAggregate(
            root = root,
            frames = framesForRoot(root.id).map { frame ->
                AngleAnalysisV2StoredFrame(frame = frame, marks = marksForFrame(frame.id))
            },
        )
    }

    /**
     * Instantánea **consistente** del agregado de [rootId] por **identidad de tabla + id**.
     *
     * Es la lectura de consulta del detalle guardado V2: no abre vídeo, no escribe, no borra y no
     * renderiza nada; devuelve exactamente las filas persistidas para que la UI muestre los
     * fotogramas en su orden estable y las marcas de cada uno. Raíz, fotogramas y marcas se leen en
     * **una sola** transacción para que una foto de pantalla no mezcle dos estados (por ejemplo una
     * raíz con fotogramas de otra escritura).
     *
     * Un `id` numéricamente igual al de una fila de `angle_analyses` **no** es este agregado: la
     * identidad de tipo la aporta esta tabla, y el llamante no debe usar esta función con un id que
     * provenga del family legacy (ni al contrario). Sólo lectura: no abre vídeo, no escribe, no
     * renderiza y no borra; el único camino de `DELETE` V2 es la puerta confirmada
     * [deleteConfirmedAggregate] (los resultados guardados son inmutables y no hay `UPDATE`).
     */
    @Transaction
    suspend fun aggregateByRootId(rootId: Long): AngleAnalysisV2StoredAggregate? {
        if (rootId <= 0L) return null
        val root = rootById(rootId) ?: return null
        return AngleAnalysisV2StoredAggregate(
            root = root,
            frames = framesForRoot(root.id).map { frame ->
                AngleAnalysisV2StoredFrame(frame = frame, marks = marksForFrame(frame.id))
            },
        )
    }

    /**
     * Inserta una raíz nueva. `ABORT` es deliberado: una `sessionKey` repetida debe abortar la
     * transacción, no reemplazar la raíz existente. El `id` lo asigna Room (`id = 0` en el borrador).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoot(root: AngleAnalysisV2RootEntity): Long

    /** Inserta un fotograma nuevo y devuelve su `id` autogenerado. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFrame(frame: AngleAnalysisV2FrameEntity): Long

    /** Inserta las marcas de un fotograma **ya insertado**; el llamante fija `frameId`. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMarks(marks: List<AngleAnalysisV2MarkEntity>)

    /**
     * Alta **transaccional** de un agregado completo: raíz + N fotogramas + sus marcas.
     *
     * Contrato:
     *  - [draft] se valida **antes** de escribir la primera fila ([AngleAnalysisV2AggregateDraft.validate]);
     *    un borrador inválido lanza [IllegalArgumentException] y no toca la base;
     *  - no hay `UPDATE` ni `DELETE`: los resultados son inmutables y una repetición no reescribe;
     *  - `sessionKey` es el identificador de idempotencia: si ya existe una raíz con **todas** las
     *    columnas semánticas idénticas devuelve [AngleAnalysisV2InsertOutcome.AlreadyStored] sin
     *    insertar nada; cualquier diferencia lanza [AngleAnalysisV2AggregateConflictException]
     *    (ver KDoc de la interfaz);
     *  - cualquier excepción (validación, conflicto, `SQLiteConstraintException`, fallo de Room)
     *    revierte **toda** la transacción: no queda una raíz sin sus fotogramas ni un fotograma sin
     *    sus marcas;
     *  - el llamante debe publicar y verificar los PNG en el almacén privado **antes** de invocar esta
     *    función: esta puerta no escribe ficheros y no comprueba que existan. Si la transacción falla,
     *    los ficheros ya publicados quedan sin fila; su conservación y su reporte son del llamante
     *    (aquí no se borran).
     *
     * Los `id` de raíz, fotograma y marca y el `frameId` de cada marca los asigna esta función: los
     * borradores deben traerlos a `0`.
     */
    @Transaction
    suspend fun insertAggregate(draft: AngleAnalysisV2AggregateDraft): AngleAnalysisV2InsertOutcome {
        draft.validate()
        val existing = rootBySessionKey(draft.root.sessionKey)
        if (existing != null) {
            // Huella completa dentro de la misma transacción: raíz, fotogramas y marcas. Cualquier
            // diferencia —o cualquier columna que no se pueda comparar— es conflicto, nunca
            // "ya guardado".
            val rootDifference = describeRootDifference(
                stored = existing.toSemantics(),
                expected = draft.root.toSemantics(),
            )
            if (rootDifference != null) {
                throw AngleAnalysisV2AggregateConflictException(
                    "La sessionKey ${existing.describeSession()} ya está guardada con otra raíz " +
                        "($rootDifference): no se reescribe un resultado inmutable.",
                )
            }
            val storedFrames = framesForRoot(existing.id).map { frame ->
                AngleAnalysisV2StoredFrame(frame = frame, marks = marksForFrame(frame.id))
            }
            val contentDifference = describeAggregateDifference(
                stored = storedFrames.map { storedFrame -> storedFrame.toSemantics() },
                expected = draft.frames.map { child -> child.toSemantics() },
            )
            if (contentDifference != null) {
                throw AngleAnalysisV2AggregateConflictException(
                    "La sessionKey ${existing.describeSession()} ya está guardada con otro contenido " +
                        "($contentDifference): no se reescribe un resultado inmutable.",
                )
            }
            return AngleAnalysisV2InsertOutcome.AlreadyStored(existing.id)
        }
        val rootId = insertRoot(draft.root)
        val frameIds = ArrayList<Long>(draft.frames.size)
        draft.frames.forEach { child ->
            // `rootId` y `frameId` los aporta la base: el borrador sólo trae el `id` a 0 que valida.
            val frameId = insertFrame(child.frame.copy(rootId = rootId))
            frameIds += frameId
            if (child.marks.isNotEmpty()) {
                insertMarks(child.marks.map { mark -> mark.copy(frameId = frameId) })
            }
        }
        check(frameIds.size == draft.frames.size) { "El alta debe insertar exactamente N fotogramas." }
        return AngleAnalysisV2InsertOutcome.Inserted(rootId = rootId, frameIds = frameIds)
    }

    /**
     * **Primitiva de borrado de una fila de raíz.** `DELETE` de exactamente una fila por `id`; no
     * valida la prueba, no compara columnas y no comprueba nada más.
     *
     * Su único llamante legítimo es [deleteConfirmedAggregate], **dentro** de su `@Transaction` y
     * **después** de que la prueba exacta haya coincidido. Room **no puede** impedir que otro
     * llamante con acceso al DAO la invoque por su cuenta: no es una API segura y no se debe usar
     * desde UI, backup ni scripts. Un `id` de esta tabla y uno de `angle_analyses` son espacios
     * distintos; esta consulta nunca toca la tabla legacy.
     *
     * Devuelve el número de filas afectadas (1 si existía; 0 si ya no estaba). La cascada de claves
     * foráneas retira fotogramas y marcas hijos cuando el `PRAGMA foreign_keys` de Room está activo.
     */
    @Query("DELETE FROM angle_analysis_v2_roots WHERE id = :rootId")
    suspend fun deleteRootRow(rootId: Long): Int

    /**
     * Borrado **confirmado** de un agregado completo, comparado por igualdad exacta dentro de la
     * transacción.
     *
     * Contrato:
     *  - [proof] no es un id: es la instantánea completa del agregado (raíz, fotogramas en orden y
     *    marcas en orden, con **todas** las columnas persistidas, incluidos `id`, `rootId`/`frameId`,
     *    `imageKey` y `createdAt`/`updatedAt`). Se obtiene con [aggregateByRootId] o
     *    [aggregateBySessionKey]; una prueba construida a mano con un id suelto no puede borrar
     *    nada.
     *  - **Validación previa:** [describeInvalidDeletionProof] comprueba forma, la cota de
     *    fotogramas del contrato de backup, ordinales contiguos, ids persistidos, claves canónicas
     *    y no repetidas, propiedad de los hijos y la integridad declarada de la imagen. Cualquier
     *    problema devuelve [AngleAnalysisV2DeleteOutcome.InvalidProof] **sin escribir**. No se
     *    descarta ni se ignora ninguna clave: una clave no canónica o repetida invalida la prueba
     *    entera.
     *  - **Lectura dentro de la transacción:** raíz, fotogramas y marcas se releen aquí (una sola
     *    `@Transaction`), así que la comparación no mezcla dos estados. La comparación es de
     *    **igualdad exacta** de filas, no la huella semántica del alta: el borrado también exige que
     *    coincidan los ids, la `imageKey` y los timestamps, porque son justo la identidad de lo que
     *    se va a borrar y de los ficheros que se van a devolver.
     *  - **Resultados:** si la raíz ya no existe → [AngleAnalysisV2DeleteOutcome.Missing]; si existe
     *    pero difiere en cualquier columna (geometría, clave, marcas, `sessionKey`, timestamps…) →
     *    [AngleAnalysisV2DeleteOutcome.Changed] y **no** se escribe nada; si coincide exactamente se
     *    borra **sólo** la fila de `angle_analysis_v2_roots` y se devuelve
     *    [AngleAnalysisV2DeleteOutcome.Deleted] con la lista **exacta** de imágenes que el agregado
     *    poseía (clave + integridad declarada, tal cual estaba persistida).
     *  - **Atomicidad:** la transacción debe afectar a **exactamente una** fila de raíz. Si el
     *    `DELETE` afectase a cero (por ejemplo una restricción/trigger lo ignora) se lanza
     *    [AngleAnalysisV2DeleteRollbackException] y Room revierte la transacción: nunca se informa
     *    [AngleAnalysisV2DeleteOutcome.Deleted] de un borrado que no ocurrió, ni siquiera
     *    parcialmente.
     *  - **Sin E/S de ficheros:** esta puerta no borra, mueve ni verifica PNG; devuelve los metadatos
     *    para que el repositorio de borrado los reclame después, con la comprobación de referencias
     *    ([frameImageReferenceCount]) bajo el mutex del coordinador de medios.
     *  - **Sin `UPDATE`:** no hay camino para reescribir un resultado guardado; borrar y volver a
     *    guardar produce filas nuevas.
     *
     * @throws AngleAnalysisV2DeleteRollbackException sólo si el `DELETE` no afecta exactamente a la
     *   fila confirmada; es un fallo interno inesperado y revierte la transacción.
     */
    @Transaction
    suspend fun deleteConfirmedAggregate(
        proof: AngleAnalysisV2StoredAggregate,
    ): AngleAnalysisV2DeleteOutcome {
        val invalid = describeInvalidDeletionProof(proof)
        if (invalid != null) return AngleAnalysisV2DeleteOutcome.InvalidProof(detail = invalid)
        val currentRoot = rootById(proof.root.id)
            ?: return AngleAnalysisV2DeleteOutcome.Missing(rootId = proof.root.id)
        // Relectura completa dentro de ESTA transacción: la prueba se contrasta contra el estado
        // que se va a borrar, no contra la lectura que hizo el llamante antes.
        val current = AngleAnalysisV2StoredAggregate(
            root = currentRoot,
            frames = framesForRoot(currentRoot.id).map { frame ->
                AngleAnalysisV2StoredFrame(frame = frame, marks = marksForFrame(frame.id))
            },
        )
        val difference = describeExactAggregateDifference(stored = current, proof = proof)
        if (difference != null) {
            return AngleAnalysisV2DeleteOutcome.Changed(
                rootId = currentRoot.id,
                detail = difference,
            )
        }
        // Se capturan las imágenes ANTES de borrar, desde las filas releídas: es la lista exacta de
        // ficheros que el agregado poseía, sin deduplicar nada (una imagen por fotograma).
        val ownedImages = current.frames.map { storedFrame ->
            val frame = storedFrame.frame
            AngleAnalysisV2OwnedImage(
                imageKey = frame.imageKey,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                imageByteCount = frame.imageByteCount,
                imageSha256 = frame.imageSha256,
                imageMimeType = frame.imageMimeType,
            )
        }
        check(ownedImages.map { it.imageKey }.toSet().size == ownedImages.size) {
            "Cada fotograma tiene una clave de imagen y el índice único impide repetirla."
        }
        val deletedRows = deleteRootRow(currentRoot.id)
        if (deletedRows != 1) {
            // La transacción se revierte: ni se informa de éxito ni queda un borrado parcial.
            throw AngleAnalysisV2DeleteRollbackException(
                "El borrado confirmado de la raíz ${currentRoot.id} afectó a $deletedRows filas " +
                    "(se esperaba exactamente 1): se revierte toda la transacción.",
            )
        }
        return AngleAnalysisV2DeleteOutcome.Deleted(
            rootId = currentRoot.id,
            ownedImages = ownedImages,
        )
    }
}

/** Fotograma de un alta, con sus marcas ya compuestas (todavía sin `id` ni `frameId`). */
data class AngleAnalysisV2FrameDraft(
    val frame: AngleAnalysisV2FrameEntity,
    val marks: List<AngleAnalysisV2MarkEntity> = emptyList(),
)

/**
 * Borrador completo de un alta V2: la raíz y sus fotogramas ordenados con sus marcas.
 *
 * Es un valor de entrada, no una fila: todos los `id` (raíz, fotograma, marca) y todos los
 * `rootId`/`frameId` deben venir a `0` porque los asigna Room. [validate] comprueba la forma
 * (ordinales contiguos, claves canónicas, integridad declarada, geometría y coordenadas finitas)
 * **antes** de que se escriba ninguna fila.
 */
data class AngleAnalysisV2AggregateDraft(
    val root: AngleAnalysisV2RootEntity,
    val frames: List<AngleAnalysisV2FrameDraft>,
) {

    /**
     * Lanza [IllegalArgumentException] con el primer problema encontrado.
     *
     * No sustituye a la validación del llamante: aquí se comprueba que el agregado sea
     * *persistible* (forma, rangos e integridad declarada), no que los PNG existan.
     */
    fun validate() {
        require(root.id == 0L) { "La raíz del alta debe traer id = 0." }
        require(root.analysisFormat == ANGLE_ANALYSIS_V2_FORMAT) {
            "Formato de análisis no soportado: ${root.analysisFormat}."
        }
        require(root.sessionKey.isNotBlank()) { "La sessionKey no puede estar vacía." }
        require(root.revision == 0) { "Una raíz nueva debe empezar en revision = 0." }
        require(frames.isNotEmpty()) { "Un alta V2 necesita al menos un fotograma." }
        require(frames.size == frames.map { it.frame.imageKey }.toSet().size) {
            "Cada fotograma necesita una imageKey distinta."
        }
        frames.forEachIndexed { index, child ->
            val frame = child.frame
            require(frame.id == 0L && frame.rootId == 0L) {
                "El fotograma $index debe traer id = 0 y rootId = 0."
            }
            require(frame.ordinal == index) {
                "Los ordinales de fotograma deben ser contiguos desde 0: se esperaba $index y llegó ${frame.ordinal}."
            }
            require(AngleImageKey.isCanonical(frame.imageKey)) {
                "La imageKey del fotograma $index no tiene la forma canónica de 32 hex."
            }
            require(frame.assignedFrameIndex >= 0) { "assignedFrameIndex negativo en el fotograma $index." }
            require(frame.assignedPtsUs >= 0L) { "assignedPtsUs negativo en el fotograma $index." }
            require((frame.reportedFrameIndex ?: 0) >= 0) {
                "reportedFrameIndex negativo en el fotograma $index."
            }
            require((frame.reportedPtsUs ?: 0L) >= 0L) {
                "reportedPtsUs negativo en el fotograma $index."
            }
            require(frame.encodedWidth >= 1 && frame.encodedHeight >= 1) {
                "Dimensiones codificadas no positivas en el fotograma $index."
            }
            require(frame.presentationWidth >= 1 && frame.presentationHeight >= 1) {
                "Dimensiones de presentación no positivas en el fotograma $index."
            }
            require(frame.rotationDegrees in 0..359) { "rotationDegrees fuera de 0..359 en el fotograma $index." }
            require(frame.geometryVersion >= 1) { "geometryVersion debe ser >= 1 en el fotograma $index." }
            require(frame.imageWidth >= 1 && frame.imageHeight >= 1) {
                "Dimensiones de imagen no positivas en el fotograma $index."
            }
            require(frame.imageByteCount >= 1L) { "imageByteCount debe ser positivo en el fotograma $index." }
            require(SHA256_HEX.matches(frame.imageSha256)) {
                "imageSha256 debe ser hexadecimal de 64 caracteres en el fotograma $index."
            }
            require(frame.imageMimeType.isNotBlank()) { "imageMimeType vacío en el fotograma $index." }
            child.marks.forEachIndexed { markIndex, mark ->
                require(mark.id == 0L && mark.frameId == 0L) {
                    "La marca $markIndex del fotograma $index debe traer id = 0 y frameId = 0."
                }
                require(mark.ordinal == markIndex) {
                    "Los ordinales de marca del fotograma $index deben ser contiguos desde 0."
                }
                require(mark.name.isNotBlank()) { "Nombre de marca vacío en el fotograma $index." }
                require(
                    mark.pointAX.isFinite() && mark.pointAY.isFinite() &&
                        mark.pointBX.isFinite() && mark.pointBY.isFinite() &&
                        mark.pointCX.isFinite() && mark.pointCY.isFinite(),
                ) { "Coordenadas no finitas en la marca $markIndex del fotograma $index." }
            }
        }
    }
}

/** Resultado de [AngleAnalysisV2Dao.insertAggregate]: alta nueva o repetición idempotente. */
sealed interface AngleAnalysisV2InsertOutcome {

    /** Raíz nueva insertada con sus fotogramas; [frameIds] en el orden de los ordinales. */
    data class Inserted(val rootId: Long, val frameIds: List<Long>) : AngleAnalysisV2InsertOutcome

    /**
     * La `sessionKey` ya estaba guardada con el mismo contenido semántico completo (todas las
     * columnas comparadas de raíz, fotogramas y marcas): no se ha insertado ni modificado nada.
     *
     * Sólo se devuelve ante una coincidencia **completa**; una coincidencia parcial es
     * [AngleAnalysisV2AggregateConflictException].
     */
    data class AlreadyStored(val rootId: Long) : AngleAnalysisV2InsertOutcome
}

/**
 * La `sessionKey` ya existe con cualquier diferencia semántica (dueño, formato, revisión,
 * fotogramas, geometría, integridad de imagen o marcas) o con contenido que no se puede comprobar.
 *
 * Se lanza **dentro** de la `@Transaction` de [AngleAnalysisV2Dao.insertAggregate], así que Room la
 * revierte entera: es la forma de decir "no guardes esto" sin dejar filas a medias ni reescribir un
 * resultado inmutable. No es un fallo de E/S ni de disco.
 */
class AngleAnalysisV2AggregateConflictException(message: String) : IllegalStateException(message)

/**
 * Resultado de [AngleAnalysisV2Dao.deleteConfirmedAggregate].
 *
 * Es un valor inmutable: no expone ninguna operación para editar filas ni para reintentar el
 * borrado por id. Cada variante dice exactamente qué pasó, sin ambigüedad entre "no existe", "no
 * coincide" y "la prueba era inválida".
 */
sealed interface AngleAnalysisV2DeleteOutcome {

    /**
     * La raíz y **todos** sus hijos coincidían exactamente con la prueba y se borraron.
     *
     * [ownedImages] es la lista completa de imágenes que el agregado poseía, en el orden de los
     * ordinales de fotograma: una entrada por fotograma, con la clave y la integridad **tal y como
     * estaban persistidas**. No se deduplica ni se filtra: el repositorio de borrado debe intentar
     * reclamar exactamente esas claves y ninguna otra. Las filas hijos se retiraron por la cascada
     * de claves foráneas de la raíz.
     */
    data class Deleted(
        val rootId: Long,
        val ownedImages: List<AngleAnalysisV2OwnedImage>,
    ) : AngleAnalysisV2DeleteOutcome

    /**
     * La raíz existe, pero el agregado guardado **no** coincide exactamente con la prueba: otra
     * escritura/restauración cambió alguna columna o el llamante leyó otro estado.
     *
     * [detail] nombra el componente y las columnas distintas, nunca valores de usuario. No se ha
     * escrito ni borrado nada.
     */
    data class Changed(val rootId: Long, val detail: String) : AngleAnalysisV2DeleteOutcome

    /** No hay ninguna raíz con [rootId]: no se ha escrito nada (repetición de un borrado ya hecho). */
    data class Missing(val rootId: Long) : AngleAnalysisV2DeleteOutcome

    /** La prueba no describe un agregado persistido válido; [detail] dice por qué. No se escribió nada. */
    data class InvalidProof(val detail: String) : AngleAnalysisV2DeleteOutcome
}

/**
 * Imagen privada que un agregado poseía al borrarse: clave opaca y la integridad declarada de su
 * fichero, copiada de la fila del fotograma.
 *
 * No es una autorización para borrar el fichero: el repositorio de borrado debe comprobar bajo el
 * mutex de medios que no queda ninguna referencia ([AngleAnalysisV2Dao.frameImageReferenceCount]) y
 * contrastar estos metadatos con el fichero real antes de retirarlo.
 */
data class AngleAnalysisV2OwnedImage(
    val imageKey: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageByteCount: Long,
    val imageSha256: String,
    val imageMimeType: String,
)

/**
 * El `DELETE` de la raíz confirmada no afectó a exactamente una fila, así que la transacción se
 * revierte y **no** se informa de un borrado que no ocurrió.
 *
 * Es un fallo interno inesperado (restricción/trigger o estado inconsistente), no un resultado
 * normal: la API tipada ya distingue "no existe" ([AngleAnalysisV2DeleteOutcome.Missing]) y "no
 * coincide" ([AngleAnalysisV2DeleteOutcome.Changed]) sin excepciones.
 */
class AngleAnalysisV2DeleteRollbackException(message: String) : IllegalStateException(message)

/**
 * Techo de fotogramas de una prueba de borrado.
 *
 * Se ancla al contrato **soportado** del formato de backup ([MEDIA_BACKUP_MAX_IMAGES]: un manifiesto
 * válido no declara más de 4096 imágenes y cada fotograma posee exactamente una) y **no** al
 * presupuesto de captura (`AngleV2SaveBudget.maxFrames`, 24 por defecto), que acota lo que la UI de
 * alta ofrece capturar y no lo que el formato persistido admite. Así ninguna raíz **aceptada** se
 * queda sin poder borrarse: un alta de producto queda muy por debajo de este techo y una restauración
 * sólo puede traer agregados que el manifiesto ya validó con esta misma cota.
 *
 * El techo es además una cota finita del trabajo de prueba: la validación recorre la prueba una vez
 * y una prueba manipulada con más fotogramas se rechaza con
 * [AngleAnalysisV2DeleteOutcome.InvalidProof] antes de leer la base, sin materializar
 * comparaciones ilimitadas.
 *
 * Límite declarado: [AngleAnalysisV2Dao.insertAggregate] no impone ningún techo de fotogramas y el
 * alta acepta un presupuesto personalizado, así que código al margen de los caminos soportados puede
 * persistir un agregado de más de 4096 fotogramas. Que ese agregado sea borrable por esta puerta
 * **no** se promete: se rechaza con un resultado tipado (nunca un borrado parcial). Si cambia el
 * contrato de backup, esta constante lo sigue; no hay un segundo número que mantener.
 */
internal val ANGLE_ANALYSIS_V2_DELETE_MAX_FRAMES: Int = MEDIA_BACKUP_MAX_IMAGES

/**
 * `null` si [proof] describe un agregado V2 persistido con forma válida; si no, una descripción
 * acotada del primer problema.
 *
 * Es pura (no toca la base ni el disco) y **fail-closed**: comprueba forma y cota de fotogramas de
 * la prueba antes de que [AngleAnalysisV2Dao.deleteConfirmedAggregate] lea nada. No demuestra que el
 * agregado exista ni que coincida con lo guardado; eso lo hace la comparación exacta dentro de la
 * transacción.
 */
internal fun describeInvalidDeletionProof(proof: AngleAnalysisV2StoredAggregate): String? {
    val root = proof.root
    if (root.id <= 0L) return "la raíz de la prueba no trae un id persistido"
    if (root.analysisFormat != ANGLE_ANALYSIS_V2_FORMAT) {
        return "la prueba declara un formato de análisis no soportado"
    }
    if (root.sessionKey.isBlank()) return "la prueba trae una sessionKey vacía"
    if (root.revision < 0) return "la prueba trae una revisión negativa"
    if (proof.frames.isEmpty()) return "una prueba de borrado necesita al menos un fotograma"
    if (proof.frames.size > ANGLE_ANALYSIS_V2_DELETE_MAX_FRAMES) {
        return "la prueba declara ${proof.frames.size} fotogramas y el máximo es " +
            "$ANGLE_ANALYSIS_V2_DELETE_MAX_FRAMES"
    }
    val frameIds = HashSet<Long>(proof.frames.size)
    val imageKeys = HashSet<String>(proof.frames.size)
    proof.frames.forEachIndexed { index, storedFrame ->
        val frame = storedFrame.frame
        if (frame.ordinal != index) return "el fotograma $index no es contiguo desde 0 (ordinal ${frame.ordinal})"
        if (frame.id <= 0L) return "el fotograma $index no trae id persistido"
        if (!frameIds.add(frame.id)) return "el fotograma $index repite un id"
        if (frame.rootId != root.id) return "el fotograma $index no pertenece a la raíz de la prueba"
        if (!AngleImageKey.isCanonical(frame.imageKey)) {
            return "la imageKey del fotograma $index no tiene la forma canónica de 32 hex"
        }
        if (!imageKeys.add(frame.imageKey)) return "la imageKey del fotograma $index está repetida"
        if (frame.assignedFrameIndex < 0) return "assignedFrameIndex negativo en el fotograma $index"
        if (frame.assignedPtsUs < 0L) return "assignedPtsUs negativo en el fotograma $index"
        if ((frame.reportedFrameIndex ?: 0) < 0) return "reportedFrameIndex negativo en el fotograma $index"
        if ((frame.reportedPtsUs ?: 0L) < 0L) return "reportedPtsUs negativo en el fotograma $index"
        if (frame.encodedWidth < 1 || frame.encodedHeight < 1) {
            return "dimensiones codificadas no positivas en el fotograma $index"
        }
        if (frame.presentationWidth < 1 || frame.presentationHeight < 1) {
            return "dimensiones de presentación no positivas en el fotograma $index"
        }
        if (frame.rotationDegrees !in 0..359) return "rotationDegrees fuera de 0..359 en el fotograma $index"
        if (frame.geometryVersion < 1) return "geometryVersion debe ser >= 1 en el fotograma $index"
        if (frame.imageWidth < 1 || frame.imageHeight < 1) {
            return "dimensiones de imagen no positivas en el fotograma $index"
        }
        if (frame.imageByteCount < 1L) return "imageByteCount debe ser positivo en el fotograma $index"
        if (!SHA256_HEX.matches(frame.imageSha256)) {
            return "imageSha256 no es hexadecimal de 64 caracteres en el fotograma $index"
        }
        if (frame.imageMimeType.isBlank()) return "imageMimeType vacío en el fotograma $index"
        val markIds = HashSet<Long>(storedFrame.marks.size)
        storedFrame.marks.forEachIndexed { markIndex, mark ->
            if (mark.ordinal != markIndex) {
                return "la marca $markIndex del fotograma $index no es contigua desde 0"
            }
            if (mark.id <= 0L) return "la marca $markIndex del fotograma $index no trae id persistido"
            if (!markIds.add(mark.id)) return "la marca $markIndex del fotograma $index repite un id"
            if (mark.frameId != frame.id) {
                return "la marca $markIndex del fotograma $index no pertenece a ese fotograma"
            }
            if (mark.name.isBlank()) return "nombre de marca vacío en la marca $markIndex del fotograma $index"
            if (!(
                    mark.pointAX.isFinite() && mark.pointAY.isFinite() &&
                        mark.pointBX.isFinite() && mark.pointBY.isFinite() &&
                        mark.pointCX.isFinite() && mark.pointCY.isFinite()
                    )
            ) {
                return "coordenadas no finitas en la marca $markIndex del fotograma $index"
            }
        }
    }
    return null
}

/**
 * `null` si [stored] y [proof] son **exactamente** iguales fila a fila; si no, una descripción
 * acotada de la primera diferencia.
 *
 * A diferencia de [describeAggregateDifference] (huella semántica del alta), aquí se comparan las
 * entidades completas: `id`, `rootId`/`frameId`, `imageKey` y `createdAt`/`updatedAt` también
 * cuentan, porque identifican las filas que se van a borrar y los ficheros que se van a devolver.
 * Si la diferencia no está en una columna semántica, el mensaje lo dice: no se oculta que el
 * cambio es de identidad.
 */
internal fun describeExactAggregateDifference(
    stored: AngleAnalysisV2StoredAggregate,
    proof: AngleAnalysisV2StoredAggregate,
): String? {
    if (stored.root != proof.root) {
        return describeRootDifference(stored.root.toSemantics(), proof.root.toSemantics())
            ?: "la raíz guardada difiere de la prueba en id o en createdAt/updatedAt"
    }
    if (stored.frames.size != proof.frames.size) {
        return "${stored.frames.size} fotogramas guardados frente a ${proof.frames.size} en la prueba"
    }
    stored.frames.forEachIndexed { index, storedFrame ->
        val proofFrame = proof.frames[index]
        if (storedFrame.frame != proofFrame.frame) {
            val columns = differingFrameColumns(
                stored = storedFrame.frame.toSemantics(emptyList()),
                expected = proofFrame.frame.toSemantics(emptyList()),
            )
            return if (columns.isEmpty()) {
                "el fotograma $index difiere de la prueba en identidad (id/rootId) o en su imageKey"
            } else {
                "el fotograma $index difiere de la prueba en " +
                    columns.joinToString(limit = MAX_REPORTED_COLUMNS)
            }
        }
        val markDifference = describeMarkDifference(
            frameIndex = index,
            stored = storedFrame.marks.map { mark -> mark.toSemantics() },
            expected = proofFrame.marks.map { mark -> mark.toSemantics() },
        )
        if (markDifference != null) return markDifference
        if (storedFrame.marks != proofFrame.marks) {
            return "las marcas del fotograma $index difieren de la prueba en identidad (id/frameId)"
        }
    }
    return null
}

/**
 * Instantánea de un agregado guardado: la raíz y sus fotogramas con sus marcas tal y como se leyeron
 * dentro de una única transacción (ver [AngleAnalysisV2Dao.aggregateBySessionKey]).
 */
data class AngleAnalysisV2StoredAggregate(
    val root: AngleAnalysisV2RootEntity,
    val frames: List<AngleAnalysisV2StoredFrame>,
)

/** Fotograma guardado con sus marcas, sin proyección: conserva todas las columnas persistidas. */
data class AngleAnalysisV2StoredFrame(
    val frame: AngleAnalysisV2FrameEntity,
    val marks: List<AngleAnalysisV2MarkEntity>,
)

/**
 * Columnas semánticas de la raíz que participan en la comparación de idempotencia.
 *
 * `createdAt`/`updatedAt` quedan fuera **a propósito**: los deriva el intento de guardado con su
 * reloj (`AngleV2SaveRepository.clock`), no el usuario. Un reintento legítimo del mismo análisis
 * produce instantes distintos, así que compararlos convertiría toda repetición en un falso
 * conflicto; y no son contenido: no describen qué se guardó, sólo cuándo se intentó. El `id` también
 * queda fuera porque lo autogenera Room.
 *
 * `revision` sí se compara: un contador distinto significa que la raíz guardada ya no es el estado
 * que este intento describe (aunque hoy ningún camino lo modifique todavía).
 */
data class AngleAnalysisV2RootSemantics(
    val analysisFormat: String,
    val sessionKey: String,
    val athleteId: Long?,
    val revision: Int,
)

/** Proyección de las columnas semánticas de una raíz persistida; excluye `id` y los timestamps. */
fun AngleAnalysisV2RootEntity.toSemantics(): AngleAnalysisV2RootSemantics =
    AngleAnalysisV2RootSemantics(
        analysisFormat = analysisFormat,
        sessionKey = sessionKey,
        athleteId = athleteId,
        revision = revision,
    )

/**
 * Columnas semánticas de una marca: **todas** menos `id` y `frameId`, que autogenera la base al
 * insertar el fotograma. Las coordenadas son píxeles de presentación del fotograma padre.
 */
data class AngleAnalysisV2MarkSemantics(
    val ordinal: Int,
    val name: String,
    val colorArgb: Int,
    val visible: Boolean,
    val pointAX: Double,
    val pointAY: Double,
    val pointBX: Double,
    val pointBY: Double,
    val pointCX: Double,
    val pointCY: Double,
)

/** Proyección de las columnas semánticas de una marca persistida (excluye `id` y `frameId`). */
fun AngleAnalysisV2MarkEntity.toSemantics(): AngleAnalysisV2MarkSemantics =
    AngleAnalysisV2MarkSemantics(
        ordinal = ordinal,
        name = name,
        colorArgb = colorArgb,
        visible = visible,
        pointAX = pointAX,
        pointAY = pointAY,
        pointBX = pointBX,
        pointBY = pointBY,
        pointCX = pointCX,
        pointCY = pointCY,
    )

/**
 * Columnas semánticas de un fotograma: **todas** las persistidas salvo `id`, `rootId` y `imageKey`,
 * que no son contenido (los dos primeros los autogenera Room; la clave de imagen es nueva en cada
 * intento aunque los bytes sean los mismos).
 *
 * La integridad de imagen se compara por contenido declarado (`imageWidth`/`imageHeight`,
 * `imageByteCount`, `imageSha256`, `imageMimeType`), que es lo que permite afirmar "el mismo PNG"
 * sin abrir el almacén.
 */
data class AngleAnalysisV2FrameSemantics(
    val ordinal: Int,
    val assignedFrameIndex: Int,
    val reportedFrameIndex: Int?,
    val assignedPtsUs: Long,
    val reportedPtsUs: Long?,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    val geometryVersion: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageByteCount: Long,
    val imageSha256: String,
    val imageMimeType: String,
    val marks: List<AngleAnalysisV2MarkSemantics>,
)

/** Proyección de un fotograma persistido con sus marcas (el SHA se normaliza a minúsculas). */
fun AngleAnalysisV2FrameEntity.toSemantics(marks: List<AngleAnalysisV2MarkEntity>): AngleAnalysisV2FrameSemantics =
    AngleAnalysisV2FrameSemantics(
        ordinal = ordinal,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = reportedFrameIndex,
        assignedPtsUs = assignedPtsUs,
        reportedPtsUs = reportedPtsUs,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        presentationWidth = presentationWidth,
        presentationHeight = presentationHeight,
        rotationDegrees = rotationDegrees,
        geometryVersion = geometryVersion,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        imageByteCount = imageByteCount,
        imageSha256 = imageSha256.lowercase(),
        imageMimeType = imageMimeType,
        marks = marks.map { mark -> mark.toSemantics() },
    )

/** Fotograma guardado con sus marcas → columnas semánticas comparables. */
fun AngleAnalysisV2StoredFrame.toSemantics(): AngleAnalysisV2FrameSemantics = frame.toSemantics(marks)

/** Fotograma de un alta (sin `id` ni `frameId`) → las mismas columnas semánticas que se persistirán. */
fun AngleAnalysisV2FrameDraft.toSemantics(): AngleAnalysisV2FrameSemantics = frame.toSemantics(marks)

/**
 * `null` si las columnas semánticas de raíz de [stored] y [expected] coinciden; si no, una
 * descripción acotada que sólo nombra las columnas distintas (nunca valores de usuario).
 */
fun describeRootDifference(
    stored: AngleAnalysisV2RootSemantics,
    expected: AngleAnalysisV2RootSemantics,
): String? {
    val columns = ArrayList<String>(4)
    if (stored.analysisFormat != expected.analysisFormat) columns += "analysisFormat"
    if (stored.sessionKey != expected.sessionKey) columns += "sessionKey"
    if (stored.athleteId != expected.athleteId) columns += "athleteId"
    if (stored.revision != expected.revision) columns += "revision"
    return if (columns.isEmpty()) null else "la raíz guardada difiere en ${columns.joinToString()}"
}

/**
 * Compara el contenido semántico completo del agregado (fotogramas + marcas).
 *
 * `null` significa **idéntico en todas las columnas comparadas**; cualquier diferencia —número de
 * fotogramas, ordinal, instantes `assigned`/`reported`, geometría, integridad de imagen, nombre,
 * color, visibilidad o cualquiera de las seis coordenadas de una marca— devuelve una descripción
 * acotada con el índice y los nombres de columna, nunca con los valores.
 *
 * Es deliberadamente **estricta y fail-closed**: ante la duda se prefiere el conflicto (rollback)
 * antes que dar por guardado algo distinto. Las coordenadas se comparan con igualdad IEEE de
 * `Double`, de modo que `-0.0` y `0.0` son el mismo punto (no un cambio de contenido); `NaN` no
 * puede llegar aquí porque la validación exige coordenadas finitas.
 */
fun describeAggregateDifference(
    stored: List<AngleAnalysisV2FrameSemantics>,
    expected: List<AngleAnalysisV2FrameSemantics>,
): String? {
    if (stored.size != expected.size) {
        return "${stored.size} fotogramas guardados frente a ${expected.size} fotogramas nuevos"
    }
    expected.forEachIndexed { index, expectedFrame ->
        val storedFrame = stored[index]
        val frameColumns = differingFrameColumns(stored = storedFrame, expected = expectedFrame)
        if (frameColumns.isNotEmpty()) {
            return "el fotograma $index difiere en ${frameColumns.joinToString(limit = MAX_REPORTED_COLUMNS)}"
        }
        val markDifference = describeMarkDifference(
            frameIndex = index,
            stored = storedFrame.marks,
            expected = expectedFrame.marks,
        )
        if (markDifference != null) return markDifference
    }
    return null
}

/** Columnas de fotograma que difieren entre [stored] y [expected] (vacío si coinciden todas). */
private fun differingFrameColumns(
    stored: AngleAnalysisV2FrameSemantics,
    expected: AngleAnalysisV2FrameSemantics,
): List<String> {
    val columns = ArrayList<String>(FRAME_COLUMN_COUNT)
    if (stored.ordinal != expected.ordinal) columns += "ordinal"
    if (stored.assignedFrameIndex != expected.assignedFrameIndex) columns += "assignedFrameIndex"
    if (stored.reportedFrameIndex != expected.reportedFrameIndex) columns += "reportedFrameIndex"
    if (stored.assignedPtsUs != expected.assignedPtsUs) columns += "assignedPtsUs"
    if (stored.reportedPtsUs != expected.reportedPtsUs) columns += "reportedPtsUs"
    if (stored.encodedWidth != expected.encodedWidth) columns += "encodedWidth"
    if (stored.encodedHeight != expected.encodedHeight) columns += "encodedHeight"
    if (stored.presentationWidth != expected.presentationWidth) columns += "presentationWidth"
    if (stored.presentationHeight != expected.presentationHeight) columns += "presentationHeight"
    if (stored.rotationDegrees != expected.rotationDegrees) columns += "rotationDegrees"
    if (stored.geometryVersion != expected.geometryVersion) columns += "geometryVersion"
    if (stored.imageWidth != expected.imageWidth) columns += "imageWidth"
    if (stored.imageHeight != expected.imageHeight) columns += "imageHeight"
    if (stored.imageByteCount != expected.imageByteCount) columns += "imageByteCount"
    if (!stored.imageSha256.equals(expected.imageSha256, ignoreCase = true)) columns += "imageSha256"
    if (!stored.imageMimeType.equals(expected.imageMimeType, ignoreCase = true)) columns += "imageMimeType"
    return columns
}

/** Diferencias de marcas del fotograma [frameIndex] (mismo número y mismas columnas, en orden). */
private fun describeMarkDifference(
    frameIndex: Int,
    stored: List<AngleAnalysisV2MarkSemantics>,
    expected: List<AngleAnalysisV2MarkSemantics>,
): String? {
    if (stored.size != expected.size) {
        return "el fotograma $frameIndex tiene ${stored.size} marcas guardadas frente a ${expected.size} nuevas"
    }
    expected.forEachIndexed { markIndex, expectedMark ->
        val columns = differingMarkColumns(stored = stored[markIndex], expected = expectedMark)
        if (columns.isNotEmpty()) {
            return "la marca $markIndex del fotograma $frameIndex difiere en " +
                columns.joinToString(limit = MAX_REPORTED_COLUMNS)
        }
    }
    return null
}

/** Columnas de marca que difieren entre [stored] y [expected] (vacío si coinciden todas). */
private fun differingMarkColumns(
    stored: AngleAnalysisV2MarkSemantics,
    expected: AngleAnalysisV2MarkSemantics,
): List<String> {
    val columns = ArrayList<String>(MARK_COLUMN_COUNT)
    if (stored.ordinal != expected.ordinal) columns += "ordinal"
    if (stored.name != expected.name) columns += "name"
    if (stored.colorArgb != expected.colorArgb) columns += "colorArgb"
    if (stored.visible != expected.visible) columns += "visible"
    if (stored.pointAX != expected.pointAX) columns += "pointAX"
    if (stored.pointAY != expected.pointAY) columns += "pointAY"
    if (stored.pointBX != expected.pointBX) columns += "pointBX"
    if (stored.pointBY != expected.pointBY) columns += "pointBY"
    if (stored.pointCX != expected.pointCX) columns += "pointCX"
    if (stored.pointCY != expected.pointCY) columns += "pointCY"
    return columns
}

/** Identidad acotada de una raíz para mensajes de conflicto (sin PII: ni URI ni nombres). */
private fun AngleAnalysisV2RootEntity.describeSession(): String = "id=$id, athleteId=$athleteId"

/** Columnas semánticas de fotograma comparadas; sólo acota la capacidad de la lista de diferencias. */
private const val FRAME_COLUMN_COUNT = 16

/** Columnas semánticas de marca comparadas; sólo acota la capacidad de la lista de diferencias. */
private const val MARK_COLUMN_COUNT = 10

/** Máximo de nombres de columna citados por fotograma/marca en un mensaje de conflicto. */
private const val MAX_REPORTED_COLUMNS = 4

private val SHA256_HEX: Regex = Regex("^[0-9a-fA-F]{64}$")
