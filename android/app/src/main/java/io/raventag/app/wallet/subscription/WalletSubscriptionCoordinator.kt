package io.raventag.app.wallet.subscription

import android.content.Context
import android.util.Log
import io.raventag.app.wallet.RavencoinPublicNode
import io.raventag.app.wallet.WalletManager
import io.raventag.app.worker.IncomingTxNotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

data class IncomingTransferEvent(
    val txid: String,
    val rvnAmount: Double,
    val assetName: String?,
    val assetAmount: Double,
    val confirmations: Int
)

/**
 * Coordinates persistent ElectrumX socket subscriptions across foreground sessions
 * and background services.
 *
 * Ensures incoming transactions trigger real-time notifications (< 2 seconds) and
 * update the wallet UI without duplicate alerts.
 */
object WalletSubscriptionCoordinator {
    private const val TAG = "WalletSubCoord"
    private var subscriptionManager: SubscriptionManager? = null
    private var coordinatorScope: CoroutineScope? = null
    private var isForegroundServiceRunning = false
    private var isAppInForeground = false
    private val isProcessingEvent = AtomicBoolean(false)

    private val _incomingEvents = MutableSharedFlow<IncomingTransferEvent>(extraBufferCapacity = 16)
    val incomingEvents: SharedFlow<IncomingTransferEvent> = _incomingEvents.asSharedFlow()

    @Synchronized
    fun onAppForeground(context: Context) {
        Log.i(TAG, "onAppForeground")
        isAppInForeground = true
        ensureStarted(context.applicationContext)
    }

    @Synchronized
    fun onAppBackground(@Suppress("UNUSED_PARAMETER") context: Context) {
        Log.i(TAG, "onAppBackground, fgService=$isForegroundServiceRunning")
        isAppInForeground = false
        if (!isForegroundServiceRunning) {
            stopInternal()
        }
    }

    @Synchronized
    fun onForegroundServiceStarted(context: Context) {
        Log.i(TAG, "onForegroundServiceStarted")
        isForegroundServiceRunning = true
        ensureStarted(context.applicationContext)
    }

    @Synchronized
    fun onForegroundServiceStopped(@Suppress("UNUSED_PARAMETER") context: Context) {
        Log.i(TAG, "onForegroundServiceStopped, appInFg=$isAppInForeground")
        isForegroundServiceRunning = false
        if (!isAppInForeground) {
            stopInternal()
        }
    }

    fun onAddressRotated(@Suppress("UNUSED_PARAMETER") context: Context, newAddress: String) {
        Log.i(TAG, "onAddressRotated: $newAddress")
        val scope = coordinatorScope ?: return
        scope.launch(Dispatchers.IO) {
            try {
                subscriptionManager?.subscribeAddresses(listOf(newAddress))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to subscribe rotated address $newAddress", e)
            }
        }
    }

    private fun ensureStarted(context: Context) {
        if (coordinatorScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        coordinatorScope = scope

        val sm = subscriptionManager ?: SubscriptionManager(context).also { subscriptionManager = it }

        scope.launch {
            val wm = WalletManager(context)
            if (!wm.hasWallet()) return@launch
            val currentIndex = wm.getCurrentAddressIndex()
            // Subscribe current receive address and last 4 addresses for fast startup
            val startIdx = maxOf(0, currentIndex - 4)
            val addresses = wm.getAddressBatch(0, startIdx..currentIndex).values.toList()
            if (addresses.isEmpty()) return@launch

            Log.i(TAG, "Starting subscription for ${addresses.size} addresses (indices $startIdx..$currentIndex)")

            var currentAddresses = addresses
            while (isActive) {
                try {
                    sm.start(currentAddresses)
                    Log.i(TAG, "Subscription active, listening for events on ${currentAddresses.size} addresses...")
                    sm.eventsFlow().collect { ev ->
                        when (ev) {
                            is ScripthashEvent.StatusChanged -> {
                                Log.i(TAG, "Received StatusChanged for sh=${ev.scripthash} status=${ev.newStatus}")
                                handleStatusChanged(context, ev.scripthash, ev.newStatus)
                            }
                            is ScripthashEvent.ConnectionLost, is ScripthashEvent.PingTimeout, is ScripthashEvent.AllNodesDown -> {
                                Log.w(TAG, "Connection lost or timeout: ${ev.javaClass.simpleName}, breaking collect to reconnect...")
                                sm.stop()
                                throw java.io.IOException("Subscription disconnected: ${ev.javaClass.simpleName}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Subscription loop error, reconnecting in 3s: ${e.message}")
                    delay(3_000L)
                    val currentIdx = wm.getCurrentAddressIndex()
                    val startIdx = maxOf(0, currentIdx - 4)
                    currentAddresses = wm.getAddressBatch(0, startIdx..currentIdx).values.toList()
                }
            }
        }
    }

    private fun stopInternal() {
        Log.i(TAG, "Stopping coordinator and subscription")
        coordinatorScope?.cancel()
        coordinatorScope = null
        val sm = subscriptionManager
        subscriptionManager = null
        if (sm != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try { sm.stop() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun handleStatusChanged(context: Context, scripthash: String, status: String?) {
        if (status == null) return
        if (isProcessingEvent.getAndSet(true)) {
            Log.i(TAG, "Event already in processing, skipping duplicate trigger")
            return
        }
        try {
            withContext(Dispatchers.IO) {
                val wm = WalletManager(context)
                if (!wm.hasWallet()) return@withContext
                val currentIndex = wm.getCurrentAddressIndex()
                val activeAddresses = wm.getAddressBatch(0, maxOf(0, currentIndex - 4)..currentIndex)
                val node = RavencoinPublicNode(context)

                val prefs = context.getSharedPreferences("wallet_polling", Context.MODE_PRIVATE)
                val appPrefs = context.getSharedPreferences("raventag_app", Context.MODE_PRIVATE)
                val notificationsEnabled = appPrefs.getBoolean("notifications_enabled", true)
                val lastNotifiedTxid = prefs.getString("last_notified_txid", null)
                val lastRvnSat = prefs.getLong("poll_rvn_sat", -1L)

                // Target address for this scripthash
                val targetAddr = activeAddresses.values.firstOrNull {
                    node.addressToScripthash(it) == scripthash
                } ?: wm.getCurrentAddress() ?: return@withContext

                Log.i(TAG, "Checking transactions for target address $targetAddr (scripthash=$scripthash)")

                val ownedSet = activeAddresses.values.toSet()
                val history = try {
                    node.getTransactionHistory(targetAddr, limit = 5, offset = 0, ownedAddresses = ownedSet)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get history for $targetAddr", e)
                    emptyList()
                }

                val newestIncoming = history.firstOrNull { it.isIncoming && it.txid != lastNotifiedTxid }
                Log.i(TAG, "newestIncoming=${newestIncoming?.txid} amountSat=${newestIncoming?.amountSat} asset=${newestIncoming?.assetName} lastNotified=$lastNotifiedTxid")

                // Total balance check
                val currentRvnSat = try {
                    (node.getTotalBalance(activeAddresses.values.toList()) * 1e8).toLong()
                } catch (_: Exception) { lastRvnSat }
                val rvnDeltaSat = if (lastRvnSat >= 0L) currentRvnSat - lastRvnSat else 0L

                if (newestIncoming != null) {
                    val isFreshOrUnconfirmed = lastNotifiedTxid != null || newestIncoming.confirmations < 6
                    val rvnAmount = if (newestIncoming.amountSat > 0L) {
                        newestIncoming.amountSat / 1e8
                    } else if (rvnDeltaSat > 0L) {
                        rvnDeltaSat / 1e8
                    } else {
                        0.0
                    }
                    val assetName = newestIncoming.assetName
                    val assetAmount = newestIncoming.assetAmount / 1e8

                    if (notificationsEnabled && isFreshOrUnconfirmed) {
                        if (assetName != null) {
                            IncomingTxNotificationHelper.showIncomingAsset(
                                context = context,
                                txid = newestIncoming.txid,
                                assetName = assetName,
                                assetAmount = assetAmount,
                                confirmations = newestIncoming.confirmations
                            )
                        } else if (rvnAmount > 0.0) {
                            IncomingTxNotificationHelper.showIncoming(
                                context = context,
                                txid = newestIncoming.txid,
                                rvnAmount = rvnAmount,
                                confirmations = newestIncoming.confirmations
                            )
                        }
                    }

                    prefs.edit()
                        .putString("last_notified_txid", newestIncoming.txid)
                        .putLong("poll_rvn_sat", currentRvnSat)
                        .apply()

                    _incomingEvents.emit(
                        IncomingTransferEvent(
                            txid = newestIncoming.txid,
                            rvnAmount = rvnAmount,
                            assetName = assetName,
                            assetAmount = assetAmount,
                            confirmations = newestIncoming.confirmations
                        )
                    )
                } else if (rvnDeltaSat > 0L) {
                    val rvnAmount = rvnDeltaSat / 1e8
                    val fallbackTxid = "rvn_${System.currentTimeMillis()}"
                    Log.i(TAG, "Fallback notification on delta: +$rvnAmount RVN")
                    if (notificationsEnabled) {
                        IncomingTxNotificationHelper.showIncoming(
                            context = context,
                            txid = fallbackTxid,
                            rvnAmount = rvnAmount,
                            confirmations = 0
                        )
                    }
                    prefs.edit()
                        .putString("last_notified_txid", fallbackTxid)
                        .putLong("poll_rvn_sat", currentRvnSat)
                        .apply()

                    _incomingEvents.emit(
                        IncomingTransferEvent(
                            txid = fallbackTxid,
                            rvnAmount = rvnAmount,
                            assetName = null,
                            assetAmount = 0.0,
                            confirmations = 0
                        )
                    )
                } else {
                    Log.i(TAG, "No new incoming tx detected, updating poll_rvn_sat to $currentRvnSat")
                    prefs.edit().putLong("poll_rvn_sat", currentRvnSat).apply()
                    _incomingEvents.emit(
                        IncomingTransferEvent(
                            txid = "",
                            rvnAmount = 0.0,
                            assetName = null,
                            assetAmount = 0.0,
                            confirmations = 0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling status change for $scripthash", e)
        } finally {
            isProcessingEvent.set(false)
        }
    }
}
