package io.raventag.app.wallet.fee

import io.raventag.app.wallet.RavencoinPublicNode

/**
 * Wave 0 stub. Plan 30-04 replaces this with real implementation.
 *
 * The constructor accepts an optional fee provider lambda for testability.
 * Wave 1 plan 30-04 MUST honor this constructor signature.
 */
class FeeEstimator(
    private val node: RavencoinPublicNode? = null,
    private val estimateFeeProvider: (suspend (Int) -> Double)? = null
) {
    companion object {
        const val FALLBACK_SAT_PER_KB: Long = 1_000_000L
    }

    /**
     * Returns sat/kB. Falls back to FALLBACK_SAT_PER_KB when estimate <= 0 or throws.
     */
    suspend fun estimateSatPerKb(targetBlocks: Int = 6): Long {
        TODO("30-04")
    }
}
