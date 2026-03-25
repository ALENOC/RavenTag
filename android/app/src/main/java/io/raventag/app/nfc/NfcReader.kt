/**
 * NfcReader.kt
 *
 * Parses incoming Android NFC intents and extracts the SUN (Secure Unique NFC)
 * URL parameters broadcast by an NTAG 424 DNA chip.
 *
 * The NTAG 424 DNA encodes two parameters in each tap URL:
 *   e = AES-128-CBC encrypted PICCData (UID + scan counter), 32 hex chars
 *   m = NXP-truncated AES-CMAC over the session key, 16 hex chars
 * An optional "asset" query parameter carries the Ravencoin asset name.
 *
 * This object handles all three NFC intent actions that Android may deliver,
 * decodes the NDEF URI record, and validates parameter lengths before returning
 * a typed SunParams value object.
 */
package io.raventag.app.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.util.Log

/**
 * NFC reading utilities for RavenTag.
 * Extracts SUN URL parameters from NDEF messages.
 */
object NfcReader {

    private const val TAG = "NfcReader"

    /**
     * Typed container for the SUN URL query parameters extracted from an NFC tap.
     *
     * @property e       32 hex chars: AES-128-CBC encrypted PICCData (UID + 3-byte scan counter)
     * @property m       16 hex chars: NXP-truncated SDMMAC (8 bytes at odd indices of full CMAC)
     * @property asset   Optional Ravencoin asset name embedded in the URL (e.g. "BRAND/ITEM#SN001")
     * @property rawUrl  The complete unmodified URL as read from the NDEF record
     */
    data class SunParams(
        val e: String,
        val m: String,
        val asset: String? = null,
        val rawUrl: String
    )

    /**
     * Extract SUN parameters from an NFC intent.
     * Returns null if the intent is not an NFC NDEF intent or no SUN params found.
     */
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
                // Fallback: try to read NDEF from low-level Tag object (UID not logged for privacy)
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                tag?.let { extractFromTagObject(it) }
            }
            else -> null
        }
    }

    /**
     * Read NDEF records directly from a Tag object (used in ACTION_TAG_DISCOVERED fallback).
     *
     * Android delivers ACTION_TAG_DISCOVERED when it cannot match a more specific intent filter.
     * We open the Ndef technology layer manually to read the NDEF message.
     */
    private fun extractFromTagObject(tag: Tag): SunParams? {
        Log.d(TAG, "extractFromTagObject: techs=${tag.techList.joinToString()}")
        return try {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                Log.w(TAG, "Ndef.get returned null, tag has no NDEF tech")
                return null
            }
            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()
            if (message == null) {
                Log.w(TAG, "NDEF message is null (tag formatted but empty?)")
                return null
            }
            Log.d(TAG, "NDEF message has ${message.records.size} record(s)")
            val result = extractFromNdefMessage(message)
            if (result == null) Log.w(TAG, "extractFromNdefMessage returned null")
            result
        } catch (e: Exception) {
            Log.w(TAG, "NDEF read from Tag object failed: ${e.message}")
            null
        }
    }

    /**
     * Extract SUN parameters from the EXTRA_NDEF_MESSAGES parcelable array
     * delivered with ACTION_NDEF_DISCOVERED intents.
     */
    private fun extractFromNdef(intent: Intent): SunParams? {
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?: return null
        for (raw in rawMessages) {
            val message = raw as? NdefMessage ?: continue
            extractFromNdefMessage(message)?.let { return it }
        }
        return null
    }

    /**
     * Iterate NDEF records and parse the first Well-Known URI record that contains
     * valid SUN parameters.
     *
     * Only TNF 0x01 (Well-Known URI) records are considered; this matches the record
     * type written by Ntag424Configurator. The URI prefix byte (first payload byte)
     * is expanded to its string equivalent before URL parsing.
     */
    private fun extractFromNdefMessage(message: NdefMessage): SunParams? {
        for (record in message.records) {
            val payload = record.payload ?: continue
            // Only TNF 0x01 (Well Known URI) , correct for NTAG 424 DNA SUN records
            val urlStr = when (record.tnf) {
                0x01.toShort() -> {
                    val prefix = uriPrefix(payload[0])
                    prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
                }
                else -> null
            } ?: continue

            Log.d(TAG, "NDEF URI record: $urlStr")
            parseSunUrl(urlStr)?.let { return it }
        }
        return null
    }

    /**
     * Parse SUN URL and extract e and m parameters.
     * Expected format: https://verify.raventag.com/?e=HEX&m=HEX
     *
     * Special case: Ravencoin unique asset names contain '#' (the separator between
     * sub-asset name and serial number). If the '#' was not percent-encoded when
     * the NDEF URL was written, Android's Uri.parse() treats it as a fragment
     * separator and moves the 'e' and 'm' parameters into the fragment. This
     * method detects that case and re-encodes the '#' as '%23' before parsing.
     */
    fun parseSunUrl(url: String): SunParams? {
        return try {
            // If the asset name contains '#' (unique asset separator) and it was not
            // percent-encoded when the NDEF URL was built, Android Uri.parse treats it
            // as a fragment separator, pushing e= and m= into the fragment.
            // Detect this and fix by replacing the first bare '#' with '%23'.
            val fragment = android.net.Uri.parse(url).fragment
            val fixedUrl = if (fragment != null && fragment.contains("&e=") && fragment.contains("&m=")) {
                Log.d(TAG, "Detected unencoded '#' in URL, fixing")
                url.replaceFirst("#", "%23")
            } else {
                url
            }

            val uri = android.net.Uri.parse(fixedUrl)
            val e = uri.getQueryParameter("e") ?: return null
            val m = uri.getQueryParameter("m") ?: return null
            val asset = uri.getQueryParameter("asset")

            // Basic validation: e = 32 hex chars (16 bytes PICC data), m = 16 hex chars (8 bytes MACt)
            if (!e.matches(Regex("[0-9a-fA-F]{32}"))) return null
            if (!m.matches(Regex("[0-9a-fA-F]{16}"))) return null

            SunParams(e = e, m = m, asset = asset, rawUrl = url)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to parse SUN URL: ${ex.message}")
            null
        }
    }

    /**
     * Expand the NDEF URI prefix byte to its string representation.
     * Per NDEF URI Record Type Definition spec, byte 0 of a URI payload
     * encodes a common URI prefix to save space. Code 0x04 = "https://"
     * is the typical value for NTAG 424 DNA SUN URLs.
     */
    private fun uriPrefix(code: Byte): String = when (code.toInt()) {
        0x00 -> ""
        0x01 -> "http://www."
        0x02 -> "https://www."
        0x03 -> "http://"
        0x04 -> "https://"
        0x05 -> "tel:"
        0x06 -> "mailto:"
        else -> ""
    }

    /**
     * Check if device supports NFC.
     */
    fun isNfcSupported(activity: Activity): Boolean =
        NfcAdapter.getDefaultAdapter(activity) != null

    /**
     * Check if NFC is enabled.
     */
    fun isNfcEnabled(activity: Activity): Boolean =
        NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true
}
