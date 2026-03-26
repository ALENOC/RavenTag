/**
 * AssetManager.kt
 *
 * Backend API client for all brand asset operations in RavenTag.
 *
 * Provides authenticated HTTP calls (X-Admin-Key header) to the RavenTag backend
 * for asset lifecycle management:
 *  - Issue root assets (500 RVN fee), sub-assets (100 RVN fee), and unique tokens (5 RVN fee)
 *  - Revoke and un-revoke assets (soft revocation in the SQLite revocation table)
 *  - Check revocation status (public endpoint, no auth required)
 *  - Upload RTP-1 metadata JSON to IPFS via backend proxy
 *  - Derive per-chip AES-128 keys from the brand BRAND_MASTER_KEY (slot-prefixed AES-ECB)
 *  - Server-side SUN verification via POST /api/verify/tag (chip keys never leave backend)
 *
 * Fail-closed security policy: if the backend is unreachable when checking revocation,
 * the method returns revoked=true to prevent authentic-looking results on offline devices.
 */
package io.raventag.app.wallet

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.raventag.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Parameters for issuing a new root Ravencoin asset.
 *
 * @property assetName  Root asset name (uppercase). Must be 3-12 alphanumeric characters.
 * @property qty        Supply quantity. Typically 1 for brand ownership tokens.
 * @property toAddress  Ravencoin P2PKH address to receive the issued asset.
 * @property units      Decimal places (0-8). Use 0 for non-divisible tokens.
 * @property reissuable Whether additional supply can be minted later.
 * @property ipfsHash   Optional CIDv0 IPFS hash of RTP-1 metadata JSON.
 */
data class AssetIssueParams(
    val assetName: String,
    val qty: Long = 1,
    val toAddress: String,
    val units: Int = 0,
    val reissuable: Boolean = false,
    val ipfsHash: String? = null
)

/**
 * Parameters for issuing a Ravencoin sub-asset (PARENT/CHILD).
 *
 * @property parentAsset  Name of the already-owned parent asset.
 * @property childName    Child asset segment (appended after '/').
 * @property qty          Supply quantity, typically 1 per product item.
 * @property toAddress    Ravencoin P2PKH address to receive the issued sub-asset.
 * @property units        Decimal places (0-8).
 * @property reissuable   Whether additional supply can be minted later.
 * @property ipfsHash     Optional CIDv0 IPFS hash of RTP-1 metadata JSON.
 */
data class SubAssetIssueParams(
    val parentAsset: String,
    val childName: String,
    val qty: Long = 1,
    val toAddress: String,
    val units: Int = 0,
    val reissuable: Boolean = false,
    val ipfsHash: String? = null
)

/**
 * Parameters for revoking (and optionally burning) a Ravencoin asset.
 *
 * @property assetName    Full asset name to revoke, including any sub-asset path.
 * @property qty          Quantity to burn if burnOnChain is true.
 * @property reason       Human-readable revocation reason (e.g. "Counterfeit detected").
 * @property burnOnChain  If true, also transfer the asset to the Ravencoin burn address
 *                        (RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV), making revocation permanent.
 */
data class BurnParams(
    val assetName: String,
    val qty: Long = 1,
    val reason: String? = null,
    val burnOnChain: Boolean = false
)

/**
 * Result of an asset issuance, revocation, or transfer operation.
 *
 * @property success    True if the backend accepted and executed the request.
 * @property txid       Ravencoin transaction ID if an on-chain transaction was broadcast.
 * @property assetName  Canonical asset name as returned by the backend.
 * @property error      Human-readable error description when success is false.
 */
data class AssetOperationResult(
    val success: Boolean,
    val txid: String? = null,
    val assetName: String? = null,
    val error: String? = null
)

/**
 * Revocation state for a Ravencoin asset, as recorded in the backend SQLite table.
 *
 * @property revoked    True if the asset is marked as revoked (counterfeit or withdrawn).
 * @property reason     Optional human-readable revocation reason.
 * @property revokedAt  Unix timestamp (ms) when revocation was recorded, or null.
 */
data class RevocationStatus(
    val revoked: Boolean,
    val reason: String? = null,
    val revokedAt: Long? = null
)

/**
 * Full verification response from the backend POST /api/verify/tag endpoint.
 *
 * The backend performs SUN decryption, MAC verification, and blockchain asset lookup
 * using keys that never leave the server, then returns this structured result.
 *
 * @property authentic      True only when all checks (SUN MAC, asset existence, not revoked) pass.
 * @property tagUid         7-byte chip UID in lowercase hex, recovered from PICCData decryption.
 * @property counter        SDMReadCtr value from the decrypted PICCData.
 * @property nfcPubId       SHA-256(uid || BRAND_SALT) as lowercase hex, for display.
 * @property assetName      Canonical Ravencoin asset name linked to this chip.
 * @property metadata       Parsed RTP-1 metadata from IPFS, if available.
 * @property revoked        True if the asset appears in the revocation table.
 * @property revokedReason  Human-readable revocation reason, if revoked.
 * @property error          Error message when authentic is false.
 * @property stepFailed     Which verification step failed (e.g. "sun_mac", "blockchain").
 */
data class ServerVerifyResponse(
    val authentic: Boolean,
    val tagUid: String? = null,
    val counter: Int? = null,
    val nfcPubId: String? = null,
    val assetName: String? = null,
    val metadata: io.raventag.app.ravencoin.RaventagMetadata? = null,
    val revoked: Boolean = false,
    val revokedReason: String? = null,
    val error: String? = null,
    val stepFailed: String? = null
)

/**
 * Per-chip AES-128 keys derived from the brand BRAND_MASTER_KEY by the backend.
 *
 * The backend uses slot-prefixed AES-ECB key diversification to produce four independent
 * 16-byte keys from a single brand master key and the chip UID. The nfc_pub_id is
 * SHA-256(uid || BRAND_SALT), ensuring the raw UID is not recoverable from the public ID.
 *
 * @property appMasterKey   Key 0: NTAG 424 DNA application master key.
 * @property sdmmacInputKey Key 1: reserved for SDMCtrRet or future SDM input uses.
 * @property sdmEncKey      Key 2 (K_SDMMetaRead): AES-CBC encrypts PICCData in the SUN URL.
 * @property sdmMacKey      Key 3 (K_SDMFileRead): base key for session MAC key derivation.
 * @property nfcPubId       SHA-256(uid || BRAND_SALT) as lowercase hex string.
 */
data class DerivedChipKeys(
    val appMasterKey: ByteArray,
    val sdmmacInputKey: ByteArray,
    val sdmEncKey: ByteArray,
    val sdmMacKey: ByteArray,
    val nfcPubId: String
)

/**
 * Backend API client for RavenTag brand asset operations.
 *
 * All write operations (issue, revoke, transfer) require a valid admin key sent via the
 * X-Admin-Key header. The revocation check endpoint is public (no auth required).
 *
 * @param apiBaseUrl  Base URL of the RavenTag backend, from BuildConfig.API_BASE_URL.
 * @param adminKey    Brand admin key (ADMIN_KEY env var on backend side).
 */
class AssetManager(
    private val apiBaseUrl: String = BuildConfig.API_BASE_URL,
    private val adminKey: String = ""
) {
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    /** OkHttp client with generous timeouts for blockchain operations that may take several seconds. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Check whether the backend server is reachable by hitting /health or /api/health.
     * Returns true if the server is up and responding with a 2xx status code.
     */
    fun checkHealth(): Boolean {
        return try {
            // Try /health (new standardized endpoint) then /api/health (legacy)
            val endpoints = listOf("/health", "/api/health")
            var success = false
            for (path in endpoints) {
                val request = Request.Builder()
                    .url("${apiBaseUrl.trimEnd('/')}$path")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        success = true
                    }
                }
                if (success) break
            }
            success
        } catch (e: Exception) {
            Log.e("AssetManager", "Health check failed for $apiBaseUrl: ${e.message}")
            false
        }
    }

    /**
     * Execute an authenticated HTTP request and parse the JSON response.
     *
     * Sends the X-Admin-Key header for operator authentication. Throws IOException
     * if the server returns a non-2xx status code, using the "error" field from the
     * response body as the exception message when available.
     */
    private fun adminRequest(method: String, path: String, body: Any? = null): JsonObject {
        val rb = body?.let { gson.toJson(it).toRequestBody(json) }
        val request = Request.Builder()
            .url("$apiBaseUrl$path")
            .header("X-Admin-Key", adminKey)
            .apply {
                when (method) {
                    "POST" -> post(rb ?: "{}".toRequestBody(json))
                    "DELETE" -> delete(rb)
                    else -> get()
                }
            }
            .build()

        val response = http.newCall(request).execute()
        val bodyStr = response.body?.string() ?: "{}"
        val obj = gson.fromJson(bodyStr, JsonObject::class.java)

        if (!response.isSuccessful) {
            val errMsg = obj["error"]?.asString ?: "HTTP ${response.code}"
            throw IOException(errMsg)
        }
        return obj
    }

    /**
     * Issue a new root Ravencoin asset via POST /api/brand/issue.
     *
     * Requires 500 RVN in the brand node wallet to cover the protocol burn fee.
     * The asset name is converted to uppercase before submission.
     */
    fun issueAsset(params: AssetIssueParams): AssetOperationResult {
        return try {
            val body = mapOf(
                "asset_name" to params.assetName.uppercase(),
                "qty" to params.qty,
                "to_address" to params.toAddress,
                "units" to params.units,
                "reissuable" to params.reissuable,
                "ipfs_hash" to params.ipfsHash
            ).filter { it.value != null }

            val resp = adminRequest("POST", "/api/brand/issue", body)
            AssetOperationResult(
                success = resp["success"]?.asBoolean == true,
                txid = resp["txid"]?.asString,
                assetName = resp["asset_name"]?.asString
            )
        } catch (e: Exception) {
            AssetOperationResult(success = false, error = e.message)
        }
    }

    /**
     * Issue a Ravencoin sub-asset (PARENT/CHILD) via POST /api/brand/issue-sub.
     *
     * Requires 100 RVN in the brand node wallet. Both parent and child names are
     * uppercased before submission.
     */
    fun issueSubAsset(params: SubAssetIssueParams): AssetOperationResult {
        return try {
            val body = mapOf(
                "parent_asset" to params.parentAsset.uppercase(),
                "child_name" to params.childName.uppercase(),
                "qty" to params.qty,
                "to_address" to params.toAddress,
                "units" to params.units,
                "reissuable" to params.reissuable,
                "ipfs_hash" to params.ipfsHash
            ).filter { it.value != null }

            val resp = adminRequest("POST", "/api/brand/issue-sub", body)
            AssetOperationResult(
                success = resp["success"]?.asBoolean == true,
                txid = resp["txid"]?.asString,
                assetName = resp["asset_name"]?.asString
            )
        } catch (e: Exception) {
            AssetOperationResult(success = false, error = e.message)
        }
    }

    /**
     * Revoke an asset via POST /api/brand/revoke.
     *
     * Soft revocation adds the asset to the backend SQLite revocation table.
     * If burnOnChain is true, the backend also transfers the asset to the Ravencoin
     * burn address (RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV), making revocation permanent.
     */
    fun revokeAsset(params: BurnParams): AssetOperationResult {
        return try {
            val body = mapOf(
                "asset_name" to params.assetName.uppercase(),
                "reason" to (params.reason ?: "Counterfeit detected"),
                "burn_on_chain" to params.burnOnChain,
                "qty" to params.qty
            )
            val resp = adminRequest("POST", "/api/brand/revoke", body)
            AssetOperationResult(
                success = resp["success"]?.asBoolean == true,
                txid = resp["burn_txid"]?.asString,
                assetName = params.assetName
            )
        } catch (e: Exception) {
            AssetOperationResult(success = false, error = e.message)
        }
    }

    /**
     * Remove revocation for an asset via DELETE /api/brand/revoke/:name.
     *
     * Only works for soft-revoked assets. If the asset was also burned on-chain,
     * the on-chain burn cannot be reversed, but the backend revocation record is removed.
     */
    fun unrevokeAsset(assetName: String): AssetOperationResult {
        return try {
            val encoded = java.net.URLEncoder.encode(assetName.uppercase(), "UTF-8")
            val resp = adminRequest("DELETE", "/api/brand/revoke/$encoded")
            AssetOperationResult(
                success = resp["success"]?.asBoolean == true,
                assetName = resp["asset_name"]?.asString ?: assetName
            )
        } catch (e: Exception) {
            AssetOperationResult(success = false, error = e.message)
        }
    }

    /**
     * Check whether an asset is revoked via GET /api/assets/:name/revocation.
     *
     * This endpoint is public and requires no authentication. If the backend is
     * unreachable, this method returns revoked=true (fail-closed) to prevent a
     * network outage from being exploited to bypass revocation checks.
     */
    fun checkRevocationStatus(assetName: String): RevocationStatus {
        return try {
            val request = Request.Builder()
                .url("$apiBaseUrl/api/assets/${assetName.uppercase()}/revocation")
                .get()
                .build()
            val response = http.newCall(request).execute()
            val obj = gson.fromJson(response.body?.string(), JsonObject::class.java)
            RevocationStatus(
                revoked = obj["revoked"]?.asBoolean == true,
                reason = obj["reason"]?.takeIf { !it.isJsonNull }?.asString,
                revokedAt = obj["revokedAt"]?.takeIf { !it.isJsonNull }?.asLong
            )
        } catch (e: Exception) {
            // Fail closed: if backend is unreachable, do not declare authentic.
            // An attacker could block connectivity to bypass revocation; treating
            // unreachable as revoked prevents that attack.
            RevocationStatus(revoked = true, reason = "Revocation status unavailable, tag cannot be verified")
        }
    }

    /**
     * Upload RTP-1 metadata JSON to IPFS via POST /api/brand/upload-metadata.
     *
     * The backend proxies the upload to a configured IPFS node (Kubo or Pinata).
     *
     * @return CIDv0 IPFS hash (starting with "Qm") on success, or null on error.
     */
    fun uploadMetadata(metadata: Map<String, Any?>): String? {
        return try {
            val resp = adminRequest("POST", "/api/brand/upload-metadata", metadata)
            resp["ipfs_hash"]?.asString
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Issue a Ravencoin unique token (PARENT/SUB#SERIAL) via POST /api/brand/issue-unique.
     *
     * Unique tokens are non-fungible: the '#' separator is part of the Ravencoin asset name.
     * Cost: 5 RVN per unique token. Both parentSubAsset and serial are uppercased.
     *
     * @param parentSubAsset  The parent sub-asset path (e.g. "BRAND/ITEM").
     * @param serial          The unique serial identifier appended after '#'.
     * @param toAddress       Ravencoin address to receive the unique token.
     * @param ipfsHash        Optional CIDv0 IPFS hash of per-item RTP-1 metadata.
     */
    fun issueUniqueToken(
        parentSubAsset: String,
        serial: String,
        toAddress: String,
        ipfsHash: String? = null
    ): AssetOperationResult {
        return try {
            val body = mutableMapOf<String, Any>(
                "parent_sub_asset" to parentSubAsset.uppercase(),
                "asset_tags" to listOf(serial.uppercase()),
                "to_address" to toAddress
            )
            ipfsHash?.takeIf { it.isNotBlank() }?.let { body["ipfs_hashes"] = listOf(it) }
            val resp = adminRequest("POST", "/api/brand/issue-unique", body)
            val names = resp["asset_names"]?.asJsonArray?.map { it.asString } ?: emptyList()
            AssetOperationResult(
                success = resp["success"]?.asBoolean == true,
                txid = resp["txid"]?.asString,
                assetName = names.firstOrNull()
            )
        } catch (e: Exception) {
            AssetOperationResult(success = false, error = e.message)
        }
    }

    /**
     * Derive per-chip AES-128 keys from the brand master key via POST /api/brand/derive-chip-key.
     *
     * The backend performs slot-prefixed AES-ECB diversification:
     *   Key_n = AES_ECB(BRAND_MASTER_KEY, slot_prefix_n || tag_uid || padding)
     * This derives four independent keys (Key 0-3) from a single brand secret.
     * The nfcPubId is SHA-256(uid || BRAND_SALT), used to identify the chip without
     * exposing the raw UID.
     *
     * Returns null if the backend is unreachable or the brand keys are not configured.
     *
     * @param tagUidHex  7-byte chip UID as lowercase hex string.
     */
    fun deriveChipKeys(tagUidHex: String): DerivedChipKeys? {
        return try {
            Log.i("AssetManager", "deriveChipKeys request tagUid=$tagUidHex")
            val body = mapOf("tag_uid" to tagUidHex.lowercase())
            val resp = adminRequest("POST", "/api/brand/derive-chip-key", body)
            fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val appMasterKey = hexToBytes(resp["app_master_key"]?.asString ?: return null)
            val sdmmacInputKey = hexToBytes(resp["sdmmac_input_key"]?.asString ?: return null)
            val sdmEncKey = hexToBytes(resp["sdm_enc_key"]?.asString ?: return null)
            val sdmMacKey = hexToBytes(resp["sdm_mac_key"]?.asString ?: return null)
            val nfcPubId = resp["nfc_pub_id"]?.asString ?: return null
            Log.i("AssetManager", "deriveChipKeys success tagUid=$tagUidHex nfcPubId=$nfcPubId")
            DerivedChipKeys(appMasterKey, sdmmacInputKey, sdmEncKey, sdmMacKey, nfcPubId)
        } catch (e: Exception) {
            Log.e("AssetManager", "deriveChipKeys failed tagUid=$tagUidHex error=${e.message}", e)
            null
        }
    }

    /**
     * Perform server-side SUN verification via POST /api/verify/tag.
     *
     * The backend reconstructs the chip keys from BRAND_MASTER_KEY + registered UID,
     * decrypts PICCData, verifies the SDMMAC, looks up the Ravencoin asset on-chain,
     * and checks the revocation table. No client-side keys are required.
     *
     * The asset parameter is sent in uppercase. If the server returns an error HTTP status
     * but with valid JSON, the error field is surfaced; otherwise a generic error is returned.
     *
     * @param asset  Ravencoin asset name from the SUN URL.
     * @param e      32 hex chars: AES-CBC encrypted PICCData from URL parameter "e".
     * @param m      16 hex chars: NXP-truncated SDMMAC from URL parameter "m".
     */
    fun verifyTag(asset: String, e: String, m: String): ServerVerifyResponse {
        return try {
            val body = mapOf("asset" to asset.uppercase(), "e" to e, "m" to m)
            val request = Request.Builder()
                .url("$apiBaseUrl/api/verify/tag")
                .post(gson.toJson(body).toRequestBody(json))
                .build()
            val response = http.newCall(request).execute()
            val bodyStr = response.body?.string()
            Log.d("AssetManager", "verifyTag HTTP ${response.code} body=$bodyStr")
            val parsed = gson.fromJson(bodyStr, com.google.gson.JsonElement::class.java)
            val obj = parsed?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return ServerVerifyResponse(
                    authentic = false,
                    error = "Server error ${response.code}: ${parsed?.asString ?: bodyStr}",
                    stepFailed = "server_error"
                )
            val assetObj = obj["asset"]?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject
            val metaObj = obj["metadata"]?.takeIf { !it.isJsonNull }?.asJsonObject
            val metadata = metaObj?.let {
                gson.fromJson(it, io.raventag.app.ravencoin.RaventagMetadata::class.java)
            }
            ServerVerifyResponse(
                authentic = obj["authentic"]?.takeIf { !it.isJsonNull }?.asBoolean == true,
                tagUid = obj["tag_uid"]?.takeIf { !it.isJsonNull }?.asString,
                counter = obj["counter"]?.takeIf { !it.isJsonNull }?.asInt,
                nfcPubId = obj["nfc_pub_id"]?.takeIf { !it.isJsonNull }?.asString,
                assetName = assetObj?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: asset,
                metadata = metadata,
                revoked = obj["revoked"]?.takeIf { !it.isJsonNull }?.asBoolean == true,
                revokedReason = obj["revoked_reason"]?.takeIf { !it.isJsonNull }?.asString,
                error = obj["error"]?.takeIf { !it.isJsonNull }?.asString,
                stepFailed = obj["step_failed"]?.takeIf { !it.isJsonNull }?.asString
            )
        } catch (e: Exception) {
            Log.e("AssetManager", "verifyTag failed asset=$asset error=${e.message}", e)
            ServerVerifyResponse(authentic = false, error = "Verification failed: ${e.message}")
        }
    }

    /**
     * Register a chip UID linked to an existing Ravencoin asset via POST /api/brand/register-chip.
     *
     * This creates the binding between a physical chip UID and a Ravencoin asset name
     * in the backend database. The binding is required for server-side SUN verification.
     *
     * @param assetName  Ravencoin asset name (uppercased before submission).
     * @param tagUid     7-byte chip UID in hex (uppercased before submission).
     */
    fun registerChip(assetName: String, tagUid: String): AssetOperationResult {
        return try {
            Log.i("AssetManager", "registerChip request asset=$assetName tagUid=$tagUid")
            val body = mapOf("asset_name" to assetName.uppercase(), "tag_uid" to tagUid.uppercase())
            val resp = adminRequest("POST", "/api/brand/register-chip", body)
            Log.i("AssetManager", "registerChip success asset=$assetName tagUid=$tagUid")
            AssetOperationResult(
                success = resp["success"]?.asBoolean == true,
                assetName = resp["asset_name"]?.asString
            )
        } catch (e: Exception) {
            Log.e("AssetManager", "registerChip failed asset=$assetName tagUid=$tagUid error=${e.message}", e)
            AssetOperationResult(success = false, error = e.message)
        }
    }

    /**
     * Retrieve the brand node wallet balance and receive address via GET /api/brand/wallet.
     *
     * @return Pair of (RVN balance as Double, receive address as String), or null on error.
     */
    fun getWalletInfo(): Pair<Double, String>? {
        return try {
            val resp = adminRequest("GET", "/api/brand/wallet")
            val balance = resp["balance_rvn"]?.asDouble ?: 0.0
            val address = resp["receive_address"]?.asString ?: ""
            balance to address
        } catch (e: Exception) {
            null
        }
    }
}
