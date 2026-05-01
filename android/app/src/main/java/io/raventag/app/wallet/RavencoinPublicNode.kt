package io.raventag.app.wallet

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Thrown when no ElectrumX server in the server list is able to provide a
 * relay fee rate via the "blockchain.relayfee" call. The caller should surface
 * a user-facing error asking to retry when connectivity is restored.
 */
class FeeUnavailableException : Exception("Fee unavailable")

// Data models (public API unchanged for WalletManager compatibility) ──────────

/**
 * RVN balance for a single address, expressed in satoshis (1 RVN = 1e8 satoshis).
 *
 * @property confirmed   Balance locked in confirmed blocks.
 * @property unconfirmed Balance in the mempool (not yet mined).
 */
data class AddressBalance(val confirmed: Long, val unconfirmed: Long) {
    /** Combined confirmed + unconfirmed balance converted to RVN (display units). */
    val totalRvn: Double get() = (confirmed + unconfirmed) / 1e8
}

/**
 * A single unspent transaction output (UTXO) for a Ravencoin address.
 *
 * @property txid        Transaction hash (hex, big-endian as returned by ElectrumX).
 * @property outputIndex Index of this output within the transaction (vout index).
 * @property satoshis    Value of this output in satoshis.
 * @property script      Hex-encoded scriptPubKey, used during transaction signing.
 *                       For RVN-only UTXOs this is the standard P2PKH script.
 *                       For asset UTXOs this is the full asset scriptPubKey.
 * @property height      Block height at which the UTXO was confirmed (0 = mempool).
 */
data class Utxo(
    val txid: String,
    val outputIndex: Int,
    val satoshis: Long,
    val script: String,   // hex P2PKH scriptPubKey (reconstructed from address)
    val height: Int
)

/**
 * Asset balance entry returned by the ElectrumX balance call with asset=true.
 *
 * @property name   Asset name as registered on the Ravencoin blockchain.
 * @property amount Balance in display units (divided by 10^divisions).
 */
data class ElectrumAssetBalance(val name: String, val amount: Double)

/**
 * Metadata for a Ravencoin asset as returned by the ElectrumX asset.get_meta call.
 *
 * @property name          Canonical asset name.
 * @property totalSupply   Total circulating supply in raw units (satoshi-equivalent).
 * @property divisions     Number of decimal places (0 = integer, 8 = max precision).
 * @property reissuable    Whether the issuer can mint additional supply.
 * @property hasIpfs       True if an IPFS hash was attached during issuance.
 * @property ipfsHash      CIDv0 "Qm..." hash pointing to off-chain metadata (null if none).
 */
data class ElectrumAssetMeta(
    val name: String,
    val totalSupply: Long,
    val divisions: Int,
    val reissuable: Boolean,
    val hasIpfs: Boolean,
    val ipfsHash: String?
)

/**
 * A single entry in the transaction history for an address.
 *
 * Direction is determined from the perspective of the monitored address:
 * - isIncoming = true  when the address appears in at least one vout.
 * - isIncoming = false when all vout destinations are other addresses (pure send).
 *
 * @property txid          Transaction hash.
 * @property height        Block height (0 = unconfirmed/mempool).
 * @property confirmations Number of confirmations (0 if unconfirmed).
 * @property amountSat     Satoshis received by this address (positive if incoming).
 * @property sentSat       Satoshis sent to other addresses (positive if outgoing).
 * @property isIncoming    True if this address received funds in this transaction.
 * @property timestamp     Unix timestamp in seconds from the block header (0 if unknown).
 */
data class TxHistoryEntry(
    val txid: String,
    val height: Int,           // 0 = unconfirmed/mempool
    val confirmations: Int,
    val amountSat: Long,       // positive = received to our address
    val sentSat: Long,         // positive = sent to other addresses (external, D-19)
    val isIncoming: Boolean,   // true if amountSat > 0 (our address in vout)
    val isSelfTransfer: Boolean = false, // true if this is an internal sweep (< 1% net loss)
    val timestamp: Long = 0L,  // Unix timestamp in seconds (0 if unknown)
    // D-19 three-value breakdown (0 when unknown / not yet enriched):
    val cycledSat: Long = 0L,  // satoshis paying the change / currentIndex+1 address
    val feeSat: Long = 0L,     // fee paid (sum(vin) - sum(vout))
    // Asset transfer detection: when this tx delivers an asset to one of our
    // addresses, [assetName] and [assetAmount] describe the asset; otherwise null/0.
    val assetName: String? = null,
    val assetAmount: Long = 0L,  // raw asset amount (sats * 10^divisions)
    // Full list of asset names cycled/received in this tx (toUs vouts).
    // Used by the UI to compact the row to "Ciclati N asset" with a tap-to-list dialog.
    val incomingAssetNames: List<String> = emptyList(),
    // Issuance detection: true when tx burns to a canonical issuance address.
    val isIssuance: Boolean = false,
    val issuanceBurnSat: Long = 0L  // RVN burned for issuance (5/100/500 RVN)
)

/**
 * A UTXO that carries a Ravencoin asset rather than plain RVN.
 *
 * Asset UTXOs must be handled separately from RVN UTXOs to avoid accidental
 * asset destruction when building RVN-only transactions.
 *
 * @property utxo         The underlying UTXO with the full asset scriptPubKey in script.
 * @property assetName    Name of the asset carried by this output.
 * @property assetRawAmount Asset amount in raw units (not divided by 10^divisions).
 */
data class AssetUtxo(val utxo: Utxo, val assetName: String, val assetRawAmount: Long)

/** Internal server descriptor. Not exposed in the public API. */
private data class ElectrumServer(val host: String, val port: Int)

/**
 * Direct Ravencoin blockchain access via the ElectrumX JSON-RPC protocol (no backend required).
 *
 * Connects to community-maintained Ravencoin ElectrumX servers over TLS on port 50002.
 * All public methods use an internal failover loop: each server in the list is tried in
 * order, and the first successful response is returned. If all servers fail, an exception
 * is thrown.
 *
 * Certificate trust model: TOFU (Trust On First Use).
 * On the first connection to a host, the server's SHA-256 certificate fingerprint is
 * pinned in the in-process cache. All subsequent connections to the same host verify
 * the fingerprint to prevent man-in-the-middle attacks. The cache lives only for the
 * lifetime of the process (not persisted to disk).
 *
 * Supported ElectrumX operations:
 *   balance:   blockchain.scripthash.get_balance
 *   UTXOs:     blockchain.scripthash.listunspent (RVN-only, asset UTXOs filtered out)
 *   broadcast: blockchain.transaction.broadcast
 *   assets:    blockchain.scripthash.listunspent + blockchain.scripthash.get_balance
 *              with the Ravencoin ElectrumX asset extensions
 */
class RavencoinPublicNode(private val context: Context) {

    companion object {
        private const val TAG = "ElectrumX"

        /** Timeout for the TCP connection handshake in milliseconds.
         *  Kept tight so a dead server does not stall the cold-start failover
         *  rotation: 5 servers × 5 s previously meant up to 25 s of "Reconnecting…"
         *  on app resume; 2.5 s caps that at ~12 s worst case. */
        private const val CONNECT_TIMEOUT_MS = 2_500

        /** Timeout for reading a response line from the server in milliseconds. */
        private const val READ_TIMEOUT_MS = 15_000

        /** Maximum number of pipelined requests per [callBatch] TLS connection. */
        private const val BATCH_CHUNK_SIZE = 20

        /**
         * List of public Ravencoin ElectrumX servers, tried in order.
         * All use the standard TLS port 50002.
         *
         * Sourced from [io.raventag.app.config.AppConfig.ELECTRUM_SERVERS] so
         * that [io.raventag.app.wallet.health.NodeHealthMonitor] and this
         * class iterate the same pool. Evaluated once at class init; adding
         * hosts requires editing AppConfig (see KDoc there for provenance).
         */
        private val SERVERS: List<ElectrumServer> =
            io.raventag.app.config.AppConfig.ELECTRUM_SERVERS.map { (host, port) ->
                ElectrumServer(host, port)
            }

        /**
         * The Cipig mirrors answer the core Electrum calls but currently return
         * "unknown method" for blockchain.asset.get_meta. Asset preview metadata
         * must bypass them or every cached asset row loses its IPFS CID.
         */
        private val ASSET_META_SERVERS: List<ElectrumServer> =
            SERVERS.filterNot { it.host.contains("cipig", ignoreCase = true) }
                .ifEmpty { SERVERS }

        /**
         * Monotonically increasing request ID counter, shared across all instances.
         * ElectrumX requires a unique integer "id" in each JSON-RPC request so that
         * responses can be matched to their requests (though we use synchronous I/O
         * here, so matching is implicit).
         */
        private val idCounter = AtomicInteger(1)

        /** Shared Gson instance for serializing JSON-RPC request objects. */
        private val gson = Gson()
    }

    // Public API ──────────────────────────────────────────────────────────────

    /**
     * Quick connectivity check that tries to reach at least one ElectrumX server.
     *
     * Sends a "server.version" handshake to each server in order. Returns true
     * as soon as one server responds successfully. Returns false only if all
     * servers time out or refuse the connection.
     *
     * Used to drive the wallet connectivity status indicator in the UI.
     */
    fun ping(): Boolean {
        for (server in SERVERS) {
            try {
                call(server, "server.version", listOf("RavenTag/1.0", "1.4"))
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Lightweight heartbeat that routes through [callWithFailover] so
     * NodeHealthMonitor receives success/failure signals and the UI pill
     * stays fresh between wallet refreshes. Returns true if any server answered.
     */
    fun heartbeat(): Boolean = try {
        callWithFailover("server.version", listOf("RavenTag/1.0", "1.4"))
        true
    } catch (_: Exception) { false }

    /**
     * Returns the confirmed and unconfirmed RVN balance for [address].
     *
     * Converts the P2PKH address to an ElectrumX scripthash (reversed SHA-256
     * of the scriptPubKey), then calls "blockchain.scripthash.get_balance".
     *
     * @param address Ravencoin P2PKH address (Base58Check encoded).
     * @return [AddressBalance] with satoshi amounts for confirmed and unconfirmed funds.
     * @throws Exception if all servers are unreachable.
     */
    fun getBalance(address: String): AddressBalance {
        val scripthash = addressToScripthash(address)
        val result = callWithFailover("blockchain.scripthash.get_balance", listOf(scripthash)).asJsonObject
        return AddressBalance(
            confirmed = result.get("confirmed")?.asLong ?: 0L,
            unconfirmed = result.get("unconfirmed")?.asLong ?: 0L
        )
    }

    /**
     * Aggregates asset balances across all [addresses] using a single pipelined batch request.
     *
     * Sends one `blockchain.scripthash.get_balance` call (with asset=true) per address,
     * all pipelined in one TLS connection. Returns a map from asset name to total amount
     * (in human-readable units, i.e. divided by 10^8), excluding plain RVN entries.
     *
     * @param addresses List of Ravencoin P2PKH addresses to aggregate.
     * @return Map of asset name to total balance; empty if no assets or network failure.
     */
    fun getTotalAssetBalances(addresses: List<String>): Map<String, Double> {
        return getBalanceAndAssets(addresses).second
    }

    /**
     * Single-batch combined fetch: aggregates RVN balance and per-asset balances
     * across [addresses] in one pipelined `blockchain.scripthash.get_balance(asset=true)`
     * batch. Replaces two separate batches (getTotalBalance + getTotalAssetBalances)
     * with one TLS roundtrip.
     *
     * @return Pair(totalRvn, assetMap). Both balances are in human-readable units
     *         (divided by 10^8). Owner-token "!" assets are included in assetMap.
     */
    fun getBalanceAndAssets(addresses: List<String>): Pair<Double, Map<String, Double>> {
        if (addresses.isEmpty()) return 0.0 to emptyMap()
        val requests = addresses.map { addr ->
            "blockchain.scripthash.get_balance" to listOf(addressToScripthash(addr), true) as List<Any>
        }
        val responses = callWithFailoverBatch(requests)
        var rvnTotal = 0L
        val totals = mutableMapOf<String, Long>()
        addresses.forEachIndexed { i, _ ->
            val resp = responses.getOrNull(i) ?: return@forEachIndexed
            if (resp == null || !resp.isJsonObject) return@forEachIndexed
            val obj = resp.asJsonObject
            // Top-level confirmed/unconfirmed = RVN balance
            try {
                rvnTotal += (obj.get("confirmed")?.asLong ?: 0L) +
                            (obj.get("unconfirmed")?.asLong ?: 0L)
            } catch (_: Exception) {}
            for ((name, value) in obj.entrySet()) {
                if (name == "confirmed" || name == "unconfirmed" || name == "rvn" || name == "RVN") continue
                try {
                    val a = value.asJsonObject
                    val sat = (a.get("confirmed")?.asLong ?: 0L) + (a.get("unconfirmed")?.asLong ?: 0L)
                    if (sat > 0) {
                        totals[name] = (totals[name] ?: 0L) + sat
                    }
                } catch (_: Exception) {}
            }
        }
        return (rvnTotal / 1e8) to totals.mapValues { (_, sat) -> sat / 1e8 }
    }

    /**
     * Returns the subset of [addresses] that currently hold any funds (RVN or assets),
     * using a single pipelined batch `get_balance?asset=true` request.
     *
     * Replaces the previous pattern of N*2 sequential getAssetBalances+getUtxos calls
     * (one TLS connection per address) with a single batched query (ceil(N/20) connections).
     *
     * @param addresses List of Ravencoin P2PKH addresses to check.
     * @return Set of addresses that have at least one satoshi of RVN or assets.
     */
    fun getAddressesWithFunds(addresses: List<String>): Set<String> =
        getAddressesWithSignificantFunds(addresses, minRvnSat = 1L)

    /**
     * Like [getAddressesWithFunds] but ignores RVN residues below [minRvnSat].
     * Use a non-zero floor (e.g. 100_000 sat = 0.001 RVN) for the consolidation
     * banner so we don't keep nagging the user about dust left behind by a sweep.
     * Asset balances always count regardless of [minRvnSat].
     */
    fun getAddressesWithSignificantFunds(addresses: List<String>, minRvnSat: Long): Set<String> {
        if (addresses.isEmpty()) return emptySet()
        val requests = addresses.map { addr ->
            "blockchain.scripthash.get_balance" to listOf(addressToScripthash(addr), true) as List<Any>
        }
        val responses = callWithFailoverBatch(requests)

        // If every response is null the node rejected the `true` asset param with a
        // JSON-RPC error (callBatch silently stores null for per-request errors).
        // Fall back to plain get_balance so at least RVN-bearing addresses are found.
        val allNull = responses.all { it == null || !it.isJsonObject }
        val effectiveResponses: List<JsonElement?>
        val hasAssetData: Boolean
        if (allNull) {
            android.util.Log.w(TAG, "get_balance?asset=true returned all nulls for ${addresses.size} addr; falling back to RVN-only")
            val fallbackRequests = addresses.map { addr ->
                "blockchain.scripthash.get_balance" to listOf(addressToScripthash(addr)) as List<Any>
            }
            effectiveResponses = callWithFailoverBatch(fallbackRequests)
            hasAssetData = false
        } else {
            effectiveResponses = responses
            hasAssetData = true
        }

        val result = mutableSetOf<String>()
        addresses.forEachIndexed { i, addr ->
            val resp = effectiveResponses.getOrNull(i) ?: return@forEachIndexed
            if (resp == null || !resp.isJsonObject) return@forEachIndexed
            val obj = resp.asJsonObject
            val rvnSat = (try { obj.get("confirmed")?.asLong ?: 0L } catch (_: Exception) { 0L }) +
                         (try { obj.get("unconfirmed")?.asLong ?: 0L } catch (_: Exception) { 0L })
            if (rvnSat >= minRvnSat) { result.add(addr); return@forEachIndexed }
            if (hasAssetData) {
                for ((key, value) in obj.entrySet()) {
                    if (key == "confirmed" || key == "unconfirmed") continue
                    try {
                        val assetObj = value.asJsonObject
                        val sat = (assetObj.get("confirmed")?.asLong ?: 0L) +
                                  (assetObj.get("unconfirmed")?.asLong ?: 0L)
                        if (sat > 0) { result.add(addr); break }
                    } catch (_: Exception) {}
                }
            }
        }
        return result
    }

    /**
     * Returns the total RVN balance (confirmed + unconfirmed) across all [addresses]
     * using a single pipelined batch request.
     *
     * Replaces N sequential/parallel [getBalance] calls with one TLS connection and
     * N pipelined `blockchain.scripthash.get_balance` requests (chunked at [BATCH_CHUNK_SIZE]).
     * With 37 addresses this drops from 37 connections to 2.
     *
     * @param addresses List of Ravencoin P2PKH addresses to aggregate.
     * @return Total balance in RVN, 0.0 if all addresses are empty or on network failure.
     */
    fun getTotalBalance(addresses: List<String>): Double {
        if (addresses.isEmpty()) return 0.0
        val requests = addresses.map { addr ->
            "blockchain.scripthash.get_balance" to listOf(addressToScripthash(addr)) as List<Any>
        }
        val responses = callWithFailoverBatch(requests)
        var totalSat = 0L
        var successCount = 0
        for (resp in responses) {
            if (resp != null && !resp.isJsonNull && resp.isJsonObject) {
                val obj = resp.asJsonObject
                totalSat += obj.get("confirmed")?.asLong ?: 0L
                totalSat += obj.get("unconfirmed")?.asLong ?: 0L
                successCount++
            }
        }
        // If every single response failed, treat it as a network error rather than silently
        // returning 0.0: the caller can catch this and preserve the previously known balance.
        if (successCount == 0) throw java.io.IOException("All balance queries failed (network unreachable)")
        return totalSat / 1e8
    }

    /**
     * Returns true if [address] has any UTXOs (RVN or assets).
     *
     * Uses [listUnspentRaw] directly without secondary raw-tx fetches, so it is
     * suitable as a lightweight fallback when batched get_balance?asset=true
     * returns all errors and falls back to RVN-only detection.
     */
    fun hasAnyUtxos(address: String): Boolean {
        return try {
            listUnspentRaw(address).isNotEmpty()
        } catch (_: Exception) { false }
    }

    /**
     * Returns plain RVN UTXOs for [address], filtering out asset-carrying outputs.
     *
     * The script field of each returned Utxo is the P2PKH scriptPubKey reconstructed
     * from the address, required for SIGHASH computation during signing.
     *
     * @param address Ravencoin P2PKH address.
     * @return List of spendable RVN UTXOs (may be empty if the address has no funds).
     * @throws Exception if all servers are unreachable.
     */
    fun getUtxos(address: String): List<Utxo> {
        val script = p2pkhScriptHex(address)
        val all = listUnspentRaw(address)
        return all
            .filter { obj ->
                // Determine whether this UTXO carries an asset or plain RVN
                val assetField = if (obj.has("asset")) obj.get("asset") else null
                when {
                    assetField == null || assetField.isJsonNull -> true  // no asset: RVN UTXO
                    assetField.isJsonPrimitive -> {
                        // Some servers send asset name as a bare string
                        val name = runCatching { assetField.asString }.getOrDefault("")
                        name.isEmpty() || name == "RVN"
                    }
                    else -> {
                        // Other servers send an object { "name": "ASSET", "amount": ... }
                        val name = assetField.asJsonObject.get("name")?.asString ?: ""
                        name.isEmpty() || name == "RVN"
                    }
                }
            }
            .mapNotNull { obj ->
                try {
                    Utxo(
                        txid = obj.get("tx_hash").asString,
                        outputIndex = obj.get("tx_pos").asInt,
                        satoshis = obj.get("value").asLong,
                        script = script,
                        height = obj.get("height").asInt
                    )
                } catch (_: Exception) { null }
            }
    }

    /**
     * Broadcasts a raw signed transaction to the Ravencoin network.
     *
     * Sends the hex-encoded transaction via "blockchain.transaction.broadcast"
     * using the failover server loop. The first server that accepts the broadcast
     * will propagate it to the network.
     *
     * @param rawHex Hex-encoded signed transaction bytes.
     * @return The transaction ID (txid) returned by the server.
     * @throws Exception if all servers reject or are unreachable.
     */
    fun broadcast(rawHex: String): String =
        callWithFailover("blockchain.transaction.broadcast", listOf(rawHex)).asString

    /**
     * Broadcast with direct-server fallback when [broadcast] fails because all
     * nodes are quarantined after a long scan phase.
     *
     * First tries [broadcast] (respects [NodeHealthMonitor] quarantine). If that
     * fails, tries every server directly via [call], bypassing quarantine, so
     * the consolidation/sweep final step can complete.
     */
    fun broadcastWithAllServers(rawHex: String): String {
        try {
            return broadcast(rawHex)
        } catch (_: Exception) {
            android.util.Log.w(TAG, "broadcast: normal failover exhausted, trying all servers directly")
        }
        var lastError: Exception? = null
        for (server in SERVERS) {
            try {
                val result = call(server, "blockchain.transaction.broadcast", listOf(rawHex)).asString
                io.raventag.app.wallet.health.NodeHealthMonitor.reportSuccess("${server.host}:${server.port}")
                return result
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w(TAG, "broadcast: direct server ${server.host}: ${e.message}")
                io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                    "${server.host}:${server.port}", e.javaClass.simpleName
                )
            }
        }
        throw lastError ?: java.io.IOException("broadcast failed on all servers")
    }

    /**
     * Low-level RPC call with failover; returns null on any exception.
     *
     * Used by RebroadcastWorker for confirmation checks and other callers
     * that need best-effort access to ElectrumX RPC without propagating errors.
     */
    fun callElectrumRawOrNull(method: String, params: List<Any>): com.google.gson.JsonElement? = try {
        callWithFailover(method, params)
    } catch (_: Exception) { null }

    /**
     * Queries all known ElectrumX servers for "blockchain.relayfee" and returns a
     * safe fee rate to use when building transactions.
     *
     * The relay fee is the minimum fee rate (RVN per kilobyte) that nodes will accept
     * to relay a transaction. The result is converted to satoshis per byte, the minimum
     * across all responding servers is taken, then a 2x safety margin is applied to reduce
     * the chance of the transaction being dropped from the mempool. A floor of 200 sat/byte
     * is enforced to protect against unreasonably low values from misconfigured nodes.
     *
     * @return Fee rate in satoshis per byte, with 2x margin and a floor of 200.
     * @throws FeeUnavailableException if every server fails to respond.
     */
    fun getMinRelayFeeRateSatPerByte(): Long {
        val results = SERVERS.mapNotNull { server ->
            try {
                // relayfee is returned as RVN per kilobyte (e.g. 0.01)
                val rvnPerKb = call(server, "blockchain.relayfee", emptyList()).asDouble
                // Convert: RVN/kB * 1e8 (sat/RVN) / 1000 (bytes/kB) = sat/byte
                val satPerByte = (rvnPerKb * 1e8 / 1000).toLong()
                Log.d(TAG, "relayfee ${server.host}: $rvnPerKb RVN/kB = $satPerByte sat/byte")
                satPerByte
            } catch (e: Exception) {
                Log.w(TAG, "relayfee failed for ${server.host}: ${e.message}")
                null
            }
        }
        if (results.isEmpty()) throw FeeUnavailableException()
        val minSatPerByte = results.min()
        // Apply 2x safety margin; enforce a minimum floor of 200 sat/byte
        return maxOf(minSatPerByte * 2, 200L)
    }

    /**
     * Returns the current Ravencoin chain tip block height.
     *
     * Uses "blockchain.headers.subscribe" which returns the latest block header
     * including its height. Returns null if all servers fail.
     */
    fun getBlockHeight(): Int? {
        val result = callWithFailover("blockchain.headers.subscribe", emptyList())
        return result.asJsonObject.get("height")?.asInt
    }

    /**
     * D-05 support: subscribes to a scripthash and returns the current status hash.
     * Uses the one-shot RPC socket; the foreground-session long-lived socket lives in
     * [io.raventag.app.wallet.subscription.SubscriptionManager].
     *
     * @param address Ravencoin P2PKH address.
     * @return The current status hash, or null if the address has no history.
     */
    fun subscribeScripthashRpc(address: String): String? {
        val scripthash = addressToScripthash(address)
        val result = callWithFailover("blockchain.scripthash.subscribe", listOf(scripthash))
        return if (result.isJsonNull) null else result.asString
    }

    /**
     * D-22 support: calls blockchain.estimatefee with a block target and returns
     * the raw RVN/kB number. Returns -1.0 when the server returns null. Callers
     * (FeeEstimator) are responsible for the static-fallback policy.
     *
     * @param targetBlocks Number of blocks for the fee estimation target.
     * @return Fee rate in RVN per kilobyte, or -1.0 if unavailable.
     */
    fun estimateFeeRvnPerKb(targetBlocks: Int): Double {
        val result = callWithFailover("blockchain.estimatefee", listOf(targetBlocks))
        return if (result.isJsonNull) -1.0 else result.asDouble
    }

    /**
     * Returns all asset-carrying UTXOs for a specific asset owned by [address].
     *
     * First attempts to use the Ravencoin ElectrumX asset extension
     * "blockchain.scripthash.listunspent" with an asset name parameter to query
     * only UTXOs for that asset. If that call fails (e.g., older server version),
     * falls back to fetching all UTXOs and filtering by asset name manually.
     *
     * The "script" field of each returned Utxo is the full asset scriptPubKey,
     * retrieved from the raw transaction via "blockchain.transaction.get". If the
     * raw transaction lookup fails, the script is reconstructed locally:
     * - Owner tokens (name ending in "!") use the "rvno" payload format.
     * - Normal asset UTXOs use the "rvnt" payload format with amount.
     *
     * The script must be accurate because it is used as the subscript during
     * SIGHASH computation when signing the spending input.
     *
     * @param address   Ravencoin P2PKH address holding the asset.
     * @param assetName Asset name to query (e.g., "BRAND/ITEM", "ROOT!", "ROOT/SUB#SERIAL").
     * @return List of asset UTXOs with full scriptPubKeys (may be empty).
     * @throws Exception if all servers are unreachable.
     */
    fun getAssetUtxosFull(address: String, assetName: String): List<AssetUtxo> {
        // Attempt server-side filtering with asset name parameter
        val scripthash = addressToScripthash(address)
        val all = try {
            callWithFailover("blockchain.scripthash.listunspent", listOf(scripthash, assetName))
                .asJsonArray
                .mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
        } catch (_: Exception) {
            // Fallback: fetch all UTXOs and filter manually by asset name
            listUnspentRaw(address)
        }
        // Cache fetched raw transactions to avoid duplicate network round-trips
        val txCache = mutableMapOf<String, JsonObject?>()
        return all.mapNotNull { obj ->
            val assetField = obj.get("asset") ?: return@mapNotNull null
            // Asset field encoding varies by server version: string or object
            val name: String
            val rawAmount: Long
            when {
                assetField.isJsonPrimitive -> {
                    // Older servers return the asset name as a plain string;
                    // amount must then be read from the top-level "value" field
                    name = assetField.asString
                    rawAmount = (obj.get("value")?.asLong ?: return@mapNotNull null)
                }
                assetField.isJsonObject -> {
                    // Newer servers return an object with "name" and "amount" sub-fields
                    val ao = assetField.asJsonObject
                    name = ao.get("name")?.asString ?: return@mapNotNull null
                    rawAmount = ao.get("amount")?.asLong ?: return@mapNotNull null
                }
                else -> return@mapNotNull null
            }
            // Skip UTXOs for assets other than the one we requested (applies after manual fallback)
            if (name != assetName) return@mapNotNull null
            val txHash = obj.get("tx_hash").asString
            val txPos = obj.get("tx_pos").asInt
            // Fetch and cache the raw transaction to extract the on-chain script and actual RVN satoshis.
            // The "value" field in listunspent for asset UTXOs may contain the asset amount rather than
            // the RVN satoshi value, so we always read the actual satoshis from the decoded transaction.
            val tx = txCache.getOrPut(txHash) {
                try { callWithFailover("blockchain.transaction.get", listOf(txHash, true)).asJsonObject }
                catch (_: Exception) { null }
            }
            // Read the actual RVN satoshi value from the decoded TX (vout[txPos].value is in RVN, multiply by 1e8).
            // Fall back to 0 if unavailable so that transferAssetLocal always fetches extra RVN UTXOs for the fee.
            val satoshis = try {
                val rvn = tx?.getAsJsonArray("vout")?.get(txPos)?.asJsonObject?.get("value")?.asDouble ?: 0.0
                (rvn * 100_000_000.0).toLong()
            } catch (_: Exception) { 0L }
            // Must have the real on-chain scriptPubKey. Never reconstruct it —
            // a byte-for-byte mismatch causes bad-txns-bad-asset-transaction.
            val onChainScript = try {
                tx?.getAsJsonArray("vout")
                    ?.get(txPos)
                    ?.asJsonObject
                    ?.getAsJsonObject("scriptPubKey")
                    ?.get("hex")
                    ?.asString
            } catch (_: Exception) { null }
            if (onChainScript == null) return@mapNotNull null
            val utxo = Utxo(
                txid = txHash,
                outputIndex = txPos,
                satoshis = satoshis,
                script = onChainScript,
                height = obj.get("height").asInt
            )
            AssetUtxo(utxo, assetName, rawAmount)
        }
    }

    /**
     * Returns asset balances for [address] using the Ravencoin ElectrumX asset extension.
     *
     * Calls "blockchain.scripthash.get_balance" with the additional boolean parameter
     * "true" to request asset balances. The expected response shape is:
     *   { "rvn": { "confirmed": ..., "unconfirmed": ... },
     *     "ASSETNAME": { "confirmed": ..., "unconfirmed": ... }, ... }
     *
     * The "rvn" and "RVN" entries are excluded (handled separately by [getBalance]).
     * Only assets with a total balance greater than zero are included in the result.
     * Results are sorted alphabetically by asset name.
     *
     * @param address Ravencoin P2PKH address.
     * @return Sorted list of non-zero asset balances (display units, divided by 1e8).
     * @throws Exception if all servers are unreachable.
     */
    fun getAssetBalances(address: String): List<ElectrumAssetBalance> {
        val scripthash = addressToScripthash(address)
        val result = callWithFailover("blockchain.scripthash.get_balance", listOf(scripthash, true))
        val obj = result.asJsonObject
        return obj.entrySet()
            // Exclude the plain RVN balance entries; only process named assets
            .filter { (key, _) -> key != "rvn" && key != "RVN" }
            .mapNotNull { (name, value) ->
                try {
                    val balObj = value.asJsonObject
                    val confirmed = balObj.get("confirmed")?.asLong ?: 0L
                    val unconfirmed = balObj.get("unconfirmed")?.asLong ?: 0L
                    val total = confirmed + unconfirmed
                    // Skip assets with zero total balance (confirmed + unconfirmed)
                    if (total > 0) ElectrumAssetBalance(name, total / 1e8) else null
                } catch (_: Exception) { null }
            }
            .sortedBy { it.name }
    }

    /**
     * Returns the set of ALL asset UTXO outpoints ("txid:vout") held by [address].
     *
     * Iterates every UTXO returned by the unfiltered listunspent call and classifies
     * each one as plain RVN or asset using the following strategy:
     * - If the "asset" field is present and names a non-RVN asset: asset UTXO.
     * - If the "asset" field is absent or null (some servers omit it): fetch the raw
     *   transaction and look for the OP_RVN_ASSET marker sequence "88acc0" in the
     *   scriptPubKey hex. This catches UTXOs that the server fails to tag as assets,
     *   preventing them from being accidentally spent as plain-RVN fee inputs.
     */
    fun getAllAssetOutpoints(address: String): Set<String> {
        val all = try { listUnspentRaw(address) } catch (e: Exception) {
            android.util.Log.w("RavenTag", "getAllAssetOutpoints: listUnspentRaw failed: $e")
            return emptySet()
        }
        val result = mutableSetOf<String>()
        for (obj in all) {
            val txHash = obj.get("tx_hash")?.asString ?: continue
            val txPos  = obj.get("tx_pos")?.asInt  ?: continue
            val assetField = if (obj.has("asset")) obj.get("asset") else null
            val classified: Boolean? = when {
                assetField == null || assetField.isJsonNull -> null  // unknown
                assetField.isJsonPrimitive -> {
                    val name = runCatching { assetField.asString }.getOrDefault("")
                    name.isNotEmpty() && name != "RVN"
                }
                else -> {
                    val name = assetField.asJsonObject.get("name")?.asString ?: ""
                    name.isNotEmpty() && name != "RVN"
                }
            }
            when (classified) {
                true -> result.add("$txHash:$txPos")
                null -> {
                    try {
                        val rawTx = callWithFailover(
                            "blockchain.transaction.get", listOf(txHash, true)
                        ).asJsonObject
                        val scriptHex = rawTx.getAsJsonArray("vout")
                            ?.get(txPos)?.asJsonObject
                            ?.getAsJsonObject("scriptPubKey")
                            ?.get("hex")?.asString ?: continue
                        if ("88acc0" in scriptHex.lowercase()) result.add("$txHash:$txPos")
                    } catch (e: Exception) {
                        android.util.Log.w("RavenTag", "getAllAssetOutpoints: $txHash:$txPos rawTx fetch failed: $e")
                    }
                }
                false -> { /* plain RVN */ }
            }
        }
        return result
    }

    /**
     * Fetches all UTXOs for [address] and returns RVN UTXOs, asset outpoints, and all
     * asset UTXOs with full scripts in at most 2 TLS connections.
     *
     * TLS 1: blockchain.scripthash.listunspent (full unfiltered UTXO list)
     * TLS 2: batch blockchain.transaction.get for every unique txid referenced by
     *        unknown-type UTXOs (need "88acc0" check) and asset UTXOs (need on-chain script)
     *
     * This replaces the combination of [getUtxos] + [getAllAssetOutpoints] + N calls to
     * [getAssetUtxosFull] that previously required N+2 separate TLS connections.
     *
     * @param address Ravencoin P2PKH address.
     * @param fetchMissingAssetUtxos When true, cross-check asset balances, fetch
     *        UTXOs for every asset name, and merge only outpoints not already found
     *        by the batch path. This is intentionally opt-in because it can require
     *        one extra server query per asset.
     * @return Triple:
     *   - rvnUtxos:       plain RVN UTXOs safe to spend as fee inputs
     *   - assetOutpoints: set of "txid:vout" that carry assets (to exclude from fee inputs)
     *   - assetUtxosMap:  map from asset name to list of AssetUtxo (with on-chain scripts)
     */
    fun getUtxosAndAllAssetUtxosBatch(
        address: String,
        fetchMissingAssetUtxos: Boolean = false
    ): Triple<List<Utxo>, Set<String>, Map<String, List<AssetUtxo>>> {
        val rvnScript = p2pkhScriptHex(address)
        val rawList = listUnspentRaw(address)   // TLS 1

        // Classify each UTXO into one of three buckets
        data class PendingUtxo(
            val txHash: String,
            val txPos: Int,
            val height: Int,
            val valueField: Long?,   // raw "value" from listunspent (may be asset amount for asset UTXOs)
            val isKnownAsset: Boolean,
            val isUnknown: Boolean,  // no "asset" field: needs raw-tx check for "88acc0"
            val assetName: String?,
            val assetAmount: Long?
        )

        val pending = mutableListOf<PendingUtxo>()
        for (obj in rawList) {
            val txHash = obj.get("tx_hash")?.asString ?: continue
            val txPos  = obj.get("tx_pos")?.asInt    ?: continue
            val height = obj.get("height")?.asInt    ?: 0
            val value  = obj.get("value")?.asLong
            val assetField = if (obj.has("asset")) obj.get("asset") else null

            when {
                assetField == null || assetField.isJsonNull -> {
                    // No "asset" tag: server either omitted it or this is plain RVN
                    pending.add(PendingUtxo(txHash, txPos, height, value, false, true, null, null))
                }
                assetField.isJsonPrimitive -> {
                    val name = runCatching { assetField.asString }.getOrDefault("")
                    if (name.isEmpty() || name == "RVN") {
                        pending.add(PendingUtxo(txHash, txPos, height, value, false, false, null, null))
                    } else {
                        pending.add(PendingUtxo(txHash, txPos, height, value, true, false, name, value))
                    }
                }
                else -> {
                    val ao = assetField.asJsonObject
                    val name   = ao.get("name")?.asString   ?: ""
                    val amount = ao.get("amount")?.asLong
                    if (name.isEmpty() || name == "RVN") {
                        pending.add(PendingUtxo(txHash, txPos, height, value, false, false, null, null))
                    } else {
                        pending.add(PendingUtxo(txHash, txPos, height, value, true, false, name, amount))
                    }
                }
            }
        }

        // Collect txids that need a raw transaction fetch (unknown + known asset)
        val txidsToFetch = pending
            .filter { it.isKnownAsset || it.isUnknown }
            .map { it.txHash }
            .distinct()

        // Batch-fetch all raw transactions in one TLS connection  (TLS 2)
        val txCache = mutableMapOf<String, JsonObject?>()
        if (txidsToFetch.isNotEmpty()) {
            val requests = txidsToFetch.map { "blockchain.transaction.get" to listOf(it, true) as List<Any> }
            val results = callWithFailoverBatch(requests)
            txidsToFetch.forEachIndexed { i, txid ->
                txCache[txid] = try { results[i]?.asJsonObject } catch (_: Exception) { null }
            }
        }

        // Build the three return collections
        val rvnUtxos      = mutableListOf<Utxo>()
        val assetOutpoints = mutableSetOf<String>()
        val assetUtxosMap  = mutableMapOf<String, MutableList<AssetUtxo>>()

        for (u in pending) {
            val outpoint = "${u.txHash}:${u.txPos}"
            when {
                u.isKnownAsset -> {
                    // Asset UTXO: extract on-chain script and actual RVN satoshis from raw tx
                    assetOutpoints.add(outpoint)
                    val tx = txCache[u.txHash]
                    val vout = try {
                        tx?.getAsJsonArray("vout")?.get(u.txPos)?.asJsonObject
                    } catch (_: Exception) { null }
                    val satoshis = try {
                        val rvn = vout?.get("value")?.asDouble ?: 0.0
                        (rvn * 100_000_000.0).toLong()
                    } catch (_: Exception) { 0L }
                    val onChainScript = try {
                        vout?.getAsJsonObject("scriptPubKey")?.get("hex")?.asString
                    } catch (_: Exception) { null }
                    if (onChainScript == null) continue // skip: need real on-chain script
                    val name = u.assetName ?: continue
                    val rawAmount = u.assetAmount ?: continue
                    val utxo = Utxo(u.txHash, u.txPos, satoshis, onChainScript, u.height)
                    assetUtxosMap.getOrPut(name) { mutableListOf() }.add(AssetUtxo(utxo, name, rawAmount))
                }
                u.isUnknown -> {
                    // No "asset" tag: check raw tx scriptPubKey for OP_RVN_ASSET marker "88acc0"
                    val tx = txCache[u.txHash]
                    val vout = try {
                        tx?.getAsJsonArray("vout")?.get(u.txPos)?.asJsonObject
                    } catch (_: Exception) { null }
                    val scriptHex = try {
                        vout?.getAsJsonObject("scriptPubKey")?.get("hex")?.asString
                    } catch (_: Exception) { null }
                    if (scriptHex != null && "88acc0" in scriptHex.lowercase()) {
                        assetOutpoints.add(outpoint)
                        // Parse asset name and amount directly from the script so the UTXO
                        // is properly included in assetUtxosMap (not just silently dropped).
                        val parsed = parseAssetFromScript(scriptHex)
                        if (parsed != null) {
                            val (assetName, rawAmount) = parsed
                            val satoshis = try {
                                ((vout?.get("value")?.asDouble ?: 0.0) * 100_000_000.0).toLong()
                            } catch (_: Exception) { 0L }
                            val utxo = Utxo(u.txHash, u.txPos, satoshis, scriptHex, u.height)
                            assetUtxosMap.getOrPut(assetName) { mutableListOf() }
                                .add(AssetUtxo(utxo, assetName, rawAmount))
                        } else {
                            // Recognition failed but it has an asset marker: treat as RVN so it's at least swept
                            val satoshis = try {
                                ((vout?.get("value")?.asDouble ?: 0.0) * 100_000_000.0).toLong()
                            } catch (_: Exception) { u.valueField ?: 0L }
                            rvnUtxos.add(Utxo(u.txHash, u.txPos, satoshis, scriptHex, u.height))
                        }
                    } else {
                        // Confirmed RVN or unknown (treat as RVN to avoid locking up funds)
                        val satoshis = u.valueField ?: continue
                        rvnUtxos.add(Utxo(u.txHash, u.txPos, satoshis, rvnScript, u.height))
                    }
                }
                else -> {
                    // Explicitly tagged as plain RVN
                    val satoshis = u.valueField ?: continue
                    rvnUtxos.add(Utxo(u.txHash, u.txPos, satoshis, rvnScript, u.height))
                }
            }
        }

        // Secondary fallback: when the primary listunspent path returned zero
        // assets (server omitted asset tags), use getAssetBalances to discover
        // which assets exist, then fetch owner token UTXOs directly. Issuance
        // asks for fetchMissingAssetUtxos=true, which expands this to every
        // asset name so the issuance tx can atomically sweep the whole address.
        var unresolvedAssetNames: List<String> = emptyList()
        if (assetUtxosMap.isEmpty() || fetchMissingAssetUtxos) {
            try {
                val assetBalances = getAssetBalances(address)
                if (assetBalances.isNotEmpty()) {
                    val existingAssetOutpoints = assetUtxosMap.values
                        .flatten()
                        .map { "${it.utxo.txid}:${it.utxo.outputIndex}" }
                        .toMutableSet()
                    val mode = if (fetchMissingAssetUtxos) "all asset UTXOs" else "owner tokens"
                    android.util.Log.i("RavencoinPublicNode", "  secondary: get_balance has ${assetBalances.size}; fetching $mode via getAssetUtxosFull (parallel)")
                    val toFetch = assetBalances.filter { fetchMissingAssetUtxos || it.name.endsWith("!") }
                    val executor = java.util.concurrent.Executors.newFixedThreadPool(
                        minOf(toFetch.size, 8)
                    )
                    try {
                        val futures = toFetch.map { ab ->
                            executor.submit<java.util.AbstractMap.SimpleEntry<String, List<AssetUtxo>>> {
                                try {
                                    java.util.AbstractMap.SimpleEntry(ab.name, getAssetUtxosFull(address, ab.name))
                                } catch (e: Exception) {
                                    android.util.Log.w("RavencoinPublicNode", "  secondary: getAssetUtxosFull failed for ${ab.name}: ${e.message}")
                                    java.util.AbstractMap.SimpleEntry(ab.name, emptyList<AssetUtxo>())
                                }
                            }
                        }
                        for (future in futures) {
                            val entry = future.get()
                            val name = entry.key
                            val utxos = entry.value
                            val freshUtxos = utxos.filter { assetUtxo ->
                                existingAssetOutpoints.add("${assetUtxo.utxo.txid}:${assetUtxo.utxo.outputIndex}")
                            }
                            if (freshUtxos.isNotEmpty()) {
                                assetUtxosMap.getOrPut(name) { mutableListOf() }.addAll(freshUtxos)
                                assetOutpoints.addAll(freshUtxos.map { "${it.utxo.txid}:${it.utxo.outputIndex}" })
                                android.util.Log.i("RavencoinPublicNode", "  secondary: ${name} -> ${freshUtxos.size} additional UTXO(s) via getAssetUtxosFull")
                            }
                        }
                    } finally {
                        executor.shutdown()
                    }
                    // For non-owner assets: try re-parsing from cached raw txs
                    val balanceNames = assetBalances.map { it.name }.toSet()
                    for (u in pending) {
                        if (!u.isUnknown) continue
                        val tx = txCache[u.txHash] ?: continue
                        val vout = try { tx.getAsJsonArray("vout")?.get(u.txPos)?.asJsonObject } catch (_: Exception) { null } ?: continue
                        val scriptHex = try { vout.getAsJsonObject("scriptPubKey")?.get("hex")?.asString } catch (_: Exception) { null } ?: continue
                        if ("88acc0" !in scriptHex) continue
                        val parsed = parseAssetFromScript(scriptHex) ?: continue
                        val (assetName, rawAmount) = parsed
                        if (assetName !in balanceNames) continue
                        val outpoint = "${u.txHash}:${u.txPos}"
                        if (!existingAssetOutpoints.add(outpoint)) continue
                        assetOutpoints.add(outpoint)
                        val satoshis = try { ((vout.get("value")?.asDouble ?: 0.0) * 100_000_000.0).toLong() } catch (_: Exception) { 0L }
                        val utxo = Utxo(u.txHash, u.txPos, satoshis, scriptHex, u.height)
                        assetUtxosMap.getOrPut(assetName) { mutableListOf() }.add(AssetUtxo(utxo, assetName, rawAmount))
                        android.util.Log.i("RavencoinPublicNode", "  secondary: re-parsed $assetName from unknown UTXO ${u.txHash}:${u.txPos}")
                    }
                    if (fetchMissingAssetUtxos) {
                        unresolvedAssetNames = balanceNames.filter { assetUtxosMap[it].isNullOrEmpty() }
                    }
                }
            } catch (_: Exception) {}
        }
        if (fetchMissingAssetUtxos && unresolvedAssetNames.isNotEmpty()) {
            throw java.io.IOException("incomplete asset UTXO scan: missing=$unresolvedAssetNames")
        }

        return Triple(rvnUtxos, assetOutpoints, assetUtxosMap)
    }

    /**
     * Returns metadata for [assetName] via the "blockchain.asset.get_meta" call.
     *
     * Handles two variations of the IPFS field name ("ipfs" vs "ipfs_hash") seen
     * across different ElectrumX server implementations. The "has_ipfs" field may
     * be returned as a boolean, an integer (0/1), or a string ("1"/"true"), so it
     * is parsed by the flexible boolean helper [JsonElement.asFlexibleBoolean].
     *
     * @param assetName Full asset name.
     * @return [ElectrumAssetMeta], or null if the asset does not exist or the call fails.
     */
    fun getAssetMeta(assetName: String): ElectrumAssetMeta? {
        return try {
            val result = callWithAssetMetaFailover("blockchain.asset.get_meta", listOf(assetName))
            val obj = result.asJsonObject
            val hasIpfs = obj.get("has_ipfs").asFlexibleBoolean()
            // Accept both "ipfs" and "ipfs_hash" field names for compatibility
            val ipfsHash = obj.get("ipfs")?.asString
                ?: obj.get("ipfs_hash")?.asString
            ElectrumAssetMeta(
                name = assetName,
                totalSupply = obj.get("sats_in_circulation")?.asLong ?: 0L,
                divisions = obj.get("divisions")?.asInt ?: 0,
                reissuable = obj.get("reissuable").asFlexibleBoolean(),
                hasIpfs = hasIpfs,
                ipfsHash = if (hasIpfs) ipfsHash else null
            )
        } catch (_: Exception) { null }
    }

    /**
     * Fetch metadata for multiple assets in a single pipelined TLS connection.
     *
     * Equivalent to calling [getAssetMeta] N times, but uses one batch connection
     * for all N [blockchain.asset.get_meta] requests instead of N separate connections.
     *
     * @param assetNames List of full asset names to look up.
     * @return Map from asset name to [ElectrumAssetMeta] (null value if a specific
     *         asset was not found or its result could not be parsed).
     */
    fun getAssetMetaBatch(assetNames: List<String>): Map<String, ElectrumAssetMeta?> {
        if (assetNames.isEmpty()) return emptyMap()
        val reqs = assetNames.map { name ->
            "blockchain.asset.get_meta" to listOf(name) as List<Any>
        }
        val resps = try { callWithAssetMetaFailoverBatch(reqs) } catch (_: Exception) { return emptyMap() }
        val result = mutableMapOf<String, ElectrumAssetMeta?>()
        assetNames.forEachIndexed { i, name ->
            result[name] = try {
                val obj = resps.getOrNull(i)?.asJsonObject ?: return@forEachIndexed
                val hasIpfs = obj.get("has_ipfs").asFlexibleBoolean()
                val ipfsHash = obj.get("ipfs")?.asString ?: obj.get("ipfs_hash")?.asString
                ElectrumAssetMeta(
                    name = name,
                    totalSupply = obj.get("sats_in_circulation")?.asLong ?: 0L,
                    divisions = obj.get("divisions")?.asInt ?: 0,
                    reissuable = obj.get("reissuable").asFlexibleBoolean(),
                    hasIpfs = hasIpfs,
                    ipfsHash = if (hasIpfs) ipfsHash else null
                )
            } catch (_: Exception) { null }
        }
        return result
    }

    /**
     * Returns up to [limit] transactions for [address], sorted newest-first.
     *
     * This is a suspend function because it performs concurrent network I/O:
     * all required raw transactions are fetched in parallel (with a semaphore
     * limiting concurrent connections to 4) to minimize total latency.
     *
     * Processing steps:
     * 1. Fetch the address transaction history via "blockchain.scripthash.get_history".
     *    Unconfirmed transactions (height = 0) are sorted to the top.
     * 2. Fetch full decoded transaction objects for each history entry.
     * 3. Fetch previous transaction objects for all inputs (vin) to determine
     *    how much value was spent from our address.
     * 4. For each transaction, compute:
     *    - toUs:     sum of all vout values sent to our address.
     *    - toOthers: sum of all vout values sent elsewhere.
     *    - fromUs:   sum of all vin values that previously belonged to our address.
     * 5. Net amount = toUs - fromUs. Positive = incoming, negative = outgoing.
     *
     * @param address Ravencoin P2PKH address.
     * @param limit   Maximum number of history entries to process (default 15).
     * @param offset  Number of entries to skip for pagination (default 0).
     * @return List of [TxHistoryEntry] sorted newest-first, empty on failure.
     */
    fun getTransactionHistory(
        address: String,
        limit: Int = 15,
        offset: Int = 0,
        ownedAddresses: Set<String> = setOf(address)
    ): List<TxHistoryEntry> {
        val scripthash = addressToScripthash(address)
        val owned = if (ownedAddresses.isEmpty()) setOf(address) else ownedAddresses
        // Hash160 of each owned address (lowercase hex). Asset outputs wrap a P2PKH
        // payload inside an OP_RVN_ASSET script; some ElectrumX servers do not expose
        // the inner address in `scriptPubKey.addresses`, so we fall back to hex match.
        val ownedHashes: Set<String> = owned.mapNotNull { addr ->
            try {
                val decoded = base58Decode(addr)
                if (decoded.size < 21) null
                else decoded.copyOfRange(1, 21).joinToString("") { "%02x".format(it) }
            } catch (_: Exception) { null }
        }.toSet()

        // Batch step 1: fetch block height + address history in a single TLS connection
        val step1 = callWithFailoverBatch(listOf(
            "blockchain.headers.subscribe" to emptyList<Any>(),
            "blockchain.scripthash.get_history" to listOf(scripthash)
        ))
        val currentHeight = try { step1[0]?.asJsonObject?.get("height")?.asInt ?: 0 } catch (_: Exception) { 0 }
        val history = try {
            step1[1]?.asJsonArray
                ?.mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
                ?.sortedWith(compareByDescending {
                    val h = it.get("height")?.asInt ?: 0
                    if (h <= 0) Int.MAX_VALUE else h
                })
                ?.drop(offset)
                ?.take(limit)
                ?: emptyList()
        } catch (_: Exception) { return emptyList() }

        if (history.isEmpty()) return emptyList()

        val txHashes = history.mapNotNull { it.get("tx_hash")?.asString }.distinct()

        // Batch step 2: fetch all current-tx bodies in a single TLS connection
        val txBatch = callWithFailoverBatch(
            txHashes.map { "blockchain.transaction.get" to listOf(it, true) }
        )
        val txMap = txHashes.zip(txBatch)
            .mapNotNull { (txId, result) -> result?.let { txId to it.asJsonObject } }
            .toMap()

        // Collect prev-TX IDs from inputs (needed to compute fromUs for outgoing detection)
        val prevTxIds = txMap.values
            .flatMap { tx ->
                tx.getAsJsonArray("vin")
                    ?.mapNotNull { vin ->
                        try { vin.asJsonObject.get("txid")?.asString } catch (_: Exception) { null }
                    }
                    .orEmpty()
            }
            .distinct()
            .filterNot { txMap.containsKey(it) }

        // Batch step 3: fetch all prev-tx bodies in a single TLS connection
        val prevTxMap: Map<String, JsonObject> = if (prevTxIds.isNotEmpty()) {
            val prevBatch = callWithFailoverBatch(
                prevTxIds.map { "blockchain.transaction.get" to listOf(it, true) }
            )
            prevTxIds.zip(prevBatch)
                .mapNotNull { (txId, result) -> result?.let { txId to it.asJsonObject } }
                .toMap()
        } else emptyMap()

        return history.mapNotNull { item ->
            val txHash = item.get("tx_hash")?.asString ?: return@mapNotNull null
            val height = item.get("height")?.asInt ?: 0
            val tx = txMap[txHash] ?: return@mapNotNull null

            // Classify vout per wallet ownership across ALL owned addresses so
            // "cycled" (change back to wallet) is not mis-classified as "sent".
            var toUs = 0L        // vout back to any owned address (incl. change at currentIndex+1)
            var toOthers = 0L    // vout to external addresses (true external send)
            var totalVout = 0L
            var incomingAssetName: String? = null
            var incomingAssetAmount: Long = 0L
            var outgoingAssetName: String? = null
            var outgoingAssetAmount: Long = 0L
            val incomingAssetNamesSet = LinkedHashSet<String>()
            // Issuance detection: track burn to canonical issuance addresses.
            val issuanceBurnAddresses = setOf(
                RavencoinTxBuilder.BURN_ADDRESS_ROOT,
                RavencoinTxBuilder.BURN_ADDRESS_SUB,
                RavencoinTxBuilder.BURN_ADDRESS_UNIQUE
            )
            var isIssuance = false
            var issuanceBurnSat = 0L
            // Track the LAST non-owner-token incoming asset (issuance output is always
            // last in vout order, and owner tokens end with "!").
            var lastIssuedAssetName: String? = null
            var lastIssuedAssetAmount: Long = 0L
            tx.getAsJsonArray("vout")?.forEach { vout ->
                try {
                    val obj = vout.asJsonObject
                    val valueSat = ((obj.get("value")?.asDouble ?: 0.0) * 1e8).toLong()
                    totalVout += valueSat
                    val spk = obj.getAsJsonObject("scriptPubKey")
                    val addresses = spk?.getAsJsonArray("addresses")
                    val hex = spk?.get("hex")?.asString?.lowercase() ?: ""
                    val byAddr = addresses?.any { it.asString in owned } == true
                    val byHex = !byAddr && hex.isNotEmpty() && ownedHashes.any { hex.contains(it) }
                    // Check for issuance burn addresses
                    val vaultAddrMatch = addresses?.any { it.asString in issuanceBurnAddresses } == true
                    if (vaultAddrMatch) {
                        isIssuance = true
                        issuanceBurnSat += valueSat
                    }
                    val ours = byAddr || byHex
                    if (ours) toUs += valueSat else if (!vaultAddrMatch) toOthers += valueSat

                    // Detect asset payload (OP_RVN_ASSET) and tag it as incoming or
                    // outgoing depending on whether the output is to one of our addresses.
                    if (hex.contains("72766e")) {
                        parseAssetPayload(hex)?.let { (name, amount) ->
                            if (ours) {
                                incomingAssetNamesSet.add(name)
                                if (incomingAssetName == null) {
                                    incomingAssetName = name; incomingAssetAmount = amount
                                }
                                // Track last non-owner-token asset (issuance output)
                                if (!name.endsWith("!")) {
                                    lastIssuedAssetName = name
                                    lastIssuedAssetAmount = amount
                                }
                            } else {
                                if (outgoingAssetName == null) {
                                    outgoingAssetName = name; outgoingAssetAmount = amount
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            var fromUs = 0L      // prev-vout value consumed from our inputs
            var totalVin = 0L    // total input value (all vin, regardless of ownership)
            tx.getAsJsonArray("vin")?.forEach { vin ->
                try {
                    val vinObj = vin.asJsonObject
                    val prevTxId = vinObj.get("txid")?.asString ?: return@forEach
                    val prevVoutIdx = vinObj.get("vout")?.asInt ?: return@forEach
                    val prevTx = txMap[prevTxId] ?: prevTxMap[prevTxId] ?: return@forEach
                    val prevVoutObj = prevTx.getAsJsonArray("vout")
                        ?.mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
                        ?.getOrNull(prevVoutIdx) ?: return@forEach
                    val prevValueSat = ((prevVoutObj.get("value")?.asDouble ?: 0.0) * 1e8).toLong()
                    totalVin += prevValueSat
                    val prevSpk = prevVoutObj.getAsJsonObject("scriptPubKey")
                    val prevAddresses = prevSpk?.getAsJsonArray("addresses")
                    val prevByAddr = prevAddresses?.any { it.asString in owned } == true
                    val prevByHex = if (!prevByAddr) {
                        val hex = prevSpk?.get("hex")?.asString?.lowercase() ?: ""
                        hex.isNotEmpty() && ownedHashes.any { hex.contains(it) }
                    } else false
                    if (prevByAddr || prevByHex) fromUs += prevValueSat
                } catch (_: Exception) {}
            }

            val netSat = toUs - fromUs
            val confs = when {
                height <= 0 -> 0
                currentHeight >= height -> currentHeight - height + 1
                else -> 0
            }
            val timestamp = tx.get("blocktime")?.asLong ?: tx.get("time")?.asLong ?: 0L
            // Fee only attributable to us when we contributed inputs.
            val feeSat = if (fromUs > 0L && totalVin > totalVout) totalVin - totalVout else 0L
            // Asset transfers can ride on a 0-sat dust output (Ravencoin allows this when
            // the receiving address is also paid via a separate RVN output in the same tx).
            // Include "asset to non-owned address" as outgoing even when toOthers == 0.
            val isOutgoing = fromUs > 0L && (toOthers > 0L || outgoingAssetName != null)
            val isSelfTransfer = fromUs > 0L && !isOutgoing && toUs > 0L
            // The scripthash query returned this tx, so our address is involved in
            // some way the parser may have missed (asset OP_RVN_ASSET script with
            // no addresses[] and no inner hash160 hex match — happens on some
            // ElectrumX server variants). Treat as incoming when nothing else
            // tagged it as outgoing or self.
            val isHiddenIncoming = !isOutgoing && !isSelfTransfer && fromUs == 0L && toUs == 0L
            // Issuance: use the last non-owner-token asset (consensus: issuance output is
            // always last in vout). This is the NEW token, not a cycled/returned one.
            val effectiveAssetName = if (isIssuance && lastIssuedAssetName != null)
                lastIssuedAssetName else when {
                isOutgoing && outgoingAssetName != null -> outgoingAssetName
                isOutgoing -> null // RVN send (assets cycled to self, not sent out)
                incomingAssetName != null -> incomingAssetName
                else -> null
            }
            val effectiveAssetAmount = if (isIssuance && lastIssuedAssetName != null)
                lastIssuedAssetAmount else when {
                isOutgoing && outgoingAssetName != null -> outgoingAssetAmount
                isOutgoing -> 0L // RVN send
                incomingAssetName != null -> incomingAssetAmount
                else -> 0L
            }
            TxHistoryEntry(
                txid = txHash,
                height = height,
                confirmations = confs,
                amountSat = if (netSat > 0) netSat else 0L,
                sentSat = if (isOutgoing) toOthers else 0L,
                cycledSat = if (isOutgoing || isSelfTransfer) toUs else 0L,
                feeSat = feeSat,
                isIncoming = (netSat > 0 && !isOutgoing) || isHiddenIncoming,
                isSelfTransfer = if (isIssuance) false else isSelfTransfer,
                timestamp = timestamp,
                assetName = effectiveAssetName,
                assetAmount = effectiveAssetAmount,
                incomingAssetNames = incomingAssetNamesSet.toList(),
                isIssuance = isIssuance,
                issuanceBurnSat = issuanceBurnSat
            )
        }
    }

    /**
     * Returns the total number of transactions for an address.
     * Used to determine if more transactions can be loaded.
     *
     * @param address Ravencoin P2PKH address.
     * @return Total transaction count, or 0 on failure.
     */
    fun getTransactionCount(address: String): Int {
        val scripthash = addressToScripthash(address)
        return try {
            val history = callWithFailover("blockchain.scripthash.get_history", listOf(scripthash))
                .asJsonArray
            history.size()
        } catch (_: Exception) { 0 }
    }

    /**
     * D-23 lightweight paged history fetch used by the WalletScreen "Load more" button.
     *
     * Returns `TxHistoryEntry` shells (amount/sent fields = 0) so the UI can insert
     * placeholder rows into [io.raventag.app.wallet.cache.TxHistoryDao] that are then
     * enriched on the next authoritative refresh via [getTransactionHistory].
     *
     * Unlike [getTransactionHistory], this helper:
     *   - Does NOT walk vin/vout to compute amounts (expensive full tx decode).
     *   - Reorders the list so mempool entries (height == 0) come first, then confirmed
     *     rows sorted by height DESC (newest-first).
     *   - Slices `[offset, offset + limit)` client-side.
     *   - Swallows exceptions and returns `emptyList()` so the Load more path is resilient.
     *
     * @param address Ravencoin P2PKH address.
     * @param offset  Zero-based offset into the newest-first ordered list.
     * @param limit   Max rows to return (default 20 per UI-SPEC Load more).
     * @return List of shell [TxHistoryEntry] rows; empty on any failure.
     */
    suspend fun getHistoryPaged(
        address: String,
        offset: Int,
        limit: Int = 20
    ): List<TxHistoryEntry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val scripthash = addressToScripthash(address)
            // Batch: fetch tip height + history in one TLS connection, same pattern as getTransactionHistory.
            val batch = callWithFailoverBatch(listOf(
                "blockchain.headers.subscribe" to emptyList<Any>(),
                "blockchain.scripthash.get_history" to listOf(scripthash)
            ))
            val currentHeight = try {
                batch.getOrNull(0)?.asJsonObject?.get("height")?.asInt ?: 0
            } catch (_: Exception) { 0 }
            val raw = try {
                batch.getOrNull(1)?.asJsonArray
            } catch (_: Exception) { null }
                ?: return@withContext emptyList<TxHistoryEntry>()

            val ordered = raw
                .mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
                .sortedWith(Comparator { a, b ->
                    val ha = a.get("height")?.asInt ?: 0
                    val hb = b.get("height")?.asInt ?: 0
                    // mempool (<=0) sorts first, then confirmed by height DESC
                    val ka = if (ha <= 0) Int.MAX_VALUE else ha
                    val kb = if (hb <= 0) Int.MAX_VALUE else hb
                    kb.compareTo(ka)
                })
                .drop(offset.coerceAtLeast(0))
                .take(limit.coerceAtLeast(0))

            ordered.mapNotNull { item ->
                val txHash = item.get("tx_hash")?.asString ?: return@mapNotNull null
                val height = item.get("height")?.asInt ?: 0
                val confirmations = if (height > 0 && currentHeight > 0) {
                    (currentHeight - height + 1).coerceAtLeast(0)
                } else 0
                TxHistoryEntry(
                    txid = txHash,
                    height = height,
                    confirmations = confirmations,
                    amountSat = 0L,
                    sentSat = 0L,
                    isIncoming = false,
                    isSelfTransfer = false,
                    timestamp = 0L
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Returns true if [address] has any transaction history on-chain.
     *
     * @param address Ravencoin P2PKH address.
     * @return true if the address has at least one on-chain transaction.
     */
    fun hasHistory(address: String): Boolean {
        val scripthash = addressToScripthash(address)
        return try {
            callWithFailover("blockchain.scripthash.get_history", listOf(scripthash))
                .asJsonArray.size() > 0
        } catch (_: Exception) { false }
    }

    /**
     * Tri-state classification of a Ravencoin address for address rotation.
     *
     * - [NO_HISTORY]: address has never appeared on-chain (completely unused).
     * - [RECEIVE_ONLY]: address has received funds but never signed a transaction,
     *   so its public key has never been exposed on-chain (quantum-safe).
     * - [HAS_OUTGOING]: address has signed at least one outgoing transaction,
     *   exposing its public key on-chain (quantum-vulnerable).
     *
     * Detection heuristic: if the number of unspent outputs (UTXOs) is strictly
     * less than the number of history entries, at least one UTXO was consumed,
     * which requires a signature that reveals the public key.
     */
    enum class AddressStatus { NO_HISTORY, RECEIVE_ONLY, HAS_OUTGOING }

    /**
     * Batch variant of [getAddressStatus] for many addresses at once.
     *
     * Uses two pipelined batch calls:
     *   1. `get_history` for all addresses to identify which have on-chain history.
     *   2. `listunspent` only for the subset with history (to distinguish RECEIVE_ONLY from HAS_OUTGOING).
     *
     * With 20 addresses this replaces up to 40 individual TLS connections with 2.
     *
     * @param addresses List of Ravencoin P2PKH addresses.
     * @return Map from address to [AddressStatus]; missing entries default to [AddressStatus.NO_HISTORY].
     */
    fun getAddressStatusBatch(addresses: List<String>): Map<String, AddressStatus> {
        if (addresses.isEmpty()) return emptyMap()
        val scripthashes = addresses.map { addressToScripthash(it) }

        // Batch 1: history for all
        val histReqs = scripthashes.map { sh ->
            "blockchain.scripthash.get_history" to listOf(sh) as List<Any>
        }
        val histResps = callWithFailoverBatch(histReqs)

        val result = mutableMapOf<String, AddressStatus>()
        val histCounts = mutableMapOf<Int, Int>()
        val needsUtxo = mutableListOf<Int>()

        addresses.forEachIndexed { i, addr ->
            val arr = histResps.getOrNull(i)
            val n = if (arr != null && arr.isJsonArray) arr.asJsonArray.size() else 0
            if (n == 0) result[addr] = AddressStatus.NO_HISTORY
            else { histCounts[i] = n; needsUtxo.add(i) }
        }

        if (needsUtxo.isEmpty()) return result

        // Batch 2: get_balance(asset=true) for all addresses with history.
        // Using balance instead of listunspent because listunspent is RVN-only —
        // an address that received only an asset (and zero RVN dust) would otherwise
        // report 0 UTXOs while having 1 history entry, mis-classifying it as HAS_OUTGOING.
        // Balance with asset flag detects asset funds correctly.
        val balReqs = needsUtxo.map { i ->
            "blockchain.scripthash.get_balance" to listOf(scripthashes[i], true) as List<Any>
        }
        val balResps = callWithFailoverBatch(balReqs)

        needsUtxo.forEachIndexed { j, i ->
            val addr = addresses[i]
            val resp = balResps.getOrNull(j)
            val hasFunds = if (resp != null && resp.isJsonObject) {
                val obj = resp.asJsonObject
                val rvnSat = (try { obj.get("confirmed")?.asLong ?: 0L } catch (_: Exception) { 0L }) +
                             (try { obj.get("unconfirmed")?.asLong ?: 0L } catch (_: Exception) { 0L })
                var funds = rvnSat > 0
                if (!funds) {
                    for ((k, v) in obj.entrySet()) {
                        if (k == "confirmed" || k == "unconfirmed") continue
                        try {
                            val a = v.asJsonObject
                            val sat = (a.get("confirmed")?.asLong ?: 0L) + (a.get("unconfirmed")?.asLong ?: 0L)
                            if (sat > 0) { funds = true; break }
                        } catch (_: Exception) {}
                    }
                }
                funds
            } else {
                // Conservative: if balance call failed, assume funds present so we don't
                // wrongly advance the index. The next sync will re-evaluate.
                true
            }
            result[addr] = if (hasFunds) AddressStatus.RECEIVE_ONLY else AddressStatus.HAS_OUTGOING
        }

        return result
    }

    /**
     * Classifies [address] as unused, receive-only, or has-outgoing.
     * Makes at most 2 ElectrumX calls (history + listunspent).
     */
    fun getAddressStatus(address: String): AddressStatus {
        val scripthash = addressToScripthash(address)
        val history = try {
            callWithFailover("blockchain.scripthash.get_history", listOf(scripthash)).asJsonArray
        } catch (_: Exception) { return AddressStatus.NO_HISTORY }
        if (history.size() == 0) return AddressStatus.NO_HISTORY
        val utxos = try {
            callWithFailover("blockchain.scripthash.listunspent", listOf(scripthash)).asJsonArray
        } catch (_: Exception) { return AddressStatus.RECEIVE_ONLY }
        return if (utxos.size() < history.size()) AddressStatus.HAS_OUTGOING
               else AddressStatus.RECEIVE_ONLY
    }

    // Internal helpers ────────────────────────────────────────────────────────

    /**
     * Fetches raw UTXO list for [address] via "blockchain.scripthash.listunspent"
     * without any asset filtering. Used as the data source for both [getUtxos]
     * and the fallback path in [getAssetUtxosFull].
     */
    private fun listUnspentRaw(address: String): List<JsonObject> {
        val scripthash = addressToScripthash(address)
        return callWithFailover("blockchain.scripthash.listunspent", listOf(scripthash))
            .asJsonArray
            .mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
    }

    /**
     * Parses a JSON element as a boolean, tolerating multiple representation formats
     * found across different ElectrumX server implementations:
     * - JSON boolean: true / false
     * - JSON number: non-zero = true, zero = false
     * - JSON string: "1", "true", "yes" = true; everything else = false
     * - null or JSON null: false
     */
    private fun JsonElement?.asFlexibleBoolean(): Boolean {
        if (this == null || isJsonNull) return false
        return runCatching {
            val primitive = asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asInt != 0
                primitive.isString -> {
                    when (primitive.asString.trim().lowercase()) {
                        "1", "true", "yes" -> true
                        else -> false
                    }
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    /**
     * Parse asset name and raw amount from an on-chain OP_RVN_ASSET scriptPubKey hex.
     *
     * Works on transfer scripts ("rvnt" marker, has 8-byte LE amount) and owner-token
     * scripts ("rvno" marker, no amount field, always 100_000_000 raw units).
     * Returns null if the script is not a recognised asset script or if parsing fails.
     *
     * Hex layout after the P2PKH prefix (...88acc0):
     *   <push_byte>  "rvnt"|"rvno"  <1-byte name len>  <name bytes>  [<amount LE64>]
     */
    private fun parseAssetFromScript(scriptHex: String): Pair<String, Long>? {
        return try {
            val hex = scriptHex.lowercase()
            val idx = hex.indexOf("88acc0")
            if (idx < 0 || idx % 2 != 0) return null
            // Byte position just after the 3-byte marker
            var pos = idx / 2 + 3
            if (pos * 2 + 2 > scriptHex.length) return null
            val pushByte = scriptHex.substring(pos * 2, pos * 2 + 2).toInt(16)
            pos++
            val payloadLen = when {
                pushByte in 1..75 -> pushByte
                pushByte == 0x4c -> {   // OP_PUSHDATA1
                    if (pos * 2 + 2 > scriptHex.length) return null
                    val len = scriptHex.substring(pos * 2, pos * 2 + 2).toInt(16)
                    pos++
                    len
                }
                pushByte == 0x4d -> {   // OP_PUSHDATA2
                    if (pos * 2 + 4 > scriptHex.length) return null
                    // 2 bytes LE
                    val low = scriptHex.substring(pos * 2, pos * 2 + 2).toInt(16)
                    val high = scriptHex.substring(pos * 2 + 2, pos * 2 + 4).toInt(16)
                    pos += 2
                    (high shl 8) or low
                }
                else -> return null
            }
            val dataEnd = pos + payloadLen
            if (dataEnd * 2 > scriptHex.length || payloadLen < 6) return null
            // 4-byte type marker
            val marker = buildString {
                for (i in 0..3) append(scriptHex.substring((pos + i) * 2, (pos + i) * 2 + 2).toInt(16).toChar())
            }
            val isTransfer = marker == "rvnt"
            val isOwner    = marker == "rvno"
            val isIssue    = marker == "rvnq"
            val isReissue  = marker == "rvnr"
            if (!isTransfer && !isOwner && !isIssue && !isReissue) return null
            var p = pos + 4
            // compact_size name length (1 byte; names are always < 253 chars)
            val nameLen = scriptHex.substring(p * 2, p * 2 + 2).toInt(16)
            p++
            if ((p + nameLen) * 2 > scriptHex.length) return null
            val assetName = buildString {
                for (i in 0 until nameLen) append(scriptHex.substring((p + i) * 2, (p + i) * 2 + 2).toInt(16).toChar())
            }
            p += nameLen
            val rawAmount: Long = if (isOwner) {
                100_000_000L
            } else {
                if ((p + 8) * 2 > scriptHex.length) return null
                var amt = 0L
                for (i in 0..7) amt = amt or (scriptHex.substring((p + i) * 2, (p + i) * 2 + 2).toLong(16) shl (8 * i))
                amt
            }
            Pair(assetName, rawAmount)
        } catch (_: Exception) { null }
    }

    // buildAssetScriptHex and buildOwnerAssetScriptHex removed.
    // Never reconstruct asset UTXO scripts — a single-byte mismatch with the
    // on-chain scriptPubKey causes bad-txns-bad-asset-transaction rejections.
    // Always fetch the real script from the raw transaction via getAssetUtxosFull.

    /**
     * Converts a Ravencoin P2PKH address to the ElectrumX scripthash format.
     *
     * ElectrumX identifies addresses by the SHA-256 hash of their scriptPubKey,
     * with the bytes in reversed (little-endian) order. This is used as the
     * parameter for all "blockchain.scripthash.*" calls.
     *
     * Steps:
     * 1. Base58Check-decode the address (25 bytes: 1 version + 20 hash160 + 4 checksum).
     * 2. Reconstruct the P2PKH scriptPubKey: OP_DUP OP_HASH160 <hash160> OP_EQUALVERIFY OP_CHECKSIG.
     * 3. SHA-256 hash the script bytes.
     * 4. Reverse the hash bytes to produce the ElectrumX scripthash.
     *
     * @param address Ravencoin P2PKH address.
     * @return Lowercase hex-encoded reversed SHA-256 of the scriptPubKey.
     */
    internal fun addressToScripthash(address: String): String {
        val decoded = base58Decode(address)
        require(decoded.size == 25) { "Invalid Ravencoin address (decoded=${decoded.size} bytes)" }
        val hash160 = decoded.copyOfRange(1, 21)
        // Assemble minimal P2PKH script: 76 a9 14 <hash160> 88 ac
        val script = byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte()) +
                hash160 + byteArrayOf(0x88.toByte(), 0xac.toByte())
        // ElectrumX scripthash = reversed SHA-256 of the scriptPubKey
        return MessageDigest.getInstance("SHA-256").digest(script).reversedArray()
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Builds the hex-encoded P2PKH scriptPubKey for a Ravencoin address.
     *
     * Format: "76a914" + hash160 (20 bytes hex) + "88ac"
     * This is used as the "script" field in RVN-only [Utxo] objects and as the
     * subscript for SIGHASH computation during transaction signing.
     *
     * @param address Ravencoin P2PKH address.
     * @return Lowercase hex string of the P2PKH scriptPubKey.
     */
    private fun p2pkhScriptHex(address: String): String {
        val decoded = base58Decode(address)
        val hash160 = decoded.copyOfRange(1, 21)
        return "76a914" + hash160.joinToString("") { "%02x".format(it) } + "88ac"
    }

    /** Base58 alphabet as used by Bitcoin and Ravencoin (no 0, O, I, l). */
    private val BASE58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /**
     * Decodes a Base58Check-encoded string into raw bytes.
     *
     * Algorithm:
     * 1. Map each character to its index in the Base58 alphabet.
     * 2. Accumulate the value as a big-endian BigInteger (base 58 positional notation).
     * 3. Convert to a byte array, stripping any leading zero byte added by BigInteger.
     * 4. Prepend one zero byte for each leading "1" character in the input
     *    (each "1" encodes a leading zero byte in the original data).
     *
     * Note: this function does not verify the 4-byte checksum; that is the
     * responsibility of the caller if needed.
     *
     * @param input Base58-encoded string.
     * @return Raw byte array.
     * @throws IllegalArgumentException if the string contains an invalid character.
     */
    /**
     * Parse a Ravencoin OP_RVN_ASSET payload from a scriptPubKey hex string.
     * Returns (assetName, rawAmount) when the script carries a transfer/issue/owner
     * marker, null otherwise. Amount is the on-chain integer (sats * 10^divisions).
     */
    private fun parseAssetPayload(hex: String): Pair<String, Long>? {
        // "rvn" magic prefix in hex = 72 76 6e
        var i = hex.indexOf("72766e")
        while (i >= 0) {
            // After "rvn" comes a 1-byte type marker: t=transfer, q=issue, o=owner, r=reissue.
            val typeIdx = i + 6
            if (typeIdx + 2 > hex.length) return null
            val type = hex.substring(typeIdx, typeIdx + 2)
            if (type !in setOf("74", "71", "6f", "72")) {
                i = hex.indexOf("72766e", i + 1); continue
            }
            // After the type byte, 1 byte = name length (hex pair).
            val lenIdx = typeIdx + 2
            if (lenIdx + 2 > hex.length) return null
            val nameLen = hex.substring(lenIdx, lenIdx + 2).toIntOrNull(16) ?: return null
            if (nameLen <= 0 || nameLen > 32) {
                i = hex.indexOf("72766e", i + 1); continue
            }
            val nameStart = lenIdx + 2
            val nameEnd = nameStart + nameLen * 2
            if (nameEnd > hex.length) return null
            val nameBytes = ByteArray(nameLen) { k ->
                hex.substring(nameStart + k * 2, nameStart + k * 2 + 2).toInt(16).toByte()
            }
            val name = String(nameBytes, Charsets.US_ASCII)
            if (!name.all { it.isLetterOrDigit() || it in "/#_-." }) {
                i = hex.indexOf("72766e", i + 1); continue
            }
            // Owner tokens (rvno) carry no amount — return amount 0.
            if (type == "6f") return name to 0L
            // For transfer / issue / reissue, 8 bytes amount little-endian follow.
            val amtEnd = nameEnd + 16
            if (amtEnd > hex.length) return name to 0L
            var amount = 0L
            for (b in 0 until 8) {
                val byteHex = hex.substring(nameEnd + b * 2, nameEnd + b * 2 + 2)
                amount = amount or ((byteHex.toLong(16) and 0xff) shl (b * 8))
            }
            return name to amount
        }
        return null
    }

    private fun base58Decode(input: String): ByteArray {
        var num = BigInteger.ZERO
        for (char in input) {
            val digit = BASE58_CHARS.indexOf(char)
            require(digit >= 0) { "Invalid Base58 character: $char" }
            num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = num.toByteArray()
        // Count leading "1" chars, each representing a zero byte in the decoded output
        val leadingZeros = input.takeWhile { it == '1' }.length
        // BigInteger.toByteArray() may produce a leading 0x00 sign byte; strip it
        val trimmed = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        return ByteArray(leadingZeros) + trimmed
    }

    // ElectrumX JSON-RPC over TLS ─────────────────────────────────────────────

    /**
     * Executes a JSON-RPC call against the server list with automatic failover.
     *
     * Iterates through [SERVERS] in order. If a server throws any exception
     * (connection refused, TLS error, ElectrumX protocol error, timeout, etc.),
     * the error is logged and the next server is tried. If all servers fail,
     * an aggregated exception message is thrown.
     *
     * @param method JSON-RPC method name (e.g., "blockchain.scripthash.get_balance").
     * @param params Ordered list of positional parameters.
     * @return Parsed JSON "result" element.
     * @throws Exception listing all server errors if every server fails.
     */
    private fun callWithFailover(method: String, params: List<Any>): com.google.gson.JsonElement {
        io.raventag.app.wallet.health.NodeHealthMonitor.init(context)
        val errors = mutableListOf<String>()
        var lastError: Throwable? = null
        repeat(SERVERS.size) {
            val candidate = io.raventag.app.wallet.health.NodeHealthMonitor.nextHealthyNode()
                ?: throw AllNodesUnreachableException()
            val (host, portStr) = candidate.split(":", limit = 2)
            val port = portStr.toInt()
            val server = ElectrumServer(host, port)
            try {
                val result = call(server, method, params)
                io.raventag.app.wallet.health.NodeHealthMonitor.reportSuccess(candidate)
                return result
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Server ${server.host} failed for $method: ${e.message}")
                errors.add("${server.host}: ${e.message}")
                if (isTofuMismatch(e)) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(candidate)
                } else {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                        candidate,
                        e.javaClass.simpleName
                    )
                }
            }
        }
        throw lastError
            ?: Exception("All ElectrumX servers failed for $method: ${errors.joinToString("; ")}")
    }

    /**
     * Asset metadata uses Ravencoin-specific Electrum extensions. Some otherwise
     * healthy public nodes do not implement them, so use the known metadata-capable
     * subset directly instead of the generic health rotation.
     */
    private fun callWithAssetMetaFailover(method: String, params: List<Any>): com.google.gson.JsonElement {
        io.raventag.app.wallet.health.NodeHealthMonitor.init(context)
        val errors = mutableListOf<String>()
        var lastError: Throwable? = null
        for (server in ASSET_META_SERVERS) {
            val key = "${server.host}:${server.port}"
            try {
                val result = call(server, method, params)
                io.raventag.app.wallet.health.NodeHealthMonitor.reportSuccess(key)
                return result
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Asset metadata server ${server.host} failed for $method: ${e.message}")
                errors.add("${server.host}: ${e.message}")
                if (isTofuMismatch(e)) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(key)
                }
            }
        }
        throw lastError
            ?: Exception("All asset metadata servers failed for $method: ${errors.joinToString("; ")}")
    }

    /**
     * Detects the TofuTrustManager cert-mismatch exception.
     *
     * TofuTrustManager throws a plain Exception with message
     * "Certificate mismatch for <host>: expected <a>, got <b>" on a pinned
     * cert change. Some TLS stacks wrap this in a CertificateException. We
     * match both so NodeHealthMonitor can write the 1h quarantine row.
     */
    private fun isTofuMismatch(e: Throwable): Boolean {
        if (e is java.security.cert.CertificateException) return true
        val m = e.message ?: return false
        return m.contains("Certificate mismatch", ignoreCase = true) ||
            m.contains("fingerprint mismatch", ignoreCase = true) ||
            m.contains("TOFU", ignoreCase = true)
    }

    /**
     * Executes multiple JSON-RPC calls in a single TLS connection using ElectrumX pipelining.
     *
     * Sends all requests at once after the server.version handshake, then reads all responses
     * matching them back to their requests via the JSON-RPC "id" field. This eliminates the
     * per-call TCP+TLS handshake overhead: N calls cost 1 connection instead of N connections.
     *
     * Large batches are chunked at [BATCH_CHUNK_SIZE] to bound per-chunk socket timeout.
     *
     * @param server   Target ElectrumX server.
     * @param requests List of (method, params) pairs in any order.
     * @return List of results in the same order as [requests]; null for each failed/errored request.
     * @throws Exception on connection or TLS failure (triggers failover in [callWithFailoverBatch]).
     */
    private fun callBatch(
        server: ElectrumServer,
        requests: List<Pair<String, List<Any>>>
    ): List<JsonElement?> {
        if (requests.isEmpty()) return emptyList()
        if (requests.size > BATCH_CHUNK_SIZE) {
            return requests.chunked(BATCH_CHUNK_SIZE).flatMap { callBatch(server, it) }
        }
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(null, arrayOf(TofuTrustManager(context, server.host)), SecureRandom())
        val rawSocket = java.net.Socket()
        rawSocket.connect(InetSocketAddress(server.host, server.port), CONNECT_TIMEOUT_MS)
        val sslSocket = sslCtx.socketFactory.createSocket(rawSocket, server.host, server.port, true) as SSLSocket
        // Scale timeout with batch size so the last response has time to arrive
        sslSocket.soTimeout = READ_TIMEOUT_MS + requests.size * 500
        return sslSocket.use { sock ->
            val writer = PrintWriter(sock.outputStream, true)
            val reader = BufferedReader(InputStreamReader(sock.inputStream))
            // Handshake
            val hsId = idCounter.getAndIncrement()
            writer.println("""{"id":$hsId,"method":"server.version","params":["RavenTag/1.0","1.4"]}""")
            reader.readLine()
            // Send all requests and remember id -> index mapping
            val idToIndex = mutableMapOf<Int, Int>()
            for ((index, req) in requests.withIndex()) {
                val (method, params) = req
                val id = idCounter.getAndIncrement()
                idToIndex[id] = index
                writer.println(gson.toJson(mapOf("id" to id, "method" to method, "params" to params)))
            }
            // Read all responses
            val results = arrayOfNulls<JsonElement>(requests.size)
            var received = 0
            while (received < requests.size) {
                val line = reader.readLine() ?: break
                received++
                try {
                    val json = JsonParser.parseString(line).asJsonObject
                    val id = json.get("id")?.asInt ?: continue
                    val index = idToIndex[id] ?: continue
                    val err = json.get("error")
                    if (err != null && !err.isJsonNull) continue
                    results[index] = json.get("result")
                } catch (_: Exception) {}
            }
            results.toList()
        }
    }

    /**
     * Pipelined multi-call with automatic server failover.
     *
     * Tries each server in [SERVERS] order. Returns a null-filled list only when
     * every server fails (network unreachable or all timeout). Individual request
     * errors within a successful batch are represented as null entries.
     *
     * @param requests List of (method, params) pairs.
     * @return Results in the same order as [requests]; null per failed request.
     */
    private fun callWithFailoverBatch(requests: List<Pair<String, List<Any>>>): List<JsonElement?> {
        if (requests.isEmpty()) return emptyList()
        io.raventag.app.wallet.health.NodeHealthMonitor.init(context)
        repeat(SERVERS.size) {
            val candidate = io.raventag.app.wallet.health.NodeHealthMonitor.nextHealthyNode()
                ?: run {
                    Log.w(TAG, "All nodes quarantined for batch of ${requests.size} — falling back to per-request singles")
                    // Sequential single-RPC fallback: slower but resilient when batch
                    // pipelining fails on every server (common on flaky mobile networks
                    // where the first batch hits a TLS race that closes the socket).
                    return requests.map { (method, params) ->
                        try { callWithFailover(method, params) } catch (_: Exception) { null }
                    }
                }
            val (host, portStr) = candidate.split(":", limit = 2)
            val server = ElectrumServer(host, portStr.toInt())
            try {
                val result = callBatch(server, requests)
                io.raventag.app.wallet.health.NodeHealthMonitor.reportSuccess(candidate)
                return result
            } catch (e: Exception) {
                Log.w(TAG, "Server ${server.host} failed for batch(${requests.size}): ${e.message}")
                if (isTofuMismatch(e)) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(candidate)
                } else {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                        candidate,
                        e.javaClass.simpleName
                    )
                }
            }
        }
        Log.w(TAG, "All servers failed for batch of ${requests.size} requests")
        return List(requests.size) { null }
    }

    private fun callWithAssetMetaFailoverBatch(requests: List<Pair<String, List<Any>>>): List<JsonElement?> {
        if (requests.isEmpty()) return emptyList()
        io.raventag.app.wallet.health.NodeHealthMonitor.init(context)
        for (server in ASSET_META_SERVERS) {
            val key = "${server.host}:${server.port}"
            try {
                val result = callBatch(server, requests)
                if (result.any { it != null }) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportSuccess(key)
                    return result
                }
                Log.w(TAG, "Asset metadata server ${server.host} returned no usable rows for batch(${requests.size})")
            } catch (e: Exception) {
                Log.w(TAG, "Asset metadata server ${server.host} failed for batch(${requests.size}): ${e.message}")
                if (isTofuMismatch(e)) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(key)
                }
            }
        }
        return List(requests.size) { null }
    }

    /**
     * Opens a TLS connection to [server], sends a JSON-RPC request, and returns the
     * parsed "result" element from the response.
     *
     * Protocol details:
     * - Each JSON-RPC message is a single UTF-8 line terminated by newline (line-delimited JSON).
     * - A "server.version" handshake is sent first (required by ElectrumX before most calls),
     *   except when the call itself is "server.version" (to avoid double-handshake).
     * - The response line is parsed as JSON and the "error" field is checked before returning.
     *
     * TLS uses a custom [TofuTrustManager] for each connection to implement TOFU certificate
     * pinning. A raw TCP socket is connected first (with CONNECT_TIMEOUT_MS), then wrapped
     * in an SSL socket to separate TCP and TLS timeout handling.
     *
     * @param server Target ElectrumX server.
     * @param method JSON-RPC method name.
     * @param params Positional parameters.
     * @return Parsed JSON "result" element.
     * @throws Exception on any network, TLS, or protocol error.
     */
    private fun call(server: ElectrumServer, method: String, params: List<Any>): com.google.gson.JsonElement {
        // Create a TLS context with TOFU certificate validation for this server
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(null, arrayOf(TofuTrustManager(context, server.host)), SecureRandom())

        // Connect TCP first with the connect timeout, then upgrade to TLS
        val rawSocket = java.net.Socket()
        rawSocket.connect(InetSocketAddress(server.host, server.port), CONNECT_TIMEOUT_MS)
        val sslSocket = sslCtx.socketFactory.createSocket(rawSocket, server.host, server.port, true) as SSLSocket
        sslSocket.soTimeout = READ_TIMEOUT_MS

        sslSocket.use { sock ->
            val writer = PrintWriter(sock.outputStream, true)
            val reader = BufferedReader(InputStreamReader(sock.inputStream))

            // ElectrumX requires a server.version handshake before accepting other calls
            if (method != "server.version") {
                val hsId = idCounter.getAndIncrement()
                writer.println("""{"id":$hsId,"method":"server.version","params":["RavenTag/1.0","1.4"]}""")
                reader.readLine() // consume and discard the handshake response
            }

            // Send the actual request
            val id = idCounter.getAndIncrement()
            writer.println(gson.toJson(mapOf("id" to id, "method" to method, "params" to params)))

            val response = reader.readLine() ?: throw Exception("Empty response from ${server.host}")
            val json = JsonParser.parseString(response).asJsonObject
            // Check for an ElectrumX-level error response
            val err = json.get("error")
            if (err != null && !err.isJsonNull) throw Exception("ElectrumX error: $err")
            return json.get("result") ?: throw Exception("Null result from ${server.host}")
        }
    }
}

/**
 * D-19 three-value accounting helpers. Pure functions: no network, no storage,
 * safe to unit-test in isolation.
 *
 * Semantics operate on a raw JSON transaction object returned by
 * `blockchain.transaction.get` with verbose=true, i.e. an object with a `vout`
 * array of `{ value: Double (RVN), scriptPubKey: { addresses: [...] } }` entries.
 *
 * Two concepts:
 *  - "cycled" = outputs paying the wallet's change/consolidation address (never-spent
 *    address at currentIndex + 1). This is the RVN that remains under the user's control
 *    after an outgoing send.
 *  - "sent"   = outputs paying ANY address != changeAddress. For self-transfers
 *    (pure consolidations) this returns 0.
 */
object RavencoinTxHistoryMath {

    private const val SAT_PER_RVN = 100_000_000L

    /**
     * Sum (in satoshis) of vout entries whose scriptPubKey.addresses contains
     * [changeAddress]. Malformed entries contribute 0.
     */
    fun computeCycledSat(
        tx: com.google.gson.JsonObject,
        changeAddress: String
    ): Long {
        val vout = try { tx.getAsJsonArray("vout") } catch (_: Exception) { null }
            ?: return 0L
        var total = 0L
        for (element in vout) {
            try {
                val out = element.asJsonObject
                val addresses = out
                    .getAsJsonObject("scriptPubKey")
                    ?.getAsJsonArray("addresses")
                    ?: continue
                val hasChange = addresses.any { it.asString == changeAddress }
                if (hasChange) {
                    val rvn = out.get("value")?.asDouble ?: 0.0
                    total += (rvn * SAT_PER_RVN).toLong()
                }
            } catch (_: Exception) {
                // skip malformed output
            }
        }
        return total
    }

    /**
     * Sum (in satoshis) of vout entries whose scriptPubKey.addresses contains
     * AT LEAST ONE address != [changeAddress]. Conservative: multi-sig outputs
     * with any non-change leg are counted as "sent" for their full value.
     * Malformed entries contribute 0.
     */
    fun computeSentSat(
        tx: com.google.gson.JsonObject,
        changeAddress: String
    ): Long {
        val vout = try { tx.getAsJsonArray("vout") } catch (_: Exception) { null }
            ?: return 0L
        var total = 0L
        for (element in vout) {
            try {
                val out = element.asJsonObject
                val addresses = out
                    .getAsJsonObject("scriptPubKey")
                    ?.getAsJsonArray("addresses")
                    ?: continue
                val external = addresses.any { it.asString != changeAddress }
                if (external) {
                    val rvn = out.get("value")?.asDouble ?: 0.0
                    total += (rvn * SAT_PER_RVN).toLong()
                }
            } catch (_: Exception) {
                // skip malformed output
            }
        }
        return total
    }
}
