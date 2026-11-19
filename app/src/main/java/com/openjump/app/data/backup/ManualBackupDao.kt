package com.openjump.app.data.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.openjump.app.data.*
import java.security.MessageDigest

/**
 * Acceso de backup manual a las tablas legacy.
 *
 * **Barrera TRANSITORIA del agregado V2 (Room 14).** El formato v1
 * ([MANUAL_BACKUP_FORMAT_VERSION]) no modela `angle_analysis_v2_roots/frames/marks`
 * ([MANUAL_BACKUP_V2_INTRODUCED_ROOM_VERSION]), asi que `snapshot()` produciria un backup
 * incompleto y `replaceAll()` borraria filas V2 sin poder devolverlas (tambien las raices con
 * `athleteId` NULL, que no cuelgan de ningun atleta y se perderian sin aviso). Para no abrir esa
 * via de perdida de datos, AMBOS metodos comprueban **dentro de su propia `@Transaction`** que las
 * tres tablas V2 esten vacias antes de capturar o de borrar nada; si tienen datos lanzan
 * [ManualBackupV2BlockedException] y no tocan una sola fila. Con V2 vacio, las operaciones legacy
 * siguen funcionando exactamente igual.
 *
 * Esto es una medida TEMPORAL y fail-closed: NO es el backup con imagenes que se requiere, y no se
 * sustituye relajando el gate, sino escribiendo ese formato y su coordinador. Los escritores de V2
 * siguen sin existir ([com.openjump.app.data.AngleAnalysisV2Dao] es de solo lectura), y este DAO no
 * gana ningun `INSERT`/`DELETE` sobre V2.
 *
 * **Camino de metadatos con imagenes (G3a).** [snapshotWithAngleMedia] es la UNICA captura que
 * incluye el agregado V2, y solo devuelve **metadatos** en un [MediaBackupManifest]; no lee ficheros
 * de imagen, no escribe contenedores y no reabre ninguna via de restauracion. No sustituye la
 * barrera v1: [snapshot] y [replaceAll] siguen bloqueados mientras haya filas V2. La captura legacy
 * compartida vive en un helper `private` ([captureLegacyDocument]) para que no exista una ruta
 * publica que devuelva el documento v1 saltandose el gate.
 *
 * **Restauracion con imagenes (G3b).** [replaceAllWithAngleMedia] es la contrapartida de
 * [snapshotWithAngleMedia]: recibe un [MediaBackupManifest] **completo**, valida otra vez dentro de
 * la transaccion y sustituye legacy + V2 en **una sola** `@Transaction` de Room, con ids exactos.
 * Sigue sin existir un escritor de producto sobre V2
 * ([com.openjump.app.data.AngleAnalysisV2Dao] es de solo lectura); las unicas escrituras V2 son las
 * de este restore. El metodo **no** valida ni publica ficheros de imagen y no promete atomicidad
 * Room+sistema de ficheros: ver su KDoc.
 *
 * **Segunda confirmacion del agregado V2, para CUALQUIER formato.** Restaurar un backup —JSON v1 o
 * contenedor ZIP, con o sin analisis dentro— sustituye el agregado V2 que haya ahora en la base.
 * Por eso [replaceAllWithAngleMedia] recibe la [AngleAnalysisV2AggregateSnapshot] que el usuario vio
 * en la advertencia (`null` significa "no habia ningun agregado que perder"), la **recalcula dentro
 * de la misma transaccion y antes del primer `DELETE`** y falla cerrado si no coincide: con
 * instantanea, si el agregado cambio ([ManualBackupAngleV2ChangedException]); sin ella, si el
 * agregado actual **no** esta vacio ([ManualBackupV2BlockedException], vacio atomico). No hay
 * ninguna ruta en este DAO que borre filas V2 sin esa comprobacion previa.
 *
 * **Restaurar JSON legacy descartando el V2 actual (descartar confirmado).**
 * [replaceAllDiscardingAngleV2] es la unica ruta que restaura un documento v1 **teniendo filas V2**,
 * y no es una relajacion generica de la barrera: exige la [AngleAnalysisV2AggregateSnapshot]
 * **completa** que el usuario acaba de ver y confirmar —identidad y contenido semantico de TODAS las
 * filas V2, no solo sus conteos—, recalcula esa instantanea dentro de su propia `@Transaction` antes
 * del primer `DELETE` y falla cerrado ([ManualBackupAngleV2ChangedException]) si el agregado cambio
 * entre la advertencia y la aplicacion, **tambien** cuando el contenido es otro y los conteos
 * coinciden. Se apoya en el mismo cuerpo transaccional que [replaceAllWithAngleMedia] con un
 * `angleV2` vacio (ver [replaceValidatedWithAngleMedia]), no borra ni publica ficheros de imagen y no
 * puede alcanzarse con un conteo vacio. [snapshot] y [replaceAll] siguen lanzando
 * [ManualBackupV2BlockedException] mientras haya filas V2: esta ruta no los toca.
 */
@Dao
interface ManualBackupDao {
    @Query("SELECT * FROM athletes ORDER BY id") suspend fun athletes(): List<BackupAthlete>
    @Query("SELECT * FROM athlete_groups ORDER BY id") suspend fun groups(): List<BackupGroup>
    @Query("SELECT * FROM athlete_group_cross_ref ORDER BY groupId, athleteId") suspend fun memberships(): List<BackupMembership>
    @Query("SELECT * FROM testing_sessions ORDER BY id") suspend fun testingSessions(): List<BackupTestingSession>
    @Query("SELECT * FROM testing_participants ORDER BY sessionId, ordinal, athleteId") suspend fun testingParticipants(): List<BackupTestingParticipant>
    @Query("SELECT * FROM assessments ORDER BY id") suspend fun assessments(): List<BackupAssessment>
    /** Explicit projection intentionally omits the private video URI. */
    @Query("SELECT id, assessmentId, ordinal, side, source, detectedFps, dropHeightCm, distanceCm FROM attempts ORDER BY assessmentId, ordinal, id") suspend fun attempts(): List<BackupAttempt>
    @Query("SELECT attemptId, type, ordinal, ptsUs, frameIndex, previousPtsUs, nextPtsUs FROM attempt_events ORDER BY attemptId, type, ordinal") suspend fun attemptEvents(): List<BackupAttemptEvent>
    @Query("SELECT * FROM attempt_metrics ORDER BY attemptId, ordinal, key") suspend fun attemptMetrics(): List<BackupAttemptMetric>
    @Query("SELECT * FROM attempt_calibrations ORDER BY attemptId") suspend fun attemptCalibrations(): List<BackupAttemptCalibration>
    @Query("SELECT * FROM attempt_spatial_marks ORDER BY attemptId, type") suspend fun attemptSpatialMarks(): List<BackupAttemptSpatialMark>
    /** Explicit projection intentionally omits the private video URI. */
    @Query("SELECT id, sessionKey, athleteId, testingSessionId, exercise, loadKg, dateTime, source, observedFps, nominalFps, captureFps, timingMode, slowMotionFactor, calibrationFrameIndex, calibrationAX, calibrationAY, calibrationBX, calibrationBY, referenceLengthM, metersPerPixel, analysisVersion, minimumVelocityMps, noiseMultiplier, exitRatio, minimumDirectionHoldUs, stationaryDurationUs, pauseDurationUs, minimumPhaseDurationUs, minimumPhaseDisplacementM, romOutlierFraction, minimumMedianConfidence, maximumUncertainFraction, smoothingWindowSamples, velocityEnterMps, velocityExitMps, totalRepetitions, validRepetitions, bestMcv, setVelocityLoss, notes FROM encoder_sessions ORDER BY id") suspend fun encoderSessions(): List<BackupEncoderSession>
    @Query("SELECT * FROM encoder_samples ORDER BY sessionId, ordinal") suspend fun encoderSamples(): List<BackupEncoderSample>
    @Query("SELECT * FROM encoder_repetitions ORDER BY sessionId, ordinal, id") suspend fun encoderRepetitions(): List<BackupEncoderRepetition>
    @Query("SELECT * FROM encoder_rep_metrics ORDER BY repetitionId, key") suspend fun encoderRepMetrics(): List<BackupEncoderRepMetric>
    @Query("SELECT * FROM fvp_assessments ORDER BY id") suspend fun fvpAssessments(): List<BackupFvpAssessment>
    @Query("SELECT * FROM fvp_conditions ORDER BY assessmentId, ordinal") suspend fun fvpConditions(): List<BackupFvpCondition>
    @Query("SELECT * FROM fvp_attempts ORDER BY conditionId, ordinal") suspend fun fvpAttempts(): List<BackupFvpAttempt>
    /** Explicit projection intentionally omits the private video URI/source identity/source hash. */
    @Query("SELECT id, sessionKey, athleteId, createdAt, updatedAt, revision, encodedWidth, encodedHeight, presentationWidth, presentationHeight, rotationDegrees, frameIndex, sourcePtsUs, geometryVersion FROM angle_analyses ORDER BY id") suspend fun angleAnalyses(): List<BackupAngleAnalysis>
    @Query("SELECT id, analysisId, ordinal, name, colorArgb, visible, pointAX, pointAY, pointBX, pointBY, pointCX, pointCY FROM angle_marks ORDER BY analysisId, ordinal, id") suspend fun angleMarks(): List<BackupAngleMark>

    /**
     * Captura de las tablas legacy. Rechaza (fail-closed) si hay datos en el agregado V2: el backup
     * v1 seria incompleto. La comprobacion va DENTRO de la transaccion y antes de leer/capturar
     * nada; no muta la base.
     */
    @Transaction
    suspend fun snapshot(): ManualBackupDocument {
        requireNoAngleV2Rows()
        return captureLegacyDocument()
    }

    /**
     * Captura transaccional para el **futuro repositorio de backup con imagenes** (interno): el mismo
     * payload legacy que [snapshot] capturaria, mas TODAS las filas de
     * `angle_analysis_v2_roots/frames/marks` proyectadas a [MediaBackupManifest] con una entrada de
     * imagen por fotograma (solo metadatos: clave opaca, tamano y SHA-256 de la fila).
     *
     * Que es y que **no** es:
     *  - **No** es un archivo de backup completo. Las imagenes no se leen aqui: Room y el almacen de
     *    ficheros no comparten transaccion, asi que este metodo solo garantiza una foto consistente
     *    de las **filas**. El escritor que consuma el manifiesto debe abrir cada fichero, comprobar
     *    `imageByteCount`/`imageSha256` y fallar cerrado si no coinciden.
     *  - **No** se coordina con mutaciones/GC del almacen de imagenes ni con escritores de V2: una
     *    imagen borrada o sustituida durante la captura deja una entrada declarada sin fichero (o
     *    con otro contenido), y eso lo detectan la verificacion de bytes del escritor y, en el
     *    futuro, la restauracion. Aqui no hay ninguna promesa de atomicidad con el sistema de
     *    ficheros.
     *  - **No** sustituye ni relaja la barrera v1: [snapshot] y [replaceAll] siguen lanzando
     *    [ManualBackupV2BlockedException] mientras exista cualquier fila V2, y la exportacion actual
     *    ([com.openjump.app.data.export.DataExportRepository]) sigue sin incluir medios. Esta es la
     *    unica captura que transporta V2 y siempre devuelve el agregado completo: nunca devuelve
     *    solo el documento v1.
     *
     * Todo ocurre en **una sola** `@Transaction` de Room (legacy + V2 + validacion), de modo que un
     * escritor concurrente no puede producir un manifiesto con el legacy de un instante y el V2 de
     * otro. Fail-closed: el manifiesto se valida con [MediaBackupManifest.validate] dentro de la
     * transaccion, asi que un agregado huerfano o incompleto (raiz sin fotogramas, fotograma sin
     * marcas o sin imagen declarada, dueno inexistente, SHA no canonico, clave duplicada...) lanza
     * [IllegalArgumentException] y no se devuelve un manifiesto parcial. No muta la base.
     */
    @Transaction
    suspend fun snapshotWithAngleMedia(): MediaBackupManifest {
        // A proposito NO se llama a requireNoAngleV2Rows(): este payload SI transporta V2. El helper
        // legacy es privado para que ninguna ruta publica pueda saltarse ese gate devolviendo v1.
        val legacy = captureLegacyDocument()
        val angleV2 = mediaBackupAngleV2Snapshot(
            roots = angleV2Roots(),
            frames = angleV2Frames(),
            marks = angleV2Marks(),
        )
        return MediaBackupManifest(
            contract = MEDIA_BACKUP_CONTRACT,
            formatVersion = MEDIA_BACKUP_FORMAT_VERSION,
            sourceRoomSchemaVersion = MEDIA_BACKUP_ROOM_VERSION,
            // Un unico instante para envoltorio y payload: dos lecturas del reloj no aportarian nada
            // y permitirian que un lector viera un manifiesto "anterior" a su propio documento.
            createdAtEpochMillis = legacy.createdAtEpochMillis,
            legacy = legacy,
            angleV2 = angleV2,
        ).validate()
    }

    /**
     * Constructor del documento legacy, **sin** la barrera v1. Es `private` a proposito: solo lo
     * llaman [snapshot] (que comprueba antes que V2 esta vacio) y [snapshotWithAngleMedia] (que
     * transporta V2 y por eso no aplica ese gate). Debe invocarse SIEMPRE dentro de una
     * `@Transaction` para que todas las tablas se lean del mismo instante.
     */
    private suspend fun captureLegacyDocument(): ManualBackupDocument = ManualBackupDocument(
        contract = MANUAL_BACKUP_CONTRACT,
        formatVersion = MANUAL_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MANUAL_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = System.currentTimeMillis(),
        mediaIncluded = false,
        preferencesIncluded = false,
        tables = ManualBackupTables(
            athletes = athletes(),
            athleteGroups = groups(),
            athleteGroupCrossRef = memberships(),
            testingSessions = testingSessions(),
            testingParticipants = testingParticipants(),
            assessments = assessments(),
            attempts = attempts(),
            attemptEvents = attemptEvents(),
            attemptMetrics = attemptMetrics(),
            attemptCalibrations = attemptCalibrations(),
            attemptSpatialMarks = attemptSpatialMarks(),
            encoderSessions = encoderSessions(),
            encoderSamples = encoderSamples(),
            encoderRepetitions = encoderRepetitions(),
            encoderRepMetrics = encoderRepMetrics(),
            fvpAssessments = fvpAssessments(),
            fvpConditions = fvpConditions(),
            fvpAttempts = fvpAttempts(),
            angleAnalyses = angleAnalyses(),
            angleMarks = angleMarks(),
        ),
    )

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAthletes(rows: List<AthleteEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGroups(rows: List<GroupEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMemberships(rows: List<AthleteGroupCrossRef>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTestingSessions(rows: List<TestingSessionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTestingParticipants(rows: List<TestingParticipantEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAssessments(rows: List<AssessmentEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttempts(rows: List<AttemptEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttemptEvents(rows: List<AttemptEventEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttemptMetrics(rows: List<AttemptMetricEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttemptCalibrations(rows: List<AttemptCalibrationEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttemptSpatialMarks(rows: List<AttemptSpatialMarkEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEncoderSessions(rows: List<EncoderSessionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEncoderSamples(rows: List<EncoderSampleEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEncoderRepetitions(rows: List<EncoderRepetitionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEncoderRepMetrics(rows: List<EncoderRepMetricEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFvpAssessments(rows: List<FvpAssessmentEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFvpConditions(rows: List<FvpConditionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFvpAttempts(rows: List<FvpAttemptEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAngleAnalyses(rows: List<AngleAnalysisEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAngleMarks(rows: List<AngleMarkEntity>)

    @Query("DELETE FROM assessments") suspend fun clearAssessments()
    @Query("DELETE FROM encoder_sessions") suspend fun clearEncoderSessions()
    @Query("DELETE FROM testing_sessions") suspend fun clearTestingSessions()
    @Query("DELETE FROM athlete_group_cross_ref") suspend fun clearMemberships()
    @Query("DELETE FROM athlete_groups") suspend fun clearGroups()
    @Query("DELETE FROM fvp_attempts") suspend fun clearFvpAttempts()
    @Query("DELETE FROM fvp_conditions") suspend fun clearFvpConditions()
    @Query("DELETE FROM fvp_assessments") suspend fun clearFvpAssessments()
    @Query("DELETE FROM angle_marks") suspend fun clearAngleMarks()
    @Query("DELETE FROM angle_analyses") suspend fun clearAngleAnalyses()
    @Query("DELETE FROM athletes") suspend fun clearAthletes()

    @RawQuery
    suspend fun foreignKeyCheck(query: SupportSQLiteQuery): List<ForeignKeyViolation>

    /**
     * Conteo de filas V2 **sin** barrera, en una sola transaccion de lectura para que los tres
     * numeros sean del mismo instante. Es de solo lectura: no captura filas, no valida el agregado y
     * no muta nada. Es **solo diagnostico**: un conteo no identifica el agregado y por eso ya no
     * autoriza un descarte (para eso esta [angleV2AggregateSnapshot]).
     */
    @Transaction
    suspend fun angleV2RowCounts(): AngleAnalysisV2RowCounts = observeAngleV2Counts()

    /**
     * Instantanea **semantica completa** del agregado V2, capturada en **una sola** transaccion de
     * lectura: los tres conteos y el contenido de todas las filas (identidad y todas las columnas)
     * son del mismo instante.
     *
     * Es la unica entrada valida para la segunda confirmacion de un restore legacy: identifica el
     * agregado, no solo cuanto ocupa. Dos estados con el mismo numero de raices/fotogramas/marcas
     * pero contenido distinto (otro restore, un guardado que cambia geometria o nombres, una imagen
     * sustituida, fechas distintas) producen instantaneas distintas.
     *
     * Es de solo lectura: no valida el agregado, no captura ficheros de imagen y no muta nada. El
     * objeto devuelto no se puede fabricar fuera de este modulo y solo expone conteos y una huella
     * criptografica (ver [AngleAnalysisV2AggregateSnapshot]).
     */
    @Transaction
    suspend fun angleV2AggregateSnapshot(): AngleAnalysisV2AggregateSnapshot = observeAngleV2Aggregate()

    /**
     * Lectura de los tres conteos V2, **sin** abrir transaccion propia: la usa la lectura publica
     * ([angleV2RowCounts], dentro de su `@Transaction`).
     */
    private suspend fun observeAngleV2Counts(): AngleAnalysisV2RowCounts =
        AngleAnalysisV2RowCounts(angleV2RootCount(), angleV2FrameCount(), angleV2MarkCount())

    /**
     * Lectura + huella del agregado V2, **sin** abrir transaccion propia: la usa la lectura publica
     * ([angleV2AggregateSnapshot], dentro de su `@Transaction`) y [replaceAllDiscardingAngleV2], que
     * debe recalcular el estado **dentro de la misma** transaccion que despues borra (sin
     * transacciones anidadas). Usa las lecturas de orden estable del agregado, y la huella vuelve a
     * ordenar por clave estable, asi que no depende del plan de consulta.
     */
    private suspend fun observeAngleV2Aggregate(): AngleAnalysisV2AggregateSnapshot =
        AngleAnalysisV2AggregateSnapshot.of(
            roots = angleV2Roots(),
            frames = angleV2Frames(),
            marks = angleV2Marks(),
        )

    @Transaction
    suspend fun replaceAll(snapshot: ManualBackupDocument) {
        val document = snapshot.validate()
        // Barrera TRANSITORIA dentro de la MISMA transaccion que borra: si V2 tiene datos se lanza
        // antes del primer DELETE y no se toca ni una fila (tampoco la raiz V2 sin atleta).
        requireNoAngleV2Rows()
        replaceLegacyTables(document.tables)
    }

    /**
     * Restauracion transaccional de las tablas legacy **sin** la barrera v1. Es `private` a
     * proposito, igual que [captureLegacyDocument]: solo la llaman [replaceAll] (que comprueba antes
     * que V2 esta vacio) y las dos restauraciones con agregado V2 ([replaceAllWithAngleMedia] y
     * [replaceAllDiscardingAngleV2], via [replaceValidatedWithAngleMedia]), que sustituyen el
     * agregado V2 en la misma transaccion y por eso no aplican ese gate. El orden de borrado e
     * insercion y las comprobaciones finales son exactamente los del restore v1: pasar por este
     * helper no cambia su comportamiento.
     */
    private suspend fun replaceLegacyTables(t: ManualBackupTables) {
        // Las marcas cuelgan de los analisis y los analisis de `athletes` con NO_ACTION: se vacian
        // los hijos antes que los padres.
        clearAngleMarks()
        clearAngleAnalyses()
        // El recolector F-V-P cuelga de `athletes` con NO_ACTION: hay que vaciarlo antes del atleta.
        clearFvpAttempts()
        clearFvpConditions()
        clearFvpAssessments()
        clearAssessments()
        clearEncoderSessions()
        clearTestingSessions()
        clearMemberships()
        clearGroups()
        clearAthletes()
        insertAthletes(t.athletes.map { it.entity() })
        insertGroups(t.athleteGroups.map { it.entity() })
        insertMemberships(t.athleteGroupCrossRef.map { it.entity() })
        insertTestingSessions(t.testingSessions.map { it.entity() })
        insertTestingParticipants(t.testingParticipants.map { it.entity() })
        insertAssessments(t.assessments.map { it.entity() })
        insertAttempts(t.attempts.map { it.entity() })
        insertAttemptEvents(t.attemptEvents.map { it.entity() })
        insertAttemptMetrics(t.attemptMetrics.map { it.entity() })
        insertAttemptCalibrations(t.attemptCalibrations.map { it.entity() })
        insertAttemptSpatialMarks(t.attemptSpatialMarks.map { it.entity() })
        insertEncoderSessions(t.encoderSessions.map { it.entity() })
        insertEncoderSamples(t.encoderSamples.map { it.entity() })
        insertEncoderRepetitions(t.encoderRepetitions.map { it.entity() })
        insertEncoderRepMetrics(t.encoderRepMetrics.map { it.entity() })
        insertFvpAssessments(t.fvpAssessments.map { it.entity() })
        insertFvpConditions(t.fvpConditions.map { it.entity() })
        insertFvpAttempts(t.fvpAttempts.map { it.entity() })
        // Padres antes que hijos: los analisis necesitan su atleta insertado (arriba).
        insertAngleAnalyses(t.angleAnalyses.map { it.entity() })
        insertAngleMarks(t.angleMarks.map { it.entity() })
        check(foreignKeyCheck(SimpleSQLiteQuery("PRAGMA foreign_key_check")).isEmpty()) { "Restored data has foreign-key violations" }
        check(counts() == t.counts()) { "Restored data counts do not match backup" }
    }

    /**
     * Restauracion **transaccional** de un backup con imagenes: sustituye las tablas legacy y el
     * agregado multi-fotograma V2 (`angle_analysis_v2_roots/frames/marks`) por lo que declara un
     * [MediaBackupManifest] completo, conservando **ids, ordinals y claves exactos**.
     *
     * Contrato y garantias:
     *  - [MediaBackupManifest.validate] se ejecuta **antes del primer DELETE** (dentro de la misma
     *    transaccion): un manifiesto invalido, huerfano, con ids/claves duplicados, con geometria
     *    imposible o con una imagen declarada que no cuadre con su fotograma lanza
     *    [IllegalArgumentException] y no toca ni una fila;
     *  - borra V2 hijos antes que padres (`marks`, `frames`, `roots`), despues el legacy con el mismo
     *    cuerpo que [replaceAll] (incluidas sus comprobaciones de conteos y `foreign_key_check`) y
     *    reinserta V2 padres antes que hijos; como la raiz referencia `athletes` con `NO_ACTION`, el
     *    agregado V2 se vacia antes de borrar atletas y se reinserta despues de insertarlos;
     *  - verifica al final que los conteos observados en las tres tablas V2 son los del manifiesto y
     *    que `PRAGMA foreign_key_check` queda vacio. La unicidad de `sessionKey`, `imageKey`,
     *    `(rootId, ordinal)` y `(frameId, ordinal)` la imponen los indices unicos de las tablas con
     *    `ABORT`, de modo que una colision aborta **toda** la transaccion;
     *  - **todo** ocurre en una unica `@Transaction` de Room: si algo falla en cualquier etapa
     *    (borrado, insercion legacy, insercion de fotograma o de marca, verificacion final), Room
     *    deshace el restore completo y la base queda como estaba, sin filas a medias;
     *  - la barrera v1 no se relaja: [snapshot] y [replaceAll] siguen lanzando
     *    [ManualBackupV2BlockedException] mientras exista cualquier fila V2. Restaurar un manifiesto
     *    con V2 vacio deja la base como la dejaria el restore v1 y reabre ese camino.
     *
     * **Lo que este metodo NO hace (advertencia explicita).** Solo escribe **filas**. No abre, no
     * publica, no verifica ni borra ficheros de imagen: `imageKey`/`imageByteCount`/`imageSha256` se
     * guardan como los declara el manifiesto, sin comprobar que el PNG exista o sea duradero. Room y
     * el almacen de ficheros **no comparten transaccion**, asi que esto no es un restore atomico de
     * Room+ficheros. Por tanto, el futuro coordinador debe: (a) publicar y verificar cada PNG contra
     * su tamano/SHA-256 declarados **antes** de llamar aqui (o fallar cerrado si no coinciden), y
     * (b) poseer la exclusion de guardados, borrados, otras restauraciones y backups concurrentes
     * mientras dura la publicacion **y** esta transaccion. Sin (a) y (b) el resultado puede ser un
     * manifiesto coherente que apunte a medios ausentes o sustituidos, y este DAO no puede detectarlo.
     *
     * Restaurar un JSON **v1 legacy** cuando la base ya tiene V2 no se resuelve aqui: esa operacion
     * destruiria analisis que el documento no transporta. La unica ruta que lo hace es
     * [replaceAllDiscardingAngleV2], que exige el agregado V2 exacto que el usuario confirmo
     * descartar y revalida ese estado dentro de su propia transaccion antes de borrar nada.
     *
     * **Segunda confirmacion, tambien para un manifiesto con imagenes.** Cualquier restore
     * —contenedor ZIP con o sin analisis, o JSON v1— sustituye el agregado V2 actual, asi que
     * [confirmedAngleV2] es obligatorio en la practica para un llamante de producto:
     *  - con la instantanea que el usuario acepto ([AngleV2DiscardWarning]), se **recalcula completa**
     *    dentro de esta transaccion y antes del primer `DELETE`; si el agregado actual es otro (aunque
     *    tenga los mismos conteos) se lanza [ManualBackupAngleV2ChangedException] sin borrar nada;
     *  - con `null` solo se admite que el agregado actual este **vacio** (ese es el caso "la
     *    advertencia inicial fue nula"): se comprueba de forma atomica y, si aparecio cualquier fila
     *    V2, se lanza [ManualBackupV2BlockedException] sin borrar nada. El vacio no se supone: se
     *    verifica dentro de la misma transaccion que borra.
     *
     * No se conecta a UI ni sustituye a [replaceAll]; no borra ficheros y no escribe en el agregado
     * fuera de esta transaccion de restore.
     */
    @Transaction
    suspend fun replaceAllWithAngleMedia(
        manifest: MediaBackupManifest,
        confirmedAngleV2: AngleAnalysisV2AggregateSnapshot? = null,
    ) {
        // 1) Validacion completa ANTES de mutar: un manifiesto invalido no borra ni una fila.
        val validated = manifest.validate()
        // 2) Huella completa recalculada dentro de esta transaccion e inmediatamente antes del
        //    primer `DELETE` del cuerpo compartido. Sin confirmacion el agregado actual debe estar
        //    vacio de forma atomica; con confirmacion debe coincidir exactamente.
        requireConfirmedAngleV2(confirmedAngleV2)
        replaceValidatedWithAngleMedia(validated)
    }

    /**
     * Restaura un documento **v1 legacy** borrando el agregado V2 actual, y solo si ese agregado es
     * exactamente el que el usuario confirmo descartar tras ver la advertencia.
     *
     * Contrato:
     *  - [snapshot] se valida (v1 completo, con preflight) a traves de
     *    [MediaBackupManifest.validate] del manifiesto vacio, antes de tocar nada;
     *  - [confirmedAngleV2] **no puede estar vacio**: descartar nada no es un descarte y ese caso ya
     *    lo cubre [replaceAll]. Una instantanea vacia aqui lanzaria [IllegalArgumentException];
     *  - la instantanea **completa** (conteos **y** contenido semantico de todas las filas) se
     *    **recalcula dentro de la transaccion** y debe coincidir **exactamente** con
     *    [confirmedAngleV2]. Si cambia —otra pantalla guardo o borro un analisis, otra restauracion
     *    sustituyo el agregado por otro, o el contenido cambio manteniendo los conteos— se lanza
     *    [ManualBackupAngleV2ChangedException] **antes del primer `DELETE`**: no se borra ni una fila
     *    y no se pierde nada que el usuario no haya visto. Comparar solo conteos destruiria un
     *    conjunto distinto del confirmado, y por eso no se hace;
     *  - la sustitucion es el **mismo cuerpo** que [replaceAllWithAngleMedia] con `angleV2` vacio
     *    (helper compartido): hijos V2 antes que padres, legacy con sus comprobaciones de conteos y
     *    `foreign_key_check`, V2 vacio verificado y todo en **una sola** `@Transaction`;
     *  - **no** borra ni escribe ficheros de imagen: las imagenes anteriores se conservan tal cual y
     *    quedan sin fila que las referencie (huerfanas). Este metodo no puede borrarlas y no lo
     *    intenta.
     */
    @Transaction
    suspend fun replaceAllDiscardingAngleV2(
        snapshot: ManualBackupDocument,
        confirmedAngleV2: AngleAnalysisV2AggregateSnapshot,
    ) {
        require(!confirmedAngleV2.counts.isEmpty) {
            "Discarding legacy restore needs a non-empty confirmed angle V2 aggregate"
        }
        // Manifiesto con V2 vacio: el restore de [replaceAllWithAngleMedia] borra el agregado actual
        // e inserta cero filas V2, dejando un estado identico al que produciria el restore v1. Se
        // valida entero (incluido el documento legacy y su preflight) ANTES del primer `DELETE`,
        // igual que la entrada con manifiesto completo.
        val manifest = MediaBackupManifest(
            contract = MEDIA_BACKUP_CONTRACT,
            formatVersion = MEDIA_BACKUP_FORMAT_VERSION,
            sourceRoomSchemaVersion = MEDIA_BACKUP_ROOM_VERSION,
            createdAtEpochMillis = snapshot.createdAtEpochMillis,
            legacy = snapshot,
            angleV2 = MediaBackupAngleV2(),
        ).validate()
        // Recomprobacion atomica compartida con [replaceAllWithAngleMedia]: identidad + contenido
        // completo, no solo conteos. Se recalcula dentro de esta transaccion y ANTES de cualquier
        // `DELETE` (incluidos los del cuerpo compartido), asi que ningun escritor concurrente puede
        // colarse entre la comprobacion y el borrado. Una diferencia, por minima que sea, aborta sin
        // tocar Room ni ficheros.
        requireConfirmedAngleV2(confirmedAngleV2)
        replaceValidatedWithAngleMedia(manifest)
    }

    /**
     * Guard unico de la segunda confirmacion del agregado V2: recalcula la instantanea **completa**
     * dentro de la transaccion del llamante y la compara con la que el usuario confirmo. Debe
     * invocarse **despues** de validar el manifiesto y **antes** del primer `DELETE` del cuerpo
     * compartido ([replaceValidatedWithAngleMedia]), que es el orden que garantiza que una
     * advertencia obsoleta o un agregado que aparecio despues no borren nada.
     *
     * `null` = no habia agregado que perder: se exige que siga **vacio** (vacio atomico). No es una
     * via permisiva: cualquier fila V2 surgida entre la advertencia y la aplicacion aborta.
     */
    private suspend fun requireConfirmedAngleV2(confirmed: AngleAnalysisV2AggregateSnapshot?) {
        val observed = observeAngleV2Aggregate()
        if (confirmed == null) {
            if (!observed.counts.isEmpty) throw ManualBackupV2BlockedException(observed.counts)
            return
        }
        if (observed != confirmed) {
            throw ManualBackupAngleV2ChangedException(observed = observed, confirmed = confirmed)
        }
    }

    /**
     * Cuerpo transaccional compartido de las dos restauraciones con imagenes. Debe llamarse siempre
     * dentro de una `@Transaction`: borra el agregado V2, sustituye el legacy, reinserta el V2 del
     * manifiesto (posiblemente vacio) y verifica conteos y claves foraneas. No valida el manifiesto:
     * eso lo hace cada entrada publica **antes** de invocarlo.
     */
    private suspend fun replaceValidatedWithAngleMedia(validated: MediaBackupManifest) {
        val rows = validated.angleV2.restoreRows()
        // 2) Agregado V2 actual fuera, hijos antes que padres. Tiene que ocurrir antes de borrar
        // `athletes`: la FK de la raiz es NO_ACTION y bloquearia el borrado.
        clearAngleV2Marks()
        clearAngleV2Frames()
        clearAngleV2Roots()
        // 3) Legacy: mismo cuerpo (y mismas comprobaciones) que el restore v1.
        replaceLegacyTables(validated.legacy.tables)
        // 4) V2 del manifiesto, padres antes que hijos y con los ids exactos.
        insertAngleV2Roots(rows.roots)
        insertAngleV2Frames(rows.frames)
        insertAngleV2Marks(rows.marks)
        // 5) Verificacion final: conteos observados == manifiesto y sin huerfanos.
        val stored = AngleAnalysisV2RowCounts(angleV2RootCount(), angleV2FrameCount(), angleV2MarkCount())
        check(stored == rows.counts) {
            "Restored angle V2 rows do not match the manifest (${stored.describe()} != ${rows.counts.describe()})"
        }
        check(foreignKeyCheck(SimpleSQLiteQuery("PRAGMA foreign_key_check")).isEmpty()) {
            "Restored media backup has foreign-key violations"
        }
    }

    @Query("SELECT COUNT(*) FROM athletes") suspend fun athleteCount(): Int
    @Query("SELECT COUNT(*) FROM athlete_groups") suspend fun groupCount(): Int
    @Query("SELECT COUNT(*) FROM athlete_group_cross_ref") suspend fun membershipCount(): Int
    @Query("SELECT COUNT(*) FROM testing_sessions") suspend fun testingSessionCount(): Int
    @Query("SELECT COUNT(*) FROM testing_participants") suspend fun testingParticipantCount(): Int
    @Query("SELECT COUNT(*) FROM assessments") suspend fun assessmentCount(): Int
    @Query("SELECT COUNT(*) FROM attempts") suspend fun attemptCount(): Int
    @Query("SELECT COUNT(*) FROM attempt_events") suspend fun attemptEventCount(): Int
    @Query("SELECT COUNT(*) FROM attempt_metrics") suspend fun attemptMetricCount(): Int
    @Query("SELECT COUNT(*) FROM attempt_calibrations") suspend fun attemptCalibrationCount(): Int
    @Query("SELECT COUNT(*) FROM attempt_spatial_marks") suspend fun attemptSpatialMarkCount(): Int
    @Query("SELECT COUNT(*) FROM encoder_sessions") suspend fun encoderSessionCount(): Int
    @Query("SELECT COUNT(*) FROM encoder_samples") suspend fun encoderSampleCount(): Int
    @Query("SELECT COUNT(*) FROM encoder_repetitions") suspend fun encoderRepetitionCount(): Int
    @Query("SELECT COUNT(*) FROM encoder_rep_metrics") suspend fun encoderRepMetricCount(): Int
    @Query("SELECT COUNT(*) FROM fvp_assessments") suspend fun fvpAssessmentCount(): Int
    @Query("SELECT COUNT(*) FROM fvp_conditions") suspend fun fvpConditionCount(): Int
    @Query("SELECT COUNT(*) FROM fvp_attempts") suspend fun fvpAttemptCount(): Int
    @Query("SELECT COUNT(*) FROM angle_analyses") suspend fun angleAnalysisCount(): Int
    @Query("SELECT COUNT(*) FROM angle_marks") suspend fun angleMarkCount(): Int

    // --- Agregado V2 (Room 14) ----------------------------------------------------------------
    // Lecturas de SOLO LECTURA para la captura de metadatos con imagenes. El agregado se sigue
    // escribiendo (cuando exista) por su propio contrato transaccional y `AngleAnalysisV2Dao` no
    // gana ninguna escritura. El orden es estable (id, y ordinal con id como desempate) para que la
    // proyeccion al manifiesto no dependa del plan de consulta.
    @Query("SELECT * FROM angle_analysis_v2_roots ORDER BY id") suspend fun angleV2Roots(): List<AngleAnalysisV2RootEntity>
    @Query("SELECT * FROM angle_analysis_v2_frames ORDER BY rootId, ordinal, id") suspend fun angleV2Frames(): List<AngleAnalysisV2FrameEntity>
    @Query("SELECT * FROM angle_analysis_v2_marks ORDER BY frameId, ordinal, id") suspend fun angleV2Marks(): List<AngleAnalysisV2MarkEntity>

    // Escrituras del restore con imagenes (G3b). NO son un escritor de producto: solo las usa
    // [replaceAllWithAngleMedia] dentro de su propia transaccion. `ABORT` convierte cualquier
    // colision de identidad o de clave unica en un fallo que deshace el restore completo.
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAngleV2Roots(rows: List<AngleAnalysisV2RootEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAngleV2Frames(rows: List<AngleAnalysisV2FrameEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAngleV2Marks(rows: List<AngleAnalysisV2MarkEntity>)

    // Hijos antes que padres. Se borran explicitamente en vez de confiar en `ON DELETE CASCADE` para
    // que el orden sea el del contrato y el rollback cubra las tres tablas por igual.
    @Query("DELETE FROM angle_analysis_v2_marks") suspend fun clearAngleV2Marks()
    @Query("DELETE FROM angle_analysis_v2_frames") suspend fun clearAngleV2Frames()
    @Query("DELETE FROM angle_analysis_v2_roots") suspend fun clearAngleV2Roots()

    // Los conteos existen para que el rechazo nombre la tabla que bloquea y para que la barrera v1
    // sea observable en tests.
    @Query("SELECT COUNT(*) FROM angle_analysis_v2_roots") suspend fun angleV2RootCount(): Int
    @Query("SELECT COUNT(*) FROM angle_analysis_v2_frames") suspend fun angleV2FrameCount(): Int
    @Query("SELECT COUNT(*) FROM angle_analysis_v2_marks") suspend fun angleV2MarkCount(): Int

    private suspend fun counts() = listOf(athleteCount(), groupCount(), membershipCount(), testingSessionCount(), testingParticipantCount(), assessmentCount(), attemptCount(), attemptEventCount(), attemptMetricCount(), attemptCalibrationCount(), attemptSpatialMarkCount(), encoderSessionCount(), encoderSampleCount(), encoderRepetitionCount(), encoderRepMetricCount(), fvpAssessmentCount(), fvpConditionCount(), fvpAttemptCount(), angleAnalysisCount(), angleMarkCount())

    /**
     * Barrera TRANSITORIA fail-closed: cualquier fila en cualquiera de las tres tablas V2 bloquea
     * snapshot y restore. Debe llamarse siempre como primera sentencia del cuerpo `@Transaction`
     * correspondiente, para que la comprobacion y la mutacion compartan transaccion.
     */
    private suspend fun requireNoAngleV2Rows() {
        val rows = AngleAnalysisV2RowCounts(angleV2RootCount(), angleV2FrameCount(), angleV2MarkCount())
        if (!rows.isEmpty) throw ManualBackupV2BlockedException(rows)
    }

    private fun ManualBackupTables.counts() = listOf(athletes.size, athleteGroups.size, athleteGroupCrossRef.size, testingSessions.size, testingParticipants.size, assessments.size, attempts.size, attemptEvents.size, attemptMetrics.size, attemptCalibrations.size, attemptSpatialMarks.size, encoderSessions.size, encoderSamples.size, encoderRepetitions.size, encoderRepMetrics.size, fvpAssessments.size, fvpConditions.size, fvpAttempts.size, angleAnalyses.size, angleMarks.size)
}

data class ForeignKeyViolation(val table: String, val rowid: Long, val parent: String, val fkid: Int)

/**
 * El agregado V2 cambio entre la advertencia que el usuario confirmo y la aplicacion del restore
 * legacy. Se lanza **dentro** de la transaccion y antes del primer `DELETE`: no se sustituyo ninguna
 * fila y los datos locales (incluidos los analisis que el usuario habia visto) se conservan.
 *
 * El cambio se detecta sobre la instantanea **completa** ([AngleAnalysisV2AggregateSnapshot]), no
 * solo sobre los conteos: si otra restauracion dejo el mismo numero de raices/fotogramas/marcas con
 * otro contenido, tambien se lanza y tampoco se borra nada.
 *
 * No es un fallo de formato: es una carrera resuelta en contra de la operacion. La salida correcta es
 * volver a leer el archivo y confirmar sobre el estado nuevo, nunca reintentar a ciegas con la
 * instantanea vieja. El mensaje solo lleva conteos y un prefijo de la huella: nunca filas ni datos
 * personales.
 */
class ManualBackupAngleV2ChangedException(
    val observed: AngleAnalysisV2AggregateSnapshot,
    val confirmed: AngleAnalysisV2AggregateSnapshot,
) : IllegalStateException(
    "The angle V2 aggregate changed since it was confirmed for discard " +
        "(confirmed ${confirmed.describe()}, now ${observed.describe()}). Nothing was replaced.",
)

/**
 * Instantanea **semantica completa** del agregado multi-fotograma V2 (Room 14): los tres conteos y
 * una huella SHA-256 de **todas** las filas de `angle_analysis_v2_roots/frames/marks` con **todas**
 * sus columnas, en un orden estable.
 *
 * **Por que no basta [AngleAnalysisV2RowCounts].** Dos estados distintos pueden tener el mismo
 * numero de raices, fotogramas y marcas: otra restauracion puede sustituir un analisis por otro, o
 * un guardado puede cambiar geometria, `sessionKey`, dimensiones, integridad de imagen, fechas o el
 * nombre de una marca sin cambiar cuantas filas hay. Confirmar un descarte sobre los conteos y
 * aplicarlo contra el segundo estado destruye un conjunto que el usuario nunca vio. Esta instantanea
 * incluye identidad (`id` de cada fila y claves foraneas) y contenido de **todas** las columnas, de
 * modo que ese caso se detecta y el restore falla cerrado.
 *
 * **No fabricable.** El constructor y la fabrica son `internal` y la huella solo se calcula desde
 * filas que se acaban de leer ([ManualBackupDao.angleV2AggregateSnapshot] o la revalidacion dentro
 * de la transaccion que borra). No existe ninguna via publica para construir una instantanea con un
 * contenido distinto del que hay en la base.
 *
 * **Privacidad.** El objeto no guarda filas: solo los conteos y la huella. Ni el contenido de las
 * columnas ni datos personales (nombres de marca, `sessionKey`, fechas...) se conservan aqui ni se
 * registran en logs; [describe] publica unicamente conteos y un prefijo corto de la huella.
 *
 * **Que NO es.** No es una version, un contador de proceso ni una marca de tiempo, no caduca, no
 * depende de `sessionKey` y no autoriza nada por si misma: la autorizacion sigue siendo la
 * confirmacion del usuario, y la comprobacion que vale es la que se recalcula dentro de la misma
 * transaccion Room que borra.
 *
 * **Coste.** Una lectura de las tres tablas y un SHA-256 sobre sus columnas: lineal en el numero de
 * filas V2 y sin abrir ni leer un solo byte de imagen. No incluye el agregado legacy.
 */
class AngleAnalysisV2AggregateSnapshot internal constructor(
    /** Conteos de las tres tablas en el instante de la captura; diagnostico y contrato no vacio. */
    val counts: AngleAnalysisV2RowCounts,
    private val semanticFingerprint: String,
) {
    /**
     * Descripcion apta para diagnostico y mensajes de error: los conteos y un prefijo de la huella.
     * Nunca incluye filas, nombres de marca ni ninguna otra columna.
     */
    internal fun describe(): String =
        "${counts.describe()}, semanticFingerprint=${semanticFingerprint.take(AGGREGATE_FINGERPRINT_PREFIX_LENGTH)}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AngleAnalysisV2AggregateSnapshot) return false
        return counts == other.counts && semanticFingerprint == other.semanticFingerprint
    }

    override fun hashCode(): Int = 31 * counts.hashCode() + semanticFingerprint.hashCode()

    override fun toString(): String = "AngleAnalysisV2AggregateSnapshot(${describe()})"

    internal companion object {
        /**
         * Huella de [roots], [frames] y [marks] **tal y como se acaban de leer**.
         *
         * La codificacion es inyectiva y no depende de `toString()` ni del locale: cada entero va
         * como 4 bytes big-endian, cada `Long` como 8, cada `Double` como sus bits crudos (8), cada
         * booleano como un byte y cada texto como su longitud UTF-8 en 4 bytes seguida de sus bytes.
         * Los `null` llevan una marca de presencia explicita de un byte, de modo que `null` no puede
         * colisionar con `""` ni con `0`. Cada lista se introduce con su tamano, cada fila con una
         * etiqueta de tabla y todo el flujo empieza por un separador de dominio fijo, asi que dos
         * agregados distintos no pueden producir el mismo flujo de bytes.
         *
         * Las filas se ordenan aqui por su clave estable (`id` en las raices; `rootId`/`frameId`,
         * `ordinal` e `id` en los hijos) para que la huella no dependa del plan de consulta ni del
         * orden en que el llamante entrego las listas.
         *
         * **Mantenimiento.** Al anadir o cambiar una columna en
         * `AngleAnalysisV2RootEntity`/`FrameEntity`/`MarkEntity` hay que anadirla al codificador de
         * su fila (`encodeRoot`/`encodeFrame`/`encodeMark`) en este mismo fichero: la huella solo
         * cubre las columnas que se escriben aqui.
         */
        fun of(
            roots: List<AngleAnalysisV2RootEntity>,
            frames: List<AngleAnalysisV2FrameEntity>,
            marks: List<AngleAnalysisV2MarkEntity>,
        ): AngleAnalysisV2AggregateSnapshot {
            val orderedRoots = roots.sortedBy { it.id }
            val orderedFrames = frames.sortedWith(compareBy({ it.rootId }, { it.ordinal }, { it.id }))
            val orderedMarks = marks.sortedWith(compareBy({ it.frameId }, { it.ordinal }, { it.id }))
            val sink = SemanticFingerprintSink()
            sink.text(AGGREGATE_FINGERPRINT_DOMAIN)
            sink.integer(orderedRoots.size)
            orderedRoots.forEach { sink.encodeRoot(it) }
            sink.integer(orderedFrames.size)
            orderedFrames.forEach { sink.encodeFrame(it) }
            sink.integer(orderedMarks.size)
            orderedMarks.forEach { sink.encodeMark(it) }
            return AngleAnalysisV2AggregateSnapshot(
                counts = AngleAnalysisV2RowCounts(
                    roots = orderedRoots.size,
                    frames = orderedFrames.size,
                    marks = orderedMarks.size,
                ),
                semanticFingerprint = sink.finishHex(),
            )
        }
    }
}

/**
 * Codificador inyectivo de campos hacia un SHA-256. Es `private` a proposito: la unica forma de
 * producir una [AngleAnalysisV2AggregateSnapshot] es
 * [AngleAnalysisV2AggregateSnapshot.of] con filas que se acaban de leer de la base.
 */
private class SemanticFingerprintSink {
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    private val scratch = ByteArray(8)

    fun byte(value: Int) {
        digest.update(value.toByte())
    }

    fun integer(value: Int) {
        scratch[0] = (value ushr 24).toByte()
        scratch[1] = (value ushr 16).toByte()
        scratch[2] = (value ushr 8).toByte()
        scratch[3] = value.toByte()
        digest.update(scratch, 0, 4)
    }

    fun long(value: Long) {
        scratch[0] = (value ushr 56).toByte()
        scratch[1] = (value ushr 48).toByte()
        scratch[2] = (value ushr 40).toByte()
        scratch[3] = (value ushr 32).toByte()
        scratch[4] = (value ushr 24).toByte()
        scratch[5] = (value ushr 16).toByte()
        scratch[6] = (value ushr 8).toByte()
        scratch[7] = value.toByte()
        digest.update(scratch, 0, 8)
    }

    fun double(value: Double) {
        // Bits crudos: sin formato, sin locale y sin perder -0.0/NaN frente a +0.0.
        long(value.toRawBits())
    }

    fun bool(value: Boolean) {
        byte(if (value) 1 else 0)
    }

    /** Texto con prefijo de longitud UTF-8: `"a", "bc"` y `"ab", "c"` no pueden colisionar. */
    fun text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        integer(bytes.size)
        digest.update(bytes)
    }

    fun nullableInteger(value: Int?) {
        if (value == null) {
            byte(0)
        } else {
            byte(1)
            integer(value)
        }
    }

    fun nullableLong(value: Long?) {
        if (value == null) {
            byte(0)
        } else {
            byte(1)
            long(value)
        }
    }

    fun finishHex(): String = hex(digest.digest())
}

private const val AGGREGATE_FINGERPRINT_DOMAIN = "openjump.angle-analysis-v2.aggregate.fingerprint.v1"

/** Longitud del prefijo de huella que se publica en mensajes; el digest completo no se loguea. */
private const val AGGREGATE_FINGERPRINT_PREFIX_LENGTH = 12

private const val HEX_DIGITS = "0123456789abcdef"

/** Hexadecimal en minusculas, sin `String.format` (que depende del locale). */
private fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

/**
 * Filas de la raiz V2: identidad, formato, idempotencia, duenio (nullable), fechas y revision.
 * Todas las columnas de [AngleAnalysisV2RootEntity].
 */
private fun SemanticFingerprintSink.encodeRoot(row: AngleAnalysisV2RootEntity) {
    text("root")
    long(row.id)
    text(row.analysisFormat)
    text(row.sessionKey)
    nullableLong(row.athleteId)
    long(row.createdAt)
    long(row.updatedAt)
    integer(row.revision)
}

/**
 * Filas de fotograma: identidad, orden, indices/PTS asignados y reportados (nullable), geometria de
 * codificacion y presentacion, version de geometria e integridad de imagen. Todas las columnas de
 * [AngleAnalysisV2FrameEntity].
 */
private fun SemanticFingerprintSink.encodeFrame(row: AngleAnalysisV2FrameEntity) {
    text("frame")
    long(row.id)
    long(row.rootId)
    integer(row.ordinal)
    integer(row.assignedFrameIndex)
    nullableInteger(row.reportedFrameIndex)
    long(row.assignedPtsUs)
    nullableLong(row.reportedPtsUs)
    integer(row.encodedWidth)
    integer(row.encodedHeight)
    integer(row.presentationWidth)
    integer(row.presentationHeight)
    integer(row.rotationDegrees)
    integer(row.geometryVersion)
    text(row.imageKey)
    integer(row.imageWidth)
    integer(row.imageHeight)
    long(row.imageByteCount)
    text(row.imageSha256)
    text(row.imageMimeType)
}

/**
 * Filas de marca: identidad, orden, nombre, color, visibilidad y las seis coordenadas. Todas las
 * columnas de [AngleAnalysisV2MarkEntity].
 */
private fun SemanticFingerprintSink.encodeMark(row: AngleAnalysisV2MarkEntity) {
    text("mark")
    long(row.id)
    long(row.frameId)
    integer(row.ordinal)
    text(row.name)
    integer(row.colorArgb)
    bool(row.visible)
    double(row.pointAX)
    double(row.pointAY)
    double(row.pointBX)
    double(row.pointBY)
    double(row.pointCX)
    double(row.pointCY)
}
