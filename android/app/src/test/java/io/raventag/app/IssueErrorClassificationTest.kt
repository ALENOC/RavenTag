package io.raventag.app

import org.junit.Assert.assertEquals
import org.junit.Test

data class TestStrings(
    val issueErrorInsufficientFunds: String = "ERR_INSUFFICIENT_FUNDS",
    val issueErrorDuplicateName: String = "ERR_DUPLICATE_NAME",
    val issueErrorNodeUnreachable: String = "ERR_NODE_UNREACHABLE",
    val issueErrorTimeout: String = "ERR_TIMEOUT",
    val issueErrorFeeEstimation: String = "ERR_FEE_ESTIMATION",
    val issueErrorIpfsAuth: String = "ERR_IPFS_AUTH",
    val issueErrorIpfsFailed: String = "ERR_IPFS_FAILED",
    val issueErrorInvalidAddress: String = "ERR_INVALID_ADDRESS",
    val issueErrorNoWallet: String = "ERR_NO_WALLET",
    val issueFailed: String = "Issuance failed"
)

fun classifyIssuanceError(e: Throwable, s: TestStrings): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("insufficient funds") || msg.contains("fondi insufficienti")
            || msg.contains("no spendable") || msg.contains("nessun rvn spendibile")
            -> s.issueErrorInsufficientFunds
        msg.contains("duplicate") || msg.contains("already exists") || msg.contains("gia esiste")
            -> s.issueErrorDuplicateName
        msg.contains("connection refused") || msg.contains("unreachable") || msg.contains("irraggiungibile")
            || msg.contains("unknownhost")
            -> s.issueErrorNodeUnreachable
        msg.contains("timeout")
            -> s.issueErrorTimeout
        msg.contains("fee") && (msg.contains("estimate") || msg.contains("commissione"))
            -> s.issueErrorFeeEstimation
        msg.contains("pinata") && (msg.contains("jwt") || msg.contains("auth") || msg.contains("scaduto"))
            -> s.issueErrorIpfsAuth
        msg.contains("ipfs") || msg.contains("caricamento ipfs fallito")
            -> s.issueErrorIpfsFailed
        msg.contains("invalid address") || msg.contains("indirizzo non valido")
            -> s.issueErrorInvalidAddress
        msg.contains("wallet non disponibile") || msg.contains("no wallet")
            -> s.issueErrorNoWallet
        else -> "${s.issueFailed}: ${e.message ?: ""}"
    }
}

class IssueErrorClassificationTest {

    private val s = TestStrings()

    // ── Insufficient funds ────────────────────────────────────────────────────

    @Test
    fun `insufficientFunds - english trigger`() {
        val result = classifyIssuanceError(RuntimeException("insufficient funds"), s)
        assertEquals(s.issueErrorInsufficientFunds, result)
    }

    @Test
    fun `insufficientFunds - no spendable`() {
        val result = classifyIssuanceError(RuntimeException("no spendable RVN"), s)
        assertEquals(s.issueErrorInsufficientFunds, result)
    }

    @Test
    fun `insufficientFunds - italian`() {
        val result = classifyIssuanceError(RuntimeException("fondi insufficienti"), s)
        assertEquals(s.issueErrorInsufficientFunds, result)
    }

    @Test
    fun `insufficientFunds - italian no spendable`() {
        val result = classifyIssuanceError(RuntimeException("nessun rvn spendibile"), s)
        assertEquals(s.issueErrorInsufficientFunds, result)
    }

    // ── Duplicate name ────────────────────────────────────────────────────────

    @Test
    fun `duplicateName - english`() {
        val result = classifyIssuanceError(RuntimeException("duplicate asset name"), s)
        assertEquals(s.issueErrorDuplicateName, result)
    }

    @Test
    fun `duplicateName - already exists`() {
        val result = classifyIssuanceError(RuntimeException("already exists"), s)
        assertEquals(s.issueErrorDuplicateName, result)
    }

    @Test
    fun `duplicateName - italian`() {
        val result = classifyIssuanceError(RuntimeException("gia esiste"), s)
        assertEquals(s.issueErrorDuplicateName, result)
    }

    // ── Node unreachable ──────────────────────────────────────────────────────

    @Test
    fun `nodeUnreachable - connection refused`() {
        val result = classifyIssuanceError(RuntimeException("connection refused"), s)
        assertEquals(s.issueErrorNodeUnreachable, result)
    }

    @Test
    fun `nodeUnreachable - unreachable`() {
        val result = classifyIssuanceError(RuntimeException("node unreachable"), s)
        assertEquals(s.issueErrorNodeUnreachable, result)
    }

    @Test
    fun `nodeUnreachable - unknownHost`() {
        val result = classifyIssuanceError(RuntimeException("unknownhost exception"), s)
        assertEquals(s.issueErrorNodeUnreachable, result)
    }

    @Test
    fun `nodeUnreachable - italian`() {
        val result = classifyIssuanceError(RuntimeException("nodo irraggiungibile"), s)
        assertEquals(s.issueErrorNodeUnreachable, result)
    }

    // ── Timeout ───────────────────────────────────────────────────────────────

    @Test
    fun `timeout - socket timeout`() {
        val result = classifyIssuanceError(RuntimeException("socket timeout"), s)
        assertEquals(s.issueErrorTimeout, result)
    }

    // ── Fee estimation ────────────────────────────────────────────────────────

    @Test
    fun `feeEstimation - english`() {
        val result = classifyIssuanceError(RuntimeException("fee estimate failed"), s)
        assertEquals(s.issueErrorFeeEstimation, result)
    }

    @Test
    fun `feeEstimation - italian`() {
        val result = classifyIssuanceError(RuntimeException("fee estimate commissione fallita"), s)
        assertEquals(s.issueErrorFeeEstimation, result)
    }

    // ── IPFS auth ─────────────────────────────────────────────────────────────

    @Test
    fun `ipfsAuth - jwt expired`() {
        val result = classifyIssuanceError(RuntimeException("pinata jwt expired"), s)
        assertEquals(s.issueErrorIpfsAuth, result)
    }

    @Test
    fun `ipfsAuth - auth scaduto`() {
        val result = classifyIssuanceError(RuntimeException("pinata auth scaduto"), s)
        assertEquals(s.issueErrorIpfsAuth, result)
    }

    // ── IPFS failed ───────────────────────────────────────────────────────────

    @Test
    fun `ipfsFailed - generic`() {
        val result = classifyIssuanceError(RuntimeException("ipfs upload error"), s)
        assertEquals(s.issueErrorIpfsFailed, result)
    }

    @Test
    fun `ipfsFailed - italian`() {
        val result = classifyIssuanceError(RuntimeException("caricamento ipfs fallito"), s)
        assertEquals(s.issueErrorIpfsFailed, result)
    }

    // ── Invalid address ───────────────────────────────────────────────────────

    @Test
    fun `invalidAddress - english`() {
        val result = classifyIssuanceError(RuntimeException("invalid address format"), s)
        assertEquals(s.issueErrorInvalidAddress, result)
    }

    @Test
    fun `invalidAddress - italian`() {
        val result = classifyIssuanceError(RuntimeException("indirizzo non valido"), s)
        assertEquals(s.issueErrorInvalidAddress, result)
    }

    // ── No wallet ─────────────────────────────────────────────────────────────

    @Test
    fun `noWallet - italian`() {
        val result = classifyIssuanceError(RuntimeException("wallet non disponibile"), s)
        assertEquals(s.issueErrorNoWallet, result)
    }

    @Test
    fun `noWallet - english`() {
        val result = classifyIssuanceError(RuntimeException("no wallet found"), s)
        assertEquals(s.issueErrorNoWallet, result)
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    @Test
    fun `fallback - unknown error`() {
        val result = classifyIssuanceError(RuntimeException("something completely unexpected"), s)
        assertEquals("${s.issueFailed}: something completely unexpected", result)
    }

    @Test
    fun `fallback - null message`() {
        val result = classifyIssuanceError(RuntimeException(), s)
        assertEquals("${s.issueFailed}: ", result)
    }
}
