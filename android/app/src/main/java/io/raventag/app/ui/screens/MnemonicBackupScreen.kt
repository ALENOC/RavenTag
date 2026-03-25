package io.raventag.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.raventag.app.ui.theme.*

/**
 * Full-screen overlay shown exactly once after a new wallet is generated.
 *
 * Forces the user to acknowledge their BIP-39 12-word seed phrase before the wallet is
 * persisted. The flow is:
 *   1. The 12 words are displayed in a numbered 3-column grid.
 *   2. An optional "Copy All" button copies the mnemonic to the clipboard, then erases it
 *      automatically after 60 seconds to limit exposure.
 *   3. Three warning cards remind the user never to share the phrase and that it cannot be
 *      recovered if lost.
 *   4. A confirmation checkbox must be ticked before the "I've saved it" button becomes active.
 *   5. Tapping the button calls [onConfirmed], which finalizes the wallet in the ViewModel and
 *      clears [mnemonic] from memory.
 *
 * After dismissal this screen is never shown again automatically; the phrase can be revealed
 * later from the Wallet screen.
 *
 * @param mnemonic Space-separated 12-word BIP-39 mnemonic phrase generated for the new wallet.
 * @param onConfirmed Callback invoked when the user confirms they have saved the phrase.
 *   Should finalize wallet persistence and clear the mnemonic from ViewModel state.
 */
@Composable
fun MnemonicBackupScreen(
    mnemonic: String,
    onConfirmed: () -> Unit
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current

    // Split the single mnemonic string into individual words for grid rendering.
    val words = mnemonic.trim().split(" ")

    // True while the mnemonic has been copied to clipboard (drives icon and label change).
    var copied by remember { mutableStateOf(false) }

    // True when the user has ticked the confirmation checkbox, enabling the continue button.
    var confirmed by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)   // Pure black to match the logo background
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Warning icon in an amber-tinted rounded square container.
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color(0xFF3D1A00), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(32.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            s.backupTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            s.backupSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = RavenMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ----------------------------------------------------------------
        // 12-word grid: words are chunked into rows of 3.
        // Each cell shows a sequential number label and the word in monospace.
        // ----------------------------------------------------------------
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .background(Color(0xFF0A0A0A), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            words.chunked(3).forEachIndexed { rowIdx, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEachIndexed { colIdx, word ->
                        // Word ordinal number (1-based) for user-readable labeling.
                        val n = rowIdx * 3 + colIdx + 1
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .background(RavenCard, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Ordinal number in muted color with a fixed width to align words.
                            Text(
                                "$n.",
                                style = MaterialTheme.typography.labelSmall,
                                color = RavenMuted,
                                modifier = Modifier.width(18.dp)
                            )
                            // The word itself in monospace for legibility and copy-paste accuracy.
                            Text(
                                word,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ----------------------------------------------------------------
        // Copy All button: copies the full mnemonic string to the clipboard.
        // A coroutine erases the clipboard after 60 seconds as a security precaution,
        // since the mnemonic grants full wallet access.
        // ----------------------------------------------------------------
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(mnemonic))
                copied = true
                scope.launch {
                    delay(60_000)
                    // Clear clipboard after 60 seconds for security
                    clipboard.setText(AnnotatedString(""))
                    copied = false
                }
            },
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (copied) AuthenticGreen else RavenBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (copied) AuthenticGreen else RavenMuted)
        ) {
            // Icon and label switch between "Copy" and "Copied" states.
            Icon(
                if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (copied) s.backupCopied else s.backupCopyAll,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ----------------------------------------------------------------
        // Warning cards: three short reminders about seed phrase security.
        // Rendered from the localized strings list so they are translated automatically.
        // ----------------------------------------------------------------
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(s.backupWarning1, s.backupWarning2, s.backupWarning3).forEach { warning ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A0A00), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF59E0B).copy(alpha = 0.9f),
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ----------------------------------------------------------------
        // Confirmation checkbox: the user must explicitly check this box,
        // acknowledging that they have written down the phrase.
        // The card border turns green when the checkbox is ticked.
        // ----------------------------------------------------------------
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .background(RavenCard, RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    if (confirmed) AuthenticGreen.copy(alpha = 0.5f) else RavenBorder,
                    RoundedCornerShape(12.dp)
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = confirmed,
                onCheckedChange = { confirmed = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = AuthenticGreen,
                    uncheckedColor = RavenMuted
                )
            )
            Text(
                s.backupConfirmCheck,
                style = MaterialTheme.typography.bodySmall,
                // Text color brightens when the checkbox is ticked for additional feedback.
                color = if (confirmed) Color.White else RavenMuted,
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ----------------------------------------------------------------
        // Continue button: only enabled after the user ticks the checkbox.
        // Remains a dimmed card color while disabled so it is clearly unavailable.
        // Calling onConfirmed triggers wallet persistence and dismisses this screen.
        // ----------------------------------------------------------------
        Button(
            onClick = onConfirmed,
            enabled = confirmed,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AuthenticGreen,
                disabledContainerColor = RavenCard
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(s.backupConfirmBtn, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
