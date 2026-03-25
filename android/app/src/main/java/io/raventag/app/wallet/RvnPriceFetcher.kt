package io.raventag.app.wallet

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Fetches the current RVN/USDT (or RVN/USD) price from multiple public exchange
 * and aggregator APIs, using a sequential fallback strategy.
 *
 * Each source is tried in order; the first successful response is returned.
 * No API keys are required. All requests use plain HTTPS GET with a short
 * timeout to avoid blocking the calling thread.
 *
 * The caller is responsible for running this on a background thread (e.g.,
 * inside a coroutine with Dispatchers.IO).
 *
 * Sources tried in order:
 *   1. Binance    - https://api.binance.com/api/v3/ticker/price?symbol=RVNUSDT
 *   2. MEXC       - https://api.mexc.com/api/v3/ticker/price?symbol=RVNUSDT
 *   3. KuCoin     - https://api.kucoin.com/api/v1/market/orderbook/level1?symbol=RVN-USDT
 *   4. CoinGecko  - https://api.coingecko.com/api/v3/simple/price?ids=ravencoin&vs_currencies=usd
 */
object RvnPriceFetcher {

    private const val TAG = "RvnPrice"

    /** Maximum time in milliseconds to wait for connection and data from each source. */
    private const val TIMEOUT_MS = 8_000

    /**
     * Returns the current RVN price in USD/USDT, or null if all configured
     * sources fail (network error, timeout, rate-limiting, unexpected JSON, etc.).
     *
     * The fallback chain is: Binance -> MEXC -> KuCoin -> CoinGecko.
     */
    fun fetch(): Double? {
        return tryBinance() ?: tryMexc() ?: tryKucoin() ?: tryCoingecko()
    }

    /**
     * Performs a simple HTTPS GET request and returns the response body as a
     * string, or null on any error (network failure, non-2xx HTTP status, etc.).
     *
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
        if (conn.responseCode in 200..299) {
            // Read full response body and close the connection immediately
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        } else {
            conn.disconnect()
            null
        }
    } catch (_: Exception) { null }

    /**
     * Fetches RVN/USDT price from Binance spot ticker API.
     *
     * Expected JSON shape: { "symbol": "RVNUSDT", "price": "0.01234" }
     * The "price" field is a string-encoded decimal and must be parsed to Double.
     *
     * @return Price in USDT, or null on failure.
     */
    private fun tryBinance(): Double? {
        return try {
            val body = get("https://api.binance.com/api/v3/ticker/price?symbol=RVNUSDT")
                ?: return null
            JSONObject(body).getString("price").toDouble()
                .also { Log.d(TAG, "Binance: $it") }
        } catch (_: Exception) { null }
    }

    /**
     * Fetches RVN/USDT price from MEXC spot ticker API.
     *
     * Expected JSON shape: { "symbol": "RVNUSDT", "price": "0.01234" }
     * Response format mirrors the Binance v3 ticker endpoint.
     *
     * @return Price in USDT, or null on failure.
     */
    private fun tryMexc(): Double? {
        return try {
            val body = get("https://api.mexc.com/api/v3/ticker/price?symbol=RVNUSDT")
                ?: return null
            JSONObject(body).getString("price").toDouble()
                .also { Log.d(TAG, "MEXC: $it") }
        } catch (_: Exception) { null }
    }

    /**
     * Fetches RVN/USDT price from KuCoin level-1 order book API.
     *
     * Expected JSON shape:
     *   { "code": "200000", "data": { "price": "0.01234", ... } }
     * The price is nested under a "data" object, unlike Binance/MEXC.
     *
     * @return Price in USDT, or null on failure.
     */
    private fun tryKucoin(): Double? {
        return try {
            val body = get("https://api.kucoin.com/api/v1/market/orderbook/level1?symbol=RVN-USDT")
                ?: return null
            // KuCoin wraps the result under a "data" object
            JSONObject(body).getJSONObject("data").getString("price").toDouble()
                .also { Log.d(TAG, "KuCoin: $it") }
        } catch (_: Exception) { null }
    }

    /**
     * Fetches RVN/USD price from CoinGecko simple price API.
     *
     * Expected JSON shape:
     *   { "ravencoin": { "usd": 0.01234 } }
     * CoinGecko returns "usd" as a numeric value (not a string), and uses the
     * coin ID "ravencoin" rather than a ticker symbol.
     *
     * @return Price in USD, or null on failure.
     */
    private fun tryCoingecko(): Double? {
        return try {
            val body = get("https://api.coingecko.com/api/v3/simple/price?ids=ravencoin&vs_currencies=usd")
                ?: return null
            // Navigate: root -> "ravencoin" object -> "usd" numeric field
            JSONObject(body).getJSONObject("ravencoin").getDouble("usd")
                .also { Log.d(TAG, "CoinGecko: $it") }
        } catch (_: Exception) { null }
    }
}
