package io.raventag.app.wallet.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Wave 0 tests. Wave 1-3 implementations will replace the Stub objects below with real classes.
// Until then, tests MUST fail. Do not make them pass by weakening assertions.

class ReservedUtxoDaoTest {

    @Test
    fun insert_on_broadcast_records_all_inputs() {
        val now = System.currentTimeMillis()
        val entries = listOf(
            ReservedUtxoDao.ReservedUtxo("txA", 0, 100L, "subX", now),
            ReservedUtxoDao.ReservedUtxo("txA", 1, 200L, "subX", now)
        )
        ReservedUtxoDao.reserve(entries)
        val all = ReservedUtxoDao.all()
        assertEquals(2, all.size)
        assertTrue(all.all { it.submittedTxid == "subX" })
    }

    @Test
    fun cleanup_on_confirm_removes_rows_for_submitted_txid() {
        val now = System.currentTimeMillis()
        ReservedUtxoDao.reserve(listOf(
            ReservedUtxoDao.ReservedUtxo("txY1", 0, 100L, "subY", now),
            ReservedUtxoDao.ReservedUtxo("txY2", 0, 200L, "subY", now),
            ReservedUtxoDao.ReservedUtxo("txY3", 0, 300L, "subY", now),
            ReservedUtxoDao.ReservedUtxo("txZ1", 0, 400L, "subZ", now)
        ))
        ReservedUtxoDao.releaseFor("subY")
        val remaining = ReservedUtxoDao.all()
        assertEquals(1, remaining.size)
        assertEquals("subZ", remaining[0].submittedTxid)
    }

    @Test
    fun prune_stale_removes_rows_older_than_48h() {
        val now = System.currentTimeMillis()
        val fortyEightHours = 48L * 3600 * 1000
        ReservedUtxoDao.reserve(listOf(
            ReservedUtxoDao.ReservedUtxo("old", 0, 100L, "subOld", now - fortyEightHours - 3600 * 1000),
            ReservedUtxoDao.ReservedUtxo("new", 0, 200L, "subNew", now - 3600 * 1000)
        ))
        ReservedUtxoDao.pruneOlderThan(now - fortyEightHours)
        val remaining = ReservedUtxoDao.all()
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].submittedAt > now - 2 * 3600 * 1000)
    }

    @Test
    fun sum_reserved_returns_total_value() {
        val now = System.currentTimeMillis()
        ReservedUtxoDao.reserve(listOf(
            ReservedUtxoDao.ReservedUtxo("a", 0, 100L, "sub", now),
            ReservedUtxoDao.ReservedUtxo("b", 0, 250L, "sub", now),
            ReservedUtxoDao.ReservedUtxo("c", 0, 999L, "sub", now)
        ))
        val sum = ReservedUtxoDao.sumReservedSat()
        assertEquals(1349L, sum)
    }
}
