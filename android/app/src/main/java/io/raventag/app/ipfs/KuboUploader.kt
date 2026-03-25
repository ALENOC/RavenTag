/**
 * KuboUploader.kt
 *
 * Uploads files and JSON metadata to a self-hosted IPFS node running the Kubo
 * implementation (formerly go-ipfs). Kubo exposes an HTTP RPC API at /api/v0.
 *
 * This uploader is used when the user has configured a local or private IPFS node
 * (e.g. a Raspberry Pi on the same LAN, or a VPS) instead of the Pinata cloud service.
 * The node URL is stored in app settings and passed to each upload call at runtime.
 *
 * All network calls are synchronous and must be dispatched from a background coroutine
 * or thread by the caller. No suspend functions are used here; OkHttp's blocking API
 * is used directly.
 *
 * Relevant Kubo API endpoints used:
 *   POST /api/v0/add?pin=true   Upload and pin a file, returns JSON with "Hash" field.
 *   POST /api/v0/version        Health-check endpoint that returns the node version JSON.
 */
package io.raventag.app.ipfs

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Singleton that communicates with a Kubo IPFS node via its HTTP API.
 *
 * A single [OkHttpClient] instance is shared across all requests to benefit from
 * connection pooling. The client is created lazily to avoid initializing it on the
 * main thread before it is needed.
 */
object KuboUploader {

    /**
     * Shared OkHttp client with conservative timeouts suitable for IPFS operations
     * over LAN or the internet. The read timeout is longer than the connect timeout
     * because the node may take several seconds to add and pin a file.
     */
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Normalizes a user-supplied node URL to ensure it ends with "/api/v0".
     *
     * Accepts all common forms that a user might enter in the settings field:
     *   - "http://10.0.2.2:5001"           -> "http://10.0.2.2:5001/api/v0"
     *   - "http://10.0.2.2:5001/"          -> "http://10.0.2.2:5001/api/v0"
     *   - "https://ipfs.example.com/api/v0" -> unchanged
     *
     * @param url The raw node URL as entered by the user.
     * @return The URL guaranteed to end with "/api/v0" and no trailing slash.
     */
    private fun apiBase(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        // Append the API path only when it is not already present.
        return if (trimmed.endsWith("/api/v0")) trimmed else "$trimmed/api/v0"
    }

    /**
     * Uploads raw bytes to the Kubo node as a multipart/form-data POST to /api/v0/add.
     *
     * The ?pin=true query parameter instructs Kubo to pin the content so that it is
     * not garbage-collected on the next GC cycle.
     *
     * @param bytes     Raw content to upload.
     * @param mimeType  MIME type of the content, e.g. "application/json" or "image/jpeg".
     * @param filename  Filename field in the multipart request (used by Kubo for display only).
     * @param nodeUrl   Base URL of the Kubo node, e.g. "http://10.0.2.2:5001".
     * @return The IPFS CID (Content Identifier) returned by Kubo in the "Hash" field.
     * @throws Exception if the HTTP response is not successful or the response body
     *                   does not contain a "Hash" field.
     */
    fun uploadFile(bytes: ByteArray, mimeType: String, filename: String, nodeUrl: String): String {
        // Build a multipart body with the file content as the "file" part.
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("${apiBase(nodeUrl)}/add?pin=true")  // pin=true keeps the content alive
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Kubo upload failed: ${response.code}")
            // Kubo returns a JSON object like: {"Name":"metadata.json","Hash":"Qm...","Size":"123"}
            val json = Gson().fromJson(response.body!!.string(), JsonObject::class.java)
            return json["Hash"]?.asString ?: throw Exception("No Hash in Kubo response")
        }
    }

    /**
     * Convenience wrapper that uploads a JSON string as UTF-8 bytes with filename "metadata.json".
     *
     * Used to pin Ravencoin asset metadata (name, description, IPFS image CID, nfc_pub_id)
     * to IPFS before calling the Ravencoin RPC to issue the asset.
     *
     * @param json    The JSON string to upload.
     * @param nodeUrl Base URL of the Kubo node.
     * @return The IPFS CID of the uploaded JSON file.
     */
    fun uploadJson(json: String, nodeUrl: String): String =
        uploadFile(json.toByteArray(Charsets.UTF_8), "application/json", "metadata.json", nodeUrl)

    /**
     * Checks whether the Kubo node at [url] is reachable and responding correctly.
     *
     * Sends a POST to /api/v0/version (Kubo requires POST even for read-only endpoints)
     * and verifies that the response body contains the "Version" key, confirming it is
     * a valid Kubo API response and not a generic HTTP error page.
     *
     * Used in Settings to show the "Node verified / unreachable" status badge.
     *
     * @param url Base URL of the Kubo node to test.
     * @return true if the node is reachable and returns a valid version response, false otherwise.
     */
    fun testNode(url: String): Boolean {
        val request = Request.Builder()
            .url("${apiBase(url)}/version")
            // Kubo's /api/v0/version requires a POST; an empty body is sufficient.
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body?.string().orEmpty()
            // A valid Kubo response contains a JSON "Version" field.
            body.contains("\"Version\"")
        }
    }
}
