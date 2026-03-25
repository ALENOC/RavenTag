package io.raventag.app.ui.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * Composition local that provides the active [AppStrings] instance to the entire composable tree.
 *
 * All composables that need localized strings read them via:
 *
 *     val s = LocalStrings.current
 *
 * The default value is [stringsEn] (English), which is used in preview environments and as a
 * safe fallback if [CompositionLocalProvider] has not been called by an ancestor composable.
 *
 * The active language is swapped at two points in the app:
 *   - [OnboardingScreen]: hot-swaps strings while the user picks a language so the UI
 *     updates in real time without restarting the Activity.
 *   - [MainActivity.setContent]: wraps [RavenTagApp] with the language stored in
 *     SharedPreferences so the correct strings are provided for the entire session.
 *
 * Because [compositionLocalOf] uses reference equality to detect changes, replacing the
 * [AppStrings] reference (rather than mutating fields in place) is necessary to trigger
 * recomposition. The [appStringsFor] factory in AppStrings.kt returns a new instance on
 * each call, which satisfies this requirement.
 */
val LocalStrings = compositionLocalOf<AppStrings> { stringsEn }
