package com.openjump.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A local roster grouping. Archiving keeps its roster and all athlete history intact. */
@Entity(
    tableName = "athlete_groups",
    indices = [
        Index(value = ["archivedAt", "name"]),
        Index(value = ["name"]),
    ],
)
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val archivedAt: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "El nombre del grupo es obligatorio." }
    }
}

/** Many-to-many membership. Both parent deletes only cascade this join row. */
@Entity(
    tableName = "athlete_group_cross_ref",
    primaryKeys = ["groupId", "athleteId"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AthleteEntity::class,
            parentColumns = ["id"],
            childColumns = ["athleteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId"), Index("athleteId")],
)
data class AthleteGroupCrossRef(
    val groupId: Long,
    val athleteId: Long,
)

data class GroupWithMembers(
    @Embedded val group: GroupEntity,
    @Relation(
        entity = AthleteEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = androidx.room.Junction(
            value = AthleteGroupCrossRef::class,
            parentColumn = "groupId",
            entityColumn = "athleteId",
        ),
    )
    val members: List<AthleteEntity>,
)

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity): Int

    @Query("SELECT * FROM athlete_groups WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): GroupEntity?

    @Query("SELECT * FROM athlete_groups WHERE archivedAt IS NULL ORDER BY name COLLATE NOCASE, id")
    fun active(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM athlete_groups ORDER BY archivedAt IS NOT NULL, name COLLATE NOCASE, id")
    fun all(): Flow<List<GroupEntity>>

    @Transaction
    @Query("SELECT * FROM athlete_groups WHERE id = :id LIMIT 1")
    suspend fun detail(id: Long): GroupWithMembers?

    /** Reads metadata and members under one Room transaction for an immutable test snapshot. */
    @Transaction
    suspend fun activeSnapshot(id: Long): Pair<GroupEntity, List<AthleteEntity>>? {
        val value = detail(id) ?: return null
        if (value.group.archivedAt != null) return null
        val activeMembers = value.members.filter { it.archivedAt == null }
        return value.group to activeMembers
    }

    @Transaction
    @Query("SELECT * FROM athlete_groups WHERE id = :id LIMIT 1")
    fun observeDetail(id: Long): Flow<GroupWithMembers?>

    @Query(
        """
        SELECT a.* FROM athletes AS a
        INNER JOIN athlete_group_cross_ref AS membership
          ON membership.athleteId = a.id
        WHERE membership.groupId = :groupId
        ORDER BY a.archivedAt IS NOT NULL, a.displayName COLLATE NOCASE, a.id
        """,
    )
    suspend fun members(groupId: Long): List<AthleteEntity>

    @Query(
        """
        SELECT a.* FROM athletes AS a
        INNER JOIN athlete_group_cross_ref AS membership
          ON membership.athleteId = a.id
        WHERE membership.groupId = :groupId AND a.archivedAt IS NULL
        ORDER BY a.displayName COLLATE NOCASE, a.id
        """,
    )
    suspend fun activeMembers(groupId: Long): List<AthleteEntity>

    @Query("DELETE FROM athlete_group_cross_ref WHERE groupId = :groupId")
    suspend fun clearMemberships(groupId: Long)

    @Query(
        """
        DELETE FROM athlete_group_cross_ref
        WHERE groupId = :groupId AND athleteId = :athleteId
          AND EXISTS (
            SELECT 1 FROM athlete_groups
            WHERE id = :groupId AND archivedAt IS NULL
          )
        """,
    )
    suspend fun removeMembership(groupId: Long, athleteId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemberships(memberships: List<AthleteGroupCrossRef>)

    /** Creates a group and its initial roster atomically. */
    @Transaction
    suspend fun insertWithMemberships(group: GroupEntity, athleteIds: List<Long>): Long {
        val id = insert(group)
        replaceMemberships(id, athleteIds)
        return id
    }

    /** Updates group metadata and replaces its roster atomically. */
    @Transaction
    suspend fun updateWithMemberships(group: GroupEntity, athleteIds: List<Long>): Boolean {
        if (update(group) != 1) return false
        replaceMemberships(group.id, athleteIds)
        return true
    }

    /** Replaces the whole roster in one transaction; N:M rows remain independent. */
    @Transaction
    suspend fun replaceMemberships(groupId: Long, athleteIds: List<Long>) {
        check(byId(groupId) != null) { "El grupo no existe." }
        clearMemberships(groupId)
        val distinctIds = athleteIds.distinct()
        if (distinctIds.isNotEmpty()) {
            insertMemberships(distinctIds.map { AthleteGroupCrossRef(groupId, it) })
        }
    }

    @Query("UPDATE athlete_groups SET archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archivedAt: Long, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE athlete_groups SET archivedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restore(id: Long, updatedAt: Long = System.currentTimeMillis()): Int

    /** DAO-only cleanup seam used by FK tests; the UI never exposes hard delete. */
    @Query("DELETE FROM athlete_groups WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

class GroupRepository(private val dao: GroupDao) {
    fun active(): Flow<List<GroupEntity>> = dao.active()
    fun all(): Flow<List<GroupEntity>> = dao.all()
    suspend fun byId(id: Long): GroupEntity? = dao.byId(id)
    suspend fun detail(id: Long): GroupWithMembers? = dao.detail(id)
    fun observeDetail(id: Long): Flow<GroupWithMembers?> = dao.observeDetail(id)
    suspend fun members(groupId: Long): List<AthleteEntity> = dao.members(groupId)

    suspend fun create(name: String, notes: String? = null, now: Long = System.currentTimeMillis()): Long {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre del grupo es obligatorio." }
        return dao.insert(GroupEntity(0, cleanName, notes?.trim()?.takeIf { it.isNotEmpty() }, now, now))
    }

    suspend fun createWithMemberships(name: String, notes: String? = null, athleteIds: List<Long> = emptyList(), now: Long = System.currentTimeMillis()): Long {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre del grupo es obligatorio." }
        return dao.insertWithMemberships(GroupEntity(0, cleanName, notes?.trim()?.takeIf { it.isNotEmpty() }, now, now), athleteIds)
    }

    suspend fun update(group: GroupEntity): Boolean {
        val cleanName = group.name.trim()
        require(cleanName.isNotEmpty()) { "El nombre del grupo es obligatorio." }
        return dao.update(group.copy(name = cleanName, notes = group.notes?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = System.currentTimeMillis())) == 1
    }

    suspend fun edit(id: Long, name: String, notes: String? = null): Boolean =
        dao.byId(id)?.let { update(it.copy(name = name, notes = notes)) } ?: false

    suspend fun updateWithMemberships(group: GroupEntity, name: String, notes: String?, athleteIds: List<Long>): Boolean {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre del grupo es obligatorio." }
        return dao.updateWithMemberships(
            group.copy(name = cleanName, notes = notes?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = System.currentTimeMillis()),
            athleteIds,
        )
    }

    suspend fun archive(id: Long): Boolean = dao.setArchived(id, System.currentTimeMillis()) == 1
    suspend fun restore(id: Long): Boolean = dao.restore(id) == 1
    suspend fun replaceMemberships(groupId: Long, athleteIds: List<Long>) =
        dao.replaceMemberships(groupId, athleteIds)
    suspend fun removeMembership(groupId: Long, athleteId: Long): Boolean =
        dao.removeMembership(groupId, athleteId) == 1
}
