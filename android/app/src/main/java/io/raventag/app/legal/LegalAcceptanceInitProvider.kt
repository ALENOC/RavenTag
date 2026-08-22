package io.raventag.app.legal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import io.raventag.app.BuildConfig

/**
 * Lightweight process-start initializer for legal-document versioning.
 *
 * RavenTag historically persisted only `onboarding_done`, so users who accepted an
 * older legal text would otherwise never be shown a materially updated Terms/Privacy
 * version. This provider runs before MainActivity and forces the onboarding screen
 * whenever the locally accepted legal versions are stale.
 *
 * No acceptance data is transmitted off-device. The acceptance versions are stored
 * only in the existing `raventag_app` SharedPreferences file.
 */
class LegalAcceptanceInitProvider : ContentProvider() {

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val termsCurrent = prefs.getString(KEY_TERMS_VERSION, null) == CURRENT_TERMS_VERSION
        val privacyCurrent = prefs.getString(KEY_PRIVACY_VERSION, null) == CURRENT_PRIVACY_VERSION
        val specificCurrent = !BuildConfig.IS_BRAND ||
            prefs.getString(KEY_SPECIFIC_APPROVAL_VERSION, null) == CURRENT_TERMS_VERSION

        if (termsCurrent && privacyCurrent && specificCurrent) {
            return true
        }

        // A stale or legacy acceptance must pass through the current onboarding.
        // The current OnboardingScreen already blocks completion until Terms and
        // Privacy are checked, and Brand builds additionally require the separate
        // approval of the identified B2B clauses.
        prefs.edit()
            .putBoolean(KEY_ONBOARDING_DONE, false)
            .putString(KEY_PENDING_LEGAL_VERSION, CURRENT_TERMS_VERSION)
            .apply()

        // MainActivity still owns the onboarding completion callback. Observe that
        // existing local flag so the version evidence is persisted immediately when
        // the user successfully completes the current legal screen, without adding
        // any remote telemetry or identity collection.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key != KEY_ONBOARDING_DONE || !shared.getBoolean(KEY_ONBOARDING_DONE, false)) {
                return@OnSharedPreferenceChangeListener
            }
            if (shared.getString(KEY_PENDING_LEGAL_VERSION, null) != CURRENT_TERMS_VERSION) {
                return@OnSharedPreferenceChangeListener
            }

            val editor = shared.edit()
                .putString(KEY_TERMS_VERSION, CURRENT_TERMS_VERSION)
                .putString(KEY_PRIVACY_VERSION, CURRENT_PRIVACY_VERSION)
                .remove(KEY_PENDING_LEGAL_VERSION)

            if (BuildConfig.IS_BRAND) {
                editor.putString(KEY_SPECIFIC_APPROVAL_VERSION, CURRENT_TERMS_VERSION)
            } else {
                editor.remove(KEY_SPECIFIC_APPROVAL_VERSION)
            }
            editor.apply()
        }
        prefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)

        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val PREFS_NAME = "raventag_app"

        const val CURRENT_TERMS_VERSION = "1.2"
        const val CURRENT_PRIVACY_VERSION = "1.2"

        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_TERMS_VERSION = "accepted_terms_version"
        const val KEY_PRIVACY_VERSION = "accepted_privacy_version"
        const val KEY_SPECIFIC_APPROVAL_VERSION = "accepted_specific_clauses_version"
        const val KEY_PENDING_LEGAL_VERSION = "pending_legal_acceptance_version"

        /** Persist legal evidence atomically with onboarding completion. */
        fun recordAcceptance(prefs: SharedPreferences) {
            val editor = prefs.edit()
                .putString(KEY_TERMS_VERSION, CURRENT_TERMS_VERSION)
                .putString(KEY_PRIVACY_VERSION, CURRENT_PRIVACY_VERSION)
                .remove(KEY_PENDING_LEGAL_VERSION)
                .putBoolean(KEY_ONBOARDING_DONE, true)
            if (BuildConfig.IS_BRAND) {
                editor.putString(KEY_SPECIFIC_APPROVAL_VERSION, CURRENT_TERMS_VERSION)
            } else {
                editor.remove(KEY_SPECIFIC_APPROVAL_VERSION)
            }
            check(editor.commit()) { "Unable to persist legal acceptance" }
        }
    }
}
