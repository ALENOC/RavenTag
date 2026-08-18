/**
 * NfcReader.kt
 *
 * Parses incoming Android NFC intents and extracts the SUN (Secure Unique NFC)
 * URL parameters broadcast by an NTAG 424 DNA chip.
 */
package io.raventag.app.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.util.Log

/** NFC reading utilities for RavenTag. */
object NfcReader {

    private const val TAG = "NfcReader"
    private const val VERIFY_SCHEME = "https"
    private const val VERIFY_HOST = "verify.raventag.com"
    private const val VERIFY_PATH = "/verify"
    private const val MAX_URI_PAYLOAD_BYTES = 2048
    private const val MAX_ASSET_LENGTH = 100

    data class SunParams(
        val e: String,
        val m: String,
        val asset: String? = null,
        val rawUrl: String
    )

    fun extractSunParams(intent: Intent): SunParams? {
        if (intent.action !in setOf(
                NfcAdapter.ACTION_NDEF_DISCOVERED,
                NfcAdapter.ACTION_TAG_DISCOVERED,
                NfcAdapter.ACTION_TECH_DISCOVERED
            )
        ) return null

        return when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> extractFromNdef(intent)
            NfcAdapter.ACTION_TAG_DISCOVERED -> {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                tag?.let { extractFromTagObject(it) }
            }
            else -> null
        }
    }

    private fun extractFromTagObject(tag: Tag): SunParams? {
        Log.d(TAG, "extractFromTagObject: techs=${tag.techList.joinToString()}")
        return try {
            val ndef = Ndef.get(tag) ?: return null
            ndef.connect()
            val message = try { ndef.ndefMessage } finally { runCatching { ndef.close() } }
            message?.let { extractFromNdefMessage(it) }
        } catch (e: Exception) {
            Log.w(TAG, "NDEF read from Tag object failed: ${e.message}")
            null
        }
    }

    private fun extractFromNdef(intent: Intent): SunParams? {
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (raw in rawMessages) {
            val message = raw as? NdefMessage ?: continue
            extractFromNdefMessage(message)?.let { return it }
        }
        return null
    }

    private fun extractFromNdefMessage(message: NdefMessage): SunParams? {
        for (record in message.records) {
            if (record.tnf != 0x01.toShort()) continue
            val payload = record.payload ?: continue
            // A URI record always needs at least the prefix byte. Bound input before
            // allocating a String so a hostile tag cannot trigger unbounded parsing.
            if (payload.isEmpty() || payload.size > MAX_URI_PAYLOAD_BYTES) {
                Log.w(TAG, "Rejecting malformed/oversized NDEF URI payload (${payload.size} bytes)")
                continue
            }
            val prefixCode = payload.firstOrNull() ?: continue
            val prefix = uriPrefix(prefixCode) ?: continue
            val suffix = if (payload.size > 1) String(payload, 1, payload.size - 1, Charsets.UTF_8) else ""
            val urlStr = prefix + suffix
            parseSunUrl(urlStr)?.let { return it }
        }
        return null
    }

    /**
     * Parse a canonical RavenTag SUN URL. Generic HTTPS hosts are rejected even if
     * Android dispatches an unexpected intent to this activity.
     */
    fun parseSunUrl(url: String): SunParams? {
        if (url.length > MAX_URI_PAYLOAD_BYTES) return null
        return try {
            val firstParse = android.net.Uri.parse(url)
            val fragment = firstParse.fragment
            val fixedUrl = if (fragment != null && fragment.contains("&e=") && fragment.contains("&m=")) {
                url.replaceFirst("#", "%23")
            } else url

            val uri = android.net.Uri.parse(fixedUrl)
            if (!uri.scheme.equals(VERIFY_SCHEME, ignoreCase = true)) return null
            if (!uri.host.equals(VERIFY_HOST, ignoreCase = true)) return null
            if (uri.path != VERIFY_PATH && uri.path != "/") return null
            if (uri.userInfo != null) return null
            if (uri.port != -1 && uri.port != 443) return null

            val e = uri.getQueryParameter("e") ?: return null
            val m = uri.getQueryParameter("m") ?: return null
            val asset = uri.getQueryParameter("asset")

            if (!e.matches(Regex("[0-9a-fA-F]{32}"))) return null
            if (!m.matches(Regex("[0-9a-fA-F]{16}"))) return null
            if (asset != null && (asset.length > MAX_ASSET_LENGTH || !asset.matches(Regex("[A-Z0-9_./#!-]+")))) return null

            SunParams(e = e, m = m, asset = asset, rawUrl = url)
        } catch (ex: Exception) {
            Log.w(TAG, "Rejecting malformed SUN URL: ${ex.message}")
            null
        }
    }

    /** Null means the URI-prefix identifier is unsupported. */
    private fun uriPrefix(code: Byte): String? = when (code.toInt() and 0xff) {
        0x00 -> ""
        0x01 -> "http://www."
        0x02 -> "https://www."
        0x03 -> "http://"
        0x04 -> "https://"
        0x05 -> "tel:"
        0x06 -> "mailto:"
        else -> null
    }

    fun isNfcSupported(activity: Activity): Boolean =
        NfcAdapter.getDefaultAdapter(activity) != null

    fun isNfcEnabled(activity: Activity): Boolean =
        NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true
}
