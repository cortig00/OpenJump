package com.openjump.app

import android.app.Application
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.SelectedAthleteStore
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.export.DataExportRepository
import com.openjump.app.data.backup.ManualBackupRepository
import com.openjump.app.settings.DisplayPreferencesStore
import com.openjump.app.settings.ExperienceModeStore
import com.openjump.app.settings.OnboardingPreferencesStore
import com.openjump.app.video.AndroidPersistedVideoGrantStore
import com.openjump.app.video.AppSession
import com.openjump.app.video.VideoUriGrantReconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenJumpApp : Application() {

    // Initialize in onCreate (after Context attachment), before any Activity can open Room.
    val onboardingPreferencesStore: OnboardingPreferencesStore by lazy { OnboardingPreferencesStore(this) }

    override fun onCreate() {
        super.onCreate()
        onboardingPreferencesStore // Synchronously persist PENDING before Room bootstrap.
    }

    val database: JumpDatabase by lazy { JumpDatabase.get(this) }

    /** Constructing the manager is Room-lazy; its DAO lambda is first invoked after UI bootstrap. */
    internal val videoUriGrantReconciler: VideoUriGrantReconciler by lazy {
        VideoUriGrantReconciler(
            grants = AndroidPersistedVideoGrantStore(contentResolver),
            savedReferences = { database.jumpDao().savedVideoUris().toSet() },
            liveReferences = AppSession::videoUriReferences,
        )
    }
    private val grantCleanupScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    suspend fun reconcileVideoUriGrants() = withContext(Dispatchers.IO) {
        videoUriGrantReconciler.reconcile()
    }

    fun requestVideoUriReconciliation() {
        grantCleanupScope.launch { videoUriGrantReconciler.reconcile() }
    }

    val repository: JumpRepository by lazy { JumpRepository(database.jumpDao(), ::reconcileVideoUriGrants) }
    val encoderRepository: EncoderRepository by lazy { EncoderRepository(database.encoderDao(), ::reconcileVideoUriGrants) }
    val athleteRepository: AthleteRepository by lazy { AthleteRepository(database.athleteDao(), ::reconcileVideoUriGrants) }
    val groupRepository: com.openjump.app.data.GroupRepository by lazy { com.openjump.app.data.GroupRepository(database.groupDao()) }
    val dataExportRepository: DataExportRepository by lazy { DataExportRepository(database) }
    val manualBackupRepository: ManualBackupRepository by lazy { ManualBackupRepository(database.manualBackupDao()) }
    val testingRepository: com.openjump.app.data.TestingRepository by lazy {
        com.openjump.app.data.TestingRepository(database.testingDao(), database.groupDao())
    }
    val selectedAthleteStore: SelectedAthleteStore by lazy { SelectedAthleteStore(this) }

    val experienceModeStore: ExperienceModeStore by lazy { ExperienceModeStore(this) }
    val displayPreferencesStore: DisplayPreferencesStore by lazy { DisplayPreferencesStore(this) }
}
