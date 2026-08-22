package io.raventag.app.wallet

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class RavencoinBalanceResponseTest {

    private fun balance(json: String) =
        assetAwareRvnBalanceSat(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `reads legacy top-level RVN balance used by version 1_0_7 servers`() {
        assertEquals(125_000_000L, balance("""{"confirmed":120000000,"unconfirmed":5000000}"""))
    }

    @Test
    fun `reads nested lowercase RVN balance returned by ElectrumX 1_13`() {
        assertEquals(
            125_000_000L,
            balance("""{"rvn":{"confirmed":120000000,"unconfirmed":5000000},"ASSET":{"confirmed":1}}""")
        )
    }

    @Test
    fun `reads nested uppercase RVN compatibility shape`() {
        assertEquals(42L, balance("""{"RVN":{"confirmed":40,"unconfirmed":2}}"""))
    }

    @Test
    fun `does not count asset balances as RVN`() {
        assertEquals(0L, balance("""{"BRAND/ITEM":{"confirmed":100000000,"unconfirmed":0}}"""))
    }

    @Test
    fun `prefers legacy compatibility fields when both shapes are present`() {
        assertEquals(
            7L,
            balance("""{"confirmed":7,"unconfirmed":0,"rvn":{"confirmed":7,"unconfirmed":0}}""")
        )
    }
}
