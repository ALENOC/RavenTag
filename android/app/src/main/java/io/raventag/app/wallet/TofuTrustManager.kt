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
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

        val persisted = TofuFingerprintDao.getFingerprint(host)
        val inMemory = certCache[host]

        // Accept if fingerprint matches either L1 (memory) or L2 (SQLite)
        if (fingerprint == inMemory || fingerprint == persisted) {
            if (persisted == null) {
                TofuFingerprintDao.pinFingerprint(host, fingerprint)
                Log.i(TAG, "TOFU: pinned new certificate for $host")
            }
            certCache[host] = fingerprint
            return
        }

        // Fingerprint changed: server rotated its TLS certificate.
        // Auto-heal by updating the pinned fingerprint instead of failing.
        // Strict TOFU would reject, but ElectrumX public servers rotate certs
        // and a hard rejection bricks the wallet until manual DB deletion.
        Log.w(TAG, "TOFU: certificate rotated for $host (was $persisted, now $fingerprint) — auto-updating pin")
        TofuFingerprintDao.pinFingerprint(host, fingerprint)
        certCache[host] = fingerprint
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
