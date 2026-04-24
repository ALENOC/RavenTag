package io.raventag.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.raventag.app.ipfs.IpfsResolver
import io.raventag.app.MainViewModel
import io.raventag.app.ravencoin.AssetType
import io.raventag.app.ravencoin.OwnedAsset
import io.raventag.app.ui.theme.*
import io.raventag.app.wallet.TxHistoryEntry
import io.raventag.app.wallet.cache.TxHistoryDao
import okhttp3.Request
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.raventag.app.network.NetworkModule
import io.raventag.app.wallet.cache.WalletCacheDao
import io.raventag.app.wallet.health.ConnectionHealth
import io.raventag.app.wallet.health.NodeHealthMonitor
import io.raventag.app.wallet.subscription.ScripthashEvent
import io.raventag.app.wallet.subscription.SubscriptionManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect

data class WalletInfo(
    val address: String,
    val balanceRvn: Double,
    val mnemonic: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

private fun assetPreviewCandidates(asset: OwnedAsset): List<String> {
    return when {
        asset.imageUrl != null -> IpfsResolver.candidateUrls(asset.imageUrl)
        asset.ipfsHash != null -> IpfsResolver.candidateUrls(asset.ipfsHash)
        else -> emptyList()
    }
}

@Composable
fun WalletScreen(
    walletInfo: WalletInfo?,
    hasWallet: Boolean,
    isGenerating: Boolean = false,
    restoreError: String? = null,
    ownedAssets: List<OwnedAsset>?,
    assetsLoading: Boolean,
    assetsLoadError: Boolean = false,
    needsConsolidation: Boolean = false,
    consolidationInProgress: Boolean = false,
    onConsolidateFunds: (() -> Unit)? = null,
    electrumStatus: MainViewModel.ElectrumStatus = MainViewModel.ElectrumStatus.UNKNOWN,
    blockHeight: Int? = null,
    rvnPrice: Double? = null,
    networkHashrate: Double? = null,
    onGenerateWallet: (controlKey: String) -> Unit,
    onRestoreWallet: (mnemonic: String, controlKey: String) -> Unit,
    onRefreshBalance: () -> Unit,
    onDeleteWallet: () -> Unit,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onTransferAsset: ((asset: OwnedAsset) -> Unit)? = null,
    walletBalance: Double = 0.0,
    txHistory: List<TxHistoryEntry> = emptyList(),
    txHistoryLoading: Boolean = false,
    txHistoryTotal: Int = 0,
    txHistoryLoadedCount: Int = 0,
    onLoadMoreTransactions: () -> Unit = {},
    isBrandApp: Boolean = io.raventag.app.config.AppConfig.IS_BRAND_APP,
    walletRole: String = "",
    controlKeyValidating: Boolean = false,
    controlKeyError: String? = null,
    onRestoreModeChange: (Boolean) -> Unit = {},
    onNavigateToMnemonicBackup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var pendingTransferAsset by remember { mutableStateOf<OwnedAsset?>(null) }
    var showMnemonic by remember { mutableStateOf(false) }
    // D-23 extra paged rows appended locally via TxHistoryDao.getPage / getHistoryPaged
    // in addition to txHistory provided by MainViewModel.
    var extraTxHistory by remember { mutableStateOf<List<TxHistoryEntry>>(emptyList()) }
    // Reset local pagination when the active wallet address changes.
    LaunchedEffect(walletInfo?.address) { extraTxHistory = emptyList() }
    var showRestore by remember { mutableStateOf(false) }
    var restoreWords by remember { mutableStateOf(List(12) { "" }) }
    var controlKey by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRestoreArgs by remember { mutableStateOf<Pair<String, String>?>(null) }
    var assetFilter by remember { mutableStateOf<AssetType?>(null) } // null = All
    var previewAsset by remember { mutableStateOf<OwnedAsset?>(null) }
    var showOwnerTokens by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val isOperator = walletRole == "operator"

    // D-12: NodeHealthMonitor.stateFlow drives the pill and Send/Receive enabled state.
    val health by NodeHealthMonitor.stateFlow.collectAsState(initial = ConnectionHealth.GREEN)
    var showConnectionSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // D-04: cached banner state; flipped false once a successful refresh has been observed.
    var cachedBannerVisible by remember { mutableStateOf(true) }
    var cachedLastRefreshedAt by remember { mutableStateOf(0L) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cachedLastRefreshedAt = WalletCacheDao.getLastRefreshedAt()
    }

    // D-28: battery-saver chip visibility.
    val isPowerSave = remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isPowerSaveMode == true
    }

    // D-02, D-26: 30-second periodic refresh while foreground and not power-save.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isPowerSaveMode != true) {
                isRefreshing = true
                onRefreshBalance()
                isRefreshing = false
                cachedLastRefreshedAt = WalletCacheDao.getLastRefreshedAt()
                cachedBannerVisible = false
            }
        }
    }

    // D-05, D-07: SubscriptionManager scripthash events -> re-fetch + incoming snackbar on positive delta.
    val subscriptionManager = remember { SubscriptionManager(context) }
    val strings = s
    LaunchedEffect(walletInfo?.address) {
        val addr = walletInfo?.address
        if (!addr.isNullOrBlank()) {
            try { subscriptionManager.start(listOf(addr)) } catch (_: Exception) {}
        }
        subscriptionManager.eventsFlow().collect { ev ->
            when (ev) {
                is ScripthashEvent.StatusChanged -> {
                    val beforeSat = WalletCacheDao.readState()?.balanceSat ?: 0L
                    onRefreshBalance()
                    val afterSat = WalletCacheDao.readState()?.balanceSat ?: 0L
                    val deltaSat = afterSat - beforeSat
                    if (deltaSat > 0L) {
                        val rvn = String.format(java.util.Locale.ROOT, "%.8f", deltaSat / 1e8)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                String.format(strings.incomingTxSnackbar, rvn)
                            )
                        }
                    }
                    cachedLastRefreshedAt = WalletCacheDao.getLastRefreshedAt()
                    cachedBannerVisible = false
                }
                else -> {}
            }
        }
    }

    if (showConnectionSheet) {
        ConnectionPillSheet(onDismiss = { showConnectionSheet = false })
    }

    previewAsset?.let { asset ->
        AssetPreviewDialog(asset = asset, onDismiss = { previewAsset = null })
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF1A0000),
            title = { Text(s.walletDeleteTitle, color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(s.walletDeleteMsg, color = RavenMuted, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; onDeleteWallet() },
                    colors = ButtonDefaults.buttonColors(containerColor = NotAuthenticRed)
                ) { Text(s.walletDeleteBtn, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = false },
                    border = BorderStroke(1.dp, RavenBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text(s.walletCancelBtn) }
            }
        )
    }

    if (showRestoreConfirmDialog) {
        val prefs = context.getSharedPreferences("raventag_wallet", Context.MODE_PRIVATE)
        val hasBackedUp = prefs.getBoolean("backup_completed", false)
        val assetsCount = ownedAssets?.size ?: 0
        RestoreWalletConfirmDialog(
            hasBackedUp = hasBackedUp,
            rvnAmount = walletBalance,
            assetsCount = assetsCount,
            onDismiss = {
                showRestoreConfirmDialog = false
                pendingRestoreArgs = null
            },
            onBackupFirst = {
                showRestoreConfirmDialog = false
                pendingRestoreArgs = null
                onNavigateToMnemonicBackup()
            },
            onReplace = {
                val args = pendingRestoreArgs
                showRestoreConfirmDialog = false
                pendingRestoreArgs = null
                if (args != null) onRestoreWallet(args.first, args.second)
            }
        )
    }

    if (pendingTransferAsset != null && pendingTransferAsset!!.type != AssetType.UNIQUE) {
        AlertDialog(
            onDismissRequest = { pendingTransferAsset = null },
            containerColor = Color(0xFF2D1A00),
            title = { Text(s.brandOwnershipTransfer, color = RavenOrange, fontWeight = FontWeight.Bold) },
            text = { Text(s.transferOwnershipWarning, color = RavenMuted, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = { onTransferAsset?.invoke(pendingTransferAsset!!); pendingTransferAsset = null },
                    colors = ButtonDefaults.buttonColors(containerColor = RavenOrange)
                ) { Text(s.assetsTransferBtn) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingTransferAsset = null },
                    border = BorderStroke(1.dp, RavenBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text(s.walletCancelBtn) }
            }
        )
    }

    val filteredAssets = remember(ownedAssets, assetFilter, showOwnerTokens) {
        ownedAssets.orEmpty().filter { asset ->
            val typeMatch = assetFilter == null || asset.type == assetFilter
            val ownerTokenMatch = showOwnerTokens || !asset.name.endsWith("!")
            typeMatch && ownerTokenMatch
        }
    }

    // Full-screen loading during wallet restore (per UI-SPEC.md).
    // Shown when the wallet is being generated or an initial restore is in progress.
    // Keeps the screen simple and gives a clear signal that heavy work is happening.
    if (hasWallet && walletInfo?.isLoading == true && walletInfo.balanceRvn == 0.0 && ownedAssets.isNullOrEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().background(RavenBg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = RavenOrange,
                    strokeWidth = 3.dp
                )
                Text(
                    text = s.walletLoading,
                    color = RavenMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RavenBg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "top_spacer") { Spacer(modifier = Modifier.height(24.dp)) }

        // Error banner for wallet restore errors (per UI-SPEC.md transient error pattern).
        // Shown near the top so the user sees it immediately on return to the wallet tab.
        // The existing WalletSetupCard also displays the error when no wallet exists yet;
        // this banner covers the case where the wallet is present but a restore/refresh failed.
        if (hasWallet && restoreError != null) {
            item(key = "restore_error_banner") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = NotAuthenticRedBg),
                    border = BorderStroke(1.dp, NotAuthenticRed.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = NotAuthenticRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = restoreError,
                            color = NotAuthenticRed,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = onRefreshBalance,
                            colors = ButtonDefaults.buttonColors(containerColor = RavenOrange),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = s.retry,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Header
        item(key = "header") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(if (isBrandApp) s.walletTitle else s.navWallet, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    if (isBrandApp) Text(s.walletSubtitle, style = MaterialTheme.typography.bodySmall, color = RavenMuted)
                    if (hasWallet) {
                        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = AuthenticGreen, modifier = Modifier.size(12.dp))
                            Text("Android Keystore \u00b7 AES-256-GCM", style = MaterialTheme.typography.labelSmall, color = AuthenticGreen.copy(alpha = 0.8f))
                        }
                        if (isBrandApp && walletRole.isNotEmpty()) {
                            val roleColor = if (isOperator) Color(0xFF60A5FA) else RavenOrange
                            val roleLabel = if (isOperator) s.walletRoleOperator else s.walletRoleAdmin
                            Row(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (isOperator) Icons.Default.ManageAccounts else Icons.Default.AdminPanelSettings, contentDescription = null, tint = roleColor, modifier = Modifier.size(11.dp))
                                Text(roleLabel, style = MaterialTheme.typography.labelSmall, color = roleColor)
                            }
                        }
                        // ElectrumX status badge (legacy, kept for existing telemetry)
                        ElectrumStatusBadge(electrumStatus, s)

                        // D-12: NodeHealthMonitor-driven pill with YELLOW state + tap-to-sheet.
                        ConnectionHealthPill(health = health, onTap = { showConnectionSheet = true })

                        // D-28: battery-saver informational chip.
                        if (isPowerSave) { BatterySaverChip() }

                        // Block height counter (Always occupy space to avoid layout shift)
                        val showBlockHeight = blockHeight != null && electrumStatus == MainViewModel.ElectrumStatus.ONLINE
                        Box(modifier = Modifier.alpha(if (showBlockHeight) 1f else 0f)) {
                            BlockHeightBadge(blockHeight ?: 0)
                        }

                        // Network hashrate (Always occupy space to avoid layout shift)
                        val showHashrate = networkHashrate != null && electrumStatus == MainViewModel.ElectrumStatus.ONLINE
                        Box(modifier = Modifier.alpha(if (showHashrate) 1f else 0f)) {
                            HashrateRow(networkHashrate ?: 0.0)
                        }
                    }
                }
                Row {
                    if (hasWallet) {
                        IconButton(onClick = onRefreshBalance) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = RavenOrange)
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.DeleteForever, contentDescription = "Delete wallet", tint = NotAuthenticRed)
                        }
                    }
                }
            }
        }

        // D-04: sync-in-background 2dp LinearProgressIndicator under header
        if (isRefreshing) {
            item(key = "sync_indicator") {
                LinearProgressIndicator(
                    color = RavenOrange,
                    trackColor = RavenBorder,
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
        }

        // D-04: cached-state banner
        if (hasWallet && cachedBannerVisible && cachedLastRefreshedAt > 0L) {
            item(key = "cached_banner") {
                CachedStateBanner(
                    lastRefreshedAt = cachedLastRefreshedAt,
                    isReconnecting = health == ConnectionHealth.YELLOW,
                    visible = true
                )
            }
        }

        item(key = "header_spacer") { Spacer(modifier = Modifier.height(24.dp)) }

        if (!hasWallet) {
            item(key = "setup") {
                WalletSetupCard(
                    strings = s,
                    showRestore = showRestore,
                    restoreWords = restoreWords,
                    isGenerating = isGenerating,
                    isBrandApp = isBrandApp,
                    controlKey = controlKey,
                    controlKeyValidating = controlKeyValidating,
                    controlKeyError = controlKeyError,
                    restoreError = restoreError,
                    onControlKeyChange = { controlKey = it },
                    onWordChange = { idx, word ->
                        restoreWords = restoreWords.toMutableList().also { it[idx] = word }
                    },
                    onGenerate = { showRestore = false; restoreWords = List(12) { "" }; onRestoreModeChange(false); onGenerateWallet(controlKey) },
                    onToggleRestore = { val next = !showRestore; showRestore = next; restoreWords = List(12) { "" }; onRestoreModeChange(next) },
                    onRestore = {
                        // D-14: if the current wallet holds funds or assets, gate the
                        // restore with a destructive-confirm dialog and a forced-backup
                        // variant when `backup_completed` is false.
                        val phrase = restoreWords.joinToString(" ")
                        val assetsCount = ownedAssets?.size ?: 0
                        val hasFunds = walletBalance > 0.0 || assetsCount > 0
                        if (hasFunds) {
                            pendingRestoreArgs = phrase to controlKey
                            showRestoreConfirmDialog = true
                        } else {
                            onRestoreWallet(phrase, controlKey)
                        }
                    }
                )
            }
        } else if (walletInfo != null) {
            item(key = "balance") {
                Column {
                    BalanceCard(s, walletInfo, rvnPrice = rvnPrice, onCopyAddress = { clipboard.setText(AnnotatedString(walletInfo.address)) })
                    // D-24: pending mempool incoming line (reads reserved-aware cache value).
                    val mempoolSat = remember(walletInfo.balanceRvn) {
                        (WalletCacheDao.readState()?.utxos.orEmpty())
                            .filter { it.height <= 0 }
                            .sumOf { it.satoshis }
                    }
                    PendingBalanceLine(mempoolIncomingSat = mempoolSat)
                }
            }
            item(key = "balance_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            if (walletInfo.mnemonic != null) {
                item(key = "mnemonic") { MnemonicCard(s, walletInfo.mnemonic, visible = showMnemonic, onToggle = { showMnemonic = !showMnemonic }) }
                item(key = "mnemonic_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
            item(key = "actions") {
                // D-12: ConnectionHealth.RED disables Send/Receive with a Snackbar on tap.
                val offline = health == ConnectionHealth.RED
                val alphaMod = if (offline) 0.3f else 1f
                val offlineTapMod = if (offline) {
                    Modifier.clickable {
                        scope.launch { snackbarHostState.showSnackbar(s.offlineAllNodesUnreachable) }
                    }
                } else Modifier
                Row(modifier = Modifier.fillMaxWidth().then(offlineTapMod), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onReceive,
                        enabled = !offline,
                        modifier = Modifier.weight(1f).height(48.dp).alpha(alphaMod),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RavenCard,
                            disabledContainerColor = RavenCard,
                            disabledContentColor = RavenMuted
                        ),
                        border = BorderStroke(1.dp, if (offline) RavenMuted.copy(alpha = 0.4f) else AuthenticGreen.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CallReceived, contentDescription = null, tint = if (offline) RavenMuted else AuthenticGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.walletReceiveBtn, color = if (offline) RavenMuted else AuthenticGreen, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { if (!isOperator) onSend() },
                        enabled = !offline,
                        modifier = Modifier.weight(1f).height(48.dp).alpha(alphaMod),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RavenCard,
                            disabledContainerColor = RavenCard,
                            disabledContentColor = RavenMuted
                        ),
                        border = BorderStroke(1.dp, (if (offline || isOperator) RavenMuted else NotAuthenticRed).copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(if (isOperator) Icons.Default.Lock else Icons.Default.Send, contentDescription = null, tint = if (offline || isOperator) RavenMuted else NotAuthenticRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.walletSendBtn, color = if (offline || isOperator) RavenMuted else NotAuthenticRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (walletInfo.error != null) {
                item(key = "error_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "error") {
                    Card(colors = CardDefaults.cardColors(containerColor = NotAuthenticRedBg), border = BorderStroke(1.dp, NotAuthenticRed.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = NotAuthenticRed, modifier = Modifier.size(18.dp))
                            Text(walletInfo.error, style = MaterialTheme.typography.bodySmall, color = NotAuthenticRed)
                        }
                    }
                }
            }
            item(key = "after_actions_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            if (!isBrandApp && walletBalance < 0.01 && hasWallet && !assetsLoading && !ownedAssets.isNullOrEmpty()) {
                item(key = "low_rvn") {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1A00)), border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.5f)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(16.dp))
                            Text(s.assetsLowRvnWarning, style = MaterialTheme.typography.bodySmall, color = RavenOrange.copy(alpha = 0.9f))
                        }
                    }
                }
            }
            // Consolidation banner: shown when funds are detected on old addresses
            if (needsConsolidation && onConsolidateFunds != null && !assetsLoading && !consolidationInProgress) {
                item(key = "consolidation_banner") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2D00)),
                        border = BorderStroke(1.dp, AuthenticGreen.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SyncProblem, contentDescription = null, tint = AuthenticGreen, modifier = Modifier.size(16.dp))
                                Text(
                                    "Funds detected on old addresses",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuthenticGreen.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Consolidate all RVN and assets to a fresh, secure address",
                                style = MaterialTheme.typography.bodySmall,
                                color = RavenMuted
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onConsolidateFunds,
                                colors = ButtonDefaults.buttonColors(containerColor = AuthenticGreen),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Consolidate to Fresh Address", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            // Consolidation in progress banner
            if (consolidationInProgress) {
                item(key = "consolidation_progress") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2D00)),
                        border = BorderStroke(1.dp, AuthenticGreen.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = AuthenticGreen, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Consolidating funds to fresh address...",
                                style = MaterialTheme.typography.bodySmall,
                                color = AuthenticGreen.copy(alpha = 0.9f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            item(key = "assets_header") {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.walletMyAssets, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = RavenMuted)
                    if (assetsLoading || walletInfo.isLoading) CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            item(key = "assets_header_spacer") { Spacer(modifier = Modifier.height(10.dp)) }
            item(key = "asset_filters") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to s.walletFilterAll, AssetType.ROOT to s.walletAssetRoot, AssetType.SUB to s.walletAssetSub, AssetType.UNIQUE to s.walletAssetUnique).forEach { (type, label) ->
                        val selected = assetFilter == type
                        val typeColor = when(type) { AssetType.ROOT -> RavenOrange; AssetType.SUB -> Color(0xFF60A5FA); AssetType.UNIQUE -> AuthenticGreen; else -> RavenMuted }
                        FilterChip(selected = selected, onClick = { assetFilter = type }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = typeColor.copy(alpha = 0.15f), selectedLabelColor = typeColor, containerColor = RavenCard, labelColor = RavenMuted), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, selectedBorderColor = typeColor.copy(alpha = 0.4f), borderColor = RavenBorder))
                    }
                }
            }
            item(key = "asset_filters_spacer") { Spacer(modifier = Modifier.height(4.dp)) }
            // Error card: only when no cached assets are available to show
            if (!assetsLoading && assetsLoadError && filteredAssets.isEmpty()) {
                item(key = "assets_load_error") {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0D00)), border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp))
                            Text(s.walletAssetsNotVerifiable, style = MaterialTheme.typography.bodySmall, color = RavenOrange)
                        }
                    }
                }
            } else if (!assetsLoading && !walletInfo.isLoading && filteredAssets.isEmpty()) {
                item(key = "assets_empty") {
                    Card(colors = CardDefaults.cardColors(containerColor = RavenCard), border = BorderStroke(1.dp, RavenBorder), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.padding(20.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(s.walletNoAssets, style = MaterialTheme.typography.bodySmall, color = RavenMuted, textAlign = TextAlign.Center)
                        }
                    }
                }
            } else {
                // Operators can only transfer UNIQUE tokens; ROOT/SUB transfers are admin-only.
                items(filteredAssets, key = { it.name }) { asset ->
                    val canTransferThis = onTransferAsset != null && (!isOperator || asset.type == AssetType.UNIQUE)
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        AssetCard(s = s, asset = asset, onPreview = if (asset.imageUrl != null || asset.ipfsHash != null) ({ previewAsset = asset }) else null, onTransfer = if (canTransferThis) { { if (asset.type != AssetType.UNIQUE) { pendingTransferAsset = asset } else { onTransferAsset!!.invoke(asset) } } } else null)
                    }
                }
            }
            item(key = "owner_tokens_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
            item(key = "owner_tokens") {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = s.walletShowOwnerTokens, style = MaterialTheme.typography.labelSmall, color = RavenMuted, modifier = Modifier.padding(end = 12.dp))
                    Switch(checked = showOwnerTokens, onCheckedChange = { showOwnerTokens = it }, colors = SwitchDefaults.colors(checkedThumbColor = RavenOrange, checkedTrackColor = RavenOrange.copy(alpha = 0.3f), uncheckedThumbColor = RavenMuted, uncheckedTrackColor = RavenMuted.copy(alpha = 0.3f)), modifier = Modifier.size(width = 40.dp, height = 24.dp))
                }
            }
            item(key = "tx_section_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "tx_header") {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.walletTxHistory, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = RavenMuted)
                    if (txHistoryLoading) CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            item(key = "tx_header_spacer") { Spacer(modifier = Modifier.height(10.dp)) }
            if (!txHistoryLoading && txHistory.isEmpty() && extraTxHistory.isEmpty()) {
                // D-23 UI-SPEC empty state: heading + body (verbatim Copywriting Contract).
                item(key = "tx_empty") {
                    Card(colors = CardDefaults.cardColors(containerColor = RavenCard), border = BorderStroke(1.dp, RavenBorder), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = s.txHistoryEmptyHeading,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = s.txHistoryEmptyBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = RavenMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(txHistory, key = { "vm_${it.txid}" }) { tx ->
                    Box(modifier = Modifier.padding(bottom = 6.dp)) {
                        TxCard(s, tx)
                    }
                }
                // D-23 locally appended rows (from TxHistoryDao.getPage / getHistoryPaged).
                items(extraTxHistory, key = { "ex_${it.txid}" }) { tx ->
                    Box(modifier = Modifier.padding(bottom = 6.dp)) {
                        TxCard(s, tx)
                    }
                }
                if (!txHistoryLoading &&
                    (txHistoryLoadedCount < txHistoryTotal ||
                        (txHistory.isNotEmpty() && extraTxHistory.size < 200))) {
                    item(key = "load_more_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
                    item(key = "load_more") {
                        // D-23 Load more: primary = parent VM callback (enriches via network);
                        // fallback = local paged read from TxHistoryDao, then RavencoinPublicNode.getHistoryPaged
                        // for shells when the DB is exhausted.
                        Button(
                            onClick = {
                                onLoadMoreTransactions()
                                scope.launch {
                                    val offset = txHistory.size + extraTxHistory.size
                                    val local: List<TxHistoryDao.TxHistoryRow> = try {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            TxHistoryDao.getPage(offset = offset, limit = 20)
                                        }
                                    } catch (_: Exception) { emptyList() }
                                    val localMapped = local.map { row ->
                                        TxHistoryEntry(
                                            txid = row.txid,
                                            height = row.height,
                                            confirmations = row.confirms,
                                            amountSat = row.amountSat,
                                            sentSat = row.sentSat,
                                            isIncoming = row.isIncoming,
                                            isSelfTransfer = row.isSelf,
                                            timestamp = row.timestamp,
                                            cycledSat = row.cycledSat,
                                            feeSat = row.feeSat
                                        )
                                    }
                                    if (localMapped.isNotEmpty()) {
                                        val existing = (txHistory + extraTxHistory).map { it.txid }.toHashSet()
                                        extraTxHistory = extraTxHistory + localMapped.filter { it.txid !in existing }
                                    } else {
                                        val addr = walletInfo?.address
                                        if (addr != null) {
                                            val network = try {
                                                io.raventag.app.wallet.RavencoinPublicNode(context)
                                                    .getHistoryPaged(address = addr, offset = offset, limit = 20)
                                            } catch (_: Exception) { emptyList() }
                                            if (network.isNotEmpty()) {
                                                val existing = (txHistory + extraTxHistory).map { it.txid }.toHashSet()
                                                extraTxHistory = extraTxHistory + network.filter { it.txid !in existing }
                                            }
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RavenOrange),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Icon(Icons.Default.MoreHoriz, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(s.txHistoryLoadMore, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            item(key = "wallet_loading") {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RavenOrange) }
            }
        }
    }
    // D-07, D-12: snackbar overlay for incoming tx + offline-all-nodes messages.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
    )
    }
}

@Composable
private fun AssetCard(s: AppStrings, asset: OwnedAsset, onPreview: (() -> Unit)? = null, onTransfer: (() -> Unit)? = null) {
    val previewUrls = assetPreviewCandidates(asset)
    val typeColor = when (asset.type) { AssetType.ROOT -> RavenOrange; AssetType.SUB -> Color(0xFF60A5FA); AssetType.UNIQUE -> AuthenticGreen }
    Card(colors = CardDefaults.cardColors(containerColor = RavenCard), border = BorderStroke(1.dp, typeColor.copy(alpha = 0.2f)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(typeColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                if (previewUrls.isNotEmpty()) {
                    IpfsPreviewImage(urls = previewUrls, contentDescription = asset.name, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).then(if (onPreview != null) Modifier.clickable { onPreview() } else Modifier), fallback = { Icon(Icons.Default.Token, contentDescription = null, tint = typeColor, modifier = Modifier.size(18.dp)) }, loading = { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = typeColor, modifier = Modifier.size(14.dp), strokeWidth = 2.dp) } })
                } else {
                    Icon(imageVector = when (asset.type) { AssetType.ROOT -> Icons.Default.AccountBalance; AssetType.SUB -> Icons.Default.AccountTree; AssetType.UNIQUE -> Icons.Default.Token }, contentDescription = null, tint = typeColor, modifier = Modifier.size(18.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(asset.name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = if (asset.name.length > 25) 11.sp else 12.sp), fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
                Text(if (asset.name.endsWith("!")) "Owner token" else (asset.description ?: ""), style = MaterialTheme.typography.labelSmall, color = if (asset.name.endsWith("!")) RavenMuted else typeColor, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp)
            }
            Text(
                java.math.BigDecimal.valueOf(asset.balance).stripTrailingZeros().toPlainString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = typeColor,
                textAlign = TextAlign.Center
            )
            if (onTransfer != null) { AssetActionChip(label = "Tx", icon = Icons.Default.Send, color = typeColor, onClick = onTransfer) }
            else if (asset.ipfsHash != null || asset.imageUrl != null) { AssetActionChip(label = "Open", icon = Icons.Default.Image, color = Color.White, onClick = { onPreview?.invoke() }, enabled = onPreview != null) }
        }
    }
}

@Composable
internal fun IpfsPreviewImage(
    urls: List<String>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    loading: @Composable () -> Unit,
    fallback: @Composable () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { NetworkModule.getImageLoader(context) }
    // Track which URL index we are currently trying
    var urlIndex by remember(urls) { mutableStateOf(0) }
    var resolvedUrl by remember(urls) { mutableStateOf<String?>(urls.firstOrNull()) }
    var resolveFailed by remember(urls) { mutableStateOf(false) }

    if (resolvedUrl != null && !resolveFailed) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(resolvedUrl)
                .diskCacheKey(resolvedUrl)
                .memoryCacheKey(resolvedUrl)
                .error(android.R.color.transparent) // Prevent Coil from caching error state
                .build(),
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            loading = { loading() },
            error = {
                LaunchedEffect(resolvedUrl) {
                    // Step 1: try the next gateway URL in the list
                    val nextIndex = urlIndex + 1
                    if (nextIndex < urls.size) {
                        urlIndex = nextIndex
                        resolvedUrl = urls[nextIndex]
                        return@LaunchedEffect
                    }

                    // Step 2: all direct URLs failed, try JSON metadata parsing on each gateway.
                    // The JSON may contain an image field that is either:
                    //   - a full HTTP URL
                    //   - a bare CID (which needs to be resolved against all gateways)
                    val result = withContext(Dispatchers.IO) {
                        val client = NetworkModule.getHttpClient(context)
                        urls.firstNotNullOfOrNull { url ->
                            try {
                                val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
                                client.newCall(req).execute().use { resp ->
                                    if (!resp.isSuccessful) return@use null
                                    val body = resp.body?.string() ?: ""
                                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                                    val img = listOf("image", "image_url", "icon", "logo")
                                        .firstNotNullOfOrNull { k -> json[k]?.takeIf { !it.isJsonNull }?.asString }
                                    img?.let { rawImg ->
                                        when {
                                            rawImg.startsWith("http") -> rawImg
                                            else -> {
                                                // rawImg is a bare CID, ipfs://..., or /ipfs/...
                                                // Resolve it against ALL gateways and try each one
                                                val candidates = IpfsResolver.candidateUrls(rawImg)
                                                candidates.firstNotNullOfOrNull { candidateUrl ->
                                                    try {
                                                        val imgReq = Request.Builder().url(candidateUrl).get().build()
                                                        client.newCall(imgReq).execute().use { imgResp ->
                                                            if (imgResp.isSuccessful) candidateUrl else null
                                                        }
                                                    } catch (_: Exception) { null }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) { null }
                        }
                    }
                    if (result != null && result != resolvedUrl) {
                        urlIndex = 0
                        resolvedUrl = result
                    } else {
                        resolveFailed = true
                    }
                }
                loading()
            }
        )
    } else { fallback() }
}

@Composable
private fun AssetActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, border = BorderStroke(1.dp, color.copy(alpha = 0.35f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = color), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(26.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(11.dp))
        Spacer(modifier = Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AssetPreviewDialog(asset: OwnedAsset, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val previewUrls = assetPreviewCandidates(asset)
    AlertDialog(onDismissRequest = onDismiss, containerColor = RavenCard, title = { Text(asset.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { if (previewUrls.isNotEmpty()) { IpfsPreviewImage(urls = previewUrls, contentDescription = asset.name, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).clickable { previewUrls.firstOrNull()?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }, contentScale = ContentScale.Crop, fallback = { Box(modifier = Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF111111)), contentAlignment = Alignment.Center) { Text("Preview unavailable", color = RavenMuted, style = MaterialTheme.typography.bodySmall) } }, loading = { Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RavenOrange) } }) } ; asset.description?.let { Text(it, color = RavenMuted, style = MaterialTheme.typography.bodySmall) } ; asset.ipfsHash?.let { Text("IPFS: $it", color = RavenMuted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } } }, confirmButton = { Button(onClick = { previewUrls.firstOrNull()?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }, enabled = previewUrls.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = RavenOrange)) { Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp)) ; Spacer(modifier = Modifier.width(6.dp)) ; Text("Open") } }, dismissButton = { OutlinedButton(onClick = onDismiss, border = BorderStroke(1.dp, RavenBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Close") } })
}

@Composable
private fun BalanceCard(s: AppStrings, info: WalletInfo, rvnPrice: Double? = null, onCopyAddress: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = RavenCard), border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.3f)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp)) ; Text(s.walletBalance, fontWeight = FontWeight.SemiBold, color = Color.White) }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (info.isLoading) {
                    AnnotatedString(s.walletLoading)
                } else {
                    val full = String.format(java.util.Locale.US, "%.8f", info.balanceRvn)
                    val dotIdx = full.indexOf('.')
                    buildAnnotatedString {
                        append(full.substring(0, dotIdx))
                        withStyle(SpanStyle(fontSize = 18.sp)) {
                            append(",${full.substring(dotIdx + 1)} RVN")
                        }
                    }
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = RavenOrange,
                fontSize = 28.sp
            )
            if (!info.isLoading && rvnPrice != null) {
                Spacer(modifier = Modifier.height(4.dp))
                if (info.balanceRvn > 0) { Text(text = "\u2248 ${"$%.2f".format(info.balanceRvn * rvnPrice)} USD", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AuthenticGreen) }
                Text(text = "1 RVN = ${"$%.4f".format(rvnPrice)}", style = MaterialTheme.typography.bodySmall, color = RavenMuted)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(s.walletReceiveAddr, style = MaterialTheme.typography.labelSmall, color = RavenMuted, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(info.address, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) ; IconButton(onClick = onCopyAddress, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = RavenMuted, modifier = Modifier.size(16.dp)) } }
        }
    }
}

@Composable
private fun TxCard(s: AppStrings, tx: TxHistoryEntry) {
    val isSelf     = tx.isSelfTransfer
    val isIncoming = tx.isIncoming && !isSelf
    // D-08 dot color: red 0 conf, amber 1..5, green >=6.
    val dotColor   = when {
        tx.confirmations == 0 -> NotAuthenticRed
        tx.confirmations in 1..5 -> Color(0xFFF59E0B)
        else -> AuthenticGreen
    }
    val confLabel  = when {
        tx.confirmations == 0 -> s.walletTxUnconfirmed
        tx.confirmations < 6  -> "${tx.confirmations} ${s.walletTxConfs}"
        else -> s.walletTxConfirmed
    }
    val amtColor   = when { isSelf -> RavenOrange; isIncoming -> AuthenticGreen; else -> NotAuthenticRed }
    val iconVec    = when { isSelf -> Icons.Default.Autorenew; isIncoming -> Icons.Default.CallReceived; else -> Icons.Default.CallMade }

    // D-19 three-value amounts (outgoing only). Cycled/fee fall back to 0 for unenriched shells.
    val sentSat   = tx.sentSat
    val cycledSat = tx.cycledSat
    val feeSat    = tx.feeSat

    // Pre-existing incoming/self "big amount" composite with 10sp decimals.
    val amountRvn = when {
        isSelf     -> (if (cycledSat > 0L) cycledSat else tx.amountSat) / 1e8
        isIncoming -> tx.amountSat / 1e8
        else       -> sentSat / 1e8
    }
    val sign = if (isIncoming) "+" else ""
    val full   = String.format(java.util.Locale.US, "%.8f", amountRvn)
    val dotIdx = full.indexOf('.')
    val intPart = full.substring(0, dotIdx)
    val decPart = full.substring(dotIdx + 1).trimEnd('0')
    val bigAmountAnnotated = buildAnnotatedString {
        append("$sign$intPart")
        if (decPart.isNotEmpty()) {
            withStyle(SpanStyle(fontSize = 10.sp)) { append(",$decPart RVN") }
        } else {
            append(" RVN")
        }
    }

    // Plain 8-decimal RVN rendering for Sent/Cycled/Fee lines.
    fun sat2Rvn(v: Long) = String.format(java.util.Locale.US, "%.8f", v / 1e8).trimEnd('0').trimEnd('.')

    val dateText = if (tx.timestamp > 0) {
        java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date(tx.timestamp * 1000))
    } else { "" }

    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val scale = if (tx.confirmations == 0) {
                rememberInfiniteTransition(label = "").animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = ""
                ).value
            } else 1f
            Box(modifier = Modifier.size(10.dp).scale(scale).background(dotColor, androidx.compose.foundation.shape.CircleShape))
            Icon(imageVector = iconVec, contentDescription = null, tint = amtColor, modifier = Modifier.size(16.dp))
            Text(
                "${tx.txid.take(8)}\u2026${tx.txid.takeLast(6)}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = RavenMuted,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                when {
                    isIncoming -> {
                        // UNCHANGED incoming layout.
                        Text(bigAmountAnnotated, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = amtColor)
                    }
                    isSelf -> {
                        // D-19 self-transfer variant: single line "Cycled X RVN \u00b7 Fee Y RVN".
                        val cycledStr = sat2Rvn(if (cycledSat > 0L) cycledSat else tx.amountSat)
                        val feeStr = sat2Rvn(feeSat)
                        Text(
                            text = "${s.txHistoryCycledPrefix} $cycledStr RVN \u00b7 ${s.txHistoryFeePrefix} $feeStr RVN",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuthenticGreen
                        )
                    }
                    else -> {
                        // D-19 outgoing three-value breakdown.
                        val sentStr = sat2Rvn(sentSat)
                        val cycledStr = sat2Rvn(cycledSat)
                        val feeStr = sat2Rvn(feeSat)
                        Text(
                            text = "${s.txHistorySentPrefix} -$sentStr RVN",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = NotAuthenticRed
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${s.txHistoryCycledPrefix} $cycledStr RVN",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuthenticGreen
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${s.txHistoryFeePrefix} $feeStr RVN",
                            style = MaterialTheme.typography.labelSmall,
                            color = RavenMuted
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (dateText.isNotEmpty()) {
                        Text(dateText, style = MaterialTheme.typography.labelSmall, color = RavenMuted, fontSize = 9.sp)
                    }
                    Text("\u00b7", style = MaterialTheme.typography.labelSmall, color = RavenMuted, fontSize = 9.sp)
                    Text(confLabel, style = MaterialTheme.typography.labelSmall, color = dotColor, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun ElectrumStatusBadge(status: MainViewModel.ElectrumStatus, s: AppStrings) {
    val color = when(status) {
        MainViewModel.ElectrumStatus.ONLINE -> AuthenticGreen
        MainViewModel.ElectrumStatus.CHECKING -> RavenOrange
        else -> NotAuthenticRed
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val pulse = status == MainViewModel.ElectrumStatus.ONLINE || status == MainViewModel.ElectrumStatus.CHECKING
        val scale = if (pulse) {
            val inf = rememberInfiniteTransition(label = "pulse")
            inf.animateFloat(
                initialValue = 0.8f, targetValue = 1.2f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "dot"
            ).value
        } else 1f
        Box(modifier = Modifier.size(6.dp).scale(scale).background(color, androidx.compose.foundation.shape.CircleShape))
        Text(text = when(status) {
            MainViewModel.ElectrumStatus.ONLINE -> s.electrumOnline
            MainViewModel.ElectrumStatus.CHECKING -> s.electrumChecking
            else -> s.electrumOffline
        }, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.8f))
    }
}

@Composable
private fun BlockHeightBadge(height: Int) {
    var prevHeight by remember { mutableStateOf<Int?>(null) }
    var newBlock by remember { mutableStateOf(false) }

    val blockColor by animateColorAsState(
        targetValue = if (newBlock) AuthenticGreen else RavenMuted.copy(alpha = 0.6f),
        animationSpec = if (newBlock) tween(150) else tween(2000),
        label = "block_flash"
    )

    LaunchedEffect(height) {
        if (prevHeight != null && height > prevHeight!!) {
            newBlock = true
            kotlinx.coroutines.delay(700)
            newBlock = false
        }
        prevHeight = height
    }

    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Layers,
            contentDescription = null,
            tint = blockColor,
            modifier = Modifier.size(10.dp)
        )
        Text(
            "Block #%,d".format(height),
            style = MaterialTheme.typography.labelSmall,
            color = blockColor
        )
    }
}

@Composable
private fun HashrateRow(hashrate: Double) {
    // Convert H/s to TH/s
    val thash = hashrate / 1_000_000_000_000.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            Icons.Default.Speed,
            contentDescription = null,
            tint = RavenMuted.copy(alpha = 0.6f),
            modifier = Modifier.size(10.dp)
        )
        Text(
            "%.2f TH/s".format(thash),
            style = MaterialTheme.typography.labelSmall,
            color = RavenMuted.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun WalletSetupCard(
    strings: AppStrings,
    showRestore: Boolean,
    restoreWords: List<String>,
    isGenerating: Boolean,
    isBrandApp: Boolean,
    controlKey: String,
    controlKeyValidating: Boolean,
    controlKeyError: String?,
    restoreError: String? = null,
    onControlKeyChange: (String) -> Unit,
    onWordChange: (Int, String) -> Unit,
    onGenerate: () -> Unit,
    onToggleRestore: () -> Unit,
    onRestore: () -> Unit
) {
    val controlKeyValid = controlKey.isNotBlank()
    Card(colors = CardDefaults.cardColors(containerColor = RavenCard), border = BorderStroke(1.dp, RavenBorder), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(52.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(strings.walletNoWallet, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(strings.walletNoWalletDesc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(modifier = Modifier.height(24.dp))
            if (isBrandApp) {
                OutlinedTextField(
                    value = controlKey,
                    onValueChange = onControlKeyChange,
                    label = { Text(strings.walletControlKey, style = MaterialTheme.typography.bodySmall) },
                    placeholder = { Text(strings.walletControlKeyHint, style = MaterialTheme.typography.bodySmall, color = RavenMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = controlKeyError != null,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RavenOrange,
                        unfocusedBorderColor = RavenBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = RavenOrange,
                        errorBorderColor = NotAuthenticRed
                    ),
                    shape = RoundedCornerShape(8.dp),
                    trailingIcon = if (controlKeyValidating) {
                        { CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp) }
                    } else null
                )
                if (controlKeyError != null) {
                    Text(controlKeyError, color = NotAuthenticRed, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else {
                    Text(
                        strings.walletControlKeyDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = RavenMuted,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (isGenerating || controlKeyValidating) {
                CircularProgressIndicator(color = RavenOrange)
                if (isGenerating && !controlKeyValidating) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        strings.walletScanningBlockchain,
                        style = MaterialTheme.typography.bodySmall,
                        color = RavenMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Button(
                    onClick = if (showRestore) onRestore else onGenerate,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showRestore) AuthenticGreen else RavenOrange
                    ),
                    enabled = if (showRestore)
                        restoreWords.all { it.isNotBlank() } && (!isBrandApp || controlKeyValid)
                    else
                        (!isBrandApp || controlKeyValid)
                ) { Text(if (showRestore) strings.walletRestoreBtn else strings.walletGenerate, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onToggleRestore,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    border = BorderStroke(1.dp, if (showRestore) RavenOrange.copy(alpha = 0.6f) else RavenBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (showRestore) RavenOrange else Color.White)
                ) { Text(if (showRestore) strings.walletCancelBtn else strings.walletRestore) }
                if (showRestore) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(14.dp))
                        Text(strings.walletRestore, style = MaterialTheme.typography.bodySmall, color = RavenOrange, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    MnemonicInputGrid(strings = strings, words = restoreWords, onWordChange = onWordChange)
                }
            }
            if (restoreError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    restoreError,
                    color = NotAuthenticRed,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MnemonicInputGrid(
    strings: AppStrings,
    words: List<String>,
    onWordChange: (Int, String) -> Unit
) {
    val focusRequesters = remember { List(12) { FocusRequester() } }
    var spaceErrorIdx by remember { mutableStateOf(-1) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 4 rows of 3 columns = 12 words
        for (row in 0 until 4) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (col in 0 until 3) {
                    val idx = row * 3 + col
                    val hasSpaceError = spaceErrorIdx == idx
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${idx + 1}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = RavenMuted,
                            modifier = Modifier.padding(start = 2.dp, bottom = 1.dp),
                            fontSize = 9.sp
                        )
                        OutlinedTextField(
                            value = words[idx],
                            onValueChange = { newValue ->
                                if (newValue.contains(' ')) {
                                    val parts = newValue.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                                    if (parts.size >= 2) {
                                        parts.take(12 - idx).forEachIndexed { i, word ->
                                            onWordChange(idx + i, word)
                                        }
                                        spaceErrorIdx = -1
                                    } else {
                                        onWordChange(idx, newValue.replace(" ", ""))
                                        spaceErrorIdx = idx
                                    }
                                } else {
                                    onWordChange(idx, newValue)
                                    if (spaceErrorIdx == idx) spaceErrorIdx = -1
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequesters[idx]),
                            singleLine = true,
                            isError = hasSpaceError,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (hasSpaceError) MaterialTheme.colorScheme.error else RavenOrange,
                                unfocusedBorderColor = if (hasSpaceError) MaterialTheme.colorScheme.error else RavenBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = RavenOrange,
                                errorBorderColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(8.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                capitalization = KeyboardCapitalization.None,
                                autoCorrect = false,
                                imeAction = if (idx < 11) ImeAction.Next else ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = {
                                    if (idx < 11) focusRequesters[idx + 1].requestFocus()
                                },
                                onDone = {}
                            )
                        )
                    }
                }
            }
        }
        if (spaceErrorIdx != -1) {
            Text(
                text = strings.mnemonicSpaceError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MnemonicCard(s: AppStrings, mnemonic: String, visible: Boolean, onToggle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1200)), border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.4f)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.Key, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(16.dp)) ; Text(s.walletRecoveryPhrase, fontWeight = FontWeight.SemiBold, color = RavenOrange, style = MaterialTheme.typography.bodyMedium) } ; IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp)) } }
            if (visible) { Spacer(modifier = Modifier.height(12.dp)) ; Card(colors = CardDefaults.cardColors(containerColor = RavenBg), shape = RoundedCornerShape(8.dp)) { val words = mnemonic.split(" ") ; Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { words.chunked(3).forEachIndexed { row, chunk -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { chunk.forEachIndexed { col, word -> val n = row * 3 + col + 1 ; Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { Text("$n.", style = MaterialTheme.typography.labelSmall, color = RavenMuted, modifier = Modifier.width(20.dp)) ; Text(word, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Color.White) } } } } } } ; Spacer(modifier = Modifier.height(8.dp)) ; Text(s.walletNeverShare, style = MaterialTheme.typography.labelSmall, color = RavenOrange, textAlign = TextAlign.Center) } else { Spacer(modifier = Modifier.height(8.dp)) ; Text(s.walletTapReveal, style = MaterialTheme.typography.bodySmall, color = RavenMuted) }
        }
    }
}

/**
 * D-14: destructive-confirm dialog shown when the user initiates a restore-over-wallet
 * with funds or assets in the current wallet.
 *
 * Two variants:
 *  - `hasBackedUp == true`  : body describes the replacement, primary button "Replace wallet"
 *                             (NotAuthenticRed), Cancel outlined.
 *  - `hasBackedUp == false` : body tells the user to back up first, primary button
 *                             "Back up phrase first" (RavenOrange) routes to MnemonicBackupScreen,
 *                             Cancel still available per UI-SPEC.
 */
// ============================================================
// Phase 30 plan 30-08 composables (D-04, D-12, D-18, D-24, D-28)
// ============================================================

private fun formatHhMm(ms: Long): String {
    if (ms <= 0L) return "--:--"
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}

/** D-04: cached-state banner shown while awaiting a successful refresh. */
@Composable
private fun CachedStateBanner(
    lastRefreshedAt: Long,
    isReconnecting: Boolean,
    visible: Boolean
) {
    if (!visible || lastRefreshedAt <= 0L) return
    val strings = LocalStrings.current
    val label = if (isReconnecting) {
        String.format(strings.cachedStateReconnecting, formatHhMm(lastRefreshedAt))
    } else {
        String.format(strings.cachedStateBanner, formatHhMm(lastRefreshedAt))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = RavenMuted,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = RavenMuted
            )
        }
    }
}

/** D-24: pending mempool-incoming line displayed under the balance. */
@Composable
private fun PendingBalanceLine(mempoolIncomingSat: Long) {
    if (mempoolIncomingSat <= 0L) return
    val strings = LocalStrings.current
    val amber = Color(0xFFF59E0B)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = "Pending",
            tint = RavenMuted,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = strings.pendingBalanceLabel,
            style = MaterialTheme.typography.bodySmall,
            color = RavenMuted
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = String.format(java.util.Locale.US, "+%.8f RVN", mempoolIncomingSat / 1e8),
            style = MaterialTheme.typography.bodySmall,
            color = amber
        )
    }
}

/** D-28: battery-saver informational chip. */
@Composable
private fun BatterySaverChip() {
    val strings = LocalStrings.current
    val amber = Color(0xFFF59E0B)
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(top = 4.dp)
            .semantics { contentDescription = strings.batterySaverChipDesc }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.BatterySaver,
                contentDescription = null,
                tint = amber,
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = strings.batterySaverChip,
                style = MaterialTheme.typography.labelSmall,
                color = amber
            )
        }
    }
}

/** D-12: pill (GREEN/YELLOW/RED) driven by NodeHealthMonitor.stateFlow, tap opens sheet. */
@Composable
private fun ConnectionHealthPill(
    health: ConnectionHealth,
    onTap: () -> Unit
) {
    val strings = LocalStrings.current
    val (color, label, pulse) = when (health) {
        ConnectionHealth.GREEN -> Triple(AuthenticGreen, strings.connectionPillOnline, true)
        ConnectionHealth.YELLOW -> Triple(Color(0xFFF59E0B), strings.connectionPillReconnecting, true)
        ConnectionHealth.RED -> Triple(NotAuthenticRed, strings.connectionPillOffline, false)
    }
    val scale = if (pulse) {
        val inf = rememberInfiniteTransition(label = "pill_pulse")
        inf.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "pill_dot"
        ).value
    } else 1f
    Row(
        modifier = Modifier
            .sizeIn(minHeight = 48.dp)
            .clickable { onTap() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .scale(scale)
                .background(color, androidx.compose.foundation.shape.CircleShape)
                .semantics { contentDescription = "${strings.connectionStatusDotDesc}: $label" }
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f)
        )
    }
}

/** D-12: tap sheet listing current node + fallback nodes with quarantine status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPillSheet(onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val currentNode = NodeHealthMonitor.currentNode() ?: strings.connectionPillNoNode
    val diagnostics = NodeHealthMonitor.diagnostics()
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RavenCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = strings.connectionPillSheetTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(strings.connectionPillCurrentNode, style = MaterialTheme.typography.labelSmall, color = RavenMuted)
                Text(
                    text = currentNode,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color.White
                )
            }
            val lastSuccess = diagnostics.firstOrNull { it.host == currentNode }?.lastSuccessAt
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(strings.connectionPillLastSuccess, style = MaterialTheme.typography.labelSmall, color = RavenMuted)
                Text(
                    text = if (lastSuccess != null) formatHhMm(lastSuccess) else strings.connectionPillNoNode,
                    style = MaterialTheme.typography.bodySmall,
                    color = RavenMuted
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(strings.connectionPillFallbackNodes, style = MaterialTheme.typography.labelSmall, color = RavenMuted)
                diagnostics.forEach { diag ->
                    val quarantined = diag.quarantinedUntil != null && diag.quarantinedUntil!! > System.currentTimeMillis()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (quarantined) NotAuthenticRed else AuthenticGreen,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Text(
                            text = diag.host,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        if (quarantined) {
                            Text(
                                text = String.format(
                                    strings.connectionPillQuarantined,
                                    formatHhMm(diag.quarantinedUntil!!)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = NotAuthenticRed
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, RavenBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.align(Alignment.End)
            ) { Text(strings.connectionPillClose) }
        }
    }
}

@Composable
private fun RestoreWalletConfirmDialog(
    hasBackedUp: Boolean,
    rvnAmount: Double,
    assetsCount: Int,
    onDismiss: () -> Unit,
    onBackupFirst: () -> Unit,
    onReplace: () -> Unit
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A0000),
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = strings.restoreReplaceWalletTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            val body = if (hasBackedUp) {
                String.format(
                    strings.restoreReplaceWalletBody,
                    String.format("%.8f", rvnAmount),
                    assetsCount.toString()
                )
            } else {
                strings.restoreBackupFirstBody
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = RavenMuted
            )
        },
        confirmButton = {
            if (hasBackedUp) {
                Button(
                    onClick = onReplace,
                    colors = ButtonDefaults.buttonColors(containerColor = NotAuthenticRed)
                ) { Text(strings.restoreReplaceCta, fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = onBackupFirst,
                    colors = ButtonDefaults.buttonColors(containerColor = RavenOrange)
                ) { Text(strings.restoreBackupFirstCta, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, RavenBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text(strings.cancel) }
        }
    )
}
