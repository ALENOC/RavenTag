/**
 * MainActivity.kt
 *
 * Single-Activity entry point for the RavenTag Android application.
 *
 * Responsibilities:
 *   - NFC foreground dispatch: captures TAG_DISCOVERED, TECH_DISCOVERED, and
 *     NDEF_DISCOVERED intents while the app is in the foreground.
 *   - SUN verification flow: NFC read -> SUN MAC verify (client-side or backend)
 *     -> optional blockchain/revocation check -> VerifyScreen overlay.
 *   - Chip programming flow: issue Ravencoin asset -> read UID -> derive keys
 *     (backend) -> upload IPFS metadata -> configure NTAG 424 DNA.
 *   - Wallet lifecycle: BIP39 mnemonic generation, confirmation, and restore.
 *   - Biometric authentication gate on launch (when wallet is present).
 *   - Secure storage: admin/operator/master keys stored in EncryptedSharedPreferences
 *     backed by Android Keystore AES256-GCM.
 *   - Bottom navigation with three tabs: Scan (consumer verify), Wallet, Brand
 *     (asset management, brand-APK only).
 *
 * ViewModel: [MainViewModel] holds all state and coroutine-heavy logic so it
 * survives configuration changes (rotation, language change).
 */
package io.raventag.app

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.raventag.app.nfc.NfcCounterCache
import io.raventag.app.nfc.Ntag424Configurator
import io.raventag.app.nfc.NfcReader
import io.raventag.app.nfc.SunVerifier
import io.raventag.app.ravencoin.RpcClient
import io.raventag.app.ui.screens.*
import io.raventag.app.ui.theme.RavenBg
import android.nfc.tech.IsoDep
import io.raventag.app.ui.theme.RavenOrange
import androidx.compose.runtime.CompositionLocalProvider
import io.raventag.app.ravencoin.OwnedAsset
import io.raventag.app.ui.theme.AppStrings
import io.raventag.app.ui.theme.LocalStrings
import io.raventag.app.ui.theme.RavenBorder
import io.raventag.app.ui.theme.RavenCard
import io.raventag.app.ui.theme.RavenMuted
import io.raventag.app.ui.theme.RavenTagTheme
import io.raventag.app.ui.theme.appStringsFor
import io.raventag.app.ui.theme.stringsDe
import io.raventag.app.ui.theme.stringsEs
import io.raventag.app.ui.theme.stringsFr
import io.raventag.app.ui.theme.stringsIt
import io.raventag.app.ui.theme.stringsJa
import io.raventag.app.ui.theme.stringsKo
import io.raventag.app.ui.theme.stringsRu
import io.raventag.app.ui.theme.stringsZh
import io.raventag.app.wallet.AssetIssueParams
import io.raventag.app.wallet.AssetManager
import io.raventag.app.wallet.BurnParams
import io.raventag.app.wallet.SubAssetIssueParams
import io.raventag.app.wallet.WalletManager
import io.raventag.app.config.AppConfig
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.raventag.app.worker.NotificationHelper
import io.raventag.app.worker.WalletPollingWorker
import java.util.concurrent.TimeUnit

// ============================================================
// ViewModel
// ============================================================

/**
 * MainViewModel holds all application state and drives the coroutine-heavy
 * business logic (NFC verification, asset issuance, wallet operations).
 *
 * Extending [AndroidViewModel] gives access to the [Application] context for
 * persistent storage (NfcCounterCache) while surviving configuration changes.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // CRIT-1: client-side NFC counter cache for offline replay detection.
    // Stores the last seen NFC tap counter per nfc_pub_id in local storage.
    // A counter that does not increase is a sign of tag cloning.
    private val nfcCounterCache = NfcCounterCache(application)

    /** AES-128 key used to decrypt the SUN encrypted UID field (sdmmac input key). */
    var sdmmacKey: ByteArray = ByteArray(16)

    /** AES-128 key used to verify the SUN MAC field. */
    var sunMacKey: ByteArray = ByteArray(16)

    /** Optional salt mixed into the nfc_pub_id derivation (SHA-256(uid || salt)). */
    var salt: ByteArray? = null

    /** Expected Ravencoin asset name for the fallback local verification path. */
    var expectedAsset: String? = null

    /** True when the Scan tab is the active bottom-nav destination.
     *  NFC foreground dispatch is only enabled while this is true. */
    var isScanTabActive by mutableStateOf(true)

    /** Current state of the NFC scan animation on the Scan tab. */
    var scanState by mutableStateOf(ScanState.IDLE)

    /** Current step in the multi-step verification flow (null = not verifying). */
    var verifyStep by mutableStateOf<VerifyStep?>(null)

    /** Final result of the last verification attempt (null until flow completes). */
    var verifyResult by mutableStateOf<VerifyResult?>(null)

    /** Human-readable error message for display in the UI (null = no error). */
    var errorMessage by mutableStateOf<String?>(null)

    /** Backend base URL used for revocation checks and tag verification calls. */
    var currentVerifyUrl by mutableStateOf(BuildConfig.API_BASE_URL)

    // ── Wallet state ──────────────────────────────────────────────────────────

    /** Display data for the current wallet (address, balance, loading flag). */
    var walletInfo by mutableStateOf<WalletInfo?>(null)

    /** True once a wallet has been generated or restored and finalized. */
    var hasWallet by mutableStateOf(false)

    /** BIP44 HD wallet manager (key derivation, signing, balance queries). */
    var walletManager: WalletManager? = null

    /** Asset manager for backend API calls (issue, revoke, register chip). */
    var assetManager: AssetManager? = null

    /** True while mnemonic entropy is being generated (prevents double-click). */
    var walletGenerating by mutableStateOf(false)

    // ── Issue / revoke / register / transfer state ────────────────────────────

    /** Currently active issue/revoke/transfer mode (null = no overlay shown). */
    var issueMode by mutableStateOf<IssueMode?>(null)

    /** True while an issue, revoke, or transfer operation is in progress. */
    var issueLoading by mutableStateOf(false)

    /** Human-readable result message shown after an issue/revoke/transfer attempt. */
    var issueResult by mutableStateOf<String?>(null)

    /** True = last operation succeeded, false = failed, null = not yet run. */
    var issueSuccess by mutableStateOf<Boolean?>(null)

    /** nfc_pub_id returned by a chip registration response (shown in the result UI). */
    var registerNfcPubId by mutableStateOf<String?>(null)

    /** Pre-fills the asset name field when opening the Transfer overlay. */
    var prefilledTransferAssetName by mutableStateOf<String?>(null)

    // ── No-funds warning dialog ───────────────────────────────────────────────

    /** True while the "insufficient RVN balance" warning dialog is shown. */
    var showNoFundsWarning by mutableStateOf(false)

    /**
     * Issue mode held in reserve while the no-funds dialog is displayed.
     * Restored to [issueMode] if the user taps "Continue anyway".
     */
    var pendingIssueMode by mutableStateOf<IssueMode?>(null)

    // ── Standalone NFC write (without issuing a new asset) ────────────────────

    /** True when the Program Tag screen (standalone write) is visible. */
    var showProgramTagForm by mutableStateOf(false)

    /** Verify base URL used for the NDEF record URL in standalone write mode. */
    var standaloneWriteBaseUrl = ""

    /** True when the active write flow is standalone (no asset issuance step). */
    var isStandaloneWrite = false

    /** Helper to get localized strings based on current language preference. */
    private fun getStrings(): AppStrings {
        val prefs = getApplication<Application>().getSharedPreferences("raventag_app", Application.MODE_PRIVATE)
        val langCode = prefs.getString("language", "en") ?: "en"
        return appStringsFor(langCode)
    }

    /** Admin or operator key passed to the backend during chip registration. */
    var writeTagAdminKey = ""

    /**
     * Hex-encoded current app master key of the tag (default 16 zero bytes).
     * Set in Settings when re-programming already-configured tags.
     */
    var initialMasterKeyHex = ""

    /** Pinata JWT for direct IPFS pin uploads (alternative to backend upload). */
    var pinataJwt = ""

    /** Kubo node URL for self-hosted IPFS uploads (alternative to backend upload). */
    var kuboNodeUrl = ""

    /** Connectivity and authentication status for the Pinata JWT. */
    var pinataJwtStatus by mutableStateOf(AdminKeyStatus.UNKNOWN)

    /** Connectivity and authentication status for the Kubo node URL. */
    var kuboNodeStatus by mutableStateOf(AdminKeyStatus.UNKNOWN)

    /**
     * Test the Pinata JWT by making a lightweight authenticated API call.
     * Sets [pinataJwtStatus] to VALID or INVALID based on the result.
     * No-ops if a check is already in progress.
     */
    fun checkPinataJwt(jwt: String) {
        if (jwt.isBlank()) { pinataJwtStatus = AdminKeyStatus.UNKNOWN; return }
        if (pinataJwtStatus == AdminKeyStatus.CHECKING) return
        pinataJwtStatus = AdminKeyStatus.CHECKING
        viewModelScope.launch {
            pinataJwtStatus = withContext(Dispatchers.IO) {
                runCatching { io.raventag.app.ipfs.PinataUploader.testAuthentication(jwt) }
                    .getOrDefault(false)
                    .let { if (it) AdminKeyStatus.VALID else AdminKeyStatus.INVALID }
            }
        }
    }

    /**
     * Ping the Kubo node's /api/v0/id endpoint to verify reachability.
     * Sets [kuboNodeStatus] to VALID or INVALID based on the result.
     * No-ops if a check is already in progress.
     */
    fun checkKuboNode(url: String) {
        if (url.isBlank()) { kuboNodeStatus = AdminKeyStatus.UNKNOWN; return }
        if (kuboNodeStatus == AdminKeyStatus.CHECKING) return
        kuboNodeStatus = AdminKeyStatus.CHECKING
        viewModelScope.launch {
            kuboNodeStatus = withContext(Dispatchers.IO) {
                runCatching { io.raventag.app.ipfs.KuboUploader.testNode(url) }
                    .getOrDefault(false)
                    .let { if (it) AdminKeyStatus.VALID else AdminKeyStatus.INVALID }
            }
        }
    }

    /**
     * Start the standalone write flow (program a tag that already has an asset,
     * without issuing a new one on-chain).
     *
     * Builds the NDEF verify URL from [verifyUrl] and [assetName], then transitions
     * to [WriteTagStep.WAIT_TAG] so the user is prompted to tap the tag.
     */
    fun startStandaloneTagWrite(assetName: String, verifyUrl: String, adminKey: String = "") {
        standaloneWriteBaseUrl = "$verifyUrl/verify?asset=${assetName.uppercase().replace("#", "%23")}&"
        isStandaloneWrite = true
        writeTagAdminKey = adminKey
        writeTagAssetName = assetName.uppercase()
        writeTagStep = WriteTagStep.WAIT_TAG
        writeTagKeys = null
        writeTagError = null
        pendingWriteParams = null
        pendingWriteSalt = null
        pendingWriteUid = null
        showProgramTagForm = false
    }

    // ── ElectrumX connectivity ────────────────────────────────────────────────

    /** ElectrumX public node reachability status. */
    enum class ElectrumStatus { UNKNOWN, CHECKING, ONLINE, OFFLINE }
    var electrumStatus by mutableStateOf(ElectrumStatus.UNKNOWN)

    /**
     * Ping the configured ElectrumX node and update [electrumStatus].
     * No-ops if a check is already in progress.
     */
    fun checkElectrumStatus() {
        if (electrumStatus == ElectrumStatus.CHECKING) return
        electrumStatus = ElectrumStatus.CHECKING
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { io.raventag.app.wallet.RavencoinPublicNode().ping() } catch (_: Exception) { false }
            }
            electrumStatus = if (ok) ElectrumStatus.ONLINE else ElectrumStatus.OFFLINE
        }
    }

    // ── Chain tip / price / hashrate ──────────────────────────────────────────

    /** Latest Ravencoin chain-tip block height (updated every ~60s on the Wallet tab). */
    var blockHeight by mutableStateOf<Int?>(null)

    /** Fetch the current block height from the ElectrumX node. Silently ignores errors. */
    fun fetchBlockHeight() {
        viewModelScope.launch {
            val h = withContext(Dispatchers.IO) {
                try { io.raventag.app.wallet.RavencoinPublicNode().getBlockHeight() }
                catch (_: Exception) { null }
            }
            if (h != null) blockHeight = h
        }
    }

    /** Current RVN/USDT price fetched from a public price API (updated every ~60s). */
    var rvnPrice by mutableStateOf<Double?>(null)

    /** Fetch the current RVN price in USD. Silently ignores errors. */
    fun fetchRvnPrice() {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) {
                io.raventag.app.wallet.RvnPriceFetcher.fetch()
            }
            if (p != null) rvnPrice = p
        }
    }

    /** Ravencoin network hashrate in H/s (updated every ~60s on the Wallet tab). */
    var networkHashrate by mutableStateOf<Double?>(null)

    /** Fetch the current Ravencoin network hashrate. Silently ignores errors. */
    fun fetchNetworkHashrate() {
        viewModelScope.launch {
            val h = withContext(Dispatchers.IO) {
                io.raventag.app.wallet.RvnHashrateFetcher.fetch()
            }
            if (h != null) networkHashrate = h
        }
    }

    // ── Backend server connectivity ───────────────────────────────────────────

    /** Verification backend server reachability status. */
    enum class ServerStatus { UNKNOWN, CHECKING, ONLINE, OFFLINE }
    var serverStatus by mutableStateOf(ServerStatus.UNKNOWN)

    /**
     * Check whether the backend server at [url] is reachable by hitting /api/health.
     * Any HTTP response code 100-599 is treated as "online" (server is up).
     * Network errors or connection timeouts set the status to OFFLINE.
     */
    fun checkServerStatus(url: String) {
        if (serverStatus == ServerStatus.CHECKING) return
        serverStatus = ServerStatus.CHECKING
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URL("$url/api/health").openConnection()
                            as java.net.HttpURLConnection
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 100..599
                } catch (_: Exception) { false }
            }
            serverStatus = if (ok) ServerStatus.ONLINE else ServerStatus.OFFLINE
        }
    }

    // ── Admin / operator key validation ──────────────────────────────────────

    /**
     * Possible validation states for an API key.
     * WRONG_TYPE means the key is valid but for a different permission level
     * (e.g. an admin key was tested against the operator endpoint).
     */
    enum class AdminKeyStatus { UNKNOWN, CHECKING, VALID, INVALID, WRONG_TYPE }

    /** Validation status of the saved admin key. */
    var adminKeyStatus by mutableStateOf(AdminKeyStatus.UNKNOWN)

    /**
     * Validate the admin key by probing the admin-only /api/brand/revoked endpoint.
     * If that returns 200, the key is VALID (full admin access).
     * If the operator endpoint /api/brand/chips returns 200 instead, the key is
     * WRONG_TYPE (it is an operator key, not an admin key).
     * Otherwise the key is INVALID.
     */
    fun checkAdminKey(url: String, key: String) {
        if (key.isBlank()) { adminKeyStatus = AdminKeyStatus.INVALID; return }
        if (adminKeyStatus == AdminKeyStatus.CHECKING) return
        adminKeyStatus = AdminKeyStatus.CHECKING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                fun get(endpoint: String): Int = try {
                    val conn = java.net.URL("${url.trimEnd('/')}$endpoint").openConnection()
                            as java.net.HttpURLConnection
                    conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("X-Admin-Key", key)
                    conn.responseCode.also { conn.disconnect() }
                } catch (_: Exception) { -1 }

                val adminCode = get("/api/brand/revoked")
                if (adminCode == 200) return@withContext AdminKeyStatus.VALID
                // If the operator-level endpoint responds, the key is an operator key, not admin
                val operatorCode = get("/api/brand/chips")
                if (operatorCode == 200) return@withContext AdminKeyStatus.WRONG_TYPE
                AdminKeyStatus.INVALID
            }
            adminKeyStatus = result
        }
    }

    // ── Operator key validation ───────────────────────────────────────────────

    /**
     * Operator key: limited access (Program NFC Tag and Transfer operations only).
     * An operator cannot issue/revoke assets or view the revoked-assets list.
     */
    var operatorKeyStatus by mutableStateOf(AdminKeyStatus.UNKNOWN)

    /**
     * Validate the operator key by probing /api/brand/chips (operator endpoint).
     * If the admin-only endpoint also returns 200, the key is WRONG_TYPE (it is
     * an admin key, not an operator key).
     */
    fun checkOperatorKey(url: String, key: String) {
        if (key.isBlank()) { operatorKeyStatus = AdminKeyStatus.UNKNOWN; return }
        if (operatorKeyStatus == AdminKeyStatus.CHECKING) return
        operatorKeyStatus = AdminKeyStatus.CHECKING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                fun get(endpoint: String): Int = try {
                    val conn = java.net.URL("${url.trimEnd('/')}$endpoint").openConnection()
                            as java.net.HttpURLConnection
                    conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("X-Admin-Key", key)
                    conn.responseCode.also { conn.disconnect() }
                } catch (_: Exception) { -1 }

                // If the key passes the admin-only endpoint, it is the admin key, not operator
                val adminCode = get("/api/brand/revoked")
                if (adminCode == 200) return@withContext AdminKeyStatus.WRONG_TYPE
                // Check operator endpoint (accepts operator OR admin key)
                val operatorCode = get("/api/brand/chips")
                if (operatorCode == 200) return@withContext AdminKeyStatus.VALID
                AdminKeyStatus.INVALID
            }
            operatorKeyStatus = result
        }
    }

    // ── Wallet role (control key) ─────────────────────────────────────────────

    /** Wallet access role determined at wallet creation: "admin", "operator", or "". */
    var walletRole by mutableStateOf("")

    /** True while the control key is being validated against the backend. */
    var controlKeyValidating by mutableStateOf(false)

    /** Error message from the last failed control key validation, or null. */
    var controlKeyError by mutableStateOf<String?>(null)

    /**
     * Validate a control key against the backend and return the role ("admin", "operator"),
     * or null if the key is invalid or the server is unreachable.
     */
    suspend fun validateControlKey(verifyUrl: String, key: String): String? {
        if (key.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                fun get(endpoint: String): Int = try {
                    val conn = java.net.URL("${verifyUrl.trimEnd('/')}$endpoint").openConnection()
                            as java.net.HttpURLConnection
                    conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("X-Admin-Key", key)
                    conn.responseCode.also { conn.disconnect() }
                } catch (_: Exception) { -1 }

                val adminCode = get("/api/brand/revoked")
                if (adminCode == 200) return@withContext "admin"
                val operatorCode = get("/api/brand/chips")
                if (operatorCode == 200) return@withContext "operator"
                null
            } catch (_: Exception) { null }
        }
    }

    // ── Transaction history ───────────────────────────────────────────────────

    /** Cached list of recent transactions for the wallet address. */
    var txHistory by mutableStateOf<List<io.raventag.app.wallet.TxHistoryEntry>>(emptyList())

    /** True while transaction history is being fetched from ElectrumX. */
    var txHistoryLoading by mutableStateOf(false)

    /** Total number of transactions available for this address. */
    var txHistoryTotal by mutableStateOf<Int>(0)

    /** Number of transactions currently loaded (for pagination). */
    var txHistoryLoadedCount by mutableStateOf<Int>(0)

    /** Page size for loading more transactions. */
    private val txHistoryPageSize = 15

    // ── Asset portfolio ───────────────────────────────────────────────────────

    /**
     * Owned assets for the wallet address (null = not yet loaded, empty = none found).
     * Items are progressively enriched with IPFS metadata after the initial load.
     */
    var ownedAssets by mutableStateOf<List<OwnedAsset>?>(emptyList())

    /** True while the asset list is being fetched. */
    var assetsLoading by mutableStateOf(false)

    /** True if the last asset-list fetch failed with an error. */
    var assetsLoadError by mutableStateOf(false)

    /**
     * Load the asset portfolio for the wallet address.
     *
     * Two-phase loading for responsive UI:
     *   Phase 1: fetch the basic asset list immediately and display it.
     *   Phase 2: enrich each asset with IPFS metadata in parallel (max 4 concurrent
     *            requests via a semaphore) and update the list progressively.
     */
    fun loadOwnedAssets() {
        val address = walletManager?.getAddress() ?: return
        viewModelScope.launch {
            assetsLoading = true
            assetsLoadError = false
            try {
                // 1. Fetch raw balances first - this is one fast call
                val basic = withContext(Dispatchers.IO) { rpcClient.listAssetsByAddress(address) }

                // Show balances IMMEDIATELY
                ownedAssets = basic
                assetsLoading = false

                // 2. Fetch all metadata (blockchain IPFS hash + IPFS JSON) in parallel
                // Max 3 concurrent IPFS requests to avoid gateway rate limiting
                val semaphore = Semaphore(3)
                basic.forEach { asset ->
                    // Launch a separate coroutine per asset enrichment for maximum reactivity
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            semaphore.withPermit {
                                val enriched = rpcClient.enrichWithIpfsData(asset)
                                // Update UI as EACH item finishes independently
                                withContext(Dispatchers.Main) {
                                    ownedAssets = ownedAssets?.map { 
                                        if (it.name == enriched.name) enriched else it 
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MainViewModel", "Enrichment failed for ${asset.name}", e)
                        }
                    }
                }

                Log.d("MainActivity", "loadOwnedAssets: background enrichment started for ${basic.size} assets")
            } catch (e: Exception) {
                Log.e("MainActivity", "loadOwnedAssets failed", e)
                assetsLoadError = true
                assetsLoading = false
            }
        }
    }
    /**
     * Fetch the transaction history for the wallet address from ElectrumX.
     * Loads the first page of transactions.
     * Errors are silently swallowed because tx history is non-critical.
     */
    fun loadTransactionHistory() {
        val wm = walletManager ?: return
        val address = wm.getAddress() ?: return
        txHistoryLoading = true
        viewModelScope.launch {
            try {
                // Fetch total count first
                val totalCount = withContext(Dispatchers.IO) {
                    io.raventag.app.wallet.RavencoinPublicNode().getTransactionCount(address)
                }
                txHistoryTotal = totalCount

                // Fetch first page
                val history = withContext(Dispatchers.IO) {
                    io.raventag.app.wallet.RavencoinPublicNode().getTransactionHistory(
                        address,
                        limit = txHistoryPageSize,
                        offset = 0
                    )
                }
                txHistory = history
                txHistoryLoadedCount = history.size
            } catch (_: Throwable) {
                // silently ignore: tx history is optional
            } finally {
                txHistoryLoading = false
            }
        }
    }

    /**
     * Load more transactions (next page).
     * Called when user taps "Load More" button.
     */
    fun loadMoreTransactions() {
        val wm = walletManager ?: return
        val address = wm.getAddress() ?: return
        
        // Check if we've loaded all transactions already
        if (txHistoryLoadedCount >= txHistoryTotal) return
        
        viewModelScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    io.raventag.app.wallet.RavencoinPublicNode().getTransactionHistory(
                        address,
                        limit = txHistoryPageSize,
                        offset = txHistoryLoadedCount
                    )
                }
                
                // Append new transactions to existing list
                txHistory = txHistory + history
                txHistoryLoadedCount += history.size
            } catch (_: Throwable) {
                // silently ignore: tx history is optional
            }
        }
    }

    // ── Write tag flow state ──────────────────────────────────────────────────

    /** Current step in the NFC tag programming flow (null = flow not active). */
    var writeTagStep by mutableStateOf<WriteTagStep?>(null)

    /** Full asset name being programmed onto the tag (e.g. "BRAND/ITEM#SN001"). */
    var writeTagAssetName by mutableStateOf("")

    /** Keys returned by a successful tag programming operation (shown in the result UI). */
    var writeTagKeys by mutableStateOf<WriteTagKeys?>(null)

    /** Human-readable error message if the tag programming flow fails. */
    var writeTagError by mutableStateOf<String?>(null)

    /** Distinguishes sub-asset issuance from unique-token issuance in [WriteTagArgs]. */
    enum class WriteTagAssetKind { SUB_ASSET, UNIQUE_TOKEN }

    /**
     * Arguments collected from the Issue form before the tag-programming flow starts.
     * Held in the ViewModel so they survive the foreground-dispatch round-trip that
     * delivers the NFC intent.
     */
    data class WriteTagArgs(
        val baseUrl: String,
        val parentAsset: String,
        val subAsset: String? = null,
        val variantAsset: String? = null,
        val toAddress: String,
        val imageIpfsCid: String? = null,
        val description: String? = null,
        val assetKind: WriteTagAssetKind
    ) {
        /**
         * Compute the full Ravencoin asset name from the component parts.
         * SUB_ASSET produces "PARENT/SUB".
         * UNIQUE_TOKEN produces "PARENT/SUB#VARIANT".
         */
        val fullAssetName: String
            get() = when (assetKind) {
                WriteTagAssetKind.SUB_ASSET -> "${parentAsset.uppercase()}/${requireNotNull(subAsset).uppercase()}"
                WriteTagAssetKind.UNIQUE_TOKEN -> "${requireNotNull(subAsset).uppercase()}#${requireNotNull(variantAsset).uppercase()}"
            }
    }

    /** Issue/write arguments set before the flow starts; read when the tag is tapped. */
    var pendingWriteArgs: WriteTagArgs? = null

    /** NTAG 424 write parameters (built after UID read + key derivation). */
    var pendingWriteParams by mutableStateOf<Ntag424Configurator.WriteParams?>(null)

    /** Salt generated at UID-read time; kept until the flow succeeds or is cancelled. */
    internal var pendingWriteSalt: ByteArray? = null

    /** Raw tag UID read at the start of the write flow; kept for registration. */
    internal var pendingWriteUid: ByteArray? = null

    /** NTAG 424 DNA configurator (runs ISO 7816-4 APDU commands over IsoDep). */
    private val ntag424 = Ntag424Configurator()

    /** RPC client for Ravencoin node calls (asset metadata, UTXO queries). */
    private val rpcClient = RpcClient(context = application)

    // ── Wallet lifecycle ──────────────────────────────────────────────────────

    /**
     * Inject wallet and asset managers after they are created in [MainActivity.onCreate].
     * If a wallet already exists, immediately loads balance and owned assets.
     */
    fun initWallet(wm: WalletManager, am: AssetManager) {
        walletManager = wm
        assetManager = am
        hasWallet = wm.hasWallet()
        if (hasWallet) { loadWalletInfo(); loadOwnedAssets() }
    }

    /** Delete the wallet from secure storage and clear all wallet state. */
    fun deleteWallet() {
        walletManager?.deleteWallet()
        hasWallet = false
        walletInfo = null
    }

    /**
     * Mnemonic shown once to the user after wallet generation.
     * Cleared once the user confirms backup and [confirmMnemonicBackup] is called.
     */
    var pendingMnemonic by mutableStateOf<String?>(null)

    /**
     * Generate BIP39 mnemonic entropy on the Default dispatcher (CPU-bound).
     * The wallet is NOT persisted yet; [hasWallet] stays false until the user
     * confirms the mnemonic backup via [confirmMnemonicBackup].
     * Guards against double-click with [walletGenerating].
     */
    fun generateWallet() {
        val wm = walletManager ?: return
        if (walletGenerating) return  // guard against double-click
        viewModelScope.launch {
            walletGenerating = true
            try {
                val mnemonic = withContext(Dispatchers.Default) { wm.generateMnemonic() }
                pendingMnemonic = mnemonic
                // wallet is NOT yet stored, hasWallet stays false
            } catch (e: Throwable) {
                walletInfo = WalletInfo(address = "", balanceRvn = 0.0, error = "Wallet creation failed: ${e.message}")
            } finally {
                walletGenerating = false
            }
        }
    }

    /**
     * Persist the wallet after the user confirms they have backed up the mnemonic.
     * Sets [hasWallet] to true and starts loading the initial balance.
     */
    fun confirmMnemonicBackup() {
        val wm = walletManager ?: return
        val mnemonic = pendingMnemonic ?: return
        viewModelScope.launch {
            val address = withContext(Dispatchers.Default) {
                wm.finalizeWallet(mnemonic)
                wm.getAddress() ?: ""
            }
            hasWallet = true
            walletInfo = WalletInfo(address = address, balanceRvn = 0.0, isLoading = true)
            pendingMnemonic = null
            loadWalletBalance()
        }
    }

    /**
     * Restore a wallet from a BIP39 mnemonic phrase.
     * On success, loads balance, assets, and transaction history.
     * On failure, sets an error message in [walletInfo].
     */
    fun restoreWallet(mnemonic: String) {
        val wm = walletManager ?: return
        viewModelScope.launch {
            try {
                val address = withContext(Dispatchers.Default) {
                    if (!wm.restoreWallet(mnemonic)) return@withContext null
                    wm.getAddress()
                }
                if (address != null) {
                    hasWallet = true
                    walletInfo = WalletInfo(address = address, balanceRvn = 0.0, isLoading = true)
                    loadWalletBalance()
                    loadOwnedAssets()
                    loadTransactionHistory()
                } else {
                    walletInfo = WalletInfo(address = "", balanceRvn = 0.0, error = "Invalid mnemonic")
                }
            } catch (e: Throwable) {
                walletInfo = WalletInfo(address = "", balanceRvn = 0.0, error = "Restore failed: ${e.message}")
            }
        }
    }

    /** Initialise [walletInfo] with the address and start loading balance + history. */
    private fun loadWalletInfo() {
        val wm = walletManager ?: return
        walletInfo = WalletInfo(address = wm.getAddress() ?: "", balanceRvn = 0.0, isLoading = true)
        loadWalletBalance()
        loadTransactionHistory()
    }

    /** Refresh balance, owned assets, and transaction history (pull-to-refresh). */
    fun refreshBalance() { loadWalletBalance(); loadOwnedAssets(); loadTransactionHistory() }

    /**
     * Load the RVN balance for the wallet address.
     *
     * Tries [WalletManager.getLocalBalance] first (ElectrumX direct), then falls
     * back to [AssetManager.getWalletInfo] (backend API). Errors are swallowed to
     * keep the UI usable even when the network is unavailable.
     */
    private fun loadWalletBalance() {
        val wm = walletManager ?: return
        viewModelScope.launch {
            try {
                val balance = withContext(Dispatchers.IO) { wm.getLocalBalance() }
                if (balance != null) {
                    walletInfo = walletInfo?.copy(balanceRvn = balance, isLoading = false)
                    return@launch
                }
                val am = assetManager ?: run {
                    walletInfo = walletInfo?.copy(isLoading = false)
                    return@launch
                }
                val info = withContext(Dispatchers.IO) { am.getWalletInfo() }
                walletInfo = walletInfo?.copy(
                    balanceRvn = info?.first ?: 0.0,
                    isLoading = false
                )
            } catch (_: Throwable) {
                walletInfo = walletInfo?.copy(isLoading = false)
            }
        }
    }

    // ── Asset issuance ────────────────────────────────────────────────────────

    /**
     * Issue a root Ravencoin asset on-chain using the local HD wallet.
     * On success, notifies the RavenTag public registry (fire-and-forget).
     */
    fun issueRootAsset(name: String, qty: Long, toAddress: String, ipfsHash: String?, reissuable: Boolean) {
        val wm = walletManager ?: return
        viewModelScope.launch {
            issueLoading = true
            try {
                val assetName = name.uppercase()
                val txid = withContext(Dispatchers.IO) {
                    wm.issueAssetLocal(assetName, qty.toDouble(), toAddress, units = 0, reissuable = reissuable, ipfsHash = ipfsHash)
                }
                issueSuccess = true
                val s = getStrings()
                issueResult = s.issueRootSuccess.replace("%1", assetName).replace("%2", "${txid.take(16)}...")
                notifyRavenTagRegistry(assetName, txid, "root")
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = getStrings().issueFailed
            } finally { issueLoading = false }
        }
    }

    /**
     * Issue a sub-asset ("PARENT/CHILD") on-chain using the local HD wallet.
     * On success, notifies the RavenTag public registry (fire-and-forget).
     */
    fun issueSubAsset(parent: String, child: String, qty: Long, toAddress: String, ipfsHash: String?, reissuable: Boolean) {
        val wm = walletManager ?: return
        viewModelScope.launch {
            issueLoading = true
            try {
                val fullName = "${parent.uppercase()}/${child.uppercase()}"
                val txid = withContext(Dispatchers.IO) {
                    wm.issueAssetLocal(fullName, qty.toDouble(), toAddress, units = 0, reissuable = reissuable, ipfsHash = ipfsHash)
                }
                issueSuccess = true
                val s = getStrings()
                issueResult = s.issueSubSuccess.replace("%1", fullName).replace("%2", "${txid.take(16)}...")
                notifyRavenTagRegistry(fullName, txid, "sub")
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = getStrings().issueFailed
            } finally { issueLoading = false }
        }
    }

    /**
     * Issue a unique token ("PARENT/SUB#SERIAL") on-chain using the local HD wallet.
     * Unique tokens always have qty=1, units=0, reissuable=false (Ravencoin protocol).
     * On success, notifies the RavenTag public registry (fire-and-forget).
     */
    fun issueUniqueToken(parentSub: String, serial: String, toAddress: String, ipfsHash: String?) {
        val wm = walletManager ?: return
        viewModelScope.launch {
            issueLoading = true
            try {
                val fullName = "${parentSub.uppercase()}#${serial.uppercase()}"
                val txid = withContext(Dispatchers.IO) {
                    wm.issueAssetLocal(fullName, qty = 1.0, toAddress = toAddress, units = 0, reissuable = false, ipfsHash = ipfsHash)
                }
                issueSuccess = true
                val s = getStrings()
                issueResult = s.issueUniqueSuccess.replace("%1", fullName).replace("%2", "${txid.take(16)}...")
                notifyRavenTagRegistry(fullName, txid, "unique")
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = getStrings().issueFailed
            } finally { issueLoading = false }
        }
    }

    // ── Asset revocation ──────────────────────────────────────────────────────

    /**
     * Remove a previous revocation from the backend database, restoring the asset
     * to AUTHENTIC status. Note: if the asset was also burned on-chain, the
     * on-chain burn cannot be undone; only the database flag is cleared.
     */
    fun unrevokeAsset(assetName: String, adminKey: String) {
        val am = AssetManager(adminKey = adminKey)
        viewModelScope.launch {
            issueLoading = true
            try {
                val result = withContext(Dispatchers.IO) { am.unrevokeAsset(assetName) }
                issueSuccess = result.success
                issueResult = if (result.success) {
                    "${result.assetName ?: assetName} restored , now AUTHENTIC"
                } else {
                    result.error ?: "Restore failed. Asset may have been burned on-chain."
                }
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = e.message ?: "Restore failed"
            } finally { issueLoading = false }
        }
    }

    /**
     * Revoke an asset by marking it as revoked in the backend SQLite database.
     * This is a soft revocation that updates the database record only.
     * The asset remains valid on-chain but will show as REVOKED in scans.
     *
     * Note: On-chain burn is NOT supported because you can only burn assets you own.
     * Unique tokens are typically held by consumers, not the brand.
     * Soft revocation is sufficient to mark assets as invalid.
     */
    fun revokeAsset(assetName: String, reason: String, adminKey: String) {
        val am = AssetManager(adminKey = adminKey)
        viewModelScope.launch {
            issueLoading = true
            try {
                withContext(Dispatchers.IO) {
                    // Mark revoked in backend SQLite
                    am.revokeAsset(BurnParams(assetName, reason = reason, burnOnChain = false))
                }
                issueSuccess = true
                issueResult = "Asset $assetName revocato"
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = e.message ?: "Revoca fallita"
            } finally { issueLoading = false }
        }
    }

    // ── RavenTag public registry notification ─────────────────────────────────

    /**
     * Silent fire-and-forget notification to the RavenTag public registry.
     * Only fires when the configured backend is raventag.com (i.e. the operator
     * has not replaced the backend with their own instance).
     * Required by the RavenTag protocol for public brand discovery.
     */
    private fun notifyRavenTagRegistry(assetName: String, txid: String, assetType: String) {
        if (!currentVerifyUrl.contains("raventag.com", ignoreCase = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val body = com.google.gson.Gson().toJson(
                    mapOf(
                        "asset_name" to assetName,
                        "asset_type" to assetType,
                        "txid" to txid,
                        "issued_at" to java.time.Instant.now().toString(),
                        "protocol_version" to "RTP-1"
                    )
                ).toRequestBody("application/json".toMediaType())
                val request = okhttp3.Request.Builder()
                    .url("https://api.raventag.com/api/registry/notify")
                    .post(body)
                    .build()
                client.newCall(request).execute().close()
            } catch (_: Exception) {
                // Non-blocking: registry may be unreachable, emission is still valid on-chain
            }
        }
    }

    /** Clear issue/transfer result state and pre-filled values after the overlay is dismissed. */
    fun clearIssueResult() {
        issueResult = null
        issueSuccess = null
        registerNfcPubId = null
        prefilledTransferAssetName = null
    }

    /**
     * Register a physical NFC chip against an asset on the backend.
     * Calls POST /api/brand/chips with the asset name and tag UID.
     */
    fun registerChip(assetName: String, tagUid: String, adminKey: String) {
        val am = AssetManager(adminKey = adminKey)
        viewModelScope.launch {
            issueLoading = true
            try {
                val result = withContext(Dispatchers.IO) { am.registerChip(assetName, tagUid) }
                issueSuccess = result.success
                if (result.success) {
                    issueResult = "Chip registered for asset ${result.assetName ?: assetName}"
                    registerNfcPubId = null
                } else {
                    issueResult = result.error ?: "Registration failed"
                }
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = e.message ?: "Registration failed"
            } finally { issueLoading = false }
        }
    }

    // ── Send / Receive RVN ────────────────────────────────────────────────────

    /** True while an RVN send transaction is being built and broadcast. */
    var sendLoading by mutableStateOf(false)

    /** Human-readable result of the last send attempt. */
    var sendResult by mutableStateOf<String?>(null)

    /** True = send succeeded, false = failed, null = not yet run. */
    var sendSuccess by mutableStateOf<Boolean?>(null)

    /** True when the fee estimate is unavailable (ElectrumX offline or no UTXOs). */
    var sendFeeUnavailable by mutableStateOf(false)

    /** True when the Receive QR-code overlay is shown. */
    var showReceive by mutableStateOf(false)

    /** True when the Send RVN overlay is shown. */
    var showSend by mutableStateOf(false)

    /** True when Send is opened in donate mode (address pre-filled with project donation address). */
    var donateMode by mutableStateOf(false)

    /** True while the user is entering a mnemonic in the restore flow. Screenshots must be blocked. */
    var restoreModeActive by mutableStateOf(false)

    /** Project donation address (pre-filled when [donateMode] is true). */
    val donateAddress = "RG5MujXzxARjWChWdU2awbAQa9ZCH52yrh"

    /**
     * Build, sign, and broadcast an RVN transfer transaction on-device via ElectrumX.
     * The result string encodes the txid and actual fee: "txid|fee:satoshis".
     * Throws [io.raventag.app.wallet.FeeUnavailableException] when fee data cannot be
     * obtained (e.g. ElectrumX is offline), which sets [sendFeeUnavailable] instead of
     * showing a generic error.
     *
     */
    fun sendRvn(toAddress: String, amount: Double) {
        val s = getStrings()
        val wm = walletManager ?: run {
            sendSuccess = false; sendResult = s.walletNoWallet; return
        }
        viewModelScope.launch {
            sendLoading = true
            sendFeeUnavailable = false
            try {
                val result = withContext(Dispatchers.IO) { wm.sendRvnLocal(toAddress, amount) }
                val txid = result.substringBefore("|fee:")
                val feeRvn = result.substringAfter("|fee:", "0").toLongOrNull()?.let { it / 1e8 } ?: 0.0
                val s = getStrings()
                sendLoading = false
                sendSuccess = true
                sendResult = s.walletSendResult.replace("%1", amount.toString())
                    .replace("%2", "%.5f".format(feeRvn))
                    .replace("%3", "${txid.take(20)}...")
                loadWalletBalance()
            } catch (e: io.raventag.app.wallet.FeeUnavailableException) {
                sendLoading = false
                sendFeeUnavailable = true
            } catch (e: Throwable) {
                val s = getStrings()
                sendLoading = false
                sendSuccess = false
                sendResult = e.message ?: s.walletSendFailed
            }
        }
    }

    /**
     * Transfer an asset from the local wallet to [toAddress] via ElectrumX (consumer mode).
     * Used from the Wallet tab when the user holds an asset and wants to send it.
     */
    fun transferAssetConsumer(assetName: String, toAddress: String, qty: Long) {
        val s = getStrings()
        val wm = walletManager ?: run { issueSuccess = false; issueResult = s.walletNoWallet; return }
        viewModelScope.launch {
            issueLoading = true
            try {
                val txid = withContext(Dispatchers.IO) { wm.transferAssetLocal(assetName, toAddress, qty.toDouble()) }
                val s = getStrings()
                issueLoading = false
                issueSuccess = true
                issueResult = s.walletTransferResult.replace("%1", assetName).replace("%2", "${txid.take(20)}...")
            } catch (e: Throwable) {
                val s = getStrings()
                issueLoading = false
                issueSuccess = false
                issueResult = e.message ?: s.walletTransferFailed
            }
        }
    }

    // ── Write tag flow entry points ───────────────────────────────────────────

    /**
     * Start the "issue sub-asset + write NFC tag" flow.
     * Step 1: show WAIT_TAG so user taps the tag.
     */
    fun startWriteTagFlow(parentAsset: String, childName: String, adminKey: String = "") {
        writeTagAssetName = "$parentAsset/$childName"
        writeTagAdminKey = adminKey
        writeTagStep = WriteTagStep.WAIT_TAG
        writeTagKeys = null
        writeTagError = null
        pendingWriteArgs = WriteTagArgs(
            baseUrl = currentVerifyUrl,
            parentAsset = parentAsset,
            subAsset = childName,
            toAddress = walletInfo?.address ?: "",
            imageIpfsCid = null,
            assetKind = WriteTagAssetKind.SUB_ASSET
        )
        pendingWriteParams = null
        pendingWriteSalt = null
        pendingWriteUid = null
    }

    /**
     * Start the combined "issue sub-asset + program NFC tag" flow.
     * Clears any previous issue/write state, sets up [pendingWriteArgs], and
     * transitions to WAIT_TAG.
     */
    fun startIssueSubAndWriteTag(parentAsset: String, childName: String, toAddress: String, ipfsHash: String?, adminKey: String) {
        issueMode = null
        issueResult = null
        issueSuccess = null
        writeTagAssetName = "${parentAsset.uppercase()}/${childName.uppercase()}"
        writeTagAdminKey = adminKey
        pendingWriteArgs = WriteTagArgs(
            baseUrl = currentVerifyUrl,
            parentAsset = parentAsset,
            subAsset = childName,
            toAddress = toAddress,
            imageIpfsCid = ipfsHash,
            assetKind = WriteTagAssetKind.SUB_ASSET
        )
        writeTagStep = WriteTagStep.WAIT_TAG
        writeTagKeys = null
        writeTagError = null
        pendingWriteParams = null
        pendingWriteSalt = null
        pendingWriteUid = null
        isStandaloneWrite = false
    }

    /**
     * Start the combined "issue unique token + program NFC tag" flow.
     * Clears any previous issue/write state, sets up [pendingWriteArgs] with
     * UNIQUE_TOKEN kind, and transitions to WAIT_TAG.
     */
    fun startIssueUniqueAndWriteTag(parentSub: String, serial: String, toAddress: String, ipfsHash: String?, description: String?, adminKey: String) {
        issueMode = null
        issueResult = null
        issueSuccess = null
        writeTagAssetName = "${parentSub.uppercase()}#${serial.uppercase()}"
        writeTagAdminKey = adminKey
        pendingWriteArgs = WriteTagArgs(
            baseUrl = currentVerifyUrl,
            parentAsset = parentSub.substringBefore("/").uppercase(),
            subAsset = parentSub.uppercase(),
            variantAsset = serial,
            toAddress = toAddress,
            imageIpfsCid = ipfsHash,
            description = description,
            assetKind = WriteTagAssetKind.UNIQUE_TOKEN
        )
        writeTagStep = WriteTagStep.WAIT_TAG
        writeTagKeys = null
        writeTagError = null
        pendingWriteParams = null
        pendingWriteSalt = null
        pendingWriteUid = null
        isStandaloneWrite = false
    }

    // ── NFC tag programming ───────────────────────────────────────────────────

    /**
     * Single entry point for NFC tag programming, called from [MainActivity.handleIntent]
     * when a tag is detected while [writeTagStep] == WAIT_TAG.
     *
     * Reads the tag UID, then dispatches to either:
     *   - [processStandaloneWrite]: re-key an existing tag without issuing a new asset.
     *   - [processIssueAndWrite]: run the full issue + program flow.
     *
     * Transitions to PROCESSING while work is in progress, then to SUCCESS or ERROR.
     */
    fun onTagTapped(tag: android.nfc.Tag) {
        val uid = ntag424.readTagUid(tag) ?: run {
            writeTagStep = WriteTagStep.ERROR
            writeTagError = "Impossibile leggere l'UID del tag. Riprova."
            return
        }
        Log.i("IssueWriteFlow", "onTagTapped uid=${uid.toHex()} asset=$writeTagAssetName standalone=$isStandaloneWrite")

        viewModelScope.launch {
            writeTagStep = WriteTagStep.PROCESSING
            writeTagError = null

            val result = withContext(Dispatchers.IO) {
                if (isStandaloneWrite) {
                    processStandaloneWrite(tag, uid)
                } else {
                    processIssueAndWrite(tag, uid)
                }
            }

            if (result.isFailure) {
                writeTagStep = WriteTagStep.ERROR
                writeTagError = result.exceptionOrNull()?.message ?: "Errore sconosciuto"
            } else {
                writeTagStep = WriteTagStep.SUCCESS
                writeTagKeys = result.getOrNull()
            }
        }
    }

    /**
     * Validate and parse the initial master key hex stored in Settings.
     * Returns a 16-byte all-zero key if the field is blank (factory default).
     * Returns a failure if the hex is non-empty but malformed.
     */
    private fun resolveInitialMasterKey(): Result<ByteArray> {
        val hex = initialMasterKeyHex.trim()
        if (hex.isBlank()) return Result.success(ByteArray(16))
        val normalized = hex.lowercase()
        if (normalized.length != 32 || !normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            return Result.failure(Exception("Initial Master Key non valida: servono 32 caratteri esadecimali"))
        }
        return Result.success(ByteArray(16) { i ->
            normalized.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        })
    }

    /**
     * Standalone tag write flow (no asset issuance):
     *   1. Derive chip keys from the backend using the tag UID.
     *   2. Program the NTAG 424 DNA with the derived keys and the verify URL.
     *   3. Register the chip on the backend (associates UID with the asset).
     *
     * @param tag  The NFC Tag object from the intent
     * @param uid  Raw 7-byte tag UID read by [Ntag424Configurator.readTagUid]
     * @return Success([WriteTagKeys]) on completion, Failure on any step error
     */
    private suspend fun processStandaloneWrite(tag: android.nfc.Tag, uid: ByteArray): Result<WriteTagKeys> {
        val uidHex = uid.toHex()
        val am = AssetManager(adminKey = writeTagAdminKey)
        Log.i("IssueWriteFlow", "processStandaloneWrite start uid=$uidHex asset=$writeTagAssetName")
        val currentMasterKey = resolveInitialMasterKey()
            .getOrElse { return Result.failure(it) }

        // 1. Derive all NTAG 424 session keys from the backend using the tag UID
        val keys = am.deriveChipKeys(uidHex)
            ?: return Result.failure(Exception("Errore derivazione chiavi. Verifica BRAND_MASTER_KEY."))
        Log.i("IssueWriteFlow", "processStandaloneWrite keys-derived uid=$uidHex nfcPubId=${keys.nfcPubId}")

        // 2. Program the tag: authenticate with currentMasterKey, then set all new keys and NDEF URL
        val params = io.raventag.app.nfc.Ntag424Configurator.WriteParams(
            baseUrl = standaloneWriteBaseUrl,
            newAppMasterKey = keys.appMasterKey,
            newSdmmacInputKey = keys.sdmmacInputKey,
            newSdmEncKey = keys.sdmEncKey,
            newSdmMacKey = keys.sdmMacKey,
            currentMasterKey = currentMasterKey
        )
        val configResult = ntag424.configure(tag, params)
        if (configResult.isFailure) return Result.failure(configResult.exceptionOrNull()!!)
        Log.i("IssueWriteFlow", "processStandaloneWrite tag-configured uid=$uidHex asset=$writeTagAssetName")

        // 3. Register the chip on the backend so it can be verified by UID later
        val regResult = am.registerChip(writeTagAssetName, uidHex)
        Log.i("IssueWriteFlow", "processStandaloneWrite registerChip success=${regResult.success} error=${regResult.error}")

        return Result.success(WriteTagKeys(
            sdmmacInputKey = keys.sdmmacInputKey.toHex(),
            sdmEncKey = keys.sdmEncKey.toHex(),
            sdmMacKey = keys.sdmMacKey.toHex(),
            nfcPubId = keys.nfcPubId,
            tagUid = uidHex,
            registrationOk = regResult.success
        ))
    }

    /**
     * Combined "issue asset + program NFC tag" flow:
     *   1. Preflight: verify the tag is writable before any irreversible on-chain action.
     *   2. Derive chip keys (and nfc_pub_id) from the backend using the tag UID.
     *   3. Build IPFS metadata JSON including the nfc_pub_id and upload it.
     *   4. Issue the Ravencoin asset on-chain with the IPFS metadata hash.
     *   5. Program the NTAG 424 DNA with the derived keys and verify URL.
     *   6. Register the chip on the backend.
     *
     * The preflight check (step 1) is critical: it authenticates the tag before
     * any on-chain transaction is broadcast. This prevents wasting burn fees on
     * already-programmed or incompatible tags.
     *
     * @param tag  The NFC Tag object from the intent
     * @param uid  Raw 7-byte tag UID read by [Ntag424Configurator.readTagUid]
     * @return Success([WriteTagKeys]) on completion, Failure on any step error
     */
    private suspend fun processIssueAndWrite(tag: android.nfc.Tag, uid: ByteArray): Result<WriteTagKeys> {
        val args = pendingWriteArgs ?: return Result.failure(Exception("Parametri di emissione mancanti"))
        val uidHex = uid.toHex()
        val am = AssetManager(adminKey = writeTagAdminKey)
        val fullName = args.fullAssetName
        Log.i("IssueWriteFlow", "processIssueAndWrite start asset=$fullName uid=$uidHex kind=${args.assetKind}")
        val currentMasterKey = resolveInitialMasterKey()
            .getOrElse { return Result.failure(it) }

        // 1. Preflight tag auth/writability before any irreversible on-chain issuance.
        // Authenticates with currentMasterKey; fails fast if the tag is not writable.
        val preflightResult = ntag424.verifyWritable(tag, currentMasterKey)
        if (preflightResult.isFailure) {
            return Result.failure(
                Exception("Tag non scrivibile: ${preflightResult.exceptionOrNull()?.message ?: "verifica preliminare fallita"}")
            )
        }
        Log.i("IssueWriteFlow", "processIssueAndWrite tag-preflight-ok asset=$fullName uid=$uidHex")

        // 2. Derive all NTAG 424 session keys and the nfc_pub_id from the backend
        val keys = am.deriveChipKeys(uidHex)
            ?: return Result.failure(Exception("Errore derivazione chiavi dal backend"))
        Log.i("IssueWriteFlow", "processIssueAndWrite keys-derived asset=$fullName uid=$uidHex nfcPubId=${keys.nfcPubId}")

        // 3. Build the RTP-1 metadata object including the nfc_pub_id that links this
        //    specific chip to the on-chain asset in a trustless, privacy-preserving way
        val metadata = buildMap<String, Any?> {
            put("raventag_version", "RTP-1")
            put("parent_asset", args.parentAsset.uppercase())
            args.subAsset?.let { put("sub_asset", it.uppercase()) }
            args.variantAsset?.let { put("variant_asset", it.uppercase()) }
            put("nfc_pub_id", keys.nfcPubId)
            put("crypto_type", "ntag424_sun")
            put("algo", "aes-128")
            if (args.imageIpfsCid != null) put("image", "ipfs://${args.imageIpfsCid}")
            args.description?.let { put("description", it) }
        }

        // 4. Upload metadata to IPFS via Pinata, Kubo, or the backend (in that priority order)
        val ipfsHash = uploadMetadata(metadata, am)
            ?: return Result.failure(Exception("Caricamento IPFS fallito"))
        Log.i("IssueWriteFlow", "processIssueAndWrite metadata-uploaded asset=$fullName metadataIpfs=$ipfsHash")

        // 5. Issue the Ravencoin asset on-chain; the IPFS hash is embedded in the issuance tx
        val wm = walletManager ?: return Result.failure(Exception("Wallet non disponibile"))
        val txid = try {
            wm.issueAssetLocal(
                fullName,
                qty = 1.0,
                toAddress = args.toAddress,
                units = 0,
                reissuable = false,
                ipfsHash = ipfsHash
            )
        } catch (e: Exception) {
            return Result.failure(Exception("Emissione Ravencoin fallita: ${e.message}"))
        }
        Log.i("IssueWriteFlow", "processIssueAndWrite asset-issued asset=$fullName txid=$txid")
        notifyRavenTagRegistry(
            assetName = fullName,
            txid = txid,
            assetType = if (args.assetKind == WriteTagAssetKind.UNIQUE_TOKEN) "unique" else "sub"
        )

        // 6. Program the tag: authenticate, set keys derived from this specific UID, write NDEF URL
        val verifyUrl = "${args.baseUrl}/verify?asset=${fullName.uppercase().replace("#", "%23")}&"
        val params = io.raventag.app.nfc.Ntag424Configurator.WriteParams(
            baseUrl = verifyUrl,
            newAppMasterKey = keys.appMasterKey,
            newSdmmacInputKey = keys.sdmmacInputKey,
            newSdmEncKey = keys.sdmEncKey,
            newSdmMacKey = keys.sdmMacKey,
            currentMasterKey = currentMasterKey
        )
        val configResult = ntag424.configure(tag, params)
        if (configResult.isFailure) return Result.failure(configResult.exceptionOrNull()!!)
        Log.i("IssueWriteFlow", "processIssueAndWrite tag-configured asset=$fullName uid=$uidHex")

        // 7. Register the chip on the backend (links UID to the new asset record)
        val regResult = am.registerChip(fullName, uidHex)
        Log.i("IssueWriteFlow", "processIssueAndWrite registerChip asset=$fullName success=${regResult.success} error=${regResult.error}")

        return Result.success(WriteTagKeys(
            sdmmacInputKey = keys.sdmmacInputKey.toHex(),
            sdmEncKey = keys.sdmEncKey.toHex(),
            sdmMacKey = keys.sdmMacKey.toHex(),
            nfcPubId = keys.nfcPubId,
            tagUid = uidHex,
            registrationOk = regResult.success
        ))
    }

    /**
     * Upload a metadata JSON object to IPFS and return the CIDv0 hash.
     *
     * Priority order:
     *   1. Pinata (if JWT is validated): direct pin via Pinata REST API.
     *   2. Kubo (if node URL is validated): direct add via local IPFS node.
     *   3. Backend fallback: upload via the RavenTag backend's /api/ipfs endpoint.
     *
     * Returns null if all upload methods fail.
     */
    private suspend fun uploadMetadata(metadata: Map<String, Any?>, am: AssetManager): String? {
        return if (pinataJwtStatus == AdminKeyStatus.VALID && pinataJwt.isNotBlank()) {
            runCatching { io.raventag.app.ipfs.PinataUploader.uploadJson(com.google.gson.Gson().toJson(metadata), pinataJwt) }.getOrNull()
        } else if (kuboNodeStatus == AdminKeyStatus.VALID && kuboNodeUrl.isNotBlank()) {
            runCatching { io.raventag.app.ipfs.KuboUploader.uploadJson(com.google.gson.Gson().toJson(metadata), kuboNodeUrl) }.getOrNull()
        } else {
            am.uploadMetadata(metadata)
        }
    }

    /** Cancel the active tag write flow and reset all related state. */
    fun cancelWriteTagFlow() {
        writeTagStep = null
        pendingWriteUid = null
        writeTagError = null
        isStandaloneWrite = false
    }

    /** Hex-encode a ByteArray for logging and result display. */
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // ── SUN verification flow ─────────────────────────────────────────────────

    /**
     * Handle SUN (Secure Unique NFC) parameters extracted from the NDEF URL.
     *
     * Two verification paths:
     *
     * Standard path (asset != null): the NDEF URL carries the asset name, so the
     *   backend can look up the chip record, decrypt the UID, verify the MAC, and
     *   check revocation in one call (POST /api/verify/tag).
     *   The client-side counter cache is updated for defense-in-depth replay detection.
     *
     * Fallback path (asset == null): the app has sdmmacKey / sunMacKey configured
     *   locally. Performs local AES-CMAC verification, counter-replay check, and
     *   (if [expectedAsset] is set) an on-chain asset + revocation check.
     *
     * @param e       Encrypted UID field from the SUN URL parameter "e" (hex)
     * @param m       Truncated SUN MAC from URL parameter "m" (8 hex chars = 4 bytes)
     * @param asset   Ravencoin asset name from URL parameter "asset", or null
     * @param rawUrl  Complete NDEF URL from which parameters were extracted (for health check)
     */
    fun onSunParamsReceived(e: String, m: String, asset: String?, rawUrl: String) {
        viewModelScope.launch {
            verifyStep = VerifyStep.VERIFYING_SUN
            verifyResult = null
            scanState = ScanState.IDLE

            // ── Health check and dynamic backend configuration ──────────────────────
            // Extract the base URL from the scanned tag's NDEF URL and check its health.
            // If the tag specifies a different backend that is healthy, we switch to it.
            val tagBaseUrl = try {
                val uri = android.net.Uri.parse(rawUrl)
                if (uri.scheme != null && uri.host != null) {
                    "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
                } else currentVerifyUrl
            } catch (_: Exception) { currentVerifyUrl }

            val healthy = withContext(Dispatchers.IO) {
                io.raventag.app.wallet.AssetManager(apiBaseUrl = tagBaseUrl).checkHealth()
            }

            if (!healthy) {
                // Server non risponde: inform the user without declaring the tag fake.
                verifyStep = VerifyStep.ERROR
                verifyResult = VerifyResult(
                    authentic = false,
                    error = "Il server backend non risponde ($tagBaseUrl). Impossibile verificare l'autenticità del tag in questo momento."
                )
                return@launch
            }

            // If healthy and different from current, update and save configuration
            if (tagBaseUrl != currentVerifyUrl) {
                currentVerifyUrl = tagBaseUrl
                getApplication<Application>().getSharedPreferences("raventag_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putString("verify_url", tagBaseUrl).apply()
                serverStatus = ServerStatus.ONLINE
                Log.i("NFC", "Switched backend to $tagBaseUrl based on scanned tag")
            }
            // ────────────────────────────────────────────────────────────────────────

            // Standard flow: asset is in the URL, backend derives keys and verifies everything.
            if (asset != null) {
                verifyStep = VerifyStep.CHECKING_BLOCKCHAIN
                val response = withContext(Dispatchers.IO) {
                    io.raventag.app.wallet.AssetManager(apiBaseUrl = currentVerifyUrl)
                        .verifyTag(asset, e, m)
                }
                // CRIT-1: update client-side counter cache for defense-in-depth replay detection
                if (response.nfcPubId != null && response.counter != null) {
                    nfcCounterCache.updateCounter(response.nfcPubId, response.counter)
                }
                verifyStep = VerifyStep.DONE
                verifyResult = VerifyResult(
                    authentic = response.authentic,
                    tagUidHex = response.tagUid,
                    counter = response.counter,
                    nfcPubId = response.nfcPubId,
                    assetName = response.assetName,
                    metadata = response.metadata,
                    revoked = response.revoked,
                    revokedReason = response.revokedReason,
                    error = when {
                        !response.authentic && response.stepFailed == "counter_replay" ->
                            "Counter replay detected: possible cloning attempt."
                        !response.authentic && response.error != null -> response.error
                        else -> null
                    }
                )
                return@launch
            }

            // Fallback: local SUN verification (requires sdmmacKey and sunMacKey configured).
            val sunResult = withContext(Dispatchers.Default) {
                SunVerifier.verify(e, m, sdmmacKey, sunMacKey, salt)
            }

            if (!sunResult.valid) {
                verifyStep = VerifyStep.ERROR
                verifyResult = VerifyResult(authentic = false, error = sunResult.error)
                return@launch
            }

            // CRIT-1: client-side counter replay check (defense-in-depth for offline scenarios)
            val nfcPubIdForCache = sunResult.nfcPubId
            val counterForCache = sunResult.counter
            if (nfcPubIdForCache != null && counterForCache != null) {
                if (!nfcCounterCache.isCounterFresh(nfcPubIdForCache, counterForCache)) {
                    // Counter did not increase: possible tag clone, reject immediately
                    verifyStep = VerifyStep.DONE
                    verifyResult = VerifyResult(
                        authentic = false,
                        counter = counterForCache,
                        nfcPubId = nfcPubIdForCache,
                        error = "Counter replay detected: possible cloning attempt."
                    )
                    return@launch
                }
                nfcCounterCache.updateCounter(nfcPubIdForCache, counterForCache)
            }

            val assetName = expectedAsset
            if (assetName == null || sunResult.nfcPubId == null) {
                // No asset configured: report SUN-only result (authentic tag, no chain check)
                verifyStep = VerifyStep.DONE
                verifyResult = VerifyResult(
                    authentic = true,
                    tagUidHex = sunResult.tagUid?.joinToString("") { "%02x".format(it) },
                    counter = sunResult.counter,
                    nfcPubId = sunResult.nfcPubId
                )
                return@launch
            }

            // Blockchain check: verify that the nfc_pub_id in the asset's IPFS metadata
            // matches the one computed from the decrypted UID.
            verifyStep = VerifyStep.CHECKING_BLOCKCHAIN
            try {
                val (assetData, metadata) = withContext(Dispatchers.IO) {
                    rpcClient.getAssetWithMetadata(assetName) ?: throw Exception("Asset not found")
                }

                // Case-insensitive comparison: nfc_pub_id is a hex SHA-256 hash
                val match = metadata?.nfcPubId?.lowercase() == sunResult.nfcPubId.lowercase()

                // Check revocation status from the backend database
                val revocationStatus = withContext(Dispatchers.IO) {
                    io.raventag.app.wallet.AssetManager(apiBaseUrl = currentVerifyUrl).checkRevocationStatus(assetName)
                }
                val revoked = revocationStatus.revoked

                verifyStep = VerifyStep.DONE
                verifyResult = VerifyResult(
                    // Authentic only if both the chip ID matches AND the asset is not revoked
                    authentic = match && !revoked,
                    tagUidHex = sunResult.tagUid?.joinToString("") { "%02x".format(it) },
                    counter = sunResult.counter,
                    nfcPubId = sunResult.nfcPubId,
                    assetName = assetData.name,
                    metadata = metadata,
                    revoked = revoked,
                    revokedReason = revocationStatus.reason,
                    error = when {
                        revoked -> "This asset has been revoked by the brand"
                        !match -> "Tag ID does not match asset metadata"
                        else -> null
                    }
                )
            } catch (ex: Exception) {
                verifyStep = VerifyStep.ERROR
                verifyResult = VerifyResult(authentic = false, error = "Blockchain query failed: ${ex.message}")
            }
        }
    }

    /** Reset verification state and return to the scanning animation on the Scan tab. */
    fun resetToScan() {
        verifyStep = null
        verifyResult = null
        errorMessage = null
        scanState = ScanState.SCANNING
    }
}

// ============================================================
// Navigation
// ============================================================

/** Top-level navigation tabs in the bottom navigation bar. */
enum class AppTab { SCAN, WALLET, BRAND, SETTINGS }

// ============================================================
// Activity
// ============================================================

/**
 * MainActivity is the single Activity for the entire RavenTag application.
 *
 * Extends [FragmentActivity] (required by the Biometric API which needs a
 * FragmentManager to attach the BiometricPrompt fragment).
 *
 * Key responsibilities:
 *   - Registers NFC foreground dispatch in [onResume] / [onPause] so all NFC
 *     intents are delivered directly to this activity while it is in the foreground.
 *   - Routes NFC intents in [handleIntent]: write-flow tags go to [MainViewModel.onTagTapped],
 *     SUN verification URLs go to [MainViewModel.onSunParamsReceived].
 *   - Initialises [WalletManager] and [AssetManager] and passes them to the ViewModel.
 *   - Creates EncryptedSharedPreferences on an IO thread during startup (held off
 *     screen by the SplashScreen API until ready).
 *   - Sets FLAG_SECURE on the window to prevent screenshots / task-switcher
 *     thumbnails from exposing AES keys or the BIP39 mnemonic.
 */
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** System NFC adapter; null if the device has no NFC hardware. */
    private var nfcAdapter: NfcAdapter? = null

    /** Launcher for POST_NOTIFICATIONS runtime permission (Android 13+). */
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied: WorkManager polling already scheduled regardless */ }

    /** EncryptedSharedPreferences for admin/operator/master keys (AES256-GCM, Keystore-backed). */
    private lateinit var securePrefs: android.content.SharedPreferences

    /**
     * Compose state that gates rendering until [securePrefs] is initialised.
     * Kept as a mutableStateOf so the Compose tree re-renders when it flips to true.
     */
    private var securePrefsReady by mutableStateOf(false)

    /**
     * Show a biometric (or device-credential) authentication prompt.
     *
     * API compatibility:
     *   - API 30+: BIOMETRIC_STRONG | DEVICE_CREDENTIAL (supports PIN/pattern/password fallback,
     *     no negative button needed).
     *   - API 26-29: BIOMETRIC_WEAK with a mandatory negative button ("Annulla").
     *
     * If no credentials are enrolled or the hardware is unavailable, authentication
     * is silently bypassed by calling [onSuccess] immediately (fail-open for usability).
     * If the user actively cancels (ERROR_USER_CANCELED or ERROR_NEGATIVE_BUTTON),
     * [onError] is called (which closes the app via [finish]).
     * Hardware/enrollment errors also call [onSuccess] to avoid locking out the user.
     *
     * @param title    Title shown in the biometric prompt dialog
     * @param subtitle Subtitle line shown below the title
     * @param onSuccess Callback invoked when authentication succeeds or is bypassed
     * @param onError   Callback invoked when the user actively cancels
     */
    fun requestBiometricAuth(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is only supported on API 30+.
        // On older APIs use BIOMETRIC_WEAK (biometric only, requires negative button).
        val authenticators = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

        // If no credentials are enrolled or hardware unavailable, skip auth silently.
        val canAuth = BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onSuccess()
            return
        }

        try {
            val executor = ContextCompat.getMainExecutor(this)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Distinguish between "user cancelled" (close app) and
                    // "hardware/enrollment error" (allow access to avoid locking out).
                    val userCancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    if (userCancelled) onError() else onSuccess()
                }
                override fun onAuthenticationFailed() {
                    // A single biometric attempt failed; more attempts allowed, do nothing.
                }
            }
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
            // On API < 30 with BIOMETRIC_WEAK, a negative button text is required.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                builder.setNegativeButtonText("Annulla")
            }
            BiometricPrompt(this, executor, callback).authenticate(builder.build())
        } catch (_: Exception) {
            // If the prompt cannot be shown for any reason, grant access.
            onSuccess()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API: shows app icon on black background immediately, no white flash
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Process any NFC intent that launched or re-launched this activity
        handleIntent(intent)

        val walletManager = WalletManager(applicationContext)
        val assetManager = AssetManager(adminKey = BuildConfig.ADMIN_KEY)
        viewModel.initWallet(walletManager, assetManager)

        // Create notification channel (safe to call on every start, system ignores duplicates)
        NotificationHelper.createChannel(this)


        // Schedule periodic wallet polling every 15 minutes.
        // UPDATE policy: replaces any previously scheduled instance so app updates always
        // run the latest worker code without requiring a reinstall.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "wallet_poll",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WalletPollingWorker>(15, TimeUnit.MINUTES).build()
        )

        val prefs = getSharedPreferences("raventag_app", MODE_PRIVATE)

        // C-2: Admin key in EncryptedSharedPreferences (AES256-GCM, Android Keystore-backed).
        // Initialized on IO thread to avoid blocking the main thread during startup.
        // The SplashScreen is held visible until this completes (setKeepOnScreenCondition).
        splash.setKeepOnScreenCondition { !securePrefsReady }
        lifecycleScope.launch(Dispatchers.IO) {
            securePrefs = try {
                val masterKey = MasterKey.Builder(this@MainActivity)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    this@MainActivity,
                    "raventag_secure",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Throwable) {
                // Fallback to plain prefs if Keystore unavailable (e.g. work profile restrictions)
                getSharedPreferences("raventag_secure", MODE_PRIVATE)
            }
            withContext(Dispatchers.Main) { securePrefsReady = true }
        }

        // Initialize verify URL from saved prefs so revocation checks use the right backend
        val savedVerifyUrl = prefs.getString("verify_url", AppConfig.DEFAULT_VERIFY_URL) ?: AppConfig.DEFAULT_VERIFY_URL
        viewModel.currentVerifyUrl = savedVerifyUrl

        setContent {
            // Hold content until EncryptedSharedPreferences is ready (avoids reading null keys)
            if (!securePrefsReady) return@setContent

            // Persisted user preferences (read from SharedPreferences, updated on save)
            var langCode by remember { mutableStateOf(prefs.getString("language", "en") ?: "en") }
            var savedAdminKey by remember { mutableStateOf(securePrefs.getString("admin_key", "") ?: "") }
            var savedInitialMasterKey by remember { mutableStateOf(securePrefs.getString("initial_master_key", "") ?: "") }
            var savedOperatorKey by remember { mutableStateOf(securePrefs.getString("operator_key", "") ?: "") }
            var walletRole by remember { mutableStateOf(prefs.getString("wallet_role", "") ?: "") }
            // Sync wallet role into ViewModel so BrandDashboard can read it reactively
            LaunchedEffect(walletRole) { viewModel.walletRole = walletRole }
            var savedPinataJwt by remember { mutableStateOf(securePrefs.getString("pinata_jwt", "") ?: "") }
            var savedKuboNodeUrl by remember { mutableStateOf(securePrefs.getString("kubo_node_url", "") ?: "") }
            // Localised strings resolved from the saved language code
            val strings: AppStrings = remember(langCode) { appStringsFor(langCode) }

            var requireAuthOnStart by remember { mutableStateOf(prefs.getBoolean("require_auth_on_start", true)) }

            // No wallet: screenshots always allowed. Wallet present: read pref, default false (protected).
            val allowScreenshotsState = remember {
                mutableStateOf(
                    if (viewModel.hasWallet) prefs.getBoolean("allow_screenshots", false) else true
                )
            }
            var allowScreenshots by allowScreenshotsState

            // Apply FLAG_SECURE whenever allowScreenshots changes (including initial state).
            LaunchedEffect(allowScreenshots) {
                if (allowScreenshots) {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                        android.view.WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
            }

            // When wallet is created for the first time, auto-enable screenshot protection.
            LaunchedEffect(viewModel.hasWallet) {
                if (viewModel.hasWallet && !prefs.contains("allow_screenshots")) {
                    allowScreenshots = false
                    prefs.edit().putBoolean("allow_screenshots", false).apply()
                }
            }

            // Entering mnemonic restore mode: disable screenshots immediately to protect the mnemonic.
            // Exiting restore mode without a wallet: re-enable screenshots (nothing to protect).
            LaunchedEffect(viewModel.restoreModeActive) {
                if (viewModel.restoreModeActive) {
                    allowScreenshotsState.value = false
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                        android.view.WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else if (!viewModel.hasWallet) {
                    allowScreenshotsState.value = true
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            var notificationsEnabled by remember { mutableStateOf(prefs.getBoolean("notifications_enabled", true)) }
            var authPassed by remember { mutableStateOf(false) }

            // Pre-compute whether biometric/device-credential authentication is available
            val hasLockScreen = remember {
                val authenticators = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                } else {
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
                }
                BiometricManager.from(this@MainActivity).canAuthenticate(authenticators) ==
                    BiometricManager.BIOMETRIC_SUCCESS
            }
            RavenTagTheme {
                CompositionLocalProvider(LocalStrings provides strings) {
                    Surface(modifier = Modifier.fillMaxSize(), color = RavenBg) {
                        var splashDone by remember { mutableStateOf(false) }
                        var onboardingDone by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }

                        when {
                            // 1. Custom in-app splash animation (distinct from the OS splash screen)
                            !splashDone -> io.raventag.app.ui.screens.SplashScreen {
                                splashDone = true
                            }
                            // 2. First-run onboarding: language selection
                            !onboardingDone -> io.raventag.app.ui.screens.OnboardingScreen { selectedLang ->
                                prefs.edit()
                                    .putBoolean("onboarding_done", true)
                                    .putString("language", selectedLang)
                                    .apply()
                                langCode = selectedLang
                                onboardingDone = true
                            }
                            else -> {
                                // Request POST_NOTIFICATIONS permission once after a wallet is created.
                                // Before wallet setup the app only scans tags, so notifications
                                // are not relevant yet.
                                LaunchedEffect(viewModel.hasWallet) {
                                    if (viewModel.hasWallet &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                // 3. Biometric gate: show blank screen while prompt is up
                                val needsAuth = viewModel.hasWallet && requireAuthOnStart && !authPassed
                                if (needsAuth) {
                                    // Show a blank screen while biometric prompt is displayed
                                    Box(modifier = Modifier.fillMaxSize().background(RavenBg))
                                    LaunchedEffect(Unit) {
                                        requestBiometricAuth(
                                            title = strings.authTitle,
                                            subtitle = strings.authSubtitle,
                                            onSuccess = { authPassed = true },
                                            onError = { finish() }
                                        )
                                    }
                                } else {
                                    // No wallet present: skip auth gate
                                    if (!authPassed && !viewModel.hasWallet) {
                                        authPassed = true  // no wallet, no auth needed
                                    }
                                    RavenTagApp(
                                        viewModel = viewModel,
                                        verifyUrl = viewModel.currentVerifyUrl,
                                        onLangChange = { newLang ->
                                            prefs.edit().putString("language", newLang).apply()
                                            langCode = newLang
                                        },
                                        onVerifyUrlSave = { url ->
                                            // Only accept HTTPS URLs to prevent cleartext interception
                                            if (url.startsWith("https://")) {
                                                prefs.edit().putString("verify_url", url).apply()
                                                viewModel.currentVerifyUrl = url
                                                // Reset status so next tab visit re-checks connectivity
                                                viewModel.serverStatus = MainViewModel.ServerStatus.UNKNOWN
                                                viewModel.adminKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                                viewModel.operatorKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                                viewModel.checkServerStatus(url)
                                            }
                                        },
                                        savedAdminKey = savedAdminKey,
                                        savedOperatorKey = savedOperatorKey,
                                        walletRole = walletRole,
                                        onWalletRoleSave = { role, key ->
                                            // Persist role and key; clear the unused key slot
                                            if (role == "admin") {
                                                securePrefs.edit().putString("admin_key", key).putString("operator_key", "").apply()
                                                savedAdminKey = key; savedOperatorKey = ""
                                                viewModel.adminKeyStatus = MainViewModel.AdminKeyStatus.VALID
                                                viewModel.operatorKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                            } else {
                                                securePrefs.edit().putString("operator_key", key).putString("admin_key", "").apply()
                                                savedOperatorKey = key; savedAdminKey = ""
                                                viewModel.operatorKeyStatus = MainViewModel.AdminKeyStatus.VALID
                                                viewModel.adminKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                            }
                                            prefs.edit().putString("wallet_role", role).apply()
                                            walletRole = role
                                            viewModel.walletRole = role
                                        },
                                        onWalletDelete = {
                                            prefs.edit().remove("wallet_role").apply()
                                            walletRole = ""
                                            viewModel.walletRole = ""
                                        },
                                        savedInitialMasterKey = savedInitialMasterKey,
                                        onInitialMasterKeySave = { key ->
                                            securePrefs.edit().putString("initial_master_key", key).apply()
                                            savedInitialMasterKey = key
                                            viewModel.initialMasterKeyHex = key
                                        },
                                        savedPinataJwt = savedPinataJwt,
                                        onPinataJwtSave = { jwt ->
                                            securePrefs.edit().putString("pinata_jwt", jwt).apply()
                                            savedPinataJwt = jwt
                                            viewModel.pinataJwt = jwt
                                            viewModel.pinataJwtStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                            viewModel.checkPinataJwt(jwt)
                                        },
                                        savedKuboNodeUrl = savedKuboNodeUrl,
                                        onKuboNodeUrlSave = { url ->
                                            securePrefs.edit().putString("kubo_node_url", url).apply()
                                            savedKuboNodeUrl = url
                                            viewModel.kuboNodeUrl = url
                                            viewModel.kuboNodeStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                            viewModel.checkKuboNode(url)
                                        },
                                        requireAuthOnStart = requireAuthOnStart,
                                        onRequireAuthChange = { enabled ->
                                            requireAuthOnStart = enabled
                                            prefs.edit().putBoolean("require_auth_on_start", enabled).apply()
                                        },
                                        hasLockScreen = hasLockScreen,
                                        allowScreenshots = allowScreenshots,
                                        onAllowScreenshotsChange = { enabled ->
                                            allowScreenshots = enabled
                                            prefs.edit().putBoolean("allow_screenshots", enabled).apply()
                                            if (enabled) {
                                                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                            } else {
                                                window.setFlags(
                                                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                                                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                                                )
                                            }
                                        },
                                        notificationsEnabled = notificationsEnabled,
                                        onNotificationsEnabledChange = { enabled ->
                                            notificationsEnabled = enabled
                                            prefs.edit().putBoolean("notifications_enabled", enabled).apply()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Enable NFC foreground dispatch so all NFC intents are delivered to this
     * activity while it is visible, bypassing the normal Android intent routing.
     *
     * Three intent filters are registered (TAG, TECH, NDEF) with null tech-lists
     * to accept all tag technologies; restrictive tech filters can cause missed
     * deliveries on some vendor NFC stacks.
     */
    fun enableNfcDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)
            val filters = arrayOf(
                android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED,
                android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED,
                android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED
            ).map { android.content.IntentFilter(it) }.toTypedArray()
            // Accept all NFC tag intents while the activity is in foreground.
            // Restrictive tech filters can prevent delivery on some vendor stacks.
            adapter.enableForegroundDispatch(this, pendingIntent, filters, null)
            Log.d("NFC", "Foreground dispatch enabled, writeTagStep=${viewModel.writeTagStep}")
        }
    }

    /** Re-enable or disable NFC dispatch when the user switches tabs.
     *  If the tag-write flow is active (WAIT_TAG), keep NFC dispatch enabled
     *  so the tag tap is delivered even when the Brand tab is open. */
    fun onTabChanged(isScan: Boolean) {
        viewModel.isScanTabActive = isScan
        if (isScan || viewModel.writeTagStep == WriteTagStep.WAIT_TAG) {
            enableNfcDispatch()
            if (isScan && viewModel.verifyStep == null) viewModel.scanState = ScanState.SCANNING
        } else {
            nfcAdapter?.disableForegroundDispatch(this)
            Log.d("NFC", "Foreground dispatch disabled (non-scan tab)")
        }
    }

    /** Re-enables NFC dispatch when returning from background.
     *  Enabled if on Scan tab OR if the tag-write flow is waiting for a tap. */
    override fun onResume() {
        super.onResume()
        if (viewModel.isScanTabActive || viewModel.writeTagStep == WriteTagStep.WAIT_TAG) {
            enableNfcDispatch()
        }
        if (viewModel.isScanTabActive && viewModel.verifyStep == null) {
            viewModel.scanState = ScanState.SCANNING
        }
    }

    /**
     * Disable NFC foreground dispatch when the activity is no longer in the foreground,
     * so NFC intents fall through to normal Android routing.
     */
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        Log.d("NFC", "Foreground dispatch disabled")
    }

    /**
     * Called when a new NFC intent is delivered to the already-running activity
     * (FLAG_ACTIVITY_SINGLE_TOP ensures the activity is reused rather than recreated).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isNfcIntent = intent.action in listOf(
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED
        )
        Log.d("NFC", "onNewIntent: action=${intent.action}, isScanTab=${viewModel.isScanTabActive}, writeTagStep=${viewModel.writeTagStep}")
        if (isNfcIntent && !viewModel.isScanTabActive && viewModel.writeTagStep != WriteTagStep.WAIT_TAG) {
            Log.d("NFC", "NFC intent ignored: not on Scan tab and no write flow active")
            return
        }
        handleIntent(intent)
    }

    /**
     * Route an incoming intent to the appropriate handler.
     *
     * Routing logic:
     *   1. If a write-flow is waiting for a tag (WAIT_TAG), hand the Tag object
     *      directly to [MainViewModel.onTagTapped] and return.
     *   2. Otherwise, attempt to extract SUN parameters from the NDEF URL
     *      (via [NfcReader.extractSunParams] for NFC intents, or
     *      [NfcReader.parseSunUrl] for ACTION_VIEW deep-links) and pass them
     *      to [MainViewModel.onSunParamsReceived].
     *   3. If no SUN parameters can be extracted, the intent is silently ignored.
     *
     * @param intent The NFC or deep-link intent to handle
     */
    private fun handleIntent(intent: Intent) {
        // Extract the Tag object in an API-level-safe way (getParcelableExtra deprecated in API 33)
        val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        Log.d("NFC", "handleIntent: tag=${tag != null}, writeTagStep=${viewModel.writeTagStep}")

        // If write flow is active, route the tag to the single entry point
        if (tag != null && viewModel.writeTagStep == WriteTagStep.WAIT_TAG) {
            Log.d("NFC", "Processing tag for write flow")
            viewModel.onTagTapped(tag)
            return
        }

        // Normal SUN verification flow: try NFC intent first, then deep-link URL
        val sunParams = NfcReader.extractSunParams(intent) ?: run {
            if (intent.action == Intent.ACTION_VIEW) {
                intent.dataString?.let { url -> NfcReader.parseSunUrl(url) }
            } else null
        } ?: return
        viewModel.onSunParamsReceived(sunParams.e, sunParams.m, sunParams.asset, sunParams.rawUrl)
    }
}

// ============================================================
// Root Composable
// ============================================================

/**
 * Root Composable that owns the bottom navigation scaffold and all full-screen
 * overlays (verify, write tag, issue, transfer, send, receive).
 *
 * Overlay priority (highest to lowest): mnemonic backup, program-tag form,
 * receive, send, write tag, verify, transfer, issue/revoke, then the main
 * bottom-nav scaffold.
 *
 * Settings are passed down as lambdas rather than direct ViewModel writes so
 * that SharedPreferences persistence is co-located with the Compose state.
 *
 * @param viewModel              Shared ViewModel
 * @param brandName              Brand display name shown in the Brand tab header
 * @param verifyUrl              Current backend base URL (HTTPS only)
 * @param savedAdminKey          Admin key loaded from EncryptedSharedPreferences
 * @param savedInitialMasterKey  Initial NTAG 424 master key (hex, default empty = all-zero)
 * @param savedOperatorKey       Operator key for limited-access operations
 * @param savedPinataJwt         Pinata JWT for direct IPFS pin uploads
 * @param savedKuboNodeUrl       Kubo node URL for self-hosted IPFS uploads
 * @param onLangChange           Persist and apply a new UI language code
 * @param onVerifyUrlSave        Persist and apply a new backend URL (HTTPS only)
 * @param walletRole             Current wallet access role: "admin", "operator", or ""
 * @param onWalletRoleSave       Persist a validated control key and its role
 * @param onInitialMasterKeySave Persist a new initial NTAG 424 master key
 * @param onPinataJwtSave        Persist and validate a new Pinata JWT
 * @param onKuboNodeUrlSave      Persist and validate a new Kubo node URL
 * @param requireAuthOnStart     Whether biometric auth is required on next launch
 * @param onRequireAuthChange    Persist the biometric auth preference
 * @param hasLockScreen          Whether the device has biometric/credential hardware enrolled
 * @param allowScreenshots       Whether FLAG_SECURE is currently cleared
 * @param onAllowScreenshotsChange Toggle FLAG_SECURE and persist the preference
 */
@Composable
fun RavenTagApp(
    viewModel: MainViewModel,
    verifyUrl: String,
    savedAdminKey: String,
    savedInitialMasterKey: String = "",
    savedOperatorKey: String = "",
    savedPinataJwt: String = "",
    savedKuboNodeUrl: String = "",
    walletRole: String = "",
    onLangChange: (String) -> Unit,
    onVerifyUrlSave: (String) -> Unit,
    onWalletRoleSave: (role: String, key: String) -> Unit = { _, _ -> },
    onWalletDelete: () -> Unit = {},
    onInitialMasterKeySave: (String) -> Unit = {},
    onPinataJwtSave: (String) -> Unit = {},
    onKuboNodeUrlSave: (String) -> Unit = {},
    requireAuthOnStart: Boolean = true,
    onRequireAuthChange: (Boolean) -> Unit = {},
    hasLockScreen: Boolean = true,
    allowScreenshots: Boolean = false,
    onAllowScreenshotsChange: (Boolean) -> Unit = {},
    notificationsEnabled: Boolean = true,
    onNotificationsEnabledChange: (Boolean) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? MainActivity
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val nfcSupported = nfcAdapter != null
    val nfcEnabled = nfcAdapter?.isEnabled == true
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val s = LocalStrings.current
    var currentTab by remember { mutableStateOf(AppTab.SCAN) }

    fun switchTab(tab: AppTab) {
        currentTab = tab
        activity?.onTabChanged(tab == AppTab.SCAN)
    }
    val verifyStep = viewModel.verifyStep
    val issueMode = viewModel.issueMode

    // If the user leaves the Wallet tab while in restore mode (without completing wallet creation),
    // reset restore mode so screenshots are re-enabled (nothing sensitive is visible anymore).
    LaunchedEffect(currentTab) {
        if (currentTab != AppTab.WALLET && viewModel.restoreModeActive && !viewModel.hasWallet) {
            viewModel.restoreModeActive = false
        }
    }

    // Sync IPFS credentials and initial master key into the ViewModel whenever they change
    LaunchedEffect(savedPinataJwt, savedKuboNodeUrl) {
        viewModel.pinataJwt = savedPinataJwt
        viewModel.kuboNodeUrl = savedKuboNodeUrl
    }

    // Enable NFC dispatch whenever the write flow starts, even if the Brand tab is active.
    // Without this, switching to Brand tab disables NFC and the WAIT_TAG tap would never arrive.
    LaunchedEffect(viewModel.writeTagStep) {
        if (viewModel.writeTagStep == WriteTagStep.WAIT_TAG) {
            activity?.enableNfcDispatch()
        }
    }
    LaunchedEffect(savedInitialMasterKey) {
        viewModel.initialMasterKeyHex = savedInitialMasterKey
    }

    // Wallet polling loop: runs continuously in the background once the wallet exists.
    // Kept outside the tab `when` so tab switches never cancel or restart it, avoiding
    // the visual "reconnecting" flash every time the user returns to the Wallet tab.
    LaunchedEffect(viewModel.hasWallet) {
        if (viewModel.hasWallet) {
            viewModel.checkElectrumStatus()
            while (true) {
                viewModel.fetchBlockHeight()
                viewModel.fetchRvnPrice()
                viewModel.fetchNetworkHashrate()
                viewModel.refreshBalance()
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    // ── No-funds warning dialog ───────────────────────────────────────────────
    // Shown when the user tries to issue an asset but the wallet RVN balance is zero.
    if (viewModel.showNoFundsWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.showNoFundsWarning = false; viewModel.pendingIssueMode = null },
            containerColor = RavenCard,
            title = { Text(s.brandNoFundsTitle, color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(s.brandNoFundsMsg, color = RavenMuted, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.showNoFundsWarning = false
                        // User acknowledged the warning: proceed to the issue screen anyway
                        viewModel.issueMode = viewModel.pendingIssueMode
                        viewModel.pendingIssueMode = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RavenOrange)
                ) { Text(s.brandNoFundsContinue) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.showNoFundsWarning = false; viewModel.pendingIssueMode = null },
                    border = androidx.compose.foundation.BorderStroke(1.dp, RavenBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text(s.walletCancelBtn) }
            }
        )
    }

    /**
     * Check the wallet balance before navigating to an issue screen.
     * If the balance is zero, show the no-funds warning dialog instead
     * of opening the issue screen directly.
     */
    fun checkAndIssue(mode: IssueMode) {
        val balance = viewModel.walletInfo?.balanceRvn ?: 0.0
        if (viewModel.hasWallet && balance <= 0.0) {
            viewModel.pendingIssueMode = mode
            viewModel.showNoFundsWarning = true
        } else {
            viewModel.issueMode = mode
        }
    }

    // ── Mnemonic backup overlay ───────────────────────────────────────────────
    // Shown once immediately after wallet generation, before the wallet is persisted.
    val pendingMnemonic = viewModel.pendingMnemonic
    if (pendingMnemonic != null) {
        MnemonicBackupScreen(
            mnemonic = pendingMnemonic,
            onConfirmed = { viewModel.confirmMnemonicBackup() }
        )
        return
    }

    // ── Program tag form overlay ──────────────────────────────────────────────
    // Full-screen form for selecting an asset and starting a standalone write.
    if (viewModel.showProgramTagForm) {
        ProgramTagScreen(
            verifyUrl = viewModel.currentVerifyUrl,
            // Use admin key for admin role, operator key otherwise
            savedAdminKey = if (walletRole == "admin") savedAdminKey else savedOperatorKey,
            ownedAssets = viewModel.ownedAssets ?: emptyList(),
            onBack = { viewModel.showProgramTagForm = false },
            onStart = { assetName, url, adminKey -> viewModel.startStandaloneTagWrite(assetName, url, adminKey) }
        )
        return
    }

    // ── Receive overlay ───────────────────────────────────────────────────────
    if (viewModel.showReceive) {
        ReceiveScreen(
            address = viewModel.walletInfo?.address ?: "",
            onBack = { viewModel.showReceive = false }
        )
        return
    }

    // ── Send RVN overlay ──────────────────────────────────────────────────────
    if (viewModel.showSend) {
        SendRvnScreen(
            isLoading = viewModel.sendLoading,
            resultMessage = viewModel.sendResult,
            resultSuccess = viewModel.sendSuccess,
            feeUnavailable = viewModel.sendFeeUnavailable,
            prefillAddress = if (viewModel.donateMode) viewModel.donateAddress else "",
            donateMode = viewModel.donateMode,
            walletBalance = viewModel.walletInfo?.balanceRvn ?: 0.0,
            onBack = {
                viewModel.showSend = false
                viewModel.donateMode = false
                viewModel.sendResult = null
                viewModel.sendSuccess = null
                viewModel.sendFeeUnavailable = false
            },
            onSend = viewModel::sendRvn
        )
        return
    }

    // ── Write tag overlay ─────────────────────────────────────────────────────
    // Shown during both standalone and issue+write flows.
    val writeStep = viewModel.writeTagStep
    if (writeStep != null) {
        WriteTagScreen(
            step = writeStep,
            assetName = viewModel.writeTagAssetName,
            errorMessage = viewModel.writeTagError,
            // Only show the derived keys to admin users (not operator users)
            successKeys = if (walletRole == "admin") viewModel.writeTagKeys else null,
            onCancel = { viewModel.cancelWriteTagFlow() }
        )
        return
    }

    // ── Verify overlay ────────────────────────────────────────────────────────
    // Full-screen result shown after an NFC scan triggers the SUN verification flow.
    if (verifyStep != null) {
        VerifyScreen(
            step = verifyStep,
            result = viewModel.verifyResult,
            onScanAgain = { viewModel.resetToScan() }
        )
        return
    }

    // Register chip is now integrated into the "Program NFC Tag" flow, no separate overlay needed.

    // ── Transfer overlay ──────────────────────────────────────────────────────
    // Handles token transfers, root-asset transfers, and sub-asset transfers.
    if (issueMode == IssueMode.TRANSFER || issueMode == IssueMode.TRANSFER_ROOT || issueMode == IssueMode.TRANSFER_SUB) {
        TransferScreen(
            isLoading = viewModel.issueLoading,
            resultMessage = viewModel.issueResult,
            resultSuccess = viewModel.issueSuccess,
            mode = issueMode,
            prefilledAssetName = viewModel.prefilledTransferAssetName,
            showLowRvnWarning = !AppConfig.IS_BRAND_APP && (viewModel.walletInfo?.balanceRvn ?: 0.0) < 0.01,
            onBack = { viewModel.issueMode = null; viewModel.clearIssueResult() },
            onTransfer = { assetName, toAddress, qty ->
                viewModel.transferAssetConsumer(assetName, toAddress, qty)
            }
        )
        return
    }

    // ── Issue / revoke overlay ────────────────────────────────────────────────
    val walletAddress = viewModel.walletInfo?.address ?: ""
    if (issueMode != null) {
        IssueAssetScreen(
            mode = issueMode,
            isLoading = viewModel.issueLoading,
            resultMessage = viewModel.issueResult,
            resultSuccess = viewModel.issueSuccess,
            prefilledAddress = walletAddress,
            ownedAssets = viewModel.ownedAssets ?: emptyList(),
            savedAdminKey = savedAdminKey,
            savedKuboNodeUrl = savedKuboNodeUrl,
            onBack = { viewModel.issueMode = null; viewModel.clearIssueResult() },
            onIssueRoot = viewModel::issueRootAsset,
            onIssueSub = viewModel::issueSubAsset,
            onIssueUnique = { parentSub, serial, toAddress, ipfsHash, description ->
                viewModel.issueUniqueToken(parentSub, serial, toAddress, ipfsHash)
            },
            onIssueUniqueAndWriteTag = { parentSub, serial, toAddress, ipfsHash, description ->
                viewModel.startIssueUniqueAndWriteTag(parentSub, serial, toAddress, ipfsHash, description, savedAdminKey)
            },
            onRevoke = { assetName, reason, adminKey -> viewModel.revokeAsset(assetName, reason, adminKey) },
            onUnrevoke = { assetName, adminKey -> viewModel.unrevokeAsset(assetName, adminKey) },
            pinataJwtValidated = viewModel.pinataJwtStatus == MainViewModel.AdminKeyStatus.VALID,
            kuboNodeValidated = viewModel.kuboNodeStatus == MainViewModel.AdminKeyStatus.VALID,
            savedPinataJwt = savedPinataJwt
        )
        return
    }

    // ── Main bottom-nav scaffold ──────────────────────────────────────────────
    val isBrandApp = AppConfig.IS_BRAND_APP
    val brandTabLabel = s.navBrand
    val navColors = NavigationBarItemDefaults.colors(
        selectedIconColor = RavenOrange, selectedTextColor = RavenOrange,
        unselectedIconColor = RavenMuted, unselectedTextColor = RavenMuted,
        indicatorColor = RavenOrange.copy(alpha = 0.1f)
    )

    Scaffold(
        containerColor = RavenBg,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111111), tonalElevation = 0.dp) {
                // Scan tab: consumer NFC verify flow
                NavigationBarItem(selected = currentTab == AppTab.SCAN, onClick = { switchTab(AppTab.SCAN) },
                    icon = { Icon(Icons.Default.Nfc, contentDescription = null) },
                    label = { Text(s.navScan) }, colors = navColors)
                // Wallet tab: balance, assets, send/receive
                NavigationBarItem(selected = currentTab == AppTab.WALLET, onClick = { switchTab(AppTab.WALLET) },
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                    label = { Text(s.navWallet) }, colors = navColors)
                // Brand tab: shown only in the brand-flavour APK
                if (isBrandApp) {
                    NavigationBarItem(selected = currentTab == AppTab.BRAND, onClick = { switchTab(AppTab.BRAND) },
                        icon = { Icon(Icons.Default.Business, contentDescription = null) },
                        label = { Text(brandTabLabel, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        colors = navColors)
                }
                // Settings tab: language, backend URL, keys, auth preferences
                NavigationBarItem(selected = currentTab == AppTab.SETTINGS, onClick = { switchTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(s.navSettings) }, colors = navColors)
            }
        }
    ) { innerPadding ->
        when (currentTab) {
            // ── Scan tab ──────────────────────────────────────────────────────
            AppTab.SCAN -> ScanScreen(
                modifier = Modifier.padding(innerPadding),
                scanState = viewModel.scanState,
                errorMessage = viewModel.errorMessage,
                nfcSupported = nfcSupported,
                nfcEnabled = nfcEnabled,
                onStartScan = { viewModel.scanState = ScanState.SCANNING }
            )

            // ── Wallet tab ────────────────────────────────────────────────────
            AppTab.WALLET -> {
                WalletScreen(
                    modifier = Modifier.padding(innerPadding),
                    walletInfo = viewModel.walletInfo,
                    hasWallet = viewModel.hasWallet,
                    isGenerating = viewModel.walletGenerating,
                    ownedAssets = viewModel.ownedAssets,
                    assetsLoading = viewModel.assetsLoading,
                    assetsLoadError = viewModel.assetsLoadError,
                    electrumStatus = viewModel.electrumStatus,
                    blockHeight = viewModel.blockHeight,
                    rvnPrice = viewModel.rvnPrice,
                    networkHashrate = viewModel.networkHashrate,
                    walletRole = walletRole,
                    controlKeyValidating = viewModel.controlKeyValidating,
                    controlKeyError = viewModel.controlKeyError,
                    onGenerateWallet = { controlKey ->
                        if (!io.raventag.app.config.AppConfig.IS_BRAND_APP) {
                            viewModel.generateWallet()
                        } else {
                            scope.launch {
                                viewModel.controlKeyValidating = true
                                viewModel.controlKeyError = null
                                val role = viewModel.validateControlKey(viewModel.currentVerifyUrl, controlKey)
                                viewModel.controlKeyValidating = false
                                if (role == null) {
                                    viewModel.controlKeyError = s.walletControlKeyInvalid
                                } else {
                                    onWalletRoleSave(role, controlKey)
                                    viewModel.generateWallet()
                                }
                            }
                        }
                    },
                    onRestoreWallet = { mnemonic, controlKey ->
                        if (!io.raventag.app.config.AppConfig.IS_BRAND_APP) {
                            viewModel.restoreWallet(mnemonic)
                        } else {
                            scope.launch {
                                viewModel.controlKeyValidating = true
                                viewModel.controlKeyError = null
                                val role = viewModel.validateControlKey(viewModel.currentVerifyUrl, controlKey)
                                viewModel.controlKeyValidating = false
                                if (role == null) {
                                    viewModel.controlKeyError = s.walletControlKeyInvalid
                                } else {
                                    onWalletRoleSave(role, controlKey)
                                    viewModel.restoreWallet(mnemonic)
                                }
                            }
                        }
                    },
                    onRefreshBalance = {
                        viewModel.checkElectrumStatus()
                        viewModel.fetchBlockHeight()
                        viewModel.fetchRvnPrice()
                        viewModel.fetchNetworkHashrate()
                        viewModel.refreshBalance()
                    },
                    onDeleteWallet = {
                        viewModel.deleteWallet()
                        onWalletDelete()
                    },
                    onReceive = { viewModel.showReceive = true },
                    onSend = { viewModel.showSend = true },
                    onTransferAsset = { asset ->
                        viewModel.prefilledTransferAssetName = asset.name
                        // Choose transfer mode based on asset type
                        viewModel.issueMode = when (asset.type) {
                            io.raventag.app.ravencoin.AssetType.ROOT -> IssueMode.TRANSFER_ROOT
                            io.raventag.app.ravencoin.AssetType.SUB -> IssueMode.TRANSFER_SUB
                            io.raventag.app.ravencoin.AssetType.UNIQUE -> IssueMode.TRANSFER
                        }
                    },
                    walletBalance = viewModel.walletInfo?.balanceRvn ?: 0.0,
                    txHistory = viewModel.txHistory,
                    txHistoryLoading = viewModel.txHistoryLoading,
                    txHistoryTotal = viewModel.txHistoryTotal,
                    txHistoryLoadedCount = viewModel.txHistoryLoadedCount,
                    onLoadMoreTransactions = { viewModel.loadMoreTransactions() },
                    onRestoreModeChange = { restoreActive ->
                        viewModel.restoreModeActive = restoreActive
                    }
                )
            }

            // ── Brand tab ─────────────────────────────────────────────────────
            AppTab.BRAND -> {
                // Auto-check server and key statuses when landing on Brand tab
                LaunchedEffect(Unit) {
                    if (viewModel.serverStatus == MainViewModel.ServerStatus.UNKNOWN)
                        viewModel.checkServerStatus(viewModel.currentVerifyUrl)
                }
                LaunchedEffect(viewModel.serverStatus) {
                    if (viewModel.serverStatus == MainViewModel.ServerStatus.ONLINE) {
                        if (viewModel.pinataJwtStatus == MainViewModel.AdminKeyStatus.UNKNOWN)
                            viewModel.checkPinataJwt(savedPinataJwt)
                        if (viewModel.kuboNodeStatus == MainViewModel.AdminKeyStatus.UNKNOWN)
                            viewModel.checkKuboNode(savedKuboNodeUrl)
                    }
                }
                BrandDashboardScreen(
                modifier = Modifier.padding(innerPadding),
                hasWallet = viewModel.hasWallet,
                serverStatus = viewModel.serverStatus,
                walletRole = walletRole,
                onIssueAsset = { checkAndIssue(IssueMode.ROOT_ASSET) },
                onIssueSubAsset = { checkAndIssue(IssueMode.SUB_ASSET) },
                onIssueUnique = { checkAndIssue(IssueMode.UNIQUE_TOKEN) },
                onRevokeAsset = { viewModel.issueMode = IssueMode.REVOKE },
                onUnrevokeAsset = { viewModel.issueMode = IssueMode.UNREVOKE },
                onGoToWallet = { switchTab(AppTab.WALLET) }
            )
            }

            // ── Settings tab ──────────────────────────────────────────────────
            AppTab.SETTINGS -> {
                LaunchedEffect(Unit) {
                    if (viewModel.serverStatus == MainViewModel.ServerStatus.UNKNOWN) {
                        viewModel.checkServerStatus(viewModel.currentVerifyUrl)
                    }
                }
                // Auto-check admin key whenever server becomes Online (brand app only)
                LaunchedEffect(viewModel.serverStatus) {
                    if (viewModel.serverStatus == MainViewModel.ServerStatus.ONLINE) {
                        if (viewModel.pinataJwtStatus == MainViewModel.AdminKeyStatus.UNKNOWN)
                            viewModel.checkPinataJwt(savedPinataJwt)
                        if (viewModel.kuboNodeStatus == MainViewModel.AdminKeyStatus.UNKNOWN)
                            viewModel.checkKuboNode(savedKuboNodeUrl)
                    }
                }
                SettingsScreen(
                    modifier = Modifier.padding(innerPadding),
                    // Map AppStrings instance back to a language code for the selector UI
                    currentLang = when (s) {
                        stringsIt -> "it"; stringsFr -> "fr"; stringsDe -> "de"; stringsEs -> "es"
                        stringsZh -> "zh"; stringsJa -> "ja"; stringsKo -> "ko"; stringsRu -> "ru"
                        else -> "en"
                    },
                    currentVerifyUrl = viewModel.currentVerifyUrl,
                    currentInitialMasterKey = savedInitialMasterKey,
                    currentPinataJwt = savedPinataJwt,
                    currentKuboNodeUrl = savedKuboNodeUrl,
                    onPinataJwtSave = onPinataJwtSave,
                    onKuboNodeUrlSave = onKuboNodeUrlSave,
                    serverStatus = viewModel.serverStatus,
                    pinataJwtStatus = viewModel.pinataJwtStatus,
                    kuboNodeStatus = viewModel.kuboNodeStatus,
                    onLangChange = onLangChange,
                    onVerifyUrlSave = onVerifyUrlSave,
                    onInitialMasterKeySave = onInitialMasterKeySave,
                    onDonate = {
                        viewModel.donateMode = true
                        viewModel.showSend = true
                    },
                    walletBalance = viewModel.walletInfo?.balanceRvn ?: 0.0,
                    hasWallet = viewModel.hasWallet,
                    requireAuthOnStart = requireAuthOnStart,
                    onRequireAuthChange = onRequireAuthChange,
                    hasLockScreen = hasLockScreen,
                    allowScreenshots = allowScreenshots,
                    onAllowScreenshotsChange = onAllowScreenshotsChange,
                    notificationsEnabled = notificationsEnabled,
                    onNotificationsEnabledChange = onNotificationsEnabledChange
                )
            }
        }
    }
}
