/**
 * AdminKeyStorage.kt
 *
 * Secure storage for the admin API key using AndroidX Security Crypto.
 *
 * This class provides encrypted storage for the admin key using EncryptedSharedPreferences
 * with AES-256-GCM encryption via the Android Keystore. This prevents extraction of the
 * admin key from the compiled APK (unlike BuildConfig, which is extractable via static
 * analysis tools like strings or JADX).
 *
 * The admin key is persisted across app restarts in encrypted form, and can be
 * configured via the Settings screen by the user.
 *
 * @property context Application context used to create EncryptedSharedPreferences.
 */
package io.raventag.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage wrapper for the admin API key.
 *
 * Uses AES-256-GCM encryption via Android Keystore when available. The key is never
 * stored in plain text in the APK (BuildConfig) or in shared preferences.
 */
class AdminKeyStorage(context: Context) {

    /**
     * Master key for encrypted preferences.
     * Uses AES-256-GCM encryption scheme for maximum security.
     */
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    /**
     * Encrypted shared preferences instance.
     * All values stored here are encrypted/decrypted transparently by AndroidX Security.
     */
    private val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        "admin_key_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private companion object {
        /** Key name for storing the admin key in preferences. */
        private const val KEY_ADMIN_KEY = "admin_key"
    }

    /**
     * Retrieve the stored admin key.
     *
     * @return The admin key as a string, or null if not configured.
     */
    fun getAdminKey(): String? {
        return sharedPrefs.getString(KEY_ADMIN_KEY, null)
    }

    /**
     * Store the admin key in encrypted storage.
     *
     * @param key The admin key to store.
     */
    fun setAdminKey(key: String) {
        sharedPrefs.edit().putString(KEY_ADMIN_KEY, key).apply()
    }

    /**
     * Check whether an admin key has been configured.
     *
     * @return true if an admin key is stored, false otherwise.
     */
    fun hasAdminKey(): Boolean {
        return sharedPrefs.contains(KEY_ADMIN_KEY)
    }

    /**
     * Remove the stored admin key.
     *
     * This effectively logs out the user from admin mode.
     */
    fun clearAdminKey() {
        sharedPrefs.edit().remove(KEY_ADMIN_KEY).apply()
    }
}
