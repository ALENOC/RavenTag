# Phase 40: Asset Emission UX - Research

**Researched:** 2026-04-25
**Domain:** Android (Kotlin + Jetpack Compose) - Asset issuance error/UX hardening
**Confidence:** HIGH

## Summary

Phase 40 adds robust error classification, pre-issuance validation, multi-step progress indicators, and safe retry policies on top of the existing asset/sub-asset/unique-token issuance flow in the Android app. The issuance mechanism itself (RPC broadcast via `WalletManager.issueAssetLocal()`, ElectrumX failover, RavencoinTxBuilder) already works and must not be broken.

The current error handling in `MainActivity.kt` issuance callbacks (lines 1611-1677) is a single generic `catch(e: Throwable)` that sets `issueResult = getStrings().issueFailed`. No classification, no actionable messaging, no retry. The `processIssueAndWrite` combined flow (lines 2233-2325) uses `Result.failure(Exception(...))` with hardcoded Italian strings but no classification. The `revokeAsset` callback (line 1714) discards the `AssetOperationResult` from `am.revokeAsset()` and always sets `issueSuccess = true`.

The existing `RetryUtils.retryWithBackoff()` (Phase 20 pattern, 5 attempts, 1s base delay, 2x multiplier) can be reused for safe-error retries. The `AssetOperationResult` envelope in `AssetManager.kt` already carries typed results. AppStrings.kt supports 9 languages (4 clone from English). The `Resource.transientError`/`criticalError` pattern from Phase 20 can be used for error surfacing.

**Primary recommendation:** Add error classification in the catch blocks of issuance callbacks and `processIssueAndWrite`, using exception message pattern-matching to select localized string keys. Add a sealed class for issuance step state to drive the multi-step progress indicator. Reuse `RetryUtils.retryWithBackoff` for safe (transient) errors. Fix the `revokeAsset` result-discard bug.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Classify known RPC errors into Italian user-facing messages. Fallback to raw error message for unknown errors. All messages defined in `AppStrings.kt` for localization across all 9 app languages.
- **D-02:** IPFS upload errors classified separately from RPC issuance errors. IPFS failures allow retry without restarting the entire form.
- **D-03:** Known error categories to classify: insufficient funds, duplicate asset name, RPC node unreachable/connection refused, RPC timeout, fee estimation failure, IPFS gateway down, IPFS auth expired, and invalid address format.
- **D-04:** Full pre-flight validation in three sequential steps on submit: (1) Wallet balance check, (2) Asset name uniqueness check via backend API call, (3) IPFS metadata upload.
- **D-05:** Multi-step progress indicator shown on submit button tap: "Caricamento IPFS..." to "Verifica disponibilita'..." to "Emissione in corso..." to "Conferma in corso...". Each step shows success/failure before advancing.
- **D-06:** IPFS upload triggered on submit (not as separate button, not auto on image select). Sequential steps with clear per-step status. Uploaded CID preserved for retry.
- **D-07:** Auto-retry only safe errors with 5x exponential backoff. Safe errors: connection failures, DNS resolution failures, IPFS upload failures.
- **D-08:** On RPC timeout: do NOT re-broadcast. Query tx status via `getrawtransaction`. If tx landed on-chain, treat as success. If not found, prompt user to retry manually.
- **D-09:** RPC rejections (duplicate name, insufficient funds, invalid params) never auto-retry. Show classified error with suggested action.
- **D-10:** Show confirmation progress after successful issuance: "Pending..." to "N/6 conferme" to "Confermato". Consistent with Phase 30 D-08 receive confirmation pattern. Auto-dismiss banner after 6 confirmations.
- **D-11:** Txid in result banner is tappable. Opens block explorer at `https://ravencoin.network/tx/{txid}`.
- **D-12:** Issued asset appears in transaction history after next WalletScreen sync (Phase 30 D-01 periodic poll).
- **D-13:** Combined "Issue + Write Tag" flow: progress indicator includes NFC programming as distinct step. Tag write step has its own progress since user must hold phone to tag.
- **C-01:** Unique token issuance flow must remain intact. All error handling changes are additive.
- **C-02:** Asset emission currently works. Do not alter successful issuance code path.
- **C-03:** IssueAssetScreen composable API (callback signatures) is the boundary. Error handling improvements happen in MainActivity callbacks and AssetManager.

### Claude's Discretion
- Exact Italian error string content for each classification category
- IPFS retry UX details (inline retry button vs auto-retry within submit flow)
- Balance check threshold display format and minimum balance calculation
- Confirmation progress indicator visual design and animation
- Exact placement of progress step indicator in the IssueAssetScreen layout

### Deferred Ideas (OUT OF SCOPE)
- Burn on-chain in revocation flow
- Asset transfer UX (TRANSFER_ROOT, TRANSFER_SUB modes)
- Notification on confirmation (background tracking + push)
- Em-dash cleanup in RavencoinTxBuilder.kt:907,908
</user_constraints>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Error classification | ViewModel (MainActivity) | AssetManager | MainActivity catch blocks classify exceptions; AssetManager returns typed `AssetOperationResult` |
| User-facing error messages | AppStrings.kt | IssueAssetScreen (composable) | Strings are localized in AppStrings, displayed via resultMessage parameter |
| Pre-issuance validation | ViewModel (MainActivity) | AssetManager | Balance check uses walletInfo; uniqueness check uses AssetManager API call |
| Multi-step progress indicator | IssueAssetScreen (composable) | — | New composable component driven by sealed class from ViewModel |
| Retry policy | ViewModel (MainActivity) | RetryUtils | retryWithBackoff wraps the issuance call; classification decides auto vs manual |
| Confirmation progress | ViewModel (MainActivity) | RavencoinPublicNode | Poll `blockchain.transaction.get` for confirmations after successful txid |
| Combined Issue+Write flow | processIssueAndWrite (MainActivity) | Ntag424Configurator | Existing 7-step flow; add step progress and error classification |
| Revoke bug fix | revokeAsset (MainActivity) | — | Capture AssetOperationResult return value instead of discarding it |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Jetpack Compose | BOM 2024.02+ | Multi-step progress indicator composable | Existing UI framework |
| kotlinx.coroutines | 1.7+ | `viewModelScope.launch`, `withContext(Dispatchers.IO)` | Existing async pattern |
| `RetryUtils.retryWithBackoff()` | Phase 20 | 5x exponential backoff for safe errors | Existing utility, proven in FeeEstimator and ElectrumX calls |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `AssetOperationResult` | internal | Typed result envelope from AssetManager | All issuance callbacks already use this |
| `isTransientError()` | RetryUtils | Classify SocketTimeout/UnknownHost/IOExceptions | Auto-retry decision for safe errors |
| `TransactionNotificationHelper` | Phase 30 | Notification channel for confirmation tracking | Phase 30 created this pattern, reuse confirm pattern |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Exception message parsing | Custom exception types | Custom types need new files; message parsing works with existing `catch(e: Throwable)` without restructuring |
| Sealed class for steps | Boolean `isLoading` | Current single bool cannot express multi-step; sealed class is the standard Compose pattern |
| ViewModel step state | Local composable state | The step state must survive recomposition and be set from async callbacks; ViewModel state is the right level |

**Installation:** No new dependencies. All patterns use existing libraries.

**Version verification:** Not applicable -- all dependencies are already in the project.

## Architecture Patterns

### Current Issuance Flow (unchanged core)
```
User taps Submit
  -> ViewModel callback (e.g., issueRootAsset)
  -> issueLoading = true
  -> WalletManager.issueAssetLocal() on Dispatchers.IO
     -> RavencoinPublicNode: get UTXOs, fee rate
     -> RavencoinTxBuilder: build + sign transaction
     -> RavencoinPublicNode.broadcast(rawHex)
     -> returns txid string
  -> on success: issueSuccess=true, issueResult=formatted message
  -> on failure: catch(Throwable), issueSuccess=false, issueResult=issueFailed
  -> finally: issueLoading = false
```

### Phase 40 Enhanced Flow (proposed)
```
User taps Submit
  -> Multi-step: Step 1: IPFS Upload (if image attached)
     -> Show "Caricamento IPFS..."
     -> uploadMetadata() with retryWithBackoff (5x exp)
     -> Show green check on success, red X + Retry on failure
     -> Preserve CID for retry
  -> Multi-step: Step 2: Balance check [pre-issuance validation]
     -> Show "Verifica disponibilita'..."
     -> walletInfo.balanceRvn >= burnFee + networkFee
     -> Show inline warning if insufficient
  -> Multi-step: Step 3: Asset name uniqueness [pre-issuance validation]
     -> Show "Verifica disponibilita'..."
     -> Check ownedAssets list for duplicate name
  -> Multi-step: Step 4: Issuance with classification
     -> Show "Emissione in corso..."
     -> RetryUtils.retryWithBackoff for safe errors
     -> Classification catch: classify exception, pick AppStrings key
     -> On failure: show classified message with suggested action
  -> Multi-step: Step 5: Confirmation tracking (N/6)
     -> Show "Conferma in corso..." or "Pending..."
     -> Poll RavencoinPublicNode for confirmations
     -> Auto-dismiss after 6
```

### Combined Issue+Write Tag Enhanced Flow
```
User taps "Issue Unique Token & Program NFC Tag"
  -> WriteTagStep.WAIT_TAG (user taps tag)
  -> Step 1 (PROCESSING): Preflight tag writability check
  -> Step 2 (PROCESSING): Derive chip keys from backend
  -> Step 3 (PROCESSING): Build IPFS metadata + upload
  -> Step 4 (PROCESSING): Issue asset on-chain (classified error)
  -> Step 5 (PROCESSING): Program tag with keys (user holds phone)
  -> Step 6 (PROCESSING): Register chip on backend
  -> Step 7 (POST_ISSUANCE): Confirmation tracking (N/6)
  -> WriteTagStep.SUCCESS or ERROR at any step failure
```

### Recommended Project Structure (no new files needed)
```
MainActivity.kt
  - Enhanced catch blocks in issueRootAsset, issueSubAsset, issueUniqueToken (lines 1611-1677)
  - Fixed revokeAsset result handling (line 1714-1729)
  - Enhanced processIssueAndWrite error classification (lines 2233-2325)
  - New sealed class: IssueStep { IPFS_UPLOAD, BALANCE_CHECK, NAME_CHECK, ISSUING, CONFIRMING, COMPLETE }

IssueAssetScreen.kt
  - Multi-step progress indicator composable (replacing simple isLoading)
  - Tappable txid link in result banner (D-11)
  - New parameter: currentStep: IssueStep? or similar sealed class

AppStrings.kt
  - New string keys for error classification (9 languages, 4 cloned)
  - New string keys for step labels

AssetManager.kt
  - No changes to issuance methods (C-02)
  - Possibly add checkAssetNameExists() API call if backend supports it

RetryUtils.kt
  - No changes needed -- existing retryWithBackoff and isTransientError work
```

### Pattern 1: Error Classification Pattern
**What:** Match exception messages to known patterns, select a localized error string key
**When to use:** In every issuance callback catch block (`catch(e: Throwable)`)
**Example:**
```kotlin
// In MainActivity issuance callbacks, replace:
//   issueSuccess = false; issueResult = getStrings().issueFailed
// with:
issueSuccess = false
issueResult = classifyIssuanceError(e, getStrings())
```

Pin the classification function as a private method in MainActivity:
```kotlin
private fun classifyIssuanceError(e: Throwable, s: AppStrings): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("insufficient funds") || msg.contains("fondi insufficienti")
            -> s.issueErrorInsufficientFunds
        msg.contains("duplicate") || msg.contains("already exists") || msg.contains("gia esiste")
            -> s.issueErrorDuplicateName
        msg.contains("connection refused") || msg.contains("unreachable") || msg.contains("irraggiungibile")
            -> s.issueErrorNodeUnreachable
        msg.contains("timeout")
            -> s.issueErrorTimeout
        msg.contains("fee") && (msg.contains("estimate") || msg.contains("commissione"))
            -> s.issueErrorFeeEstimation
        msg.contains("unknownhost") || msg.contains("dns")
            -> s.issueErrorNodeUnreachable
        msg.contains("owner token") || msg.contains("missing") || msg.contains("mancante")
            -> s.issueErrorMissingOwnerToken
        msg.contains("wallet non disponibile") || msg.contains("no wallet")
            -> s.issueErrorNoWallet
        msg.contains("no spendable") || msg.contains("nessun rvn spendibile")
            -> s.issueErrorInsufficientFunds
        // IPFS-specific errors
        msg.contains("pinata") && msg.contains("jwt") || msg.contains("auth") || msg.contains("scaduto")
            -> s.issueErrorIpfsAuth
        msg.contains("ipfs") || msg.contains("caricamento ipfs fallito")
            -> s.issueErrorIpfsFailed
        // Fallback: show raw message
        else -> "${s.issueFailed}: ${e.message ?: ""}"
    }
}
```

### Pattern 2: Multi-step Progress Sealed Class
**What:** Sealed class representing each step of the issuance flow with its status
**When to use:** Drive the multi-step progress indicator in IssueAssetScreen
**Example:**
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
        NFC_PROGRAMMING  // Only for combined flow
    }
}
```

### Pattern 3: Safe Error Retry Wrapping
**What:** Wrap the issuance call in `retryWithBackoff`, let transient errors retry, rethrow non-transient
**When to use:** For safe errors (connection failures, DNS failures, IPFS upload failures)
**Example:**
```kotlin
val txid = try {
    RetryUtils.retryWithBackoff(maxAttempts = 5) {
        wm.issueAssetLocal(assetName, qty, toAddress, units, reissuable, ipfsHash)
    }
} catch (e: Exception) {
    // Check if exception type is transient
    if (e is SocketTimeoutException || e is UnknownHostException || 
        (e is IOException && e.message?.contains("timeout") == true)) {
        throw e  // Allow retryWithBackoff to handle it
    }
    // Non-transient: classify immediately
    issueSuccess = false
    issueResult = classifyIssuanceError(e, getStrings())
    return@launch
}
```

### Pattern 4: Confirmation Polling
**What:** After successful txid, poll `blockchain.transaction.get` to count confirmations
**When to use:** After issuance succeeds (txid known), on both standalone and combined flows
**Example:**
```kotlin
// After successful issuance with txid
viewModelScope.launch {
    issueStep = IssueStep.InProgress(IssueStep.StepName.CONFIRMING)
    val node = RavencoinPublicNode(getApplication())
    var confirmations = 0
    while (confirmations < 6 && isActive) {
        delay(30_000) // Poll every 30 seconds
        try {
            val tx = node.callElectrumRawOrNull("blockchain.transaction.get", listOf(txid, true))
            val height = tx?.asJsonObject?.get("height")?.asInt ?: 0
            val tip = node.getBlockHeight() ?: 0
            confirmations = if (height > 0) tip - height + 1 else 0
            // Update step state with N/6 for display
        } catch (_: Exception) { /* keep waiting */ }
    }
    issueStep = if (confirmations >= 6) {
        IssueStep.Success(IssueStep.StepName.CONFIRMING)
    } else {
        IssueStep.InProgress(IssueStep.StepName.CONFIRMING) // signal pending
    }
}
```

### Anti-Patterns to Avoid
- **Changing IssueAssetScreen callback signatures:** C-03 requires the composable API to remain stable. All error handling goes in the ViewModel callbacks.
- **Modifying successful code path:** C-02. Changes are additive try/catch wrappers, not restructuring.
- **Re-broadcasting on timeout:** D-08. Use `blockchain.transaction.get` to check if tx landed. Never re-broadcast blindly.
- **Hand-rolling IPFS retry:** Use `RetryUtils.retryWithBackoff` consistently.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Exponential backoff retry | Custom loop with Thread.sleep | `RetryUtils.retryWithBackoff()` | Existing, tested in FeeEstimator and ElectrumX calls, handles transient classification |
| Result envelope | Custom success/error wrapper | `AssetOperationResult` | Already used by all AssetManager methods |
| Confirmation progress notification | Custom notification lifecycle | `TransactionNotificationHelper` | Phase 30 pattern, proven for send flow |
| Error surfacing to UI | Custom dialog logic | `reportAsyncError()` (transientError/criticalError) | Phase 20 pattern, handles auto-dismiss and modal variants |

**Key insight:** The project already has battle-tested patterns for retry, error surfacing, and confirmation tracking. Phase 40 implementation should reuse these patterns rather than creating new infrastructure.

## Runtime State Inventory

> This phase is an additive UX improvement -- no rename, refactor, or migration. Skip.

## Common Pitfalls

### Pitfall 1: Silent error in revokeAsset (pre-existing bug)
**What goes wrong:** `revokeAsset` at MainActivity.kt line 1714-1729 calls `am.revokeAsset(...)` but discards the returned `AssetOperationResult` and unconditionally sets `issueSuccess = true`. Result: revocations that fail at the backend level (e.g., auth error, asset not found) appear as successful to the user.
**Root cause:** The `withContext(Dispatchers.IO)` block returns the `AssetOperationResult` but the result is never captured.
**How to avoid:** Capture the result: `val result = withContext(Dispatchers.IO) { am.revokeAsset(BurnParams(...)) }` then check `result.success`.
**Warning signs:** Assets that remain unrevoked after UI shows "revocato".

### Pitfall 2: Over-classification of error messages
**What goes wrong:** Exception messages from different sources (ElectrumX, WalletManager, RavencoinTxBuilder, AssetManager) are unreliable for pattern matching. Messages may change when ElectrumX server software is updated or when Ravencoin Core changes.
**Why it happens:** The project uses exception message string matching (e.g., `msg.contains("insufficient funds")`) rather than typed exception classes.
**How to avoid:** Keep classification as a single `when` block with fallback to raw message. Log the original message for debugging. Accept that some errors will fall through to the default case.
**Warning signs:** New ElectrumX versions causing misclassified errors.

### Pitfall 3: Race condition in multi-step state
**What goes wrong:** If the user dismisses the screen or navigates away while a step is in progress, the coroutine continues running and may update stale state.
**Why it happens:** `viewModelScope.launch` is not cancelled on navigation; IssueStep state lives in the ViewModel.
**How to avoid:** Use `clearIssueResult()` existing pattern to reset step state on navigation. Check `isActive` at each step boundary.
**Warning signs:** "Ghost" step progress shown after returning to the screen.

### Pitfall 4: Double submission during multi-step flow
**What goes wrong:** The user taps Submit during Step 1 (IPFS upload), and the step takes long enough that the user taps Submit again.
**Why it happens:** The step indicator replaces the submit button, but if button enablement is not properly gated, re-taps can occur.
**How to avoid:** Gate the submit button on `issueStep is IssueStep.Idle`. Once any step is in progress, the button must be disabled. The `isLoading` parameter already does this, but the step state must also gate it.
**Warning signs:** Multiple simultaneous IPFS uploads or issuance RPC calls.

### Pitfall 5: Polling for confirmation after processIssueAndWrite
**What goes wrong:** The `processIssueAndWrite` flow is a `suspend` function that returns `Result<WriteTagKeys>`. The confirmation polling needs to run after the combined flow completes, but `onTagTapped` only sets `writeTagStep = SUCCESS` or `ERROR`.
**Why it happens:** The post-issuance confirmation phase is not part of the existing flow; it's additive.
**How to avoid:** After `processIssueAndWrite` returns success, start a separate coroutine for confirmation polling. Do not integrate it into the processIssueAndWrite function itself.
**Warning signs:** Confirmation progress never appears after combined flow.

## Code Examples

### Current revokeAsset bug (must fix)
```kotlin
// MainActivity.kt lines 1714-1729
fun revokeAsset(assetName: String, reason: String, adminKey: String) {
    val am = AssetManager(context = getApplication(), adminKeyStorage = adminKeyStorage!!)
    viewModelScope.launch {
        issueLoading = true
        try {
            withContext(Dispatchers.IO) {
                am.revokeAsset(BurnParams(assetName, reason = reason, burnOnChain = false))
            }
            issueSuccess = true  // BUG: always true
            issueResult = "Asset $assetName revocato"
        } catch (e: Throwable) {
            issueSuccess = false; issueResult = e.message ?: "Revoca fallita"
        } finally { issueLoading = false }
    }
}
```

### Current generic catch pattern in issuance callbacks
```kotlin
// MainActivity.kt line 1625-1627 (issueRootAsset example)
try {
    val txid = withContext(Dispatchers.IO) {
        wm.issueAssetLocal(assetName, qty.toDouble(), toAddress, units = 0, reissuable = reissuable, ipfsHash = ipfsHash)
    }
    issueSuccess = true
    issueResult = s.issueRootSuccess.replace("%1", assetName).replace("%2", "${txid.take(16)}...")
} catch (e: Throwable) {
    issueSuccess = false; issueResult = getStrings().issueFailed  // Generic, no classification
}
```

### Existing retryWithBackoff usage pattern
Source: `android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt`
```kotlin
RetryUtils.retryWithBackoff(maxAttempts = 5, initialDelayMs = 1000L, backoffMultiplier = 2.0) {
    networkCall()  // Will retry on SocketTimeout, UnknownHost, transient IOException
}
```

### Existing isTransientError classification
Source: `android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt`
```kotlin
fun isTransientError(e: Exception): Boolean = when (e) {
    is SocketTimeoutException -> true
    is UnknownHostException -> true
    is IOException -> {
        val msg = e.message?.lowercase() ?: return false
        msg.contains("timeout") || msg.contains("connection") || msg.contains("network") || msg.contains("temporary")
    }
    else -> false
}
```

### Burn fee constants for balance check
Source: `android/app/src/main/java/io/raventag/app/wallet/RavencoinTxBuilder.kt`
```kotlin
const val BURN_ROOT_SAT = 50_000_000_000L    // 500 RVN
const val BURN_SUB_SAT = 10_000_000_000L     // 100 RVN
const val BURN_UNIQUE_SAT = 500_000_000L     // 5 RVN
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Generic `issueFailed` string | Classified error with specific message + action | Phase 40 | Users get actionable guidance instead of "emissione fallita" |
| Single `isLoading` boolean | Multi-step sealed class with per-step status | Phase 40 | Users see which step failed and can act on it |
| No retry on issuance failure | Safe-error retry with 5x exp backoff + timeout check | Phase 40 | Transient failures auto-recover without user action |
| `revokeAsset` always succeeds | `revokeAsset` checks AssetOperationResult | Phase 40 | Silent revocation failures surface to user |

**Deprecated/outdated:**
- `revokeAsset` result discard (MainActivity.kt line 1721): confirmed bug via code review; fix is in scope since it's a silent failure elimination

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Exception message string matching is reliable enough for the 8 known categories | Standard Stack | ElectrumX/Ravencoin error messages vary by version; misclassification falls back to raw message which is acceptable |
| A2 | Backend does not have a dedicated name-uniqueness API endpoint | Pre-issuance Validation | Pre-issuance validation must use ownedAssets list from frontend cache. If backend endpoint exists, use it instead |
| A3 | `RavencoinPublicNode.callElectrumRawOrNull(method, params)` can query `blockchain.transaction.get` for timeout-check | Confirmation Tracking | If `callElectrumRawOrNull` cannot reach any server, timeout handling degrades to "assume failure, prompt retry" which is the D-08 fallback |
| A4 | The `walletInfo.balanceRvn` value is current enough for pre-issuance balance check | Pre-issuance Validation | If wallet balance is stale (not refreshed), the check may pass when on-chain balance is insufficient. The `issueAssetLocal()` will fail with its own error. This is acceptable as a best-effort pre-check |

## Open Questions

1. **Backend name uniqueness endpoint?**
   - What we know: `AssetManager` has `issueAsset`/`issueSubAsset`/`issueUniqueToken` but no dedicated "check name exists" endpoint. The ownedAssets list in the frontend cache is the closest proxy.
   - What's unclear: Whether the backend provides an endpoint like `/api/brand/check-name` or if we should just check against the local cache.
   - Recommendation: Use local ownedAssets list for pre-flight check (ownedAssets contains all brand assets). Consider adding a backend endpoint in Phase 50 if needed.

2. **Exact error strings from WalletManager.issueAssetLocal()?**
   - What we know: Uses `error("...")` (IllegalStateException), `require(...)` (IllegalArgumentException), and exceptions from RavencoinTxBuilder and RavencoinPublicNode.
   - What's unclear: The full set of possible error messages without running the code against all failure modes.
   - Recommendation: Use broad message pattern matching (.contains) in the classification function. Add logging (`Log.e`) of the original message for debugging. Fall through to raw message for unclassified errors.

3. **getrawtransaction availability?**
   - What we know: `RavencoinPublicNode` uses ElectrumX protocol, which provides `blockchain.transaction.get`. This is available via `callElectrumRawOrNull`.
   - What's unclear: Whether the verbose=true format returns a `height` field for all tx states (mempool vs confirmed).
   - Recommendation: In timeout handling, check if `blockchain.transaction.get` returns a result. If height > 0, tx is confirmed. If height == null or result is error, tx not found.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4 + Robolectric (Android project) |
| Config file | Not checked -- Phase 30 used @Ignore for Robolectric-dependent tests |
| Quick run command | `./gradlew :app:testDebugUnitTest -x lint` |
| Full suite command | `./gradlew :app:testDebugUnitTest` |

### Phase Requirements to Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| D-01 | Error classification maps known exception messages to correct string keys | unit | `./gradlew :app:testDebugUnitTest --tests "*IssueErrorClassificationTest*"` | Will be Wave 0 |
| D-07 | retryWithBackoff wraps transient errors correctly | unit | Reuse existing RetryUtils tests | Existing tests |
| D-10 | Confirmation polling logic | unit | `./gradlew :app:testDebugUnitTest --tests "*ConfirmationTest*"` | Wave 0 |

### Sampling Rate
- **Per task commit:** Not applicable (no existing test pattern for per-commit runs observed)
- **Per wave merge:** `./gradlew :app:testDebugUnitTest`
- **Phase gate:** Full test suite green before verify

### Wave 0 Gaps
- [ ] `IssueErrorClassificationTest.kt` -- unit tests for `classifyIssuanceError` function (pure logic, no Android deps)
- [ ] `ConfirmationPollingTest.kt` -- unit tests for confirmation tracking logic (pure logic)
- [ ] No UI test detected for the composable step indicator (Jetpack Compose UI tests may need Compose Test dependency -- skip for Phase 40, manual verification only)

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Android SDK 34 | Compile target | ✓ | 34 | — |
| Kotlin 1.9 | Language | ✓ | 1.9.x | — |
| Gradle | Build system | ✓ | 8.x | — |
| OkHttp | HTTP client | ✓ | 4.x | — |
| Bouncy Castle | Crypto | ✓ | 1.77 | — |
| ElectrumX server | Broadcast/confirmation check | Runtime | — | Fallback server list, fail-closed |

**Missing dependencies with no fallback:** None -- all dependencies are already in the project.

## Sources

### Primary (HIGH confidence)
- Codebase inspection of `MainActivity.kt`, `IssueAssetScreen.kt`, `AssetManager.kt`, `WalletManager.kt`, `AppStrings.kt`, `RetryUtils.kt`, `RavencoinPublicNode.kt`, `RavencoinTxBuilder.kt`, `TransactionNotificationHelper.kt`

### Secondary (MEDIUM confidence)
- `40-CONTEXT.md` — Phase decisions and constraints (D-01 through D-13, C-01 through C-03)
- `.planning/STATE.md` — Project progress and recent decisions
- `.planning/PROJECT.md` — Milestone focus and requirements

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All patterns and libraries verified in codebase
- Architecture: HIGH - Existing flows well understood, changes are additive
- Pitfalls: HIGH - revokeAsset bug confirmed via code read, other patterns observed in existing code

**Research date:** 2026-04-25
**Valid until:** 2026-05-25 (codebase is stable, no expected breaking changes)
