package com.openjump.app.ui.result

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.ui.HOME_ROUTE
import com.openjump.app.video.AppSession
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F-004 real navigation proof (compile-only on this KVM-blocked station):
 * drives the production [navigateBilateralFinalSave] router inside a real
 * `NavHost` + `NavHostController` (no navigation-testing lib) with a real Room
 * id, then asserts reset-before-navigate ordering, Home-below-detail and
 * Back-lands-Home. `tools/validate.sh android-test-compile` covers it here;
 * device run is KVM-blocked.
 */
@RunWith(AndroidJUnit4::class)
class BilateralFinalSaveHostTest {

    @get:Rule val compose = createComposeRule()

    private fun comparison(sessionKey: String, athleteId: Long): BilateralComparison {
        fun snapshot(side: MeasurementSide, ordinal: Int): BilateralAttemptSnapshot {
            val draft = MeasurementDraft(
                sessionKey = "$sessionKey-${side.name}-$ordinal",
                protocolId = ProtocolId.UNILATERAL,
                athleteId = athleteId,
                setup = ProtocolSetup(side = side),
            )
            val result = ProtocolResult(
                ProtocolId.UNILATERAL,
                MetricValue(MetricKey.HEIGHT_CM, 30.0 + ordinal, MetricUnit.CENTIMETER),
                emptyList(),
            )
            return BilateralAttemptSnapshot(side, ordinal, draft, result, detectedFps = 120)
        }
        return BilateralComparison(
            sessionKey = sessionKey,
            athleteId = athleteId,
            targetAttempts = 1,
            attempts = listOf(snapshot(MeasurementSide.LEFT, 1), snapshot(MeasurementSide.RIGHT, 1)),
        )
    }

    @Test
    fun productionRouterResetsSessionAndLandsHomeBelowPersistedDetail() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
        val realId: Long
        val athleteId: Long
        try {
            athleteId = runBlocking {
                database.athleteDao().insert(
                    AthleteEntity(displayName = "Bilateral host", createdAt = 1L, updatedAt = 1L),
                )
            }
            realId = runBlocking {
                JumpRepository(database.jumpDao()).saveBilateral(
                    comparison("host-${UUID.randomUUID()}", athleteId),
                )
            }
        } finally {
            database.close()
        }
        assertTrue("Room must issue a real persisted id", realId > 0L)

        lateinit var nav: NavHostController
        compose.setContent {
            val controller = rememberNavController()
            SideEffect { nav = controller }
            NavHost(navController = controller, startDestination = HOME_ROUTE) {
                composable(HOME_ROUTE) { Text("home") }
                composable("jumps") { Text("jumps") }
                composable("protocol/ASYMMETRY") { Text("protocol") }
                composable("selector") { Text("selector") }
                composable("result") { Text("result") }
                composable("measurement/{assessmentId}") { Text("detail") }
            }
        }
        compose.waitForIdle()
        // Normal entry stack before the final save: home -> jumps -> protocol -> selector -> result.
        compose.runOnUiThread {
            nav.navigate("jumps")
            nav.navigate("protocol/ASYMMETRY")
            nav.navigate("selector")
            nav.navigate("result")
        }
        compose.waitForIdle()

        // Seed a live bilateral session so reset() has observable work to do.
        AppSession.beginBilateral(targetAttempts = 1, athleteId = athleteId)
        assertTrue(AppSession.bilateral.value != null)

        // Production router: the same call AppNav's onBilateralSaved makes.
        compose.runOnUiThread { nav.navigateBilateralFinalSave(realId) }
        compose.waitForIdle()

        // Reset ran before navigating: ephemeral session is gone.
        assertNull(AppSession.bilateral.value)
        // Real id threaded through to the detail route.
        assertEquals("measurement/$realId", nav.currentBackStackEntry?.destination?.route)
        // Home kept below the detail; stale jumps/protocol/selector/result popped.
        // backQueue is private in navigation 2.8.9: prove via entry lookup + previous entry.
        assertEquals(HOME_ROUTE, nav.previousBackStackEntry?.destination?.route)
        runCatching { nav.getBackStackEntry("jumps") }.onSuccess {
            throw AssertionError("stale jumps must be popped")
        }
        runCatching { nav.getBackStackEntry("protocol/ASYMMETRY") }.onSuccess {
            throw AssertionError("stale protocol must be popped")
        }
        runCatching { nav.getBackStackEntry("selector") }.onSuccess {
            throw AssertionError("stale selector must be popped")
        }
        runCatching { nav.getBackStackEntry("result") }.onSuccess {
            throw AssertionError("stale result must be popped")
        }
        // Home entry survives (throws if missing).
        nav.getBackStackEntry(HOME_ROUTE)
        // Back from the saved detail lands Home (not protocol/setup, not ExpiredSession).
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(HOME_ROUTE, nav.currentBackStackEntry?.destination?.route)

        AppSession.reset()
    }
}
