package io.raventag.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.raventag.app.ui.theme.*

/**
 * Screen for transferring a Ravencoin asset (unique token, sub-asset, or root asset) to a
 * recipient address.
 *
 * Supports three operational modes driven by [IssueMode]:
 *   - [IssueMode.TRANSFER]: transfer a unique token (consumer or brand use).
 *   - [IssueMode.TRANSFER_ROOT]: transfer root asset ownership to another wallet.
 *   - [IssueMode.TRANSFER_SUB]: transfer sub-asset ownership to another wallet.
 *
 * The root and sub-asset ownership modes display an additional orange warning card because the
 * operation permanently hands over the ability to issue new child tokens under that asset.
 *
 * Form validation requires:
 *   - Asset name at least 3 characters.
 *   - Recipient address at least 26 characters (Ravencoin P2PKH minimum).
 *   - Quantity at least 1 (digit-only).
 *
 * @param isLoading True while the transfer RPC call is in flight; shows a spinner and disables
 *   the submit button to prevent double-submission.
 * @param resultMessage Human-readable outcome message from the ViewModel after the transfer.
 * @param resultSuccess True on success (green banner), false on error (red banner), null before
 *   any submission attempt.
 * @param mode The transfer sub-mode that determines title, subtitle, and whether the ownership
 *   warning is displayed.
 * @param prefilledAssetName Optional asset name pre-populated in the asset field (e.g. when the
 *   user taps "Transfer" directly from their asset list).
 * @param showLowRvnWarning True when the wallet balance is below 0.01 RVN, warning the user that
 *   the ~0.01 RVN fee may not be coverable.
 * @param onBack Callback invoked when the user taps the back arrow.
 * @param onTransfer Callback invoked when the user submits the form. Receives the asset name,
 *   recipient address, and quantity.
 */
@Composable
fun TransferScreen(
    isLoading: Boolean,
    resultMessage: String?,
    resultSuccess: Boolean?,
    mode: IssueMode = IssueMode.TRANSFER,
    prefilledAssetName: String? = null,
    showLowRvnWarning: Boolean = false,
    onBack: () -> Unit,
    onTransfer: (assetName: String, toAddress: String, qty: Long) -> Unit
) {
    val s = LocalStrings.current

    // Mutable state for each form field.
    var assetName by remember { mutableStateOf(prefilledAssetName ?: "") }
    var toAddress by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }

    // Controls whether the QR scanner overlay replaces this screen temporarily.
    var showScanner by remember { mutableStateOf(false) }

    // QR scanner overlay: takes over the full screen while active.
    if (showScanner) {
        QrScannerScreen(
            onScanned = { result ->
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
        return
    }

    // True for root/sub ownership transfers, which carry additional irreversibility warnings.
    val isOwnershipTransfer = mode == IssueMode.TRANSFER_ROOT || mode == IssueMode.TRANSFER_SUB

    // Form is valid when all three fields meet the minimum length/value constraints.
    val isValid = assetName.length >= 3 && toAddress.length >= 26 && (qty.toLongOrNull() ?: 0L) >= 1

    // Derive display strings from the mode enum so the UI adapts without conditional logic below.
    val title = when (mode) {
        IssueMode.TRANSFER_ROOT -> s.transferRootTitle
        IssueMode.TRANSFER_SUB -> s.transferSubTitle
        else -> s.transferTitle
    }
    val subtitle = when (mode) {
        IssueMode.TRANSFER_ROOT -> s.transferRootSubtitle
        IssueMode.TRANSFER_SUB -> s.transferSubSubtitle
        else -> s.transferSubtitle
    }
    // Placeholder asset name shown in the asset name field depending on the expected asset type.
    val placeholder = when (mode) {
        IssueMode.TRANSFER_ROOT -> "FASHIONX"
        IssueMode.TRANSFER_SUB -> "FASHIONX/BAG01"
        else -> "FASHIONX/BAG001#SN0001"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Header row: back button + title and subtitle.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = s.back, tint = Color.White)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RavenMuted)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Ownership warning: shown only for root/sub transfers because those actions permanently
        // move control of all child tokens to the recipient's wallet.
        if (isOwnershipTransfer) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1A00)),
                border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp))
                    Text(s.transferOwnershipWarning, style = MaterialTheme.typography.bodySmall, color = RavenOrange.copy(alpha = 0.9f))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Result banner: green on success, red on error. Only rendered after a submission attempt.
        // If resultMessage starts with "tx:" the value is a transaction ID and monospace font is
        // used for easier reading and copying.
        resultSuccess?.let { success ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (success) AuthenticGreenBg else NotAuthenticRedBg),
                border = BorderStroke(1.dp, if (success) AuthenticGreen.copy(0.4f) else NotAuthenticRed.copy(0.4f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (success) AuthenticGreen else NotAuthenticRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        resultMessage ?: "",
                        color = if (success) AuthenticGreen else NotAuthenticRed,
                        style = MaterialTheme.typography.bodySmall,
                        // Use monospace when the success message contains a transaction ID.
                        fontFamily = if (success && resultMessage?.startsWith("tx:") == true) FontFamily.Monospace else FontFamily.Default
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Low-balance warning: shown in the consumer flavor when RVN balance is below the
        // estimated ~0.01 RVN transfer fee.
        if (showLowRvnWarning) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1A00)),
                border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp))
                    Text(s.assetsLowRvnWarning, style = MaterialTheme.typography.bodySmall, color = RavenOrange.copy(alpha = 0.9f))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Asset name field: forced to uppercase on every keystroke for Ravencoin naming rules.
        FieldLabel(s.fieldAssetName, "e.g. $placeholder")
        OutlinedTextField(
            value = assetName,
            onValueChange = { assetName = it.uppercase() },
            placeholder = { Text(placeholder, color = RavenMuted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth(),
            colors = transferFieldColors(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Recipient address field: accepts any Ravencoin address string (R-prefixed, 34 chars).
        // QR scanner button on the right allows scanning the recipient's address from a QR code.
        FieldLabel(s.fieldRecipient, s.fieldRecipientHint)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = toAddress,
                onValueChange = { toAddress = it },
                placeholder = { Text("RXxxxxxxxxxxxxxxxxxxxxxxxxxxxx", color = RavenMuted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.weight(1f),
                colors = transferFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            IconButton(
                onClick = { showScanner = true },
                modifier = Modifier
                    .size(56.dp)
                    .background(RavenCard, RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = s.scanQr, tint = RavenOrange, modifier = Modifier.size(24.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quantity field: numeric only; NFC-linked unique tokens are almost always qty = 1.
        FieldLabel(s.fieldQtyLabel, s.fieldQtyHint)
        OutlinedTextField(
            value = qty,
            // Filter out any non-digit characters to keep the field numeric.
            onValueChange = { qty = it.filter { c -> c.isDigit() } },
            placeholder = { Text("1", color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            colors = transferFieldColors(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Submit button: disabled while loading or when form validation fails.
        // Falls back to qty = 1 if the quantity field cannot be parsed (e.g. empty string).
        Button(
            onClick = { onTransfer(assetName, toAddress, qty.toLongOrNull() ?: 1L) },
            enabled = isValid && !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RavenOrange, disabledContainerColor = RavenOrange.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isLoading) {
                // Show spinner while the on-chain transaction is being broadcast.
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * Small uppercase section label with a hint line used above each input field.
 * The [hint] parameter is currently unused in the UI but kept for future tooltip support.
 */
@Composable
private fun FieldLabel(label: String, hint: String? = null) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = RavenMuted,
        letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp))
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * Shared [OutlinedTextFieldDefaults.colors] configuration used by all three input fields.
 * Centralised here so a single change propagates to every field in this screen.
 */
@Composable
private fun transferFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RavenOrange,
    unfocusedBorderColor = RavenBorder,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = RavenOrange,
    focusedContainerColor = RavenCard,
    unfocusedContainerColor = RavenCard
)
