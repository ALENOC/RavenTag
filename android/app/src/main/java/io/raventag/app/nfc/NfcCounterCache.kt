/**
 * NfcCounterCache.kt
 *
 * Client-side defense layer against NFC tap URL replay attacks.
 *
 * The NTAG 424 DNA chip embeds a 3-byte monotonically incrementing scan counter
 * (SDMReadCtr) inside the AES-encrypted PICCData field of every SUN URL.
 * The backend enforces strict counter monotonicity server-side, but when the
 * device is offline or the backend is temporarily unreachable, an attacker could
 * replay a captured SUN URL indefinitely.
 *
 * This cache stores the last verified counter value per tag (keyed by nfc_pub_id,
 * the SHA-256 hash of UID || brand_salt). Any new scan that presents a counter
 * not strictly greater than the cached value is rejected immediately, before
 * the backend is even contacted.
 *
 * Security notes:
 *  - This is defense-in-depth only; the server-side check remains the primary guard.
 *  - The cache is per-device and per-app installation; it does not synchronize
 *    across devices or survive an app reinstall.
 *  - Values are stored in EncryptedSharedPreferences (AES-256-GCM) to prevent
 *    an attacker from reading scan frequency or resetting the counter by editing
 *    the shared preferences file directly (without root).
 *  - If the encryption backend is unavailable (e.g. old device), the cache falls
 *    back to plain SharedPreferences. The replay protection still works, but the
 *    counter values are stored in cleartext.
 */
package io.raventag.app.nfc

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the last-seen NFC scan counter per tag to enable client-side
 * replay detection in offline scenarios.
 *
 * @param context Application context, used to access SharedPreferences.
 */
class NfcCounterCache(context: Context) {

    /**
     * The underlying preferences store. Tries EncryptedSharedPreferences first
     * (AES-256-SIV for keys, AES-256-GCM for values). Falls back to plain
     * SharedPreferences if hardware encryption is unavailable.
     */
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Throwable) {
        // Fallback to plain prefs if encryption unavailable
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns true if [counter] is strictly greater than the last cached value for [nfcPubId].
     * A pub_id with no cached entry is always considered fresh.
     *
     * @param nfcPubId  The tag's public identifier: SHA-256(uid || brand_salt) as hex string.
     * @param counter   The scan counter value decoded from the current SUN URL.
     */
    fun isCounterFresh(nfcPubId: String, counter: Int): Boolean {
        val last = prefs.getInt(nfcPubId, NO_ENTRY)
        return counter > last
    }

    /**
     * Stores [counter] as the latest seen value for [nfcPubId].
     * Call only after a scan has been fully verified as authentic (SUN MAC verified
     * and blockchain check passed) to avoid caching values from invalid scans.
     *
     * @param nfcPubId  The tag's public identifier.
     * @param counter   The verified scan counter to persist.
     */
    fun updateCounter(nfcPubId: String, counter: Int) {
        prefs.edit().putInt(nfcPubId, counter).apply()
    }

    companion object {
        /** SharedPreferences file name for the counter cache. */
        private const val PREFS_NAME = "nfc_counter_cache"
        /**
         * Sentinel value meaning no counter has been cached for this nfc_pub_id yet.
         * Any real counter (>= 0) is strictly greater, so first-time scans always pass.
         */
        private const val NO_ENTRY = -1
    }
}
