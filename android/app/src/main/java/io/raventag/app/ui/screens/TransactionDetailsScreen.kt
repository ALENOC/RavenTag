package io.raventag.app.ui.screens

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.raventag.app.config.AppConfig
import io.raventag.app.ui.theme.*
import io.raventag.app.wallet.cache.TxHistoryDao

/**
 * Transaction details screen overlay showing txid, amount, confirmations, and status.
 *
 * Implements D-04 from CONTEXT.md: Tapping send notification opens to transaction details screen.
 *
 * @param txid Transaction ID to display details for
 * @param onClose Callback when user taps close button
 */
@Composable
fun TransactionDetailsScreen(
    txid: String,
    onClose: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    var transaction by remember { mutableStateOf<Transaction?>(null) }
    var txRow by remember { mutableStateOf<TxHistoryDao.TxHistoryRow?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(txid) {
        isLoading = true
        errorMessage = null
        try {
            // D-19 primary source: local TxHistoryDao with three-value breakdown.
            val row = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    TxHistoryDao.findByTxid(txid)
                }
            } catch (_: Exception) { null }
            txRow = row
            transaction = Transaction(
                txid = txid,
                amount = (row?.amountSat ?: 0L) / 1e8,
                fee = (row?.feeSat ?: 0L) / 1e8,
                confirmations = row?.confirms ?: 0,
                blockHeight = (row?.height ?: 0).toLong(),
                from = "",
                to = "",
                timestamp = row?.timestamp ?: 0L
            )
        } catch (e: Exception) {
            Log.e("TransactionDetailsScreen", "Failed to fetch transaction", e)
            errorMessage = e.message ?: "Unknown error"
        } finally {
            isLoading = false
        }
    }

    // Full-screen overlay with semi-transparent background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
    ) {
        // Card with transaction details
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.Center),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = RavenCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = RavenMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Loading state
                if (isLoading) {
                    CircularProgressIndicator(
                        color = RavenOrange,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Loading transaction details...",
                        color = RavenMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Error state
                else if (errorMessage != null) {
                    Text(
                        text = "Failed to load transaction",
                        color = NotAuthenticRed,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = RavenMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
                // Transaction details
                else if (transaction != null) {
                    Text(
                        text = "Transaction Details",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Status badge
                    val statusColor = when {
                        transaction!!.confirmations > 0 -> Color(0xFF4ADE80) // Green
                        else -> RavenOrange
                    }
                    val statusText = when {
                        transaction!!.confirmations > 0 -> "Confirmed"
                        else -> "Pending"
                    }

                    Surface(
                        color = statusColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Transaction details in scrollable column
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Transaction ID
                        DetailRow(label = "Transaction ID", value = transaction!!.txid)

                        // Confirmations
                        DetailRow(
                            label = "Confirmations",
                            value = "${transaction!!.confirmations}",
                            valueColor = if (transaction!!.confirmations > 0) RavenOrange else RavenMuted
                        )

                        // Block height (if confirmed)
                        if (transaction!!.blockHeight > 0) {
                            DetailRow(label = "Block Height", value = "${transaction!!.blockHeight}")
                        }

                        // D-19 three-value breakdown for outgoing transactions.
                        val row = txRow
                        if (row != null && !row.isIncoming) {
                            val sentStr = formatRvn(row.sentSat)
                            val cycledStr = formatRvn(row.cycledSat)
                            val feeStr = formatRvn(row.feeSat)
                            if (!row.isSelf) {
                                ThreeValueRow(
                                    icon = Icons.Default.CallMade,
                                    tint = NotAuthenticRed,
                                    label = strings.txHistorySentPrefix,
                                    amount = "-$sentStr RVN",
                                    amountColor = NotAuthenticRed,
                                    bold = true
                                )
                            }
                            ThreeValueRow(
                                icon = Icons.Default.Autorenew,
                                tint = AuthenticGreen,
                                label = strings.txHistoryCycledPrefix,
                                amount = "$cycledStr RVN",
                                amountColor = AuthenticGreen,
                                bold = false
                            )
                            ThreeValueRow(
                                icon = Icons.Default.Payments,
                                tint = RavenMuted,
                                label = strings.txHistoryFeePrefix,
                                amount = "$feeStr RVN",
                                amountColor = RavenMuted,
                                bold = false
                            )
                        } else {
                            // Incoming or legacy fallback: keep prior single-amount layout.
                            if (transaction!!.amount > 0) {
                                DetailRow(
                                    label = "Amount",
                                    value = "${transaction!!.amount} RVN",
                                    valueColor = RavenOrange,
                                    valueBold = true
                                )
                            }
                            if (transaction!!.fee > 0) {
                                DetailRow(
                                    label = strings.txHistoryFeePrefix,
                                    value = "${transaction!!.fee} RVN"
                                )
                            }
                        }

                        // From address (truncated)
                        if (transaction!!.from.isNotEmpty()) {
                            DetailRow(
                                label = "From",
                                value = transaction!!.from.take(20) + "..."
                            )
                        }

                        // To address (truncated)
                        if (transaction!!.to.isNotEmpty()) {
                            DetailRow(
                                label = "To",
                                value = transaction!!.to.take(20) + "..."
                            )
                        }

                        // Timestamp (if available)
                        if (transaction!!.timestamp > 0) {
                            val date = java.util.Date(transaction!!.timestamp * 1000)
                            DetailRow(
                                label = "Timestamp",
                                value = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(date)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // D-19 View on explorer: opens Intent.ACTION_VIEW at AppConfig.EXPLORER_URL + txid.
                    OutlinedButton(
                        onClick = {
                            val uri = android.net.Uri.parse(AppConfig.EXPLORER_URL + txid)
                            try {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                )
                            } catch (_: android.content.ActivityNotFoundException) {
                                // No browser available; silent (ASVS V7 error handling).
                            }
                        },
                        border = BorderStroke(1.dp, RavenOrange),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RavenOrange),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = null,
                            tint = RavenOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.txDetailsViewOnExplorer, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/** D-19 three-value breakdown row (icon + label + amount) for outgoing tx details. */
@Composable
private fun ThreeValueRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    amount: String,
    amountColor: Color,
    bold: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                color = RavenMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = amount,
            color = amountColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End
        )
    }
}

private fun formatRvn(sat: Long): String {
    if (sat <= 0L) return "0"
    return String.format(java.util.Locale.US, "%.8f", sat / 1e8).trimEnd('0').trimEnd('.')
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.White,
    valueBold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = RavenMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (valueBold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2f)
        )
    }
}

/**
 * Data class representing a blockchain transaction.
 */
data class Transaction(
    val txid: String,
    val amount: Double,
    val fee: Double,
    val confirmations: Int,
    val blockHeight: Long,
    val from: String,
    val to: String,
    val timestamp: Long
)
