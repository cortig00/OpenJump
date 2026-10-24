package com.openjump.app.ui.components

import com.openjump.app.data.DEFAULT_ATHLETE_AVATAR_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AthleteAvatarTest {
    @Test
    fun initialsUseAtMostFirstTwoWordsAndHandleBlankNames() {
        assertEquals("AU", athleteInitials("Ana Usain Bolt"))
        assertEquals("M", athleteInitials("  Marta  "))
        assertEquals("?", athleteInitials("   "))
    }

    @Test
    fun catalogKeysAreUniqueAndUnknownKeysFallBackSafely() {
        val options = AthleteAvatarCatalog.options
        assertEquals(90, options.size)
        assertEquals(DEFAULT_ATHLETE_AVATAR_KEY, options.first().key)
        assertEquals(
            listOf(11, 10, 12, 6, 11, 20, 20),
            AthleteAvatarCatalog.groups.map { it.options.size },
        )
        assertEquals(
            listOf("avatar_jumper", "avatar_volleyball", "avatar_female_coach", "avatar_kettlebell", "avatar_jump_hero", "avatar_matador"),
            AthleteAvatarCatalog.groups.drop(1).map { it.options.first().key },
        )
        assertEquals(options.size, options.map { it.key }.distinct().size)
        assertEquals(options.size, options.map { it.drawableRes }.distinct().size)
        assertTrue(options.all { it.key.matches(Regex("avatar_[a-z0-9_]+")) })
        options.forEach { assertEquals(it, AthleteAvatarCatalog.find(it.key)) }
        assertTrue(AthleteAvatarCatalog.isValid(null))
        assertTrue(AthleteAvatarCatalog.isValid(options.first().key))
        for (sheet in listOf("02", "03")) {
            for (row in 1..5) for (column in 1..5) {
                val oldKey = "avatar_img${sheet}_r${row}_c${column}"
                assertTrue(oldKey, AthleteAvatarCatalog.isValid(oldKey))
                assertTrue(oldKey, AthleteAvatarCatalog.find(oldKey) in options)
            }
        }
        for (key in listOf("avatar_img01_r2_c2", "avatar_img05_r5_c4")) {
            assertEquals("avatar_swimmer", AthleteAvatarCatalog.find(key)?.key)
        }
        val retired = mapOf(
            "gymnast" to "mobility", "cyclist" to "sprinter", "climber" to "mobility",
            "junior_runner" to "sprinter", "water_bottle" to "kettlebell",
            "resistance_band" to "barbell", "cone" to "stopwatch",
            "balance_hero" to "jump_hero", "recovery_hero" to "strength_hero",
            "explosive_athlete" to "jumper", "rocket" to "lightning",
            "biomech_guardian" to "athlete_robot",
        )
        retired.forEach { (key, replacement) ->
            assertEquals("avatar_$replacement", AthleteAvatarCatalog.find("avatar_$key")?.key)
            assertTrue(AthleteAvatarCatalog.isValid("avatar_$key"))
            assertFalse(options.any { it.key == "avatar_$key" })
        }
        assertFalse(AthleteAvatarCatalog.isValid("removed-avatar"))
        assertNull(AthleteAvatarCatalog.find("removed-avatar"))
    }

    @Test
    fun sixApprovedSportAndHeroKeysAreAppendedWithoutMovingExistingOptions() {
        val groups = AthleteAvatarCatalog.groups
        val sports = groups[2].options.map { it.key }
        assertEquals(
            listOf(
                "avatar_volleyball", "avatar_basketball", "avatar_tennis",
                "avatar_goalkeeper", "avatar_boxer", "avatar_swimmer", "avatar_mobility",
                "avatar_volleyball_player_1", "avatar_volleyball_player_2",
                "avatar_basketball_player_1", "avatar_basketball_player_2",
                "avatar_swimmer_hero",
            ),
            sports,
        )
        val heroes = groups[5].options.map { it.key }
        assertEquals("avatar_mech_guardian", heroes[heroes.size - 2])
        assertEquals("avatar_gorilla_swimmer", heroes.last())
        // Pre-existing swimmer key and drawable are preserved alongside the new hero.
        assertTrue(sports.contains("avatar_swimmer"))
        for (key in sports.takeLast(5) + heroes.takeLast(1)) {
            assertTrue(key, AthleteAvatarCatalog.isValid(key))
            assertEquals(key, AthleteAvatarCatalog.find(key)?.key)
        }
    }
}
