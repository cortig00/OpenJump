package com.openjump.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Acceso Room al agregado de ángulos: raíz [AngleAnalysisEntity] + marcas [AngleMarkEntity].
 *
 * Esta interfaz es deliberadamente **mecánica**: solo declara sentencias y garantías de SQL
 * (guarda de `analysisId`, incremento de revisión, borrado explícito). La validación del agregado
 * y la orquestación transaccional viven en [AngleRepository]; ningún llamante debe usar el DAO
 * directamente para escribir porque podría saltarse esas reglas.
 *
 * Las sentencias de escritura devuelven las filas afectadas: 1 = aplicada, 0 = la fila no
 * existía o no pertenecía al análisis indicado, que el repositorio trata como error/conflicto
 * (nunca como éxito silencioso).
 */
@Dao
interface AngleDao {

    /**
     * Inserta la raíz. `IGNORE` protege la idempotencia por `sessionKey` incluso si dos guardados
     * compiten: el segundo devuelve -1 y el repositorio resuelve la raíz existente sin duplicar
     * hijos.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAnalysis(analysis: AngleAnalysisEntity): Long

    @Insert
    suspend fun insertMarks(marks: List<AngleMarkEntity>)

    @Query("SELECT id FROM angle_analyses WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun analysisIdForSessionKey(sessionKey: String): Long?

    @Query("SELECT * FROM angle_analyses WHERE id = :analysisId LIMIT 1")
    suspend fun analysisById(analysisId: Long): AngleAnalysisEntity?

    @Query("SELECT * FROM angle_analyses WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun analysisBySessionKey(sessionKey: String): AngleAnalysisEntity?

    /** Raíces ordenadas para la lista: la más reciente primero, con `id` como desempate estable. */
    @Query("SELECT * FROM angle_analyses ORDER BY createdAt DESC, id DESC")
    fun observeAnalyses(): Flow<List<AngleAnalysisEntity>>

    /** Igual que [observeAnalyses] pero acotado al dueño; el `id` desempata fechas repetidas. */
    @Query(
        "SELECT * FROM angle_analyses WHERE athleteId = :athleteId " +
            "ORDER BY createdAt DESC, id DESC",
    )
    fun observeAnalysesForAthlete(athleteId: Long): Flow<List<AngleAnalysisEntity>>

    /** Orden de presentación estable de las marcas: `ordinal` y, ante un empate imposible hoy, `id`. */
    @Query("SELECT * FROM angle_marks WHERE analysisId = :analysisId ORDER BY ordinal ASC, id ASC")
    suspend fun marksForAnalysis(analysisId: Long): List<AngleMarkEntity>

    /** Dueño real de una marca, para distinguir "no existe" de "pertenece a otra raíz". */
    @Query("SELECT analysisId FROM angle_marks WHERE id = :markId LIMIT 1")
    suspend fun markOwner(markId: Long): Long?

    @Query("SELECT id FROM athletes WHERE id = :athleteId LIMIT 1")
    suspend fun athleteIdOrNull(athleteId: Long): Long?

    /**
     * Compare-and-swap de la raíz: solo avanza la revisión si sigue en [expectedRevision].
     * Devuelve 0 si otro escritor se adelantó; el repositorio lo traduce a conflicto sin tocar
     * ninguna marca.
     */
    @Query(
        "UPDATE angle_analyses SET updatedAt = :updatedAt, revision = revision + 1 " +
            "WHERE id = :analysisId AND revision = :expectedRevision",
    )
    suspend fun touchAnalysis(analysisId: Long, expectedRevision: Int, updatedAt: Long): Int

    /**
     * Aparca el ordinal de una marca en un valor negativo único (`-id`) antes de reordenar.
     * El índice único `(analysisId, ordinal)` es inmediato, así que un intercambio de ordinales
     * (0↔1) colisionaría si se escribiera directamente; los negativos se restauran a su valor
     * final dentro de la misma transacción y nunca son visibles fuera de ella.
     */
    @Query("UPDATE angle_marks SET ordinal = -id WHERE id = :markId AND analysisId = :analysisId")
    suspend fun parkMarkOrdinal(markId: Long, analysisId: Long): Int

    @Query(
        """
        UPDATE angle_marks SET
            ordinal = :ordinal,
            name = :name,
            colorArgb = :colorArgb,
            visible = :visible,
            pointAX = :pointAX,
            pointAY = :pointAY,
            pointBX = :pointBX,
            pointBY = :pointBY,
            pointCX = :pointCX,
            pointCY = :pointCY
        WHERE id = :markId AND analysisId = :analysisId
        """,
    )
    suspend fun updateMark(
        analysisId: Long,
        markId: Long,
        ordinal: Int,
        name: String,
        colorArgb: Int,
        visible: Boolean,
        pointAX: Double,
        pointAY: Double,
        pointBX: Double,
        pointBY: Double,
        pointCX: Double,
        pointCY: Double,
    ): Int

    /** Borrado intencional de una marca concreta, siempre acotado a su raíz. */
    @Query("DELETE FROM angle_marks WHERE id = :markId AND analysisId = :analysisId")
    suspend fun deleteMark(markId: Long, analysisId: Long): Int

    /** Borra la raíz; las marcas caen por `ON DELETE CASCADE`. */
    @Query("DELETE FROM angle_analyses WHERE id = :analysisId")
    suspend fun deleteAnalysis(analysisId: Long): Int
}
