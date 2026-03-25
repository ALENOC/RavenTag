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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import okhttp3.Request
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.raventag.app.network.NetworkModule

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
    ownedAssets: List<OwnedAsset>?,
    assetsLoading: Boolean,
    assetsLoadError: Boolean = false,
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
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var pendingTransferAsset by remember { mutableStateOf<OwnedAsset?>(null) }
    var showMnemonic by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var restoreWords by remember { mutableStateOf(List(12) { "" }) }
    var controlKey by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var assetFilter by remember { mutableStateOf<AssetType?>(null) } // null = All
    var previewAsset by remember { mutableStateOf<OwnedAsset?>(null) }
    var showOwnerTokens by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val isOperator = walletRole == "operator"

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RavenBg)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Header
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
                    // ElectrumX status badge
                    ElectrumStatusBadge(electrumStatus, s)

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

        Spacer(modifier = Modifier.height(24.dp))

        if (!hasWallet) {
            WalletSetupCard(
                strings = s,
                showRestore = showRestore,
                restoreWords = restoreWords,
                isGenerating = isGenerating,
                isBrandApp = isBrandApp,
                controlKey = controlKey,
                controlKeyValidating = controlKeyValidating,
                controlKeyError = controlKeyError,
                onControlKeyChange = { controlKey = it },
                onWordChange = { idx, word ->
                    restoreWords = restoreWords.toMutableList().also { it[idx] = word }
                },
                onGenerate = { showRestore = false; restoreWords = List(12) { "" }; onRestoreModeChange(false); onGenerateWallet(controlKey) },
                onToggleRestore = { val next = !showRestore; showRestore = next; restoreWords = List(12) { "" }; onRestoreModeChange(next) },
                onRestore = { onRestoreWallet(restoreWords.joinToString(" "), controlKey) }
            )
        } else if (walletInfo != null) {
            BalanceCard(s, walletInfo, rvnPrice = rvnPrice, onCopyAddress = { clipboard.setText(AnnotatedString(walletInfo.address)) })
            Spacer(modifier = Modifier.height(16.dp))
            walletInfo.mnemonic?.let { mnemonic ->
                MnemonicCard(s, mnemonic, visible = showMnemonic, onToggle = { showMnemonic = !showMnemonic })
                Spacer(modifier = Modifier.height(16.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onReceive, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = RavenCard), border = BorderStroke(1.dp, AuthenticGreen.copy(alpha = 0.4f)), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.CallReceived, contentDescription = null, tint = AuthenticGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(s.walletReceiveBtn, color = AuthenticGreen, fontWeight = FontWeight.SemiBold)
                }
                Button(onClick = { if (!isOperator) onSend() }, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = RavenCard), border = BorderStroke(1.dp, (if (isOperator) RavenMuted else NotAuthenticRed).copy(alpha = 0.4f)), shape = RoundedCornerShape(12.dp)) {
                    Icon(if (isOperator) Icons.Default.Lock else Icons.Default.Send, contentDescription = null, tint = if (isOperator) RavenMuted else NotAuthenticRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(s.walletSendBtn, color = if (isOperator) RavenMuted else NotAuthenticRed, fontWeight = FontWeight.SemiBold)
                }
            }
            walletInfo.error?.let { err ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = NotAuthenticRedBg), border = BorderStroke(1.dp, NotAuthenticRed.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = NotAuthenticRed, modifier = Modifier.size(18.dp))
                        Text(err, style = MaterialTheme.typography.bodySmall, color = NotAuthenticRed)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (!isBrandApp && walletBalance < 0.01 && hasWallet && !assetsLoading && !ownedAssets.isNullOrEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1A00)), border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.5f)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(16.dp))
                        Text(s.assetsLowRvnWarning, style = MaterialTheme.typography.bodySmall, color = RavenOrange.copy(alpha = 0.9f))
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.walletMyAssets, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = RavenMuted)
                if (assetsLoading) CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to s.walletFilterAll, AssetType.ROOT to s.walletAssetRoot, AssetType.SUB to s.walletAssetSub, AssetType.UNIQUE to s.walletAssetUnique).forEach { (type, label) ->
                    val selected = assetFilter == type
                    val typeColor = when(type) { AssetType.ROOT -> RavenOrange; AssetType.SUB -> Color(0xFF60A5FA); AssetType.UNIQUE -> AuthenticGreen; else -> RavenMuted }
                    FilterChip(selected = selected, onClick = { assetFilter = type }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = typeColor.copy(alpha = 0.15f), selectedLabelColor = typeColor, containerColor = RavenCard, labelColor = RavenMuted), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, selectedBorderColor = typeColor.copy(alpha = 0.4f), borderColor = RavenBorder))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (!assetsLoading && assetsLoadError) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0D00)), border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp))
                        Text(s.walletAssetsNotVerifiable, style = MaterialTheme.typography.bodySmall, color = RavenOrange)
                    }
                }
            } else {
                val filteredAssets = ownedAssets.orEmpty().filter { asset ->
                    val typeMatch = assetFilter == null || asset.type == assetFilter
                    val ownerTokenMatch = showOwnerTokens || !asset.name.endsWith("!")
                    typeMatch && ownerTokenMatch
                }
                if (!assetsLoading && filteredAssets.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = RavenCard), border = BorderStroke(1.dp, RavenBorder), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.padding(20.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(s.walletNoAssets, style = MaterialTheme.typography.bodySmall, color = RavenMuted, textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        filteredAssets.forEach { asset ->
                            key(asset.name) {
                                // Operators can only transfer UNIQUE tokens; ROOT/SUB transfers are admin-only.
                                val canTransferThis = onTransferAsset != null && (!isOperator || asset.type == AssetType.UNIQUE)
                                AssetCard(s = s, asset = asset, onPreview = if (asset.imageUrl != null || asset.ipfsHash != null) ({ previewAsset = asset }) else null, onTransfer = if (canTransferThis) { { if (asset.type != AssetType.UNIQUE) { pendingTransferAsset = asset } else { onTransferAsset!!.invoke(asset) } } } else null)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(text = s.walletShowOwnerTokens, style = MaterialTheme.typography.labelSmall, color = RavenMuted, modifier = Modifier.padding(end = 12.dp))
                Switch(checked = showOwnerTokens, onCheckedChange = { showOwnerTokens = it }, colors = SwitchDefaults.colors(checkedThumbColor = RavenOrange, checkedTrackColor = RavenOrange.copy(alpha = 0.3f), uncheckedThumbColor = RavenMuted, uncheckedTrackColor = RavenMuted.copy(alpha = 0.3f)), modifier = Modifier.size(width = 40.dp, height = 24.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.walletTxHistory, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = RavenMuted)
                if (txHistoryLoading) CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (!txHistoryLoading && txHistory.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = RavenCard), border = BorderStroke(1.dp, RavenBorder), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(20.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(s.walletNoTxHistory, style = MaterialTheme.typography.bodySmall, color = RavenMuted, textAlign = TextAlign.Center)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    txHistory.forEach { tx -> TxCard(s, tx) }
                }
                // Show "Load More" button if there are more transactions to load
                if (!txHistoryLoading && txHistoryLoadedCount < txHistoryTotal) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onLoadMoreTransactions,
                        colors = ButtonDefaults.buttonColors(containerColor = RavenCard),
                        border = BorderStroke(1.dp, RavenBorder),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s.walletLoadMore, color = RavenOrange, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RavenOrange) }
        }
        Spacer(modifier = Modifier.height(24.dp))
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
private fun IpfsPreviewImage(
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

                    // Step 2: all direct URLs failed, try JSON metadata parsing on each gateway
                    val result = withContext(Dispatchers.IO) {
                        urls.firstNotNullOfOrNull { url ->
                            try {
                                val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
                                NetworkModule.getHttpClient(context).newCall(req).execute().use { resp ->
                                    if (!resp.isSuccessful) return@use null
                                    val body = resp.body?.string() ?: ""
                                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                                    val img = listOf("image", "image_url", "icon", "logo")
                                        .firstNotNullOfOrNull { k -> json[k]?.takeIf { !it.isJsonNull }?.asString }
                                    img?.let { if (it.startsWith("http")) it else IpfsResolver.primaryUrl(it) }
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
    val isIncoming = tx.isIncoming
    val dotColor = when { tx.confirmations == 0 -> NotAuthenticRed; tx.confirmations < 6 -> Color(0xFFF59E0B); else -> AuthenticGreen }
    val confLabel = when { tx.confirmations == 0 -> s.walletTxUnconfirmed; tx.confirmations < 6 -> "${tx.confirmations} ${s.walletTxConfs}"; else -> s.walletTxConfirmed }
    val amountRvn = if (isIncoming) tx.amountSat / 1e8 else tx.sentSat / 1e8
    val sign = if (isIncoming) "+" else "-"
    val full = String.format(java.util.Locale.US, "%.8f", amountRvn)
    val dotIdx = full.indexOf('.')
    val intPart = full.substring(0, dotIdx)
    val decPart = full.substring(dotIdx + 1).trimEnd('0')
    val amountAnnotated = buildAnnotatedString {
        append("$sign$intPart")
        if (decPart.isNotEmpty()) {
            withStyle(SpanStyle(fontSize = 10.sp)) {
                append(",$decPart RVN")
            }
        } else {
            append(" RVN")
        }
    }
    val dateText = if (tx.timestamp > 0) { java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()).apply { timeZone = java.util.TimeZone.getDefault() }.format(java.util.Date(tx.timestamp * 1000)) } else { "" }
    Card(colors = CardDefaults.cardColors(containerColor = RavenCard), border = BorderStroke(1.dp, RavenBorder), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val scale = if (tx.confirmations == 0) { rememberInfiniteTransition(label = "").animateFloat(initialValue = 0.8f, targetValue = 1.2f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "").value } else 1f
            Box(modifier = Modifier.size(10.dp).scale(scale).background(dotColor, androidx.compose.foundation.shape.CircleShape))
            Icon(imageVector = if (isIncoming) Icons.Default.CallReceived else Icons.Default.CallMade, contentDescription = null, tint = if (isIncoming) AuthenticGreen else NotAuthenticRed, modifier = Modifier.size(16.dp))
            Text("${tx.txid.take(8)}\u2026${tx.txid.takeLast(6)}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = RavenMuted, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(amountAnnotated, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = if (isIncoming) AuthenticGreen else NotAuthenticRed)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { if (dateText.isNotEmpty()) { Text(dateText, style = MaterialTheme.typography.labelSmall, color = RavenMuted, fontSize = 9.sp) } ; Text("\u2022", style = MaterialTheme.typography.labelSmall, color = RavenMuted, fontSize = 9.sp) ; Text(confLabel, style = MaterialTheme.typography.labelSmall, color = dotColor, fontSize = 9.sp) }
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
            if (isGenerating || controlKeyValidating) { CircularProgressIndicator(color = RavenOrange) } else {
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
