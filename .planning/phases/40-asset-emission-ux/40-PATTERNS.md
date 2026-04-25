# Phase 40: Asset Emission UX - Pattern Map

**Mapped:** 2026-04-25
**Files analyzed:** 6 (4 modified, 2 added)
**Analogs found:** 6 / 6

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `MainActivity.kt` (issuance callbacks, classifyIssuanceError) | ViewModel (controller) | CRUD + event-driven | `MainActivity.kt` (existing unrevokeAsset at lines 1687-1702) | exact (same file, same role) |
| `MainActivity.kt` (IssueStep sealed class) | ViewModel state (model) | event-driven | `WriteTagStep` enum (WriteTagScreen.kt lines 48-57) | role-match (step state machine) |
| `IssueAssetScreen.kt` (step progress + tappable txid) | Composable (component) | request-response | `WriteTagScreen.kt` (LoadingStep, SuccessStep, ErrorStep at lines 240-414) | role-match (step display composable) |
| `AppStrings.kt` (new error + step keys) | Config (localization) | N/A | `AppStrings.kt` existing issue strings (lines 359-362) | exact (same file, same pattern) |
| `AssetManager.kt` (optional checkAssetNameExists) | Service (API client) | CRUD (HTTP) | `AssetManager.kt` existing methods (e.g., unrevokeAsset at lines 349-360) | exact (same file, same pattern) |
| `MainActivity.kt` (revokeAsset bug fix) | ViewModel (controller) | CRUD | `MainActivity.kt` unrevokeAsset (lines 1687-1702, correct result capture pattern) | exact (same pattern, opposite method) |

## Pattern Assignments

### `MainActivity.kt` — Issuance callbacks with error classification (lines 1611-1677)

**Analog:** `MainActivity.kt` unrevokeAsset (lines 1687-1702) — shows the correct pattern for capturing `AssetOperationResult` and using it to set `issueSuccess`.

**Existing generic catch pattern (replace this):**
```kotlin
// MainActivity.kt lines 1625-1627 (issueRootAsset example — must be replaced)
} catch (e: Throwable) {
    issueSuccess = false; issueResult = getStrings().issueFailed
}
```

**Core ViewModel callback pattern** (lines 1611-1628):
```kotlin
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
            walletInfo = walletInfo?.copy(address = wm.getCurrentAddress() ?: walletInfo?.address ?: "")
            notifyRavenTagRegistry(assetName, txid, "root")
        } catch (e: Throwable) {
            issueSuccess = false; issueResult = getStrings().issueFailed  // ← REPLACE with classifyIssuanceError(e, getStrings())
        } finally { issueLoading = false }
    }
}
```

**Analog for correct result capture** (unrevokeAsset lines 1687-1702 — shows how to use `AssetOperationResult`):
```kotlin
// MainActivity.kt lines 1687-1702
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
```

**revokeAsset bug — current broken pattern (lines 1714-1729, must fix):**
```kotlin
// MainActivity.kt lines 1714-1729 — BUG: result discarded, always sets success
fun revokeAsset(assetName: String, reason: String, adminKey: String) {
    val am = AssetManager(context = getApplication(), adminKeyStorage = adminKeyStorage!!)
    viewModelScope.launch {
        issueLoading = true
        try {
            withContext(Dispatchers.IO) {
                am.revokeAsset(BurnParams(assetName, reason = reason, burnOnChain = false))
            }
            issueSuccess = true  // BUG: always true, result discarded
            issueResult = "Asset $assetName revocato"
        } catch (e: Throwable) {
            issueSuccess = false; issueResult = e.message ?: "Revoca fallita"
        } finally { issueLoading = false }
    }
}
```

**Fix pattern** — capture the result like unrevokeAsset does (lines 1687-1702):
```kotlin
// Fix: capture AssetOperationResult instead of discarding it
val result = withContext(Dispatchers.IO) {
    am.revokeAsset(BurnParams(assetName, reason = reason, burnOnChain = false))
}
issueSuccess = result.success
issueResult = if (result.success) "Asset $assetName revocato" else (result.error ?: "Revoca fallita")
```

**Imports pattern** (MainActivity.kt top of file):
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

---

### `MainActivity.kt` — New `classifyIssuanceError` private method

**Analog:** No exact analog exists — this is a new utility. Use pattern from RESEARCH.md section "Pattern 1: Error Classification Pattern".

**Recommended pattern:**
```kotlin
// New private method in MainViewModel (MainActivity.kt)
private fun classifyIssuanceError(e: Throwable, s: AppStrings): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("insufficient funds") || msg.contains("fondi insufficienti")
            -> s.issueErrorInsufficientFunds
        msg.contains("duplicate") || msg.contains("already exists") || msg.contains("gia esiste")
            -> s.issueErrorDuplicateName
        msg.contains("connection refused") || msg.contains("unreachable") || msg.contains("irraggiungibile")
            -> s.issueErrorNodeUnreachable
        msg.contains("timeout") -> s.issueErrorTimeout
        msg.contains("fee") && (msg.contains("estimate") || msg.contains("commissione"))
            -> s.issueErrorFeeEstimation
        msg.contains("unknownhost") || msg.contains("dns") -> s.issueErrorNodeUnreachable
        msg.contains("no spendable") || msg.contains("nessun rvn spendibile")
            -> s.issueErrorInsufficientFunds
        msg.contains("pinata") && (msg.contains("jwt") || msg.contains("auth") || msg.contains("scaduto"))
            -> s.issueErrorIpfsAuth
        msg.contains("ipfs") || msg.contains("caricamento ipfs fallito")
            -> s.issueErrorIpfsFailed
        msg.contains("invalid address") || msg.contains("indirizzo non valido")
            -> s.issueErrorInvalidAddress
        else -> "${s.issueFailed}: ${e.message ?: ""}"
    }
}
```

---

### `MainActivity.kt` — New `IssueStep` sealed class

**Analog:** `WriteTagStep` enum (WriteTagScreen.kt lines 48-57) — existing step-state-enum pattern for the NFC programming flow.

**WriteTagStep analog** (lines 48-57):
```kotlin
enum class WriteTagStep {
    WAIT_TAG,
    PROCESSING,
    SUCCESS,
    ERROR
}
```

**Recommended IssueStep sealed class** (in MainActivity.kt, alongside existing state fields like `writeTagStep`):
```kotlin
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
        NFC_PROGRAMMING  // only for combined issue+write flow
    }
}

// State field in MainViewModel (alongside issueLoading, issueResult, issueSuccess at lines 258-264):
var issueStep by mutableStateOf<IssueStep>(IssueStep.Idle)
```

**ViewModel state field pattern** (lines 258-264):
```kotlin
var issueLoading by mutableStateOf(false)
var issueResult by mutableStateOf<String?>(null)
var issueSuccess by mutableStateOf<Boolean?>(null)
```

---

### `MainActivity.kt` — processIssueAndWrite combined flow (lines 2233-2325)

**Analog:** `processStandaloneWrite` (lines 2177-2209) — same `Result<WriteTagKeys>` return pattern.

**Existing pattern** (lines 2233-2325) — step-by-step `Result.failure(Exception(...))` with hardcoded Italian strings:
```kotlin
private suspend fun processIssueAndWrite(tag: android.nfc.Tag, uid: ByteArray): Result<WriteTagKeys> {
    // ...
    // 4. Upload metadata to IPFS
    val ipfsHash = uploadMetadata(metadata, am)
        ?: return Result.failure(Exception("Caricamento IPFS fallito"))
    // 5. Issue the Ravencoin asset on-chain
    val txid = try {
        wm.issueAssetLocal(fullName, ...)
    } catch (e: Exception) {
        return Result.failure(Exception("Emissione Ravencoin fallita: ${e.message}"))
    }
    // ...
}
```

**Add error classification pattern** — replace `Result.failure(Exception("..."))` with `classifyIssuanceError`:
```kotlin
// In processIssueAndWrite, replace hardcoded error strings:
val txid = try {
    wm.issueAssetLocal(fullName, ...)
} catch (e: Exception) {
    val msg = classifyIssuanceError(e, getStrings())
    return Result.failure(Exception(msg))
}
```

**Step state pattern** — set `issueStep` before and during each phase:
```kotlin
issueStep = IssueStep.InProgress(IssueStep.StepName.IPFS_UPLOAD)
// ... do IPFS upload ...
issueStep = IssueStep.Success(IssueStep.StepName.IPFS_UPLOAD)

issueStep = IssueStep.InProgress(IssueStep.StepName.ISSUING)
// ... do issuance ...
issueStep = IssueStep.Success(IssueStep.StepName.ISSUING)
```

**onTagTapped pattern** (lines 2120-2148) — reference for `viewModelScope.launch` + `withContext(Dispatchers.IO)` + step state transitions:
```kotlin
fun onTagTapped(tag: android.nfc.Tag) {
    val uid = ntag424.readTagUid(tag) ?: run {
        writeTagStep = WriteTagStep.ERROR
        writeTagError = "Impossibile leggere l'UID del tag. Riprova."
        return
    }
    viewModelScope.launch {
        writeTagStep = WriteTagStep.PROCESSING
        writeTagError = null
        val result = withContext(Dispatchers.IO) {
            if (isStandaloneWrite) processStandaloneWrite(tag, uid)
            else processIssueAndWrite(tag, uid)
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
```

---

### `IssueAssetScreen.kt` — Multi-step progress indicator + tappable txid

**Analog:** `WriteTagScreen.kt` — LoadingStep (lines 240-251) and ErrorStep (lines 399-414) for progress display pattern. `TransactionDetailsScreen.kt` (lines 283-307) for tappable explorer link pattern.

**LoadingStep composable pattern** (WriteTagScreen.kt lines 240-251):
```kotlin
@Composable
private fun LoadingStep(title: String, subtitle: String) {
    CircularProgressIndicator(
        color = RavenOrange,
        strokeWidth = 3.dp,
        modifier = Modifier.size(64.dp)
    )
    Spacer(Modifier.height(32.dp))
    Text(title, ...)
    Spacer(Modifier.height(12.dp))
    Text(subtitle, ...)
}
```

**New multi-step progress composable pattern** (to add in IssueAssetScreen.kt):
```kotlin
@Composable
private fun IssueStepIndicator(currentStep: IssueStep, strings: AppStrings) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        // Render each step with icon + label, colored by status
        when (currentStep) {
            is IssueStep.Idle -> { /* hidden */ }
            is IssueStep.InProgress -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringForStep(currentStep.step, strings))
                }
            }
            is IssueStep.Success -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, ... tint = AuthenticGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringForStep(currentStep.step, strings))
                }
            }
            is IssueStep.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, ... tint = NotAuthenticRed, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(currentStep.error, color = NotAuthenticRed)
                }
            }
        }
    }
}
```

**Tappable txid pattern** — reuse the explorer link from TransactionDetailsScreen.kt (lines 283-307):
```kotlin
// TransactionDetailsScreen.kt lines 283-307
OutlinedButton(
    onClick = {
        val uri = android.net.Uri.parse(AppConfig.EXPLORER_URL + txid)
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            )
        } catch (_: android.content.ActivityNotFoundException) {
            // No browser available; silent
        }
    },
    border = BorderStroke(1.dp, RavenOrange),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = RavenOrange),
    modifier = Modifier.fillMaxWidth()
) {
    Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(16.dp))
    Spacer(modifier = Modifier.width(8.dp))
    Text(strings.txDetailsViewOnExplorer, fontWeight = FontWeight.SemiBold)
}
```

**Result banner pattern** (IssueAssetScreen.kt lines 256-269) — extend with tappable txid:
```kotlin
// Existing pattern — add txid click handling
resultSuccess?.let { success ->
    Card(
        colors = CardDefaults.cardColors(containerColor = if (success) AuthenticGreenBg else NotAuthenticRedBg),
        border = BorderStroke(1.dp, if (success) AuthenticGreen.copy(0.4f) else NotAuthenticRed.copy(0.4f)),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (success) Icons.Default.CheckCircle else Icons.Default.Error, contentDescription = null,
                tint = if (success) AuthenticGreen else NotAuthenticRed, modifier = Modifier.size(20.dp))
            // Wrap resultMessage in ClickableText if txid present, or add "View on explorer" link below
            Text(resultMessage ?: "", color = if (success) AuthenticGreen else NotAuthenticRed, style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

**IssueAssetScreen composable API pattern** (lines 80-100) — the boundary per C-03. Add new parameters:
```kotlin
@Composable
fun IssueAssetScreen(
    mode: IssueMode,
    isLoading: Boolean,
    resultMessage: String?,
    resultSuccess: Boolean?,
    // New parameters for Phase 40:
    currentStep: IssueStep = IssueStep.Idle,        // drives multi-step progress
    issuedTxid: String? = null,                      // non-null after successful issuance, for tappable link
    // ... existing parameters unchanged ...
)
```

**SubmitButton composable pattern** (IssueAssetScreen.kt lines 709-725) — gate on `currentStep`:
```kotlin
@Composable
private fun SubmitButton(text: String, loading: Boolean, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading && currentStep is IssueStep.Idle,  // ← gate on Idle
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}
```

---

### `AppStrings.kt` — New error message and step label keys

**Analog:** Existing issue strings at lines 359-362, `txHistoryConfirmations` at line 442, and all other localization keys.

**Existing issue result strings** (lines 359-362):
```kotlin
var issueRootSuccess: String = ""
var issueSubSuccess: String = ""
var issueUniqueSuccess: String = ""
var issueFailed: String = ""
```

**Existing confirmation pattern** (line 442):
```kotlin
var txHistoryConfirmations: String = "%1\$d/6 confirmations"

// Italian (line 988):
txHistoryConfirmations = "%1\$d/6 conferme"
```

**New string keys to add** (after line 362, before `// Shared`):
```kotlin
// Phase 40: Error classification
var issueErrorInsufficientFunds: String = ""
var issueErrorDuplicateName: String = ""
var issueErrorNodeUnreachable: String = ""
var issueErrorTimeout: String = ""
var issueErrorFeeEstimation: String = ""
var issueErrorIpfsAuth: String = ""
var issueErrorIpfsFailed: String = ""
var issueErrorInvalidAddress: String = ""
var issueErrorNoWallet: String = ""

// Phase 40: Multi-step progress
var issueStepIpfsUpload: String = ""
var issueStepBalanceCheck: String = ""
var issueStepNameCheck: String = ""
var issueStepIssuing: String = ""
var issueStepConfirming: String = ""
var issueStepNfcProgramming: String = ""
```

**Italian values pattern** (AppStrings.kt Italian section, around line 939):
```kotlin
// English (line 668):
issueFailed = "Issuance failed"

// Italian (line 939):
issueFailed = "Emissione fallita"
```

**English values for Phase 40** (add around line 668):
```kotlin
issueErrorInsufficientFunds = "Insufficient funds. Send RVN to your brand wallet and try again."
issueErrorDuplicateName = "Asset name already exists. Choose a different name."
issueErrorNodeUnreachable = "RPC node unreachable. Check your internet connection and try again."
issueErrorTimeout = "Request timed out. The transaction may have been broadcast — check your wallet."
issueErrorFeeEstimation = "Fee estimation failed. The network may be congested."
issueErrorIpfsAuth = "IPFS authentication expired. Update your Pinata JWT in Settings."
issueErrorIpfsFailed = "IPFS upload failed. Check your connection and retry."
issueErrorInvalidAddress = "Invalid Ravencoin address format."
issueErrorNoWallet = "No Ravencoin wallet found. Create or restore a wallet first."
issueStepIpfsUpload = "Uploading to IPFS..."
issueStepBalanceCheck = "Checking balance..."
issueStepNameCheck = "Checking name availability..."
issueStepIssuing = "Issuing on Ravencoin..."
issueStepConfirming = "Confirming (%d/6)..."
issueStepNfcProgramming = "Programming NFC tag..."
```

**Italian values for Phase 40** (add around line 939):
```kotlin
issueErrorInsufficientFunds = "Fondi insufficienti. Invia RVN al wallet brand e riprova."
issueErrorDuplicateName = "Nome asset gia' esistente. Scegli un nome diverso."
issueErrorNodeUnreachable = "Nodo RPC irraggiungibile. Controlla la connessione e riprova."
issueErrorTimeout = "Richiesta scaduta. La transazione potrebbe essere stata emessa — controlla il wallet."
issueErrorFeeEstimation = "Stima commissione fallita. La rete potrebbe essere congestionata."
issueErrorIpfsAuth = "Autenticazione IPFS scaduta. Aggiorna il JWT Pinata in Impostazioni."
issueErrorIpfsFailed = "Caricamento IPFS fallito. Controlla la connessione e riprova."
issueErrorInvalidAddress = "Formato indirizzo Ravencoin non valido."
issueErrorNoWallet = "Nessun wallet Ravencoin trovato. Crea o ripristina un wallet prima."
issueStepIpfsUpload = "Caricamento IPFS..."
issueStepBalanceCheck = "Verifica disponibilita'..."
issueStepNameCheck = "Verifica disponibilita'..."
issueStepIssuing = "Emissione in corso..."
issueStepConfirming = "Conferma in corso..."
issueStepNfcProgramming = "Programmazione tag NFC..."
```

**Localization value assignment pattern** (AppStrings.kt lines 451-452):
```kotlin
private fun cloneStrings(base: AppStrings): AppStrings =
    Gson().fromJson(Gson().toJson(base), AppStrings::class.java)

/** English (default) strings. */
val stringsEn = AppStrings().apply {
    // all string assignments here...
}
```

---

### `AssetManager.kt` — Optional `checkAssetNameExists` method

**Analog:** `AssetManager.kt` unrevokeAsset (lines 349-360) — simple GET request returning `AssetOperationResult`.

**Pattern for existing GET-style call** (lines 369-388 — checkRevocationStatus):
```kotlin
fun checkRevocationStatus(assetName: String): RevocationStatus {
    return try {
        val request = Request.Builder()
            .url("$apiBaseUrl/api/assets/${assetName.uppercase()}/revocation")
            .get()
            .build()
        val response = http.newCall(request).execute()
        val obj = gson.fromJson(response.body?.string(), JsonObject::class.java)
        RevocationStatus(...)
    } catch (e: Exception) {
        RevocationStatus(revoked = true, reason = "...")
    }
}
```

**Recommended pattern for new method** (consistent with existing GET-style):
```kotlin
fun checkAssetNameExists(assetName: String): Boolean {
    return try {
        val request = Request.Builder()
            .url("$apiBaseUrl/api/brand/check-name?asset_name=${assetName.uppercase()}")
            .header("X-Admin-Key", adminKey)
            .get()
            .build()
        val response = http.newCall(request).execute()
        val obj = gson.fromJson(response.body?.string(), JsonObject::class.java)
        obj["exists"]?.asBoolean == true
    } catch (e: Exception) {
        false  // Fail open for pre-flight check: let backend decide at issuance
    }
}
```

---

### `MainActivity.kt` — Retry with backoff wrapping (safe errors)

**Analog:** `RetryUtils.retryWithBackoff()` usage in `RetryUtils.kt` (lines 37-68) — existing utility used by FeeEstimator and ElectrumX calls.

**Imports pattern** (RetryUtils.kt lines 1-7):
```kotlin
import kotlinx.coroutines.delay
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
```

**Core retry utility** (RetryUtils.kt lines 37-68):
```kotlin
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 5,
    initialDelayMs: Long = 1000L,
    backoffMultiplier: Double = 2.0,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    var currentDelay = initialDelayMs
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            val isTransient = isTransientError(e)
            if (attempt < maxAttempts - 1 && isTransient) {
                delay(currentDelay)
                currentDelay = (currentDelay * backoffMultiplier).toLong()
            } else {
                throw e
            }
        }
    }
    throw lastException ?: IllegalStateException("Retry logic failed with no exception")
}
```

**Transient error detection** (RetryUtils.kt lines 86-99):
```kotlin
fun isTransientError(e: Exception): Boolean {
    when (e) {
        is SocketTimeoutException -> return true
        is UnknownHostException -> return true
        is IOException -> {
            val message = e.message?.lowercase() ?: return false
            return message.contains("timeout") || message.contains("connection") ||
                   message.contains("network") || message.contains("temporary")
        }
        else -> return false
    }
}
```

**Recommended usage pattern** — wrap safe operations in `RetryUtils.retryWithBackoff`:
```kotlin
val txid = try {
    RetryUtils.retryWithBackoff(maxAttempts = 5) {
        withContext(Dispatchers.IO) {
            wm.issueAssetLocal(assetName, qty.toDouble(), toAddress, units = 0, reissuable = reissuable, ipfsHash = ipfsHash)
        }
    }
} catch (e: Exception) {
    if (RetryUtils.isTransientError(e)) {
        // Transient — retry exhausted, but safe: no tx broadcast
        issueSuccess = false
        issueResult = classifyIssuanceError(e, getStrings())
        return@launch
    }
    // Non-transient — classify immediately
    issueSuccess = false
    issueResult = classifyIssuanceError(e, getStrings())
    return@launch
}
```

---

## Shared Patterns

### Sealed class state machine for multi-step flows
**Source:** `WriteTagStep` enum (WriteTagScreen.kt lines 48-57)
**Apply to:** `MainActivity.kt` — new `IssueStep` sealed class
**Rationale:** The existing `WriteTagStep` enum drives the NFC programming flow screen (WAIT_TAG → PROCESSING → SUCCESS/ERROR). Phase 40 extends this pattern for the issuance flow with more granular steps, using a sealed class to carry step-specific metadata (error message, retry flag).

### ViewModel coroutine dispatch (viewModelScope.launch + withContext(Dispatchers.IO))
**Source:** All issuance callbacks in MainActivity.kt (lines 1611-1677)
**Apply to:** All issuance callbacks and `processIssueAndWrite`
**Pattern:**
```kotlin
viewModelScope.launch {
    issueLoading = true
    // (or issueStep = IssueStep.InProgress(...))
    try {
        val result = withContext(Dispatchers.IO) {
            // network / blockchain operation
        }
        issueSuccess = true
        issueResult = ...
    } catch (e: Throwable) {
        issueSuccess = false
        issueResult = classifyIssuanceError(e, getStrings())
    } finally {
        issueLoading = false
        // (or issueStep = IssueStep.Idle on final completion)
    }
}
```

### Localized strings via `AppStrings` class + `LocalStrings.current`
**Source:** AppStrings.kt lines 1-11, IssueAssetScreen.kt line 101
**Apply to:** All new error messages and step labels
**Pattern:**
```kotlin
// In composable:
val s = LocalStrings.current

// Use: s.issueErrorInsufficientFunds, s.issueStepIpfsUpload, etc.
```

### Result banner pattern (green/red Card with icon)
**Source:** IssueAssetScreen.kt lines 256-269
**Apply to:** Extended with tappable txid link
**Pattern:**
```kotlin
resultSuccess?.let { success ->
    Card(
        colors = CardDefaults.cardColors(containerColor = if (success) AuthenticGreenBg else NotAuthenticRedBg),
        border = BorderStroke(1.dp, if (success) AuthenticGreen.copy(0.4f) else NotAuthenticRed.copy(0.4f)),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), ...) {
            Icon(if (success) Icons.Default.CheckCircle else Icons.Default.Error, ...)
            Text(resultMessage ?: "", ...)
        }
    }
}
```

### Tappable explorer link (ACTION_VIEW + EXPLORER_URL)
**Source:** TransactionDetailsScreen.kt lines 283-307, AppConfig.kt line 62
**Apply to:** IssueAssetScreen.kt result banner (D-11)
**Pattern:**
```kotlin
val uri = android.net.Uri.parse(AppConfig.EXPLORER_URL + txid)
try {
    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
} catch (_: android.content.ActivityNotFoundException) {
    // No browser available; silent
}
```

### `AssetOperationResult` typed result envelope
**Source:** AssetManager.kt lines 97-102
**Apply to:** revokeAsset bug fix (capture result), checkAssetNameExists return
**Pattern:**
```kotlin
data class AssetOperationResult(
    val success: Boolean,
    val txid: String? = null,
    val assetName: String? = null,
    val error: String? = null
)
```

### Confirmation progress display (N/6 pattern)
**Source:** AppStrings.kt `txHistoryConfirmations` at line 442, `IncomingTxNotificationHelper.kt` lines 78-81
**Apply to:** Post-issuance confirmation tracking (D-10)
**Pattern:**
```
strings.txHistoryConfirmations: "%1$d/6 confirmations"
stringsIt: "%1$d/6 conferme"
```

### Button Loading Spinner pattern
**Source:** IssueAssetScreen.kt SubmitButton (lines 709-725)
**Apply to:** Submit button during multi-step flow
**Pattern:**
```
20.dp white CircularProgressIndicator, 2.dp stroke, disabled container at 30% opacity
```

### `clearIssueResult()` cleanup pattern
**Source:** MainActivity.kt lines 1768-1773
**Apply to:** Reset step state on navigation (Pitfall 3 protection)
```kotlin
fun clearIssueResult() {
    issueResult = null
    issueSuccess = null
    issueStep = IssueStep.Idle  // ← add this
    registerNfcPubId = null
    prefilledTransferAssetName = null
}
```

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `classifyIssuanceError` function | utility | N/A (classification) | No existing error classification function in codebase — new utility. Pattern from RESEARCH.md section "Pattern 1". |
| Confirmation polling after issuance | ViewModel | event-driven (polling) | No existing post-tx confirmation polling on Android side. Tx screen shows raw confirmations from history; wallet polling in WalletPollingWorker uses scripthash.subscribe, not direct polling. |

## Metadata

**Analog search scope:** `/home/ale/Projects/RavenTag/android/app/src/main/java/io/raventag/app/`
**Files scanned:** 8 (MainActivity.kt, IssueAssetScreen.kt, AppStrings.kt, AssetManager.kt, WriteTagScreen.kt, RetryUtils.kt, TransactionDetailsScreen.kt, AppConfig.kt)
**Pattern extraction date:** 2026-04-25
