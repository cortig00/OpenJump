package com.openjump.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AthleteDaoInstrumentedTest {
    private lateinit var database: JumpDatabase
    private lateinit var repository: AthleteRepository
    private lateinit var selectedAthleteStore: SelectedAthleteStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), JumpDatabase::class.java,
        ).build()
        repository = AthleteRepository(database.athleteDao())
        selectedAthleteStore = SelectedAthleteStore(ApplicationProvider.getApplicationContext())
        runBlocking { selectedAthleteStore.clear() }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun crudOrderingArchiveRestoreAndLastUsedValidationPreserveIdentity() = runBlocking {
        val zeta = repository.create(
            "Zeta",
            notes = "kept",
            weightKg = 72.5,
            heightCm = 181.0,
            now = 1L,
        )
        val alpha = repository.create("Alpha", now = 2L)
        assertEquals(DEFAULT_ATHLETE_AVATAR_KEY, repository.byId(alpha)?.avatarKey)
        assertEquals(listOf("Alpha", "Zeta"), repository.activeOnce().map { it.displayName })

        val original = repository.byId(zeta)!!
        assertEquals("kept", original.notes)
        assertEquals(72.5, original.weightKg!!, 0.0)
        assertEquals(181.0, original.heightCm!!, 0.0)
        repository.update(
            original.copy(
                displayName = "Beta",
                avatarKey = "avatar_img02_r1_c1",
                weightKg = 73.25,
            ),
        )
        assertEquals("Beta", repository.byId(zeta)!!.displayName)
        assertEquals("avatar_img02_r1_c1", repository.byId(zeta)!!.avatarKey)
        assertEquals(73.25, repository.byId(zeta)!!.weightKg!!, 0.0)
        selectedAthleteStore.select(repository, zeta)
        assertEquals(zeta, selectedAthleteStore.resolve(repository))

        repository.archive(zeta)
        assertNull(repository.activeById(zeta))
        assertEquals(alpha, selectedAthleteStore.resolve(repository))
        assertEquals(1, repository.active().first().size)

        repository.unarchive(zeta)
        assertEquals(listOf("Alpha", "Beta"), repository.activeOnce().map { it.displayName })
        assertEquals(alpha, selectedAthleteStore.selectedAthleteId.value)
    }

    @Test
    fun emptyInstallationBootstrapsExactlyOneDefaultAthlete() = runBlocking {
        val created = repository.bootstrapIfInstallationEmpty()!!
        assertEquals("Atleta 1", created.displayName)
        assertEquals(DEFAULT_ATHLETE_AVATAR_KEY, created.avatarKey)
        assertEquals(1, repository.allOnce().size)
        assertNull(repository.bootstrapIfInstallationEmpty())
        assertEquals(1, repository.allOnce().size)
    }

    @Test
    fun existingInitialsAreNotReplacedByTheNewDefault() = runBlocking {
        val id = repository.create("Legacy", avatarKey = null, now = 1L)
        assertNull(repository.bootstrapIfInstallationEmpty())
        assertNull(repository.byId(id)?.avatarKey)
    }

    @Test
    fun bootstrapAssociatesExactAthleteAsDefaultWithoutReplacingValidPreference() = runBlocking {
        val created = repository.bootstrapIfInstallationEmpty()!!
        assertTrue(selectedAthleteStore.associateBootstrapIfNeeded(repository, created.id))
        assertEquals(created.id, selectedAthleteStore.selectedAthleteId.value)

        selectedAthleteStore.clear()
        val preferred = repository.create("Preferred", now = 2L)
        assertTrue(selectedAthleteStore.select(repository, preferred))
        assertTrue(selectedAthleteStore.associateBootstrapIfNeeded(repository, created.id))
        assertEquals(preferred, selectedAthleteStore.selectedAthleteId.value)
    }

    @Test
    fun archivedRosterDoesNotCreateBootstrapAthlete() = runBlocking {
        val existing = repository.create("Legacy", now = 1L)
        repository.archive(existing)
        assertNull(repository.bootstrapIfInstallationEmpty())
        assertEquals(listOf("Legacy"), repository.allOnce().map { it.displayName })
    }

    @Test
    fun legacyMeasurementsDoNotTriggerSyntheticOwnershipBootstrap() = runBlocking {
        database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = "legacy-without-athlete",
                athleteId = null,
                protocolId = "SJ",
                dateTime = 1L,
                primaryMetricKey = "HEIGHT_CM",
                primaryMetricValue = 20.0,
                primaryMetricUnit = "CENTIMETER",
            ),
        )

        assertNull(repository.bootstrapIfInstallationEmpty())
        assertEquals(emptyList<AthleteEntity>(), repository.allOnce())
    }

    @Test
    fun currentLastUsedPreferenceMigratesToCanonicalSelectionAndWinsOverOlderValue() = runBlocking {
        val olderSelected = repository.create("Older selection", now = 1L)
        val mostRecentlySelected = repository.create("Most recent selection", now = 2L)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("openjump_settings", android.content.Context.MODE_PRIVATE)
        preferences.edit()
            .putLong("selected_athlete_id", olderSelected)
            .putLong("last_used_athlete_id", mostRecentlySelected)
            .commit()

        val migrated = SelectedAthleteStore(context)
        assertEquals(mostRecentlySelected, migrated.selectedAthleteId.value)
        assertEquals(mostRecentlySelected, migrated.resolve(repository))
        assertEquals(mostRecentlySelected, preferences.getLong("selected_athlete_id", 0L))
        assertFalse(preferences.contains("last_used_athlete_id"))
        migrated.clear()
    }

    @Test
    fun activeSelectionSurvivesFreshStoreInstance() = runBlocking {
        val athleteId = repository.create("Selected", now = 1L)
        assertTrue(selectedAthleteStore.select(repository, athleteId))

        val freshStore = SelectedAthleteStore(ApplicationProvider.getApplicationContext())
        assertEquals(athleteId, freshStore.selectedAthleteId.value)
        assertEquals(athleteId, freshStore.resolve(repository))
        freshStore.clear()
    }

    @Test
    fun staleSelectionAfterRestoreFallsBackToSoleActiveAthlete() = runBlocking {
        val restoredAlpha = repository.create("Restored Alpha", now = 1L)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("openjump_settings", android.content.Context.MODE_PRIVATE)
        preferences.edit().putLong("selected_athlete_id", Long.MAX_VALUE).commit()

        val store = SelectedAthleteStore(context)
        assertEquals(restoredAlpha, store.resolve(repository))
        assertEquals(restoredAlpha, store.selectedAthleteId.value)
        assertEquals(restoredAlpha, preferences.getLong("selected_athlete_id", 0L))
    }

    @Test
    fun permanentDeleteRemovesOwnedDataAndProtectsLastActiveAthlete() = runBlocking {
        val removable = repository.create("Removable", now = 1L)
        val survivor = repository.create("Survivor", now = 2L)
        val groupId = database.groupDao().insert(GroupEntity(name = "Team", createdAt = 1L, updatedAt = 1L))
        database.groupDao().insertMemberships(listOf(AthleteGroupCrossRef(groupId, removable)))
        val testingId = database.testingDao().createSnapshot(
            TestingSessionEntity(
                family = TestingFamily.JUMP.name,
                protocolId = "SJ",
                targetAttempts = 1,
                currentOrdinal = 0,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            listOf(
                TestingParticipantEntity(0L, removable, ordinal = 0),
                TestingParticipantEntity(0L, survivor, ordinal = 1),
            ),
        )
        val singleAthleteTestingId = database.testingDao().createSnapshot(
            TestingSessionEntity(
                family = TestingFamily.ENCODER.name,
                exercise = "BENCH_PRESS",
                loadKg = 80.0,
                plateDiameterCm = 45.0,
                targetAttempts = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            listOf(TestingParticipantEntity(0L, removable, ordinal = 0)),
        )
        val assessmentId = database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = "delete-athlete-jump",
                athleteId = removable,
                testingSessionId = testingId,
                protocolId = "SJ",
                dateTime = 2L,
                primaryMetricKey = "HEIGHT_CM",
                primaryMetricValue = 30.0,
                primaryMetricUnit = "CENTIMETER",
            ),
        )
        val encoderId = database.encoderDao().insertSession(testEncoderSession(removable))

        assertEquals(AthletePermanentDeleteResult.DELETED, repository.permanentlyDelete(removable))
        assertNull(repository.byId(removable))
        assertNull(database.jumpDao().assessmentWithAttempts(assessmentId))
        assertNull(database.encoderDao().sessionWithData(encoderId))
        assertTrue(database.groupDao().detail(groupId)!!.members.isEmpty())
        val testing = database.testingDao().detail(testingId)!!
        assertEquals(listOf(survivor), testing.participants.map { it.athleteId })
        assertEquals(1, testing.session.currentOrdinal)
        assertNull(database.testingDao().detail(singleAthleteTestingId))

        assertEquals(AthletePermanentDeleteResult.LAST_ACTIVE_ATHLETE, repository.permanentlyDelete(survivor))
        assertEquals("Survivor", repository.byId(survivor)!!.displayName)
    }

    @Test
    fun multipleActiveAthletesWithoutSelectionRequireExplicitChoice() = runBlocking {
        repository.create("Zulu", now = 1L)
        repository.create("Alpha", now = 2L)
        repository.create("Mike", now = 3L)

        assertNull(selectedAthleteStore.resolve(repository))
        assertNull(selectedAthleteStore.selectedAthleteId.value)
    }

    @Test
    fun deletingSelectedAthleteFallsBackToSoleRemainingActiveAthlete() = runBlocking {
        val selected = repository.create("Zulu", now = 1L)
        val firstRemaining = repository.create("Alpha", now = 2L)
        assertTrue(selectedAthleteStore.select(repository, selected))

        assertEquals(AthletePermanentDeleteResult.DELETED, repository.permanentlyDelete(selected))
        assertEquals(firstRemaining, selectedAthleteStore.resolve(repository))
    }

    /**
     * W3.2: el borrado definitivo de un atleta purga su familia F-V-P COMPLETA (raiz + condiciones +
     * intentos por CASCADE) sin tocar la de otro atleta ni sus saltos, y `persistedRootCount`
     * (atletas + assessments + encoder_sessions + fvp_assessments) refleja el cambio. Sin cambios de
     * esquema: la cascada y el borrado por atleta ya existen y aqui se cubren con datos reales.
     */
    @Test
    fun permanentDeletePurgesTheFvpFamilyOfTheRemovedAthleteOnly() = runBlocking {
        val removable = repository.create("Removable FVP", now = 1L)
        val survivor = repository.create("Survivor FVP", now = 2L)
        val fvp = FvpRepository(database.fvpDao())
        val removedFvpId = fvp.save(fvpDraft(sessionKey = "delete-athlete-fvp", athleteId = removable))
        val keptFvpId = fvp.save(fvpDraft(sessionKey = "delete-athlete-fvp-kept", athleteId = survivor))
        val keptJumpId = database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = "delete-athlete-kept-jump",
                athleteId = survivor,
                protocolId = "SJ",
                dateTime = 2L,
                primaryMetricKey = "HEIGHT_CM",
                primaryMetricValue = 30.0,
                primaryMetricUnit = "CENTIMETER",
            ),
        )
        val removedConditionIds = database.fvpDao().assessmentWithData(removedFvpId)!!
            .conditions.map { it.condition.id }
        assertEquals(2, removedConditionIds.size)
        assertEquals(2, countRows("fvp_assessments"))
        assertEquals(4, countRows("fvp_conditions"))
        assertEquals(4, countRows("fvp_attempts"))
        // 2 atletas + 1 salto + 2 series F-V-P.
        assertEquals(5, repository.persistedRootCount())

        assertEquals(AthletePermanentDeleteResult.DELETED, repository.permanentlyDelete(removable))

        assertNull(repository.byId(removable))
        assertNull(fvp.byId(removedFvpId))
        assertTrue(database.fvpDao().forAthlete(removable).first().isEmpty())
        // La familia F-V-P del atleta borrado desaparece entera: no queda raiz, ni condicion, ni
        // intento apuntando a una condicion suya.
        assertEquals(0, countRowsForIds("fvp_conditions", "assessmentId", listOf(removedFvpId)))
        assertEquals(0, countRowsForIds("fvp_attempts", "conditionId", removedConditionIds))
        assertEquals(1, countRows("fvp_assessments"))
        assertEquals(2, countRows("fvp_conditions"))
        assertEquals(2, countRows("fvp_attempts"))
        // El otro atleta conserva intactas su serie F-V-P y su salto.
        val kept = requireNotNull(fvp.byId(keptFvpId))
        assertEquals(survivor, kept.assessment.athleteId)
        assertEquals(2, kept.conditions.size)
        assertEquals(2, kept.attempts.size)
        // El otro atleta tiene UNA raiz F-V-P (la suya) y su salto sigue existiendo.
        assertEquals(1, database.fvpDao().forAthlete(survivor).first().size)
        assertNotNull(database.jumpDao().assessmentWithAttempts(keptJumpId))
        // 1 atleta + 1 salto + 1 serie F-V-P.
        assertEquals(3, repository.persistedRootCount())
    }

    private fun countRows(table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** Filas de [table] cuya columna [column] apunta a alguno de [ids] (familia F-V-P borrada). */
    private fun countRowsForIds(table: String, column: String, ids: List<Long>): Int =
        if (ids.isEmpty()) {
            0
        } else {
            database.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM $table WHERE $column IN (${ids.joinToString(",")})")
                .use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
        }

    /**
     * Serie F-V-P persistible con 2 condiciones y 2 intentos por la via real (`FvpRepository.save`
     * -> motor): 2 condiciones es el minimo calculable (se marca como diagnostica).
     */
    private fun fvpDraft(sessionKey: String, athleteId: Long): FvpDraft = FvpDraft(
        sessionKey = sessionKey,
        athleteId = athleteId,
        protocol = "CMJ",
        dateTime = 1_000L,
        bodyMassKg = 70.0,
        bodyMassSource = "PROFILE",
        pushOffMode = "COMMON",
        pushOffM = 0.40,
        pushOffSource = "MEASURED",
        sourceFps = 240.0,
        methodVersion = FVP_METHOD_VERSION,
        notes = null,
        conditions = listOf(
            FvpConditionDraft(
                conditionKey = "0kg",
                addedLoadKg = 0.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.34, method = "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
                ),
            ),
            FvpConditionDraft(
                conditionKey = "20kg",
                addedLoadKg = 20.0,
                attempts = listOf(
                    FvpAttemptDraft(heightM = 0.26, method = "FLIGHT_TIME_VIDEO", sourceFps = 240.0),
                ),
            ),
        ),
    )

    private fun testEncoderSession(athleteId: Long) = EncoderSessionEntity(
        sessionKey = "delete-athlete-encoder",
        athleteId = athleteId,
        exercise = "BENCH_PRESS",
        loadKg = 80.0,
        dateTime = 3L,
        source = "IMPORTED",
        videoUri = null,
        observedFps = 120.0,
        nominalFps = 120.0,
        captureFps = null,
        timingMode = "NORMAL",
        slowMotionFactor = 1.0,
        calibrationFrameIndex = 0,
        calibrationAX = 0.0,
        calibrationAY = 0.0,
        calibrationBX = 1.0,
        calibrationBY = 0.0,
        referenceLengthM = 1.0,
        metersPerPixel = 1.0,
        analysisVersion = 1,
        minimumVelocityMps = 0.1,
        noiseMultiplier = 1.0,
        exitRatio = 0.5,
        minimumDirectionHoldUs = 1L,
        stationaryDurationUs = 1L,
        pauseDurationUs = 1L,
        minimumPhaseDurationUs = 1L,
        minimumPhaseDisplacementM = 0.1,
        romOutlierFraction = 0.2,
        minimumMedianConfidence = 0.5,
        maximumUncertainFraction = 0.5,
        smoothingWindowSamples = 5,
        velocityEnterMps = null,
        velocityExitMps = null,
        totalRepetitions = 0,
        validRepetitions = 0,
        bestMcv = null,
        setVelocityLoss = null,
    )
}
