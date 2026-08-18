package io.raventag.app.wallet

/**
 * Local fee invariants that are independent of ElectrumX responses.
 *
 * Public ElectrumX servers are untrusted. They may provide estimates only inside
 * this locally-enforced envelope; they never get authority to choose an arbitrary
 * wallet debit. The absolute ceiling mirrors the historical Ravencoin/Bitcoin Core
 * default max transaction fee (0.1 coin), while the rate ceiling is deliberately
 * generous enough for temporary relay pressure but bounded well below values that
 * could drain a wallet.
 */
object FeeSafetyPolicy {
    /** Existing RavenTag floor retained for compatibility. */
    const val MIN_SAT_PER_BYTE: Long = 200L

    /** 10,000 sat/B = 0.1 RVN/kB; values above this are treated as hostile/anomalous. */
    const val MAX_SAT_PER_BYTE: Long = 10_000L

    /** Never sign a transaction paying more than 0.1 RVN in miner fee. */
    const val MAX_ABSOLUTE_FEE_SAT: Long = 10_000_000L

    /** Normal sends may not spend more than 25% of the requested recipient amount on fees. */
    const val MAX_NORMAL_SEND_FEE_BPS: Long = 2_500L

    fun sanitizeRelayFeeRvnPerKb(rvnPerKb: Double): Long {
        require(rvnPerKb.isFinite() && rvnPerKb > 0.0) { "Invalid network fee" }
        val satPerByteDouble = rvnPerKb * 100_000_000.0 / 1000.0
        require(satPerByteDouble.isFinite() && satPerByteDouble > 0.0) { "Invalid network fee" }
        require(satPerByteDouble <= Long.MAX_VALUE.toDouble()) { "Network fee overflow" }
        val raw = satPerByteDouble.toLong()
        val withMargin = Math.multiplyExact(raw, 2L)
        require(withMargin <= MAX_SAT_PER_BYTE) { "Unreasonable network fee" }
        return maxOf(withMargin, MIN_SAT_PER_BYTE)
    }

    fun calculateFee(estimatedBytes: Long, satPerByte: Long): Long {
        require(estimatedBytes > 0L) { "Invalid transaction size" }
        require(satPerByte in MIN_SAT_PER_BYTE..MAX_SAT_PER_BYTE) { "Unreasonable network fee" }
        val fee = Math.multiplyExact(estimatedBytes, satPerByte)
        requireSafeAbsoluteFee(fee)
        return fee
    }

    fun calculateFee(estimatedBytes: Int, satPerByte: Long): Long =
        calculateFee(estimatedBytes.toLong(), satPerByte)

    fun requireSafeAbsoluteFee(feeSat: Long) {
        require(feeSat > 0L) { "Invalid network fee" }
        require(feeSat <= MAX_ABSOLUTE_FEE_SAT) { "Unreasonable network fee" }
    }

    fun requireSafeNormalSendFee(feeSat: Long, requestedAmountSat: Long) {
        requireSafeAbsoluteFee(feeSat)
        require(requestedAmountSat > 0L) { "Invalid send amount" }
        val maxByRatio = Math.multiplyExact(requestedAmountSat, MAX_NORMAL_SEND_FEE_BPS) / 10_000L
        require(feeSat <= maxByRatio) { "Unreasonable network fee" }
    }
}
