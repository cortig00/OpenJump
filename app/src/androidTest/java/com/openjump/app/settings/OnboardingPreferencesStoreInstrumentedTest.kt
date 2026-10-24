package com.openjump.app.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingPreferencesStoreInstrumentedTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var databaseFile: File
    private lateinit var isolatedContext: Context

    @Before
    fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "onboarding_test_$suffix"
        databaseFile = File(base.cacheDir, "onboarding_test_$suffix.db")
        preferences = base.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        isolatedContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
            override fun getDatabasePath(name: String): File = databaseFile
        }
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
        databaseFile.delete()
    }

    @Test
    fun freshInstallSynchronouslyPersistsPendingBeforeRoomBootstrap() {
        assertFalse(databaseFile.exists())

        val store = OnboardingPreferencesStore(isolatedContext)

        assertEquals(OnboardingCompletion.PENDING, store.completion.value)
        assertEquals("PENDING", preferences.getString("onboarding_completion", null))
        assertFalse(databaseFile.exists())
    }

    @Test
    fun existingDatabaseOrSettingsPreferencesMigratesToCompleted() {
        databaseFile.createNewFile()
        val databaseStore = OnboardingPreferencesStore(isolatedContext)
        assertEquals(OnboardingCompletion.COMPLETED, databaseStore.completion.value)
        assertEquals("COMPLETED", preferences.getString("onboarding_completion", null))

        preferences.edit().clear().commit()
        databaseFile.delete()
        preferences.edit().putBoolean("display_units_us", true).commit()
        val preferencesStore = OnboardingPreferencesStore(isolatedContext)
        assertEquals(OnboardingCompletion.COMPLETED, preferencesStore.completion.value)
        assertEquals("COMPLETED", preferences.getString("onboarding_completion", null))
    }

    @Test
    fun pendingPartialFlowSurvivesRestartEvenAfterDatabaseExists() {
        val firstLaunch = OnboardingPreferencesStore(isolatedContext)
        assertEquals(OnboardingCompletion.PENDING, firstLaunch.completion.value)
        databaseFile.createNewFile()

        val restarted = OnboardingPreferencesStore(isolatedContext)
        assertEquals(OnboardingCompletion.PENDING, restarted.completion.value)

        restarted.completeOnboarding()
        assertEquals(OnboardingCompletion.COMPLETED, OnboardingPreferencesStore(isolatedContext).completion.value)
    }

    @Test
    fun oldToolSelectionKeysAreIgnoredWithoutAffectingCompletion() {
        preferences.edit().putBoolean("onboarding_tool_jumps", false)
            .putBoolean("onboarding_tool_encoder", false).commit()
        val store = OnboardingPreferencesStore(isolatedContext)
        assertEquals(OnboardingCompletion.COMPLETED, store.completion.value)
    }
}
