package io.raventag.app.ravencoin

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import io.raventag.app.ipfs.IpfsResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import io.raventag.app.BuildConfig
import io.raventag.app.network.NetworkModule
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class RaventagMetadata(
    @SerializedName("raventag_version") val raventagVersion: String,
    @SerializedName("parent_asset") val parentAsset: String,
    @SerializedName("sub_asset") val subAsset: String? = null,
    @SerializedName("variant_asset") val variantAsset: String? = null,
    @SerializedName("nfc_pub_id") val nfcPubId: String,
    @SerializedName("crypto_type") val cryptoType: String,
    @SerializedName("algo") val algo: String,
    @SerializedName("metadata_ipfs") val metadataIpfs: String? = null,
    @SerializedName("brand_info") val brandInfo: BrandInfo? = null,
    // Per-token image stored as "ipfs://<CID>" or a plain IPFS CID
    @SerializedName("image") val image: String? = null,
    // Per-token description set by the brand at issuance time
    @SerializedName("description") val description: String? = null
)

enum class AssetType { ROOT, SUB, UNIQUE }

data class OwnedAsset(
    val name: String,
    val balance: Double,
    val type: AssetType,
    val ipfsHash: String? = null,
    val imageUrl: String? = null,
    val description: String? = null,
)

data class BrandInfo(
    val website: String? = null,
    val description: String? = null,
    val contact: String? = null
)

data class AssetData(
    val name: String,
    val amount: Long,
    val units: Int,
    val reissuable: Boolean,
    @SerializedName("has_ipfs") val hasIpfs: Boolean,
    @SerializedName("ipfs_hash") val ipfsHash: String? = null
)

class RpcClient(
    private val context: Context? = null,
    private val rpcUrl: String = BuildConfig.API_BASE_URL,
    private val ipfsGateway: String = BuildConfig.IPFS_GATEWAY
) {
    companion object {
        private const val TAG = "RpcClient"
        private const val PREFS_NAME = "asset_metadata_cache"
        // Key = IPFS hash, Value = Pair(imageUrl, description)
        private val ipfsMetadataCache = ConcurrentHashMap<String, Pair<String?, String?>>()
    }

    private fun getPrefs() = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveToPersistentCache(hash: String, imageUrl: String?, description: String?) {
        getPrefs()?.edit()?.apply {
            putString("${hash}_img", imageUrl)
            putString("${hash}_desc", description)
            apply()
        }
    }

    private fun loadFromPersistentCache(hash: String): Pair<String?, String?>? {
        val prefs = getPrefs() ?: return null
        if (!prefs.contains("${hash}_img") && !prefs.contains("${hash}_desc")) return null
        val img = prefs.getString("${hash}_img", null)
        val desc = prefs.getString("${hash}_desc", null)
        return Pair(img, desc)
    }

    private val gson = Gson()
    private val json = "application/json".toMediaType()

    // Use shared client from NetworkModule if context is available, fallback to new client
    private val http = if (context != null) NetworkModule.getHttpClient(context)
                       else OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .build()

    data class RpcPayload(
        val jsonrpc: String = "1.0",
        val id: String = "raventag-android",
        val method: String,
        val params: List<Any>
    )

    private fun rpcCall(method: String, params: List<Any> = emptyList()): JsonObject {
        val payload = RpcPayload(method = method, params = params)
        val body = gson.toJson(payload).toRequestBody(json)
        val request = Request.Builder()
            .url(rpcUrl)
            .post(body)
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("RPC HTTP error: ${response.code}")
        }

        val responseJson = gson.fromJson(response.body?.string(), JsonObject::class.java)
        val error = responseJson["error"]
        if (error != null && !error.isJsonNull) {
            val errObj = error.asJsonObject
            throw IOException("RPC error ${errObj["code"]?.asInt}: ${errObj["message"]?.asString}")
        }

        return responseJson
    }

    /**
     * Get raw asset data via ElectrumX blockchain.asset.get_meta (no backend required).
     * Falls back to backend proxy if ElectrumX call fails.
     */
    fun getAssetData(assetName: String): AssetData? {
        val meta = try {
            io.raventag.app.wallet.RavencoinPublicNode().getAssetMeta(assetName.uppercase())
        } catch (_: Exception) { null }
        if (meta != null) {
            return AssetData(
                name = meta.name,
                amount = meta.totalSupply,
                units = meta.divisions,
                reissuable = meta.reissuable,
                hasIpfs = meta.hasIpfs,
                ipfsHash = meta.ipfsHash
            )
        }
        // Fallback to backend proxy
        return try {
            val request = Request.Builder()
                .url("$rpcUrl/api/assets/${assetName.uppercase()}")
                .get().build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) return null
            val obj = gson.fromJson(response.body?.string(), JsonObject::class.java)
            AssetData(
                name = obj["name"]?.asString ?: assetName,
                amount = obj["amount"]?.asLong ?: 0L,
                units = obj["units"]?.asInt ?: 0,
                reissuable = obj["reissuable"]?.asBoolean ?: false,
                hasIpfs = obj["has_ipfs"]?.asBoolean ?: false,
                ipfsHash = obj["ipfs_hash"]?.asString
            )
        } catch (_: Exception) { null }
    }

    /**
     * Fetch metadata JSON from IPFS gateway.
     */
    fun fetchIpfsMetadata(ipfsUri: String): RaventagMetadata? {
        val urls = IpfsResolver.candidateUrls(ipfsUri).ifEmpty {
            when {
                ipfsUri.startsWith("ipfs://") -> listOf(ipfsGateway + ipfsUri.removePrefix("ipfs://"))
                ipfsUri.startsWith("http") -> listOf(ipfsUri)
                else -> listOf(ipfsGateway + ipfsUri)
            }
        }
        urls.forEach { url ->
            runCatching {
                val request = Request.Builder().url(url).get().build()
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchIpfsMetadata $ipfsUri via $url http=${response.code}")
                    return@runCatching null
                }
                val raw = gson.fromJson(response.body?.string(), JsonObject::class.java)
                val version = raw["raventag_version"]?.asString
                if (version != "RTP-1") return@runCatching null
                gson.fromJson(raw, RaventagMetadata::class.java)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Search assets by name pattern via backend proxy.
     */
    fun searchAssets(query: String): List<String> {
        return try {
            val request = Request.Builder()
                .url("$rpcUrl/api/assets?search=${query.uppercase()}")
                .get().build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val obj = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val arr = obj["assets"]?.asJsonArray ?: return emptyList()
            arr.map { it.asString }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * List all Ravencoin assets owned by a given address via ElectrumX (no backend required).
     */
    fun listAssetsByAddress(address: String): List<OwnedAsset> {
        val node = io.raventag.app.wallet.RavencoinPublicNode()
        val assetBalances = node.getAssetBalances(address)
        
        // Parallelize metadata fetching using a fixed thread pool or coroutines scope
        // Since we are in a non-suspend function called from a coroutine, we can use parallel streams or a custom executor,
        // but for simplicity and compatibility, we'll use a simple thread-based approach or just return 
        // the list and let the ViewModel handle metadata fetch if we want it truly instant.
        
        // Optimization: return the balances immediately, the ViewModel will enrich them.
        return assetBalances.map { asset ->
            val type = when {
                asset.name.contains('#') -> AssetType.UNIQUE
                asset.name.contains('/') -> AssetType.SUB
                else -> AssetType.ROOT
            }
            OwnedAsset(
                name = asset.name,
                balance = asset.amount,
                type = type,
                ipfsHash = null // Will be enriched in parallel by ViewModel
            )
        }.sortedWith(compareBy({ it.type.ordinal }, { it.name }))
    }

    /**
     * Enrich an OwnedAsset with IPFS metadata (image URL, description).
     * Fetches asset data from node then the IPFS JSON.
     * IPFS metadata is cached by IPFS hash to avoid redundant network calls
     * while ensuring each asset gets its own correct metadata.
     */
    fun enrichWithIpfsData(asset: OwnedAsset): OwnedAsset {
        // Use ipfsHash already fetched in listAssetsByAddress when available,
        // falling back to a fresh getAssetData call only if needed.
        val hash = asset.ipfsHash ?: run {
            try { getAssetData(asset.name) } catch (_: Exception) { null }?.ipfsHash ?: return asset
        }
        
        // 1. Check memory cache
        ipfsMetadataCache[hash]?.let { (cachedImageUrl, cachedDescription) ->
            return asset.copy(ipfsHash = hash, imageUrl = cachedImageUrl, description = cachedDescription)
        }

        // 2. Check persistent disk cache
        loadFromPersistentCache(hash)?.let { (cachedImageUrl, cachedDescription) ->
            ipfsMetadataCache[hash] = Pair(cachedImageUrl, cachedDescription)
            return asset.copy(ipfsHash = hash, imageUrl = cachedImageUrl, description = cachedDescription)
        }
        
        Log.d(TAG, "enrichWithIpfsData FETCHING ${asset.name}: hash=$hash")
        val urls = IpfsResolver.candidateUrls(hash).ifEmpty { listOf("$ipfsGateway$hash") }
        for (url in urls) {
            data class Result(val imageUrl: String?, val description: String?)
            val found: Result? = try {
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "enrichWithIpfsData ${asset.name} HTTP ${resp.code} via $url")
                        return@use null
                    }
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    if (contentType.startsWith("image/")) {
                        return@use Result("ipfs://$hash", null)
                    }
                    val body = resp.body?.string() ?: return@use null
                    val json = try {
                        gson.fromJson(body, com.google.gson.JsonObject::class.java)
                    } catch (_: Exception) { return@use null }
                    val imgRaw = listOf("image", "image_url", "icon", "logo")
                        .firstNotNullOfOrNull { key -> json[key]?.takeIf { !it.isJsonNull }?.asString }
                    val imageUrl = imgRaw?.let { img ->
                        when {
                            img.startsWith("http") -> img
                            img.startsWith("ipfs://") -> img
                            img.startsWith("/ipfs/") -> "ipfs://${img.removePrefix("/ipfs/")}"
                            else -> "ipfs://$img"
                        }
                    }
                    val description = json["description"]?.takeIf { !it.isJsonNull }?.asString
                        ?: json["desc"]?.takeIf { !it.isJsonNull }?.asString
                    Result(imageUrl, description)
                }
            } catch (e: Exception) {
                Log.w(TAG, "enrichWithIpfsData ${asset.name} FAILED via $url", e)
                null
            }
            if (found != null) {
                Log.d(TAG, "enrichWithIpfsData ${asset.name} SUCCESS via $url: imageUrl=${found.imageUrl}")
                ipfsMetadataCache[hash] = Pair(found.imageUrl, found.description)
                saveToPersistentCache(hash, found.imageUrl, found.description)
                return asset.copy(ipfsHash = hash, imageUrl = found.imageUrl, description = found.description)
            }
        }
        return asset.copy(ipfsHash = hash)
    }

    /**
     * Get asset with RTP-1 metadata.
     */
    fun getAssetWithMetadata(assetName: String): Pair<AssetData, RaventagMetadata?>? {
        val asset = getAssetData(assetName) ?: return null
        val metadata = asset.ipfsHash?.let {
            try { fetchIpfsMetadata("ipfs://$it") } catch (e: Exception) { null }
        }
        return Pair(asset, metadata)
    }
}
