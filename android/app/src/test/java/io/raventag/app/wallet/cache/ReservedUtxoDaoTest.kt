// Wave 0 tests. Wave 1-3 implementations will replace the Stub objects below with real classes.
// Until then, tests MUST fail. Do not make them pass by weakening assertions.
package io.raventag.app.wallet.cache

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

class ReservedUtxoDaoTest {

    @Ignore("requires Android Context - implemented by plan 30-02")
    @Test
    fun insert_on_broadcast_records_all_inputs() {
        val now = System.currentTimeMillis()
        ReservedUtxoDao.reserve(listOf(
            ReservedUtxoDao.ReservedUtxo(txidIn = "txA", vout = 0, valueSat = 100L, submittedTxid = "subX", submittedAt = now),
            ReservedUtxoDao.ReservedUtxo(txidIn = "txA", vout = 1, valueSat = 200L, submittedTxid = "subX", submittedAt = now)
        ))
        val rows = ReservedUtxoDao.all()
        assertEquals(2, rows.size)
        assertEquals("subX", rows[0].submittedTxid)
        assertEquals("subX", rows[1].submittedTxid)
    }

    @Ignore("requires Android Context - implemented by plan 30-02")
    @Test
    fun cleanup_on_confirm_removes_rows_for_submitted_txid() {
        val now = System.currentTimeMillis()
        ReservedUtxoDao.reserve(listOf(
            ReservedUtxoDao.ReservedUtxo(txidIn = "tx1", vout = 0, valueSat = 100L, submittedTxid = "subY", submittedAt = now),
            ReservedUtxoDao.ReservedUtxo(txidIn = "tx2", vout = 0, valueSat = 200L, submittedTxid = "subY", submittedAt = now),
            ReservedUtxoDao.ReservedUtxo(txidIn = "tx3", vout = 0, valueSat = 300L, submittedTxid = "subY", submittedAt = now),
            ReservedUtxoDao.ReservedUtxo(txidIn = "tx4", vout = 0, valueSat = 400L, submittedTxid = "subZ", submittedAt = now)
        ))
        ReservedUtxoDao.releaseFor("subY")
        val remaining = ReservedUtxoDao.all()
        assertEquals(1, remaining.size)
        assertEquals("subZ", remaining[0].submittedTxid)
    }

    @Ignore("requires Android Context - implemented by plan 30-02")
    @Test
    fun prune_stale_removes_rows_older_than_48h() {
        val now = System.currentTimeMillis()
        ReservedUtxoDao.reserve(listOf(
            ReservedUtxoDao.ReservedUtxo(txidIn = "oldTx", vout = 0, valueSat = 500L, submittedTxid = "oldSub", submittedAt = now - 49L * 3600_000),
            ReservedUtxoDao.ReservedUtxo(txidIn = "newTx", vout = 0, valueSat = 600L, submittedTxid = "newSub", submittedAt = now - 1L * 3600_000)
        ))
        ReservedUtxoDao.pruneOlderThan(now - 48L * 3600_000)
        val remaining = ReservedUtxoDao.all()
        assertEquals(1, remaining.size)
    }

    @Ignore("requires Android Context - implemented by plan 30-02")
    @Test
    fun sum_reserved_returns_total_value() {
        val now = System.currentTimeMillis()
        ReservedUtxoDao.reserve(listOf(
            ReservedUtxoDao.ReservedUtxo(txidIn = "tx1", vout = 0, valueSat = 100L, submittedTxid = "subA", submittedAt = now),
            ReservedUtxoDao.ReservedUtxo(txidIn = "tx2", vout = 0, valueSat = 250L, submittedTxid = "subA", submittedAt = now),
            ReservedUtxoDao.ReservedUtxo(txidIn = "tx3", vout = 0, valueSat = 999L, submittedTxid = "subA", submittedAt = now)
        ))
        assertEquals(1349L, ReservedUtxoDao.sumReservedSat())
    }
}
