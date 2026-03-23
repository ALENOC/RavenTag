package io.raventag.app.wallet

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
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val height: Int,         // 0 = unconfirmed/mempool
    val confirmations: Int,
    val amountSat: Long,     // positive = received to our address
    val sentSat: Long,       // positive = sent to other addresses
    val isIncoming: Boolean, // true if amountSat > 0 (our address in vout)
    val timestamp: Long = 0L // Unix timestamp in seconds (0 if unknown)
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
class RavencoinPublicNode {

    companion object {
        private const val TAG = "ElectrumX"

        /** Timeout for the TCP connection handshake in milliseconds. */
        private const val CONNECT_TIMEOUT_MS = 12_000

        /** Timeout for reading a response line from the server in milliseconds. */
        private const val READ_TIMEOUT_MS = 15_000

        /**
         * List of public Ravencoin ElectrumX servers, tried in order.
         * All use the standard TLS port 50002.
         * New servers can be added here; removal of dead servers avoids unnecessary
         * timeout delays on every request.
         */
        private val SERVERS = listOf(
            ElectrumServer("rvn4lyfe.com", 50002),
            ElectrumServer("rvn-dashboard.com", 50002),
            ElectrumServer("162.19.153.65", 50002),
            ElectrumServer("51.222.139.25", 50002),
        )

        /**
         * Monotonically increasing request ID counter, shared across all instances.
         * ElectrumX requires a unique integer "id" in each JSON-RPC request so that
         * responses can be matched to their requests (though we use synchronous I/O
         * here, so matching is implicit).
         */
        private val idCounter = AtomicInteger(1)

        /** Shared Gson instance for serializing JSON-RPC request objects. */
        private val gson = Gson()

        /**
         * TOFU certificate fingerprint cache: hostname -> SHA-256 hex string.
         * Thread-safe via ConcurrentHashMap. Scoped to the process lifetime.
         */
        private val certCache = ConcurrentHashMap<String, String>()
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
     * Returns only RVN-carrying UTXOs for [address].
     *
     * Asset UTXOs are intentionally filtered out to prevent accidental asset
     * destruction when building a RVN-only transaction. Different ElectrumX
     * server implementations represent RVN UTXOs in slightly different ways:
     * - No "asset" field at all: plain RVN UTXO.
     * - "asset": null: plain RVN UTXO.
     * - "asset": "RVN" (primitive string): RVN UTXO flagged explicitly.
     * - "asset": { "name": "RVN", ... } (object): RVN UTXO with object notation.
     * All four variants are treated as spendable RVN.
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
            // Navigate vout[txPos].scriptPubKey.hex in the decoded transaction
            val onChainScript = try {
                tx?.getAsJsonArray("vout")
                    ?.get(txPos)
                    ?.asJsonObject
                    ?.getAsJsonObject("scriptPubKey")
                    ?.get("hex")
                    ?.asString
            } catch (_: Exception) { null }
            val script = onChainScript ?: if (assetName.endsWith("!")) {
                buildOwnerAssetScriptHex(address, assetName)
            } else {
                buildAssetScriptHex(address, assetName, rawAmount)
            }
            val utxo = Utxo(
                txid = txHash,
                outputIndex = txPos,
                satoshis = satoshis,
                script = script,
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
                        if ("88acc0" in scriptHex) result.add("$txHash:$txPos")
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
            val result = callWithFailover("blockchain.asset.get_meta", listOf(assetName))
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
    suspend fun getTransactionHistory(address: String, limit: Int = 15, offset: Int = 0): List<TxHistoryEntry> = coroutineScope {
        val currentHeight = try { getBlockHeight() ?: 0 } catch (_: Exception) { 0 }
        val scripthash = addressToScripthash(address)
        val history = try {
            callWithFailover("blockchain.scripthash.get_history", listOf(scripthash))
                .asJsonArray
                .mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
                .sortedWith(compareByDescending {
                    val h = it.get("height")?.asInt ?: 0
                    // Unconfirmed transactions have height=0 or negative; sort them first
                    // by mapping 0/negative to Int.MAX_VALUE
                    if (h <= 0) Int.MAX_VALUE else h
                })
                .drop(offset)
                .take(limit)
        } catch (_: Exception) { return@coroutineScope emptyList() }

        // Semaphore limits concurrent ElectrumX connections to avoid overwhelming servers
        val requestLimiter = Semaphore(4)
        suspend fun fetchTransaction(txId: String): JsonObject? = requestLimiter.withPermit {
            try {
                // Pass "true" to get the verbose/decoded JSON form (not just hex)
                callWithFailover("blockchain.transaction.get", listOf(txId, true)).asJsonObject
            } catch (_: Exception) {
                null
            }
        }

        // Fetch all current transactions in parallel
        val txHashes = history.mapNotNull { it.get("tx_hash")?.asString }
        val txMap = txHashes
            .map { txId -> async { txId to fetchTransaction(txId) } }
            .awaitAll()
            .mapNotNull { (txId, tx) -> tx?.let { txId to it } }
            .toMap()

        // Collect all previous transaction IDs referenced by the inputs of our transactions
        // (needed to determine whether a vin was funded by our address)
        val prevTxIds = txMap.values
            .flatMap { tx ->
                tx.getAsJsonArray("vin")
                    ?.mapNotNull { vin ->
                        try { vin.asJsonObject.get("txid")?.asString } catch (_: Exception) { null }
                    }
                    .orEmpty()
            }
            .distinct()

        // Fetch previous transactions that are not already in txMap (avoid redundant fetches)
        val prevTxMap = prevTxIds
            .filterNot { txMap.containsKey(it) }
            .map { txId -> async { txId to fetchTransaction(txId) } }
            .awaitAll()
            .mapNotNull { (txId, tx) -> tx?.let { txId to it } }
            .toMap()

        history.mapNotNull { item ->
            val txHash = item.get("tx_hash")?.asString ?: return@mapNotNull null
            val height = item.get("height")?.asInt ?: 0
            val tx = txMap[txHash] ?: return@mapNotNull null

            // Compute how much the transaction sent to our address (sum of matching vout values)
            var toUs = 0L
            var toOthers = 0L
            tx.getAsJsonArray("vout")?.forEach { vout ->
                try {
                    val obj = vout.asJsonObject
                    // vout value is in RVN (floating-point); multiply by 1e8 to get satoshis
                    val valueSat = ((obj.get("value")?.asDouble ?: 0.0) * 1e8).toLong()
                    val spk = obj.getAsJsonObject("scriptPubKey")
                    val addresses = spk?.getAsJsonArray("addresses")
                    if (addresses?.any { it.asString == address } == true) toUs += valueSat
                    else toOthers += valueSat
                } catch (_: Exception) {}
            }

            // Compute how much was spent from our address (sum of vin values where we owned the output)
            var fromUs = 0L
            tx.getAsJsonArray("vin")?.forEach { vin ->
                try {
                    val vinObj = vin.asJsonObject
                    val prevTxId = vinObj.get("txid")?.asString ?: return@forEach
                    val prevVoutIdx = vinObj.get("vout")?.asInt ?: return@forEach
                    // Look up the previous transaction in both caches
                    val prevTx = txMap[prevTxId] ?: prevTxMap[prevTxId] ?: return@forEach
                    val prevVoutObj = prevTx.getAsJsonArray("vout")
                        ?.mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
                        ?.getOrNull(prevVoutIdx) ?: return@forEach
                    val prevValueSat = ((prevVoutObj.get("value")?.asDouble ?: 0.0) * 1e8).toLong()
                    val prevSpk = prevVoutObj.getAsJsonObject("scriptPubKey")
                    val prevAddresses = prevSpk?.getAsJsonArray("addresses")
                    // Only count this input as "from us" if the previous output was ours
                    if (prevAddresses?.any { it.asString == address } == true) fromUs += prevValueSat
                } catch (_: Exception) {}
            }

            // Net from our perspective: positive = received, negative = sent
            val netSat = toUs - fromUs
            val confs = when {
                height <= 0 -> 0  // unconfirmed
                currentHeight >= height -> currentHeight - height + 1
                else -> 0
            }
            // Prefer "blocktime" (set when mined) over "time" (set when first seen in mempool)
            val timestamp = tx.get("time")?.asLong ?: tx.get("blocktime")?.asLong ?: 0L
            TxHistoryEntry(
                txid = txHash,
                height = height,
                confirmations = confs,
                amountSat = if (netSat > 0) netSat else 0L,
                sentSat = if (netSat < 0) -netSat else 0L,
                isIncoming = netSat > 0,
                timestamp = timestamp
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
    suspend fun getTransactionCount(address: String): Int {
        val scripthash = addressToScripthash(address)
        return try {
            val history = callWithFailover("blockchain.scripthash.get_history", listOf(scripthash))
                .asJsonArray
            history.size()
        } catch (_: Exception) { 0 }
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
     * Reconstructs the full asset transfer scriptPubKey for an address, asset name, and amount.
     *
     * This is used as a fallback when the raw transaction cannot be fetched from the server.
     * The reconstructed script matches the on-chain format used by the Ravencoin protocol.
     *
     * Script structure:
     *   OP_DUP OP_HASH160 <hash160(20 bytes)> OP_EQUALVERIFY OP_CHECKSIG
     *   OP_RVN_ASSET <push(payload)> OP_DROP
     *
     * Asset payload (rvnt format):
     *   "rvnt" (4 bytes) || compact_size(name_len) (1 byte) || name_bytes || LE64(raw_amount) (8 bytes)
     *
     * Opcodes used:
     *   0x76 = OP_DUP, 0xa9 = OP_HASH160, 0x14 = push 20 bytes,
     *   0x88 = OP_EQUALVERIFY, 0xac = OP_CHECKSIG,
     *   0xc0 = OP_RVN_ASSET (Ravencoin custom opcode), 0x75 = OP_DROP
     *
     * @param address   Ravencoin P2PKH address (provides the hash160).
     * @param assetName Asset name in ASCII.
     * @param rawAmount Asset amount in raw units (not divided by 10^divisions).
     * @return Hex-encoded scriptPubKey.
     */
    private fun buildAssetScriptHex(address: String, assetName: String, rawAmount: Long): String {
        val decoded = base58Decode(address)
        require(decoded.size == 25) { "Invalid Ravencoin address" }
        // Bytes 1..20 of the decoded address are the hash160 (RIPEMD160(SHA256(pubkey)))
        val hash160 = decoded.copyOfRange(1, 21)
        val nameBytes = assetName.toByteArray(Charsets.US_ASCII)
        // Asset data: "rvnt" + 1-byte name length + name + 8-byte LE amount
        val payload = ByteArray(4 + 1 + nameBytes.size + 8)
        payload[0] = 0x72.toByte(); payload[1] = 0x76.toByte(); payload[2] = 0x6e.toByte(); payload[3] = 0x74.toByte()
        payload[4] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, payload, 5, nameBytes.size)
        // Write raw_amount as 8 bytes little-endian starting at offset 5 + name length
        val off = 5 + nameBytes.size
        payload[off + 0] = rawAmount.toByte(); payload[off + 1] = (rawAmount shr 8).toByte()
        payload[off + 2] = (rawAmount shr 16).toByte(); payload[off + 3] = (rawAmount shr 24).toByte()
        payload[off + 4] = (rawAmount shr 32).toByte(); payload[off + 5] = (rawAmount shr 40).toByte()
        payload[off + 6] = (rawAmount shr 48).toByte(); payload[off + 7] = (rawAmount shr 56).toByte()
        // Assemble the full script using a byte stream
        val script = java.io.ByteArrayOutputStream()
        // OP_DUP OP_HASH160 <push 20>
        script.write(byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte()))
        script.write(hash160)
        // OP_EQUALVERIFY OP_CHECKSIG OP_RVN_ASSET
        script.write(byteArrayOf(0x88.toByte(), 0xac.toByte(), 0xc0.toByte()))
        // Push payload: use OP_PUSHDATA1 (0x4c) for payloads 76..255 bytes
        when {
            payload.size <= 75 -> { script.write(payload.size); script.write(payload) }
            payload.size <= 255 -> { script.write(0x4c); script.write(payload.size); script.write(payload) }
            else -> throw IllegalArgumentException("Asset script payload too large: ${payload.size}")
        }
        // OP_DROP
        script.write(0x75)
        return script.toByteArray().joinToString("") { "%02x".format(it) }
    }

    /**
     * Reconstructs the full owner-token scriptPubKey for an address and owner asset name.
     *
     * Owner tokens are special outputs created alongside the main asset (or sub-asset).
     * They use the "rvno" payload marker instead of "rvnt" and do not include an amount
     * field, since owner tokens always have exactly 1 token (represented as 100000000
     * raw units internally).
     *
     * Script structure is identical to [buildAssetScriptHex] except the payload uses
     * the "rvno" four-byte header (0x72 0x76 0x6e 0x6f) and has no amount bytes.
     *
     * @param address   Ravencoin P2PKH address.
     * @param assetName Owner asset name, must end with "!" (e.g., "BRAND!").
     * @return Hex-encoded scriptPubKey.
     */
    private fun buildOwnerAssetScriptHex(address: String, assetName: String): String {
        val decoded = base58Decode(address)
        require(decoded.size == 25) { "Invalid Ravencoin address" }
        val hash160 = decoded.copyOfRange(1, 21)
        val nameBytes = assetName.toByteArray(Charsets.US_ASCII)

        // Owner payload: "rvno" + 1-byte name length + name (no amount field)
        val payload = ByteArray(4 + 1 + nameBytes.size)
        payload[0] = 0x72.toByte(); payload[1] = 0x76.toByte(); payload[2] = 0x6e.toByte(); payload[3] = 0x6f.toByte()
        payload[4] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, payload, 5, nameBytes.size)

        val script = java.io.ByteArrayOutputStream()
        script.write(byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte()))
        script.write(hash160)
        script.write(byteArrayOf(0x88.toByte(), 0xac.toByte(), 0xc0.toByte()))
        when {
            payload.size <= 75 -> { script.write(payload.size); script.write(payload) }
            payload.size <= 255 -> { script.write(0x4c); script.write(payload.size); script.write(payload) }
            else -> throw IllegalArgumentException("Owner asset script payload too large: ${payload.size}")
        }
        script.write(0x75)
        return script.toByteArray().joinToString("") { "%02x".format(it) }
    }

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
    private fun addressToScripthash(address: String): String {
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
        val errors = mutableListOf<String>()
        for (server in SERVERS) {
            try {
                return call(server, method, params)
            } catch (e: Exception) {
                Log.w(TAG, "Server ${server.host} failed for $method: ${e.message}")
                errors.add("${server.host}: ${e.message}")
            }
        }
        throw Exception("All ElectrumX servers failed for $method: ${errors.joinToString("; ")}")
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
        sslCtx.init(null, arrayOf(TofuTrustManager(server.host)), SecureRandom())

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

    /**
     * TOFU (Trust On First Use) TrustManager for ElectrumX self-signed TLS certificates.
     *
     * Standard certificate authority validation is not used because ElectrumX servers
     * commonly use self-signed certificates. TOFU provides a practical security model:
     *
     * - First connection to a host: the server's SHA-256 fingerprint is computed from
     *   the raw DER-encoded certificate bytes and stored in the in-process [certCache].
     *   The connection is allowed.
     * - Subsequent connections to the same host: the fingerprint is verified against
     *   the cached value. If it differs, the connection is rejected with an exception
     *   to protect against man-in-the-middle attacks.
     *
     * Limitation: the cache is not persisted, so a certificate change across process
     * restarts is silently accepted (pinned fresh). This is an acceptable trade-off
     * for a mobile wallet that rotates processes frequently.
     *
     * @param host Hostname of the ElectrumX server, used as the cache key.
     */
    private class TofuTrustManager(private val host: String) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw Exception("No certificate from $host")
            // Compute SHA-256 fingerprint of the raw DER-encoded certificate
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02x".format(it) }
            // putIfAbsent returns the existing value if already pinned, or null if this is the first pin
            val existing = certCache.putIfAbsent(host, fingerprint)
            if (existing != null && existing != fingerprint) {
                // Certificate changed since last pin: possible MITM, reject immediately
                throw Exception("Certificate mismatch for $host: expected $existing, got $fingerprint")
            }
            if (existing == null) Log.i(TAG, "TOFU: pinned $host")
        }
    }
}
