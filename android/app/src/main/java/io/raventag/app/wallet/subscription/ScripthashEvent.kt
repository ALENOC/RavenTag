package io.raventag.app.wallet.subscription

/**
 * Events emitted by [SubscriptionManager] via its [SubscriptionManager.eventsFlow].
 *
 * Subscription notifications use RESEARCH.md Architecture Pattern 1:
 * a status change only signals "something changed" and the caller MUST re-fetch
 * balance/utxo/history to get the actual data.
 */
sealed class ScripthashEvent {
    /**
     * ElectrumX pushed a status-hash change for [scripthash]. [newStatus] may be null
     * when the server reports "no history". Caller MUST re-fetch balance/utxo/history
     * per RESEARCH.md Architecture Pattern 1: subscription only says "something changed".
     */
    data class StatusChanged(val scripthash: String, val newStatus: String?) : ScripthashEvent()

    /** The session socket died (network transition, server reset). */
    data object ConnectionLost : ScripthashEvent()

    /** All fallback servers refused connection. D-12 red pill. */
    data object AllNodesDown : ScripthashEvent()

    /** Ping did not return within 60s: socket is a zombie (Pitfall 2). */
    data object PingTimeout : ScripthashEvent()
}
