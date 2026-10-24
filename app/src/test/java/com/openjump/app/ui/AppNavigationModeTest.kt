package com.openjump.app.ui

import com.openjump.app.R
import com.openjump.app.settings.ExperienceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationModeTest {
    @Test
    fun bottomNavigationKeepsTeamAsStablePeopleDestination() {
        assertEquals(listOf("home", "history", "people", "settings"), topLevelRouteOrder())
    }

    @Test
    fun athleteEditUsesDedicatedStableRoute() {
        assertEquals("athlete/42/edit", athleteEditRoute(42L))
    }

    @Test
    fun peopleLabelFollowsExperienceMode() {
        assertEquals(R.string.nav_people_personal, peopleLabel(ExperienceMode.PERSONAL))
        assertEquals(R.string.nav_people_coach, peopleLabel(ExperienceMode.COACH))
    }

    @Test
    fun comparisonUsesSelectionAndResultsChildRoutesWithoutTopLevelEntry() {
        assertEquals(listOf("comparison", "comparison/results"), comparisonRoutes())
        assertEquals("comparison/results", comparisonResultsRoute())
        assertFalse(topLevelRouteOrder().contains("comparison"))
    }

    @Test
    fun settingsSectionKeepsBottomBarVisible() {
        listOf(
            "settings",
            "settings/about",
            "privacy",
            "settings/about/license",
            "settings/units",
        ).forEach { route ->
            assertTrue(route, isSettingsSectionRoute(route))
            assertTrue(route, isBottomBarVisible(route))
        }
    }

    @Test
    fun settingsSectionHelperRejectsUnrelatedAndPrefixRoutes() {
        listOf(
            null,
            "",
            "home",
            "history",
            "people",
            "jumps",
            "settings/about2",
            "settings/units-extra",
            "settings2",
            "privacy-policy",
            "labs/archive",
            "encoder/setup",
            "onboarding",
            "encoder/methodology",
            "comparison",
            "testing",
        ).forEach { route ->
            assertFalse(route, isSettingsSectionRoute(route))
        }
    }

    @Test
    fun athleteRecordsKeepsBottomBarWithoutExposingOtherAthleteSubscreens() {
        assertTrue(isBottomBarVisible("athlete/{athleteId}/records"))
        assertTrue(isBottomBarVisible("athlete/{athleteId}/progress"))
        assertFalse(isBottomBarVisible("athlete/{athleteId}"))
        assertFalse(isBottomBarVisible("athlete/{athleteId}/edit"))
        assertFalse(topLevelRouteOrder().contains("athlete/{athleteId}/records"))
    }

    @Test
    fun fvpFlowRoutesStayOutOfBottomBarAndSettingsSection() {
        assertEquals(
            listOf("fvp/setup", "fvp/conditions", "fvp/result", "fvp/result/{assessmentId}", "fvp/history"),
            fvpFlowRoutes(),
        )
        assertEquals("fvp/result/42", fvpSavedResultRoute(42L))
        fvpFlowRoutes().forEach { route ->
            assertFalse(route, isBottomBarVisible(route))
            assertFalse(route, isSettingsSectionRoute(route))
            assertFalse(route, topLevelRouteOrder().contains(route))
        }
    }

    @Test
    fun fvpSetupNavigationIsSingleTopSafe() {
        // Politica real usada por AppNav (`onFvpSelected`), verificable sin componer un NavHost.
        val target = fvpSetupNavTarget()

        assertEquals(FVP_SETUP_ROUTE, target.route)
        assertTrue("un doble toque en Home no debe apilar dos setups", target.launchSingleTop)
        assertNull("Home -> setup no poda la pila", target.popUpToRoute)
        assertFalse(target.popUpToInclusive)
    }

    @Test
    fun fvpSavedResultNavigationAlwaysPopsToHomeAndNeverToSetup() {
        // Invariante A-P0-1: al guardar (vivo o tras editar) la pila queda Home -> resultado
        // guardado. Podar hacia `fvp/setup` se ignora cuando se llega por "Editar", que es
        // justamente el defecto que este contrato evita.
        val target = fvpSavedResultNavTarget(42L)

        assertEquals("fvp/result/42", target.route)
        assertEquals(HOME_ROUTE, target.popUpToRoute)
        assertFalse("Home es la raiz del flujo: no debe eliminarse", target.popUpToInclusive)
        assertTrue(target.launchSingleTop)
        assertFalse(
            "el pop no puede depender de fvp/setup, ausente en la pila al editar",
            target.popUpToRoute == FVP_SETUP_ROUTE,
        )
    }

    @Test
    fun bottomBarVisibilityPreservesExistingTopLevelBehavior() {
        listOf("home", "history", "people", "jumps", "settings").forEach { route ->
            assertTrue(route, isBottomBarVisible(route))
        }
        listOf(
            null,
            "labs/archive",
            "encoder/setup",
            "encoder/result",
            "comparison",
            "testing",
            "measurement/1",
        ).forEach { route ->
            assertFalse(route, isBottomBarVisible(route))
        }
    }
}
