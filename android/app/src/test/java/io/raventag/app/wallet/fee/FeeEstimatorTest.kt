// Wave 0 tests. Wave 1-3 implementations will replace the Stub objects below with real classes.
// Until then, tests MUST fail. Do not make them pass by weakening assertions.
package io.raventag.app.wallet.fee

import io.raventag.app.wallet.RavencoinPublicNode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class FeeEstimatorTest {

    /**
     * Helper to create a FeeEstimator that uses a lambda for fee estimation
     * instead of making real RPC calls. The lambda receives targetBlocks and
     * returns the RVN/kB rate as a Double.
     *
     * Wave 1 plan 30-04 MUST provide a constructor or factory that accepts
     * this lambda pattern.
     */
    private fun createEstimator(estimateFn: suspend (Int) -> Double): FeeEstimator {
        // The real FeeEstimator(RavencoinPublicNode) constructor exists in Wave 1.
        // For Wave 0, we call the lambda-injectable constructor stub.
        // This stub must exist for the test to compile.
        return FeeEstimator(null, estimateFn)
    }

    @Test
    fun fallback_when_estimate_returns_negative_one() {
        val estimator = createEstimator { -1.0 }
        runBlockingTest {
            assertEquals(1_000_000L, estimator.estimateSatPerKb(6))
        }
    }

    @Test
    fun fallback_when_estimate_returns_zero() {
        val estimator = createEstimator { 0.0 }
        runBlockingTest {
            assertEquals(1_000_000L, estimator.estimateSatPerKb(6))
        }
    }

    @Test
    fun fallback_when_estimate_throws_IOException() {
        val estimator = createEstimator { throw IOException("timeout") }
        runBlockingTest {
            assertEquals(1_000_000L, estimator.estimateSatPerKb(6))
        }
    }

    @Test
    fun converts_rvn_per_kb_to_sat_per_kb() {
        // 0.002 RVN/kB = 200_000 sat/kB
        val estimator = createEstimator { 0.002 }
        runBlockingTest {
            assertEquals(200_000L, estimator.estimateSatPerKb(6))
        }
    }

    @Test
    fun passes_target_blocks_to_lambda() {
        var capturedTarget = 0
        val estimator = createEstimator { target ->
            capturedTarget = target
            0.001
        }
        runBlockingTest {
            estimator.estimateSatPerKb(12)
        }
        assertEquals(12, capturedTarget)
    }
}

/**
 * Minimal runBlocking equivalent for JVM unit tests.
 * kotlinx.coroutines.test is not on the classpath; this avoids adding it.
 */
private fun runBlockingTest(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
