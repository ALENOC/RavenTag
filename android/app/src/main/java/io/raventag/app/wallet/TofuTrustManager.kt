package io.raventag.app.wallet

import android.content.Context
import android.util.Log
import io.raventag.app.security.TofuFingerprintDao
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Strict TOFU (Trust On First Use) TrustManager for ElectrumX self-signed TLS certificates.
 *
 * Standard certificate authority validation is not used because ElectrumX servers commonly
 * use self-signed certificates. The first certificate observed for a host is persisted. Any
 * later certificate change is treated as a security failure and MUST be explicitly resolved
 * by a trusted pin-rotation mechanism; it is never accepted or re-pinned automatically.
 */
internal class TofuTrustManager(private val context: Context, private val host: String) : X509TrustManager {
    init {
        TofuFingerprintDao.init(context)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("No certificate from $host")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

        val persisted = TofuFingerprintDao.getFingerprint(host)
        val inMemory = certCache[host]

        // Persistent storage is authoritative across process restarts. If a pin exists,
        // only that exact fingerprint is accepted. Never accept a changed certificate
        // merely because an in-memory value differs or is absent.
        if (persisted != null) {
            if (fingerprint != persisted) {
                Log.e(TAG, "TOFU: certificate mismatch for $host; refusing changed fingerprint")
                throw CertificateException(
                    "Certificate mismatch for $host: expected $persisted, got $fingerprint"
                )
            }
            certCache[host] = persisted
            return
        }

        // No persistent pin yet. If this process already saw the host, require consistency
        // with that first observation before persisting it.
        if (inMemory != null && fingerprint != inMemory) {
            Log.e(TAG, "TOFU: first-use race/mismatch for $host; refusing changed fingerprint")
            throw CertificateException(
                "Certificate mismatch for $host: expected $inMemory, got $fingerprint"
            )
        }

        // First trusted observation for this host. Persist before returning success so a
        // subsequent connection cannot silently establish a different identity.
        TofuFingerprintDao.pinFingerprint(host, fingerprint)
        certCache[host] = fingerprint
        Log.i(TAG, "TOFU: pinned first certificate for $host")
    }

    companion object {
        private const val TAG = "ElectrumX"
        internal val certCache = ConcurrentHashMap<String, String>()
    }
}
