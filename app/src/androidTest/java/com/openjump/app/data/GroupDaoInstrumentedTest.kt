package com.openjump.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupDaoInstrumentedTest {
    private lateinit var database: JumpDatabase
    private lateinit var groups: GroupRepository
    private lateinit var athletes: AthleteRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), JumpDatabase::class.java,
        ).build()
        groups = GroupRepository(database.groupDao())
        athletes = AthleteRepository(database.athleteDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun athleteCanBelongToMultipleGroupsAndReplaceIsAtomic() = runBlocking {
        val firstAthlete = athletes.create("First")
        val secondAthlete = athletes.create("Second")
        val firstGroup = groups.create("First group")
        val secondGroup = groups.create("Second group")

        groups.replaceMemberships(firstGroup, listOf(firstAthlete, secondAthlete))
        groups.replaceMemberships(secondGroup, listOf(firstAthlete))
        assertEquals(setOf(firstAthlete, secondAthlete), groups.detail(firstGroup)!!.members.map { it.id }.toSet())
        assertEquals(setOf(firstAthlete), groups.detail(secondGroup)!!.members.map { it.id }.toSet())

        groups.replaceMemberships(firstGroup, listOf(secondAthlete))
        assertEquals(setOf(secondAthlete), groups.detail(firstGroup)!!.members.map { it.id }.toSet())
        assertEquals(setOf(firstAthlete), groups.detail(secondGroup)!!.members.map { it.id }.toSet())
    }

    @Test
    fun archivePreservesRosterAndShowsArchivedMembers() = runBlocking {
        val athlete = athletes.create("Historical")
        val group = groups.create("Roster")
        groups.replaceMemberships(group, listOf(athlete))
        athletes.archive(athlete)
        groups.archive(group)

        assertTrue(groups.all().first().single().archivedAt != null)
        val detail = groups.detail(group)!!
        assertEquals(listOf(athlete), detail.members.map { it.id })
        assertTrue(detail.members.single().archivedAt != null)
    }

    @Test
    fun deletingGroupOnlyCascadesMembershipRows() = runBlocking {
        val athlete = athletes.create("Kept")
        val group = groups.create("Temporary")
        groups.replaceMemberships(group, listOf(athlete))
        database.groupDao().deleteById(group)

        assertEquals(1, athletes.activeOnce().size)
        assertEquals(0, database.groupDao().members(group).size)
    }
}
