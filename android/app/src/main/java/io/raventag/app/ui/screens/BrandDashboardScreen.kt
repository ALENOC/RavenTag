/**
 * BrandDashboardScreen.kt
 *
 * Home screen of the Brand tab. Provides a single entry point to all brand operations:
 * issuing Ravencoin assets, programming NFC chips, and managing revocations.
 *
 * The screen has two distinct rendering paths driven by [hasWallet]:
 *
 *   No wallet: a full-width call-to-action card prompts the operator to create or
 *              restore a wallet before any asset operation can be performed.
 *
 *   Wallet present: all action cards are shown with their enabled/disabled states
 *                   derived from the live server and admin-key status. A "How revocation
 *                   works" explainer section is also rendered at the bottom.
 *
 * Action cards are disabled (greyed out) when either the backend is unreachable or
 * the Admin Key has not been validated, ensuring operations that require server
 * communication cannot be started without a working connection.
 */
package io.raventag.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.raventag.app.MainViewModel
import io.raventag.app.ui.theme.*

/**
 * Brand dashboard composable.
 *
 * @param hasWallet         Whether the operator has a wallet loaded. Determines which
 *                          layout path is rendered (CTA vs. action cards).
 * @param serverStatus      Live connectivity status of the RavenTag backend, polled by the ViewModel.
 * @param walletRole        Wallet access role: "admin" for full access, "operator" for unique token issuance only.
 * @param onIssueAsset      Navigate to ROOT_ASSET issue form.
 * @param onIssueSubAsset   Navigate to SUB_ASSET issue form.
 * @param onIssueUnique     Navigate to UNIQUE_TOKEN issue form.
 * @param onRevokeAsset     Navigate to REVOKE form.
 * @param onUnrevokeAsset   Navigate to UNREVOKE form.
 * @param onGoToWallet      Navigate to the Wallet tab (used by the no-wallet CTA button).
 * @param modifier          Optional modifier forwarded to the root [Column].
 */
@Composable
fun BrandDashboardScreen(
    hasWallet: Boolean,
    serverStatus: MainViewModel.ServerStatus = MainViewModel.ServerStatus.UNKNOWN,
    walletRole: String = "",
    onIssueAsset: () -> Unit,
    onIssueSubAsset: () -> Unit,
    onIssueUnique: () -> Unit,
    onRevokeAsset: () -> Unit,
    onUnrevokeAsset: () -> Unit,
    onGoToWallet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val titleText = s.brandTitle

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RavenBg)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Screen title and subtitle.
        Text(titleText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        Text(s.brandSubtitle, style = MaterialTheme.typography.bodySmall, color = RavenMuted, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(24.dp))

        // Protocol info card: always visible, briefly describes the RTP-1 trustless protocol.
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A0A)),
            border = BorderStroke(1.dp, AuthenticGreen.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, tint = AuthenticGreen, modifier = Modifier.size(18.dp))
                Column {
                    Text("Protocol RTP-1", fontWeight = FontWeight.SemiBold, color = AuthenticGreen, style = MaterialTheme.typography.bodyMedium)
                    Text(s.brandProtocolDesc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (!hasWallet) {
            // No wallet: show CTA for ALL operations
            // The operator must have a wallet before they can sign transactions or call admin APIs.
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1000)),
                border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    // Wallet icon in a small rounded container for visual balance.
                    Surface(shape = RoundedCornerShape(10.dp), color = RavenOrange.copy(alpha = 0.12f), modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(22.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.brandNoWalletMsg, style = MaterialTheme.typography.bodySmall, color = RavenMuted)
                        Spacer(modifier = Modifier.height(12.dp))
                        // CTA button navigates directly to the Wallet tab.
                        Button(
                            onClick = onGoToWallet,
                            colors = ButtonDefaults.buttonColors(containerColor = RavenOrange),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(s.brandGoToWallet, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            // Derive gating flags from the live status values so action cards reflect real-time state.
            val isAdmin = walletRole == "admin"
            val isOperator = walletRole == "operator"
            val serverOnline = serverStatus == MainViewModel.ServerStatus.ONLINE
            // Admin-only operations: root/sub issuance and revocation.
            val canIssue = serverOnline && isAdmin
            // Issue unique tokens also accept operator role.
            val canIssueUnique = serverOnline && (isAdmin || isOperator)

            // Wallet present: all operations visible
            Text(s.brandAssetOps, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = RavenMuted)
            Spacer(modifier = Modifier.height(12.dp))

            // Asset issuance cards ordered by cost: ROOT (500 RVN), SUB (100 RVN), UNIQUE (5 RVN).
            BrandActionCard(Icons.Default.AddCircleOutline, s.brandIssueRoot, s.brandIssueRootDesc, "500 RVN", RavenOrange, onIssueAsset, enabled = canIssue)
            Spacer(modifier = Modifier.height(8.dp))
            BrandActionCard(Icons.Default.AccountTree, s.brandIssueSub, s.brandIssueSubDesc, "100 RVN", RavenOrange, onIssueSubAsset, enabled = canIssue)
            Spacer(modifier = Modifier.height(8.dp))
            BrandActionCard(Icons.Default.Token, s.brandIssueUnique, s.brandIssueUniqueDesc, "5 RVN", AuthenticGreen, onIssueUnique, enabled = canIssueUnique)

            Spacer(modifier = Modifier.height(20.dp))

            // Anti-counterfeiting section: revoke and unrevoke operations.
            Text(s.brandAntiCf, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = RavenMuted)
            Spacer(modifier = Modifier.height(12.dp))

            // "!" badge on Revoke signals a destructive / irreversible action.
            BrandActionCard(Icons.Default.Block, s.brandRevoke, s.brandRevokeDesc, "!", NotAuthenticRed, onRevokeAsset, enabled = canIssue)
            Spacer(modifier = Modifier.height(8.dp))
            // "↩" badge on Unrevoke signals reversal of a prior revocation.
            BrandActionCard(Icons.Default.Restore, s.brandUnrevoke, s.brandUnrevokeDesc, "↩", AuthenticGreen, onUnrevokeAsset, enabled = canIssue)

            Spacer(modifier = Modifier.height(20.dp))

            // Explainer section: three-step description of how revocation works end-to-end.
            Text(s.brandRevocationWorks, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = RavenMuted)
            Spacer(modifier = Modifier.height(12.dp))

            // Steps are rendered from a list to keep the layout DRY; each step is a Triple of
            // (icon, step title, step description).
            listOf(
                Triple(Icons.Default.Flag, s.brandDetect, s.brandDetectDesc),
                Triple(Icons.Default.Block, s.brandRevokeStep, s.brandRevokeStepDesc),
                Triple(Icons.Default.PhoneAndroid, s.brandConsumer, s.brandConsumerDesc),
            ).forEach { (icon, title, desc) ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = RavenCard),
                    border = BorderStroke(1.dp, RavenBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(icon, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Column {
                            Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Tappable action card used for every brand operation in the dashboard.
 *
 * When [enabled] is false, all colors are heavily desaturated and a lock icon
 * replaces the chevron, indicating that the prerequisite conditions (server online
 * + admin key valid) have not been met. The click handler is gated inside the card's
 * onClick so the lambda is silently ignored rather than throwing when disabled.
 *
 * @param icon         Icon rendered in the left icon container.
 * @param title        Primary label text.
 * @param subtitle     Secondary description shown below the title.
 * @param badge        Short badge string shown next to the title (e.g. "500 RVN", "!", "↩").
 * @param badgeColor   Accent color applied to the icon, badge background, and border.
 * @param onClick      Callback invoked when the card is tapped in the enabled state.
 * @param enabled      Whether the card is interactive. False renders a disabled/locked style.
 */
@Composable
private fun BrandActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    // All three color variants are pre-computed so enabled/disabled state is applied consistently.
    val effectiveColor = if (enabled) badgeColor else RavenMuted.copy(alpha = 0.35f)
    val textColor = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    val subtitleColor = if (enabled) RavenMuted else RavenMuted.copy(alpha = 0.35f)

    Card(
        // Guard the click in the lambda: the card's onClick is always set, but the actual
        // callback is only invoked when enabled, preventing accidental navigation.
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.cardColors(containerColor = if (enabled) RavenCard else RavenCard.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, effectiveColor.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Icon container: small rounded square with a low-opacity fill of the accent color.
            Surface(shape = RoundedCornerShape(10.dp), color = effectiveColor.copy(alpha = 0.12f), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = effectiveColor, modifier = Modifier.size(22.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.SemiBold, color = textColor, style = MaterialTheme.typography.bodyMedium)
                    // Small pill badge showing cost or action symbol next to the title.
                    Surface(color = effectiveColor.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                        Text(badge, style = MaterialTheme.typography.labelSmall, color = effectiveColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, modifier = Modifier.padding(top = 3.dp))
            }
            // Trailing icon: chevron when enabled (tappable), lock when disabled (blocked).
            if (enabled) {
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = RavenMuted, modifier = Modifier.size(18.dp))
            } else {
                Icon(Icons.Default.Lock, contentDescription = null, tint = RavenMuted.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
            }
        }
    }
}
