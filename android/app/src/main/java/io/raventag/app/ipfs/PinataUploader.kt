/**
 * PinataUploader.kt
 *
 * Uploads files and JSON metadata to Pinata (https://pinata.cloud), a managed IPFS
 * pinning service. Pinata is the cloud alternative to a self-hosted Kubo node; it does
 * not require the user to run any local infrastructure.
 *
 * Authentication is performed via a Pinata JWT (JSON Web Token) that the user obtains
 * from the Pinata dashboard and stores in app settings. The JWT is sent as a Bearer
 * token in the Authorization header on every request.
 *
 * Pinata API endpoints used:
 *   POST https://api.pinata.cloud/pinning/pinFileToIPFS   Upload and pin a file.
 *   GET  https://api.pinata.cloud/data/testAuthentication  Validate the JWT.
 *
 * All network calls are synchronous and must be dispatched from a background coroutine
 * or thread by the caller.
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
 * Singleton that uploads content to Pinata's IPFS pinning service.
 *
 * A single [OkHttpClient] is shared across all requests to benefit from connection
 * pooling. The client is created lazily on first use.
 */
object PinataUploader {

    /**
     * Pinata's multipart file upload endpoint.
     * The response contains a JSON object with the "IpfsHash" field (the CID).
     */
    private const val PIN_FILE_URL = "https://api.pinata.cloud/pinning/pinFileToIPFS"

    /**
     * Shared HTTP client. The read timeout is longer than the connect timeout because
     * Pinata may take several seconds to process and pin the uploaded content.
     */
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Uploads raw bytes to Pinata and returns the resulting IPFS CID.
     *
     * Builds a multipart/form-data POST request with the file content in the "file" part
     * and authenticates using a Bearer JWT. Pinata pins the content immediately upon
     * successful upload, making it available via any IPFS gateway.
     *
     * @param bytes    Raw content to upload.
     * @param mimeType MIME type of the content, e.g. "application/json" or "image/jpeg".
     * @param filename Filename passed to Pinata for display in the Pinata dashboard.
     * @param jwt      The Pinata JWT token from app settings.
     * @return The IPFS CID (Content Identifier) returned by Pinata in the "IpfsHash" field.
     * @throws Exception if the HTTP response is not successful or the response body
     *                   does not contain an "IpfsHash" field.
     */
    fun uploadFile(bytes: ByteArray, mimeType: String, filename: String, jwt: String): String {
        // Build a multipart body with the file content as the "file" part.
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(PIN_FILE_URL)
            // Pinata requires the JWT as a Bearer token in the Authorization header.
            .header("Authorization", "Bearer $jwt")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Pinata upload failed: ${response.code}")
            // Pinata returns JSON like: {"IpfsHash":"Qm...","PinSize":123,"Timestamp":"..."}
            val json = Gson().fromJson(response.body!!.string(), JsonObject::class.java)
            return json["IpfsHash"]?.asString ?: throw Exception("No IpfsHash in Pinata response")
        }
    }

    /**
     * Convenience wrapper that uploads a JSON string as UTF-8 bytes with filename "metadata.json".
     *
     * Used to pin Ravencoin asset metadata (name, description, IPFS image CID, nfc_pub_id)
     * to IPFS via Pinata before calling the Ravencoin RPC to issue the asset.
     *
     * @param json The JSON string to upload.
     * @param jwt  The Pinata JWT token from app settings.
     * @return The IPFS CID of the uploaded JSON file.
     */
    fun uploadJson(json: String, jwt: String): String =
        uploadFile(json.toByteArray(Charsets.UTF_8), "application/json", "metadata.json", jwt)

    /**
     * Validates the Pinata JWT by calling the testAuthentication endpoint.
     *
     * Used in Settings to show the "Token verified / invalid" status badge. The endpoint
     * returns HTTP 200 if the JWT is valid and the Pinata account is active, or a 4xx
     * status if the token is expired, revoked, or malformed.
     *
     * @param jwt The Pinata JWT token to validate.
     * @return true if the JWT is accepted by Pinata (HTTP 200 response), false otherwise.
     */
    fun testAuthentication(jwt: String): Boolean {
        val request = Request.Builder()
            .url("https://api.pinata.cloud/data/testAuthentication")
            .header("Authorization", "Bearer $jwt")
            .get()
            .build()
        return http.newCall(request).execute().use { it.isSuccessful }
    }
}
