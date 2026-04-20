package io.raventag.app.wallet.cache

import android.content.Context

// Wave 0 stub. Plan 30-02 will implement real DAO.
object ReservedUtxoDao {
    data class ReservedUtxo(val txidIn: String, val vout: Int, val valueSat: Long, val submittedTxid: String, val submittedAt: Long)

    fun init(context: Context): Unit = TODO("30-02")
    fun reserve(entries: List<ReservedUtxo>): Unit = TODO("30-02")
    fun releaseFor(submittedTxid: String): Unit = TODO("30-02")
    fun sumReservedSat(): Long = TODO("30-02")
    fun pruneOlderThan(thresholdMillis: Long): Unit = TODO("30-02")
    fun all(): List<ReservedUtxo> = TODO("30-02")
}
