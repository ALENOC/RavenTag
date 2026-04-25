package io.raventag.app.wallet

import org.junit.Assert.*
import org.junit.Test
import org.junit.Ignore
import io.raventag.app.wallet.BackupRequiredException
import io.raventag.app.wallet.IntegrityException
import io.raventag.app.wallet.KeystoreInvalidatedException

// Wave 0 tests. Wave 1-3 implementations will replace the Stub objects below with real classes.
// Until then, tests MUST fail. Do not make them pass by weakening assertions.

class WalletManagerMnemonicTest {
    private val validPhrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Ignore("requires access to private validateMnemonic; plan 30-06 will expose test helper")
    @Test
    fun validateMnemonic_rejects_padding() {
        // Stub test body calling TODO()
        TODO("30-06: BIP39 validation test")
    }

    @Test
    fun restore_forces_backup_when_wallet_non_zero_and_not_backed_up() {
        try {
            WalletManager.checkRestorePreconditions(currentBalanceSat = 100_000_000L, hasBackedUp = false)
            fail("expected BackupRequiredException")
        } catch (_: BackupRequiredException) {
        }
        WalletManager.checkRestorePreconditions(currentBalanceSat = 100_000_000L, hasBackedUp = true)
        WalletManager.checkRestorePreconditions(currentBalanceSat = 0L, hasBackedUp = false)
    }

    @Test
    fun hmac_integrity_mismatch_throws() {
        val seed = byteArrayOf(1, 2, 3)
        val goodTag = WalletManager.computeSeedHmacForTest(seed, keyBytes = ByteArray(32) { it.toByte() })
        WalletManager.verifySeedHmac(seed, goodTag, keyBytes = ByteArray(32) { it.toByte() })
        try {
            WalletManager.verifySeedHmac(seed, byteArrayOf(9, 9, 9), keyBytes = ByteArray(32) { it.toByte() })
            fail("expected IntegrityException")
        } catch (_: IntegrityException) {
        }
    }

    @Test
    fun key_invalidated_routes_to_restore() {
        try {
            WalletManager.wrapKeystoreException<Unit> {
                throw android.security.keystore.KeyPermanentlyInvalidatedException()
            }
            fail("expected KeystoreInvalidatedException")
        } catch (e: KeystoreInvalidatedException) {
            assertTrue(e.cause is android.security.keystore.KeyPermanentlyInvalidatedException)
        }
        try {
            WalletManager.wrapKeystoreException<Unit> { throw java.io.IOException("transient") }
            fail("expected passthrough IOException")
        } catch (e: java.io.IOException) {
            assertEquals("transient", e.message)
        }
    }
}
