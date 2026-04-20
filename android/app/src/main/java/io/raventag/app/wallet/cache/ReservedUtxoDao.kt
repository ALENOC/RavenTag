package io.raventag.app.wallet.cache

/**
 * Wave 0 stub. Plan 30-02 replaces this with real SQLite DAO implementation.
 */
object ReservedUtxoDao {

    data class ReservedUtxo(
        val txidIn: String,
        val vout: Int,
        val valueSat: Long,
        val submittedTxid: String,
        val submittedAt: Long
    )

    fun init(context: android.content.Context) {
        TODO("30-02")
    }

    fun reserve(entries: List<ReservedUtxo>) {
        TODO("30-02")
    }

    fun releaseFor(submittedTxid: String) {
        TODO("30-02")
    }

    fun sumReservedSat(): Long {
        TODO("30-02")
    }

    fun pruneOlderThan(thresholdMillis: Long) {
        TODO("30-02")
    }

    fun all(): List<ReservedUtxo> {
        TODO("30-02")
    }
}
