package com.openjump.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.ui.theme.OpenJumpTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AthleteAvatarPickerTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun groupsKeepBrandFirstAndChoosingHeroReturnsStableKey() {
        var chosen: String? = null
        compose.setContent {
            OpenJumpTheme {
                AthleteAvatarPickerDialog("Atleta 1", "avatar_frog_jump", {}, { chosen = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.athlete_avatar_group_openjump)).assertExists()
        // Lazy sections beyond the larger brand group must be scrolled into view.
        compose.onNodeWithTag("avatar-picker-grid").performScrollToIndex(13)
        compose.onNodeWithText(context.getString(R.string.athlete_avatar_group_athletes)).assertIsDisplayed()
        compose.onNodeWithTag("avatar-picker-grid").performScrollToIndex(0)
        compose.onNodeWithContentDescription(context.getString(R.string.athlete_avatar_option, 2)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_confirm)).performClick()
        assertEquals("avatar_frog_hero", chosen)
    }

    @Test
    fun confirmingRetiredSavedAvatarChoosesItsVisibleReplacement() {
        var chosen: String? = null
        compose.setContent {
            OpenJumpTheme {
                AthleteAvatarPickerDialog("Atleta 1", "avatar_gymnast", {}, { chosen = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.common_confirm)).performClick()
        assertEquals("avatar_mobility", chosen)
    }

    @Test fun everyCategoryAndAvatarIsReachableAndSelectableInLightMode() = browseAll(false)
    @Test fun everyCategoryAndAvatarIsReachableAndSelectableInDarkMode() = browseAll(true)

    private fun browseAll(dark: Boolean) {
        var chosen: String? = null
        val started = android.os.SystemClock.elapsedRealtime()
        compose.setContent {
            OpenJumpTheme(darkTheme = dark) {
                AthleteAvatarPickerDialog("Avatar test", "avatar_frog_jump", {}, { chosen = it })
            }
        }
        compose.waitForIdle()
        android.util.Log.i("AvatarValidation", "picker_host_first_idle_ms=${android.os.SystemClock.elapsedRealtime() - started}, dark=$dark")
        var gridIndex = 0
        var ordinal = 1
        AthleteAvatarCatalog.groups.forEachIndexed { groupIndex, group ->
            compose.onNodeWithTag("avatar-picker-grid").performScrollToIndex(gridIndex++)
            compose.onNodeWithText(context.getString(group.titleRes)).assertIsDisplayed()
            group.options.forEach { option ->
                compose.onNodeWithTag("avatar-picker-grid").performScrollToIndex(gridIndex++)
                compose.onNodeWithContentDescription(context.getString(R.string.athlete_avatar_option, ordinal++))
                    .assertIsDisplayed().performClick().assertIsSelected()
                assertEquals(option, AthleteAvatarCatalog.find(option.key))
            }
            if (groupIndex == 0) gridIndex++ // Initials tile after the brand group.
        }
        compose.onNodeWithText(context.getString(R.string.common_confirm)).performClick()
        assertEquals("avatar_sculptor", chosen)
    }

    @Test
    fun everyBundledResourceDecodesAtBoundedResolution() {
        AthleteAvatarCatalog.options.forEach { option ->
            val bitmap = BitmapFactory.decodeResource(context.resources, option.drawableRes)
            assertNotNull(option.key, bitmap)
            assertTrue(option.key, bitmap.width in 192..256 && bitmap.height == bitmap.width)
            assertTrue(option.key, bitmap.allocationByteCount <= 256 * 256 * 4)
            bitmap.recycle()
            assertEquals(option.key, context.resources.getResourceEntryName(option.drawableRes))
        }
    }

    @Test
    fun allStableKeysAndLegacyKeysSurviveDatabaseCloseAndReopen() = runBlocking {
        // Own disposable database; never touch openjump.db or selection preferences.
        val name = "avatar-persistence-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, JumpDatabase::class.java, name).build()
        var database = open()
        try {
            val repository = AthleteRepository(database.athleteDao())
            val keys = AthleteAvatarCatalog.options.map { it.key } +
                listOf("avatar_img02_r1_c1", "avatar_gymnast", null, "unknown-avatar")
            val ids = keys.mapIndexed { index, key -> repository.create("Avatar $index", avatarKey = key) }
            database.close()
            database = open()
            val reopened = AthleteRepository(database.athleteDao())
            ids.zip(keys).forEach { (id, key) ->
                assertEquals(key, reopened.byId(id)!!.avatarKey)
                assertEquals(AthleteAvatarCatalog.find(key), AthleteAvatarCatalog.find(reopened.byId(id)!!.avatarKey))
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
