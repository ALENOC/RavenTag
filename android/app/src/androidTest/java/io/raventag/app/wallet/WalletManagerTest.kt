package io.raventag.app.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for WalletManager.
 * Requires an Android device or emulator (uses real Android Keystore).
 *
 * Tests the fixes for:
 * - restoreWallet validation: BIP39 word check + checksum verification
 * - Deterministic address derivation: same mnemonic always gives same address
 */
@RunWith(AndroidJUnit4::class)
class WalletManagerTest {

    private lateinit var walletManager: WalletManager

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        walletManager = WalletManager(context)
        walletManager.deleteWallet()
    }

    @After
    fun tearDown() {
        walletManager.deleteWallet()
    }

    // ── BIP39 word validation ─────────────────────────────────────────────────

    @Test
    fun restoreWallet_validCanonicalBip39Mnemonic_returnsTrue() {
        // "abandon" x11 + "about" is the canonical BIP39 test vector for 128-bit zero entropy.
        // This must always restore successfully (word list check + checksum passes).
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertTrue(walletManager.restoreWallet(mnemonic))
    }

    @Test
    fun restoreWallet_wordNotInWordlist_returnsFalse() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon NOTAWORD"
        assertFalse(walletManager.restoreWallet(mnemonic))
    }

    @Test
    fun restoreWallet_wordInWrongCase_returnsFalse() {
        // BIP39 words are all lowercase; uppercase must fail
        val mnemonic = "Abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertFalse(walletManager.restoreWallet(mnemonic))
    }

    @Test
    fun restoreWallet_wrongWordCount_returnsFalse() {
        // 11 words (one short)
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
        assertFalse(walletManager.restoreWallet(mnemonic))
    }

    @Test
    fun restoreWallet_extraWord_returnsFalse() {
        // 13 words
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about zoo"
        assertFalse(walletManager.restoreWallet(mnemonic))
    }

    @Test
    fun restoreWallet_invalidBip39Checksum_returnsFalse() {
        // "abandon" x12: entropy is 0x00*16, expected checksum bits are first 4 bits of
        // SHA-256(0x00*16). "abandon" has index 0 (all-zero bits), so the last word
        // contributes all-zero checksum bits. SHA-256(0x00*16) starts with 0x37 (0011 0111),
        // so the expected first 4 bits are 0011. "abandon" contributes 0000. Mismatch.
        val mnemonic = List(12) { "abandon" }.joinToString(" ")
        assertFalse("abandon x12 has invalid BIP39 checksum", walletManager.restoreWallet(mnemonic))
    }

    // ── Deterministic derivation ──────────────────────────────────────────────

    @Test
    fun restoreWallet_sameMemonic_producesSameAddress() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        assertTrue(walletManager.restoreWallet(mnemonic))
        val address1 = walletManager.getAddress()
        assertNotNull(address1)

        walletManager.deleteWallet()

        assertTrue(walletManager.restoreWallet(mnemonic))
        val address2 = walletManager.getAddress()
        assertNotNull(address2)

        assertEquals("Same mnemonic must always derive the same address", address1, address2)
    }

    @Test
    fun generateWallet_thenRestoreFromMnemonic_givesSameAddress() {
        val mnemonic = walletManager.generateWallet()
        val addressGenerated = walletManager.getAddress()
        assertNotNull(addressGenerated)

        walletManager.deleteWallet()

        assertTrue(walletManager.restoreWallet(mnemonic))
        val addressRestored = walletManager.getAddress()

        assertEquals("Restored wallet must have same address as generated wallet", addressGenerated, addressRestored)
    }

    // ── Address format ────────────────────────────────────────────────────────

    @Test
    fun getAddress_afterRestore_hasRavenAddressFormat() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        walletManager.restoreWallet(mnemonic)
        val address = walletManager.getAddress()

        assertNotNull(address)
        // Ravencoin mainnet P2PKH addresses start with 'R'
        assertTrue("RVN address must start with R", address!!.startsWith("R"))
        // Base58Check addresses are 25 bytes -> 33-34 Base58 chars typically
        assertTrue("RVN address length must be in [25..35]", address.length in 25..35)
    }

    // ── Mnemonic storage ─────────────────────────────────────────────────────

    @Test
    fun getMnemonic_afterRestore_returnsOriginalMnemonic() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        walletManager.restoreWallet(mnemonic)
        assertEquals(mnemonic, walletManager.getMnemonic())
    }

    @Test
    fun hasWallet_afterRestore_returnsTrue() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertFalse(walletManager.hasWallet())
        walletManager.restoreWallet(mnemonic)
        assertTrue(walletManager.hasWallet())
    }

    @Test
    fun deleteWallet_clearsAll() {
        walletManager.restoreWallet(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        )
        assertTrue(walletManager.hasWallet())
        walletManager.deleteWallet()
        assertFalse(walletManager.hasWallet())
    }
}
