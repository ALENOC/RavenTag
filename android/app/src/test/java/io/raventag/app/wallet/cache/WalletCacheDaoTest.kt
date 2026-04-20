package io.raventag.app.wallet.cache

import io.raventag.app.wallet.Utxo
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

// Wave 0 tests. Wave 1-3 implementations will replace the Stub objects below with real classes.
// Until then, tests MUST fail. Do not make them pass by weakening assertions.

class WalletCacheDaoTest {

    @Test
    fun balance_subtracts_reserved_never_negative() {
        val utxos = listOf(Utxo(txid = "a", outputIndex = 0, satoshis = 300_000_000L, script = "", height = 100))
        val reserved = 500_000_000L
        // WalletCacheDao.computeSpendableBalanceSat signature: (utxos, reservedSat) -> Long
        val spendable = WalletCacheDao.computeSpendableBalanceSat(utxos, reserved)
        assertEquals(0L, spendable)
    }

    @Test
    fun balance_subtracts_reserved_positive() {
        val utxos = listOf(
            Utxo(txid = "a", outputIndex = 0, satoshis = 400_000_000L, script = "", height = 100),
            Utxo(txid = "b", outputIndex = 0, satoshis = 300_000_000L, script = "", height = 100),
            Utxo(txid = "c", outputIndex = 0, satoshis = 300_000_000L, script = "", height = 100)
        )
        val reserved = 250_000_000L
        val spendable = WalletCacheDao.computeSpendableBalanceSat(utxos, reserved)
        assertEquals(750_000_000L, spendable)
    }

    @Ignore("requires Android Context - implemented by plan 30-02")
    @Test
    fun roundtrip_preserves_utxos_and_timestamp() {
        // Stub test body calling TODO()
        TODO("30-02: SQLite roundtrip test")
    }
}
