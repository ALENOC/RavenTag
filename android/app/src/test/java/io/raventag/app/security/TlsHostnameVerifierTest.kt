package io.raventag.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsHostnameVerifierTest {
    @Test
    fun `exact DNS name matches case insensitively`() {
        assertTrue(TlsHostnameVerifier.matchesDnsName("Server.Example", "server.example"))
    }

    @Test
    fun `wildcard matches exactly one leftmost label`() {
        assertTrue(TlsHostnameVerifier.matchesDnsName("electrum1.cipig.net", "*.cipig.net"))
        assertFalse(TlsHostnameVerifier.matchesDnsName("deep.electrum1.cipig.net", "*.cipig.net"))
        assertFalse(TlsHostnameVerifier.matchesDnsName("cipig.net", "*.cipig.net"))
    }

    @Test
    fun `partial and multi wildcards are rejected`() {
        assertFalse(TlsHostnameVerifier.matchesDnsName("electrum1.cipig.net", "electrum*.cipig.net"))
        assertFalse(TlsHostnameVerifier.matchesDnsName("a.cipig.net", "*.*.net"))
    }
}
