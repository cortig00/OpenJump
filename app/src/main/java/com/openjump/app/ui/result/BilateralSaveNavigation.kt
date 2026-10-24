package com.openjump.app.ui.result

import com.openjump.app.ui.HOME_ROUTE

/**
 * F-004 navigation contract for bilateral saves.
 *
 * - Per-attempt continuation carries no Room id yet: the bilateral session is
 *   still open and the flow returns to [BILATERAL_SELECTOR_ROUTE] with that
 *   ephemeral state intact. Records-only saves never open a saved detail.
 * - The final successful save carries the real persisted Room id and lands on
 *   `measurement/{id}` (Room-backed, safe after reset). Jumps/protocol/selector/result
 *   are popped so Back from the detail lands on Home. `AppSession.reset()`
 *   runs before navigating.
 * - Ordinary unilateral / series / collective Testing flows are untouched by
 *   this contract; ASYMMETRY stays unavailable inside Testing.
 */
const val BILATERAL_SELECTOR_ROUTE = "selector"

/**
 * Target for a records-only save while a bilateral session is open
 * (per-attempt continuation, CONTINUE): back to the selector with the
 * ephemeral session intact. Returns null when no bilateral session is open,
 * leaving ordinary unilateral / series / collective Testing routing untouched.
 */
fun bilateralContinuationTarget(bilateralActive: Boolean): String? =
    if (bilateralActive) BILATERAL_SELECTOR_ROUTE else null

/**
 * Back-stack target for the persisted bilateral detail: pops jumps/protocol/
 * selector/result and keeps Home below the detail ([popUpToInclusive] is false
 * so Back lands on Home). Uses the shared [HOME_ROUTE] start destination,
 * which always exists, instead of a fragile selector entry.
 */
data class BilateralFinalSaveNavigation(
    val detailRoute: String,
    val popUpToRoute: String = HOME_ROUTE,
    val popUpToInclusive: Boolean = false,
)

/**
 * Builds the navigation for a final bilateral save. Rejects non-positive ids
 * so a failed or missing Room commit can never produce a `measurement/0`
 * destination; failure keeps the session and the retry UI instead.
 */
fun bilateralFinalSaveNavigation(assessmentId: Long): BilateralFinalSaveNavigation {
    require(assessmentId > 0L) { "Bilateral detail requires a persisted assessment id." }
    return BilateralFinalSaveNavigation(detailRoute = "measurement/$assessmentId")
}
