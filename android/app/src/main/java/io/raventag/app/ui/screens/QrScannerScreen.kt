package io.raventag.app.ui.screens

import android.Manifest
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import io.raventag.app.ui.theme.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Full-screen QR code scanner screen using CameraX for the preview and ZXing for decoding.
 *
 * Camera permission is requested lazily on first composition via [rememberLauncherForActivityResult].
 * If permission is already granted the camera starts immediately; otherwise the runtime permission
 * dialog is shown. If permission is denied, an error message is displayed with a back button.
 *
 * The scanner accepts any QR code text and delegates filtering to the caller via [onScanned].
 * In practice the [SendRvnScreen] caller extracts a Ravencoin address from the decoded string,
 * handling both bare addresses ("R...") and "ravencoin:" URI scheme values.
 *
 * @param onScanned Callback invoked with the decoded QR string when a QR code is detected.
 *   Only fired when the result differs from the last decoded value to avoid duplicate callbacks
 *   from the continuous frame analysis loop.
 * @param onBack Callback invoked when the user taps the back arrow or the "Back" button on the
 *   permission denied screen.
 */
@Composable
fun QrScannerScreen(
    onScanned: (String) -> Unit,
    onBack: () -> Unit
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Whether the CAMERA permission has been granted.
    var hasCameraPermission by remember { mutableStateOf(false) }
    // True once the user explicitly denies the CAMERA permission request.
    var permissionDenied by remember { mutableStateOf(false) }

    // Runtime permission launcher. Updates hasCameraPermission or permissionDenied based on result.
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionDenied = true
    }

    // On first composition: check if permission is already granted; if not, request it.
    LaunchedEffect(Unit) {
        val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (perm == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            // Camera preview fills the entire screen; the overlay is drawn on top via Z-ordering.
            CameraPreviewWithZxing(
                onQrCodeDetected = { result ->
                    // Accept any string and let the caller decide what to do with it.
                    // The SendRvnScreen caller extracts Ravencoin addresses from the decoded value.
                    onScanned(result)
                }
            )

            // Transparent UI overlay drawn on top of the camera preview.
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                // Top bar with back button and title.
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = s.back, tint = Color.White)
                    }
                    Text(s.qrScannerTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.weight(0.2f))

                // Viewfinder outline: orange-bordered box that guides the user on where to aim.
                // The actual ZXing decoding analyzes the full camera frame, not just this region.
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .border(2.dp, RavenOrange, RoundedCornerShape(16.dp))
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    s.qrScannerHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                Spacer(modifier = Modifier.weight(0.3f))
            }
        } else if (permissionDenied) {
            // Permission denied state: inform the user and provide a way to leave the screen.
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(s.qrScannerPermissionDenied, color = Color.White, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = RavenOrange)) {
                    Text(s.back)
                }
            }
        }
        // If neither condition is true, the permission dialog is still in progress: show nothing.
    }
}

/**
 * CameraX composable that binds a [Preview] and [ImageAnalysis] use case to the lifecycle.
 *
 * Uses [AndroidView] to embed the camera [PreviewView] inside the Compose tree. The analysis
 * pipeline runs on a dedicated single-thread executor to avoid blocking the main thread.
 *
 * The [ImageAnalysis] is configured with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] so that if
 * the ZXing decoder is slower than the camera frame rate, older frames are discarded rather than
 * queued. This prevents latency build-up while scanning.
 *
 * A [lastScanned] variable deduplicates consecutive identical results, ensuring [onQrCodeDetected]
 * is not called repeatedly for the same QR code while it remains in view.
 *
 * @param onQrCodeDetected Callback invoked on the main thread with the decoded QR string.
 */
@Composable
private fun CameraPreviewWithZxing(onQrCodeDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Dedicated single-thread executor for image analysis to keep analysis off the main thread.
    val executor = remember { Executors.newSingleThreadExecutor() }

    // Tracks the last successfully decoded string to suppress duplicate callbacks.
    var lastScanned by remember { mutableStateOf("") }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Preview use case: binds the camera output to the PreviewView surface.
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Analysis use case: receives YUV frames and decodes them with ZXing.
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    // Drop frames instead of queuing when the decoder is busy.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    val result = decodeQr(imageProxy)
                    // Only fire the callback when a new (different) QR code is decoded.
                    if (result != null && result != lastScanned) {
                        lastScanned = result
                        onQrCodeDetected(result)
                    }
                    // Always close the proxy to release the frame buffer back to CameraX.
                    imageProxy.close()
                }

                try {
                    // Unbind all use cases before rebinding to avoid IllegalStateException.
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (_: Exception) {
                    // Silently ignore binding failures (e.g. camera already in use).
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Decodes a QR code from a CameraX [ImageProxy] using ZXing's [MultiFormatReader].
 *
 * Reads the Y-plane (luminance channel) of the YUV_420_888 image, wraps it in a
 * [PlanarYUVLuminanceSource], and passes it through [HybridBinarizer] + [MultiFormatReader].
 *
 * Row-stride compensation: some camera HALs (common on recent Xiaomi/MediaTek devices) pad each
 * row of the Y plane to a multiple of 64 bytes. Reading the buffer verbatim produces a corrupted
 * luminance array because the padding bytes shift every row. This function strips the padding by
 * copying only [width] bytes per row, regardless of [rowStride].
 *
 * Rotation fallback: if the primary decode fails, [PlanarYUVLuminanceSource.rotateCounterClockwise]
 * is used to try the frame in portrait orientation. This handles devices whose camera sensor
 * reports a 90-degree rotation that differs from the default assumption.
 *
 * Returns null if no QR code is found in the frame or if any decoding step throws an exception.
 * Exceptions are swallowed intentionally; ZXing throws [NotFoundException] on every frame that
 * does not contain a barcode, which is the normal case.
 *
 * @param imageProxy The current frame from the CameraX [ImageAnalysis] pipeline.
 * @return The decoded QR string, or null if no QR code was detected.
 */
private fun decodeQr(imageProxy: ImageProxy): String? {
    return try {
        val plane = imageProxy.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = imageProxy.width
        val height = imageProxy.height
        val buffer: ByteBuffer = plane.buffer

        // Strip per-row padding: when rowStride > width the HAL adds alignment bytes at the end
        // of each row. Reading the buffer as a flat array corrupts all rows after the first.
        val bytes = if (rowStride == width && pixelStride == 1) {
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } else {
            val raw = ByteArray(buffer.remaining())
            buffer.get(raw)
            ByteArray(width * height) { i ->
                raw[(i / width) * rowStride + (i % width) * pixelStride]
            }
        }

        val source = PlanarYUVLuminanceSource(
            bytes, width, height, 0, 0, width, height, false
        )

        // Primary attempt: decode in the orientation provided by CameraX.
        try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            // Rotation fallback: try counter-clockwise (portrait) orientation.
            // Handles sensors that deliver 90-degree rotated frames on some devices.
            if (source.isRotateSupported) {
                MultiFormatReader().decode(
                    BinaryBitmap(HybridBinarizer(source.rotateCounterClockwise()))
                ).text
            } else null
        }
    } catch (_: Exception) {
        null
    }
}
