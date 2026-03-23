package io.raventag.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.raventag.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Screen that displays the wallet's Ravencoin receive address as a QR code and as copyable text.
 *
 * The QR code is generated off the main thread via [generateQr] (defined in QrUtils.kt) and
 * stored in a [mutableStateOf] variable. While the bitmap is being generated, a [CircularProgressIndicator]
 * is shown inside the QR code container. The generation is triggered by [LaunchedEffect] whenever
 * [address] changes.
 *
 * The copy icon turns green and a "Copied!" label appears when the address is copied to the
 * clipboard. The feedback is reset automatically after 2 seconds via a [LaunchedEffect].
 *
 * @param address The wallet's P2PKH Ravencoin address (34 characters, R-prefixed).
 * @param onBack Callback invoked when the user taps the back arrow.
 */
@Composable
fun ReceiveScreen(
    address: String,
    onBack: () -> Unit
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current

    // Tracks whether the address has just been copied; drives the green icon feedback.
    var copied by remember { mutableStateOf(false) }

    // Null until the QR bitmap is generated on the default dispatcher.
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Generate the QR bitmap on a background thread whenever the address changes.
    // Using Dispatchers.Default because generateQr is CPU-intensive (pixel-by-pixel bitmap write).
    LaunchedEffect(address) {
        if (address.isNotBlank()) {
            qrBitmap = withContext(Dispatchers.Default) { generateQr(address) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Header row: back arrow + screen title.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = s.back, tint = Color.White)
            }
            Text(s.walletReceiveTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Brief instruction text.
        Text(s.walletReceiveDesc, style = MaterialTheme.typography.bodyMedium, color = RavenMuted, textAlign = TextAlign.Center)

        Spacer(modifier = Modifier.height(32.dp))

        // QR code container: white background with a subtle orange border.
        // White background is required for QR scanners that expect high contrast.
        Box(
            modifier = Modifier
                .size(240.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(2.dp, RavenOrange.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (qrBitmap != null) {
                // Render the generated bitmap. Slightly smaller than the container to leave
                // a white margin that acts as the QR code quiet zone.
                Image(
                    bitmap = qrBitmap!!.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(220.dp)
                )
            } else {
                // Loading spinner shown while the bitmap is being generated off-thread.
                CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(40.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Address display row with an inline copy button.
        Text("RVN", style = MaterialTheme.typography.labelSmall, color = RavenMuted, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RavenCard, RoundedCornerShape(12.dp))
                .border(1.dp, RavenBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Address in monospace for readability and easy manual comparison.
            Text(
                address,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            // Copy button: turns green when the address has been copied.
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(address))
                    copied = true
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = if (copied) AuthenticGreen else RavenMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Feedback label shown briefly after copying.
        if (copied) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(s.walletCopyDone, style = MaterialTheme.typography.bodySmall, color = AuthenticGreen)
            // Auto-reset the copied state after 2 seconds to hide the feedback label.
            LaunchedEffect(copied) { kotlinx.coroutines.delay(2000); copied = false }
        }
    }
}
