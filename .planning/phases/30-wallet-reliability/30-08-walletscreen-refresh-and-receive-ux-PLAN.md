---
id: 30-08-walletscreen-refresh-and-receive-ux
phase: 30
plan: 08
type: execute
wave: 3
depends_on:
  - 30-02-wallet-cache-db-daos
  - 30-03-scripthash-subscription
  - 30-05-consolidation-reliability
  - 30-07-node-reliability
files_modified:
  - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt
  - android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt
  - android/app/src/main/java/io/raventag/app/MainActivity.kt
  - android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt
  - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
autonomous: true
requirements:
  - WALLET-BAL
  - WALLET-RECV
threat_refs:
  - T-30-RECV
  - T-30-NET
ui_spec_refs:
  - "UI-SPEC §Cached-state banner (D-04)"
  - "UI-SPEC §Connection status pill (D-12) + ModalBottomSheet"
  - "UI-SPEC §Pending balance line (D-24)"
  - "UI-SPEC §Battery-saver chip (D-28)"
  - "UI-SPEC §Sync-in-background indicator"
  - "UI-SPEC §Incoming transaction notification (D-07) + in-app Snackbar"
  - "UI-SPEC §Receive flow (D-18) sub-label + 200ms cross-fade"
  - "UI-SPEC §Disabled state for Send/Receive (D-12)"
  - "UI-SPEC §Implementation Notes, New notification channel (D-06, D-07)"
  - "UI-SPEC §Implementation Notes, Em-dash audit"
  - "UI-SPEC §Copywriting Contract, Error states + Incoming transaction notification table"

must_haves:
  truths:
    - "WalletScreen renders cached state from WalletCacheDao instantly on open and shows a cached-state banner with HH:MM timestamp until a successful refresh completes (D-04)"
    - "On refresh failure while cached state is present, the banner switches to 'Last updated HH:MM · reconnecting…' with exact EN/IT copy and the connection pill transitions to YELLOW (D-12)"
    - "When ConnectionHealth.RED (AllNodesUnreachableException from NodeHealthMonitor), Send and Receive buttons render with container alpha 0.3 + RavenMuted foreground, and tapping either shows a NotAuthenticRedBg Snackbar 'Offline · all nodes unreachable' / 'Offline · nessun nodo raggiungibile' (D-12)"
    - "A Pending line renders under the balance ONLY when mempool incoming > 0, using Icons.Default.Schedule 12dp RavenMuted + label + amber 0xFFF59E0B amount (D-24)"
    - "Battery-saver chip renders ONLY when PowerManager.isPowerSaveMode() is true and WalletScreen is foreground; chip uses amber 0xFFF59E0B 25% alpha border and amber labelSmall text (D-28)"
    - "While foreground, a 30-second periodic refresh loop runs unless isPowerSaveMode() is true; the scripthash subscription from plan 30-03 remains open regardless of power-save (D-02, D-26)"
    - "Tapping the connection pill opens a ModalBottomSheet listing current node URL (monospace), last-success timestamp, per-node quarantine status from NodeHealthMonitor.diagnostics(), and a Close OutlinedButton with 1dp RavenBorder (UI-SPEC §Connection status pill)"
    - "ReceiveScreen displays the strings.receiveCurrentAddressLabel main label and the D-18 sub-label 'Changes after your next send or consolidation.' / 'Cambia dopo il prossimo invio o consolidamento.' and cross-fades the address text with tween(200) when currentIndex advances (D-18)"
    - "An 'incoming_tx' NotificationChannel is registered in MainActivity.onCreate with name 'Incoming transactions'/'Transazioni in arrivo', IMPORTANCE_DEFAULT, showBadge=true (UI-SPEC §Implementation Notes)"
    - "WalletPollingWorker compares per-address scripthash status vs SharedPreferences 'wallet_poll:last_status_<addr>' and on change re-fetches balance; positive delta triggers IncomingTxNotificationHelper with txid deep-link (D-06)"
    - "SubscriptionManager StatusChanged events (from plan 30-03) flowing into WalletScreen trigger a re-fetch; a positive RVN delta pushes an AuthenticGreenBg Snackbar '+X RVN received'/'+X RVN ricevuti' with Icons.Default.CallReceived (D-07)"
    - "Notification tap builds an Intent(MainActivity) with action=VIEW_TRANSACTION and extra 'txid'; MainActivity onNewIntent/onCreate route to TransactionDetailsScreen when the extra is present (UI-SPEC §Incoming tx detection)"
    - "notificationId is computed as (2100 + (txid.hashCode() and 0x3FF)) to allow distinct incoming notifications per txid (UI-SPEC §Implementation Notes)"
    - "All new user-facing strings live in stringsEn AND stringsIt verbatim from UI-SPEC Copywriting Contract; zero U+2014 em-dashes in any touched file"
  artifacts:
    - path: "android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt"
      provides: "Cached-state banner, 2dp LinearProgressIndicator, YELLOW ElectrumStatusBadge state + ModalBottomSheet, Pending line, Battery-saver chip, 30s power-save-gated poll, incoming Snackbar, disabled Send/Receive when pill RED"
    - path: "android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt"
      provides: "D-18 main + sub-label composables and 200ms AnimatedContent cross-fade on currentIndex change"
    - path: "android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt"
      provides: "Per-address scripthash status diff via blockchain.scripthash.subscribe one-shot, triggers IncomingTxNotificationHelper on balance delta (D-06)"
    - path: "android/app/src/main/java/io/raventag/app/MainActivity.kt"
      provides: "'incoming_tx' NotificationChannel (API 26+), VIEW_TRANSACTION intent extra 'txid' handler routing to TransactionDetailsScreen"
    - path: "android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt"
      provides: "Three-variant builder (mempool / confirming / confirmed), PendingIntent with txid, notificationId = 2100 + (txid.hashCode() and 0x3FF)"
      exports: ["IncomingTxNotificationHelper"]
    - path: "android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt"
      provides: "EN + IT strings for cached banner, reconnecting suffix, Pending line, Battery saver chip, pill labels, notification rows, snackbars, ReceiveScreen sub-label"
  key_links:
    - from: "WalletScreen header"
      to: "NodeHealthMonitor.stateFlow (plan 30-07)"
      via: "collectAsState()"
      pattern: "ConnectionHealth"
    - from: "WalletScreen connection pill tap"
      to: "NodeHealthMonitor.diagnostics() + NodeHealthMonitor.currentNode() (plan 30-07)"
      via: "ModalBottomSheet"
      pattern: "ModalBottomSheet"
    - from: "WalletScreen Send/Receive buttons"
      to: "AllNodesUnreachableException handling via ConnectionHealth.RED signal (plan 30-07)"
      via: "enabled=false + alpha(0.3f)"
      pattern: "ConnectionHealth.RED"
    - from: "WalletScreen balance card"
      to: "WalletCacheDao.readState + getLastRefreshedAt (plan 30-02)"
      via: "LaunchedEffect on open"
      pattern: "WalletCacheDao"
    - from: "WalletScreen subscription wiring"
      to: "SubscriptionManager.eventsFlow (plan 30-03)"
      via: "collectLatest StatusChanged"
      pattern: "ScripthashEvent.StatusChanged"
    - from: "WalletPollingWorker"
      to: "IncomingTxNotificationHelper.showIncoming"
      via: "per-address scripthash status diff + balance delta"
      pattern: "IncomingTxNotificationHelper"
    - from: "MainActivity.onCreate"
      to: "IncomingTxNotificationHelper.createChannel(this)"
      via: "channel-creation wiring block"
      pattern: "incoming_tx"
    - from: "MainActivity intent handler"
      to: "TransactionDetailsScreen"
      via: "getStringExtra(\"txid\") → navigate(TransactionDetails, txid)"
      pattern: "VIEW_TRANSACTION"
---

<objective>
Deliver the WalletScreen and ReceiveScreen UX for Phase 30 reliability plus the foreground + background incoming-transaction notification path. This plan is the integration point where every upstream Phase 30 subsystem lands on the screen: it consumes the DAOs from plan 30-02, the scripthash subscription Flow from plan 30-03, the reservation-aware balance + Rebroadcast side-effects from plan 30-05, and the NodeHealthMonitor.stateFlow + AllNodesUnreachableException from plan 30-07. It also creates the new `incoming_tx` notification channel and extends the existing `WalletPollingWorker` for D-06 background detection.

Purpose: without this plan, WALLET-BAL and WALLET-RECV are implemented in the data layer but invisible in the UI. This plan closes every visible D-01/02/04/06/07/08/12/18/24/26/28 decision.
Output: in-place extensions to WalletScreen.kt, ReceiveScreen.kt, WalletPollingWorker.kt, MainActivity.kt; one new NotificationHelper file; EN + IT string additions verbatim from UI-SPEC Copywriting Contract.

Hard constraints:
- Do NOT touch `RavencoinTxBuilder.kt` (D-17).
- Do NOT redesign the Phase 20 `transaction_progress` channel; the `incoming_tx` channel is strictly additive.
- All new user-visible strings must be added to `AppStrings.kt` `stringsEn` + `stringsIt` in English AND Italian per UI-SPEC Copywriting Contract.
- No U+2014 em-dashes anywhere in touched files.
- Connection pill visual rendering lives HERE; the data source (StateFlow<ConnectionHealth>) was produced by plan 30-07.
- Scripthash subscription lifecycle (start/stop per foreground) lives HERE; the wire protocol and Flow API were produced by plan 30-03.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/30-wallet-reliability/30-CONTEXT.md
@.planning/phases/30-wallet-reliability/30-RESEARCH.md
@.planning/phases/30-wallet-reliability/30-PATTERNS.md
@.planning/phases/30-wallet-reliability/30-UI-SPEC.md
@.planning/phases/30-wallet-reliability/30-VALIDATION.md
@.planning/phases/30-wallet-reliability/30-02-wallet-cache-db-daos-PLAN.md
@.planning/phases/30-wallet-reliability/30-03-scripthash-subscription-PLAN.md
@.planning/phases/30-wallet-reliability/30-05-consolidation-reliability-PLAN.md
@.planning/phases/30-wallet-reliability/30-07-node-reliability-PLAN.md
@android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
@android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt
@android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt
@android/app/src/main/java/io/raventag/app/worker/NotificationHelper.kt
@android/app/src/main/java/io/raventag/app/worker/TransactionNotificationHelper.kt
@android/app/src/main/java/io/raventag/app/MainActivity.kt
@android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
@android/app/src/main/java/io/raventag/app/ui/theme/Theme.kt
@android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
@android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt

<interfaces>
**Types and singletons produced by upstream plans (consumed here — DO NOT redeclare):**

```kotlin
// From plan 30-02 (wallet/cache)
object WalletCacheDao {
    data class CachedWalletState(
        val walletId: String,
        val balanceSat: Long,
        val utxos: List<io.raventag.app.wallet.RavencoinPublicNode.Utxo>,
        val assetUtxos: Map<String, List<io.raventag.app.wallet.RavencoinPublicNode.AssetUtxo>>,
        val blockHeight: Int,
        val lastRefreshedAt: Long
    )
    fun init(context: android.content.Context)
    fun writeState(utxos: List<Utxo>, assetUtxos: Map<String, List<AssetUtxo>>, blockHeight: Int)
    fun readState(): CachedWalletState?
    fun getLastRefreshedAt(): Long
}

// From plan 30-03 (wallet/subscription)
sealed class ScripthashEvent {
    data class StatusChanged(val scripthash: String, val newStatus: String?) : ScripthashEvent()
    data object ConnectionLost : ScripthashEvent()
    data object AllNodesDown : ScripthashEvent()
}
class SubscriptionManager(private val context: android.content.Context) {
    suspend fun start(addresses: List<String>)
    suspend fun stop()
    fun eventsFlow(): kotlinx.coroutines.flow.SharedFlow<ScripthashEvent>
}

// RavencoinPublicNode extension from plan 30-03 (one-shot scripthash status fetch for WorkManager path)
// NOTE: confirm exact signature at execution time against plan 30-03 source; fall back to a thin
// wrapper around callWithFailover("blockchain.scripthash.subscribe", listOf(scripthash)) that
// returns the String status hash if the named wrapper is missing.
fun io.raventag.app.wallet.RavencoinPublicNode.subscribeScripthashRpc(address: String): String?

// From plan 30-07 (wallet/health + wallet)
enum class ConnectionHealth { GREEN, YELLOW, RED }
object NodeHealthMonitor {
    fun init(context: android.content.Context)
    fun nextHealthyNode(): String?
    fun reportSuccess(host: String)
    fun reportFailure(host: String, reason: String)
    fun reportTofuMismatch(host: String)
    val stateFlow: kotlinx.coroutines.flow.StateFlow<ConnectionHealth>
    data class NodeDiagnostic(
        val host: String,
        val lastSuccessAt: Long?,
        val lastFailureAt: Long?,
        val lastError: String?,
        val quarantinedUntil: Long?
    )
    fun diagnostics(): List<NodeDiagnostic>
    fun currentNode(): String?
}
class AllNodesUnreachableException(msg: String = "all ElectrumX nodes quarantined") : RuntimeException(msg)
```

**New types introduced by THIS plan (consumed by plan 30-10 and the housekeeping audit):**

```kotlin
// android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt
object IncomingTxNotificationHelper {
    const val CHANNEL_ID: String = "incoming_tx"
    const val ACTION_VIEW_TRANSACTION: String = "VIEW_TRANSACTION"
    const val EXTRA_TXID: String = "txid"
    fun createChannel(context: android.content.Context)
    /**
     * Builds a notification for an incoming transaction. Variant chosen by confirmation count:
     *   confirmations == 0  -> mempool variant ("+X RVN · Pending" / "+X RVN · In attesa")
     *   1..5                -> confirming variant ("+X RVN · N/6 confirmations" / "+X RVN · N/6 conferme")
     *   >= 6                -> confirmed variant ("+X RVN confirmed" / "+X RVN confermati")
     * notificationId = 2100 + (txid.hashCode() and 0x3FF)
     */
    fun showIncoming(context: android.content.Context, txid: String, rvnAmount: Double, confirmations: Int)
}
```

**Existing codebase facts verified at planning time:**
- `MainActivity` already extends `androidx.fragment.app.FragmentActivity` (file MainActivity.kt:2333 — no base-class change needed).
- `AppConfig` is a per-flavor object (`consumer/.../AppConfig.kt` + `brand/.../AppConfig.kt`). Plan 30-07 introduces `ELECTRUM_SERVERS` on each flavor. This plan MAY introduce a no-op helper but does NOT change AppConfig itself.
- Existing `TransactionNotificationHelper` already defines `ACTION_VIEW_TRANSACTION` and `EXTRA_TXID` as `const val` (TransactionNotificationHelper.kt:34-35). The new `IncomingTxNotificationHelper` MUST reuse the same action string value `"VIEW_TRANSACTION"` and extra key `"txid"` so the MainActivity handler is unified.
- Existing `WalletPollingWorker` already uses SharedPreferences file `"wallet_poll"` with key pattern `poll_rvn_sat` (long). New keys introduced here live in the same file and MUST use prefix `last_status_` keyed by address.
- Existing `NotificationHelper` (channel `raventag_wallet`) is NOT removed and is NOT reused. It remains for existing non-phase-30 notifications.
</interfaces>
</context>

<tasks>

<task type="auto" tdd="false">
  <name>Task 1: Create IncomingTxNotificationHelper.kt (incoming_tx channel, 3-variant builder, txid deep-link)</name>
  <files>
    android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L210-L222,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L396-L410,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L268-L319,
    @android/app/src/main/java/io/raventag/app/worker/NotificationHelper.kt,
    @android/app/src/main/java/io/raventag/app/worker/TransactionNotificationHelper.kt,
    @android/app/src/main/java/io/raventag/app/MainActivity.kt
  </read_first>
  <behavior>
    Create a new helper `IncomingTxNotificationHelper` that mirrors `TransactionNotificationHelper` in shape but:
    - uses a separate channel `incoming_tx` (distinct from `transaction_progress` and `raventag_wallet`)
    - supports three text variants (mempool / confirming / confirmed) chosen by the `confirmations: Int` argument
    - builds a tappable PendingIntent with `Intent.ACTION_VIEW` (via MainActivity) carrying `action = VIEW_TRANSACTION` and `extra txid = <txid>`
    - computes the notification ID as `2100 + (txid.hashCode() and 0x3FF)` so each distinct txid gets its own slot
    - respects `POST_NOTIFICATIONS` on API 33+ (silently returns if not granted, same pattern as NotificationHelper.kt:50-55)

    Channel properties (UI-SPEC §Implementation Notes):
    | Property    | Value                                                     |
    |-------------|-----------------------------------------------------------|
    | Channel ID  | `incoming_tx`                                             |
    | Name (EN)   | `Incoming transactions`                                   |
    | Name (IT)   | `Transazioni in arrivo`                                   |
    | Description | `Notifications for received RVN and assets`               |
    | Importance  | `NotificationManager.IMPORTANCE_DEFAULT`                  |
    | Show badge  | `true`                                                    |
    | Vibration   | default (left enabled; IMPORTANCE_DEFAULT brings its own) |
    | Sound       | default                                                   |

    Channel name must be locale-sensitive: fetch from `AppStrings.current.incomingTxChannelName` at creation time. Since `createChannel` may run before any Compose LocalStrings is alive, channel name is read from a Locale-resolved resource: if `java.util.Locale.getDefault().language` starts with `"it"`, use the Italian literal; otherwise English. Do NOT read from `AppStrings.kt` here (LocalStrings is Compose-only). Inline the two literals in this file.

    Notification copy (UI-SPEC §Copywriting Contract, Incoming transaction notification table — reproduced verbatim):

    | Stage              | Title (EN)            | Text (EN)                       | Title (IT)               | Text (IT)                      |
    |--------------------|-----------------------|---------------------------------|--------------------------|--------------------------------|
    | Mempool (0 conf)   | Incoming transaction  | `+%1 RVN · Pending`             | Transazione in arrivo    | `+%1 RVN · In attesa`          |
    | Confirming (1-5)   | Incoming transaction  | `+%1 RVN · %2/6 confirmations`  | Transazione in arrivo    | `+%1 RVN · %2/6 conferme`      |
    | Confirmed (>=6)    | Received              | `+%1 RVN confirmed`             | Ricevuto                 | `+%1 RVN confermati`           |

    The `%1` slot is a Double formatted as `%.8f` RVN (trim trailing zeros is acceptable; `%.8f` verbatim is fine for v1 per UI-SPEC). The separator character is U+00B7 (`·`) middle dot. Not an em dash (U+2014 is forbidden).

    Language selection is by `java.util.Locale.getDefault().language.startsWith("it")`.
  </behavior>
  <action>
    Create `android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt` with exactly this content:

    ```kotlin
    package io.raventag.app.worker

    import android.app.NotificationChannel
    import android.app.NotificationManager
    import android.app.PendingIntent
    import android.content.Context
    import android.content.Intent
    import android.content.pm.PackageManager
    import android.os.Build
    import androidx.core.app.NotificationCompat
    import androidx.core.app.NotificationManagerCompat
    import io.raventag.app.MainActivity
    import io.raventag.app.R
    import java.util.Locale

    /**
     * D-06, D-07, D-08 — incoming RVN transaction notifications.
     *
     * Channel: `incoming_tx`, distinct from Phase 20 `transaction_progress` and the legacy
     * `raventag_wallet` channel. Tapping the notification opens MainActivity with
     * `action = VIEW_TRANSACTION` and `extra txid = <txid>`; MainActivity routes to
     * TransactionDetailsScreen.
     *
     * Notification ID strategy per UI-SPEC §Implementation Notes:
     *   id = 2100 + (txid.hashCode() and 0x3FF)   -> mod-1024, distinct slots per txid.
     */
    object IncomingTxNotificationHelper {

        const val CHANNEL_ID: String = "incoming_tx"
        const val ACTION_VIEW_TRANSACTION: String = "VIEW_TRANSACTION"
        const val EXTRA_TXID: String = "txid"

        private const val NOTIFICATION_ID_BASE: Int = 2100
        private const val NOTIFICATION_ID_MASK: Int = 0x3FF

        private fun isItalian(): Boolean =
            Locale.getDefault().language.startsWith("it", ignoreCase = true)

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = if (isItalian()) "Transazioni in arrivo" else "Incoming transactions"
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    name,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for received RVN and assets"
                    setShowBadge(true)
                }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }

        fun showIncoming(
            context: Context,
            txid: String,
            rvnAmount: Double,
            confirmations: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) return
            }

            val amountStr = String.format(Locale.ROOT, "%.8f", rvnAmount)
            val italian = isItalian()

            val title: String
            val text: String
            when {
                confirmations <= 0 -> {
                    title = if (italian) "Transazione in arrivo" else "Incoming transaction"
                    text = if (italian) "+$amountStr RVN \u00B7 In attesa"
                           else         "+$amountStr RVN \u00B7 Pending"
                }
                confirmations < 6 -> {
                    title = if (italian) "Transazione in arrivo" else "Incoming transaction"
                    text = if (italian) "+$amountStr RVN \u00B7 $confirmations/6 conferme"
                           else         "+$amountStr RVN \u00B7 $confirmations/6 confirmations"
                }
                else -> {
                    title = if (italian) "Ricevuto" else "Received"
                    text = if (italian) "+$amountStr RVN confermati"
                           else         "+$amountStr RVN confirmed"
                }
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_VIEW_TRANSACTION
                putExtra(EXTRA_TXID, txid)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val requestCode = txid.hashCode()
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val id = NOTIFICATION_ID_BASE + (txid.hashCode() and NOTIFICATION_ID_MASK)
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
    ```

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "object IncomingTxNotificationHelper" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q 'CHANNEL_ID: String = "incoming_tx"' android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q 'ACTION_VIEW_TRANSACTION: String = "VIEW_TRANSACTION"' android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q 'EXTRA_TXID: String = "txid"' android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "NOTIFICATION_ID_BASE: Int = 2100" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "NOTIFICATION_ID_MASK: Int = 0x3FF" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "IMPORTANCE_DEFAULT" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "setShowBadge(true)" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "Incoming transactions" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "Transazioni in arrivo" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "In attesa" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "conferme" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "confermati" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "POST_NOTIFICATIONS" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `grep -q "FLAG_IMMUTABLE" android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>IncomingTxNotificationHelper compiles; three-variant text selection, POST_NOTIFICATIONS guard, FLAG_IMMUTABLE PendingIntent, EN + IT verbatim from UI-SPEC, no em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 2: Register incoming_tx channel + VIEW_TRANSACTION handler in MainActivity</name>
  <files>
    android/app/src/main/java/io/raventag/app/MainActivity.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L396-L410,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L446-L448,
    @android/app/src/main/java/io/raventag/app/MainActivity.kt,
    @android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt,
    @android/app/src/main/java/io/raventag/app/worker/TransactionNotificationHelper.kt
  </read_first>
  <behavior>
    1. In `MainActivity.onCreate`, immediately after the existing `NotificationHelper.createChannel(this)` and `TransactionNotificationHelper.createChannel(this)` calls (MainActivity.kt ~ lines 2447-2451 per PATTERNS.md line 447), add:
       ```kotlin
       io.raventag.app.worker.IncomingTxNotificationHelper.createChannel(this)
       ```
       If plan 30-07 already inserted `NodeHealthMonitor.init(this)` after the channel creations, place the `createChannel` call BEFORE `NodeHealthMonitor.init(this)` so all three channels are registered before any worker or monitor fires.

    2. Add a private helper method `private fun handleIncomingTxIntent(intent: Intent?)`:
       - Checks `intent?.action == IncomingTxNotificationHelper.ACTION_VIEW_TRANSACTION` (reuses the constant; `TransactionNotificationHelper.ACTION_VIEW_TRANSACTION_EXT` also has the same string value `"VIEW_TRANSACTION"` — either constant works, prefer the IncomingTx one to avoid coupling to Phase 20 helper).
       - If true, reads `val txid = intent.getStringExtra(IncomingTxNotificationHelper.EXTRA_TXID)`.
       - If `txid != null`, calls the existing navigation lambda that opens TransactionDetailsScreen (inspect MainActivity for the current navigation state — likely a `mutableStateOf<Screen.TransactionDetails>` or a `navController.navigate("tx/$txid")` pattern; the executor MUST identify and reuse that exact path).

    3. Invoke `handleIncomingTxIntent(intent)` in:
       - `onCreate(savedInstanceState: Bundle?)` — after `super.onCreate` and after `setContent { ... }` is composed (use a `LaunchedEffect(Unit)` inside setContent OR call `handleIncomingTxIntent(intent)` AFTER setContent since the navigation state is set up at that point — defer to the existing navigation pattern).
       - `override fun onNewIntent(intent: Intent)` — if this override does not yet exist, add it. Call `super.onNewIntent(intent)` then `setIntent(intent)` then `handleIncomingTxIntent(intent)`.

    4. If MainActivity already handles `TransactionNotificationHelper`'s `VIEW_TRANSACTION` action (it does, per the existing TransactionNotificationHelper deep-link pattern), the SAME handler can route both sources since the action string and extra key are identical. In that case, ensure the handler distinguishes ONLY by the presence of the `txid` extra (incoming path) vs the Phase 20 confirmed-send path (which also uses `txid`). Route both to TransactionDetailsScreen — no behavioral divergence needed.

    Em-dash audit on the touched lines (inspect diff).
  </behavior>
  <action>
    1) Locate the block in MainActivity.kt around lines 2447-2461 (per PATTERNS.md line 447) that contains:
       ```kotlin
       io.raventag.app.worker.NotificationHelper.createChannel(this)
       io.raventag.app.worker.TransactionNotificationHelper.createChannel(this)
       ```

    2) Insert a new line immediately after those two:
       ```kotlin
       io.raventag.app.worker.IncomingTxNotificationHelper.createChannel(this)
       ```

    3) Inspect the existing code for any `ACTION_VIEW_TRANSACTION` handler (search for `TransactionNotificationHelper.ACTION_VIEW_TRANSACTION_EXT` OR for a literal `"VIEW_TRANSACTION"` string — MainActivity.kt already contains the Phase 20 deep-link per TransactionNotificationHelper.kt:34). If a handler exists, verify it already reads `EXTRA_TXID`/`"txid"` and routes to TransactionDetailsScreen; then no further change is required (the new IncomingTxNotificationHelper reuses the same action + extra).

    4) If NO handler exists yet (i.e., Phase 20 plan 20-05 is unchecked on ROADMAP so the deep-link was never wired), add one:
       ```kotlin
       private fun handleIncomingTxIntent(intent: Intent?) {
           if (intent?.action == io.raventag.app.worker.IncomingTxNotificationHelper.ACTION_VIEW_TRANSACTION) {
               val txid = intent.getStringExtra(io.raventag.app.worker.IncomingTxNotificationHelper.EXTRA_TXID)
               if (!txid.isNullOrBlank()) {
                   // Route to TransactionDetailsScreen. Use the existing nav state variable
                   // that WalletScreen uses for the "view tx details" tap. SUMMARY must
                   // record the exact navigation hook used.
                   pendingTxNavigation.value = txid
               }
           }
       }
       ```
       ...where `pendingTxNavigation` is a `MutableState<String?>` declared at class scope (or a reusable existing one). The `setContent { }` block reads this state inside a `LaunchedEffect(pendingTxNavigation.value)` and calls the existing tx-detail navigation hook.

       Then:
       ```kotlin
       override fun onNewIntent(intent: Intent) {
           super.onNewIntent(intent)
           setIntent(intent)
           handleIncomingTxIntent(intent)
       }
       ```

       And call `handleIncomingTxIntent(intent)` at the end of `onCreate` (after `setContent`).

    5) DO NOT disturb the existing `TransactionNotificationHelper` deep-link (Phase 20 D-04) if already wired; the new helper is strictly additive via the shared action string.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/MainActivity.kt` on the full file. If the file already contains em dashes from prior code, the executor MUST replace them with `:`, `,`, or `·` during this pass (this is a hard project rule — see MEMORY).

    Record in SUMMARY.md: (a) the exact method name + line number where `IncomingTxNotificationHelper.createChannel(this)` was inserted; (b) whether a VIEW_TRANSACTION handler already existed or a new one was added; (c) the navigation hook the handler calls (e.g., `navController.navigate("tx_details/$txid")` or `selectedScreen.value = Screen.TxDetails(txid)`).
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -30</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "IncomingTxNotificationHelper.createChannel(this)" android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - `grep -q "IncomingTxNotificationHelper.ACTION_VIEW_TRANSACTION\|ACTION_VIEW_TRANSACTION_EXT\|\"VIEW_TRANSACTION\"" android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - `grep -q "IncomingTxNotificationHelper.EXTRA_TXID\|EXTRA_TXID_EXT\|getStringExtra(\"txid\")" android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - Either `grep -q "override fun onNewIntent" android/app/src/main/java/io/raventag/app/MainActivity.kt` is true OR the existing `onCreate` already calls a VIEW_TRANSACTION handler.
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>incoming_tx channel registered at startup. VIEW_TRANSACTION intent with `txid` extra routes to TransactionDetailsScreen. Both onCreate and onNewIntent dispatch the handler. Build passes. No em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 3: Extend WalletPollingWorker with scripthash-status diff and fire IncomingTxNotificationHelper on balance delta (D-06)</name>
  <files>
    android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L26-L29,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L81-L88,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L225-L265,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L365-L370,
    @android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt,
    @android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt,
    @android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt
  </read_first>
  <behavior>
    Extend the existing `WalletPollingWorker.doWork()` (15-min periodic, `NetworkType.CONNECTED`) with a D-06 scripthash-status-diff pass that runs AFTER the existing balance-diff logic (preserve Phase 20 behavior). The new pass:

    1. Reads the wallet's current receive address list (at minimum: the current `currentIndex` address used by ReceiveScreen; optionally the last N addresses — v1 keeps it single-address to match the quantum-resistance model where only `currentIndex` is ever the active receive). The worker already has the necessary WalletManager access per the existing pattern; reuse it.

    2. For each address `addr`:
       a. Compute scripthash via the existing `io.raventag.app.wallet.RavencoinPublicNode.addressToScripthash(addr)` helper (or equivalent already used in the worker).
       b. Issue a ONE-SHOT RPC call to `blockchain.scripthash.subscribe` (this is functionally a "get current status" call in ElectrumX — the subscription aspect requires a persistent socket which is plan 30-03's domain; the RPC returns the status hash immediately). Call signature: `node.subscribeScripthashRpc(addr)` if plan 30-03 exported that wrapper, else a direct `node.callWithFailover("blockchain.scripthash.subscribe", listOf(scripthash))` followed by `?.takeUnless { it.isJsonNull }?.asString`.
       c. Fetch SharedPreferences: `val prevStatus = prefs.getString("last_status_$addr", null)` (file `"wallet_poll"`, same file used by Phase 20 `poll_rvn_sat`).
       d. If `currentStatus != prevStatus`:
          - Persist new status: `prefs.edit().putString("last_status_$addr", currentStatus).apply()`.
          - Re-fetch balance via the existing `RavencoinPublicNode.getBalance(addr)` path.
          - Compute delta vs the previously-cached balance (`poll_rvn_sat` key, existing).
          - If delta > 0 (new incoming funds): find the newest transaction in `node.getTransactionHistory(addr, limit = 3, offset = 0)` that is NOT already in SharedPreferences key `"last_notified_txid"`; call `IncomingTxNotificationHelper.showIncoming(applicationContext, newTxid, rvnAmount = deltaSat / 1e8, confirmations = newTxEntry.confirmations)`. Save the new `last_notified_txid`.
          - On first run (prevStatus == null): do NOT notify — persist the current status and balance; subsequent runs compare against these baselines. Rationale: avoid spamming the user on fresh install with a retroactive "incoming" for any existing balance.

    3. Resilience policy mirrors the existing worker (WalletPollingWorker.kt:116-122):
       - `java.io.IOException` → `Result.retry()`.
       - Any other `Exception` → swallow gracefully, `Result.success()`.
       - Do NOT surface errors to the user (D-06 is a silent background path).

    4. Call `NodeHealthMonitor.init(applicationContext)` at the top of `doWork()` (plan 30-07 hand-off). The node health monitor is a singleton and the call is idempotent.

    SharedPreferences keys in use (same file `"wallet_poll"`):
    - `poll_rvn_sat` — Long, existing Phase 20 last-seen balance.
    - `last_status_<addr>` — String, new, per-address scripthash status.
    - `last_notified_txid` — String, new, the most recent txid a D-06 notification fired for.

    Em-dash audit on file.
  </behavior>
  <action>
    1) Read the full `WalletPollingWorker.kt` file to identify the WalletManager + RavencoinPublicNode access pattern already in place.

    2) At the TOP of `doWork()` inside the `withContext(Dispatchers.IO)` block, add (if not already present after plan 30-07):
       ```kotlin
       io.raventag.app.wallet.health.NodeHealthMonitor.init(applicationContext)
       ```

    3) AFTER the existing balance-diff notification logic (look for the current `NotificationHelper.notify(...)` call), add a new block:
       ```kotlin
       try {
           val addresses = buildList {
               // Use whatever receive-address resolver the worker currently uses.
               // At minimum the current index address. SUMMARY must document the exact
               // WalletManager method called.
               val current = wm.getCurrentReceiveAddress()
               if (!current.isNullOrBlank()) add(current)
           }
           val node = io.raventag.app.wallet.RavencoinPublicNode(applicationContext)
           for (addr in addresses) {
               val status: String? = try {
                   val scripthash = io.raventag.app.wallet.RavencoinPublicNode.addressToScripthash(addr)
                   val raw = node.callWithFailover(
                       "blockchain.scripthash.subscribe",
                       listOf(scripthash)
                   )
                   if (raw == null || raw.isJsonNull) null else raw.asString
               } catch (_: Exception) {
                   null
               }
               val prev = prefs.getString("last_status_$addr", null)
               if (status != prev) {
                   prefs.edit().putString("last_status_$addr", status).apply()
                   if (prev != null) {
                       // Real change after baseline established -> re-fetch balance + notify.
                       val balance = try { node.getBalance(addr) } catch (_: Exception) { null }
                       val confirmedSat = balance?.confirmed ?: 0L
                       val cachedSat = prefs.getLong("poll_rvn_sat", 0L)
                       val deltaSat = confirmedSat + (balance?.unconfirmed ?: 0L) - cachedSat
                       if (deltaSat > 0L) {
                           val history = try {
                               node.getTransactionHistory(addr, limit = 3, offset = 0)
                           } catch (_: Exception) { emptyList() }
                           val lastNotified = prefs.getString("last_notified_txid", null)
                           val newestNew = history.firstOrNull { it.txid != lastNotified }
                           if (newestNew != null) {
                               io.raventag.app.worker.IncomingTxNotificationHelper.showIncoming(
                                   context = applicationContext,
                                   txid = newestNew.txid,
                                   rvnAmount = deltaSat / 1e8,
                                   confirmations = newestNew.confirmations
                               )
                               prefs.edit()
                                   .putString("last_notified_txid", newestNew.txid)
                                   .putLong("poll_rvn_sat", confirmedSat + (balance.unconfirmed))
                                   .apply()
                           }
                       }
                   }
                   // First-ever observation: baseline recorded, do not notify retroactively.
               }
           }
       } catch (_: java.io.IOException) {
           return@withContext Result.retry()
       } catch (_: Exception) {
           // D-06 is silent; swallow.
       }
       ```

       Notes on field discovery:
       - `wm` — the existing `WalletManager` instance variable in the worker. If the worker constructs a new one per run (`val wm = WalletManager(applicationContext)`), keep that pattern.
       - `prefs` — already exists per `PATTERNS.md` line 236 (`applicationContext.getSharedPreferences("wallet_poll", MODE_PRIVATE)`).
       - `wm.getCurrentReceiveAddress()` — the method may be named differently in the existing code (e.g., `getCurrentAddress()`, `getReceiveAddress(currentIndex)`, or computed from `currentIndex`). Inspect at execution time and use the exact existing accessor; SUMMARY.md records the precise name.

    4) Ensure the new block executes AFTER the existing Phase 20 balance-diff logic (so that the `poll_rvn_sat` key is already read and any existing notification is already dispatched before the new scripthash-diff pass).

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "blockchain.scripthash.subscribe" android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`
    - `grep -q "last_status_" android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`
    - `grep -q "last_notified_txid" android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`
    - `grep -q "IncomingTxNotificationHelper.showIncoming" android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`
    - `grep -q "NodeHealthMonitor.init(applicationContext)" android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`
    - `grep -q "Result.retry()" android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>WorkManager worker now performs per-address scripthash-status diff, fires IncomingTxNotificationHelper on positive balance delta post-baseline, preserves Phase 20 balance-diff logic, silent on any error except IOException which retries. No em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 4: AppStrings.kt — EN + IT strings for cached banner, pill labels, Pending line, battery chip, notification snackbar, ReceiveScreen sub-label, disabled-state snackbar</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L120-L140,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L143-L222,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L239-L330,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L351-L370,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L419-L435,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </read_first>
  <behavior>
    Add these properties to `class AppStrings` (defaults are the EN values) and assign EN/IT values in the respective `stringsEn` / `stringsIt` blocks. All literals are copy-verbatim from UI-SPEC Copywriting Contract. No em dashes — all separators use middle dot `·` (U+00B7) or colon/comma.

    Property name → EN value → IT value table (every row is a new property unless noted "reuse"):

    | Property key                          | EN value                                                                               | IT value                                                                                    |
    |---------------------------------------|----------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
    | `cachedStateBanner`                   | `Showing cached state · Last updated %1$s`                                             | `Stato in cache · Ultimo aggiornamento %1$s`                                                |
    | `cachedStateReconnecting`             | `Last updated %1$s · reconnecting…`                                                    | `Ultimo aggiornamento %1$s · riconnessione…`                                                 |
    | `pendingBalanceLabel`                 | `Pending`                                                                              | `In attesa`                                                                                 |
    | `batterySaverChip`                    | `Battery saver · manual refresh`                                                       | `Risparmio energetico · aggiorna a mano`                                                    |
    | `connectionPillOnline`                | `Online`                                                                               | `Online`                                                                                    |
    | `connectionPillReconnecting`          | `Reconnecting…`                                                                        | `Riconnessione…`                                                                            |
    | `connectionPillOffline`               | `Offline`                                                                              | `Offline`                                                                                   |
    | `connectionPillSheetTitle`            | `Ravencoin network`                                                                    | `Rete Ravencoin`                                                                            |
    | `connectionPillCurrentNode`           | `Current node`                                                                         | `Nodo attuale`                                                                              |
    | `connectionPillLastSuccess`           | `Last successful RPC`                                                                  | `Ultima RPC riuscita`                                                                       |
    | `connectionPillFallbackNodes`         | `Fallback nodes`                                                                       | `Nodi di riserva`                                                                           |
    | `connectionPillQuarantined`           | `Quarantined until %1$s`                                                               | `In quarantena fino a %1$s`                                                                 |
    | `connectionPillClose`                 | `Close`                                                                                | `Chiudi`                                                                                    |
    | `reconnectingToast`                   | `Reconnecting to Ravencoin network…`                                                   | `Riconnessione alla rete Ravencoin…`                                                        |
    | `offlineAllNodesUnreachable`          | `Offline · all nodes unreachable`                                                      | `Offline · nessun nodo raggiungibile`                                                       |
    | `incomingTxSnackbar`                  | `+%1$s RVN received`                                                                   | `+%1$s RVN ricevuti`                                                                        |
    | `receiveCurrentAddressLabel`          | `Your current address`                                                                 | `Il tuo indirizzo attuale`                                                                  |
    | `receiveCurrentAddressSubLabel`       | `Changes after your next send or consolidation.`                                       | `Cambia dopo il prossimo invio o consolidamento.`                                           |
    | `walletOfflineHeading`                | `Wallet offline`                                                                       | `Wallet offline`                                                                            |
    | `walletOfflineBody`                   | `Cannot reach any Ravencoin node. Check your internet connection, then tap Refresh.`   | `Nessun nodo Ravencoin raggiungibile. Controlla la connessione e tocca Aggiorna.`           |

    Also (if not already present from plans 30-06 / 30-04), ensure the verbatim string "Send" / "Invia" and "Receive" / "Ricevi" actions exist — reuse if already declared.

    Format-string rules:
    - `%1$s` is the Kotlin/Java positional format specifier (literal text `%1$s`). Any "HH:MM" value gets formatted by the caller (e.g., `String.format(strings.cachedStateBanner, "14:32")`).
    - The `%1$s` RVN amount in `incomingTxSnackbar` is passed as the pre-formatted RVN string (e.g., `String.format("%.8f", rvnDouble)` produced by the caller).

    Em-dash audit mandatory.
  </behavior>
  <action>
    1) Read `AppStrings.kt` fully. Identify the shape:
       - A `class AppStrings` declaring public properties (likely all `var x: String = "..."`).
       - A `val stringsEn: AppStrings = AppStrings().apply { ... }` instance near ~line 393 per PATTERNS.
       - A `val stringsIt: AppStrings = AppStrings().apply { ... }` instance near ~line 608.
       - A `val LocalStrings = staticCompositionLocalOf { stringsEn }` provider at the bottom (or similar).

    2) Declare the new `var` properties on `class AppStrings` with EN defaults:
       ```kotlin
       var cachedStateBanner: String = "Showing cached state \u00B7 Last updated %1\$s"
       var cachedStateReconnecting: String = "Last updated %1\$s \u00B7 reconnecting\u2026"
       var pendingBalanceLabel: String = "Pending"
       var batterySaverChip: String = "Battery saver \u00B7 manual refresh"
       var connectionPillOnline: String = "Online"
       var connectionPillReconnecting: String = "Reconnecting\u2026"
       var connectionPillOffline: String = "Offline"
       var connectionPillSheetTitle: String = "Ravencoin network"
       var connectionPillCurrentNode: String = "Current node"
       var connectionPillLastSuccess: String = "Last successful RPC"
       var connectionPillFallbackNodes: String = "Fallback nodes"
       var connectionPillQuarantined: String = "Quarantined until %1\$s"
       var connectionPillClose: String = "Close"
       var reconnectingToast: String = "Reconnecting to Ravencoin network\u2026"
       var offlineAllNodesUnreachable: String = "Offline \u00B7 all nodes unreachable"
       var incomingTxSnackbar: String = "+%1\$s RVN received"
       var receiveCurrentAddressLabel: String = "Your current address"
       var receiveCurrentAddressSubLabel: String = "Changes after your next send or consolidation."
       var walletOfflineHeading: String = "Wallet offline"
       var walletOfflineBody: String = "Cannot reach any Ravencoin node. Check your internet connection, then tap Refresh."
       ```
       (Use the unicode escape `\u00B7` for `·` middle dot and `\u2026` for `…` horizontal ellipsis — these are NOT em dashes; em dash is `\u2014` and is forbidden.)

    3) Add the EN assignments inside `stringsEn.apply { ... }`. They are already the defaults; still set them explicitly for consistency with the existing style.

    4) Add the IT overrides inside `stringsIt.apply { ... }`:
       ```kotlin
       cachedStateBanner = "Stato in cache \u00B7 Ultimo aggiornamento %1\$s"
       cachedStateReconnecting = "Ultimo aggiornamento %1\$s \u00B7 riconnessione\u2026"
       pendingBalanceLabel = "In attesa"
       batterySaverChip = "Risparmio energetico \u00B7 aggiorna a mano"
       connectionPillOnline = "Online"
       connectionPillReconnecting = "Riconnessione\u2026"
       connectionPillOffline = "Offline"
       connectionPillSheetTitle = "Rete Ravencoin"
       connectionPillCurrentNode = "Nodo attuale"
       connectionPillLastSuccess = "Ultima RPC riuscita"
       connectionPillFallbackNodes = "Nodi di riserva"
       connectionPillQuarantined = "In quarantena fino a %1\$s"
       connectionPillClose = "Chiudi"
       reconnectingToast = "Riconnessione alla rete Ravencoin\u2026"
       offlineAllNodesUnreachable = "Offline \u00B7 nessun nodo raggiungibile"
       incomingTxSnackbar = "+%1\$s RVN ricevuti"
       receiveCurrentAddressLabel = "Il tuo indirizzo attuale"
       receiveCurrentAddressSubLabel = "Cambia dopo il prossimo invio o consolidamento."
       walletOfflineHeading = "Wallet offline"
       walletOfflineBody = "Nessun nodo Ravencoin raggiungibile. Controlla la connessione e tocca Aggiorna."
       ```

    5) Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`. If the file already contains em dashes from earlier code, the executor MUST replace them per MEMORY rule before this task is considered done.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "Showing cached state" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Stato in cache" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "reconnecting" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "riconnessione" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "In attesa" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Battery saver" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Risparmio energetico" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Reconnecting to Ravencoin network" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Riconnessione alla rete Ravencoin" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "all nodes unreachable" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "nessun nodo raggiungibile" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "RVN received" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "RVN ricevuti" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Your current address" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Il tuo indirizzo attuale" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Changes after your next send or consolidation" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Cambia dopo il prossimo invio o consolidamento" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Wallet offline" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>All Phase 30 visible EN + IT strings live in AppStrings.kt verbatim from UI-SPEC Copywriting Contract. No em dashes. Build passes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 5: WalletScreen.kt — cached-state banner + 2dp LinearProgressIndicator + Pending line + Battery-saver chip + extended ElectrumStatusBadge (YELLOW) + ModalBottomSheet</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L117-L140,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L237-L302,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L320-L335,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L339-L370,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L378-L389,
    @android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/Theme.kt,
    @android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt,
    @android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt,
    @android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  </read_first>
  <behavior>
    Extend WalletScreen.kt with the following composable additions (all inside the same file; no new top-level files). The screen's existing architecture uses a `WalletInfo` data class (line ~62-68) and `electrumStatus: MainViewModel.ElectrumStatus` parameter (line ~90) — preserve existing parameters; add what is needed.

    Sub-elements to add (each is its own private composable where reasonable):

    5.1) `@Composable private fun CachedStateBanner(lastRefreshedAt: Long?, isReconnecting: Boolean, visible: Boolean)`:
        - Visible iff `visible == true` AND `lastRefreshedAt != null`.
        - Card: `RavenCard` bg, `1.dp RavenBorder` border, `RoundedCornerShape(12.dp)`, padding `12dp`.
        - Row: `Icons.Default.History` 16dp `RavenMuted` + 8dp gap + Text bodySmall RavenMuted.
        - Text value:
          - when `isReconnecting == false`: `String.format(strings.cachedStateBanner, formatHhMm(lastRefreshedAt))` → EN "Showing cached state · Last updated HH:MM" / IT "Stato in cache · Ultimo aggiornamento HH:MM".
          - when `isReconnecting == true`: `String.format(strings.cachedStateReconnecting, formatHhMm(lastRefreshedAt))` → EN "Last updated HH:MM · reconnecting…" / IT "Ultimo aggiornamento HH:MM · riconnessione…".
        - `formatHhMm(ms)` → `java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(ms))`.
        - Auto-dismiss: caller sets `visible = false` as soon as a successful refresh completes.

    5.2) `@Composable private fun PendingBalanceLine(mempoolIncomingSat: Long)`:
        - Hidden entirely when `mempoolIncomingSat <= 0L`.
        - Row: `Icons.Default.Schedule` 12dp RavenMuted + 4dp gap + Text "Pending"/"In attesa" (`strings.pendingBalanceLabel`) bodySmall RavenMuted + 8dp gap + Text amount formatted `"+%.8f RVN"`.
        - Amount color: `Color(0xFFF59E0B)` (amber literal, per UI-SPEC §Pending balance line). Use `Color(0xFFF59E0B)` inline (not from Theme — the amber is a Phase 30 addition that UI-SPEC keeps literal).
        - Style: `bodySmall` / Normal weight.

    5.3) `@Composable private fun BatterySaverChip()`:
        - Only rendered when `PowerManager.isPowerSaveMode() == true`. Caller gates rendering; chip itself is unconditional once rendered.
        - Card container: `RavenCard` bg, `RoundedCornerShape(8.dp)`, `BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.25f))`.
        - Inner padding: `horizontal = 8.dp, vertical = 4.dp`.
        - Content: Row — `Icons.Default.BatterySaver` 10dp amber `Color(0xFFF59E0B)` + 4dp gap + Text `strings.batterySaverChip` labelSmall amber.
        - Tap: does nothing (informational). No clickable modifier.

    5.4) Extend the existing `ElectrumStatusBadge` (WalletScreen.kt:757-780) with a YELLOW state:
        - Drive pill color from `NodeHealthMonitor.stateFlow.collectAsState(initial = ConnectionHealth.GREEN)` — the screen already has a binding; pass the enum in.
        - GREEN: existing behavior — AuthenticGreen dot pulsing + text `strings.connectionPillOnline`.
        - YELLOW: amber `Color(0xFFF59E0B)` pulsing dot + text `strings.connectionPillReconnecting`.
        - RED: NotAuthenticRed STATIC dot (no pulse) + text `strings.connectionPillOffline`.
        - Dot pulse animation: reuse the existing animation modifier the badge already has for GREEN; apply to YELLOW; suppress on RED.
        - The pill itself must be tappable: `Modifier.clickable { showConnectionSheet = true }`. Accessibility touch target ≥ 48dp (use `Modifier.sizeIn(minHeight = 48.dp)` or equivalent).

    5.5) Connection pill tap sheet: `@Composable private fun ConnectionPillSheet(onDismiss: () -> Unit)`:
        - `ModalBottomSheet(onDismissRequest = onDismiss, containerColor = RavenCard)` from `androidx.compose.material3`.
        - Content column, padding 16dp:
          - Title bar: Text `strings.connectionPillSheetTitle` titleSmall SemiBold white.
          - Section 1: Text `strings.connectionPillCurrentNode` labelSmall RavenMuted + Text `NodeHealthMonitor.currentNode() ?: "—"` bodySmall monospace white. (Note: the dash `—` literal is forbidden per MEMORY. Replace with the explicit fallback string `"(none)"` / `"(nessuno)"` — add a new property `connectionPillNoNode` to AppStrings if needed in Task 4 above; if not already added, executor adds inline literals `"(none)"`/`"(nessuno)"` here and appends the missing string to AppStrings in the same diff.)
          - Section 2: Text `strings.connectionPillLastSuccess` labelSmall RavenMuted + Text formatted timestamp from `NodeHealthMonitor.diagnostics()` for `currentNode()`; format via `formatHhMm(...)` or `"—"` equivalent non-emdash fallback.
          - Section 3: Text `strings.connectionPillFallbackNodes` labelSmall RavenMuted, then a Column over `NodeHealthMonitor.diagnostics()`. Each row: status circle (green if `quarantinedUntil == null`, red if quarantined) + 8dp gap + host string (monospace bodySmall) + if quarantined: 8dp + Text formatted via `strings.connectionPillQuarantined` with HH:MM.
          - Close button at bottom: `OutlinedButton(onClick = onDismiss, border = BorderStroke(1.dp, RavenBorder))` with Text `strings.connectionPillClose`.

    5.6) Sync-in-background indicator: a 2dp `LinearProgressIndicator` placed flush below the wallet header. Visible while `isRefreshing == true`. Colors: `LinearProgressIndicator(color = RavenOrange, trackColor = RavenBorder)`. `height = 2.dp`. Indeterminate.

    5.7) Disabled Send/Receive when pill is RED:
        - Read `val health by NodeHealthMonitor.stateFlow.collectAsState(initial = ConnectionHealth.GREEN)`.
        - When `health == ConnectionHealth.RED`:
          - Send button: `Modifier.alpha(0.3f)`, text color `RavenMuted`, icon tint `RavenMuted`, `enabled = false` (so the existing onClick does not fire).
          - Receive button: same as Send.
          - Wrap the button Row with `Modifier.clickable(enabled = true)` that shows a Snackbar using `strings.offlineAllNodesUnreachable` on `NotAuthenticRedBg` container. The clickable MUST be attached to the wrapper, NOT the disabled button, so that even when `enabled = false`, the tap still fires the snackbar. Use `LaunchedEffect` with a `MutableState<Boolean>` that triggers `snackbarHostState.showSnackbar(...)`.
        - When `health != ConnectionHealth.RED`: existing Send/Receive behavior unchanged.

    5.8) 30-second periodic refresh loop, gated by power-save:
        - In the WalletScreen `@Composable`, add:
          ```kotlin
          val context = LocalContext.current
          val lifecycleState = ... // existing lifecycle observer pattern
          LaunchedEffect(lifecycleState) {
              while (true) {
                  kotlinx.coroutines.delay(30_000L)
                  val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                  if (!pm.isPowerSaveMode) {
                      onPeriodicRefresh()  // existing refresh hook
                  }
              }
          }
          ```
        - The scripthash subscription (plan 30-03 SubscriptionManager) stays alive regardless of power-save — its own lifecycle is managed independently by the WalletViewModel / SubscriptionManager start()/stop() paired with foreground state (already wired by plan 30-03).

    5.9) In-app Snackbar on incoming tx:
        - Collect `SubscriptionManager.eventsFlow()` (inject via existing DI or `remember { SubscriptionManager(context) }` used elsewhere in the file).
        - On each `ScripthashEvent.StatusChanged`: schedule a re-fetch via the existing refresh hook. AFTER the re-fetch completes, compute `deltaSat = newBalance - previousBalance`. If `deltaSat > 0L`:
          - `snackbarHostState.showSnackbar(String.format(strings.incomingTxSnackbar, String.format("%.8f", deltaSat / 1e8)))`.
          - Visual: this task keeps the snackbar call; the styled Snackbar host that paints AuthenticGreenBg + AuthenticGreen text + Icons.Default.CallReceived is the screen's top-level `SnackbarHost` configuration. If the existing SnackbarHost does not support per-call theming, add a `customSnackbar` override using `Snackbar(containerColor = AuthenticGreenBg, contentColor = AuthenticGreen, action = null)` with an icon-decorated message body.

    5.10) Instant-render cached state on open:
        - On first composition (`LaunchedEffect(Unit)`): read `WalletCacheDao.readState()` and seed the screen state (balance, tx list) BEFORE any network call. Track `lastRefreshedAt = WalletCacheDao.getLastRefreshedAt()`. Flip `showCachedBanner = true` while a successful refresh has not yet completed.

    Order of composition additions on the screen header column (top-down):
    1. Existing `walletTitle` Text row.
    2. Row: block height + ElectrumStatusBadge (YELLOW-capable) + Refresh icon.
    3. `BatterySaverChip()` (conditional).
    4. `CachedStateBanner(...)` (conditional).
    5. 2dp LinearProgressIndicator (conditional on `isRefreshing`).
    6. BalanceCard (existing, with PendingBalanceLine inserted inside directly under fiat).
    7. Actions Row (Send / Receive with disabled-state wrapper).
    8. Existing LazyColumn tx history (untouched in this task; plan 30-09 rewrites the outgoing row).

    Em-dash audit on WalletScreen.kt.
  </behavior>
  <action>
    1) Read `WalletScreen.kt` fully. Identify the exact names of:
       - The existing `electrumStatus`/`ElectrumStatus` parameter or ViewModel field.
       - The existing refresh hook (`onRefresh`, `viewModel.refresh()`, or inline fetch block).
       - The existing `LazyColumn`/`BalanceCard`/`ActionsRow` composables.
       - The existing `SnackbarHost` / `SnackbarHostState` binding.

    2) Add `import`s as needed:
       ```kotlin
       import androidx.compose.material.icons.filled.History
       import androidx.compose.material.icons.filled.Schedule
       import androidx.compose.material.icons.filled.BatterySaver
       import androidx.compose.material.icons.filled.CallReceived
       import androidx.compose.material3.LinearProgressIndicator
       import androidx.compose.material3.ModalBottomSheet
       import androidx.compose.material3.rememberModalBottomSheetState
       import androidx.compose.runtime.collectAsState
       import io.raventag.app.wallet.health.NodeHealthMonitor
       import io.raventag.app.wallet.health.ConnectionHealth
       import io.raventag.app.wallet.cache.WalletCacheDao
       import io.raventag.app.wallet.subscription.SubscriptionManager
       import io.raventag.app.wallet.subscription.ScripthashEvent
       ```

    3) Add the five private composables above (`CachedStateBanner`, `PendingBalanceLine`, `BatterySaverChip`, extension to `ElectrumStatusBadge`, `ConnectionPillSheet`) inside WalletScreen.kt, ideally at the bottom of the file next to the existing pattern.

    4) Insert usages in the top-level `WalletScreen` composable in the stated order.

    5) Wire the 30-second `LaunchedEffect` loop with `isPowerSaveMode` gate. The subscription start/stop is already managed by plan 30-03's `SubscriptionManager` and the WalletViewModel's foreground observer — do NOT re-implement subscription lifecycle here; only subscribe to its `eventsFlow()`.

    6) Wire the disabled Send/Receive state:
       ```kotlin
       val health by NodeHealthMonitor.stateFlow.collectAsState(initial = ConnectionHealth.GREEN)
       val sendEnabled = health != ConnectionHealth.RED
       val receiveEnabled = health != ConnectionHealth.RED
       Box(
           modifier = Modifier.then(
               if (!sendEnabled) Modifier.clickable {
                   scope.launch { snackbarHostState.showSnackbar(strings.offlineAllNodesUnreachable) }
               } else Modifier
           )
       ) {
           Button(
               onClick = onSendClick,
               enabled = sendEnabled,
               modifier = Modifier.alpha(if (sendEnabled) 1f else 0.3f),
               colors = ButtonDefaults.buttonColors(
                   contentColor = if (sendEnabled) Color.White else RavenMuted,
                   disabledContentColor = RavenMuted
               )
           ) { /* existing content */ }
       }
       ```
       (Mirror for Receive.)

    7) Wire the ModalBottomSheet:
       ```kotlin
       var showConnectionSheet by remember { mutableStateOf(false) }
       // ElectrumStatusBadge tap: showConnectionSheet = true
       if (showConnectionSheet) {
           ConnectionPillSheet(onDismiss = { showConnectionSheet = false })
       }
       ```

    8) Wire the scripthash-event collector for the in-app Snackbar:
       ```kotlin
       LaunchedEffect(Unit) {
           subscriptionManager.eventsFlow().collect { ev ->
               when (ev) {
                   is ScripthashEvent.StatusChanged -> {
                       val beforeSat = WalletCacheDao.readState()?.balanceSat ?: 0L
                       onPeriodicRefresh()
                       val afterSat = WalletCacheDao.readState()?.balanceSat ?: 0L
                       val deltaSat = afterSat - beforeSat
                       if (deltaSat > 0L) {
                           val rvn = String.format(java.util.Locale.ROOT, "%.8f", deltaSat / 1e8)
                           snackbarHostState.showSnackbar(
                               String.format(strings.incomingTxSnackbar, rvn)
                           )
                       }
                   }
                   ScripthashEvent.ConnectionLost, ScripthashEvent.AllNodesDown -> {
                       // Pill color already driven via NodeHealthMonitor.stateFlow.
                   }
                   else -> {}
               }
           }
       }
       ```
       Where `subscriptionManager` is the existing instance the screen uses (inspect the file; if not already present, hoist a `remember { SubscriptionManager(context) }` near the top and start/stop it in a `DisposableEffect(Unit)` tied to foreground).

    9) PowerManager gating:
       ```kotlin
       val isPowerSave by remember {
           derivedStateOf {
               val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
               pm.isPowerSaveMode
           }
       }
       if (isPowerSave) { BatterySaverChip() }
       ```
       (If the executor finds that `derivedStateOf` on a non-State input is ineffective, substitute a `var isPowerSave by remember { mutableStateOf(...) }` refreshed on `LaunchedEffect(lifecycleState)` — same effect for v1.)

    10) Do NOT modify the existing TxCard rendering in this task (plan 30-09 owns it). Explicitly keep LazyColumn + `items(txHistory)` body unchanged.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`. If the file contains em dashes from earlier code, replace them per MEMORY rule.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -30</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "fun CachedStateBanner" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "fun PendingBalanceLine" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "fun BatterySaverChip" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "fun ConnectionPillSheet" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "ModalBottomSheet" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Icons.Default.History" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Icons.Default.Schedule" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Icons.Default.BatterySaver" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Icons.Default.CallReceived" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "LinearProgressIndicator" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Color(0xFFF59E0B)" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "NodeHealthMonitor.stateFlow" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "NodeHealthMonitor.diagnostics" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "NodeHealthMonitor.currentNode" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "ConnectionHealth.RED" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "WalletCacheDao.readState" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "isPowerSaveMode" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "ScripthashEvent" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "offlineAllNodesUnreachable" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "incomingTxSnackbar" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "cachedStateBanner" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "cachedStateReconnecting" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "batterySaverChip" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "pendingBalanceLabel" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "connectionPillSheetTitle" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "delay(30_000L)" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>CachedStateBanner, PendingBalanceLine, BatterySaverChip, extended ElectrumStatusBadge (YELLOW), ConnectionPillSheet, 2dp LinearProgressIndicator, 30s power-save-gated poll, disabled-state Send/Receive, incoming-tx in-app snackbar all wired. TxCard unchanged (plan 30-09 owns it). No em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 6: ReceiveScreen.kt — D-18 sub-label + 200ms cross-fade on currentIndex advance</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L351-L358,
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L47-L48,
    @android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </read_first>
  <behavior>
    Extend ReceiveScreen with the D-18 main label, sub-label, and the 200ms cross-fade on address change.

    1. Directly under the QR code (existing layout, unchanged), render:
       - Main label: `Text(strings.receiveCurrentAddressLabel, style = bodyMedium, color = Color.White, textAlign = TextAlign.Center)`.
       - Sub-label: `Text(strings.receiveCurrentAddressSubLabel, style = bodySmall, color = RavenMuted, textAlign = TextAlign.Center)`.

    2. The address Text below (existing tap-to-copy pattern, retained) must now be wrapped in `AnimatedContent(targetState = currentAddress, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }) { addr -> Text(addr, ...) }`. `currentAddress` is the input currentIndex-derived address that the screen already receives.

    3. Do NOT add a rotation button. Do NOT add multi-address UI. D-18 explicitly excludes these.

    4. Existing "Copied" fade on tap (UI-SPEC §Receive flow step 4) is untouched.

    Em-dash audit.
  </behavior>
  <action>
    1) Read `ReceiveScreen.kt`. Identify:
       - The current address Text element and its binding (e.g., `address: String` parameter or collected state).
       - Any existing label above/below the QR.

    2) Add imports:
       ```kotlin
       import androidx.compose.animation.AnimatedContent
       import androidx.compose.animation.fadeIn
       import androidx.compose.animation.fadeOut
       import androidx.compose.animation.togetherWith
       import androidx.compose.animation.core.tween
       ```

    3) Replace or wrap the current address `Text(address)` with:
       ```kotlin
       AnimatedContent(
           targetState = address,
           transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
           label = "receiveAddressCrossFade"
       ) { shown ->
           Text(
               text = shown,
               style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
               color = Color.White,
               modifier = Modifier
                   .clickable { /* existing copy-to-clipboard handler */ }
                   .padding(8.dp)
           )
       }
       ```
       (Preserve the existing clipboard handler — executor inspects and reuses it verbatim.)

    4) Insert the two labels directly under the QR (or between QR and address, respecting existing spacing):
       ```kotlin
       Text(
           text = strings.receiveCurrentAddressLabel,
           style = MaterialTheme.typography.bodyMedium,
           color = Color.White,
           textAlign = TextAlign.Center,
           modifier = Modifier.fillMaxWidth()
       )
       Spacer(Modifier.height(4.dp))
       Text(
           text = strings.receiveCurrentAddressSubLabel,
           style = MaterialTheme.typography.bodySmall,
           color = RavenMuted,
           textAlign = TextAlign.Center,
           modifier = Modifier.fillMaxWidth()
       )
       ```

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "receiveCurrentAddressLabel" android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`
    - `grep -q "receiveCurrentAddressSubLabel" android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`
    - `grep -q "AnimatedContent" android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`
    - `grep -q "tween(200)" android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`
    - `grep -q "fadeIn" android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`
    - `grep -q "fadeOut" android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>ReceiveScreen shows main label + sub-label per D-18 verbatim from UI-SPEC; address text cross-fades with tween(200) when currentIndex advances. No em dashes.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| ElectrumX subscription socket → WalletScreen state | Notifications carry only a status hash; WalletScreen refetches balance through the trusted one-shot RPC path (TofuTrustManager, pinned TLS). No balance value comes from the push payload. |
| WorkManager one-shot scripthash RPC → notification | The RPC call is pinned via the same TofuTrustManager; notification payload is derived only from a successful `getBalance` + `getTransactionHistory` result. |
| NotificationChannel `incoming_tx` → system | Only this app's process writes to the channel (Android enforces this). Cross-app notification spoofing is not applicable. |
| Intent `VIEW_TRANSACTION` + `txid` extra → MainActivity | Intent originates from this app's own PendingIntent with FLAG_IMMUTABLE; external intents without the correct package/component cannot forge. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-RECV-04 | Spoofing | Malicious ElectrumX server pushes forged `scripthash.subscribe` notification to mislead WalletScreen | mitigate | Notification only triggers a re-fetch via `RavencoinPublicNode` (TofuTrustManager + TLS). Balance written from RPC result, never from notification payload (RESEARCH §Pattern 1 invariants). |
| T-30-RECV-05 | Tampering | Stale scripthash status in SharedPreferences causes missed incoming notification after process death | accept | Baseline-on-first-run is intentional (no retroactive spam). Subsequent runs compare against persisted state. Worst case: one missed notification for a tx that arrived in the brief baseline window — the tx still appears in history on next WalletScreen open. |
| T-30-RECV-06 | Tampering | Notification payload uses server-reported confirmations; malicious server could mislead variant selection (mempool vs confirmed) | accept | Impact limited to text style; balance delta is derived from the Keystore-protected local cache vs fresh RPC, so even a spoofed "6 confirms" cannot change the amount the user sees. User still verifies on WalletScreen. |
| T-30-NET-08 | Denial of Service | Attacker forces a rapid scripthash-status flap to spam notifications | mitigate | WalletPollingWorker runs at 15-min intervals (OS-enforced minimum). Foreground subscription collapses bursts via SharedFlow `extraBufferCapacity` (plan 30-03). Snackbar for incoming is transient (SnackbarDuration.Short). |
| T-30-NET-09 | Information Disclosure | PendingIntent leaks txid to other apps | accept | PendingIntent uses `FLAG_IMMUTABLE` + explicit component targeting MainActivity. txid is public blockchain data; no secret disclosed. |
| T-30-NET-10 | Elevation of Privilege | Malicious external intent replays `VIEW_TRANSACTION` with arbitrary `txid` | accept | TransactionDetailsScreen fetches details via trusted RPC; displaying a forged txid only shows "tx not found" — no privileged action is taken. User cannot be tricked into authorizing anything from the details screen (no send / signing action lives there). |

ASVS V9 Communications (TLS + TOFU inherited from Phase 10 / plan 30-03 / plan 30-07), V7 Error Handling (silent D-06 path), V10 Malicious Code (PendingIntent IMMUTABLE + explicit component). ASVS L1 adequate.
</threat_model>

<verification>
- `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
- `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
- `! grep -rP '\u2014' android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt android/app/src/main/java/io/raventag/app/MainActivity.kt android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt` returns no matches.
- `grep -c "createChannel" android/app/src/main/java/io/raventag/app/MainActivity.kt` is ≥ 3 (NotificationHelper, TransactionNotificationHelper, IncomingTxNotificationHelper).
- Manual device verification (per 30-VALIDATION.md Manual-Only row "WorkManager detects balance increase"):
  1. Install consumer APK. Put app in background. From another wallet send 0.001 RVN to the current receive address. Within 15 minutes, expect system notification `Incoming transaction · +0.001 RVN · Pending`. Tap notification → TransactionDetailsScreen opens with the txid.
  2. With the app foregrounded and WiFi connected, send 0.001 RVN from another wallet. Expect within seconds an in-app Snackbar `+0.00100000 RVN received` (or IT `ricevuti`) and the tx row prepended with a red (0-conf) dot.
  3. Enable Battery Saver (system Settings). Open WalletScreen. Expect amber "Battery saver · manual refresh" chip. Leave the screen open for 2 minutes — scripthash subscription remains open (verify via logcat that pings still happen); the 30s poll does NOT fire (no refresh log entries in the 30s interval).
  4. Disable all network. Wait for NodeHealthMonitor RED. Send/Receive buttons render at alpha 0.3 with RavenMuted text; tap Send → Snackbar `Offline · all nodes unreachable`.
  5. Re-enable network. Pill transitions YELLOW → GREEN. Send/Receive return to normal.
  6. Tap the connection pill. Bottom sheet opens listing current node URL + last success HH:MM + fallback nodes with quarantine markers + Close button.
  7. Open ReceiveScreen. Verify main label "Your current address" / "Il tuo indirizzo attuale" and sub-label "Changes after your next send or consolidation." / "Cambia dopo il prossimo invio o consolidamento." Initiate a send; after broadcast the displayed address cross-fades (≈200ms) to the new `currentIndex` address.
</verification>

<success_criteria>
- IncomingTxNotificationHelper compiles with channel `incoming_tx`, three-variant text selection, FLAG_IMMUTABLE PendingIntent, POST_NOTIFICATIONS guard, and the mod-1024 notificationId formula.
- MainActivity registers all three notification channels at startup and routes VIEW_TRANSACTION + `txid` intents to TransactionDetailsScreen.
- WalletPollingWorker performs per-address scripthash-status diff against SharedPreferences `last_status_*`, fires IncomingTxNotificationHelper on positive balance delta (post-baseline), and preserves Phase 20 balance-diff logic.
- WalletScreen renders CachedStateBanner, 2dp LinearProgressIndicator, PendingBalanceLine, BatterySaverChip, extended ElectrumStatusBadge with YELLOW state, ConnectionPillSheet, 30s power-save-gated poll, scripthash-event-driven incoming Snackbar, and RED-state disabled Send/Receive with offline snackbar.
- ReceiveScreen shows D-18 main label + sub-label and 200ms cross-fade on currentIndex change.
- AppStrings.kt contains every new EN + IT string verbatim from UI-SPEC Copywriting Contract.
- `! grep -P '\u2014'` on every touched file returns no matches.
- `./gradlew :app:assembleConsumerDebug` exits 0.
</success_criteria>

<output>
After completion, create `.planning/phases/30-wallet-reliability/30-08-SUMMARY.md`:
- Exact line number where `IncomingTxNotificationHelper.createChannel(this)` was inserted in MainActivity.kt.
- Whether a VIEW_TRANSACTION handler pre-existed (from Phase 20 plan 20-05) or was newly added in this plan, plus the navigation hook invoked (e.g., `navController.navigate("tx/$txid")`).
- Exact WalletManager accessor used by WalletPollingWorker to obtain the current receive address (e.g., `getCurrentReceiveAddress()`, `getReceiveAddress(currentIndex)`).
- Exact SubscriptionManager instance source used by WalletScreen (injected vs `remember { SubscriptionManager(context) }`).
- Hand-off to plan 30-09: WalletScreen TxCard outgoing row rewrite is the ONLY remaining WalletScreen change; this plan preserved TxCard untouched.
- Hand-off to plan 30-10: em-dash audit sweep should include all files touched here (IncomingTxNotificationHelper.kt, WalletPollingWorker.kt, MainActivity.kt, WalletScreen.kt, ReceiveScreen.kt, AppStrings.kt).
</output>
