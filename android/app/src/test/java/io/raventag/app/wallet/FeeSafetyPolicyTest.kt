package io.raventag.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FeeSafetyPolicyTest {
    @Test
    fun malicious21RvnPerKbIsRejected() {
        try {
            FeeSafetyPolicy.sanitizeRelayFeeRvnPerKb(21.0)
            fail("expected unreasonable relay fee rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun nonFiniteAndNegativeRelayFeesAreRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0).forEach { value ->
            try {
                FeeSafetyPolicy.sanitizeRelayFeeRvnPerKb(value)
                fail("expected rejection for $value")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun ordinaryBoundedRelayFeeIsAccepted() {
        // 0.01 RVN/kB -> 1,000 sat/B -> existing 2x RavenTag margin -> 2,000 sat/B.
        assertEquals(2_000L, FeeSafetyPolicy.sanitizeRelayFeeRvnPerKb(0.01))
    }

    @Test
    fun absoluteFeeCeilingRejectsAuditDrainScenario() {
        // Audit scenario: 226 bytes * 4,200,000 sat/B = 9.492 RVN.
        try {
            FeeSafetyPolicy.calculateFee(226, 4_200_000L)
            fail("expected malicious fee rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun normalSendFeeRatioIsBounded() {
        FeeSafetyPolicy.requireSafeNormalSendFee(10_000_000L, 100_000_000L) // 0.1 fee on 1 RVN
        try {
            FeeSafetyPolicy.requireSafeNormalSendFee(10_000_000L, 20_000_000L) // 0.1 on 0.2 = 50%
            fail("expected fee ratio rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun multiplicationOverflowFailsClosed() {
        try {
            FeeSafetyPolicy.calculateFee(Long.MAX_VALUE, FeeSafetyPolicy.MIN_SAT_PER_BYTE)
            fail("expected overflow")
        } catch (_: ArithmeticException) { }
    }

    @Test
    fun fragmentedWalletMaintenanceCanExceedNormalSendCap() {
        val fee = FeeSafetyPolicy.calculateMaintenanceFee(60_000L, 500L)
        assertEquals(30_000_000L, fee)
    }

    @Test
    fun walletMaintenanceStillRejectsHostileRateAndOverflow() {
        try {
            FeeSafetyPolicy.calculateMaintenanceFee(1_000L, FeeSafetyPolicy.MAX_SAT_PER_BYTE + 1L)
            fail("expected hostile maintenance rate rejection")
        } catch (_: IllegalArgumentException) { }

        try {
            FeeSafetyPolicy.calculateMaintenanceFee(Long.MAX_VALUE, FeeSafetyPolicy.MIN_SAT_PER_BYTE)
            fail("expected maintenance fee overflow")
        } catch (_: ArithmeticException) { }
    }
}
