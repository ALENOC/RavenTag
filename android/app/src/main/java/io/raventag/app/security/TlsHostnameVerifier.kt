package io.raventag.app.security

import java.net.IDN
import java.security.cert.X509Certificate

/** Strict SAN-based hostname verification for system-trusted TLS rotation evidence. */
object TlsHostnameVerifier {
    private const val SAN_DNS = 2
    private const val SAN_IP = 7

    fun verify(host: String, certificate: X509Certificate): Boolean {
        val normalizedHost = normalizeDns(host) ?: return false
        val isIpv4 = normalizedHost.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
        val names = try {
            certificate.subjectAlternativeNames.orEmpty()
        } catch (_: Exception) {
            return false
        }
        val expectedType = if (isIpv4) SAN_IP else SAN_DNS
        return names.any { entry ->
            entry.size >= 2 && entry[0] == expectedType &&
                (entry[1] as? String)?.let { candidate ->
                    if (isIpv4) candidate == normalizedHost
                    else matchesDnsName(normalizedHost, candidate)
                } == true
        }
    }

    internal fun matchesDnsName(host: String, certificateName: String): Boolean {
        val normalizedHost = normalizeDns(host) ?: return false
        if ('*' !in certificateName) {
            return normalizedHost == (normalizeDns(certificateName) ?: return false)
        }
        if (!certificateName.startsWith("*.") || certificateName.indexOf('*', 1) >= 0) return false
        val normalizedName = "*." +
            (normalizeDns(certificateName.substring(2)) ?: return false)
        val suffix = normalizedName.substring(1)
        return normalizedHost.endsWith(suffix) &&
            normalizedHost.count { it == '.' } == normalizedName.count { it == '.' }
    }

    private fun normalizeDns(value: String): String? = try {
        IDN.toASCII(value.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
            .takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null
    }
}
