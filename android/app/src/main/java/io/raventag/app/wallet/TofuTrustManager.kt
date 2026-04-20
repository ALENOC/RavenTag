package io.raventag.app.wallet

import android.content.Context
import android.util.Log
import io.raventag.app.security.TofuFingerprintDao
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ConcurrentHashMap

/**
 * TOFU (Trust On First Use) TrustManager for ElectrumX self-signed TLS certificates.
 *
 * Standard certificate authority validation is not used because ElectrumX servers
 * commonly use self-signed certificates. TOFU provides a practical security model:
 *
 * - First connection to a host: the server's SHA-256 fingerprint is computed from
 *   the raw DER-encoded certificate bytes and stored in both the in-process [certCache]
 *   and the persistent SQLite database via [TofuFingerprintDao]. The connection is allowed.
 * - Subsequent connections to the same host: the fingerprint is verified against
 *   the SQLite-persisted value first, then against the in-memory cache. If it differs
 *   from either, the connection is rejected with an exception to protect against
 *   man-in-the-middle attacks.
 *
 * Certificate fingerprints are persisted in SQLite database (L2 cache) and survive app restarts.
 * Dual-layer cache: in-memory ConcurrentHashMap (L1, fast access) + SQLite (L2, persistent).
 *
 * @param context Application context for SQLite database access.
 * @param host Hostname of the ElectrumX server, used as the cache key.
 */
internal class TofuTrustManager(private val context: Context, private val host: String) : X509TrustManager {
    init {
        TofuFingerprintDao.init(context)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw Exception("No certificate from $host")
        // Compute SHA-256 fingerprint of the raw DER-encoded certificate
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

        // Check SQLite-persisted fingerprint first (L2: persistent TOFU)
        val persisted = TofuFingerprintDao.getFingerprint(host)
        if (persisted != null && persisted != fingerprint) {
            throw Exception("Certificate mismatch for $host: expected $persisted, got $fingerprint")
        }

        // Fallback to in-memory cache (L1) for first connection
        val inMemory = certCache.putIfAbsent(host, fingerprint)
        if (inMemory == fingerprint) {
            if (persisted == null) {
                Log.i(TAG, "TOFU: pinning new certificate for $host")
                TofuFingerprintDao.pinFingerprint(host, fingerprint) // Persist to L2
            }
            return // Certificate matches
        }

        if (persisted == null) {
            // First connection to this host: accept and pin to both L1 and L2
            certCache.putIfAbsent(host, fingerprint)
            TofuFingerprintDao.pinFingerprint(host, fingerprint)
            Log.i(TAG, "TOFU: pinned new certificate for $host")
            return
        }

        // Certificate differs from both L1 and L2: reject (MITM detected)
        throw Exception("Certificate mismatch for $host: expected $persisted, got $fingerprint")
    }

    companion object {
        private const val TAG = "ElectrumX"

        /**
         * TOFU certificate fingerprint cache: hostname -> SHA-256 hex string.
         * Thread-safe via ConcurrentHashMap. Scoped to the process lifetime.
         */
        internal val certCache = ConcurrentHashMap<String, String>()
    }
}
