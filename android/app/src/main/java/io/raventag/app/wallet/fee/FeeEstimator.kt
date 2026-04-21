package io.raventag.app.wallet.fee

import io.raventag.app.utils.RetryUtils
import io.raventag.app.wallet.RavencoinPublicNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * D-22 dynamic fee estimator with fallback policy.
 *
 * Calls [RavencoinPublicNode.estimateFeeRvnPerKb] (or the injected lambda in tests),
 * converts the RVN/kB result to sat/kB, and falls back to [FALLBACK_SAT_PER_KB]
 * (0.01 RVN/kB = 1_000_000 sat/kB) when the node returns a non-positive value or
 * throws any exception.
 *
 * The node call is wrapped in [RetryUtils.retryWithBackoff] (3 attempts, 500ms base
 * delay, 2x backoff) so a single transient failure does not immediately collapse to
 * the static fallback.
 *
 * @param node Optional ElectrumX node for the production code path.
 * @param estimateFeeProvider Optional lambda for test injection. When provided, it
 *   takes precedence over the node-based estimation.
 */
class FeeEstimator(
    private val node: RavencoinPublicNode? = null,
    private val estimateFeeProvider: (suspend (Int) -> Double)? = null
) {

    /**
     * Returns a sat/kB fee rate for the requested block target.
     *
     * Falls back to [FALLBACK_SAT_PER_KB] (0.01 RVN/kB) on any failure
     * or when the server indicates insufficient data (return value <= 0).
     *
     * @param targetBlocks Number of blocks for the fee estimation target (default 6).
     * @return Fee rate in satoshis per kilobyte.
     */
    suspend fun estimateSatPerKb(targetBlocks: Int = 6): Long {
        val rvnPerKb: Double = try {
            RetryUtils.retryWithBackoff(maxAttempts = 3, initialDelayMs = 500L, backoffMultiplier = 2.0) {
                invokeProvider(targetBlocks)
            }
        } catch (_: Exception) { -1.0 }
        if (rvnPerKb <= 0.0) return FALLBACK_SAT_PER_KB
        val satPerKb = (rvnPerKb * 100_000_000.0).toLong()
        return if (satPerKb <= 0L) FALLBACK_SAT_PER_KB else satPerKb
    }

    /**
     * Same signature but surfaces whether the fallback was used.
     *
     * UI (SendRvnScreen / TransferScreen) uses this to decide whether
     * to show the amber "estimate unavailable" warning (UI-SPEC).
     *
     * @param targetBlocks Number of blocks for the fee estimation target (default 6).
     * @return [Result] containing the fee rate and a flag indicating fallback usage.
     */
    suspend fun estimateSatPerKbWithSource(targetBlocks: Int = 6): Result {
        val rvnPerKb: Double = try {
            RetryUtils.retryWithBackoff(maxAttempts = 3, initialDelayMs = 500L, backoffMultiplier = 2.0) {
                invokeProvider(targetBlocks)
            }
        } catch (_: Exception) { return Result(FALLBACK_SAT_PER_KB, usedFallback = true) }
        if (rvnPerKb <= 0.0) return Result(FALLBACK_SAT_PER_KB, usedFallback = true)
        val satPerKb = (rvnPerKb * 100_000_000.0).toLong()
        // Sanity cap: reject absurdly high fees (> 1.0 RVN/kB = 100_000_000 sat/kB)
        if (satPerKb > 100_000_000L) return Result(FALLBACK_SAT_PER_KB, usedFallback = true)
        return if (satPerKb <= 0L) Result(FALLBACK_SAT_PER_KB, usedFallback = true)
               else Result(satPerKb, usedFallback = false)
    }

    /**
     * Invokes the appropriate fee provider: the injected lambda if present,
     * otherwise the live ElectrumX node.
     */
    private suspend fun invokeProvider(targetBlocks: Int): Double {
        return if (estimateFeeProvider != null) {
            estimateFeeProvider.invoke(targetBlocks)
        } else {
            val n = node ?: throw IllegalStateException("FeeEstimator requires either a node or a provider lambda")
            withContext(Dispatchers.IO) { n.estimateFeeRvnPerKb(targetBlocks) }
        }
    }

    /**
     * Fee estimation result with metadata about whether the fallback value was used.
     */
    data class Result(val satPerKb: Long, val usedFallback: Boolean)

    companion object {
        /** D-22 fallback: 0.01 RVN/kB = 1_000_000 sat/kB. */
        const val FALLBACK_SAT_PER_KB: Long = 1_000_000L
    }
}
