/**
 * WriteTagScreen.kt
 *
 * Full-screen overlay shown during the NFC chip programming flow. Guides the brand
 * operator through four sequential steps:
 *
 *  1. [WriteTagStep.WAIT_TAG]   - waiting for the operator to place the chip on the reader.
 *  2. [WriteTagStep.PROCESSING] - deriving AES-128 keys from the tag UID, issuing the
 *                                 Ravencoin unique token via RPC, and writing SUN
 *                                 configuration to the NTAG 424 DNA chip.
 *  3. [WriteTagStep.SUCCESS]    - chip programmed. Displays the derived keys and the
 *                                 NFC public ID so the operator can store them securely.
 *  4. [WriteTagStep.ERROR]      - something went wrong; a close/retry button is shown.
 *
 * Security note: the keys shown in the SUCCESS step (SDMMAC Input Key, SUN ENC Key,
 * SUN MAC Key) are AES-128 secrets. They are never persisted in the app; the operator
 * is responsible for copying them to a secure vault immediately.
 */
package io.raventag.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.raventag.app.ui.theme.*

/**
 * Steps in the tag writing flow.
 *
 * The ViewModel drives transitions between these steps as the write operation progresses.
 */
enum class WriteTagStep {
    /** Waiting for user to tap the NFC tag. */
    WAIT_TAG,
    /** Reading UID, deriving keys, issuing asset, and programming tag. */
    PROCESSING,
    /** Tag successfully programmed. */
    SUCCESS,
    /** An error occurred. */
    ERROR
}

/**
 * Root composable for the NFC chip programming screen.
 *
 * The screen is scrollable so the key cards in the SUCCESS step are reachable on
 * smaller displays without clipping. A close/cancel button in the top-left corner
 * is always visible to allow the operator to abort at any point (including mid-write,
 * though the ViewModel should handle partial-write cleanup in that case).
 *
 * @param step         Current step in the programming flow, drives which sub-composable is shown.
 * @param assetName    The Ravencoin unique token name being linked to this chip, shown in the header.
 * @param errorMessage Human-readable error description, populated only in [WriteTagStep.ERROR].
 * @param successKeys  Key material to display after success; null if the Admin Key is not configured
 *                     in Settings (keys are derived server-side and not forwarded without auth).
 * @param onCancel     Invoked by the close button and by the retry/close button in the error step.
 */
@Composable
fun WriteTagScreen(
    step: WriteTagStep,
    assetName: String,
    errorMessage: String?,
    /** Hex keys/salt to display after success (brand keeps these secure). */
    successKeys: WriteTagKeys?,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        // Header row: close button on the left, title and asset name on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Program NFC Tag",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                // Asset name in orange gives the operator a visual confirmation of which token
                // is being linked to the chip about to be programmed.
                Text(
                    text = assetName,
                    style = MaterialTheme.typography.bodySmall,
                    color = RavenOrange
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        // Delegate rendering to a dedicated composable for each step.
        when (step) {
            WriteTagStep.WAIT_TAG -> NfcWaitStep(
                icon = Icons.Default.NearMe,
                title = "Tap and hold",
                subtitle = "Hold your phone against the NFC chip. Keep it close until finished.",
                stepLabel = "Automatic Setup"
            )

            WriteTagStep.PROCESSING -> LoadingStep(
                title = "Programming Tag",
                subtitle = "Deriving keys, issuing Ravencoin asset, and configuring security…"
            )

            WriteTagStep.SUCCESS -> SuccessStep(keys = successKeys)

            WriteTagStep.ERROR -> ErrorStep(message = errorMessage, onRetry = onCancel)
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ---- Sub-composables ────────────────────────────────────────────────────────

/**
 * UI shown while the screen is waiting for the operator to present an NFC chip.
 *
 * An infinite scale animation breathes the icon in and out to indicate an active
 * listening state without requiring any user input yet. The same composable could
 * theoretically be reused for any NFC-wait step by passing a different icon.
 *
 * @param icon      Icon drawn in the center of the animated circle.
 * @param title     Large bold text below the animation.
 * @param subtitle  Secondary descriptive text below the title.
 * @param stepLabel Small uppercase label above the animation (e.g. "Automatic Setup").
 * @param color     Accent color for the icon and pulse rings (default: [AuthenticGreen]).
 */
@Composable
private fun NfcWaitStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    stepLabel: String,
    color: Color = AuthenticGreen
) {
    // Smooth back-and-forth scale animation (0.92 to 1.08) gives a gentle "breathing" effect.
    val infiniteTransition = rememberInfiniteTransition(label = "nfc_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse // Reverse so the animation oscillates rather than restarting.
        ),
        label = "scale"
    )

    Text(
        text = stepLabel,
        style = MaterialTheme.typography.labelSmall,
        color = RavenMuted,
        letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)
    )

    Spacer(Modifier.height(32.dp))

    // Layered circles: outer ring pulses with scale, inner ring stays fixed, icon scales with it.
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        // Outer pulse ring: its diameter is driven by the animated scale value.
        Box(
            modifier = Modifier
                .size((120 * scale).dp)
                .background(color.copy(alpha = 0.08f), CircleShape)
        )
        // Inner ring: fixed size with a slightly higher opacity to create visual depth.
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(color.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(48.dp)
                    .scale(scale) // Icon scales with the animation for a cohesive pulsing feel.
            )
        }
    }

    Spacer(Modifier.height(32.dp))

    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = RavenMuted,
        textAlign = TextAlign.Center
    )
}

/**
 * UI shown while the asynchronous programming operation is in progress.
 *
 * Displays an indeterminate [CircularProgressIndicator] with a title and a subtitle
 * describing what the system is currently doing (key derivation, RPC call, NFC write).
 *
 * @param title    Bold heading shown below the spinner.
 * @param subtitle Secondary text with more detail about the ongoing operation.
 */
@Composable
private fun LoadingStep(title: String, subtitle: String) {
    CircularProgressIndicator(
        color = RavenOrange,
        strokeWidth = 3.dp,
        modifier = Modifier.size(64.dp)
    )
    Spacer(Modifier.height(32.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = RavenMuted, textAlign = TextAlign.Center)
}

/**
 * UI shown after the chip has been successfully programmed.
 *
 * If [keys] is non-null (Admin Key was configured), the full set of derived key material
 * is displayed in individual [KeyCard]s alongside a registration status badge indicating
 * whether the chip's nfc_pub_id was successfully registered on the backend.
 *
 * If [keys] is null, a note informs the operator that key display requires the Admin Key
 * to be set in Settings.
 *
 * @param keys Key material and registration status; null when Admin Key is absent.
 */
@Composable
private fun SuccessStep(keys: WriteTagKeys?) {
    Icon(
        Icons.Default.CheckCircle,
        contentDescription = null,
        tint = AuthenticGreen,
        modifier = Modifier.size(72.dp)
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "Tag Programmed!",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = AuthenticGreen,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    // The message adapts: if keys are available it reminds the operator to save them;
    // if not, it explains how to unlock key display.
    Text(
        if (keys != null)
            "NFC chip configured successfully.\nSave the keys below securely, they are not stored anywhere else."
        else
            "NFC chip configured successfully.\nKeys are only visible when the Admin Key is configured in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = RavenMuted,
        textAlign = TextAlign.Center
    )

    if (keys != null) {
        Spacer(Modifier.height(16.dp))
        // Registration status badge: green tick if nfc_pub_id was posted to the backend,
        // amber warning if the registration call failed (e.g. backend unreachable).
        Surface(
            color = if (keys.registrationOk) AuthenticGreen.copy(alpha = 0.15f) else Color(0xFF1A1200),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (keys.registrationOk) AuthenticGreen.copy(alpha = 0.4f) else Color(0xFFF59E0B).copy(alpha = 0.4f))
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (keys.registrationOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (keys.registrationOk) AuthenticGreen else Color(0xFFF59E0B),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    if (keys.registrationOk) "Chip registered on backend ✓" else "Chip not registered , set Admin Key in Settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (keys.registrationOk) AuthenticGreen else Color(0xFFF59E0B)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        // Display each key / identifier in its own card. The operator must copy these
        // to a secure vault: SDMMAC Input Key (Key 1), SUN ENC Key (Key 2), SUN MAC Key (Key 3).
        KeyCard("SDMMAC Input Key (Key 1)", keys.sdmmacInputKey)
        Spacer(Modifier.height(10.dp))
        KeyCard("SUN ENC Key (Key 2)", keys.sdmEncKey)
        Spacer(Modifier.height(10.dp))
        KeyCard("SUN MAC Key (Key 3)", keys.sdmMacKey)
        Spacer(Modifier.height(10.dp))
        // nfc_pub_id = SHA-256(uid || BRAND_SALT); this is what is stored on-chain and in IPFS metadata.
        KeyCard("NFC Pub ID", keys.nfcPubId)
        Spacer(Modifier.height(10.dp))
        // Raw tag UID is private; it is used together with BRAND_SALT to compute nfc_pub_id.
        KeyCard("Tag UID", keys.tagUid)

        Spacer(Modifier.height(20.dp))
        // Amber warning card reinforces that key material is ephemeral and must be saved now.
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1200)),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(0.4f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                Text(
                    "Store these keys in a secure vault. Without them you cannot revoke the tag or verify readings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF59E0B)
                )
            }
        }
    }
}

/**
 * Compact card that displays a single labeled hex value.
 *
 * Used in the success step to show each key and identifier on its own row with a
 * monospace font so hex strings are easy to copy and compare visually.
 *
 * @param label The key name, rendered in small uppercase caps above the value.
 * @param value The hex string value, rendered in [RavenOrange] monospace.
 */
@Composable
private fun KeyCard(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = RavenMuted,
                letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp)
            )
            Spacer(Modifier.height(4.dp))
            // Monospace font keeps hex characters evenly spaced for readability.
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = RavenOrange,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * UI shown when the programming flow fails for any reason (NFC IO error, RPC failure,
 * key derivation error, etc.).
 *
 * The "Close" button reuses [onRetry] to dismiss the screen (the label is "Close" rather
 * than "Retry" because re-entering the flow is the caller's responsibility).
 *
 * @param message  Human-readable error description; falls back to "Operation failed." if null.
 * @param onRetry  Callback invoked when the operator taps the close button.
 */
@Composable
private fun ErrorStep(message: String?, onRetry: () -> Unit) {
    Icon(Icons.Default.Error, contentDescription = null, tint = NotAuthenticRed, modifier = Modifier.size(72.dp))
    Spacer(Modifier.height(20.dp))
    Text("Error", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = NotAuthenticRed)
    Spacer(Modifier.height(12.dp))
    Text(message ?: "Operation failed.", style = MaterialTheme.typography.bodyMedium, color = RavenMuted, textAlign = TextAlign.Center)
    Spacer(Modifier.height(28.dp))
    OutlinedButton(
        onClick = onRetry,
        border = BorderStroke(1.dp, NotAuthenticRed),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NotAuthenticRed)
    ) {
        Text("Close")
    }
}

/**
 * Keys and identifiers revealed to the brand operator after a successful chip write.
 *
 * All string fields are lowercase hex. The operator must store these offline; the app
 * does not persist them anywhere after the screen is dismissed.
 *
 * @param sdmmacInputKey 16-byte AES-128 SDMMAC Input Key (Key 1 in NTAG 424 DNA terminology).
 * @param sdmEncKey      16-byte AES-128 SUN Encryption Key (Key 2), used for PICC data encryption.
 * @param sdmMacKey      16-byte AES-128 SUN MAC Key (Key 3), used for CMAC generation in SUN messages.
 * @param nfcPubId       SHA-256(uid || BRAND_SALT) published in IPFS metadata and the backend registry.
 * @param tagUid         Raw 7-byte NTAG 424 DNA UID (14 hex chars); never published, kept for internal use.
 * @param registrationOk Whether the backend's POST /api/chips endpoint accepted the chip registration.
 */
data class WriteTagKeys(
    val sdmmacInputKey: String,  // hex
    val sdmEncKey: String,       // hex
    val sdmMacKey: String,       // hex
    val nfcPubId: String,        // hex SHA-256(uid || BRAND_SALT)
    val tagUid: String,          // hex
    val registrationOk: Boolean = false
)
