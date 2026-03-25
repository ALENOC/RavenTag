package io.raventag.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import io.raventag.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Holds the state for a single [ImagePickerButton] instance.
 *
 * An instance is created via [rememberImagePickerState] and passed to [ImagePickerButton].
 * After a successful upload, [ipfsCid] contains the content identifier returned by IPFS.
 * The [ipfsUri] convenience property converts the CID to the "ipfs://<cid>" format expected
 * by Ravencoin asset metadata.
 *
 * @property uri The local [Uri] selected from the gallery or camera. Null before selection.
 * @property ipfsCid The IPFS CID returned after a successful upload. Null until upload completes.
 * @property isUploading True while an upload is in progress.
 * @property error Error message if the upload failed. Null on success.
 */
class ImagePickerState(
    var uri: Uri? = null,
    var ipfsCid: String? = null,
    var isUploading: Boolean = false,
    var error: String? = null
) {
    /** Convenience "ipfs://<cid>" URI string, or null if no CID is available yet. */
    val ipfsUri: String? get() = ipfsCid?.let { "ipfs://$it" }

    /** True when either a local URI or an IPFS CID is available. */
    val hasImage: Boolean get() = uri != null || ipfsCid != null
}

/**
 * Creates and remembers an [ImagePickerState] instance tied to the current composition.
 * Use this instead of a bare [remember] to keep the API consistent across call sites.
 */
@Composable
fun rememberImagePickerState() = remember { mutableStateOf(ImagePickerState()) }

/**
 * Composable button that lets the user attach an image to a Ravencoin asset.
 *
 * The user can pick an image from the gallery or take a photo with the camera.
 * After selection, the image is uploaded automatically to IPFS using one of two backends:
 *   1. Pinata.cloud via their Pinning API (requires a valid JWT stored in Settings).
 *   2. A self-hosted IPFS Kubo node (requires a reachable node URL stored in Settings).
 *
 * Priority: Pinata is tried first if [pinataValidated] is true; otherwise Kubo is tried if
 * [kuboValidated] is true. If neither backend is configured and validated, the button shows
 * a warning and cannot be tapped.
 *
 * The button has three visual states:
 *   - Empty: a clickable row with an "Add photo" icon and a short label.
 *   - Uploading: a loading row with a spinner.
 *   - Preview: a thumbnail + "Uploaded to IPFS" label (or error message on failure).
 *
 * Tapping the preview row re-opens the picker dialog to change or remove the image.
 *
 * Usage:
 * ```kotlin
 * val imageState by rememberImagePickerState()
 * ImagePickerButton(
 *     state = imageState,
 *     adminKey = adminKey,
 *     pinataJwt = savedPinataJwt,
 *     pinataValidated = viewModel.pinataJwtStatus == AdminKeyStatus.VALID
 * )
 * // After successful upload: imageState.value.ipfsUri == "ipfs://Qm..."
 * ```
 *
 * @param state Mutable state holder. The composable reads and writes this object.
 * @param adminKey Admin key forwarded to the legacy backend upload endpoint (fallback only).
 * @param apiBaseUrl Backend API base URL for the legacy upload endpoint fallback.
 * @param pinataJwt Pinata.cloud JWT obtained from Settings.
 * @param kuboNodeUrl Kubo node URL obtained from Settings (e.g. "http://10.0.2.2:5001").
 * @param pinataValidated True when the Pinata JWT has been verified against the Pinata API.
 * @param kuboValidated True when the Kubo node URL has been verified as reachable.
 */
@Composable
fun ImagePickerButton(
    state: MutableState<ImagePickerState>,
    adminKey: String,
    apiBaseUrl: String = io.raventag.app.BuildConfig.API_BASE_URL,
    pinataJwt: String = "",
    kuboNodeUrl: String = "",
    pinataValidated: Boolean = false,
    kuboValidated: Boolean = false
) {
    val s = LocalStrings.current
    val context = LocalContext.current

    // Controls visibility of the source-selection dialog (Gallery / Camera / Remove).
    var showDialog by remember { mutableStateOf(false) }

    // Gallery picker launcher using the modern PickVisualMedia API (Android 13+).
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // Set isUploading=true immediately so the LaunchedEffect below triggers the upload.
            state.value = ImagePickerState(uri = uri, isUploading = true)
        }
    }

    // Holds the temporary file URI created for camera captures.
    // Must be remembered across recompositions so the camera result callback can access it.
    val cameraUri = remember { mutableStateOf<Uri?>(null) }

    // Camera launcher: receives true if the user took and saved a photo, false if cancelled.
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri.value != null) {
            state.value = ImagePickerState(uri = cameraUri.value, isUploading = true)
        }
    }

    // CAMERA runtime permission launcher.
    // When granted, immediately creates a temp file URI and launches the camera.
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createTempImageUri(context)
            cameraUri.value = uri
            cameraLauncher.launch(uri)
        }
    }

    // Auto-upload side effect: triggered whenever state.value.uri changes AND isUploading is true.
    // Decides which IPFS backend to use based on the validated flags, then calls the upload
    // function on Dispatchers.IO. Updates state with the CID on success or an error message on failure.
    LaunchedEffect(state.value.uri) {
        val uri = state.value.uri ?: return@LaunchedEffect
        if (!state.value.isUploading) return@LaunchedEffect
        try {
            val cid = if (pinataValidated && pinataJwt.isNotBlank()) {
                // Preferred: upload directly to Pinata without routing through the backend.
                uploadImageViaPinata(context, uri, pinataJwt)
            } else if (kuboValidated && kuboNodeUrl.isNotBlank()) {
                // Alternative: upload to a self-hosted Kubo IPFS node.
                uploadImageViaKubo(context, uri, kuboNodeUrl)
            } else {
                // Neither IPFS backend is configured or validated.
                throw Exception("Configure and verify Pinata or a Kubo node in Settings before attaching an image.")
            }
            // Update state to reflect successful upload.
            state.value = state.value.copy().also {
                it.uri = uri; it.ipfsCid = cid; it.isUploading = false; it.error = null
            }
        } catch (e: Exception) {
            // Upload failed: store the error message so the UI can display it.
            state.value = state.value.copy().also {
                it.uri = uri; it.isUploading = false; it.error = e.message
            }
        }
    }

    // Source-selection dialog: lets the user pick gallery, camera, or remove the current image.
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = RavenCard,
            title = { Text(s.imagePickTitle, color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(s.imageForAssetHint, color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Gallery button: launches the modern photo picker.
                    Button(
                        onClick = {
                            showDialog = false
                            galleryLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RavenOrange)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s.imagePickGallery)
                    }
                    // Camera button: checks CAMERA permission first, then launches the camera intent.
                    Button(
                        onClick = {
                            showDialog = false
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                // Permission already granted: go straight to camera.
                                val uri = createTempImageUri(context)
                                cameraUri.value = uri
                                cameraLauncher.launch(uri)
                            } else {
                                // Request permission; camera will launch in the callback if granted.
                                cameraPermLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RavenCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RavenBorder)
                    ) {
                        Icon(Icons.Default.CameraAlt, null, tint = RavenOrange, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s.imagePickCamera, color = Color.White)
                    }
                    // Remove button: only shown when an image is already attached.
                    // Resets state to the initial empty ImagePickerState.
                    if (state.value.hasImage) {
                        TextButton(onClick = {
                            showDialog = false
                            state.value = ImagePickerState()
                        }) {
                            Text(s.imagePickRemove, color = NotAuthenticRed)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel", color = RavenMuted)
                }
            }
        )
    }

    // ----------------------------------------------------------------
    // Main button / preview UI
    // ----------------------------------------------------------------
    Column(modifier = Modifier.fillMaxWidth()) {
        // Section label above the button.
        Text(
            s.imageForAsset.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = RavenMuted,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Spacer(modifier = Modifier.height(6.dp))

        val current = state.value

        // True when at least one IPFS backend is validated; gates the clickable state.
        val uploadEnabled = pinataValidated || kuboValidated

        when {
            current.isUploading -> {
                // Loading state: spinner with "Uploading to IPFS..." label.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RavenCard, RoundedCornerShape(12.dp))
                        .border(1.dp, RavenOrange.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(s.imagePickUploading, style = MaterialTheme.typography.bodySmall, color = RavenMuted)
                }
            }

            current.uri != null || current.error != null -> {
                // Preview / error state: shows a thumbnail and CID snippet or error message.
                // Tapping this row re-opens the dialog to allow changing or removing the image.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RavenCard, RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            // Green border on success, red on error.
                            if (current.error != null) NotAuthenticRed.copy(alpha = 0.4f)
                            else AuthenticGreen.copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(10.dp)
                        .clickable { showDialog = true },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Square thumbnail loaded asynchronously with Coil.
                    current.uri?.let { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (current.error != null) {
                            // Error: show error label and message in red.
                            Text(s.imagePickError, style = MaterialTheme.typography.bodySmall, color = NotAuthenticRed)
                            Text(current.error!!, style = MaterialTheme.typography.labelSmall, color = NotAuthenticRed.copy(alpha = 0.7f))
                        } else {
                            // Success: show checkmark + "Uploaded to IPFS" and a truncated CID.
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.CheckCircle, null, tint = AuthenticGreen, modifier = Modifier.size(14.dp))
                                Text(s.imagePickDone, style = MaterialTheme.typography.bodySmall, color = AuthenticGreen)
                            }
                            // Show the first 12 chars of the CID for a quick sanity check.
                            current.ipfsCid?.let { cid ->
                                Text(
                                    "ipfs://${cid.take(12)}…",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = RavenMuted
                                )
                            }
                        }
                    }
                    // Edit icon signals that the row is tappable for re-selection.
                    Icon(Icons.Default.Edit, null, tint = RavenMuted, modifier = Modifier.size(16.dp))
                }
            }

            else -> {
                // Empty state: shows "Add image" button.
                // If no IPFS backend is validated, border turns red and clicking is disabled.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RavenCard, RoundedCornerShape(12.dp))
                        .border(1.dp, if (uploadEnabled) RavenBorder else NotAuthenticRed.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .clickable(enabled = uploadEnabled) { showDialog = true }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).background(RavenBg, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = RavenOrange, modifier = Modifier.size(22.dp))
                    }
                    Column {
                        Text(s.imagePickTitle, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        Text(
                            // When no backend is available, explain why uploading is blocked.
                            if (uploadEnabled) "${s.imagePickGallery} / ${s.imagePickCamera}"
                            else "Verify Pinata or Kubo in Settings to enable IPFS image upload",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uploadEnabled) RavenMuted else NotAuthenticRed.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Creates a temporary JPEG file in the app's cache directory and returns a content URI for it.
 *
 * The URI is vended via [FileProvider] so the camera intent can write to it. The timestamp
 * in the filename prevents collisions when the user takes multiple photos.
 *
 * @param context Application context used to locate the cache directory and build the URI.
 * @return A [Uri] pointing to the temporary file that the camera can write into.
 */
private fun createTempImageUri(context: Context): Uri {
    val file = java.io.File(context.cacheDir, "raventag_photo_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
}

/**
 * Returns a shallow copy of this [ImagePickerState] with the same field values.
 * Used to create a new instance (triggering recomposition) while preserving the current data.
 */
private fun ImagePickerState.copy() = ImagePickerState(uri, ipfsCid, isUploading, error)

/**
 * Uploads an image to IPFS via the Pinata Pinning API.
 *
 * Reads the image bytes from the content resolver, determines the MIME type, and sends a
 * multipart POST to the Pinata API using the provided JWT for authentication.
 * Runs on [Dispatchers.IO]; must be called from a coroutine.
 *
 * @param context Context used to read the image bytes from the content resolver.
 * @param uri URI of the image to upload.
 * @param jwt Pinata API JWT obtained from Settings.
 * @return The IPFS CID (content identifier) of the pinned file.
 * @throws Exception if the image cannot be read or the upload fails.
 */
private suspend fun uploadImageViaPinata(context: Context, uri: Uri, jwt: String): String {
    val bytes = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.readBytes()
    } ?: throw Exception("Cannot read image")
    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
    return withContext(Dispatchers.IO) {
        io.raventag.app.ipfs.PinataUploader.uploadFile(bytes, mimeType, "image.$ext", jwt)
    }
}

/**
 * Uploads an image to IPFS via a self-hosted Kubo node's HTTP API (/api/v0/add).
 *
 * Reads the image bytes from the content resolver and sends a multipart POST to the Kubo node.
 * Runs on [Dispatchers.IO]; must be called from a coroutine.
 *
 * @param context Context used to read the image bytes from the content resolver.
 * @param uri URI of the image to upload.
 * @param nodeUrl Base URL of the Kubo node (e.g. "http://10.0.2.2:5001").
 * @return The IPFS CID returned by the Kubo node.
 * @throws Exception if the image cannot be read or the upload fails.
 */
private suspend fun uploadImageViaKubo(context: Context, uri: Uri, nodeUrl: String): String {
    val bytes = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.readBytes()
    } ?: throw Exception("Cannot read image")
    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
    return withContext(Dispatchers.IO) {
        io.raventag.app.ipfs.KuboUploader.uploadFile(bytes, mimeType, "image.$ext", nodeUrl)
    }
}

/**
 * Legacy backend IPFS upload path.
 *
 * Routes the image through the RavenTag backend's "/api/brand/upload-image" endpoint. This was
 * the original upload strategy before Pinata and Kubo direct-upload were added. Kept as a
 * reference but no longer called by [ImagePickerButton] (Pinata/Kubo are preferred because they
 * do not depend on the backend being online).
 *
 * @param context Context for reading the image bytes.
 * @param uri URI of the selected image.
 * @param adminKey Admin API key sent in the X-Admin-Key header.
 * @param apiBaseUrl Base URL of the RavenTag backend.
 * @return The IPFS hash (CID) returned by the backend.
 * @throws Exception on read failure, non-2xx response, or missing ipfs_hash in the response JSON.
 */
private fun uploadImageToIpfs(context: Context, uri: Uri, adminKey: String, apiBaseUrl: String): String {
    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
        ?: throw Exception("Cannot read image")
    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }

    val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("image", "asset_image.$ext", bytes.toRequestBody(mimeType.toMediaType()))
        .build()
    val request = Request.Builder()
        .url("$apiBaseUrl/api/brand/upload-image")
        .header("X-Admin-Key", adminKey)
        .post(body)
        .build()

    val response = client.newCall(request).execute()
    val bodyStr = response.body?.string() ?: throw Exception("Empty response")
    if (!response.isSuccessful) throw Exception("Upload failed: ${response.code}")

    val json = com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
    return json["ipfs_hash"]?.asString ?: throw Exception("No ipfs_hash in response")
}
