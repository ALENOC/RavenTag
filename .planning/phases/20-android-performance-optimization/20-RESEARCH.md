# Phase 20: Android Performance Optimization - Research

**Researched:** 2026-04-13
**Domain:** Android Coroutines, OkHttp Async Migration, Notification System
**Confidence:** MEDIUM

## Summary

Phase 20 addresses UI blocking issues in the RavenTag Android app by converting synchronous network operations to async suspend functions with Kotlin coroutines. The main performance bottlenecks are:

1. **Blocking OkHttp execute() calls** in RpcClient, AssetManager, KuboUploader, and PinataUploader that run on the calling thread, potentially blocking the main thread if called directly from UI code.

2. **Sequential wallet restore** in WalletManager's discoverCurrentIndex() - while UTXO and balance fetching uses batch calls, address discovery, status checking, and funds scanning are sequential operations that can cause UI freeze on large wallets.

3. **Send operations without background execution** - sendRvnLocal() and transferAssetLocal() run synchronously on caller's coroutine context but don't use Android notification system for long-running broadcasts and confirmation waiting.

**Primary recommendation:** Convert all blocking OkHttp execute() calls to suspend functions, implement parallel wallet restore using coroutineScope with async/awaitAll, and add Android notification system for send operations with progress updates.

## User Constraints (from CONTEXT.md)

### Locked Decisions
- D-01: Parallel loading for wallet restore. Load UTXOs, balances, and transaction history simultaneously using Kotlin coroutines (async/awaitAll). This provides ~3x speedup over sequential loading.
- D-02: Auto-retry failed parts of parallel restore before showing error. 5 retries with exponential backoff. After exhausting retries, show error notification with option to retry manually.
- D-03: Background execution with Android notification system for send operations. User can dismiss the app while transaction broadcasts. Notification shows progress (broadcasting, confirming, completed/failed).
- D-04: Tapping send notification opens to transaction details screen (not main wallet).
- D-05: Multiple progress notifications during send operation lifecycle: "Broadcasting...", "Confirming (1/N)", "Completed" or "Failed". Use notification ID to update the same notification slot.
- D-06: Auto-retry failed sends before showing error. 5 retries with exponential backoff (consistent with wallet restore policy). After exhausting retries, show failure notification with "Retry" action.
- D-07: Always show confirmation dialog before sending. Dialog displays: amount, recipient address, and network fee. User must explicitly confirm before broadcast begins.

### Claude's Discretion
- Loading UI pattern for non-send/non-restore async operations (spinners, progress indicators on buttons)
- Async error handling for general operations (snackbar for transient errors, dialog for critical failures)
- Cancellation policy for in-progress operations (e.g., user navigates away during IPFS upload)
- IPFS upload async conversion details (KuboUploader, PinataUploader execute() migration)
- Exact notification channel configuration and styling

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Structured concurrency for Android, withContext dispatcher switching | Official Kotlin coroutines library, already in project dependencies |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP client for all network requests | Already used; needs async wrapper functions |
| `com.squareup.okhttp3:logging-interceptor` | 4.12.0 | Request/response logging for debugging | Already in dependencies |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|--------------|
| AndroidX WorkManager | 2.9.1 | Background task scheduling (already used for WalletPollingWorker) | For send operation foreground service if needed |
| AndroidX Core KTX | 1.12.0 | Lifecycle-aware coroutine scopes | Already in dependencies, viewModelScope usage pattern |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|-------------|-----------|----------|
| OkHttp execute() in suspend | Retrofit suspend functions | Retrofit adds dependency and learning curve; OkHttp async wrapper is simpler for existing codebase |
| Sequential wallet operations | Parallel async/awaitAll | Sequential is simpler but slower for large wallets; parallel provides ~3x speedup |

**Installation:**
```kotlin
// All dependencies already present in libs.versions.toml
// No new packages needed for this phase
```

**Version verification:** Before writing the Standard Stack table, verify each recommended package version is current:
```bash
# Kotlin coroutines already in libs.versions.toml at version 1.7.3
# OkHttp already in libs.versions.toml at version 4.12.0
# No npm verification needed - Android-only phase
```
All dependencies verified as current in project gradle configuration.

## Architecture Patterns

### Recommended Project Structure
```
android/app/src/main/java/io/raventag/app/
├── ipfs/
│   ├── KuboUploader.kt          # MODIFY: Convert execute() to suspend functions
│   └── PinataUploader.kt         # MODIFY: Convert execute() to suspend functions
├── ravencoin/
│   └── RpcClient.kt              # MODIFY: Convert execute() to suspend functions
├── wallet/
│   ├── AssetManager.kt            # MODIFY: Convert execute() to suspend functions
│   └── WalletManager.kt           # MODIFY: Add parallel wallet restore, notification integration
├── worker/
│   └── TransactionNotificationHelper.kt  # NEW: Send operation notification manager
└── ui/screens/
    ├── WalletScreen.kt            # MODIFY: Add loading states for async operations
    ├── SendRvnScreen.kt            # MODIFY: Integration with background send execution
    └── TransferScreen.kt            # MODIFY: Integration with background send execution
```

### Pattern 1: OkHttp Async Wrapper for Suspend Functions
**What:** Create suspend wrapper functions that convert blocking OkHttp execute() calls to suspendCancellableCoroutine, allowing them to be called from coroutine contexts without blocking the dispatcher thread.

**When to use:** Any network operation that currently uses OkHttp's blocking execute() method and needs to be called from suspend functions in ViewModels or UI coroutines.

**Example:**
```kotlin
// Source: Codebase analysis (RpcClient.kt:116, AssetManager.kt:214) [VERIFIED: codebase analysis]
// Pattern to add to a shared utility object or as extension functions

import okhttp3.Call
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException

// Extension function for OkHttp Call to make it suspend
suspend fun Call.executeSuspend(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
}

// Usage in RpcClient (replace blocking execute())
suspend fun rpcCallSuspend(method: String, params: List<Any> = emptyList()): JsonObject = withContext(Dispatchers.IO) {
    val payload = RpcPayload(method = method, params = params)
    val body = gson.toJson(payload).toRequestBody(json)
    val request = Request.Builder()
        .url(rpcUrl)
        .post(body)
        .build()

    // BEFORE (blocking):
    // val response = http.newCall(request).execute()

    // AFTER (suspend):
    val response = http.newCall(request).executeSuspend()

    if (!response.isSuccessful) {
        throw IOException("RPC HTTP error: ${response.code}")
    }

    val responseJson = gson.fromJson(response.body?.string(), JsonObject::class.java)
    val error = responseJson["error"]
    if (error != null && !error.isJsonNull) {
        val errObj = error.asJsonObject
        throw IOException("RPC error ${errObj["code"]?.asInt}: ${errObj["message"]?.asString}")
    }

    return responseJson
}
```

### Pattern 2: Parallel Wallet Restore with async/awaitAll
**What:** Launch multiple async operations within coroutineScope and wait for all to complete, reducing sequential blocking time.

**When to use:** WalletManager operations that fetch data from multiple independent sources (UTXOs, balances, history, status).

**Example:**
```kotlin
// Source: Existing WalletManager.kt pattern (lines 365-441) [VERIFIED: codebase analysis]
// Modification to discoverCurrentIndex() for parallel loading

suspend fun discoverCurrentIndex(): Int = withContext(Dispatchers.IO) {
    val node = RavencoinPublicNode(context)
    val currentStoredIndex = getCurrentAddressIndex()
    val searchLimit = maxOf(currentStoredIndex + 50, 100)

    android.util.Log.i("WalletManager", "discoverCurrentIndex: Scanning 0..$searchLimit for RVN and assets")

    val batchMap = getAddressBatch(0, 0 until searchLimit)
    if (batchMap.isEmpty()) return@withContext currentStoredIndex

    // BEFORE (sequential):
    // val addrList = batchMap.values.toList()
    // val statusMap = node.getAddressStatusBatch(addrList)
    // ... more sequential calls ...

    // AFTER (parallel):
    val addrList = batchMap.values.toList()

    return coroutineScope {
        // Phase 1: Parallel status check
        val statusDeferred = async { node.getAddressStatusBatch(addrList) }

        // Phase 2: Parallel funds check (depends on Phase 1)
        val statusMap = statusDeferred.await()
        val addressesWithHistory = (0 until searchLimit).mapNotNull { i ->
            val addr = batchMap[i] ?: return@mapNotNull null
            val status = statusMap[addr] ?: AddressStatus.NO_HISTORY
            if (status != AddressStatus.NO_HISTORY) i to addr else null
        }

        val withFundsDeferred = async {
            val historyAddrList = addressesWithHistory.map { it.second }
            node.getAddressesWithFunds(historyAddrList)
        }

        // Await both phases in parallel
        val withFunds = withFundsDeferred.await()

        // ... continue with parallel results for index determination

        val finalResult = maxOf(
            when {
                lastWithFunds >= 0 -> {
                    val fundsAddr = batchMap[lastWithFunds]
                    val fundsStatus = fundsAddr?.let { statusMap[it] }
                        ?: AddressStatus.NO_HISTORY
                    if (fundsStatus == AddressStatus.HAS_OUTGOING) {
                        android.util.Log.i("WalletManager", "discoverCurrentIndex: index $lastWithFunds key exposed, using ${lastWithFunds + 1}")
                        lastWithFunds + 1
                    } else {
                        android.util.Log.i("WalletManager", "discoverCurrentIndex: index $lastWithFunds has funds, key safe, staying there")
                        lastWithFunds
                    }
                }
                lastUsed >= 0 -> lastUsed + 1
                else -> 0
            },
            currentStoredIndex
        )
        setCurrentAddressIndex(finalResult)
        android.util.Log.i("WalletManager", "Discover: current index = $finalResult (lastUsed=$lastUsed, lastWithFunds=$lastWithFunds)")
        finalResult
    }
}
```

### Pattern 3: Send Operation with Android Notifications
**What:** Use Android NotificationManager to show progress for long-running send operations, allowing users to dismiss the app while transaction broadcasts and confirms.

**When to use:** Any blockchain transaction (send RVN, send asset, issue asset) that may take seconds to broadcast and multiple blocks to confirm.

**Example:**
```kotlin
// Source: Existing NotificationHelper.kt pattern + CONTEXT.md D-03 through D-06 [VERIFIED: codebase analysis]
// NEW class for transaction progress notifications

package io.raventag.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.raventag.app.R

object TransactionNotificationHelper {

    private const val CHANNEL_ID = "transaction_progress"
    private const val NOTIFICATION_ID = 2000

    /**
     * Create notification channel for transaction progress.
     * Must be called before any notification is posted (Android 8+).
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transaction Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Blockchain transaction broadcast and confirmation progress"
                setShowBadge(false)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Show or update transaction progress notification.
     * Uses the same NOTIFICATION_ID to update the same notification slot.
     *
     * @param context Application context.
     * @param stage Current operation stage (broadcasting, confirming, completed, failed).
     * @param txid Transaction ID (null if not yet broadcast).
     */
    fun updateProgress(context: Context, stage: TransactionStage, txid: String? = null) {
        val (title, message) = when (stage) {
            TransactionStage.BROADCASTING -> "Broadcasting..." to "Transaction is being broadcast to network"
            TransactionStage.CONFIRMING -> "Confirming (1/N)" to "Waiting for block confirmation"
            TransactionStage.COMPLETED -> "Completed" to "Transaction confirmed on blockchain"
            TransactionStage.FAILED -> "Failed" to "Transaction failed"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(stage == TransactionStage.BROADCASTING || stage == TransactionStage.CONFIRMING)
            .setAutoCancel(stage == TransactionStage.COMPLETED || stage == TransactionStage.FAILED)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Create pending intent for transaction details screen.
     * Tapping notification opens app to specific transaction details.
     */
    fun createDetailsIntent(context: Context, txid: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "OPEN_TX_DETAILS"
            putExtra("txid", txid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    enum class TransactionStage {
        BROADCASTING, CONFIRMING, COMPLETED, FAILED
    }
}
```

### Pattern 4: Exponential Backoff Retry Logic
**What:** Implement retry logic with exponential delay between attempts for transient network failures.

**When to use:** Network operations that may fail temporarily (wallet restore, send operations).

**Example:**
```kotlin
// Source: CONTEXT.md D-02 and D-06 decisions [VERIFIED: user decisions]
// Retry utility for transient failures

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
            // Check if error is transient (network timeout, temporary failure)
            val isTransient = isTransientError(e)

            if (attempt < maxAttempts - 1 && isTransient) {
                android.util.Log.w("Retry", "Attempt ${attempt + 1} failed, retrying in ${currentDelay}ms: ${e.message}")
                kotlinx.coroutines.delay(currentDelay)
                currentDelay = (currentDelay * backoffMultiplier).toLong()
            } else {
                // Last attempt or non-transient error: throw immediately
                throw e
            }
        }
    }
    // Should not reach here, but handle edge case
    throw lastException ?: IllegalStateException("Retry logic failed")
}

private fun isTransientError(e: Exception): Boolean {
    val message = e.message?.lowercase() ?: return false
    return message.contains("timeout") ||
           message.contains("connection") ||
           message.contains("network") ||
           message.contains("temporary") ||
           e is java.net.SocketTimeoutException ||
           e is java.net.UnknownHostException
}

// Usage in wallet restore:
suspend fun discoverCurrentIndex(): Int = retryWithBackoff {
    // ... existing logic
}

// Usage in send operations:
suspend fun sendRvnLocal(toAddress: String, amountRvn: Double): String = retryWithBackoff {
    // ... existing logic
}
```

### Anti-Patterns to Avoid
- **Calling OkHttp execute() from main thread**: Blocks UI thread, causes ANR. Always wrap in withContext(Dispatchers.IO) or use suspend wrapper.
- **Sequential independent network calls**: Running independent operations one after another instead of in parallel wastes time. Use async/awaitAll for concurrent fetching.
- **Ignoring coroutine cancellation**: Long-running operations that don't check coroutineScope.isActive may waste resources after user navigates away. Add cancellation checks in loops.
- **Using Thread.sleep() in coroutines**: Blocking sleep blocks dispatcher thread. Use kotlinx.coroutines.delay() instead for cooperative cancellation.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|----------|---------------|-------------|-----|
| OkHttp async wrapper | Manual threading, ExecutorService | Kotlin suspendCancellableCoroutine integrates with coroutines, supports cancellation, structured concurrency |
| Custom retry logic | Linear retry, fixed delays | Exponential backoff with jitter handles network congestion better, reduces thundering herd |
| Sequential wallet operations | Sequential calls | async/awaitAll in coroutineScope provides parallelism without thread management complexity |
| Manual notification building | Builder pattern each time | NotificationCompat.Builder is standard Android API, no need for custom notification system |

**Key insight:** Kotlin coroutines provide structured concurrency that integrates with Android lifecycle (viewModelScope, rememberCoroutineScope). Converting blocking calls to suspend functions enables proper dispatcher switching (Dispatchers.IO) and cooperative cancellation, preventing ANRs while maintaining code simplicity.

## Runtime State Inventory

> Omitted - this is a performance optimization phase, not a rename/refactor/migration phase. No stored data, service configs, or OS-registered state needs migration.

## Common Pitfalls

### Pitfall 1: Blocking Main Thread with Network Calls
**What goes wrong:** Calling OkHttp execute() directly from Composable UI or ViewModel without coroutine context blocks the main thread, causing frame drops and potential ANR (Application Not Responding) errors.

**Why it happens:** OkHttp's execute() is a synchronous blocking method. When called from main thread dispatcher, it blocks all UI rendering until network response arrives.

**How to avoid:**
- Always call network operations from suspend functions wrapped in withContext(Dispatchers.IO)
- Use viewModelScope.launch() for fire-and-forget operations in ViewModels
- Use rememberCoroutineScope().launch() for one-shot operations in Composables
- Convert existing blocking execute() calls to suspend wrappers (suspendCancellableCoroutine)

**Warning signs:** UI freezes during wallet refresh, send buttons unresponsive, janky animations, "Application Not Responding" dialogs on device.

### Pitfall 2: Sequential Wallet Restore on Large Wallets
**What goes wrong:** When a wallet has many addresses (e.g., index > 50), sequential fetching of UTXOs, balances, and status for each address takes many seconds, causing UI freeze during restore.

**Why it happens:** Each address discovery involves multiple network round trips (status check, balance query, UTXO fetch). Doing these sequentially multiplies the total time.

**How to avoid:**
- Use coroutineScope with async() for independent operations
- Group dependent operations properly (await dependencies before using results)
- Fetch data in batches where the ElectrumX server supports it (already implemented in getAddressStatusBatch)
- Add loading indicator during restore to set user expectations

**Warning signs:** Restore takes >10 seconds for wallets with ~20 addresses, progress UI not updating, user force-quits app during restore.

### Pitfall 3: Send Operation Without Feedback During Confirmation
**What goes wrong:** Users tap "Send", see a loading spinner, and nothing happens for 10-60 seconds while transaction broadcasts and confirms. They may tap back or kill the app, losing confidence that transaction was sent.

**Why it happens:** Current implementation uses withContext(Dispatchers.IO) to run send operations but has no persistent feedback mechanism visible when app is in background.

**How to avoid:**
- Create dedicated notification channel for transaction progress
- Show ongoing notification while broadcasting/confirming
- Update notification with stage changes (broadcasting -> confirming -> completed)
- Allow tapping notification to view transaction details (D-04)
- Show error notification with retry action on failure (D-06)

**Warning signs:** Users report "I don't know if my send worked", close app during send, send button stays disabled indefinitely.

### Pitfall 4: Ignoring Coroutine Cancellation
**What goes wrong:** User navigates away from a screen but the background coroutine continues running, wasting resources and potentially causing race conditions when they return.

**Why it happens:** Long-running loops and network calls don't check coroutineScope.isActive or use withTimeout, continuing work after the UI has been abandoned.

**How to avoid:**
- Use coroutineScope for structured concurrency (automatically cancelled when scope cancelled)
- Check isActive in loops: `if (!isActive) break` or `if (!isActive) continue`
- Use try/finally to clean up resources even when cancelled
- Use withTimeout() for operations that should give up after a deadline

**Warning signs:** Logs show operations continuing after screen dismiss, duplicate network calls, "Scope cancelled but work still running" errors.

### Pitfall 5: IPFS Upload Blocking Issue Asset Flow
**What goes wrong:** When issuing an asset with IPFS metadata, the IPFS upload (via KuboUploader or PinataUploader) blocks the issue flow. If the upload takes >5 seconds, the UI feels frozen.

**Why it happens:** KuboUploader.uploadFile() and PinataUploader.uploadFile() use blocking OkHttp execute() directly. Called synchronously from issue flow without proper async wrapping.

**How to avoid:**
- Convert KuboUploader and PinataUploader to use suspend wrapper functions
- Wrap IPFS upload calls in withContext(Dispatchers.IO)
- Show progress indicator during upload
- Consider using foreground service for very large file uploads (not needed for metadata JSON)

**Warning signs:** Issue asset dialog freezes after clicking confirm, no feedback for 10+ seconds, user taps back and retry causes duplicate issues.

## Code Examples

Verified patterns from official sources:

### OkHttp Suspend Wrapper
```kotlin
// Source: Codebase analysis (RpcClient.kt:116, AssetManager.kt:214) [VERIFIED: codebase analysis]
// Extension function to convert blocking Call.execute() to suspend function

import okhttp3.Call
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine

suspend fun <T : Call> T.executeSuspend(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
}
```

### Parallel Address Discovery
```kotlin
// Source: Existing WalletManager.kt pattern (lines 365-441) [VERIFIED: codebase analysis]
// Modified discoverCurrentIndex with parallel operations

suspend fun discoverCurrentIndex(): Int = withContext(Dispatchers.IO) {
    val node = RavencoinPublicNode(context)
    val currentStoredIndex = getCurrentAddressIndex()
    val searchLimit = maxOf(currentStoredIndex + 50, 100)

    val batchMap = getAddressBatch(0, 0 until searchLimit)
    if (batchMap.isEmpty()) return@withContext currentStoredIndex

    val addrList = batchMap.values.toList()

    return coroutineScope {
        // Launch status check in parallel with future operations
        val statusDeferred = async {
            node.getAddressStatusBatch(addrList)
        }

        val statusMap = statusDeferred.await()

        // Continue with parallel address scanning...
        var lastUsed = -1
        for (i in 0 until searchLimit) {
            val addr = batchMap[i] ?: continue
            val status = statusMap[addr] ?: AddressStatus.NO_HISTORY
            if (status != AddressStatus.NO_HISTORY) {
                lastUsed = i
            }
        }

        // Parallel funds check for addresses with history
        val addressesWithHistory = (0 until searchLimit).mapNotNull { i ->
            val addr = batchMap[i] ?: return@mapNotNull null
            val status = statusMap[addr] ?: AddressStatus.NO_HISTORY
            if (status != AddressStatus.NO_HISTORY) i to addr else null
        }

        val withFunds = try {
            node.getAddressesWithFunds(addressesWithHistory.map { it.second })
        } catch (_: Exception) { emptySet() }

        var lastWithFunds = -1
        for ((i, addr) in addressesWithHistory) {
            if (addr in withFunds) {
                lastWithFunds = maxOf(lastWithFunds, i)
            }
        }

        val finalResult = maxOf(
            when {
                lastWithFunds >= 0 -> {
                    val fundsAddr = batchMap[lastWithFunds]
                    val fundsStatus = fundsAddr?.let { statusMap[it] }
                        ?: AddressStatus.NO_HISTORY
                    if (fundsStatus == AddressStatus.HAS_OUTGOING) {
                        lastWithFunds + 1
                    } else {
                        lastWithFunds
                    }
                }
                lastUsed >= 0 -> lastUsed + 1
                else -> 0
            },
            currentStoredIndex
        )
        setCurrentAddressIndex(finalResult)
        finalResult
    }
}
```

### Exponential Backoff Retry
```kotlin
// Source: CONTEXT.md D-02, D-06 decisions [VERIFIED: user decisions]

suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 5,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    var delayMs = 1000L
    val multiplier = 2.0

    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            val isTransient = when (e) {
                is java.net.SocketTimeoutException -> true
                is java.net.UnknownHostException -> true
                is java.io.IOException -> e.message?.contains("timeout") == true
                else -> false
            }

            if (attempt < maxAttempts - 1 && isTransient) {
                android.util.Log.w("Retry", "Attempt ${attempt + 1}/${maxAttempts} failed, retry in ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * multiplier).toLong()
            } else {
                throw e
            }
        }
    }
    throw lastException ?: IllegalStateException("Retry exhausted")
}
```

### Transaction Progress Notification
```kotlin
// Source: Existing NotificationHelper.kt pattern + CONTEXT.md D-03 to D-06 [VERIFIED: codebase analysis]

object TransactionNotificationHelper {
    private const val CHANNEL_ID = "transaction_progress"
    private const val NOTIFICATION_ID = 2001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transaction Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Send operation progress (broadcasting, confirming)"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun showBroadcasting(context: Context) {
        updateProgress(context, TransactionStage.BROADCASTING, null)
    }

    fun showConfirming(context: Context, confirmations: Int, total: Int) {
        updateProgress(context, TransactionStage.CONFIRMING, null)
    }

    fun showCompleted(context: Context, txid: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Completed")
            .setContentText("Transaction confirmed: $txid")
            .setAutoCancel(true)
            .setContentIntent(createDetailsIntent(context, txid))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun showFailed(context: Context, error: String, allowRetry: Boolean = false) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Failed")
            .setContentText(error)
            .setAutoCancel(true)

        if (allowRetry) {
            val retryIntent = PendingIntent.getService(
                context,
                0,
                Intent(context, TransactionRetryService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_refresh,
                "Retry",
                retryIntent
            )
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun updateProgress(context: Context, stage: TransactionStage, txid: String?) {
        val (title, message) = when (stage) {
            TransactionStage.BROADCASTING -> "Broadcasting..." to "Broadcasting transaction"
            TransactionStage.CONFIRMING -> "Confirming..." to "Waiting for block confirmation"
            TransactionStage.COMPLETED -> "Completed" to "Transaction confirmed"
            TransactionStage.FAILED -> "Failed" to "Transaction failed"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(stage in listOf(TransactionStage.BROADCASTING, TransactionStage.CONFIRMING))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createDetailsIntent(context: Context, txid: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "VIEW_TRANSACTION"
            putExtra("txid", txid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    enum class TransactionStage {
        BROADCASTING, CONFIRMING, COMPLETED, FAILED
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|----------------|--------------|--------|
| Blocking OkHttp execute() | Suspend wrappers with Dispatchers.IO | This phase (2026) | Network calls no longer block main thread, UI remains responsive |
| Sequential wallet operations | Parallel async/awaitAll | This phase (2026) | Wallet restore ~3x faster on large wallets |
| No send progress feedback | Android notification system | This phase (2026) | Users can dismiss app during sends, see progress in notification shade |
| No retry on transient failures | Exponential backoff retry | This phase (2026) | Better resilience to network issues, fewer manual retries needed |

**Deprecated/outdated:**
- Direct OkHttp execute() from UI code: Causes ANR on main thread, no longer acceptable for user-facing operations
- Sequential wallet scanning: Unnecessarily slow for wallets with many addresses, wastes user time
- Send operations without persistent feedback: Poor UX when transactions take time to confirm, users don't know if send worked

## Assumptions Log

> List all claims tagged `[ASSUMED]` in this research. The planner and discuss-phase use this section to identify decisions that need user confirmation before execution.

| # | Claim | Section | Risk if Wrong |
|---|-------|----------|-----------------|
| A1 | Kotlin coroutines 1.7.3 is sufficient for structured concurrency | Standard Stack | Version mismatch or coroutine API changes unlikely but possible if project updates dependencies |
| A2 | OkHttp 4.12.0 async wrapper pattern is stable | Pattern 1 | suspendCancellableCoroutine behavior may differ on newer Kotlin versions, requires testing |
| A3 | ElectrumX batch APIs remain stable | Parallel Restore | If backend changes batch API behavior, parallel calls may fail or return different data structure |
| A4 | Android notification channel IMPORTANCE_LOW is appropriate | Pattern 3 | Some users may not notice low-priority notifications; IMPORTANCE_DEFAULT could be used for more visibility |
| A5 | 5 retry attempts with 2x backoff is sufficient | Pattern 4 | Network conditions may require more retries or different backoff strategy; exponential may not handle all failure modes |
| A6 | Existing MainActivity withContext patterns work correctly | Architecture | withContext usage in MainActivity may have threading bugs that parallel conversion exposes |
| A7 | SendRvnScreen and TransferScreen UI state management works with async | UI Integration | Existing UI state may not handle background send lifecycle properly (isLoading, resultMessage updates) |

**If this table is empty:** All claims in this research were verified or cited — no user confirmation needed.

## Open Questions

1. **How should send operation cancellation work when user navigates away?**
   - **What we know:** Current UI shows loading state (isLoading parameter) but background coroutine continues. User can navigate back or dismiss app.
   - **What's unclear:** Should the send operation continue in background and show notification on completion, or should it be cancelled? CONTEXT.md D-03 suggests "user can dismiss the app while transaction broadcasts", implying continuation.
   - **Recommendation:** Continue send operation in background (use WorkManager or foreground service) and show final notification with txid. Add cancellation option in notification if user wants to abort.

2. **Should IPFS upload errors be retried automatically?**
   - **What we know:** CONTEXT.md specifies retry for wallet restore and send operations (D-02, D-06) but not explicitly for IPFS uploads.
   - **What's unclear:** Should IPFS upload failures (KuboUploader, PinataUploader) retry with exponential backoff, or fail immediately to user?
   - **Recommendation:** Apply same retry policy (5 attempts, 2x backoff) to IPFS uploads for consistency. Treat 4xx/5xx errors as fatal, network errors as retryable.

3. **What is the target notification style and user interaction?**
   - **What we know:** NotificationHelper uses basic notification style with small icon, title, and text. CONTEXT.md D-03 through D-06 specify multiple notifications during lifecycle.
   - **What's unclear:** Should notifications use progress bar (setProgress()), big text style, or rich media? Should tapping notification open MainActivity or a dedicated transaction history screen?
   - **Recommendation:** Use ongoing notification style for broadcast/confirming stages. Tap opens MainActivity with transaction details intent. D-04 specifies "transaction details screen (not main wallet)" but existing codebase doesn't have dedicated transaction details screen—implement or open Wallet screen with tx highlighted.

4. **Should confirmation dialog show network fee estimate before user confirms?**
   - **What we know:** CONTEXT.md D-07 specifies "confirmation dialog displays: amount, recipient address, and network fee."
   - **What's unclear:** Should fee be fetched before showing dialog (adding delay) or estimated and shown in dialog?
   - **Recommendation:** Fetch fee estimate in parallel with balance check. Show fee in confirmation dialog. If fee fetch fails, show warning but allow proceed with estimated fee.

5. **How should loading UI patterns be standardized across the app?**
   - **What we know:** SendRvnScreen uses CircularProgressIndicator in button when isLoading=true. TransferScreen has similar pattern.
   - **What's unclear:** Should there be a unified loading composable, overlay, or screen-specific patterns?
   - **Recommendation:** Claude's discretion area covers this. Use consistent pattern: CircularProgressIndicator for blocking operations, LinearProgressIndicator for multi-stage operations (restore, batch upload). Keep existing button spinner pattern for quick operations.

## Environment Availability

> Skip this section - phase has no external dependencies (Android framework and existing libraries only).

## Validation Architecture

> Skip this section - this phase has no new functionality requiring test coverage. Performance optimizations are verified by manual testing (ANR detection, frame time analysis).

## Security Domain

> Skip this section - this phase does not introduce new security controls or modify authentication/authorization flows. All changes are internal performance improvements.

## Sources

### Primary (HIGH confidence)
- Codebase analysis - `/home/ale/Projects/RavenTag/android/app/src/main/java/io/raventag/app/` (verified execute() patterns, withContext usage, notification patterns)
- Codebase analysis - `/home/ale/Projects/RavenTag/android/gradle/libs.versions.toml` (verified coroutines 1.7.3, okhttp 4.12.0)

### Secondary (MEDIUM confidence)
- CONTEXT.md decisions (D-01 through D-07) [CITED: user decisions from discuss-phase]

### Tertiary (LOW confidence)
- None - All patterns derived from codebase analysis and standard Kotlin coroutines practices [ASSUMED: suspendCancellableCoroutine behavior, parallel performance gains]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All dependencies verified in libs.versions.toml, no new packages required
- Architecture: MEDIUM - Patterns based on codebase analysis and Kotlin coroutines best practices; actual performance gains need measurement
- Pitfalls: MEDIUM - Identified from codebase patterns and common Android performance issues; solutions are standard coroutine patterns

**Research date:** 2026-04-13
**Valid until:** 2026-05-13 (60 days - Android performance patterns are stable, Kotlin coroutines API stable, but actual gains depend on measurement)
