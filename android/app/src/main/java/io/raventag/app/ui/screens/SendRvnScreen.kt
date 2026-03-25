package io.raventag.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.raventag.app.ui.theme.*

/**
 * Screen for sending RVN (Ravencoin) to a recipient address.
 *
 * Supports two modes:
 *   - Normal send: the user types or scans a recipient address and enters an amount.
 *   - Donate mode: [donateMode] is true and [prefillAddress] is the project donation address.
 *     In this mode the address field is pre-filled and locked (non-editable), and a
 *     donation message banner is shown at the top.
 *
 * QR code scanning is integrated: tapping the QR icon next to the address field pushes the
 * [QrScannerScreen] as an overlay within the same composable slot (using [showScanner] state).
 * When a QR result arrives, the address is extracted from the decoded string handling both
 * bare Ravencoin addresses and "ravencoin:" URI-scheme values.
 *
 * A confirmation dialog is shown before broadcasting the transaction to give the user a
 * final opportunity to review the amount and recipient address.
 *
 * @param isLoading True while the transaction is being built and broadcast; shows spinner.
 * @param resultMessage Human-readable outcome string from the ViewModel.
 * @param resultSuccess True on success (green banner), false on error (red banner), null before
 *   any send attempt.
 * @param feeUnavailable True when the ElectrumX fee estimate is unavailable; shows a warning.
 * @param prefillAddress Pre-fills the recipient address field (used in donate mode).
 * @param donateMode When true, locks the address field and shows the donation banner.
 * @param walletBalance Current RVN balance of the wallet; drives the "MAX" button.
 * @param onBack Callback invoked when the user taps back or dismisses the screen.
 * @param onSend Callback invoked after confirmation with (toAddress, amount).
 */
@Composable
fun SendRvnScreen(
    isLoading: Boolean,
    resultMessage: String?,
    resultSuccess: Boolean?,
    feeUnavailable: Boolean = false,
    prefillAddress: String = "",
    donateMode: Boolean = false,
    walletBalance: Double = 0.0,
    onBack: () -> Unit,
    onSend: (toAddress: String, amount: Double) -> Unit
) {
    val s = LocalStrings.current

    // Address field state. Uses prefillAddress as the initial value so donate mode
    // pre-populates the field without the user needing to type anything.
    var toAddress by remember(prefillAddress) { mutableStateOf(prefillAddress) }

    // Amount entered by the user as a raw string; parsed to Double before sending.
    var amount by remember { mutableStateOf("") }

    // Controls whether the pre-send confirmation AlertDialog is visible.
    var showConfirm by remember { mutableStateOf(false) }

    // Controls whether the QR scanner overlay replaces this screen temporarily.
    var showScanner by remember { mutableStateOf(false) }

    // Normalize the decimal separator (comma -> dot) to handle locales that use a comma.
    val parsedAmount = amount.replace(',', '.').toDoubleOrNull() ?: 0.0

    // Form is valid only when address meets the minimum Ravencoin address length and amount > 0.
    val isValid = toAddress.length >= 26 && parsedAmount > 0.0

    // ----------------------------------------------------------------
    // QR scanner overlay: takes over the full screen while active.
    // Returning from the scanner (via onBack or onScanned) restores the send form.
    // ----------------------------------------------------------------
    if (showScanner) {
        QrScannerScreen(
            onScanned = { result ->
                // Parse the decoded QR value:
                //   - "ravencoin:<address>?amount=..." -> extract address part before "?"
                //   - Bare Ravencoin address (R-prefix, 26-34 alphanumeric chars) -> use as-is
                //   - Anything else -> use the trimmed raw string as a best-effort address
                val addr = when {
                    result.startsWith("ravencoin:") -> result.removePrefix("ravencoin:").substringBefore("?")
                    result.matches(Regex("R[1-9A-HJ-NP-Za-km-z]{25,34}")) -> result
                    else -> result.trim()
                }
                toAddress = addr
                showScanner = false
            },
            onBack = { showScanner = false }
        )
        // Return early so the send form is not rendered behind the scanner.
        return
    }

    // ----------------------------------------------------------------
    // Pre-send confirmation dialog: shown after the user taps "Send".
    // Summarizes the amount and recipient; warns that the action is irreversible.
    // ----------------------------------------------------------------
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = Color(0xFF101020),
            title = { Text(s.walletSendDialogTitle, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Replace %1 with the formatted amount and %2 with the address.
                    Text(
                        s.walletSendDialogMsg
                            .replace("%1", "%.8f".format(parsedAmount))
                            .replace("%2", toAddress),
                        color = RavenMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Irreversibility warning in red.
                    Text(s.walletSendWarning, color = NotAuthenticRed.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showConfirm = false; onSend(toAddress, parsedAmount) },
                    colors = ButtonDefaults.buttonColors(containerColor = RavenOrange)
                ) { Text(s.walletSendConfirm, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showConfirm = false },
                    border = androidx.compose.foundation.BorderStroke(1.dp, RavenBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text(s.walletCancelBtn) }
            }
        )
    }

    // ----------------------------------------------------------------
    // Send form UI
    // ----------------------------------------------------------------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Header row: back arrow + screen title.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = s.back, tint = Color.White)
            }
            Text(s.walletSendTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Donation banner: shown when the user arrived via the Settings "Donate" button.
        if (donateMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1200)),
                border = androidx.compose.foundation.BorderStroke(1.dp, RavenOrange.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text("🦅", style = MaterialTheme.typography.headlineSmall)
                    Column {
                        Text(
                            s.settingsDonateTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = RavenOrange
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            s.settingsDonateMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = RavenMuted
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Fee-unavailable warning: shown when ElectrumX cannot provide a fee estimate.
        // The send can still be attempted but a fallback fee will be used.
        if (feeUnavailable) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1200)),
                border = androidx.compose.foundation.BorderStroke(1.dp, RavenOrange.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(20.dp))
                    Text(s.walletSendFeeUnavailable, color = RavenOrange, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Transaction result banner: green on success, red on failure.
        resultSuccess?.let { success ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (success) AuthenticGreenBg else NotAuthenticRedBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (success) AuthenticGreen.copy(0.4f) else NotAuthenticRed.copy(0.4f)),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (success) Icons.Default.CheckCircle else Icons.Default.Error, contentDescription = null,
                        tint = if (success) AuthenticGreen else NotAuthenticRed, modifier = Modifier.size(20.dp))
                    Text(resultMessage ?: "", color = if (success) AuthenticGreen else NotAuthenticRed, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Amount input row: text field + "MAX" button that fills in the current wallet balance.
        SndFieldHeader(s.walletSendAmountLabel)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = amount,
                // Normalize comma to dot on input for locales that use commas as decimal separator.
                onValueChange = { amount = it.replace(',', '.') },
                placeholder = { Text("0.00000000", color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f),
                colors = sndFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                // "RVN" suffix displayed inside the field to clarify the currency.
                suffix = { Text("RVN", color = RavenOrange, style = MaterialTheme.typography.bodySmall) }
            )
            // MAX button: fills in the full wallet balance formatted to 8 decimal places.
            // Disabled when balance is zero to avoid setting 0.00000000 accidentally.
            OutlinedButton(
                onClick = { amount = "%.8f".format(walletBalance) },
                enabled = walletBalance > 0.0,
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(12.dp),
                // Orange border only when the wallet has meaningful balance (>0.01 RVN).
                border = androidx.compose.foundation.BorderStroke(1.dp, if (walletBalance > 0.01) RavenOrange else RavenBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RavenOrange)
            ) {
                Text("MAX", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recipient address row: text field + QR scanner button (hidden in donate mode).
        SndFieldHeader(s.walletSendAddrLabel)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = toAddress,
                // In donate mode the address is fixed; ignore user input.
                onValueChange = { if (!donateMode) toAddress = it },
                enabled = !donateMode,
                placeholder = { Text("RXxxxxxxxxxxxxxxxxxxxxxxxxxxxx", color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RavenOrange,
                    unfocusedBorderColor = RavenBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    // Slightly dimmed text when disabled (donate mode) to signal read-only state.
                    disabledTextColor = Color.White.copy(alpha = 0.7f),
                    disabledBorderColor = RavenBorder,
                    disabledContainerColor = RavenCard,
                    cursorColor = RavenOrange,
                    focusedContainerColor = RavenCard,
                    unfocusedContainerColor = RavenCard
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            // QR scanner button: only shown in normal mode. Hidden in donate mode because
            // the address is already fixed and scanning would be confusing.
            if (!donateMode) {
                IconButton(
                    onClick = { showScanner = true },
                    modifier = Modifier
                        .size(56.dp)
                        .background(RavenCard, RoundedCornerShape(12.dp))
                        .then(Modifier.padding(0.dp))
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR", tint = RavenOrange, modifier = Modifier.size(24.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Send button: shows a spinner while loading, otherwise shows the confirm label.
        // Uses red container color to reinforce that this is an irreversible action.
        Button(
            onClick = { showConfirm = true },
            enabled = isValid && !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NotAuthenticRed, disabledContainerColor = NotAuthenticRed.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(s.walletSendConfirm, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Small uppercase section label with letter-spacing, used above each input field in this screen.
 */
@Composable
private fun SndFieldHeader(label: String) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = RavenMuted,
        letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = Modifier.padding(bottom = 6.dp))
}

/**
 * Shared [OutlinedTextFieldDefaults.colors] configuration for the amount and address fields.
 * Extracted to avoid repeating the same color block for every field.
 */
@Composable
private fun sndFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RavenOrange, unfocusedBorderColor = RavenBorder,
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    cursorColor = RavenOrange, focusedContainerColor = RavenCard, unfocusedContainerColor = RavenCard
)
