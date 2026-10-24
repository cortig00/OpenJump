package com.openjump.app.ui.result

import androidx.navigation.NavHostController
import com.openjump.app.video.AppSession

/**
 * F-004 production final-save router: validates the persisted Room id,
 * clears the ephemeral bilateral session only after the commit, then lands on
 * the Room-backed `measurement/{id}` detail with Home below it.
 *
 * Called from `AppNav`'s `onBilateralSaved` and from the Compose host test so
 * both prove the same wiring (real id threading, reset-before-navigate order,
 * Home-below-detail back stack).
 */
fun NavHostController.navigateBilateralFinalSave(assessmentId: Long) {
    val destination = bilateralFinalSaveNavigation(assessmentId)
    AppSession.reset()
    navigate(destination.detailRoute) {
        popUpTo(destination.popUpToRoute) { inclusive = destination.popUpToInclusive }
    }
}
