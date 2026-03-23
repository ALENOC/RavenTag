package io.raventag.app.wallet

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Fetches the Ravencoin network hashrate from multiple public data sources,
 * using a sequential fallback strategy: each source is tried in order and the
 * first successful response is returned.
 *
 * No API keys are required. All requests use plain HTTPS GET with a short
 * timeout to avoid blocking the UI thread for too long.
 *
 * Returns the hashrate in hashes per second (H/s). The caller is responsible
 * for running this on a background thread (e.g., inside a coroutine with
 * Dispatchers.IO).
 *
 * Sources tried in order:
 *   1. WhatToMine  - https://whattomine.com/coins/234.json  (field: nethash)
 *   2. 2Miners     - https://rvn.2miners.com/api/stats      (field: nodes[0].networkhashps)
 */
object RvnHashrateFetcher {

    private const val TAG = "RvnHashrate"

    /** Maximum time in milliseconds to wait for connection and data from each source. */
    private const val TIMEOUT_MS = 8_000

    /**
     * Returns the current Ravencoin network hashrate in H/s, or null if all
     * configured sources fail (network error, timeout, unexpected JSON shape, etc.).
     *
     * Tries WhatToMine first; if that fails, falls back to 2Miners.
     */
    fun fetch(): Double? {
        return tryWhatToMine() ?: try2Miners()
    }

    /**
     * Performs a simple HTTP GET request and returns the response body as a string,
     * or null on any error (network failure, non-2xx status, etc.).
     *
     * Sets a common User-Agent header so servers can identify the client.
     * The connection is always disconnected after use to release resources.
     *
     * @param url The full URL to fetch.
     * @return Response body text, or null on failure.
     */
    private fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "RavenTag/1.0")
        if (conn.responseCode in 200..299) {
            // Read full response body and close the connection immediately
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        } else {
            conn.disconnect()
            null
        }
    } catch (_: Exception) { null }

    /**
     * Fetches network hashrate from WhatToMine (coin ID 234 = Ravencoin).
     *
     * Expected JSON shape: { "nethash": <double>, ... }
     * The "nethash" field is the network hashrate in H/s.
     *
     * @return Hashrate in H/s, or null if the request fails or the field is missing.
     */
    private fun tryWhatToMine(): Double? {
        return try {
            val body = get("https://whattomine.com/coins/234.json") ?: return null
            JSONObject(body).getDouble("nethash")
                .also { Log.d(TAG, "WhatToMine: $it H/s") }
        } catch (_: Exception) { null }
    }

    /**
     * Fetches network hashrate from the 2Miners Ravencoin pool API.
     *
     * Expected JSON shape: { "nodes": [ { "networkhashps": <double>, ... }, ... ] }
     * Uses the first node entry's "networkhashps" field (network-wide, not pool-specific).
     *
     * @return Hashrate in H/s, or null if the request fails, nodes array is empty,
     *         or the expected field is absent.
     */
    private fun try2Miners(): Double? {
        return try {
            val body = get("https://rvn.2miners.com/api/stats") ?: return null
            val nodes = JSONObject(body).getJSONArray("nodes")
            // Guard against an empty nodes array to avoid an index-out-of-bounds error
            if (nodes.length() == 0) return null
            nodes.getJSONObject(0).getDouble("networkhashps")
                .also { Log.d(TAG, "2Miners: $it H/s") }
        } catch (_: Exception) { null }
    }
}
