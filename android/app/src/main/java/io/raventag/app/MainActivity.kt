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
import io.raventag.app.utils.RetryUtils
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import io.raventag.app.wallet.RavencoinPublicNode
import io.raventag.app.wallet.RavencoinTxBuilder
import io.raventag.app.wallet.SubAssetIssueParams
import io.raventag.app.wallet.WalletManager
import io.raventag.app.security.AdminKeyStorage
import io.raventag.app.config.AppConfig
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.raventag.app.worker.NotificationHelper
import io.raventag.app.worker.TransactionNotificationHelper
import io.raventag.app.worker.WalletPollingWorker
import java.util.concurrent.TimeUnit

// ============================================================
// ViewModel
// ============================================================

sealed class IssueStep {
    object Idle : IssueStep()
    data class InProgress(val step: StepName) : IssueStep()
    data class Success(val step: StepName) : IssueStep()
    data class Failed(val step: StepName, val error: String, val canRetry: Boolean) : IssueStep()

    enum class StepName {
        IPFS_UPLOAD,
        BALANCE_CHECK,
        NAME_CHECK,
        ISSUING,
        CONFIRMING,
        NFC_PROGRAMMING
    }
}

@Composable
private fun TabLayer(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 0f else -1f)
            .alpha(if (visible) 1f else 0f)
    ) {
        content()
        if (!visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }
    }
}

enum class WarningType { INSUFFICIENT_BALANCE, DUPLICATE_NAME }

private fun ravencoinAssetNameLengthError(assetName: String): String? {
    val max = RavencoinTxBuilder.MAX_ASSET_NAME_LENGTH
    if (assetName.length <= max) return null
    return "Asset name too long (${assetName.length}/$max): $assetName. Ravencoin requires the full name to be at most $max characters."
}

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

    /** Encrypted storage for the admin key; injected via [initWallet]. */
    internal var adminKeyStorage: AdminKeyStorage? = null

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

    // ── Async error display state (per 20-UI-SPEC.md Error State Patterns) ─────
    // These two properties back the top-level banner + dialog shown by
    // [RavenTagApp]. Transient errors (timeout, network) auto-dismiss after a
    // few seconds; critical errors are modal and require an explicit OK tap.

    /** Transient error message shown as a dismissible banner with a Retry action. */
    var transientError by mutableStateOf<String?>(null)

    /** Critical error shown as a modal AlertDialog requiring user intervention. */
    var criticalError by mutableStateOf<String?>(null)

    /**
     * Classify [throwable] via [RetryUtils.isTransientError] and surface it to the
     * user through the appropriate UI pattern. Transient failures trigger a banner
     * that auto-dismisses after 5 seconds; anything else becomes a modal dialog
     * so the user explicitly acknowledges the failure.
     */
    fun reportAsyncError(throwable: Throwable, prefix: String? = null) {
        val full = if (prefix != null) "$prefix: ${throwable.message ?: "Unknown error"}" else (throwable.message ?: "Unknown error")
        val isTransient = throwable is Exception && RetryUtils.isTransientError(throwable)
        if (isTransient) showTransientError(full) else showCriticalError(full)
    }

    /** Show a transient error banner that auto-dismisses after 5 seconds. */
    fun showTransientError(message: String) {
        transientError = message
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            // Only clear if the user has not already dismissed (value could have
            // changed to a newer message during the delay).
            if (transientError == message) transientError = null
        }
    }

    /** Show a critical error dialog that requires explicit dismissal. */
    fun showCriticalError(message: String) {
        criticalError = message
    }

    /** Clear the transient error banner (called from the banner Dismiss button). */
    fun clearTransientError() {
        transientError = null
    }

    /** Clear the critical error dialog (called from the dialog OK button). */
    fun clearCriticalError() {
        criticalError = null
    }

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

    /** Error from the last failed wallet restore (invalid mnemonic, network failure). */
    var restoreError by mutableStateOf<String?>(null)


    // ── Transaction details (per D-04) ────────────────────────────────────────

    /** Transaction ID for transaction details screen (per D-04) */
    var viewingTxid by mutableStateOf<String?>(null)

    /** True when viewing transaction details overlay */
    var isViewingTransaction by mutableStateOf(false)

    // ── Issue / revoke / register / transfer state ────────────────────────────

    /** Currently active issue/revoke/transfer mode (null = no overlay shown). */
    var issueMode by mutableStateOf<IssueMode?>(null)

    /** True while an issue, revoke, or transfer operation is in progress. */
    var issueLoading by mutableStateOf(false)

    /** Human-readable result message shown after an issue/revoke/transfer attempt. */
    var issueResult by mutableStateOf<String?>(null)

    /** True = last operation succeeded, false = failed, null = not yet run. */
    var issueSuccess by mutableStateOf<Boolean?>(null)

    /** Phase 40: Current step in the multi-step issuance flow. */
    var issueStep by mutableStateOf<IssueStep>(IssueStep.Idle)

    /** Phase 40: Transaction ID of the most recently issued asset (for explorer link). */
    var issuedTxid by mutableStateOf<String?>(null)

    /** Phase 40: Inline pre-issuance warning type (null = no warning). */
    var warningType by mutableStateOf<WarningType?>(null)

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

    fun handleViewTransactionIntent(txid: String) {
        viewingTxid = txid
        isViewingTransaction = true
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
                try { io.raventag.app.wallet.RavencoinPublicNode(getApplication()).ping() } catch (_: Exception) { false }
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
                try { io.raventag.app.wallet.RavencoinPublicNode(getApplication()).getBlockHeight() }
                catch (_: Exception) { null }
            }
            if (h != null) {
                blockHeight = h
                // Persist so the next cold start renders the chain-tip pill instantly
                try { io.raventag.app.wallet.cache.WalletCacheDao.writeBlockHeight(h) } catch (_: Throwable) {}
            }
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

    /**
     * Validate the admin key against the backend using OkHttp.
     *
     * Makes an actual API call to the backend validation endpoint and returns
     * the validation status. This is a suspend function intended to be called
     * from coroutines.
     *
     * @param key The admin key to validate.
     * @param apiBaseUrl The backend API base URL.
     * @return The validation status (VALID, INVALID, WRONG_TYPE, or INVALID on error).
     */
    suspend fun validateAdminKey(key: String, apiBaseUrl: String): AdminKeyStatus {
        adminKeyStatus = AdminKeyStatus.CHECKING
        return try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("$apiBaseUrl/api/admin/validate-key")
                .header("X-Admin-Key", key)
                .get()
                .build()
            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> AdminKeyStatus.VALID
                401 -> AdminKeyStatus.INVALID
                403 -> AdminKeyStatus.WRONG_TYPE
                else -> AdminKeyStatus.INVALID
            }
        } catch (e: Exception) {
            AdminKeyStatus.INVALID
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
    private val txHistoryPageSize = 20

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

    /** True when portfolio scan found funds on old addresses that need consolidation. */
    var needsConsolidation by mutableStateOf(false)

    /** True while consolidation is in progress (prevents banner from reappearing). */
    var consolidationInProgress by mutableStateOf(false)

    /** True while automatic sweep is running (shows progress banner instead of manual button). */
    var autoSweepInProgress by mutableStateOf(false)

    /**
     * Load the asset portfolio for the wallet address.
     *
     * Two-phase loading for responsive UI:
     *   Phase 1: fetch the basic asset list immediately and display it.
     *   Phase 2: enrich each asset with IPFS metadata in parallel (max 4 concurrent
     *            requests via a semaphore) and update the list progressively.
     */
    /**
     * Lightweight cache entry: strips heavy fields (imageUrl, description)
     * to keep JSON under SharedPreferences 10240-byte limit with 24+ assets.
     */
    private data class CachedAsset(val name: String, val balance: Double, val typeOrdinal: Int, val ipfsHash: String?)

    private data class StartupWalletCache(
        val address: String,
        val balanceRvn: Double?,
        val blockHeight: Int?,
        val txHistory: List<io.raventag.app.wallet.TxHistoryEntry>,
        val assets: List<OwnedAsset>?
    )

    private fun saveAssetsCache(assets: List<OwnedAsset>) {
        try {
            val addr = walletManager?.getCurrentAddress() ?: return
            val cached = assets.map { CachedAsset(it.name, it.balance, it.type.ordinal, it.ipfsHash) }
            val json = com.google.gson.Gson().toJson(cached)
            getApplication<Application>().getSharedPreferences("raventag_assets_cache", Application.MODE_PRIVATE)
                .edit().putString(addr, json).apply()
        } catch (_: Exception) {}
    }

    private fun loadAssetsCache(): List<OwnedAsset>? {
        return try {
            val addr = walletManager?.getCurrentAddress() ?: return null
            loadAssetsCacheForAddress(addr)
        } catch (_: Exception) { null }
    }

    private fun loadAssetsCacheForAddress(addr: String): List<OwnedAsset>? {
        return try {
            val prefs = getApplication<Application>().getSharedPreferences("raventag_assets_cache", Application.MODE_PRIVATE)
            val json = prefs.getString(addr, null) ?: return null
            val listType = object : com.google.gson.reflect.TypeToken<List<CachedAsset>>() {}.type
            val cached = com.google.gson.Gson().fromJson<List<CachedAsset>>(json, listType)
            cached.map { ca ->
                OwnedAsset(
                    name = ca.name,
                    balance = ca.balance,
                    type = io.raventag.app.ravencoin.AssetType.entries[ca.typeOrdinal],
                    ipfsHash = ca.ipfsHash,
                    imageUrl = null,
                    description = null
                )
            }
        } catch (_: Exception) { null }
    }

    private fun mapCachedTxRows(
        rows: List<io.raventag.app.wallet.cache.TxHistoryDao.TxHistoryRow>
    ): List<io.raventag.app.wallet.TxHistoryEntry> {
        return rows.map { row ->
            io.raventag.app.wallet.TxHistoryEntry(
                txid = row.txid,
                height = row.height,
                confirmations = row.confirms,
                amountSat = row.amountSat,
                sentSat = row.sentSat,
                cycledSat = row.cycledSat,
                feeSat = row.feeSat,
                isIncoming = row.isIncoming,
                isSelfTransfer = row.isSelf,
                timestamp = row.timestamp,
                isIssuance = row.isIssuance,
                issuanceBurnSat = row.issuanceBurnSat
            )
        }
    }

    private suspend fun loadStartupWalletCache(wm: WalletManager): StartupWalletCache = withContext(Dispatchers.IO) {
        coroutineScope {
            val cachedStateDeferred = async {
                try { io.raventag.app.wallet.cache.WalletCacheDao.readState() } catch (_: Throwable) { null }
            }
            val cachedAddressDeferred = async {
                try { wm.getCurrentAddress() } catch (_: Throwable) { null }.orEmpty()
            }
            val cachedTxDeferred = async {
                try {
                    mapCachedTxRows(io.raventag.app.wallet.cache.TxHistoryDao.getPage(offset = 0, limit = 50))
                } catch (_: Throwable) {
                    emptyList()
                }
            }

            val cachedState = cachedStateDeferred.await()
            val cachedAddress = cachedAddressDeferred.await()
            val cachedAssets = if (cachedAddress.isNotEmpty()) loadAssetsCacheForAddress(cachedAddress) else null

            StartupWalletCache(
                address = cachedAddress,
                balanceRvn = cachedState?.balanceSat?.let { it / 1e8 },
                blockHeight = cachedState?.blockHeight?.takeIf { it > 0 },
                txHistory = cachedTxDeferred.await(),
                assets = cachedAssets
            )
        }
    }

    private fun applyStartupWalletCache(cache: StartupWalletCache) {
        val current = walletInfo
        walletInfo = current?.copy(
            address = cache.address.ifEmpty { current.address },
            balanceRvn = current.balanceRvn ?: cache.balanceRvn,
            isLoading = current.isLoading
        ) ?: WalletInfo(
            address = cache.address,
            balanceRvn = cache.balanceRvn,
            isLoading = true
        )

        if ((blockHeight == null || blockHeight == 0) && cache.blockHeight != null) {
            blockHeight = cache.blockHeight
        }
        if (txHistory.isEmpty() && cache.txHistory.isNotEmpty()) {
            txHistory = cache.txHistory
            txHistoryTotal = cache.txHistory.size
            txHistoryLoadedCount = cache.txHistory.size
        }
        if (ownedAssets.isNullOrEmpty() && !cache.assets.isNullOrEmpty()) {
            ownedAssets = cache.assets
        }
    }

    fun loadOwnedAssets() {
        val wm = walletManager ?: return

        viewModelScope.launch {
            assetsLoadError = false

            // Show the spinner only when there is nothing to display yet.
            // If assets are already on screen (from cache or a previous load), refresh
            // silently in the background so the list never flashes or shows a spinner.
            if (ownedAssets.isNullOrEmpty()) {
                val cached = withContext(Dispatchers.IO) { loadAssetsCache() }
                if (!cached.isNullOrEmpty()) {
                    ownedAssets = cached
                } else {
                    assetsLoading = true
                }
            }

            try {
                // One Keystore decrypt + one pipelined batch for all asset balances.
                val basic = withContext(Dispatchers.IO) {
                    val currentIndex = wm.getCurrentAddressIndex()
                    val addresses = wm.getAddressBatch(0, 0..currentIndex).values.toList()
                    val node = io.raventag.app.wallet.RavencoinPublicNode(getApplication())

                    // Fetch both asset balances and RVN balance in parallel
                    val (totals, _) = coroutineScope {
                        val assetsDeferred = async { node.getTotalAssetBalances(addresses) }
                        val rvnDeferred = async { 
                            try { node.getTotalBalance(addresses) } catch (_: Exception) { 0.0 }
                        }
                        
                        Pair(assetsDeferred.await(), rvnDeferred.await())
                    }
                    
                    // Consolidation banner logic:
                    // Trigger if ANY address BEFORE the current one (0 until currentIndex) holds funds.
                    // The current address (currentIndex) is safe until it spends, and will be
                    // cycled automatically during the next outgoing transaction.
                    if (currentIndex > 0) {
                        val oldAddresses = wm.getAddressBatch(0, 0 until currentIndex).values.toList()
                        var hasOldFunds = try {
                            node.getAddressesWithFunds(oldAddresses).isNotEmpty()
                        } catch (_: Exception) { false }
                        // Secondary check: getAddressesWithFunds relies on get_balance?asset=true
                        // batch. If the server rejects asset=true, all responses come back null and
                        // the fallback only detects RVN, missing asset-only addresses. Use
                        // listunspent (standard ElectrumX, no asset=true needed) as reliable
                        // fallback for addresses the primary check missed.
                        if (!hasOldFunds) {
                            hasOldFunds = try {
                                oldAddresses.any { addr -> node.hasAnyUtxos(addr) }
                            } catch (_: Exception) { false }
                        }
                        needsConsolidation = hasOldFunds
                    } else {
                        needsConsolidation = false
                    }
                    
                    totals.map { (name, amount) ->
                        val type = when {
                            name.contains('#') -> io.raventag.app.ravencoin.AssetType.UNIQUE
                            name.contains('/') -> io.raventag.app.ravencoin.AssetType.SUB
                            else -> io.raventag.app.ravencoin.AssetType.ROOT
                        }
                        io.raventag.app.ravencoin.OwnedAsset(
                            name = name,
                            balance = amount,
                            type = type,
                            ipfsHash = null
                        )
                    }.sortedWith(compareBy({ it.type.ordinal }, { it.name }))
                }

                // Merge balances with already-loaded metadata so images never disappear on refresh.
                // IPFS content is immutable: same CID always serves the same image, so cached
                // imageUrl and description can be reused without re-fetching.
                val previous = ownedAssets?.associateBy { it.name } ?: emptyMap()
                val merged = basic.map { asset ->
                    val prev = previous[asset.name]
                    if (prev?.imageUrl != null) {
                        asset.copy(ipfsHash = prev.ipfsHash, imageUrl = prev.imageUrl, description = prev.description)
                    } else {
                        asset
                    }
                }
                // Avoid wiping the visible asset list when a transient network error
                // returns an empty `basic`. Keep the previous list visible.
                if (merged.isNotEmpty() || ownedAssets.isNullOrEmpty()) {
                    ownedAssets = merged
                    saveAssetsCache(merged)
                }
                assetsLoading = false

                // Launch auto-sweep from detection result (no race with fixed delay)
                if (needsConsolidation && !consolidationInProgress) {
                    launchAutoSweep(wm)
                }

                // Only fetch metadata for assets not yet enriched.
                val needsEnrichment = merged.filter { it.imageUrl == null }
                if (needsEnrichment.isEmpty()) return@launch

                // Pre-fetch IPFS hashes for un-enriched assets in one batch RPC call.
                val withHashes = withContext(Dispatchers.IO) {
                    val node = io.raventag.app.wallet.RavencoinPublicNode(getApplication())
                    val names = needsEnrichment.map { it.name }
                    val metaBatch = try { node.getAssetMetaBatch(names) } catch (_: Exception) { emptyMap() }
                    val hashesFound = metaBatch.count { it.value?.ipfsHash != null }
                    android.util.Log.i("MainActivity", "loadOwnedAssets: getAssetMetaBatch ${metaBatch.size}/${names.size} responses, $hashesFound hashes")
                    if (hashesFound == 0 && names.isNotEmpty()) {
                        android.util.Log.w("MainActivity", "loadOwnedAssets: no IPFS hashes found for ${names.size} assets (server may not support blockchain.asset.get_meta)")
                    }
                    needsEnrichment.map { asset ->
                        val hash = metaBatch[asset.name]?.ipfsHash
                        if (hash != null) asset.copy(ipfsHash = hash) else asset
                    }
                }
                // Update only the un-enriched entries with their hashes.
                ownedAssets = ownedAssets?.map { existing ->
                    withHashes.find { it.name == existing.name } ?: existing
                }

                // Fetch IPFS metadata in parallel only for assets that still need it.
                // Using async instead of launch so we can await completion and update the cache.
                val semaphore = Semaphore(8)
                val enrichmentJobs = withHashes.map { asset ->
                    viewModelScope.async(Dispatchers.IO) {
                        try {
                            semaphore.withPermit {
                                val enriched = rpcClient.enrichWithIpfsData(asset)
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

                // Save cache once all enrichments complete so images survive the next startup.
                viewModelScope.launch {
                    enrichmentJobs.awaitAll()
                    val current = ownedAssets
                    if (!current.isNullOrEmpty()) {
                        withContext(Dispatchers.IO) { saveAssetsCache(current) }
                    }
                    Log.d("MainActivity", "loadOwnedAssets: enrichment done, cache updated with images")
                }
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
        txHistoryLoading = true
        viewModelScope.launch {
            try {
                val currentIndex = wm.getCurrentAddressIndex()
                val node = io.raventag.app.wallet.RavencoinPublicNode(getApplication())

                // One Keystore decrypt for all addresses, then parallel ElectrumX queries.
                // Include currentIndex+1 (change address) in the owned set so cycled outputs
                // are correctly classified as "back to wallet" instead of "sent to others".
                val allHistory = withContext(Dispatchers.IO) {
                    val addresses = wm.getAddressBatch(0, 0..(currentIndex + 1))
                    val ownedSet = addresses.values.toSet()
                    val deferreds = addresses.values.map { addr ->
                        async {
                            try { node.getTransactionHistory(addr, limit = txHistoryPageSize, ownedAddresses = ownedSet) }
                            catch (_: Throwable) { emptyList() }
                        }
                    }
                    deferreds.awaitAll().flatten()
                }

                // Deduplicate by txid (same tx may appear in multiple address histories)
                val deduped = allHistory.distinctBy { it.txid }
                    .sortedWith(
                        compareByDescending<io.raventag.app.wallet.TxHistoryEntry> {
                            if (it.height <= 0) Int.MAX_VALUE else it.height
                        }.thenByDescending { it.timestamp }
                    )

                // Avoid wiping the visible list when a transient network error
                // returns an empty result during a refresh. Initial display caps
                // at txHistoryPageSize (Load more pulls successive pages).
                if (deduped.isNotEmpty() || txHistory.isEmpty()) {
                    val firstPage = deduped.take(txHistoryPageSize)
                    txHistory = firstPage
                    txHistoryTotal = deduped.size
                    txHistoryLoadedCount = firstPage.size
                }
                // Persist so the next cold start renders the list instantly
                // from cache instead of waiting for the network round-trip.
                if (deduped.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val now = System.currentTimeMillis()
                            val rows = deduped.map { e ->
                                io.raventag.app.wallet.cache.TxHistoryDao.TxHistoryRow(
                                    txid = e.txid,
                                    height = e.height,
                                    confirms = e.confirmations,
                                    amountSat = e.amountSat,
                                    sentSat = e.sentSat,
                                    cycledSat = e.cycledSat,
                                    feeSat = e.feeSat,
                                    isIncoming = e.isIncoming,
                                    isSelf = e.isSelfTransfer,
                                    timestamp = e.timestamp,
                                    cachedAt = now
                                )
                            }
                            io.raventag.app.wallet.cache.TxHistoryDao.upsert(rows)
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {
                // silently ignore: tx history is optional
            } finally {
                txHistoryLoading = false
            }
        }
    }

    /**
     * Load more transactions (next page).
     * With multi-address aggregation, all transactions are loaded at once,
     * so this is a no-op for now.
     */
    fun loadMoreTransactions() {
        if (txHistoryLoadedCount >= txHistoryTotal) return

        val wm = walletManager ?: return
        val address = wm.getCurrentAddress() ?: return

        viewModelScope.launch {
            try {
                val currentIndex = wm.getCurrentAddressIndex()
                val ownedSet = withContext(Dispatchers.IO) {
                    wm.getAddressBatch(0, 0..(currentIndex + 1)).values.toSet()
                }
                val history = withContext(Dispatchers.IO) {
                    io.raventag.app.wallet.RavencoinPublicNode(getApplication()).getTransactionHistory(
                        address,
                        limit = txHistoryPageSize,
                        offset = txHistoryLoadedCount,
                        ownedAddresses = ownedSet
                    )
                }

                txHistory = txHistory + history
                txHistoryLoadedCount += history.size
            } catch (_: Throwable) {
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
    fun initWallet(wm: WalletManager, am: AssetManager, aks: AdminKeyStorage) {
        walletManager = wm
        assetManager = am
        adminKeyStorage = aks
        hasWallet = wm.hasWallet()
        if (hasWallet && walletInfo == null) {
            walletInfo = WalletInfo(address = "", balanceRvn = null, isLoading = true)
            viewModelScope.launch {
                val startupCacheDeferred = async(Dispatchers.IO) { loadStartupWalletCache(wm) }
                loadWalletInfo()
                val startupCache = startupCacheDeferred.await()
                if (walletManager === wm && hasWallet) {
                    applyStartupWalletCache(startupCache)
                }
            }
        } else if (hasWallet) {
            loadWalletInfo()
        }
        startHealthHeartbeat()
    }

    // 30s heartbeat: keep the ElectrumX pill fresh between wallet refreshes so
    // transient disconnects surface quickly without waiting for the next user action.
    private var heartbeatStarted = false
    private fun startHealthHeartbeat() {
        if (heartbeatStarted) return
        heartbeatStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            val node = io.raventag.app.wallet.RavencoinPublicNode(getApplication())
            delay(2_500L)
            while (true) {
                try { node.heartbeat() } catch (_: Exception) {}
                delay(30_000L)
            }
        }
    }

    /** Delete the wallet from secure storage and clear all wallet state. */
    fun deleteWallet() {
        walletManager?.deleteWallet()
        hasWallet = false
        walletInfo = null
        // Reset all dependent UI state so the next restore-after-delete does NOT
        // see stale balance / assets / tx history (which would falsely trigger
        // the "Replace current wallet?" gate).
        ownedAssets = null
        txHistory = emptyList()
        txHistoryTotal = 0
        txHistoryLoadedCount = 0
        needsConsolidation = false
        try { saveAssetsCache(emptyList()) } catch (_: Throwable) {}
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
                walletInfo = WalletInfo(address = "", balanceRvn = null, error = "Wallet creation failed: ${e.message}")
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
                wm.getCurrentAddress() ?: ""
            }
            hasWallet = true
            walletInfo = WalletInfo(address = address, balanceRvn = null, isLoading = true)
            pendingMnemonic = null
            loadWalletBalance()
        }
    }

    /**
     * Restore a wallet from a BIP39 mnemonic phrase.
     * On success, loads balance, assets, and transaction history.
     * On failure, sets an error message in [walletInfo].
     *
     * Guards against double-click with [walletGenerating].
     * Runs BIP44 address discovery before loading balance to ensure correct index.
     */
    fun restoreWallet(mnemonic: String) {
        val wm = walletManager ?: return
        if (walletGenerating) return
        viewModelScope.launch {
            walletGenerating = true
            // Hide any stale wallet UI and clear previous errors during discovery.
            // hasWallet stays false until the correct index is found, so address and
            // balance are never shown before discovery completes.
            hasWallet = false
            walletInfo = null
            restoreError = null
            try {
                val restored = withContext(Dispatchers.Default) {
                    wm.restoreWallet(mnemonic)
                }
                if (!restored) {
                    restoreError = "Invalid mnemonic"
                    return@launch
                }

                // Discover the correct address index on the blockchain.
                // isGenerating stays true so WalletSetupCard shows a spinner, not the form.
                try {
                    wm.discoverCurrentIndex()
                } catch (_: Exception) {
                    Log.w("MainActivity", "discoverCurrentIndex failed, using index 0")
                }

                val address = wm.getCurrentAddress() ?: ""
                walletInfo = WalletInfo(address = address, balanceRvn = null, isLoading = true)
                hasWallet = true

                // Parallel restore: load balance, assets, and history simultaneously
                coroutineScope {
                    val balanceDeferred = async(Dispatchers.IO) {
                        RetryUtils.retryWithBackoff {
                            loadWalletBalanceInternal(wm)
                        }
                    }

                    val assetsDeferred = async(Dispatchers.IO) {
                        RetryUtils.retryWithBackoff {
                            loadOwnedAssetsInternal(wm)
                        }
                    }

                    val historyDeferred = async(Dispatchers.IO) {
                        RetryUtils.retryWithBackoff {
                            loadTransactionHistoryInternal(wm)
                        }
                    }

                    // Wait for all three operations to complete
                    awaitAll(balanceDeferred, assetsDeferred, historyDeferred)
                }

                walletInfo = walletInfo?.copy(isLoading = false)
            } catch (e: Throwable) {
                restoreError = "Restore failed: ${e.message}"
                walletInfo = walletInfo?.copy(isLoading = false)
                Log.e("MainViewModel", "Wallet restore failed", e)
            } finally {
                walletGenerating = false
            }
        }
    }

    /** Initialise [walletInfo] with the address and start loading balance + history. */
    private fun loadWalletInfo() {
        val wm = walletManager ?: return
        // Preserve existing data while refreshing. Any disk/Keystore-backed cold-start
        // cache seed runs on Dispatchers.IO in initWallet(), never on the main thread.
        if (walletInfo == null) {
            walletInfo = WalletInfo(
                address = "",
                balanceRvn = null,
                isLoading = true
            )
        } else {
            walletInfo = walletInfo?.copy(isLoading = true)
        }

        viewModelScope.launch {
            // STEP 1: Load balance + assets + tx history immediately from the stored index.
            // Do NOT wait for reconcile/sweep: users see data in seconds instead of 30+ s.
            // getLocalBalance() uses getAddressBatch() internally: one Keystore decrypt, then
            // parallel ElectrumX balance queries.
            val balanceDeferred = async(Dispatchers.IO) { wm.getLocalBalance() }
            val addressDeferred = async(Dispatchers.IO) { try { wm.getCurrentAddress() ?: "" } catch (_: Throwable) { "" } }
            launch { loadOwnedAssets() }
            launch { loadTransactionHistory() }

            val balance = balanceDeferred.await()
            val address = addressDeferred.await()
            walletInfo = walletInfo?.copy(
                // Keep existing address/balance if new load fails (network error)
                address = address.ifEmpty { walletInfo?.address ?: "" },
                balanceRvn = balance ?: walletInfo?.balanceRvn,
                isLoading = false
            )

            // Persist the just-fetched balance so the next cold start can render
            // it instantly from cache instead of showing "Loading…" again.
            if (balance != null) {
                try {
                    io.raventag.app.wallet.cache.WalletCacheDao.writeBalanceSat(
                        (balance * 1e8).toLong()
                    )
                } catch (_: Throwable) {}
            }

            // STEP 2: Background maintenance (does not block the UI).
            launch(Dispatchers.IO) {

                // Auto-discovery: run when index is 0 and balance is 0 or null.
                // This handles the case where the stored index was lost/reset but
                // the user has funds at a higher address index.
                val currentIdx = wm.getCurrentAddressIndex()
                if (currentIdx == 0 && (balance == null || balance == 0.0)) {
                    try {
                        Log.i("MainViewModel", "Zero balance at index 0, running discoverCurrentIndex")
                        wm.discoverCurrentIndex()
                        val discoveredAddr = wm.getCurrentAddress()
                        if (discoveredAddr != null && discoveredAddr != walletInfo?.address) {
                            withContext(Dispatchers.Main) {
                                walletInfo = walletInfo?.copy(address = discoveredAddr, isLoading = true)
                            }
                            val newBalance = wm.getLocalBalance()
                            withContext(Dispatchers.Main) {
                                walletInfo = walletInfo?.copy(
                                    balanceRvn = newBalance,
                                    isLoading = false
                                )
                            }
                            loadOwnedAssets()
                            loadTransactionHistory()
                        }
                    } catch (_: Exception) {}
                }

                // Auto-sweep is now triggered by loadOwnedAssets() when
                // detection finds old funds (avoids race with 3s delay).

                // Refresh address after sweep: sweep advances the index to a fresh address.
                wm.getCurrentAddress()?.let { addr ->
                    withContext(Dispatchers.Main) {
                        if (addr != walletInfo?.address) walletInfo = walletInfo?.copy(address = addr)
                    }
                }
            }

            // Update ElectrumX health pill and chain info after initial load.
            // Skip full refreshBalance() — the initial load already fetched
            // balance, assets, and tx history. A second full round trip is wasteful.
            checkElectrumStatus()
            fetchBlockHeight()
            fetchRvnPrice()
            fetchNetworkHashrate()
        }
    }

    /**
     * Refresh balance, owned assets, and transaction history (pull-to-refresh).
     * AUTO-SWEEP: Automatically consolidates any funds sent to old/exposed addresses
     * to the current clean address (currentIndex+1) before loading the balance.
     */
    private val isRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun refreshBalance() {
        if (isRefreshing.getAndSet(true)) return

        val wm = walletManager ?: run { isRefreshing.set(false); return }

        viewModelScope.launch {
            try {
                walletInfo = walletInfo?.copy(isLoading = true)

                // Sync index first (sequential dependency)
                val indexChanged = try {
                    wm.syncCurrentIndex()
                } catch (e: Exception) {
                    Log.e("MainActivity", "syncCurrentIndex failed", e)
                    false
                }

                if (indexChanged) {
                    val newAddress = wm.getCurrentAddress() ?: ""
                    withContext(Dispatchers.Main) {
                        walletInfo = walletInfo?.copy(address = newAddress)
                    }
                }

                // Parallel refresh: balance, assets, and history simultaneously
                coroutineScope {
                    val balanceDeferred = async(Dispatchers.IO) {
                        RetryUtils.retryWithBackoff {
                            loadWalletBalanceInternal(wm)
                        }
                    }

                    val assetsDeferred = async(Dispatchers.IO) {
                        RetryUtils.retryWithBackoff {
                            loadOwnedAssetsInternal(wm)
                        }
                    }

                    val historyDeferred = async(Dispatchers.IO) {
                        RetryUtils.retryWithBackoff {
                            loadTransactionHistoryInternal(wm)
                        }
                    }

                    awaitAll(balanceDeferred, assetsDeferred, historyDeferred)
                }

                walletInfo = walletInfo?.copy(isLoading = false)

                // Auto-sweep is triggered by loadOwnedAssetsInternal() when
                // detection finds old funds (launchAutoSweep).
            } catch (e: Exception) {
                Log.e("MainActivity", "refreshBalance failed", e)
            } finally {
                isRefreshing.set(false)
                walletInfo = walletInfo?.copy(isLoading = false)
            }
        }
    }

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
                val balance = wm.getLocalBalance()
                if (balance != null) {
                    walletInfo = walletInfo?.copy(balanceRvn = balance, isLoading = false)
                    try {
                        io.raventag.app.wallet.cache.WalletCacheDao.writeBalanceSat(
                            (balance * 1e8).toLong()
                        )
                    } catch (_: Throwable) {}
                    return@launch
                }
                val am = assetManager ?: run {
                    walletInfo = walletInfo?.copy(isLoading = false)
                    return@launch
                }
                val info = withContext(Dispatchers.IO) { am.getWalletInfo() }
                walletInfo = walletInfo?.copy(
                    // Preserve the last known balance if backend also fails; never overwrite with 0
                    balanceRvn = info?.first ?: walletInfo?.balanceRvn,
                    isLoading = false
                )
            } catch (_: Throwable) {
                walletInfo = walletInfo?.copy(isLoading = false)
            }
        }
    }

    // Extract existing load functions to internal versions for use in parallel restore
    private suspend fun loadWalletBalanceInternal(wm: WalletManager) {
        val balance = wm.getLocalBalance()
        if (balance != null) {
            withContext(Dispatchers.Main) {
                walletInfo = walletInfo?.copy(balanceRvn = balance)
            }
            // Persist the freshly loaded balance so the next cold start can render
            // the last-known value instantly instead of flashing 0 RVN.
            try {
                io.raventag.app.wallet.cache.WalletCacheDao.writeBalanceSat(
                    (balance * 1e8).toLong()
                )
            } catch (_: Throwable) {}
        }
    }

    private suspend fun loadOwnedAssetsInternal(wm: WalletManager) {
        assetsLoading = true
        val currentIndex = wm.getCurrentAddressIndex()
        val addresses = wm.getAddressBatch(0, 0..currentIndex).values.toList()
        val node = io.raventag.app.wallet.RavencoinPublicNode(getApplication())

        try {
            // One Keystore decrypt + one pipelined batch for all asset balances
            val totals = withContext(Dispatchers.IO) {
                val (assets, _) = coroutineScope {
                    val assetsDeferred = async { node.getTotalAssetBalances(addresses) }
                    val rvnDeferred = async {
                        try { node.getTotalBalance(addresses) } catch (_: Exception) { 0.0 }
                    }

                    Pair(assetsDeferred.await(), rvnDeferred.await())
                }

                assets.map { (name, amount) ->
                    val type = when {
                        name.contains('#') -> io.raventag.app.ravencoin.AssetType.UNIQUE
                        name.contains('/') -> io.raventag.app.ravencoin.AssetType.SUB
                        else -> io.raventag.app.ravencoin.AssetType.ROOT
                    }
                    io.raventag.app.ravencoin.OwnedAsset(
                        name = name,
                        balance = amount,
                        type = type,
                        ipfsHash = null
                    )
                }.sortedWith(compareBy({ it.type.ordinal }, { it.name }))
            }

            // Consolidation banner logic: only trigger if addresses before currentIndex have funds
            if (currentIndex > 0) {
                val oldAddresses = wm.getAddressBatch(0, 0 until currentIndex).values.toList()
                var hasOldFunds = try {
                    node.getAddressesWithFunds(oldAddresses).isNotEmpty()
                } catch (_: Exception) { false }
                if (!hasOldFunds) {
                    hasOldFunds = try {
                        oldAddresses.any { addr -> node.hasAnyUtxos(addr) }
                    } catch (_: Exception) { false }
                }
                withContext(Dispatchers.Main) {
                    needsConsolidation = hasOldFunds
                }
            } else {
                withContext(Dispatchers.Main) {
                    needsConsolidation = false
                }
            }

            // Merge balances with already-loaded metadata so images never disappear on refresh
            val previous = ownedAssets?.associateBy { it.name } ?: emptyMap()
            val merged = totals.map { asset ->
                val prev = previous[asset.name]
                if (prev?.imageUrl != null) {
                    asset.copy(ipfsHash = prev.ipfsHash, imageUrl = prev.imageUrl, description = prev.description)
                } else {
                    asset
                }
            }
            withContext(Dispatchers.Main) {
                if (merged.isNotEmpty() || ownedAssets.isNullOrEmpty()) {
                    ownedAssets = merged
                }
                assetsLoading = false
                if (needsConsolidation && !consolidationInProgress) {
                    launchAutoSweep(wm)
                }
            }
            if (merged.isNotEmpty()) saveAssetsCache(merged)

            // IPFS enrichment for assets that still need it (refreshBalance path
            // previously skipped this — that's why brand previews never appeared).
            val needsEnrichment = merged.filter { it.imageUrl == null }
            if (needsEnrichment.isNotEmpty()) {
                val withHashes = withContext(Dispatchers.IO) {
                    val metaBatch = try { node.getAssetMetaBatch(needsEnrichment.map { it.name }) }
                                    catch (_: Exception) { emptyMap() }
                    needsEnrichment.map { a ->
                        val h = metaBatch[a.name]?.ipfsHash
                        if (h != null) a.copy(ipfsHash = h) else a
                    }
                }
                withContext(Dispatchers.Main) {
                    ownedAssets = ownedAssets?.map { existing ->
                        withHashes.find { it.name == existing.name } ?: existing
                    }
                }
                val sem = Semaphore(8)
                val jobs = withHashes.map { asset ->
                    viewModelScope.async(Dispatchers.IO) {
                        try {
                            sem.withPermit {
                                val enriched = rpcClient.enrichWithIpfsData(asset)
                                withContext(Dispatchers.Main) {
                                    ownedAssets = ownedAssets?.map {
                                        if (it.name == enriched.name) enriched else it
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                viewModelScope.launch {
                    jobs.awaitAll()
                    val current = ownedAssets
                    if (!current.isNullOrEmpty()) {
                        withContext(Dispatchers.IO) { saveAssetsCache(current) }
                    }
                }
            }
        } catch (_: Throwable) {
            withContext(Dispatchers.Main) {
                assetsLoadError = true
                assetsLoading = false
            }
        }
    }

    private suspend fun loadTransactionHistoryInternal(wm: WalletManager) {
        txHistoryLoading = true
        val currentIndex = wm.getCurrentAddressIndex()
        val node = io.raventag.app.wallet.RavencoinPublicNode(getApplication())

        try {
            // One Keystore decrypt for all addresses, then parallel ElectrumX queries.
            // Include currentIndex+1 in the owned set so change outputs are classified correctly.
            val allHistory = withContext(Dispatchers.IO) {
                val addresses = wm.getAddressBatch(0, 0..(currentIndex + 1))
                val ownedSet = addresses.values.toSet()
                val deferreds = addresses.values.map { addr ->
                    async {
                        try { node.getTransactionHistory(addr, limit = txHistoryPageSize, ownedAddresses = ownedSet) }
                        catch (_: Throwable) { emptyList() }
                    }
                }
                deferreds.awaitAll().flatten()
            }

            // Deduplicate by txid (same tx may appear in multiple address histories)
            val deduped = allHistory.distinctBy { it.txid }
                .sortedWith(
                    compareByDescending<io.raventag.app.wallet.TxHistoryEntry> {
                        if (it.height <= 0) Int.MAX_VALUE else it.height
                    }.thenByDescending { it.timestamp }
                )

            withContext(Dispatchers.Main) {
                // Keep prior list visible if this refresh returned empty (network blip).
                // Show only the first page (txHistoryPageSize); Load more appends the rest.
                if (deduped.isNotEmpty() || txHistory.isEmpty()) {
                    val firstPage = deduped.take(txHistoryPageSize)
                    txHistory = firstPage
                    txHistoryTotal = deduped.size
                    txHistoryLoadedCount = firstPage.size
                }
                txHistoryLoading = false
            }
            // Persist tx history rows so the next cold start can render the list
            // immediately from cache instead of waiting for the network.
            if (deduped.isNotEmpty()) {
                try {
                    val now = System.currentTimeMillis()
                    val rows = deduped.map { e ->
                        io.raventag.app.wallet.cache.TxHistoryDao.TxHistoryRow(
                            txid = e.txid,
                            height = e.height,
                            confirms = e.confirmations,
                            amountSat = e.amountSat,
                            sentSat = e.sentSat,
                            cycledSat = e.cycledSat,
                            feeSat = e.feeSat,
                            isIncoming = e.isIncoming,
                            isSelf = e.isSelfTransfer,
                            timestamp = e.timestamp,
                            cachedAt = now
                        )
                    }
                    io.raventag.app.wallet.cache.TxHistoryDao.upsert(rows)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            withContext(Dispatchers.Main) {
                txHistoryLoading = false
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
                ravencoinAssetNameLengthError(assetName)?.let { msg ->
                    warningType = null
                    issueResult = msg
                    issueSuccess = false
                    issueStep = IssueStep.Failed(IssueStep.StepName.NAME_CHECK, msg, canRetry = false)
                    issueLoading = false
                    return@launch
                }

                // D-04 Step 1: Wallet balance check
                val modeBurnSat = RavencoinTxBuilder.BURN_ROOT_SAT
                val burnRvn = modeBurnSat / 1e8
                val networkFeeRvn = 0.01
                if ((walletInfo?.balanceRvn ?: 0.0) < burnRvn + networkFeeRvn) {
                    warningType = WarningType.INSUFFICIENT_BALANCE
                    issueResult = getStrings().balanceWarningRoot
                    issueSuccess = false
                    issueLoading = false
                    return@launch
                }
                // D-04 Step 2: Asset name uniqueness check
                if (!ownedAssets.isNullOrEmpty()) {
                    val duplicate = ownedAssets!!.any { it.name.equals(assetName, ignoreCase = true) }
                    if (duplicate) {
                        warningType = WarningType.DUPLICATE_NAME
                        issueResult = getStrings().issueErrorDuplicateName
                        issueSuccess = false
                        issueLoading = false
                        return@launch
                    }
                }
                warningType = null

                val txid = try {
                    RetryUtils.retryWithBackoff(maxAttempts = 5) {
                        withContext(Dispatchers.IO) {
                            try {
                                wm.issueAssetLocal(assetName, qty.toDouble(), toAddress, units = 0, reissuable = reissuable, ipfsHash = ipfsHash)
                            } catch (e: java.net.SocketTimeoutException) {
                                // D-08: SocketTimeout must NOT be retried (tx may be broadcast).
                                // Throw as RuntimeException so isTransientError returns false.
                                throw RuntimeException(e)
                            }
                        }
                    }
                } catch (e: RuntimeException) {
                    if (e.cause is java.net.SocketTimeoutException) {
                        // D-08: RPC timeout -- tx may have been broadcast, cannot verify without txid
                        issueSuccess = false
                        issueResult = getStrings().issueErrorTimeout
                        issueStep = IssueStep.Failed(IssueStep.StepName.ISSUING, getStrings().issueErrorTimeout, canRetry = true)
                        return@launch
                    }
                    throw e
                } catch (e: Exception) {
                    // Non-transient or connection retries exhausted
                    issueSuccess = false
                    issueResult = classifyIssuanceError(e, getStrings())
                    issueStep = IssueStep.Failed(IssueStep.StepName.ISSUING, classifyIssuanceError(e, getStrings()), canRetry = RetryUtils.isTransientError(e))
                    return@launch
                }

                issueSuccess = true
                val s = getStrings()
                issueResult = s.issueRootSuccess.replace("%1", assetName).replace("%2", "${txid.take(16)}...")
                issuedTxid = txid
                walletInfo = walletInfo?.copy(address = wm.getCurrentAddress() ?: walletInfo?.address ?: "", isLoading = true)
                notifyRavenTagRegistry(assetName, txid, "root")
                // Rotate balance/assets to fresh address (currentIndex+1)
                loadWalletBalance()
                loadOwnedAssets()

                // D-10: Start confirmation polling
                issueStep = IssueStep.InProgress(IssueStep.StepName.CONFIRMING)
                viewModelScope.launch {
                    pollingLoop(txid)
                }
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = classifyIssuanceError(e, getStrings())
            } finally { issueLoading = false }
        }
    }

    private suspend fun pollingLoop(txid: String) {
        val node = RavencoinPublicNode(getApplication())
        var confirmations = 0
        while (confirmations < 6) {
            delay(30_000L)
            try {
                val tx = withContext(Dispatchers.IO) {
                    node.callElectrumRawOrNull("blockchain.transaction.get", listOf(txid, true))
                }
                val height = tx?.asJsonObject?.get("height")?.asInt ?: 0
                val tip = withContext(Dispatchers.IO) { node.getBlockHeight() } ?: 0
                confirmations = if (height > 0) tip - height + 1 else 0
            } catch (_: Exception) { }
        }
        if (confirmations >= 6) {
            issueStep = IssueStep.Success(IssueStep.StepName.CONFIRMING)
            delay(2_000L)
            issueResult = null
            issueSuccess = null
            issueStep = IssueStep.Idle
            issuedTxid = null
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
                ravencoinAssetNameLengthError(fullName)?.let { msg ->
                    warningType = null
                    issueResult = msg
                    issueSuccess = false
                    issueStep = IssueStep.Failed(IssueStep.StepName.NAME_CHECK, msg, canRetry = false)
                    issueLoading = false
                    return@launch
                }

                // D-04 Step 1: Wallet balance check
                val modeBurnSat = RavencoinTxBuilder.BURN_SUB_SAT
                val burnRvn = modeBurnSat / 1e8
                val networkFeeRvn = 0.01
                if ((walletInfo?.balanceRvn ?: 0.0) < burnRvn + networkFeeRvn) {
                    warningType = WarningType.INSUFFICIENT_BALANCE
                    issueResult = getStrings().balanceWarningSub
                    issueSuccess = false
                    issueLoading = false
                    return@launch
                }
                // D-04 Step 2: Asset name uniqueness check
                if (!ownedAssets.isNullOrEmpty()) {
                    val duplicate = ownedAssets!!.any { it.name.equals(fullName, ignoreCase = true) }
                    if (duplicate) {
                        warningType = WarningType.DUPLICATE_NAME
                        issueResult = getStrings().issueErrorDuplicateName
                        issueSuccess = false
                        issueLoading = false
                        return@launch
                    }
                }
                warningType = null

                val txid = try {
                    RetryUtils.retryWithBackoff(maxAttempts = 5) {
                        withContext(Dispatchers.IO) {
                            try {
                                wm.issueAssetLocal(fullName, qty.toDouble(), toAddress, units = 0, reissuable = reissuable, ipfsHash = ipfsHash)
                            } catch (e: java.net.SocketTimeoutException) {
                                throw RuntimeException(e)
                            }
                        }
                    }
                } catch (e: RuntimeException) {
                    if (e.cause is java.net.SocketTimeoutException) {
                        issueSuccess = false
                        issueResult = getStrings().issueErrorTimeout
                        issueStep = IssueStep.Failed(IssueStep.StepName.ISSUING, getStrings().issueErrorTimeout, canRetry = true)
                        return@launch
                    }
                    throw e
                } catch (e: Exception) {
                    issueSuccess = false
                    issueResult = classifyIssuanceError(e, getStrings())
                    issueStep = IssueStep.Failed(IssueStep.StepName.ISSUING, classifyIssuanceError(e, getStrings()), canRetry = RetryUtils.isTransientError(e))
                    return@launch
                }

                issueSuccess = true
                val s = getStrings()
                issueResult = s.issueSubSuccess.replace("%1", fullName).replace("%2", "${txid.take(16)}...")
                issuedTxid = txid
                walletInfo = walletInfo?.copy(address = wm.getCurrentAddress() ?: walletInfo?.address ?: "", isLoading = true)
                notifyRavenTagRegistry(fullName, txid, "sub")
                // Rotate balance/assets to fresh address (currentIndex+1)
                loadWalletBalance()
                loadOwnedAssets()
                issueStep = IssueStep.InProgress(IssueStep.StepName.CONFIRMING)
                viewModelScope.launch { pollingLoop(txid) }
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = classifyIssuanceError(e, getStrings())
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
                ravencoinAssetNameLengthError(fullName)?.let { msg ->
                    warningType = null
                    issueResult = msg
                    issueSuccess = false
                    issueStep = IssueStep.Failed(IssueStep.StepName.NAME_CHECK, msg, canRetry = false)
                    issueLoading = false
                    return@launch
                }

                // D-04 Step 1: Wallet balance check
                val modeBurnSat = RavencoinTxBuilder.BURN_UNIQUE_SAT
                val burnRvn = modeBurnSat / 1e8
                val networkFeeRvn = 0.01
                if ((walletInfo?.balanceRvn ?: 0.0) < burnRvn + networkFeeRvn) {
                    warningType = WarningType.INSUFFICIENT_BALANCE
                    issueResult = getStrings().balanceWarningUnique
                    issueSuccess = false
                    issueLoading = false
                    return@launch
                }
                // D-04 Step 2: Asset name uniqueness check
                if (!ownedAssets.isNullOrEmpty()) {
                    val duplicate = ownedAssets!!.any { it.name.equals(fullName, ignoreCase = true) }
                    if (duplicate) {
                        warningType = WarningType.DUPLICATE_NAME
                        issueResult = getStrings().issueErrorDuplicateName
                        issueSuccess = false
                        issueLoading = false
                        return@launch
                    }
                }
                warningType = null

                val txid = try {
                    RetryUtils.retryWithBackoff(maxAttempts = 5) {
                        withContext(Dispatchers.IO) {
                            try {
                                wm.issueAssetLocal(fullName, qty = 1.0, toAddress = toAddress, units = 0, reissuable = false, ipfsHash = ipfsHash)
                            } catch (e: java.net.SocketTimeoutException) {
                                throw RuntimeException(e)
                            }
                        }
                    }
                } catch (e: RuntimeException) {
                    if (e.cause is java.net.SocketTimeoutException) {
                        issueSuccess = false
                        issueResult = getStrings().issueErrorTimeout
                        issueStep = IssueStep.Failed(IssueStep.StepName.ISSUING, getStrings().issueErrorTimeout, canRetry = true)
                        return@launch
                    }
                    throw e
                } catch (e: Exception) {
                    issueSuccess = false
                    issueResult = classifyIssuanceError(e, getStrings())
                    issueStep = IssueStep.Failed(IssueStep.StepName.ISSUING, classifyIssuanceError(e, getStrings()), canRetry = RetryUtils.isTransientError(e))
                    return@launch
                }

                issueSuccess = true
                val s = getStrings()
                issueResult = s.issueUniqueSuccess.replace("%1", fullName).replace("%2", "${txid.take(16)}...")
                issuedTxid = txid
                walletInfo = walletInfo?.copy(address = wm.getCurrentAddress() ?: walletInfo?.address ?: "", isLoading = true)
                notifyRavenTagRegistry(fullName, txid, "unique")
                // Rotate balance/assets to fresh address (currentIndex+1)
                loadWalletBalance()
                loadOwnedAssets()
                issueStep = IssueStep.InProgress(IssueStep.StepName.CONFIRMING)
                viewModelScope.launch { pollingLoop(txid) }
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = classifyIssuanceError(e, getStrings())
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
        val am = AssetManager(context = getApplication(), adminKeyStorage = adminKeyStorage!!)
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
        val am = AssetManager(context = getApplication(), adminKeyStorage = adminKeyStorage!!)
        viewModelScope.launch {
            issueLoading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    am.revokeAsset(BurnParams(assetName, reason = reason, burnOnChain = false))
                }
                issueSuccess = result.success
                issueResult = if (result.success) getStrings().revokeSuccess else (result.error ?: getStrings().revokeFailed)
            } catch (e: Throwable) {
                issueSuccess = false; issueResult = e.message ?: getStrings().revokeFailed
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
        issueStep = IssueStep.Idle
        issuedTxid = null
        warningType = null
        registerNfcPubId = null
        prefilledTransferAssetName = null
    }

    /**
     * Phase 40: Classify an exception caught during asset issuance into a localized
     * user-facing error message. Falls back to raw exception message for unknown errors.
     */
    private fun classifyIssuanceError(e: Throwable, s: AppStrings): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("insufficient funds") || msg.contains("fondi insufficienti")
                || msg.contains("no spendable") || msg.contains("nessun rvn spendibile")
                -> s.issueErrorInsufficientFunds
            msg.contains("duplicate") || msg.contains("already exists") || msg.contains("gia esiste")
                -> s.issueErrorDuplicateName
            msg.contains("connection refused") || msg.contains("unreachable") || msg.contains("irraggiungibile")
                || msg.contains("unknownhost")
                -> s.issueErrorNodeUnreachable
            msg.contains("timeout")
                -> s.issueErrorTimeout
            msg.contains("fee") && (msg.contains("estimate") || msg.contains("commissione"))
                -> s.issueErrorFeeEstimation
            msg.contains("pinata") && (msg.contains("jwt") || msg.contains("auth") || msg.contains("scaduto"))
                -> s.issueErrorIpfsAuth
            msg.contains("ipfs") || msg.contains("caricamento ipfs fallito")
                -> s.issueErrorIpfsFailed
            msg.contains("invalid address") || msg.contains("indirizzo non valido")
                -> s.issueErrorInvalidAddress
            msg.contains("wallet non disponibile") || msg.contains("no wallet")
                -> s.issueErrorNoWallet
            else -> "${s.issueFailed}: ${e.message ?: ""}"
        }
    }

    /**
     * Register a physical NFC chip against an asset on the backend.
     * Calls POST /api/brand/chips with the asset name and tag UID.
     */
    fun registerChip(assetName: String, tagUid: String, adminKey: String) {
        val am = AssetManager(context = getApplication(), adminKeyStorage = adminKeyStorage!!)
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

    /** Estimated network fee for the pending send operation, in RVN. */
    var estimatedFee by mutableStateOf(0.0)

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
                // Show broadcasting notification (D-03, D-05)
                TransactionNotificationHelper.showBroadcasting(getApplication())

                // Execute send with retry (D-06)
                val result = RetryUtils.retryWithBackoff {
                    withContext(Dispatchers.IO) { wm.sendRvnLocal(toAddress, amount) }
                }

                val txid = result.substringBefore("|fee:")
                val feeRvn = result.substringAfter("|fee:", "0").toLongOrNull()?.let { it / 1e8 } ?: 0.0

                // Show confirming notification (waiting for blocks)
                TransactionNotificationHelper.showConfirming(getApplication(), 1, 1)

                // Brief delay to allow user to see confirming state, then show completed
                kotlinx.coroutines.delay(2000)

                // Show completed notification (D-03, D-04, D-05)
                TransactionNotificationHelper.showCompleted(getApplication(), txid)

                // Update UI state
                sendLoading = false
                sendSuccess = true
                sendResult = s.walletSendResult.replace("%1", amount.toString())
                    .replace("%2", "%.5f".format(feeRvn))
                    .replace("%3", "${txid.take(20)}...")

                // Update displayed address (rotated after send)
                walletInfo = walletInfo?.copy(address = wm.getCurrentAddress() ?: walletInfo?.address ?: "")

                // Optimistically deduct sent amount + fee so the balance updates instantly
                // instead of waiting for the network refresh round-trip.
                val currentBalance = walletInfo?.balanceRvn
                if (currentBalance != null) {
                    walletInfo = walletInfo?.copy(
                        balanceRvn = (currentBalance - amount - feeRvn).coerceAtLeast(0.0)
                    )
                }

                // Refresh balance from network (confirms the exact post-send amount)
                loadWalletBalance()
            } catch (e: io.raventag.app.wallet.FeeUnavailableException) {
                sendLoading = false
                sendFeeUnavailable = true
                TransactionNotificationHelper.showFailed(getApplication(), "Fee unavailable: ${e.message}")
            } catch (e: Throwable) {
                // Show failed notification (D-05, D-06)
                TransactionNotificationHelper.showFailed(getApplication(), "Send failed: ${e.message}")

                val s = getStrings()
                sendLoading = false
                sendSuccess = false
                sendResult = s.walletSendError.replace("%1", e.message ?: "Unknown error")

                // Classify error: transient (timeout, network) -> banner with auto-dismiss;
                // non-transient (validation, wallet logic) -> modal dialog.
                // Per 20-UI-SPEC.md Error State Patterns and Claude's discretion areas in 20-CONTEXT.md.
                reportAsyncError(e, prefix = "Send failed")

                android.util.Log.e("MainActivity", "sendRvn failed", e)
            }
        }
    }

    /**
     * Auto-sweep launched from detection when old funds are found.
     * Runs in background without blocking UI; shows sweep banner.
     */
    private fun launchAutoSweep(wm: io.raventag.app.wallet.WalletManager) {
        if (autoSweepInProgress || consolidationInProgress) return
        autoSweepInProgress = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val txids = wm.sweepOldAddresses()
                if (txids.isNotEmpty()) {
                    Log.i("MainViewModel", "Auto-sweep: ${txids.size} txs")
                    withContext(Dispatchers.Main) {
                        needsConsolidation = false
                        loadWalletBalance()
                    }
                }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) { autoSweepInProgress = false }
            }
        }
    }

    /**
     * Consolidate all funds (RVN + assets) from scattered addresses to a fresh virgin address.
     * Triggered when the portfolio scan detects funds on old addresses that need to be moved.
     */
    fun consolidateFunds() {
        val wm = walletManager ?: return
        
        // Set flag to prevent banner from reappearing during consolidation
        consolidationInProgress = true
        
        viewModelScope.launch {
            try {
                assetsLoading = true
                val txid = withContext(Dispatchers.IO) { wm.consolidateAllFundsToFreshAddress() }
                
                if (txid != null) {
                    needsConsolidation = false
                    // Update current address to the target address (currentIndex + 1)
                    // so the UI shows the correct receiving address and balance
                    val newAddress = wm.getCurrentAddress() ?: wm.getAddress(0, wm.getCurrentAddressIndex() + 1)
                    walletInfo = walletInfo?.copy(address = newAddress ?: walletInfo?.address ?: "")

                    // Reload balance and assets after consolidation
                    loadWalletBalance()
                    loadOwnedAssets()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "consolidateFunds failed", e)
            } finally {
                // Clear the flag when done (success or failure)
                consolidationInProgress = false
                assetsLoading = false
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
                // Show broadcasting notification (D-03, D-05)
                TransactionNotificationHelper.showBroadcasting(getApplication())

                // Execute transfer with retry (D-06)
                val txid = RetryUtils.retryWithBackoff {
                    withContext(Dispatchers.IO) {
                        wm.transferAssetLocal(assetName, toAddress, qty.toDouble())
                    }
                }

                // Show confirming notification (waiting for blocks)
                TransactionNotificationHelper.showConfirming(getApplication(), 1, 1)

                // Brief delay to allow user to see confirming state, then show completed
                kotlinx.coroutines.delay(2000)

                // Show completed notification (D-03, D-04, D-05)
                TransactionNotificationHelper.showCompleted(getApplication(), txid)

                // Update UI state
                val s = getStrings()
                issueLoading = false
                issueSuccess = true
                issueResult = s.walletTransferResult.replace("%1", assetName).replace("%2", "${txid.take(20)}...")

                // Update displayed address (rotated after transfer) and optimistically
                // mark loading so the UI shows the previous balance + spinner instead
                // of a temporarily wrong value while ElectrumX mempool propagates.
                walletInfo = walletInfo?.copy(
                    address = wm.getCurrentAddress() ?: walletInfo?.address ?: "",
                    isLoading = true
                )

                // Optimistically remove the sent asset from the visible list so the
                // user does not see it lingering after a UNIQUE token transfer.
                // For fungible assets, decrement the local quantity by `qty`.
                ownedAssets = ownedAssets?.mapNotNull { a ->
                    if (a.name == assetName) {
                        val remaining = (a.balance - qty.toDouble()).coerceAtLeast(0.0)
                        if (remaining <= 0.0) null else a.copy(balance = remaining)
                    } else a
                }

                // Snapshot the pre-broadcast balance so we can reject obviously wrong
                // (mempool race) refresh values that would temporarily double the total.
                val preBalance = walletInfo?.balanceRvn ?: 0.0

                // Wait longer for ElectrumX to settle: with a 1s block-cycle emulator
                // the mempool view across multiple servers stabilizes around 8s.
                kotlinx.coroutines.delay(8000)
                loadWalletBalance()
                // Sanity guard: if the just-loaded balance is more than 1.05× the
                // pre-send balance, ElectrumX is in a transient inconsistent state.
                // Keep the user-trusted previous balance and try again shortly.
                val postBalance = walletInfo?.balanceRvn ?: 0.0
                if (preBalance > 0.0 && postBalance > preBalance * 1.05) {
                    walletInfo = walletInfo?.copy(balanceRvn = preBalance, isLoading = true)
                    kotlinx.coroutines.delay(5000)
                    loadWalletBalance()
                }
                loadOwnedAssets()
            } catch (e: Throwable) {
                // Show failed notification (D-05, D-06)
                TransactionNotificationHelper.showFailed(getApplication(), "Transfer failed: ${e.message}")

                val s = getStrings()
                issueLoading = false
                issueSuccess = false
                issueResult = s.walletTransferError.replace("%1", e.message ?: "Unknown error")

                android.util.Log.e("MainActivity", "transferAssetConsumer failed", e)
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
        val fullName = "${parentAsset.uppercase()}/${childName.uppercase()}"
        ravencoinAssetNameLengthError(fullName)?.let { msg ->
            issueMode = null
            issueResult = msg
            issueSuccess = false
            issueStep = IssueStep.Failed(IssueStep.StepName.NAME_CHECK, msg, canRetry = false)
            return
        }
        issueMode = null
        issueResult = null
        issueSuccess = null
        writeTagAssetName = fullName
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
        val fullName = "${parentSub.uppercase()}#${serial.uppercase()}"
        ravencoinAssetNameLengthError(fullName)?.let { msg ->
            issueMode = null
            issueResult = msg
            issueSuccess = false
            issueStep = IssueStep.Failed(IssueStep.StepName.NAME_CHECK, msg, canRetry = false)
            return
        }
        issueMode = null
        issueResult = null
        issueSuccess = null
        writeTagAssetName = fullName
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
                val errorMsg = result.exceptionOrNull()?.message ?: "Errore sconosciuto"
                writeTagError = errorMsg
                if (!isStandaloneWrite) {
                    issueStep = IssueStep.Failed(IssueStep.StepName.ISSUING, errorMsg, canRetry = false)
                }
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
        val am = AssetManager(context = getApplication(), adminKeyStorage = adminKeyStorage!!)
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
        val am = AssetManager(context = getApplication(), adminKeyStorage = adminKeyStorage!!)
        val fullName = args.fullAssetName
        ravencoinAssetNameLengthError(fullName)?.let { return Result.failure(Exception(it)) }
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
        issueStep = IssueStep.InProgress(IssueStep.StepName.IPFS_UPLOAD)
        val ipfsHash = try {
            uploadMetadata(metadata, am) ?: throw Exception("ipfs upload failed")
        } catch (e: Exception) {
            val msg = classifyIssuanceError(e, getStrings())
            return Result.failure(Exception(msg))
        }
        issueStep = IssueStep.Success(IssueStep.StepName.IPFS_UPLOAD)
        Log.i("IssueWriteFlow", "processIssueAndWrite metadata-uploaded asset=$fullName metadataIpfs=$ipfsHash")

        // 5. Issue the Ravencoin asset on-chain; the IPFS hash is embedded in the issuance tx
        val wm = walletManager ?: return Result.failure(Exception(classifyIssuanceError(Exception("no wallet"), getStrings())))
        issueStep = IssueStep.InProgress(IssueStep.StepName.ISSUING)
        val txid = try {
            RetryUtils.retryWithBackoff(maxAttempts = 5) {
                try {
                    wm.issueAssetLocal(fullName, qty = 1.0, toAddress = args.toAddress,
                        units = 0, reissuable = false, ipfsHash = ipfsHash)
                } catch (e: java.net.SocketTimeoutException) {
                    throw RuntimeException(e)
                }
            }
        } catch (e: RuntimeException) {
            if (e.cause is java.net.SocketTimeoutException) {
                val msg = classifyIssuanceError(e.cause!!, getStrings())
                return Result.failure(Exception(msg))
            }
            val msg = classifyIssuanceError(e, getStrings())
            return Result.failure(Exception(msg))
        } catch (e: Exception) {
            val msg = classifyIssuanceError(e, getStrings())
            return Result.failure(Exception(msg))
        }
        Log.i("IssueWriteFlow", "processIssueAndWrite asset-issued asset=$fullName txid=$txid")
        issueStep = IssueStep.Success(IssueStep.StepName.ISSUING)
        issuedTxid = txid
        // Update displayed address (rotated after issuance).
        // Defer balance/asset reload: NFC programming follows immediately and
        // must not be delayed by RPC-heavy loadOwnedAssets calls.
        walletInfo = walletInfo?.copy(address = wm.getCurrentAddress() ?: walletInfo?.address ?: "")
        notifyRavenTagRegistry(
            assetName = fullName,
            txid = txid,
            assetType = if (args.assetKind == WriteTagAssetKind.UNIQUE_TOKEN) "unique" else "sub"
        )

        // 6. Program the tag: authenticate, set keys derived from this specific UID, write NDEF URL
        issueStep = IssueStep.InProgress(IssueStep.StepName.NFC_PROGRAMMING)
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
        issueStep = IssueStep.Success(IssueStep.StepName.NFC_PROGRAMMING)
        Log.i("IssueWriteFlow", "processIssueAndWrite tag-configured asset=$fullName uid=$uidHex")

        // 7. Register the chip on the backend (links UID to the new asset record)
        val regResult = am.registerChip(fullName, uidHex)
        Log.i("IssueWriteFlow", "processIssueAndWrite registerChip asset=$fullName success=${regResult.success} error=${regResult.error}")

        // D-10: Start confirmation polling after combined flow
        issueStep = IssueStep.InProgress(IssueStep.StepName.CONFIRMING)
        viewModelScope.launch { pollingLoop(txid) }

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
                io.raventag.app.wallet.AssetManager(context = getApplication(), apiBaseUrl = tagBaseUrl, adminKeyStorage = adminKeyStorage!!).checkHealth()
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
                    io.raventag.app.wallet.AssetManager(context = getApplication(), apiBaseUrl = currentVerifyUrl, adminKeyStorage = adminKeyStorage!!)
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
                    io.raventag.app.wallet.AssetManager(context = getApplication(), apiBaseUrl = currentVerifyUrl, adminKeyStorage = adminKeyStorage!!).checkRevocationStatus(assetName)
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

    /** AdminKeyStorage for encrypted admin key persistence (AES256-GCM, Keystore-backed). */
    private lateinit var adminKeyStorage: AdminKeyStorage

    private var initialAdminKey: String = ""
    private var initialMasterKey: String = ""
    private var initialOperatorKey: String = ""
    private var initialPinataJwt: String = ""
    private var initialKuboNodeUrl: String = ""

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
        splash.setKeepOnScreenCondition { !securePrefsReady }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Process any NFC intent that launched or re-launched this activity
        handleIntent(intent)

        // Create notification channel (safe to call on every start, system ignores duplicates)
        NotificationHelper.createChannel(this)

        // Create transaction progress notification channel
        TransactionNotificationHelper.createChannel(applicationContext)

        // D-06, D-07: create incoming_tx notification channel for received RVN/assets
        io.raventag.app.worker.IncomingTxNotificationHelper.createChannel(applicationContext)

        val prefs = getSharedPreferences("raventag_app", MODE_PRIVATE)

        // Keystore and SQLite initialization are expensive on some devices. Run them
        // in parallel while the splash screen is visible; the first Compose frame then
        // starts with managers ready but without blocking the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            val securePrefsDeferred = async {
                try {
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
            }
            val adminKeyStorageDeferred = async {
                val storage = AdminKeyStorage(applicationContext)
                storage to (try { storage.getAdminKey() } catch (_: Throwable) { null }).orEmpty()
            }
            val walletManagerDeferred = async { WalletManager(applicationContext) }
            val dbDeferred = async {
                // Initialize wallet reliability database BEFORE initWallet so cache reads
                // for balance, tx history and block height have a ready SQLite handle.
                io.raventag.app.wallet.cache.WalletReliabilityDb.init(this@MainActivity)
                io.raventag.app.wallet.health.NodeHealthMonitor.init(this@MainActivity)
                io.raventag.app.wallet.cache.ReservedUtxoDao.pruneOlderThan(
                    System.currentTimeMillis() - 48L * 3600_000L
                )
                // Schedule periodic wallet polling every 15 minutes.
                // UPDATE policy replaces older work after app updates.
                WorkManager.getInstance(this@MainActivity).enqueueUniquePeriodicWork(
                    "wallet_poll",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<WalletPollingWorker>(15, TimeUnit.MINUTES).build()
                )
            }

            val initializedSecurePrefs = securePrefsDeferred.await()
            val initializedAdmin = adminKeyStorageDeferred.await()
            val initializedAdminKeyStorage = initializedAdmin.first
            val initializedAdminKey = initializedAdmin.second
            val initializedMasterKey = initializedSecurePrefs.getString("initial_master_key", "") ?: ""
            val initializedOperatorKey = initializedSecurePrefs.getString("operator_key", "") ?: ""
            val initializedPinataJwt = initializedSecurePrefs.getString("pinata_jwt", "") ?: ""
            val initializedKuboNodeUrl = initializedSecurePrefs.getString("kubo_node_url", "") ?: ""
            val initializedWalletManager = walletManagerDeferred.await()
            dbDeferred.await()
            val initializedAssetManager = AssetManager(
                context = applicationContext,
                adminKeyStorage = initializedAdminKeyStorage
            )

            withContext(Dispatchers.Main) {
                securePrefs = initializedSecurePrefs
                adminKeyStorage = initializedAdminKeyStorage
                initialAdminKey = initializedAdminKey
                initialMasterKey = initializedMasterKey
                initialOperatorKey = initializedOperatorKey
                initialPinataJwt = initializedPinataJwt
                initialKuboNodeUrl = initializedKuboNodeUrl
                viewModel.initWallet(
                    initializedWalletManager,
                    initializedAssetManager,
                    initializedAdminKeyStorage
                )
                securePrefsReady = true
            }
        }

        // Initialize verify URL from saved prefs so revocation checks use the right backend
        val savedVerifyUrl = prefs.getString("verify_url", AppConfig.DEFAULT_VERIFY_URL) ?: AppConfig.DEFAULT_VERIFY_URL
        viewModel.currentVerifyUrl = savedVerifyUrl

        setContent {
            // Hold content until EncryptedSharedPreferences is ready (avoids reading null keys)
            if (!securePrefsReady) return@setContent

            // Persisted user preferences (read from SharedPreferences, updated on save)
            var langCode by remember { mutableStateOf(prefs.getString("language", "en") ?: "en") }
            var savedAdminKey by remember { mutableStateOf(initialAdminKey) }
            var savedInitialMasterKey by remember { mutableStateOf(initialMasterKey) }
            var savedOperatorKey by remember { mutableStateOf(initialOperatorKey) }
            var walletRole by remember { mutableStateOf(prefs.getString("wallet_role", "") ?: "") }
            // Sync wallet role into ViewModel so BrandDashboard can read it reactively
            LaunchedEffect(walletRole) { viewModel.walletRole = walletRole }
            var savedPinataJwt by remember { mutableStateOf(initialPinataJwt) }
            var savedKuboNodeUrl by remember { mutableStateOf(initialKuboNodeUrl) }
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
                                                try { viewModel.adminKeyStorage?.setAdminKey(key) } catch (_: Throwable) {}
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

    /** Set when the activity goes to background; cleared on resume after triggering refresh. */
    private var resumeRefreshNeeded = false

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
        // Refresh wallet immediately when returning from background so address, balance,
        // and asset list are up to date (e.g. the other app flavor sent a tx while away).
        if (resumeRefreshNeeded && viewModel.hasWallet) {
            resumeRefreshNeeded = false
            viewModel.refreshBalance()
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
        resumeRefreshNeeded = true
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
        // Handle VIEW_TRANSACTION intent from notification (per D-04)
        if (intent.action == TransactionNotificationHelper.ACTION_VIEW_TRANSACTION_EXT) {
            val txid = intent.getStringExtra(TransactionNotificationHelper.EXTRA_TXID_EXT)
            if (txid != null) {
                viewModel.handleViewTransactionIntent(txid)
            }
        }

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
                if (currentTab == AppTab.WALLET) {
                    viewModel.refreshBalance()
                }
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

    // ── Critical error dialog (per 20-UI-SPEC.md Dialog Error pattern) ────────
    // Shown for non-recoverable async failures (validation errors, wallet logic
    // errors). The user must explicitly acknowledge before proceeding.
    viewModel.criticalError?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearCriticalError() },
            containerColor = Color(0xFF101020),
            icon = {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Color(0xFFF87171)
                )
            },
            title = { Text("Error", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    msg,
                    color = RavenMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearCriticalError() },
                    colors = ButtonDefaults.buttonColors(containerColor = RavenOrange)
                ) { Text("OK", fontWeight = FontWeight.Bold) }
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
        val sendCtx = LocalContext.current
        val sendFeeEstimator = remember { io.raventag.app.wallet.fee.FeeEstimator(io.raventag.app.wallet.RavencoinPublicNode(sendCtx)) }
        SendRvnScreen(
            isLoading = viewModel.sendLoading,
            resultMessage = viewModel.sendResult,
            resultSuccess = viewModel.sendSuccess,
            feeUnavailable = viewModel.sendFeeUnavailable,
            estimatedFee = viewModel.estimatedFee,
            feeEstimator = sendFeeEstimator,
            prefillAddress = if (viewModel.donateMode) viewModel.donateAddress else "",
            donateMode = viewModel.donateMode,
            walletBalance = viewModel.walletInfo?.balanceRvn ?: 0.0,
            onBack = {
                viewModel.showSend = false
                viewModel.donateMode = false
                viewModel.sendResult = null
                viewModel.sendSuccess = null
                viewModel.sendFeeUnavailable = false
                viewModel.estimatedFee = 0.0
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

    // ── Transaction details overlay (per D-04) ────────────────────────────────
    if (viewModel.isViewingTransaction && viewModel.viewingTxid != null) {
        TransactionDetailsScreen(
            txid = viewModel.viewingTxid!!,
            onClose = { viewModel.isViewingTransaction = false }
        )
        return
    }

    // ── Transfer overlay ──────────────────────────────────────────────────────
    // Handles token transfers, root-asset transfers, and sub-asset transfers.
    if (issueMode == IssueMode.TRANSFER || issueMode == IssueMode.TRANSFER_ROOT || issueMode == IssueMode.TRANSFER_SUB) {
        val transferCtx = LocalContext.current
        val transferFeeEstimator = remember { io.raventag.app.wallet.fee.FeeEstimator(io.raventag.app.wallet.RavencoinPublicNode(transferCtx)) }
        TransferScreen(
            isLoading = viewModel.issueLoading,
            resultMessage = viewModel.issueResult,
            resultSuccess = viewModel.issueSuccess,
            mode = issueMode,
            prefilledAssetName = viewModel.prefilledTransferAssetName,
            showLowRvnWarning = !AppConfig.IS_BRAND_APP && (viewModel.walletInfo?.balanceRvn ?: 0.0) < 0.01,
            feeEstimator = transferFeeEstimator,
            onBack = { viewModel.issueMode = null; viewModel.clearIssueResult() },
            onTransfer = { assetName, toAddress, qty ->
                viewModel.transferAssetConsumer(assetName, toAddress, qty)
            }
        )
        return
    }

    // ── Issue / revoke overlay ────────────────────────────────────────────────
    val walletAddress = viewModel.walletManager?.getNextAddress()
        ?: viewModel.walletInfo?.address ?: ""
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
            currentStep = viewModel.issueStep,
            issuedTxid = viewModel.issuedTxid,
            warningType = viewModel.warningType,
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
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            // Keep heavy destination tabs alive once warmed. Wallet is prewarmed shortly
            // after startup when a wallet exists, so the first Scan -> Wallet tap does not
            // pay the large initial composition cost.
            val walletEverShown = remember { mutableStateOf(currentTab == AppTab.WALLET) }
            val brandEverShown = remember { mutableStateOf(isBrandApp && currentTab == AppTab.BRAND) }
            val settingsEverShown = remember { mutableStateOf(currentTab == AppTab.SETTINGS) }

            LaunchedEffect(viewModel.hasWallet, isBrandApp) {
                delay(1_400)
                if (viewModel.hasWallet) walletEverShown.value = true
                delay(300)
                if (isBrandApp) brandEverShown.value = true
                delay(200)
                settingsEverShown.value = true
            }

            if (currentTab == AppTab.WALLET) walletEverShown.value = true
            if (currentTab == AppTab.BRAND && isBrandApp) brandEverShown.value = true
            if (currentTab == AppTab.SETTINGS) settingsEverShown.value = true

            val walletVisible = currentTab == AppTab.WALLET
            val brandVisible = currentTab == AppTab.BRAND
            val settingsVisible = currentTab == AppTab.SETTINGS

            if (walletEverShown.value) {
                TabLayer(visible = walletVisible) {
                    WalletScreen(
                        modifier = Modifier.fillMaxSize(),
                        active = walletVisible,
                        walletInfo = viewModel.walletInfo,
                        hasWallet = viewModel.hasWallet,
                        isGenerating = viewModel.walletGenerating,
                        ownedAssets = viewModel.ownedAssets,
                        assetsLoading = viewModel.assetsLoading,
                        assetsLoadError = viewModel.assetsLoadError,
                        needsConsolidation = viewModel.needsConsolidation,
                        consolidationInProgress = viewModel.consolidationInProgress,
                        autoSweepInProgress = viewModel.autoSweepInProgress,
                        onConsolidateFunds = { viewModel.consolidateFunds() },
                        electrumStatus = viewModel.electrumStatus,
                        blockHeight = viewModel.blockHeight,
                        rvnPrice = viewModel.rvnPrice,
                        networkHashrate = viewModel.networkHashrate,
                        walletRole = walletRole,
                        controlKeyValidating = viewModel.controlKeyValidating,
                        controlKeyError = viewModel.controlKeyError,
                        restoreError = viewModel.restoreError,
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
            }

            if (brandEverShown.value && isBrandApp) {
                TabLayer(visible = brandVisible) {
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
                        modifier = Modifier.fillMaxSize(),
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
            }

            if (settingsEverShown.value) {
                TabLayer(visible = settingsVisible) {
                    LaunchedEffect(Unit) {
                        if (viewModel.serverStatus == MainViewModel.ServerStatus.UNKNOWN) {
                            viewModel.checkServerStatus(viewModel.currentVerifyUrl)
                        }
                    }
                    LaunchedEffect(viewModel.serverStatus) {
                        if (viewModel.serverStatus == MainViewModel.ServerStatus.ONLINE) {
                            if (viewModel.pinataJwtStatus == MainViewModel.AdminKeyStatus.UNKNOWN)
                                viewModel.checkPinataJwt(savedPinataJwt)
                            if (viewModel.kuboNodeStatus == MainViewModel.AdminKeyStatus.UNKNOWN)
                                viewModel.checkKuboNode(savedKuboNodeUrl)
                            if (viewModel.adminKeyStatus == MainViewModel.AdminKeyStatus.UNKNOWN && savedAdminKey.isNotEmpty())
                                viewModel.checkAdminKey(viewModel.currentVerifyUrl, savedAdminKey)
                        }
                    }
                    SettingsScreen(
                        modifier = Modifier.fillMaxSize(),
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
                        currentAdminKey = savedAdminKey,
                        onAdminKeySave = { key ->
                            viewModel.adminKeyStorage?.setAdminKey(key)
                            viewModel.viewModelScope.launch {
                                viewModel.validateAdminKey(key, viewModel.currentVerifyUrl)
                            }
                        },
                        adminKeyStatus = viewModel.adminKeyStatus,
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

            if (currentTab == AppTab.SCAN) {
                ScanScreen(
                    modifier = Modifier.fillMaxSize(),
                    scanState = viewModel.scanState,
                    errorMessage = viewModel.errorMessage,
                    nfcSupported = nfcSupported,
                    nfcEnabled = nfcEnabled,
                    onStartScan = { viewModel.scanState = ScanState.SCANNING }
                )
            }

            // ── Transient error banner overlay ────────────────────────────────
            // Drawn last inside the Box so it sits above all tab content.
            // Auto-dismisses after 5s (see MainViewModel.showTransientError) or
            // on explicit Dismiss tap. Used for recoverable errors (network
            // timeout, transient failures) per 20-UI-SPEC.md Banner Error pattern.
            viewModel.transientError?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.TopCenter
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D0A0A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF87171).copy(alpha = 0.4f)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = msg,
                                color = Color(0xFFF87171),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { viewModel.clearTransientError() },
                                colors = ButtonDefaults.buttonColors(containerColor = RavenOrange),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    "Dismiss",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
