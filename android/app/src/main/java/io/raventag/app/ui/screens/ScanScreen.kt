/**
 * ScanScreen.kt
 *
 * Entry-point screen for the RavenTag consumer app. Presents the brand logo and
 * an NFC scan button that triggers tag reading. The screen handles three distinct
 * NFC states (IDLE, SCANNING, ERROR) and also gracefully covers the cases where
 * NFC hardware is absent or disabled on the device.
 *
 * Layout flow:
 *   - Logo image at the top (aspect-ratio locked to the original 1184x880 artwork)
 *   - NFC readiness gate:
 *       * Hardware missing: NoNfcCard
 *       * Hardware present but disabled: NfcDisabledCard
 *       * Ready: NfcScanButton + status text + HowItWorks explainer
 */
package io.raventag.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.raventag.app.R
import io.raventag.app.ui.theme.*

/**
 * Represents the three possible states of the NFC scan session:
 * - [IDLE]: no active scan; the button is ready to start one.
 * - [SCANNING]: the NFC adapter is listening; user should tap the tag.
 * - [ERROR]: the last scan attempt failed; an error message is shown.
 */
enum class ScanState { IDLE, SCANNING, ERROR }

/**
 * Main composable for the Scan tab.
 *
 * @param scanState   Current state of the NFC scan session.
 * @param errorMessage Human-readable error string, populated only in [ScanState.ERROR].
 * @param nfcSupported Whether the device has NFC hardware at all.
 * @param nfcEnabled   Whether the user has NFC turned on in system settings.
 * @param onStartScan  Callback invoked when the user taps the scan button.
 * @param modifier     Optional modifier forwarded to the root [Column].
 */
@Composable
fun ScanScreen(
    scanState: ScanState,
    errorMessage: String?,
    nfcSupported: Boolean,
    nfcEnabled: Boolean,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    // Derived convenience flag: both conditions must be true to show the scan UI.
    val nfcReady = nfcSupported && nfcEnabled

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RavenBg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Full logo with raven (1184x880 px, aspect ratio preserved)
        Image(
            painter = painterResource(id = R.drawable.raven_logo_transparent),
            contentDescription = "RavenTag",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .aspectRatio(1184f / 880f)
        )
        Text(
            text = s.scanSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = RavenMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (!nfcReady) {
            // NFC unavailable: center the card, then show how it works
            Spacer(modifier = Modifier.height(32.dp))
            // Show different cards depending on whether hardware is missing vs just disabled.
            if (!nfcSupported) NoNfcCard(s) else NfcDisabledCard(s)
            Spacer(modifier = Modifier.height(32.dp))
            HowItWorks(s)
            Spacer(modifier = Modifier.height(24.dp))
        } else {
            // NFC ready: button + status + how it works
            Spacer(modifier = Modifier.height(40.dp))

            NfcScanButton(scanState, onStartScan)

            Spacer(modifier = Modifier.height(28.dp))

            // Status text below the button changes based on the current scan state.
            when {
                scanState == ScanState.SCANNING -> {
                    // Actively listening: prompt user to bring the tag close.
                    Text(text = s.scanTapping, style = MaterialTheme.typography.titleMedium, color = RavenOrange, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(text = s.scanTapHint, style = MaterialTheme.typography.bodySmall, color = RavenMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
                scanState == ScanState.ERROR && errorMessage != null -> {
                    // Previous scan failed: show warning icon alongside the error detail.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Text(text = errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> {
                    // Idle: quiet prompt inviting the user to tap.
                    Text(text = s.scanIdle, style = MaterialTheme.typography.bodyMedium, color = RavenMuted, textAlign = TextAlign.Center)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HowItWorks(s)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Circular NFC scan button with an animated ripple effect while scanning.
 *
 * When [scanState] is [ScanState.SCANNING], three staggered ring animations
 * expand outward from the button center, each delayed by 500 ms, giving the
 * classic "NFC pulse" look. The button itself switches to a hollow outlined
 * style to signal the active state, and its click handler is disabled to
 * prevent re-entry while a scan is already in progress.
 *
 * @param scanState   Drives button appearance and whether rings are drawn.
 * @param onStartScan Invoked when the button is tapped in the IDLE or ERROR state.
 */
@Composable
private fun NfcScanButton(scanState: ScanState, onStartScan: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        if (scanState == ScanState.SCANNING) {
            // Draw three concentric expanding rings, each offset by 500 ms so they
            // create a continuous outward-flowing ripple rather than all pulsing at once.
            repeat(3) { index ->
                val infiniteTransition = rememberInfiniteTransition(label = "ring_$index")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f, targetValue = 2f,
                    animationSpec = infiniteRepeatable(animation = tween(1500, delayMillis = index * 500, easing = EaseOut), repeatMode = RepeatMode.Restart),
                    label = "scale_$index"
                )
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.7f, targetValue = 0f,
                    // Alpha fades to 0 in sync with the scale, so rings fade out as they expand.
                    animationSpec = infiniteRepeatable(animation = tween(1500, delayMillis = index * 500, easing = EaseOut), repeatMode = RepeatMode.Restart),
                    label = "alpha_$index"
                )
                Box(modifier = Modifier.size(120.dp).scale(scale).background(RavenOrange.copy(alpha = alpha), CircleShape))
            }
        }
        // The button itself: solid orange when idle, outlined with semi-transparent fill while scanning.
        Surface(
            onClick = if (scanState != ScanState.SCANNING) onStartScan else { {} },
            shape = CircleShape,
            color = if (scanState == ScanState.SCANNING) RavenOrange.copy(alpha = 0.2f) else RavenOrange,
            border = if (scanState == ScanState.SCANNING) BorderStroke(2.dp, RavenOrange) else null,
            modifier = Modifier.size(120.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(imageVector = Icons.Default.Nfc, contentDescription = "Scan NFC",
                    // Icon tint matches the button state: orange outline when scanning, white on solid fill.
                    tint = if (scanState == ScanState.SCANNING) RavenOrange else Color.White,
                    modifier = Modifier.size(56.dp))
            }
        }
    }
}

/**
 * Informational card shown when the device does not have NFC hardware.
 * Since the user cannot do anything to fix this, the card simply explains
 * the limitation without offering any action.
 *
 * @param s Localized string bundle.
 */
@Composable
private fun NoNfcCard(s: AppStrings) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Nfc, contentDescription = null, tint = RavenMuted, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(s.nfcNotSupported, fontWeight = FontWeight.SemiBold, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(s.nfcNotSupportedDesc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Informational card shown when NFC hardware is present but switched off in
 * system settings. The user can resolve this by enabling NFC; this card
 * describes the requirement.
 *
 * @param s Localized string bundle.
 */
@Composable
private fun NfcDisabledCard(s: AppStrings) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Error tint signals an actionable problem, unlike the muted tint in NoNfcCard.
            Icon(Icons.Default.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(s.nfcDisabled, fontWeight = FontWeight.SemiBold, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(s.nfcDisabledDesc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Three-step explainer panel rendered below the scan button (or the NFC error
 * card) that briefly describes how RavenTag verification works.
 *
 * Steps are stored as pairs of (badge label, description string) and are
 * rendered in a consistent format: an orange circle badge on the left followed
 * by the description text.
 *
 * @param s Localized string bundle.
 */
@Composable
private fun HowItWorks(s: AppStrings) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = s.howItWorks, style = MaterialTheme.typography.titleSmall, color = RavenMuted, fontWeight = FontWeight.SemiBold)
        // Build the three step rows from the localized strings.
        listOf("1" to s.howStep1, "2" to s.howStep2, "3" to s.howStep3).forEach { (num, desc) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Numbered badge: a small circle with the step number inside.
                Surface(shape = CircleShape, color = RavenOrange.copy(alpha = 0.1f), modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(num, color = RavenOrange, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Text(desc, style = MaterialTheme.typography.bodySmall, color = RavenMuted)
            }
        }
    }
}
