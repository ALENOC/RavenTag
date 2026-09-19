package io.raventag.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TofuPinRotationPolicyTest {
    private val trustedMismatch = TofuMismatch("server.example", "old", "new", 1L, true)

    @Test
    fun `recorded trusted change rotates only after explicit confirmation`() {
        assertFalse(TofuPinRotationPolicy.isAuthorized(trustedMismatch, "old", "new", false, false))
        assertTrue(TofuPinRotationPolicy.isAuthorized(trustedMismatch, "old", "new", true, false))
    }

    @Test
    fun `stale or unrecorded change is rejected`() {
        assertFalse(TofuPinRotationPolicy.isAuthorized(null, "old", "new", true, true))
        assertFalse(TofuPinRotationPolicy.isAuthorized(trustedMismatch, "other", "new", true, true))
        assertFalse(TofuPinRotationPolicy.isAuthorized(trustedMismatch, "old", "other", true, true))
    }

    @Test
    fun `untrusted chain requires conscious override`() {
        val untrusted = trustedMismatch.copy(systemTrusted = false)
        assertFalse(TofuPinRotationPolicy.isAuthorized(untrusted, "old", "new", true, false))
        assertTrue(TofuPinRotationPolicy.isAuthorized(untrusted, "old", "new", true, true))
    }
}
